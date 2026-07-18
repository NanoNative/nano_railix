package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltRailixAppMainTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunPackBackedAppFromPlanEnvelopeAndRunsRoot() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));
        final Path settingsFile = writeJson("settings.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.settingsTree()));
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs").toString(),
                        "--settings", settingsFile.toString(),
                        "--run-id", "run-main-1"
                ),
                new PrintStream(stdout),
                new PrintStream(stderr),
                testAppLoader()
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.runId()).isEqualTo("run-main-1");
        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(stdout.toString()).contains("runId=run-main-1").contains("outcome=ok");
        assertThat(stderr.toString()).isEmpty();
        assertThat(Files.exists(Path.of(result.runFolder()).resolve("summary.json"))).isTrue();
        final Map<String, Object> summary = KernelContractCodec.parseStableJsonObject(Files.readString(Path.of(result.runFolder()).resolve("summary.json")));
        assertThat(summary).containsEntry("outcome", "ok").containsEntry("executedSteps", 1);
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson"))).contains("\"type\":\"setting.read\"");
    }

    @Test
    void shouldRunPackBackedAppFromYamlPlanEnvelopeAndRunsRoot() throws Exception {
        final Path planFile = tempDir.resolve("plan.railix.app.yaml");
        final Path envelopeFile = tempDir.resolve("envelope.yaml");
        Files.writeString(planFile, """
                app:
                  id: railix-yaml-app
                  name: YAML Launch
                  flows:
                    - id: yaml-flow
                      trigger: capture
                      steps:
                        - id: capture
                          use: test.bootstrap.capture
                          next: end
                """);
        Files.writeString(envelopeFile, """
                envelope:
                  source: manual.dev
                  protocol: manual
                  payload:
                    customer:
                      email: user@example.com
                  metadata:
                    actor: developer
                  replyChannel:
                    supported: true
                    modes: [immediate]
                """);

        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-yaml").toString(),
                        "--run-id", "run-main-yaml-1"
                ),
                new PrintStream(stdout),
                new PrintStream(stderr),
                testAppLoader()
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.runId()).isEqualTo("run-main-yaml-1");
        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(stdout.toString()).contains("runId=run-main-yaml-1").contains("outcome=ok");
        assertThat(stderr.toString()).isEmpty();
        assertThat(Files.exists(Path.of(result.runFolder()).resolve("summary.json"))).isTrue();
    }

    @Test
    void shouldGenerateRunIdWhenMissing() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-generated").toString()
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                testAppLoader()
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(result.runId()).startsWith("run-");
        assertThat(result.runFolder()).contains(result.runId());
    }

    @Test
    void shouldAutoLoadPackagedDefaultsWhenNoProfileOrExplicitSettingsAreProvided() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-defaults").toString(),
                        "--run-id", "run-main-defaults-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("packaged-default")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"setting.read\"");
    }

    @Test
    void shouldAutoLoadPackagedProfileDefaultsWhenProfileIsProvided() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-profile").toString(),
                        "--profile", "dev",
                        "--run-id", "run-main-profile-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("profile-dev")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"setting.read\"");
    }

    @Test
    void shouldOverlayPackagedDefaultsProfileAndExplicitSettingsInOrder() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));
        final Path settingsOverrideFile = writeJson("settings-override.json", KernelContractCodec.toUiModel(new dev.nanonative.railix.kernel.model.SettingsTree(
                "CLI overrides",
                Map.of(
                        dev.nanonative.railix.kernel.model.RailixPath.parse("settings.app.mode"),
                        new dev.nanonative.railix.kernel.model.SettingsTree.Entry(
                                dev.nanonative.railix.kernel.model.RailixPath.parse("settings.app.mode"),
                                "string",
                                new dev.nanonative.railix.kernel.model.SettingsTree.PlainValue(new dev.nanonative.railix.kernel.model.RailixValue.StringValue("file-override")),
                                true,
                                false,
                                false,
                                "settings-override.json",
                                dev.nanonative.railix.kernel.model.SettingsTree.Visibility.NORMAL,
                                dev.nanonative.railix.kernel.model.SettingsTree.Audit.NEVER,
                                dev.nanonative.railix.kernel.model.SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.Scope.APP),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.CLI_ARGS)
        )));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-layered").toString(),
                        "--profile", "dev",
                        "--settings", settingsOverrideFile.toString(),
                        "--run-id", "run-main-layered-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("file-override")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.exists(Path.of(result.runFolder()).resolve("signals.ndjson"))).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"step.finished\"")
                .contains("\"type\":\"reply.produced\"");
    }

    @Test
    void shouldOverlayEnvironmentOverridesAfterPackagedAndFileSettings() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));
        final Path settingsOverrideFile = writeJson("settings-override.json", KernelContractCodec.toUiModel(new dev.nanonative.railix.kernel.model.SettingsTree(
                "File overrides",
                Map.of(
                        dev.nanonative.railix.kernel.model.RailixPath.parse("settings.app.mode"),
                        new dev.nanonative.railix.kernel.model.SettingsTree.Entry(
                                dev.nanonative.railix.kernel.model.RailixPath.parse("settings.app.mode"),
                                "string",
                                new dev.nanonative.railix.kernel.model.SettingsTree.PlainValue(new dev.nanonative.railix.kernel.model.RailixValue.StringValue("file-override")),
                                true,
                                false,
                                false,
                                "settings-override.json",
                                dev.nanonative.railix.kernel.model.SettingsTree.Visibility.NORMAL,
                                dev.nanonative.railix.kernel.model.SettingsTree.Audit.NEVER,
                                dev.nanonative.railix.kernel.model.SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.Scope.APP),
                List.of(dev.nanonative.railix.kernel.model.SettingsTree.SourceLayer.CLI_ARGS)
        )));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-env").toString(),
                        "--profile", "dev",
                        "--settings", settingsOverrideFile.toString(),
                        "--run-id", "run-main-env-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("env-override"),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"setting.read\"");
    }

    @Test
    void shouldOverlayCliInlineOverridesAfterEnvironmentOverrides() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-cli-inline").toString(),
                        "--profile", "dev",
                        "--set", "settings.app.mode=cli-inline-override",
                        "--run-id", "run-main-cli-inline-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("cli-inline-override"),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"reply.produced\"");
    }

    @Test
    void shouldOverlaySystemPropertyOverridesAfterEnvironmentOverridesAndBeforeCliOverrides() throws Exception {
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE)));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-system-properties").toString(),
                        "--profile", "dev",
                        "--run-id", "run-main-system-properties-1"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                layeredSettingsAppLoader("property-override"),
                Map.of("RAILIX_SETTING__settings__app__mode", "env-override"),
                Map.of("railix.setting.settings.app.mode", "property-override")
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(Path.of(result.runFolder()).resolve("signals.ndjson")))
                .contains("\"type\":\"setting.read\"");
    }

    @Test
    void shouldRejectRepeatedProfileArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", "plan.json",
                        "--envelope", "envelope.json",
                        "--runs-root", "runs",
                        "--profile", "dev",
                        "--profile", "prod"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--profile");
    }

    @Test
    void shouldReportMissingPackagedProfileResource() {
        final Path planFile = tempDir.resolve("missing-profile-plan.json");
        final Path envelopeFile = tempDir.resolve("missing-profile-envelope.json");
        try {
            Files.writeString(planFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan(TestBootstrapStepProvider.USE))));
            Files.writeString(envelopeFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope())));
        } catch (final Exception exception) {
            throw new RuntimeException(exception);
        }

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-missing-profile").toString(),
                        "--profile", "missing"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("Classpath resource not found");
    }

    @Test
    void shouldRejectInvalidProfileSyntax() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", "plan.json",
                        "--envelope", "envelope.json",
                        "--runs-root", "runs",
                        "--profile", "../prod"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--profile");
    }

    @Test
    void shouldExitNonZeroBeforeRunWhenResolvedStepUseIsUnknown() throws Exception {
        final Path planFile = writeJson("broken-plan.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.appPlan("test.bootstrap.missing")));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(BuiltRailixAppLoaderTest.envelope()));
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", planFile.toString(),
                        "--envelope", envelopeFile.toString(),
                        "--runs-root", tempDir.resolve("runs-failed").toString(),
                        "--run-id", "run-main-failed"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr),
                testAppLoader()
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(stderr.toString()).contains("Failed to launch built app:");
        assertThat(result.runFolder()).isEmpty();
    }

    @Test
    void shouldRejectMissingPlanArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--envelope", "envelope.json", "--runs-root", "runs"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--plan");
    }

    @Test
    void shouldRejectMissingEnvelopeArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--runs-root", "runs"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--envelope");
    }

    @Test
    void shouldRejectMissingRunsRootArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--envelope", "envelope.json"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--runs-root");
    }

    @Test
    void shouldRejectUnknownCliArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--envelope", "envelope.json", "--runs-root", "runs", "--mystery", "value"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--mystery");
    }

    @Test
    void shouldRejectRepeatedPlanArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan-a.json", "--plan", "plan-b.json", "--envelope", "envelope.json", "--runs-root", "runs"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--plan");
    }

    @Test
    void shouldRejectBlankRunIdArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--envelope", "envelope.json", "--runs-root", "runs", "--run-id", " "),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--run-id");
    }

    @Test
    void shouldRejectInvalidInlineSettingAssignment() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--envelope", "envelope.json", "--runs-root", "runs", "--set", "settings.app.mode"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--set");
    }

    @Test
    void shouldRejectDuplicateInlineSettingPaths() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", "plan.json",
                        "--envelope", "envelope.json",
                        "--runs-root", "runs",
                        "--set", "settings.app.mode=dev",
                        "--set", "settings.app.mode=prod"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("Duplicate --set path");
    }

    @Test
    void shouldRejectMissingValueForArgument() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("--plan", "plan.json", "--envelope"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("--envelope");
    }

    @Test
    void shouldRejectPositionalArguments() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of("plan.json"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("plan.json");
    }

    @Test
    void shouldReportMissingPlanFileReadFailure() {
        final BuiltRailixAppMain.LaunchResult result = BuiltRailixAppMain.launch(
                List.of(
                        "--plan", tempDir.resolve("missing-plan.json").toString(),
                        "--envelope", tempDir.resolve("missing-envelope.json").toString(),
                        "--runs-root", tempDir.resolve("runs").toString()
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.message()).contains("Failed to launch built app");
    }

    private Path writeJson(final String fileName, final Object model) throws Exception {
        final Path file = tempDir.resolve(fileName);
        Files.writeString(file, KernelContractCodec.toStableJson(model));
        return file;
    }

    private java.util.function.Function<String, BuiltRailixApp> testAppLoader() {
        final TestBootstrapStepProvider provider = new TestBootstrapStepProvider();
        return planLocation -> new BuiltRailixApp(
                BuiltRailixAppLoader.loadPlan(planLocation),
                new LocalExecutionKernel(),
                use -> provider.resolve(use).orElse(null)
        );
    }

    private java.util.function.Function<String, BuiltRailixApp> layeredSettingsAppLoader(final String expectedMode) {
        return planLocation -> new BuiltRailixApp(
                BuiltRailixAppLoader.loadPlan(planLocation),
                new LocalExecutionKernel(),
                use -> new Step() {
                    @Override
                    public dev.nanonative.railix.kernel.model.StepContract contract() {
                        return new TestBootstrapStepProvider().resolve(use).orElseThrow().contract();
                    }

                    @Override
                    public Result execute(final ExecutionInput input) {
                        final dev.nanonative.railix.kernel.model.RailixValue actual =
                                input.settings().require(dev.nanonative.railix.kernel.model.RailixPath.parse("settings.app.mode")).value();
                        assertThat(actual).isEqualTo(new dev.nanonative.railix.kernel.model.RailixValue.StringValue(expectedMode));
                        return new TestBootstrapStepProvider().resolve(use).orElseThrow().execute(input);
                    }
                }
        );
    }
}
