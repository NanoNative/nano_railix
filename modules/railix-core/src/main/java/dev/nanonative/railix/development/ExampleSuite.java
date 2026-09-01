package dev.nanonative.railix.development;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.runtime.RunResult;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Generated-application execution and bounded retention of compiled development Examples. */
final class ExampleSuite implements AutoCloseable {
    private static final String RESOURCE = "/META-INF/railix/examples.json";
    private static final int MAX_RESOURCE_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES * 4;
    private static final int MAX_TRACE_EVENT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES * 4;
    static final int CHUNK_SIZE = 16;
    static final long MAX_EXAMPLE_BYTES = 64L * 1_024 * 1_024;
    static final long MAX_SUITE_BYTES = 256L * 1_024 * 1_024;
    private static final long CHUNK_SECONDS = 30;
    private static final long CLOSE_SECONDS = 5;
    private static final String CASE_STORAGE_MESSAGE = "Example trace exceeded the 64 MiB storage limit.";
    private static final String SUITE_STORAGE_MESSAGE =
            "Example traces exceeded the 256 MiB suite storage limit.";
    private static final String EXECUTION_FAILURE_MESSAGE =
            "Example execution failed before a terminal result.";
    private static final String EXECUTION_TIMEOUT_MESSAGE =
            "Example exceeded the 30-second execution limit.";
    private static final String EVENT_STORAGE_MESSAGE =
            "One development trace event exceeded 4194304 bytes.";
    private static final byte[] CASE_STORAGE_TERMINAL = terminal(
            "TRACE_CASE_STORAGE_LIMIT",
            CASE_STORAGE_MESSAGE
    );
    private static final byte[] SUITE_STORAGE_TERMINAL = terminal(
            "TRACE_SUITE_STORAGE_LIMIT",
            SUITE_STORAGE_MESSAGE
    );
    private static final byte[] EXECUTION_FAILURE_TERMINAL = terminal(
            "TRACE_EXECUTION_FAILED",
            EXECUTION_FAILURE_MESSAGE
    );
    private static final byte[] EXECUTION_TIMEOUT_TERMINAL = terminal(
            "TRACE_EXECUTION_TIMEOUT",
            EXECUTION_TIMEOUT_MESSAGE
    );
    private static final byte[] EVENT_STORAGE_TERMINAL = terminal(
            "TRACE_EVENT_TOO_LARGE",
            EVENT_STORAGE_MESSAGE
    );
    static final long TERMINAL_RESERVATION_BYTES = 1L + List.of(
            CASE_STORAGE_TERMINAL,
            SUITE_STORAGE_TERMINAL,
            EXECUTION_FAILURE_TERMINAL,
            EXECUTION_TIMEOUT_TERMINAL,
            EVENT_STORAGE_TERMINAL
    ).stream().mapToInt(value -> value.length).max().orElseThrow();
    private final DevelopmentRuntime.Application application;
    private final Path directory;
    private final List<ExampleCase> cases;
    private final Map<String, ExampleCase> casesById;
    private final int nodeCount;
    private final BitSet coverage;
    private final AtomicLong storageBytes = new AtomicLong();
    private final AtomicLong allocatedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService workers;
    private final Object lifecycle;
    private final Runnable abortApplication;
    private final Thread coordinator;
    private final Set<TraceReader> readers = new HashSet<>();
    private String state = "queued";
    private int completed;
    private int succeeded;
    private int failed;
    private int cancelled;
    private long revision;
    private boolean activated;
    private boolean unrecoverable;

    private ExampleSuite(
            final DevelopmentRuntime.Application application,
            final Path directory,
            final List<ExampleCase> cases,
            final int nodeCount,
            final Object lifecycle,
            final Runnable abortApplication
    ) throws IOException {
        this.application = application;
        this.directory = directory;
        this.cases = cases;
        this.cases.forEach(example -> example.trace = directory.resolve(example.trace));
        final Map<String, ExampleCase> indexed = new LinkedHashMap<>();
        this.cases.forEach(example -> indexed.put(example.id, example));
        casesById = Collections.unmodifiableMap(indexed);
        this.nodeCount = nodeCount;
        this.lifecycle = lifecycle;
        this.abortApplication = abortApplication;
        coverage = new BitSet(nodeCount);
        Files.createDirectories(directory);
        final ExecutorService createdWorkers = Executors.newVirtualThreadPerTaskExecutor();
        final Thread createdCoordinator;
        try {
            createdCoordinator = Thread.ofVirtual().name("railix-example-suite").unstarted(this::execute);
        } catch (final RuntimeException | Error failure) {
            shutdown(createdWorkers, failure);
            throw failure;
        }
        workers = createdWorkers;
        coordinator = createdCoordinator;
    }

