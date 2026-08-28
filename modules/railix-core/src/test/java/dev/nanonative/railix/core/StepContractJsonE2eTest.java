package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class StepContractJsonE2eTest {
    private static final String IMPLEMENTATION = CodecHandler.class.getCanonicalName();
    private static final String IMPLEMENTATION_ENTRY = CodecHandler.class.getName().replace('.', '/') + ".class";

    @Test
    void jsonInputRoundTripsItsShapeDefaultRangeAndRequirement() {
        assertRoundTrip(StepDefinition.named("codec.json", "1")
                .input("number", Input.json(ValueShape.NUMBER)
                        .between(RailixValue.number(1), RailixValue.number(9))
                        .defaultValue(RailixValue.number(4)))
                .run(CodecHandler.class));
    }

    @Test
    void pathInputRoundTripsItsAccessDefaultAndRequirement() {
        assertRoundTrip(StepDefinition.named("codec.path", "1")
                .input("target", Input.path(StepDefinition.PathAccess.READ_WRITE)
                        .defaultPath("context", "payload", "value"))
                .run(CodecHandler.class));
    }

    @Test
    void optionsInputRoundTripsNestedInputsSourcesAndDefault() {
        assertRoundTrip(StepDefinition.named("codec.options", "1")
                .input("current", Input.path(StepDefinition.PathAccess.READ))
                .input("source", Input.options(
                        Input.option("current").fromParent("current"),
                        Input.option("literal")
                                .input("value", Input.json(ValueShape.ANY))
                                .fromOwned("value")
                ).defaultOption("current"))
                .run(CodecHandler.class));
    }

    @Test
    void candidatesInputRoundTripsOrderedSourcesAndDefault() {
        assertRoundTrip(StepDefinition.named("codec.candidates", "1")
                .input("current", Input.path(StepDefinition.PathAccess.READ).optional())
                .input("candidates", Input.candidates(
                        Input.option("current").fromParent("current"),
                        Input.option("literal")
                                .input("value", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("fallback")))
                                .fromOwned("value")
                ).defaultCandidate("literal"))
                .run(CodecHandler.class));
    }

    @Test
    void authoredOutcomeInputRoundTripsItsCaseContract() {
        final StepDefinition definition = StepDefinition.named("codec.routes", "1")
                .input("cases", Input.candidates(
                        Input.option("literal")
                                .input("value", Input.json(ValueShape.ANY))
                                .fromOwned("value")
                ).withAuthoredOutcomes())
                .run(CodecHandler.class);

        assertThat(StepContractJson.write(definition)).contains("\"authored_outcomes\":true");
        assertRoundTrip(definition);
    }

    @Test
    void matcherGroupsInputRoundTripsOrderedBooleanSources() {
        assertRoundTrip(StepDefinition.named("codec.matchers", "1")
                .input("current", Input.path(StepDefinition.PathAccess.READ))
                .input("conditions", Input.matcherGroups(
                        Input.option("current").fromParent("current"),
                        Input.option("literal")
                                .input("value", Input.json(ValueShape.BOOLEAN)
                                        .defaultValue(RailixValue.bool(true)))
                                .fromOwned("value")
                ))
                .run(CodecHandler.class));
    }

    @Test
    void nestedStepsInputRoundTripsMissingAndPropagatedOutcomes() {
        assertRoundTrip(StepDefinition.named("codec.steps", "1")
                .input("value", Input.path(StepDefinition.PathAccess.READ).optional())
                .input("pipeline", Input.steps(
                        StepDefinition.ValueSource.from("value").onMissing("missing")
                ).propagateOutcomes())
                .outcome("failed")
                .run(CodecHandler.class));
    }

    @Test
    void triggerContractRoundTripsSourceResponsesExamplesResultsAndCatalogMetadata() {
        assertRoundTrip(StepDefinition.named("codec.trigger", "7")
                .kind(StepDefinition.Kind.TRIGGER)
                .displayName("Codec Trigger")
                .searchTerms("codec", "ingress")
                .maximumInstances(1)
                .receive("request", ValueShape.OBJECT)
                .returns("accepted", ValueShape.BOOLEAN)
                .input("target", Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "payload"))
                .source("codec.source")
                .requiredResult("message", ValueShape.STRING)
                .result("exit_code", ValueShape.NUMBER, RailixValue.number(0))
                .response("stdout", "message")
                .response("exit", "exit_code")
                .exampleTarget("target")
                .example(
                        "payload-only",
                        RailixValue.object(Map.of("value", RailixValue.string("one")))
                )
                .example(
                        "whole-context",
                        RailixValue.string("two"),
                        RailixValue.object(Map.of("header", RailixValue.object(Map.of(
                                "trace", RailixValue.string("known")
                        ))))
                )
                .primaryOutcome("received")
                .outcome("rejected")
                .run(CodecHandler.class));
    }

    @Test
    void appContractRoundTripsWithoutAnImplementationAddress() {
        assertRoundTrip(StepDefinition.named("codec.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define());
    }

    @Test
    void bundleManifestRequiresAtLeastOneDefinition() {
        assertThatThrownBy(() -> StepContractJson.writeManifest(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Step bundle manifest must contain at least one definition.");
    }

    @Test
    void bundleManifestRequiresUniqueStepIds() {
        final StepDefinition definition = definition();

        assertThatThrownBy(() -> StepContractJson.writeManifest(List.of(definition, definition)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Step bundle manifest ids must be unique.");
    }

    @Test
    void bundleManifestRequiresAnImplementationForEveryStep() {
        final StepDefinition definition = StepDefinition.named("codec.unimplemented", "1").define();

        assertThatThrownBy(() -> StepContractJson.writeManifest(List.of(definition)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bundle Step must declare an implementation: codec.unimplemented.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeImplementationAddresses")
    void bundleContractRejectsAnUnsafeImplementationAddress(
            final String scenario,
            final String className,
            final String classEntry,
            final String message
    ) {
        assertThatThrownBy(() -> StepContractJson.read(contract(), className, classEntry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void contractRequiresItsPrimaryOutcomeFirst() {
        final RailixValue.ObjectValue contract = with(
                contract(),
                "outcomes",
                RailixValue.array(List.of(RailixValue.string("other"), RailixValue.string("next")))
        );

        assertInvalid(contract, "contract.outcomes must start with primary_outcome.");
    }

    @Test
    void contractRejectsAnUnsupportedInputType() {
        final RailixValue.ObjectValue contract = withFirstInput(
                contract(),
                input -> with(input, "type", RailixValue.string("mystery"))
        );

        assertInvalid(contract, "contract.inputs[].type unsupported input type mystery.");
    }

    @Test
    void contractRequiresBothJsonRangeBounds() {
        final RailixValue.ObjectValue contract = withFirstInput(
                contract(),
                input -> with(input, "minimum", RailixValue.number(1))
        );

        assertInvalid(contract, "contract.inputs[] minimum and maximum must be supplied together.");
    }

    @Test
    void contractRejectsAnUnsupportedKind() {
        final RailixValue.ObjectValue contract = with(contract(), "kind", RailixValue.string("mystery"));

        assertInvalid(contract, "contract.kind unsupported value mystery.");
    }

    @Test
    void contractRequiresEveryRootField() {
        final RailixValue.ObjectValue contract = without(contract(), "version");

        assertInvalid(contract, "contract.version is required.");
    }

    @Test
    void contractRequiredFlagsMustBeBoolean() {
        final RailixValue.ObjectValue contract = withFirstInput(
                contract(),
                input -> with(input, "required", RailixValue.string("yes"))
        );

        assertInvalid(contract, "contract.inputs[].required must be boolean.");
    }

    @Test
    void contractMaximumInstancesMustBeANumber() {
        final RailixValue.ObjectValue contract = with(
                contract(),
                "maximum_instances",
                RailixValue.string("one")
        );

        assertInvalid(contract, "contract.maximum_instances must be a number.");
    }

    @Test
    void contractMaximumInstancesMustFitA32BitInteger() {
        final RailixValue.ObjectValue contract = with(
                contract(),
                "maximum_instances",
                RailixValue.number(new BigDecimal("2147483648"))
        );

        assertInvalid(contract, "contract.maximum_instances must be a 32-bit integer.");
    }

    @Test
    void contractOutcomesMustBeAnArray() {
        final RailixValue.ObjectValue contract = with(contract(), "outcomes", RailixValue.string("next"));

        assertInvalid(contract, "contract.outcomes must be an array.");
    }

    @Test
    void contractSourceMustBeAnObject() {
        final RailixValue.ObjectValue contract = with(contract(), "source", RailixValue.string("source"));

        assertInvalid(contract, "contract.source must be an object.");
    }

    @Test
    void contractRejectsUnknownRootFields() {
        final RailixValue.ObjectValue contract = with(contract(), "mystery", RailixValue.bool(true));

        assertInvalid(contract, "contract.mystery is unknown.");
    }

    private static void assertRoundTrip(final StepDefinition expected) {
        final StepDefinition actual = StepContractJson.read(
                StepContractJson.value(expected),
                IMPLEMENTATION,
                IMPLEMENTATION_ENTRY
        );

        assertThat(StepContractJson.write(actual)).isEqualTo(StepContractJson.write(expected));
    }

    private static StepDefinition definition() {
        return StepDefinition.named("codec.base", "1")
                .input("value", Input.json(ValueShape.STRING))
                .run(CodecHandler.class);
    }

    private static Stream<Arguments> unsafeImplementationAddresses() {
        final String classMessage = "Step implementation must be a canonical Java class name.";
        final String entryMessage = "Step implementation entry must be a safe JAR class entry.";
        return Stream.of(
                Arguments.of("implementation class without package", "Handler", IMPLEMENTATION_ENTRY, classMessage),
                Arguments.of("implementation class with empty segment", "dev..Handler", IMPLEMENTATION_ENTRY, classMessage),
                Arguments.of("implementation class with numeric segment", "dev.1Handler", IMPLEMENTATION_ENTRY, classMessage),
                Arguments.of("implementation class with invalid character", "dev.Handler-name", IMPLEMENTATION_ENTRY, classMessage),
                Arguments.of("implementation entry without class suffix", IMPLEMENTATION, "dev/Handler", entryMessage),
                Arguments.of("absolute implementation entry", IMPLEMENTATION, "/dev/Handler.class", entryMessage),
                Arguments.of("backslash implementation entry", IMPLEMENTATION, "dev\\Handler.class", entryMessage),
                Arguments.of("empty implementation entry segment", IMPLEMENTATION, "dev//Handler.class", entryMessage),
                Arguments.of("current-directory implementation entry", IMPLEMENTATION, "dev/./Handler.class", entryMessage),
                Arguments.of("parent-directory implementation entry", IMPLEMENTATION, "dev/../Handler.class", entryMessage)
        );
    }

    private static RailixValue.ObjectValue contract() {
        return StepContractJson.value(definition());
    }

    private static void assertInvalid(final RailixValue.ObjectValue contract, final String message) {
        assertThatThrownBy(() -> StepContractJson.read(contract, IMPLEMENTATION, IMPLEMENTATION_ENTRY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static RailixValue.ObjectValue with(
            final RailixValue.ObjectValue value,
            final String name,
            final RailixValue replacement
    ) {
        final Map<String, RailixValue> fields = new LinkedHashMap<>(value.values());
        fields.put(name, replacement);
        return RailixValue.object(fields);
    }

    private static RailixValue.ObjectValue without(
            final RailixValue.ObjectValue value,
            final String name
    ) {
        final Map<String, RailixValue> fields = new LinkedHashMap<>(value.values());
        fields.remove(name);
        return RailixValue.object(fields);
    }

    private static RailixValue.ObjectValue withFirstInput(
            final RailixValue.ObjectValue contract,
            final UnaryOperator<RailixValue.ObjectValue> mutation
    ) {
        final List<RailixValue> inputs = new ArrayList<>(
                ((RailixValue.ArrayValue) contract.values().get("inputs")).values()
        );
        inputs.set(0, mutation.apply((RailixValue.ObjectValue) inputs.getFirst()));
        return with(contract, "inputs", RailixValue.array(inputs));
    }

    public static final class CodecHandler implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }
}
