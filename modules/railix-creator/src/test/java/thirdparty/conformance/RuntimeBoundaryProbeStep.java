package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;

/** Real bundle-owned Step used by generated-runtime boundary tests. */
public final class RuntimeBoundaryProbeStep {
    private RuntimeBoundaryProbeStep() {
        throw new AssertionError("Static Steps must never be constructed.");
    }

    /** Returns the bound value, or the explicitly requested invalid output test value. */
    public static StepResult process(final StepInput configuration, final StepInput input) {
        if (input.optionalValue("invalid")
                .map(RailixValue.BooleanValue.class::cast)
                .map(RailixValue.BooleanValue::value)
                .orElse(false)) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.array(List.of(RailixValue.string("\uD800")))
            );
        }
        return input.optionalValue("value")
                .map(value -> StepResult.outcome(input.primaryOutcome()).output("value", value))
                .orElseGet(() -> StepResult.outcome(input.primaryOutcome()));
    }
}
