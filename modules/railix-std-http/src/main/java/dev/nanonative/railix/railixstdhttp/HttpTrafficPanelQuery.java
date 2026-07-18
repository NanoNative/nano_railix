package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.stream.Stream;

public final class HttpTrafficPanelQuery {

    public List<HttpTrafficRow> read(final Path runsRoot, final int limit) {
        Objects.requireNonNull(runsRoot, "runsRoot");
        requireNonNegative(limit, "limit");
        if (limit == 0 || !Files.exists(runsRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.find(
                runsRoot,
                Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile() && HttpCaptureArtifact.FILE_NAME.equals(path.getFileName().toString())
        )) {
            final PriorityQueue<HttpTrafficRow> newestRows = new PriorityQueue<>(Comparator.comparing(HttpTrafficRow::timestamp));
            files.map(HttpTrafficPanelQuery::readRow).forEach(row -> offerNewest(newestRows, row, limit));
            return newestRows.stream()
                    .sorted(Comparator.comparing(HttpTrafficRow::timestamp).reversed())
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void offerNewest(
            final PriorityQueue<HttpTrafficRow> newestRows,
            final HttpTrafficRow candidate,
            final int limit
    ) {
        if (newestRows.size() < limit) {
            newestRows.offer(candidate);
            return;
        }
        final HttpTrafficRow oldest = newestRows.peek();
        if (oldest != null && candidate.timestamp().isAfter(oldest.timestamp())) {
            newestRows.poll();
            newestRows.offer(candidate);
        }
    }

    private static HttpTrafficRow readRow(final Path file) {
        final Map<String, Object> capture;
        try {
            capture = KernelContractCodec.parseStableJsonObject(Files.readString(file));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException("HTTP capture file is invalid JSON: " + file, exception);
        }
        return new HttpTrafficRow(
                instantField(capture, "timestamp", file),
                stringField(capture, "appId", file),
                stringField(capture, "flowId", file),
                stringField(capture, "runId", file),
                stringField(capture, "method", file),
                stringField(capture, "path", file),
                intField(capture, "responseStatus", file),
                longField(capture, "durationMs", file),
                stringField(capture, "runOutcome", file),
                captureKind(capture, file)
        );
    }

    private static String captureKind(final Map<String, Object> capture, final Path file) {
        final Object value = capture.get("captureKind");
        if (value == null) {
            return "handled-run";
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new IllegalArgumentException("HTTP capture field 'captureKind' must be a non-blank string in " + file);
    }

    private static Instant instantField(final Map<String, Object> capture, final String field, final Path file) {
        try {
            return Instant.parse(stringField(capture, field, file));
        } catch (final RuntimeException exception) {
            throw new IllegalArgumentException("HTTP capture field '" + field + "' must be an ISO-8601 timestamp in " + file, exception);
        }
    }

    private static String stringField(final Map<String, Object> capture, final String field, final Path file) {
        final Object value = capture.get(field);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        throw new IllegalArgumentException("HTTP capture field '" + field + "' must be a non-blank string in " + file);
    }

    private static int intField(final Map<String, Object> capture, final String field, final Path file) {
        final long value = longField(capture, field, file);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("HTTP capture field '" + field + "' is out of int range in " + file);
        }
        return Math.toIntExact(value);
    }

    private static long longField(final Map<String, Object> capture, final String field, final Path file) {
        final Object value = capture.get(field);
        return switch (value) {
            case Integer integerValue -> integerValue.longValue();
            case Long longValue -> longValue;
            case BigDecimal decimalValue -> {
                try {
                    yield decimalValue.longValueExact();
                } catch (final ArithmeticException exception) {
                    throw new IllegalArgumentException("HTTP capture field '" + field + "' must be an integer in " + file, exception);
                }
            }
            default -> throw new IllegalArgumentException("HTTP capture field '" + field + "' must be a number in " + file);
        };
    }

    private static void requireNonNegative(final int value, final String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
    }

    public record HttpTrafficRow(
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String method,
            String path,
            int status,
            long durationMs,
            String outcome,
            String captureKind
    ) {
        public HttpTrafficRow {
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = Objects.requireNonNull(appId, "appId");
            flowId = Objects.requireNonNull(flowId, "flowId");
            runId = Objects.requireNonNull(runId, "runId");
            method = Objects.requireNonNull(method, "method");
            path = Objects.requireNonNull(path, "path");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must be >= 0");
            }
            outcome = Objects.requireNonNull(outcome, "outcome");
            captureKind = Objects.requireNonNull(captureKind, "captureKind");
        }
    }
}
