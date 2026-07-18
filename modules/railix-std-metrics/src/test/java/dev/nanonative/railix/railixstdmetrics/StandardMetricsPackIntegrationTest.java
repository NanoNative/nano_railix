package dev.nanonative.railix.railixstdmetrics;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixApp;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;
import dev.nanonative.railix.kernel.runtime.RunSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardMetricsPackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledStandardMetricsPack() {
        final BuiltRailixApp app = new BuiltRailixApp(
                metricPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:30:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-metrics-pack-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.metric.name")))
                .isEqualTo(new RailixValue.StringValue("railix.metric.value"));
        assertThat(record.context().get(RailixPath.parse("ctx.metric.unit")))
                .isEqualTo(new RailixValue.StringValue("count"));
        assertThat(record.context().get(RailixPath.parse("ctx.metric.emitted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "metric.emitted",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.MetricEmitted metric = (RunSignal.MetricEmitted) allSignals(record).get(2);
        assertThat(metric.name()).isEqualTo("railix.metric.value");
        assertThat(metric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(9)));
        assertThat(metric.unit()).isEqualTo("count");
        assertThat(metric.labels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "source", "checkout",
                "result", "success"
        ));
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledStandardMetricsPack() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path runsRoot = tempDir.resolve("runs");
        writePlan(planFile);
        writeEnvelope(envelopeFile);

        final Process process = new ProcessBuilder(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-metrics-module-path-1"
        )
                .redirectErrorStream(true)
                .start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("runId=run-metrics-module-path-1").contains("outcome=ok");
        assertSuccessfulRunArtifacts(
                runsRoot.resolve("railix-app").resolve("metrics-flow").resolve("run-metrics-module-path-1")
        );
    }

    private static AppPlan metricPlan() {
        return new AppPlan(
                "railix-app",
                "metrics-flow",
                "metric-step",
                List.of(new AppPlan.StepInvocation("metric-step", StandardMetricsStepProvider.METRIC_EMIT_USE, Map.of()))
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "metric", new RailixValue.ObjectValue(Map.of(
                                "value", new RailixValue.NumberValue(BigDecimal.valueOf(9)),
                                "source", new RailixValue.StringValue("checkout"),
                                "result", new RailixValue.StringValue("success")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static void writePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(metricPlan())));
    }

    private static void writeEnvelope(final Path envelopeFile) throws Exception {
        Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope())));
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        return record.signalLog().readSignals(0, record.signalLog().signalCount());
    }

    private static void assertSuccessfulRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", "ok").containsEntry("executedSteps", 1);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"metric.emitted\"")
                .contains("\"name\":\"railix.metric.value\"")
                .contains("\"type\":\"run.finished\"");
    }

    private static Path javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).normalize();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from " + System.getProperty("user.dir"));
    }

    private static String modulePath() {
        final Path moduleBase = repoRoot().resolve("modules");
        final Path kernelClasses = moduleBase.resolve("railix-kernel").resolve("target").resolve("classes");
        final Path stdMetricsClasses = moduleBase.resolve("railix-std-metrics").resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdMetricsClasses;
    }
}
