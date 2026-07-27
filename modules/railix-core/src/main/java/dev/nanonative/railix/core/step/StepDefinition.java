package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Immutable metadata and behavior for one named Step dependency. */
public final class StepDefinition {
    /** Execution-independent authoring classification for one Step definition. */
    public enum Kind {
        STEP,
        VALIDATOR,
        NORMALIZER,
        MAPPER,
        TRANSLATOR
    }

    /** Closed, serializable configuration semantics enforced by the compiler. */
    public enum ConfigFormat {
        DATA_FORMAT("data-format", ValueShape.STRING),
        LANGUAGE_TAG("language-tag", ValueShape.STRING),
        TIMEOUT_MILLIS("timeout-millis", ValueShape.NUMBER);

        private final String wireName;
        private final ValueShape shape;

        ConfigFormat(final String wireName, final ValueShape shape) {
            this.wireName = wireName;
            this.shape = shape;
        }

        public String wireName() {
            return wireName;
        }

        public ValueShape shape() {
            return shape;
        }

        /** Returns whether a value satisfies this format without normalizing it. */
        public boolean accepts(final RailixValue value) {
            if (this == TIMEOUT_MILLIS) {
                if (!(value instanceof RailixValue.NumberValue number)) {
                    return false;
                }
                try {
                    final long timeout = number.value().longValueExact();
                    return timeout >= 1 && timeout <= 300_000;
                } catch (final ArithmeticException exception) {
                    return false;
                }
            }
            if (!(value instanceof RailixValue.StringValue string)) {
                return false;
            }
            if (this == DATA_FORMAT) {
                return switch (string.value()) {
                    case "json", "xml", "yaml" -> true;
                    default -> false;
                };
            }
            try {
                new Locale.Builder().setLanguageTag(string.value());
                return true;
            } catch (final IllformedLocaleException exception) {
                return false;
            }
        }
    }

    private final String id;
    private final String version;
    private final Kind kind;
    private final List<Port> inputs;
    private final List<Port> outputs;
    private final List<Config> config;
    private final List<String> outcomes;
    private final Optional<StepHandler> handler;

    private StepDefinition(
            final String id,
            final String version,
            final Kind kind,
            final List<Port> inputs,
            final List<Port> outputs,
            final List<Config> config,
            final List<String> outcomes,
            final Optional<StepHandler> handler
    ) {
        this.id = text(id);
        this.version = text(version);
        this.kind = kind;
        this.inputs = immutable(inputs);
        this.outputs = immutable(outputs);
        this.config = immutable(config);
        this.outcomes = immutable(outcomes);
        this.handler = handler;
    }

    /** Creates a named Step with an explicit opaque implementation version. */
    public static Builder named(final String id, final String version) {
        return new Builder(id, version);
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    /** Returns authoring metadata only; execution never branches on this value. */
    public Kind kind() {
        return kind;
    }

    public List<Port> inputs() {
        return inputs;
    }

    public List<Port> outputs() {
        return outputs;
    }

    /** Configuration declared by the Step and resolved once when a flow compiles. */
    public List<Config> config() {
        return config;
    }

    public List<String> outcomes() {
        return outcomes;
    }

    public Optional<StepHandler> handler() {
        return handler;
    }

    public static final class Port {
        private final String name;
        private final Optional<ValueShape> shape;

        private Port(final String name, final ValueShape shape) {
            this.name = text(name);
            this.shape = Optional.ofNullable(shape);
        }

        public String name() {
            return name;
        }

        public Optional<ValueShape> shape() {
            return shape;
        }
    }

    /** One fixed per-flow configuration value, optionally backed by a visible default. */
    public static final class Config {
        private final String name;
        private final Optional<ValueShape> shape;
        private final Optional<ConfigFormat> format;
        private final List<RailixValue> defaultValue;

        private Config(
                final String name,
                final ValueShape shape,
                final Optional<ConfigFormat> format,
                final List<RailixValue> defaultValue
        ) {
            this.name = text(name);
            this.shape = Optional.ofNullable(shape);
            this.format = format;
            this.defaultValue = List.copyOf(defaultValue);
        }

        public String name() {
            return name;
        }

        public Optional<ValueShape> shape() {
            return shape;
        }

        public Optional<ConfigFormat> format() {
            return format;
        }

        public Optional<RailixValue> defaultValue() {
            return defaultValue.stream().findFirst();
        }

        public boolean required() {
            return defaultValue.isEmpty();
        }
    }

    public static final class Builder {
        private final String id;
        private final String version;
        private Kind kind = Kind.STEP;
        private final List<Port> inputs = new ArrayList<>();
        private final List<Port> outputs = new ArrayList<>();
        private final List<Config> config = new ArrayList<>();
        private final List<String> outcomes = new ArrayList<>();

        private Builder(final String id, final String version) {
            this.id = text(id);
            this.version = text(version);
        }

        /** Classifies this definition without changing its execution contract. */
        public Builder kind(final Kind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("Step kind cannot be Java null.");
            }
            this.kind = kind;
            return this;
        }

        public Builder input(final String name, final ValueShape shape) {
            inputs.add(new Port(name, shape));
            return this;
        }

        public Builder output(final String name, final ValueShape shape) {
            outputs.add(new Port(name, shape));
            return this;
        }

        /** Declares configuration with a Step-owned default exposed to flow authors. */
        public Builder config(final String name, final ValueShape shape, final RailixValue defaultValue) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Step configuration default cannot be Java null.");
            }
            config.add(new Config(name, shape, Optional.empty(), List.of(defaultValue)));
            return this;
        }

        /** Declares a compiler-enforced format with a visible Step-owned default. */
        public Builder config(
                final String name,
                final ValueShape shape,
                final ConfigFormat format,
                final RailixValue defaultValue
        ) {
            if (format == null) {
                throw new IllegalArgumentException("Step configuration format cannot be Java null.");
            }
            if (defaultValue == null) {
                throw new IllegalArgumentException("Step configuration default cannot be Java null.");
            }
            config.add(new Config(name, shape, Optional.of(format), List.of(defaultValue)));
            return this;
        }

        /** Declares configuration that every flow invocation must set explicitly. */
        public Builder requiredConfig(final String name, final ValueShape shape) {
            config.add(new Config(name, shape, Optional.empty(), List.of()));
            return this;
        }

        /** Declares required configuration with a compiler-enforced format. */
        public Builder requiredConfig(
                final String name,
                final ValueShape shape,
                final ConfigFormat format
        ) {
            if (format == null) {
                throw new IllegalArgumentException("Step configuration format cannot be Java null.");
            }
            config.add(new Config(name, shape, Optional.of(format), List.of()));
            return this;
        }

        public Builder outcome(final String outcome) {
            outcomes.add(text(outcome));
            return this;
        }

        public StepDefinition run(final StepHandler handler) {
            return new StepDefinition(
                    id,
                    version,
                    kind,
                    inputs,
                    outputs,
                    config,
                    outcomes,
                    Optional.ofNullable(handler)
            );
        }
    }

    private static <T> List<T> immutable(final List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String text(final String value) {
        return value == null ? "" : value;
    }
}
