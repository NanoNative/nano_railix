package dev.nanonative.railix.development;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.ObservedApplication;
import dev.nanonative.railix.core.project.RuntimeApplication;
import dev.nanonative.railix.core.runtime.RuntimeMetrics;
import dev.nanonative.railix.core.runtime.MetricsEndpoint;
import static dev.nanonative.railix.core.runtime.RuntimeMetrics.*;
import static dev.nanonative.railix.core.runtime.ManagementHttp.*;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.project.WorkflowRuntime.StepCall;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Optional Example execution and value-recording support embedded only when that capability is included. */
public final class DevelopmentRuntime {
    private static final int MAX_CONTEXT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_TRACE_EVENT_BYTES = MAX_CONTEXT_BYTES * 4;
    private static final int MAX_EXAMPLE_SUMMARY_BYTES = MAX_CONTEXT_BYTES * 16;
    private static final long MAX_EXAMPLE_STEP_REPLAY_BYTES = 64L * 1_024 * 1_024;
    private static final int CONTROL_FRAME_LIMIT = 128;
    private static final int CALLBACK_CONNECT_MILLIS = 2_000;
    private static final int MAX_CONCURRENT_RUNS = 32;
    private static final int MAX_CONCURRENT_TRACES = 16;
    private static final int MAX_CONCURRENT_REQUEST_BODIES = MAX_CONCURRENT_RUNS + MAX_CONCURRENT_TRACES;
    private static final int MAX_CONCURRENT_UNAUTHORIZED_BODIES = 4;
    private static final int MAX_CONCURRENT_EXAMPLE_READS = 2;
    private static final int MAX_CONCURRENT_EXAMPLE_SNAPSHOTS = 4;
    private static final int MAX_CONCURRENT_CANCELLATIONS = MAX_CONCURRENT_TRACES;
    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final int CANCELLATION_WAIT_MILLIS = 2_000;
    private static final int PROJECTION_SNAPSHOT_WAIT_MILLIS = 1_000;
    private static final String TRACE_ID_HEADER = "X-Railix-Trace-Id";
    private static final int DRAIN_SECONDS = 10;
    private static final int FORCE_SECONDS = 1;
    private static final int PROCESS_WAIT_SECONDS = 1;

    private DevelopmentRuntime() {
    }

    /** Optional Example execution surface, independent of the shared operational metric implementation. */
    public interface Application extends ObservedApplication {
        /** Returns the same application-owned metric store used by ordinary ingress. */
        RuntimeMetrics metrics();

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
         * Executes one complete Trigger flow exactly once and streams every reached Step boundary.
         *
         * @param triggerId compiled Trigger node identifier
         * @param context workflow context supplied at ingress
         * @param test value exposed to the flow as {@code context.runtime.test}
         * @param sink development-only trace destination
         * @return complete flow result
         */
        RunResult trace(
                String triggerId,
                RailixValue.ObjectValue context,
                boolean test,
                TraceSink sink
        );
    }

    /** Streaming development trace destination. Implementations own persistence and backpressure. */
    @FunctionalInterface
    public interface TraceSink {
        /** Writes one complete immutable trace event and returns whether tracing may continue. */
        boolean write(RailixValue.ObjectValue event) throws IOException;
    }

    /** Development-only one-pass trace recorder used by generated development applications. */
    public static final class Trace {
        static final int MAX_CONTEXT_CHANGES = 4_096;
        private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
        private final TraceSink sink;
        private final Trace root;
        private final long path;
        private final Deque<Pending> calls = new ArrayDeque<>();
        private final Map<String, Integer> occurrences = new HashMap<>();
        private final AtomicBoolean recording;
        private RailixValue.ObjectValue lastContext;
        private long sequence;
        private long nextPath;

        private Trace(final TraceSink sink) {
            this.sink = sink;
            recording = new AtomicBoolean(true);
            root = this;
            path = 0;
        }

        private Trace(final Trace parent) {
            sink = parent.sink;
            recording = parent.recording;
            root = parent.root;
            synchronized (root) { path = ++root.nextPath; }
        }

        /**
         * Captures the active trace for lifecycle-owned outgoing work, with a separate call stack
         * and context timeline per path. The returned function restores thread-local state.
         *
         * @param action non-null compiled continuation
         * @param context non-null initial-context reader
         * @return continuation with request-local trace propagation, or the original action
         */
        public static <T> java.util.function.BiFunction<T, Integer, RunResult> continuation(
                final java.util.function.BiFunction<T, Integer, RunResult> action,
                final java.util.function.Function<T, RailixValue.ObjectValue> context) {
            java.util.Objects.requireNonNull(action);
            java.util.Objects.requireNonNull(context);
            final Trace parent = CURRENT.get();
            if (parent == null) return action;
            return (execution, outcome) -> {
                final Trace previous = CURRENT.get();
                final Trace branch = new Trace(parent);
                CURRENT.set(branch);
                try {
                    branch.lastContext = context.apply(execution);
                    branch.emit(object("type", RailixValue.string("path_start"),
                            "parent_path", RailixValue.number(parent.path), "context", branch.lastContext));
                    final RunResult result = action.apply(execution, outcome);
                    if (branch.root.recording.get()) {
                        final RailixValue.ObjectValue resultContext = switch (result) {
                            case RunResult.Succeeded succeeded -> succeeded.context();
                            case RunResult.Completed completed -> completed.context();
                            default -> null;
                        };
                        if (resultContext == null && result instanceof RunResult.Succeeded
                                || resultContext != null && ValueRefinement.canonical().rejection(resultContext).isPresent()) {
                            branch.emit(traceError("RUN_RESPONSE_INVALID", "Application response is not valid canonical JSON."));
                            branch.discard();
                        } else {
                            final Map<String, RailixValue> event = new LinkedHashMap<>(terminal(result).values());
                            event.put("type", RailixValue.string("path_result"));
                            branch.emit(RailixValue.object(event));
                        }
                    }
                    branch.complete();
                    return result;
                } finally {
                    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                }
            };
        }

        /** Runs one flow with a request-scoped trace and always removes thread-local state. */
        public static RunResult start(
                final RailixValue.ObjectValue initialContext,
                final TraceSink sink,
                final Supplier<RunResult> execution
        ) {
            if (initialContext == null || sink == null || execution == null) {
                throw new IllegalArgumentException("Trace inputs cannot be Java null.");
            }
            if (CURRENT.get() != null) {
                throw new IllegalStateException("A trace is already active on this execution thread.");
            }
            final Trace trace = new Trace(sink);
            CURRENT.set(trace);
            try {
                trace.emit(object(
                        "type", RailixValue.string("trace"),
                        "context", initialContext
                ));
                final RunResult result = execution.get();
                if (trace.root.recording.get()) {
                    if (result.contextRejection().isPresent()) {
                        trace.emit(traceError("RUN_RESPONSE_INVALID", "Application response is not valid canonical JSON."));
                        trace.discard();
                    } else trace.emit(terminal(result));
                }
                trace.complete();
                return result;
            } finally {
                CURRENT.remove();
            }
        }

