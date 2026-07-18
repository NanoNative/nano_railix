package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepContractTest {

    @Test
    void shouldMaterializeTypedConfigurationMetadata() {
        final StepContract.ConfigField field = new StepContract.ConfigField(
                Shape.string(),
                false,
                StepContract.DefaultValue.of(new RailixValue.StringValue("default"))
        );
        final StepContract contract = StepContract.typed(
                "example.text.lowercase",
                "1.0.0",
                "Lowercase",
                "Lowercase one string.",
                StepContract.Kind.NORMAL,
                List.of(),
                List.of(),
                Map.of("locale", field),
                Map.of("ok", new StepContract.Outcome("Completed.")),
                StepContract.Semantics.pure()
        );

        assertThat(contract.config()).containsEntry("locale", field);
    }

    @Test
    void shouldRejectConfigurationDefaultThatViolatesItsShape() {
        assertThatThrownBy(() -> new StepContract.ConfigField(
                Shape.string(),
                false,
                StepContract.DefaultValue.of(new RailixValue.NumberValue(BigDecimal.ONE))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("config default expected string but received number");
    }

    @Test
    void shouldRepresentAnAbsentConfigurationDefaultWithoutNull() {
        assertThat(StepContract.DefaultValue.none())
                .isEqualTo(new StepContract.DefaultValue(false, RailixValue.NULL));
    }

    @Test
    void shouldCanonicalizeIgnoredAbsentConfigurationDefaultValue() {
        assertThat(new StepContract.DefaultValue(
                false,
                new RailixValue.StringValue("ignored")
        )).isEqualTo(StepContract.DefaultValue.none());
    }

    @Test
    void shouldRepresentAnExplicitNullConfigurationDefault() {
        assertThat(StepContract.DefaultValue.of(RailixValue.NULL))
                .isEqualTo(new StepContract.DefaultValue(true, RailixValue.NULL));
    }

    @Test
    void shouldRejectUnsupportedContractVersion() {
        final StepContract typed = typedContract(List.of(), List.of());

        assertThatThrownBy(() -> new StepContract(
                typed.id(),
                typed.version(),
                typed.displayName(),
                typed.description(),
                typed.kind(),
                typed.inputs(),
                typed.outputs(),
                typed.outcomes(),
                typed.settings(),
                typed.permissions(),
                typed.timeout(),
                typed.retryPolicy(),
                typed.cachePolicy(),
                typed.resources(),
                typed.metrics(),
                typed.ui(),
                StepContract.CURRENT_CONTRACT_VERSION + 1,
                typed.config(),
                typed.semantics()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported contractVersion: 2");
    }

    @Test
    void shouldRejectNegativeContractVersion() {
        final StepContract typed = typedContract(List.of(), List.of());

        assertThatThrownBy(() -> new StepContract(
                typed.id(),
                typed.version(),
                typed.displayName(),
                typed.description(),
                typed.kind(),
                typed.inputs(),
                typed.outputs(),
                typed.outcomes(),
                typed.settings(),
                typed.permissions(),
                typed.timeout(),
                typed.retryPolicy(),
                typed.cachePolicy(),
                typed.resources(),
                typed.metrics(),
                typed.ui(),
                -1,
                typed.config(),
                typed.semantics()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported contractVersion: -1");
    }

    @Test
    void shouldRejectBlankTypedPortName() {
        assertThatThrownBy(() -> new StepContract.Port(" ", Shape.string(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("port.name must not be blank");
    }

    @Test
    void shouldRejectDuplicateInputPortNames() {
        final StepContract.Port first = new StepContract.Port("value", Shape.string(), true);
        final StepContract.Port duplicate = new StepContract.Port("value", Shape.number(), false);

        assertThatThrownBy(() -> typedContract(List.of(first, duplicate), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate input port: value");
    }

    @Test
    void shouldRejectDuplicateOutputPortNames() {
        final StepContract.Port first = new StepContract.Port("value", Shape.string(), true);
        final StepContract.Port duplicate = new StepContract.Port("value", Shape.number(), false);

        assertThatThrownBy(() -> typedContract(List.of(), List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate output port: value");
    }

    @ParameterizedTest(name = "legacy port type {0} maps to {1}")
    @MethodSource("legacyPortShapes")
    void shouldMapLegacyPortTypesToStableShapes(final String type, final Shape expectedShape) {
        final StepContract.Port port = new StepContract.Port("value", type, "ctx.value", true, List.of());

        assertThat(port.shape()).isEqualTo(expectedShape);
    }

    @Test
    void shouldExposeTypedContractVersionAndPortShape() {
        final StepContract contract = StepContract.typed(
                "example.text.lowercase",
                "1.0.0",
                "Lowercase",
                "Lowercase one string.",
                StepContract.Kind.NORMAL,
                List.of(new StepContract.Port("value", Shape.string(), true)),
                List.of(new StepContract.Port("result", Shape.string(), true)),
                Map.of("ok", new StepContract.Outcome("Lowercase completed."))
        );

        assertThat(contract.contractVersion()).isEqualTo(StepContract.CURRENT_CONTRACT_VERSION);
        assertThat(contract.inputs().getFirst().shape()).isEqualTo(Shape.string());
    }

    @Test
    void shouldCopyTopLevelCollections() {
        final List<StepContract.Port> inputs = new ArrayList<>(List.of(
                new StepContract.Port("payload", "document", "payload", false, List.of())
        ));
        final List<StepContract.Port> outputs = new ArrayList<>(List.of(
                new StepContract.Port("outcome", "enum", "ctx", true, List.of("ok", "invalid", "error"))
        ));
        final Map<String, StepContract.Outcome> outcomes = new HashMap<>(Map.of(
                "ok", new StepContract.Outcome("Transform completed.")
        ));
        final Map<String, RailixValue> ui = new HashMap<>(Map.of(
                "icon", new RailixValue.StringValue("transform")
        ));

        final StepContract contract = new StepContract(
                "railix.std.data.DataTransform",
                "0.1.0",
                "Data Transform",
                "Map, transform, and patch nested data.",
                StepContract.Kind.NORMAL,
                inputs,
                outputs,
                outcomes,
                new StepContract.Settings(List.of("settings.database.password")),
                PermissionSet.requestedOnly(Map.of("settings.secret", List.of("settings.database.password"))),
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
                ui
        );

        inputs.clear();
        outputs.clear();
        outcomes.clear();
        ui.clear();

        assertThat(contract.inputs()).hasSize(1);
        assertThat(contract.outputs()).hasSize(1);
        assertThat(contract.outcomes()).containsKey("ok");
        assertThat(contract.ui()).containsKey("icon");
    }

    @Test
    void shouldCopyNestedPortAndMetricLists() {
        final List<String> outputValues = new ArrayList<>(List.of("ok", "invalid"));
        final List<String> labels = new ArrayList<>(List.of("step"));

        final StepContract.Port port = new StepContract.Port("outcome", "enum", "ctx", true, outputValues);
        final StepContract.MetricDefinition metric = new StepContract.MetricDefinition("step.duration", "duration", "ms", labels);

        outputValues.clear();
        labels.clear();

        assertThat(port.values()).containsExactly("ok", "invalid");
        assertThat(metric.labels()).containsExactly("step");
        assertThatThrownBy(() -> port.values().add("error")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static StepContract typedContract(
            final List<StepContract.Port> inputs,
            final List<StepContract.Port> outputs
    ) {
        return StepContract.typed(
                "example.step",
                "1.0.0",
                "Example",
                "Example typed step.",
                StepContract.Kind.NORMAL,
                inputs,
                outputs,
                Map.of("ok", new StepContract.Outcome("Completed."))
        );
    }

    private static Stream<Arguments> legacyPortShapes() {
        final Shape.ObjectShape openMetadata = new Shape.ObjectShape(Map.of(), true);
        return Stream.of(
                Arguments.of("bool", Shape.bool()),
                Arguments.of("boolean", Shape.bool()),
                Arguments.of("number", Shape.number()),
                Arguments.of("string", Shape.string()),
                Arguments.of("enum", Shape.string()),
                Arguments.of("duration", Shape.string()),
                Arguments.of("list", Shape.list(Shape.any())),
                Arguments.of("patch-list", Shape.list(Shape.any())),
                Arguments.of("mapping-list", Shape.list(Shape.any())),
                Arguments.of("document", Shape.openObject()),
                Arguments.of("object", Shape.openObject()),
                Arguments.of("envelope", Shape.openObject()),
                Arguments.of("file-ref", new Shape.RefShape(Shape.Kind.FILE_REF, openMetadata)),
                Arguments.of("custom", Shape.any())
        );
    }
}
