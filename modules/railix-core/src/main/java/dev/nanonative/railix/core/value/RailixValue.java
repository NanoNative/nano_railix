package dev.nanonative.railix.core.value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical values exchanged by Railix Steps. Java {@code null} is not a Railix value. */
public sealed interface RailixValue permits
        RailixValue.NullValue,
        RailixValue.BooleanValue,
        RailixValue.NumberValue,
        RailixValue.StringValue,
        RailixValue.ArrayValue,
        RailixValue.ObjectValue {

    static RailixValue nullValue() {
        return NullValue.INSTANCE;
    }

    static BooleanValue bool(final boolean value) {
        return new BooleanValue(value);
    }

    static NumberValue number(final BigDecimal value) {
        return new NumberValue(value);
    }

    static NumberValue number(final long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    static StringValue string(final String value) {
        return new StringValue(value);
    }

    static ArrayValue array(final List<RailixValue> values) {
        return new ArrayValue(values);
    }

    static ObjectValue object(final Map<String, RailixValue> values) {
        return new ObjectValue(values);
    }

    record NullValue() implements RailixValue {
        private static final NullValue INSTANCE = new NullValue();
    }

    record BooleanValue(boolean value) implements RailixValue {
    }

    record NumberValue(BigDecimal value) implements RailixValue {
        public NumberValue {
            if (value == null) {
                throw new IllegalArgumentException("Railix number cannot be Java null.");
            }
        }
    }

    record StringValue(String value) implements RailixValue {
        public StringValue {
            if (value == null) {
                throw new IllegalArgumentException("Railix string cannot be Java null.");
            }
        }
    }

    record ArrayValue(List<RailixValue> values) implements RailixValue {
        public ArrayValue {
            values = immutableArray(values);
        }
    }

    record ObjectValue(Map<String, RailixValue> values) implements RailixValue {
        public ObjectValue {
            values = immutableObject(values);
        }
    }

    private static List<RailixValue> immutableArray(final List<RailixValue> values) {
        if (values == null) {
            throw new IllegalArgumentException("Railix array values cannot be Java null.");
        }
        final List<RailixValue> copy = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            final RailixValue value = values.get(index);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Railix array value at index " + index + " cannot be Java null."
                );
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, RailixValue> immutableObject(final Map<String, RailixValue> values) {
        if (values == null) {
            throw new IllegalArgumentException("Railix object values cannot be Java null.");
        }
        final Map<String, RailixValue> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, RailixValue> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("Railix object field name cannot be Java null.");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "Railix object field '" + entry.getKey() + "' cannot be Java null."
                );
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