        /** Opens one graph-Step record immediately before its inputs and handler are evaluated. */
        public static boolean before(
                final int node,
                final String invocation,
                final String use,
                final RailixValue.ObjectValue context
        ) {
            final Trace trace = CURRENT.get();
            if (trace != null && trace.root.recording.get()) {
                trace.open(node, invocation, use, context);
            }
            return trace != null && trace.root.recording.get();
        }

        /** Completes one successful graph-Step record after context mutations are committed. */
        public static boolean after(
                final String invocation,
                final String outcome,
                final RailixValue.ObjectValue context
        ) {
            final Trace trace = CURRENT.get();
            if (trace != null && trace.root.recording.get()) {
                trace.close(invocation, "succeeded", outcome, context);
            }
            return trace != null && trace.root.recording.get();
        }

        /** Completes one rejected, failed, or cancelled graph-Step record. */
        public static boolean after(
                final String invocation,
                final RunResult result,
                final RailixValue.ObjectValue context
        ) {
            final Trace trace = CURRENT.get();
            if (trace != null && trace.root.recording.get()) {
                final Pending call = trace.calls.peek();
                if (call != null) call.terminal = result;
                trace.close(invocation, result instanceof RunResult.Completed ? "succeeded" : status(result), "", context);
            }
            return trace != null && trace.root.recording.get();
        }

        /** Captures one atomic commit's snapshots without writing to the trace transport under the context lock. */
        public static boolean committed(final RailixValue.ObjectValue before, final RailixValue.ObjectValue after) {
            final Trace trace = CURRENT.get();
            final Pending call = trace == null ? null : trace.calls.peek();
            if (call == null || !trace.root.recording.get()) return false;
            call.commitBefore = before;
            call.commitAfter = after;
            return true;
        }

        /** Invokes one graph handler and records its resolved values and explicit result. */
        public static StepResult invoke(
                final StepInput configuration,
                final StepInput input,
                final StepCall handler
        ) throws InterruptedException {
            final Trace trace = CURRENT.get();
            if (trace == null || !trace.root.recording.get()) {
                return handler.process(configuration, input);
            }
            final Pending call = trace.calls.peek();
            if (call == null || call.entered) {
                throw new IllegalStateException("Trace has no pending graph Step invocation.");
            }
            return trace.invoke(call, false, configuration, input, handler);
        }

        /** Invokes one nested handler under its stable parent-relative identity. */
        public static StepResult invoke(
                final String invocation,
                final String use,
                final StepInput configuration,
                final StepInput input,
                final StepCall handler
        ) throws InterruptedException {
            final Trace trace = CURRENT.get();
            if (trace == null || !trace.root.recording.get()) {
                return handler.process(configuration, input);
            }
            final Pending call = trace.open(-1, invocation, use, null);
            return trace.root.recording.get()
                    ? trace.invoke(call, true, configuration, input, handler)
                    : handler.process(configuration, input);
        }

        private StepResult invoke(
                final Pending call,
                final boolean nested,
                final StepInput configuration,
                final StepInput input,
                final StepCall handler
        ) throws InterruptedException {
            call.entered = true;
            call.inputs = input.values();
            call.options = input.options();
            try {
                final StepResult result = handler.process(configuration, input);
                call.result = result;
                if (nested) {
                    close(call.invocation, result == null ? "failed" : "succeeded",
                            result == null ? "" : result.outcome(), null);
                }
                return result;
            } catch (final InterruptedException exception) {
                if (nested) {
                    close(call.invocation, "cancelled", "", null);
                }
                throw exception;
            } catch (final RuntimeException exception) {
                call.exception = exception;
                if (nested) {
                    close(call.invocation, "failed", "", null);
                }
                throw exception;
            }
        }

        private Pending open(
                final int node,
                final String invocation,
                final String use,
                final RailixValue.ObjectValue context
        ) {
            final Pending call;
            synchronized (root) {
                final int occurrence = root.occurrences.merge(invocation, 1, Integer::sum) - 1;
                call = new Pending(root.sequence++, occurrence, invocation, use, context);
            }
            calls.push(call);
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("type", RailixValue.string("step_start"));
            value.put("sequence", RailixValue.number(call.sequence));
            value.put("occurrence", RailixValue.number(call.occurrence));
            value.put("id", RailixValue.string(invocation));
            value.put("use", RailixValue.string(use));
            if (node >= 0) {
                value.put("node", RailixValue.number(node));
                if (path != 0 && lastContext != null) {
                    try {
                        final var changes = changes(lastContext, context);
                        if (!changes.isEmpty()) value.put("entry_changes", RailixValue.array(changes));
                    } catch (final ContextChangeLimit exceeded) {
                        emit(traceError("TRACE_CHANGES_TOO_LARGE", "Development trace context change limit exceeded."));
                        discard();
                    }
                }
                lastContext = context;
            }
            emit(RailixValue.object(value));
            return call;
        }

