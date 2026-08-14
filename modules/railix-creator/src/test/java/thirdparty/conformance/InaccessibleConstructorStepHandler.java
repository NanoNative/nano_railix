package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

/** Bundle fixture that is a valid handler type but cannot be constructed by generated code. */
public final class InaccessibleConstructorStepHandler implements StepHandler {
    private InaccessibleConstructorStepHandler() {
    }

    @Override
    public StepResult run(final StepInput input) {
        return StepResult.outcome(input.primaryOutcome());
    }
}