    private static void shutdown(final ExecutorService executor, final Throwable failure) {
        try {
            executor.shutdownNow();
            executor.close();
        } catch (final RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    static ExampleSuite start(
            final DevelopmentRuntime.Application application,
            final Object lifecycle,
            final Runnable abortApplication,
            final Path directory
    ) throws IOException {
        final ProjectExamples project = examples();
        return new ExampleSuite(
                application,
                directory,
                project.cases(),
                project.nodeCount(),
                lifecycle,
                abortApplication
        );
    }

    static Path prepareRuntime() throws IOException {
        final Path directory = runtimeDirectory();
        Files.createDirectories(directory);
        return directory;
    }

    synchronized ExampleSuite activate() {
        if (closed.get()) {
            throw new IllegalStateException("Closed Example suite cannot be activated.");
        }
        if (activated) {
            return this;
        }
        try {
            coordinator.start();
            activated = true;
            return this;
        } catch (final RuntimeException | Error failure) {
            shutdown(workers, failure);
            throw failure;
        }
    }

    synchronized RailixValue.ObjectValue status() {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("state", RailixValue.string(state));
        value.put("revision", RailixValue.number(revision));
        value.put("application_pid", RailixValue.number(ProcessHandle.current().pid()));
        value.put("total", RailixValue.number(cases.size()));
        value.put("completed", RailixValue.number(completed));
        value.put("succeeded", RailixValue.number(succeeded));
        value.put("failed", RailixValue.number(failed));
        value.put("cancelled", RailixValue.number(cancelled));
        value.put("storage_bytes", RailixValue.number(storageBytes.get()));
        value.put("storage_limit_bytes", RailixValue.number(MAX_SUITE_BYTES));
        return RailixValue.object(value);
    }

    synchronized RailixValue.ObjectValue coverage() {
        return RailixValue.object(Map.of(
                "application_pid", RailixValue.number(ProcessHandle.current().pid()),
                "coverage_bits", RailixValue.string(Base64.getEncoder().encodeToString(coverage.toByteArray())),
                "covered_steps", RailixValue.number(coverage.cardinality()),
                "revision", RailixValue.number(revision)
        ));
    }

    synchronized RailixValue.ObjectValue inventory() {
        final List<RailixValue> values = new ArrayList<>(cases.size());
        for (final ExampleCase example : cases) {
            values.add(exampleValue(example));
        }
        final Map<String, RailixValue> value = new LinkedHashMap<>(status().values());
        value.putAll(coverage().values());
        value.put("cases", RailixValue.array(values));
        return RailixValue.object(value);
    }

    synchronized Optional<RailixValue.ObjectValue> example(final String id) {
        final ExampleCase example = casesById.get(id);
        return example == null ? Optional.empty() : Optional.of(exampleValue(example));
    }

    boolean containsNode(final int node) {
        return node >= 0 && node < nodeCount;
    }

    Optional<StepCasesSnapshot> snapshotStepCases(final int node) {
        if (!containsNode(node)) {
            return Optional.empty();
        }
        synchronized (this) {
            if (closed.get()) {
                return Optional.empty();
            }
            return Optional.of(new StepCasesSnapshot(
                    node,
                    revision,
                    cases.stream().map(ExampleSuite::snapshot).toList()
            ));
        }
    }

    Optional<RailixValue.ObjectValue> stepCases(
            final StepCasesSnapshot snapshot,
            final int maxBytes,
            final long maxReplayBytes
    ) throws IOException {
        requireReplayWithin(snapshot.cases, maxReplayBytes);
        final List<RailixValue> values = new ArrayList<>(snapshot.cases.size());
        final RailixValue.ObjectValue empty = stepCasesValue(snapshot.node, snapshot.revision, values);
        final String emptyJson = RailixJson.write(empty, maxBytes).orElse(null);
        if (emptyJson == null) {
            return Optional.empty();
        }
        int bytes = emptyJson.getBytes(StandardCharsets.UTF_8).length;
        for (final ExampleSnapshot example : snapshot.cases) {
            final Map<String, RailixValue> fields = new LinkedHashMap<>(example.value().values());
            if (example.observedContext() != null) {
                fields.put("initial_context", example.observedContext());
            }
            final int separator = values.isEmpty() ? 0 : 1;
            if (bytes + separator >= maxBytes
                    || RailixJson.write(
                    RailixValue.object(fields),
                    maxBytes - bytes - separator
            ).isEmpty()) {
                return Optional.empty();
            }
            if (example.trace() != null) {
                final InputStream trace;
                try {
                    trace = trace(example.trace()).orElseThrow(() ->
                            new ProjectionUnavailable(closed.get() ? "closed" : "trace"));
                } catch (final ProjectionUnavailable unavailable) {
                    throw unavailable;
                } catch (final IOException failure) {
                    throw new ProjectionUnavailable(closed.get() ? "closed" : "trace", failure);
                }
                try {
                    project(trace, snapshot.node, false)
                            .ifPresent(projection -> fields.put("projection", projection));
                } catch (final IOException failure) {
                    if (closed.get()) {
                        throw new ProjectionUnavailable("closed", failure);
                    }
                    throw failure;
                }
            }
            final RailixValue.ObjectValue value = RailixValue.object(fields);
            final String json = bytes + separator < maxBytes
                    ? RailixJson.write(value, maxBytes - bytes - separator).orElse(null)
                    : null;
            if (json == null) {
                return Optional.empty();
            }
            bytes += separator + json.getBytes(StandardCharsets.UTF_8).length;
            values.add(value);
        }
        return Optional.of(stepCasesValue(snapshot.node, snapshot.revision, values));
    }

    private static void requireReplayWithin(
            final List<ExampleSnapshot> cases,
            final long limit
    ) throws IOException {
        long bytes = 0;
        for (final ExampleSnapshot example : cases) {
            if (example.trace() == null) {
                continue;
            }
            if (example.traceBytes() > limit - bytes) {
                throw new ReplayLimitExceeded();
            }
            bytes += example.traceBytes();
        }
    }

    private static RailixValue.ObjectValue stepCasesValue(
            final int node,
            final long revision,
            final List<RailixValue> cases
    ) {
        return RailixValue.object(Map.of(
                "application_pid", RailixValue.number(ProcessHandle.current().pid()),
                "revision", RailixValue.number(revision),
                "node", RailixValue.number(node),
                "cases", RailixValue.array(cases)
        ));
    }

    private static RailixValue.ObjectValue exampleValue(final ExampleCase example) {
        synchronized (example) {
            return exampleValueLocked(example);
        }
    }

    private static ExampleSnapshot snapshot(final ExampleCase example) {
        synchronized (example) {
            return new ExampleSnapshot(
                    exampleValueLocked(example),
                    example.observedContext,
                    example.finished ? example.trace : null,
                    example.finished ? example.storageBytes : 0
            );
        }
    }

    private static RailixValue.ObjectValue exampleValueLocked(final ExampleCase example) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("id", RailixValue.string(example.id));
        value.put("trigger", RailixValue.string(example.trigger));
        value.put("name", RailixValue.string(example.name));
        value.put("index", RailixValue.number(example.index));
        value.put("status", RailixValue.string(example.status));
        value.put("events", RailixValue.number(example.events));
        value.put("storage_bytes", RailixValue.number(example.storageBytes));
        value.put("application_pid", RailixValue.number(ProcessHandle.current().pid()));
        if (!example.message.isEmpty()) {
            value.put("message", RailixValue.string(example.message));
        }
        return RailixValue.object(value);
    }

    Optional<InputStream> trace(
            final String id,
            final int selectedNode
    ) throws IOException {
        synchronized (this) {
            if (closed.get() || id == null || selectedNode >= nodeCount) {
                return Optional.empty();
            }
            final ExampleCase example = casesById.get(id);
            if (example == null) {
                return Optional.empty();
            }
            if (!example.finished || !Files.isRegularFile(example.trace)) {
                return Optional.empty();
            }
            return trace(example.trace);
        }
    }

    private Optional<InputStream> trace(final Path trace) throws IOException {
        synchronized (this) {
            if (closed.get() || trace == null) {
                return Optional.empty();
            }
            final TraceReader reader = new TraceReader(Files.newInputStream(trace), Thread.currentThread());
            readers.add(reader);
            return Optional.of(reader);
        }
    }

    /** Projects one trace and always closes its source stream. */
    static Optional<RailixValue.ObjectValue> project(
            final InputStream source,
            final int selectedNode,
            final boolean summary
    ) throws IOException {
        final Deque<TraceFrame> calls = new ArrayDeque<>();
        final Set<Integer> nodes = new LinkedHashSet<>();
        MutableContext context = null;
        RailixValue.ObjectValue initial = null;
        RailixValue.ObjectValue selectedResult = null;
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(source, StandardCharsets.UTF_8),
                16_384
        )) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Example view was interrupted.");
                }
                final RailixValue.ObjectValue event = event(line);
                switch (string(event, "type")) {
                    case "trace" -> {
                        if (initial != null || !(event.values().get("context")
                                instanceof RailixValue.ObjectValue traceContext)) {
                            throw new IOException("Development trace initial context is invalid.");
                        }
                        initial = traceContext;
                        context = new MutableContext(traceContext);
                    }
                    case "step_start" -> {
                        final long sequence = longInteger(event, "sequence");
                        final int occurrence = integer(event, "occurrence");
                        final String id = string(event, "id");
                        final int node = optionalInteger(event, "node");
                        if (node == selectedNode && context == null) {
                            throw new IOException("Development trace has no initial context.");
                        }
                        calls.push(new TraceFrame(
                                sequence,
                                occurrence,
                                id,
                                node,
                                event,
                                node == selectedNode ? context.snapshot() : null,
                                new ArrayList<>()
                        ));
                        if (node >= 0) {
                            nodes.add(node);
                        }
                    }
                    case "step_result" -> {
                        final TraceFrame call = calls.poll();
                        if (call == null
                                || call.sequence != longInteger(event, "sequence")
                                || call.occurrence != integer(event, "occurrence")
                                || !call.id.equals(string(event, "id"))) {
                            throw new IOException("Development trace Step nesting is invalid.");
                        }
                        if (context == null) {
                            throw new IOException("Development trace has no initial context.");
                        }
                        context.apply(event);
                        if (!summary && call.node == selectedNode) {
                            selectedResult = stepProjection(call, event, context.snapshot());
                        }
                        if (!summary && call.node < 0) {
                            final TraceFrame owner = calls.stream()
                                    .filter(frame -> frame.node == selectedNode)
                                    .findFirst()
                                    .orElse(null);
                            if (owner != null) {
                                owner.stages.add(stage(call.start, event));
                            }
                        }
                    }
                    case "result" -> {
                        if (!calls.isEmpty()) {
                            throw new IOException("Development trace completed with open Step records.");
                        }
                        if (summary) {
                            return Optional.of(summary(initial, nodes, event));
                        }
                        return Optional.ofNullable(selectedResult);
                    }
                    case "trace_error" -> {
                        if (summary) {
                            return Optional.of(summary(initial, nodes, event));
                        }
                        final TraceFrame selected = calls.stream()
                                .filter(frame -> frame.node == selectedNode)
                                .findFirst()
                                .orElse(null);
                        return selected == null
                                ? Optional.ofNullable(selectedResult)
                                : Optional.of(failedStepProjection(selected, event, context));
                    }
                    default -> throw new IOException("Development trace event type is invalid.");
                }
            }
            return Optional.empty();
        }
    }

    private static RailixValue.ObjectValue summary(
            final RailixValue.ObjectValue initial,
            final Set<Integer> nodes,
            final RailixValue.ObjectValue terminal
    ) throws IOException {
        if (initial == null) {
            throw new IOException("Development trace has no initial context.");
        }
        final List<RailixValue> reached = nodes.stream()
                .map(RailixValue::number)
                .map(RailixValue.class::cast)
                .toList();
        return RailixValue.object(Map.of(
                "application_pid", RailixValue.number(ProcessHandle.current().pid()),
                "initial_context", initial,
                "nodes", RailixValue.array(reached),
                "result", without(terminal, Set.of("type"))
        ));
    }

    private static RailixValue.ObjectValue stepProjection(
            final TraceFrame call,
            final RailixValue.ObjectValue result,
            final RailixValue.ObjectValue context
    ) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        copy(value, call.start, Set.of("type", "node"));
        copy(value, result, Set.of("type", "changes"));
        value.put("application_pid", RailixValue.number(ProcessHandle.current().pid()));
        value.put("input_context", call.inputContext);
        value.put("context", context);
        value.put("stages", RailixValue.array(call.stages));
        return RailixValue.object(value);
    }

    private static RailixValue.ObjectValue failedStepProjection(
            final TraceFrame call,
            final RailixValue.ObjectValue failure,
            final MutableContext context
    ) throws IOException {
        if (context == null) {
            throw new IOException("Development trace has no initial context.");
        }
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        copy(value, call.start, Set.of("type", "node"));
        copy(value, failure, Set.of("type"));
        value.put("application_pid", RailixValue.number(ProcessHandle.current().pid()));
        value.put("input_context", call.inputContext);
        value.put("context", context.snapshot());
        value.put("stages", RailixValue.array(call.stages));
        return RailixValue.object(value);
    }

    private static RailixValue.ObjectValue stage(
            final RailixValue.ObjectValue start,
            final RailixValue.ObjectValue result
    ) {
        final String id = string(start, "id");
        final int inputStart = id.lastIndexOf(".inputs.");
        final String path = inputStart < 0 ? "" : id.substring(inputStart + 8);
        final int itemStart = path.endsWith("]") ? path.lastIndexOf('[') : -1;
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("input", RailixValue.string(itemStart < 0 ? path : path.substring(0, itemStart)));
        value.put("invocation", start.values().getOrDefault("use", RailixValue.string(id)));
        value.put("status", result.values().getOrDefault(
                "outcome",
                result.values().getOrDefault("status", RailixValue.string("failed"))
        ));
        if (result.values().get("inputs") instanceof RailixValue.ObjectValue inputs) {
            value.put("inputs", inputs);
        }
        final RailixValue outputs = result.values().getOrDefault("returns", result.values().get("writes"));
        if (outputs instanceof RailixValue.ObjectValue object && !object.values().isEmpty()) {
            value.put("value", object.values().values().iterator().next());
        }
        return RailixValue.object(value);
    }

    private static RailixValue.ObjectValue without(
            final RailixValue.ObjectValue source,
            final Set<String> omitted
    ) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        copy(value, source, omitted);
        return RailixValue.object(value);
    }

    private static void copy(
            final Map<String, RailixValue> target,
            final RailixValue.ObjectValue source,
            final Set<String> omitted
    ) {
        source.values().forEach((name, value) -> {
            if (!omitted.contains(name)) {
                target.put(name, value);
            }
        });
    }

    private static RailixValue.ObjectValue event(final String line) throws IOException {
        final RailixJson.Result parsed = RailixJson.parse(line);
        if (parsed instanceof RailixJson.Parsed value
                && value.value() instanceof RailixValue.ObjectValue event) {
            return event;
        }
        throw new IOException("Development trace contains invalid JSON.");
    }

    private static int integer(final RailixValue.ObjectValue owner, final String name) throws IOException {
        if (owner.values().get(name) instanceof RailixValue.NumberValue value) {
            try {
                return value.value().intValueExact();
            } catch (final ArithmeticException exception) {
                throw new IOException("Development trace field '" + name + "' is invalid.", exception);
            }
        }
        throw new IOException("Development trace field '" + name + "' is invalid.");
    }

    private static int optionalInteger(final RailixValue.ObjectValue owner, final String name) throws IOException {
        return owner.values().containsKey(name) ? integer(owner, name) : -1;
    }

    private static long longInteger(final RailixValue.ObjectValue owner, final String name) throws IOException {
        if (owner.values().get(name) instanceof RailixValue.NumberValue value) {
            try {
                return value.value().longValueExact();
            } catch (final ArithmeticException exception) {
                throw new IOException("Development trace field '" + name + "' is invalid.", exception);
            }
        }
        throw new IOException("Development trace field '" + name + "' is invalid.");
    }

    private void execute() {
        synchronized (this) {
            state = cases.isEmpty() ? "completed" : "running";
            revision++;
        }
        try {
            for (int start = 0; start < cases.size() && !closed.get(); start += CHUNK_SIZE) {
                final int end = Math.min(cases.size(), start + CHUNK_SIZE);
                final List<ExampleCase> prepared = new ArrayList<>(end - start);
                for (int index = start; index < end; index++) {
                    final ExampleCase example = cases.get(index);
                    if (prepare(example)) {
                        prepared.add(example);
                    }
                }
                final List<Running> running = new ArrayList<>(prepared.size());
                for (final ExampleCase example : prepared) {
                    final CountDownLatch exited = new CountDownLatch(1);
                    running.add(new Running(example, workers.submit(() -> {
                        try {
                            execute(example);
                        } finally {
                            exited.countDown();
                        }
                    }), exited));
                }
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHUNK_SECONDS);
                boolean timedOut = false;
                for (final Running item : running) {
                    try {
                        final long remaining = deadline - System.nanoTime();
                        item.future.get(remaining, TimeUnit.NANOSECONDS);
                    } catch (final InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        running.forEach(this::cancel);
                        return;
                    } catch (final TimeoutException exception) {
                        timedOut = true;
                        break;
                    } catch (final CancellationException | ExecutionException ignored) {
                        // Each case records its own deterministic failure state.
                    }
                }
                if (timedOut) {
                    running.stream().filter(Running::active).forEach(active -> {
                        if (timeout(active.example)) {
                            cancel(active);
                        }
                    });
                }
                if (!awaitExit(running)) {
                    return;
                }
            }
        } finally {
            workers.shutdownNow();
            synchronized (this) {
                if (closed.get()) {
                    cancelQueued();
                    state = "cancelled";
                } else if (!unrecoverable) {
                    state = "completed";
                }
                revision++;
            }
        }
    }

    private void execute(final ExampleCase example) {
        final RailixValue.ObjectValue context;
        synchronized (this) {
            if (closed.get()) {
                cancel(example);
                return;
            }
            synchronized (example) {
                example.status = "running";
                example.coverage = new BitSet();
                example.coverage.set(example.triggerIndex);
                context = example.context;
            }
            revision++;
        }
        String status = "failed";
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(
                example.trace,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        ), 16_384)) {
            try {
                final RunResult result = application.trace(
                        example.trigger,
                        context,
                        true,
                        event -> store(example, file, event)
                );
                synchronized (example) {
                    status = example.forcedFailure != null
                            ? example.forcedFailure.status
                            : example.terminal
                            ? example.status
                            : result instanceof RunResult.Cancelled || Thread.currentThread().isInterrupted()
                            ? "cancelled"
                            : "failed";
                }
            } catch (final RuntimeException exception) {
                message(example, exception.getMessage());
                status = Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
            } finally {
                final boolean terminal;
                final ForcedFailure forced;
                synchronized (example) {
                    terminal = example.terminal;
                    forced = example.forcedFailure;
                }
                if (!terminal && !closed.get()) {
                    if (forced == null) {
                        message(example, EXECUTION_FAILURE_MESSAGE);
                        storeReservedTerminal(example, file, EXECUTION_FAILURE_TERMINAL, "failed");
                        status = "failed";
                    } else {
                        storeReservedTerminal(example, file, forced.event, forced.status);
                        status = forced.status;
                    }
                }
            }
        } catch (final IOException | RuntimeException exception) {
            message(example, exception.getMessage());
            status = Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
        } finally {
            reconcile(example);
            finish(example, status);
        }
    }

    private boolean store(
            final ExampleCase example,
            final OutputStream file,
            final RailixValue.ObjectValue event
    ) throws IOException {
        final String json = RailixJson.write(event, MAX_TRACE_EVENT_BYTES).orElse(null);
        if (json == null) {
            synchronized (example) {
                if (!example.terminal && example.terminalReserved) {
                    example.message = EVENT_STORAGE_MESSAGE;
                    storeReservedTerminal(example, file, EVENT_STORAGE_TERMINAL, "failed");
                }
            }
            return false;
        }
        final byte[] source = json.getBytes(StandardCharsets.UTF_8);
        final long bytes = source.length + 1L;
        synchronized (example) {
            if (example.finished || example.terminal || example.forcedFailure != null || closed.get()) {
                return false;
            }
            final String type = string(event, "type");
            if ("result".equals(type) || "trace_error".equals(type)) {
                return storeTerminal(example, file, source, event, bytes, type);
            }
            if (bytes > MAX_EXAMPLE_BYTES - TERMINAL_RESERVATION_BYTES - example.storageBytes) {
                return storeStorageFailure(example, file, CASE_STORAGE_TERMINAL, CASE_STORAGE_MESSAGE);
            }
            if (!allocate(example, bytes)) {
                return storeStorageFailure(example, file, SUITE_STORAGE_TERMINAL, SUITE_STORAGE_MESSAGE);
            }
            writeAllocated(example, file, source, bytes);
            if ("trace".equals(type)
                    && event.values().get("context") instanceof RailixValue.ObjectValue context) {
                example.observedContext = context;
            } else if ("step_start".equals(type)) {
                recordCoverage(example, event);
            }
            return true;
        }
    }

    private boolean storeTerminal(
            final ExampleCase example,
            final OutputStream file,
            final byte[] source,
            final RailixValue.ObjectValue event,
            final long bytes,
            final String type
    ) throws IOException {
        final String status = "result".equals(type) ? string(event, "status") : "failed";
        final String failure = "trace_error".equals(type) ? string(event, "message") : "";
        if (bytes > MAX_EXAMPLE_BYTES - example.storageBytes) {
            return storeStorageFailure(example, file, CASE_STORAGE_TERMINAL, CASE_STORAGE_MESSAGE);
        }
        final long additional = Math.max(0, bytes - TERMINAL_RESERVATION_BYTES);
        if (!allocate(example, additional)) {
            return storeStorageFailure(example, file, SUITE_STORAGE_TERMINAL, SUITE_STORAGE_MESSAGE);
        }
        writeAllocated(example, file, source, bytes);
        releaseAllocation(example, Math.max(0, TERMINAL_RESERVATION_BYTES - bytes));
        example.terminalReserved = false;
        example.terminal = true;
        example.status = status;
        message(example, failure);
        return true;
    }

    private boolean storeStorageFailure(
            final ExampleCase example,
            final OutputStream file,
            final byte[] terminal,
            final String failure
    ) throws IOException {
        example.message = failure;
        storeReservedTerminal(example, file, terminal, "failed");
        return false;
    }

    private void storeReservedTerminal(
            final ExampleCase example,
            final OutputStream file,
            final byte[] terminal,
            final String status
    ) throws IOException {
        synchronized (example) {
            if (example.terminal || !example.terminalReserved) {
                return;
            }
            final long bytes = terminal.length + 1L;
            file.write(terminal);
            file.write('\n');
            storageBytes.addAndGet(bytes);
            example.storageBytes += bytes;
            example.events++;
            releaseAllocation(example, TERMINAL_RESERVATION_BYTES - bytes);
            example.terminalReserved = false;
            example.terminal = true;
            example.status = status;
        }
    }

    private void writeAllocated(
            final ExampleCase example,
            final OutputStream file,
            final byte[] source,
            final long bytes
    ) throws IOException {
        file.write(source);
        file.write('\n');
        storageBytes.addAndGet(bytes);
        example.storageBytes += bytes;
        example.events++;
    }

    private void recordCoverage(
            final ExampleCase example,
            final RailixValue.ObjectValue event
    ) {
        final RailixValue value = event.values().get("node");
        if (!(value instanceof RailixValue.NumberValue number)) {
            return;
        }
        final int node = number.value().intValueExact();
        if (node >= 0 && node < nodeCount) {
            example.coverage.set(node);
        }
    }

    private boolean allocate(final ExampleCase example, final long bytes) {
        long current = allocatedBytes.get();
        while (bytes <= MAX_SUITE_BYTES - current) {
            if (allocatedBytes.compareAndSet(current, current + bytes)) {
                example.allocatedBytes += bytes;
                return true;
            }
            current = allocatedBytes.get();
        }
        return false;
    }

    private void releaseAllocation(final ExampleCase example, final long bytes) {
        if (bytes > 0) {
            allocatedBytes.addAndGet(-bytes);
            example.allocatedBytes -= bytes;
        }
    }

    private boolean prepare(final ExampleCase example) {
        final boolean allocated;
        synchronized (example) {
            allocated = allocate(example, TERMINAL_RESERVATION_BYTES);
            if (allocated) {
                example.terminalReserved = true;
            }
        }
        if (!allocated) {
            message(example, SUITE_STORAGE_MESSAGE);
            finish(example, "failed");
            return false;
        }
        if (closed.get()) {
            cancel(example);
            return false;
        }
        return true;
    }

    private boolean timeout(final ExampleCase example) {
        synchronized (this) {
            synchronized (example) {
                if (example.finished) {
                    return false;
                }
                example.forcedFailure = new ForcedFailure(
                        "timed-out",
                        EXECUTION_TIMEOUT_TERMINAL
                );
                example.status = "timed-out";
                example.message = EXECUTION_TIMEOUT_MESSAGE;
            }
            revision++;
            return true;
        }
    }

    private void reconcile(final ExampleCase example) {
        try {
            final long physical = Files.isRegularFile(example.trace) ? Files.size(example.trace) : 0;
            synchronized (example) {
                storageBytes.addAndGet(physical - example.storageBytes);
                example.storageBytes = physical;
                final long retained = physical + (example.terminalReserved ? TERMINAL_RESERVATION_BYTES : 0);
                allocatedBytes.addAndGet(retained - example.allocatedBytes);
                example.allocatedBytes = retained;
            }
        } catch (final IOException exception) {
            message(example, exception.getMessage());
        }
    }

    private void finish(final ExampleCase example, final String status) {
        synchronized (this) {
            synchronized (example) {
                if (example.finished) {
                    return;
                }
                if (example.terminalReserved) {
                    releaseAllocation(example, TERMINAL_RESERVATION_BYTES);
                    example.terminalReserved = false;
                }
                example.finished = true;
                example.status = status;
                if (example.coverage == null && example.triggerIndex >= 0 && example.triggerIndex < nodeCount) {
                    coverage.set(example.triggerIndex);
                } else if (example.coverage != null) {
                    coverage.or(example.coverage);
                }
                example.coverage = null;
                example.context = null;
            }
            completed++;
            switch (status) {
                case "succeeded" -> succeeded++;
                case "cancelled" -> cancelled++;
                default -> failed++;
            }
            if (completed == cases.size() && !closed.get()) {
                state = "completed";
            }
            revision++;
        }
    }

    private static void message(final ExampleCase example, final String failure) {
        if (failure == null || failure.isBlank()) {
            return;
        }
        synchronized (example) {
            if (example.message.isEmpty()) {
                example.message = failure;
            }
        }
    }

    private void cancel(final ExampleCase example) {
        finish(example, "cancelled");
    }

    private void cancel(final Running running) {
        running.future.cancel(true);
    }

    private boolean awaitExit(final List<Running> running) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_SECONDS);
        for (final Running execution : running) {
            try {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !execution.exited.await(remaining, TimeUnit.NANOSECONDS)) {
                    abort();
                    return false;
                }
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                running.forEach(this::cancel);
                return false;
            }
        }
        return true;
    }

    private void abort() {
        synchronized (lifecycle) {
            synchronized (this) {
                if (unrecoverable || closed.get()) {
                    return;
                }
                unrecoverable = true;
                state = "failed";
                revision++;
            }
            abortApplication.run();
        }
    }

    private void cancelQueued() {
        cases.stream().filter(example -> !example.finished).forEach(this::cancel);
    }

    private synchronized void releaseTrace(final TraceReader reader) {
        readers.remove(reader);
    }

    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        IllegalStateException failure = null;
        closed.compareAndSet(false, true);
        coordinator.interrupt();
        workers.shutdownNow();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_SECONDS);
        IOException readerFailure = null;
        final List<TraceReader> activeReaders;
        synchronized (this) {
            activeReaders = List.copyOf(readers);
        }
        for (final TraceReader reader : activeReaders) {
            try {
                reader.revoke();
            } catch (final IOException exception) {
                if (readerFailure == null) {
                    readerFailure = exception;
                } else {
                    readerFailure.addSuppressed(exception);
                }
            }
        }
        while (coordinator.isAlive()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                coordinator.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (coordinator.isAlive()) {
            failure = merge(failure, new IllegalStateException(
                    "Example suite did not stop within five seconds."
            ));
        }
        while (!workers.isTerminated()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (!workers.isTerminated()) {
            failure = merge(failure, new IllegalStateException(
                    "Example workers did not stop within five seconds."
            ));
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (readerFailure != null) {
            failure = merge(failure, new IllegalStateException(
                    "Example trace readers could not be revoked.", readerFailure
            ));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IllegalStateException merge(
            final IllegalStateException failure,
            final IllegalStateException next
    ) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private final class TraceReader extends FilterInputStream {
        private final Thread readerThread;
        private boolean released;

        private TraceReader(final InputStream source, final Thread readerThread) {
            super(source);
            this.readerThread = readerThread;
        }

        @Override
        public synchronized void close() throws IOException {
            release(false);
        }

        private synchronized void revoke() throws IOException {
            release(true);
        }

        private void release(final boolean revoke) throws IOException {
            if (released) {
                return;
            }
            released = true;
            try {
                if (revoke) {
                    readerThread.interrupt();
                }
                super.close();
            } finally {
                releaseTrace(this);
            }
        }
    }

    private static ProjectExamples examples() throws IOException {
        try (InputStream source = ExampleSuite.class.getResourceAsStream(RESOURCE)) {
            if (source == null) {
                throw new IOException("Compiled development Example manifest is missing.");
            }
            final byte[] bytes = source.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IOException("Compiled development Examples exceed 4194304 bytes.");
            }
            final RailixData.Result normalized = RailixData.normalize(
                    RailixData.Format.JSON,
                    bytes,
                    MAX_RESOURCE_BYTES,
                    RailixData.DEFAULT_MAX_DEPTH
            );
            if (!(normalized instanceof RailixData.Normalized data)
                    || !(data.value() instanceof RailixValue.ObjectValue resource)
                    || !(resource.values().get("format") instanceof RailixValue.NumberValue formatValue)
                    || !(resource.values().get("node_count") instanceof RailixValue.NumberValue nodeCountValue)
                    || !(resource.values().get("examples") instanceof RailixValue.ArrayValue values)) {
                throw new IOException("Compiled development Examples are invalid.");
            }
            final int nodeCount;
            try {
                if (formatValue.value().intValueExact() != 1) {
                    throw new IOException("Compiled development Example format is unsupported.");
                }
                nodeCount = nodeCountValue.value().intValueExact();
            } catch (final ArithmeticException exception) {
                throw new IOException("Compiled development Example node count is invalid.", exception);
            }
            if (nodeCount < 0) {
                throw new IOException("Compiled development Example node count is invalid.");
            }
            final List<ExampleCase> examples = new ArrayList<>(values.values().size());
            final Set<String> identifiers = new HashSet<>();
            for (int position = 0; position < values.values().size(); position++) {
                if (!(values.values().get(position) instanceof RailixValue.ObjectValue example)
                        || !(example.values().get("context") instanceof RailixValue.ObjectValue context)
                        || !(example.values().get("id") instanceof RailixValue.StringValue idValue)
                        || !(example.values().get("trigger") instanceof RailixValue.StringValue triggerValue)
                        || !(example.values().get("name") instanceof RailixValue.StringValue nameValue)
                        || !(example.values().get("index") instanceof RailixValue.NumberValue indexValue)
                        || !(example.values().get("node") instanceof RailixValue.NumberValue nodeValue)) {
                    throw new IOException("Compiled development Example is invalid.");
                }
                try {
                    final int index = indexValue.value().intValueExact();
                    final int node = nodeValue.value().intValueExact();
                    if (idValue.value().isBlank() || triggerValue.value().isBlank()
                            || index < 0 || node < 0 || node >= nodeCount
                            || !identifiers.add(idValue.value())) {
                        throw new IOException("Compiled development Example is invalid.");
                    }
                    examples.add(new ExampleCase(
                            idValue.value(),
                            triggerValue.value(),
                            nameValue.value(),
                            index,
                            context,
                            Path.of(position + ".ndjson"),
                            node
                    ));
                } catch (final ArithmeticException exception) {
                    throw new IOException("Compiled development Example is invalid.", exception);
                }
            }
            return new ProjectExamples(List.copyOf(examples), nodeCount);
        }
    }

    private static String string(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.StringValue) value.values().get(name)).value();
    }

    private static byte[] terminal(final String code, final String message) {
        final byte[] value = RailixJson.write(RailixValue.object(Map.of(
                "code", RailixValue.string(code),
                "message", RailixValue.string(message),
                "status", RailixValue.string("failed"),
                "type", RailixValue.string("trace_error")
        ))).getBytes(StandardCharsets.UTF_8);
        return value;
    }

    private static Path runtimeDirectory() throws IOException {
        final var source = ExampleSuite.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("Development application location is unavailable.");
        }
        try {
            final Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            final Path owner = Files.isRegularFile(location) ? location.getParent() : location;
            if (owner == null) {
                throw new IOException("Development application location has no owner directory.");
            }
            return owner.resolve(".railix-runtime").resolve(Long.toString(ProcessHandle.current().pid()));
        } catch (final URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Development application location is invalid.", exception);
        }
    }

    static void deleteRuntime(final Path directory) throws IOException {
        delete(directory);
        try {
            Files.deleteIfExists(directory.getParent());
        } catch (final DirectoryNotEmptyException ignoredSiblingProcess) {
            // Another development process owns a sibling directory.
        }
    }

    private static void delete(final Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    final Path path,
                    final IOException failure
            ) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class ExampleCase {
        private final String id;
        private final String trigger;
        private final String name;
        private final int index;
        private RailixValue.ObjectValue context;
        private RailixValue.ObjectValue observedContext;
        private Path trace;
        private final int triggerIndex;
        private BitSet coverage;
        private String status = "queued";
        private String message = "";
        private int events;
        private long storageBytes;
        private long allocatedBytes;
        private boolean terminal;
        private boolean terminalReserved;
        private boolean finished;
        private ForcedFailure forcedFailure;

        private ExampleCase(
                final String id,
                final String trigger,
                final String name,
                final int index,
                final RailixValue.ObjectValue context,
                final Path trace,
                final int triggerIndex
        ) {
            this.id = id;
            this.trigger = trigger;
            this.name = name;
            this.index = index;
            this.context = context;
            this.trace = trace;
            this.triggerIndex = triggerIndex;
        }
    }

    static final class ReplayLimitExceeded extends IOException {
        private ReplayLimitExceeded() {
            super("Application Step Example replay exceeds its source-byte limit.");
        }
    }

    static final class ProjectionUnavailable extends IOException {
        private final String reason;

        private ProjectionUnavailable(final String reason) {
            this(reason, null);
        }

        private ProjectionUnavailable(final String reason, final IOException cause) {
            super("Application Step Example projection is unavailable.", cause);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    private record TraceFrame(
            long sequence,
            int occurrence,
            String id,
            int node,
            RailixValue.ObjectValue start,
            RailixValue.ObjectValue inputContext,
            List<RailixValue> stages
    ) {
    }

    private static final class MutableContext {
        private final Map<String, Object> value;

        private MutableContext(final RailixValue.ObjectValue source) {
            value = mutableObject(source);
        }

        private RailixValue.ObjectValue snapshot() {
            return freezeObject(value);
        }

        private void apply(final RailixValue.ObjectValue event) throws IOException {
            if (!(event.values().get("changes") instanceof RailixValue.ArrayValue changes)) {
                return;
            }
            for (final RailixValue change : changes.values()) {
                apply(change, false);
            }
            for (int index = changes.values().size() - 1; index >= 0; index--) {
                apply(changes.values().get(index), true);
            }
        }

        private void apply(final RailixValue source, final boolean removals) throws IOException {
            if (!(source instanceof RailixValue.ObjectValue change)
                    || !(change.values().get("kind") instanceof RailixValue.StringValue kind)
                    || !(change.values().get("path") instanceof RailixValue.ArrayValue path)
                    || path.values().size() < 2
                    || !(path.values().getFirst() instanceof RailixValue.StringValue root)
                    || !"context".equals(root.value())) {
                throw new IOException("Development trace context change is invalid.");
            }
            final boolean removed = "removed".equals(kind.value());
            if (removed != removals) {
                return;
            }
            Object owner = value;
            for (int index = 1; index < path.values().size() - 1; index++) {
                owner = child(owner, path.values().get(index));
            }
            final RailixValue field = path.values().getLast();
            if (owner instanceof Map<?, ?> map && field instanceof RailixValue.StringValue name) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> object = (Map<String, Object>) map;
                if (removed) {
                    object.remove(name.value());
                } else {
                    final RailixValue after = change.values().get("after");
                    if (after == null) {
                        throw new IOException("Development trace context change has no result value.");
                    }
                    object.put(name.value(), mutable(after));
                }
                return;
            }
            if (owner instanceof List<?> list && field instanceof RailixValue.NumberValue number) {
                final int index = exactIndex(number);
                @SuppressWarnings("unchecked")
                final List<Object> array = (List<Object>) list;
                if (removed) {
                    if (index >= array.size()) {
                        throw new IOException("Development trace array removal is invalid.");
                    }
                    array.remove(index);
                } else {
                    final RailixValue after = change.values().get("after");
                    if (after == null || index > array.size()) {
                        throw new IOException("Development trace array change is invalid.");
                    }
                    if (index == array.size()) {
                        array.add(mutable(after));
                    } else {
                        array.set(index, mutable(after));
                    }
                }
                return;
            }
            throw new IOException("Development trace context change path is invalid.");
        }

        private static Object child(final Object owner, final RailixValue part) throws IOException {
            if (owner instanceof Map<?, ?> map && part instanceof RailixValue.StringValue name) {
                final Object child = map.get(name.value());
                if (child != null) {
                    return child;
                }
            } else if (owner instanceof List<?> list && part instanceof RailixValue.NumberValue number) {
                final int index = exactIndex(number);
                if (index < list.size()) {
                    return list.get(index);
                }
            }
            throw new IOException("Development trace context change path is invalid.");
        }

        private static int exactIndex(final RailixValue.NumberValue number) throws IOException {
            try {
                final int index = number.value().intValueExact();
                if (index >= 0) {
                    return index;
                }
            } catch (final ArithmeticException ignored) {
                // Report one stable trace diagnostic below.
            }
            throw new IOException("Development trace array index is invalid.");
        }

        private static Map<String, Object> mutableObject(final RailixValue.ObjectValue source) {
            final Map<String, Object> result = new LinkedHashMap<>();
            source.values().forEach((name, child) -> result.put(name, mutable(child)));
            return result;
        }

        private static Object mutable(final RailixValue source) {
            if (source instanceof RailixValue.ObjectValue object) {
                return mutableObject(object);
            }
            if (source instanceof RailixValue.ArrayValue array) {
                final List<Object> result = new ArrayList<>(array.values().size());
                array.values().forEach(child -> result.add(mutable(child)));
                return result;
            }
            return source;
        }

        private static RailixValue.ObjectValue freezeObject(final Map<String, Object> source) {
            final Map<String, RailixValue> result = new LinkedHashMap<>();
            source.forEach((name, child) -> result.put(name, freeze(child)));
            return RailixValue.object(result);
        }

        private static RailixValue freeze(final Object source) {
            if (source instanceof Map<?, ?> map) {
                final Map<String, RailixValue> result = new LinkedHashMap<>();
                map.forEach((name, child) -> result.put((String) name, freeze(child)));
                return RailixValue.object(result);
            }
            if (source instanceof List<?> list) {
                return RailixValue.array(list.stream().map(MutableContext::freeze).toList());
            }
            return (RailixValue) source;
        }
    }

    private record ForcedFailure(String status, byte[] event) {
    }

    private record Running(ExampleCase example, Future<?> future, CountDownLatch exited) {
        private boolean active() {
            return exited.getCount() != 0;
        }
    }

    static final class StepCasesSnapshot {
        private final int node;
        private final long revision;
        private final List<ExampleSnapshot> cases;

        private StepCasesSnapshot(final int node, final long revision, final List<ExampleSnapshot> cases) {
            this.node = node;
            this.revision = revision;
            this.cases = cases;
        }
    }

    private record ExampleSnapshot(
            RailixValue.ObjectValue value,
            RailixValue.ObjectValue observedContext,
            Path trace,
            long traceBytes
    ) {
    }

    private record ProjectExamples(List<ExampleCase> cases, int nodeCount) {
    }
}
