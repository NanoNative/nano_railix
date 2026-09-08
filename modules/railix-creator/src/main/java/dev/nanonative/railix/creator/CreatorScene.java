package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Immutable, derived Creator geometry, absent from compiled applications and project files.
 * The compiler establishes a rooted acyclic graph with one incoming connection per Step;
 * contracting connected user groups therefore preserves a tree.
 */
final class CreatorScene {
    static final int MAX_NODES = 2048;
    static final int MAX_SEGMENTS = 4096;
    private static final int MAX_ROUTE_VISITS = 16_384;
    private static final Set<String> VIEW_PARAMETERS = Set.of("x", "y", "width", "height", "scale", "focus");
    private static final List<String> COUNTERS = List.of(
            "executions", "errors", "cancelled", "duration_nanos_total", "duration_samples");
    private static final double NODE_WIDTH = 168;
    private static final double NODE_HEIGHT = 64;
    private static final double COLUMN = 248;
    private static final double ROW = 144;
    private static final double REGION_DETAIL_WIDTH = 520;
    private static final double REGION_DETAIL_HEIGHT = 280;
    private final String source;
    private final RailixValue.ObjectValue metadata;
    private final String revision;
    private final Part root;
    private final Routes routes;
    private final List<Part> leaves;
    private final Map<String, RailixValue> icons;
    private final int nodeCount;
    private final Map<String, Part> identities = new HashMap<>();

    CreatorScene(final String source, final RailixValue.ObjectValue metadata, final StepCatalog catalog) {
        this.source = source;
        this.metadata = metadata;
        revision = identity(source, RailixJson.write(metadata));
        final RailixValue.ObjectValue project =
                (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final Map<String, RailixValue> styles = object(metadata.values().get("steps"));
        final Map<String, RailixValue> groups = new HashMap<>();
        array(metadata, "groups").forEach(value -> groups.put(text(object(value), "id"), value));
        final Map<String, Part> nodes = new LinkedHashMap<>();
        for (final RailixValue value : array(project, "nodes")) {
            final Map<String, RailixValue> node = object(value);
            final String id = text(node, "id");
            final String use = text(node, "use");
            final StepDefinition definition = catalog.find(use).orElseThrow();
            final String kind = switch (definition.kind()) {
                case APP -> "app";
                case TRIGGER -> "trigger";
                default -> "step";
            };
            final Map<String, RailixValue> style = object(styles.get(id));
            final String name = style.containsKey("name") ? text(style, "name")
                    : "app".equals(kind) ? text(project.values(), "id") : definition.displayName();
            final Part part = new Part(id, kind, name, use, text(style, "group"), style, List.of());
            part.node = nodes.size();
            part.metrics = !RailixValue.bool(false).equals(node.get("metrics"));
            part.exampleCount = node.get("examples") instanceof RailixValue.ArrayValue examples
                    ? examples.values().size() : 0;
            nodes.put(id, part);
        }
        nodeCount = nodes.size();
        final List<Route> edges = new ArrayList<>();
        for (final RailixValue value : array(project, "links")) {
            final Map<String, RailixValue> link = object(value);
            final String from = text(link, "from");
            final int separator = from.lastIndexOf('.');
            final Part start = nodes.get(from.substring(0, separator));
            final String target = text(link, "to");
            final Part end;
            if ("end".equals(target)) {
                end = new Part("end:" + from, "end", "End", "", "", Map.of(), List.of());
                nodes.put(end.id, end);
            } else {
                end = nodes.get(target);
            }
            final Route edge = new Route(from + ">" + target, start, end, from.substring(separator + 1));
            edges.add(edge);
            start.outgoing.add(edge);
        }
        final Part app = nodes.values().stream().filter(node -> "app".equals(node.kind)).findFirst().orElseThrow();
        final List<Part> order = layout(app);
        final Map<Part, Part> occurrences = groups(order, groups);
        if (!occurrences.isEmpty()) {
            order.forEach(node -> node.outgoing.clear());
            for (final Route edge : edges) {
                final Part from = occurrences.getOrDefault(edge.from, edge.from);
                final Part to = occurrences.getOrDefault(edge.to, edge.to);
                if (from != to) {
                    from.outgoing.add(new Route(edge.id, from, to, edge.outcome));
                }
            }
            layout(app);
        }
        final List<Part> world = new ArrayList<>();
        app.box = new Box(0, Math.max(0, app.outgoing.size() - 1) * 200 + 108, NODE_WIDTH, NODE_HEIGHT);
        world.add(app);
        int flow = 0;
        for (final Route triggerRoute : app.outgoing) {
            final Part trigger = triggerRoute.to;
            trigger.box = new Box(COLUMN, flow * 400 + 108, NODE_WIDTH, NODE_HEIGHT);
            world.add(trigger);
            final List<Part> units = new ArrayList<>();
            final Set<Part> added = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            final ArrayDeque<Part> pending = new ArrayDeque<>();
            trigger.outgoing.forEach(edge -> pending.add(edge.to));
            while (!pending.isEmpty()) {
                final Part node = pending.removeFirst();
                final Part unit = occurrences.getOrDefault(node, node);
                if (added.add(unit)) {
                    units.add(unit);
                }
                node.outgoing.forEach(edge -> pending.addLast(edge.to));
            }
            if (!units.isEmpty()) {
                final Part section = hierarchy(sections(units, "region:" + trigger.id), "index:" + trigger.id, false);
                place(section, new Box(COLUMN * 2, flow * 400, 720, 280));
                world.add(section);
            }
            flow++;
        }
        root = container("index", "world", "Application", "", Map.of(), world);
        int sequence = 0;
        final ArrayDeque<Part> pending = new ArrayDeque<>();
        pending.add(root);
        final List<Part> hierarchy = new ArrayList<>();
        final List<Part> indexedLeaves = new ArrayList<>();
        final Map<RailixValue, String> iconReferences = new HashMap<>();
        final Map<String, RailixValue> indexedIcons = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            final Part part = pending.removeLast();
            part.outgoing.clear();
            if (part.style.get("icon") instanceof RailixValue.ObjectValue icon) {
                part.iconRef = iconReferences.computeIfAbsent(icon, value -> {
                    final String reference = identity(text(icon.values(), "media_type"), text(icon.values(), "data"));
                    indexedIcons.put(reference, icon);
                    return reference;
                });
            }
            identities.put(part.id, part);
            hierarchy.add(part);
            part.first = sequence;
            if (part.children.isEmpty()) {
                part.last = sequence++;
                indexedLeaves.add(part);
            } else {
                for (int index = part.children.size() - 1; index >= 0; index--) {
                    final Part child = part.children.get(index);
                    child.parent = part;
                    pending.addLast(child);
                }
            }
        }
        leaves = List.copyOf(indexedLeaves);
        icons = Map.copyOf(indexedIcons);
        for (int index = hierarchy.size() - 1; index >= 0; index--) {
            final Part part = hierarchy.get(index);
            if (!part.children.isEmpty()) {
                part.last = part.children.getLast().last;
            }
        }
        final Map<String, Part> groupFocus = new java.util.TreeMap<>();
        hierarchy.stream().filter(part -> "region".equals(part.kind) && !part.group.isEmpty()).forEach(part ->
                groupFocus.merge(part.group, part, (first, next) -> first.id.compareTo(next.id) <= 0 ? first : next));
        groupFocus.forEach((group, part) -> {
            identities.put("group:" + group, part);
        });
        routes = edges.isEmpty() ? null : new Routes(edges);
    }

