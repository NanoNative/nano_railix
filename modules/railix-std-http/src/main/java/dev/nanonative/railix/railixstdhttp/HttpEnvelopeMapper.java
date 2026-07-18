package dev.nanonative.railix.railixstdhttp;

import com.sun.net.httpserver.HttpExchange;
import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpEnvelopeMapper {

    public HttpRequestSnapshot snapshot(final HttpExchange exchange) throws IOException {
        return new HttpRequestSnapshot(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getLocalAddress(),
                exchange.getRemoteAddress(),
                copyHeaders(exchange.getRequestHeaders()),
                readRequestBody(exchange.getRequestBody(), HttpBoundarySettings.MAX_REQUEST_BYTES)
        );
    }

    public Envelope map(final HttpExchange exchange) throws IOException {
        return map(snapshot(exchange));
    }

    public Envelope map(final HttpRequestSnapshot request) {
        return map(
                request.method(),
                request.requestUri(),
                request.localAddress(),
                request.remoteAddress(),
                request.headers(),
                request.rawBodyBytes()
        );
    }

    public Envelope map(
            final String method,
            final URI requestUri,
            final InetSocketAddress localAddress,
            final InetSocketAddress remoteAddress,
            final Map<String, List<String>> headers,
            final byte[] bodyBytes
    ) {
        final String normalizedMethod = requireNonBlank(method, "method").toUpperCase(Locale.ROOT);
        final URI normalizedRequestUri = java.util.Objects.requireNonNull(requestUri, "requestUri");
        final InetSocketAddress normalizedLocalAddress = java.util.Objects.requireNonNull(localAddress, "localAddress");
        final InetSocketAddress normalizedRemoteAddress = java.util.Objects.requireNonNull(remoteAddress, "remoteAddress");
        final Map<String, List<String>> normalizedHeaders = copyHeaders(headers);
        final byte[] normalizedBodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
        if (normalizedBodyBytes.length > HttpBoundarySettings.MAX_REQUEST_BYTES) {
            throw new HttpProblemException(
                    "urn:railix:http:payload-too-large",
                    "Payload Too Large",
                    413,
                    "HTTP request body exceeds the first std.http size cap of " + HttpBoundarySettings.MAX_REQUEST_BYTES + " bytes"
            );
        }
        final RailixValue.ObjectValue payload = parsePayload(normalizedHeaders, normalizedBodyBytes);
        final RailixValue.ObjectValue metadata = new RailixValue.ObjectValue(Map.of(
                "method", new RailixValue.StringValue(normalizedMethod),
                "path", new RailixValue.StringValue(normalizedRequestUri.getPath().isEmpty() ? "/" : normalizedRequestUri.getPath()),
                "headers", multiMapValue(normalizedHeaders),
                "query", multiMapValue(HttpQueryCodec.parseRawQuery(normalizedRequestUri.getRawQuery())),
                "remoteAddress", new RailixValue.StringValue(normalizedRemoteAddress.getHostString())
        ));
        return new Envelope(
                source(normalizedLocalAddress),
                "http",
                payload,
                metadata,
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }

    private static byte[] readRequestBody(final InputStream requestBody, final int maxRequestBytes) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int maxBufferedBytes = maxRequestBytes == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxRequestBytes + 1;
        final byte[] chunk = new byte[Math.min(8_192, Math.max(1, maxBufferedBytes))];
        int totalBytes = 0;
        while (totalBytes < maxBufferedBytes) {
            final int read = requestBody.read(chunk, 0, Math.min(chunk.length, maxBufferedBytes - totalBytes));
            if (read < 0) {
                break;
            }
            bytes.write(chunk, 0, read);
            totalBytes += read;
        }
        return bytes.toByteArray();
    }

    private static RailixValue.ObjectValue parsePayload(final Map<String, List<String>> headers, final byte[] bodyBytes) {
        if (bodyBytes.length == 0) {
            return new RailixValue.ObjectValue(Map.of());
        }
        final String contentType = firstHeaderValue(headers, "content-type").orElseThrow(() -> new HttpProblemException(
                "urn:railix:http:unsupported-media-type",
                "Unsupported Media Type",
                415,
                "HTTP request bodies require Content-Type application/json"
        ));
        final String baseMediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/json".equals(baseMediaType)) {
            throw new HttpProblemException(
                    "urn:railix:http:unsupported-media-type",
                    "Unsupported Media Type",
                    415,
                    "HTTP request bodies require Content-Type application/json"
            );
        }
        final Object parsed;
        try {
            parsed = KernelContractCodec.parseStableJson(new String(bodyBytes, StandardCharsets.UTF_8));
        } catch (final RuntimeException exception) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-json",
                    "Invalid JSON",
                    400,
                    "HTTP request body must be valid JSON"
            );
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-payload-shape",
                    "Invalid Request Payload",
                    400,
                    "HTTP request body must decode to a JSON object"
            );
        }
        return new RailixValue.ObjectValue(rawJsonObject(rawMap));
    }

    private static Map<String, RailixValue> rawJsonObject(final Map<?, ?> rawMap) {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new HttpProblemException(
                        "urn:railix:http:invalid-payload-shape",
                        "Invalid Request Payload",
                        400,
                        "HTTP request body object keys must be strings"
                );
            }
            values.put(key, rawJsonValue(entry.getValue()));
        }
        return Map.copyOf(values);
    }

    private static RailixValue rawJsonValue(final Object rawValue) {
        return switch (rawValue) {
            case null -> RailixValue.NULL;
            case Boolean boolValue -> new RailixValue.BoolValue(boolValue);
            case Integer integerValue -> new RailixValue.NumberValue(BigDecimal.valueOf(integerValue.longValue()));
            case Long longValue -> new RailixValue.NumberValue(BigDecimal.valueOf(longValue));
            case BigDecimal decimalValue -> new RailixValue.NumberValue(decimalValue);
            case String stringValue -> new RailixValue.StringValue(stringValue);
            case Map<?, ?> rawMap -> new RailixValue.ObjectValue(rawJsonObject(rawMap));
            case List<?> rawList -> new RailixValue.ListValue(rawList.stream().map(HttpEnvelopeMapper::rawJsonValue).toList());
            default -> throw new HttpProblemException(
                    "urn:railix:http:invalid-payload-shape",
                    "Invalid Request Payload",
                    400,
                    "Unsupported JSON value type: " + rawValue.getClass().getName()
            );
        };
    }

    private static RailixValue.ObjectValue multiMapValue(final Map<String, List<String>> values) {
        final Map<String, RailixValue> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : values.entrySet()) {
            final List<RailixValue> listValues = entry.getValue().stream()
                    .map(RailixValue.StringValue::new)
                    .map(value -> (RailixValue) value)
                    .toList();
            normalized.put(
                    entry.getKey(),
                    listValues.size() == 1 ? listValues.getFirst() : new RailixValue.ListValue(listValues)
            );
        }
        return new RailixValue.ObjectValue(normalized);
    }

    private static Map<String, List<String>> copyHeaders(final Map<String, List<String>> headers) {
        final Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : java.util.Objects.requireNonNull(headers, "headers").entrySet()) {
            final String key = requireNonBlank(entry.getKey(), "headerName").toLowerCase(Locale.ROOT);
            normalized.put(key, List.copyOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private static java.util.Optional<String> firstHeaderValue(final Map<String, List<String>> headers, final String headerName) {
        final List<String> values = headers.get(headerName.toLowerCase(Locale.ROOT));
        if (values == null || values.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(values.getFirst());
    }

    private static String source(final InetSocketAddress localAddress) {
        return "http." + requireNonBlank(localAddress.getHostString(), "localAddress.host") + ":" + localAddress.getPort();
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

}