        private void close(
                final String invocation,
                final String status,
                final String outcome,
                final RailixValue.ObjectValue context
        ) {
            if (context != null && ValueRefinement.canonical().rejection(context).isPresent()) {
                emit(traceError("RUN_RESPONSE_INVALID", "Application response is not valid canonical JSON."));
                discard();
                return;
            }
            final Pending call = calls.poll();
            if (call == null || !call.invocation.equals(invocation)) {
                throw new IllegalStateException("Trace Step nesting is inconsistent: " + invocation + ".");
            }
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("type", RailixValue.string("step_result"));
            value.put("sequence", RailixValue.number(call.sequence));
            value.put("occurrence", RailixValue.number(call.occurrence));
            value.put("id", RailixValue.string(call.invocation));
            value.put("use", RailixValue.string(call.use));
            value.put("status", RailixValue.string(status));
            if (call.terminal instanceof RunResult.Completed completed) {
                value.put("outcomes", RailixValue.array(completed.paths().stream()
                        .filter(path -> path.stepId().equals(invocation)).map(RunResult.PathResult::outcome)
                        .distinct().<RailixValue>map(RailixValue::string).toList()));
            } else if (call.terminal instanceof RunResult.Failed failed) {
                value.put("failure", failureValue(failed));
            } else if (call.terminal instanceof RunResult.Rejected rejected) {
                value.put("diagnostics", diagnosticValues(rejected.diagnostics()));
            }
            if (call.exception != null) {
                final List<RailixValue> causes = new ArrayList<>();
                final Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (Throwable failure = call.exception; failure != null && seen.add(failure) && causes.size() < 8;
                     failure = failure.getCause()) {
                    causes.add(RailixValue.object(Map.of(
                            "type", RailixValue.string(failure.getClass().getName()),
                            "frames", RailixValue.array(java.util.Arrays.stream(failure.getStackTrace()).limit(32)
                                    .<RailixValue>map(frame -> RailixValue.string(frame.toString())).toList()))));
                }
                final Map<String, RailixValue> exception = new LinkedHashMap<>(
                        ((RailixValue.ObjectValue) causes.getFirst()).values());
                exception.put("causes", RailixValue.array(causes.subList(1, causes.size())));
                value.put("exception", RailixValue.object(exception));
            }
            if (!outcome.isEmpty()) {
                value.put("outcome", RailixValue.string(outcome));
            }
            if (!call.inputs.isEmpty()) {
                value.put("inputs", RailixValue.object(call.inputs));
            }
            if (!call.options.isEmpty()) {
                value.put("options", RailixValue.object(call.options.entrySet().stream().collect(
                        LinkedHashMap::new,
                        (options, entry) -> options.put(entry.getKey(), RailixValue.string(entry.getValue())),
                        LinkedHashMap::putAll
                )));
            }
            if (call.result != null) {
                if (!call.result.outputs().isEmpty()) {
                    value.put("returns", RailixValue.object(call.result.outputs()));
                }
                if (!call.result.writes().isEmpty()) {
                    value.put("writes", RailixValue.object(call.result.writes()));
                }
            }
            final List<RailixValue> changes;
            try {
                changes = changes(call.context, context);
                if (path != 0 && call.context != null || call.terminal instanceof RunResult.Completed) {
                    value.put("own_changes", RailixValue.array(call.commitBefore == null ? List.of()
                            : changes(call.commitBefore, call.commitAfter)));
                }
            } catch (final ContextChangeLimit exceeded) {
                calls.clear();
                emit(traceError(
                        "TRACE_CHANGES_TOO_LARGE",
                        "Development trace context change limit exceeded."
                ));
                discard();
                return;
            }
            if (!changes.isEmpty()) {
                value.put("changes", RailixValue.array(changes));
            }
            if (call.context != null) lastContext = context;
            emit(RailixValue.object(value));
        }

        private void emit(final RailixValue.ObjectValue event) {
            synchronized (root) {
                if (!root.recording.get()) return;
                try {
                    RailixValue.ObjectValue emitted = event;
                    if (path != 0) {
                        final var value = new LinkedHashMap<>(event.values());
                        value.put("path", RailixValue.number(path));
                        emitted = RailixValue.object(value);
                    }
                    if (!sink.write(emitted)) discard();
                } catch (final IOException ignored) {
                    discard();
                }
            }
        }

        private void discard() {
            synchronized (root) {
                root.recording.set(false);
                calls.clear();
                root.occurrences.clear();
            }
        }

        private void complete() {
            if (root.recording.get() && !calls.isEmpty()) {
                throw new IllegalStateException("Trace completed with unfinished Step records.");
            }
        }

        private static RailixValue.ObjectValue terminal(final RunResult result) {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("type", RailixValue.string("result"));
            value.put("status", RailixValue.string(status(result)));
            switch (result) {
                case RunResult.Succeeded succeeded -> value.put("context", succeeded.context());
                case RunResult.Completed completed -> {
                    value.put("context", completed.context());
                    value.put("paths", RailixValue.array(completed.paths().stream().<RailixValue>map(path ->
                            object("step", RailixValue.string(path.stepId()), "outcome", RailixValue.string(path.outcome()),
                                    "result", terminal(path.result()))).toList()));
                }
                case RunResult.Rejected rejected -> value.put("diagnostics", diagnosticValues(rejected.diagnostics()));
                case RunResult.Failed failed -> value.put("failure", failureValue(failed));
                case RunResult.Cancelled ignored -> {
                }
            }
            return RailixValue.object(value);
        }

        private static String status(final RunResult result) {
            return switch (result) {
                case RunResult.Succeeded ignored -> "succeeded";
                case RunResult.Completed ignored -> "completed";
                case RunResult.Rejected ignored -> "rejected";
                case RunResult.Failed ignored -> "failed";
                case RunResult.Cancelled ignored -> "cancelled";
            };
        }

        private static RailixValue.ObjectValue object(final Object... entries) {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            for (int index = 0; index < entries.length; index += 2) {
                value.put((String) entries[index], (RailixValue) entries[index + 1]);
            }
            return RailixValue.object(value);
        }

        private static RailixValue.ObjectValue traceError(final String code, final String message) {
            return object(
                    "type", RailixValue.string("trace_error"),
                    "status", RailixValue.string("failed"),
                    "code", RailixValue.string(code),
                    "message", RailixValue.string(message)
            );
        }

        private static List<RailixValue> changes(
                final RailixValue.ObjectValue before,
                final RailixValue.ObjectValue after
        ) {
            if (before == null || after == null || before == after) {
                return List.of();
            }
            final List<RailixValue> changes = new ArrayList<>();
            final List<RailixValue> path = new ArrayList<>();
            path.add(RailixValue.string("context"));
            compare(path, before, after, true, true, changes);
            return List.copyOf(changes);
        }

        private static void compare(
                final List<RailixValue> path,
                final RailixValue before,
                final RailixValue after,
                final boolean beforePresent,
                final boolean afterPresent,
                final List<RailixValue> changes
        ) {
            if (beforePresent && afterPresent && (before == after || before.equals(after))) {
                return;
            }
            if (beforePresent && afterPresent
                    && before instanceof RailixValue.ObjectValue beforeObject
                    && after instanceof RailixValue.ObjectValue afterObject) {
                for (final String key : beforeObject.values().keySet()) {
                    compareChild(path, RailixValue.string(key), beforeObject.values().get(key),
                            afterObject.values().get(key), true, afterObject.values().containsKey(key), changes);
                }
                for (final String key : afterObject.values().keySet()) {
                    if (!beforeObject.values().containsKey(key)) {
                        compareChild(path, RailixValue.string(key), null, afterObject.values().get(key),
                                false, true, changes);
                    }
                }
                return;
            }
            if (beforePresent && afterPresent
                    && before instanceof RailixValue.ArrayValue beforeArray
                    && after instanceof RailixValue.ArrayValue afterArray) {
                final int size = Math.max(beforeArray.values().size(), afterArray.values().size());
                for (int index = 0; index < size; index++) {
                    compareChild(path, RailixValue.number(index),
                            index < beforeArray.values().size() ? beforeArray.values().get(index) : null,
                            index < afterArray.values().size() ? afterArray.values().get(index) : null,
                            index < beforeArray.values().size(), index < afterArray.values().size(), changes);
                }
                return;
            }
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("path", RailixValue.array(List.copyOf(path)));
            if (!beforePresent) {
                value.put("kind", RailixValue.string("added"));
                value.put("after", after);
            } else if (!afterPresent) {
                value.put("kind", RailixValue.string("removed"));
                value.put("before", before);
            } else {
                value.put("kind", RailixValue.string("changed"));
                value.put("before", before);
                value.put("after", after);
            }
            if (changes.size() >= MAX_CONTEXT_CHANGES) {
                throw new ContextChangeLimit();
            }
            changes.add(RailixValue.object(value));
        }

