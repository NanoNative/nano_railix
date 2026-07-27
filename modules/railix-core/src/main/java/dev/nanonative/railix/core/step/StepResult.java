package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.LinkedHashMap;
import java.util.Map;

/** One declared control outcome plus named output values. */
public final class StepResult {
    private final String outcome;
    private final Map<String, RailixValue> outputs;

    private StepResult(final String outcome, final Map<String, RailixValue> outputs) {
        this.outcome = outcome;
        this.outputs = Map.copyOf(outputs);
    }

    public static StepResult outcome(final String outcome) {
        return new StepResult(outcome, Map.of());
    }

    public StepResult output(final String name, final RailixValue value) {
        final Map<String, RailixValue> next = new LinkedHashMap<>(outputs);
        next.put(name, value);
        return new StepResult(outcome, next);
    }

    public String outcome() {
        return outcome;
    }

    public Map<String, RailixValue> outputs() {
        return outputs;
    }
}
