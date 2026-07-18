package dev.nanonative.railix.railixstdfile;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StandardFilePackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledStandardFilePack() throws Exception {
        final Path fileRoot = tempDir.resolve("files");
        final BuiltRailixApp app = new BuiltRailixApp(
                filePlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:30:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-file-pack-1", tempDir, envelope(), settingsTree(fileRoot))
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(3);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.file.result")))
                .isEqualTo(new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.StringValue("yuna"),
                        "status", new RailixValue.StringValue("ok")
                )));
        final RailixValue.FileRef fileRef = (RailixValue.FileRef) record.context().get(RailixPath.parse("ctx.file.ref"));
        assertThat(fileRef.path()).isEqualTo("reports/result.json");
        assertThat(fileRef.mediaType()).isEqualTo("application/json");
        assertThat(fileRef.digest()).startsWith("sha256:");
        assertThat(fileRef.size()).isPositive();
        assertThat(record.context().get(RailixPath.parse("ctx.file.deleted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(Files.exists(fileRoot.resolve("reports").resolve("result.json"))).isFalse();
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "setting.read",
                "permission.decided",
                "permission.decided",
                "resource.created",
                "context.patched",
                "step.finished",
                "step.started",
                "permission.decided",
                "setting.read",
                "permission.decided",
                "context.patched",
                "step.finished",
                "step.started",
                "permission.decided",
                "setting.read",
                "permission.decided",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.ResourceCreated resourceCreated = (RunSignal.ResourceCreated) allSignals(record).get(6);
        assertThat(resourceCreated.refType()).isEqualTo("file");
        assertThat(resourceCreated.metadata().values()).containsEntry("path", new RailixValue.StringValue("reports/result.json"));
        assertThat(resourceCreated.metadata().values()).containsEntry("mediaType", new RailixValue.StringValue("application/json"));
        final List<RunSignal.PermissionDecided> permissionDecisions = allSignals(record).stream()
                .filter(RunSignal.PermissionDecided.class::isInstance)
                .map(RunSignal.PermissionDecided.class::cast)
                .toList();
        assertThat(permissionDecisions).hasSize(7);
        assertThat(permissionDecisions.stream().map(RunSignal.PermissionDecided::reason).collect(Collectors.toSet()))
                .containsExactly("permission-granted-by-plan");
    }

    @Test
    void shouldFailInstalledStandardFilePackWithoutPlanPermissions() {
        final Path fileRoot = tempDir.resolve("files-denied");
        final BuiltRailixApp app = new BuiltRailixApp(
                filePlanWithoutPermissions(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:35:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-file-pack-denied-1", tempDir, envelope(), settingsTree(fileRoot))
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.executedSteps()).isZero();
        assertThat(Files.exists(fileRoot.resolve("reports").resolve("result.json"))).isFalse();
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.failed",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("settings.read");
        assertThat(permissionDecided.reason()).isEqualTo("permission-not-granted");
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledStandardFilePack() throws Exception {
        final Path fileRoot = tempDir.resolve("module-files");
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path settingsFile = tempDir.resolve("settings.json");
        final Path runsRoot = tempDir.resolve("runs");
        writePlan(planFile);
        writeEnvelope(envelopeFile);
        writeSettings(settingsFile, fileRoot);

        final Process process = new ProcessBuilder(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--settings", settingsFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-file-module-path-1"
        )
                .redirectErrorStream(true)
                .start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("runId=run-file-module-path-1").contains("outcome=ok");
        assertThat(Files.exists(fileRoot.resolve("reports").resolve("result.json"))).isFalse();
        assertSuccessfulRunArtifacts(
                runsRoot.resolve("railix-app").resolve("file-flow").resolve("run-file-module-path-1"),
                fileRoot
        );
    }

    private static AppPlan filePlan() {
        return new AppPlan(
                "railix-app",
                "file-flow",
                "write-step",
                new PermissionSet(
                        Map.of(),
                        Map.of(
                                "settings.read", List.of("settings.file.rootDir"),
                                "resource.create", List.of("file/*"),
                                "resource.read", List.of("file/*"),
                                "resource.write", List.of("file/*")
                        ),
                        List.of()
                ),
                List.of(
                        new AppPlan.StepInvocation("write-step", StandardFileStepProvider.FILE_WRITE_USE, Map.of("ok", "read-step")),
                        new AppPlan.StepInvocation("read-step", StandardFileStepProvider.FILE_READ_USE, Map.of("ok", "delete-step", "miss", "delete-step")),
                        new AppPlan.StepInvocation("delete-step", StandardFileStepProvider.FILE_DELETE_USE, Map.of())
                )
        );
    }

    private static AppPlan filePlanWithoutPermissions() {
        return new AppPlan(
                "railix-app",
                "file-flow",
                "write-step",
                List.of(
                        new AppPlan.StepInvocation("write-step", StandardFileStepProvider.FILE_WRITE_USE, Map.of("ok", "read-step")),
                        new AppPlan.StepInvocation("read-step", StandardFileStepProvider.FILE_READ_USE, Map.of("ok", "delete-step", "miss", "delete-step")),
                        new AppPlan.StepInvocation("delete-step", StandardFileStepProvider.FILE_DELETE_USE, Map.of())
                )
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "file", new RailixValue.ObjectValue(Map.of(
                                "path", new RailixValue.StringValue("reports/result.json"),
                                "value", new RailixValue.ObjectValue(Map.of(
                                        "customer", new RailixValue.StringValue("yuna"),
                                        "status", new RailixValue.StringValue("ok")
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static SettingsTree settingsTree(final Path fileRoot) {
        return new SettingsTree(
                "file-root",
                Map.of(
                        RailixPath.parse("settings.file.rootDir"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.file.rootDir"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue(fileRoot.toString())),
                                true,
                                false,
                                false,
                                "integration-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                )
                ,
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }

    private static void writePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(filePlan())));
    }

    private static void writeEnvelope(final Path envelopeFile) throws Exception {
        Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope())));
    }

    private static void writeSettings(final Path settingsFile, final Path fileRoot) throws Exception {
        Files.writeString(settingsFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingsTree(fileRoot))));
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        return record.signalLog().readSignals(0, record.signalLog().signalCount());
    }

    private static void assertSuccessfulRunArtifacts(final Path runFolder, final Path fileRoot) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", "ok").containsEntry("executedSteps", 3);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"resource.created\"")
                .contains("\"path\":{\"kind\":\"string\",\"value\":\"reports/result.json\"}")
                .contains("\"refType\":\"file\"")
                .doesNotContain(fileRoot.resolve("reports").resolve("result.json").toString())
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
        final Path stdFileClasses = moduleBase.resolve("railix-std-file").resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdFileClasses;
    }
}
