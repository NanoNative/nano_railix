package dev.nanonative.railix.development;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class DevelopmentMetricsContractTest {
    @Test
    void metricStoreRejectsANullProjectIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                null,
                new String[]{"flow"},
                new String[]{"step"}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsABlankProjectIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                " ",
                new String[]{"flow"},
                new String[]{"step"}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsNullFlowIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                null,
                new String[]{"step"}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsNullStepIdentifiers() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                new String[]{"flow"},
                null
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsABlankFlowIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                new String[]{""},
                new String[]{"step"}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsANullFlowIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                new String[]{null},
                new String[]{"step"}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsABlankStepIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                new String[]{"flow"},
                new String[]{""}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricStoreRejectsANullStepIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Metrics(
                "project",
                new String[]{"flow"},
                new String[]{null}
        )).withMessage("Metric project, flows, and Steps must be supplied.");
    }

    @Test
    void metricJsonRejectsANullDestination() {
        assertThatIllegalArgumentException().isThrownBy(() -> metrics().writeJson(null))
                .withMessage("Metric destination cannot be Java null.");
    }

    @Test
    void nodeMetricsRejectABlankNodeIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> metrics().writeNodeJson(
                new ByteArrayOutputStream(),
                " "
        )).withMessage("Metric node must be a non-blank identifier.");
    }

    @Test
    void nodeMetricsRejectANullNodeIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> metrics().writeNodeJson(
                new ByteArrayOutputStream(),
                null
        )).withMessage("Metric node must be a non-blank identifier.");
    }

    @Test
    void negativeStepOutcomeCountsAnError() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), -1, succeeded());

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", 1L,
                "errors", 1L,
                "cancelled", 0L
        ));
    }

    @Test
    void cancelledStepDoesNotAlsoCountAsAnError() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), -1, new RunResult.Cancelled());

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", 1L,
                "errors", 0L,
                "cancelled", 1L
        ));
    }

    @Test
    void missingStepOutcomeCountsFailureEvenWhenCancellationWasAlsoReported() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), Integer.MIN_VALUE, new RunResult.Cancelled());

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", 1L,
                "errors", 1L,
                "cancelled", 1L
        ));
    }

    @Test
    void successfulStepOutcomeIgnoresAnUnrelatedTerminalFlowResult() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), 0, new RunResult.Rejected(List.of(
                Diagnostic.atPath("IGNORED", "Ignored.", "flow")
        )));

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", 1L,
                "errors", 0L,
                "cancelled", 0L
        ));
    }

    @Test
    void absentFlowResultCountsAnError() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishFlow(0, metrics.startFlow(0), null);

        assertThat(metric(flow(metrics, "command"))).containsAllEntriesOf(Map.of(
                "executions", 1L,
                "errors", 1L,
                "cancelled", 0L,
                "in_flight", 0L
        ));
    }

    @Test
    void unknownNodeMetricsContainNoFlowOrStepSeries() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        metrics().writeNodeJson(output, "unknown");

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("\"flows\":[]", "\"steps\":[]")
                .contains("\"application\":", "\"process\":");
    }

    @Test
    void constructorContainsOnlyCompilerSelectedMetricSeries() {
        final DevelopmentRuntime.Metrics metrics = metrics();

        assertThat(steps(metrics)).extracting(DevelopmentMetricsContractTest::id)
                .containsExactly("trigger", "enabled");
    }

    @Test
    void successErrorCancellationAndInflightAreCountedCumulatively() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        final long first = metrics.startFlow(0);
        assertThat(metric(flow(metrics, "command"))).containsEntry("in_flight", 1L);
        metrics.finishFlow(0, first, new RunResult.Succeeded(RailixValue.object(Map.of())));
        metrics.finishFlow(0, metrics.startFlow(0), new RunResult.Rejected(List.of(
                Diagnostic.atPath("INVALID", "Invalid.", "input")
        )));
        metrics.finishFlow(0, metrics.startFlow(0), new RunResult.Cancelled());

        assertThat(metric(flow(metrics, "command"))).containsAllEntriesOf(Map.of(
                "executions", 3L,
                "errors", 1L,
                "cancelled", 1L,
                "in_flight", 0L
        ));
        assertThat(metric(application(metrics))).containsAllEntriesOf(Map.of(
                "executions", 3L,
                "errors", 1L,
                "cancelled", 1L,
                "in_flight", 0L
        ));
    }

    @Test
    void contendedStepUpdatesStayBoundedWithoutLosingExecutions() throws Exception {
        final DevelopmentRuntime.Metrics metrics = metrics();
        final int workers = 32;
        final int calls = 100_000;
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>(workers);
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (final InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int call = 0; call < calls; call++) {
                        metrics.finishStep(1, metrics.startStep(1), 0, null);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            final long started = System.nanoTime();
            start.countDown();
            for (final var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(10));
        }

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", (long) workers * calls,
                "errors", 0L,
                "cancelled", 0L
        ));
    }

    @Test
    void snapshotReportsTheExactPrimitiveCounterCapacity() {
        final RailixValue.ObjectValue snapshot = snapshot(metrics());

        assertThat(number(snapshot, "application_pid")).isEqualTo(ProcessHandle.current().pid());
        assertThat(number(snapshot, "metric_counter_bytes")).isEqualTo(148);
        assertThat(number(snapshot, "metric_lookup_bytes")).isEqualTo(24);
        assertThat(number(snapshot, "metric_primitive_bytes")).isEqualTo(172);
    }

    @Test
    void applicationSnapshotOmitsNodeSeriesWithoutLookingUpAnAbsentNode() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        metrics().writeApplicationJson(output);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("\"flows\":[]", "\"steps\":[]")
                .contains("\"metric_primitive_bytes\":172");
    }

    @Test
    void stepDurationsAreSampledWithoutLosingExactExecutionCounts() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        for (int execution = 0; execution < 1_025; execution++) {
            metrics.finishStep(1, metrics.startStep(1), 0, null);
        }

        assertThat(metric(step(metrics, "enabled"))).containsAllEntriesOf(Map.of(
                "executions", 1_025L,
                "duration_samples", 2L
        ));
    }

    @Test
    void warmedMetricUpdatesAllocateNoHeapBeyondCounterReadOverhead() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        for (int call = 0; call < 50_000; call++) {
            metrics.finishStep(1, metrics.startStep(1), 0, null);
        }
        final com.sun.management.ThreadMXBean threads = ManagementFactory.getPlatformMXBean(
                com.sun.management.ThreadMXBean.class
        );
        assertThat(threads).isNotNull();
        assertThat(threads.isThreadAllocatedMemorySupported()).isTrue();
        if (!threads.isThreadAllocatedMemoryEnabled()) {
            threads.setThreadAllocatedMemoryEnabled(true);
        }
        final long thread = Thread.currentThread().threadId();
        for (int read = 0; read < 10; read++) {
            threads.getThreadAllocatedBytes(thread);
        }
        final long calibrationBefore = threads.getThreadAllocatedBytes(thread);
        final long calibrationAfter = threads.getThreadAllocatedBytes(thread);
        final long counterReadOverhead = calibrationAfter - calibrationBefore;
        final long before = threads.getThreadAllocatedBytes(thread);
        for (int call = 0; call < 200_000; call++) {
            metrics.finishStep(1, metrics.startStep(1), 0, null);
        }

        assertThat(threads.getThreadAllocatedBytes(thread) - before)
                .isLessThanOrEqualTo(counterReadOverhead);
    }

    @Test
    void warmedStepMetricRecordingStaysBelowOneMicrosecondPerCall() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        final int calls = 200_000;
        for (int call = 0; call < calls; call++) {
            metrics.finishStep(1, metrics.startStep(1), 0, null);
        }
        final long[] rounds = new long[5];
        for (int round = 0; round < rounds.length; round++) {
            final long started = System.nanoTime();
            for (int call = 0; call < calls; call++) {
                metrics.finishStep(1, metrics.startStep(1), 0, null);
            }
            rounds[round] = System.nanoTime() - started;
        }
        Arrays.sort(rounds);

        assertThat(rounds[rounds.length / 2] / calls).isLessThan(1_000);
    }

    @Test
    void snapshotsReportMetricStorageAndRealJvmResourcesWithoutWorkflowValues() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), 0, null);

        final RailixValue.ObjectValue snapshot = snapshot(metrics);

        assertThat(number(snapshot, "metric_counter_bytes")).isPositive();
        assertThat(number(object(snapshot, "process"), "uptime_millis")).isNotNegative();
        assertThat(number(object(snapshot, "process"), "heap_used_bytes")).isPositive();
        assertThat(snapshot.toString()).doesNotContain("payload", "context", "secret");
    }

    @Test
    void prometheusExportUsesCountersGaugesAndBoundedIdentityLabels() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishStep(1, metrics.startStep(1), 0, null);

        assertThat(prometheus(metrics))
                .contains(
                        "railix_step_executions_total{project=\"metrics-project\",step=\"enabled\"} 1",
                        "railix_step_duration_seconds_count{project=\"metrics-project\",step=\"enabled\"} 1",
                        "railix_process_heap_used_bytes{project=\"metrics-project\"} ",
                        "railix_metric_primitive_bytes{project=\"metrics-project\"} 172"
                )
                .doesNotContain("railix_step_in_flight", "disabled", "payload", "context");
    }

    @Test
    void influxExportUsesIntegerFieldsAndBoundedIdentityTags() {
        final DevelopmentRuntime.Metrics metrics = metrics();
        metrics.finishFlow(0, metrics.startFlow(0), new RunResult.Succeeded(RailixValue.object(Map.of())));

        assertThat(influx(metrics))
                .contains(
                        "railix_flow,project=metrics-project,flow=command ",
                        "executions=1i",
                        "railix_process,project=metrics-project ",
                        "metric_primitive_bytes=172i"
                )
                .doesNotContain("payload", "context");
    }

    @Test
    void maximumApplicationJsonStreamsBeyondOneMebibyteInBoundedWrites() throws Exception {
        final String[] steps = IntStream.range(0, 16_384)
                .mapToObj(index -> "step-" + index + "-" + "x".repeat(32))
                .toArray(String[]::new);
        final DevelopmentRuntime.Metrics metrics = new DevelopmentRuntime.Metrics(
                "large-metrics-project",
                new String[]{"command"},
                steps
        );
        final CountingOutput output = new CountingOutput();

        metrics.writeJson(output);

        assertThat(output.bytes).isGreaterThan(1_048_576);
        assertThat(output.maximumWrite).isLessThanOrEqualTo(16_384);
    }

    private static DevelopmentRuntime.Metrics metrics() {
        return new DevelopmentRuntime.Metrics(
                "metrics-project",
                new String[]{"command"},
                new String[]{"trigger", "enabled"}
        );
    }

    private static RunResult succeeded() {
        return new RunResult.Succeeded(RailixValue.object(Map.of()));
    }

    private static RailixValue.ObjectValue application(final DevelopmentRuntime.Metrics metrics) {
        return object(snapshot(metrics), "application");
    }

    private static RailixValue.ObjectValue flow(
            final DevelopmentRuntime.Metrics metrics,
            final String id
    ) {
        return values(snapshot(metrics), "flows").stream()
                .map(RailixValue.ObjectValue.class::cast)
                .filter(value -> id.equals(id(value)))
                .findFirst()
                .orElseThrow();
    }

    private static RailixValue.ObjectValue step(
            final DevelopmentRuntime.Metrics metrics,
            final String id
    ) {
        return steps(metrics).stream()
                .filter(value -> id.equals(id(value)))
                .findFirst()
                .orElseThrow();
    }

    private static List<RailixValue.ObjectValue> steps(final DevelopmentRuntime.Metrics metrics) {
        return values(snapshot(metrics), "steps").stream()
                .map(RailixValue.ObjectValue.class::cast)
                .toList();
    }

    private static String id(final RailixValue.ObjectValue value) {
        return ((RailixValue.StringValue) value.values().get("id")).value();
    }

    private static Map<String, Long> metric(final RailixValue.ObjectValue value) {
        final Map<String, Long> metric = new java.util.LinkedHashMap<>();
        object(value, "metrics").values().forEach((name, field) -> {
            if (field instanceof RailixValue.NumberValue number) {
                metric.put(name, number.value().longValueExact());
            }
        });
        return metric;
    }

    private static RailixValue.ObjectValue object(final RailixValue.ObjectValue value, final String name) {
        return (RailixValue.ObjectValue) value.values().get(name);
    }

    private static List<RailixValue> values(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.ArrayValue) value.values().get(name)).values();
    }

    private static long number(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.NumberValue) value.values().get(name)).value().longValueExact();
    }

    private static RailixValue.ObjectValue snapshot(final DevelopmentRuntime.Metrics metrics) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            metrics.writeJson(output);
        } catch (final IOException exception) {
            throw new AssertionError(exception);
        }
        final RailixJson.Result parsed = RailixJson.parse(output.toString(StandardCharsets.UTF_8));
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static String prometheus(final DevelopmentRuntime.Metrics metrics) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            metrics.writePrometheus(output);
        } catch (final IOException exception) {
            throw new AssertionError(exception);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String influx(final DevelopmentRuntime.Metrics metrics) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            metrics.writeInflux(output);
        } catch (final IOException exception) {
            throw new AssertionError(exception);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class CountingOutput extends OutputStream {
        private long bytes;
        private int maximumWrite;

        @Override
        public void write(final int value) {
            bytes++;
            maximumWrite = Math.max(maximumWrite, 1);
        }

        @Override
        public void write(final byte[] values, final int offset, final int length) {
            bytes += length;
            maximumWrite = Math.max(maximumWrite, length);
        }
    }
}
