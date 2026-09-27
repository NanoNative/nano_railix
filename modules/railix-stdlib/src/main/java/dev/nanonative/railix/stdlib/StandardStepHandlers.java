package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.Map;

/** Stateless implementations kept separate from Creator catalog metadata. */
public final class StandardStepHandlers {
    private StandardStepHandlers() {
    }

    /** Maps external CLI arguments to the configured workflow-context path. */
    public static final class Cli {
        /** Registers one finite argument invocation; source completion never stops another Trigger. */
        public static StepResult pre(final StepInput configuration, final StepInput context) {
            final StepInput.Source source = context.source();
            source.onStart(() -> Thread.startVirtualThread(() -> {
                int status = 1;
                try {
                    final var result = source.run(Map.of("arguments", RailixValue.array(
                            source.arguments().stream().<RailixValue>map(RailixValue::string).toList())));
                    status = switch (result.result()) {
                        case RunResult.Succeeded ignored -> success(result.responses());
                        case RunResult.Rejected rejected -> {
                            rejected.diagnostics().forEach(diagnostic -> System.err.println(
                                    diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message()));
                            yield 2;
                        }
                        case RunResult.Failed failed -> {
                            System.err.println(failed.failure().code() + " " + failed.failure().stepId()
                                    + " " + failed.failure().message());
                            yield 1;
                        }
                        case RunResult.Cancelled ignored -> 130;
                    };
                } finally {
                    source.complete(status);
                }
            }));
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("arguments"));
        }

        private static int success(final Map<String, RailixValue> responses) {
            if (!(responses.get("status") instanceof RailixValue.NumberValue status)) {
                System.err.println("CLI exit code must be a number.");
                return 2;
            }
            try {
                final int code = status.value().intValueExact();
                if (code < 0 || code > 255) {
                    System.err.println("CLI exit code must be from 0 through 255.");
                    return 2;
                }
                final RailixValue output = responses.get("output");
                if (!(output instanceof RailixValue.NullValue)) {
                    System.out.println(RailixJson.write(output));
                }
                return code;
            } catch (final ArithmeticException exception) {
                System.err.println("CLI exit code must be an integer.");
                return 2;
            }
        }
    }

    /** Applies a configured nested Step sequence and writes its value when present. */
    public static final class FieldManipulation {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("steps").writeWhenPresent("field");
        }
    }

    /** Routes a present candidate to match and an absent candidate to otherwise. */
    public static final class Filter {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.optionalValue("conditions").isPresent() ? "match" : "otherwise");
        }
    }

    /** Routes the matcher-groups Boolean to match or otherwise. */
    public static final class Choice {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(
                    RailixValue.bool(true).equals(input.value("conditions")) ? "match" : "otherwise"
            );
        }
    }

    /** Routes the first accepted authored candidate, or the Step's primary fallback. */
    public static final class Switch {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.selectedOutcomeOrPrimary("cases"));
        }
    }
}
