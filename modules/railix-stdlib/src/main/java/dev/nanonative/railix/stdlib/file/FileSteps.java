package dev.nanonative.railix.stdlib.file;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** Trusted local file Steps with explicit outcomes and bounded data. */
public final class FileSteps {
    private static final int MAX_PATH_BYTES = 4_096;
    private static final ReentrantLock MUTATIONS = new ReentrantLock();

    private FileSteps() {
    }

    /** Returns the bounded normalized file reader. */
    public static StepDefinition read() {
        return StepDefinition.named("file.read", "1.1.0")
                .config(
                        "format",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.DATA_FORMAT,
                        RailixValue.string("json")
                )
                .input("path", ValueShape.string())
                .output("value", ValueShape.ANY)
                .outcome("read")
                .outcome("missing")
                .outcome("rejected")
                .run(FileSteps::read);
    }

    /** Returns the canonical JSON writer with explicit conflict handling. */
    public static StepDefinition write() {
        return StepDefinition.named("file.write", "1.0.0")
                .config("overwrite", ValueShape.BOOLEAN, RailixValue.bool(false))
                .input("path", ValueShape.string())
                .input("value", ValueShape.ANY)
                .outcome("written")
                .outcome("conflict")
                .outcome("rejected")
                .run(FileSteps::write);
    }

    /** Returns the regular-file deleter. */
    public static StepDefinition delete() {
        return StepDefinition.named("file.delete", "1.0.0")
                .input("path", ValueShape.string())
                .outcome("deleted")
                .outcome("missing")
                .outcome("rejected")
                .run(FileSteps::delete);
    }

    private static StepResult read(final StepInput input) {
        final Optional<Path> target = path(input.string("path"));
        if (target.isEmpty()) {
            return readResult("rejected", RailixValue.nullValue());
        }

        final byte[] source;
        try {
            if (!attributes(target.get()).isRegularFile()) {
                return readResult("rejected", RailixValue.nullValue());
            }
            try (InputStream file = Files.newInputStream(
                    target.get(),
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                source = file.readNBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);
            }
        } catch (final NoSuchFileException exception) {
            return readResult("missing", RailixValue.nullValue());
        } catch (final IOException exception) {
            return readResult("rejected", RailixValue.nullValue());
        }
        if (source.length > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
            return readResult("rejected", RailixValue.nullValue());
        }

        return format(input.configString("format"))
                .map(selected -> switch (RailixData.normalize(selected, source)) {
                    case RailixData.Normalized normalized ->
                            readResult("read", normalized.value());
                    case RailixData.Invalid ignored ->
                            readResult("rejected", RailixValue.nullValue());
                })
                .orElseGet(() -> readResult("rejected", RailixValue.nullValue()));
    }

    private static StepResult write(final StepInput input) throws InterruptedException {
        final Optional<Path> target = path(input.string("path"));
        if (target.isEmpty()) {
            return outcome("rejected");
        }
        final Optional<String> json;
        try {
            json = RailixJson.write(input.value("value"), RailixData.DEFAULT_MAX_SOURCE_BYTES);
        } catch (final IllegalArgumentException exception) {
            return outcome("rejected");
        }
        if (json.isEmpty()) {
            return outcome("rejected");
        }

        checkInterrupted();
        MUTATIONS.lockInterruptibly();
        try {
            return writeLocked(
                    target.get(),
                    json.get().getBytes(StandardCharsets.UTF_8),
                    ((RailixValue.BooleanValue) input.configValue("overwrite")).value()
            );
        } finally {
            MUTATIONS.unlock();
        }
    }

    private static StepResult writeLocked(
            final Path target,
            final byte[] source,
            final boolean overwrite
    ) throws InterruptedException {
        try {
            final BasicFileAttributes attributes = attributes(target);
            if (!attributes.isRegularFile()) {
                return outcome("rejected");
            }
            if (!overwrite) {
                return outcome("conflict");
            }
        } catch (final NoSuchFileException ignored) {
            // A missing regular target may be created.
        } catch (final IOException exception) {
            return outcome("rejected");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".railix-", ".tmp");
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer bytes = ByteBuffer.wrap(source);
                while (bytes.hasRemaining()) {
                    checkInterrupted();
                    file.write(bytes);
                }
                checkInterrupted();
                file.force(true);
            }
            checkInterrupted();
            if (overwrite) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
            temporary = null;
            return outcome("written");
        } catch (final FileAlreadyExistsException exception) {
            return cleanup(temporary, overwrite ? "rejected" : "conflict");
        } catch (final AtomicMoveNotSupportedException exception) {
            return cleanup(temporary, "rejected");
        } catch (final IOException exception) {
            return cleanup(temporary, "rejected");
        } catch (final InterruptedException exception) {
            cleanup(temporary, "rejected");
            throw exception;
        }
    }

    private static StepResult delete(final StepInput input) throws InterruptedException {
        final Optional<Path> target = path(input.string("path"));
        if (target.isEmpty()) {
            return outcome("rejected");
        }

        MUTATIONS.lockInterruptibly();
        try {
            if (!attributes(target.get()).isRegularFile()) {
                return outcome("rejected");
            }
            checkInterrupted();
            Files.delete(target.get());
            return outcome("deleted");
        } catch (final NoSuchFileException exception) {
            return outcome("missing");
        } catch (final IOException exception) {
            return outcome("rejected");
        } finally {
            MUTATIONS.unlock();
        }
    }

    private static Optional<Path> path(final String source) {
        if (source.isEmpty()
                || source.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            return Optional.empty();
        }
        try {
            final Path target = Path.of(source).toAbsolutePath().normalize();
            return target.getFileName() == null ? Optional.empty() : Optional.of(target);
        } catch (final InvalidPathException exception) {
            return Optional.empty();
        }
    }

    private static BasicFileAttributes attributes(final Path path) throws IOException {
        return Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private static Optional<RailixData.Format> format(final String source) {
        return switch (source) {
            case "json" -> Optional.of(RailixData.Format.JSON);
            case "yaml" -> Optional.of(RailixData.Format.YAML);
            case "xml" -> Optional.of(RailixData.Format.XML);
            default -> Optional.empty();
        };
    }

    private static StepResult readResult(final String outcome, final RailixValue value) {
        return StepResult.outcome(outcome).output("value", value);
    }

    private static StepResult cleanup(final Path temporary, final String result) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException exception) {
                return outcome("rejected");
            }
        }
        return outcome(result);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private static StepResult outcome(final String outcome) {
        return StepResult.outcome(outcome);
    }
}
