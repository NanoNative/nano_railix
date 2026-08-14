package dev.nanonative.railix.core.value;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit recursive constraints for a Step value port; absent constraints do no traversal. */
public record ValueRefinement(boolean canonicalValues, int maxDepth, int maxJsonBytes) {
    /** Validates one immutable refinement declaration. Zero means the corresponding bound is absent. */
    public ValueRefinement {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("Maximum value depth must be zero or positive.");
        }
        if (maxDepth > RailixData.DEFAULT_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Maximum value depth must not exceed " + RailixData.DEFAULT_MAX_DEPTH + "."
            );
        }
        if (maxDepth > 0 && !canonicalValues) {
            throw new IllegalArgumentException("Value depth refinement requires canonical values.");
        }
        if (maxJsonBytes < 0) {
            throw new IllegalArgumentException("Maximum canonical JSON bytes must be zero or positive.");
        }
        if (maxJsonBytes > RailixData.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "Maximum canonical JSON bytes must not exceed " + RailixData.MAX_SOURCE_BYTES + "."
            );
        }
        if (maxJsonBytes > 0 && !canonicalValues) {
            throw new IllegalArgumentException("JSON byte refinement requires canonical values.");
        }
    }

    /** @return the explicit outer-shape-only contract */
    public static ValueRefinement none() {
        return new ValueRefinement(false, 0, 0);
    }

    /** @return recursive canonical-value validation using the global container-depth limit */
    public static ValueRefinement canonical() {
        return new ValueRefinement(true, 0, 0);
    }

    /**
     * Narrows the accepted container depth.
     *
     * @param depth maximum nested object/array depth, from 1 through 64
     * @return a new immutable refinement
     */
    public ValueRefinement withMaxDepth(final int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("Maximum value depth must be at least 1.");
        }
        return new ValueRefinement(canonicalValues, depth, maxJsonBytes);
    }

    /**
     * Bounds the canonical JSON representation.
     *
     * @param bytes maximum UTF-8 bytes, from 1 through 8,388,608
     * @return a new immutable refinement
     */
    public ValueRefinement withMaxJsonBytes(final int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("Maximum canonical JSON bytes must be at least 1.");
        }
        return new ValueRefinement(canonicalValues, maxDepth, bytes);
    }

    /**
     * Validates one programmatic Railix value without retaining state.
     *
     * @param value candidate value
     * @return the first deterministic incompatibility, or empty when accepted
     * @throws IllegalArgumentException when the candidate is Java null
     */
    public Optional<String> rejection(final RailixValue value) {
        if (value == null) {
            throw new IllegalArgumentException("Value candidate cannot be Java null.");
        }
        if (!canonicalValues) {
            return Optional.empty();
        }
        final int depthLimit = maxDepth == 0 ? RailixData.DEFAULT_MAX_DEPTH : maxDepth;
        final Deque<Frame> pending = new ArrayDeque<>();
        RailixValue current = value;
        String key = null;
        int parentDepth = 0;
        boolean invalidNumber = false;
        boolean invalidUnicode = false;
        long jsonBytes = 0;
        while (true) {
            if (key != null) {
                final boolean invalidKey = invalidSurrogate(key);
                invalidUnicode |= invalidKey;
                if (maxJsonBytes > 0 && !invalidKey) {
                    jsonBytes = addBytes(jsonBytes, jsonStringBytes(key) + 1);
                }
            }
            if (current instanceof RailixValue.NumberValue number
                    && !RailixData.fitsCanonicalNumber(number.value())) {
                invalidNumber = true;
            } else if (current instanceof RailixValue.NumberValue number && maxJsonBytes > 0) {
                final var canonical = number.value().signum() == 0
                        ? java.math.BigDecimal.ZERO
                        : number.value().stripTrailingZeros();
                jsonBytes = addBytes(jsonBytes, canonical.toPlainString().length());
            } else if (current instanceof RailixValue.StringValue string) {
                final boolean invalidString = invalidSurrogate(string.value());
                invalidUnicode |= invalidString;
                if (maxJsonBytes > 0 && !invalidString) {
                    jsonBytes = addBytes(jsonBytes, jsonStringBytes(string.value()));
                }
            } else if (current instanceof RailixValue.NullValue && maxJsonBytes > 0) {
                jsonBytes = addBytes(jsonBytes, 4);
            } else if (current instanceof RailixValue.BooleanValue bool && maxJsonBytes > 0) {
                jsonBytes = addBytes(jsonBytes, bool.value() ? 4 : 5);
            } else if (current instanceof RailixValue.ArrayValue array) {
                final int depth = parentDepth + 1;
                if (depth > depthLimit) {
                    return Optional.of("Value exceeds maximum container depth " + depthLimit + ".");
                }
                if (maxJsonBytes > 0) {
                    jsonBytes = addBytes(jsonBytes, 2L + Math.max(0, array.values().size() - 1));
                }
                if (!array.values().isEmpty()) {
                    final Frame frame = Frame.array(array.values(), depth);
                    pending.push(frame);
                    current = frame.next();
                    key = frame.key();
                    parentDepth = depth;
                    continue;
                }
            } else if (current instanceof RailixValue.ObjectValue object) {
                final int depth = parentDepth + 1;
                if (depth > depthLimit) {
                    return Optional.of("Value exceeds maximum container depth " + depthLimit + ".");
                }
                if (maxJsonBytes > 0) {
                    jsonBytes = addBytes(jsonBytes, 2L + Math.max(0, object.values().size() - 1));
                }
                if (!object.values().isEmpty()) {
                    final Frame frame = Frame.object(object.values(), depth);
                    pending.push(frame);
                    current = frame.next();
                    key = frame.key();
                    parentDepth = depth;
                    continue;
                }
            }
            while (!pending.isEmpty() && !pending.peek().hasNext()) {
                pending.pop();
            }
            if (pending.isEmpty()) {
                break;
            }
            final Frame frame = pending.peek();
            current = frame.next();
            key = frame.key();
            parentDepth = frame.depth();
        }
        if (invalidNumber) {
            return Optional.of("Value contains a number outside the canonical 1024-character domain.");
        }
        if (invalidUnicode) {
            return Optional.of("Value contains an unpaired Unicode surrogate.");
        }
        if (maxJsonBytes > 0 && jsonBytes > maxJsonBytes) {
            return Optional.of("Canonical JSON exceeds " + maxJsonBytes + " bytes.");
        }
        return Optional.empty();
    }

    private long addBytes(final long current, final long added) {
        return Math.min((long) maxJsonBytes + 1, current + added);
    }

    private static long jsonStringBytes(final String value) {
        long bytes = 2;
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            bytes += switch (character) {
                case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                default -> {
                    if (character < 0x20) {
                        yield 6;
                    }
                    final int codePoint = value.codePointAt(index);
                    index += Character.charCount(codePoint) - 1;
                    yield codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
                }
            };
        }
        return bytes;
    }

    private static boolean invalidSurrogate(final String value) {
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

    private static final class Frame {
        private final Iterator<?> children;
        private final boolean object;
        private final int depth;
        private String key;

        private Frame(
                final Iterator<?> children,
                final boolean object,
                final int depth
        ) {
            this.children = children;
            this.object = object;
            this.depth = depth;
        }

        private static Frame array(final List<RailixValue> values, final int depth) {
            return new Frame(values.iterator(), false, depth);
        }

        private static Frame object(final Map<String, RailixValue> fields, final int depth) {
            return new Frame(fields.entrySet().iterator(), true, depth);
        }

        private boolean hasNext() {
            return children.hasNext();
        }

        private RailixValue next() {
            if (!object) {
                key = null;
                return (RailixValue) children.next();
            }
            final Map.Entry<?, ?> field = (Map.Entry<?, ?>) children.next();
            key = (String) field.getKey();
            return (RailixValue) field.getValue();
        }

        private String key() {
            return key;
        }

        private int depth() {
            return depth;
        }
    }
}
