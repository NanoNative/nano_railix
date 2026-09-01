package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.stdlib.StandardLibrary;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Builds and invokes real generated application artifacts for boundary cases HTTP cannot express. */
final class CreatorFirstExecutionArtifacts {
    private static final String PRODUCTION_PROBE_CLASS =
            "dev.nanonative.railix.core.project.CreatorFirstExecutionProductionProbe";
    private static final String DEVELOPMENT_PROBE_CLASS =
            "dev.nanonative.railix.core.project.CreatorFirstExecutionDevelopmentProbe";

    private CreatorFirstExecutionArtifacts() {
    }

    static Path production(final Path workspace, final String source) throws IOException {
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IOException("Production conformance project did not compile: " + result);
        }
        return ApplicationBuilder.buildProduction(project, compiled).jar();
    }

    static Path generatedJar(final Path workspace) throws IOException {
        try (var files = Files.walk(workspace.resolve(".railix/build"))) {
            final List<Path> jars = files.filter(path -> path.getFileName().toString().equals("application.jar"))
                    .toList();
            if (jars.size() != 1) {
                throw new IOException("Expected one generated application JAR but found " + jars.size() + ".");
            }
            return jars.getFirst();
        }
    }

    static ProductionProbe compileProductionProbe(final Path workspace, final Path applicationJar) throws IOException {
        return new ProductionProbe(
                compileProbe(workspace, applicationJar, PRODUCTION_PROBE_CLASS, productionProbeSource()),
                applicationJar
        );
    }

    static DevelopmentProbe compileDevelopmentProbe(
            final Path workspace,
            final Path applicationJar
    ) throws IOException {
        return new DevelopmentProbe(
                compileProbe(workspace, applicationJar, DEVELOPMENT_PROBE_CLASS, developmentProbeSource()),
                applicationJar
        );
    }

    static Path compileProbe(
            final Path workspace,
            final Path applicationJar,
            final String className,
            final String probeSource
    ) throws IOException {
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        final Path source = workspace.resolve("src/dev/nanonative/railix/core/project/" + simpleName + ".java");
        final Path classes = workspace.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, probeSource, StandardCharsets.UTF_8);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Creator-first execution tests require the JDK Java compiler.");
        }
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics,
                java.util.Locale.ROOT,
                StandardCharsets.UTF_8
        )) {
            final boolean compiled = Boolean.TRUE.equals(compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "-proc:none",
                            "-encoding", "UTF-8",
                            "--release", Integer.toString(Runtime.version().feature()),
                            "-classpath", applicationJar.toString(),
                            "-d", classes.toString()
                    ),
                    null,
                    files.getJavaFileObjects(source)
            ).call());
            if (!compiled) {
                throw new IOException("Generated runtime probe did not compile: " + diagnostics.getDiagnostics());
            }
        }
        return classes;
    }

    static ProcessResult runJar(final Path jar, final String... arguments) throws IOException {
        final List<String> command = new java.util.ArrayList<>();
        command.add(java().toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(arguments));
        return run(command);
    }

    static ProcessResult runProbe(
            final Path classes,
            final Path applicationJar,
            final String probeClass,
            final String... arguments
    ) throws IOException {
        final List<String> command = new java.util.ArrayList<>(List.of(
                java().toString(),
                "-cp", classes + java.io.File.pathSeparator + applicationJar,
                probeClass
        ));
        command.addAll(List.of(arguments));
        return run(command);
    }

    private static ProcessResult run(final List<String> command) throws IOException {
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(command))
                .redirectErrorStream(true)
                .start();
        try {
            final boolean exited;
            try {
                exited = process.waitFor(20, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Generated application invocation was interrupted.", exception);
            }
            if (!exited) {
                throw new IOException("Generated application invocation exceeded 20 seconds.");
            }
            return new ProcessResult(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()
            );
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor();
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static String productionProbeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixJson;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.List;
                import java.util.Map;

                public final class CreatorFirstExecutionProductionProbe {
                    private CreatorFirstExecutionProductionProbe() {
                    }

                    public static void main(final String[] arguments) {
                        final RuntimeApplication application = RailixApplication.runtime();
                        System.out.print(switch (arguments[0]) {
                            case "source-response-slots" -> sourceSlots(application);
                            case "parity-success" -> parity(
                                    application, List.of(RailixValue.string("Hello RAILIX"))
                            );
                            case "parity-incompatible" -> parity(
                                    application, List.of(RailixValue.number(1))
                            );
                            case "parity-missing" -> parity(application, List.of());
                            case "missing-source-name" -> result(application.runSource(null, Map.of()).result());
                            case "unknown-source" -> result(application.runSource("missing", Map.of()).result());
                            case "missing-source-values" -> result(application.runSource("application.arguments", null).result());
                            case "unknown-source-value" -> result(application.runSource(
                                    "application.arguments", Map.of("other", RailixValue.array(List.of()))
                            ).result());
                            case "missing-required-source-value" -> result(application.runSource(
                                    "application.arguments", Map.of()
                            ).result());
                            case "incompatible-source-value" -> result(application.runSource(
                                    "application.arguments", Map.of("arguments", RailixValue.string("wrong"))
                            ).result());
                            default -> throw new IllegalArgumentException(
                                    "Unknown production probe scenario: " + arguments[0] + "."
                            );
                        });
                    }

                    private static String sourceSlots(final RuntimeApplication application) {
                        final WorkflowRuntime.SourceResult source = application.runSource(
                                "application.arguments", Map.of("arguments", RailixValue.array(List.of()))
                        );
                        return result(source.result())
                                + "|output-null=" + (source.responses().get("output") instanceof RailixValue.NullValue)
                                + "|status-zero=" + RailixValue.number(0).equals(source.responses().get("status"));
                    }

                    private static String parity(
                            final RuntimeApplication application,
                            final List<RailixValue> arguments
                    ) {
                        final RunResult result = application.runSource(
                                "application.arguments", Map.of("arguments", RailixValue.array(arguments))
                        ).result();
                        return switch (result) {
                            case RunResult.Succeeded succeeded -> "succeeded|"
                                    + RailixJson.write(succeeded.context());
                            case RunResult.Rejected rejected -> "rejected|"
                                    + rejected.diagnostics().getFirst().code() + "|"
                                    + rejected.diagnostics().getFirst().path();
                            case RunResult.Failed failed -> "failed|"
                                    + failed.failure().code() + "|" + failed.failure().stepId();
                            case RunResult.Cancelled ignored -> "cancelled";
                        };
                    }

                    private static String result(final RunResult result) {
                        return switch (result) {
                            case RunResult.Succeeded ignored -> "succeeded";
                            case RunResult.Rejected rejected -> "rejected:" + rejected.diagnostics().getFirst().code();
                            case RunResult.Failed failed -> "failed:" + failed.failure().code();
                            case RunResult.Cancelled ignored -> "cancelled";
                        };
                    }
                }
                """;
    }

    private static String developmentProbeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixData;
                import dev.nanonative.railix.core.value.RailixValue;
                import dev.nanonative.railix.development.DevelopmentRuntime;
                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Map;

                public final class CreatorFirstExecutionDevelopmentProbe {
                    private CreatorFirstExecutionDevelopmentProbe() {
                    }

                    public static void main(final String[] arguments) {
                        final DevelopmentRuntime.Application application = RailixApplication.runtime();
                        System.out.print(switch (arguments[0]) {
                            case "canonical-input" -> result(application.run(
                                    "canonical-input", context(nonCanonicalNumber()), false
                            ));
                            case "canonical-output-detail" -> failure(application.run(
                                    "canonical-output", context(RailixValue.string("fault")), false
                            ));
                            case "unrefined-input" -> unchanged(application, "unrefined-input", mixedNonCanonical());
                            case "unrefined-output" -> unchanged(
                                    application,
                                    "unrefined-output",
                                    RailixValue.array(List.of(RailixValue.string(String.valueOf((char) 0xD800))))
                            );
                            case "missing-run-trigger" -> result(application.run(
                                    null, context(RailixValue.string("Hello")), false
                            ));
                            case "missing-stream-item" -> result(application.run("context", null, false));
                            case "interrupted" -> interrupted(application);
                            default -> throw new IllegalArgumentException(
                                    "Unknown development probe scenario: " + arguments[0] + "."
                            );
                        });
                    }

                    private static String unchanged(
                            final DevelopmentRuntime.Application application,
                            final String trigger,
                            final RailixValue expected
                    ) {
                        final RunResult result = application.run(
                                trigger,
                                context(trigger.equals("unrefined-input")
                                        ? expected
                                        : RailixValue.string("fault")),
                                false
                        );
                        if (!(result instanceof RunResult.Succeeded succeeded)) {
                            return result(result);
                        }
                        final RailixValue.ObjectValue payload = (RailixValue.ObjectValue)
                                succeeded.context().values().get("payload");
                        return "succeeded:equal=" + expected.equals(payload.values().get("value"));
                    }

                    private static String interrupted(final DevelopmentRuntime.Application application) {
                        Thread.currentThread().interrupt();
                        final RunResult result = application.run(
                                "context", context(RailixValue.string("Hello")), false
                        );
                        Thread.interrupted();
                        return result(result);
                    }

                    private static RailixValue.ObjectValue context(final RailixValue value) {
                        return RailixValue.object(Map.of(
                                "payload", RailixValue.object(Map.of(
                                        value instanceof RailixValue.StringValue string
                                                && string.value().equals("Hello") ? "name" : "value",
                                        value
                                ))
                        ));
                    }

                    private static String result(final RunResult result) {
                        return switch (result) {
                            case RunResult.Succeeded ignored -> "succeeded";
                            case RunResult.Rejected rejected -> "rejected:" + rejected.diagnostics().getFirst().code();
                            case RunResult.Failed failed -> "failed:" + failed.failure().code();
                            case RunResult.Cancelled ignored -> "cancelled";
                        };
                    }

                    private static String failure(final RunResult result) {
                        if (!(result instanceof RunResult.Failed failed)) {
                            return result(result);
                        }
                        return "failed:" + failed.failure().code()
                                + "|message=" + failed.failure().message()
                                + "|step=" + failed.failure().stepId()
                                + "|path=" + failed.failure().path();
                    }

                    private static RailixValue nonCanonicalNumber() {
                        return RailixValue.array(List.of(RailixValue.number(
                                BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
                        )));
                    }

                    private static RailixValue mixedNonCanonical() {
                        return RailixValue.array(List.of(
                                RailixValue.number(BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)),
                                RailixValue.string(String.valueOf((char) 0xD800))
                        ));
                    }
                }
                """;
    }

    record ProductionProbe(Path classes, Path applicationJar) {
        ProcessResult run(final String scenario) throws IOException {
            return runProbe(classes, applicationJar, PRODUCTION_PROBE_CLASS, scenario);
        }
    }

    record DevelopmentProbe(Path classes, Path applicationJar) {
        ProcessResult run(final String scenario) throws IOException {
            return runProbe(classes, applicationJar, DEVELOPMENT_PROBE_CLASS, scenario);
        }
    }

    record ProcessResult(int exitCode, String output) {
    }
}