    boolean matches(final String source, final RailixValue.ObjectValue metadata) {
        return this.source.equals(source) && this.metadata.equals(metadata);
    }

    /** Returns a bounded viewport projection; an omitted viewport fits the world or requested identity. */
    RailixValue.ObjectValue view(final String query) {
        return view(parameters(query, VIEW_PARAMETERS), (id, edge) -> {});
    }

    private RailixValue.ObjectValue view(final Map<String, String> parameters,
                                        final BiConsumer<String, Route> observe) {
        if (parameters.containsKey("focus") && parameters.get("focus").isBlank()) {
            throw new IllegalArgumentException("Scene focus must identify a Step or region.");
        }
        final Part focused = parameters.containsKey("focus") ? identities.get(parameters.get("focus")) : null;
        if (parameters.containsKey("focus") && focused == null) {
            throw new NoSuchElementException("Scene focus does not identify a Step or region.");
        }
        final Box basis = focused == null ? root.box : focused.box;
        final boolean explicit = parameters.containsKey("x");
        final long coordinates = List.of("x", "y", "width", "height").stream()
                .filter(parameters::containsKey).count();
        if (coordinates != 0 && coordinates != 4) {
            throw new IllegalArgumentException("Scene viewport requires x, y, width and height together.");
        }
        final Box viewport = explicit ? new Box(
                number(parameters, "x", 0, false), number(parameters, "y", 0, false),
                number(parameters, "width", 0, true), number(parameters, "height", 0, true)
        ) : basis.expand(Math.max(basis.width, basis.height) * 0.12);
        if (!Double.isFinite(viewport.x + viewport.width) || !Double.isFinite(viewport.y + viewport.height)) {
            throw new IllegalArgumentException("Scene viewport bounds must be finite.");
        }
        final double scale = number(parameters, "scale",
                Math.min(focused == null ? 1 : Double.MAX_VALUE,
                        Math.min(1200 / viewport.width, 800 / viewport.height)), true);
        final List<RailixValue> values = new ArrayList<>();
        final List<Part> constrained = new ArrayList<>();
        final ArrayDeque<Part> pending = new ArrayDeque<>(root.children);
        boolean limited = false;
        while (!pending.isEmpty()) {
            final Part part = pending.removeFirst();
            if (!part.box.intersects(viewport)) {
                continue;
            }
            final boolean expand = expands(part, scale, focused);
            final boolean boundary = expand && !part.group.isEmpty();
            if (expand && values.size() + pending.size() + part.children.size() + (boundary ? 1 : 0) <= MAX_NODES) {
                if (boundary) {
                    values.add(part.value(true));
                }
                pending.addAll(part.children);
            } else {
                limited |= expand;
                if (expand) {
                    constrained.add(part);
                }
                values.add(part.value(false));
            }
        }
        constrained.sort(Comparator.comparingInt(part -> part.first));
        final List<RailixValue> links = new ArrayList<>();
        if (routes != null) {
            final ArrayDeque<Routes> routeQueue = new ArrayDeque<>();
            final Set<String> emitted = new java.util.HashSet<>();
            routeQueue.add(routes);
            int visited = 0;
            while (!routeQueue.isEmpty()) {
                if (++visited > MAX_ROUTE_VISITS || links.size() * 3 + 3 > MAX_SEGMENTS) {
                    limited = true;
                    break;
                }
                final Routes next = routeQueue.removeLast();
                if (!next.box.intersects(viewport)) {
                    continue;
                }
                final Part enclosing = representative(leaves.get(next.first), scale, focused, constrained);
                if (enclosing.last >= next.last) {
                    continue;
                }
                if (next.edge == null) {
                    routeQueue.addLast(next.right);
                    routeQueue.addLast(next.left);
                    continue;
                }
                final Route edge = next.edge;
                final Part start = representative(edge.from, scale, focused, constrained);
                final Part end = representative(edge.to, scale, focused, constrained);
                if (start == end || !start.box.union(end.box).intersects(viewport)) {
                    continue;
                }
                final String id = start.id + "." + edge.outcome + ">" + end.id;
                if (emitted.add(id)) {
                    links.add(edge.value(id, start, end));
                }
                observe.accept(id, edge);
            }
        }
        final Map<String, RailixValue> response = new LinkedHashMap<>();
        response.put("revision", RailixValue.string(revision));
        response.put("bounds", root.box.value());
        if (focused != null) {
            response.put("focus", focused.box.value());
            response.put("focus_min_scale", RailixValue.number(BigDecimal.valueOf(focused.children.isEmpty() ? 0
                    : 1.1 * Math.min(REGION_DETAIL_WIDTH / focused.box.width, REGION_DETAIL_HEIGHT / focused.box.height))));
        }
        response.put("nodes", RailixValue.array(values));
        final Map<String, RailixValue> visibleIcons = new LinkedHashMap<>();
        for (final RailixValue value : values) {
            if (object(value).get("icon_ref") instanceof RailixValue.StringValue reference) {
                visibleIcons.putIfAbsent(reference.value(), icons.get(reference.value()));
            }
        }
        response.put("icons", RailixValue.object(visibleIcons));
        response.put("links", RailixValue.array(links));
        response.put("limited", RailixValue.bool(limited));
        return RailixValue.object(response);
    }

