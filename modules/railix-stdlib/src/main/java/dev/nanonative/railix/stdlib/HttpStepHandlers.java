package dev.nanonative.railix.stdlib;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.RuntimeApplication;
import dev.nanonative.railix.core.project.WorkflowRuntime;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** HTTP-only standard Step implementations kept isolated from CLI-only applications. */
public final class HttpStepHandlers {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final int HTTP_BODY_LIMIT = 1024 * 1024;

    private HttpStepHandlers() {
    }

    /** Maps one HTTP request object to the configured workflow-context path. */
    public static final class Trigger implements StepHandler {
        @Override
        public StepResult run(final StepInput input) {
            return StepResult.outcome(input.primaryOutcome()).write("target", input.value("request"));
        }

        public static int serve(final RuntimeApplication application, final String host, final int port) {
            if (application == null) {
                throw new IllegalArgumentException("HTTP application cannot be Java null.");
            }
            final String bindHost = host == null || host.isBlank() ? "127.0.0.1" : host;
            try {
                final HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
                server.createContext("/", exchange -> handle(application, exchange));
                server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
                server.start();
                System.out.println("Railix HTTP http://" + bindHost + ":" + server.getAddress().getPort() + "/");
                Thread.currentThread().join();
                return 0;
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                return 130;
            } catch (final IOException exception) {
                System.err.println(exception.getMessage());
                return 2;
            }
        }

        private static void handle(final RuntimeApplication application, final HttpExchange exchange)
                throws IOException {
            try {
                final RailixValue.ObjectValue request = RailixValue.object(Map.of(
                        "method", RailixValue.string(exchange.getRequestMethod()),
                        "path", RailixValue.string(exchange.getRequestURI().getPath()),
                        "query", RailixValue.string(exchange.getRequestURI().getRawQuery() == null
                                ? ""
                                : exchange.getRequestURI().getRawQuery()),
                        "headers", headerObject(exchange.getRequestHeaders()),
                        "body", requestBody(exchange.getRequestHeaders().getFirst("Content-Type"),
                                exchange.getRequestBody().readNBytes(HTTP_BODY_LIMIT + 1))
                ));
                final WorkflowRuntime.SourceResult source = application.runSource(
                        "application.http",
                        Map.of("request", request)
                );
                send(exchange, source);
            } catch (final IllegalArgumentException exception) {
                send(exchange, requestErrorStatus(exception), RailixValue.object(Map.of(
                        "error", RailixValue.object(Map.of(
                                "code", RailixValue.string(requestErrorCode(exception)),
                                "message", RailixValue.string(requestErrorMessage(exception))
                        ))
                )), RailixValue.object(Map.of()));
            } catch (final RuntimeException exception) {
                send(exchange, 500, RailixValue.object(Map.of(
                        "error", RailixValue.object(Map.of(
                                "code", RailixValue.string("HTTP_TRIGGER_FAILED"),
                                "message", RailixValue.string("HTTP request failed.")
                        ))
                )), RailixValue.object(Map.of()));
            } finally {
                exchange.close();
            }
        }

        private static void send(final HttpExchange exchange, final WorkflowRuntime.SourceResult source)
                throws IOException {
            switch (source.result()) {
                case RunResult.Succeeded ignored -> send(exchange,
                        status(source.responses().get("status")),
                        source.responses().getOrDefault("body", RailixValue.nullValue()),
                        source.responses().getOrDefault("headers", RailixValue.object(Map.of())));
                case RunResult.Rejected rejected -> send(exchange, 400, RailixValue.object(Map.of(
                        "diagnostics", diagnostics(rejected.diagnostics())
                )), RailixValue.object(Map.of()));
                case RunResult.Failed failed -> send(exchange, 500, RailixValue.object(Map.of(
                        "error", RailixValue.object(Map.of(
                                "code", RailixValue.string(failed.failure().code()),
                                "message", RailixValue.string(failed.failure().message()),
                                "step", RailixValue.string(failed.failure().stepId())
                        ))
                )), RailixValue.object(Map.of()));
                case RunResult.Cancelled ignored -> send(exchange, 409, RailixValue.object(Map.of(
                        "error", RailixValue.object(Map.of(
                                "code", RailixValue.string("RUN_CANCELLED"),
                                "message", RailixValue.string("HTTP request was cancelled.")
                        ))
                )), RailixValue.object(Map.of()));
            }
        }

        private static void send(
                final HttpExchange exchange,
                final int status,
                final RailixValue body,
                final RailixValue headers
        ) throws IOException {
            final Map<String, List<String>> headerValues = headerValues(headers);
            headerValues.forEach(exchange.getResponseHeaders()::put);
            final byte[] bytes = body instanceof RailixValue.NullValue
                    ? new byte[0]
                    : RailixJson.write(body).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0 && !hasHeader(headerValues, "Content-Type")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private static int status(final RailixValue value) {
            if (value instanceof RailixValue.NumberValue number) {
                final int status = number.value().intValue();
                if (BigDecimal.valueOf(status).compareTo(number.value()) == 0 && status >= 100 && status <= 599) {
                    return status;
                }
            }
            return 500;
        }

        private static RailixValue.ArrayValue diagnostics(final List<Diagnostic> diagnostics) {
            return RailixValue.array(diagnostics.stream()
                    .<RailixValue>map(diagnostic -> RailixValue.object(Map.of(
                            "code", RailixValue.string(diagnostic.code()),
                            "message", RailixValue.string(diagnostic.message()),
                            "path", RailixValue.string(diagnostic.path())
                    )))
                    .toList());
        }
    }

