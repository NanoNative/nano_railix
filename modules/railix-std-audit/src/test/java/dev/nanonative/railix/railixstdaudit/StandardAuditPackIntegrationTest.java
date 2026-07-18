package dev.nanonative.railix.railixstdaudit;

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

class StandardAuditPackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledStandardAuditPack() {
        final BuiltRailixApp app = new BuiltRailixApp(
                auditPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:30:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-audit-pack-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.audit.eventName")))
                .isEqualTo(new RailixValue.StringValue("customer.reviewed"));
        assertThat(record.context().get(RailixPath.parse("ctx.audit.emitted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "audit.emitted",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.AuditEmitted audit = (RunSignal.AuditEmitted) allSignals(record).get(2);
        assertThat(audit.eventName()).isEqualTo("customer.reviewed");
        assertThat(audit.data()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "actor", new RailixValue.StringValue("developer"),
                "channel", new RailixValue.StringValue("manual")
        )));
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledStandardAuditPack() throws Exception {
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
                "--run-id", "run-audit-module-path-1"
        )
                .redirectErrorStream(true)
                .start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("runId=run-audit-module-path-1").contains("outcome=ok");
        assertSuccessfulRunArtifacts(
                runsRoot.resolve("railix-app").resolve("audit-flow").resolve("run-audit-module-path-1")
        );
    }

    private static AppPlan auditPlan() {
        return new AppPlan(
                "railix-app",
                "audit-flow",
                "audit-step",
                List.of(new AppPlan.StepInvocation("audit-step", StandardAuditStepProvider.AUDIT_CUSTOM_USE, Map.of()))
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "audit", new RailixValue.ObjectValue(Map.of(
                                "eventName", new RailixValue.StringValue("customer.reviewed"),
                                "data", new RailixValue.ObjectValue(Map.of(
                                        "actor", new RailixValue.StringValue("developer"),
                                        "channel", new RailixValue.StringValue("manual")
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static void writePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(auditPlan())));
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
                .contains("\"type\":\"audit.emitted\"")
                .contains("\"eventName\":\"customer.reviewed\"")
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
        final Path stdAuditClasses = moduleBase.resolve("railix-std-audit").resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdAuditClasses;
    }
}
