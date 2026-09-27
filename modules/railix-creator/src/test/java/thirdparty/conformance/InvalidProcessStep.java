package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

/** Bundle fixture with a legacy instance method instead of the required static PROCESS. */
public final class InvalidProcessStep {
    private InvalidProcessStep() {
    }

    public StepResult process(final StepInput configuration, final StepInput input) {
        return StepResult.outcome(input.primaryOutcome());
    }
}
