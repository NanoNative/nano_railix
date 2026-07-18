package dev.nanonative.railix.railixstdhttp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HttpQueryCodec {

    private HttpQueryCodec() {}

    static Map<String, List<String>> parseRawQuery(final String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        final Map<String, List<String>> values = new LinkedHashMap<>();
        for (final String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final String[] tokens = pair.split("=", 2);
            final String key = decodeToken(tokens[0]);
            final String value = tokens.length == 2 ? decodeToken(tokens[1]) : "";
            values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        final Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : values.entrySet()) {
            normalized.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private static String decodeToken(final String token) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(token.length());
        for (int index = 0; index < token.length(); index++) {
            final char current = token.charAt(index);
            if (current == '%') {
                if (index + 2 >= token.length()) {
                    throw invalidQueryToken(token);
                }
                final int high = Character.digit(token.charAt(index + 1), 16);
                final int low = Character.digit(token.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalidQueryToken(token);
                }
                bytes.write((high << 4) + low);
                index += 2;
                continue;
            }
            final byte[] encoded = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(encoded);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalidQueryToken(final String token) {
        return new IllegalArgumentException("HTTP query token contains invalid percent-encoding: " + token);
    }
}
