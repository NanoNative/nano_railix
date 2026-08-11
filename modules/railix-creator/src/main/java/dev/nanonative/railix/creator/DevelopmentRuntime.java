package dev.nanonative.railix.creator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local authenticated run boundary hosted by one development application JVM. */
final class DevelopmentRuntime {
    private static final int MAX_CONTEXT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;

    private DevelopmentRuntime() {
    }

    static int run(final Path projectFile, final String token) {
        final String source;
        try {
            if (Files.size(projectFile) > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                System.err.println("Project exceeds the 1048576-byte development limit.");
                return 2;
            }
            source = Files.readString(projectFile, StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            System.err.println("Cannot read development project: " + exception.getMessage());
            return 2;
        }
        final CompileResult result = ProjectCompiler.compile(source, StandardLibrary.catalog());
        if (result instanceof CompileResult.Rejected rejected) {
            rejected.diagnostics().forEach(diagnostic ->
                    System.err.println(diagnostic.code() + " " + diagnostic.path() + " " + diagnostic.message())
            );
            return 2;
        }
        final CompileResult.Compiled compiled = (CompileResult.Compiled) result;
        try {
            final RuntimeServer runtime = RuntimeServer.start(compiled, token);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(runtime::close));
            System.out.println("RAILIX_READY " + runtime.port());
            System.out.flush();
            runtime.await();
            return 0;
        } catch (final IOException exception) {
            System.err.println("Cannot start development application: " + exception.getMessage());
            return 3;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    static final class RuntimeServer {
        private final CompileResult.Compiled compiled;
        private final String token;
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean open = new AtomicBoolean(true);

        private RuntimeServer(
                final CompileResult.Compiled compiled,
                final String token,
                final HttpServer server,
                final java.util.concurrent.ExecutorService executor
        ) {
            this.compiled = compiled;
            this.token = token;
            this.server = server;
            this.executor = executor;
        }

        static RuntimeServer start(
                final CompileResult.Compiled compiled,
                final String token
        ) throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final var executor = Executors.newVirtualThreadPerTaskExecutor();
            final RuntimeServer runtime = new RuntimeServer(compiled, token, server, executor);
            server.createContext("/v1/run/", runtime::run);
            server.createContext("/v1/preview/", runtime::preview);
            server.setExecutor(executor);
            server.start();
            return runtime;
        }

        int port() {
            return server.getAddress().getPort();
        }

        private void await() throws InterruptedException {
            closed.await();
        }

        private void run(final HttpExchange exchange) throws IOException {
            execute(exchange, "/v1/run/", false);
        }

        private void preview(final HttpExchange exchange) throws IOException {
            execute(exchange, "/v1/preview/", true);
        }

        private void execute(
                final HttpExchange exchange,
                final String prefix,
                final boolean preview
        ) throws IOException {
            if (!authorized(exchange)) {
                send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
                return;
            }
            final String[] target = exchange.getRequestURI().getPath().substring(prefix.length()).split("/", -1);
            if (target[0].isBlank()
                    || target.length != (preview ? 2 : 1)
                    || (preview && target[1].isBlank())) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            final byte[] body = exchange.getRequestBody().readNBytes(MAX_CONTEXT_BYTES + 1);
            if (body.length > MAX_CONTEXT_BYTES) {
                send(exchange, 413, problem(
                        "CONTEXT_TOO_LARGE",
                        "Workflow context exceeds the 1048576-byte limit.",
                        ""
                ));
                return;
            }
            final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, body);
            if (!(normalized instanceof RailixData.Normalized data)
                    || !(data.value() instanceof RailixValue.ObjectValue context)) {
                final RailixValue value = normalized instanceof RailixData.Invalid invalid
                        ? problem(invalid.code(), invalid.message(), "")
                        : problem("CONTEXT_OBJECT_REQUIRED", "Workflow context must be an object.", "");
                send(exchange, 422, value);
                return;
            }
            final String testHeader = exchange.getRequestHeaders().getFirst("X-Railix-Test");
            if (testHeader != null
                    && !"true".equalsIgnoreCase(testHeader)
                    && !"false".equalsIgnoreCase(testHeader)) {
                send(exchange, 422, problem(
                        "RUN_TEST_FLAG_INVALID",
                        "X-Railix-Test must be true or false.",
                        "X-Railix-Test"
                ));
                return;
            }
            final CompiledProject.StreamItem item =
                    new CompiledProject.StreamItem(Boolean.parseBoolean(testHeader), context);
            if (preview) {
                final CompiledProject.PreviewResult result = compiled.project().preview(target[0], target[1], item);
                consumeCancellation(result.result());
                send(exchange, result, target[1]);
            } else {
                final RunResult result = compiled.project().run(target[0], item);
                consumeCancellation(result);
                send(exchange, result);
            }
        }

        private boolean authorized(final HttpExchange exchange) {
            return exchange.getRequestHeaders()
                    .getOrDefault("Authorization", List.of())
                    .contains("Bearer " + token);
        }

        void close() {
            if (open.compareAndSet(true, false)) {
                server.stop(0);
                executor.shutdownNow();
                closed.countDown();
            }
        }

        private static void consumeCancellation(final RunResult result) {
            if (result instanceof RunResult.Cancelled) {
                Thread.interrupted();
            }
        }
    }

