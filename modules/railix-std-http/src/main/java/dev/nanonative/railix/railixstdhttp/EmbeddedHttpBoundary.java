package dev.nanonative.railix.railixstdhttp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.runtime.BuiltRailixApp;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;
import dev.nanonative.railix.kernel.model.SettingsTree;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class EmbeddedHttpBoundary implements AutoCloseable {

    private final BuiltRailixApp app;
    private final Path runsRoot;
    private final SettingsTree settingsTree;
    private final HttpEnvelopeMapper envelopeMapper;
    private final Function<Reply, HttpReplyAdapter.AdaptedHttpReply> replyAdapter;
    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final AtomicLong runCounter;
    private final HttpBoundarySettings boundarySettings;

    public EmbeddedHttpBoundary(final BuiltRailixApp app, final Path runsRoot, final SettingsTree settingsTree) {
        this(app, runsRoot, settingsTree, new HttpEnvelopeMapper(), new HttpReplyAdapter()::adapt);
    }

    EmbeddedHttpBoundary(
            final BuiltRailixApp app,
            final Path runsRoot,
            final SettingsTree settingsTree,
            final HttpEnvelopeMapper envelopeMapper,
            final Function<Reply, HttpReplyAdapter.AdaptedHttpReply> replyAdapter
    ) {
        this.app = java.util.Objects.requireNonNull(app, "app");
        this.runsRoot = java.util.Objects.requireNonNull(runsRoot, "runsRoot");
        this.settingsTree = java.util.Objects.requireNonNull(settingsTree, "settingsTree");
        this.envelopeMapper = java.util.Objects.requireNonNull(envelopeMapper, "envelopeMapper");
        this.replyAdapter = java.util.Objects.requireNonNull(replyAdapter, "replyAdapter");
        this.runCounter = new AtomicLong(1L);
        this.boundarySettings = HttpBoundarySettings.from(settingsTree);
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(this.boundarySettings.host(), this.boundarySettings.port()), 0);
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to create HTTP listener on " + this.boundarySettings.host() + ":" + this.boundarySettings.port(), exception);
        }
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpServer.setExecutor(executor);
        this.httpServer.createContext("/", this::handleExchange);
    }

    public void start() {
        httpServer.start();
    }

    public URI baseUri() {
        return URI.create("http://" + httpServer.getAddress().getHostString() + ":" + httpServer.getAddress().getPort());
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executor.close();
    }

    private void handleExchange(final HttpExchange exchange) throws IOException {
        final long startedAtNanos = System.nanoTime();
        HttpRequestSnapshot request = null;
        try {
            request = envelopeMapper.snapshot(exchange);
            final Envelope envelope;
            try {
                envelope = envelopeMapper.map(request);
            } catch (final HttpProblemException problem) {
                final String requestId = nextRejectedRequestId();
                HttpCaptureArtifact.writeRejected(
                        runsRoot,
                        app.plan().appId(),
                        app.plan().flowId(),
                        requestId,
                        request,
                        problem,
                        elapsedMillis(startedAtNanos)
                );
                writeProblem(exchange, problem, requestId);
                return;
            }
            final LocalExecutionKernel.RunRecord record = app.run(new LocalExecutionKernel.RunRequest(
                    nextRunId(),
                    runsRoot,
                    envelope,
                    settingsTree
            ));
            if ("failed".equals(record.outcome())) {
                final Map<String, Object> terminalFailure = terminalFailure(record.runFolder());
                final String phase = stringValue(terminalFailure.get("phase"));
                final String message = stringValue(terminalFailure.get("message"));
                if ("reply.validation".equals(phase)) {
                    final HttpProblemException problem = new HttpProblemException(
                            "urn:railix:http:unsupported-reply-mode",
                            "Unsupported Reply Mode",
                            501,
                            message.isBlank() ? "HTTP boundary does not support the produced reply mode yet" : message
                    );
                    HttpCaptureArtifact.write(
                            record.runFolder(),
                            Instant.now(),
                            request,
                            envelope,
                            record,
                            problem.status(),
                            problemResponseHeaders(record.runId()),
                            elapsedMillis(startedAtNanos),
                            problem
                    );
                    writeProblem(exchange, problem, record.runId());
                    return;
                }
                final HttpProblemException problem = new HttpProblemException(
                        "urn:railix:http:run-failed",
                        "Flow Execution Failed",
                        500,
                        message.isBlank() ? "Railix flow execution failed for HTTP request" : message
                );
                HttpCaptureArtifact.write(
                        record.runFolder(),
                        Instant.now(),
                        request,
                        envelope,
                        record,
                        problem.status(),
                        problemResponseHeaders(record.runId()),
                        elapsedMillis(startedAtNanos),
                        problem
                );
                writeProblem(exchange, problem, record.runId());
                return;
            }
            final HttpReplyAdapter.AdaptedHttpReply adaptedReply;
            try {
                adaptedReply = replyAdapter.apply(record.reply());
            } catch (final HttpProblemException problem) {
                HttpCaptureArtifact.write(
                        record.runFolder(),
                        Instant.now(),
                        request,
                        envelope,
                        record,
                        problem.status(),
                        problemResponseHeaders(record.runId()),
                        elapsedMillis(startedAtNanos),
                        problem
                );
                writeProblem(exchange, problem, record.runId());
                return;
            }
            HttpCaptureArtifact.write(
                    record.runFolder(),
                    Instant.now(),
                    request,
                    envelope,
                    record,
                    adaptedReply.status(),
                    headersWithRunId(adaptedReply.headers(), record.runId()),
                    elapsedMillis(startedAtNanos),
                    null
            );
            writeResponse(exchange, adaptedReply, record.runId());
        } catch (final HttpProblemException exception) {
            writeProblem(exchange, exception, null);
        } catch (final RuntimeException exception) {
            final HttpProblemException problem = new HttpProblemException(
                    "urn:railix:http:internal-error",
                    "Internal Server Error",
                    500,
                    exception.getMessage() == null ? "Unhandled HTTP boundary failure" : exception.getMessage()
            );
            final String requestId = request == null ? null : nextFailedRequestId();
            if (request != null) {
                HttpCaptureArtifact.writeFailure(
                        runsRoot,
                        app.plan().appId(),
                        app.plan().flowId(),
                        requestId,
                        request,
                        problem,
                        elapsedMillis(startedAtNanos)
                );
            }
            writeProblem(exchange, problem, requestId);
        } finally {
            exchange.close();
        }
    }

    private String nextRunId() {
        return "http-run-" + Instant.now().toEpochMilli() + "-" + runCounter.getAndIncrement();
    }

    private String nextRejectedRequestId() {
        return "http-rejected-" + Instant.now().toEpochMilli() + "-" + runCounter.getAndIncrement();
    }

    private String nextFailedRequestId() {
        return "http-failed-" + Instant.now().toEpochMilli() + "-" + runCounter.getAndIncrement();
    }

    private static Map<String, Object> terminalFailure(final Path runFolder) {
        final Path summaryFile = runFolder.resolve("summary.json");
        if (!Files.exists(summaryFile)) {
            return Map.of();
        }
        try {
            final Object terminalFailure = KernelContractCodec.parseStableJsonObject(Files.readString(summaryFile)).get("terminalFailure");
            if (!(terminalFailure instanceof Map<?, ?> rawFailure)) {
                return Map.of();
            }
            final Map<String, Object> normalized = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : rawFailure.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    normalized.put(key, entry.getValue());
                }
            }
            return Map.copyOf(normalized);
        } catch (final RuntimeException | java.io.IOException ignored) {
            return Map.of();
        }
    }

    private static String stringValue(final Object value) {
        return value instanceof String stringValue ? stringValue : "";
    }

    private static long elapsedMillis(final long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private static void writeResponse(
            final HttpExchange exchange,
            final HttpReplyAdapter.AdaptedHttpReply adaptedReply,
            final String runId
    ) throws IOException {
        addHeaders(exchange, adaptedReply.headers(), runId);
        exchange.sendResponseHeaders(adaptedReply.status(), adaptedReply.body().length);
        if (adaptedReply.body().length > 0) {
            exchange.getResponseBody().write(adaptedReply.body());
        }
    }

    private static void writeProblem(
            final HttpExchange exchange,
            final HttpProblemException problem,
            final String runId
    ) throws IOException {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.type());
        body.put("title", problem.title());
        body.put("status", problem.status());
        body.put("detail", problem.detail());
        final byte[] bodyBytes = KernelContractCodec.toStableJson(body).getBytes(StandardCharsets.UTF_8);
        addHeaders(exchange, Map.of("content-type", List.of("application/problem+json")), runId);
        exchange.sendResponseHeaders(problem.status(), bodyBytes.length);
        exchange.getResponseBody().write(bodyBytes);
    }

    private static Map<String, List<String>> problemResponseHeaders(final String runId) {
        return headersWithRunId(Map.of("content-type", List.of("application/problem+json")), runId);
    }

    private static Map<String, List<String>> headersWithRunId(
            final Map<String, List<String>> headers,
            final String runId
    ) {
        final Map<String, List<String>> merged = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
            merged.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        if (runId != null && !runId.isBlank()) {
            merged.put("x-railix-run-id", List.of(runId));
        }
        return Map.copyOf(merged);
    }

    private static void addHeaders(
            final HttpExchange exchange,
            final Map<String, List<String>> headers,
            final String runId
    ) {
        for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
            exchange.getResponseHeaders().put(entry.getKey(), new java.util.ArrayList<>(entry.getValue()));
        }
        if (runId != null && !runId.isBlank()) {
            exchange.getResponseHeaders().set("x-railix-run-id", runId);
        }
    }
}
