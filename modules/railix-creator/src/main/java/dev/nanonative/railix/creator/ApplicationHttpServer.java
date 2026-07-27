package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One loopback listener for every compiler-validated HTTP trigger in an application. */
final class ApplicationHttpServer {
    private static final int MAX_EVENT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_REPLY_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_CONCURRENT_REQUESTS = 32;

    private final HttpServer server;
    private final ExecutorService executor;
    private final CompiledFlow flow;
    private final Map<String, String> routes;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean ready;
    private final Semaphore requests = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private boolean open = true;

    private ApplicationHttpServer(
            final HttpServer server,
            final ExecutorService executor,
            final CompiledFlow flow,
            final Map<String, String> routes,
            final AtomicBoolean ready
    ) {
        this.server = server;
        this.executor = executor;
        this.flow = flow;
        this.routes = Map.copyOf(routes);
        this.ready = ready;
    }

    static ApplicationHttpServer start(final CompiledFlow flow) throws IOException {
        return start(flow, new AtomicBoolean());
    }

    static ApplicationHttpServer start(
            final CompiledFlow flow,
            final AtomicBoolean ready
    ) throws IOException {
        if (flow == null) {
            throw new IllegalArgumentException("Compiled flow cannot be Java null.");
        }
        if (ready == null) {
            throw new IllegalArgumentException("Application readiness cannot be Java null.");
        }
        final List<CompiledFlow.Trigger> triggers = flow.triggers().stream()
                .filter(trigger -> "http".equals(trigger.type()))
                .toList();
        if (triggers.isEmpty()) {
            throw new IllegalArgumentException("Compiled flow has no HTTP trigger.");
        }
        final int port = ((RailixValue.NumberValue) triggers.getFirst()
                .config().values().get("port")).value().intValueExact();
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer httpServer;
        try {
            httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port),
                    0
            );
        } catch (final IOException exception) {
            executor.shutdownNow();
            throw exception;
        }
        final ApplicationHttpServer application = new ApplicationHttpServer(
                httpServer,
                executor,
                flow,
                routes(flow, triggers),
                ready
        );
        try {
            httpServer.setExecutor(executor);
            httpServer.createContext("/", application::handle);
            httpServer.start();
        } catch (final RuntimeException exception) {
            httpServer.stop(0);
            executor.shutdownNow();
            throw exception;
        }
        return application;
    }

    synchronized ApplicationHttpServer ready() {
        if (!open) {
            throw new IllegalStateException("Application HTTP server is not running.");
        }
        ready.set(true);
        return this;
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    ApplicationHttpServer awaitStop() throws InterruptedException {
        stopped.await();
        return this;
    }

    synchronized ApplicationHttpServer stop() {
        if (open) {
            open = false;
            ready.set(false);
            try {
                server.stop(0);
            } finally {
                executor.shutdownNow();
                stopped.countDown();
            }
        }
        return this;
    }

    private void handle(final HttpExchange exchange) {
        if (!requests.tryAcquire()) {
            sendSafely(exchange, problem(
                    503,
                    "HTTP_BACKPRESSURE",
                    "Service Unavailable",
                    "HTTP request limit is reached."
            ));
            exchange.close();
            return;
        }
        boolean interrupted = false;
        try {
            final Response response = route(exchange);
            interrupted = Thread.interrupted();
            send(exchange, response);
        } catch (final IOException | RuntimeException exception) {
            interrupted = Thread.interrupted() || interrupted;
            sendSafely(exchange, problem(
                    500,
                    "HTTP_REQUEST_FAILED",
                    "Internal Server Error",
                    "HTTP request could not be completed."
            ));
        } finally {
            interrupted = Thread.interrupted() || interrupted;
            exchange.close();
            requests.release();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Response route(final HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawQuery() != null) {
            return problem(
                    400,
                    "HTTP_QUERY_UNSUPPORTED",
                    "Bad Request",
                    "HTTP event routes do not accept a query."
            );
        }
        final String path = exchange.getRequestURI().getPath();
        if (!routes.containsKey(path)) {
            return problem(404, "HTTP_ROUTE_NOT_FOUND", "Not Found", "HTTP route is not enabled.");
        }
        if (!ready.get()) {
            return problem(
                    503,
                    "APPLICATION_STARTING",
                    "Service Unavailable",
                    "Application startup has not completed."
            );
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            return problem(
                    405,
                    "HTTP_METHOD_NOT_ALLOWED",
                    "Method Not Allowed",
                    "HTTP event routes accept POST only."
            );
        }
        if (!jsonContentType(exchange)) {
            return problem(
                    415,
                    "HTTP_CONTENT_TYPE_UNSUPPORTED",
                    "Unsupported Media Type",
                    "HTTP event Content-Type must be application/json."
            );
        }

        final byte[] source = exchange.getRequestBody().readNBytes(MAX_EVENT_BYTES + 1);
        if (source.length > MAX_EVENT_BYTES) {
            return problem(
                    413,
                    "HTTP_EVENT_TOO_LARGE",
                    "Content Too Large",
                    "HTTP event exceeds the " + MAX_EVENT_BYTES + "-byte limit."
            );
        }
        final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, source);
        if (normalized instanceof RailixData.Invalid invalid) {
            return invalidEvent(invalid);
        }
        final RailixValue value = ((RailixData.Normalized) normalized).value();
        if (!(value instanceof RailixValue.ObjectValue event)) {
            return applicationResponse(new RailixRunner.Rejected(
                    2,
                    RailixProtocol.diagnostics(
                            "run-rejected",
                            List.of(Diagnostic.atPath(
                                    "FLOW_INPUT_OBJECT_REQUIRED",
                                    "Flow inputs must be an object.",
                                    "inputs"
                            )),
                            List.of()
                    )
            ));
        }

        final String step = routes.get(path);
        final RailixRunner.Outcome outcome = step.isEmpty()
                ? RailixRunner.run(flow, event)
                : RailixRunner.runStepEvent(flow, step, event);
        return applicationResponse(outcome);
    }

    private static Response invalidEvent(final RailixData.Invalid invalid) {
        final String code = switch (invalid.code()) {
            case "DATA_SOURCE_UTF8_INVALID" -> "HTTP_EVENT_UTF8_INVALID";
            case "DATA_DEPTH_EXCEEDED" -> "HTTP_EVENT_DEPTH_EXCEEDED";
            case "DATA_NUMBER_LIMIT_EXCEEDED" -> "HTTP_EVENT_NUMBER_LIMIT_EXCEEDED";
            default -> "HTTP_EVENT_JSON_INVALID";
        };
        return problem(400, code, "Bad Request", invalid.message());
    }

    private static Response applicationResponse(final RailixRunner.Outcome outcome) {
        final int status = switch (outcome) {
            case RailixRunner.Completed ignored -> 200;
            case RailixRunner.Rejected rejected -> switch (rejected.exitCode()) {
                case 2 -> 422;
                case 3 -> 500;
                case 130 -> 503;
                default -> 500;
            };
        };
        return RailixJson.write(outcome.payload(), MAX_REPLY_BYTES)
                .map(source -> json(status, source))
                .orElseGet(() -> problem(
                        500,
                        "HTTP_REPLY_TOO_LARGE",
                        "Internal Server Error",
                        "HTTP reply exceeds the " + MAX_REPLY_BYTES + "-byte limit."
                ));
    }

    private static boolean jsonContentType(final HttpExchange exchange) {
        final String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType != null
                && contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json");
    }

    private static Map<String, String> routes(
            final CompiledFlow flow,
            final List<CompiledFlow.Trigger> triggers
    ) {
        final Map<String, String> routes = new LinkedHashMap<>();
        for (final CompiledFlow.Trigger trigger : triggers) {
            final Map<String, RailixValue> config = trigger.config().values();
            if (config.get("path") instanceof RailixValue.StringValue path) {
                routes.put(path.value(), "");
            } else if (config.containsKey("flow")) {
                routes.put("/v1/flows/" + flow.id() + "/events", "");
            } else {
                final String step = ((RailixValue.StringValue) config.get("step")).value();
                routes.put("/v1/flows/" + flow.id() + "/steps/" + step + "/events", step);
            }
        }
        return routes;
    }

    private static Response problem(
            final int status,
            final String code,
            final String title,
            final String detail
    ) {
        return new Response(
                status,
                "application/problem+json; charset=utf-8",
                RailixJson.write(RailixValue.object(Map.of(
                        "code", RailixValue.string(code),
                        "detail", RailixValue.string(detail),
                        "status", RailixValue.number(status),
                        "title", RailixValue.string(title),
                        "type", RailixValue.string("about:blank")
                ))).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static Response json(final int status, final String source) {
        return new Response(
                status,
                "application/json; charset=utf-8",
                source.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void send(final HttpExchange exchange, final Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (response.status() == 405) {
            exchange.getResponseHeaders().set("Allow", "POST");
        }
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
    }

    private static void sendSafely(final HttpExchange exchange, final Response response) {
        try {
            send(exchange, response);
        } catch (final IOException ignored) {
            // The request peer already owns the disconnected transport.
        }
    }

    private record Response(int status, String contentType, byte[] body) {
    }
}
