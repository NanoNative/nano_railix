package dev.nanonative.railix.core;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.Locale;

final class TestStepHandlers {
    private TestStepHandlers() {
    }

    static final class PrimaryOutcome implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    static final class PrimaryIdentity implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    static final class OkIdentity implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome("ok").output("value", input.value("value"));
        }
    }

    static final class LowercaseValue implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome("ok").output(
                    "value",
                    RailixValue.string(input.string("value").toLowerCase(Locale.ROOT))
            );
        }
    }

    static final class SourceResultIdentity implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("result", input.value("source"));
        }
    }

    static final class WriteArguments implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("arguments"));
        }
    }

    static final class RunOperationsWriteField implements StepHandler {
        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("operations").write("field");
        }
    }

    static final class RunStepsWriteField implements StepHandler {
        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("steps").write("field");
        }
    }

    static final class RunStepsWriteWhenPresentField implements StepHandler {
        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            return input.run("steps").writeWhenPresent("field");
        }
    }

    static final class EqualsExpected implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(input.value("value").equals(input.value("expected")))
            );
        }
    }

    static final class BooleanNot implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(!((RailixValue.BooleanValue) input.value("value")).value())
            );
        }
    }

    static final class TextLength implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.number(((RailixValue.StringValue) input.value("value")).value().length())
            );
        }
    }

    static final class True implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    static final class GreaterThan implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                            ((RailixValue.NumberValue) input.value("than")).value()
                    ) > 0)
            );
        }
    }

    static final class LessThan implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                            ((RailixValue.NumberValue) input.value("than")).value()
                    ) < 0)
            );
        }
    }

    static final class InterruptAndTrue implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    static final class AlwaysFault implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            throw new IllegalStateException("fault");
        }
    }

    static final class InvalidString implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("wrong"));
        }
    }

    static final class PrimaryConstantValue implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("value"));
        }
    }

    static final class WriteMatches implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("matches"));
        }
    }

    static final class ReadChoice implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            input.option("choice");
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    static final class ToNumber implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            try {
                return StepResult.outcome("ok").output(
                        "value",
                        RailixValue.number(new BigDecimal(input.string("value")))
                );
            } catch (NumberFormatException exception) {
                return StepResult.outcome("invalid");
            }
        }
    }
}