    static Map<String, String> observationParameters(final String query) {
        if (query != null && query.length() > 8192) {
            throw new IllegalArgumentException("Scene observation query exceeds 8192 characters.");
        }
        final Set<String> allowed = new java.util.HashSet<>(VIEW_PARAMETERS);
        allowed.addAll(Set.of("revision", "example"));
        final Map<String, String> parameters = parameters(query, allowed);
        if (!parameters.getOrDefault("revision", "").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Scene observations require the current scene revision.");
        }
        if (parameters.containsKey("example")
                && !parameters.get("example").matches("[a-z][a-z0-9-]{0,63}:[0-9]{1,10}")) {
            throw new IllegalArgumentException("An Example selection requires a compiled Example ID.");
        }
        return parameters;
    }

    boolean observesRevision(final String expected) {
        return revision.equals(expected);
    }

    RailixValue.ObjectValue observationView(final Map<String, String> parameters) {
        if (parameters.containsKey("example")) {
            exampleTrigger(parameters.get("example"));
        }
        return view(viewParameters(parameters), (id, edge) -> {});
    }

    /** Aggregates one application-owned observation snapshot without changing scene geometry. */
    RailixValue.ObjectValue observations(final Map<String, String> parameters,
                                        final RailixValue.ObjectValue projection,
                                        final Map<String, RailixValue.ObjectValue> documents,
                                        final long applicationRevision, final long applicationPid) throws IOException {
        final boolean hasMetrics = documents.containsKey("metrics");
        final boolean hasCoverage = documents.containsKey("coverage");
        final boolean hasSelection = documents.containsKey("example");
        final BitSet covered = hasCoverage ? coverage(documents.get("coverage")) : new BitSet();
        final BitSet selected = new BitSet(nodeCount);
        if (hasSelection) {
            for (final RailixValue value : observedArray(documents.get("example"), "nodes", nodeCount)) {
                final long node = observedNumber(value);
                if (node >= nodeCount || selected.get((int) node)) {
                    throw new IOException("Application Example summary contains an invalid node index.");
                }
                selected.set((int) node);
            }
            selected.set(exampleTrigger(parameters.get("example")).node);
        }
        final RailixValue.ObjectValue metrics = hasMetrics ? documents.get("metrics") : RailixValue.object(Map.of());
        final RailixValue.ObjectValue applicationMetrics = hasMetrics
                ? observedCounters(observedObject(metrics.values().get("application")).values().get("metrics"))
                : RailixValue.object(Map.of());
        final Map<String, RailixValue.ObjectValue> steps = hasMetrics ? metricSeries(metrics, "steps") : Map.of();
        final Map<String, RailixValue.ObjectValue> flows = hasMetrics ? metricSeries(metrics, "flows") : Map.of();
        if (hasMetrics) {
            for (final Part part : leaves) {
                final boolean executable = part.node >= 0 && !"app".equals(part.kind);
                if (executable && part.metrics && !steps.containsKey(part.id)) {
                    throw new IOException("Application metrics omit an enabled Step.");
                }
                if ("trigger".equals(part.kind) && !flows.containsKey(part.id)) {
                    throw new IOException("Application metrics omit a flow.");
                }
            }
        }
        final Map<String, Map<String, RailixValue>> connections = new HashMap<>();
        final RailixValue.ObjectValue visible = !hasMetrics && !hasSelection ? projection
                : view(viewParameters(parameters), (id, edge) -> {
                    final boolean first = !connections.containsKey(id);
                    final Map<String, RailixValue> entry = connections.computeIfAbsent(id,
                            ignored -> new LinkedHashMap<>(Map.of("id", RailixValue.string(id))));
                    if (hasSelection) {
                        final String selection = edge.from.node < 0 || edge.to.node < 0
                                || "app".equals(edge.from.kind) || "app".equals(edge.to.kind)
                                ? "unknown" : selected.get(edge.from.node) && selected.get(edge.to.node)
                                ? "reached" : "unreached";
                        final String previous = text(entry, "selection");
                        entry.put("selection", RailixValue.string("reached".equals(previous) || "reached".equals(selection)
                                ? "reached" : "unknown".equals(previous) || "unknown".equals(selection)
                                ? "unknown" : "unreached"));
                    }
                    if (hasMetrics) {
                        if (edge.to.node < 0 || !edge.to.metrics) {
                            entry.remove("executions");
                        } else if (first || entry.containsKey("executions")) {
                            // One-parent graph: each original destination counts this connection's ingress, not region work.
                            final BigDecimal ingress = ((RailixValue.NumberValue) steps.get(edge.to.id)
                                    .values().get("executions")).value();
                            final BigDecimal previous = entry.get("executions") instanceof RailixValue.NumberValue count
                                    ? count.value() : BigDecimal.ZERO;
                            entry.put("executions", RailixValue.number(previous.add(ingress)));
                        }
                    }
                });
        final List<RailixValue> nodes = new ArrayList<>();
        for (final RailixValue value : array(visible, "nodes")) {
            final Part part = identities.get(text(object(value), "id"));
            final int[] counts = new int[3];
            final BigInteger[] totals = new BigInteger[hasMetrics ? COUNTERS.size() : 0];
            java.util.Arrays.fill(totals, BigInteger.ZERO);
            // Visible regions form a disjoint frontier; no project-sized prefix tables are needed.
            for (int index = part.first; index <= part.last; index++) {
                final Part leaf = leaves.get(index);
                if (leaf.node < 0 || "app".equals(leaf.kind)) continue;
                if (!leaf.metrics) counts[0]++;
                if (covered.get(leaf.node)) counts[1]++;
                if (selected.get(leaf.node)) counts[2]++;
                if (hasMetrics && leaf.metrics) {
                    final RailixValue.ObjectValue series = steps.get(leaf.id);
                    for (int counter = 0; counter < totals.length; counter++) {
                        totals[counter] = totals[counter].add(((RailixValue.NumberValue)
                                series.values().get(COUNTERS.get(counter))).value().toBigInteger());
                    }
                }
            }
            final Map<String, RailixValue> entry = new LinkedHashMap<>();
            entry.put("id", RailixValue.string(part.id));
            entry.put("count", RailixValue.number(part.count));
            entry.put("disabled_count", RailixValue.number(counts[0]));
            if (hasCoverage) {
                entry.put("covered_count", RailixValue.number(counts[1]));
            }
            if (hasSelection) {
                entry.put("selected_count", RailixValue.number(counts[2]));
            }
            if (hasMetrics) {
                final RailixValue.ObjectValue series = switch (part.kind) {
                    case "app" -> applicationMetrics;
                    case "trigger" -> flows.get(part.id);
                    default -> RailixValue.object(Map.of());
                };
                for (int counter = 0; counter < COUNTERS.size(); counter++) {
                    final String name = COUNTERS.get(counter);
                    entry.put(name, series.values().isEmpty()
                            ? RailixValue.number(new BigDecimal(totals[counter]))
                            : series.values().get(name));
                }
            }
            nodes.add(RailixValue.object(entry));
        }
        if (RailixValue.bool(true).equals(visible.values().get("limited"))) {
            // A capped traversal cannot prove that every represented ingress edge contributed.
            connections.values().forEach(entry -> entry.remove("executions"));
        }
        final List<RailixValue> links = array(visible, "links").stream().<RailixValue>map(value -> {
            final String id = text(object(value), "id");
            return RailixValue.object(connections.getOrDefault(id, Map.of("id", RailixValue.string(id))));
        }).toList();
        final Map<String, RailixValue> response = new LinkedHashMap<>();
        response.put("revision", RailixValue.string(revision));
        response.put("application_revision", RailixValue.number(applicationRevision));
        response.put("application_pid", RailixValue.number(applicationPid));
        response.put("limited", visible.values().get("limited"));
        response.put("nodes", RailixValue.array(nodes));
        response.put("links", RailixValue.array(links));
        if (hasSelection) {
            response.put("example", RailixValue.string(parameters.get("example")));
        }
        if (hasCoverage) {
            response.put("coverage_revision", RailixValue.number(
                    observedNumber(documents.get("coverage").values().get("revision"))));
        }
        return RailixValue.object(response);
    }

