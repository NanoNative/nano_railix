package dev.nanonative.railix.core.value;

/** Structural value categories used by the first compiler boundary. */
public enum ValueShape {
    ANY,
    NULL,
    BOOLEAN,
    NUMBER,
    STRING,
    ARRAY,
    OBJECT;

    public static ValueShape string() {
        return STRING;
    }

    public static ValueShape number() {
        return NUMBER;
    }

    public boolean accepts(final ValueShape source) {
        return this == ANY || this == source;
    }

    public boolean accepts(final RailixValue value) {
        return this == ANY || this == shapeOf(value);
    }

    public static ValueShape shapeOf(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> NULL;
            case RailixValue.BooleanValue ignored -> BOOLEAN;
            case RailixValue.NumberValue ignored -> NUMBER;
            case RailixValue.StringValue ignored -> STRING;
            case RailixValue.ArrayValue ignored -> ARRAY;
            case RailixValue.ObjectValue ignored -> OBJECT;
        };
    }
}
