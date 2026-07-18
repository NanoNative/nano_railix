package dev.nanonative.railix.railixstddata;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixApp;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;
import dev.nanonative.railix.kernel.runtime.RunSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StandardDataPackIntegrationTest {
    private static final long PROCESS_TIMEOUT_SECONDS = 120;
    private static final long OUTPUT_DRAIN_GRACE_SECONDS = 2;

    @TempDir
    Path tempDir;

    @Test
    void shouldRunKernelFlowThroughInstalledStandardDataPack() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                List.of(new AppPlan.StepInvocation("capture", "std.data.capture-payload", Map.of()))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-pack-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.payload"))).isEqualTo(envelope().payload());
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
    void shouldRunKernelFlowThroughInstalledDataTransformPack() {
        final AppPlan plan = dataTransformPlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:02:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-transform-pack-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.context().get(RailixPath.parse("ctx.user.email")))
                .isEqualTo(new RailixValue.StringValue("user@example.com"));
        assertThat(record.context().get(RailixPath.parse("ctx.items")))
                .isEqualTo(expectedTransformedItems());
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "lastSku", new RailixValue.StringValue("C")
        )));
        assertThat(record.reply().metadata()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "normalizedEmail", new RailixValue.StringValue("user@example.com")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataValidatePackOnValidInput() {
        final AppPlan plan = dataValidatePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:03:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-validate-pack-valid-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("valid")
        )));
        assertThat(record.reply().status()).isSameAs(RailixValue.NULL);
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataValidatePackOnInvalidInput() {
        final AppPlan plan = dataValidatePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:04:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-validate-pack-invalid-1", tempDir, invalidValidationEnvelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().status()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(422)));
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("invalid")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataRoutePackOnApprovalBranch() {
        final AppPlan plan = dataRoutePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:05:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-route-pack-approval-1", tempDir, routeApprovalEnvelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(3);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "route", new RailixValue.StringValue("approval")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataRoutePackOnAutoBranch() {
        final AppPlan plan = dataRoutePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:06:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-route-pack-auto-1", tempDir, routeAutoEnvelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(3);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "route", new RailixValue.StringValue("auto")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataForEachPackOnPopulatedSelector() {
        final AppPlan plan = dataForEachPlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:06:30Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-foreach-pack-ok-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(8);
        assertThat(record.context().get(RailixPath.parse("ctx.firstSku")))
                .isEqualTo(new RailixValue.StringValue("A"));
        assertThat(record.context().get(RailixPath.parse("ctx.lastSku")))
                .isEqualTo(new RailixValue.StringValue("C"));
        assertThat(record.context().get(RailixPath.parse("ctx.lastOrderId")))
                .isEqualTo(new RailixValue.StringValue("order-2"));
        assertThat(record.context().get(RailixPath.parse("ctx.foreach"))).isEqualTo(RailixValue.NULL);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("foreached"),
                "firstSku", new RailixValue.StringValue("A"),
                "lastSku", new RailixValue.StringValue("C"),
                "lastOrderId", new RailixValue.StringValue("order-2")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataForEachPackOnEmptySelector() {
        final AppPlan plan = dataForEachPlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:06:45Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-foreach-pack-empty-1", tempDir, invalidValidationEnvelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.context().get(RailixPath.parse("ctx.foreach"))).isEqualTo(RailixValue.NULL);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("empty")
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataAggregatePackOnPopulatedSelector() {
        final AppPlan plan = dataAggregatePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:07:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-aggregate-pack-ok-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.context().get(RailixPath.parse("ctx.summary.itemCount")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(3)));
        assertThat(record.context().get(RailixPath.parse("ctx.summary.totalQty")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(6)));
        assertThat(record.context().get(RailixPath.parse("ctx.summary.minPrice")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2.5)));
        assertThat(record.context().get(RailixPath.parse("ctx.summary.maxPrice")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(4)));
        assertThat(record.context().get(RailixPath.parse("ctx.summary.skus"))).isEqualTo(new RailixValue.ListValue(List.of(
                new RailixValue.StringValue("A"),
                new RailixValue.StringValue("B"),
                new RailixValue.StringValue("C")
        )));
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("aggregated"),
                "itemCount", new RailixValue.NumberValue(BigDecimal.valueOf(3)),
                "totalQty", new RailixValue.NumberValue(BigDecimal.valueOf(6)),
                "minPrice", new RailixValue.NumberValue(BigDecimal.valueOf(2.5)),
                "maxPrice", new RailixValue.NumberValue(BigDecimal.valueOf(4)),
                "skus", new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("A"),
                        new RailixValue.StringValue("B"),
                        new RailixValue.StringValue("C")
                ))
        )));
    }

    @Test
    void shouldRunKernelFlowThroughInstalledDataAggregatePackOnEmptySelector() {
        final AppPlan plan = dataAggregatePlan();
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:07:15Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-aggregate-pack-empty-1", tempDir, invalidValidationEnvelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.context().get(RailixPath.parse("ctx.summary"))).isEqualTo(RailixValue.NULL);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("empty")
        )));
    }

    @Test
    void shouldFailInstalledSettingCaptureWithoutPlanPermission() {
        final BuiltRailixApp app = new BuiltRailixApp(
                settingCapturePlanWithoutPermissions(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:07:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver()
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest(
                        "run-setting-pack-denied-1",
                        tempDir,
                        envelope(),
                        settingsTree(
                                "Denied settings",
                                "denied-default",
                                "settings/denied.json",
                                dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.SETTINGS_FILE
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.executedSteps()).isZero();
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.failed",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("settings.read");
        assertThat(permissionDecided.resource()).isEqualTo("settings.app.mode");
        assertThat(permissionDecided.reason()).isEqualTo("permission-not-granted");
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledStandardDataPack() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path runsRoot = tempDir.resolve("runs");
        writePayloadCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-module-path-1"
        ));

        assertThat(output).contains("runId=run-module-path-1").contains("outcome=ok");
        assertSuccessfulRunArtifacts(runsRoot.resolve("railix-app").resolve("order-flow").resolve("run-module-path-1"));
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledDataTransformPack() throws Exception {
        final Path planFile = tempDir.resolve("transform-plan.json");
        final Path envelopeFile = tempDir.resolve("transform-envelope.json");
        final Path runsRoot = tempDir.resolve("runs-transform");
        writeDataTransformPlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-data-transform-module-1"
        ));

        assertThat(output).contains("runId=run-data-transform-module-1").contains("outcome=ok");
        assertSuccessfulDataTransformRunArtifacts(
                runsRoot.resolve("railix-app").resolve("transform-flow").resolve("run-data-transform-module-1"),
                "user@example.com",
                "\"ctx.items\""
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledDataValidatePackOnInvalidInput() throws Exception {
        final Path planFile = tempDir.resolve("validate-plan.json");
        final Path envelopeFile = tempDir.resolve("validate-envelope.json");
        final Path runsRoot = tempDir.resolve("runs-validate");
        writeDataValidatePlan(planFile);
        writeInvalidValidationInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-data-validate-module-1"
        ));

        assertThat(output).contains("runId=run-data-validate-module-1").contains("outcome=ok");
        assertSuccessfulDataValidateRunArtifacts(
                runsRoot.resolve("railix-app").resolve("validate-flow").resolve("run-data-validate-module-1")
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledDataRoutePackOnApprovalBranch() throws Exception {
        final Path planFile = tempDir.resolve("route-plan.json");
        final Path envelopeFile = tempDir.resolve("route-envelope.json");
        final Path runsRoot = tempDir.resolve("runs-route");
        writeDataRoutePlan(planFile);
        writeRouteApprovalInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-data-route-module-1"
        ));

        assertThat(output).contains("runId=run-data-route-module-1").contains("outcome=ok");
        assertSuccessfulDataRouteRunArtifacts(
                runsRoot.resolve("railix-app").resolve("route-flow").resolve("run-data-route-module-1"),
                "\"outcome\":\"approval\""
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledDataForEachPackOnPopulatedSelector() throws Exception {
        final Path planFile = tempDir.resolve("foreach-plan.json");
        final Path envelopeFile = tempDir.resolve("foreach-envelope.json");
        final Path runsRoot = tempDir.resolve("runs-foreach");
        writeDataForEachPlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-data-foreach-module-1"
        ));

        assertThat(output).contains("runId=run-data-foreach-module-1").contains("outcome=ok");
        assertSuccessfulDataForEachRunArtifacts(
                runsRoot.resolve("railix-app").resolve("foreach-flow").resolve("run-data-foreach-module-1")
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithInstalledDataAggregatePackOnPopulatedSelector() throws Exception {
        final Path planFile = tempDir.resolve("aggregate-plan.json");
        final Path envelopeFile = tempDir.resolve("aggregate-envelope.json");
        final Path runsRoot = tempDir.resolve("runs-aggregate");
        writeDataAggregatePlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-data-aggregate-module-1"
        ));

        assertThat(output).contains("runId=run-data-aggregate-module-1").contains("outcome=ok");
        assertSuccessfulDataAggregateRunArtifacts(
                runsRoot.resolve("railix-app").resolve("aggregate-flow").resolve("run-data-aggregate-module-1")
        );
    }

    @Test
    @Timeout(5)
    void shouldCollectOutputWhenParentExitsBeforeInheritedStdoutPipeCloses() throws Exception {
        final FailedProcess result = runProcessResult(
                List.of("sh", "-c", "printf 'parent-finished\\n'; sleep 30 & exit 0"),
                Map.of()
        );

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("parent-finished");
    }

    @Test
    void shouldBuildJlinkImageScriptAndRunInstalledStandardDataPackLauncher() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path imageDir = tempDir.resolve("image");
        final Path runsRoot = tempDir.resolve("runs-jlink-script");
        writePayloadCapturePlan(planFile);
        writeInputs(envelopeFile);

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
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-jlink-script-1"
        ));
        assertThat(runOutput).contains("runId=run-jlink-script-1").contains("outcome=ok");
        assertSuccessfulRunArtifacts(runsRoot.resolve("railix-app").resolve("order-flow").resolve("run-jlink-script-1"));
    }

    @Test
    void shouldBuildJpackageAppImageScriptAndRunInstalledStandardDataPackLauncher() throws Exception {
        final Path planFile = tempDir.resolve("plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path imageDir = tempDir.resolve("image");
        final Path packageDir = tempDir.resolve("package");
        final Path runsRoot = tempDir.resolve("runs-jpackage-script");
        writePayloadCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                packageDir.toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-jpackage-script-1"
        ));
        assertThat(runOutput).contains("runId=run-jpackage-script-1").contains("outcome=ok");
        assertSuccessfulRunArtifacts(runsRoot.resolve("railix-app").resolve("order-flow").resolve("run-jpackage-script-1"));
    }

    @Test
    void shouldBuildJlinkImageThroughRailixPackageCommandWithBuilderEmittedPackagedDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-driver-jlink.json");
        final Path envelopeFile = tempDir.resolve("envelope-driver-jlink.json");
        final Path imageDir = tempDir.resolve("driver-jlink-image");
        final Path reportFile = tempDir.resolve("driver-jlink-report.json");
        final Path runsRoot = tempDir.resolve("runs-driver-jlink");
        final Path packagedSettingsDir = tempDir.resolve("driver-jlink-settings");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "driver-default", "driver-profile-dev");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--type", "jlink",
                "--dest", imageDir.toString(),
                "--report", reportFile.toString(),
                "--defaults", packagedSettingsDir.resolve("defaults.json").toString(),
                "--packaged-profile", "dev=" + packagedSettingsDir.resolve("profiles").resolve("dev.json")
        ));
        assertThat(buildOutput).contains("Created " + imageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                imageDir.resolve("bin").resolve("railix-app").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-driver-jlink-1"
        ));
        assertThat(runOutput).contains("runId=run-driver-jlink-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-driver-jlink-1"),
                "driver-default"
        );
        assertPackageReport(
                reportFile,
                "jlink",
                "RailixII",
                imageDir,
                imageDir.resolve("bin").resolve("railix-app"),
                imageDir,
                null,
                null,
                true,
                List.of("dev")
        );
    }

    @Test
    void shouldBuildJpackageAppImageThroughRailixPackageCommandWithBuilderEmittedPackagedProfileDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-driver-app-image.json");
        final Path envelopeFile = tempDir.resolve("envelope-driver-app-image.json");
        final Path imageDir = tempDir.resolve("driver-app-image-runtime");
        final Path packageDir = tempDir.resolve("driver-app-image-package");
        final Path reportFile = tempDir.resolve("driver-app-image-report.json");
        final Path runsRoot = tempDir.resolve("runs-driver-app-image");
        final Path packagedSettingsDir = tempDir.resolve("driver-app-image-settings");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "driver-default", "driver-profile-dev");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--type", "app-image",
                "--dest", packageDir.toString(),
                "--report", reportFile.toString(),
                "--runtime-image", imageDir.toString(),
                "--defaults", packagedSettingsDir.resolve("defaults.json").toString(),
                "--packaged-profile", "dev=" + packagedSettingsDir.resolve("profiles").resolve("dev.json")
        ));
        assertThat(buildOutput).contains("Created " + packageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-driver-app-image-1"
        ));
        assertThat(runOutput).contains("runId=run-driver-app-image-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-driver-app-image-1"),
                "driver-profile-dev"
        );
        assertPackageReport(
                reportFile,
                "app-image",
                "RailixII",
                packageDir,
                packagedLauncher(packageDir, "RailixII"),
                imageDir,
                appImagePath(packageDir, "RailixII"),
                null,
                true,
                List.of("dev")
        );
    }

    @Test
    void shouldBuildJpackageAppImageThroughRailixPackageCommandByDefault() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-driver-app-image-default.json");
        final Path envelopeFile = tempDir.resolve("envelope-driver-app-image-default.json");
        final Path imageDir = tempDir.resolve("driver-app-image-default-runtime");
        final Path packageDir = tempDir.resolve("driver-app-image-default-package");
        final Path reportFile = tempDir.resolve("driver-app-image-default-report.json");
        final Path runsRoot = tempDir.resolve("runs-driver-app-image-default");
        final Path packagedSettingsDir = tempDir.resolve("driver-app-image-default-settings");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "driver-default", "driver-profile-dev");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--dest", packageDir.toString(),
                "--report", reportFile.toString(),
                "--runtime-image", imageDir.toString(),
                "--defaults", packagedSettingsDir.resolve("defaults.json").toString(),
                "--packaged-profile", "dev=" + packagedSettingsDir.resolve("profiles").resolve("dev.json")
        ));
        assertThat(buildOutput).contains("Created " + packageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-driver-app-image-default-1"
        ));
        assertThat(runOutput).contains("runId=run-driver-app-image-default-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-driver-app-image-default-1"),
                "driver-profile-dev"
        );
        assertPackageReport(
                reportFile,
                "app-image",
                "RailixII",
                packageDir,
                packagedLauncher(packageDir, "RailixII"),
                imageDir,
                appImagePath(packageDir, "RailixII"),
                null,
                true,
                List.of("dev")
        );
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void shouldBuildMacPkgInstallerArtifactFromPackagedLauncher() throws Exception {
        final Path appImageDir = tempDir.resolve("pkg-app-image");
        final Path installerDir = tempDir.resolve("pkg-installer");
        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-pkg.sh").toString(),
                installerDir.toString(),
                appImageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + installerDir);

        final Path pkgFile = firstPkgFile(installerDir);
        assertThat(Files.size(pkgFile)).isPositive();

        final String payloadFiles = runProcess(List.of(
                "pkgutil",
                "--payload-files",
                pkgFile.toString()
        ));
        assertThat(payloadFiles)
                .contains("./RailixII.app/Contents/MacOS/RailixII")
                .contains("./RailixII.app/Contents/runtime/Contents/Home/bin/railix-app");

        final Path expandedDir = tempDir.resolve("pkg-expanded");
        runProcess(List.of(
                "pkgutil",
                "--expand",
                pkgFile.toString(),
                expandedDir.toString()
        ));

        assertThat(Files.exists(expandedDir.resolve("Distribution"))).isTrue();
        assertThat(Files.exists(expandedDir.resolve("RailixII-app.pkg").resolve("PackageInfo"))).isTrue();
        assertThat(Files.exists(expandedDir.resolve("RailixII-app.pkg").resolve("Payload"))).isTrue();

        final String distribution = Files.readString(expandedDir.resolve("Distribution"));
        final String packageInfo = Files.readString(expandedDir.resolve("RailixII-app.pkg").resolve("PackageInfo"));
        assertThat(distribution)
                .contains("<title>RailixII</title>")
                .contains("path=\"RailixII.app\"");
        assertThat(packageInfo)
                .contains("install-location=\"/Applications\"")
                .contains("path=\"./RailixII.app\"");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void shouldBuildMacPkgInstallerArtifactFromBuilderEmittedPackagedSettings() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-pkg-builder-profile.json");
        final Path envelopeFile = tempDir.resolve("envelope-pkg-builder-profile.json");
        final Path appImageDir = tempDir.resolve("pkg-builder-app-image");
        final Path installerDir = tempDir.resolve("pkg-builder-installer");
        final Path runsRoot = tempDir.resolve("runs-pkg-builder-profile");
        final Path packagedSettingsDir = tempDir.resolve("packaged-settings-pkg");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "builder-default", "builder-profile-dev");

        final String buildOutput = runProcess(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("package").resolve("jpackage-pkg.sh").toString(),
                        installerDir.toString(),
                        appImageDir.toString()
                ),
                Map.of("APP_SETTINGS_DIR", packagedSettingsDir.toString())
        );
        assertThat(buildOutput).contains("Created " + installerDir);

        final String runOutput = runProcess(List.of(
                packagedLauncher(appImageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-pkg-builder-profile-1"
        ));
        assertThat(runOutput).contains("runId=run-pkg-builder-profile-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-pkg-builder-profile-1"),
                "builder-profile-dev"
        );

        final Path pkgFile = firstPkgFile(installerDir);
        assertThat(Files.size(pkgFile)).isPositive();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void shouldBuildMacPkgThroughRailixPackageCommandWithBuilderEmittedPackagedProfileDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-driver-pkg.json");
        final Path envelopeFile = tempDir.resolve("envelope-driver-pkg.json");
        final Path appImageDir = tempDir.resolve("driver-pkg-app-image");
        final Path installerDir = tempDir.resolve("driver-pkg-installer");
        final Path reportFile = tempDir.resolve("driver-pkg-report.json");
        final Path runsRoot = tempDir.resolve("runs-driver-pkg");
        final Path packagedSettingsDir = tempDir.resolve("driver-pkg-settings");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "driver-default", "driver-profile-dev");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--type", "pkg",
                "--dest", installerDir.toString(),
                "--report", reportFile.toString(),
                "--app-image-dir", appImageDir.toString(),
                "--defaults", packagedSettingsDir.resolve("defaults.json").toString(),
                "--packaged-profile", "dev=" + packagedSettingsDir.resolve("profiles").resolve("dev.json")
        ));
        assertThat(buildOutput).contains("Created " + installerDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                packagedLauncher(appImageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-driver-pkg-1"
        ));
        assertThat(runOutput).contains("runId=run-driver-pkg-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-driver-pkg-1"),
                "driver-profile-dev"
        );

        final Path pkgFile = firstPkgFile(installerDir);
        assertThat(Files.size(pkgFile)).isPositive();
        assertPackageReport(
                reportFile,
                "pkg",
                "RailixII",
                installerDir,
                packagedLauncher(appImageDir, "RailixII"),
                null,
                appImagePath(appImageDir, "RailixII"),
                pkgFile,
                true,
                List.of("dev")
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithPackagedDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path runsRoot = tempDir.resolve("runs-layered-module-path");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(List.of(
                javaBinary().toString(),
                "--module-path", modulePath(),
                "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-module-path-layered-1"
        ));

        assertThat(output).contains("runId=run-module-path-layered-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-module-path-layered-1"),
                "packaged-default"
        );
    }

    @Test
    void shouldBuildJlinkImageScriptAndRunLauncherWithPackagedProfileDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path imageDir = tempDir.resolve("image-layered");
        final Path runsRoot = tempDir.resolve("runs-layered-jlink");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

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
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-jlink-layered-1"
        ));
        assertThat(runOutput).contains("runId=run-jlink-layered-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jlink-layered-1"),
                "profile-dev"
        );
    }

    @Test
    void shouldBuildJlinkImageScriptWithBuilderEmittedPackagedDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-builder-defaults.json");
        final Path envelopeFile = tempDir.resolve("envelope-builder-defaults.json");
        final Path imageDir = tempDir.resolve("image-builder-defaults");
        final Path runsRoot = tempDir.resolve("runs-builder-defaults-jlink");
        final Path packagedSettingsDir = tempDir.resolve("packaged-settings-jlink");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "builder-default", "builder-profile-dev");

        final String buildOutput = runProcess(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("package").resolve("jlink-dev.sh").toString(),
                        imageDir.toString()
                ),
                Map.of("APP_SETTINGS_DIR", packagedSettingsDir.toString())
        );
        assertThat(buildOutput).contains("Created " + imageDir);

        final String runOutput = runProcess(List.of(
                imageDir.resolve("bin").resolve("railix-app").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-jlink-builder-defaults-1"
        ));
        assertThat(runOutput).contains("runId=run-jlink-builder-defaults-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jlink-builder-defaults-1"),
                "builder-default"
        );
    }

    @Test
    void shouldRejectJlinkImageScriptWhenBuilderEmittedPackagedSettingsDirIsEmpty() throws Exception {
        final Path imageDir = tempDir.resolve("image-empty-builder-settings");
        final Path packagedSettingsDir = tempDir.resolve("packaged-settings-empty");
        Files.createDirectories(packagedSettingsDir);

        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("package").resolve("jlink-dev.sh").toString(),
                        imageDir.toString()
                ),
                Map.of("APP_SETTINGS_DIR", packagedSettingsDir.toString())
        );
        assertThat(failedProcess.output())
                .contains("APP_SETTINGS_DIR must contain defaults.json and/or profiles/*.json");
    }

    @Test
    void shouldRejectRailixPackageCommandWhenPackagedProfileMappingIsMalformed() throws Exception {
        final Path imageDir = tempDir.resolve("driver-invalid-profile-image");
        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--type", "jlink",
                        "--dest", imageDir.toString(),
                        "--packaged-profile", "dev"
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("Invalid --packaged-profile mapping: dev");
    }

    @Test
    void shouldNotWritePackageReportWhenRailixPackageCommandFails() throws Exception {
        final Path imageDir = tempDir.resolve("driver-invalid-profile-report-image");
        final Path reportFile = tempDir.resolve("driver-invalid-profile-report.json");
        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--dest", imageDir.toString(),
                        "--report", reportFile.toString(),
                        "--packaged-profile", "dev"
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("Invalid --packaged-profile mapping: dev");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldBuildJpackageAppImageThroughRailixPackageCommandFromAppSpecAndProfile() throws Exception {
        final Path appDir = tempDir.resolve("spec-app-image-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of("dev"), true);
        writePackagedSettingsDir(appDir.resolve("settings"), "spec-default", "spec-profile-dev");

        final Path planFile = tempDir.resolve("settings-plan-spec-app-image.json");
        final Path envelopeFile = tempDir.resolve("envelope-spec-app-image.json");
        final Path imageDir = tempDir.resolve("spec-app-image-runtime");
        final Path packageDir = tempDir.resolve("spec-app-image-package");
        final Path reportFile = tempDir.resolve("spec-app-image-report.json");
        final Path runsRoot = tempDir.resolve("runs-spec-app-image");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--app", appSpecFile.toString(),
                "--profile", "dev",
                "--dest", packageDir.toString(),
                "--runtime-image", imageDir.toString(),
                "--report", reportFile.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "SpecDrivenTool").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-spec-app-image-1"
        ));
        assertThat(runOutput).contains("runId=run-spec-app-image-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-spec-app-image-1"),
                "spec-profile-dev"
        );
        assertPackageReport(
                reportFile,
                "app-image",
                "SpecDrivenTool",
                packageDir,
                packagedLauncher(packageDir, "SpecDrivenTool"),
                imageDir,
                appImagePath(packageDir, "SpecDrivenTool"),
                null,
                true,
                List.of("dev")
        );
    }

    @Test
    void shouldBuildJlinkImageThroughRailixPackageCommandFromAppSpecDefaults() throws Exception {
        final Path appDir = tempDir.resolve("spec-jlink-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of("dev"), true);
        writePackagedSettingsDir(appDir.resolve("settings"), "spec-default", "spec-profile-dev");

        final Path planFile = tempDir.resolve("settings-plan-spec-jlink.json");
        final Path envelopeFile = tempDir.resolve("envelope-spec-jlink.json");
        final Path imageDir = tempDir.resolve("spec-jlink-image");
        final Path reportFile = tempDir.resolve("spec-jlink-report.json");
        final Path runsRoot = tempDir.resolve("runs-spec-jlink");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--app", appSpecFile.toString(),
                "--type", "jlink",
                "--dest", imageDir.toString(),
                "--report", reportFile.toString()
        ));
        assertThat(buildOutput).contains("Created " + imageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                imageDir.resolve("bin").resolve("railix-app").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--run-id", "run-spec-jlink-1"
        ));
        assertThat(runOutput).contains("runId=run-spec-jlink-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-spec-jlink-1"),
                "spec-default"
        );
        assertPackageReport(
                reportFile,
                "jlink",
                "SpecDrivenTool",
                imageDir,
                imageDir.resolve("bin").resolve("railix-app"),
                imageDir,
                null,
                null,
                true,
                List.of("dev")
        );
    }

    @Test
    void shouldBuildJpackageAppImageThroughRailixPackageCommandFromAppSpecWithAllDeclaredProfiles() throws Exception {
        final Path appDir = tempDir.resolve("spec-all-profiles-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of("dev", "prod"), true);
        final Path packagedSettingsDir = appDir.resolve("settings");
        writePackagedDefaults(packagedSettingsDir, "spec-default");
        writePackagedProfile(packagedSettingsDir, "dev", "spec-profile-dev");
        writePackagedProfile(packagedSettingsDir, "prod", "spec-profile-prod");

        final Path planFile = tempDir.resolve("settings-plan-spec-all-profiles.json");
        final Path envelopeFile = tempDir.resolve("envelope-spec-all-profiles.json");
        final Path imageDir = tempDir.resolve("spec-all-profiles-runtime");
        final Path packageDir = tempDir.resolve("spec-all-profiles-package");
        final Path reportFile = tempDir.resolve("spec-all-profiles-report.json");
        final Path runsRoot = tempDir.resolve("runs-spec-all-profiles");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--app", appSpecFile.toString(),
                "--dest", packageDir.toString(),
                "--runtime-image", imageDir.toString(),
                "--report", reportFile.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir).contains("Wrote " + reportFile);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "SpecDrivenTool").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "prod",
                "--run-id", "run-spec-all-profiles-1"
        ));
        assertThat(runOutput).contains("runId=run-spec-all-profiles-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-spec-all-profiles-1"),
                "spec-profile-prod"
        );
        assertPackageReport(
                reportFile,
                "app-image",
                "SpecDrivenTool",
                packageDir,
                packagedLauncher(packageDir, "SpecDrivenTool"),
                imageDir,
                appImagePath(packageDir, "SpecDrivenTool"),
                null,
                true,
                List.of("dev", "prod")
        );
    }

    @Test
    void shouldRejectRailixPackageCommandWhenRequestedAppProfileIsNotDeclared() throws Exception {
        final Path appDir = tempDir.resolve("spec-invalid-profile-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of("dev"), true);
        writePackagedSettingsDir(appDir.resolve("settings"), "spec-default", "spec-profile-dev");

        final Path packageDir = tempDir.resolve("spec-invalid-profile-package");
        final Path reportFile = tempDir.resolve("spec-invalid-profile-report.json");

        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--app", appSpecFile.toString(),
                        "--profile", "prod",
                        "--dest", packageDir.toString(),
                        "--report", reportFile.toString()
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("App profile not declared");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldRejectRailixPackageCommandWhenAppSpecDefaultsFileDoesNotExist() throws Exception {
        final Path appDir = tempDir.resolve("spec-missing-defaults-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of(), true);
        final Path imageDir = tempDir.resolve("spec-missing-defaults-image");
        final Path reportFile = tempDir.resolve("spec-missing-defaults-report.json");

        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--app", appSpecFile.toString(),
                        "--type", "jlink",
                        "--dest", imageDir.toString(),
                        "--report", reportFile.toString()
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("Packaged defaults file not found");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldRejectRailixPackageCommandWhenDeclaredAppProfileFileIsMissing() throws Exception {
        final Path appDir = tempDir.resolve("spec-missing-profile-file-app");
        final Path appSpecFile = writePackagingAppSpec(appDir, "SpecDrivenTool", List.of("dev", "prod"), true);
        final Path packagedSettingsDir = appDir.resolve("settings");
        writePackagedDefaults(packagedSettingsDir, "spec-default");
        writePackagedProfile(packagedSettingsDir, "dev", "spec-profile-dev");

        final Path imageDir = tempDir.resolve("spec-missing-profile-file-image");
        final Path reportFile = tempDir.resolve("spec-missing-profile-file-report.json");

        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--app", appSpecFile.toString(),
                        "--type", "jlink",
                        "--dest", imageDir.toString(),
                        "--report", reportFile.toString()
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("Packaged profile file not found for prod");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldRejectRailixPackageCommandWhenAppDependencyModuleIsUnavailable() throws Exception {
        final Path appDir = tempDir.resolve("spec-missing-module-app");
        final Path appSpecFile = writePackagingAppSpec(
                appDir,
                "SpecDrivenTool",
                List.of(),
                false,
                List.of("railix.std.missing")
        );
        final Path imageDir = tempDir.resolve("spec-missing-module-image");
        final Path reportFile = tempDir.resolve("spec-missing-module-report.json");

        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--app", appSpecFile.toString(),
                        "--type", "jlink",
                        "--dest", imageDir.toString(),
                        "--report", reportFile.toString()
                ),
                Map.of()
        );
        assertThat(failedProcess.output()).contains("App dependency module not available for packaging: railix.std.missing");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldNotWritePackageReportWhenPackagerFailsAfterDispatch() throws Exception {
        final Path packageDir = tempDir.resolve("driver-packager-failure-package");
        final Path reportFile = tempDir.resolve("driver-packager-failure-report.json");
        final FailedProcess failedProcess = runProcessExpectFailure(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("railix.sh").toString(),
                        "package",
                        "--dest", packageDir.toString(),
                        "--report", reportFile.toString()
                ),
                Map.of("JPACKAGE_BIN", tempDir.resolve("missing-jpackage").toString())
        );
        assertThat(failedProcess.output()).contains("No such file or directory");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldWritePackageReportWithNoPackagedSettingsWhenNoneAreProvided() throws Exception {
        final Path reportFile = tempDir.resolve("driver-no-settings-report.json");
        final Path imageDir = tempDir.resolve("driver-no-settings-image");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--type", "jlink",
                "--dest", imageDir.toString(),
                "--report", reportFile.toString()
        ));
        assertThat(buildOutput).contains("Created " + imageDir).contains("Wrote " + reportFile);
        assertPackageReport(
                reportFile,
                "jlink",
                "RailixII",
                imageDir,
                imageDir.resolve("bin").resolve("railix-app"),
                imageDir,
                null,
                null,
                false,
                List.of()
        );
    }

    @Test
    void shouldBuildJpackageAppImageScriptAndRunLauncherWithPackagedProfileAndExplicitSettingsOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan.json");
        final Path envelopeFile = tempDir.resolve("envelope.json");
        final Path runtimeOverrideFile = tempDir.resolve("runtime-override.json");
        final Path imageDir = tempDir.resolve("image-layered");
        final Path packageDir = tempDir.resolve("package-layered");
        final Path runsRoot = tempDir.resolve("runs-layered-jpackage");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writeRuntimeOverrideSettings(runtimeOverrideFile, "runtime-override");

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                packageDir.toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--settings", runtimeOverrideFile.toString(),
                "--run-id", "run-jpackage-layered-1"
        ));
        assertThat(runOutput).contains("runId=run-jpackage-layered-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jpackage-layered-1"),
                "runtime-override"
        );
    }

    @Test
    void shouldBuildJpackageAppImageScriptWithBuilderEmittedPackagedProfileDefaults() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-builder-profile.json");
        final Path envelopeFile = tempDir.resolve("envelope-builder-profile.json");
        final Path imageDir = tempDir.resolve("image-builder-profile");
        final Path packageDir = tempDir.resolve("package-builder-profile");
        final Path runsRoot = tempDir.resolve("runs-builder-profile-jpackage");
        final Path packagedSettingsDir = tempDir.resolve("packaged-settings-jpackage");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);
        writePackagedSettingsDir(packagedSettingsDir, "builder-default", "builder-profile-dev");

        final String buildOutput = runProcess(
                List.of(
                        "sh",
                        repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                        packageDir.toString(),
                        imageDir.toString()
                ),
                Map.of("APP_SETTINGS_DIR", packagedSettingsDir.toString())
        );
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(List.of(
                packagedLauncher(packageDir, "RailixII").toString(),
                "--plan", planFile.toString(),
                "--envelope", envelopeFile.toString(),
                "--runs-root", runsRoot.toString(),
                "--profile", "dev",
                "--run-id", "run-jpackage-builder-profile-1"
        ));
        assertThat(runOutput).contains("runId=run-jpackage-builder-profile-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jpackage-builder-profile-1"),
                "builder-profile-dev"
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithEnvironmentOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-env.json");
        final Path envelopeFile = tempDir.resolve("envelope-env.json");
        final Path runsRoot = tempDir.resolve("runs-layered-module-path-env");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(
                List.of(
                        javaBinary().toString(),
                        "--module-path", modulePath(),
                        "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--run-id", "run-module-path-env-1"
                ),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );

        assertThat(output).contains("runId=run-module-path-env-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-module-path-env-1"),
                "env-override"
        );
    }

    @Test
    void shouldBuildJpackageAppImageScriptAndRunLauncherWithCliOverrideAfterEnvironmentOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-cli.json");
        final Path envelopeFile = tempDir.resolve("envelope-cli.json");
        final Path imageDir = tempDir.resolve("image-cli");
        final Path packageDir = tempDir.resolve("package-cli");
        final Path runsRoot = tempDir.resolve("runs-layered-jpackage-cli");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                packageDir.toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(
                List.of(
                        packagedLauncher(packageDir, "RailixII").toString(),
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--set", "settings.app.mode=cli-override",
                        "--run-id", "run-jpackage-cli-1"
                ),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );
        assertThat(runOutput).contains("runId=run-jpackage-cli-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jpackage-cli-1"),
                "cli-override"
        );
    }

    @Test
    void shouldBuildJpackageAppImageScriptAndRunLauncherWithJavaToolOptionsPropertyOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-java-tool-options.json");
        final Path envelopeFile = tempDir.resolve("envelope-java-tool-options.json");
        final Path imageDir = tempDir.resolve("image-java-tool-options");
        final Path packageDir = tempDir.resolve("package-java-tool-options");
        final Path runsRoot = tempDir.resolve("runs-layered-jpackage-java-tool-options");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                packageDir.toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(
                List.of(
                        packagedLauncher(packageDir, "RailixII").toString(),
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--run-id", "run-jpackage-java-tool-options-1"
                ),
                Map.of(
                        "RAILIX_SETTING__settings__app__mode", "env-override",
                        "JAVA_TOOL_OPTIONS", "-Drailix.setting.settings.app.mode=property-override"
                )
        );
        assertThat(runOutput).contains("runId=run-jpackage-java-tool-options-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jpackage-java-tool-options-1"),
                "property-override"
        );
    }

    @Test
    void shouldBuildJpackageAppImageScriptAndRunLauncherWithCliOverrideAfterJavaToolOptionsPropertyOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-java-tool-options-cli.json");
        final Path envelopeFile = tempDir.resolve("envelope-java-tool-options-cli.json");
        final Path imageDir = tempDir.resolve("image-java-tool-options-cli");
        final Path packageDir = tempDir.resolve("package-java-tool-options-cli");
        final Path runsRoot = tempDir.resolve("runs-layered-jpackage-java-tool-options-cli");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("package").resolve("jpackage-app-image.sh").toString(),
                packageDir.toString(),
                imageDir.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir);

        final String runOutput = runProcess(
                List.of(
                        packagedLauncher(packageDir, "RailixII").toString(),
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--set", "settings.app.mode=cli-override",
                        "--run-id", "run-jpackage-java-tool-options-cli-1"
                ),
                Map.of(
                        "RAILIX_SETTING__settings__app__mode", "env-override",
                        "JAVA_TOOL_OPTIONS", "-Drailix.setting.settings.app.mode=property-override"
                )
        );
        assertThat(runOutput).contains("runId=run-jpackage-java-tool-options-cli-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-jpackage-java-tool-options-cli-1"),
                "cli-override"
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithSystemPropertyOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-property.json");
        final Path envelopeFile = tempDir.resolve("envelope-property.json");
        final Path runsRoot = tempDir.resolve("runs-layered-module-path-property");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String output = runProcess(
                List.of(
                        javaBinary().toString(),
                        "-Drailix.setting.settings.app.mode=property-override",
                        "--module-path", modulePath(),
                        "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--run-id", "run-module-path-property-1"
                ),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );

        assertThat(output).contains("runId=run-module-path-property-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-module-path-property-1"),
                "property-override"
        );
    }

    @Test
    void shouldRunLauncherThroughNamedModulePathWithCliOverrideAfterSystemPropertyOverride() throws Exception {
        final Path planFile = tempDir.resolve("settings-plan-module-property-cli.json");
        final Path envelopeFile = tempDir.resolve("envelope-module-property-cli.json");
        final Path runsRoot = tempDir.resolve("runs-layered-module-path-property-cli");
        writeSettingCapturePlan(planFile);
        writeInputs(envelopeFile);

        final String runOutput = runProcess(
                List.of(
                        javaBinary().toString(),
                        "-Drailix.setting.settings.app.mode=property-override",
                        "--module-path", modulePath(),
                        "--module", "railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain",
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--profile", "dev",
                        "--set", "settings.app.mode=cli-override",
                        "--run-id", "run-module-path-property-cli-1"
                ),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );
        assertThat(runOutput).contains("runId=run-module-path-property-cli-1").contains("outcome=ok");
        assertSuccessfulSettingCaptureRunArtifacts(
                runsRoot.resolve("railix-app").resolve("settings-flow").resolve("run-module-path-property-cli-1"),
                "cli-override"
        );
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        final List<RunSignal> signals = new ArrayList<>();
        record.signalLog().forEachSignal(signals::add);
        return List.copyOf(signals);
    }

    private static void writeInputs(final Path envelopeFile) throws Exception {
        writeEnvelope(envelopeFile, envelope());
    }

    private static void writeInvalidValidationInputs(final Path envelopeFile) throws Exception {
        writeEnvelope(envelopeFile, invalidValidationEnvelope());
    }

    private static void writeRouteApprovalInputs(final Path envelopeFile) throws Exception {
        writeEnvelope(envelopeFile, routeApprovalEnvelope());
    }

    private static void writeRouteAutoInputs(final Path envelopeFile) throws Exception {
        writeEnvelope(envelopeFile, routeAutoEnvelope());
    }

    private static void writeEnvelope(final Path envelopeFile, final Envelope envelope) throws Exception {
        Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope)));
    }

    private static void writePayloadCapturePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                List.of(new AppPlan.StepInvocation("capture", "std.data.capture-payload", Map.of()))
        ))));
    }

    private static void writeDataTransformPlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(dataTransformPlan())));
    }

    private static void writeDataValidatePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(dataValidatePlan())));
    }

    private static void writeDataRoutePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(dataRoutePlan())));
    }

    private static void writeDataForEachPlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(dataForEachPlan())));
    }

    private static void writeDataAggregatePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(dataAggregatePlan())));
    }

    private static void writeSettingCapturePlan(final Path planFile) throws Exception {
        Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingCapturePlan())));
    }

    private static AppPlan settingCapturePlan() {
        return new AppPlan(
                "railix-app",
                "settings-flow",
                "capture-setting",
                new PermissionSet(
                        Map.of(),
                        Map.of("settings.read", List.of("settings.app.mode")),
                        List.of()
                ),
                List.of(new AppPlan.StepInvocation("capture-setting", "std.data.capture-setting", Map.of()))
        );
    }

    private static AppPlan dataTransformPlan() {
        return new AppPlan(
                "railix-app",
                "transform-flow",
                "transform",
                List.of(new AppPlan.StepInvocation(
                        "transform",
                        "railix.std.data.DataTransform",
                        new RailixValue.ObjectValue(Map.of(
                                "mappings", new RailixValue.ListValue(List.of(
                                        new RailixValue.ObjectValue(Map.of(
                                                "target", new RailixValue.StringValue("ctx.user.email"),
                                                "expression", new RailixValue.ObjectValue(Map.of(
                                                        "op", new RailixValue.StringValue("lower"),
                                                        "input", new RailixValue.ObjectValue(Map.of(
                                                                "op", new RailixValue.StringValue("trim"),
                                                                "input", new RailixValue.ObjectValue(Map.of(
                                                                        "path", new RailixValue.StringValue("payload.customer.email")
                                                                ))
                                                        ))
                                                ))
                                        )),
                                        new RailixValue.ObjectValue(Map.of(
                                                "repeat", new RailixValue.ObjectValue(Map.of(
                                                        "selector", new RailixValue.StringValue("payload.orders[*].items[*]"),
                                                        "as", new RailixValue.StringValue("item"),
                                                        "parentAliases", new RailixValue.ObjectValue(Map.of(
                                                                "order", new RailixValue.StringValue("payload.orders[*]")
                                                        )),
                                                        "mappings", new RailixValue.ListValue(List.of(
                                                                new RailixValue.ObjectValue(Map.of(
                                                                        "target", new RailixValue.StringValue("ctx.items[*].sku"),
                                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                                "path", new RailixValue.StringValue("item.sku")
                                                                        ))
                                                                )),
                                                                new RailixValue.ObjectValue(Map.of(
                                                                        "target", new RailixValue.StringValue("ctx.items[*].quantity"),
                                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                                "op", new RailixValue.StringValue("toInt"),
                                                                                "input", new RailixValue.ObjectValue(Map.of(
                                                                                        "path", new RailixValue.StringValue("item.qty")
                                                                                ))
                                                                        ))
                                                                )),
                                                                new RailixValue.ObjectValue(Map.of(
                                                                        "target", new RailixValue.StringValue("ctx.items[*].lineTotal"),
                                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                                "op", new RailixValue.StringValue("multiply"),
                                                                                "left", new RailixValue.ObjectValue(Map.of(
                                                                                        "path", new RailixValue.StringValue("item.qty")
                                                                                )),
                                                                                "right", new RailixValue.ObjectValue(Map.of(
                                                                                        "path", new RailixValue.StringValue("item.price")
                                                                                ))
                                                                        ))
                                                                )),
                                                                new RailixValue.ObjectValue(Map.of(
                                                                        "target", new RailixValue.StringValue("ctx.items[*].orderId"),
                                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                                "path", new RailixValue.StringValue("order.id")
                                                                        ))
                                                                ))
                                                        ))
                                                ))
                                        )),
                                        new RailixValue.ObjectValue(Map.of(
                                                "target", new RailixValue.StringValue("reply.mode"),
                                                "expression", new RailixValue.ObjectValue(Map.of(
                                                        "const", new RailixValue.StringValue("immediate")
                                                ))
                                        )),
                                        new RailixValue.ObjectValue(Map.of(
                                                "target", new RailixValue.StringValue("reply.metadata.normalizedEmail"),
                                                "expression", new RailixValue.ObjectValue(Map.of(
                                                        "path", new RailixValue.StringValue("ctx.user.email")
                                                ))
                                        )),
                                        new RailixValue.ObjectValue(Map.of(
                                                "target", new RailixValue.StringValue("reply.payload.lastSku"),
                                                "expression", new RailixValue.ObjectValue(Map.of(
                                                        "path", new RailixValue.StringValue("ctx.items[2].sku")
                                                ))
                                        ))
                                ))
                        )),
                        Map.of()
                ))
        );
    }

    private static AppPlan dataValidatePlan() {
        return new AppPlan(
                "railix-app",
                "validate-flow",
                "validate",
                List.of(
                        new AppPlan.StepInvocation(
                                "validate",
                                "railix.std.data.DataValidate",
                                new RailixValue.ObjectValue(Map.of(
                                        "required", new RailixValue.ListValue(List.of(
                                                new RailixValue.StringValue("payload.customer.email"),
                                                new RailixValue.StringValue("payload.orders[0].items[0].sku")
                                        ))
                                )),
                                Map.of(
                                        "valid", "build-valid-reply",
                                        "invalid", "build-invalid-reply"
                                )
                        ),
                        new AppPlan.StepInvocation(
                                "build-valid-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("valid")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        ),
                        new AppPlan.StepInvocation(
                                "build-invalid-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.NumberValue(BigDecimal.valueOf(422))
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("invalid")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        )
                )
        );
    }

    private static AppPlan dataRoutePlan() {
        return new AppPlan(
                "railix-app",
                "route-flow",
                "seed-total",
                List.of(
                        new AppPlan.StepInvocation(
                                "seed-total",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.order.total"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("payload.orderTotal")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of("*", "route")
                        ),
                        new AppPlan.StepInvocation(
                                "route",
                                "railix.std.data.DataRoute",
                                new RailixValue.ObjectValue(Map.of(
                                        "routes", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "outcome", new RailixValue.StringValue("approval"),
                                                        "when", new RailixValue.ObjectValue(Map.of(
                                                                "op", new RailixValue.StringValue("greaterThan"),
                                                                "left", new RailixValue.ObjectValue(Map.of(
                                                                        "path", new RailixValue.StringValue("ctx.order.total")
                                                                )),
                                                                "right", new RailixValue.ObjectValue(Map.of(
                                                                        "const", new RailixValue.NumberValue(BigDecimal.valueOf(1000))
                                                                ))
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "outcome", new RailixValue.StringValue("auto"),
                                                        "when", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.BoolValue(true)
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of(
                                        "approval", "build-approval-reply",
                                        "auto", "build-auto-reply"
                                )
                        ),
                        new AppPlan.StepInvocation(
                                "build-approval-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.route"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("approval")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        ),
                        new AppPlan.StepInvocation(
                                "build-auto-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.route"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("auto")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        )
                )
        );
    }

    private static AppPlan dataForEachPlan() {
        return new AppPlan(
                "railix-app",
                "foreach-flow",
                "foreach",
                List.of(
                        new AppPlan.StepInvocation(
                                "foreach",
                                "railix.std.data.DataForEach",
                                new RailixValue.ObjectValue(Map.of(
                                        "selector", new RailixValue.StringValue("payload.orders[*].items[*]"),
                                        "as", new RailixValue.StringValue("item"),
                                        "statePath", new RailixValue.StringValue("ctx.foreach"),
                                        "parentAliases", new RailixValue.ObjectValue(Map.of(
                                                "order", new RailixValue.StringValue("payload.orders[*]")
                                        ))
                                )),
                                Map.of(
                                        "item", "capture-current-item",
                                        "done", "build-foreach-reply",
                                        "empty", "build-empty-reply"
                                )
                        ),
                        new AppPlan.StepInvocation(
                                "capture-current-item",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.firstSku"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "op", new RailixValue.StringValue("default"),
                                                                "value", new RailixValue.ObjectValue(Map.of(
                                                                        "path", new RailixValue.StringValue("ctx.firstSku")
                                                                )),
                                                                "default", new RailixValue.ObjectValue(Map.of(
                                                                        "path", new RailixValue.StringValue("ctx.foreach.item.sku")
                                                                ))
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.lastSku"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.foreach.item.sku")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.lastOrderId"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.foreach.order.id")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of("*", "foreach")
                        ),
                        new AppPlan.StepInvocation(
                                "build-foreach-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("foreached")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.firstSku"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.firstSku")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.lastSku"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.lastSku")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.lastOrderId"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.lastOrderId")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        ),
                        new AppPlan.StepInvocation(
                                "build-empty-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("empty")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        )
                )
        );
    }

    private static AppPlan dataAggregatePlan() {
        return new AppPlan(
                "railix-app",
                "aggregate-flow",
                "aggregate",
                List.of(
                        new AppPlan.StepInvocation(
                                "aggregate",
                                "railix.std.data.DataAggregate",
                                new RailixValue.ObjectValue(Map.of(
                                        "selector", new RailixValue.StringValue("payload.orders[*].items[*]"),
                                        "as", new RailixValue.StringValue("item"),
                                        "aggregations", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.summary.itemCount"),
                                                        "op", new RailixValue.StringValue("count")
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.summary.totalQty"),
                                                        "op", new RailixValue.StringValue("sum"),
                                                        "path", new RailixValue.StringValue("item.qty")
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.summary.minPrice"),
                                                        "op", new RailixValue.StringValue("min"),
                                                        "path", new RailixValue.StringValue("item.price")
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.summary.maxPrice"),
                                                        "op", new RailixValue.StringValue("max"),
                                                        "path", new RailixValue.StringValue("item.price")
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("ctx.summary.skus"),
                                                        "op", new RailixValue.StringValue("collect"),
                                                        "path", new RailixValue.StringValue("item.sku")
                                                ))
                                        ))
                                )),
                                Map.of(
                                        "ok", "build-aggregate-reply",
                                        "empty", "build-empty-reply"
                                )
                        ),
                        new AppPlan.StepInvocation(
                                "build-aggregate-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("aggregated")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.itemCount"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.summary.itemCount")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.totalQty"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.summary.totalQty")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.minPrice"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.summary.minPrice")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.maxPrice"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.summary.maxPrice")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.skus"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "path", new RailixValue.StringValue("ctx.summary.skus")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        ),
                        new AppPlan.StepInvocation(
                                "build-empty-reply",
                                "railix.std.data.DataTransform",
                                new RailixValue.ObjectValue(Map.of(
                                        "mappings", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.mode"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("immediate")
                                                        ))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "target", new RailixValue.StringValue("reply.payload.status"),
                                                        "expression", new RailixValue.ObjectValue(Map.of(
                                                                "const", new RailixValue.StringValue("empty")
                                                        ))
                                                ))
                                        ))
                                )),
                                Map.of()
                        )
                )
        );
    }

    private static AppPlan settingCapturePlanWithoutPermissions() {
        return new AppPlan(
                "railix-app",
                "settings-flow",
                "capture-setting",
                List.of(new AppPlan.StepInvocation("capture-setting", "std.data.capture-setting", Map.of()))
        );
    }

    private static void writeRuntimeOverrideSettings(final Path runtimeOverrideFile, final String runtimeOverrideValue) throws Exception {
        Files.writeString(runtimeOverrideFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(new dev.nanonative.railix.kernel.model.SettingsTree(
                "Runtime override",
                Map.of(
                        RailixPath.parse("settings.app.mode"),
                        new dev.nanonative.railix.kernel.model.SettingsTree.Entry(
                                RailixPath.parse("settings.app.mode"),
                                "string",
                                new dev.nanonative.railix.kernel.model.SettingsTree.PlainValue(new RailixValue.StringValue(runtimeOverrideValue)),
                                true,
                                false,
                                false,
                                "runtime-override.json",
                                dev.nanonative.railix.kernel.model.SettingsTree.Visibility.NORMAL,
                                dev.nanonative.railix.kernel.model.SettingsTree.Audit.ON_READ,
                                dev.nanonative.railix.kernel.model.SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.Scope.APP),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.CLI_ARGS)
        ))));
    }

    private static void writePackagedSettingsDir(
            final Path packagedSettingsDir,
            final String defaultMode,
            final String profileMode
    ) throws Exception {
        writePackagedDefaults(packagedSettingsDir, defaultMode);
        writePackagedProfile(packagedSettingsDir, "dev", profileMode);
    }

    private static void writePackagedDefaults(final Path packagedSettingsDir, final String defaultMode) throws Exception {
        Files.createDirectories(packagedSettingsDir);
        Files.writeString(
                packagedSettingsDir.resolve("defaults.json"),
                KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingsTree(
                        "Builder packaged defaults",
                        defaultMode,
                        "builder/defaults.json",
                        dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.PACKAGED_DEFAULTS
                )))
        );
    }

    private static void writePackagedProfile(
            final Path packagedSettingsDir,
            final String profileName,
            final String profileMode
    ) throws Exception {
        Files.createDirectories(packagedSettingsDir.resolve("profiles"));
        Files.writeString(
                packagedSettingsDir.resolve("profiles").resolve(profileName + ".json"),
                KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingsTree(
                        "Builder packaged profile",
                        profileMode,
                        "builder/profiles/" + profileName + ".json",
                        dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.PACKAGED_DEFAULTS
                )))
        );
    }

    private static Path writePackagingAppSpec(
            final Path appDir,
            final String appName,
            final List<String> profiles,
            final boolean includeDefaultsSection
    ) throws Exception {
        return writePackagingAppSpec(appDir, appName, profiles, includeDefaultsSection, List.of("railix.std.data"));
    }

    private static Path writePackagingAppSpec(
            final Path appDir,
            final String appName,
            final List<String> profiles,
            final boolean includeDefaultsSection,
            final List<String> dependencyModules
    ) throws Exception {
        Files.createDirectories(appDir);
        final StringBuilder yaml = new StringBuilder()
                .append("app:\n")
                .append("  id: example.spec-driven\n")
                .append("  name: ").append(appName).append('\n')
                .append("  version: 0.1.0\n");
        if (!dependencyModules.isEmpty()) {
            yaml.append("  dependencies:\n");
            for (final String dependencyModule : dependencyModules) {
                yaml.append("    - id: ").append(dependencyModule).append('\n')
                        .append("      version: 0.1.0\n");
            }
        }
        if (!profiles.isEmpty()) {
            yaml.append("  profiles:\n");
            for (final String profile : profiles) {
                yaml.append("    - ").append(profile).append('\n');
            }
        }
        if (includeDefaultsSection) {
            yaml.append("  settings:\n")
                    .append("    defaults: settings/defaults.json\n");
        }
        final Path appSpecFile = appDir.resolve("railix.app.yaml");
        Files.writeString(appSpecFile, yaml.toString());
        return appSpecFile;
    }

    private static dev.nanonative.railix.kernel.model.SettingsTree settingsTree(
            final String description,
            final String mode,
            final String source,
            final dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer sourceLayer
    ) {
        return new dev.nanonative.railix.kernel.model.SettingsTree(
                description,
                Map.of(
                        RailixPath.parse("settings.app.mode"),
                        new dev.nanonative.railix.kernel.model.SettingsTree.Entry(
                                RailixPath.parse("settings.app.mode"),
                                "string",
                                new dev.nanonative.railix.kernel.model.SettingsTree.PlainValue(new RailixValue.StringValue(mode)),
                                true,
                                false,
                                false,
                                source,
                                dev.nanonative.railix.kernel.model.SettingsTree.Visibility.NORMAL,
                                dev.nanonative.railix.kernel.model.SettingsTree.Audit.ON_READ,
                                dev.nanonative.railix.kernel.model.SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.Scope.APP),
                List.of(sourceLayer)
        );
    }

    private static void assertSuccessfulRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", "ok").containsEntry("executedSteps", 1);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"context.patched\"")
                .contains("\"type\":\"run.finished\"");
    }

    private static void assertSuccessfulSettingCaptureRunArtifacts(final Path runFolder, final String expectedMode) throws Exception {
        assertSuccessfulRunArtifacts(runFolder);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"permission.decided\"")
                .contains("\"reason\":\"permission-granted-by-plan\"")
                .contains("\"type\":\"setting.read\"")
                .contains("\"mode\":\"immediate\"")
                .contains("resolvedMode")
                .contains(expectedMode);
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("replyMode", "immediate");
    }

    private static void assertSuccessfulDataTransformRunArtifacts(
            final Path runFolder,
            final String expectedEmail,
            final String expectedChangedPath
    ) throws Exception {
        assertSuccessfulRunArtifacts(runFolder);
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"type\":\"reply.produced\"")
                .contains(expectedEmail)
                .contains(expectedChangedPath)
                .contains("\"count\":{\"kind\":\"number\",\"value\":\"16\"}");
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary).containsEntry("replyMode", "immediate");
    }

    private static void assertSuccessfulDataValidateRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"step\":\"validate\"")
                .contains("\"outcome\":\"invalid\"")
                .contains("\"step\":\"build-invalid-reply\"")
                .contains("\"status\":{\"kind\":\"number\",\"value\":\"422\"}")
                .contains("\"type\":\"context.patched\"");
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary)
                .containsEntry("outcome", "ok")
                .containsEntry("executedSteps", 2)
                .containsEntry("replyMode", "immediate");
    }

    private static void assertSuccessfulDataRouteRunArtifacts(
            final Path runFolder,
            final String expectedRouteOutcome
    ) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"step\":\"route\"")
                .contains(expectedRouteOutcome)
                .contains("\"type\":\"reply.produced\"");
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary)
                .containsEntry("outcome", "ok")
                .containsEntry("executedSteps", 3)
                .containsEntry("replyMode", "immediate");
    }

    private static void assertSuccessfulDataForEachRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"step\":\"foreach\"")
                .contains("\"step\":\"capture-current-item\"")
                .contains("\"outcome\":\"item\"")
                .contains("\"outcome\":\"done\"")
                .contains("ctx.firstSku")
                .contains("ctx.lastSku")
                .contains("\"type\":\"reply.produced\"");
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary)
                .containsEntry("outcome", "ok")
                .containsEntry("executedSteps", 8)
                .containsEntry("replyMode", "immediate");
    }

    private static void assertSuccessfulDataAggregateRunArtifacts(final Path runFolder) throws Exception {
        assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
        assertThat(Files.exists(runFolder.resolve("signals.ndjson"))).isTrue();
        assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                .contains("\"step\":\"aggregate\"")
                .contains("\"outcome\":\"ok\"")
                .contains("\"changedPaths\":[\"ctx.summary\"]")
                .contains("\"count\":{\"kind\":\"number\",\"value\":\"5\"}")
                .contains("\"type\":\"reply.produced\"");
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json")));
        assertThat(summary)
                .containsEntry("outcome", "ok")
                .containsEntry("executedSteps", 2)
                .containsEntry("replyMode", "immediate");
    }

    private static RailixValue.ListValue expectedTransformedItems() {
        return new RailixValue.ListValue(List.of(
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("A"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.valueOf(2)),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                        "orderId", new RailixValue.StringValue("order-1")
                )),
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("B"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.ONE),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(2.5)),
                        "orderId", new RailixValue.StringValue("order-1")
                )),
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("C"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.valueOf(3)),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                        "orderId", new RailixValue.StringValue("order-2")
                ))
        ));
    }

    @SuppressWarnings("unchecked")
    private static void assertPackageReport(
            final Path reportFile,
            final String expectedType,
            final String expectedAppName,
            final Path expectedDestDir,
            final Path expectedLauncherPath,
            final Path expectedRuntimeImagePath,
            final Path expectedAppImagePath,
            final Path expectedInstallerPath,
            final boolean expectedDefaultsEmbedded,
            final List<String> expectedProfileNames
    ) throws Exception {
        assertThat(Files.exists(reportFile)).isTrue();
        final Map<String, Object> report = KernelContractCodec.parseStableJsonObject(Files.readString(reportFile));
        assertThat(report)
                .containsEntry("schemaVersion", 1)
                .containsEntry("type", expectedType)
                .containsEntry("mode", "headless")
                .containsEntry("appName", expectedAppName);
        assertThat(Path.of((String) report.get("destDir"))).isEqualTo(expectedDestDir);

        final Map<String, Object> artifacts = (Map<String, Object>) report.get("artifacts");
        assertThat(artifacts).isNotNull();
        assertPathOrNull(artifacts.get("launcherPath"), expectedLauncherPath);
        assertPathOrNull(artifacts.get("runtimeImageDir"), expectedRuntimeImagePath);
        assertPathOrNull(artifacts.get("appImageDir"), expectedAppImagePath);
        assertPathOrNull(artifacts.get("pkgFile"), expectedInstallerPath);

        final Map<String, Object> packagedSettings = (Map<String, Object>) report.get("embeddedSettings");
        assertThat(packagedSettings).isNotNull().containsEntry("defaultsEmbedded", expectedDefaultsEmbedded);
        assertThat((List<String>) packagedSettings.get("profileNames")).containsExactlyElementsOf(expectedProfileNames);
    }

    private static void assertPathOrNull(final Object actualValue, final Path expectedPath) {
        if (expectedPath == null) {
            assertThat(actualValue).isNull();
            return;
        }
        assertThat(actualValue).isInstanceOf(String.class);
        assertThat(Path.of((String) actualValue)).isEqualTo(expectedPath);
    }

    private record FailedProcess(int exitCode, String output) {
    }

    private static String runProcess(final List<String> command) throws Exception {
        return runProcess(command, Map.of());
    }

    private static String runProcess(final List<String> command, final Map<String, String> environmentOverrides) throws Exception {
        final FailedProcess result = runProcessResult(command, environmentOverrides);
        assertThat(result.exitCode())
                .withFailMessage(
                        "Expected exit code 0 for command %s but was %s. Output:%n%s",
                        command,
                        result.exitCode(),
                        result.output()
                )
                .isZero();
        return result.output();
    }

    private static FailedProcess runProcessExpectFailure(
            final List<String> command,
            final Map<String, String> environmentOverrides
    ) throws Exception {
        final FailedProcess result = runProcessResult(command, environmentOverrides);
        assertThat(result.exitCode())
                .withFailMessage(
                        "Expected non-zero exit code for command %s but got 0. Output:%n%s",
                        command,
                        result.output()
                )
                .isNotZero();
        return result;
    }

    private static FailedProcess runProcessResult(
            final List<String> command,
            final Map<String, String> environmentOverrides
    ) throws Exception {
        final ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repoRoot().toFile())
                .redirectErrorStream(true);
        processBuilder.environment().putAll(environmentOverrides);
        final Process process = processBuilder.start();
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final AtomicReference<IOException> readerFailure = new AtomicReference<>();
        final Thread outputReader = Thread.ofVirtual().name("std-data-process-output-reader").start(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(buffer);
            } catch (IOException exception) {
                if (process.isAlive()) {
                    readerFailure.set(exception);
                }
            }
        });

        final boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            destroyProcessTree(process);
            awaitOutputDrain(command, process, outputReader, buffer, true);
            throw new AssertionError("Process timed out after " + PROCESS_TIMEOUT_SECONDS + "s: " + command);
        }

        awaitOutputDrain(command, process, outputReader, buffer, false);
        final IOException exception = readerFailure.get();
        if (exception != null) {
            throw exception;
        }
        return new FailedProcess(process.exitValue(), buffer.toString(StandardCharsets.UTF_8));
    }

    private static void awaitOutputDrain(
            final List<String> command,
            final Process process,
            final Thread outputReader,
            final ByteArrayOutputStream buffer,
            final boolean forceClose
    ) throws Exception {
        outputReader.join(TimeUnit.SECONDS.toMillis(OUTPUT_DRAIN_GRACE_SECONDS));
        if (outputReader.isAlive() || forceClose) {
            process.getInputStream().close();
            outputReader.join(TimeUnit.SECONDS.toMillis(OUTPUT_DRAIN_GRACE_SECONDS));
        }
        if (outputReader.isAlive()) {
            throw new AssertionError(
                    "Process output did not drain after exit for command "
                            + command
                            + ". Partial output:\n"
                            + buffer.toString(StandardCharsets.UTF_8)
            );
        }
    }

    private static void destroyProcessTree(final Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static Path firstPkgFile(final Path installerDir) throws Exception {
        try (var files = Files.list(installerDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".pkg"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No .pkg file found in " + installerDir));
        }
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
        final Path workingDirectory = repoRoot().resolve("modules").resolve("railix-std-data");
        final Path moduleBase = Files.exists(workingDirectory.resolve("target").resolve("classes"))
                ? workingDirectory
                : repoRoot().resolve("modules").resolve("railix-std-data");
        final Path kernelClasses = moduleBase.resolveSibling("railix-kernel").resolve("target").resolve("classes");
        final Path stdDataClasses = moduleBase.resolve("target").resolve("classes");
        return kernelClasses + java.io.File.pathSeparator + stdDataClasses;
    }

    private static Path packagedLauncher(final Path packageDir, final String appName) {
        final String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("mac")) {
            return packageDir.resolve(appName + ".app").resolve("Contents").resolve("MacOS").resolve(appName);
        }
        if (osName.contains("win")) {
            return packageDir.resolve(appName).resolve(appName + ".exe");
        }
        return packageDir.resolve(appName).resolve("bin").resolve(appName);
    }

    private static Path appImagePath(final Path packageDir, final String appName) {
        final String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (osName.contains("mac")) {
            return packageDir.resolve(appName + ".app");
        }
        return packageDir.resolve(appName);
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        )),
                        "orders", new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of(
                                        "id", new RailixValue.StringValue("order-1"),
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("A"),
                                                        "qty", new RailixValue.StringValue("2"),
                                                        "price", new RailixValue.NumberValue(BigDecimal.valueOf(3.5))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("B"),
                                                        "qty", new RailixValue.NumberValue(BigDecimal.ONE),
                                                        "price", new RailixValue.StringValue("2.5")
                                                ))
                                        ))
                                )),
                                new RailixValue.ObjectValue(Map.of(
                                        "id", new RailixValue.StringValue("order-2"),
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("C"),
                                                        "qty", new RailixValue.StringValue("3"),
                                                        "price", new RailixValue.NumberValue(BigDecimal.valueOf(4))
                                                ))
                                        ))
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }

    private static Envelope invalidValidationEnvelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of()),
                        "orders", new RailixValue.ListValue(List.of())
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }

    private static Envelope routeApprovalEnvelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "orderTotal", new RailixValue.NumberValue(BigDecimal.valueOf(1500))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }

    private static Envelope routeAutoEnvelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "orderTotal", new RailixValue.NumberValue(BigDecimal.valueOf(100))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }
}