    private static Map<String, String> viewParameters(final Map<String, String> parameters) {
        final Map<String, String> view = new HashMap<>(parameters);
        view.keySet().retainAll(VIEW_PARAMETERS);
        return view;
    }

    private Part exampleTrigger(final String example) {
        final int separator = example.lastIndexOf(':');
        final Part trigger = identities.get(example.substring(0, separator));
        final long index = Long.parseLong(example.substring(separator + 1));
        if (trigger == null || !"trigger".equals(trigger.kind) || index >= trigger.exampleCount
                || !example.equals(trigger.id + ":" + index)) {
            throw new NoSuchElementException("Scene Example selection does not identify a compiled Example.");
        }
        return trigger;
    }

    private BitSet coverage(final RailixValue.ObjectValue document) throws IOException {
        if (!(document.values().get("coverage_bits") instanceof RailixValue.StringValue encoded)
                || encoded.value().length() > ((nodeCount + 7) / 8 + 2) / 3 * 4) {
            throw new IOException("Application coverage bitmap exceeds the scene node count.");
        }
        final BitSet bits;
        try {
            bits = BitSet.valueOf(Base64.getDecoder().decode(encoded.value()));
        } catch (final IllegalArgumentException failure) {
            throw new IOException("Application coverage bitmap is invalid.", failure);
        }
        if (bits.length() > nodeCount || bits.cardinality() != observedNumber(document.values().get("covered_steps"))) {
            throw new IOException("Application coverage bitmap does not match its count or scene.");
        }
        return bits;
    }

