package dev.nanonative.railix.development;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.RuntimeApplication;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local authenticated run boundary hosted by one development application JVM. */
public final class DevelopmentRuntime {
    private static final int MAX_CONTEXT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int CONTROL_FRAME_LIMIT = 128;
    private static final int CALLBACK_CONNECT_MILLIS = 2_000;
    private static final int MAX_CONCURRENT_REQUESTS = 32;
    private static final int DRAIN_SECONDS = 10;
    private static final int FORCE_SECONDS = 1;
    private static final int PROCESS_WAIT_SECONDS = 1;

    private DevelopmentRuntime() {
    }

    /** Development-only execution surface implemented by a generated observable application. */
    public interface Application extends RuntimeApplication {
        /**
         * Executes one complete Trigger flow exactly once.
         *
         * @param triggerId compiled Trigger node identifier
         * @param context workflow context supplied at ingress
         * @param test value exposed to the flow as {@code context.runtime.test}
         * @return complete flow result
         */
        RunResult run(String triggerId, RailixValue.ObjectValue context, boolean test);

        /**
         * Executes one complete Trigger flow exactly once and captures one selected Step boundary.
         *
         * @param triggerId compiled Trigger node identifier
         * @param stepId selected compiled Step node identifier
         * @param context workflow context supplied at ingress
         * @param test value exposed to the flow as {@code context.runtime.test}
         * @return complete result and bounded selected-Step observation
         */
        Observation observe(
                String triggerId,
                String stepId,
                RailixValue.ObjectValue context,
                boolean test
        );
    }

    /** Immutable result of one full-flow development observation. */
    public record Observation(
            RunResult result,
            String step,
            RailixValue.ObjectValue inputContext,
            Map<String, RailixValue> inputs,
            List<Stage> stages,
            Map<String, Integer> selectedCandidates
    ) {
        public Observation {
            if (result == null || step == null || inputContext == null
                    || inputs == null || stages == null || selectedCandidates == null) {
                throw new IllegalArgumentException("Development observation values cannot be Java null.");
            }
            inputs = immutableMap(inputs, "Development observation input");
            stages = immutableList(stages, "Development observation stage");
            selectedCandidates = immutableMap(
                    selectedCandidates,
                    "Development observation selected candidate"
            );
        }
    }

    /** Immutable output or explicit terminal state of one nested Step invocation. */
    public record Stage(
            String input,
            String invocation,
            String use,
            String status,
            List<RailixValue> values
    ) {
        public Stage {
            if (input == null || invocation == null || use == null || status == null || values == null) {
                throw new IllegalArgumentException("Development observation stage values cannot be Java null.");
            }
            values = immutableList(values, "Development observation stage output");
        }
    }

    /**
     * Serves authenticated Creator development endpoints for one already compiled application.
     * The Creator writes one bounded startup frame to stdin and keeps the pipe open for process ownership.
     *
     * @param application immutable generated application
     * @return process exit status
     * @throws IllegalArgumentException when application is Java {@code null}
     */
    public static int run(final Application application) {
        if (application == null) {
            throw new IllegalArgumentException("Development application cannot be Java null.");
        }
        final Startup startup;
        try {
            startup = startup();
        } catch (final IOException exception) {
            System.err.println("Cannot read Creator startup frame: " + exception.getMessage());
            return 2;
        }
        RuntimeServer runtime = null;
        Thread shutdownHook = null;
        int status = 0;
        try {
            runtime = RuntimeServer.start(application, startup.token());
            final RuntimeServer started = runtime;
            shutdownHook = Thread.ofPlatform().unstarted(() -> {
                started.close();
                terminateDescendants();
            });
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            ready(startup, runtime.port());
            owner(started);
        } catch (final IOException exception) {
            System.err.println("Cannot start development application: " + exception.getMessage());
            status = 3;
        } finally {
            final boolean interrupted = Thread.currentThread().isInterrupted();
            if (runtime != null) {
                runtime.close();
            }
            removeShutdownHook(shutdownHook);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!terminateDescendants()) {
            System.err.println("Development application descendants did not terminate.");
            return 4;
        }
        return status;
    }

    private static Startup startup() throws IOException {
        final String frame = frame();
        if (frame.isBlank()) {
            throw new IOException("Creator startup frame is required.");
        }
        final String[] parts = frame.split(" ", -1);
        if (parts.length != 3 || !"START".equals(parts[0]) || parts[1].isBlank()) {
            throw new IOException("Creator startup frame is invalid.");
        }
        final String port = parts[2];
        if (port.isEmpty() || !port.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IOException("Creator startup callback port is invalid.");
        }
        try {
            final int value = Integer.parseInt(port);
            if (value < 1 || value > 65_535) {
                throw new IOException("Creator startup callback port is invalid.");
            }
            return new Startup(parts[1], value);
        } catch (final NumberFormatException invalidPort) {
            throw new IOException("Creator startup callback port is invalid.", invalidPort);
        }
    }

