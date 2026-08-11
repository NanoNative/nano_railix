package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** One declared control outcome plus named output values. */
public final class StepResult {
    private final String outcome;
    private final Map<String, RailixValue> outputs;
    private final Map<String, RailixValue> writes;

    private StepResult(
            final String outcome,
            final Map<String, RailixValue> outputs,
            final Map<String, RailixValue> writes
    ) {
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("Step outcome must be a non-blank string.");
        }
        this.outcome = outcome;
        this.outputs = Map.copyOf(outputs);
        this.writes = Map.copyOf(writes);
    }

    public static StepResult outcome(final String outcome) {
        return new StepResult(outcome, Map.of(), Map.of());
    }

    public StepResult output(final String name, final RailixValue value) {
        valid(name, value, "output");
        final Map<String, RailixValue> next = new LinkedHashMap<>(outputs);
        next.put(name, value);
        return new StepResult(outcome, next, writes);
    }

    /** Writes one value through a declared writable PATH input of the enclosing Step. */
    public StepResult write(final String input, final RailixValue value) {
        valid(input, value, "write");
        final Map<String, RailixValue> next = new LinkedHashMap<>(writes);
        next.put(input, value);
        return new StepResult(outcome, outputs, next);
    }

    public String outcome() {
        return outcome;
    }

    public Map<String, RailixValue> outputs() {
        return outputs;
    }

    public Map<String, RailixValue> writes() {
        return writes;
    }

    private static void valid(final String name, final RailixValue value, final String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step " + label + " name must be a non-blank string.");
        }
        if (value == null) {
            throw new IllegalArgumentException("Step " + label + " value cannot be Java null.");
        }
    }
}
