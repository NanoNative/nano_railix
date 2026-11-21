package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Typed, zero-allocation comparable key and scalar index value for {@code railix.database}.
 *
 * <p>Ordering matches the canonical store order: booleans ({@code false < true}), then numbers
 * by exact {@link BigDecimal} value, then strings by UTF-16 code unit order.</p>
 */
sealed interface DatabaseKey extends Comparable<DatabaseKey>
        permits DatabaseKey.BoolKey, DatabaseKey.NumberKey, DatabaseKey.StringKey {

    byte TYPE_BOOL = 0;
    byte TYPE_NUMBER = 1;
    byte TYPE_STRING = 2;

    StringKey EMPTY_STRING = new StringKey("");

    byte typeTag();

    @Override
    default int compareTo(final DatabaseKey other) {
        final int tagCompare = Byte.compare(typeTag(), other.typeTag());
        if (tagCompare != 0) {
            return tagCompare;
        }
        return switch (this) {
            case BoolKey left -> Boolean.compare(left.value(), ((BoolKey) other).value());
            case NumberKey left -> left.value().compareTo(((NumberKey) other).value());
            case StringKey left -> left.value().compareTo(((StringKey) other).value());
        };
    }

    byte wireKeyType();

    String wireKeyText();

    default byte[] wireKeyBytes() {
        return wireKeyText().getBytes(StandardCharsets.UTF_8);
    }

    default int bucket() {
        return bucketOf(wireKeyType(), wireKeyText());
    }

    static DatabaseKey encodePrimary(final RailixValue key) {
        if (key instanceof RailixValue.StringValue string) {
            final String raw = string.value();
            if (raw.isEmpty() || invalidSurrogate(raw)) {
                return null;
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > DatabaseJournal.MAX_KEY_BYTES) {
                return null;
            }
            return new StringKey(raw);
        }
        if (key instanceof RailixValue.NumberValue number) {
            final BigDecimal raw = number.value();
            if (!RailixData.fitsCanonicalNumber(raw)) {
                return null;
            }
            final NumberKey candidate = NumberKey.of(raw);
            if (candidate.encoded().getBytes(StandardCharsets.UTF_8).length > DatabaseJournal.MAX_KEY_BYTES) {
                return null;
            }
            return candidate;
        }
        return null;
    }

    static DatabaseKey encodeIndexValue(final RailixValue value) {
        return switch (value) {
            case null -> null;
            case RailixValue.StringValue string -> string.value().isEmpty()
                    ? EMPTY_STRING
                    : new StringKey(string.value());
            case RailixValue.NumberValue number -> RailixData.fitsCanonicalNumber(number.value())
                    ? NumberKey.of(number.value())
                    : null;
            case RailixValue.BooleanValue bool -> bool.value() ? BoolKey.TRUE : BoolKey.FALSE;
            default -> null;
        };
    }

    static DatabaseKey fromJournal(final byte keyType, final String rawKey) {
        if (keyType == DatabaseJournal.KEY_STRING) {
            return rawKey.isEmpty() ? null : new StringKey(rawKey);
        }
        if (keyType == DatabaseJournal.KEY_NUMBER) {
            if (rawKey.isEmpty()) {
                return null;
            }
            try {
                final BigDecimal parsed = new BigDecimal(rawKey);
                if (!RailixData.fitsCanonicalNumber(parsed)) {
                    return null;
                }
                final NumberKey candidate = NumberKey.of(parsed);
                return candidate.encoded().equals(rawKey) ? candidate : null;
            } catch (final NumberFormatException invalid) {
                return null;
            }
        }
        return null;
    }

    static int bucketOf(final byte keyType, final String rawKey) {
        final CRC32 crc = new CRC32();
        crc.update(keyType == DatabaseJournal.KEY_STRING ? 's' : 'n');
        crc.update(':');
        final byte[] bytes = rawKey.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return ((int) crc.getValue()) & DatabaseJournal.BUCKET_MASK;
    }

    static RailixValue.ObjectValue canonicalObject(final RailixValue.ObjectValue object) {
        return (RailixValue.ObjectValue) canonicalValue(object);
    }

    static RailixValue canonicalValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> RailixValue.nullValue();
            case RailixValue.BooleanValue bool -> bool;
            case RailixValue.StringValue string -> string;
            case RailixValue.NumberValue number -> {
                final BigDecimal raw = number.value();
                final BigDecimal stripped = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
                final BigDecimal canonical = stripped.scale() < 0 ? stripped.setScale(0) : stripped;
                yield canonical.equals(raw) ? number : RailixValue.number(canonical);
            }
            case RailixValue.ArrayValue array -> {
                final List<RailixValue> items = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    items.add(canonicalValue(item));
                }
                yield RailixValue.array(items);
            }
            case RailixValue.ObjectValue object -> {
                final Map<String, RailixValue> fields = new TreeMap<>();
                for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
                    fields.put(entry.getKey(), canonicalValue(entry.getValue()));
                }
                yield RailixValue.object(fields);
            }
        };
    }

    static boolean invalidSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    record BoolKey(boolean value) implements DatabaseKey {
        static final BoolKey FALSE = new BoolKey(false);
        static final BoolKey TRUE = new BoolKey(true);

        @Override
        public byte typeTag() {
            return TYPE_BOOL;
        }

        @Override
        public byte wireKeyType() {
            throw new UnsupportedOperationException("Boolean values cannot be primary keys.");
        }

        @Override
        public String wireKeyText() {
            throw new UnsupportedOperationException("Boolean values cannot be primary keys.");
        }
    }

    record NumberKey(BigDecimal value, String encoded) implements DatabaseKey {
        static NumberKey of(final BigDecimal raw) {
            final BigDecimal normalized = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
            return new NumberKey(normalized, normalized.toString());
        }

        @Override
        public byte typeTag() {
            return TYPE_NUMBER;
        }

        @Override
        public byte wireKeyType() {
            return DatabaseJournal.KEY_NUMBER;
        }

        @Override
        public String wireKeyText() {
            return encoded;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof NumberKey other && encoded.equals(other.encoded);
        }

        @Override
        public int hashCode() {
            return encoded.hashCode();
        }
    }

    record StringKey(String value) implements DatabaseKey {
        @Override
        public byte typeTag() {
            return TYPE_STRING;
        }

        @Override
        public byte wireKeyType() {
            return DatabaseJournal.KEY_STRING;
        }

        @Override
        public String wireKeyText() {
            return value;
        }
    }
}
