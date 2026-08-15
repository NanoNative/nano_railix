package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.List;

/** Real third-party Step implementations used by the generated execution conformance application. */
public final class CreatorFirstExecutionSteps {
    private CreatorFirstExecutionSteps() {
    }

    /** Stateless Trigger implementation for explicit run and source boundaries. */
    public static final class Trigger implements StepHandler {
        /** Creates one stateless Trigger. */
        public Trigger() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Stateless adversarial value Step selected by an immutable contract input. */
    public static final class Probe implements StepHandler {
        /** Creates one stateless value Step. */
        public Probe() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final String behavior = input.string("behavior");
            final RailixValue value = input.optionalValue("value").orElse(RailixValue.nullValue());
            return switch (behavior) {
                case "identity" -> output(input, value);
                case "reject-noncanonical-input" -> rejectNonCanonicalInput(input, value);
                case "canonical-output" -> output(
                        input,
                        RailixValue.string("surrogate").equals(value)
                                ? RailixValue.array(List.of(RailixValue.string("\uD800")))
                                : nonCanonicalNumber()
                );
                case "nested-depth" -> output(input, RailixValue.array(List.of(
                        RailixValue.array(List.of(RailixValue.nullValue()))
                )));
                case "long-string" -> output(input, RailixValue.string("longer"));
                case "surrogate" -> output(input, RailixValue.array(List.of(RailixValue.string("\uD800"))));
                case "echo-mixed" -> output(
                        input,
                        mixedNonCanonical().equals(value) ? value : RailixValue.nullValue()
                );
                case "java-null" -> null;
                case "undeclared-outcome" -> StepResult.outcome("other");
                case "missing-output" -> StepResult.outcome(input.primaryOutcome());
                case "number-output" -> output(input, RailixValue.number(1));
                case "exception" -> throw new IllegalStateException("private detail");
                default -> throw new IllegalStateException("Unknown conformance behavior: " + behavior + ".");
            };
        }

        private static StepResult rejectNonCanonicalInput(final StepInput input, final RailixValue value) {
            if (nonCanonicalNumber().equals(value)) {
                throw new IllegalStateException("Refined input reached the handler.");
            }
            return output(input, value);
        }

        private static StepResult output(final StepInput input, final RailixValue value) {
            return StepResult.outcome(input.primaryOutcome()).output("value", value);
        }

        private static RailixValue nonCanonicalNumber() {
            return RailixValue.array(List.of(RailixValue.number(
                    BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
            )));
        }

        private static RailixValue mixedNonCanonical() {
            return RailixValue.array(List.of(
                    RailixValue.number(BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)),
                    RailixValue.string("\uD800")
            ));
        }
    }
}
