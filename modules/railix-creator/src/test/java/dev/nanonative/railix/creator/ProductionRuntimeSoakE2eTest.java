package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import thirdparty.production.ProductionRuntimeMultiWriteStep;

import static dev.nanonative.railix.core.step.StepDefinition.Input;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/** Production-artifact memory acceptance through the generated runtime boundary. */
final class ProductionRuntimeSoakE2eTest {
    private static final long MAX_HEAP_BYTES = 64L * 1024 * 1024;
    private static final long RETAINED_GROWTH_BUDGET_BYTES = 2L * 1024 * 1024;
    private static final long RUN_ALLOCATION_BUDGET_BYTES = 8L * 1024;
    private static final long RUN_LATENCY_BUDGET_NANOS = 5_000;
    private static final long FLOW_ALLOCATION_BUDGET_BYTES_PER_STEP = 1_024;
    private static final long FLOW_LATENCY_BUDGET_NANOS_PER_STEP = 2_000;
    private static final long ARRAY_ALLOCATION_BUDGET_BYTES = 256L * 1024;
    private static final int FLOW_STEPS = 129;
    private static final int ARRAY_ITEMS = 4_096;
    private static final int ARRAY_READ_STEPS = 32;
    private static final int MULTI_WRITE_STEPS = 1_800;
    private static final Duration CHILD_TIMEOUT = Duration.ofSeconds(120);
    private static final String PROBE_CLASS = "dev.nanonative.railix.core.project.ProductionRuntimeSoakProbe";

