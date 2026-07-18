package dev.nanonative.railix.railixstdstore;

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

class StandardStorePackIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledStandardStorePack() {
        final Path storeRoot = tempDir.resolve("store-root");
        final BuiltRailixApp app = new BuiltRailixApp(
                writeReadDeletePlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:10:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(new LocalExecutionKernel.RunRequest(
                "run-store-pack-1",
                tempDir,
                envelope(),
                settingsTree(storeRoot)
        ));

        assertThat(record.outcome()).isEqualTo("miss");
        assertThat(record.executedSteps()).isEqualTo(6);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.store.deleted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(record.context().get(RailixPath.parse("ctx.store.result"))).isEqualTo(RailixValue.NULL);
        assertThat(record.context().get(RailixPath.parse("ctx.store.keys")))
                .isEqualTo(new RailixValue.ListValue(List.of(new RailixValue.StringValue("customer/u-123"))));
        assertThat(Files.exists(storeRoot.resolve("customer").resolve("u-123").resolve("entry.json"))).isFalse();
        assertThat(allSignals(record).stream().map(RunSignal::type)).contains(
                "run.started",
                "permission.decided",
                "setting.read",
                "context.patched",
                "run.finished"
        );
        final List<RunSignal.PermissionDecided> permissionDecisions = allSignals(record).stream()
                .filter(RunSignal.PermissionDecided.class::isInstance)
                .map(RunSignal.PermissionDecided.class::cast)
                .toList();
        assertThat(permissionDecisions).hasSize(12);
        assertThat(permissionDecisions.stream().map(RunSignal.PermissionDecided::reason).collect(Collectors.toSet()))
                .containsExactly("permission-granted-by-plan");
    }

    @Test
    void shouldFailInstalledStandardStorePackWithoutPlanPermissions() {
        final Path storeRoot = tempDir.resolve("store-root-denied");
        final BuiltRailixApp app = new BuiltRailixApp(
                writeReadDeletePlanWithoutPermissions(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T15:15:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(new LocalExecutionKernel.RunRequest(
                "run-store-pack-denied-1",
                tempDir,
                envelope(),
                settingsTree(storeRoot)
        ));

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.executedSteps()).isZero();
        assertThat(Files.exists(storeRoot.resolve("customer").resolve("u-123").resolve("entry.json"))).isFalse();
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
    void shouldRunLauncherThroughNamedModulePathWithInstalledStandardStorePack() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path settingsFile = tempDir.resolve("settings.json");
        final Path runsRoot = tempDir.resolve("runs");
        final Path storeRoot = tempDir.resolve("launcher-store-root");
        writePlan(planFile);
        writeEnvelope(envelopeFile);
        writeSettings(settingsFile, storeRoot);

        final Process process = new ProcessBuilder(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--settings", settingsFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-store-module-path-1"
        )
                .redirectErrorStream(true)
                .start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }

        assertThat(process.waitFor()).isZero();
        assertThat(output).contains("runId=run-store-module-path-1").contains("outcome=miss");
        assertSuccessfulRunArtifacts(
                runsRoot.resolve("railix-app").resolve("store-flow").resolve("run-store-module-path-1"),
                "miss"
        );
        assertThat(Files.exists(storeRoot.resolve("customer").resolve("u-123").resolve("entry.json"))).isFalse();
    }

    @Test
    void shouldRunJlinkPackagedLauncherThroughInstalledStandardStorePack() throws Exception {
        final Path planFile = tempDir.resolve("jlink-plan.json");
        final Path envelopeFile = tempDir.resolve("jlink-envelope.json");
        final Path settingsFile = tempDir.resolve("jlink-settings.json");
        final Path imageDir = tempDir.resolve("image");
        final Path runsRoot = tempDir.resolve("runs-jlink");
        final Path storeRoot = tempDir.resolve("jlink-store-root");
        writePlan(planFile);
        writeEnvelope(envelopeFile);
        writeSettings(settingsFile, storeRoot);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jlink-dev.sh").toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + imageDir);

        final String runOutput = runProcess(List.of(
                imageDir.resolve("bin").resolve("railix-app").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--settings", settingsFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-store-jlink-1"
        ));
        assertThat(runOutput).contains("runId=run-store-jlink-1").contains("outcome=miss");
        assertSuccessfulRunArtifacts(
                runsRoot.resolve("railix-app").resolve("store-flow").resolve("run-store-jlink-1"),
                "miss"
        );
        assertThat(Files.exists(storeRoot.resolve("customer").resolve("u-123").resolve("entry.json"))).isFalse();
    }

