package dev.nanonative.railix.railixstdtrigger;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardTriggerPackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledManualTriggerPack() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "manual-flow",
                "manual-trigger",
                List.of(new AppPlan.StepInvocation(
                        "manual-trigger",
                        StandardTriggerStepProvider.MANUAL_TRIGGER_USE,
                        Map.of()
                ))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:30:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-trigger-pack-1", tempDir, envelope("manual.dev", "manual"))
        );

        assertThat(record.outcome()).isEqualTo("triggered");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.trigger.kind")))
                .isEqualTo(new RailixValue.StringValue("manual"));
        assertThat(record.context().get(RailixPath.parse("ctx.payload")))
                .isEqualTo(envelope("manual.dev", "manual").payload());
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledCliTriggerPack() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path runsRoot = tempDir.resolve("runs");
        writeCliTriggerPlan(planFile);
        writeEnvelope(envelopeFile, envelope("cli.dev", "cli"));

        final Process process = new ProcessBuilder(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-trigger-module-path-1"
        )
                .redirectErrorStream(true)
                .start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("runId=run-trigger-module-path-1").contains("outcome=triggered");
        assertSuccessfulRunArtifacts(runsRoot.resolve("railix-app").resolve("cli-flow").resolve("run-trigger-module-path-1"));
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        return record.signalLog().readSignals(0, record.signalLog().signalCount());
    }

    private static void writeCliTriggerPlan(final Path planFile) throws Exception {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "cli-flow",
                "cli-trigger",
                List.of(new AppPlan.StepInvocation(
                        "cli-trigger",
                        StandardTriggerStepProvider.CLI_TRIGGER_USE,
                        Map.of()
                ))
        );
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(plan)));
    }

    private static void writeEnvelope(final Path envelopeFile, final Envelope envelope) throws Exception {
        Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope)));
    }

    @SuppressWarnings("unchecked")
    private static void assertSuccessfulRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", "triggered").containsEntry("executedSteps", 1);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"context.patched\"")
                .contains("\"step\":\"cli-trigger\"")
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
        final Path stdTriggerClasses = moduleBase.resolve("railix-std-trigger").resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdTriggerClasses;
    }

    private static Envelope envelope(final String source, final String protocol) {
        return new Envelope(
                source,
                protocol,
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of(
                        "actor", new RailixValue.StringValue("developer")
                )),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE, Reply.Mode.STREAM))
        );
    }
}
