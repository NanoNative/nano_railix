package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback-only Creator server backed by the real compiler and local executor. */
public final class CreatorServer implements AutoCloseable {
    private static final int MAX_COMPILE_REQUEST_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_RUN_REQUEST_BYTES = RailixData.MAX_SOURCE_BYTES;
    private static final int MAX_RUN_RESPONSE_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final String WEB_ROOT = "/dev/nanonative/railix/creator/web/";

    private final HttpServer server;
    private final ExecutorService executor;
    private final StepCatalog catalog;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);

    private CreatorServer(
            final HttpServer server,
            final ExecutorService executor,
            final StepCatalog catalog
    ) {
        this.server = server;
        this.executor = executor;
        this.catalog = catalog;
    }

    public static CreatorServer start(final int port) throws IOException {
        return start(port, StandardLibrary.catalog());
    }

    /**
     * Starts Creator with exactly the supplied trusted Step dependencies.
     *
     * @param port loopback port, or zero for an operating-system assigned port
     * @param catalog explicit trusted Step dependencies used by palette, compiler, and runner
     * @return the running loopback Creator server
     * @throws IOException when the loopback server cannot start
     * @throws IllegalArgumentException when the catalog is Java null
     */
    public static CreatorServer start(final int port, final StepCatalog catalog) throws IOException {
        if (catalog == null) {
            throw new IllegalArgumentException("Creator Step catalog cannot be Java null.");
        }
        final HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port),
                0
        );
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final CreatorServer creator = new CreatorServer(httpServer, executor, catalog);
        httpServer.setExecutor(executor);
        httpServer.createContext("/", creator::handle);
        httpServer.start();
        return creator;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** Blocks until close and returns this server. */
    public CreatorServer awaitClose() throws InterruptedException {
        closed.await();
        return this;
    }

    @Override
    public synchronized void close() {
        if (open.compareAndSet(true, false)) {
            server.stop(0);
            executor.shutdownNow();
            executor.close();
            closed.countDown();
        }
    }

    private void handle(final HttpExchange exchange) {
        try {
            send(exchange, route(exchange));
        } catch (final RequestTooLarge exception) {
            sendSafely(exchange, json(413, RailixProtocol.error(
                    "request-rejected",
                    "REQUEST_TOO_LARGE",
                    "Request body exceeds " + exception.maxBytes() + " bytes."
            )));
        } catch (final InvalidUtf8 exception) {
            sendSafely(exchange, json(400, RailixProtocol.error(
                    "request-rejected",
                    "REQUEST_UTF8_INVALID",
                    "Request body must be valid UTF-8."
            )));
        } catch (final RuntimeException | IOException exception) {
            sendSafely(exchange, json(500, RailixProtocol.error(
                    "server-failed",
                    "CREATOR_INTERNAL_FAILURE",
                    "Creator could not process the request."
            )));
        } finally {
            exchange.close();
        }
    }

    private Response route(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        return switch (path) {
            case "/", "/index.html" -> staticResource(exchange, "index.html", "text/html; charset=utf-8");
            case "/app.css" -> staticResource(exchange, "app.css", "text/css; charset=utf-8");
            case "/app.js" -> staticResource(exchange, "app.js", "text/javascript; charset=utf-8");
            case "/health", "/api/health" -> getOnly(exchange, json(200, RailixValue.object(Map.of(
                    "status", RailixValue.string("ready")
            ))));
            case "/api/steps" -> getOnly(exchange, json(200, stepsPayload(catalog)));
            case "/api/compile" -> "POST".equals(exchange.getRequestMethod())
                    ? compile(readBody(exchange, MAX_COMPILE_REQUEST_BYTES), catalog)
                    : methodNotAllowed();
            case "/api/run" -> "POST".equals(exchange.getRequestMethod())
                    ? run(readBody(exchange, MAX_RUN_REQUEST_BYTES), catalog)
                    : methodNotAllowed();
            default -> json(404, RailixProtocol.error("request-rejected", "NOT_FOUND", "Route does not exist."));
        };
    }

    private static Response getOnly(final HttpExchange exchange, final Response response) {
        return "GET".equals(exchange.getRequestMethod()) ? response : methodNotAllowed();
    }

    private static Response staticResource(
            final HttpExchange exchange,
            final String name,
            final String contentType
    ) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            return methodNotAllowed();
        }
        try (InputStream input = CreatorServer.class.getResourceAsStream(WEB_ROOT + name)) {
            if (input == null) {
                return json(500, RailixProtocol.error(
                        "server-failed",
                        "CREATOR_ASSET_MISSING",
                        "Creator asset is missing."
                ));
            }
            return new Response(200, contentType, input.readAllBytes());
        }
    }

    private static Response compile(final String source, final StepCatalog catalog) {
        final CompileResult result = FlowCompiler.compile(source, catalog);
        return switch (result) {
            case CompileResult.Rejected rejected -> json(
                    422,
                    RailixProtocol.diagnostics("compile-rejected", rejected.diagnostics())
            );
            case CompileResult.Compiled compiled -> json(200, compiledPayload(compiled));
        };
    }

    private static Response run(final String requestSource, final StepCatalog catalog) {
        final RailixData.Result parsed = RailixData.normalize(
                RailixData.Format.JSON,
                requestSource.getBytes(StandardCharsets.UTF_8),
                MAX_RUN_REQUEST_BYTES,
                RailixData.DEFAULT_MAX_DEPTH
        );
        if (parsed instanceof RailixData.Invalid invalid) {
            if ("DATA_NUMBER_LIMIT_EXCEEDED".equals(invalid.code())) {
                return json(422, RailixProtocol.diagnostics(
                        "run-rejected",
                        List.of(new Diagnostic(
                                "FLOW_NUMBER_LIMIT_EXCEEDED",
                                invalid.message(),
                                "$",
                                invalid.line(),
                                invalid.column()
                        )),
                        List.of()
                ));
            }
            final String message = "DATA_DEPTH_EXCEEDED".equals(invalid.code())
                    ? "JSON exceeds the maximum container depth of " + RailixData.DEFAULT_MAX_DEPTH + "."
                    : invalid.message();
            return json(400, RailixProtocol.diagnostics("request-rejected", List.of(new Diagnostic(
                    "REQUEST_JSON_INVALID",
                    message,
                    "$",
                    invalid.line(),
                    invalid.column()
            ))));
        }
        final RailixValue request = ((RailixData.Normalized) parsed).value();
        if (!(request instanceof RailixValue.ObjectValue object)
                || !(object.values().get("flow") instanceof RailixValue.ObjectValue flow)
                || !(object.values().get("event") instanceof RailixValue.ObjectValue event)
                || !(event.values().get("format") instanceof RailixValue.StringValue format)
                || !(event.values().get("source") instanceof RailixValue.StringValue source)) {
            return json(400, RailixProtocol.error(
                    "request-rejected",
                    "RUN_REQUEST_INVALID",
                    "Run request requires object field flow and event object string fields format and source."
            ));
        }
        final RailixData.Format dataFormat;
        switch (format.value()) {
            case "json" -> dataFormat = RailixData.Format.JSON;
            case "yaml" -> dataFormat = RailixData.Format.YAML;
            case "xml" -> dataFormat = RailixData.Format.XML;
            default -> {
                return json(400, RailixProtocol.error(
                        "request-rejected",
                        "EVENT_FORMAT_UNSUPPORTED",
                        "Event format must be json, yaml, or xml."
                ));
            }
        }
        final RailixData.Result normalized = RailixData.normalize(
                dataFormat,
                source.value().getBytes(StandardCharsets.UTF_8)
        );
        if (normalized instanceof RailixData.Invalid invalid) {
            return json(422, RailixProtocol.diagnostics("event-rejected", List.of(new Diagnostic(
                    invalid.code(),
                    invalid.message(),
                    "event.source",
                    invalid.line(),
                    invalid.column()
            ))));
        }
        final RailixValue eventValue = ((RailixData.Normalized) normalized).value();
        if (!(eventValue instanceof RailixValue.ObjectValue input)) {
            return json(422, RailixProtocol.diagnostics("event-rejected", List.of(Diagnostic.atPath(
                    "FLOW_INPUT_OBJECT_REQUIRED",
                    "Flow inputs must be an object.",
                    "event.source"
            ))));
        }
        final RailixRunner.Outcome outcome = RailixRunner.run(
                RailixJson.write(flow),
                RailixJson.write(input),
                catalog
        );
        return outcome instanceof RailixRunner.Completed completed
                ? runJson(200, completed.payload())
                : runJson(422, ((RailixRunner.Rejected) outcome).payload());
    }

    private static RailixValue.ObjectValue compiledPayload(final CompileResult.Compiled compiled) {
        final CompiledFlow flow = compiled.flow();
        return RailixValue.object(Map.of(
                "status", RailixValue.string("compiled"),
                "flow", RailixValue.string(flow.id()),
                "inputs", shapes(flow.inputs()),
                "outputs", shapes(flow.outputs()),
                "source", RailixValue.string(compiled.source() + "\n")
        ));
    }

    private static RailixValue.ObjectValue stepsPayload(final StepCatalog catalog) {
        final List<RailixValue> steps = catalog.definitions().stream()
                .map(CreatorServer::stepPayload)
                .map(RailixValue.class::cast)
                .toList();
        return RailixValue.object(Map.of(
                "conversions", RailixValue.array(FlowCompiler.supportedConversions().stream()
                        .map(RailixValue::string)
                        .map(RailixValue.class::cast)
                        .toList()),
                "maxEventSourceBytes", RailixValue.number(RailixData.DEFAULT_MAX_SOURCE_BYTES),
                "shapes", shapeNames(),
                "status", RailixValue.string("ready"),
                "steps", RailixValue.array(steps),
                "triggers", RailixValue.array(FlowCompiler.supportedTriggers().stream()
                        .map(RailixValue::string)
                        .map(RailixValue.class::cast)
                        .toList())
        ));
    }

    private static RailixValue.ObjectValue stepPayload(final StepDefinition definition) {
        return RailixValue.object(Map.of(
                "config", config(definition.config()),
                "id", RailixValue.string(definition.id()),
                "kind", RailixValue.string(definition.kind().name().toLowerCase(Locale.ROOT)),
                "inputs", ports(definition.inputs()),
                "outputs", ports(definition.outputs()),
                "outcomes", RailixValue.array(definition.outcomes().stream()
                        .map(RailixValue::string)
                        .map(RailixValue.class::cast)
                        .toList())
        ));
    }

    private static RailixValue.ArrayValue config(final List<StepDefinition.Config> config) {
        return RailixValue.array(config.stream()
                .map(value -> {
                    final Map<String, RailixValue> fields = new LinkedHashMap<>();
                    fields.put("name", RailixValue.string(value.name()));
                    fields.put("shape", RailixValue.string(value.shape().orElseThrow().name().toLowerCase(Locale.ROOT)));
                    fields.put("required", RailixValue.bool(value.required()));
                    value.format().ifPresent(format ->
                            fields.put("format", RailixValue.string(format.wireName()))
                    );
                    value.defaultValue().ifPresent(defaultValue -> fields.put("default", defaultValue));
                    return RailixValue.object(fields);
                })
                .map(RailixValue.class::cast)
                .toList());
    }

    private static RailixValue.ArrayValue shapeNames() {
        return RailixValue.array(Arrays.stream(ValueShape.values())
                .map(shape -> RailixValue.string(shape.name().toLowerCase(Locale.ROOT)))
                .map(RailixValue.class::cast)
                .toList());
    }

    private static RailixValue.ArrayValue ports(final List<StepDefinition.Port> ports) {
        return RailixValue.array(ports.stream()
                .map(port -> RailixValue.object(Map.of(
                        "name", RailixValue.string(port.name()),
                        "shape", RailixValue.string(port.shape().orElseThrow().name().toLowerCase(Locale.ROOT))
                )))
                .map(RailixValue.class::cast)
                .toList());
    }

    private static RailixValue.ObjectValue shapes(final Map<String, ValueShape> shapes) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        for (final Map.Entry<String, ValueShape> shape : shapes.entrySet()) {
            result.put(shape.getKey(), RailixValue.string(shape.getValue().name().toLowerCase(Locale.ROOT)));
        }
        return RailixValue.object(result);
    }

    private static String readBody(final HttpExchange exchange, final int maxBytes) throws IOException {
        final byte[] body = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new RequestTooLarge(maxBytes);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        } catch (final CharacterCodingException exception) {
            throw new InvalidUtf8();
        }
    }

    private static Response methodNotAllowed() {
        return json(405, RailixProtocol.error(
                "request-rejected",
                "METHOD_NOT_ALLOWED",
                "HTTP method is not allowed for this route."
        ));
    }

    private static Response json(final int status, final RailixValue value) {
        return new Response(
                status,
                "application/json; charset=utf-8",
                RailixJson.write(value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Response runJson(final int status, final RailixValue value) {
        return RailixJson.write(value, MAX_RUN_RESPONSE_BYTES)
                .map(source -> new Response(
                        status,
                        "application/json; charset=utf-8",
                        source.getBytes(StandardCharsets.UTF_8)
                ))
                .orElseGet(() -> json(422, RailixProtocol.error(
                        "run-rejected",
                        "RUN_RESPONSE_TOO_LARGE",
                        "Run response exceeds " + MAX_RUN_RESPONSE_BYTES + " bytes."
                )));
    }

    private static void send(final HttpExchange exchange, final Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
    }

    private static void sendSafely(final HttpExchange exchange, final Response response) {
        try {
            send(exchange, response);
        } catch (final IOException ignored) {
            // The client disconnected before the deterministic error response could be written.
        }
    }

    private record Response(int status, String contentType, byte[] body) {
    }

    private static final class RequestTooLarge extends RuntimeException {
        private final int maxBytes;

        private RequestTooLarge(final int maxBytes) {
            super(null, null, false, false);
            this.maxBytes = maxBytes;
        }

        private int maxBytes() {
            return maxBytes;
        }
    }

    private static final class InvalidUtf8 extends RuntimeException {
        private InvalidUtf8() {
            super(null, null, false, false);
        }
    }
}
