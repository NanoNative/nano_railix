package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Stateless third-party-style Steps used to exhaust every generated routing edge. */
public final class ProductionRuntimeBranchSteps {
    private ProductionRuntimeBranchSteps() {
    }

    /** Selects one binary outcome from the configured character position. */
    public static final class Branch {
        /** Creates one stateless branch Step. */
        public Branch() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            final int depth = ((RailixValue.NumberValue) input.value("depth")).value().intValueExact();
            return StepResult.outcome(input.string("route").charAt(depth) == '0' ? "zero" : "one");
        }
    }

    /** Returns the route only when it reaches its uniquely authored leaf. */
    public static final class Leaf {
        /** Creates one stateless leaf Step. */
        public Leaf() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            final String route = input.string("route");
            return route.equals(input.string("expected"))
                    ? StepResult.outcome("ok").output("value", RailixValue.string(route))
                    : StepResult.outcome("invalid");
        }
    }
}
