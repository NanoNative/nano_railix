package dev.nanonative.railix.core.runtime;

import com.sun.net.httpserver.HttpExchange;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared authentication, body bounds and encoding for application management endpoints. */
public final class ManagementHttp {
    private static final int MAX_CONTEXT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int REQUEST_BODY_SECONDS = 5;

    private ManagementHttp() { }

    /**
     * Checks exactly one bearer credential with a constant-time byte comparison.
     * @param exchange incoming management request
     * @param authorization expected complete Authorization header in UTF-8
     * @return whether the single supplied credential matches
     */
    public static boolean authorized(final HttpExchange exchange, final byte[] authorization) {
        final List<String> values = exchange.getRequestHeaders().getOrDefault("Authorization", List.of());
        return values.size() == 1 && MessageDigest.isEqual(authorization, values.getFirst().getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<byte[]> requestBody(final HttpExchange exchange) throws IOException {
        final AtomicBoolean reading = new AtomicBoolean(true);
        final Thread deadline = Thread.ofVirtual().name("railix-management-body-deadline").start(() -> {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(REQUEST_BODY_SECONDS));
                if (reading.compareAndSet(true, false)) {
                    exchange.close();
                }
            } catch (final InterruptedException ignoredCompletedRead) {
                // The request body completed before its deadline.
            }
        });
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8_192];
        try {
            final InputStream input = exchange.getRequestBody();
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                final int retained = Math.min(read, MAX_CONTEXT_BYTES + 1 - body.size());
                if (retained > 0) {
                    body.write(buffer, 0, retained);
                }
            }
            return reading.compareAndSet(true, false) ? Optional.of(body.toByteArray()) : Optional.empty();
        } catch (final IOException failure) {
            if (reading.compareAndSet(true, false)) {
                throw failure;
            }
            return Optional.empty();
        } finally {
            deadline.interrupt();
        }
    }

    /**
     * Reads an admitted body, retaining at most the source limit plus one overflow byte.
     * @param exchange request to read, closed on saturation or expiration of its five-second deadline
     * @param admission caller-owned concurrent body budget, released on every admitted exit
     * @return bytes read, or empty when admission was denied or the read expired
     * @throws IOException for a transport failure before expiration
     */
    public static Optional<byte[]> admittedBody(
            final HttpExchange exchange,
            final Semaphore admission
    ) throws IOException {
        if (!admission.tryAcquire()) {
            exchange.close();
            return Optional.empty();
        }
        try {
            return requestBody(exchange);
        } finally {
            admission.release();
        }
    }

    /**
     * Encodes a rejected response with its diagnostic list.
     * @param diagnostics structured failures, without workflow payloads
     * @return canonical rejected response
     */
    public static RailixValue diagnostics(final List<Diagnostic> diagnostics) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("rejected"),
                "diagnostics", diagnosticValues(diagnostics)
        ));
    }

    /**
     * Encodes diagnostic code, message and path fields.
     * @param diagnostics failures to encode in order
     * @return canonical diagnostic array
     */
    public static RailixValue.ArrayValue diagnosticValues(final List<Diagnostic> diagnostics) {
        return RailixValue.array(diagnostics.stream().<RailixValue>map(diagnostic -> RailixValue.object(Map.of(
                "code", RailixValue.string(diagnostic.code()),
                "message", RailixValue.string(diagnostic.message()),
                "path", RailixValue.string(diagnostic.path())
        ))).toList());
    }

    /**
     * Encodes a rejected response containing one diagnostic.
     * @param code machine-readable failure identifier
     * @param message human-readable failure description
     * @param path location associated with the failure, empty when not applicable
     * @return canonical rejected response
     */
    public static RailixValue problem(final String code, final String message, final String path) {
        return diagnostics(List.of(Diagnostic.atPath(code, message, path)));
    }

    /**
     * Sends canonical JSON within the application source-size limit.
     * @param exchange response destination
     * @param status intended HTTP status
     * @param value canonical response value
     * @throws IOException for a response transport failure
     */
    public static void send(
            final HttpExchange exchange,
            final int status,
            final RailixValue value
    ) throws IOException {
        send(
                exchange,
                status,
                value,
                MAX_CONTEXT_BYTES,
                "RUN_RESPONSE_TOO_LARGE",
                "Application response exceeds the 1048576-byte limit."
        );
    }

    /**
     * Sends bounded canonical JSON, or a structured encoding/size failure.
     * @param exchange response destination
     * @param status intended HTTP status
     * @param value canonical response value
     * @param limit maximum encoded UTF-8 byte count
     * @param code diagnostic identifier for an oversized response
     * @param message diagnostic message for an oversized response
     * @throws IOException for a response transport failure
     */
    public static void send(
            final HttpExchange exchange,
            final int status,
            final RailixValue value,
            final int limit,
            final String code,
            final String message
    ) throws IOException {
        final Optional<String> json;
        try {
            json = RailixJson.writeDocument(value, limit);
        } catch (final IllegalArgumentException exception) {
            sendInvalidResponse(exchange);
            return;
        }
        if (json.isPresent()) {
            send(exchange, status, json.orElseThrow().getBytes(StandardCharsets.UTF_8));
            return;
        }
        final String problem = RailixJson.write(problem(
                code,
                message,
                ""
        ), MAX_CONTEXT_BYTES).orElseThrow();
        send(exchange, 413, problem.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendInvalidResponse(final HttpExchange exchange) throws IOException {
        final String response = RailixJson.write(problem(
                "RUN_RESPONSE_INVALID",
                "Application response is not valid canonical JSON.",
                ""
        ), MAX_CONTEXT_BYTES).orElseThrow();
        send(exchange, 500, response.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends pre-encoded JSON with caching disabled.
     * @param exchange response destination
     * @param status HTTP status
     * @param body caller-bounded UTF-8 JSON bytes
     * @throws IOException for a response transport failure
     */
    public static void send(
            final HttpExchange exchange,
            final int status,
            final byte[] body
    ) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    /**
     * Sends a caller-bounded encoded response with caching disabled.
     * @param exchange response destination
     * @param status HTTP status
     * @param contentType complete response Content-Type
     * @param body bytes to transmit unchanged
     * @throws IOException for a response transport failure
     */
    public static void send(
            final HttpExchange exchange,
            final int status,
            final String contentType,
            final byte[] body
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }

}
