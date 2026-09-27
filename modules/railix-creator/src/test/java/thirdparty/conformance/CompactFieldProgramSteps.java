package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Real third-party Step implementations for the compact-field generated application. */
public final class CompactFieldProgramSteps {
    private CompactFieldProgramSteps() {
    }

    /** Starts one flow through its primary outcome. */
    public static final class Trigger {
        /** Creates one stateless Trigger. */
        public Trigger() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Returns the received value unchanged. */
    public static final class Identity {
        /** Creates one stateless identity Step. */
        public Identity() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    /** Runs the declared nested program and writes its final value to the selected field. */
    public static final class Change {
        /** Creates one stateless field-changing Step. */
        public Change() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("steps").write("field");
        }
    }

    /** Interrupts the executing child request after the predicate handler runs. */
    public static final class Interrupt {
        /** Creates one stateless interruption Step. */
        public Interrupt() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Fails only for the canonical text value used by the fault scenarios. */
    public static final class Fault {
        /** Creates one stateless fault Step. */
        public Fault() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            if (RailixValue.string("fault").equals(input.value("value"))) {
                throw new IllegalStateException("predicate fault");
            }
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }
}