    /** Issues one outbound HTTP request and returns status, headers, and body. */
    public static final class Client implements StepHandler {
        @Override
        public StepResult run(final StepInput input) throws InterruptedException {
            try {
                final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                final RailixValue body = input.optionalValue("body").orElse(RailixValue.nullValue());
                final String method = input.string("method").toUpperCase(java.util.Locale.ROOT);
                final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(input.string("url")))
                        .timeout(HTTP_TIMEOUT);
                final Map<String, List<String>> headers = headerValues(input.value("headers"));
                headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                if (body instanceof RailixValue.NullValue) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    if (!hasHeader(headers, "Content-Type")) {
                        builder.header("Content-Type", "application/json");
                    }
                    builder.method(method, HttpRequest.BodyPublishers.ofString(
                            RailixJson.write(body),
                            StandardCharsets.UTF_8
                    ));
                }
                final HttpResponse<String> response = client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                return StepResult.outcome(input.primaryOutcome()).output("response", RailixValue.object(Map.of(
                        "status", RailixValue.number(response.statusCode()),
                        "headers", headerObject(response.headers().map()),
                        "body", parseBody(response.body())
                )));
            } catch (final IOException exception) {
                return StepResult.outcome(input.primaryOutcome()).output("response", RailixValue.object(Map.of(
                        "status", RailixValue.number(0),
                        "headers", RailixValue.object(Map.of()),
                        "body", RailixValue.string(exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage())
                )));
            }
        }
    }

    private static RailixValue requestBody(final String contentType, final byte[] bytes) {
        if (bytes.length > HTTP_BODY_LIMIT) {
            throw new IllegalArgumentException(
                    "HTTP_BODY_TOO_LARGE: HTTP body exceeds the " + HTTP_BODY_LIMIT + "-byte limit."
            );
        }
        if (bytes.length == 0) {
            return RailixValue.nullValue();
        }
        final String text = utf8(bytes);
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
            return jsonBody(text);
        }
        return RailixValue.string(text);
    }

    private static String utf8(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (final CharacterCodingException exception) {
            throw new IllegalArgumentException("HTTP_BODY_UTF8_INVALID: HTTP body is not valid UTF-8.");
        }
    }

    private static RailixValue jsonBody(final String body) {
        final RailixJson.Result parsed = RailixJson.parse(body);
        if (parsed instanceof RailixJson.Parsed valid) {
            return valid.value();
        }
        final RailixJson.Invalid invalid = (RailixJson.Invalid) parsed;
        throw new IllegalArgumentException(
                "HTTP_JSON_INVALID: HTTP JSON body is invalid at " + invalid.line() + ":" + invalid.column()
                        + ": " + invalid.message()
        );
    }

    private static RailixValue parseBody(final String body) {
        if (body == null || body.isEmpty()) {
            return RailixValue.nullValue();
        }
        final RailixJson.Result parsed = RailixJson.parse(body);
        return parsed instanceof RailixJson.Parsed valid ? valid.value() : RailixValue.string(body);
    }

    private static RailixValue.ObjectValue headerObject(final Map<String, List<String>> headers) {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        headers.forEach((name, items) -> values.put(
                name.toLowerCase(java.util.Locale.ROOT),
                RailixValue.array(items.stream().<RailixValue>map(RailixValue::string).toList())
        ));
        return RailixValue.object(values);
    }

    private static Map<String, List<String>> headerValues(final RailixValue value) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        if (value instanceof RailixValue.ObjectValue object) {
            object.values().forEach((name, item) -> {
                if (item instanceof RailixValue.StringValue string) {
                    headers.put(name, List.of(string.value()));
                } else if (item instanceof RailixValue.ArrayValue array) {
                    final List<String> values = new ArrayList<>();
                    for (final RailixValue header : array.values()) {
                        if (header instanceof RailixValue.StringValue string) {
                            values.add(string.value());
                        }
                    }
                    headers.put(name, List.copyOf(values));
                }
            });
        }
        return headers;
    }

    private static boolean hasHeader(final Map<String, List<String>> headers, final String expected) {
        return headers.keySet().stream().anyMatch(expected::equalsIgnoreCase);
    }

    private static int requestErrorStatus(final IllegalArgumentException exception) {
        return requestErrorCode(exception).equals("HTTP_BODY_TOO_LARGE") ? 413 : 400;
    }

    private static String requestErrorCode(final IllegalArgumentException exception) {
        final String message = exception.getMessage();
        final int separator = message == null ? -1 : message.indexOf(':');
        return separator < 0 ? "HTTP_REQUEST_INVALID" : message.substring(0, separator);
    }

    private static String requestErrorMessage(final IllegalArgumentException exception) {
        final String message = exception.getMessage();
        final int separator = message == null ? -1 : message.indexOf(':');
        return separator < 0 ? "HTTP request is invalid." : message.substring(separator + 1).strip();
    }

}