    private static void send(final HttpExchange exchange, final RunResult result) throws IOException {
        final ResultResponse response = response(result);
        send(exchange, response.status(), response.value());
    }

    private static void send(
            final HttpExchange exchange,
            final CompiledProject.PreviewResult preview,
            final String step
    ) throws IOException {
        final ResultResponse response = response(preview.result());
        final Map<String, RailixValue> body = new LinkedHashMap<>(response.value().values());
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("step", RailixValue.string(step));
        value.put("input_context", preview.inputContext());
        value.put("inputs", RailixValue.object(preview.inputs()));
        value.put("stages", RailixValue.array(preview.stages().stream()
                .<RailixValue>map(DevelopmentRuntime::stage)
                .toList()));
        value.put("selected_candidates", RailixValue.object(preview.selectedCandidates().entrySet().stream()
                .collect(LinkedHashMap::new,
                        (values, entry) -> values.put(entry.getKey(), RailixValue.number(entry.getValue())),
                        LinkedHashMap::putAll)));
        body.put("preview", RailixValue.object(value));
        final byte[] bytes = RailixJson.write(RailixValue.object(body)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTEXT_BYTES) {
            send(exchange, 413, problem(
                    "PREVIEW_RESPONSE_TOO_LARGE",
                    "Preview response exceeds the 1048576-byte limit.",
                    ""
            ));
            return;
        }
        send(exchange, response.status(), bytes);
    }

    private static RailixValue stage(final CompiledProject.NestedStepStage stage) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("input", RailixValue.string(stage.input()));
        value.put("invocation", RailixValue.string(stage.invocation()));
        value.put("use", RailixValue.string(stage.use()));
        value.put("status", RailixValue.string(stage.status()));
        if (!stage.value().isEmpty()) {
            value.put("value", stage.value().getFirst());
        }
        return RailixValue.object(value);
    }

    private static ResultResponse response(final RunResult result) {
        return switch (result) {
            case RunResult.Succeeded succeeded -> new ResultResponse(200, RailixValue.object(Map.of(
                    "status", RailixValue.string("succeeded"),
                    "context", succeeded.context(),
                    "steps", steps(succeeded.steps())
            )));
            case RunResult.Rejected rejected -> new ResultResponse(422, RailixValue.object(Map.of(
                    "status", RailixValue.string("rejected"),
                    "diagnostics", diagnosticValues(rejected.diagnostics()),
                    "steps", steps(rejected.steps())
            )));
            case RunResult.Failed failed -> new ResultResponse(500, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "failure", RailixValue.object(Map.of(
                        "code", RailixValue.string(failed.failure().code()),
                        "message", RailixValue.string(failed.failure().message()),
                        "step", RailixValue.string(failed.failure().stepId())
                    )),
                    "steps", steps(failed.steps())
            )));
            case RunResult.Cancelled cancelled -> new ResultResponse(409, RailixValue.object(Map.of(
                    "status", RailixValue.string("cancelled"),
                    "steps", steps(cancelled.steps())
            )));
        };
    }

    private static RailixValue.ArrayValue steps(final List<RunResult.StepExecution> steps) {
        return RailixValue.array(steps.stream().<RailixValue>map(step -> RailixValue.object(Map.of(
                "id", RailixValue.string(step.stepId()),
                "outcome", RailixValue.string(step.outcome())
        ))).toList());
    }

    private static RailixValue diagnostics(final List<Diagnostic> diagnostics) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("rejected"),
                "diagnostics", diagnosticValues(diagnostics)
        ));
    }

    private static RailixValue.ArrayValue diagnosticValues(final List<Diagnostic> diagnostics) {
        return RailixValue.array(diagnostics.stream().<RailixValue>map(diagnostic -> RailixValue.object(Map.of(
                "code", RailixValue.string(diagnostic.code()),
                "message", RailixValue.string(diagnostic.message()),
                "path", RailixValue.string(diagnostic.path())
        ))).toList());
    }

    private static RailixValue problem(final String code, final String message, final String path) {
        return diagnostics(List.of(Diagnostic.atPath(code, message, path)));
    }

    private static void send(
            final HttpExchange exchange,
            final int status,
            final RailixValue value
    ) throws IOException {
        send(exchange, status, RailixJson.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(
            final HttpExchange exchange,
            final int status,
            final byte[] body
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }

    private record ResultResponse(int status, RailixValue.ObjectValue value) {
    }
}
