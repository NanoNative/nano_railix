package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Starts Railix Creator. Generated project applications are independent executable JARs. */
public final class RailixMain {
    private static final int DEFAULT_PORT = 0;
    private static final String CREATOR_USAGE = "Usage: railix creator [project-file] [port]";
    private static final String USAGE = CREATOR_USAGE + "\n       railix run [arguments...]";

    private RailixMain() {
    }

    public static void main(final String[] arguments) {
        final int status = run(arguments);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(final String[] arguments) {
        if (arguments.length == 0) {
            return reject(USAGE);
        }
        return switch (arguments[0]) {
            case "creator" -> creator(arguments);
            case "run" -> runApplication(arguments);
            default -> reject("Unknown Railix command: " + arguments[0] + ".");
        };
    }

    private static int runApplication(final String[] arguments) {
        final Path project = Path.of("railix.project.json");
        try {
            final String source = readApplicationProject(project);
            final Path absoluteProject = project.toAbsolutePath().normalize();
            final Path dependencyLock = absoluteProject.resolveSibling("railix.dependencies.lock.json");
            final StepCatalog catalog = Files.exists(dependencyLock)
                    ? StandardLibrary.catalog().install(
                            dependencyLock,
                            Path.of(System.getProperty("user.home"), ".railix", "artifacts")
                    )
                    : StandardLibrary.catalog();
            final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
            if (result instanceof CompileResult.Rejected rejected) {
                final Diagnostic diagnostic = rejected.diagnostics().getFirst();
                return reject(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final Path jar = ApplicationBuilder.buildProduction(
                    absoluteProject,
                    (CompileResult.Compiled) result
            ).jar();
            final List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(List.of(arguments).subList(1, arguments.length));
            return waitFor(new ProcessBuilder(command).inheritIO().start());
        } catch (final IOException exception) {
            return reject(exception.getMessage());
        }
    }

    private static String readApplicationProject(final Path project) throws IOException {
        if (!Files.isRegularFile(project)) {
            throw new IOException("Cannot read project: " + project);
        }
        if (Files.size(project) > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
            throw new IOException("Project exceeds the 1048576-byte limit.");
        }
        final byte[] source = Files.readAllBytes(project);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (final CharacterCodingException exception) {
            throw new IOException("Project is not valid UTF-8.", exception);
        }
    }

    private static int waitFor(final Process process) throws IOException {
        final Thread shutdown = Thread.ofPlatform()
                .name("railix-run-shutdown")
                .unstarted(process::destroyForcibly);
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return process.waitFor();
        } catch (final InterruptedException exception) {
            process.destroyForcibly();
            try {
                process.waitFor();
            } catch (final InterruptedException cleanup) {
                exception.addSuppressed(cleanup);
            }
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (final IllegalStateException ignored) {
                // JVM shutdown owns the child from this point.
            }
        }
    }

    private static int creator(final String[] arguments) {
        if (arguments.length > 3) {
            return reject(CREATOR_USAGE);
        }
        try {
            final Path project = arguments.length > 1
                    ? Path.of(arguments[1])
                    : Path.of("railix.project.json");
            final int port = arguments.length > 2 ? Integer.parseInt(arguments[2]) : DEFAULT_PORT;
            try (CreatorServer creator = CreatorServer.start(port, project)) {
                final Thread shutdown = Thread.ofPlatform()
                        .name("railix-creator-shutdown")
                        .unstarted(creator::close);
                Runtime.getRuntime().addShutdownHook(shutdown);
                System.out.println("Railix Creator " + creator.baseUri());
                System.out.println("Project " + project.toAbsolutePath().normalize());
                creator.awaitClose();
                return 0;
            }
        } catch (final NumberFormatException exception) {
            return reject("Creator port must be a number.");
        } catch (final IllegalArgumentException exception) {
            return reject(exception.getMessage());
        } catch (final IOException exception) {
            return reject(exception.getMessage());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    private static int reject(final String message) {
        return reject(message, 2);
    }

    private static int reject(final String message, final int status) {
        System.err.println(message);
        return status;
    }
}
