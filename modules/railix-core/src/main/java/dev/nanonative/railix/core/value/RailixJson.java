package dev.nanonative.railix.core.value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Standard-library JSON normalization for canonical Railix values. */
public final class RailixJson {
    static final String NUMBER_SOURCE_LIMIT_MESSAGE = "JSON number exceeds the 1024-character source limit.";
    private static final String UNPAIRED_SURROGATE =
            "Unpaired Unicode surrogate is not allowed in JSON strings.";
    private final String source;
    private final int maxDepth;
    private int index;
    private int line = 1;
    private int column = 1;
    private int depth;

    private RailixJson(final String source, final int maxDepth) {
        this.source = source;
        this.maxDepth = maxDepth;
    }

    public static Result parse(final String source) {
        return parse(source, RailixData.DEFAULT_MAX_DEPTH);
    }

    static Result parse(final String source, final int maxDepth) {
        if (source == null) {
            return new Invalid("JSON source is missing.", 1, 1);
        }
        final RailixJson parser = new RailixJson(source, maxDepth);
        try {
            parser.skipWhitespace();
            final RailixValue value = parser.value();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                throw parser.failure("Unexpected content after the JSON value.");
            }
            return new Parsed(value);
        } catch (final JsonFailure failure) {
            return new Invalid(failure.getMessage(), failure.line, failure.column);
        }
    }

    public static String write(final RailixValue value) {
        if (value == null) {
            throw new IllegalArgumentException("Railix JSON value cannot be Java null.");
        }
        final JsonOutput json = new JsonOutput(0);
        writeValue(value, json, 0);
        return json.value();
    }

    /**
     * Writes canonical JSON only when its UTF-8 representation fits the supplied byte limit.
     *
     * @param value canonical value to write
     * @param maxBytes maximum UTF-8 bytes, including JSON syntax
     * @return the complete canonical JSON, or empty when it exceeds the limit
     * @throws IllegalArgumentException when the value is Java null or the limit is below one
     */
    public static Optional<String> write(final RailixValue value, final int maxBytes) {
        final JsonOutput json = bounded(value, maxBytes, true);
        return json.exceeded() ? Optional.empty() : Optional.of(json.value());
    }

    /**
     * Reports whether canonical JSON fits a UTF-8 byte limit without materializing the JSON.
     *
     * @param value canonical value to measure
     * @param maxBytes maximum UTF-8 bytes, including JSON syntax
     * @return true when the complete canonical JSON fits
     * @throws IllegalArgumentException when the value is Java null or the limit is below one
     */
    public static boolean fitsUtf8(final RailixValue value, final int maxBytes) {
        return !bounded(value, maxBytes, false).exceeded();
    }

    private static JsonOutput bounded(
            final RailixValue value,
            final int maxBytes,
            final boolean capture
    ) {
        if (value == null) {
            throw new IllegalArgumentException("Railix JSON value cannot be Java null.");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Maximum JSON bytes must be at least 1.");
        }
        final JsonOutput json = new JsonOutput(maxBytes, capture);
        writeValue(value, json, 0);
        return json;
    }

    private static void writeValue(final RailixValue value, final JsonOutput json, final int depth) {
        if (json.exceeded()) {
            return;
        }
        switch (value) {
            case RailixValue.NullValue ignored -> json.append("null");
            case RailixValue.BooleanValue bool -> json.append(bool.value());
            case RailixValue.NumberValue number -> {
                final BigDecimal source = number.value();
                if (!RailixData.fitsCanonicalNumber(source)) {
                    throw new IllegalArgumentException(
                            "Number exceeds the " + RailixData.MAX_CANONICAL_NUMBER_CHARACTERS
                                    + "-character canonical limit."
                    );
                }
                final BigDecimal canonical =
                        source.signum() == 0 ? BigDecimal.ZERO : source.stripTrailingZeros();
                json.append(canonical.toPlainString());
            }
            case RailixValue.StringValue string -> writeString(string.value(), json);
            case RailixValue.ArrayValue array -> {
                checkWriteDepth(depth);
                json.append('[');
                for (int index = 0; index < array.values().size() && !json.exceeded(); index++) {
                    if (index > 0) {
                        json.append(',');
                    }
                    writeValue(array.values().get(index), json, depth + 1);
                }
                json.append(']');
            }
            case RailixValue.ObjectValue object -> {
                checkWriteDepth(depth);
                json.append('{');
                boolean first = true;
                for (final String key : object.values().keySet().stream().sorted().toList()) {
                    if (json.exceeded()) {
                        break;
                    }
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    writeString(key, json);
                    json.append(':');
                    writeValue(object.values().get(key), json, depth + 1);
                }
                json.append('}');
            }
        }
    }

    private static void checkWriteDepth(final int depth) {
        if (depth >= RailixData.DEFAULT_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "Railix JSON exceeds the maximum container depth of " + RailixData.DEFAULT_MAX_DEPTH + "."
            );
        }
    }

    private static void writeString(final String value, final JsonOutput json) {
        if (invalidSurrogate(value) >= 0) {
            throw new IllegalArgumentException(UNPAIRED_SURROGATE);
        }
        json.append('"');
        for (int index = 0; index < value.length() && !json.exceeded(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u%04x".formatted((int) character));
                    } else {
                        final int codePoint = value.codePointAt(index);
                        json.appendCodePoint(codePoint);
                        index += Character.charCount(codePoint) - 1;
                    }
                }
            }
        }
        json.append('"');
    }

    private static final class JsonOutput {
        private final StringBuilder value = new StringBuilder();
        private final int maxBytes;
        private final boolean capture;
        private int bytes;
        private boolean exceeded;

        private JsonOutput(final int maxBytes) {
            this(maxBytes, true);
        }

        private JsonOutput(final int maxBytes, final boolean capture) {
            this.maxBytes = maxBytes;
            this.capture = capture;
        }

        private void append(final boolean source) {
            append(Boolean.toString(source));
        }

        private void append(final char source) {
            appendCodePoint(source);
        }

        private void append(final String source) {
            for (int index = 0; index < source.length() && !exceeded; ) {
                final int codePoint = source.codePointAt(index);
                appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
            }
        }

        private void appendCodePoint(final int codePoint) {
            final int added = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (maxBytes > 0 && bytes > maxBytes - added) {
                exceeded = true;
                return;
            }
            if (capture) {
                value.appendCodePoint(codePoint);
            }
            bytes += added;
        }

        private boolean exceeded() {
            return exceeded;
        }

        private String value() {
            return value.toString();
        }
    }

    private RailixValue value() {
        if (atEnd()) {
            throw failure("Expected a JSON value.");
        }
        return switch (current()) {
            case '{' -> container(true);
            case '[' -> container(false);
            case '"' -> RailixValue.string(string());
            case 't' -> literal("true", RailixValue.bool(true));
            case 'f' -> literal("false", RailixValue.bool(false));
            case 'n' -> literal("null", RailixValue.nullValue());
            default -> {
                if (current() != '-' && !digit(current())) {
                    throw failure("Expected a JSON value.");
                }
                yield number();
            }
        };
    }

    private RailixValue container(final boolean object) {
        if (depth >= maxDepth) {
            throw failure("JSON exceeds the maximum container depth of " + maxDepth + ".");
        }
        depth++;
        try {
            return object ? object() : array();
        } finally {
            depth--;
        }
    }

    private RailixValue.ObjectValue object() {
        consume('{');
        skipWhitespace();
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        if (consumeIf('}')) {
            return RailixValue.object(values);
        }
        while (true) {
            if (atEnd() || current() != '"') {
                throw failure("Expected an object field name.");
            }
            final int keyLine = line;
            final int keyColumn = column;
            final String key = string();
            if (values.containsKey(key)) {
                throw new JsonFailure("Duplicate object field: " + key, keyLine, keyColumn);
            }
            skipWhitespace();
            consume(':');
            skipWhitespace();
            values.put(key, value());
            skipWhitespace();
            if (consumeIf('}')) {
                return RailixValue.object(values);
            }
            consume(',');
            skipWhitespace();
        }
    }

    private RailixValue.ArrayValue array() {
        consume('[');
        skipWhitespace();
        final List<RailixValue> values = new ArrayList<>();
        if (consumeIf(']')) {
            return RailixValue.array(values);
        }
        while (true) {
            values.add(value());
            skipWhitespace();
            if (consumeIf(']')) {
                return RailixValue.array(values);
            }
            consume(',');
            skipWhitespace();
        }
    }

    private String string() {
        consume('"');
        final StringBuilder result = new StringBuilder();
        while (!atEnd()) {
            final char character = current();
            advance();
            if (character == '"') {
                final String value = result.toString();
                if (invalidSurrogate(value) >= 0) {
                    throw failure(UNPAIRED_SURROGATE);
                }
                return value;
            }
            if (character == '\\') {
                result.append(escape());
            } else {
                if (character < 0x20) {
                    throw failure("Control characters are not allowed in JSON strings.");
                }
                result.append(character);
            }
        }
        throw failure("Unterminated JSON string.");
    }

    private char escape() {
        if (atEnd()) {
            throw failure("Unterminated JSON escape.");
        }
        final char escaped = current();
        advance();
        return switch (escaped) {
            case '"', '\\', '/' -> escaped;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicodeEscape();
            default -> throw failure("Unsupported JSON escape: \\" + escaped);
        };
    }

    private char unicodeEscape() {
        if (index + 4 > source.length()) {
            throw failure("Incomplete Unicode escape.");
        }
        final String digits = source.substring(index, index + 4);
        for (int offset = 0; offset < 4; offset++) {
            advance();
        }
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (final NumberFormatException exception) {
            throw failure("Invalid Unicode escape: " + digits);
        }
    }

    private RailixValue number() {
        final int start = index;
        consumeIf('-');
        integerDigits();
        if (consumeIf('.')) {
            digits();
        }
        if (consumeIf('e') || consumeIf('E')) {
            if (!atEnd() && (current() == '+' || current() == '-')) {
                advance();
            }
            digits();
        }
        final String encoded = source.substring(start, index);
        final int magnitudeCharacters = encoded.length() - (encoded.charAt(0) == '-' ? 1 : 0);
        if (magnitudeCharacters > RailixData.MAX_CANONICAL_NUMBER_CHARACTERS) {
            throw failure(NUMBER_SOURCE_LIMIT_MESSAGE);
        }
        try {
            return RailixValue.number(new BigDecimal(encoded));
        } catch (final NumberFormatException exception) {
            throw failure("Invalid JSON number: " + encoded);
        }
    }

    private void integerDigits() {
        if (atEnd() || !digit(current())) {
            throw failure("Expected a digit.");
        }
        if (consumeIf('0')) {
            if (!atEnd() && digit(current())) {
                throw failure("Leading zeroes are not allowed in JSON numbers.");
            }
            return;
        }
        digits();
    }

    private void digits() {
        final int start = index;
        while (!atEnd() && digit(current())) {
            advance();
        }
        if (start == index) {
            throw failure("Expected a digit.");
        }
    }

    private RailixValue literal(final String expected, final RailixValue value) {
        for (int offset = 0; offset < expected.length(); offset++) {
            if (atEnd() || current() != expected.charAt(offset)) {
                throw failure("Expected JSON literal: " + expected);
            }
            advance();
        }
        return value;
    }

    private void skipWhitespace() {
        while (!atEnd() && whitespace(current())) {
            advance();
        }
    }

    private void consume(final char expected) {
        if (atEnd() || current() != expected) {
            throw failure("Expected '" + expected + "'.");
        }
        advance();
    }

    private boolean consumeIf(final char expected) {
        if (!atEnd() && current() == expected) {
            advance();
            return true;
        }
        return false;
    }

    private char current() {
        return source.charAt(index);
    }

    private boolean atEnd() {
        return index >= source.length();
    }

    private void advance() {
        final char character = source.charAt(index++);
        if (character == '\r') {
            line++;
            column = 1;
        } else if (character == '\n') {
            if (index < 2 || source.charAt(index - 2) != '\r') {
                line++;
            }
            column = 1;
        } else {
            column++;
        }
    }

    private JsonFailure failure(final String message) {
        return new JsonFailure(message, line, column);
    }

    private static boolean digit(final char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean whitespace(final char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

    private static int invalidSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return index;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return index;
            }
        }
        return -1;
    }

    public sealed interface Result permits Parsed, Invalid {
    }

    public record Parsed(RailixValue value) implements Result {
    }

    public record Invalid(String message, int line, int column) implements Result {
    }

    private static final class JsonFailure extends RuntimeException {
        private final int line;
        private final int column;

        private JsonFailure(final String message, final int line, final int column) {
            super(message, null, false, false);
            this.line = line;
            this.column = column;
        }
    }
}