    private Map<String, RailixValue.ObjectValue> metricSeries(final RailixValue.ObjectValue metrics,
                                                            final String name) throws IOException {
        final Map<String, RailixValue.ObjectValue> series = new HashMap<>();
        for (final RailixValue value : observedArray(metrics, name, nodeCount)) {
            final RailixValue.ObjectValue entry = observedObject(value);
            if (!(entry.values().get("id") instanceof RailixValue.StringValue id)) {
                throw new IOException("Application metric series has no identifier.");
            }
            final Part part = identities.get(id.value());
            if (part == null || part.node < 0 || "app".equals(part.kind)
                    || ("flows".equals(name) ? !"trigger".equals(part.kind) : !part.metrics)
                    || series.putIfAbsent(id.value(), observedCounters(entry.values().get("metrics"))) != null) {
                throw new IOException("Application metric series does not match the scene.");
            }
        }
        return series;
    }

    private static RailixValue.ObjectValue observedCounters(final RailixValue value) throws IOException {
        final RailixValue.ObjectValue counters = observedObject(value);
        for (final String counter : COUNTERS) {
            observedNumber(counters.values().get(counter));
        }
        return counters;
    }

    private static RailixValue.ObjectValue observedObject(final RailixValue value) throws IOException {
        if (!(value instanceof RailixValue.ObjectValue object)) {
            throw new IOException("Application observation must contain an object.");
        }
        return object;
    }

    private static List<RailixValue> observedArray(final RailixValue.ObjectValue value, final String name,
                                                  final int limit) throws IOException {
        if (!(value.values().get(name) instanceof RailixValue.ArrayValue array) || array.values().size() > limit) {
            throw new IOException("Application observation array is missing or exceeds the scene node count.");
        }
        return array.values();
    }

    private static long observedNumber(final RailixValue value) throws IOException {
        if (value instanceof RailixValue.NumberValue number) {
            try {
                final long result = number.value().longValueExact();
                if (result >= 0) {
                    return result;
                }
            } catch (final ArithmeticException failure) {
                throw new IOException("Application observation count must be a non-negative long integer.", failure);
            }
        }
        throw new IOException("Application observation count must be a non-negative long integer.");
    }

    private static List<Part> layout(final Part app) {
        final List<Part> order = new ArrayList<>();
        order.add(app);
        for (int index = 0; index < order.size(); index++) {
            final Part node = order.get(index);
            node.outgoing.sort(Comparator.comparing(Route::outcome).thenComparing(edge -> edge.to.id));
            for (final Route edge : node.outgoing) {
                edge.to.depth = node.depth + 1;
                order.add(edge.to);
            }
        }
        for (int index = order.size() - 1; index >= 0; index--) {
            final Part node = order.get(index);
            node.rows = node.outgoing.isEmpty() ? 1 : node.outgoing.stream().mapToInt(edge -> edge.to.rows).sum();
        }
        for (final Part node : order) {
            node.box = new Box(node.depth * COLUMN, (node.row + (node.rows - 1) / 2.0) * ROW,
                    NODE_WIDTH, NODE_HEIGHT);
            int row = node.row;
            for (final Route edge : node.outgoing) {
                edge.to.row = row;
                row += edge.to.rows;
            }
        }
        return order;
    }

