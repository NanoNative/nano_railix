package dev.nanonative.railix.kernel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record RailixPath(List<Token> tokens) {

    public RailixPath {
        tokens = List.copyOf(tokens);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Path must contain a root namespace");
        }
        if (!(tokens.getFirst() instanceof KeyToken)) {
            throw new IllegalArgumentException("Path root namespace must be a key");
        }
    }

    public static RailixPath parse(final String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        final List<Token> result = new ArrayList<>();
        int index = parseKey(value, 0, result);
        while (index < value.length()) {
            final char current = value.charAt(index);
            if (current == '.') {
                if (index + 1 >= value.length()) {
                    throw new IllegalArgumentException("Path must not end with '.': " + value);
                }
                if (value.charAt(index + 1) == '.' || value.charAt(index + 1) == '[') {
                    throw new IllegalArgumentException("Invalid path separator at position " + index + ": " + value);
                }
                index = parseKey(value, index + 1, result);
                continue;
            }
            if (current != '[') {
                throw new IllegalArgumentException("Expected '.' or '[' at position " + index + ": " + value);
            }
            index = parseIndex(value, index, result);
        }
        return new RailixPath(result);
    }

    private static int parseKey(final String value, final int startIndex, final List<Token> result) {
        if (startIndex >= value.length()) {
            throw new IllegalArgumentException("Expected path key at end of value: " + value);
        }
        final StringBuilder key = new StringBuilder();
        var escaped = false;
        int index = startIndex;
        for (; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (escaped) {
                key.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '.' || current == '[') {
                break;
            }
            if (current == ']') {
                throw new IllegalArgumentException("Unexpected ']' at position " + index + ": " + value);
            }
            key.append(current);
        }
        if (escaped) {
            throw new IllegalArgumentException("Dangling escape in path: " + value);
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Path key must not be empty: " + value);
        }
        result.add(new KeyToken(key.toString()));
        return index;
    }

    private static int parseIndex(final String value, final int startIndex, final List<Token> result) {
        final int close = value.indexOf(']', startIndex);
        if (close < 0) {
            throw new IllegalArgumentException("Unclosed index in path: " + value);
        }
        final String raw = value.substring(startIndex + 1, close);
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Path index must not be blank: " + value);
        }
        try {
            result.add(new IndexToken(Integer.parseInt(raw)));
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Path index must be an integer: " + value, exception);
        }
        final int nextIndex = close + 1;
        if (nextIndex < value.length()) {
            final char next = value.charAt(nextIndex);
            if (next != '.' && next != '[') {
                throw new IllegalArgumentException("Expected '.' or '[' after index at position " + close + ": " + value);
            }
        }
        return nextIndex;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        for (final Token token : tokens) {
            if (token instanceof KeyToken keyToken) {
                if (!builder.isEmpty()) {
                    builder.append('.');
                }
                builder.append(escapeKey(keyToken.key()));
            } else if (token instanceof IndexToken indexToken) {
                builder.append('[').append(indexToken.index()).append(']');
            }
        }
        return builder.toString();
    }

    private static String escapeKey(final String key) {
        final StringBuilder builder = new StringBuilder();
        for (int index = 0; index < key.length(); index++) {
            final char current = key.charAt(index);
            if (current == '.' || current == '[' || current == ']' || current == '\\') {
                builder.append('\\');
            }
            builder.append(current);
        }
        return builder.toString();
    }

    public sealed interface Token permits KeyToken, IndexToken {}

    public record KeyToken(String key) implements Token {
        public KeyToken {
            Objects.requireNonNull(key, "key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Path key must not be blank");
            }
        }
    }

    public record IndexToken(int index) implements Token {
        public IndexToken {
            if (index < 0) {
                throw new IllegalArgumentException("Path index must be >= 0");
            }
        }
    }
}
