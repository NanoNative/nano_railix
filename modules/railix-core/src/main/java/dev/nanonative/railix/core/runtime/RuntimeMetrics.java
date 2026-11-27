package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.step.StepInput;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Dense application, flow, and graph-Step metrics owned by one generated application.
 * Execution, error, and cancellation counters are exact. Flow timing is exact; graph-Step timing
 * samples one execution out of every 1,024 to keep the per-Step hot path bounded. Recording allocates
 * no request objects. Snapshots and text exports allocate only when read. Selected-node reads use a
 * fixed open-addressed index; exported storage fields report exact primitive counter and index bytes,
 * not JVM-dependent object or shared identifier storage.
 */
public final class RuntimeMetrics {
    private static final int MAX_QUERY_GROUPS = 4_096;
    private static final int MAX_QUERY_MEMBERS = 1_048_576;

    /**
     * Reads an object used by a bounded observation query.
     * @param value canonical query object
     * @return its immutable fields
     * @throws IllegalArgumentException for a non-object value
     */
    public static Map<String, RailixValue> queryObject(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue object)) {
            throw new IllegalArgumentException("Query groups must be objects.");
        }
        return object.values();
    }

    /**
     * Reads a nonblank observation identifier.
     * @param value canonical identifier value
     * @return the unchanged identifier
     * @throws IllegalArgumentException for an absent or invalid identifier
     */
    public static String queryIdentifier(final RailixValue value) {
        if (!(value instanceof RailixValue.StringValue string) || string.value().isBlank()) {
            throw new IllegalArgumentException("Query identifiers must be non-blank strings.");
        }
        return string.value();
    }

    /**
     * Rejects unknown observation query fields.
     * @param query query object to validate
     * @param allowed supported field names
     * @return the unchanged query
     * @throws IllegalArgumentException for an unsupported field
     */
    public static RailixValue.ObjectValue queryFields(final RailixValue.ObjectValue query, final Set<String> allowed) {
        if (!allowed.containsAll(query.values().keySet())) {
            throw new IllegalArgumentException("Query contains unsupported fields.");
        }
        return query;
    }

    /**
     * Reads ordered, bounded ordinal ranges.
     * @param value groups of inclusive ordinal ranges
     * @param bound exclusive ordinal bound
     * @return validated ranges by group identifier
     * @throws IllegalArgumentException for invalid ranges or exceeded query limits
     */
    public static Map<String, int[]> queryRanges(final RailixValue value, final int bound) {
        final Map<String, RailixValue> groups = queryObject(value);
        if (groups.size() > MAX_QUERY_GROUPS) {
            throw new IllegalArgumentException("Query exceeds the 4096-group limit.");
        }
        final Map<String, int[]> result = new LinkedHashMap<>();
        long members = 0;
        for (final var group : groups.entrySet()) {
            if (group.getKey().isBlank() || !(group.getValue() instanceof RailixValue.ArrayValue ranges)) {
                throw new IllegalArgumentException("Query range groups require non-blank identifiers and arrays.");
            }
            final int[] intervals = new int[ranges.values().size() * 2];
            int offset = 0;
            int previous = -1;
            for (final RailixValue range : ranges.values()) {
                if (!(range instanceof RailixValue.ArrayValue interval) || interval.values().size() != 2
                        || !(interval.values().getFirst() instanceof RailixValue.NumberValue first)
                        || !(interval.values().getLast() instanceof RailixValue.NumberValue last)) {
                    throw new IllegalArgumentException("Query ranges must contain exactly two integer ordinals.");
                }
                final int start;
                final int end;
                try {
                    start = first.value().intValueExact();
                    end = last.value().intValueExact();
                } catch (final ArithmeticException invalid) {
                    throw new IllegalArgumentException("Query ranges must contain integer ordinals.", invalid);
                }
                if (start <= previous || end < start || end >= bound) {
                    throw new IllegalArgumentException("Query ranges must be sorted, nonoverlapping, and within ordinal bounds.");
                }
                members += (long) end - start + 1;
                if (members > MAX_QUERY_MEMBERS) {
                    throw new IllegalArgumentException("Query ranges exceed the 1048576-member work limit.");
                }
                intervals[offset++] = start;
                intervals[offset++] = end;
                previous = end;
            }
            result.put(group.getKey(), intervals);
        }
        return result;
    }


    private static final int EXECUTIONS = 0;
    private static final int ERRORS = 1;
    private static final int CANCELLED = 2;
    private static final int DURATION_TOTAL = 3;
    private static final int DURATION_MAX = 4;
    private static final int DURATION_SAMPLES = 5;
    private static final int STRIDE = 6;
    private static final int FIRST_REGISTERED_VALUE = 7;
    private static final long[] NO_REGISTERED_COUNTS = new long[0];
    private static final long UNSAMPLED = Long.MIN_VALUE;
    private static final long STEP_SAMPLE_MASK = 1_023;
    private static final String APPLICATION = "application";
    private static final String FLOW = "flow";
    private static final String STEP = "step";
    private static final String PROCESS = "process";
    private static final MetricDescriptor[] METRICS = {
            new MetricDescriptor("executions", "Executions", "count", "counter", "sum", 0,
                    "executions_total", false, 0, null, APPLICATION, FLOW, STEP),
            new MetricDescriptor("errors", "Errors", "count", "counter", "sum", 1,
                    "errors_total", false, 0, null, APPLICATION, FLOW, STEP),
            new MetricDescriptor("cancelled", "Cancelled runs", "count", "counter", "sum", 2,
                    "cancelled_total", false, 0, null, APPLICATION, FLOW, STEP),
            new MetricDescriptor("in_flight", "In-flight runs", "count", "gauge", "sum", 3,
                    "in_flight", false, 0, null, APPLICATION, FLOW),
            new MetricDescriptor("duration_nanos_total", "Duration total", "ns", "counter", "sum", 4,
                    "duration_seconds_sum", true, 1_024, "duration_samples", APPLICATION, FLOW, STEP),
            new MetricDescriptor("duration_nanos_max", "Duration maximum", "ns", "gauge", "max", 5,
                    "duration_seconds_max", true, 1_024, "duration_samples", APPLICATION, FLOW, STEP),
            new MetricDescriptor("duration_samples", "Sampled durations", "count", "counter", "sum", 6,
                    "duration_seconds_count", false, 1_024, null, APPLICATION, FLOW, STEP),
            new MetricDescriptor("uptime_millis", "Process uptime", "ms", "gauge", "none", -1,
                    "uptime_millis", false, 0, null, PROCESS),
            new MetricDescriptor("heap_used_bytes", "Heap used", "bytes", "gauge", "none", -1,
                    "heap_used_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("heap_committed_bytes", "Heap committed", "bytes", "gauge", "none", -1,
                    "heap_committed_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("heap_max_bytes", "Heap maximum", "bytes", "gauge", "none", -1,
                    "heap_max_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("non_heap_used_bytes", "Non-heap used", "bytes", "gauge", "none", -1,
                    "non_heap_used_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("live_threads", "Live threads", "count", "gauge", "none", -1,
                    "live_threads", false, 0, null, PROCESS),
            new MetricDescriptor("peak_threads", "Peak threads", "count", "gauge", "none", -1,
                    "peak_threads", false, 0, null, PROCESS),
            new MetricDescriptor("process_cpu_nanos", "Process CPU time", "ns", "counter", "none", -1,
                    "process_cpu_nanos", false, 0, null, PROCESS),
            new MetricDescriptor("process_cpu_load_ppm", "Process CPU load", "ppm", "gauge", "none", -1,
                    "process_cpu_load_ppm", false, 0, null, PROCESS),
            new MetricDescriptor("system_cpu_load_ppm", "System CPU load", "ppm", "gauge", "none", -1,
                    "system_cpu_load_ppm", false, 0, null, PROCESS),
            new MetricDescriptor("gc_collections", "Garbage collections", "count", "counter", "none", -1,
                    "gc_collections", false, 0, null, PROCESS),
            new MetricDescriptor("gc_millis", "Garbage collection time", "ms", "counter", "none", -1,
                    "gc_millis", false, 0, null, PROCESS),
            new MetricDescriptor("metric_counter_bytes", "Metric counter storage", "bytes", "gauge", "none", -1,
                    "metric_counter_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("metric_lookup_bytes", "Metric lookup storage", "bytes", "gauge", "none", -1,
                    "metric_lookup_bytes", false, 0, null, PROCESS),
            new MetricDescriptor("metric_primitive_bytes", "Metric primitive storage", "bytes", "gauge", "none", -1,
                    "metric_primitive_bytes", false, 0, null, PROCESS)
    };
    private final String project;
    private final long startedNanos;
    private final String[] flows;
    private final String[] steps;
    private final AtomicLongArray flowCounters;
    private final AtomicLongArray stepCounters;
    private final AtomicIntegerArray flowInFlight;
    private volatile MetricDescriptor[] publishedMetrics = METRICS;
    private final IdentifierIndex flowIndex;
    private final IdentifierIndex stepIndex;
    private final CustomMetrics custom;

    /**
     * Fixed metrics of one compiled Step placement, including nested placements.
     * @param step owning graph-Step metric ordinal, or -1 when its measurements are disabled
     * @param placement unique compiled placement path, not request data
     * @param namespace Step definition identifier
     * @param definitions fixed declarations in authoring order
     */
    public record Registration(int step, String placement, String namespace, List<MetricDefinition> definitions) {
        /** Validates one immutable compiled registration. */
        public Registration {
            if (placement == null || placement.isBlank() || namespace == null || namespace.isBlank())
                throw new IllegalArgumentException("Metric placement and namespace must be nonblank.");
            definitions = List.copyOf(definitions);
            if (definitions.stream().map(MetricDefinition::name).distinct().count() != definitions.size())
                throw new IllegalArgumentException("Metric names must be unique within a placement.");
        }
    }

    /**
     * Creates one fixed-cardinality metric store.
     *
     * @param project stable project identifier
     * @param flows stable flow identifiers indexed by generated flow number
     * @param steps stable identifiers for metrics-enabled executable graph Steps
     */
    public RuntimeMetrics(
            final String project,
            final String[] flows,
            final String[] steps
    ) {
        this(project, flows, steps, List.of());
    }

    /**
     * Creates one application metric store and binds fixed Step-defined measurements once.
     * @param project nonblank project identifier
     * @param flows flow identifiers in generated order
     * @param steps metrics-enabled graph-Step identifiers in generated order
     * @param registrations immutable compiled declarations, never invocation-derived names
     * @throws IllegalArgumentException for invalid identifiers, ordinals or conflicting declarations
     * @throws NullPointerException for a missing registration list or entries
     */
    public RuntimeMetrics(final String project, final String[] flows, final String[] steps,
                          final List<Registration> registrations) {
        if (project == null || project.isBlank()
                || flows == null || steps == null
                || Arrays.stream(flows).anyMatch(id -> id == null || id.isBlank())
                || Arrays.stream(steps).anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("Metric project, flows, and Steps must be supplied.");
        }
        this.project = project;
        startedNanos = System.nanoTime();
        this.flows = flows.clone();
        this.steps = steps.clone();
        flowCounters = new AtomicLongArray(flows.length * STRIDE);
        stepCounters = new AtomicLongArray(steps.length * STRIDE);
        flowInFlight = new AtomicIntegerArray(flows.length);
        flowIndex = new IdentifierIndex(this.flows);
        stepIndex = new IdentifierIndex(this.steps);
        custom = registrations.isEmpty() ? CustomMetrics.EMPTY : new CustomMetrics(List.copyOf(registrations), steps.length);
    }

    /**
     * Returns prebound measurement handles for a compiled placement. No registration occurs here.
     * @param placement placement path supplied at application construction
     * @return existing thread-safe handles, with shared no-op handles for disabled measurements
     * @throws IllegalArgumentException for an undeclared placement
     */
    public StepInput.Metrics step(final String placement) {
        if (placement == null || placement.isBlank()) throw new IllegalArgumentException("Metric placement must be nonblank.");
        final var metrics = custom.bindings.get(placement);
        if (metrics == null) throw new IllegalArgumentException("Metric placement is not declared: " + placement);
        return metrics;
    }

    /** Starts one operational flow and returns its opaque monotonic start marker. */
    public long startFlow(final int flow) {
        return startFlow(flowCounters, flowInFlight, flow);
    }

    /**
     * Binds a counter to a compiled Flow at startup and publishes the sum across Flows.
     * Each Flow that increments the same metric supplies the same definition.
     * @param flow identifier of the Flow that increments the counter
     * @param definition declaration of a summed counter
     * @return a thread-safe increment operation bound to the Flow
     * @throws IllegalArgumentException for an unknown Flow or an invalid or conflicting metric definition
     * @throws NullPointerException if the definition is missing
     */
    public synchronized Runnable registerFlowCounter(final String flow, final MetricDefinition definition) {
        final int ordinal = flowIndex.find(flow, flows);
        if (ordinal < 0) {
            throw new IllegalArgumentException("Unknown Flow for metric registration: " + flow);
        }
        if (definition.kind() != MetricDefinition.Kind.COUNTER
                || definition.aggregation() != MetricDefinition.Aggregation.SUM) {
            throw new IllegalArgumentException("A Flow counter requires sum aggregation.");
        }
        final MetricDescriptor existing = metric(definition.name());
        final AtomicLongArray counts;
        if (existing == null) {
            counts = new AtomicLongArray(flows.length);
            final int index = publishedMetrics.length;
            final int value = FIRST_REGISTERED_VALUE + index - METRICS.length;
            final MetricDescriptor[] descriptors = Arrays.copyOf(publishedMetrics, index + 1);
            descriptors[index] = new MetricDescriptor(definition.name(), definition.label(), definition.unit(),
                    "counter", "sum", value, definition.name() + "_total", false, 0, null, counts, APPLICATION, FLOW);
            publishedMetrics = descriptors;
        } else {
            counts = existing.flowCounts;
            if (counts == null || !existing.label.equals(definition.label()) || !existing.unit.equals(definition.unit())) {
                throw new IllegalArgumentException("Conflicting Flow metric definition: " + definition.name());
            }
        }
        return () -> counts.incrementAndGet(ordinal);
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
    public RuntimeMetrics finishStep(
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
                outcome == Integer.MIN_VALUE || outcome < 0
                        && (result == null || result instanceof RunResult.Failed || result instanceof RunResult.Rejected),
                cancelled
        );
        return this;
    }

    /** Finishes one flow, records its terminal state and wall time, and returns this store. */
    public RuntimeMetrics finishFlow(
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
    public RuntimeMetrics writeJson(final OutputStream destination) throws IOException {
        return writeJson(destination, null, true);
    }

    /** Streams application and process metrics without per-flow or per-Step series. */
    public RuntimeMetrics writeApplicationJson(final OutputStream destination) throws IOException {
        return writeJson(destination, null, false);
    }

    /** Streams application metrics plus indexed flow and Step series matching {@code node}. */
    public RuntimeMetrics writeNodeJson(final OutputStream destination, final String node) throws IOException {
        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("Metric node must be a non-blank identifier.");
        }
        return writeJson(destination, node, false);
    }

    /**
     * Streams the metric descriptors available to this application.
     *
     * @param destination caller-owned output; flushed but not closed
     * @return this metric store for chaining
     * @throws IOException if the destination cannot accept the catalog
     */
    public RuntimeMetrics writeCatalogJson(final OutputStream destination) throws IOException {
        final Map<String, RailixValue> catalog = new LinkedHashMap<>();
        for (final MetricDescriptor metric : publishedMetrics) {
            catalog.put(metric.id, metric.definition());
        }
        for (final MetricDescriptor metric : custom.descriptors.values()) catalog.put(metric.id, metric.definition());
        final BufferedWriter output = writer(destination);
        output.append(RailixJson.write(RailixValue.object(Map.of(
                "application_pid", RailixValue.number(ProcessHandle.current().pid()),
                "metrics", RailixValue.object(catalog)
        ))));
        output.flush();
        return this;
    }

    /**
     * Returns a bounded snapshot of selected metric series without executing any application work.
     *
     * @param query validated object selecting Step ranges, flows and supported metric identifiers
     * @return current measurements and owning application PID
     * @throws IllegalArgumentException when selectors are invalid, unknown or exceed the query limits
     */
    public RailixValue.ObjectValue query(final RailixValue.ObjectValue query) {
        queryFields(query, Set.of("steps", "flows", "application", "process", "metrics"));
        final Map<String, int[]> selectedSteps = queryRanges(query.values().get("steps"), steps.length);
        final Map<String, RailixValue> requestedFlows = queryObject(query.values().get("flows"));
        final String application = query.values().containsKey("application")
                ? queryIdentifier(query.values().get("application")) : null;
        final String process = query.values().containsKey("process")
                ? queryIdentifier(query.values().get("process")) : null;
        final List<MetricDescriptor> requestedMetrics = queryMetrics(query.values().get("metrics"));
        if ((long) selectedSteps.size() + requestedFlows.size() + (application == null ? 0 : 1)
                + (process == null ? 0 : 1) > MAX_QUERY_GROUPS) {
            throw new IllegalArgumentException("Query exceeds the 4096-group limit.");
        }
        final Map<String, Integer> selectedFlows = new LinkedHashMap<>();
        for (final var group : requestedFlows.entrySet()) {
            if (group.getKey().isBlank() || selectedSteps.containsKey(group.getKey())) {
                throw new IllegalArgumentException("Query group identifiers must be non-blank and unique across categories.");
            }
            final int index = flowIndex.find(queryIdentifier(group.getValue()), flows);
            if (index < 0) {
                throw new IllegalArgumentException("Query references an unknown flow identifier.");
            }
            selectedFlows.put(group.getKey(), index);
        }
        if (application != null && (selectedSteps.containsKey(application) || selectedFlows.containsKey(application))) {
            throw new IllegalArgumentException("Query group identifiers must be unique across categories.");
        }
        if (process != null && (selectedSteps.containsKey(process) || selectedFlows.containsKey(process)
                || process.equals(application))) {
            throw new IllegalArgumentException("Query group identifiers must be unique across categories.");
        }
        final Map<String, RailixValue> groups = new LinkedHashMap<>();
        selectedSteps.forEach((id, ranges) -> groups.put(id, selected(
                stepCounters, null, ranges, STEP, requestedMetrics
        )));
        selectedFlows.forEach((id, index) -> groups.put(id, selected(
                flowValues(index, publishedMetrics), FLOW, requestedMetrics
        )));
        if (application != null) {
            groups.put(application, selected(flowCounters, flowInFlight, new int[]{0, flows.length - 1},
                    APPLICATION, requestedMetrics));
        }
        if (process != null) {
            groups.put(process, selected(processMeasurements(), PROCESS, requestedMetrics));
        }
        return RailixValue.object(Map.of(
                "application_pid", RailixValue.number(ProcessHandle.current().pid()),
                "observed_at", RailixValue.number(System.currentTimeMillis()),
                "elapsed_nanos", RailixValue.number(Math.max(0, System.nanoTime() - startedNanos)),
                "groups", RailixValue.object(groups)
        ));
    }

    private RailixValue.ObjectValue selected(
            final Values values,
            final String scope,
            final List<MetricDescriptor> requested
    ) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        for (final MetricDescriptor metric : publishedMetrics) {
            if ((requested == null || requested.contains(metric)) && metric.supports(scope) && metric.value >= 0) {
                result.put(metric.id, RailixValue.number(values.value(metric.value)));
            }
        }
        return RailixValue.object(result);
    }

    private RailixValue.ObjectValue selected(
            final AtomicLongArray counters,
            final AtomicIntegerArray inFlight,
            final int[] ranges,
            final String scope,
            final List<MetricDescriptor> requested
    ) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        for (final MetricDescriptor metric : publishedMetrics) {
            if ((requested != null && !requested.contains(metric)) || !metric.supports(scope) || metric.value < 0) {
                continue;
            }
            boolean observed = false;
            long value = 0;
            for (int range = 0; range < ranges.length; range += 2) {
                for (int index = ranges[range]; index <= ranges[range + 1]; index++) {
                    final long current = metric.flowCounts == null
                            ? counterValue(counters, inFlight, index, metric.value)
                            : metric.flowCounts.get(index);
                    value = observed ? metric.reduce(value, current) : current;
                    observed = true;
                }
            }
            result.put(metric.id, RailixValue.number(value));
        }
        if (STEP.equals(scope)) result.putAll(custom.values(ranges, requested));
        return RailixValue.object(result);
    }

    private static long counterValue(
            final AtomicLongArray counters,
            final AtomicIntegerArray inFlight,
            final int index,
            final int metric
    ) {
        final int offset = index * STRIDE;
        return switch (metric) {
            case 0 -> counters.get(offset + EXECUTIONS);
            case 1 -> counters.get(offset + ERRORS);
            case 2 -> counters.get(offset + CANCELLED);
            case 3 -> inFlight.get(index);
            case 4 -> counters.get(offset + DURATION_TOTAL);
            case 5 -> counters.get(offset + DURATION_MAX);
            case 6 -> counters.get(offset + DURATION_SAMPLES);
            default -> throw new IllegalArgumentException("Unknown metric value index.");
        };
    }

    private RailixValue.ObjectValue selected(
            final RailixValue.ObjectValue values,
            final String scope,
            final List<MetricDescriptor> requested
    ) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        for (final MetricDescriptor metric : METRICS) {
            final RailixValue value = values.values().get(metric.id);
            if ((requested == null || requested.contains(metric)) && metric.supports(scope) && value != null) {
                result.put(metric.id, value);
            }
        }
        return RailixValue.object(result);
    }

    private List<MetricDescriptor> queryMetrics(final RailixValue value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof RailixValue.ArrayValue array)) {
            throw new IllegalArgumentException("Query metrics must be an array of metric identifiers.");
        }
        final List<MetricDescriptor> result = new ArrayList<>();
        for (final RailixValue entry : array.values()) {
            final String id = queryIdentifier(entry);
            final MetricDescriptor metric = metric(id);
            if (metric == null || result.contains(metric)) {
                throw new IllegalArgumentException("Query metrics must be known, non-duplicate identifiers.");
            }
            result.add(metric);
        }
        return result;
    }

    private MetricDescriptor metric(final String id) {
        for (final MetricDescriptor metric : publishedMetrics) {
            if (metric.id.equals(id)) {
                return metric;
            }
        }
        return custom.descriptors.get(id);
    }

    private RuntimeMetrics writeJson(
            final OutputStream destination,
            final String selected,
            final boolean all
    ) throws IOException {
        final BufferedWriter output = writer(destination);
        final Values application = applicationValues();
        output.append("{\"application_pid\":")
                .append(Long.toString(ProcessHandle.current().pid()))
                .append(",\"application\":{\"metrics\":");
        json(output, application, APPLICATION);
        output.append("},\"flows\":[");
        boolean separator = false;
        if (all) {
            for (int index = 0; index < flows.length; index++) {
                separator = entity(
                        output,
                        separator,
                        flows[index],
                        flowValues(index, publishedMetrics), FLOW, -1
                );
            }
        } else {
            final int index = flowIndex.find(selected, flows);
            separator = index < 0 ? separator : entity(
                    output,
                    separator,
                    flows[index],
                    flowValues(index, publishedMetrics), FLOW, -1
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
                separator = entity(output, separator, steps[index], values(stepCounters, index), STEP, index);
            }
        } else {
            final int index = stepIndex.find(selected, steps);
            separator = index < 0 ? separator : entity(
                    output,
                    separator,
                    steps[index],
                    values(stepCounters, index), STEP, index
            );
        }
        output.append("]}");
        output.flush();
        return this;
    }

    /** Streams Prometheus text exposition using only fixed project, flow, and Step labels. */
    public RuntimeMetrics writePrometheus(final OutputStream destination) throws IOException {
        final BufferedWriter output = writer(destination);
        final Values application = applicationValues();
        prometheusTypes(output, APPLICATION);
        prometheusTypes(output, FLOW);
        prometheusTypes(output, STEP);
        prometheus(output, APPLICATION, "project=\"" + prom(project) + "\"", application);
        for (int index = 0; index < flows.length; index++) {
            prometheus(output, FLOW, "project=\"" + prom(project) + "\",flow=\""
                    + prom(flows[index]) + "\"", flowValues(index, publishedMetrics));
        }
        for (int index = 0; index < steps.length; index++) {
            prometheus(output, STEP, "project=\"" + prom(project) + "\",step=\""
                    + prom(steps[index]) + "\"", values(stepCounters, index));
        }
        for (final var metric : custom.descriptors.values()) {
            output.append("# HELP railix_step_").append(metric.prometheus).append(' ')
                    .append((metric.id + " - " + metric.label + " (" + metric.unit + ")")
                            .replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
            output.append("# TYPE railix_step_").append(metric.prometheus).append(' ').append(metric.kind).append('\n');
        }
        for (int index = 0; index < steps.length && !custom.descriptors.isEmpty(); index++) {
            for (final var placementValue : custom.placements(index)) {
                final var placement = (RailixValue.ObjectValue) placementValue;
                final var values = (RailixValue.ObjectValue) placement.values().get("metrics");
                for (final var entry : values.values().entrySet()) {
                    final var metric = custom.descriptors.get(entry.getKey());
                    final var value = (RailixValue.NumberValue) entry.getValue();
                    output.append("railix_step_").append(metric.prometheus).append("{project=\"")
                            .append(prom(project)).append("\",step=\"").append(prom(steps[index]))
                            .append("\",placement=\"").append(prom(((RailixValue.StringValue) placement.values().get("id")).value()))
                            .append("\"} ").append(value.value().toPlainString()).append('\n');
                }
            }
        }
        final RailixValue.ObjectValue process = processMeasurements();
        for (final MetricDescriptor metric : METRICS) {
            final RailixValue value = process.values().get(metric.id);
            if (!metric.supports(PROCESS) || !(value instanceof RailixValue.NumberValue number)) {
                continue;
            }
            output.append("railix_").append(metric.id.startsWith("metric_") ? metric.id : "process_" + metric.id)
                    .append("{project=\"").append(prom(project)).append("\"} ")
                    .append(number.value().toPlainString()).append('\n');
        }
        output.flush();
        return this;
    }

    /** Streams Influx line protocol using only fixed project, flow, and Step tags. */
    public RuntimeMetrics writeInflux(final OutputStream destination) throws IOException {
        final BufferedWriter output = writer(destination);
        final Values application = applicationValues();
        influx(output, APPLICATION, "project=" + influxTag(project), application);
        for (int index = 0; index < flows.length; index++) {
            influx(output, FLOW, "project=" + influxTag(project) + ",flow="
                    + influxTag(flows[index]), flowValues(index, publishedMetrics));
        }
        for (int index = 0; index < steps.length; index++) {
            influx(output, STEP, "project=" + influxTag(project) + ",step="
                    + influxTag(steps[index]), values(stepCounters, index));
        }
        for (int index = 0; index < steps.length && !custom.descriptors.isEmpty(); index++) {
            for (final var placementValue : custom.placements(index)) {
                final var placement = (RailixValue.ObjectValue) placementValue;
                final var values = (RailixValue.ObjectValue) placement.values().get("metrics");
                for (final var entry : values.values().entrySet()) {
                    final boolean counter = custom.descriptors.get(entry.getKey()).kind.equals("counter");
                    output.append(counter ? "railix_step_custom_counter,project=" : "railix_step_custom_gauge,project=").append(influxTag(project))
                            .append(",step=").append(influxTag(steps[index]))
                            .append(",placement=").append(influxTag(((RailixValue.StringValue) placement.values().get("id")).value()))
                            .append(",metric=").append(influxTag(entry.getKey()))
                            .append(" value=").append(((RailixValue.NumberValue) entry.getValue()).value().toPlainString());
                    if (counter) output.append('i');
                    output.append('\n');
                }
            }
        }
        output.append("railix_process,project=").append(influxTag(project)).append(' ');
        boolean separator = false;
        final RailixValue.ObjectValue process = processMeasurements();
        for (final MetricDescriptor metric : METRICS) {
            final RailixValue value = process.values().get(metric.id);
            if (!metric.supports(PROCESS) || !(value instanceof RailixValue.NumberValue number)) {
                continue;
            }
            if (separator) {
                output.append(',');
            }
            output.append(metric.id).append('=').append(number.value().toPlainString()).append('i');
            separator = true;
        }
        output.append('\n');
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

    private Values applicationValues() {
        final MetricDescriptor[] metrics = publishedMetrics;
        Values application = Values.EMPTY;
        for (int index = 0; index < flows.length; index++) {
            application = application.add(flowValues(index, metrics));
        }
        return application;
    }

    private Values flowValues(final int index, final MetricDescriptor[] metrics) {
        final int offset = index * STRIDE;
        return new Values(
                flowCounters.get(offset + EXECUTIONS),
                flowCounters.get(offset + ERRORS),
                flowCounters.get(offset + CANCELLED),
                flowInFlight.get(index),
                flowCounters.get(offset + DURATION_TOTAL),
                flowCounters.get(offset + DURATION_MAX),
                flowCounters.get(offset + DURATION_SAMPLES),
                registeredCounts(index, metrics)
        );
    }

    private static long[] registeredCounts(final int flow, final MetricDescriptor[] metrics) {
        long[] counts = NO_REGISTERED_COUNTS;
        for (int index = METRICS.length; index < metrics.length; index++) {
            final long count = metrics[index].flowCounts.get(flow);
            if (count == 0) {
                continue;
            }
            if (counts.length == 0) {
                counts = new long[metrics.length - METRICS.length];
            }
            counts[index - METRICS.length] = count;
        }
        return counts;
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
                counters.get(offset + DURATION_SAMPLES),
                NO_REGISTERED_COUNTS
        );
    }

    private static BufferedWriter writer(final OutputStream destination) {
        if (destination == null) {
            throw new IllegalArgumentException("Metric destination cannot be Java null.");
        }
        return new BufferedWriter(new OutputStreamWriter(destination, StandardCharsets.UTF_8), 16_384);
    }

    private boolean entity(
            final BufferedWriter output,
            final boolean separator,
            final String id,
            final Values values,
            final String scope,
            final int step
    ) throws IOException {
        if (separator) {
            output.append(',');
        }
        output.append("{\"id\":");
        string(output, id);
        output.append(",\"metrics\":");
        if (step < 0 || custom.descriptors.isEmpty()) json(output, values, scope);
        else {
            final var combined = new LinkedHashMap<>(selected(values, scope, null).values());
            combined.putAll(custom.values(new int[]{step, step}, null));
            output.append(RailixJson.write(RailixValue.object(combined)));
            final var placements = custom.placements(step);
            if (!placements.isEmpty()) output.append(",\"placements\":")
                    .append(RailixJson.write(RailixValue.array(placements)));
        }
        output.append('}');
        return true;
    }

    private void json(final BufferedWriter output, final Values values, final String scope) throws IOException {
        boolean separator = false;
        output.append('{');
        for (final MetricDescriptor metric : publishedMetrics) {
            if (metric.value < 0 || !metric.supports(scope)) {
                continue;
            }
            if (separator) {
                output.append(',');
            }
            output.append('"').append(metric.id).append("\":")
                    .append(Long.toString(values.value(metric.value)));
            separator = true;
        }
        output.append('}');
    }

    private static void string(final BufferedWriter output, final String value) throws IOException {
        output.append(RailixJson.write(RailixValue.string(value)));
    }

    private long counterBytes() {
        final long registeredCounters = (long) (publishedMetrics.length - METRICS.length) * flows.length;
        return ((long) flowCounters.length() + stepCounters.length() + registeredCounters) * Long.BYTES
                + (long) flowInFlight.length() * Integer.BYTES + custom.counterBytes;
    }

    private long lookupBytes() {
        return flowIndex.bytes() + stepIndex.bytes();
    }

    private long primitiveBytes() {
        return counterBytes() + lookupBytes();
    }

    private RailixValue.ObjectValue process() {
        final var runtime = ManagementFactory.getRuntimeMXBean();
        final var memory = ManagementFactory.getMemoryMXBean();
        final var threads = ManagementFactory.getThreadMXBean();
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put("uptime_millis", RailixValue.number(runtime.getUptime()));
        available(values, "heap_used_bytes", memory.getHeapMemoryUsage().getUsed());
        available(values, "heap_committed_bytes", memory.getHeapMemoryUsage().getCommitted());
        available(values, "heap_max_bytes", memory.getHeapMemoryUsage().getMax());
        available(values, "non_heap_used_bytes", memory.getNonHeapMemoryUsage().getUsed());
        values.put("live_threads", RailixValue.number(threads.getThreadCount()));
        values.put("peak_threads", RailixValue.number(threads.getPeakThreadCount()));
        final com.sun.management.OperatingSystemMXBean system = ManagementFactory.getPlatformMXBean(
                com.sun.management.OperatingSystemMXBean.class
        );
        if (system != null) {
            available(values, "process_cpu_nanos", system.getProcessCpuTime());
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
        boolean collectionCountAvailable = false;
        boolean collectionTimeAvailable = false;
        for (final var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long count = collector.getCollectionCount();
            final long time = collector.getCollectionTime();
            if (count >= 0) {
                collections += count;
                collectionCountAvailable = true;
            }
            if (time >= 0) {
                collectionMillis += time;
                collectionTimeAvailable = true;
            }
        }
        if (collectionCountAvailable) {
            values.put("gc_collections", RailixValue.number(collections));
        }
        if (collectionTimeAvailable) {
            values.put("gc_millis", RailixValue.number(collectionMillis));
        }
        return RailixValue.object(values);
    }

    private RailixValue.ObjectValue processMeasurements() {
        final Map<String, RailixValue> values = new LinkedHashMap<>(process().values());
        values.put("metric_counter_bytes", RailixValue.number(counterBytes()));
        values.put("metric_lookup_bytes", RailixValue.number(lookupBytes()));
        values.put("metric_primitive_bytes", RailixValue.number(primitiveBytes()));
        return RailixValue.object(values);
    }

    private static void available(final Map<String, RailixValue> values, final String id, final long value) {
        if (value >= 0) {
            values.put(id, RailixValue.number(value));
        }
    }

    private void prometheus(
            final BufferedWriter output,
            final String scope,
            final String labels,
            final Values values
    ) throws IOException {
        for (final MetricDescriptor metric : publishedMetrics) {
            if (metric.value < 0 || !metric.supports(scope)) {
                continue;
            }
            final long value = values.value(metric.value);
            output.append("railix_").append(scope).append('_').append(metric.prometheus)
                    .append('{').append(labels).append("} ")
                    .append(metric.seconds ? seconds(value) : Long.toString(value))
                    .append('\n');
        }
    }

    private void prometheusTypes(final BufferedWriter output, final String scope) throws IOException {
        for (final MetricDescriptor metric : publishedMetrics) {
            if (metric.value >= 0 && metric.supports(scope)) {
                output.append("# TYPE railix_").append(scope).append('_').append(metric.prometheus)
                        .append(' ').append(metric.kind).append('\n');
            }
        }
    }

    private void influx(
            final BufferedWriter output,
            final String scope,
            final String tags,
            final Values values
    ) throws IOException {
        output.append("railix_").append(scope).append(',').append(tags).append(' ');
        boolean separator = false;
        for (final MetricDescriptor metric : publishedMetrics) {
            if (metric.value < 0 || !metric.supports(scope)) {
                continue;
            }
            if (separator) {
                output.append(',');
            }
            output.append(metric.id).append('=').append(Long.toString(values.value(metric.value))).append('i');
            separator = true;
        }
        output.append('\n');
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

    record MetricDescriptor(
            String id,
            String label,
            String unit,
            String kind,
            String aggregation,
            int value,
            String prometheus,
            boolean seconds,
            int stepSamplingInterval,
            String sampleCount,
            AtomicLongArray flowCounts,
            String... scopes
    ) {
        MetricDescriptor(final String id, final String label, final String unit, final String kind,
                         final String aggregation, final int value, final String prometheus, final boolean seconds,
                         final int stepSamplingInterval, final String sampleCount, final String... scopes) {
            this(id, label, unit, kind, aggregation, value, prometheus, seconds, stepSamplingInterval, sampleCount,
                    null, scopes);
        }

        private boolean supports(final String scope) {
            for (final String candidate : scopes) {
                if (candidate.equals(scope)) {
                    return true;
                }
            }
            return false;
        }

        private RailixValue.ObjectValue definition() {
            final List<RailixValue> scopeValues = Arrays.stream(scopes)
                    .map(scope -> (RailixValue) RailixValue.string(scope)).toList();
            final Map<String, RailixValue> definition = new LinkedHashMap<>();
            definition.put("label", RailixValue.string(label));
            definition.put("unit", RailixValue.string(unit));
            definition.put("kind", RailixValue.string(kind));
            definition.put("aggregation", RailixValue.string(aggregation));
            definition.put("scopes", RailixValue.array(scopeValues));
            if (stepSamplingInterval > 0) {
                definition.put("sampling", RailixValue.object(Map.of(
                        STEP, RailixValue.number(stepSamplingInterval)
                )));
            }
            if (sampleCount != null) {
                definition.put("sample_count", RailixValue.string(sampleCount));
            }
            return RailixValue.object(definition);
        }

        private long reduce(final long left, final long right) {
            return switch (aggregation) {
                case "sum" -> Values.saturated(left, right);
                case "max" -> Math.max(left, right);
                case "none" -> right;
                default -> throw new IllegalStateException("Unknown metric aggregation.");
            };
        }
    }

    private record Values(
            long executions,
            long errors,
            long cancelled,
            long inFlight,
            long durationTotal,
            long durationMax,
            long durationSamples,
            long[] registeredCounts
    ) {
        private static final Values EMPTY = new Values(0, 0, 0, 0, 0, 0, 0, NO_REGISTERED_COUNTS);

        private Values add(final Values other) {
            return new Values(
                    saturated(executions, other.executions),
                    saturated(errors, other.errors),
                    saturated(cancelled, other.cancelled),
                    saturated(inFlight, other.inFlight),
                    saturated(durationTotal, other.durationTotal),
                    Math.max(durationMax, other.durationMax),
                    saturated(durationSamples, other.durationSamples),
                    sumRegistered(registeredCounts, other.registeredCounts)
            );
        }

        private static long[] sumRegistered(final long[] left, final long[] right) {
            if (left.length == 0) {
                return right;
            }
            if (right.length == 0) {
                return left;
            }
            final long[] total = new long[left.length];
            for (int slot = 0; slot < total.length; slot++) {
                total[slot] = saturated(left[slot], right[slot]);
            }
            return total;
        }

        private long value(final int index) {
            return switch (index) {
                case 0 -> executions;
                case 1 -> errors;
                case 2 -> cancelled;
                case 3 -> inFlight;
                case 4 -> durationTotal;
                case 5 -> durationMax;
                case 6 -> durationSamples;
                default -> {
                    final int slot = index - FIRST_REGISTERED_VALUE;
                    if (slot < 0) {
                        throw new IllegalArgumentException("Unknown metric value index.");
                    }
                    yield slot < registeredCounts.length ? registeredCounts[slot] : 0;
                }
            };
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