    private static String frame() throws IOException {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(CONTROL_FRAME_LIMIT);
        for (int index = 0; ; index++) {
            final int next = System.in.read();
            if (next == '\n') {
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(frame.toByteArray()))
                            .toString();
                } catch (final CharacterCodingException invalidUtf8) {
                    throw new IOException("Creator startup frame is not valid UTF-8.", invalidUtf8);
                }
            }
            if (next < 0) {
                throw new IOException("Creator closed stdin before sending a complete startup frame.");
            }
            if (index == CONTROL_FRAME_LIMIT) {
                throw new IOException("Creator startup frame exceeds 128 bytes.");
            }
            frame.write(next);
        }
    }

    private static void ready(final Startup startup, final int runtimePort) throws IOException {
        try (Socket callback = new Socket()) {
            callback.connect(
                    new InetSocketAddress("127.0.0.1", startup.callbackPort()),
                    CALLBACK_CONNECT_MILLIS
            );
            callback.getOutputStream().write(
                    ("READY " + startup.token() + " " + runtimePort + "\n").getBytes(StandardCharsets.UTF_8)
            );
            callback.getOutputStream().flush();
        }
    }

    private static void owner(final RuntimeServer runtime) {
        try {
            while (System.in.read() >= 0) {
                // The Creator keeps stdin open; no further protocol bytes are currently defined.
            }
        } catch (final IOException ignored) {
            // A broken ownership pipe has the same meaning as EOF.
        } finally {
            runtime.close();
        }
    }

    private record Startup(String token, int callbackPort) {
    }

    private static void removeShutdownHook(final Thread shutdownHook) {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (final IllegalStateException ignored) {
            // JVM shutdown already owns the hook.
        }
    }

    private static synchronized boolean terminateDescendants() {
        boolean interrupted = Thread.interrupted();
        final Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                ProcessHandle.current().descendants().forEach(process -> owned.putIfAbsent(process.pid(), process));
                final List<ProcessHandle> alive = owned.values().stream()
                        .filter(ProcessHandle::isAlive)
                        .toList()
                        .reversed();
                if (alive.isEmpty()) {
                    return true;
                }
                for (final ProcessHandle process : alive) {
                    if (attempt == 0) {
                        process.destroy();
                    } else {
                        process.destroyForcibly();
                    }
                }
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_WAIT_SECONDS);
                while (owned.values().stream().anyMatch(ProcessHandle::isAlive)
                        && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
            ProcessHandle.current().descendants().forEach(process -> owned.putIfAbsent(process.pid(), process));
            return owned.values().stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class RuntimeServer {
        private final Application application;
        private final byte[] authorization;
        private final HttpServer server;
        private final ExecutorService executor;
        private final Semaphore admission = new Semaphore(MAX_CONCURRENT_REQUESTS);
        private final ThreadLocal<Boolean> overflow = new ThreadLocal<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean open = new AtomicBoolean(true);

        private RuntimeServer(
                final Application application,
                final String token,
                final HttpServer server,
                final ExecutorService executor
        ) {
            this.application = application;
            authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            this.server = server;
            this.executor = executor;
        }

        private static RuntimeServer start(
                final Application application,
                final String token
        ) throws IOException {
            HttpServer server = null;
            ExecutorService executor = null;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                executor = Executors.newVirtualThreadPerTaskExecutor();
                final RuntimeServer runtime = new RuntimeServer(application, token, server, executor);
                server.createContext("/v1/run/", runtime::run);
                server.createContext("/v1/preview/", runtime::preview);
                server.setExecutor(runtime::dispatch);
                server.start();
                return runtime;
            } catch (final IOException | RuntimeException | Error exception) {
                try {
                    release(server, executor);
                } catch (final RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void run(final HttpExchange exchange) throws IOException {
            try (exchange) {
                execute(exchange, "/v1/run/", false);
            }
        }

        private void preview(final HttpExchange exchange) throws IOException {
            try (exchange) {
                execute(exchange, "/v1/preview/", true);
            }
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
            if (Boolean.TRUE.equals(overflow.get()) || !open.get()) {
                send(exchange, 503, RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                )));
                return;
            }
            executeAdmitted(exchange, target, preview);
        }

        private void executeAdmitted(
                final HttpExchange exchange,
                final String[] target,
                final boolean preview
        ) throws IOException {
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
            final boolean test = Boolean.parseBoolean(testHeader);
            if (preview) {
                final Observation result = application.observe(target[0], target[1], context, test);
                consumeCancellation(result.result());
                send(exchange, result);
            } else {
                final RunResult result = application.run(target[0], context, test);
                consumeCancellation(result);
                send(exchange, result);
            }
        }

        private void dispatch(final Runnable request) {
            if (!open.get() || !admission.tryAcquire()) {
                reject(request);
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        request.run();
                    } finally {
                        admission.release();
                    }
                });
            } catch (final RejectedExecutionException rejected) {
                admission.release();
                reject(request);
            }
        }

        private void reject(final Runnable request) {
            overflow.set(Boolean.TRUE);
            try {
                request.run();
            } finally {
                overflow.remove();
            }
        }

        private boolean authorized(final HttpExchange exchange) {
            final List<String> values = exchange.getRequestHeaders().getOrDefault("Authorization", List.of());
            return values.size() == 1 && MessageDigest.isEqual(
                    authorization,
                    values.getFirst().getBytes(StandardCharsets.UTF_8)
            );
        }

        private RuntimeServer close() {
            boolean interrupted = Thread.interrupted();
            try {
                if (open.compareAndSet(true, false)) {
                    try {
                        interrupted |= release(server, executor, DRAIN_SECONDS);
                    } finally {
                        Arrays.fill(authorization, (byte) 0);
                        closed.countDown();
                    }
                } else {
                    awaitClosed();
                }
                return this;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void awaitClosed() {
            boolean interrupted = false;
            while (closed.getCount() != 0) {
                try {
                    closed.await();
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static void release(
                final HttpServer server,
                final ExecutorService executor
        ) {
            release(server, executor, 0);
        }

        private static boolean release(
                final HttpServer server,
                final ExecutorService executor,
                final int drainSeconds
        ) {
            boolean interrupted = false;
            try {
                if (server != null) {
                    server.stop(drainSeconds);
                }
            } finally {
                if (executor != null) {
                    executor.shutdown();
                    interrupted |= await(executor, 100, TimeUnit.MILLISECONDS);
                    if (!executor.isTerminated()) {
                        executor.shutdownNow();
                        interrupted |= await(executor, FORCE_SECONDS, TimeUnit.SECONDS);
                    }
                }
            }
            return interrupted;
        }

        private static boolean await(
                final ExecutorService executor,
                final long timeout,
                final TimeUnit unit
        ) {
            final long deadline = System.nanoTime() + unit.toNanos(timeout);
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
            return interrupted;
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
            final Observation observation
    ) throws IOException {
        final ResultResponse response = response(observation.result());
        final Map<String, RailixValue> body = new LinkedHashMap<>(response.value().values());
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("step", RailixValue.string(observation.step()));
        value.put("input_context", observation.inputContext());
        value.put("inputs", RailixValue.object(observation.inputs()));
        value.put("stages", RailixValue.array(observation.stages().stream()
                .<RailixValue>map(DevelopmentRuntime::stage)
                .toList()));
        value.put("selected_candidates", RailixValue.object(observation.selectedCandidates().entrySet().stream()
                .collect(LinkedHashMap::new,
                        (values, entry) -> values.put(entry.getKey(), RailixValue.number(entry.getValue())),
                        LinkedHashMap::putAll)));
        body.put("preview", RailixValue.object(value));
        final Optional<String> json;
        try {
            json = RailixJson.write(RailixValue.object(body), MAX_CONTEXT_BYTES);
        } catch (final IllegalArgumentException exception) {
            sendInvalidResponse(exchange);
            return;
        }
        if (json.isEmpty()) {
            send(exchange, 413, problem(
                    "PREVIEW_RESPONSE_TOO_LARGE",
                    "Preview response exceeds the 1048576-byte limit.",
                    ""
            ));
            return;
        }
        send(exchange, response.status(), json.orElseThrow().getBytes(StandardCharsets.UTF_8));
    }

    private static RailixValue stage(final Stage stage) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("input", RailixValue.string(stage.input()));
        value.put("invocation", RailixValue.string(stage.invocation()));
        value.put("use", RailixValue.string(stage.use()));
        value.put("status", RailixValue.string(stage.status()));
        if (!stage.values().isEmpty()) {
            value.put("value", stage.values().getFirst());
        }
        return RailixValue.object(value);
    }

    private static <T> List<T> immutableList(final List<T> source, final String name) {
        final List<T> copy = new ArrayList<>(source.size());
        for (final T value : source) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be Java null.");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> Map<String, T> immutableMap(final Map<String, T> source, final String name) {
        final Map<String, T> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, T> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(name + " cannot contain Java null.");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
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
        final Optional<String> json;
        try {
            json = RailixJson.write(value, MAX_CONTEXT_BYTES);
        } catch (final IllegalArgumentException exception) {
            sendInvalidResponse(exchange);
            return;
        }
        if (json.isPresent()) {
            send(exchange, status, json.orElseThrow().getBytes(StandardCharsets.UTF_8));
            return;
        }
        final String problem = RailixJson.write(problem(
                "RUN_RESPONSE_TOO_LARGE",
                "Application response exceeds the 1048576-byte limit.",
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
