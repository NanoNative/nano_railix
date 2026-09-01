package dev.nanonative.railix.development;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.RuntimeApplication;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Development-only management runtime embedded in a generated application artifact. */
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
    private static final int MAX_CONCURRENT_METRICS = 2;
    private static final int MAX_CONCURRENT_EXAMPLE_READS = 2;
    private static final int MAX_CONCURRENT_CANCELLATIONS = MAX_CONCURRENT_TRACES;
    private static final int MAX_TRACE_ID_LENGTH = 128;
    private static final int CANCELLATION_WAIT_MILLIS = 2_000;
    private static final int PROJECTION_SNAPSHOT_WAIT_MILLIS = 1_000;
    private static final int REQUEST_BODY_SECONDS = 5;
    private static final String TRACE_ID_HEADER = "X-Railix-Trace-Id";
    private static final int DRAIN_SECONDS = 10;
    private static final int FORCE_SECONDS = 1;
    private static final int PROCESS_WAIT_SECONDS = 1;

    private DevelopmentRuntime() {
    }

    /** Development-only execution surface implemented by a generated observable application. */
    public interface Application extends RuntimeApplication {
        /** Returns the application-owned development metric store. */
        Metrics metrics();

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
        private final Deque<Pending> calls = new ArrayDeque<>();
        private final Map<String, Integer> occurrences = new HashMap<>();
        private boolean recording = true;
        private long sequence;

        private Trace(final TraceSink sink) {
            this.sink = sink;
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
                trace.emit(terminal(result));
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
            if (trace != null && trace.recording) {
                trace.open(node, invocation, use, context);
            }
            return trace != null && trace.recording;
        }

        /** Completes one successful graph-Step record after context mutations are committed. */
        public static boolean after(
                final String invocation,
                final String outcome,
                final RailixValue.ObjectValue context
        ) {
            final Trace trace = CURRENT.get();
            if (trace != null && trace.recording) {
                trace.close(invocation, "succeeded", outcome, context);
            }
            return trace != null && trace.recording;
        }

        /** Completes one rejected, failed, or cancelled graph-Step record. */
        public static boolean after(
                final String invocation,
                final RunResult result,
                final RailixValue.ObjectValue context
        ) {
            final Trace trace = CURRENT.get();
            if (trace != null && trace.recording) {
                trace.close(invocation, status(result), "", context);
            }
            return trace != null && trace.recording;
        }

        /** Invokes one graph handler and records its resolved values and explicit result. */
        public static StepResult invoke(
                final StepInput input,
                final StepHandler handler
        ) throws InterruptedException {
            final Trace trace = CURRENT.get();
            if (trace == null || !trace.recording) {
                return handler.run(input);
            }
            final Pending call = trace.calls.peek();
            if (call == null || call.entered) {
                throw new IllegalStateException("Trace has no pending graph Step invocation.");
            }
            return trace.invoke(call, false, input, handler);
        }

        /** Invokes one nested handler under its stable parent-relative identity. */
        public static StepResult invoke(
                final String invocation,
                final String use,
                final StepInput input,
                final StepHandler handler
        ) throws InterruptedException {
            final Trace trace = CURRENT.get();
            if (trace == null || !trace.recording) {
                return handler.run(input);
            }
            final Pending call = trace.open(-1, invocation, use, null);
            return trace.recording
                    ? trace.invoke(call, true, input, handler)
                    : handler.run(input);
        }

        private StepResult invoke(
                final Pending call,
                final boolean nested,
                final StepInput input,
                final StepHandler handler
        ) throws InterruptedException {
            call.entered = true;
            call.inputs = input.values();
            call.options = input.options();
            try {
                final StepResult result = handler.run(input);
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
            final int occurrence = occurrences.merge(invocation, 1, Integer::sum) - 1;
            final Pending call = new Pending(sequence++, occurrence, invocation, use, context);
            calls.push(call);
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("type", RailixValue.string("step_start"));
            value.put("sequence", RailixValue.number(call.sequence));
            value.put("occurrence", RailixValue.number(call.occurrence));
            value.put("id", RailixValue.string(invocation));
            value.put("use", RailixValue.string(use));
            if (node >= 0) {
                value.put("node", RailixValue.number(node));
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
            emit(RailixValue.object(value));
        }

        private void emit(final RailixValue.ObjectValue event) {
            if (!recording) {
                return;
            }
            try {
                if (!sink.write(event)) {
                    discard();
                }
            } catch (final IOException ignored) {
                discard();
            }
        }

        private void discard() {
            recording = false;
            calls.clear();
            occurrences.clear();
        }

        private void complete() {
            if (recording && !calls.isEmpty()) {
                throw new IllegalStateException("Trace completed with unfinished Step records.");
            }
        }

        private static RailixValue.ObjectValue terminal(final RunResult result) {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("type", RailixValue.string("result"));
            value.put("status", RailixValue.string(status(result)));
            switch (result) {
                case RunResult.Succeeded succeeded -> value.put("context", succeeded.context());
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
     * Dense application, flow, and graph-Step metrics owned by one generated development application.
     * Execution, error, and cancellation counters are exact. Flow timing is exact; graph-Step timing
     * samples one execution out of every 1,024 to keep the per-Step hot path bounded. Recording allocates
     * no request objects. Snapshots and text exports allocate only when read. Selected-node reads use a
     * fixed open-addressed index; exported storage fields report exact primitive counter and index bytes,
     * not JVM-dependent object or shared identifier storage.
     */
    public static final class Metrics {
        private static final int EXECUTIONS = 0;
        private static final int ERRORS = 1;
        private static final int CANCELLED = 2;
        private static final int DURATION_TOTAL = 3;
        private static final int DURATION_MAX = 4;
        private static final int DURATION_SAMPLES = 5;
        private static final int STRIDE = 6;
        private static final long UNSAMPLED = Long.MIN_VALUE;
        private static final long STEP_SAMPLE_MASK = 1_023;
        private final String project;
        private final String[] flows;
        private final String[] steps;
        private final AtomicLongArray flowCounters;
        private final AtomicLongArray stepCounters;
        private final AtomicIntegerArray flowInFlight;
        private final IdentifierIndex flowIndex;
        private final IdentifierIndex stepIndex;

        /**
         * Creates one fixed-cardinality metric store.
         *
         * @param project stable project identifier
         * @param flows stable flow identifiers indexed by generated flow number
         * @param steps stable identifiers for metrics-enabled executable graph Steps
         */
        public Metrics(
                final String project,
                final String[] flows,
                final String[] steps
        ) {
            if (project == null || project.isBlank()
                    || flows == null || steps == null
                    || Arrays.stream(flows).anyMatch(id -> id == null || id.isBlank())
                    || Arrays.stream(steps).anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("Metric project, flows, and Steps must be supplied.");
            }
            this.project = project;
            this.flows = flows.clone();
            this.steps = steps.clone();
            flowCounters = new AtomicLongArray(flows.length * STRIDE);
            stepCounters = new AtomicLongArray(steps.length * STRIDE);
            flowInFlight = new AtomicIntegerArray(flows.length);
            flowIndex = new IdentifierIndex(this.flows);
            stepIndex = new IdentifierIndex(this.steps);
        }

        /** Starts one operational flow and returns its opaque monotonic start marker. */
        public long startFlow(final int flow) {
            return startFlow(flowCounters, flowInFlight, flow);
        }

        /**
         * Starts one metrics-enabled executable graph Step and returns its opaque sampling marker.
         * The marker must be passed unchanged to {@link #finishStep(int, long, int, RunResult)}.
         */
        public long startStep(final int step) {
            return startStep(stepCounters, step);
        }

        /**
         * Finishes one graph Step and classifies a terminal rejection, failure, or cancellation.
         * A non-negative outcome means the Step completed normally and {@code result} is ignored.
         * Execution and terminal-state counters remain exact even when this execution was not timed.
         */
        public Metrics finishStep(
                final int step,
                final long started,
                final int outcome,
                final RunResult result
        ) {
            final boolean cancelled = result instanceof RunResult.Cancelled;
            finishStep(
                    stepCounters,
                    step,
                    started,
                    outcome == Integer.MIN_VALUE || outcome < 0 && !cancelled,
                    cancelled
            );
            return this;
        }

        /** Finishes one flow, records its terminal state and wall time, and returns this store. */
        public Metrics finishFlow(
                final int flow,
                final long started,
                final RunResult result
        ) {
            finishFlow(
                    flowCounters,
                    flowInFlight,
                    flow,
                    started,
                    result == null || result instanceof RunResult.Rejected || result instanceof RunResult.Failed,
                    result instanceof RunResult.Cancelled
            );
            return this;
        }

        /** Streams the complete JSON metric document without retaining a response-sized object graph. */
        public Metrics writeJson(final OutputStream destination) throws IOException {
            return writeJson(destination, null, true);
        }

        /** Streams application and process metrics without per-flow or per-Step series. */
        public Metrics writeApplicationJson(final OutputStream destination) throws IOException {
            return writeJson(destination, null, false);
        }

        /** Streams application metrics plus indexed flow and Step series matching {@code node}. */
        public Metrics writeNodeJson(final OutputStream destination, final String node) throws IOException {
            if (node == null || node.isBlank()) {
                throw new IllegalArgumentException("Metric node must be a non-blank identifier.");
            }
            return writeJson(destination, node, false);
        }

        private Metrics writeJson(
                final OutputStream destination,
                final String selected,
                final boolean all
        ) throws IOException {
            final BufferedWriter output = writer(destination);
            Values application = Values.EMPTY;
            for (int index = 0; index < flows.length; index++) {
                application = application.add(values(flowCounters, flowInFlight, index));
            }
            output.append("{\"application_pid\":")
                    .append(Long.toString(ProcessHandle.current().pid()))
                    .append(",\"application\":{\"metrics\":");
            json(output, application);
            output.append("},\"flows\":[");
            boolean separator = false;
            if (all) {
                for (int index = 0; index < flows.length; index++) {
                    separator = entity(
                            output,
                            separator,
                            flows[index],
                            values(flowCounters, flowInFlight, index)
                    );
                }
            } else {
                final int index = flowIndex.find(selected, flows);
                separator = index < 0 ? separator : entity(
                        output,
                        separator,
                        flows[index],
                        values(flowCounters, flowInFlight, index)
                );
            }
            output.append("],\"metric_counter_bytes\":").append(Long.toString(counterBytes()))
                    .append(",\"metric_lookup_bytes\":").append(Long.toString(lookupBytes()))
                    .append(",\"metric_primitive_bytes\":").append(Long.toString(primitiveBytes()))
                    .append(",\"process\":").append(RailixJson.write(process()))
                    .append(",\"project\":");
            string(output, project);
            output.append(",\"steps\":[");
            separator = false;
            if (all) {
                for (int index = 0; index < steps.length; index++) {
                    separator = entity(output, separator, steps[index], values(stepCounters, index));
                }
            } else {
                final int index = stepIndex.find(selected, steps);
                separator = index < 0 ? separator : entity(
                        output,
                        separator,
                        steps[index],
                        values(stepCounters, index)
                );
            }
            output.append("]}");
            output.flush();
            return this;
        }

        /** Streams Prometheus text exposition using only fixed project, flow, and Step labels. */
        public Metrics writePrometheus(final OutputStream destination) throws IOException {
            final BufferedWriter output = writer(destination);
            Values application = Values.EMPTY;
            for (int index = 0; index < flows.length; index++) {
                application = application.add(values(flowCounters, flowInFlight, index));
            }
            prometheusTypes(output, "application", true);
            prometheusTypes(output, "flow", true);
            prometheusTypes(output, "step", false);
            prometheus(output, "application", "project=\"" + prom(project) + "\"", application);
            for (int index = 0; index < flows.length; index++) {
                prometheus(output, "flow", "project=\"" + prom(project) + "\",flow=\""
                        + prom(flows[index]) + "\"", values(flowCounters, flowInFlight, index));
            }
            for (int index = 0; index < steps.length; index++) {
                prometheus(output, "step", "project=\"" + prom(project) + "\",step=\""
                        + prom(steps[index]) + "\"", values(stepCounters, index));
            }
            for (final Map.Entry<String, RailixValue> field : process().values().entrySet()) {
                final RailixValue.NumberValue number = (RailixValue.NumberValue) field.getValue();
                output.append("railix_process_").append(field.getKey()).append("{project=\"")
                        .append(prom(project)).append("\"} ")
                        .append(number.value().toPlainString()).append('\n');
            }
            output.append("railix_metric_counter_bytes{project=\"").append(prom(project)).append("\"} ")
                    .append(Long.toString(counterBytes())).append('\n');
            output.append("railix_metric_lookup_bytes{project=\"").append(prom(project)).append("\"} ")
                    .append(Long.toString(lookupBytes())).append('\n');
            output.append("railix_metric_primitive_bytes{project=\"").append(prom(project)).append("\"} ")
                    .append(Long.toString(primitiveBytes())).append('\n');
            output.flush();
            return this;
        }

        /** Streams Influx line protocol using only fixed project, flow, and Step tags. */
        public Metrics writeInflux(final OutputStream destination) throws IOException {
            final BufferedWriter output = writer(destination);
            Values application = Values.EMPTY;
            for (int index = 0; index < flows.length; index++) {
                application = application.add(values(flowCounters, flowInFlight, index));
            }
            influx(output, "application", "project=" + influxTag(project), application);
            for (int index = 0; index < flows.length; index++) {
                influx(output, "flow", "project=" + influxTag(project) + ",flow="
                        + influxTag(flows[index]), values(flowCounters, flowInFlight, index));
            }
            for (int index = 0; index < steps.length; index++) {
                influx(output, "step", "project=" + influxTag(project) + ",step="
                        + influxTag(steps[index]), values(stepCounters, index));
            }
            output.append("railix_process,project=").append(influxTag(project)).append(' ');
            boolean separator = false;
            for (final Map.Entry<String, RailixValue> field : process().values().entrySet()) {
                final RailixValue.NumberValue number = (RailixValue.NumberValue) field.getValue();
                if (separator) {
                    output.append(',');
                }
                output.append(field.getKey()).append('=').append(number.value().toPlainString()).append('i');
                separator = true;
            }
            output.append(",metric_counter_bytes=").append(Long.toString(counterBytes())).append('i')
                    .append(",metric_lookup_bytes=").append(Long.toString(lookupBytes())).append('i')
                    .append(",metric_primitive_bytes=").append(Long.toString(primitiveBytes())).append("i\n");
            output.flush();
            return this;
        }

        private static long startFlow(
                final AtomicLongArray counters,
                final AtomicIntegerArray inFlight,
                final int index
        ) {
            counters.getAndIncrement(index * STRIDE + EXECUTIONS);
            inFlight.incrementAndGet(index);
            return System.nanoTime();
        }

        private static long startStep(final AtomicLongArray counters, final int index) {
            final long execution = counters.incrementAndGet(index * STRIDE + EXECUTIONS);
            if ((execution - 1 & STEP_SAMPLE_MASK) != 0) {
                return UNSAMPLED;
            }
            final long started = System.nanoTime();
            return started == UNSAMPLED ? UNSAMPLED + 1 : started;
        }

        private static void finishFlow(
                final AtomicLongArray counters,
                final AtomicIntegerArray inFlight,
                final int index,
                final long started,
                final boolean error,
                final boolean cancelled
        ) {
            final long duration = Math.max(0, System.nanoTime() - started);
            if (error) {
                counters.getAndIncrement(index * STRIDE + ERRORS);
            }
            if (cancelled) {
                counters.getAndIncrement(index * STRIDE + CANCELLED);
            }
            duration(counters, index, duration);
            inFlight.decrementAndGet(index);
        }

        private static void finishStep(
                final AtomicLongArray counters,
                final int index,
                final long started,
                final boolean error,
                final boolean cancelled
        ) {
            if (error) {
                counters.getAndIncrement(index * STRIDE + ERRORS);
            }
            if (cancelled) {
                counters.getAndIncrement(index * STRIDE + CANCELLED);
            }
            if (started != UNSAMPLED) {
                duration(counters, index, Math.max(0, System.nanoTime() - started));
            }
        }

        private static void duration(final AtomicLongArray counters, final int index, final long duration) {
            final int offset = index * STRIDE;
            counters.getAndAdd(offset + DURATION_TOTAL, duration);
            counters.getAndIncrement(offset + DURATION_SAMPLES);
            maximum(counters, offset + DURATION_MAX, duration);
        }

        private static void maximum(final AtomicLongArray values, final int index, final long candidate) {
            long value = values.get(index);
            while (candidate > value && !values.compareAndSet(index, value, candidate)) {
                value = values.get(index);
            }
        }

        private static Values values(
                final AtomicLongArray counters,
                final AtomicIntegerArray inFlight,
                final int index
        ) {
            final int offset = index * STRIDE;
            return new Values(
                    counters.get(offset + EXECUTIONS),
                    counters.get(offset + ERRORS),
                    counters.get(offset + CANCELLED),
                    inFlight.get(index),
                    counters.get(offset + DURATION_TOTAL),
                    counters.get(offset + DURATION_MAX),
                    counters.get(offset + DURATION_SAMPLES)
            );
        }

        private static Values values(final AtomicLongArray counters, final int index) {
            final int offset = index * STRIDE;
            return new Values(
                    counters.get(offset + EXECUTIONS),
                    counters.get(offset + ERRORS),
                    counters.get(offset + CANCELLED),
                    -1,
                    counters.get(offset + DURATION_TOTAL),
                    counters.get(offset + DURATION_MAX),
                    counters.get(offset + DURATION_SAMPLES)
            );
        }

        private static BufferedWriter writer(final OutputStream destination) {
            if (destination == null) {
                throw new IllegalArgumentException("Metric destination cannot be Java null.");
            }
            return new BufferedWriter(new OutputStreamWriter(destination, StandardCharsets.UTF_8), 16_384);
        }

        private static boolean entity(
                final BufferedWriter output,
                final boolean separator,
                final String id,
                final Values values
        ) throws IOException {
            if (separator) {
                output.append(',');
            }
            output.append("{\"id\":");
            string(output, id);
            output.append(",\"metrics\":");
            json(output, values);
            output.append('}');
            return true;
        }

        private static void json(final BufferedWriter output, final Values values) throws IOException {
            output.append("{\"cancelled\":").append(Long.toString(values.cancelled))
                    .append(",\"duration_nanos_max\":").append(Long.toString(values.durationMax))
                    .append(",\"duration_nanos_total\":").append(Long.toString(values.durationTotal))
                    .append(",\"duration_samples\":").append(Long.toString(values.durationSamples))
                    .append(",\"errors\":").append(Long.toString(values.errors))
                    .append(",\"executions\":").append(Long.toString(values.executions));
            if (values.inFlight >= 0) {
                output.append(",\"in_flight\":").append(Long.toString(values.inFlight));
            }
            output.append('}');
        }

        private static void string(final BufferedWriter output, final String value) throws IOException {
            output.append(RailixJson.write(RailixValue.string(value)));
        }

        private long counterBytes() {
            return ((long) flowCounters.length() + stepCounters.length()) * Long.BYTES
                    + (long) flowInFlight.length() * Integer.BYTES;
        }

        private long lookupBytes() {
            return flowIndex.bytes() + stepIndex.bytes();
        }

        private long primitiveBytes() {
            return counterBytes() + lookupBytes();
        }

        private static RailixValue.ObjectValue process() {
            final var runtime = ManagementFactory.getRuntimeMXBean();
            final var memory = ManagementFactory.getMemoryMXBean();
            final var threads = ManagementFactory.getThreadMXBean();
            final Map<String, RailixValue> values = new LinkedHashMap<>();
            values.put("uptime_millis", RailixValue.number(runtime.getUptime()));
            values.put("heap_used_bytes", RailixValue.number(memory.getHeapMemoryUsage().getUsed()));
            values.put("heap_committed_bytes", RailixValue.number(memory.getHeapMemoryUsage().getCommitted()));
            values.put("heap_max_bytes", RailixValue.number(memory.getHeapMemoryUsage().getMax()));
            values.put("non_heap_used_bytes", RailixValue.number(memory.getNonHeapMemoryUsage().getUsed()));
            values.put("live_threads", RailixValue.number(threads.getThreadCount()));
            values.put("peak_threads", RailixValue.number(threads.getPeakThreadCount()));
            final com.sun.management.OperatingSystemMXBean system = ManagementFactory.getPlatformMXBean(
                    com.sun.management.OperatingSystemMXBean.class
            );
            if (system != null) {
                values.put("process_cpu_nanos", RailixValue.number(system.getProcessCpuTime()));
                final double processLoad = system.getProcessCpuLoad();
                if (processLoad >= 0) {
                    values.put("process_cpu_load_ppm", RailixValue.number(
                            Math.round(processLoad * 1_000_000)
                    ));
                }
                final double systemLoad = system.getCpuLoad();
                if (systemLoad >= 0) {
                    values.put("system_cpu_load_ppm", RailixValue.number(
                            Math.round(systemLoad * 1_000_000)
                    ));
                }
            }
            long collections = 0;
            long collectionMillis = 0;
            for (final var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                collections += Math.max(0, collector.getCollectionCount());
                collectionMillis += Math.max(0, collector.getCollectionTime());
            }
            values.put("gc_collections", RailixValue.number(collections));
            values.put("gc_millis", RailixValue.number(collectionMillis));
            return RailixValue.object(values);
        }

        private static void prometheus(
                final BufferedWriter output,
                final String scope,
                final String labels,
                final Values values
        ) throws IOException {
            output.append("railix_").append(scope).append("_executions_total{").append(labels).append("} ")
                    .append(Long.toString(values.executions)).append('\n');
            output.append("railix_").append(scope).append("_errors_total{").append(labels).append("} ")
                    .append(Long.toString(values.errors)).append('\n');
            output.append("railix_").append(scope).append("_cancelled_total{").append(labels).append("} ")
                    .append(Long.toString(values.cancelled)).append('\n');
            if (values.inFlight >= 0) {
                output.append("railix_").append(scope).append("_in_flight{").append(labels).append("} ")
                        .append(Long.toString(values.inFlight)).append('\n');
            }
            output.append("railix_").append(scope).append("_duration_seconds_sum{").append(labels).append("} ")
                    .append(seconds(values.durationTotal)).append('\n');
            output.append("railix_").append(scope).append("_duration_seconds_max{").append(labels).append("} ")
                    .append(seconds(values.durationMax)).append('\n');
            output.append("railix_").append(scope).append("_duration_seconds_count{").append(labels).append("} ")
                    .append(Long.toString(values.durationSamples)).append('\n');
        }

        private static void prometheusTypes(
                final BufferedWriter output,
                final String scope,
                final boolean inFlight
        ) throws IOException {
            output.append("# TYPE railix_").append(scope).append("_executions_total counter\n")
                    .append("# TYPE railix_").append(scope).append("_errors_total counter\n")
                    .append("# TYPE railix_").append(scope).append("_cancelled_total counter\n");
            if (inFlight) {
                output.append("# TYPE railix_").append(scope).append("_in_flight gauge\n");
            }
            output
                    .append("# TYPE railix_").append(scope).append("_duration_seconds_sum counter\n")
                    .append("# TYPE railix_").append(scope).append("_duration_seconds_max gauge\n")
                    .append("# TYPE railix_").append(scope).append("_duration_seconds_count counter\n");
        }

        private static void influx(
                final BufferedWriter output,
                final String scope,
                final String tags,
                final Values values
        ) throws IOException {
            output.append("railix_").append(scope).append(',').append(tags)
                    .append(" executions=").append(Long.toString(values.executions)).append('i')
                    .append(",errors=").append(Long.toString(values.errors)).append('i')
                    .append(",cancelled=").append(Long.toString(values.cancelled)).append('i');
            if (values.inFlight >= 0) {
                output.append(",in_flight=").append(Long.toString(values.inFlight)).append('i');
            }
            output
                    .append(",duration_nanos_total=").append(Long.toString(values.durationTotal)).append('i')
                    .append(",duration_nanos_max=").append(Long.toString(values.durationMax)).append('i')
                    .append(",duration_samples=").append(Long.toString(values.durationSamples)).append("i\n");
        }

        private static String seconds(final long nanos) {
            return java.math.BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString();
        }

        private static String prom(final String value) {
            return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
        }

        private static String influxTag(final String value) {
            return value.replace("\\", "\\\\").replace(" ", "\\ ")
                    .replace(",", "\\,").replace("=", "\\=");
        }

        private record Values(
                long executions,
                long errors,
                long cancelled,
                long inFlight,
                long durationTotal,
                long durationMax,
                long durationSamples
        ) {
            private static final Values EMPTY = new Values(0, 0, 0, 0, 0, 0, 0);

            private Values add(final Values other) {
                return new Values(
                        saturated(executions, other.executions),
                        saturated(errors, other.errors),
                        saturated(cancelled, other.cancelled),
                        saturated(inFlight, other.inFlight),
                        saturated(durationTotal, other.durationTotal),
                        Math.max(durationMax, other.durationMax),
                        saturated(durationSamples, other.durationSamples)
                );
            }

            private static long saturated(final long left, final long right) {
                return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
            }
        }

        private static final class IdentifierIndex {
            private final int[] slots;

            private IdentifierIndex(final String[] identifiers) {
                if (identifiers.length == 0) {
                    slots = new int[0];
                    return;
                }
                int capacity = 2;
                while (capacity < identifiers.length * 2) {
                    capacity <<= 1;
                }
                slots = new int[capacity];
                for (int index = 0; index < identifiers.length; index++) {
                    int slot = slot(identifiers[index], capacity - 1);
                    while (slots[slot] != 0) {
                        if (identifiers[slots[slot] - 1].equals(identifiers[index])) {
                            break;
                        }
                        slot = (slot + 1) & (capacity - 1);
                    }
                    if (slots[slot] == 0) {
                        slots[slot] = index + 1;
                    }
                }
            }

            private int find(final String identifier, final String[] identifiers) {
                if (identifier == null || slots.length == 0) {
                    return -1;
                }
                final int mask = slots.length - 1;
                int slot = slot(identifier, mask);
                while (slots[slot] != 0) {
                    final int index = slots[slot] - 1;
                    if (identifiers[index].equals(identifier)) {
                        return index;
                    }
                    slot = (slot + 1) & mask;
                }
                return -1;
            }

            private long bytes() {
                return (long) slots.length * Integer.BYTES;
            }

            private static int slot(final String identifier, final int mask) {
                final int hash = identifier.hashCode();
                return (hash ^ hash >>> 16) & mask;
            }
        }
    }

    /**
     * Serves authenticated Creator development endpoints for one already compiled application.
     * The Creator writes bounded startup and activation frames to stdin, then keeps the pipe open for ownership.
     *
     * @param application immutable generated application
     * @return {@code 0} after normal owner closure, {@code 2} for an invalid control frame,
     * {@code 3} for startup or activation failure, {@code 4} for retained descendants, {@code 5} for shutdown
     * failure, or {@code 6} when an Example worker cannot be reclaimed cooperatively
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
            if (!terminateDescendants()) {
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
        boolean descendantsTerminated = false;
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
                        if (activate(startup, started)) {
                            synchronized (lifecycle) {
                                if (stop.getCount() == 0) {
                                    throw new IllegalStateException(
                                            "Development application stopped before activation acknowledgement."
                                    );
                                }
                                activated(startup, callback);
                            }
                            ownsApplication = true;
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

    private static boolean activate(final Startup startup, final RuntimeServer runtime) throws IOException {
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
        runtime.activate();
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
        private final Semaphore metricAdmission = new Semaphore(MAX_CONCURRENT_METRICS);
        private final Semaphore exampleAdmission = new Semaphore(MAX_CONCURRENT_EXAMPLE_READS);
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
                server.createContext("/v1/metrics", runtime::metrics);
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
                if (!authorized(exchange)) {
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

        private void metrics(final HttpExchange exchange) throws IOException {
            try {
                if (!authorized(exchange)) {
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
                if (!open.get() || !metricAdmission.tryAcquire()) {
                    send(exchange, 503, RailixValue.object(Map.of(
                            "status", RailixValue.string("unavailable"),
                            "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                    )));
                    return;
                }
                try {
                    sendMetrics(exchange, application.metrics());
                } finally {
                    metricAdmission.release();
                }
            } finally {
                exchange.close();
            }
        }

        private void examples(final HttpExchange exchange) throws IOException {
            try {
                if (!authorized(exchange)) {
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
                if (!open.get() || !exampleAdmission.tryAcquire()) {
                    send(exchange, 503, RailixValue.object(Map.of(
                            "status", RailixValue.string("unavailable"),
                            "reason", RailixValue.string(open.get() ? "saturated" : "closed")
                    )));
                    return;
                }
                try {
                    sendExamples(exchange);
                } finally {
                    exampleAdmission.release();
                }
            } finally {
                exchange.close();
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

        private static void sendMetrics(
                final HttpExchange exchange,
                final Metrics metrics
        ) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            final String contentType;
            String node = null;
            if ("/v1/metrics".equals(path) || "/v1/metrics/application".equals(path)
                    || path.startsWith("/v1/metrics/nodes/")) {
                contentType = "application/json; charset=utf-8";
            } else if ("/v1/metrics/prometheus".equals(path)) {
                contentType = "text/plain; version=0.0.4; charset=utf-8";
            } else if ("/v1/metrics/influx".equals(path)) {
                contentType = "text/plain; charset=utf-8";
            } else {
                send(exchange, 404, RailixValue.object(Map.of(
                        "status", RailixValue.string("not-found")
                )));
                return;
            }
            if (path.startsWith("/v1/metrics/nodes/")) {
                node = path.substring("/v1/metrics/nodes/".length());
                if (node.isBlank()) {
                    send(exchange, 404, RailixValue.object(Map.of(
                            "status", RailixValue.string("not-found")
                    )));
                    return;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            try (var output = new BufferedOutputStream(exchange.getResponseBody(), 16_384)) {
                if ("/v1/metrics".equals(path)) {
                    metrics.writeJson(output);
                } else if ("/v1/metrics/application".equals(path)) {
                    metrics.writeApplicationJson(output);
                } else if (path.startsWith("/v1/metrics/nodes/")) {
                    metrics.writeNodeJson(output, node);
                } else if ("/v1/metrics/prometheus".equals(path)) {
                    metrics.writePrometheus(output);
                } else {
                    metrics.writeInflux(output);
                }
            }
        }

        private void execute(
                final HttpExchange exchange,
                final String prefix,
                final boolean trace
        ) throws IOException {
            if (!authorized(exchange)) {
                if (admittedBody(exchange, unauthorizedBodyAdmission) != null) {
                    send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                }
                return;
            }
            final byte[] body = admittedBody(exchange, bodyAdmission);
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
                        send(exchange, result);
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
                send(exchange, result);
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

        private static byte[] requestBody(final HttpExchange exchange) throws IOException {
            final AtomicBoolean reading = new AtomicBoolean(true);
            final Thread deadline = Thread.ofVirtual().name("railix-development-body-deadline").start(() -> {
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
                return reading.compareAndSet(true, false) ? body.toByteArray() : null;
            } catch (final IOException failure) {
                if (reading.compareAndSet(true, false)) {
                    throw failure;
                }
                return null;
            } finally {
                deadline.interrupt();
            }
        }

        private static byte[] admittedBody(
                final HttpExchange exchange,
                final Semaphore admission
        ) throws IOException {
            if (!admission.tryAcquire()) {
                exchange.close();
                return null;
            }
            try {
                return requestBody(exchange);
            } finally {
                admission.release();
            }
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
                if (interrupted || interruptPending) {
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

        private boolean authorized(final HttpExchange exchange) {
            final List<String> values = exchange.getRequestHeaders().getOrDefault("Authorization", List.of());
            return values.size() == 1 && MessageDigest.isEqual(
                    authorization,
                    values.getFirst().getBytes(StandardCharsets.UTF_8)
            );
        }

        private RuntimeServer close() {
            boolean interrupted = Thread.interrupted();
            RuntimeException failure = null;
            try {
                if (open.compareAndSet(true, false)) {
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
                            Arrays.fill(authorization, (byte) 0);
                        } finally {
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
            final Optional<String> json = RailixJson.write(event, MAX_TRACE_EVENT_BYTES);
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

    private static void send(final HttpExchange exchange, final RunResult result) throws IOException {
        final ResultResponse response = response(result);
        send(exchange, response.status(), response.value());
    }

    private static ResultResponse response(final RunResult result) {
        return switch (result) {
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
        send(
                exchange,
                status,
                value,
                MAX_CONTEXT_BYTES,
                "RUN_RESPONSE_TOO_LARGE",
                "Application response exceeds the 1048576-byte limit."
        );
    }

    private static void send(
            final HttpExchange exchange,
            final int status,
            final RailixValue value,
            final int limit,
            final String code,
            final String message
    ) throws IOException {
        final Optional<String> json;
        try {
            json = RailixJson.write(value, limit);
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

    private static void send(
            final HttpExchange exchange,
            final int status,
            final byte[] body
    ) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void send(
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

    private record ResultResponse(int status, RailixValue.ObjectValue value) {
    }
}
