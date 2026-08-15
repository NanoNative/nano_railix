package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Bundle-owned stateless Step implementations for generated matcher conformance applications. */
public final class MatcherConformanceSteps {
    private MatcherConformanceSteps() {
    }

    /** Continues from a Trigger through its primary outcome. */
    public static final class Trigger implements StepHandler {
        public Trigger() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Returns the received value without changing it. */
    public static final class Identity implements StepHandler {
        public Identity() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    /** Returns boolean true for every compatible input. */
    public static final class True implements StepHandler {
        public True() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Throws to prove deterministic nested implementation-fault handling. */
    public static final class Fault implements StepHandler {
        public Fault() {
        }

        @Override
        public StepResult run(final StepInput input) {
            throw new IllegalStateException("fault");
        }
    }

    /** Interrupts the generated application's request thread. */
    public static final class Interrupt implements StepHandler {
        public Interrupt() {
        }

        @Override
        public StepResult run(final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Deliberately violates its declared boolean return contract. */
    public static final class Invalid implements StepHandler {
        public Invalid() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("wrong"));
        }
    }

    /** Writes the resolved matcher-groups result through the declared target path. */
    public static final class Probe implements StepHandler {
        public Probe() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("matches"));
        }
    }
}
