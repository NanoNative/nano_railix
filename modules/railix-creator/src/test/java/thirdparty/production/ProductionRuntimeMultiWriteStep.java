package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Performs two declared writes for generated-runtime ownership and rollback proof. */
public final class ProductionRuntimeMultiWriteStep {
    /** Creates one stateless multi-write Step. */
    public ProductionRuntimeMultiWriteStep() {
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        return StepResult.outcome(input.primaryOutcome())
                .write("first", RailixValue.bool(true))
                .write("second", RailixValue.bool(true));
    }
}
