package thirdparty.production;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;

/** Advances an authored ordinal only when the received value is exactly the expected value. */
public final class ProductionRuntimeSequenceStep implements StepHandler {
    /** Creates one stateless sequence Step. */
    public ProductionRuntimeSequenceStep() {
    }

    @Override
    public StepResult run(final StepInput input) {
        final BigDecimal value = ((RailixValue.NumberValue) input.value("value")).value();
        final BigDecimal expected = ((RailixValue.NumberValue) input.value("expected")).value();
        return value.compareTo(expected) == 0
                ? StepResult.outcome("ok").output("value", RailixValue.number(value.add(BigDecimal.ONE)))
                : StepResult.outcome("invalid");
    }
}
