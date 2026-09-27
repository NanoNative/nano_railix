package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Bundle-owned stateless Step implementations for generated matcher conformance applications. */
public final class MatcherConformanceSteps {
    private MatcherConformanceSteps() {
    }

    /** Continues from a Trigger through its primary outcome. */
    public static final class Trigger {
        public Trigger() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Returns the received value without changing it. */
    public static final class Identity {
        public Identity() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    /** Returns boolean true for every compatible input. */
    public static final class True {
        public True() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Returns true through the first matched authored route, or the primary route. */
    public static final class AuthoredTrue {
        public AuthoredTrue() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.selectedOutcomeOrPrimary("cases"))
                    .output("value", RailixValue.bool(true));
        }
    }

    /** Throws to prove deterministic nested implementation-fault handling. */
    public static final class Fault {
        public Fault() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            throw new IllegalStateException("fault");
        }
    }

    /** Interrupts the generated application's request thread. */
    public static final class Interrupt {
        public Interrupt() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Deliberately violates its declared boolean return contract. */
    public static final class Invalid {
        public Invalid() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("wrong"));
        }
    }

    /** Writes the resolved matcher-groups result through the declared target path. */
    public static final class Probe {
        public Probe() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("matches"));
        }
    }
}
