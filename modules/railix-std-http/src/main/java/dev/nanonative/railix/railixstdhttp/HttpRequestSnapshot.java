package dev.nanonative.railix.railixstdhttp;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record HttpRequestSnapshot(
        String method,
        URI requestUri,
        InetSocketAddress localAddress,
        InetSocketAddress remoteAddress,
        Map<String, List<String>> headers,
        byte[] bodyBytes
) {

    HttpRequestSnapshot {
        method = requireNonBlank(method, "method");
        requestUri = Objects.requireNonNull(requestUri, "requestUri");
        localAddress = Objects.requireNonNull(localAddress, "localAddress");
        remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        headers = copyHeaders(headers);
        bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
    }

    int bodySize() {
        return bodyBytes.length;
    }

    byte[] rawBodyBytes() {
        return bodyBytes;
    }

    @Override
    public byte[] bodyBytes() {
        return bodyBytes.clone();
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static Map<String, List<String>> copyHeaders(final Map<String, List<String>> headers) {
        final Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : Objects.requireNonNull(headers, "headers").entrySet()) {
            normalized.put(requireNonBlank(entry.getKey(), "headerName"), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }
}
