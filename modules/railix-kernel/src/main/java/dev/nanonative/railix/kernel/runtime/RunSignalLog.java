package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class RunSignalLog {

    private final Path file;

    public RunSignalLog(final Path file) {
        this.file = Objects.requireNonNull(file, "file");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);
            Files.createFile(file);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public RunSignalLog append(final RunSignal signal) {
        final String line = KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(Objects.requireNonNull(signal, "signal"))) + "\n";
        try {
            Files.writeString(file, line, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return this;
    }

    public Path file() {
        return file;
    }

    public int signalCount() {
        try (var lines = Files.lines(file)) {
            return Math.toIntExact(lines.filter(line -> !line.isBlank()).count());
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Deprecated(forRemoval = false)
    public List<RunSignal> signals() {
        return readSignals(0, signalCount());
    }

    public List<RunSignal> readSignals(final int limit) {
        return readSignals(0, limit);
    }

    public List<RunSignal> readSignals(final int offset, final int limit) {
        requireNonNegative(offset, "offset");
        requireNonNegative(limit, "limit");
        if (limit == 0) {
            return List.of();
        }
        final List<RunSignal> decodedSignals = new ArrayList<>(Math.min(limit, 64));
        var skipped = 0;
        try (var lines = Files.lines(file)) {
            final var iterator = lines.iterator();
            while (iterator.hasNext() && decodedSignals.size() < limit) {
                final String line = iterator.next();
                if (line.isBlank()) {
                    continue;
                }
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                decodedSignals.add(decodeSignal(line));
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return List.copyOf(decodedSignals);
    }

    public List<RunSignal> tailSignals(final int limit) {
        requireNonNegative(limit, "limit");
        if (limit == 0) {
            return List.of();
        }
        final ArrayDeque<RunSignal> tail = new ArrayDeque<>(limit);
        forEachSignal(signal -> {
            if (tail.size() == limit) {
                tail.removeFirst();
            }
            tail.addLast(signal);
        });
        return List.copyOf(tail);
    }

    public void forEachSignal(final Consumer<RunSignal> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        try (var lines = Files.lines(file)) {
            final var iterator = lines.iterator();
            while (iterator.hasNext()) {
                final String line = iterator.next();
                if (!line.isBlank()) {
                    consumer.accept(decodeSignal(line));
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RunSignal decodeSignal(final String line) {
        try {
            return KernelContractCodec.runSignalFromUiModel(KernelContractCodec.parseStableJsonObject(line));
        } catch (final IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("root must be a map")) {
                throw new IllegalArgumentException("Signal line must decode to an object", exception);
            }
            throw exception;
        }
    }

    private static void requireNonNegative(final int value, final String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
    }

}
