package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Starts Railix Creator or its Creator-owned development application. */
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
            case "run" -> runProject(List.of(arguments).subList(1, arguments.length));
            case "application" -> application(arguments);
            default -> reject("Unknown Railix command: " + arguments[0] + ".");
        };
    }

    private static int runProject(final List<String> arguments) {
        final Path project = Path.of("railix.project.json");
        final String source;
        try {
            if (Files.size(project) > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                return reject("Project exceeds the 1048576-byte limit.");
            }
            source = Files.readString(project, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            return reject("Cannot read project: " + exception.getMessage());
        }
        final CompileResult compiled = ProjectCompiler.compile(source, StandardLibrary.catalog());
        if (compiled instanceof CompileResult.Rejected rejected) {
            return reject(rejected.diagnostics());
        }
        final CompiledProject.SourceResult sourceResult = ((CompileResult.Compiled) compiled).project().runSource(
                "application.arguments",
                Map.of("arguments", RailixValue.array(arguments.stream()
                        .<RailixValue>map(RailixValue::string)
                        .toList()))
        );
        return switch (sourceResult.result()) {
            case RunResult.Succeeded ignored -> succeeded(sourceResult.responses());
            case RunResult.Rejected rejected -> reject(rejected.diagnostics());
            case RunResult.Failed failed -> reject(
                    failed.failure().code() + " " + failed.failure().stepId() + " "
                            + failed.failure().message(),
                    1
            );
            case RunResult.Cancelled ignored -> 130;
        };
    }

    private static int succeeded(final Map<String, RailixValue> responses) {
        final RailixValue exitCode = responses.get("status");
        if (!(exitCode instanceof RailixValue.NumberValue number)) {
            return reject("CLI exit code must be a number.");
        }
        try {
            final int value = number.value().intValueExact();
            if (value < 0 || value > 255) {
                return reject("CLI exit code must be from 0 through 255.");
            }
            final RailixValue result = responses.get("output");
            if (!(result instanceof RailixValue.NullValue)) {
                System.out.println(RailixJson.write(result));
            }
            return value;
        } catch (final ArithmeticException exception) {
            return reject("CLI exit code must be an integer.");
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

    private static int application(final String[] arguments) {
        if (arguments.length != 3) {
            return reject("Invalid Creator application command.");
        }
        return DevelopmentRuntime.run(Path.of(arguments[1]), arguments[2]);
    }

    private static int reject(final String message) {
        return reject(message, 2);
    }

    private static int reject(final List<Diagnostic> diagnostics) {
        diagnostics.forEach(diagnostic ->
                System.err.println(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message())
        );
        return 2;
    }

    private static int reject(final String message, final int status) {
        System.err.println(message);
        return status;
    }
}
