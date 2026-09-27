package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.concurrent.ConcurrentHashMap;

/** Real third-party Step implementations used by the generic contract conformance application. */
public final class GenericContractSteps {
    private GenericContractSteps() {
    }

    /** Passes control from a source Step to its primary outcome. */
    public static final class Trigger {
        /** Creates one stateless trigger handler. */
        public Trigger() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Runs a declared nested Step list and writes its final value to the declared field. */
    public static final class Operation {
        /** Creates one stateless operation handler. */
        public Operation() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("operations").write("field");
        }
    }

    /** Writes the value resolved by one generic tagged option when that option supplies a value. */
    public static final class Option {
        private static final ConcurrentHashMap<String, StepInput> CONFIGURATIONS = new ConcurrentHashMap<>();
        /** Creates one stateless option handler. */
        public Option() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            final String choice = configuration.option("choice");
            final StepInput previous = CONFIGURATIONS.putIfAbsent(choice, configuration);
            if (configuration == input || previous != null && previous != configuration
                    || !(configuration.value("target") instanceof RailixValue.ArrayValue)
                    || !choice.equals(input.option("choice"))) {
                throw new IllegalStateException("Compiled configuration must be separate, immutable and reused.");
            }
            if (choice.equals("literal")
                    && configuration.selected("choice").value("value") != input.selected("choice").value("value")) {
                throw new IllegalStateException("Configuration and invocation must reuse literal constants.");
            }
            return input.optionalValue("choice")
                    .map(value -> StepResult.outcome(input.primaryOutcome()).write("target", value))
                    .orElseGet(() -> StepResult.outcome(input.primaryOutcome()));
        }
    }
}