    private static AppPlan writeReadDeletePlan() {
        return new AppPlan(
                "railix-app",
                "store-flow",
                "write",
                new PermissionSet(
                        Map.of(),
                        Map.of(
                                "settings.read", List.of("settings.store.rootDir"),
                                "store.read", List.of("store/*"),
                                "store.write", List.of("store/*")
                        ),
                        List.of()
                ),
                List.of(
                        new AppPlan.StepInvocation("write", StandardStoreStepProvider.STORE_WRITE_USE, Map.of("ok", "exists")),
                        new AppPlan.StepInvocation("exists", StandardStoreStepProvider.STORE_EXISTS_USE, Map.of("ok", "read", "miss", "read")),
                        new AppPlan.StepInvocation("read", StandardStoreStepProvider.STORE_READ_USE, Map.of("ok", "list", "miss", "list")),
                        new AppPlan.StepInvocation("list", StandardStoreStepProvider.STORE_LIST_USE, Map.of("ok", "delete", "miss", "delete")),
                        new AppPlan.StepInvocation("delete", StandardStoreStepProvider.STORE_DELETE_USE, Map.of("ok", "read-after-delete", "miss", "read-after-delete")),
                        new AppPlan.StepInvocation("read-after-delete", StandardStoreStepProvider.STORE_READ_USE, Map.of())
                )
        );
    }

    private static AppPlan writeReadDeletePlanWithoutPermissions() {
        return new AppPlan(
                "railix-app",
                "store-flow",
                "write",
                List.of(
                        new AppPlan.StepInvocation("write", StandardStoreStepProvider.STORE_WRITE_USE, Map.of("ok", "exists")),
                        new AppPlan.StepInvocation("exists", StandardStoreStepProvider.STORE_EXISTS_USE, Map.of("ok", "read", "miss", "read")),
                        new AppPlan.StepInvocation("read", StandardStoreStepProvider.STORE_READ_USE, Map.of("ok", "list", "miss", "list")),
                        new AppPlan.StepInvocation("list", StandardStoreStepProvider.STORE_LIST_USE, Map.of("ok", "delete", "miss", "delete")),
                        new AppPlan.StepInvocation("delete", StandardStoreStepProvider.STORE_DELETE_USE, Map.of("ok", "read-after-delete", "miss", "read-after-delete")),
                        new AppPlan.StepInvocation("read-after-delete", StandardStoreStepProvider.STORE_READ_USE, Map.of())
                )
        );
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        return record.signalLog().readSignals(0, record.signalLog().signalCount());
    }

    private static void writePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(writeReadDeletePlan())));
    }

    private static void writeEnvelope(final Path envelopeFile) throws Exception {
        Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope())));
    }

    private static void writeSettings(final Path settingsFile, final Path storeRoot) throws Exception {
        Files.writeString(settingsFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingsTree(storeRoot))));
    }

    private static SettingsTree settingsTree(final Path storeRoot) {
        return new SettingsTree(
                "store-root",
                Map.of(
                        RailixPath.parse("settings.store.rootDir"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.store.rootDir"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue(storeRoot.toString())),
                                true,
                                false,
                                false,
                                "integration-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "store", new RailixValue.ObjectValue(Map.of(
                                "key", new RailixValue.StringValue("customer/u-123"),
                                "prefix", new RailixValue.StringValue("customer"),
                                "value", new RailixValue.ObjectValue(Map.of(
                                        "email", new RailixValue.StringValue("user@example.com"),
                                        "status", new RailixValue.StringValue("ACTIVE")
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static void assertSuccessfulRunArtifacts(final Path runFolder, final String expectedOutcome) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", expectedOutcome).containsEntry("executedSteps", 6);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"setting.read\"")
                .contains("\"step\":\"read-after-delete\"")
                .contains("\"type\":\"run.finished\"");
    }

    private record FailedProcess(int exitCode, String output) {
    }

    private static String runProcess(final List<String> command) throws Exception {
        final FailedProcess result = runProcessResult(command);
        assertThat(result.exitCode()).isZero();
        return result.output();
    }

    private static FailedProcess runProcessResult(final List<String> command) throws Exception {
        final ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repoRoot().toFile())
                .redirectErrorStream(true);
        final Process process = processBuilder.start();
        final String output;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            process.getInputStream().transferTo(buffer);
            output = buffer.toString();
        }
        return new FailedProcess(process.waitFor(), output);
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
        final Path stdStoreClasses = moduleBase.resolve("railix-std-store").resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdStoreClasses;
    }
}
