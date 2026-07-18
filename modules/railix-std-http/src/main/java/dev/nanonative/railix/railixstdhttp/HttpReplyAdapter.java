package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpReplyAdapter {

    public AdaptedHttpReply adapt(final Reply reply) {
        return switch (reply.mode()) {
            case NONE -> new AdaptedHttpReply(204, Map.of(), new byte[0]);
            case IMMEDIATE -> adaptImmediateReply(reply);
            case FILE, STREAM, DEFERRED, SESSION, BROADCAST -> throw new HttpProblemException(
                    "urn:railix:http:unsupported-reply-mode",
                    "Unsupported Reply Mode",
                    501,
                    "HTTP boundary does not support Reply.Mode." + reply.mode().name() + " yet"
            );
        };
    }

    private AdaptedHttpReply adaptImmediateReply(final Reply reply) {
        if (reply.payload() == RailixValue.NULL) {
            final int status = httpStatus(reply.status(), 204);
            if (status == 204) {
                return new AdaptedHttpReply(status, Map.of(), new byte[0]);
            }
            return new AdaptedHttpReply(status, responseHeaders(reply.metadata(), false), new byte[0]);
        }
        final byte[] body = KernelContractCodec.toStableJson(rawJsonValue(reply.payload())).getBytes(StandardCharsets.UTF_8);
        final int status = httpStatus(reply.status(), 200);
        if (status == 204) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply",
                    "Invalid HTTP Reply",
                    500,
                    "Reply.Mode.IMMEDIATE with status 204 must not include a payload"
            );
        }
        return new AdaptedHttpReply(status, responseHeaders(reply.metadata(), true), body);
    }

    private static int httpStatus(final RailixValue statusValue, final int defaultStatus) {
        if (statusValue == RailixValue.NULL) {
            return defaultStatus;
        }
        final int status = switch (statusValue) {
            case RailixValue.NumberValue numberValue -> integerStatus(numberValue.value());
            case RailixValue.StringValue stringValue -> parseStatusString(stringValue.value());
            default -> throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-status",
                    "Invalid HTTP Reply",
                    500,
                    "reply.status must be null, a number, or a numeric string"
            );
        };
        if (status < 100 || status > 599) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-status",
                    "Invalid HTTP Reply",
                    500,
                    "reply.status must be between 100 and 599"
            );
        }
        return status;
    }

    private static int integerStatus(final BigDecimal value) {
        try {
            return value.intValueExact();
        } catch (final ArithmeticException exception) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-status",
                    "Invalid HTTP Reply",
                    500,
                    "reply.status must be an integer when provided as a number"
            );
        }
    }

    private static int parseStatusString(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-status",
                    "Invalid HTTP Reply",
                    500,
                    "reply.status must be numeric when provided as a string"
            );
        }
    }

    private static Map<String, List<String>> responseHeaders(final RailixValue.ObjectValue metadata, final boolean hasBody) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final RailixValue contentTypeValue = metadata.values().get("contentType");
        if (contentTypeValue != null && contentTypeValue != RailixValue.NULL) {
            if (!(contentTypeValue instanceof RailixValue.StringValue stringValue) || stringValue.value().isBlank()) {
                throw new HttpProblemException(
                        "urn:railix:http:invalid-reply-metadata",
                        "Invalid HTTP Reply",
                        500,
                        "reply.metadata.contentType must be a non-blank string"
                );
            }
            headers.put("content-type", List.of(stringValue.value()));
        } else if (hasBody) {
            headers.put("content-type", List.of("application/json"));
        }
        final RailixValue rawHeaders = metadata.values().getOrDefault("headers", RailixValue.NULL);
        if (rawHeaders == RailixValue.NULL) {
            return Map.copyOf(headers);
        }
        if (!(rawHeaders instanceof RailixValue.ObjectValue headerObject)) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-metadata",
                    "Invalid HTTP Reply",
                    500,
                    "reply.metadata.headers must be an object when present"
            );
        }
        for (final Map.Entry<String, RailixValue> entry : headerObject.values().entrySet()) {
            final String headerName = normalizedHeaderName(entry.getKey());
            headers.put(headerName, headerValues(entry.getValue()));
        }
        return Map.copyOf(headers);
    }

    private static String normalizedHeaderName(final String headerName) {
        if (headerName == null || headerName.isBlank()) {
            throw new HttpProblemException(
                    "urn:railix:http:invalid-reply-metadata",
                    "Invalid HTTP Reply",
                    500,
                    "reply.metadata.headers keys must be non-blank"
            );
        }
        return headerName.toLowerCase(Locale.ROOT);
    }

    private static List<String> headerValues(final RailixValue value) {
        if (value instanceof RailixValue.StringValue stringValue) {
            return List.of(stringValue.value());
        }
        if (value instanceof RailixValue.ListValue listValue) {
            final List<String> values = new ArrayList<>();
            for (final RailixValue item : listValue.values()) {
                if (!(item instanceof RailixValue.StringValue stringValue)) {
                    throw new HttpProblemException(
                            "urn:railix:http:invalid-reply-metadata",
                            "Invalid HTTP Reply",
                            500,
                            "reply.metadata.headers list values must be strings"
                    );
                }
                values.add(stringValue.value());
            }
            return List.copyOf(values);
        }
        throw new HttpProblemException(
                "urn:railix:http:invalid-reply-metadata",
                "Invalid HTTP Reply",
                500,
                "reply.metadata.headers values must be strings or lists of strings"
        );
    }

    private static Object rawJsonValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> null;
            case RailixValue.BoolValue boolValue -> boolValue.value();
            case RailixValue.NumberValue numberValue -> numberValue.value();
            case RailixValue.StringValue stringValue -> stringValue.value();
            case RailixValue.ListValue listValue -> listValue.values().stream().map(HttpReplyAdapter::rawJsonValue).toList();
            case RailixValue.ObjectValue objectValue -> {
                final Map<String, Object> values = new LinkedHashMap<>();
                for (final Map.Entry<String, RailixValue> entry : objectValue.values().entrySet()) {
                    values.put(entry.getKey(), rawJsonValue(entry.getValue()));
                }
                yield values;
            }
            case RailixValue.BlobRef blobRef -> Map.of(
                    "kind", "blob-ref",
                    "id", blobRef.id(),
                    "mediaType", blobRef.mediaType(),
                    "digest", blobRef.digest(),
                    "size", blobRef.size()
            );
            case RailixValue.FileRef fileRef -> Map.of(
                    "kind", "file-ref",
                    "id", fileRef.id(),
                    "path", fileRef.path(),
                    "mediaType", fileRef.mediaType(),
                    "digest", fileRef.digest(),
                    "size", fileRef.size()
            );
            case RailixValue.StreamRef streamRef -> Map.of(
                    "kind", "stream-ref",
                    "id", streamRef.id(),
                    "itemType", streamRef.itemType(),
                    "metadata", rawJsonValue(new RailixValue.ObjectValue(streamRef.metadata()))
            );
            case RailixValue.SessionRef sessionRef -> Map.of(
                    "kind", "session-ref",
                    "id", sessionRef.id(),
                    "protocol", sessionRef.protocol(),
                    "metadata", rawJsonValue(new RailixValue.ObjectValue(sessionRef.metadata()))
            );
            case RailixValue.DeferredRef deferredRef -> Map.of(
                    "kind", "deferred-ref",
                    "id", deferredRef.id(),
                    "statusPath", deferredRef.statusPath(),
                    "metadata", rawJsonValue(new RailixValue.ObjectValue(deferredRef.metadata()))
            );
            case RailixValue.SecretRef secretRef -> Map.of(
                    "kind", "secret-ref",
                    "path", secretRef.path().toString()
            );
        };
    }

    public record AdaptedHttpReply(int status, Map<String, List<String>> headers, byte[] body) {
        public AdaptedHttpReply {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("status must be between 100 and 599");
            }
            final Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
            for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
                copiedHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            headers = Map.copyOf(copiedHeaders);
            body = body.clone();
        }
    }
}
