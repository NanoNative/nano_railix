package dev.nanonative.railix.core;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class StepDeveloperApiContractE2eTest {
    @Test
    void nestedProgramJavaNullValuesAreRejectedExplicitly() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput.ProgramResult("next", "next", null))
                .withMessage("Nested Step values cannot be Java null.");
    }

    @Test
    void nestedProgramJavaNullValueElementIsRejectedExplicitly() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput.ProgramResult(
                        "next",
                        "next",
                        Arrays.asList(RailixValue.string("value"), null)
                ))
                .withMessage("Nested Step values cannot contain Java null.");
    }

    @Test
    void nestedProgramPrimaryOutcomeIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput.ProgramResult(null, "next", List.of(RailixValue.string("value"))))
                .withMessage("Nested Step outcomes must be non-blank strings.");
    }

    @Test
    void nestedProgramOutcomeIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput.ProgramResult("next", " ", List.of(RailixValue.string("value"))))
                .withMessage("Nested Step outcomes must be non-blank strings.");
    }

    @Test
    void completedNestedProgramMayExplicitlyProduceNoValue() {
        final StepInput.ProgramResult result = new StepInput.ProgramResult("next", "next", List.of());

        assertThat(result.outcome()).isEqualTo(result.primaryOutcome());
        assertThat(result.values()).isEmpty();
    }

    @Test
    void branchedNestedProgramCannotReturnAValue() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput.ProgramResult(
                        "next",
                        "missing",
                        List.of(RailixValue.string("value"))
                ))
                .withMessage("Branched nested Steps cannot return a value.");
    }

    @Test
    void stepInputValuesMapIsRequired() {
        assertInvocationMapsRejected(null, Map.of(), Map.of(), Map.of());
    }

    @Test
    void stepInputOptionsMapIsRequired() {
        assertInvocationMapsRejected(Map.of(), null, Map.of(), Map.of());
    }

    @Test
    void stepInputProgramsMapIsRequired() {
        assertInvocationMapsRejected(Map.of(), Map.of(), null, Map.of());
    }

    @Test
    void stepInputSelectedInputsMapIsRequired() {
        assertInvocationMapsRejected(Map.of(), Map.of(), Map.of(), null);
    }

    @Test
    void stepInputPrimaryOutcomeIsRequired() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), " "))
                .withMessage("Step primary outcome must be a non-blank string.");
    }

    @Test
    void missingStepInputValueIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().value("missing"))
                .withMessage("Step input is not available: missing");
    }

    @Test
    void blankOptionalStepInputNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().optionalValue(" "))
                .withMessage("Step input name must be a non-blank string.");
    }

    @Test
    void nonStringStepInputCannotBeReadAsAString() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().string("number"))
                .withMessage("Step input is not a string: number");
    }

    @Test
    void blankStepOptionNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().option(" "))
                .withMessage("Step option name must be a non-blank string.");
    }

    @Test
    void missingStepOptionIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().option("missing"))
                .withMessage("Step option is not available: missing");
    }

    @Test
    void selectedOptionInputsAreAvailableToTheStep() {
        assertThat(input().selected("choice").string("literal")).isEqualTo("selected");
    }

    @Test
    void blankSelectedInputNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().selected(" "))
                .withMessage("Selected input name must be a non-blank string.");
    }

    @Test
    void missingSelectedInputIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().selected("missing"))
                .withMessage("Selected input is not available: missing");
    }

    @Test
    void blankNestedStepInputNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().run(" "))
                .withMessage("Nested Step input name must be a non-blank string.");
    }

    @Test
    void missingNestedStepInputIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> input().run("missing"))
                .withMessage("Nested Step input is not available: missing");
    }

    @Test
    void JavaNullStepCatalogArrayIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepCatalog.of((StepDefinition[]) null))
                .withMessage("Step definitions cannot be Java null.");
    }

    @Test
    void JavaNullStepCatalogEntryIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepCatalog.of((StepDefinition) null))
                .withMessage("Step definition at index 0 cannot be Java null.");
    }

    @Test
    void duplicateStepCatalogDefinitionIsRejected() {
        final StepDefinition definition = definition();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepCatalog.of(definition, definition))
                .withMessage("Step definition id is duplicated: example.step");
    }

    @Test
    void JavaNullStepOutcomeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome(null))
                .withMessage("Step outcome must be a non-blank string.");
    }

    @Test
    void blankStepOutcomeIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome(" "))
                .withMessage("Step outcome must be a non-blank string.");
    }

    @Test
    void blankStepOutputNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome("next").output(" ", RailixValue.string("value")))
                .withMessage("Step output name must be a non-blank string.");
    }

    @Test
    void JavaNullStepOutputValueIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome("next").output("value", null))
                .withMessage("Step output value cannot be Java null.");
    }

    @Test
    void blankStepWriteNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome("next").write(" ", RailixValue.string("value")))
                .withMessage("Step write name must be a non-blank string.");
    }

    @Test
    void JavaNullStepWriteValueIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepResult.outcome("next").write("value", null))
                .withMessage("Step write value cannot be Java null.");
    }

    private static void assertInvocationMapsRejected(
            final Map<String, RailixValue> values,
            final Map<String, String> options,
            final Map<String, StepInput.Program> programs,
            final Map<String, StepInput> selected
    ) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepInput(values, options, programs, selected, "next"))
                .withMessage("Step invocation maps cannot be Java null.");
    }

    private static StepInput input() {
        final StepInput selected = new StepInput(
                Map.of("literal", RailixValue.string("selected")),
                Map.of(),
                Map.of(),
                Map.of(),
                "next"
        );
        return new StepInput(
                Map.of(
                        "text", RailixValue.string("value"),
                        "number", RailixValue.number(1)
                ),
                Map.of("choice", "literal"),
                Map.of("program", () -> new StepInput.ProgramResult(
                        "next",
                        "next",
                        List.of(RailixValue.string("value"))
                )),
                Map.of("choice", selected),
                "next"
        );
    }

    private static StepDefinition definition() {
        return StepDefinition.named("example.step", "1")
                .run(input -> StepResult.outcome(input.primaryOutcome()));
    }
}
