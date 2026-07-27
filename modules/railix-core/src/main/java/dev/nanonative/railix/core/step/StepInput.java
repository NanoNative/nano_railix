package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.Map;

/** Inputs already resolved and shape-checked by the compiled runtime. */
public final class StepInput {
    private final Map<String, RailixValue> values;
    private final Map<String, RailixValue> config;

    public StepInput(
            final Map<String, RailixValue> values,
            final Map<String, RailixValue> config
    ) {
        this.values = Map.copyOf(values);
        this.config = Map.copyOf(config);
    }

    public RailixValue value(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Step input name cannot be Java null.");
        }
        if (!values.containsKey(name)) {
            throw new IllegalArgumentException("Step input is not available: " + name);
        }
        return values.get(name);
    }

    public String string(final String name) {
        final RailixValue value = value(name);
        if (!(value instanceof RailixValue.StringValue string)) {
            throw new IllegalArgumentException("Step input is not a string: " + name);
        }
        return string.value();
    }

    /** Returns one compiler-resolved configuration value. */
    public RailixValue configValue(final String name) {
        if (name == null) {
            throw new IllegalArgumentException("Step configuration name cannot be Java null.");
        }
        if (!config.containsKey(name)) {
            throw new IllegalArgumentException("Step configuration is not available: " + name);
        }
        return config.get(name);
    }

    /** Returns one compiler-resolved string configuration value. */
    public String configString(final String name) {
        final RailixValue value = configValue(name);
        if (!(value instanceof RailixValue.StringValue string)) {
            throw new IllegalArgumentException("Step configuration is not a string: " + name);
        }
        return string.value();
    }
}
