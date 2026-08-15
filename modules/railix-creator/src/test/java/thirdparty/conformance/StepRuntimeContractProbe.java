package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Adversarial third-party Step implementation used by generated runtime contract tests. */
public final class StepRuntimeContractProbe implements StepHandler {
    private static final AtomicReference<StepInput.Program> RETAINED = new AtomicReference<>();

    /** Creates one stateless probe. */
    public StepRuntimeContractProbe() {
    }

    /**
     * Exercises one contract violation selected by the immutable Step definition.
     *
     * @param input resolved Step input
     * @return the selected result; the {@code graph-null} case deliberately violates this contract
     * @throws InterruptedException when the cancellation case is selected
     */
    @Override
    public StepResult run(final StepInput input) throws InterruptedException {
        return switch (input.string("mode")) {
            case "primary" -> StepResult.outcome(input.primaryOutcome());
            case "graph-exception" -> exception(input);
            case "graph-null" -> null;
            case "graph-undeclared-outcome" -> StepResult.outcome("undeclared");
            case "graph-unexpected-output" -> StepResult.outcome(input.primaryOutcome())
                    .output("value", RailixValue.string("unexpected"));
            case "graph-unknown-write" -> StepResult.outcome(input.primaryOutcome())
                    .write("unknown", RailixValue.string("unexpected"));
            case "graph-read-only-write" -> StepResult.outcome(input.primaryOutcome())
                    .write("value", RailixValue.string("unexpected"));
            case "graph-interrupt" -> throw new InterruptedException("contract cancellation");
            case "incompatible-result" -> StepResult.outcome(input.primaryOutcome())
                    .write("result", RailixValue.string("wrong"));
            case "graph-multi-write", "graph-multi-write-sparse" -> StepResult.outcome(input.primaryOutcome())
                    .write("first", RailixValue.string("first"))
                    .write("second", RailixValue.string("second"));
            case "graph-multi-write-order" -> StepResult.outcome(input.primaryOutcome())
                    .write("second", RailixValue.string("child"))
                    .write("first", RailixValue.string("parent"));
            case "interrupt-after-result" -> interruptedWrite(input);
            case "secondary-output" -> StepResult.outcome("secondary")
                    .output("value", RailixValue.string("unexpected"));
            case "output-shape" -> output(input, RailixValue.number(1));
            case "output-number-domain" -> output(input, nonCanonicalNumber());
            case "write-number-domain" -> StepResult.outcome(input.primaryOutcome())
                    .write("result", nonCanonicalNumber());
            case "run-nested-preinterrupted" -> runNested(input, true);
            case "run-nested-write", "run-nested-secondary-output", "run-nested-number-domain",
                 "run-nested-null", "run-nested-exception", "run-nested-undeclared-outcome",
                 "run-nested-interrupt" ->
                    runNested(input, false);
            case "nested-identity" -> output(input, input.value("value"));
            case "nested-null" -> null;
            case "nested-exception" -> exception(input);
            case "nested-undeclared-outcome" -> StepResult.outcome("undeclared");
            case "nested-interrupt" -> throw new InterruptedException("nested contract cancellation");
            case "nested-write" -> StepResult.outcome(input.primaryOutcome())
                    .write("context", RailixValue.string("unexpected"));
            case "nested-secondary-output" -> StepResult.outcome("secondary")
                    .output("value", RailixValue.string("unexpected"));
            case "nested-number-domain" -> output(input, nonCanonicalNumber());
            case "retain-nested" -> retainNested(input);
            case "invoke-retained" -> invokeRetained(input);
            case "run-nested-wrong-thread" -> runNestedOnAnotherThread(input);
            case "payload-number-domain" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", nonCanonicalNumber());
            case "payload-unicode" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string(Character.toString((char) 0xD800)));
            case "payload-depth" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", overDepthValue());
            case "array-descend", "array-replace" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("written"));
            case "array-sparse-existing", "array-scalar-conflict" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("unexpected"));
            case "array-later-read", "array-fork-copy" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("materialized"));
            case "array-cache-invalidate" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("before"));
            case "array-later-read-tail" -> StepResult.outcome(input.primaryOutcome())
                    .write("observed", input.value("received"));
            case "array-fork-copy-tail" -> StepResult.outcome(input.primaryOutcome())
                    .write("first", RailixValue.string("first"))
                    .write("second", RailixValue.string("second"));
            case "array-cache-invalidate-tail" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("after"));
            case "indexed-scalar-missing" -> StepResult.outcome(input.primaryOutcome())
                    .write("observed", RailixValue.string(
                            input.optionalValue("probe").isPresent() ? "present" : "missing"
                    ));
            case "source-always-fault" -> throw new IllegalStateException("private detail");
            case "source-write-target" -> StepResult.outcome(input.primaryOutcome())
                    .write("target", input.value("value"));
            case "refined-source-guard" -> refinedSource(input);
            case "unrefined-source-verifier" -> unrefinedSource(input);
            default -> throw new IllegalStateException("Unknown runtime contract probe mode.");
        };
    }

    private static StepResult exception(final StepInput input) {
        if ("fault".equals(input.string("value"))) {
            throw new IllegalStateException("private detail");
        }
        return StepResult.outcome(input.primaryOutcome());
    }

    private static StepResult interruptedWrite(final StepInput input) {
        Thread.currentThread().interrupt();
        return StepResult.outcome(input.primaryOutcome())
                .write("target", RailixValue.string("must-not-commit"));
    }

    private static StepResult runNested(final StepInput input, final boolean interrupted)
            throws InterruptedException {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return StepResult.outcome(input.run("steps").outcome());
    }

    private static StepResult retainNested(final StepInput input) {
        RETAINED.set(() -> input.run("steps"));
        return StepResult.outcome(input.primaryOutcome());
    }

    private static StepResult invokeRetained(final StepInput input) throws InterruptedException {
        final StepInput.Program retained = RETAINED.getAndSet(null);
        if (retained == null) {
            return StepResult.outcome(input.primaryOutcome())
                    .write("observed", RailixValue.string("missing"));
        }
        try {
            retained.run();
            return StepResult.outcome(input.primaryOutcome())
                    .write("observed", RailixValue.string("active"));
        } catch (final IllegalStateException expected) {
            return StepResult.outcome(input.primaryOutcome())
                    .write("observed", RailixValue.string("closed"));
        }
    }

    private static StepResult runNestedOnAnotherThread(final StepInput input) throws InterruptedException {
        final AtomicReference<String> observed = new AtomicReference<>("executed");
        final Thread thread = Thread.ofVirtual().start(() -> {
            try {
                input.run("steps");
            } catch (final IllegalStateException expected) {
                observed.set("wrong-thread");
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                observed.set("interrupted");
            }
        });
        thread.join();
        return StepResult.outcome(input.primaryOutcome())
                .write("observed", RailixValue.string(observed.get()));
    }

    private static StepResult output(final StepInput input, final RailixValue value) {
        return StepResult.outcome(input.primaryOutcome()).output("value", value);
    }

    private static RailixValue nonCanonicalNumber() {
        return RailixValue.number(BigDecimal.TEN.pow(1_024));
    }

    private static RailixValue overDepthValue() {
        RailixValue value = RailixValue.nullValue();
        for (int depth = 0; depth <= 64; depth++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }

    private static StepResult refinedSource(final StepInput input) {
        if (input.optionalValue("value").isPresent()) {
            throw new IllegalStateException("Refined source value reached the handler.");
        }
        return StepResult.outcome(input.primaryOutcome());
    }

    private static StepResult unrefinedSource(final StepInput input) {
        final RailixValue expected = RailixValue.array(List.of(
                RailixValue.number(BigDecimal.TEN.pow(1_024)),
                RailixValue.string(Character.toString((char) 0xD800))
        ));
        return StepResult.outcome(input.value("value").equals(expected)
                ? input.primaryOutcome()
                : "unexpected");
    }
}
