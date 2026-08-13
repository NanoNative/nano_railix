package thirdparty.production;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Stateless third-party-style Steps used to exhaust every generated routing edge. */
public final class ProductionRuntimeBranchSteps {
    private ProductionRuntimeBranchSteps() {
    }

    /** Selects one binary outcome from the configured character position. */
    public static final class Branch implements StepHandler {
        /** Creates one stateless branch Step. */
        public Branch() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final int depth = ((RailixValue.NumberValue) input.value("depth")).value().intValueExact();
            return StepResult.outcome(input.string("route").charAt(depth) == '0' ? "zero" : "one");
        }
    }

    /** Returns the route only when it reaches its uniquely authored leaf. */
    public static final class Leaf implements StepHandler {
        /** Creates one stateless leaf Step. */
        public Leaf() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final String route = input.string("route");
            return route.equals(input.string("expected"))
                    ? StepResult.outcome("ok").output("value", RailixValue.string(route))
                    : StepResult.outcome("invalid");
        }
    }
}
