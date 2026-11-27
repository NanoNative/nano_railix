package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.project.RuntimeApplication;
import dev.nanonative.railix.core.step.StepCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Starts Railix Creator. Generated project applications are independent executable JARs. */
public final class RailixMain {
    private static final int DEFAULT_PORT = 0;
    private static final String CREATOR_USAGE = "Usage: railix creator [project-file] [port] [--application application.jar]";
    private static final String BUILD_USAGE = "       railix build [project-file] [--include-project] [--no-observation] [--mesh]";
    private static final Set<String> BUILD_FLAGS = Set.of("--include-project", "--no-observation", "--mesh");
    private static final String USAGE = CREATOR_USAGE + "\n"
            + "       railix run [arguments...]\n" + BUILD_USAGE;

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
            case "run" -> runApplication(List.of(arguments).subList(1, arguments.length));
            case "build" -> build(List.of(arguments).subList(1, arguments.length));
            default -> reject("Unknown Railix command: " + arguments[0] + ".");
        };
    }

    private static int runApplication(final List<String> arguments) {
        try {
            final Path project = Path.of("railix.project.json");
            final Path absoluteProject = project.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteProject)) return reject("Cannot read project: " + project);
            if (Files.exists(ProjectFiles.journal(absoluteProject)))
                return reject("PROJECT_EDIT_PENDING: Reopen Creator to recover the interrupted edit before running.");
            final var documents = ProjectFiles.read(absoluteProject);
            if (Files.exists(ProjectFiles.journal(absoluteProject)))
                return reject("PROJECT_EDIT_PENDING: Project files are being edited. Retry after the edit completes.");
            if (!documents.diagnostics().isEmpty()) {
                final Diagnostic diagnostic = documents.diagnostics().getFirst();
                return reject(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final String source = documents.source();
            final StepCatalog catalog = ProjectFiles.catalog(absoluteProject,
                    Path.of(System.getProperty("user.home"), ".railix"));
            final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
            if (result instanceof CompileResult.Rejected rejected) {
                final Diagnostic diagnostic = documents.locations(rejected.diagnostics()).getFirst();
                return reject(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final CompileResult.Compiled compiled = (CompileResult.Compiled) result;
            final Path jar = ApplicationBuilder.buildProduction(absoluteProject, compiled).jar();
            final List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(arguments);
            return waitFor(new ProcessBuilder(command).inheritIO().start());
        } catch (final IOException exception) {
            return reject(exception.getMessage());
        }
    }

    static int build(final List<String> arguments) {
        final List<String> files = arguments.stream()
                .filter(argument -> !BUILD_FLAGS.contains(argument))
                .toList();
        if (files.size() > 1
                || files.stream().anyMatch(argument -> argument.startsWith("--"))
                || BUILD_FLAGS.stream().anyMatch(flag -> Collections.frequency(arguments, flag) > 1)) {
            return reject(BUILD_USAGE.strip());
        }
        final Path project = Path.of(files.isEmpty() ? "railix.project.json" : files.getFirst()).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(project)) {
                return reject("PROJECT_SOURCE_REQUIRED: Select an existing project file: " + project);
            }
            final boolean includeProject = arguments.contains("--include-project");
            final ProjectFiles.Build source = ProjectFiles.build(project,
                    Path.of(System.getProperty("user.home"), ".railix"), includeProject, arguments.contains("--mesh"));
            if (source.result() instanceof CompileResult.Rejected rejected) {
                final Diagnostic diagnostic = rejected.diagnostics().getFirst();
                return reject(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message());
            }
            final ApplicationBuilder.Artifact artifact = ApplicationBuilder.buildProduction(project, source,
                    !arguments.contains("--no-observation"));
            System.out.println(artifact.jar());
            return 0;
        } catch (final IOException | IllegalArgumentException exception) {
            return reject(exception.getMessage());
        }
    }

    private static int waitFor(final Process process) {
        final Thread shutdown = Thread.ofPlatform()
                .name("railix-run-shutdown")
                .unstarted(() -> stop(process));
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return process.waitFor();
        } catch (final InterruptedException exception) {
            stop(process);
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

    private static void stop(final Process process) {
        final long deadline = System.nanoTime() + RuntimeApplication.DEFAULT_DRAIN_BUDGET.toNanos();
        boolean interrupted = Thread.interrupted();
        try {
            process.destroy();
            while (process.isAlive()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    System.err.println("Application drain deadline expired; forcing termination.");
                    process.destroyForcibly();
                    return;
                }
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static int creator(final String[] arguments) {
        final List<String> options = new ArrayList<>(List.of(arguments).subList(1, arguments.length));
        final int applicationIndex = options.indexOf("--application");
        final Path artifact;
        if (applicationIndex >= 0) {
            if (applicationIndex + 1 >= options.size() || options.get(applicationIndex + 1).startsWith("--"))
                return reject(CREATOR_USAGE);
            artifact = Path.of(options.remove(applicationIndex + 1));
            options.remove(applicationIndex);
        } else artifact = null;
        if (options.size() > 2 || options.stream().anyMatch(option -> option.startsWith("--"))) {
            return reject(CREATOR_USAGE);
        }
        try {
            final Path selected = !options.isEmpty()
                    ? Path.of(options.getFirst())
                    : Path.of("railix.project.json");
            final int port = options.size() > 1 ? Integer.parseInt(options.get(1)) : DEFAULT_PORT;
            if (port < 0 || port > 65_535) return reject("Creator port must be from 0 through 65535.");
            final Path home = Path.of(System.getProperty("user.home"), ".railix");
            final ProjectFiles.ApplicationSource source = artifact == null
                    ? new ProjectFiles.ApplicationSource(selected, "")
                    : ProjectFiles.fromApplication(artifact, selected, home);
            final Path project = source.project();
            try (CreatorServer creator = CreatorServer.start(port, project, home, source.revision())) {
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