        private static void compareChild(
                final List<RailixValue> path,
                final RailixValue part,
                final RailixValue before,
                final RailixValue after,
                final boolean beforePresent,
                final boolean afterPresent,
                final List<RailixValue> changes
        ) {
            path.add(part);
            compare(path, before, after, beforePresent, afterPresent, changes);
            path.removeLast();
        }

        private static final class Pending {
            private final long sequence;
            private final int occurrence;
            private final String invocation;
            private String use;
            private final RailixValue.ObjectValue context;
            private Map<String, RailixValue> inputs = Map.of();
            private Map<String, String> options = Map.of();
            private StepResult result;
            private RunResult terminal;
            private RuntimeException exception;
            private RailixValue.ObjectValue commitBefore;
            private RailixValue.ObjectValue commitAfter;
            private boolean entered;

            private Pending(
                    final long sequence,
                    final int occurrence,
                    final String invocation,
                    final String use,
                    final RailixValue.ObjectValue context
            ) {
                this.sequence = sequence;
                this.occurrence = occurrence;
                this.invocation = invocation;
                this.use = use;
                this.context = context;
            }
        }

        private static final class ContextChangeLimit extends RuntimeException {
            private ContextChangeLimit() {
                super(null, null, false, false);
            }
        }

    }

    /**
     * Serves authenticated Creator development endpoints for one already compiled application.
     * The Creator writes bounded startup and activation frames to stdin, then keeps the pipe open for ownership.
     *
     * @param application compiled application with its own Step lifecycle
     * @return {@code 0} after normal owner closure, {@code 2} for an invalid control frame,
     * {@code 3} for startup or activation failure, {@code 4} for retained descendants, {@code 5} for shutdown
     * failure, or {@code 6} when an Example worker cannot be reclaimed cooperatively
     * @throws IllegalArgumentException when application is Java {@code null}
     */
    public static int run(final Application application) {
        if (application == null) {
            throw new IllegalArgumentException("Development application cannot be Java null.");
        }
        boolean initialized = false;
        boolean descendantsTerminated = false;
        try {
            initialized = application.start() instanceof RunResult.Succeeded;
        } finally {
            if (!initialized) {
                descendantsTerminated = terminateDescendants();
            }
        }
        if (!initialized) {
            System.err.println("Cannot initialize application Steps.");
            return descendantsTerminated ? 3 : 4;
        }
        final Startup startup;
        try {
            startup = startup();
        } catch (final IOException exception) {
            System.err.println("Cannot read Creator startup frame: " + exception.getMessage());
            try {
                application.stop();
            } finally {
                descendantsTerminated = terminateDescendants();
            }
            if (!descendantsTerminated) {
                System.err.println("Development application descendants did not terminate.");
                return 4;
            }
            return 2;
        }
        RuntimeServer runtime = null;
        ArtifactLease artifactLease = null;
        Path runtimeDirectory = null;
        Thread shutdownHook = null;
        final CountDownLatch stop = new CountDownLatch(1);
        final Object lifecycle = new Object();
        final AtomicBoolean exampleFailure = new AtomicBoolean();
        final AtomicBoolean controlFailure = new AtomicBoolean();
        final AtomicBoolean activationFailure = new AtomicBoolean();
        int status = 0;
        try {
            runtimeDirectory = ExampleSuite.prepareRuntime();
            artifactLease = ArtifactLease.acquire(runtimeDirectory);
            runtime = RuntimeServer.start(application, startup.token(), runtimeDirectory, lifecycle, () -> {
                exampleFailure.set(true);
                stop.countDown();
            });
            final RuntimeServer started = runtime;
            shutdownHook = Thread.ofPlatform().unstarted(() -> {
                try {
                    started.close();
                } catch (final RuntimeException exception) {
                    System.err.println("Cannot close development application: " + exception.getMessage());
                } finally {
                    terminateDescendants();
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            final Socket callback = ready(startup, runtime.port());
            try {
                Thread.ofVirtual().name("railix-development-owner").start(() -> {
                    boolean ownsApplication = false;
                    try (callback) {
                        if (activate(startup)) {
                            final RunResult launched = application.launch(List.of());
                            if (launched instanceof RunResult.Succeeded) {
                                started.activate();
                                synchronized (lifecycle) {
                                    if (stop.getCount() == 0) {
                                        throw new IllegalStateException(
                                                "Development application stopped before activation acknowledgement."
                                        );
                                    }
                                    activated(startup, callback);
                                }
                                ownsApplication = true;
                            } else {
                                activationFailure.set(true);
                                if (launched instanceof RunResult.Failed failed)
                                    System.err.println(failed.failure().code() + " " + failed.failure().stepId()
                                            + " " + failed.failure().message());
                                else System.err.println("Cannot activate application sources.");
                            }
                        }
                    } catch (final IOException exception) {
                        controlFailure.set(true);
                        System.err.println("Cannot read Creator activation frame: " + exception.getMessage());
                    } catch (final RuntimeException | Error failure) {
                        activationFailure.set(true);
                        System.err.println("Cannot activate development application: " + failure.getMessage());
                    }
                    try {
                        if (ownsApplication) {
                            owner();
                        }
                    } finally {
                        synchronized (lifecycle) {
                            stop.countDown();
                        }
                    }
                });
            } catch (final RuntimeException | Error failure) {
                try {
                    callback.close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw new IOException("Cannot start development application ownership.", failure);
            }
            await(stop);
            if (exampleFailure.get()) {
                status = 6;
            } else if (activationFailure.get()) {
                status = 3;
            } else if (controlFailure.get()) {
                status = 2;
            }
        } catch (final IOException exception) {
            System.err.println("Cannot start development application: " + exception.getMessage());
            status = 3;
        } finally {
            final boolean interrupted = Thread.currentThread().isInterrupted();
            boolean runtimeStopped = runtime == null;
            try {
                if (runtime != null) {
                    try {
                        runtime.close();
                        runtimeStopped = true;
                    } catch (final RuntimeException exception) {
                        System.err.println("Cannot close development application: " + exception.getMessage());
                        if (status == 0) {
                            status = 5;
                        }
                    }
                }
            } finally {
                try {
                    removeShutdownHook(shutdownHook);
                } finally {
                    try {
                        if (runtime == null) {
                            final RunResult stopped = status == 0 ? application.stop() : application.stop(Duration.ZERO);
                            if (!(stopped instanceof RunResult.Succeeded) && status == 0) status = 5;
                        }
                    } finally {
                        descendantsTerminated = terminateDescendants();
                        if (descendantsTerminated && artifactLease != null) {
                            try {
                                artifactLease.close();
                                if (runtimeStopped) {
                                    ExampleSuite.deleteRuntime(runtimeDirectory);
                                }
                            } catch (final IOException exception) {
                                System.err.println("Cannot release development artifact lease: " + exception.getMessage());
                                if (status == 0) {
                                    status = 5;
                                }
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
        if (!descendantsTerminated) {
            System.err.println("Development application descendants did not terminate.");
            return 4;
        }
        return status;
    }

    private static Startup startup() throws IOException {
        final String frame = frame("startup", false).orElseThrow();
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

    private static Optional<String> frame(final String phase, final boolean allowImmediateEof) throws IOException {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(CONTROL_FRAME_LIMIT);
        for (int index = 0; ; index++) {
            final int next = System.in.read();
            if (next == '\n') {
                try {
                    return Optional.of(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(frame.toByteArray()))
                            .toString());
                } catch (final CharacterCodingException invalidUtf8) {
                    throw new IOException("Creator " + phase + " frame is not valid UTF-8.", invalidUtf8);
                }
            }
            if (next < 0) {
                if (allowImmediateEof && index == 0) {
                    return Optional.empty();
                }
                throw new IOException("Creator closed stdin before sending a complete " + phase + " frame.");
            }
            if (index == CONTROL_FRAME_LIMIT) {
                throw new IOException("Creator " + phase + " frame exceeds 128 bytes.");
            }
            frame.write(next);
        }
    }

    private static boolean activate(final Startup startup) throws IOException {
        final Optional<String> candidate = frame("activation", true);
        if (candidate.isEmpty()) {
            return false;
        }
        final String[] parts = candidate.orElseThrow().split(" ", -1);
        if (parts.length != 2
                || !"ACTIVATE".equals(parts[0])
                || !MessageDigest.isEqual(
                startup.token().getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IOException("Creator activation frame is invalid.");
        }
        return true;
    }

    private static Socket ready(final Startup startup, final int runtimePort) throws IOException {
        final Socket callback = new Socket();
        try {
            callback.connect(
                    new InetSocketAddress("127.0.0.1", startup.callbackPort()),
                    CALLBACK_CONNECT_MILLIS
            );
            callback.getOutputStream().write(
                    ("READY " + startup.token() + " " + runtimePort + "\n").getBytes(StandardCharsets.UTF_8)
            );
            callback.getOutputStream().flush();
            return callback;
        } catch (final IOException | RuntimeException | Error failure) {
            try {
                callback.close();
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void activated(final Startup startup, final Socket callback) throws IOException {
        callback.getOutputStream().write(
                ("ACTIVATED " + startup.token() + "\n").getBytes(StandardCharsets.UTF_8)
        );
        callback.getOutputStream().flush();
    }

    private static void owner() {
        try {
            while (System.in.read() >= 0) {
                // The Creator keeps stdin open after activation; no further protocol bytes are defined.
            }
        } catch (final IOException ignored) {
            // A broken ownership pipe has the same meaning as EOF.
        }
    }

    private static void await(final CountDownLatch stop) {
        boolean interrupted = false;
        while (stop.getCount() != 0) {
            try {
                stop.await();
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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
        private final ExampleSuite examples;
        private final Semaphore bodyAdmission = new Semaphore(MAX_CONCURRENT_REQUEST_BODIES);
        private final Semaphore unauthorizedBodyAdmission = new Semaphore(MAX_CONCURRENT_UNAUTHORIZED_BODIES);
        private final Semaphore runAdmission = new Semaphore(MAX_CONCURRENT_RUNS);
        private final Semaphore traceAdmission = new Semaphore(MAX_CONCURRENT_TRACES);
        private final MetricsEndpoint metricEndpoint;
        private final Semaphore exampleAdmission = new Semaphore(MAX_CONCURRENT_EXAMPLE_READS);
        private final Semaphore exampleSnapshots = new Semaphore(MAX_CONCURRENT_EXAMPLE_SNAPSHOTS);
        private final Semaphore cancellationAdmission = new Semaphore(MAX_CONCURRENT_CANCELLATIONS);
        private final AtomicReference<CountDownLatch> stepProjection = new AtomicReference<>();
        private final Map<String, ActiveTrace> activeTraces = new ConcurrentHashMap<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean open = new AtomicBoolean(true);

        private RuntimeServer(
                final Application application,
                final String token,
                final HttpServer server,
                final ExecutorService executor,
                final ExampleSuite examples
        ) {
            this.application = application;
            authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            this.server = server;
            this.executor = executor;
            this.examples = examples;
            metricEndpoint = new MetricsEndpoint(application.metrics(), token);
        }

        private static RuntimeServer start(
                final Application application,
                final String token,
                final Path runtimeDirectory,
                final Object lifecycle,
                final Runnable abortApplication
        ) throws IOException {
            HttpServer server = null;
            ExecutorService executor = null;
            ExampleSuite examples = null;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                executor = Executors.newVirtualThreadPerTaskExecutor();
                examples = ExampleSuite.start(application, lifecycle, abortApplication, runtimeDirectory);
                final RuntimeServer runtime = new RuntimeServer(application, token, server, executor, examples);
                server.createContext("/v1/run/", runtime::run);
                server.createContext("/v1/trace/", runtime::trace);
                server.createContext("/v1/traces/", runtime::cancelTrace);
                server.createContext("/v1/metrics", runtime.metricEndpoint);
                server.createContext("/v1/examples", runtime::examples);
                server.setExecutor(executor);
                server.start();
                return runtime;
            } catch (final IOException | RuntimeException | Error exception) {
                if (examples != null) {
                    try {
                        examples.close();
                    } catch (final RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
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

        private RuntimeServer activate() {
            examples.activate();
            return this;
        }

        private void run(final HttpExchange exchange) throws IOException {
            try {
                execute(exchange, "/v1/run/", false);
            } finally {
                exchange.close();
            }
        }

        private void trace(final HttpExchange exchange) throws IOException {
            try {
                execute(exchange, "/v1/trace/", true);
            } finally {
                exchange.close();
            }
        }

        private void cancelTrace(final HttpExchange exchange) throws IOException {
            try {
                if (!authorized(exchange, authorization)) {
                    send(exchange, 401, RailixValue.object(Map.of(
                            "status", RailixValue.string("unauthorized")
                    )));
                    return;
                }
                if (!"DELETE".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, RailixValue.object(Map.of(
                            "status", RailixValue.string("method-not-allowed")
                    )));
                    return;
                }
                final String id = exchange.getRequestURI().getPath().substring("/v1/traces/".length());
                if (!validTraceId(id)) {
                    send(exchange, 404, RailixValue.object(Map.of(
                            "status", RailixValue.string("not-found")
                    )));
                    return;
                }
                final ActiveTrace execution = activeTraces.get(id);
                if (execution == null) {
                    send(exchange, 404, RailixValue.object(Map.of(
                            "status", RailixValue.string("not-found")
                    )));
                    return;
                }
                if (!open.get() || !cancellationAdmission.tryAcquire()) {
                    send(exchange, 503, RailixValue.object(Map.of(
                            "reason", RailixValue.string("saturated"),
                            "status", RailixValue.string("unavailable")
                    )));
                    return;
                }
                try {
                    final String status = execution.cancel();
                    final int response = switch (status) {
                        case "cancelled" -> 200;
                        case "requested" -> 202;
                        default -> 409;
                    };
                    send(exchange, response, RailixValue.object(Map.of(
                            "status", RailixValue.string(status.equals("requested")
                                    ? "cancellation-requested"
                                    : status)
                    )));
                } finally {
                    cancellationAdmission.release();
                }
            } finally {
                exchange.close();
            }
        }

        private void examples(final HttpExchange exchange) throws IOException {
            try {
                if ("/v1/examples/query".equals(exchange.getRequestURI().getPath())) {
                    query(exchange);
                    return;
                }
                if (!authorized(exchange, authorization)) {
                    send(exchange, 401, RailixValue.object(Map.of(
                            "status", RailixValue.string("unauthorized")
                    )));
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    send(exchange, 405, RailixValue.object(Map.of(
                            "status", RailixValue.string("method-not-allowed")
                    )));
                    return;
                }
                final String path = exchange.getRequestURI().getPath();
                final boolean boundedSnapshot = boundedExampleSnapshot(path);
                final Semaphore admission = boundedSnapshot ? exampleSnapshots : exampleAdmission;
                if (!open.get() || !admission.tryAcquire()) {
                    send(exchange, 503, RailixValue.object(Map.of(
                            "status", RailixValue.string("unavailable"),
                            "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                    )));
                    return;
                }
                try {
                    sendExamples(exchange);
                } finally {
                    admission.release();
                }
            } finally {
                exchange.close();
            }
        }

        private static boolean boundedExampleSnapshot(final String path) {
            return "/v1/examples/status".equals(path)
                    || (path.startsWith("/v1/examples/") && !path.endsWith("/view")
                    && !path.contains("/steps/") && !"/v1/examples/coverage".equals(path));
        }

        private void query(final HttpExchange exchange) throws IOException {
            if (!authorized(exchange, authorization)) {
                if (admittedBody(exchange, unauthorizedBodyAdmission).orElse(null) != null) {
                    send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                }
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
                return;
            }
            final byte[] body = admittedBody(exchange, bodyAdmission).orElse(null);
            if (body == null) {
                return;
            }
            if (body.length > MAX_CONTEXT_BYTES) {
                send(exchange, 413, problem("QUERY_TOO_LARGE", "Query exceeds the 1048576-byte limit.", ""));
                return;
            }
            final Semaphore admission = exampleAdmission;
            if (!open.get() || !admission.tryAcquire()) {
                send(exchange, 503, RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                )));
                return;
            }
            try {
                final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, body);
                if (!(normalized instanceof RailixData.Normalized data)
                        || !(data.value() instanceof RailixValue.ObjectValue query)) {
                    send(exchange, 400, problem("INVALID_QUERY", normalized instanceof RailixData.Invalid invalid
                            ? invalid.message() : "Query must be an object.", ""));
                    return;
                }
                final Optional<RailixValue.ObjectValue> response = examples.query(query);
                if (response.isEmpty()) {
                    send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                } else {
                    send(exchange, 200, response.orElseThrow(), MAX_CONTEXT_BYTES,
                            "QUERY_RESPONSE_TOO_LARGE", "Query response exceeds the 1048576-byte limit.");
                }
            } catch (final IllegalArgumentException invalid) {
                send(exchange, 400, problem("INVALID_QUERY", invalid.getMessage(), ""));
            } catch (final ExampleSuite.ProjectionUnavailable unavailable) {
                send(exchange, 503, RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string(unavailable.reason())
                )));
            } finally {
                admission.release();
            }
        }

        private void sendExamples(final HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            if ("/v1/examples".equals(path)) {
                send(
                        exchange,
                        200,
                        examples.inventory(),
                        MAX_EXAMPLE_SUMMARY_BYTES,
                        "EXAMPLE_SUMMARY_TOO_LARGE",
                        "Application Example summary exceeds the 16777216-byte limit."
                );
                return;
            }
            if ("/v1/examples/status".equals(path)) {
                send(exchange, 200, examples.status());
                return;
            }
            if ("/v1/examples/coverage".equals(path)) {
                send(exchange, 200, examples.coverage());
                return;
            }
            if (path.startsWith("/v1/examples/steps/")) {
                final int node;
                try {
                    node = Integer.parseInt(path.substring("/v1/examples/steps/".length()));
                } catch (final NumberFormatException exception) {
                    send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                    return;
                }
                if (!examples.containsNode(node)) {
                    send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                    return;
                }
                final CountDownLatch snapshotReady = new CountDownLatch(1);
                while (true) {
                    final CountDownLatch active = stepProjection.get();
                    if (active != null) {
                        try {
                            active.await(PROJECTION_SNAPSHOT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        send(exchange, 503, RailixValue.object(Map.of(
                                "status", RailixValue.string("unavailable"),
                                "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                        )));
                        return;
                    }
                    if (stepProjection.compareAndSet(null, snapshotReady)) {
                        break;
                    }
                }
                try {
                    final ExampleSuite.StepCasesSnapshot snapshot = examples.snapshotStepCases(node).orElse(null);
                    snapshotReady.countDown();
                    if (snapshot == null) {
                        send(exchange, 503, RailixValue.object(Map.of(
                                "status", RailixValue.string("unavailable"),
                                "reason", RailixValue.string("closed")
                        )));
                        return;
                    }
                    final RailixValue.ObjectValue projection = examples.stepCases(
                            snapshot,
                            MAX_EXAMPLE_SUMMARY_BYTES,
                            MAX_EXAMPLE_STEP_REPLAY_BYTES
                    ).orElse(null);
                    if (projection == null) {
                        send(exchange, 413, problem(
                                "EXAMPLE_STEP_PROJECTION_TOO_LARGE",
                                "Application Step Example projection exceeds the 16777216-byte limit.",
                                ""
                        ));
                    } else {
                        send(
                                exchange,
                                200,
                                projection,
                                MAX_EXAMPLE_SUMMARY_BYTES,
                                "EXAMPLE_STEP_PROJECTION_TOO_LARGE",
                                "Application Step Example projection exceeds the 16777216-byte limit."
                        );
                    }
                } catch (final ExampleSuite.ProjectionUnavailable unavailable) {
                    send(exchange, 503, RailixValue.object(Map.of(
                            "status", RailixValue.string("unavailable"),
                            "reason", RailixValue.string(unavailable.reason())
                    )));
                    return;
                } catch (final ExampleSuite.ReplayLimitExceeded tooLarge) {
                    send(exchange, 413, problem(
                            "EXAMPLE_STEP_REPLAY_TOO_LARGE",
                            "Application Step Example replay exceeds the 67108864-byte limit.",
                            ""
                    ));
                    return;
                } finally {
                    snapshotReady.countDown();
                    stepProjection.compareAndSet(snapshotReady, null);
                }
                return;
            }
            if (!path.startsWith("/v1/examples/")) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            final String requested = path.substring("/v1/examples/".length());
            final boolean summary = requested.endsWith("/view");
            final int steps = requested.indexOf("/steps/");
            final String id = summary
                    ? requested.substring(0, requested.length() - "/view".length())
                    : steps > 0 ? requested.substring(0, steps) : requested;
            if (!summary && steps < 1) {
                final RailixValue.ObjectValue example = examples.example(id).orElse(null);
                if (example == null) {
                    send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                } else {
                    send(exchange, 200, example);
                }
                return;
            }
            final int node;
            try {
                node = summary ? -1 : Integer.parseInt(requested.substring(steps + "/steps/".length()));
            } catch (final NumberFormatException exception) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            if (id.isBlank() || (!summary && node < 0)) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            final InputStream trace = examples.trace(id, node).orElse(null);
            if (trace == null) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            final RailixValue.ObjectValue projection = ExampleSuite.project(trace, node, summary).orElse(null);
            if (projection == null) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
            } else {
                send(
                        exchange,
                        200,
                        projection,
                        MAX_EXAMPLE_SUMMARY_BYTES,
                        "EXAMPLE_VIEW_TOO_LARGE",
                        "Application Example view exceeds the 16777216-byte limit."
                );
            }
        }

        private void execute(
                final HttpExchange exchange,
                final String prefix,
                final boolean trace
        ) throws IOException {
            if (!authorized(exchange, authorization)) {
                if (admittedBody(exchange, unauthorizedBodyAdmission).orElse(null) != null) {
                    send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                }
                return;
            }
            final byte[] body = admittedBody(exchange, bodyAdmission).orElse(null);
            if (body == null) {
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
                return;
            }
            final String[] target = exchange.getRequestURI().getPath().substring(prefix.length()).split("/", -1);
            if (target[0].isBlank() || target.length != 1) {
                send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
                return;
            }
            final RailixValue.ObjectValue context = context(exchange, body);
            if (context == null) {
                return;
            }
            if (!open.get()) {
                send(exchange, 503, RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string("closed")
                )));
                return;
            }
            final Semaphore routeAdmission = trace ? traceAdmission : runAdmission;
            if (!routeAdmission.tryAcquire()) {
                send(exchange, 503, RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"),
                        "reason", RailixValue.string("saturated")
                )));
                return;
            }
            final String traceId;
            final ActiveTrace activeTrace;
            if (trace) {
                final List<String> values = exchange.getRequestHeaders().getOrDefault(TRACE_ID_HEADER, List.of());
                traceId = values.size() == 1 && validTraceId(values.getFirst()) ? values.getFirst() : null;
                if (traceId == null) {
                    routeAdmission.release();
                    send(exchange, 422, problem(
                            "TRACE_ID_INVALID",
                            TRACE_ID_HEADER + " must contain one identifier of at most 128 letters, digits, '.', '_' or '-'.",
                            TRACE_ID_HEADER
                    ));
                    return;
                }
                activeTrace = new ActiveTrace(Thread.currentThread());
                if (activeTraces.putIfAbsent(traceId, activeTrace) != null) {
                    routeAdmission.release();
                    send(exchange, 409, RailixValue.object(Map.of(
                            "status", RailixValue.string("conflict")
                    )));
                    return;
                }
            } else {
                traceId = null;
                activeTrace = null;
            }
            try {
                executeAdmitted(exchange, target[0], trace, activeTrace, context);
            } finally {
                if (traceId != null) {
                    activeTrace.complete(false);
                    activeTraces.remove(traceId, activeTrace);
                }
                routeAdmission.release();
            }
        }

        private static boolean validTraceId(final String value) {
            if (value.isEmpty() || value.length() > MAX_TRACE_ID_LENGTH) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                if (!(character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z'
                        || character >= '0' && character <= '9'
                        || character == '-' || character == '_' || character == '.')) {
                    return false;
                }
            }
            return true;
        }

        private void executeAdmitted(
                final HttpExchange exchange,
                final String target,
                final boolean trace,
                final ActiveTrace activeTrace,
                final RailixValue.ObjectValue context
        ) throws IOException {
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
            if (trace) {
                try (TraceResponse response = new TraceResponse(exchange, activeTrace)) {
                    final RunResult result = application.trace(target, context, test, response);
                    activeTrace.complete(result instanceof RunResult.Cancelled);
                    consumeCancellation(result);
                    if (!response.started()) {
                        sendResult(exchange, result);
                    }
                } catch (final IllegalStateException failure) {
                    if (exchange.getResponseCode() < 0) {
                        send(exchange, 500, problem(
                                "TRACE_WRITE_FAILED",
                                "Development trace could not be written.",
                                ""
                        ));
                    }
                }
            } else {
                final RunResult result = application.run(target, context, test);
                consumeCancellation(result);
                sendResult(exchange, result);
            }
        }

        private static RailixValue.ObjectValue context(
                final HttpExchange exchange,
                final byte[] body
        ) throws IOException {
            if (body.length > MAX_CONTEXT_BYTES) {
                send(exchange, 413, problem(
                        "CONTEXT_TOO_LARGE",
                        "Workflow context exceeds the 1048576-byte limit.",
                        ""
                ));
                return null;
            }
            final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, body);
            if (!(normalized instanceof RailixData.Normalized data)
                    || !(data.value() instanceof RailixValue.ObjectValue context)) {
                final RailixValue value = normalized instanceof RailixData.Invalid invalid
                        ? problem(invalid.code(), invalid.message(), "")
                        : problem("CONTEXT_OBJECT_REQUIRED", "Workflow context must be an object.", "");
                send(exchange, 422, value);
                return null;
            }
            return context;
        }

        private static final class ActiveTrace {
            private final Thread thread;
            private Boolean cancelled;
            private boolean requested;
            private boolean transporting;
            private boolean interruptPending;

            private ActiveTrace(final Thread thread) {
                this.thread = thread;
            }

            private synchronized String cancel() {
                if (cancelled != null) {
                    return "completed";
                }
                if (requested) {
                    return "requested";
                }
                requested = true;
                if (transporting) {
                    interruptPending = true;
                } else {
                    thread.interrupt();
                }
                final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CANCELLATION_WAIT_MILLIS);
                boolean interrupted = false;
                while (cancelled == null) {
                    final long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                        break;
                    }
                }
                final String status = cancelled == null
                        ? "requested"
                        : cancelled ? "cancelled" : "completed";
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return status;
            }

            private synchronized boolean beginTransport() {
                final boolean interrupted = Thread.interrupted();
                transporting = true;
                return interrupted;
            }

            private synchronized void endTransport(final boolean interrupted) {
                transporting = false;
                if (interrupted) Thread.currentThread().interrupt();
                if (interruptPending) {
                    interruptPending = false;
                    thread.interrupt();
                }
            }

            private synchronized void complete(final boolean wasCancelled) {
                if (cancelled == null) {
                    cancelled = wasCancelled;
                    notifyAll();
                }
            }
        }

        private RuntimeServer close() {
            boolean interrupted = Thread.interrupted();
            RuntimeException failure = null;
            try {
                if (open.compareAndSet(true, false)) {
                    metricEndpoint.stopAdmission();
                    final long deadline = System.nanoTime() + RuntimeApplication.DEFAULT_DRAIN_BUDGET.toNanos();
                    final FutureTask<RunResult> draining = new FutureTask<>(() -> application.stop(
                            Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
                    Thread.ofVirtual().name("railix-development-drain").start(draining);
                    final CountDownLatch projection = stepProjection.get();
                    if (projection != null) {
                        projection.countDown();
                    }
                    try {
                        examples.close();
                    } catch (final RuntimeException exception) {
                        failure = exception;
                    }
                    try {
                        interrupted |= release(server, executor, DRAIN_SECONDS);
                    } catch (final RuntimeException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    } finally {
                        try {
                            if (failure != null) draining.cancel(true);
                            if (!(awaitDrain(draining, deadline) instanceof RunResult.Succeeded)) {
                                throw new IllegalStateException("Application work did not drain successfully.");
                            }
                        } catch (final RuntimeException exception) {
                            if (failure == null) failure = exception;
                            else failure.addSuppressed(exception);
                        } finally {
                            Arrays.fill(authorization, (byte) 0);
                            metricEndpoint.close();
                            closed.countDown();
                        }
                    }
                } else {
                    DevelopmentRuntime.await(closed);
                }
                if (failure != null) {
                    throw failure;
                }
                return this;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static RunResult awaitDrain(final FutureTask<RunResult> draining, final long deadline) {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try {
                        return draining.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    } catch (final TimeoutException exception) {
                        throw new IllegalStateException("Application drain deadline expired.", exception);
                    } catch (final ExecutionException exception) {
                        if (exception.getCause() instanceof Error fatal) throw fatal;
                        throw new IllegalStateException("Application shutdown failed.", exception.getCause());
                    }
                }
            } finally {
                draining.cancel(true);
                if (interrupted) Thread.currentThread().interrupt();
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
                if (executor != null && drainSeconds > 0) {
                    // Drain actual handlers, not idle or disconnected HTTP exchanges.
                    executor.shutdown();
                    interrupted |= await(executor, drainSeconds, TimeUnit.SECONDS);
                }
                if (server != null) {
                    server.stop(0);
                }
            } finally {
                if (executor != null) {
                    executor.shutdown();
                    interrupted |= await(executor, 100, TimeUnit.MILLISECONDS);
                    if (!executor.isTerminated()) {
                        executor.shutdownNow();
                        interrupted |= await(executor, FORCE_SECONDS, TimeUnit.SECONDS);
                        if (!executor.isTerminated()) {
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            throw new IllegalStateException(
                                    "Development HTTP workers did not stop within one second."
                            );
                        }
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

    private static final class TraceResponse implements TraceSink, AutoCloseable {
        private final HttpExchange exchange;
        private final RuntimeServer.ActiveTrace trace;
        private BufferedOutputStream output;

        private TraceResponse(final HttpExchange exchange, final RuntimeServer.ActiveTrace trace) {
            this.exchange = exchange;
            this.trace = trace;
        }

        @Override
        public boolean write(final RailixValue.ObjectValue event) throws IOException {
            final Optional<String> json = RailixJson.writeDocument(event, MAX_TRACE_EVENT_BYTES);
            if (json.isEmpty()) {
                writeLine(RailixJson.write(Trace.traceError(
                        "TRACE_EVENT_TOO_LARGE",
                        "One development trace event exceeds 4194304 bytes."
                )));
                return false;
            }
            writeLine(json.orElseThrow());
            return true;
        }

        private void writeLine(final String json) throws IOException {
            final boolean interrupted = trace.beginTransport();
            try {
                if (output == null) {
                    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, 0);
                    output = new BufferedOutputStream(exchange.getResponseBody(), 16_384);
                }
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
            } finally {
                trace.endTransport(interrupted);
            }
        }

        private boolean started() {
            return output != null;
        }

        @Override
        public void close() throws IOException {
            if (output != null) {
                final boolean interrupted = trace.beginTransport();
                try {
                    output.close();
                } finally {
                    trace.endTransport(interrupted);
                }
            }
        }
    }

    private static void sendResult(final HttpExchange exchange, final RunResult result) throws IOException {
        if (result.contextRejection().isPresent()) {
            send(exchange, 500, problem("RUN_RESPONSE_INVALID", "Application response is not valid canonical JSON.", ""));
            return;
        }
        final ResultResponse response = response(result);
        send(exchange, response.status(), response.value());
    }

    private static ResultResponse response(final RunResult result) {
        return switch (result) {
            case RunResult.Completed completed -> {
                final var value = new LinkedHashMap<>(Trace.terminal(completed).values());
                value.remove("type");
                yield new ResultResponse(200, RailixValue.object(value));
            }
            case RunResult.Succeeded succeeded -> new ResultResponse(200, RailixValue.object(Map.of(
                    "status", RailixValue.string("succeeded"),
                    "context", succeeded.context()
            )));
            case RunResult.Rejected rejected -> new ResultResponse(422, RailixValue.object(Map.of(
                    "status", RailixValue.string("rejected"),
                    "diagnostics", diagnosticValues(rejected.diagnostics())
            )));
            case RunResult.Failed failed -> new ResultResponse(500, RailixValue.object(Map.of(
                    "status", RailixValue.string("failed"),
                    "failure", failureValue(failed)
            )));
            case RunResult.Cancelled cancelled -> new ResultResponse(409, RailixValue.object(Map.of(
                    "status", RailixValue.string("cancelled")
            )));
        };
    }

    private static RailixValue.ObjectValue failureValue(final RunResult.Failed result) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("code", RailixValue.string(result.failure().code()));
        value.put("message", RailixValue.string(result.failure().message()));
        value.put("step", RailixValue.string(result.failure().stepId()));
        if (!result.failure().path().isEmpty()) {
            value.put("path", RailixValue.string(result.failure().path()));
        }
        return RailixValue.object(value);
    }

    private record ResultResponse(int status, RailixValue.ObjectValue value) {
    }
}
