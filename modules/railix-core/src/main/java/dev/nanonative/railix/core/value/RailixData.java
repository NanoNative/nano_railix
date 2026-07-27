package dev.nanonative.railix.core.value;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Normalizes supported primitive documents into one canonical Railix value. */
public final class RailixData {
    /** Default direct-document limit used by flow and event ingress. */
    public static final int DEFAULT_MAX_SOURCE_BYTES = 1_048_576;
    /** Hard ceiling available to explicitly bounded enclosing transport documents. */
    public static final int MAX_SOURCE_BYTES = 8_388_608;
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static final int MAX_CANONICAL_NUMBER_CHARACTERS = 1_024;

    private RailixData() {
    }

    /** Supported source formats. The caller selects one; content is never guessed. */
    public enum Format {
        JSON,
        YAML,
        XML
    }

    /**
     * Normalizes a UTF-8 document using the visible default limits.
     *
     * @param format explicit document format
     * @param source UTF-8 document bytes
     * @return a normalized value or stable diagnostic
     */
    public static Result normalize(final Format format, final byte[] source) {
        return normalize(format, source, DEFAULT_MAX_SOURCE_BYTES, DEFAULT_MAX_DEPTH);
    }

    /**
     * Normalizes a UTF-8 document using explicit byte and container-depth limits.
     *
     * @param format explicit document format
     * @param source UTF-8 document bytes
     * @param maxSourceBytes maximum accepted source bytes, from 1 through {@link #MAX_SOURCE_BYTES}
     * @param maxDepth maximum nested object/array depth, from 1 through {@link #DEFAULT_MAX_DEPTH}
     * @return a normalized value or stable diagnostic
     */
    public static Result normalize(
            final Format format,
            final byte[] source,
            final int maxSourceBytes,
            final int maxDepth
    ) {
        if (format == null) {
            return invalid("DATA_FORMAT_REQUIRED", "Data format is required.", 0, 0);
        }
        if (source == null) {
            return invalid("DATA_SOURCE_REQUIRED", "Data source is required.", 0, 0);
        }
        if (maxSourceBytes < 1) {
            return invalid("DATA_LIMIT_INVALID", "Maximum source bytes must be at least 1.", 0, 0);
        }
        if (maxSourceBytes > MAX_SOURCE_BYTES) {
            return invalid(
                    "DATA_LIMIT_INVALID",
                    "Maximum source bytes must not exceed " + MAX_SOURCE_BYTES + ".",
                    0,
                    0
            );
        }
        if (maxDepth < 1) {
            return invalid("DATA_LIMIT_INVALID", "Maximum container depth must be at least 1.", 0, 0);
        }
        if (maxDepth > DEFAULT_MAX_DEPTH) {
            return invalid(
                    "DATA_LIMIT_INVALID",
                    "Maximum container depth must not exceed " + DEFAULT_MAX_DEPTH + ".",
                    0,
                    0
            );
        }
        if (source.length > maxSourceBytes) {
            return invalid(
                    "DATA_SOURCE_TOO_LARGE",
                    "Data source exceeds the " + maxSourceBytes + "-byte limit.",
                    0,
                    0
            );
        }
        if (hasUtf8Bom(source)) {
            return invalid("DATA_BOM_UNSUPPORTED", "UTF-8 BOM is not supported.", 0, 0);
        }

        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (final CharacterCodingException exception) {
            return invalid("DATA_SOURCE_UTF8_INVALID", "Data source is not valid UTF-8.", 0, 0);
        }

        try {
            final RailixValue value = switch (format) {
                case JSON -> json(text, maxDepth);
                case YAML -> RailixYaml.parse(text, maxDepth);
                case XML -> RailixXml.parse(text, maxDepth);
            };
            return new Normalized(canonical(value));
        } catch (final Failure failure) {
            return failure.invalid();
        }
    }

    private static boolean hasUtf8Bom(final byte[] source) {
        return source.length >= 3
                && source[0] == (byte) 0xef
                && source[1] == (byte) 0xbb
                && source[2] == (byte) 0xbf;
    }