    private static Map<Part, Part> groups(final List<Part> order, final Map<String, RailixValue> styles) {
        final Map<Part, Part> result = new java.util.IdentityHashMap<>();
        for (final Part node : order) {
            if (node.group.isEmpty() || result.containsKey(node)) {
                continue;
            }
            final List<Part> members = new ArrayList<>();
            final ArrayDeque<Part> pending = new ArrayDeque<>();
            pending.add(node);
            while (!pending.isEmpty()) {
                final Part member = pending.removeFirst();
                members.add(member);
                member.outgoing.stream().map(edge -> edge.to).filter(child -> child.group.equals(node.group))
                        .forEach(pending::addLast);
            }
            final String first = members.stream().map(member -> member.id).min(String::compareTo).orElseThrow();
            final Map<String, RailixValue> style = new LinkedHashMap<>(object(styles.get(node.group)));
            if (!style.containsKey("icon") && node.style.containsKey("icon")) {
                style.put("icon", node.style.get("icon"));
            }
            final String name = style.containsKey("name") ? text(style, "name") : node.group;
            final String prefix = "region:group:" + node.group + ":" + first;
            final Part content = hierarchy(sections(members, prefix), "index:" + prefix, false);
            final Part group = container("region", "group-region:" + node.group + ":" + first, name, node.group, style,
                    "index".equals(content.kind) ? content.children : List.of(content));
            members.forEach(member -> result.put(member, group));
        }
        return result;
    }

    private static List<Part> sections(final List<Part> parts, final String prefix) {
        final Set<Part> members = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        members.addAll(parts);
        final Set<Part> included = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<Part> result = new ArrayList<>();
        for (final Part start : parts) {
            if (!included.add(start)) {
                continue;
            }
            final List<Part> corridor = new ArrayList<>();
            corridor.add(start);
            Part current = start;
            while ("step".equals(current.kind) && current.outgoing.size() == 1) {
                final Part next = current.outgoing.getFirst().to;
                if (!"step".equals(next.kind) || next.outgoing.size() != 1 || !members.contains(next)
                        || !included.add(next)) {
                    break;
                }
                corridor.add(next);
                current = next;
            }
            if (corridor.size() > 8) {
                result.add(hierarchy(corridor, prefix + ":" + start.id, true));
            } else {
                result.addAll(corridor);
            }
        }
        if (result.size() < parts.size()) {
            compact(parts, result);
        }
        return result;
    }

    /* An outer region occupies one station; its internal extent must not shrink adjacent junctions. */
    private static List<Part> compact(final List<Part> parts, final List<Part> regions) {
        final Map<Part, Part> owners = new java.util.IdentityHashMap<>();
        final Set<Part> units = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        units.addAll(parts);
        for (final Part region : regions) {
            final ArrayDeque<Part> pending = new ArrayDeque<>();
            pending.add(region);
            while (!pending.isEmpty()) {
                final Part part = pending.removeFirst();
                owners.put(part, region);
                if (!units.contains(part)) {
                    pending.addAll(part.children);
                }
            }
        }
        final List<Route> edges = parts.stream().flatMap(part -> part.outgoing.stream()).toList();
        regions.forEach(part -> part.outgoing.clear());
        final Set<Part> incoming = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (final Route edge : edges) {
            final Part from = owners.get(edge.from);
            final Part to = owners.get(edge.to);
            if (from != null && to != null && from != to) {
                from.outgoing.add(new Route(edge.id, from, to, edge.outcome));
                incoming.add(to);
            }
        }
        final Part origin = new Part("", "index", "", "", "", Map.of(), List.of());
        regions.stream().filter(part -> !incoming.contains(part))
                .forEach(part -> origin.outgoing.add(new Route(part.id, origin, part, "next")));
        return layout(origin);
    }

