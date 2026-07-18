package dev.nanonative.railix.kernel.model;

import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.RunSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KernelContractCodecTest {

    @Test
    void shouldRoundTripTypedConfigurationAndDeclaredSemantics() {
        final StepContract contract = StepContract.typed(
                "example.normalize",
                "1.0.0",
                "Normalize",
                "Normalize a typed value.",
                StepContract.Kind.NORMAL,
                List.of(),
                List.of(),
                Map.of(
                        "locale", new StepContract.ConfigField(
                                Shape.string(),
                                false,
                                StepContract.DefaultValue.of(new RailixValue.StringValue("en"))
                        ),
                        "strict", new StepContract.ConfigField(
                                Shape.bool(),
                                true,
                                StepContract.DefaultValue.none()
                        )
                ),
                Map.of("ok", new StepContract.Outcome("Completed.")),
                new StepContract.Semantics(
                        StepContract.Determinism.DETERMINISTIC,
                        StepContract.Effects.READ_EXTERNAL,
                        StepContract.StateScope.EXTERNAL,
                        StepContract.Idempotency.KEYED,
                        StepContract.Concurrency.SERIALIZED
                )
        );

        assertThat(KernelContractCodec.stepContractFromUiModel(KernelContractCodec.toUiModel(contract)))
                .isEqualTo(contract);
    }

    @Test
    void shouldRoundTripEveryShapeVariantThroughPublicCodec() {
        final Shape.ObjectShape metadata = new Shape.ObjectShape(
                Map.of("mediaType", Shape.Field.optional(Shape.string())),
                true
        );
        final List<StepContract.Port> ports = List.of(
                new StepContract.Port("any", Shape.any(), false),
                new StepContract.Port("null", Shape.nullValue(), false),
                new StepContract.Port("bool", Shape.bool(), false),
                new StepContract.Port("number", Shape.number(), false),
                new StepContract.Port("string", Shape.string(), false),
                new StepContract.Port("list", Shape.list(Shape.string()), false),
                new StepContract.Port("object", Shape.object(Map.of(
                        "id", new Shape.Field(
                                Shape.string(),
                                Shape.Presence.REQUIRED,
                                new Shape.Confidence(3, 4, List.of(Shape.Kind.NUMBER))
                        )
                ), false), false),
                new StepContract.Port("blob", new Shape.RefShape(Shape.Kind.BLOB_REF, metadata), false),
                new StepContract.Port("file", new Shape.RefShape(Shape.Kind.FILE_REF, metadata), false),
                new StepContract.Port("stream", new Shape.RefShape(Shape.Kind.STREAM_REF, metadata), false),
                new StepContract.Port("session", new Shape.RefShape(Shape.Kind.SESSION_REF, metadata), false),
                new StepContract.Port("deferred", new Shape.RefShape(Shape.Kind.DEFERRED_REF, metadata), false),
                new StepContract.Port("secret", new Shape.RefShape(Shape.Kind.SECRET_REF, metadata), false),
                new StepContract.Port("union", Shape.union(Shape.string(), Shape.number()), false)
        );
        final StepContract contract = StepContract.typed(
                "example.shapes",
                "1.0.0",
                "Shapes",
                "Expose every stable shape.",
                StepContract.Kind.NORMAL,
                ports,
                ports,
                Map.of("ok", new StepContract.Outcome("Completed."))
        );

        assertThat(KernelContractCodec.stepContractFromUiModel(KernelContractCodec.toUiModel(contract)))
                .isEqualTo(contract);
    }

    @Test
    void shouldDecodeLegacyContractWithoutTypedFields() {
        final Map<String, Object> model = legacyStepModelWithoutTypedFields();

        final StepContract decoded = KernelContractCodec.stepContractFromUiModel(model);

        assertThat(decoded.contractVersion()).isEqualTo(StepContract.LEGACY_CONTRACT_VERSION);
        assertThat(decoded.config()).isEmpty();
        assertThat(decoded.semantics()).isEqualTo(StepContract.Semantics.undeclared());
        assertThat(decoded.inputs().getFirst().shape()).isEqualTo(Shape.openObject());
    }

    @Test
    void shouldDefaultMissingTypedSemanticsToPure() {
        final StepContract typed = StepContract.typed(
                "example.typed",
                "1.0.0",
                "Typed",
                "Typed contract.",
                StepContract.Kind.NORMAL,
                List.of(),
                List.of(),
                Map.of("ok", new StepContract.Outcome("Completed."))
        );
        final Map<String, Object> model = new LinkedHashMap<>(KernelContractCodec.toUiModel(typed));
        model.remove("semantics");

        assertThat(KernelContractCodec.stepContractFromUiModel(model).semantics())
                .isEqualTo(StepContract.Semantics.pure());
    }

    @Test
    void shouldRejectConnectionOperatorConfigThatIsNotAnObject() {
        final Map<String, Object> model = new LinkedHashMap<>(KernelContractCodec.toUiModel(connectionPlan()));
        final Map<String, Object> connection = new LinkedHashMap<>(castMap(castList(model.get("connections")).getFirst()));
        final Map<String, Object> operator = new LinkedHashMap<>(castMap(castList(connection.get("operators")).getFirst()));
        operator.put("config", Map.of("kind", "string", "value", "invalid"));
        connection.put("operators", List.of(operator));
        model.put("connections", List.of(connection));

        assertThatThrownBy(() -> KernelContractCodec.appPlanFromUiModel(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operator.config must decode to an object");
    }

    @Test
    void shouldRejectStepInvocationConfigThatIsNotAnObject() {
        final Map<String, Object> model = new LinkedHashMap<>(KernelContractCodec.toUiModel(connectionPlan()));
        final List<Object> rawSteps = castList(model.get("steps"));
        final Map<String, Object> firstStep = new LinkedHashMap<>(castMap(rawSteps.getFirst()));
        firstStep.put("config", Map.of("kind", "string", "value", "invalid"));
        model.put("steps", List.of(firstStep, rawSteps.get(1)));

        assertThatThrownBy(() -> KernelContractCodec.appPlanFromUiModel(model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("config must decode to an object");
    }

    @ParameterizedTest(name = "round-trips patch {index}")
    @MethodSource("allPatchVariants")
    void shouldRoundTripEveryPatchVariant(final Patch patch) {
        assertThat(KernelContractCodec.patchFromUiModel(KernelContractCodec.toUiModel(patch)))
                .isEqualTo(patch);
    }

    @Test
    void shouldRoundTripTypedStepContractWithoutLosingShapes() {
        final StepContract contract = StepContract.typed(
                "example.text.lowercase",
                "1.0.0",
                "Lowercase",
                "Lowercase one string.",
                StepContract.Kind.NORMAL,
                List.of(new StepContract.Port("value", Shape.string(), true)),
                List.of(new StepContract.Port("result", Shape.string(), true)),
                Map.of("ok", new StepContract.Outcome("Completed."))
        );

        assertThat(KernelContractCodec.stepContractFromUiModel(KernelContractCodec.toUiModel(contract)))
                .isEqualTo(contract);
    }

    @Test
    void shouldRoundTripPlanConnectionsWithoutLosingOperatorOrder() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "connection-flow",
                "emit",
                PermissionSet.none(),
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(new AppPlan.Connection(
                        "normalize-email",
                        new AppPlan.PortRef("emit", "email"),
                        new AppPlan.PortRef("consume", "value"),
                        List.of(
                                new AppPlan.OperatorInvocation("railix.op.string.trim", new RailixValue.ObjectValue(Map.of())),
                                new AppPlan.OperatorInvocation("railix.op.string.lowercase", new RailixValue.ObjectValue(Map.of()))
                        )
                ))
        );

        assertThat(KernelContractCodec.appPlanFromUiModel(KernelContractCodec.toUiModel(plan)))
                .isEqualTo(plan);
    }

    @Test
    void shouldRoundTripRailixValueSelectorPatchAndShape() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "customer", new RailixValue.ObjectValue(Map.of(
                        "email", new RailixValue.StringValue("user@example.com")
                )),
                "active", new RailixValue.BoolValue(true)
        ));
        final Selector selector = new Selector("payload.orders[*].items[*].sku");
        final Patch patch = new Patch.Set(
                RailixPath.parse("ctx.customer.email"),
                new Patch.ExpressionSource(new Patch.OperationExpression(
                        "lower",
                        Map.of("input", new Patch.PathExpression(RailixPath.parse("payload.customer.email")))
                ))
        );
        final Shape shape = new Shape.ObjectShape(
                Map.of("email", new Shape.Field(
                        new Shape.ScalarShape(Shape.Kind.STRING),
                        Shape.Presence.REQUIRED,
                        Shape.Confidence.exact(3)
                )),
                false
        );

        assertThat(KernelContractCodec.railixValueFromUiModel(KernelContractCodec.toUiModel(value))).isEqualTo(value);
        assertThat(KernelContractCodec.selectorFromUiModel(KernelContractCodec.toUiModel(selector))).isEqualTo(selector);
        assertThat(KernelContractCodec.patchFromUiModel(KernelContractCodec.toUiModel(patch))).isEqualTo(patch);
        assertThat(KernelContractCodec.shapeFromUiModel(KernelContractCodec.toUiModel(shape))).isEqualTo(shape);
    }

    @Test
    void shouldRoundTripSettingsTreePermissionSetAndStepContract() {
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(
                        RailixPath.parse("settings.database.password"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.database.password"),
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP, SettingsTree.Scope.RUN),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE, SettingsTree.SourceLayer.CLI_ARGS)
        );
        final PermissionSet permissionSet = new PermissionSet(
                Map.of("settings.secret", List.of("settings.database.password")),
                Map.of("settings.secret", List.of("settings.database.password")),
                List.of(new PermissionSet.Decision(
                        "settings.secret",
                        "settings.database.password",
                        PermissionSet.DecisionResult.GRANTED,
                        "explicit grant"
                ))
        );
        final StepContract stepContract = new StepContract(
                "railix.std.data.DataTransform",
                "0.1.0",
                "Data Transform",
                "Map, transform, and patch nested data.",
                StepContract.Kind.NORMAL,
                List.of(new StepContract.Port("payload", "document", "payload", false, List.of())),
                List.of(new StepContract.Port("outcome", "enum", "ctx", true, List.of("ok", "invalid"))),
                Map.of("ok", new StepContract.Outcome("Transform completed.")),
                new StepContract.Settings(List.of("settings.database.password")),
                permissionSet,
                new StepContract.Timeout(Duration.ofSeconds(30)),
                new StepContract.RetryPolicy(1, Duration.ZERO),
                new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                new StepContract.Resources(new StepContract.Limits(
                        new RailixValue.NumberValue(BigDecimal.valueOf(512)),
                        new RailixValue.NumberValue(BigDecimal.ONE)
                )),
                new StepContract.Metrics(List.of(
                        new StepContract.MetricDefinition("step.duration", "duration", "ms", List.of("step"))
                )),
                Map.of("icon", new RailixValue.StringValue("transform"))
        );

        assertThat(KernelContractCodec.settingsTreeFromUiModel(KernelContractCodec.toUiModel(settingsTree))).isEqualTo(settingsTree);
        assertThat(KernelContractCodec.permissionSetFromUiModel(KernelContractCodec.toUiModel(permissionSet))).isEqualTo(permissionSet);
        assertThat(KernelContractCodec.stepContractFromUiModel(KernelContractCodec.toUiModel(stepContract))).isEqualTo(stepContract);
    }

    @Test
    void shouldRoundTripAppPlan() {
        final AppPlan appPlan = new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                new PermissionSet(
                        Map.of("settings.secret", List.of("settings.database.password")),
                        Map.of("settings.secret", List.of("settings.database.password")),
                        List.of(new PermissionSet.Decision(
                                "settings.secret",
                                "settings.database.password",
                                PermissionSet.DecisionResult.GRANTED,
                                "plan grant"
                        ))
                ),
                List.of(
                        new AppPlan.StepInvocation("capture", "test.capture", Map.of("ok", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                )
        );

        assertThat(KernelContractCodec.appPlanFromUiModel(KernelContractCodec.toUiModel(appPlan))).isEqualTo(appPlan);
    }

    @Test
    void shouldDefaultMissingAppPlanPermissionsToNone() {
        final AppPlan appPlan = KernelContractCodec.appPlanFromUiModel(Map.of(
                "appId", "railix-app",
                "flowId", "order-flow",
                "triggerStepId", "capture",
                "steps", List.of(Map.of(
                        "id", "capture",
                        "use", "test.capture",
                        "next", Map.of()
                ))
        ));

        assertThat(appPlan.permissions()).isEqualTo(PermissionSet.none());
        assertThat(appPlan.steps()).containsExactly(new AppPlan.StepInvocation("capture", "test.capture", Map.of()));
    }

    @Test
    void shouldRoundTripOperatorContractEnvelopeReplyAndRunSignal() {
        final OperatorContract operatorContract = new OperatorContract(
                "lower",
                "0.1.0",
                "Lowercase",
                "string",
                Map.of("input", new OperatorContract.Input("string", true, false, 1)),
                Map.of("locale", new OperatorContract.Config("string", false, new RailixValue.StringValue("en-US"))),
                Map.of("result", new OperatorContract.Output("string")),
                Map.of("icon", new RailixValue.StringValue("lower"))
        );
        final Envelope envelope = new Envelope(
                "manual-ui",
                "manual",
                new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("Yuna"))),
                new RailixValue.ObjectValue(Map.of("traceId", new RailixValue.StringValue("trace-1"))),
                Map.of("file", new RailixValue.FileRef("file-1", "/tmp/invoice.pdf", "application/pdf", "sha256:abc", 12L)),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE, Reply.Mode.FILE))
        );
        final Reply reply = new Reply(
                Reply.Mode.FILE,
                new RailixValue.NumberValue(BigDecimal.valueOf(201)),
                new RailixValue.ObjectValue(Map.of("contentType", new RailixValue.StringValue("application/pdf"))),
                RailixValue.NULL,
                new RailixValue.FileRef("file-1", "/tmp/invoice.pdf", "application/pdf", "sha256:abc", 12L),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        );
        final RunSignal runSignal = new RunSignal.SettingRead(
                "sig-1",
                Instant.parse("2026-06-27T11:00:00Z"),
                "railix-app",
                "order-flow",
                "run-1",
                "step-1",
                1,
                RailixPath.parse("settings.database.password"),
                true,
                true
        );

        assertThat(KernelContractCodec.operatorContractFromUiModel(KernelContractCodec.toUiModel(operatorContract))).isEqualTo(operatorContract);
        assertThat(KernelContractCodec.envelopeFromUiModel(KernelContractCodec.toUiModel(envelope))).isEqualTo(envelope);
        assertThat(KernelContractCodec.replyFromUiModel(KernelContractCodec.toUiModel(reply))).isEqualTo(reply);
        assertThat(KernelContractCodec.runSignalFromUiModel(KernelContractCodec.toUiModel(runSignal))).isEqualTo(runSignal);
    }

    @Test
    void shouldRenderStableJsonAndYaml() {
        final PermissionSet permissionSet = new PermissionSet(
                Map.of("settings.secret", List.of("settings.database.password")),
                Map.of(),
                List.of()
        );

        final String json = KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(permissionSet));
        final String yaml = KernelContractCodec.toStableYaml(KernelContractCodec.toUiModel(permissionSet));

        assertThat(json).isEqualTo("{\"decisions\":[],\"granted\":{},\"requested\":{\"settings.secret\":[\"settings.database.password\"]}}");
        assertThat(yaml).isEqualTo("""
"decisions": []
"granted": {}
"requested":
  "settings.secret":
    - "settings.database.password"
""");
    }

    @Test
    void shouldParseStableJsonObjectWithEscapesAndNumericKinds() {
        final Map<String, Object> decoded = KernelContractCodec.parseStableJsonObject("""
                {"count":1,"large":4000000000,"decimal":200.5,"message":"line1\\nline2\\t\\"quoted\\"","nested":{"ok":true}}
                """);

        assertThat(decoded)
                .containsEntry("count", 1)
                .containsEntry("large", 4_000_000_000L)
                .containsEntry("decimal", new BigDecimal("200.5"))
                .containsEntry("message", "line1\nline2\t\"quoted\"");
        assertThat(decoded.get("nested")).isEqualTo(Map.of("ok", true));
    }

    @Test
    void shouldParseStableJsonWithNegativeExponentAndNullValues() {
        final Object decoded = KernelContractCodec.parseStableJson("""
                [-12,1.25e3,null,false]
                """);

        assertThat((List<Object>) decoded).containsExactly(Integer.valueOf(-12), new BigDecimal("1.25E+3"), null, Boolean.FALSE);
    }

    @Test
    void shouldRejectStableJsonWithTrailingContent() {
        assertThatThrownBy(() -> KernelContractCodec.parseStableJson("{\"ok\":true} trailing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected trailing JSON content");
    }

    @Test
    void shouldRejectUnsupportedRailixValueKind() {
        assertThatThrownBy(() -> KernelContractCodec.railixValueFromUiModel(Map.of("kind", "mystery")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported RailixValue kind");
    }

    @Test
    void shouldRejectUnsupportedPatchOperation() {
        assertThatThrownBy(() -> KernelContractCodec.patchFromUiModel(Map.of("op", "explode")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patch op");
    }

    @Test
    void shouldRejectUnsupportedRunSignalType() {
        assertThatThrownBy(() -> KernelContractCodec.runSignalFromUiModel(Map.of(
                "type", "signal.unknown",
                "signalId", "sig-200",
                "timestamp", "2026-06-27T11:30:00Z",
                "app", "railix-app",
                "flow", "order-flow",
                "run", "run-1"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported run signal type");
    }

    @Test
    void shouldRejectMalformedSettingsTreeEntryModel() {
        assertThatThrownBy(() -> KernelContractCodec.settingsTreeFromUiModel(Map.of(
                "description", "Broken settings",
                "scopes", List.of("app"),
                "precedence", List.of("settings-file"),
                "entries", List.of(Map.of(
                        "path", "settings.database.password",
                        "type", "string",
                        "value", "not-a-map",
                        "required", true,
                        "secret", true,
                        "encrypted", true,
                        "source", "settings/prod.sops.yaml",
                        "visibility", "hidden",
                        "audit", "on-materialize",
                        "overridePolicy", "trusted-only"
                ))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value must be a map");
    }

    @Test
    void shouldRejectSecretPlainValueAtUiBoundary() {
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(
                        RailixPath.parse("settings.database.password"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.database.password"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("super-secret")),
                                true,
                                true,
                                false,
                                "settings/dev.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        assertThatThrownBy(() -> KernelContractCodec.toUiModel(settingsTree))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be exported as a UI model value");
    }

    @Test
    void shouldRejectSecretReferenceValueAtUiBoundary() {
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(
                        RailixPath.parse("settings.database.password"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.database.password"),
                                "string",
                                new SettingsTree.ReferenceValue(new RailixValue.SecretRef(RailixPath.parse("vault.database.password"))),
                                true,
                                true,
                                false,
                                "settings/dev.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        assertThatThrownBy(() -> KernelContractCodec.toUiModel(settingsTree))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be exported as a UI model value");
    }

    @Test
    void shouldRoundTripCompositeRailixValueWithAllReferenceKinds() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "items", new RailixValue.ListValue(List.of(
                        RailixValue.NULL,
                        new RailixValue.BoolValue(true),
                        new RailixValue.NumberValue(BigDecimal.valueOf(42)),
                        new RailixValue.StringValue("ready")
                )),
                "blob", new RailixValue.BlobRef("blob-1", "application/json", "sha256:blob", 128L),
                "file", new RailixValue.FileRef("file-1", "/tmp/report.json", "application/json", "sha256:file", 256L),
                "stream", new RailixValue.StreamRef("stream-1", "event", Map.of("schema", new RailixValue.StringValue("order.v1"))),
                "session", new RailixValue.SessionRef("session-1", "http", Map.of("tenant", new RailixValue.StringValue("acme"))),
                "deferred", new RailixValue.DeferredRef("deferred-1", "ctx.jobs[0].status", Map.of("jobType", new RailixValue.StringValue("export"))),
                "secret", new RailixValue.SecretRef(RailixPath.parse("settings.database.password"))
        ));

        assertThat(KernelContractCodec.railixValueFromUiModel(KernelContractCodec.toUiModel(value))).isEqualTo(value);
    }

    @Test
    void shouldRoundTripAllShapeKindsThroughUiModel() {
        final List<Shape> shapes = List.of(
                new Shape.ScalarShape(Shape.Kind.STRING),
                new Shape.ListShape(new Shape.ScalarShape(Shape.Kind.NUMBER)),
                new Shape.ObjectShape(Map.of(
                        "email", new Shape.Field(
                                new Shape.ScalarShape(Shape.Kind.STRING),
                                Shape.Presence.REQUIRED,
                                new Shape.Confidence(2, 3, List.of(Shape.Kind.NULL))
                        )
                ), true),
                new Shape.RefShape(
                        Shape.Kind.FILE_REF,
                        new Shape.ObjectShape(Map.of(
                                "mediaType", new Shape.Field(
                                        new Shape.ScalarShape(Shape.Kind.STRING),
                                        Shape.Presence.REQUIRED,
                                        Shape.Confidence.exact(1)
                                )
                        ), false)
                ),
                new Shape.UnionShape(List.of(
                        new Shape.ScalarShape(Shape.Kind.STRING),
                        new Shape.ScalarShape(Shape.Kind.NUMBER)
                ))
        );

        for (final Shape shape : shapes) {
            assertThat(KernelContractCodec.shapeFromUiModel(KernelContractCodec.toUiModel(shape))).isEqualTo(shape);
        }
    }

    @Test
    void shouldRoundTripAllRunSignalVariantsThroughUiModel() {
        final Instant timestamp = Instant.parse("2026-06-27T12:00:00Z");
        final RailixValue.ObjectValue metadata = new RailixValue.ObjectValue(Map.of(
                "contentType", new RailixValue.StringValue("application/json")
        ));
        final List<RunSignal> signals = new ArrayList<>(List.of(
                new RunSignal.RunStarted(
                        "sig-301",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        new RailixValue.StringValue("manual trigger")
                ),
                new RunSignal.StepStarted(
                        "sig-302",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1
                ),
                new RunSignal.StepFinished(
                        "sig-303",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        "ok",
                        25
                ),
                new RunSignal.StepFailed(
                        "sig-304",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        2,
                        new RailixValue.ObjectValue(Map.of("message", new RailixValue.StringValue("boom")))
                ),
                new RunSignal.ContextPatched(
                        "sig-305",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        new RailixValue.StringValue("set ctx.customer.email"),
                        List.of(
                                RailixPath.parse("ctx.customer.email"),
                                RailixPath.parse("ctx.customer.status")
                        )
                ),
                new RunSignal.PermissionDecided(
                        "sig-306",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        "settings.secret",
                        "settings.database.password",
                        "granted",
                        "explicit grant"
                ),
                new RunSignal.SettingRead(
                        "sig-307",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        RailixPath.parse("settings.database.password"),
                        true,
                        true
                ),
                new RunSignal.MetricEmitted(
                        "sig-308",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        "step.duration",
                        new RailixValue.NumberValue(BigDecimal.valueOf(25)),
                        "ms",
                        Map.of("step", "transform", "outcome", "ok")
                ),
                new RunSignal.AuditEmitted(
                        "sig-309",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "transform",
                        1,
                        "credentials.materialized",
                        new RailixValue.ObjectValue(Map.of("path", new RailixValue.StringValue("settings.database.password")))
                ),
                new RunSignal.ResourceCreated(
                        "sig-310",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "reply",
                        1,
                        "file-1",
                        "file",
                        metadata
                ),
                new RunSignal.ReplyProduced(
                        "sig-311",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "file",
                        new RailixValue.NumberValue(BigDecimal.valueOf(200)),
                        metadata
                ),
                new RunSignal.RunFinished(
                        "sig-312",
                        timestamp,
                        "railix-app",
                        "order-flow",
                        "run-77",
                        "ok",
                        40
                )
        ));

        for (final RunSignal signal : signals) {
            assertThat(KernelContractCodec.runSignalFromUiModel(KernelContractCodec.toUiModel(signal))).isEqualTo(signal);
        }
    }

    @Test
    void shouldRoundTripSettingsTreeWithPlainEncryptedAndReferenceValues() {
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(
                        RailixPath.parse("settings.database.password"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.database.password"),
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        ),
                        RailixPath.parse("settings.http.port"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.port"),
                                "int",
                                new SettingsTree.PlainValue(new RailixValue.NumberValue(BigDecimal.valueOf(8080))),
                                true,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        ),
                        RailixPath.parse("settings.http.baseUrl"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.baseUrl"),
                                "string",
                                new SettingsTree.ReferenceValue(new RailixValue.StringValue("env:RAILIX_BASE_URL")),
                                false,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.MASKED,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.DENY
                        )
                ),
                List.of(SettingsTree.Scope.APP, SettingsTree.Scope.RUN),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE, SettingsTree.SourceLayer.CLI_ARGS)
        );

        assertThat(KernelContractCodec.settingsTreeFromUiModel(KernelContractCodec.toUiModel(settingsTree))).isEqualTo(settingsTree);
    }

    @Test
    void shouldRejectUnsupportedNestedCodecKinds() {
        assertThatThrownBy(() -> KernelContractCodec.patchFromUiModel(Map.of(
                "op", "set",
                "path", "ctx.customer.email",
                "source", Map.of("kind", "weird")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patch source kind");

        assertThatThrownBy(() -> KernelContractCodec.patchFromUiModel(Map.of(
                "op", "set",
                "path", "ctx.customer.email",
                "source", Map.of(
                        "kind", "expression",
                        "value", Map.of("kind", "mystery")
                )
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patch expression kind");

        assertThatThrownBy(() -> KernelContractCodec.shapeFromUiModel(Map.of("kind", "mystery")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported shape kind");

        assertThatThrownBy(() -> KernelContractCodec.settingsTreeFromUiModel(Map.of(
                "description", "Broken settings",
                "scopes", List.of("app"),
                "precedence", List.of("settings-file"),
                "entries", List.of(Map.of(
                        "path", "settings.http.port",
                        "type", "int",
                        "value", Map.of("kind", "mystery"),
                        "required", true,
                        "secret", false,
                        "encrypted", false,
                        "source", "settings/app.yaml",
                        "visibility", "normal",
                        "audit", "never",
                        "overridePolicy", "allow"
                ))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported settings value kind");
    }

    @Test
    void shouldRejectMalformedNestedCodecShapes() {
        assertThatThrownBy(() -> KernelContractCodec.stepContractFromUiModel(Map.ofEntries(
                Map.entry("id", "railix.std.data.DataTransform"),
                Map.entry("version", "0.1.0"),
                Map.entry("displayName", "Data Transform"),
                Map.entry("description", "Broken contract"),
                Map.entry("kind", "normal"),
                Map.entry("inputs", List.of()),
                Map.entry("outputs", List.of()),
                Map.entry("outcomes", Map.of()),
                Map.entry("settings", Map.of("requested", "not-a-list")),
                Map.entry("permissions", Map.of("requested", Map.of(), "granted", Map.of(), "decisions", List.of())),
                Map.entry("timeout", Map.of("default", "PT30S")),
                Map.entry("retry", Map.of("default", Map.of("maxAttempts", 1, "backoff", "PT0S"))),
                Map.entry("cache", Map.of("default", Map.of("mode", "none", "key", "", "ttl", "PT0S"))),
                Map.entry("resources", Map.of("limits", Map.of(
                        "memory", Map.of("kind", "number", "value", "512"),
                        "cpu", Map.of("kind", "number", "value", "1")
                ))),
                Map.entry("metrics", Map.of("emitted", List.of())),
                Map.entry("ui", Map.of())
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested must be a list");

        assertThatThrownBy(() -> KernelContractCodec.operatorContractFromUiModel(Map.of(
                "id", "lower",
                "version", "0.1.0",
                "displayName", "Lowercase",
                "category", "string",
                "inputs", Map.of("input", "not-a-map"),
                "config", Map.of(),
                "outputs", Map.of(),
                "ui", Map.of()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input must be a map");

        assertThatThrownBy(() -> KernelContractCodec.runSignalFromUiModel(Map.ofEntries(
                Map.entry("type", "metric.emitted"),
                Map.entry("signalId", "sig-999"),
                Map.entry("timestamp", "2026-06-27T12:30:00Z"),
                Map.entry("app", "railix-app"),
                Map.entry("flow", "order-flow"),
                Map.entry("run", "run-1"),
                Map.entry("step", "transform"),
                Map.entry("attempt", 1),
                Map.entry("name", "step.duration"),
                Map.entry("value", Map.of("kind", "number", "value", "1")),
                Map.entry("unit", "ms"),
                Map.entry("labels", List.of("bad"))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labels must be a map");
    }

    @Test
    void shouldRejectDecodedNonObjectValuesAtObjectBoundaries() {
        assertThatThrownBy(() -> KernelContractCodec.envelopeFromUiModel(Map.of(
                "source", "manual-ui",
                "protocol", "manual",
                "payload", Map.of("kind", "string", "value", "oops"),
                "metadata", Map.of("kind", "object", "value", Map.of()),
                "refs", Map.of(),
                "replyChannel", Map.of("supported", true, "modes", List.of("immediate"))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must decode to an object value");

        assertThatThrownBy(() -> KernelContractCodec.replyFromUiModel(Map.of(
                "mode", "file",
                "status", Map.of("kind", "number", "value", "200"),
                "metadata", Map.of("kind", "string", "value", "oops"),
                "payload", Map.of("kind", "null"),
                "file", Map.of("kind", "null"),
                "stream", Map.of("kind", "null"),
                "session", Map.of("kind", "null"),
                "deferred", Map.of("kind", "null")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata must decode to an object value");
    }

    @Test
    void shouldRejectDecodedNonObjectShapesAtRefMetadataBoundary() {
        assertThatThrownBy(() -> KernelContractCodec.shapeFromUiModel(Map.of(
                "kind", "ref",
                "type", "file-ref",
                "metadataShape", Map.of(
                        "kind", "scalar",
                        "type", "string"
                )
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataShape must decode to an object shape");
    }

    @Test
    void shouldDecodeIntegralBoundaryFieldsFromIntegerAndLongValues() {
        final OperatorContract operatorContract = KernelContractCodec.operatorContractFromUiModel(Map.of(
                "id", "lower",
                "version", "0.1.0",
                "displayName", "Lowercase",
                "category", "string",
                "inputs", Map.of("input", Map.of(
                        "type", "string",
                        "required", true,
                        "dynamic", false,
                        "min", 1L
                )),
                "config", Map.of(),
                "outputs", Map.of(),
                "ui", Map.of()
        ));
        final RunSignal stepStarted = KernelContractCodec.runSignalFromUiModel(Map.of(
                "type", "step.started",
                "signalId", "sig-401",
                "timestamp", "2026-06-27T13:00:00Z",
                "app", "railix-app",
                "flow", "order-flow",
                "run", "run-1",
                "step", "transform",
                "attempt", 2L
        ));
        final RunSignal runFinished = KernelContractCodec.runSignalFromUiModel(Map.of(
                "type", "run.finished",
                "signalId", "sig-402",
                "timestamp", "2026-06-27T13:00:01Z",
                "app", "railix-app",
                "flow", "order-flow",
                "run", "run-1",
                "outcome", "ok",
                "durationMs", 25
        ));

        assertThat(operatorContract.inputs().get("input").min()).isEqualTo(1);
        assertThat(((RunSignal.StepStarted) stepStarted).attemptNumber()).isEqualTo(2);
        assertThat(((RunSignal.RunFinished) runFinished).durationMs()).isEqualTo(25L);
    }

    @Test
    void shouldRejectOutOfRangeLongValuesForIntegerFields() {
        assertThatThrownBy(() -> KernelContractCodec.runSignalFromUiModel(Map.of(
                "type", "step.started",
                "signalId", "sig-403",
                "timestamp", "2026-06-27T13:00:02Z",
                "app", "railix-app",
                "flow", "order-flow",
                "run", "run-1",
                "step", "transform",
                "attempt", Long.MAX_VALUE
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt must fit in a 32-bit integer");
    }

    @Test
    void shouldRejectNonStringNestedListItemsInCodecModels() {
        assertThatThrownBy(() -> KernelContractCodec.permissionSetFromUiModel(Map.of(
                "requested", Map.of("settings.secret", List.of(1)),
                "granted", Map.of(),
                "decisions", List.of()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list item must be a string");

        assertThatThrownBy(() -> KernelContractCodec.stepContractFromUiModel(Map.ofEntries(
                Map.entry("id", "railix.std.data.DataTransform"),
                Map.entry("version", "0.1.0"),
                Map.entry("displayName", "Data Transform"),
                Map.entry("description", "Broken labels"),
                Map.entry("kind", "normal"),
                Map.entry("inputs", List.of()),
                Map.entry("outputs", List.of()),
                Map.entry("outcomes", Map.of()),
                Map.entry("settings", Map.of("requested", List.of())),
                Map.entry("permissions", Map.of("requested", Map.of(), "granted", Map.of(), "decisions", List.of())),
                Map.entry("timeout", Map.of("default", "PT30S")),
                Map.entry("retry", Map.of("default", Map.of("maxAttempts", 1, "backoff", "PT0S"))),
                Map.entry("cache", Map.of("default", Map.of("mode", "none", "key", "", "ttl", "PT0S"))),
                Map.entry("resources", Map.of("limits", Map.of(
                        "memory", Map.of("kind", "number", "value", "512"),
                        "cpu", Map.of("kind", "number", "value", "1")
                ))),
                Map.entry("metrics", Map.of("emitted", List.of(Map.of(
                        "name", "step.duration",
                        "type", "duration",
                        "unit", "ms",
                        "labels", List.of(1)
                )))),
                Map.entry("ui", Map.of())
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("list item must be a string");
    }

    @Test
    void shouldRenderScalarRootsAndRejectUnsupportedStableJsonValues() {
        assertThat(KernelContractCodec.toStableJson("line\nbreak")).isEqualTo("\"line\\nbreak\"");
        assertThat(KernelContractCodec.toStableJson("\u0001\b\f")).isEqualTo("\"\\u0001\\b\\f\"");
        assertThat(KernelContractCodec.toStableYaml(true)).isEqualTo("true\n");
        assertThat(KernelContractCodec.toStableYaml(null)).isEqualTo("null\n");
        assertThat(KernelContractCodec.toStableYaml(Map.of("bad:key", "value"))).isEqualTo("\"bad:key\": \"value\"\n");

        assertThatThrownBy(() -> KernelContractCodec.toStableJson(Map.of("bad", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported JSON value type");
    }

    @Test
    void shouldRenderNullAndEmptyCollectionRootsDeterministically() {
        assertThat(KernelContractCodec.toStableJson(null)).isEqualTo("null");
        assertThat(KernelContractCodec.toStableYaml(Map.of())).isEqualTo("{}\n");
        assertThat(KernelContractCodec.toStableYaml(List.of())).isEqualTo("[]\n");
    }

    @Test
    void shouldRenderNestedYamlCollectionsAndNullListItems() {
        final List<Object> values = new ArrayList<>();
        values.add(null);
        values.add(Map.of("name", "railix"));
        values.add(List.of("nested"));

        assertThat(KernelContractCodec.toStableYaml(values)).isEqualTo("""
                - null
                -
                  "name": "railix"
                -
                  - "nested"
                """);
    }

    @Test
    void shouldEscapeCarriageReturnInStableJson() {
        assertThat(KernelContractCodec.toStableJson("line\rreturn")).isEqualTo("\"line\\rreturn\"");
    }

    @Test
    void shouldRejectNonBooleanShapeOpenFlag() {
        assertThatThrownBy(() -> KernelContractCodec.shapeFromUiModel(Map.of(
                "kind", "object",
                "open", "yes",
                "fields", Map.of()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("open must be a boolean");
    }

    @Test
    void shouldRejectNonIntegerOperatorMinimum() {
        assertThatThrownBy(() -> KernelContractCodec.operatorContractFromUiModel(Map.of(
                "id", "lower",
                "version", "1.0.0",
                "displayName", "Lowercase",
                "category", "string",
                "inputs", Map.of("value", Map.of(
                        "type", "string",
                        "required", true,
                        "dynamic", false,
                        "min", "one"
                )),
                "config", Map.of(),
                "outputs", Map.of(),
                "ui", Map.of()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("min must be an integer");
    }

    @Test
    void shouldRejectNonLongReferenceSize() {
        assertThatThrownBy(() -> KernelContractCodec.railixValueFromUiModel(Map.of(
                "kind", "file-ref",
                "id", "file",
                "path", "file.json",
                "mediaType", "application/json",
                "digest", "sha256:1",
                "size", "one"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be a long");
    }

    @Test
    void shouldRejectUnexpectedEndOfStableJson() {
        assertThatThrownBy(() -> KernelContractCodec.parseStableJsonObject(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected end of JSON input");
    }

    @Test
    void shouldDecodeEverySupportedStableJsonEscape() {
        assertThat(KernelContractCodec.parseStableJsonObject("{\"value\":\"\\b\\f\\r\"}"))
                .containsEntry("value", "\b\f\r");
    }

    @Test
    void shouldDecodeExponentAndLongStableJsonNumbers() {
        assertThat(KernelContractCodec.parseStableJsonObject(
                "{\"large\":2147483648,\"positive\":1e+3,\"negative\":1e-3}"
        )).containsEntry("large", 2147483648L)
                .containsEntry("positive", new BigDecimal("1e+3"))
                .containsEntry("negative", new BigDecimal("1e-3"));
    }

    @Test
    void shouldRejectStableJsonFractionWithoutDigits() {
        assertThatThrownBy(() -> KernelContractCodec.parseStableJsonObject("{\"value\":1.}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid JSON number");
    }

    @Test
    void shouldRejectStableJsonExponentWithoutDigits() {
        assertThatThrownBy(() -> KernelContractCodec.parseStableJsonObject("{\"value\":1e+}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid JSON number");
    }

    private static Map<String, Object> legacyStepModelWithoutTypedFields() {
        final StepContract legacy = new StepContract(
                "example.legacy",
                "0.9.0",
                "Legacy",
                "Legacy contract.",
                StepContract.Kind.NORMAL,
                List.of(new StepContract.Port("payload", "document", "payload", true, List.of())),
                List.of(),
                Map.of("ok", new StepContract.Outcome("Completed.")),
                new StepContract.Settings(List.of()),
                PermissionSet.none(),
                new StepContract.Timeout(Duration.ofSeconds(30)),
                new StepContract.RetryPolicy(1, Duration.ZERO),
                new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                new StepContract.Metrics(List.of()),
                Map.of()
        );
        final Map<String, Object> model = new LinkedHashMap<>(KernelContractCodec.toUiModel(legacy));
        model.remove("contractVersion");
        model.remove("config");
        model.remove("semantics");
        final Map<String, Object> input = new LinkedHashMap<>(castMap(castList(model.get("inputs")).getFirst()));
        input.remove("shape");
        model.put("inputs", List.of(input));
        return model;
    }

    private static AppPlan connectionPlan() {
        return new AppPlan(
                "railix-app",
                "connection-flow",
                "emit",
                PermissionSet.none(),
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(new AppPlan.Connection(
                        "normalize-email",
                        new AppPlan.PortRef("emit", "email"),
                        new AppPlan.PortRef("consume", "value"),
                        List.of(new AppPlan.OperatorInvocation(
                                "railix.op.string.lowercase",
                                new RailixValue.ObjectValue(Map.of())
                        ))
                ))
        );
    }

    private static Stream<Patch> allPatchVariants() {
        final RailixPath source = RailixPath.parse("ctx.source");
        final RailixPath target = RailixPath.parse("ctx.target");
        final Patch.LiteralSource literal = new Patch.LiteralSource(new RailixValue.StringValue("value"));
        return Stream.of(
                new Patch.Set(target, literal),
                new Patch.Set(target, new Patch.ExpressionSource(new Patch.LiteralExpression(RailixValue.NULL))),
                new Patch.Remove(target),
                new Patch.Append(target, literal),
                new Patch.Merge(target, literal, Patch.Strategy.DEEP_MERGE),
                new Patch.Copy(source, target),
                new Patch.Move(source, target),
                new Patch.Clear(target)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(final Object value) {
        return (List<Object>) value;
    }
}
