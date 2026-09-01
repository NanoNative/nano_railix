package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.production.ProductionRuntimeConcurrencySteps;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/** Production-artifact concurrency acceptance through package-private runSource. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class ProductionRuntimeConcurrencyE2eTest {
    private static final int CALLS = 2_000;
    private static final int OVERLAP_CALLS = 64;
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(90);
    private static final String PROBE_CLASS =
            "dev.nanonative.railix.core.project.ProductionRuntimeConcurrencyProbe";

    private Probe probe;

    @BeforeAll
    void buildPersistedProductionApplication(@TempDir final Path workspace) throws Exception {
        final Path projectDirectory = workspace.resolve("application");
        final Path project = projectDirectory.resolve("railix.project.json");
        Files.createDirectories(projectDirectory);
        Files.writeString(project, projectSource(), StandardCharsets.UTF_8);
        final var catalog = GeneratedApplicationFixture.installedCatalog(
                projectDirectory,
                definitions(),
                ProductionRuntimeConcurrencySteps.CreateFile.class,
                ProductionRuntimeConcurrencySteps.Barrier.class
        );
        final CompileResult result = ProjectCompiler.compileApplication(projectSource(), catalog);
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IOException("Production concurrency project did not compile: " + result);
        }
        final ApplicationBuilder.Artifact artifact = ApplicationBuilder.buildProduction(project, compiled);
        final Path jar = artifact.jar().toAbsolutePath().normalize();
        final Path publishedRoot = projectDirectory.resolve(".railix/build").toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !jar.startsWith(publishedRoot)) {
            throw new IOException("Production concurrency JAR was not atomically published under .railix/build.");
        }
        probe = new Probe(compileProbe(workspace.resolve("probe"), jar), jar, workspace.resolve("runs"));
    }

    @Test
    void concurrentProductionCallsKeepEachInputInItsOwnResultContext() throws Exception {
        final ProcessResult result = probe.run("context");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("CONTEXT|");
        assertThat(metric(result.output(), "calls")).isEqualTo(CALLS);
        assertThat(metric(result.output(), "isolated")).isEqualTo(CALLS);
    }

    @Test
    void concurrentProductionCallsCreateEachFilesystemEffectExactlyOnce() throws Exception {
        final ProcessResult result = probe.run("effect");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("EFFECT|");
        assertThat(metric(result.output(), "calls")).isEqualTo(CALLS);
        assertThat(metric(result.output(), "files")).isEqualTo(CALLS);
        assertThat(metric(result.output(), "exact")).isEqualTo(CALLS);
    }

    @Test
    void sixtyFourProductionCallsEnterARealStepConcurrently() throws Exception {
        final ProcessResult result = probe.run("overlap");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("OVERLAP|");
        assertThat(metric(result.output(), "calls")).isEqualTo(OVERLAP_CALLS);
        assertThat(metric(result.output(), "entered")).isEqualTo(OVERLAP_CALLS);
        assertThat(metric(result.output(), "succeeded")).isEqualTo(OVERLAP_CALLS);
    }

    private static List<StepDefinition> definitions() {
        return List.of(
                StepDefinition.named("production.concurrent.barrier", "1")
                        .run(ProductionRuntimeConcurrencySteps.Barrier.class),
                StepDefinition.named("production.concurrent.create-file", "1")
                        .receive("file", ValueShape.STRING)
                        .receive("id", ValueShape.STRING)
                        .input("target", StepDefinition.Input.path(WRITE)
                                .defaultPath("context", "result"))
                        .run(ProductionRuntimeConcurrencySteps.CreateFile.class)
        );
    }

    private static String projectSource() {
        return """
                {"format":1,"id":"production-concurrency","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"file","payload":["result.txt","call-0"]}
                  ]},
                  {"id":"barrier","use":"production.concurrent.barrier","inputs":{}},
                  {"id":"create","use":"production.concurrent.create-file","inputs":{},"receives":{
                    "file":["context","payload","arguments",0],
                    "id":["context","payload","arguments",1]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"barrier"},
                  {"from":"barrier.next","to":"create"},
                  {"from":"create.next","to":"end"}
                ]}
                """;
    }

    private static Path compileProbe(final Path workspace, final Path applicationJar) throws IOException {
        final Path source = workspace.resolve(
                "src/dev/nanonative/railix/core/project/ProductionRuntimeConcurrencyProbe.java"
        );
        final Path classes = workspace.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, probeSource(), StandardCharsets.UTF_8);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Production concurrency acceptance requires the JDK Java compiler.");
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
                throw new IOException("Production concurrency probe did not compile: " + diagnostics.getDiagnostics());
            }
        }
        return classes;
    }

    private static String probeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import thirdparty.production.ProductionRuntimeConcurrencySteps;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.CountDownLatch;
                import java.util.concurrent.Executors;
                import java.util.concurrent.Future;
                import java.util.concurrent.TimeUnit;

                public final class ProductionRuntimeConcurrencyProbe {
                    private static final int CALLS = 2_000;
                    private static final int OVERLAP_CALLS = 64;

                    private ProductionRuntimeConcurrencyProbe() {
                    }

                    public static void main(final String[] arguments) throws Exception {
                        final String scenario = arguments[0];
                        final Path directory = Path.of(arguments[1]);
                        Files.createDirectories(directory);
                        final RuntimeApplication application = RailixApplication.runtime();
                        if ("overlap".equals(scenario)) {
                            overlap(application, directory);
                            return;
                        }
                        final List<Observed> observed = execute(application, directory);
                        switch (scenario) {
                            case "context" -> context(observed);
                            case "effect" -> effect(directory, observed);
                            default -> throw new IllegalArgumentException(
                                    "Unknown production concurrency scenario: " + scenario
                            );
                        }
                    }

                    private static List<Observed> execute(
                            final RuntimeApplication application,
                            final Path directory
                    ) throws Exception {
                        final List<Future<Observed>> futures = new ArrayList<>(CALLS);
                        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            for (int index = 0; index < CALLS; index++) {
                                final int call = index;
                                futures.add(executor.submit(() -> invoke(application, directory, call)));
                            }
                            final List<Observed> observed = new ArrayList<>(CALLS);
                            for (final Future<Observed> future : futures) {
                                observed.add(future.get());
                            }
                            return List.copyOf(observed);
                        }
                    }

                    private static Observed invoke(
                            final RuntimeApplication application,
                            final Path directory,
                            final int call
                    ) {
                        final String id = "call-" + call;
                        final Path file = directory.resolve(id + ".txt").toAbsolutePath().normalize();
                        final List<RailixValue> arguments = List.of(
                                RailixValue.string(file.toString()),
                                RailixValue.string(id)
                        );
                        final WorkflowRuntime.SourceResult source = application.runSource(
                                "application.arguments",
                                Map.of("arguments", RailixValue.array(arguments))
                        );
                        if (!(source.result() instanceof RunResult.Succeeded succeeded)) {
                            throw new AssertionError("Production concurrent execution failed: " + source.result());
                        }
                        return new Observed(id, file, arguments, source, succeeded);
                    }

                    private static void context(final List<Observed> observed) {
                        long isolated = 0;
                        for (final Observed item : observed) {
                            final RailixValue.ObjectValue payload = (RailixValue.ObjectValue)
                                    item.succeeded().context().values().get("payload");
                            if (RailixValue.array(item.arguments()).equals(payload.values().get("arguments"))
                                    && RailixValue.string(item.id()).equals(
                                            item.succeeded().context().values().get("result")
                                    )
                                    && RailixValue.string(item.id()).equals(item.source().responses().get("output"))
                                    && RailixValue.number(0).equals(item.source().responses().get("status"))) {
                                isolated++;
                            }
                        }
                        System.out.print("CONTEXT|calls=" + CALLS + "|isolated=" + isolated);
                    }

                    private static void effect(final Path directory, final List<Observed> observed) throws Exception {
                        long exact = 0;
                        for (final Observed item : observed) {
                            if (item.id().equals(Files.readString(item.file()))) {
                                exact++;
                            }
                        }
                        try (var files = Files.list(directory)) {
                            System.out.print("EFFECT|calls=" + CALLS + "|files=" + files.count()
                                    + "|exact=" + exact);
                        }
                    }

                    private static void overlap(
                            final RuntimeApplication application,
                            final Path directory
                    ) throws Exception {
                        final CountDownLatch start = new CountDownLatch(1);
                        final List<Future<Observed>> futures = new ArrayList<>(OVERLAP_CALLS);
                        ProductionRuntimeConcurrencySteps.Barrier.arm(OVERLAP_CALLS);
                        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            for (int index = 0; index < OVERLAP_CALLS; index++) {
                                final int call = index;
                                futures.add(executor.submit(() -> {
                                    start.await();
                                    return invoke(application, directory, call);
                                }));
                            }
                            start.countDown();
                            final boolean entered = ProductionRuntimeConcurrencySteps.Barrier.awaitEntered(10_000);
                            ProductionRuntimeConcurrencySteps.Barrier.release();
                            if (!entered) {
                                throw new AssertionError("Production calls did not concurrently enter the Step handler.");
                            }
                            int succeeded = 0;
                            for (final Future<Observed> future : futures) {
                                future.get();
                                succeeded++;
                            }
                            System.out.print("OVERLAP|calls=" + OVERLAP_CALLS
                                    + "|entered=" + OVERLAP_CALLS + "|succeeded=" + succeeded);
                        } finally {
                            ProductionRuntimeConcurrencySteps.Barrier.release();
                        }
                    }

                    private record Observed(
                            String id,
                            Path file,
                            List<RailixValue> arguments,
                            WorkflowRuntime.SourceResult source,
                            RunResult.Succeeded succeeded
                    ) {
                    }
                }
                """;
    }

    private static long metric(final String output, final String name) {
        for (final String part : output.strip().split("\\|")) {
            if (part.startsWith(name + "=")) {
                return Long.parseLong(part.substring(name.length() + 1));
            }
        }
        throw new AssertionError("Missing metric " + name + " in child output: " + output);
    }

    private record Probe(Path classes, Path applicationJar, Path runs) {
        private ProcessResult run(final String scenario) throws IOException {
            final Path directory = runs.resolve(scenario);
            final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                    java().toString(),
                    "-cp", classes + File.pathSeparator + applicationJar,
                    PROBE_CLASS,
                    scenario,
                    directory.toString()
            )).redirectErrorStream(true).start();
            try {
                final boolean exited;
                try {
                    exited = process.waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Production concurrency acceptance was interrupted.", exception);
                }
                if (!exited) {
                    throw new IOException(
                            "Production concurrency acceptance exceeded " + CHILD_TIMEOUT.toSeconds() + " seconds."
                    );
                }
                return new ProcessResult(
                        process.exitValue(),
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()
                );
            } finally {
                terminate(process);
            }
        }
    }

    private static void terminate(final Process process) throws IOException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Production concurrency process did not terminate.");
                }
            }
        } catch (final InterruptedException exception) {
            process.destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Production concurrency process survived forceful cleanup.", exception);
                }
            } catch (final InterruptedException cleanupInterruption) {
                exception.addSuppressed(cleanupInterruption);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Production concurrency cleanup was interrupted.", exception);
        }
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