    private static Part hierarchy(final List<Part> parts, final String id, final boolean aggregate) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        final String kind = aggregate ? "region" : "index";
        if (parts.size() <= 8) {
            return container(kind, id, "Flow section", "", Map.of(), List.copyOf(parts));
        }
        final Box bounds = bounds(parts);
        final List<Part> sorted = new ArrayList<>(parts);
        final Comparator<Part> coordinate = bounds.width >= bounds.height
                ? Comparator.comparingDouble(part -> part.box.x)
                : Comparator.comparingDouble(part -> part.box.y);
        sorted.sort(coordinate.thenComparing(part -> part.id));
        final int middle = sorted.size() / 2;
        return container(kind, id, "Flow section", "", Map.of(), List.of(
                hierarchy(sorted.subList(0, middle), id + ":0", aggregate),
                hierarchy(sorted.subList(middle, sorted.size()), id + ":1", aggregate)
        ));
    }

    private static Part container(final String kind, final String id, final String name, final String group,
                               final Map<String, RailixValue> style, final List<Part> children) {
        final Part result = new Part(id, kind, name, "", group, style, children);
        result.box = bounds(children).expand(18);
        result.count = children.stream().mapToInt(child -> child.count).sum();
        return result;
    }

    /* Each level owns a fixed footprint. Children shrink inside it instead of extending the world. */
    private static void place(final Part part, final Box space) {
        if (part.children.isEmpty()) {
            final double width = Math.min(space.width, space.height * NODE_WIDTH / NODE_HEIGHT);
            final double height = width * NODE_HEIGHT / NODE_WIDTH;
            part.box = new Box(space.x + (space.width - width) / 2, space.y + (space.height - height) / 2,
                    width, height);
            return;
        }
        // Spatial indexes do not introduce another visual layout or move shared lanes.
        final boolean index = "index".equals(part.kind);
        final Box original = index ? part.box : bounds(part.children);
        part.box = space;
        final double insetX = index ? 0 : space.width * 0.06;
        final double insetY = index ? 0 : space.height * 0.12;
        final double scaleX = (space.width - insetX * 2) / original.width;
        final double scaleY = (space.height - insetY * 2) / original.height;
        for (final Part child : part.children) {
            place(child, new Box(space.x + insetX + (child.box.x - original.x) * scaleX,
                    space.y + insetY + (child.box.y - original.y) * scaleY,
                    child.box.width * scaleX, child.box.height * scaleY));
        }
    }

    private static Box bounds(final List<Part> parts) {
        Box result = parts.getFirst().box;
        for (int index = 1; index < parts.size(); index++) {
            result = result.union(parts.get(index).box);
        }
        return result;
    }

    private static boolean expands(final Part part, final double scale, final Part focused) {
        final boolean reveal = focused != null && part != focused
                && part.first <= focused.first && part.last >= focused.last;
        final boolean readableSmallFlow = part.group.isEmpty() && part.count <= 8
                && part.box.width * scale >= 180;
        return !part.children.isEmpty() && ("index".equals(part.kind) || reveal || readableSmallFlow
                || part.box.width * scale > REGION_DETAIL_WIDTH || part.box.height * scale > REGION_DETAIL_HEIGHT);
    }

    private static Part representative(final Part leaf, final double scale, final Part focused,
                                       final List<Part> constrained) {
        int low = 0;
        int high = constrained.size() - 1;
        while (low <= high) {
            final int middle = (low + high) >>> 1;
            final Part part = constrained.get(middle);
            if (leaf.first < part.first) {
                high = middle - 1;
            } else if (leaf.first > part.last) {
                low = middle + 1;
            } else {
                return part;
            }
        }
        Part result = leaf;
        for (Part part = leaf.parent; part != null; part = part.parent) {
            if (!expands(part, scale, focused)) {
                result = part;
            }
        }
        return result;
    }

    private static Map<String, String> parameters(final String query, final Set<String> allowed) {
        final Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (final String pair : query.split("&", -1)) {
            final String[] parts = pair.split("=", 2);
            final String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length != 2 || !allowed.contains(key)
                    || result.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException("Scene query contains an unknown, duplicate or incomplete parameter.");
            }
        }
        return result;
    }

    private static double number(final Map<String, String> parameters, final String name,
                                 final double defaultValue, final boolean positive) {
        if (!parameters.containsKey(name)) {
            return defaultValue;
        }
        final double value;
        try {
            value = Double.parseDouble(parameters.get(name));
        } catch (final NumberFormatException failure) {
            throw new IllegalArgumentException("Scene " + name + " must be a finite number.", failure);
        }
        if (!Double.isFinite(value) || positive && value <= 0) {
            throw new IllegalArgumentException("Scene " + name + " must be finite" + (positive ? " and positive." : "."));
        }
        return value;
    }

    private static Map<String, RailixValue> object(final RailixValue value) {
        return value instanceof RailixValue.ObjectValue object ? object.values() : Map.of();
    }

    private static List<RailixValue> array(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.ArrayValue) value.values().get(name)).values();
    }

    private static String text(final Map<String, RailixValue> value, final String name) {
        return value.get(name) instanceof RailixValue.StringValue text ? text.value() : "";
    }

    private static String identity(final String source, final String metadata) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(metadata.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", failure);
        }
    }

    private record Box(double x, double y, double width, double height) {
        Box expand(final double margin) {
            return new Box(x - margin, y - margin, width + margin * 2, height + margin * 2);
        }

        Box union(final Box other) {
            final double left = Math.min(x, other.x);
            final double top = Math.min(y, other.y);
            return new Box(left, top, Math.max(x + width, other.x + other.width) - left,
                    Math.max(y + height, other.y + other.height) - top);
        }

        boolean intersects(final Box other) {
            return x <= other.x + other.width && x + width >= other.x
                    && y <= other.y + other.height && y + height >= other.y;
        }

        RailixValue.ObjectValue value() {
            return RailixValue.object(Map.of("x", decimal(x), "y", decimal(y),
                    "width", decimal(width), "height", decimal(height)));
        }
    }

    private static final class Part {
        private final String id;
        private final String kind;
        private final String name;
        private final String use;
        private final String group;
        private final Map<String, RailixValue> style;
        private final List<Part> children;
        private final List<Route> outgoing = new ArrayList<>();
        private Box box;
        private int depth;
        private int row;
        private int rows;
        private int first;
        private int last;
        private int count;
        private int node = -1;
        private boolean metrics;
        private int exampleCount;
        private String iconRef = "";
        private Part parent;

        private Part(final String id, final String kind, final String name, final String use, final String group,
                     final Map<String, RailixValue> style, final List<Part> children) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.use = use;
            this.group = group;
            this.style = style;
            this.children = children;
            count = "end".equals(kind) ? 0 : 1;
        }

        private RailixValue.ObjectValue value(final boolean expanded) {
            final Map<String, RailixValue> value = new LinkedHashMap<>(box.value().values());
            value.put("id", RailixValue.string(id));
            value.put("kind", RailixValue.string("index".equals(kind) ? "region" : kind));
            value.put("name", RailixValue.string(name));
            value.put("count", RailixValue.number(count));
            if (!iconRef.isEmpty()) {
                value.put("icon_ref", RailixValue.string(iconRef));
            }
            if ("trigger".equals(kind)) {
                value.put("example_count", RailixValue.number(exampleCount));
            }
            if (!use.isEmpty()) {
                value.put("use", RailixValue.string(use));
            }
            if (!group.isEmpty()) {
                value.put("group", RailixValue.string(group));
            }
            if (!children.isEmpty()) {
                value.put("expanded", RailixValue.bool(expanded));
            }
            for (final String field : List.of("color", "boundary", "shape", "aspect", "roundness")) {
                if (style.containsKey(field)) value.put(field, style.get(field));
            }
            return RailixValue.object(value);
        }
    }

    private record Route(String id, Part from, Part to, String outcome) {
        RailixValue.ObjectValue value(final String identity, final Part start, final Part end) {
            final double startX = start.box.x + start.box.width / 2;
            final double startY = start.box.y + start.box.height / 2;
            final double endX = end.box.x + end.box.width / 2;
            final double endY = end.box.y + end.box.height / 2;
            final boolean horizontal = Math.abs(endX - startX) >= Math.abs(endY - startY);
            final double direction = Math.signum(horizontal ? endX - startX : endY - startY);
            final double x1 = startX + (horizontal ? start.box.width * direction / 2 : 0);
            final double y1 = startY + (horizontal ? 0 : start.box.height * direction / 2);
            final double x2 = endX - (horizontal ? end.box.width * direction / 2 : 0);
            final double y2 = endY - (horizontal ? 0 : end.box.height * direction / 2);
            final double middle = horizontal ? (x1 + x2) / 2 : (y1 + y2) / 2;
            return RailixValue.object(Map.of("id", RailixValue.string(identity),
                    "from", RailixValue.string(start.id), "to", RailixValue.string(end.id),
                    "outcome", RailixValue.string(outcome), "points", RailixValue.array(List.of(
                            point(x1, y1), horizontal ? point(middle, y1) : point(x1, middle),
                            horizontal ? point(middle, y2) : point(x2, middle), point(x2, y2)))));
        }

        private Box bounds() {
            Box bounds = from.box.union(to.box);
            for (Part part = from.parent; part != null && !(part.first <= to.first && part.last >= to.last);
                 part = part.parent) {
                bounds = bounds.union(part.box);
            }
            for (Part part = to.parent; part != null && !(part.first <= from.first && part.last >= from.last);
                 part = part.parent) {
                bounds = bounds.union(part.box);
            }
            return bounds;
        }

        private static RailixValue point(final double x, final double y) {
            return RailixValue.array(List.of(decimal(x), decimal(y)));
        }
    }

    /** Bounding-volume route index also prunes whole batches internal to a collapsed region. */
    private static final class Routes {
        private final Route edge;
        private final Routes left;
        private final Routes right;
        private final Box box;
        private final int first;
        private final int last;

        private Routes(final List<Route> edges) {
            this(sorted(edges), 0, edges.size());
        }

        private Routes(final List<Route> edges, final int start, final int end) {
            if (end - start == 1) {
                edge = edges.get(start);
                left = null;
                right = null;
                box = edge.bounds();
                first = Math.min(edge.from.first, edge.to.first);
                last = Math.max(edge.from.last, edge.to.last);
            } else {
                edge = null;
                final int middle = (start + end) >>> 1;
                left = new Routes(edges, start, middle);
                right = new Routes(edges, middle, end);
                box = left.box.union(right.box);
                first = Math.min(left.first, right.first);
                last = Math.max(left.last, right.last);
            }
        }

        private static List<Route> sorted(final List<Route> edges) {
            final List<Route> sorted = new ArrayList<>(edges);
            sorted.sort(Comparator.comparingInt((Route route) -> Math.min(route.from.first, route.to.first))
                    .thenComparing(Route::id));
            return sorted;
        }
    }

    private static RailixValue.NumberValue decimal(final double value) {
        return RailixValue.number(BigDecimal.valueOf(value));
    }
}
