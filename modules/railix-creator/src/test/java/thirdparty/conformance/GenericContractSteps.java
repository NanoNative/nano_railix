package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

/** Real third-party Step implementations used by the generic contract conformance application. */
public final class GenericContractSteps {
    private GenericContractSteps() {
    }

    /** Passes control from a source Step to its primary outcome. */
    public static final class Trigger implements StepHandler {
        /** Creates one stateless trigger handler. */
        public Trigger() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Runs a declared nested Step list and writes its final value to the declared field. */
    public static final class Operation implements StepHandler {
        /** Creates one stateless operation handler. */
        public Operation() {
        }

        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("operations").write("field");
        }
    }

    /** Writes the value resolved by one generic tagged option when that option supplies a value. */
    public static final class Option implements StepHandler {
        /** Creates one stateless option handler. */
        public Option() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return input.optionalValue("choice")
                    .map(value -> StepResult.outcome(input.primaryOutcome()).write("target", value))
                    .orElseGet(() -> StepResult.outcome(input.primaryOutcome()));
        }
    }
}