    @Test
    void oneMillionProductionCallsStayInside64MiBWithoutRetainingMoreThan2MiB(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace).run("soak");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("SOAK|");
        assertThat(metric(result.output(), "calls")).isEqualTo(1_000_000);
        assertThat(metric(result.output(), "max_heap")).isLessThanOrEqualTo(MAX_HEAP_BYTES);
        assertThat(metric(result.output(), "retained")).isLessThanOrEqualTo(RETAINED_GROWTH_BUDGET_BYTES);
    }

    @Test
    void productionRunSourceStaysInsideAllocationBudgetAfterOneHundredThousandWarmups(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace).run("allocation");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotStartWith("ALLOCATION_UNSUPPORTED|");
        assertThat(result.output()).startsWith("ALLOCATION|");
        assertThat(metric(result.output(), "measured_calls")).isEqualTo(200_000);
        assertThat(metric(result.output(), "bytes_per_call"))
                .isPositive()
                .isLessThanOrEqualTo(RUN_ALLOCATION_BUDGET_BYTES);
    }

    @Test
    void escapingProductionResultsStayInsideAllocationBudget(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace).run("escape-allocation");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotStartWith("ESCAPE_ALLOCATION_UNSUPPORTED|");
        assertThat(result.output()).startsWith("ESCAPE_ALLOCATION|");
        assertThat(metric(result.output(), "measured_calls")).isEqualTo(200_000);
        assertThat(metric(result.output(), "bytes_per_call"))
                .isPositive()
                .isLessThanOrEqualTo(RUN_ALLOCATION_BUDGET_BYTES);
    }

    @Test
    void warmedProductionRunSourceStaysInsideFiveRoundMedianLatencyBudget(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace).run("latency");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).startsWith("LATENCY|");
        assertThat(metric(result.output(), "rounds")).isEqualTo(5);
        assertThat(metric(result.output(), "calls_per_round")).isEqualTo(200_000);
        assertThat(metric(result.output(), "median_ns_per_call"))
                .isPositive()
                .isLessThanOrEqualTo(RUN_LATENCY_BUDGET_NANOS);
    }

    @Test
    void warmed129StepProductionFlowStaysInsidePerStepHotPathBudgets(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace, lowercaseChain(FLOW_STEPS)).run("flow-baseline");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotStartWith("FLOW_BASELINE_UNSUPPORTED|");
        assertThat(result.output()).startsWith("FLOW_BASELINE|");
        assertThat(metric(result.output(), "steps_per_call")).isEqualTo(FLOW_STEPS);
        assertThat(metric(result.output(), "measured_calls")).isEqualTo(2_500);
        assertThat(metric(result.output(), "verified_steps")).isEqualTo(322_500);
        assertThat(metric(result.output(), "retained")).isLessThanOrEqualTo(RETAINED_GROWTH_BUDGET_BYTES);
        assertThat(metric(result.output(), "bytes_per_step"))
                .isPositive()
                .isLessThanOrEqualTo(FLOW_ALLOCATION_BUDGET_BYTES_PER_STEP);
        assertThat(metric(result.output(), "median_ns_per_step"))
                .isPositive()
                .isLessThanOrEqualTo(FLOW_LATENCY_BUDGET_NANOS_PER_STEP);
    }

    @Test
    void repeatedReadsOfAMaterializedLargeArrayStayInsideAllocationBudget(
            @TempDir final Path workspace
    ) throws Exception {
        final ProcessResult result = probe(workspace, arrayReadChain(ARRAY_READ_STEPS)).run("array-read");
        System.out.println(result.output());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotStartWith("ARRAY_READ_UNSUPPORTED|");
        assertThat(result.output()).startsWith("ARRAY_READ|");
        assertThat(metric(result.output(), "items")).isEqualTo(ARRAY_ITEMS);
        assertThat(metric(result.output(), "reads_per_call")).isEqualTo(ARRAY_READ_STEPS);
        assertThat(metric(result.output(), "measured_calls")).isEqualTo(200);
        assertThat(metric(result.output(), "bytes_per_call"))
                .isPositive()
                .isLessThanOrEqualTo(ARRAY_ALLOCATION_BUDGET_BYTES);
    }

    @Test
    void repeatedNestedMultiWritesFit64MiBWithoutRetainingPriorEventFrames(
            @TempDir final Path workspace
    ) throws Exception {
        final Path projectDirectory = workspace.resolve("multi-write-application");
        final Path project = projectDirectory.resolve("railix.project.json");
        final String source = multiWriteChain(MULTI_WRITE_STEPS);
        Files.createDirectories(projectDirectory);
        Files.writeString(project, source, StandardCharsets.UTF_8);
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(
                projectDirectory,
                List.of(multiWriteDefinition()),
                ProductionRuntimeMultiWriteStep.class
        );
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path jar = ApplicationBuilder.buildProduction(
                project,
                (CompileResult.Compiled) result
        ).jar();

        final ProcessResult process = runLimitedHeap(jar);

        assertThat(process.exitCode()).as(process.output()).isZero();
        assertThat(process.output()).isEmpty();
    }

    private static Probe probe(final Path workspace) throws IOException {
        return probe(workspace, CreatorFirstExecutionProject.triggerOnlyCliSource());
    }

    private static Probe probe(final Path workspace, final String source) throws IOException {
        final Path projectDirectory = workspace.resolve("application");
        final Path project = projectDirectory.resolve("railix.project.json");
        Files.createDirectories(projectDirectory);
        Files.writeString(project, source, StandardCharsets.UTF_8);

        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IOException("Production soak project did not compile: " + result);
        }
        final ApplicationBuilder.Artifact artifact = ApplicationBuilder.buildProduction(project, compiled);
        final Path publishedRoot = projectDirectory.resolve(".railix/build").toAbsolutePath().normalize();
        final Path jar = artifact.jar().toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !jar.startsWith(publishedRoot)) {
            throw new IOException("Production soak JAR was not atomically published under .railix/build.");
        }
        return new Probe(compileProbe(workspace.resolve("probe"), jar), jar);
    }

    private static String lowercaseChain(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"soak-flow","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"baseline","payload":["RAILIX"]
                  }]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-0"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append("""
                    ,{"id":"lowercase-%d","use":"text.lowercase","inputs":{},
                      "receives":{"value":[%s]},
                      "returns":{"value":["context","result"]}}
                    """.formatted(
                    index,
                    index == 0
                            ? "\"context\",\"payload\",\"arguments\",0"
                            : "\"context\",\"result\""
            ));
            links.append("""
                    ,{"from":"lowercase-%d.ok","to":%s}
                    """.formatted(
                    index,
                    index + 1 == steps ? "\"end\"" : "\"lowercase-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String arrayReadChain(final int reads) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"array-read-flow","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"array","payload":["RAILIX"]
                  }]},
                  {"id":"materialize","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","arguments",0],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"materialize"},
                  {"from":"materialize.next","to":"size-0"}
                """);
        for (int index = 0; index < reads; index++) {
            nodes.append("""
                    ,{"id":"size-%d","use":"list.size","inputs":{},
                      "receives":{"value":["context","payload","arguments"]},
                      "returns":{"value":["context","result"]}}
                    """.formatted(index));
            links.append("""
                    ,{"from":"size-%d.ok","to":%s}
                    """.formatted(index, index + 1 == reads ? "\"end\"" : "\"size-" + (index + 1) + "\""));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static StepDefinition multiWriteDefinition() {
        return StepDefinition.named("production.multi-write", "1")
                .input("first", Input.path(WRITE).defaultPath("context", "payload", "first"))
                .input("second", Input.path(WRITE).defaultPath("context", "payload", "second"))
                .run(ProductionRuntimeMultiWriteStep.class);
    }

    private static String multiWriteChain(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"multi-write-ownership","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"ownership","payload":[]
                  }]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"write-0"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append("""
                    ,{"id":"write-%d","use":"production.multi-write","inputs":{
                      "first":["context","payload","state","branch-%d","first"],
                      "second":["context","payload","state","branch-%d","second"]}}
                    """.formatted(index, index, index));
            links.append("""
                    ,{"from":"write-%d.next","to":%s}
                    """.formatted(index, index + 1 == steps ? "\"end\"" : "\"write-" + (index + 1) + "\""));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static ProcessResult runLimitedHeap(final Path jar) throws IOException {
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                java().toString(),
                "-Xms64m",
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-jar",
                jar.toString()
        )).redirectErrorStream(true).start();
        try {
            final boolean exited;
            try {
                exited = process.waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Multi-write ownership probe was interrupted.", exception);
            }
            if (!exited) {
                throw new IOException("Multi-write ownership probe exceeded "
                        + CHILD_TIMEOUT.toSeconds() + " seconds.");
            }
            return new ProcessResult(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip()
            );
        } finally {
            terminate(process);
        }
    }

    private static Path compileProbe(final Path workspace, final Path applicationJar) throws IOException {
        final Path source = workspace.resolve(
                "src/dev/nanonative/railix/core/project/ProductionRuntimeSoakProbe.java"
        );
        final Path classes = workspace.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, probeSource(), StandardCharsets.UTF_8);

        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("Production runtime soak requires the JDK Java compiler.");
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
                throw new IOException("Production runtime soak probe did not compile: " + diagnostics.getDiagnostics());
            }
        }
        return classes;
    }

    private static long metric(final String output, final String name) {
        for (final String part : output.strip().split("\\|")) {
            if (part.startsWith(name + "=")) {
                return Long.parseLong(part.substring(name.length() + 1));
            }
        }
        throw new AssertionError("Missing metric " + name + " in child output: " + output);
    }

    private static String probeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import com.sun.management.ThreadMXBean;
                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.lang.management.ManagementFactory;
                import java.util.ArrayList;
                import java.util.Arrays;
                import java.util.List;
                import java.util.Map;

                public final class ProductionRuntimeSoakProbe {
                    private static final int SOAK_CALLS = 1_000_000;
                    private static final int WARMUP_CALLS = 100_000;
                    private static final int MEASURED_CALLS = 200_000;
                    private static final int LATENCY_ROUNDS = 5;
                    private static final int FLOW_STEPS = 129;
                    private static final int FLOW_WARMUP_CALLS = 1_000;
                    private static final int FLOW_CALLS_PER_ROUND = 500;
                    private static final int ARRAY_ITEMS = 4_096;
                    private static final int ARRAY_READ_STEPS = 32;
                    private static final int ARRAY_WARMUP_CALLS = 50;
                    private static final int ARRAY_MEASURED_CALLS = 200;
                    private static final long RETAINED_BUDGET = 2L * 1024 * 1024;
                    private static final Map<String, RailixValue> INPUT = Map.of(
                            "arguments", RailixValue.array(List.of())
                    );
                    private static final Map<String, RailixValue> FLOW_INPUT = Map.of(
                            "arguments", RailixValue.array(List.of(RailixValue.string("RAILIX")))
                    );
                    private static final Map<String, RailixValue> ARRAY_INPUT = arrayInput();
                    private static final RailixValue ZERO = RailixValue.number(0);
                    private static final RailixValue NULL = RailixValue.nullValue();
                    private static final RailixValue FLOW_OUTPUT = RailixValue.string("railix");
                    private static WorkflowRuntime.SourceResult sink;

                    private ProductionRuntimeSoakProbe() {
                    }

                    public static void main(final String[] arguments) throws Exception {
                        final RuntimeApplication application = RailixApplication.runtime();
                        switch (arguments[0]) {
                            case "soak" -> soak(application);
                            case "allocation" -> allocation(application);
                            case "escape-allocation" -> escapeAllocation(application);
                            case "latency" -> latency(application);
                            case "flow-baseline" -> flowBaseline(application);
                            case "array-read" -> arrayRead(application);
                            default -> throw new IllegalArgumentException("Unknown soak scenario: " + arguments[0]);
                        }
                    }

                    private static void soak(final RuntimeApplication application) throws InterruptedException {
                        final long before = compactedHeap();
                        long checksum = execute(application, WARMUP_CALLS);
                        checksum += execute(application, SOAK_CALLS);
                        final long after = compactedHeap();
                        final long retained = Math.max(0, after - before);
                        if (retained > RETAINED_BUDGET) {
                            throw new AssertionError("Retained heap grew by " + retained + " bytes.");
                        }
                        System.out.print("SOAK|calls=" + SOAK_CALLS
                                + "|max_heap=" + Runtime.getRuntime().maxMemory()
                                + "|retained=" + retained
                                + "|checksum=" + checksum);
                    }

                    private static void allocation(final RuntimeApplication application) {
                        execute(application, WARMUP_CALLS);
                        allocation(application, false, "ALLOCATION");
                    }

                    private static void escapeAllocation(final RuntimeApplication application) {
                        executeEscaping(application, WARMUP_CALLS);
                        allocation(application, true, "ESCAPE_ALLOCATION");
                    }

                    private static void allocation(
                            final RuntimeApplication application,
                            final boolean escape,
                            final String label
                    ) {
                        final java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
                        if (!(platformBean instanceof ThreadMXBean bean)
                                || !bean.isThreadAllocatedMemorySupported()) {
                            System.out.print(label + "_UNSUPPORTED|reason=thread_allocation_counter");
                            return;
                        }
                        if (!bean.isThreadAllocatedMemoryEnabled()) {
                            bean.setThreadAllocatedMemoryEnabled(true);
                        }
                        final long thread = Thread.currentThread().threadId();
                        final long before = bean.getThreadAllocatedBytes(thread);
                        final long checksum = escape
                                ? executeEscaping(application, MEASURED_CALLS)
                                : execute(application, MEASURED_CALLS);
                        final long after = bean.getThreadAllocatedBytes(thread);
                        if (before < 0 || after < before) {
                            throw new AssertionError("Thread allocation counter returned invalid values.");
                        }
                        System.out.print(label + "|measured_calls=" + MEASURED_CALLS
                                + "|allocated_bytes=" + (after - before)
                                + "|bytes_per_call=" + ((after - before) / MEASURED_CALLS)
                                + "|checksum=" + checksum);
                    }

                    private static void latency(final RuntimeApplication application) {
                        execute(application, WARMUP_CALLS);
                        final long[] rounds = new long[LATENCY_ROUNDS];
                        long checksum = 0;
                        for (int round = 0; round < LATENCY_ROUNDS; round++) {
                            final long start = System.nanoTime();
                            checksum += execute(application, MEASURED_CALLS);
                            rounds[round] = System.nanoTime() - start;
                        }
                        Arrays.sort(rounds);
                        System.out.print("LATENCY|rounds=" + LATENCY_ROUNDS
                                + "|calls_per_round=" + MEASURED_CALLS
                                + "|median_ns_per_call=" + (rounds[LATENCY_ROUNDS / 2] / MEASURED_CALLS)
                                + "|checksum=" + checksum);
                    }

                    private static void flowBaseline(
                            final RuntimeApplication application
                    ) throws InterruptedException {
                        executeFlow(application, FLOW_WARMUP_CALLS);
                        final java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
                        if (!(platformBean instanceof ThreadMXBean bean)
                                || !bean.isThreadAllocatedMemorySupported()) {
                            System.out.print("FLOW_BASELINE_UNSUPPORTED|reason=thread_allocation_counter");
                            return;
                        }
                        if (!bean.isThreadAllocatedMemoryEnabled()) {
                            bean.setThreadAllocatedMemoryEnabled(true);
                        }
                        final long retainedBefore = compactedHeap();
                        final long thread = Thread.currentThread().threadId();
                        final long allocatedBefore = bean.getThreadAllocatedBytes(thread);
                        final long[] rounds = new long[LATENCY_ROUNDS];
                        for (int round = 0; round < LATENCY_ROUNDS; round++) {
                            final long start = System.nanoTime();
                            executeFlow(application, FLOW_CALLS_PER_ROUND);
                            rounds[round] = System.nanoTime() - start;
                        }
                        final long allocatedAfter = bean.getThreadAllocatedBytes(thread);
                        final long retained = Math.max(0, compactedHeap() - retainedBefore);
                        if (allocatedBefore < 0 || allocatedAfter < allocatedBefore) {
                            throw new AssertionError("Thread allocation counter returned invalid values.");
                        }
                        if (retained > RETAINED_BUDGET) {
                            throw new AssertionError("Retained heap grew by " + retained + " bytes.");
                        }
                        Arrays.sort(rounds);
                        final long calls = (long) LATENCY_ROUNDS * FLOW_CALLS_PER_ROUND;
                        final long verifiedSteps = calls * FLOW_STEPS;
                        System.out.print("FLOW_BASELINE|steps_per_call=" + FLOW_STEPS
                                + "|measured_calls=" + calls
                                + "|verified_steps=" + verifiedSteps
                                + "|retained=" + retained
                                + "|bytes_per_call=" + ((allocatedAfter - allocatedBefore) / calls)
                                + "|bytes_per_step=" + ((allocatedAfter - allocatedBefore) / verifiedSteps)
                                + "|median_ns_per_call="
                                + (rounds[LATENCY_ROUNDS / 2] / FLOW_CALLS_PER_ROUND)
                                + "|median_ns_per_step="
                                + (rounds[LATENCY_ROUNDS / 2] / (FLOW_CALLS_PER_ROUND * FLOW_STEPS)));
                    }

                    private static void executeFlow(
                            final RuntimeApplication application,
                            final int calls
                    ) {
                        for (int index = 0; index < calls; index++) {
                            sink = application.runSource("application.arguments", FLOW_INPUT);
                            if (!(sink.result() instanceof RunResult.Succeeded succeeded)) {
                                throw new AssertionError("Production flow did not succeed: " + sink.result());
                            }
                            if (!FLOW_OUTPUT.equals(sink.responses().get("output"))
                                    || !ZERO.equals(sink.responses().get("status"))
                                    || !FLOW_OUTPUT.equals(succeeded.context().values().get("result"))) {
                                throw new AssertionError("Production flow returned unexpected output.");
                            }
                        }
                    }

                    private static void arrayRead(final RuntimeApplication application) {
                        executeArray(application, ARRAY_WARMUP_CALLS);
                        final java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
                        if (!(platformBean instanceof ThreadMXBean bean)
                                || !bean.isThreadAllocatedMemorySupported()) {
                            System.out.print("ARRAY_READ_UNSUPPORTED|reason=thread_allocation_counter");
                            return;
                        }
                        if (!bean.isThreadAllocatedMemoryEnabled()) {
                            bean.setThreadAllocatedMemoryEnabled(true);
                        }
                        final long thread = Thread.currentThread().threadId();
                        final long before = bean.getThreadAllocatedBytes(thread);
                        final long checksum = executeArray(application, ARRAY_MEASURED_CALLS);
                        final long after = bean.getThreadAllocatedBytes(thread);
                        if (before < 0 || after < before) {
                            throw new AssertionError("Thread allocation counter returned invalid values.");
                        }
                        System.out.print("ARRAY_READ|items=" + ARRAY_ITEMS
                                + "|reads_per_call=" + ARRAY_READ_STEPS
                                + "|measured_calls=" + ARRAY_MEASURED_CALLS
                                + "|bytes_per_call=" + ((after - before) / ARRAY_MEASURED_CALLS)
                                + "|checksum=" + checksum);
                    }

                    private static long executeArray(
                            final RuntimeApplication application,
                            final int calls
                    ) {
                        long checksum = 0;
                        final RailixValue expected = RailixValue.number(ARRAY_ITEMS);
                        for (int index = 0; index < calls; index++) {
                            sink = application.runSource("application.arguments", ARRAY_INPUT);
                            if (!(sink.result() instanceof RunResult.Succeeded succeeded)) {
                                throw new AssertionError("Array-read flow did not succeed: " + sink.result());
                            }
                            if (!expected.equals(sink.responses().get("output"))
                                    || !ZERO.equals(sink.responses().get("status"))
                                    || !expected.equals(succeeded.context().values().get("result"))) {
                                throw new AssertionError("Array-read flow returned unexpected output.");
                            }
                            checksum += ((RailixValue.ArrayValue) ((RailixValue.ObjectValue)
                                    succeeded.context().values().get("payload")).values()
                                    .get("arguments")).values().size();
                        }
                        return checksum;
                    }

                    private static long execute(
                            final RuntimeApplication application,
                            final int calls
                    ) {
                        long checksum = 0;
                        for (int index = 0; index < calls; index++) {
                            checksum += verify(application.runSource("application.arguments", INPUT));
                        }
                        return checksum;
                    }

                    private static long executeEscaping(
                            final RuntimeApplication application,
                            final int calls
                    ) {
                        long checksum = 0;
                        for (int index = 0; index < calls; index++) {
                            sink = application.runSource("application.arguments", INPUT);
                            checksum += verify(sink);
                        }
                        return checksum;
                    }

                    private static int verify(final WorkflowRuntime.SourceResult source) {
                        if (!(source.result() instanceof RunResult.Succeeded succeeded)) {
                            throw new AssertionError("Production execution did not succeed: " + source.result());
                        }
                        if (!NULL.equals(source.responses().get("output"))
                                || !ZERO.equals(source.responses().get("status"))) {
                            throw new AssertionError("Production source returned unexpected response defaults.");
                        }
                        return succeeded.context().values().size() + source.responses().size();
                    }

                    private static Map<String, RailixValue> arrayInput() {
                        final List<RailixValue> values = new ArrayList<>(ARRAY_ITEMS);
                        for (int index = 0; index < ARRAY_ITEMS; index++) {
                            values.add(RailixValue.string("RAILIX"));
                        }
                        return Map.of("arguments", RailixValue.array(values));
                    }

                    private static long compactedHeap() throws InterruptedException {
                        long minimum = Long.MAX_VALUE;
                        for (int attempt = 0; attempt < 5; attempt++) {
                            System.gc();
                            Thread.sleep(25);
                            minimum = Math.min(
                                    minimum,
                                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                            );
                        }
                        return minimum;
                    }
                }
                """;
    }

    private record Probe(Path classes, Path applicationJar) {
        private ProcessResult run(final String scenario) throws IOException {
            final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                    java().toString(),
                    "-Xms64m",
                    "-Xmx64m",
                    "-cp", classes + File.pathSeparator + applicationJar,
                    PROBE_CLASS,
                    scenario
            )).redirectErrorStream(true).start();
            try {
                final boolean exited;
                try {
                    exited = process.waitFor(CHILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Production runtime soak was interrupted.", exception);
                }
                if (!exited) {
                    throw new IOException("Production runtime soak exceeded " + CHILD_TIMEOUT.toSeconds() + " seconds.");
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
                    throw new IOException("Production runtime soak process did not terminate.");
                }
            }
        } catch (final InterruptedException exception) {
            process.destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("Production runtime soak process survived forceful cleanup.", exception);
                }
            } catch (final InterruptedException cleanupInterruption) {
                exception.addSuppressed(cleanupInterruption);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Production runtime soak cleanup was interrupted.", exception);
        }
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
