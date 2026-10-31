package dev.nanonative.railix.core;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.Locale;

public final class TestStepHandlers {
    private TestStepHandlers() {
    }

    public static final class PrimaryOutcome {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static final class PrimaryIdentity {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", input.value("value"));
        }
    }

    public static final class OkIdentity {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome("ok").output("value", input.value("value"));
        }
    }

    public static final class LowercaseValue {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome("ok").output(
                    "value",
                    RailixValue.string(input.string("value").toLowerCase(Locale.ROOT))
            );
        }
    }

    public static final class SourceResultIdentity {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("result", input.value("source"));
        }
    }

    public static final class WriteArguments {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("arguments"));
        }
    }

    public static final class RunOperationsWriteField {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("operations").write("field");
        }
    }

    public static final class RunStepsWriteField {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("steps").write("field");
        }
    }

    public static final class RunStepsWriteWhenPresentField {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("steps").writeWhenPresent("field");
        }
    }

    public static final class EqualsExpected {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(input.value("value").equals(input.value("expected")))
            );
        }
    }

    public static final class BooleanNot {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(!((RailixValue.BooleanValue) input.value("value")).value())
            );
        }
    }

    public static final class TextLength {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.number(((RailixValue.StringValue) input.value("value")).value().length())
            );
        }
    }

    public static final class True {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    public static final class GreaterThan {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                            ((RailixValue.NumberValue) input.value("than")).value()
                    ) > 0)
            );
        }
    }

    public static final class LessThan {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output(
                    "value",
                    RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                            ((RailixValue.NumberValue) input.value("than")).value()
                    ) < 0)
            );
        }
    }

    public static final class InterruptAndTrue {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        }
    }

    public static final class AlwaysFault {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            throw new IllegalStateException("fault");
        }
    }

    public static final class CompilationMustNotExecute {
        private static final boolean INITIALIZED = failInitialization();

        private static boolean failInitialization() {
            throw new AssertionError("Compiler initialized a Step class.");
        }

        public CompilationMustNotExecute() {
            throw new AssertionError("Compiler constructed a Step handler.");
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            throw new AssertionError("Compiler invoked a Step handler.");
        }
    }

    public static final class InvalidString {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("wrong"));
        }
    }

    public static final class PrimaryConstantValue {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("value"));
        }
    }

    public static final class WriteMatches {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("matches"));
        }
    }

    public static final class ReadChoice {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            input.option("choice");
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static final class ToNumber {
        public static StepResult process(final StepInput configuration, final StepInput input) {
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