    private static RailixValue json(final String source, final int maxDepth) {
        checkJsonDepth(source, maxDepth);
        return switch (RailixJson.parse(source, maxDepth)) {
            case RailixJson.Parsed parsed -> parsed.value();
            case RailixJson.Invalid invalid -> throw failure(
                    RailixJson.NUMBER_SOURCE_LIMIT_MESSAGE.equals(invalid.message())
                            ? "DATA_NUMBER_LIMIT_EXCEEDED"
                            : "DATA_JSON_INVALID",
                    invalid.message(),
                    invalid.line(),
                    invalid.column()
            );
        };
    }

    private static void checkJsonDepth(final String source, final int maxDepth) {
        int depth = 0;
        int line = 1;
        int column = 1;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{' || character == '[') {
                depth++;
                if (depth > maxDepth) {
                    throw failure(
                            "DATA_DEPTH_EXCEEDED",
                            "Data exceeds the maximum container depth of " + maxDepth + ".",
                            line,
                            column
                    );
                }
            } else if (character == '}' || character == ']') {
                depth--;
            }

            if (character == '\r') {
                line++;
                column = 1;
            } else if (character == '\n') {
                if (index == 0 || source.charAt(index - 1) != '\r') {
                    line++;
                }
                column = 1;
            } else {
                column++;
            }
        }
    }

    private static RailixValue canonical(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> RailixValue.nullValue();
            case RailixValue.BooleanValue bool -> bool;
            case RailixValue.StringValue string -> string;
            case RailixValue.NumberValue number -> canonicalNumber(number.value());
            case RailixValue.ArrayValue array -> {
                final List<RailixValue> values = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    values.add(canonical(item));
                }
                yield RailixValue.array(values);
            }
            case RailixValue.ObjectValue object -> {
                final Map<String, RailixValue> values = new TreeMap<>();
                for (final Map.Entry<String, RailixValue> field : object.values().entrySet()) {
                    values.put(field.getKey(), canonical(field.getValue()));
                }
                yield RailixValue.object(values);
            }
        };
    }

    private static RailixValue.NumberValue canonicalNumber(final BigDecimal source) {
        if (source.signum() != 0
                && source.scale() <= 0
                && canonicalNumberLength(source) > MAX_CANONICAL_NUMBER_CHARACTERS) {
            throw failure(
                    "DATA_NUMBER_LIMIT_EXCEEDED",
                    "Number exceeds the " + MAX_CANONICAL_NUMBER_CHARACTERS + "-character canonical limit.",
                    0,
                    0
            );
        }
        final BigDecimal value = source.signum() == 0 ? BigDecimal.ZERO : source.stripTrailingZeros();
        if (canonicalNumberLength(value) > MAX_CANONICAL_NUMBER_CHARACTERS) {
            throw failure(
                    "DATA_NUMBER_LIMIT_EXCEEDED",
                    "Number exceeds the " + MAX_CANONICAL_NUMBER_CHARACTERS + "-character canonical limit.",
                    0,
                    0
            );
        }
        return RailixValue.number(value);
    }

    static long canonicalNumberLength(final BigDecimal value) {
        final int precision = value.precision();
        final int scale = value.scale();
        final long sign = value.signum() < 0 ? 1 : 0;
        if (scale <= 0) {
            return sign + precision - (long) scale;
        }
        if (scale >= precision) {
            return sign + scale + 2L;
        }
        return sign + precision + 1L;
    }

    private static Invalid invalid(
            final String code,
            final String message,
            final int line,
            final int column
    ) {
        return new Invalid(code, message, line, column);
    }

    static Failure failure(
            final String code,
            final String message,
            final int line,
            final int column
    ) {
        return new Failure(code, message, line, column);
    }

    /** A normalization result. */
    public sealed interface Result permits Normalized, Invalid {
    }

    /** Successfully normalized immutable value. */
    public record Normalized(RailixValue value) implements Result {
        /** @return deterministic compact JSON for this value */
        public String canonicalJson() {
            return RailixJson.write(value);
        }
    }

    /** Stable rejected-input diagnostic. Byte-level locations are {@code 0:0}. */
    public record Invalid(String code, String message, int line, int column) implements Result {
    }

    static final class Failure extends RuntimeException {
        private final String code;
        private final int line;
        private final int column;

        private Failure(final String code, final String message, final int line, final int column) {
            super(message, null, false, false);
            this.code = code;
            this.line = line;
            this.column = column;
        }

        private Invalid invalid() {
            return RailixData.invalid(code, getMessage(), line, column);
        }
    }
}
