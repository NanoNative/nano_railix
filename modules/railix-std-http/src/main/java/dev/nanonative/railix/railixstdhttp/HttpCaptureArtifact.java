package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class HttpCaptureArtifact {

    static final String FILE_NAME = "http-capture.json";
    static final String REJECTIONS_DIR = "_http-rejections";
    static final String FAILURES_DIR = "_http-failures";
    static final String CAPTURE_KIND_HANDLED_RUN = "handled-run";
    static final String CAPTURE_KIND_BOUNDARY_REJECTION = "boundary-rejection";
    static final String CAPTURE_KIND_BOUNDARY_FAILURE = "boundary-failure";
    private static final int BODY_PREVIEW_BYTES = 4096;

    private HttpCaptureArtifact() {}

    static void write(
            final Path runFolder,
            final Instant timestamp,
            final HttpRequestSnapshot request,
            final Envelope envelope,
            final LocalExecutionKernel.RunRecord record,
            final int responseStatus,
            final Map<String, List<String>> responseHeaders,
            final long durationMs,
            final HttpProblemException problem
    ) {
        final Map<String, Object> capture = baseCapture(timestamp, envelope.protocol(), envelope.source(), record.appId(), record.flowId(), record.runId());
        capture.put("captureKind", CAPTURE_KIND_HANDLED_RUN);
        capture.put("method", metadataString(envelope.metadata(), "method"));
        capture.put("path", metadataString(envelope.metadata(), "path"));
        capture.put("remoteAddress", metadataString(envelope.metadata(), "remoteAddress"));
        capture.put("query", rawStringMultiMap(metadataObject(envelope.metadata(), "query")));
        capture.put("requestHeaders", rawStringMultiMap(metadataObject(envelope.metadata(), "headers")));
        addHandledPayloadCapture(capture, request, envelope.payload());
        capture.put("responseStatus", responseStatus);
        capture.put("responseHeaders", rawStringListMap(responseHeaders));
        capture.put("runOutcome", record.outcome());
        capture.put("replyMode", record.reply().mode().name().toLowerCase());
        capture.put("durationMs", durationMs);
        addProblem(capture, problem);
        writeCapture(runFolder.resolve(FILE_NAME), capture);
    }

    private static void addHandledPayloadCapture(
            final Map<String, Object> capture,
            final HttpRequestSnapshot request,
            final RailixValue.ObjectValue payload
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(payload, "payload");
        if (request.bodySize() == 0) {
            capture.put("requestPayload", rawJsonObject(payload));
            return;
        }
        if (request.bodySize() <= BODY_PREVIEW_BYTES) {
            capture.put("requestPayload", rawJsonObject(payload));
            return;
        }
        capture.put("requestBodyPreview", bodyPreview(request.rawBodyBytes()));
        capture.put("requestBodySize", request.bodySize());
        capture.put("requestBodyTruncated", true);
    }

    static Path rejectedCapturePath(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String requestId
    ) {
        return boundaryCapturePath(runsRoot, appId, flowId, requestId, REJECTIONS_DIR);
    }

    static void writeRejected(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String requestId,
            final HttpRequestSnapshot snapshot,
            final HttpProblemException problem,
            final long durationMs
    ) {
        final Map<String, Object> capture = baseCapture(
                Instant.now(),
                "http",
                source(snapshot.localAddress()),
                appId,
                flowId,
                Objects.requireNonNull(requestId, "requestId")
        );
        capture.put("captureKind", CAPTURE_KIND_BOUNDARY_REJECTION);
        capture.put("method", snapshot.method());
        capture.put("path", snapshot.requestUri().getPath().isEmpty() ? "/" : snapshot.requestUri().getPath());
        capture.put("remoteAddress", snapshot.remoteAddress().getHostString());
        capture.put("query", rawStringListMap(HttpQueryCodec.parseRawQuery(snapshot.requestUri().getRawQuery())));
        capture.put("requestHeaders", lowerCaseHeaders(snapshot.headers()));
        capture.put("requestBodyPreview", bodyPreview(snapshot.bodyBytes()));
        capture.put("requestBodySize", snapshot.bodyBytes().length);
        capture.put("requestBodyTruncated", snapshot.bodyBytes().length > BODY_PREVIEW_BYTES);
        capture.put("responseStatus", problem.status());
        capture.put("responseHeaders", rawStringListMap(Map.of("content-type", List.of("application/problem+json"))));
        capture.put("runOutcome", "rejected");
        capture.put("replyMode", "none");
        capture.put("durationMs", durationMs);
        addProblem(capture, problem);
        writeCapture(rejectedCapturePath(runsRoot, appId, flowId, requestId), capture);
    }

    static Path failureCapturePath(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String requestId
    ) {
        return boundaryCapturePath(runsRoot, appId, flowId, requestId, FAILURES_DIR);
    }

    static void writeFailure(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String requestId,
            final HttpRequestSnapshot snapshot,
            final HttpProblemException problem,
            final long durationMs
    ) {
        final Map<String, Object> capture = baseCapture(
                Instant.now(),
                "http",
                source(snapshot.localAddress()),
                appId,
                flowId,
                Objects.requireNonNull(requestId, "requestId")
        );
        capture.put("captureKind", CAPTURE_KIND_BOUNDARY_FAILURE);
        capture.put("method", snapshot.method());
        capture.put("path", snapshot.requestUri().getPath().isEmpty() ? "/" : snapshot.requestUri().getPath());
        capture.put("remoteAddress", snapshot.remoteAddress().getHostString());
        capture.put("query", rawStringListMap(HttpQueryCodec.parseRawQuery(snapshot.requestUri().getRawQuery())));
        capture.put("requestHeaders", lowerCaseHeaders(snapshot.headers()));
        capture.put("requestBodyPreview", bodyPreview(snapshot.bodyBytes()));
        capture.put("requestBodySize", snapshot.bodyBytes().length);
        capture.put("requestBodyTruncated", snapshot.bodyBytes().length > BODY_PREVIEW_BYTES);
        capture.put("responseStatus", problem.status());
        capture.put("responseHeaders", rawStringListMap(Map.of("content-type", List.of("application/problem+json"))));
        capture.put("runOutcome", "failed");
        capture.put("replyMode", "none");
        capture.put("durationMs", durationMs);
        addProblem(capture, problem);
        writeCapture(failureCapturePath(runsRoot, appId, flowId, requestId), capture);
    }

    private static Map<String, Object> baseCapture(
            final Instant timestamp,
            final String protocol,
            final String source,
            final String appId,
            final String flowId,
            final String runId
    ) {
        final Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("timestamp", Objects.requireNonNull(timestamp, "timestamp").toString());
        capture.put("protocol", requireNonBlank(protocol, "protocol"));
        capture.put("source", requireNonBlank(source, "source"));
        capture.put("appId", requireNonBlank(appId, "appId"));
        capture.put("flowId", requireNonBlank(flowId, "flowId"));
        capture.put("runId", requireNonBlank(runId, "runId"));
        return capture;
    }

    private static void addProblem(final Map<String, Object> capture, final HttpProblemException problem) {
        if (problem == null) {
            return;
        }
        capture.put("problemType", problem.type());
        capture.put("problemTitle", problem.title());
        capture.put("problemDetail", problem.detail());
    }

    private static void writeCapture(final Path targetFile, final Map<String, Object> capture) {
        try {
            Files.createDirectories(targetFile.getParent());
            final Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp");
            Files.writeString(tempFile, KernelContractCodec.toStableJson(capture));
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, List<String>> lowerCaseHeaders(final Map<String, List<String>> headers) {
        final Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
            normalized.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private static String bodyPreview(final byte[] bodyBytes) {
        final int length = Math.min(bodyBytes.length, BODY_PREVIEW_BYTES);
        return new String(bodyBytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String source(final java.net.InetSocketAddress localAddress) {
        return "http." + requireNonBlank(localAddress.getHostString(), "localAddress.host") + ":" + localAddress.getPort();
    }

    private static Path boundaryCapturePath(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String requestId,
            final String directory
    ) {
        return runsRoot
                .resolve(appId)
                .resolve(flowId)
                .resolve(directory)
                .resolve(requestId)
                .resolve(FILE_NAME);
    }

    private static RailixValue.ObjectValue metadataObject(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException("Envelope metadata field '" + key + "' must be an object");
    }

    private static String metadataString(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (value instanceof RailixValue.StringValue stringValue) {
            return stringValue.value();
        }
        throw new IllegalArgumentException("Envelope metadata field '" + key + "' must be a string");
    }

    private static Map<String, Object> rawStringMultiMap(final RailixValue.ObjectValue values) {
        final Map<String, Object> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, RailixValue> entry : values.values().entrySet()) {
            final RailixValue value = entry.getValue();
            if (value instanceof RailixValue.StringValue stringValue) {
                normalized.put(entry.getKey(), List.of(stringValue.value()));
                continue;
            }
            if (value instanceof RailixValue.ListValue listValue) {
                final List<String> strings = new ArrayList<>();
                for (final RailixValue item : listValue.values()) {
                    if (item instanceof RailixValue.StringValue stringValue) {
                        strings.add(stringValue.value());
                    } else {
                        throw new IllegalArgumentException("Expected string list values in HTTP capture metadata");
                    }
                }
                normalized.put(entry.getKey(), List.copyOf(strings));
                continue;
            }
            throw new IllegalArgumentException("Expected string or string-list HTTP capture metadata values");
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> rawStringListMap(final Map<String, List<String>> values) {
        final Map<String, Object> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : values.entrySet()) {
            normalized.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> rawJsonObject(final RailixValue.ObjectValue objectValue) {
        final Map<String, Object> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, RailixValue> entry : objectValue.values().entrySet()) {
            normalized.put(entry.getKey(), rawJsonValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object rawJsonValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> null;
            case RailixValue.BoolValue boolValue -> boolValue.value();
            case RailixValue.NumberValue numberValue -> rawNumber(numberValue.value());
            case RailixValue.StringValue stringValue -> stringValue.value();
            case RailixValue.ListValue listValue -> listValue.values().stream().map(HttpCaptureArtifact::rawJsonValue).toList();
            case RailixValue.ObjectValue objectValue -> rawJsonObject(objectValue);
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
                    "metadata", rawJsonObject(new RailixValue.ObjectValue(streamRef.metadata()))
            );
            case RailixValue.SessionRef sessionRef -> Map.of(
                    "kind", "session-ref",
                    "id", sessionRef.id(),
                    "protocol", sessionRef.protocol(),
                    "metadata", rawJsonObject(new RailixValue.ObjectValue(sessionRef.metadata()))
            );
            case RailixValue.DeferredRef deferredRef -> Map.of(
                    "kind", "deferred-ref",
                    "id", deferredRef.id(),
                    "statusPath", deferredRef.statusPath(),
                    "metadata", rawJsonObject(new RailixValue.ObjectValue(deferredRef.metadata()))
            );
            case RailixValue.SecretRef secretRef -> Map.of(
                    "kind", "secret-ref",
                    "path", secretRef.path().toString()
            );
        };
    }

    private static Number rawNumber(final BigDecimal value) {
        final BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() <= 0) {
            try {
                return normalized.longValueExact();
            } catch (final ArithmeticException ignored) {
                return normalized;
            }
        }
        return normalized;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
