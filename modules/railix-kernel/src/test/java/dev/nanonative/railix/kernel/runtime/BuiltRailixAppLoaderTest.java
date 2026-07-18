package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltRailixAppLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadAppPlanEnvelopeAndSettingsTreeFromPersistedJson() throws Exception {
        final AppPlan appPlan = appPlan(TestBootstrapStepProvider.USE);
        final Envelope envelope = envelope();
        final SettingsTree settingsTree = settingsTree();
        final Path planFile = writeJson("plan.json", KernelContractCodec.toUiModel(appPlan));
        final Path envelopeFile = writeJson("envelope.json", KernelContractCodec.toUiModel(envelope));
        final Path settingsFile = writeJson("settings.json", KernelContractCodec.toUiModel(settingsTree));

        assertThat(BuiltRailixAppLoader.loadPlan(planFile.toString())).isEqualTo(appPlan);
        assertThat(BuiltRailixAppLoader.loadEnvelope(envelopeFile.toString())).isEqualTo(envelope);
        assertThat(BuiltRailixAppLoader.loadSettingsTree(settingsFile.toString())).isEqualTo(settingsTree);
    }

    @Test
    void shouldLoadAppPlanWithStepConfigFromPersistedJson() throws Exception {
        final AppPlan appPlan = new AppPlan(
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
                                                        "path", new RailixValue.StringValue("payload.customer.email")
                                                ))
                                        ))
                                ))
                        )),
                        Map.of()
                ))
        );
        final Path planFile = writeJson("plan-with-config.json", KernelContractCodec.toUiModel(appPlan));

        assertThat(BuiltRailixAppLoader.loadPlan(planFile.toString())).isEqualTo(appPlan);
    }

    @Test
    void shouldLoadAppPlanFromAuthoringYamlSpec() throws Exception {
        final Path planFile = tempDir.resolve("order-approval.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.order-approval
                  name: Order Approval
                  version: 0.1.0
                  dependencies:
                    - { id: railix.std.trigger, version: 0.1.0 }
                    - { id: railix.std.data, version: 0.1.0 }
                  flows:
                    - id: process-order
                      trigger: manual-order
                      steps:
                        - id: manual-order
                          use: railix.std.trigger.ManualTrigger
                          next: normalize-items
                        - id: normalize-items
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: ctx.customer.email
                                expression: { path: payload.customer.email }
                              - repeat:
                                  selector: payload.orders[*].items[*]
                                  as: item
                                  parentAliases:
                                    order: payload.orders[*]
                                  mappings:
                                    - target: ctx.items[*].sku
                                      expression: { path: item.sku }
                                    - target: ctx.items[*].quantity
                                      expression: { path: item.qty }
                                    - target: ctx.items[*].lineTotal
                                      expression:
                                        op: multiply
                                        left: { path: item.qty }
                                        right: { path: item.price }
                                    - target: ctx.items[*].orderId
                                      expression: { path: order.id }
                          next: aggregate-total
                        - id: aggregate-total
                          use: railix.std.data.DataAggregate
                          config:
                            selector: ctx.items[*]
                            aggregations:
                              - op: sum
                                source: item.lineTotal
                                target: ctx.order.total
                          next: route-approval
                        - id: route-approval
                          use: railix.std.data.DataRoute
                          config:
                            routes:
                              - outcome: approval
                                when:
                                  op: greaterThan
                                  left: { path: ctx.order.total }
                                  right: { const: 1000 }
                              - outcome: auto
                                when: { const: true }
                          next:
                            approval: build-approval-reply
                            auto: build-auto-reply
                        - id: build-approval-reply
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: reply.mode
                                expression: { const: immediate }
                              - target: reply.payload.decision
                                expression: { const: approval }
                          next: end
                        - id: build-auto-reply
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: reply.mode
                                expression: { const: immediate }
                              - target: reply.payload.decision
                                expression: { const: auto }
                          next: end
                """);

        final AppPlan appPlan = BuiltRailixAppLoader.loadPlan(planFile.toString());

        assertThat(appPlan.appId()).isEqualTo("example.order-approval");
        assertThat(appPlan.flowId()).isEqualTo("process-order");
        assertThat(appPlan.triggerStepId()).isEqualTo("manual-order");
        assertThat(appPlan.permissions()).isEqualTo(PermissionSet.none());
        assertThat(appPlan.steps()).hasSize(6);
        assertThat(appPlan.step("manual-order").next()).containsExactly(Map.entry("*", "normalize-items"));
        assertThat(appPlan.step("normalize-items").next()).containsExactly(Map.entry("*", "aggregate-total"));
        assertThat(appPlan.step("aggregate-total").next()).containsExactly(Map.entry("*", "route-approval"));
        assertThat(appPlan.step("route-approval").next())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "approval", "build-approval-reply",
                        "auto", "build-auto-reply"
                ));
        assertThat(appPlan.nextStepId("route-approval", "approval")).contains("build-approval-reply");
        assertThat(appPlan.nextStepId("route-approval", "auto")).contains("build-auto-reply");
        assertThat(appPlan.nextStepId("build-auto-reply", "ok")).isEmpty();

        final RailixValue.ListValue mappings = (RailixValue.ListValue) appPlan.step("normalize-items").config().values().get("mappings");
        assertThat(mappings.values()).hasSize(2);
        assertThat(((RailixValue.ObjectValue) mappings.values().getFirst()).values())
                .containsEntry("target", new RailixValue.StringValue("ctx.customer.email"))
                .containsEntry("expression", new RailixValue.ObjectValue(Map.of(
                        "path", new RailixValue.StringValue("payload.customer.email")
                )));
        final RailixValue.ObjectValue repeatMapping = (RailixValue.ObjectValue) mappings.values().get(1);
        final RailixValue.ObjectValue repeatConfig = (RailixValue.ObjectValue) repeatMapping.values().get("repeat");
        assertThat(repeatConfig.values())
                .containsEntry("selector", new RailixValue.StringValue("payload.orders[*].items[*]"))
                .containsEntry("as", new RailixValue.StringValue("item"))
                .containsEntry("parentAliases", new RailixValue.ObjectValue(Map.of(
                        "order", new RailixValue.StringValue("payload.orders[*]")
                )));
        final RailixValue.ListValue repeatMappings = (RailixValue.ListValue) repeatConfig.values().get("mappings");
        assertThat(repeatMappings.values()).hasSize(4);
        assertThat(((RailixValue.ObjectValue) repeatMappings.values().get(2)).values())
                .containsEntry("target", new RailixValue.StringValue("ctx.items[*].lineTotal"));
        assertThat(((RailixValue.ObjectValue) ((RailixValue.ObjectValue) repeatMappings.values().get(2)).values().get("expression")).values())
                .containsEntry("op", new RailixValue.StringValue("multiply"))
                .containsEntry("left", new RailixValue.ObjectValue(Map.of(
                        "path", new RailixValue.StringValue("item.qty")
                )))
                .containsEntry("right", new RailixValue.ObjectValue(Map.of(
                        "path", new RailixValue.StringValue("item.price")
                )));

        final RailixValue.ListValue routes = (RailixValue.ListValue) appPlan.step("route-approval").config().values().get("routes");
        assertThat(routes.values()).hasSize(2);
        assertThat(((RailixValue.ObjectValue) routes.values().getFirst()).values())
                .containsEntry("outcome", new RailixValue.StringValue("approval"));
        assertThat(((RailixValue.ObjectValue) ((RailixValue.ObjectValue) routes.values().getFirst()).values().get("when")).values())
                .containsEntry("op", new RailixValue.StringValue("greaterThan"))
                .containsEntry("right", new RailixValue.ObjectValue(Map.of(
                        "const", new RailixValue.NumberValue(new BigDecimal("1000"))
                )));
        assertThat(((RailixValue.ObjectValue) ((RailixValue.ObjectValue) routes.values().get(1)).values().get("when")).values())
                .containsEntry("const", new RailixValue.BoolValue(true));
    }

    @Test
    void shouldLoadOrderedConnectionsAndOperatorsFromAuthoringYaml() throws Exception {
        final Path planFile = tempDir.resolve("connections.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.connections
                  flows:
                    - id: normalize
                      trigger: emit
                      steps:
                        - id: emit
                          use: test.emit
                          next: consume
                        - id: consume
                          use: test.consume
                          next: end
                      connections:
                        - id: normalize-email
                          from: { stepId: emit, port: email }
                          to: { stepId: consume, port: value }
                          operators:
                            - use: railix.op.string.trim
                              config: {}
                            - use: railix.op.string.lowercase
                """);

        final AppPlan plan = BuiltRailixAppLoader.loadPlan(planFile.toString());

        assertThat(plan.connections()).containsExactly(new AppPlan.Connection(
                "normalize-email",
                new AppPlan.PortRef("emit", "email"),
                new AppPlan.PortRef("consume", "value"),
                List.of(
                        new AppPlan.OperatorInvocation(
                                "railix.op.string.trim",
                                new RailixValue.ObjectValue(Map.of())
                        ),
                        new AppPlan.OperatorInvocation(
                                "railix.op.string.lowercase",
                                new RailixValue.ObjectValue(Map.of())
                        )
                )
        ));
    }

    @Test
    void shouldLoadConnectionWithoutOperatorPipelineFromAuthoringYaml() throws Exception {
        final Path planFile = tempDir.resolve("direct-connection.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.connections
                  flows:
                    - id: direct
                      trigger: emit
                      steps:
                        - id: emit
                          use: test.emit
                          next: consume
                        - id: consume
                          use: test.consume
                      connections:
                        - id: direct
                          from: { stepId: emit, port: value }
                          to: { stepId: consume, port: value }
                """);

        final AppPlan plan = BuiltRailixAppLoader.loadPlan(planFile.toString());

        assertThat(plan.connections().getFirst().operators()).isEmpty();
    }

    @Test
    void shouldRejectNonListConnectionsInAuthoringYaml() throws Exception {
        final Path planFile = tempDir.resolve("invalid-connections.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.connections
                  flows:
                    - id: invalid
                      trigger: emit
                      steps:
                        - id: emit
                          use: test.emit
                      connections: invalid
                """);

        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(planFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.flows[0].connections must be a list");
    }

    @Test
    void shouldRejectNonListConnectionOperatorsInAuthoringYaml() throws Exception {
        final Path planFile = tempDir.resolve("invalid-operators.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.connections
                  flows:
                    - id: invalid
                      trigger: emit
                      steps:
                        - id: emit
                          use: test.emit
                          next: consume
                        - id: consume
                          use: test.consume
                      connections:
                        - id: invalid
                          from: { stepId: emit, port: value }
                          to: { stepId: consume, port: value }
                          operators: invalid
                """);

        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(planFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection.operators must be a list");
    }

    @Test
    void shouldNormalizeYamlScalarsCollectionsAndReferences() throws Exception {
        final Path envelopeFile = tempDir.resolve("normalized.envelope.yaml");
        Files.writeString(envelopeFile, """
                # scalar and collection normalization

                envelope:
                  source: 'manual.dev'
                  protocol: "manual"
                  payload:
                    disabled: false
                    absent: null
                    ratio: 1.25
                    emptyList: []
                    emptyMap: {}
                    escaped: "line\\nnext\\rrow\\tend"
                  metadata: {}
                  refs: { note: "quoted, value" }
                """);

        final Envelope envelope = BuiltRailixAppLoader.loadEnvelope(envelopeFile.toString());

        assertThat(envelope.payload().values())
                .containsEntry("disabled", new RailixValue.BoolValue(false))
                .containsEntry("absent", RailixValue.NULL)
                .containsEntry("ratio", new RailixValue.NumberValue(new BigDecimal("1.25")))
                .containsEntry("emptyList", new RailixValue.ListValue(List.of()))
                .containsEntry("emptyMap", new RailixValue.ObjectValue(Map.of()))
                .containsEntry("escaped", new RailixValue.StringValue("line\nnext\rrow\tend"));
        assertThat(envelope.refs()).containsEntry("note", new RailixValue.StringValue("quoted, value"));
        assertThat(envelope.replyChannel()).isEqualTo(new Envelope.ReplyChannel(false, List.of()));
    }

    @ParameterizedTest(name = "rejects malformed YAML: {0}")
    @MethodSource("malformedYamlDocuments")
    void shouldRejectMalformedYamlDeterministically(
            final String caseName,
            final String yaml,
            final String expectedMessage
    ) throws Exception {
        final Path file = tempDir.resolve(caseName + ".railix.app.yaml");
        Files.writeString(file, yaml);

        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void shouldLoadEnvelopeFromYamlExample() throws Exception {
        final Path envelopeFile = tempDir.resolve("request.envelope.yaml");
        Files.writeString(envelopeFile, """
                envelope:
                  source: http.default
                  protocol: http
                  payload:
                    customer:
                      email: " USER@EXAMPLE.COM "
                  metadata:
                    method: POST
                    path: /users
                    headers:
                      content-type: application/json
                  replyChannel:
                    supported: true
                    modes: [immediate, file, stream, deferred]
                """);

        assertThat(BuiltRailixAppLoader.loadEnvelope(envelopeFile.toString())).isEqualTo(new Envelope(
                "http.default",
                "http",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue(" USER@EXAMPLE.COM ")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of(
                        "method", new RailixValue.StringValue("POST"),
                        "path", new RailixValue.StringValue("/users"),
                        "headers", new RailixValue.ObjectValue(Map.of(
                                "content-type", new RailixValue.StringValue("application/json")
                        ))
                )),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(
                        Reply.Mode.IMMEDIATE,
                        Reply.Mode.FILE,
                        Reply.Mode.STREAM,
                        Reply.Mode.DEFERRED
                ))
        ));
    }

    @Test
    void shouldRejectMultiFlowAuthoringYamlSpec() throws Exception {
        final Path planFile = tempDir.resolve("multi-flow.railix.app.yaml");
        Files.writeString(planFile, """
                app:
                  id: example.multi-flow
                  flows:
                    - id: one
                      trigger: first
                      steps:
                        - id: first
                          use: railix.std.trigger.ManualTrigger
                          next: end
                    - id: two
                      trigger: second
                      steps:
                        - id: second
                          use: railix.std.trigger.ManualTrigger
                          next: end
                """);

        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(planFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one flow");
    }

    @Test
    void shouldLoadPlanFromClasspathLocation() {
        final AppPlan appPlan = BuiltRailixAppLoader.loadPlan("classpath:/railix/test-app-plan.json");

        assertThat(appPlan.appId()).isEqualTo("railix-classpath-app");
        assertThat(appPlan.flowId()).isEqualTo("order-flow");
        assertThat(appPlan.triggerStepId()).isEqualTo("capture");
        assertThat(appPlan.steps()).containsExactly(new AppPlan.StepInvocation("capture", TestBootstrapStepProvider.USE, Map.of()));
    }

    @Test
    void shouldOverlaySettingsTreesLoadedInOrder() throws Exception {
        final Path overrideSettingsFile = writeJson("settings-override.json", KernelContractCodec.toUiModel(new SettingsTree(
                "Runtime overrides",
                Map.of(
                        RailixPath.parse("settings.app.mode"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.app.mode"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("file-override")),
                                true,
                                false,
                                false,
                                "settings-override.json",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.CLI_ARGS)
        )));

        final SettingsTree merged = BuiltRailixAppLoader.loadSettingsTree(List.of(
                "classpath:/railix/settings/defaults.json",
                "classpath:/railix/settings/profiles/dev.json",
                overrideSettingsFile.toString()
        ));

        assertThat(merged.description()).isEqualTo("Runtime overrides");
        assertThat(merged.entries().get(RailixPath.parse("settings.app.mode")).source()).isEqualTo("settings-override.json");
        assertThat(((SettingsTree.PlainValue) merged.entries().get(RailixPath.parse("settings.app.mode")).value()).value())
                .isEqualTo(new RailixValue.StringValue("file-override"));
    }

    @Test
    void shouldResolvePackagedSettingsLocationsWithOptionalProfile() {
        assertThat(BuiltRailixAppLoader.packagedSettingsLocations(Optional.empty()))
                .containsExactly("classpath:/railix/settings/defaults.json");
        assertThat(BuiltRailixAppLoader.packagedSettingsLocations(Optional.of("dev")))
                .containsExactly(
                        "classpath:/railix/settings/defaults.json",
                        "classpath:/railix/settings/profiles/dev.json"
                );
    }

    @Test
    void shouldRejectMissingOrInvalidPackagedProfileNames() {
        assertThatThrownBy(() -> BuiltRailixAppLoader.packagedSettingsLocations(Optional.of("missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Classpath resource not found");
        assertThatThrownBy(() -> BuiltRailixAppLoader.packagedSettingsLocations(Optional.of("../prod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileName");
    }

    @Test
    void shouldResolveEnvironmentOverrideSettingsFromPrefixedVariables() {
        final SettingsTree overrides = BuiltRailixAppLoader.environmentOverrideSettings(Map.of(
                "IGNORED_VALUE", "nope",
                "RAILIX_SETTING__settings__app__mode", "env-override"
        ));

        assertThat(overrides.description()).isEqualTo("Environment variable overrides");
        assertThat(overrides.entries().get(RailixPath.parse("settings.app.mode")).source())
                .isEqualTo("RAILIX_SETTING__settings__app__mode");
        assertThat(((SettingsTree.PlainValue) overrides.entries().get(RailixPath.parse("settings.app.mode")).value()).value())
                .isEqualTo(new RailixValue.StringValue("env-override"));
    }

    @Test
    void shouldResolveCliOverrideSettingsFromAssignments() {
        final SettingsTree overrides = BuiltRailixAppLoader.cliOverrideSettings(List.of(
                "settings.app.mode=cli-override"
        ));

        assertThat(overrides.description()).isEqualTo("CLI argument overrides");
        assertThat(overrides.entries().get(RailixPath.parse("settings.app.mode")).source()).isEqualTo("--set");
        assertThat(((SettingsTree.PlainValue) overrides.entries().get(RailixPath.parse("settings.app.mode")).value()).value())
                .isEqualTo(new RailixValue.StringValue("cli-override"));
    }

    @Test
    void shouldResolveSystemPropertyOverrideSettingsFromPrefixedProperties() {
        final SettingsTree overrides = BuiltRailixAppLoader.systemPropertyOverrideSettings(Map.of(
                "ignored.property", "nope",
                "railix.setting.settings.app.mode", "property-override"
        ));

        assertThat(overrides.description()).isEqualTo("JVM system property overrides");
        assertThat(overrides.entries().get(RailixPath.parse("settings.app.mode")).source())
                .isEqualTo("railix.setting.settings.app.mode");
        assertThat(((SettingsTree.PlainValue) overrides.entries().get(RailixPath.parse("settings.app.mode")).value()).value())
                .isEqualTo(new RailixValue.StringValue("property-override"));
    }

    @Test
    void shouldRejectInvalidEnvironmentAndCliOverrideShapes() {
        assertThatThrownBy(() -> BuiltRailixAppLoader.environmentOverrideSettings(Map.of(
                "RAILIX_SETTING__", "broken"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environmentOverridePath");
        assertThatThrownBy(() -> BuiltRailixAppLoader.systemPropertyOverrideSettings(Map.of(
                "railix.setting.", "broken"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemPropertyOverridePath");
        assertThatThrownBy(() -> BuiltRailixAppLoader.cliOverrideSettings(List.of("settings.app.mode")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--set");
    }

    @Test
    void shouldRejectMissingClasspathPlanResource() {
        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan("classpath:/railix/missing-plan.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Classpath resource not found");
    }

    @Test
    void shouldRejectNonObjectJsonRoots() throws Exception {
        final Path planFile = tempDir.resolve("broken-plan.json");
        Files.writeString(planFile, "[]");

        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(planFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root")
                .hasMessageContaining("map");
    }

    @Test
    void shouldRejectBlankLocations() {
        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planLocation");
        assertThatThrownBy(() -> BuiltRailixAppLoader.loadPlan("classpath: "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classpathResource");
    }

    private static Stream<Arguments> malformedYamlDocuments() {
        return Stream.of(
                Arguments.of("empty", "", "YAML file is empty"),
                Arguments.of("root-indent", "  app: {}\n", "expected indentation 0 but found 2"),
                Arguments.of("map-over-indent", "app:\n  id: one\n    bad: two\n", "unexpected indentation inside map"),
                Arguments.of("list-in-map", "app:\n  id: one\n  - bad\n", "list item is not valid in a map"),
                Arguments.of("missing-colon", "app\n", "map entries must use key: value syntax"),
                Arguments.of("duplicate-key", "app: one\napp: two\n", "duplicate key: app"),
                Arguments.of("literal-block", "app: |\n", "block scalar YAML is not supported"),
                Arguments.of("folded-block", "app: >\n", "block scalar YAML is not supported"),
                Arguments.of("missing-block", "app:\n", "missing nested block for app"),
                Arguments.of("wrong-nested-indent", "app:\n    id: one\n", "must be indented by 2 spaces"),
                Arguments.of("inline-map-end", "app: { id: one\n", "inline map must end with }"),
                Arguments.of("inline-list-end", "app: [one\n", "inline list must end with ]"),
                Arguments.of("malformed-inline", "app: { id: one }}\n", "malformed inline YAML structure"),
                Arguments.of("unterminated-inline", "app: { id: \"one }\n", "unterminated inline YAML structure"),
                Arguments.of("tab-indent", "\tapp: {}\n", "Tabs are not supported in YAML"),
                Arguments.of("odd-indent", " app: {}\n", "YAML indentation must use 2-space steps"),
                Arguments.of("trailing-root", "- one\napp: two\n", "unexpected trailing content"),
                Arguments.of("unsupported-escape", "app: \"bad\\q\"\n", "unsupported escape sequence")
        );
    }

    private Path writeJson(final String fileName, final Object model) throws Exception {
        final Path file = tempDir.resolve(fileName);
        Files.writeString(file, KernelContractCodec.toStableJson(model));
        return file;
    }

    static AppPlan appPlan(final String use) {
        return new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                PermissionSet.none(),
                List.of(new AppPlan.StepInvocation("capture", use, Map.of()))
        );
    }

    static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("user@example.com")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }

    static SettingsTree settingsTree() {
        return new SettingsTree(
                "Test settings",
                Map.of(
                        RailixPath.parse("settings.app.mode"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.app.mode"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("prod")),
                                false,
                                false,
                                false,
                                "settings.json",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }
}
