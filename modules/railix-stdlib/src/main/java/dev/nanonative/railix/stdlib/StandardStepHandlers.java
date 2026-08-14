package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

/** Stateless implementations kept separate from Creator catalog metadata. */
public final class StandardStepHandlers {
    private StandardStepHandlers() {
    }

    /** Maps external CLI arguments to the configured workflow-context path. */
    public static final class Cli implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("arguments"));
        }
    }

    /** Applies a configured nested Step sequence and writes its value when present. */
    public static final class FieldManipulation implements StepHandler {
        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("steps").writeWhenPresent("field");
        }
    }

    /** Routes a present candidate to match and an absent candidate to otherwise. */
    public static final class Filter implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.optionalValue("conditions").isPresent() ? "match" : "otherwise");
        }
    }

    /** Routes the matcher-groups Boolean to match or otherwise. */
    public static final class Choice implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(
                    RailixValue.bool(true).equals(input.value("conditions")) ? "match" : "otherwise"
            );
        }
    }
}
