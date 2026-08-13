package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Real third-party Step implementations for the compact-field generated application. */
public final class CompactFieldProgramSteps {
    private CompactFieldProgramSteps() {
    }

    /** Starts one flow through its primary outcome. */
    public static final class Trigger implements StepHandler {
        /** Creates one stateless Trigger. */
        public Trigger() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Returns the received value unchanged. */
    public static final class Identity implements StepHandler {
        /** Creates one stateless identity Step. */
        public Identity() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    /** Runs the declared nested program and writes its final value to the selected field. */
    public static final class Change implements StepHandler {
        /** Creates one stateless field-changing Step. */
        public Change() {
        }

        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("steps").write("field");
        }
    }

    /** Interrupts the executing child request after the predicate handler runs. */
    public static final class Interrupt implements StepHandler {
        /** Creates one stateless interruption Step. */
        public Interrupt() {
        }

        @Override
        public StepResult run(final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    /** Fails only for the canonical text value used by the fault scenarios. */
    public static final class Fault implements StepHandler {
        /** Creates one stateless fault Step. */
        public Fault() {
        }

        @Override
        public StepResult run(final StepInput input) {
            if (RailixValue.string("fault").equals(input.value("value"))) {
                throw new IllegalStateException("predicate fault");
            }
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }
}
