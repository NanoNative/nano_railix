package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Immutable, derived Creator geometry, absent from compiled applications and project files.
 * Functional connections form a rooted acyclic graph. Each station has one spatial identity
 * even when several routes or sources reach it.
 */
final class CreatorScene {
    static final int MAX_NODES = 2048;
    static final int MAX_SEGMENTS = 4096;
    private static final Set<String> VIEW_PARAMETERS = Set.of("x", "y", "width", "height", "scale", "focus", "inside");
    private static final double NODE_WIDTH = 168;
    private static final double NODE_HEIGHT = 64;
    private static final double COLUMN = 248;
    // Reserve ground between 136-unit foundations for raised buildings and lateral belt exits.
    private static final double ROW = 224;
    private static final double REGION_DETAIL_WIDTH = 520;
    private static final double REGION_DETAIL_HEIGHT = 280;
    private static final java.util.regex.Pattern NODE_DIAGNOSTIC = java.util.regex.Pattern.compile("^nodes\\[([0-9]{1,9})](?:\\.|$)");
    private final String source;
    private final RailixValue.ObjectValue metadata;
    private final String revision;
    private final Part root;
    private final Routes routes;
    private final List<Part> leaves;
    private final List<Part> indexedNodes;
    private final Map<String, RailixValue> icons;
    private final Map<String, Part> identities = new HashMap<>();
    private final Map<String, Routing> routingViews = new LinkedHashMap<>();
    private final double[] detailScales;

    CreatorScene(final String source, final RailixValue.ObjectValue metadata, final StepCatalog catalog) {
        this.source = source;
        final Map<String, RailixValue> presentation = new LinkedHashMap<>();
        for (final String field : List.of("groups", "steps", "reuse")) if (metadata.values().containsKey(field)) presentation.put(field, metadata.values().get(field));
        this.metadata = RailixValue.object(presentation);
        revision = identity(source, RailixJson.write(this.metadata));
        final RailixValue.ObjectValue project =
                (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final Map<String, RailixValue> styles = object(metadata.values().get("steps"));
        final Map<String, RailixValue> groups = new HashMap<>();
        array(metadata, "groups").forEach(value -> groups.put(text(object(value), "id"), value));
        final Map<String, List<RailixValue>> shared = new HashMap<>();
        for (final RailixValue relationship : array(metadata, "reuse")) {
            final String family = text(object(relationship), "id");
            for (final RailixValue occurrence : array((RailixValue.ObjectValue) relationship, "uses")) {
                final var use = object(occurrence);
                object(use.get("members")).forEach((role, id) -> shared.computeIfAbsent(((RailixValue.StringValue) id).value(), ignored -> new ArrayList<>()).add(RailixValue.string(family + "/" + role)));
                final String group = text(use, "group");
                if (!group.isEmpty()) shared.computeIfAbsent("group:" + group, ignored -> new ArrayList<>()).add(RailixValue.string(family));
            }
        }
        groups.replaceAll((id, value) -> shared.containsKey("group:" + id)
                ? CreatorChanges.with((RailixValue.ObjectValue) value, "shared", RailixValue.array(shared.get("group:" + id))) : value);
        final Map<String, Part> nodes = new LinkedHashMap<>();
        int metricIndex = 0;
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
            final Map<String, RailixValue> style = new LinkedHashMap<>(object(styles.get(id)));
            if (shared.containsKey(id)) style.put("shared", RailixValue.array(shared.get(id)));
            final String name = style.containsKey("name") ? text(style, "name")
                    : "app".equals(kind) ? text(project.values(), "id") : definition.displayName();
            final Part part = new Part(id, kind, name, use, text(style, "group"), style, List.of());
            part.node = nodes.size();
            part.metrics = !RailixValue.bool(false).equals(node.get("metrics"));
            if (!"app".equals(kind)) {
                part.members = new int[]{part.node, part.node};
                if (part.metrics) {
                    part.metricMembers = new int[]{metricIndex, metricIndex++};
                } else {
                    part.disabled = 1;
                }
            }
            part.exampleCount = node.get("examples") instanceof RailixValue.ArrayValue examples
                    ? examples.values().size() : 0;
            nodes.put(id, part);
        }
        indexedNodes = List.copyOf(nodes.values());
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
        final Set<Part> destinations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final boolean converging = edges.stream().anyMatch(edge -> !destinations.add(edge.to));
        final Map<Part, Part> occurrences = groups(order, groups);
        retainAcyclicGroups(occurrences, edges);
        if (!occurrences.isEmpty()) {
            order.forEach(node -> node.outgoing.clear());
            for (final Route edge : edges) {
                final Part from = occurrences.getOrDefault(edge.from, edge.from);
                final Part to = occurrences.getOrDefault(edge.to, edge.to);
                if (from != to) {
                    from.outgoing.add(new Route(edge.id, from, to, edge.outcome));
                }
            }
        }
        final List<Part> grouped = layout(app);
        final Map<Part, Part> areas = areas(grouped);
        if (!areas.isEmpty()) {
            grouped.forEach(part -> part.outgoing.clear());
            for (final Route edge : edges) {
                final Part originalFrom = occurrences.getOrDefault(edge.from, edge.from);
                final Part originalTo = occurrences.getOrDefault(edge.to, edge.to);
                final Part from = areas.getOrDefault(originalFrom, originalFrom);
                final Part to = areas.getOrDefault(originalTo, originalTo);
                if (from != to) from.outgoing.add(new Route(edge.id, from, to, edge.outcome));
            }
            order.forEach(part -> {
                final Part area = areas.get(occurrences.getOrDefault(part, part));
                if (area != null) occurrences.put(part, area);
            });
        }
        final List<Part> world = new ArrayList<>();
        world.add(app);
        double flowTop = 0;
        for (final Route triggerRoute : converging ? List.<Route>of() : app.outgoing) {
            final Part trigger = triggerRoute.to;
            final double flowHeight = Math.max(280, trigger.rows * ROW);
            trigger.box = new Box(COLUMN, flowTop + (flowHeight - NODE_HEIGHT) / 2, NODE_WIDTH, NODE_HEIGHT);
            world.add(trigger);
            final List<Part> units = new ArrayList<>();
            final Set<Part> added = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            final ArrayDeque<Part> pending = new ArrayDeque<>();
            trigger.outgoing.forEach(edge -> pending.add(edge.to));
            while (!pending.isEmpty()) {
                final Part node = pending.removeFirst();
                final Part unit = occurrences.getOrDefault(node, node);
                if (!added.add(unit)) continue;
                units.add(unit);
                node.outgoing.forEach(edge -> pending.addLast(edge.to));
            }
            if (!units.isEmpty()) {
                final Part section = hierarchy(sections(units, "region:" + trigger.id), "index:" + trigger.id, false);
                // Top-level stations keep their natural scale; only nested group contents use smaller levels.
                place(section, new Box(COLUMN * 2, flowTop, section.box.width, section.box.height));
                if (trigger.outgoing.size() == 1) {
                    final Box entry = trigger.outgoing.getFirst().to.box;
                    trigger.box = new Box(COLUMN, entry.y + (entry.height - NODE_HEIGHT) / 2, NODE_WIDTH, NODE_HEIGHT);
                }
                world.add(section);
            }
            flowTop += flowHeight + 120;
        }
        if (converging) {
            final List<Part> visible = layout(app).stream().filter(part -> part != app).toList();
            if (!visible.isEmpty()) {
                final Part section = hierarchy(sections(visible, "region:connected"), "index:connected", false);
                place(section, section.box);
                world.add(section);
            }
        }
        app.box = new Box(0, app.outgoing.isEmpty() ? 108
                : (app.outgoing.getFirst().to.box.y + app.outgoing.getLast().to.box.y) / 2, NODE_WIDTH, NODE_HEIGHT);
        root = container("index", "world", "Application", "", Map.of(),
                world.size() <= 8 ? world : List.of(app, container("region", "area:flows", "Flows", "", Map.of(),
                        List.of(hierarchy(world.subList(1, world.size()), "index:flows", false)))));
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
                for (final Part child : part.children) {
                    part.members = union(part.members, child.members);
                    part.metricMembers = union(part.metricMembers, child.metricMembers);
                    part.disabled += child.disabled;
                    part.groupCount += child.groupCount + ("region".equals(child.kind) && !child.group.isEmpty() ? 1 : 0);
                }
            }
        }
        final Map<String, Part> groupFocus = new java.util.TreeMap<>();
        hierarchy.stream().filter(part -> "region".equals(part.kind) && !part.group.isEmpty()).forEach(part ->
                groupFocus.merge(part.group, part, (first, next) -> first.id.compareTo(next.id) <= 0 ? first : next));
        groupFocus.forEach((group, part) -> {
            identities.put("group:" + group, part);
        });
        hierarchy.stream().filter(part -> "region".equals(part.kind) && (!part.group.isEmpty() || part.id.startsWith("area:")))
                .forEach(part -> {
                    final List<Part> members = leaves.subList(part.first, part.last + 1).stream()
                            .filter(member -> !"end".equals(member.kind)).toList();
                    final int count = Math.min(6, members.size());
                    final List<RailixValue> roof = new ArrayList<>(count);
                    final double minX = members.stream().mapToDouble(member -> member.box.x + member.box.width / 2).min().orElse(0);
                    final double maxX = members.stream().mapToDouble(member -> member.box.x + member.box.width / 2).max().orElse(minX);
                    final double minY = members.stream().mapToDouble(member -> member.box.y + member.box.height / 2).min().orElse(0);
                    final double maxY = members.stream().mapToDouble(member -> member.box.y + member.box.height / 2).max().orElse(minY);
                    for (int index = 0; index < count; index++) {
                        final Part member = members.get(count == 1 ? 0
                                : (int) Math.round((double) index * (members.size() - 1) / (count - 1)));
                        roof.add(RailixValue.object(Map.of(
                                "id", RailixValue.string(member.id),
                                "node", RailixValue.number(member.node),
                                "use", RailixValue.string(member.use),
                                "x", decimal(maxX == minX ? .5 : (member.box.x + member.box.width / 2 - minX) / (maxX - minX)),
                                "y", decimal(maxY == minY ? .5 : (member.box.y + member.box.height / 2 - minY) / (maxY - minY)),
                                "branch", RailixValue.bool(member.exits > 1))));
                    }
                    part.roof = List.copyOf(roof);
                });
        routes = edges.isEmpty() ? null : new Routes(edges);
        detailScales = hierarchy.stream().filter(part -> "region".equals(part.kind))
                .mapToDouble(CreatorScene::detailScale).distinct().sorted().toArray();
    }

    boolean matches(final String source, final RailixValue.ObjectValue metadata) {
        return this.source.equals(source) && List.of("groups", "steps", "reuse").stream()
                .allMatch(field -> java.util.Objects.equals(this.metadata.values().get(field), metadata.values().get(field)));
    }

    /** Returns a bounded viewport projection; an omitted viewport fits the world or requested identity. */
    RailixValue.ObjectValue view(final String query) {
        return view(parameters(query, VIEW_PARAMETERS), new LinkedHashMap<>());
    }

    private RailixValue.ObjectValue view(final Map<String, String> parameters,
                                        final Map<String, Connection> connections) {
        return view(parameters, connections, MAX_NODES);
    }

    private RailixValue.ObjectValue view(final Map<String, String> parameters,
                                        final Map<String, Connection> connections, final int budget) {
        connections.clear();
        if (parameters.containsKey("focus") && parameters.get("focus").isBlank()) {
            throw new IllegalArgumentException("Scene focus must identify a Step or region.");
        }
        final Part focused = parameters.containsKey("focus") ? identities.get(parameters.get("focus")) : null;
        if (parameters.containsKey("focus") && focused == null) {
            throw new NoSuchElementException("Scene focus does not identify a Step or region.");
        }
        final Part inside = parameters.containsKey("inside") ? identities.get(parameters.get("inside")) : null;
        if (parameters.containsKey("inside") && (inside == null || inside.children.isEmpty())) {
            throw new NoSuchElementException("Entered group is not in this scene.");
        }
        final Box basis = focused != null ? focused.box : inside != null ? inside.box : root.box;
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
        final ArrayDeque<Part> pending = new ArrayDeque<>(inside == null ? root.children : List.of(inside));
        boolean limited = false;
        while (!pending.isEmpty()) {
            final Part part = pending.removeFirst();
            if (!part.box.intersects(viewport)) {
                continue;
            }
            final boolean expand = expands(part, scale, focused, inside);
            final boolean boundary = expand && "region".equals(part.kind) && (!part.group.isEmpty()
                    || part == inside || inside == null && part.children.stream().noneMatch(child -> "region".equals(child.kind) && expands(child, scale, focused, inside)));
            if (expand && values.size() + pending.size() + part.children.size() + (boundary ? 1 : 0) <= budget) {
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
        if (routes != null) {
            final ArrayDeque<Routes> routeQueue = new ArrayDeque<>();
            routeQueue.add(routes);
            while (!routeQueue.isEmpty()) {
                final Routes next = routeQueue.removeLast();
                if (!next.box.intersects(viewport)) {
                    continue;
                }
                if (inside != null && (next.last < inside.first || next.first > inside.last)) continue;
                if (inside != null && (next.fromFirst < inside.first || next.toFirst < inside.first
                        || next.fromLast > inside.last || next.toLast > inside.last)) {
                    if (next.left != null) { routeQueue.addLast(next.right); routeQueue.addLast(next.left); }
                    continue;
                }
                final Part enclosing = representative(leaves.get(next.first), scale, focused, constrained, inside);
                if (enclosing.last >= next.last) {
                    continue;
                }
                final Part start = representative(leaves.get(next.fromFirst), scale, focused, constrained, inside);
                final Part end = representative(leaves.get(next.toFirst), scale, focused, constrained, inside);
                if (start.last < next.fromLast || end.last < next.toLast
                        || start.children.isEmpty() && next.outcome.isEmpty() && next.left != null) {
                    routeQueue.addLast(next.right);
                    routeQueue.addLast(next.left);
                    continue;
                }
                if (start == end || !Route.bounds(start, end).intersects(viewport)) {
                    continue;
                }
                connections.computeIfAbsent(start.id + ">" + end.id, ignored -> new Connection(start, end, next.outcome))
                        .add(next);
            }
        }
        final Map<String, RailixValue> response = new LinkedHashMap<>();
        response.put("revision", RailixValue.string(revision));
        response.put("bounds", root.box.value());
        response.put("scope", (inside == null ? root : inside).box.value());
        final List<RailixValue> location = new ArrayList<>();
        for (Part ancestor = focused != null ? focused.children.isEmpty() ? focused.parent : focused : inside;
             ancestor != null && ancestor.parent != null; ancestor = ancestor.parent) {
            if (!ancestor.group.isEmpty() || ancestor.id.startsWith("area:")
                    || "region".equals(ancestor.kind) && (ancestor == focused || ancestor == inside)) {
                Part entry = ancestor;
                while (!entry.children.isEmpty()) entry = entry.children.stream()
                        .min(Comparator.comparingDouble(child -> child.box.x)).orElseThrow();
                final Map<String, RailixValue> part = new LinkedHashMap<>();
                part.put("id", RailixValue.string(ancestor.id));
                part.put("name", RailixValue.string(ancestor.displayName()));
                part.put("group", RailixValue.string(ancestor.group));
                part.put("count", RailixValue.number(ancestor.count));
                part.put("unit", decimal(Math.min(1, entry.stationScale())));
                location.addFirst(RailixValue.object(part));
            }
        }
        response.put("location", RailixValue.array(location));
        if (inside != null) {
            response.put("inside", RailixValue.string(inside.id));
            Part entry = inside;
            // Flow advances along X; a Y-sorted spatial partition may begin on an upper branch.
            while (!entry.children.isEmpty()) entry = entry.children.stream()
                    .min(Comparator.comparingDouble(child -> child.box.x)).orElseThrow();
            response.put("entry", entry.value(false));
        }
        if (focused != null) {
            response.put("target", focused.value(false));
            response.put("focus", focused.box.value());
            response.put("focus_min_scale", RailixValue.number(BigDecimal.valueOf(focusScale(focused, inside))));
        }
        response.put("nodes", RailixValue.array(values));
        final Map<String, RailixValue> visibleIcons = new LinkedHashMap<>();
        for (final RailixValue value : values) {
            if (object(value).get("icon_ref") instanceof RailixValue.StringValue reference) {
                visibleIcons.putIfAbsent(reference.value(), icons.get(reference.value()));
            }
        }
        response.put("icons", RailixValue.object(visibleIcons));
        final Routing routing = routing(scale, focused, inside, constrained);
        if (connections.values().stream().anyMatch(link -> !routing.paths.containsKey(link.from.id + ">" + link.to.id))) {
            if (budget > 1) return view(parameters, connections, Math.max(1, budget / 2));
            return collapsedView(response, connections, inside == null ? root : inside);
        }
        final List<RailixValue> links = connections.values().stream().<RailixValue>map(connection ->
                connection.value(routing.paths.get(connection.from.id + ">" + connection.to.id),
                        routing.bridges.getOrDefault(connection.from.id + ">" + connection.to.id, List.of()))).toList();
        if (links.stream().mapToInt(link -> array((RailixValue.ObjectValue) link, "points").size() - 1).sum() > MAX_SEGMENTS) {
            if (budget > 1) return view(parameters, connections, Math.max(1, budget / 2));
            return collapsedView(response, connections, inside == null ? root : inside);
        }
        response.put("links", RailixValue.array(links));
        response.put("limited", RailixValue.bool(limited || budget < MAX_NODES));
        return RailixValue.object(response);
    }

    private static RailixValue.ObjectValue collapsedView(final Map<String, RailixValue> response,
                                                        final Map<String, Connection> connections, final Part scope) {
        connections.clear();
        response.put("nodes", RailixValue.array(List.of(scope.value(false))));
        response.put("links", RailixValue.array(List.of()));
        response.put("icons", RailixValue.object(Map.of()));
        response.put("limited", RailixValue.bool(true));
        return RailixValue.object(response);
    }

    private Routing routing(final double scale, final Part focused, final Part inside,
                                         final List<Part> constrained) {
        final int threshold = java.util.Arrays.binarySearch(detailScales, scale);
        final List<String> revealed = new ArrayList<>();
        for (Part part = focused; part != null; part = part.parent)
            if (!part.children.isEmpty() && !expands(part, scale, null, inside)) revealed.add(part.id);
        final String key = (threshold < 0 ? -threshold - 1 : threshold + 1) + ":"
                + String.join(",", revealed) + ":" + (inside == null ? "" : inside.id) + ":"
                + constrained.stream().map(part -> part.id).collect(java.util.stream.Collectors.joining(","));
        synchronized (routingViews) {
            final Routing cached = routingViews.get(key);
            if (cached != null) return cached;
        }
        // Route the complete detail level, not just its viewport. Panning cannot move an existing lane.
        final Map<Part, Part> owners = new java.util.IdentityHashMap<>();
        final Map<String, Connection> links = new LinkedHashMap<>();
        final Set<Part> stations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (final Part leaf : leaves) {
            if (inside != null && (leaf.first < inside.first || leaf.last > inside.last)) continue;
            final Part owner = representative(leaf, scale, focused, constrained, inside);
            owners.put(leaf, owner);
            stations.add(owner);
        }
        final ArrayDeque<Routes> pending = new ArrayDeque<>();
        if (routes != null) pending.add(routes);
        while (!pending.isEmpty()) {
            final Routes next = pending.removeLast();
            if (next.left != null) { pending.add(next.right); pending.add(next.left); continue; }
            final Part from = owners.get(leaves.get(next.fromFirst));
            final Part to = owners.get(leaves.get(next.toFirst));
            if (from == null || to == null || from == to) continue;
            links.computeIfAbsent(from.id + ">" + to.id, ignored -> new Connection(from, to, next.outcome)).add(next);
        }
        final Routing result = new Routing(stations, links.values());
        synchronized (routingViews) {
            if (routingViews.size() == 3) routingViews.remove(routingViews.keySet().iterator().next());
            routingViews.put(key, result);
        }
        return result;
    }

    static Map<String, String> observationParameters(final String query) {
        if (query != null && query.length() > 8192) {
            throw new IllegalArgumentException("Scene observation query exceeds 8192 characters.");
        }
        final Set<String> allowed = new java.util.HashSet<>(VIEW_PARAMETERS);
        allowed.addAll(Set.of("revision", "example", "metrics", "members", "cursor", "cases"));
        final Map<String, String> parameters = parameters(query, allowed);
        if (!parameters.getOrDefault("revision", "").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Scene observations require the current scene revision.");
        }
        if (parameters.containsKey("example")
                && !parameters.get("example").matches("[a-z][a-z0-9-]{0,63}:[0-9]{1,10}")) {
            throw new IllegalArgumentException("An Example selection requires a compiled Example ID.");
        }
        if (parameters.containsKey("metrics")) {
            final String[] metrics = parameters.get("metrics").split(",", -1);
            if (java.util.Arrays.stream(metrics).anyMatch(String::isBlank)
                    || java.util.Arrays.stream(metrics).distinct().count() != metrics.length) {
                throw new IllegalArgumentException("Metric selection requires distinct non-blank identifiers.");
            }
        }
        if (parameters.containsKey("cursor") && (!parameters.containsKey("members")
                || !parameters.get("cursor").matches("0|[1-9][0-9]{0,9}"))) {
            throw new IllegalArgumentException("Member cursor requires a region and a nonnegative integer.");
        }
        return parameters;
    }

    Iterable<RailixValue.ObjectValue> exampleQueries(final String id) {
        if (!identities.containsKey(id)) throw new NoSuchElementException("Example scope is not in this scene.");
        return new Observation(RailixValue.object(Map.of("nodes", RailixValue.array(List.of(
                RailixValue.object(Map.of("id", RailixValue.string(id))))))), List.of(), null, List.of())
                .queries("example_cases");
    }

    static Map<String, String> inspectionParameters(final String query) {
        if (query != null && query.length() > 8192) throw new IllegalArgumentException("Section inspection query exceeds 8192 characters.");
        final var selected = parameters(query, Set.of("revision", "region"));
        if (!selected.isEmpty() && (!selected.containsKey("region")
                || !selected.getOrDefault("revision", "").matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Section inspection requires a region and current scene revision.");
        }
        return selected;
    }

    /** Resolves observed section endpoints from the application's selected path. */
    RailixValue.ObjectValue passage(final String id, final RailixValue.ObjectValue summary) throws IOException {
        final Part region = identities.get(id);
        if (region == null || !"region".equals(region.kind) || region.children.isEmpty()) throw new NoSuchElementException("Section is not in this scene.");
        if (!(summary.values().get("nodes") instanceof RailixValue.ArrayValue)) throw new IOException("Example path is unavailable.");
        int first = -1, last = -1, reached = 0;
        for (final RailixValue value : array(summary, "nodes")) {
            if (!(value instanceof RailixValue.NumberValue number)) throw new IOException("Example Step index is invalid.");
            final int index;
            try { index = number.value().intValueExact(); }
            catch (final ArithmeticException failure) { throw new IOException("Example Step index is invalid.", failure); }
            if (index < 0 || index >= indexedNodes.size()) throw new IOException("Example Step index is outside the current application.");
            final int position = java.util.Arrays.binarySearch(region.members, index);
            if (position >= 0 || ((-position - 1) & 1) == 1) {
                if (first < 0) first = index;
                last = index;
                reached++;
            } else if (first >= 0) break;
        }
        final List<RailixValue> passages = first < 0 ? List.of() : List.of(passageValue(first, last, reached));
        return RailixValue.object(Map.of("region", RailixValue.string(id), "revision", RailixValue.string(revision),
                "application_pid", summary.values().get("application_pid"), "count", RailixValue.number(region.count),
                "reached", RailixValue.number(reached), "passages", RailixValue.array(passages)));
    }

    private RailixValue.ObjectValue passageValue(final int entry, final int exit, final int count) {
        final var first = indexedNodes.get(entry);
        final var last = indexedNodes.get(exit);
        return RailixValue.object(Map.of("count", RailixValue.number(count),
                "entry", RailixValue.object(Map.of("id", RailixValue.string(first.id), "name", RailixValue.string(first.name), "index", RailixValue.number(first.node))),
                "exit", RailixValue.object(Map.of("id", RailixValue.string(last.id), "name", RailixValue.string(last.name), "index", RailixValue.number(last.node)))));
    }

    /** A bounded page of individual counters, for Creator-side reductions without runtime group semantics. */
    RailixValue.ObjectValue memberPage(final Map<String, String> parameters) {
        final Part part = identities.get(parameters.get("members"));
        if (part == null || part.children.isEmpty()) throw new NoSuchElementException("Group is not in this scene.");
        long cursor = Long.parseLong(parameters.getOrDefault("cursor", "0"));
        int lower = 0, upper = part.metricMembers.length / 2;
        while (lower < upper) {
            final int middle = (lower + upper) >>> 1;
            if (part.metricMembers[middle * 2 + 1] < cursor) lower = middle + 1;
            else upper = middle;
        }
        final Map<String, RailixValue> steps = new LinkedHashMap<>();
        for (int range = lower * 2; range < part.metricMembers.length && steps.size() < 512; range += 2) {
            final int start = part.metricMembers[range], end = part.metricMembers[range + 1];
            for (cursor = Math.max(start, cursor); cursor <= end && steps.size() < 512; cursor++) {
                steps.put(Long.toString(cursor), RailixValue.array(List.of(
                        RailixValue.array(List.of(RailixValue.number(cursor), RailixValue.number(cursor))))));
            }
        }
        final boolean finished = part.metricMembers.length == 0 || cursor > part.metricMembers[part.metricMembers.length - 1];
        final Map<String, RailixValue> query = new LinkedHashMap<>();
        query.put("steps", RailixValue.object(steps));
        query.put("flows", RailixValue.object(Map.of()));
        if (parameters.containsKey("metrics")) query.put("metrics", RailixValue.array(java.util.Arrays.stream(parameters.get("metrics").split(","))
                .<RailixValue>map(RailixValue::string).toList()));
        return RailixValue.object(Map.of("query", RailixValue.object(query), "count", RailixValue.number(part.count),
                "next", RailixValue.number(finished ? -1 : cursor)));
    }

    boolean observesRevision(final String expected) {
        return revision.equals(expected);
    }

    /** Adds authoring identities to application diagnostics without changing their execution meaning. */
    RailixValue.ObjectValue exampleView(final RailixValue.ObjectValue summary) throws IOException {
        final Map<String, RailixValue> value = new LinkedHashMap<>(summary.values());
        value.put("scene_revision", RailixValue.string(revision));
        if (!(value.get("result") instanceof RailixValue.ObjectValue result)) return RailixValue.object(value);
        final List<RailixValue> diagnostics = new ArrayList<>();
        final List<RailixValue> problems = new ArrayList<>(array(result, "diagnostics"));
        if (result.values().get("failure") instanceof RailixValue.ObjectValue failure) problems.add(failure);
        for (final RailixValue item : problems) {
            if (!(item instanceof RailixValue.ObjectValue diagnostic)) throw new IOException("Example diagnostic is invalid.");
            final String path = text(diagnostic.values(), "path");
            final var match = NODE_DIAGNOSTIC.matcher(path);
            final Part node = match.find() && Integer.parseInt(match.group(1)) < indexedNodes.size()
                    ? indexedNodes.get(Integer.parseInt(match.group(1))) : identities.get(text(diagnostic.values(), "step"));
            if (node == null) {
                diagnostics.add(item);
                continue;
            }
            final Map<String, RailixValue> fields = new LinkedHashMap<>(diagnostic.values());
            fields.put("node", RailixValue.string(node.id));
            fields.put("node_name", RailixValue.string(node.name));
            fields.put("regions", RailixValue.array(node.regions()));
            diagnostics.add(RailixValue.object(fields));
        }
        final Map<String, RailixValue> fields = new LinkedHashMap<>(result.values());
        fields.put("diagnostics", RailixValue.array(diagnostics));
        value.put("result", RailixValue.object(fields));
        return RailixValue.object(value);
    }

    Observation observationView(final Map<String, String> parameters) {
        if (parameters.containsKey("example")) exampleTrigger(parameters.get("example"));
        final Map<String, Connection> connections = new LinkedHashMap<>();
        return new Observation(view(viewParameters(parameters), connections), List.copyOf(connections.values()),
                parameters.get("example"), parameters.containsKey("metrics")
                ? List.of(parameters.get("metrics").split(",")) : List.of());
    }

    /** One viewport's bounded queries and aggregate responses; never retains a full application snapshot. */
    final class Observation {
        private final RailixValue.ObjectValue projection;
        private final List<Connection> connections;
        private final String example;
        private final List<String> selectedMetrics;
        private Map<String, RailixValue> metricDefinitions = Map.of();
        private long observedAt;
        private long elapsedNanos;
        private final Map<String, Map<String, RailixValue>> metrics = new HashMap<>();
        private final Map<String, Map<String, RailixValue>> examples = new HashMap<>();
        private boolean metricsAvailable = true;
        private boolean examplesAvailable = true;
        private boolean selectionAvailable = true;
        private long coverageRevision = -1;

        private Observation(final RailixValue.ObjectValue projection, final List<Connection> connections,
                            final String example, final List<String> selectedMetrics) {
            this.projection = projection;
            this.connections = connections;
            this.example = example;
            this.selectedMetrics = selectedMetrics;
        }

        void metricDefinitions(final RailixValue.ObjectValue catalog) throws IOException {
            metricDefinitions = observedObject(catalog.values().get("metrics")).values();
        }

        Iterable<RailixValue.ObjectValue> queries(final String read) {
            final boolean metric = read.equals("metrics");
            final List<Scope> scopes = new ArrayList<>();
            int index = 0;
            for (final RailixValue value : array(projection, "nodes")) {
                final Part part = identities.get(text(object(value), "id"));
                final String id = "n" + index++;
                final String kind = !metric ? "groups" : switch (part.kind) {
                    case "app" -> "application";
                    case "trigger" -> "flows";
                    default -> "steps";
                };
                scopes.add(new Scope(id, kind, metric ? part.metricMembers : part.members, part.id));
            }
            for (index = 0; index < connections.size(); index++) {
                final Connection connection = connections.get(index);
                if (!metric || !connection.unknownMetrics) {
                    scopes.add(new Scope("l" + index, metric ? "steps" : "groups",
                            metric ? connection.metricMembers : connection.members, ""));
                }
            }
            return () -> new java.util.Iterator<>() {
                private int scope;
                private int offset;
                private int position = -1;
                private boolean first = true;

                public boolean hasNext() {
                    return first || scope < scopes.size();
                }

                public RailixValue.ObjectValue next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    first = false;
                    final Map<String, RailixValue> body = new LinkedHashMap<>();
                    if (read.equals("example_cases")) body.put("cases", RailixValue.bool(true));
                    if (metric && !selectedMetrics.isEmpty()) body.put("metrics", RailixValue.array(selectedMetrics.stream()
                            .<RailixValue>map(RailixValue::string).toList()));
                    final Map<String, Map<String, RailixValue>> categories = new LinkedHashMap<>();
                    if (!metric && example != null) body.put("example", RailixValue.string(example));
                    int groups = 0;
                    int ranges = 0;
                    int members = 0;
                    while (scope < scopes.size() && groups < 4096 && ranges < 4096 && members < 1_048_576) {
                        final Scope current = scopes.get(scope);
                        if (current.kind.equals("application")) {
                            body.put("application", RailixValue.string(current.id));
                            scope++;
                        } else if (current.kind.equals("flows")) {
                            categories.computeIfAbsent("flows", ignored -> new LinkedHashMap<>())
                                    .put(current.id, RailixValue.string(current.flow));
                            scope++;
                        } else {
                            final List<RailixValue> intervals = new ArrayList<>();
                            while (offset < current.members.length && ranges < 4096 && members < 1_048_576) {
                                if (position < 0) position = current.members[offset];
                                final int end = (int) Math.min(current.members[offset + 1],
                                        (long) position + 1_048_576 - members - 1);
                                intervals.add(RailixValue.array(List.of(RailixValue.number(position), RailixValue.number(end))));
                                members += end - position + 1;
                                ranges++;
                                if (end == current.members[offset + 1]) {
                                    offset += 2;
                                    position = -1;
                                } else {
                                    position = end + 1;
                                }
                            }
                            categories.computeIfAbsent(current.kind, ignored -> new LinkedHashMap<>())
                                    .put(current.id, RailixValue.array(intervals));
                            if (offset == current.members.length) {
                                scope++;
                                offset = 0;
                            }
                        }
                        groups++;
                    }
                    categories.forEach((kind, values) -> body.put(kind, RailixValue.object(values)));
                    for (final String kind : metric ? List.of("steps", "flows") : List.of("groups")) {
                        body.putIfAbsent(kind, RailixValue.object(Map.of()));
                    }
                    return RailixValue.object(body);
                }
            };
        }

        void unavailable(final String read) {
            if (read.equals("metrics")) {
                metricsAvailable = false;
                metrics.clear();
            } else {
                examplesAvailable = false;
                examples.clear();
            }
        }

        boolean accept(final String read, final RailixValue.ObjectValue query,
                       final RailixValue.ObjectValue document) throws IOException {
            final boolean metric = read.equals("metrics");
            final Map<String, RailixValue> expected = new HashMap<>();
            for (final String kind : List.of("steps", "flows", "groups")) {
                expected.putAll(object(query.values().get(kind)));
            }
            if (query.values().get("application") instanceof RailixValue.StringValue id) {
                expected.put(id.value(), RailixValue.nullValue());
            }
            final Map<String, RailixValue> groups = observedObject(document.values().get("groups")).values();
            if (!groups.keySet().equals(expected.keySet())) {
                throw new IOException("Application observation groups do not match the viewport query.");
            }
            final boolean selected = !metric && document.values().containsKey("example");
            if (metric) {
                observedAt = Math.max(observedAt, observedNumber(document.values().get("observed_at")));
                elapsedNanos = Math.max(elapsedNanos, observedNumber(document.values().get("elapsed_nanos")));
                for (final var group : groups.entrySet()) {
                    final Map<String, RailixValue> totals = metrics.computeIfAbsent(group.getKey(), ignored -> new LinkedHashMap<>());
                    for (final var reading : observedObject(group.getValue()).values().entrySet()) {
                        final RailixValue definition = metricDefinitions.get(reading.getKey());
                        if (definition == null || !selectedMetrics.isEmpty() && !selectedMetrics.contains(reading.getKey())
                                || !(reading.getValue() instanceof RailixValue.NumberValue number)) {
                            throw new IOException("Application returned an undefined, unrequested or nonnumeric metric.");
                        }
                        final RailixValue previous = totals.get(reading.getKey());
                        if (previous == null) {
                            totals.put(reading.getKey(), reading.getValue());
                            continue;
                        }
                        final BigDecimal before = ((RailixValue.NumberValue) previous).value();
                        final String aggregation = text(object(definition), "aggregation");
                        totals.put(reading.getKey(), RailixValue.number(switch (aggregation) {
                            case "sum" -> before.add(number.value());
                            case "max" -> before.max(number.value());
                            default -> throw new IOException("A nonaggregatable metric spans multiple query batches.");
                        }));
                    }
                }
                return metricsAvailable;
            }
            final long current = observedNumber(document.values().get("revision"));
            if (coverageRevision >= 0 && coverageRevision != current) unavailable(read);
            coverageRevision = current;
            if (selected && (example == null || !RailixValue.string(example).equals(document.values().get("example")))) {
                throw new IOException("Application observation does not match the selected Example.");
            }
            selectionAvailable &= selected;
            for (final var group : groups.entrySet()) {
                final Map<String, RailixValue> counters = observedObject(group.getValue()).values();
                final List<String> fields = selected ? List.of("covered_count", "selected_count") : List.of("covered_count");
                long count = 0;
                for (final RailixValue interval : ((RailixValue.ArrayValue) expected.get(group.getKey())).values()) {
                    final List<RailixValue> bounds = ((RailixValue.ArrayValue) interval).values();
                    count += observedNumber(bounds.getLast()) - observedNumber(bounds.getFirst()) + 1;
                }
                for (final String field : fields) {
                    final long value = observedNumber(counters.get(field));
                    if (value > count) {
                        throw new IOException("Application observation count exceeds the requested membership.");
                    }
                    if (examplesAvailable) {
                        final Map<String, RailixValue> totals = examples
                                .computeIfAbsent(group.getKey(), ignored -> new LinkedHashMap<>());
                        final long previous = totals.containsKey(field) ? observedNumber(totals.get(field)) : 0;
                        totals.put(field, RailixValue.number(value > Long.MAX_VALUE - previous ? Long.MAX_VALUE : previous + value));
                    }
                }
                if (selected && observedNumber(counters.get("selected_count")) > observedNumber(counters.get("covered_count"))) {
                    throw new IOException("Selected Example membership exceeds application coverage.");
                }
            }
            return examplesAvailable;
        }

        RailixValue.ObjectValue response(final long applicationRevision, final long applicationPid) {
            final List<RailixValue> nodes = new ArrayList<>();
            final Map<String, RailixValue> selectedNodes = new HashMap<>();
            int index = 0;
            for (final RailixValue value : array(projection, "nodes")) {
                final Part part = identities.get(text(object(value), "id"));
                final String key = "n" + index++;
                final Map<String, RailixValue> entry = new LinkedHashMap<>();
                entry.put("id", RailixValue.string(part.id));
                entry.put("count", RailixValue.number(part.count));
                entry.put("disabled_count", RailixValue.number(part.disabled));
                if (metricsAvailable) entry.put("metrics", RailixValue.object(metrics.get(key)));
                if (examplesAvailable) {
                    entry.put("covered_count", examples.get(key).get("covered_count"));
                    if (selectionAvailable) {
                        final RailixValue selected = examples.get(key).get("selected_count");
                        entry.put("selected_count", selected);
                        selectedNodes.put(part.id, selected);
                    }
                }
                nodes.add(RailixValue.object(entry));
            }
            final List<RailixValue> links = new ArrayList<>();
            for (index = 0; index < connections.size(); index++) {
                final Connection connection = connections.get(index);
                final String key = "l" + index;
                final Map<String, RailixValue> entry = new LinkedHashMap<>();
                entry.put("id", RailixValue.string(connection.id()));
                if (metricsAvailable && !connection.unknownMetrics) entry.put("metrics", RailixValue.object(metrics.get(key)));
                if (examplesAvailable && selectionAvailable) {
                    final RailixValue source = selectedNodes.get(connection.from.id);
                    final boolean unvisitedSource = !"app".equals(connection.from.kind) && RailixValue.number(0).equals(source);
                    final boolean reached = source != null && !unvisitedSource
                            && !RailixValue.number(0).equals(examples.get(key).get("selected_count"));
                    entry.put("selection", RailixValue.string(reached ? "reached"
                            : unvisitedSource ? "unreached" : source == null || connection.unknownSelection ? "unknown" : "unreached"));
                }
                links.add(RailixValue.object(entry));
            }
            final Map<String, RailixValue> response = new LinkedHashMap<>();
            response.put("revision", RailixValue.string(revision));
            response.put("application_revision", RailixValue.number(applicationRevision));
            response.put("application_pid", RailixValue.number(applicationPid));
            if (metricsAvailable) {
                response.put("observed_at", RailixValue.number(observedAt));
                response.put("elapsed_nanos", RailixValue.number(elapsedNanos));
            }
            response.put("limited", projection.values().get("limited"));
            response.put("nodes", RailixValue.array(nodes));
            response.put("links", RailixValue.array(links));
            if (examplesAvailable) {
                response.put("coverage_revision", RailixValue.number(coverageRevision));
                if (selectionAvailable) response.put("example", RailixValue.string(example));
            }
            return RailixValue.object(response);
        }
    }

    private record Scope(String id, String kind, int[] members, String flow) {}

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


    private static RailixValue.ObjectValue observedObject(final RailixValue value) throws IOException {
        if (!(value instanceof RailixValue.ObjectValue object)) {
            throw new IOException("Application observation must contain an object.");
        }
        return object;
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
        final List<Part> discovered = new ArrayList<>();
        final Map<Part, Integer> incoming = new java.util.IdentityHashMap<>();
        incoming.put(app, 0);
        discovered.add(app);
        for (int index = 0; index < discovered.size(); index++) {
            final Part node = discovered.get(index);
            node.outgoing.sort(Comparator.comparing(Route::outcome).thenComparing(edge -> edge.to.id));
            for (final Route edge : node.outgoing) {
                if (!incoming.containsKey(edge.to)) {
                    incoming.put(edge.to, 0);
                    discovered.add(edge.to);
                }
                incoming.merge(edge.to, 1, Integer::sum);
            }
        }
        final List<Part> order = new ArrayList<>();
        final Map<Part, Integer> remaining = new java.util.IdentityHashMap<>(incoming);
        discovered.forEach(part -> { part.depth = 0; if (remaining.get(part) == 0) order.add(part); });
        for (int index = 0; index < order.size(); index++) {
            final Part node = order.get(index);
            for (final Route edge : node.outgoing) {
                edge.to.depth = Math.max(edge.to.depth, node.depth + 1);
                if (remaining.merge(edge.to, -1, Integer::sum) == 0) order.add(edge.to);
            }
        }
        for (int index = order.size() - 1; index >= 0; index--) {
            final Part node = order.get(index);
            node.rows = node.outgoing.isEmpty() ? 1 : (int) Math.min(order.size(),
                    node.outgoing.stream().mapToLong(edge -> edge.to.rows).sum());
        }
        for (final Part node : order) {
            node.station = true;
            node.exits = node.outgoing.size();
            node.box = new Box(node.depth * COLUMN, (node.row + (node.rows - 1) / 2.0) * ROW,
                    NODE_WIDTH, NODE_HEIGHT);
            int row = node.row;
            for (final Route edge : node.outgoing) {
                edge.to.row = row;
                row += edge.to.rows;
            }
        }
        // Align parents only after their children's column has its final clearance.
        final Map<Integer, List<Part>> columns = new java.util.TreeMap<>(Comparator.reverseOrder());
        order.forEach(part -> columns.computeIfAbsent(part.depth, ignored -> new ArrayList<>()).add(part));
        for (final List<Part> column : columns.values()) {
            for (final Part node : column) if (!node.outgoing.isEmpty()) {
                final Box first = node.outgoing.getFirst().to.box;
                final Box last = node.outgoing.getLast().to.box;
                node.box = new Box(node.box.x, (first.y + first.height / 2 + last.y + last.height / 2) / 2
                        - NODE_HEIGHT / 2, NODE_WIDTH, NODE_HEIGHT);
            }
            column.sort(Comparator.comparingDouble((Part part) -> part.box.y).thenComparing(part -> part.id));
            double bottom = Double.NEGATIVE_INFINITY;
            for (final Part part : column) {
                final double y = Math.max(part.box.y, bottom);
                part.box = new Box(part.box.x, y, part.box.width, part.box.height);
                bottom = y + ROW;
            }
        }
        final Map<Integer, List<double[]>> spans = new HashMap<>();
        final Set<Integer> lateral = new java.util.HashSet<>();
        for (final Part part : order) for (final Route edge : part.outgoing) {
            final double from = part.box.y + part.box.height / 2, to = edge.to.box.y + edge.to.box.height / 2;
            if (from != to) lateral.add(part.depth);
            final List<double[]> events = spans.computeIfAbsent(part.depth, ignored -> new ArrayList<>());
            events.add(new double[]{from == to ? from - ROW / 2 : Math.min(from, to), 1});
            events.add(new double[]{from == to ? from + ROW / 2 : Math.max(from, to), -1});
        }
        final double[] offsets = new double[order.stream().mapToInt(part -> part.depth).max().orElse(0) + 1];
        for (int column = 1; column < offsets.length; column++) {
            final List<double[]> events = spans.getOrDefault(column - 1, List.of());
            if (!events.isEmpty()) events.sort(Comparator.comparingDouble((double[] event) -> event[0]).thenComparingDouble(event -> event[1]));
            int active = 0, lanes = 0;
            for (final double[] event : events) { active += (int) event[1];lanes = Math.max(lanes, active); }
            // A reserved lane needs belt pitch plus space for a crossing's ramp.
            final double gap = lateral.contains(column - 1) ? Math.max(COLUMN, NODE_WIDTH + 96) : COLUMN;
            offsets[column] = offsets[column - 1] + gap + Math.max(0, lanes - 1) * 84;
        }
        for (final Part part : order) part.box = new Box(offsets[part.depth], part.box.y, part.box.width, part.box.height);
        return order;
    }

    // A non-convex visual selection can create a cycle only in its contracted drawing.
    // Keep those regions expanded instead of changing or dropping functional connections.
    private static void retainAcyclicGroups(final Map<Part, Part> groups, final List<Route> edges) {
        while (!groups.isEmpty()) {
            final Map<Part, Integer> incoming = new java.util.IdentityHashMap<>();
            final Map<Part, List<Part>> outgoing = new java.util.IdentityHashMap<>();
            for (final Route edge : edges) {
                final Part from = groups.getOrDefault(edge.from, edge.from);
                final Part to = groups.getOrDefault(edge.to, edge.to);
                incoming.putIfAbsent(from, 0);
                incoming.putIfAbsent(to, 0);
                if (from != to) {
                    outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
                    incoming.merge(to, 1, Integer::sum);
                }
            }
            final ArrayDeque<Part> ready = new ArrayDeque<>();
            incoming.forEach((part, count) -> { if (count == 0) ready.add(part); });
            while (!ready.isEmpty()) for (final Part target : outgoing.getOrDefault(ready.removeFirst(), List.of())) {
                if (incoming.merge(target, -1, Integer::sum) == 0) ready.add(target);
            }
            if (!groups.entrySet().removeIf(entry -> incoming.getOrDefault(entry.getValue(), 0) > 0)) return;
        }
    }

    private static Map<Part, Part> groups(final List<Part> order, final Map<String, RailixValue> styles) {
        final Map<Part, Part> result = new java.util.IdentityHashMap<>();
        final Map<Part, List<Part>> adjacent = new java.util.IdentityHashMap<>();
        for (final Part node : order) for (final Route edge : node.outgoing) {
            adjacent.computeIfAbsent(node, ignored -> new ArrayList<>()).add(edge.to);
            adjacent.computeIfAbsent(edge.to, ignored -> new ArrayList<>()).add(node);
        }
        for (final Part node : order) {
            if (node.group.isEmpty() || result.containsKey(node)) {
                continue;
            }
            final List<Part> members = new ArrayList<>();
            final Set<Part> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            final ArrayDeque<Part> pending = new ArrayDeque<>();
            pending.add(node);
            while (!pending.isEmpty()) {
                final Part member = pending.removeFirst();
                if (!visited.add(member)) continue;
                members.add(member);
                adjacent.getOrDefault(member, List.of()).stream().filter(child -> child.group.equals(node.group))
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

    private static Map<Part, Part> areas(final List<Part> order) {
        final Map<Part, Part> result = new java.util.IdentityHashMap<>();
        // Small scenes already fit the existing named-group and run hierarchy.
        if (order.stream().mapToInt(part -> part.count).sum() <= MAX_NODES / 4) return result;
        final Map<Part, Integer> positions = new java.util.IdentityHashMap<>();
        final Map<Part, List<Part>> incoming = new java.util.IdentityHashMap<>();
        for (int index = 0; index < order.size(); index++) {
            positions.put(order.get(index), index);
            for (final Route edge : order.get(index).outgoing) incoming.computeIfAbsent(edge.to, ignored -> new ArrayList<>()).add(edge.from);
        }
        final int levels = 32 - Integer.numberOfLeadingZeros(order.size());
        final int[][] ancestors = new int[levels][order.size()];
        final int[] depth = new int[order.size()];
        final Map<Part, List<Part>> dominated = new java.util.IdentityHashMap<>();
        for (int index = 1; index < order.size(); index++) {
            final Part part = order.get(index);
            final List<Part> parents = incoming.get(part);
            int parent = positions.get(parents.getFirst());
            for (int next = 1; next < parents.size(); next++) {
                int other = positions.get(parents.get(next));
                if (depth[parent] < depth[other]) { final int swap = parent; parent = other; other = swap; }
                final int difference = depth[parent] - depth[other];
                for (int bit = 0; bit < levels; bit++) if ((difference & (1 << bit)) != 0) parent = ancestors[bit][parent];
                if (parent != other) {
                    for (int bit = levels - 1; bit >= 0; bit--) if (ancestors[bit][parent] != ancestors[bit][other]) {
                        parent = ancestors[bit][parent];other = ancestors[bit][other];
                    }
                    parent = ancestors[0][parent];
                }
            }
            ancestors[0][index] = parent;
            depth[index] = depth[parent] + 1;
            for (int bit = 1; bit < levels; bit++) ancestors[bit][index] = ancestors[bit - 1][ancestors[bit - 1][index]];
            dominated.computeIfAbsent(order.get(parent), ignored -> new ArrayList<>()).add(part);
        }
        for (final Part entry : order) {
            final List<Part> parents = incoming.getOrDefault(entry, List.of());
            if (result.containsKey(entry) || parents.size() != 1 || parents.getFirst().outgoing.size() < 2
                    || "app".equals(parents.getFirst().kind) || "trigger".equals(entry.kind)) continue;
            final Set<Part> members = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            final ArrayDeque<Part> pending = new ArrayDeque<>();pending.add(entry);
            while (!pending.isEmpty()) {
                final Part part = pending.removeLast();members.add(part);
                pending.addAll(dominated.getOrDefault(part, List.of()));
            }
            if (members.stream().mapToInt(part -> part.count).sum() < 16
                    || members.stream().noneMatch(part -> part.outgoing.size() > 1)
                    || members.stream().flatMap(part -> part.outgoing.stream()).anyMatch(edge -> !members.contains(edge.to))) continue;
            final List<Part> contents = members.stream().sorted(Comparator.comparingInt(positions::get)).toList();
            final Part index = hierarchy(contents, "index:area:" + entry.id, false);
            final Part area = container("region", "area:" + entry.id, entry.name, "", Map.of(),
                    "index".equals(index.kind) ? index.children : List.of(index));
            members.forEach(part -> result.put(part, area));
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
            return container(kind, id, "Automatic section", "", Map.of(), List.copyOf(parts));
        }
        final Box bounds = bounds(parts);
        final List<Part> sorted = new ArrayList<>(parts);
        final Comparator<Part> coordinate = bounds.width >= bounds.height
                ? Comparator.comparingDouble(part -> part.box.x)
                : Comparator.comparingDouble(part -> part.box.y);
        sorted.sort(coordinate.thenComparing(part -> part.id));
        final List<Part> children = new ArrayList<>();
        final int batch = (sorted.size() + 7) / 8;
        for (int start = 0; start < sorted.size(); start += batch) {
            children.add(hierarchy(sorted.subList(start, Math.min(start + batch, sorted.size())),
                    id + ":" + children.size(), aggregate));
        }
        return container(kind, id, "Automatic section", "", Map.of(), children);
    }

    private static Part container(final String kind, final String id, final String name, final String group,
                               final Map<String, RailixValue> style, final List<Part> children) {
        final Part result = new Part(id, kind, name, "", group, style, children);
        result.box = contentBounds(kind, group, children);
        result.count = children.stream().mapToInt(child -> child.count).sum();
        return result;
    }

    private static Box contentBounds(final String kind, final String group, final List<Part> children) {
        final double padding = "region".equals(kind) && group.isEmpty()
                ? children.getFirst().children.isEmpty() ? (COLUMN - NODE_WIDTH) / 2 : 0 : 18;
        return bounds(children).expand(padding);
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
        final boolean automatic = !index && part.group.isEmpty();
        final Box original = index ? part.box : automatic ? contentBounds(part.kind, part.group, part.children)
                : bounds(part.children);
        part.box = space;
        final double insetX = index || automatic ? 0 : space.width * 0.06;
        final double insetY = index || automatic ? 0 : space.height * 0.12;
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

    private static boolean expands(final Part part, final double scale, final Part focused, final Part inside) {
        final boolean reveal = focused != null && part != focused
                && part.first <= focused.first && part.last >= focused.last;
        final boolean entered = inside != null && (part.first <= inside.first && part.last >= inside.last
                || inside.count < MAX_NODES && part.group.isEmpty() && part.first >= inside.first && part.last <= inside.last);
        return !part.children.isEmpty() && ("index".equals(part.kind) || entered || reveal || scale >= detailScale(part));
    }

    private static double focusScale(final Part focused, final Part inside) {
        double scale = 0;
        // The target must remain revealed after the one-shot focus parameter is removed.
        for (Part part = focused; part != null; part = part.parent) {
            if (!part.children.isEmpty() && !expands(part, 0, null, inside)) {
                scale = Math.max(scale, 1.1 * detailScale(part));
            }
        }
        return scale;
    }

    /* Reveal the next level only when its children fit readable stations, not merely when the parent is wide. */
    private static double detailScale(final Part part) {
        double scale = Math.min(REGION_DETAIL_WIDTH / part.box.width, REGION_DETAIL_HEIGHT / part.box.height);
        for (final Part child : part.children) {
            scale = Math.max(scale, Math.max(120 / child.box.width, 48 / child.box.height));
        }
        return scale;
    }

    private static Part representative(final Part leaf, final double scale, final Part focused,
                                       final List<Part> constrained, final Part inside) {
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
            if (!expands(part, scale, focused, inside)) {
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
        return value.values().get(name) instanceof RailixValue.ArrayValue array ? array.values() : List.of();
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
        private int exits;
        private int first;
        private int last;
        private int count;
        private int groupCount;
        private int node = -1;
        private int[] members = new int[0];
        private int[] metricMembers = new int[0];
        private int disabled;
        private boolean metrics;
        private boolean station;
        private int exampleCount;
        private String iconRef = "";
        private List<RailixValue> roof = List.of();
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
            station = children.isEmpty();
            count = "end".equals(kind) ? 0 : 1;
        }

        private RailixValue.ObjectValue value(final boolean expanded) {
            final Map<String, RailixValue> value = new LinkedHashMap<>(box.value().values());
            value.put("id", RailixValue.string(id));
            value.put("kind", RailixValue.string("index".equals(kind) ? "region" : kind));
            value.put("name", RailixValue.string(displayName()));
            value.put("count", RailixValue.number(count));
            if (!children.isEmpty() && group.isEmpty()) {
                value.put("summary", RailixValue.string("region".equals(kind) && !id.startsWith("area:") ? "run" : "area"));
            }
            value.put("station_scale", decimal(stationScale()));
            final List<RailixValue> regions = regions();
            if (!regions.isEmpty()) value.put("regions", RailixValue.array(regions));
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
                value.put("group_count", RailixValue.number(groupCount));
                if (!expanded && !roof.isEmpty()) value.put("roof", RailixValue.array(roof));
            }
            for (final String field : List.of("color", "boundary", "shape", "aspect", "roundness", "shared")) {
                if (style.containsKey(field)) value.put(field, style.get(field));
            }
            return RailixValue.object(value);
        }

        private List<RailixValue> regions() {
            final List<RailixValue> result = new ArrayList<>();
            for (Part ancestor = parent; ancestor != null; ancestor = ancestor.parent)
                if (ancestor.parent != null) result.add(RailixValue.string(ancestor.id));
            return result;
        }

        private String displayName() {
            if (id.startsWith("area:")) return name;
            if (!group.isEmpty() || children.isEmpty()) return name;
            if (!"region".equals(kind)) return "Flow area";
            Part first = this;
            Part last = this;
            while (!first.children.isEmpty()) first = first.children.getFirst();
            while (!last.children.isEmpty()) last = last.children.getLast();
            return first.name.equals(last.name) ? first.name : first.name + " to " + last.name;
        }

        private double stationScale() {
            // Internal index regions describe a whole cell, including space around its machine.
            return Math.min(box.width / (station ? NODE_WIDTH : COLUMN),
                    box.height / (station ? NODE_HEIGHT : ROW));
        }
    }

    /** Sorted inclusive intervals keep static membership compact even when project order differs from layout. */
    private static int[] union(final int[] first, final int[] second) {
        if (first.length == 0) return second;
        if (second.length == 0) return first;
        final int[] merged = new int[first.length + second.length];
        int left = 0;
        int right = 0;
        int size = 0;
        while (left < first.length || right < second.length) {
            final boolean takeLeft = right == second.length || left < first.length && first[left] <= second[right];
            final int start = takeLeft ? first[left] : second[right];
            final int end = takeLeft ? first[left + 1] : second[right + 1];
            if (takeLeft) left += 2; else right += 2;
            if (size > 0 && (long) start <= (long) merged[size - 1] + 1) {
                merged[size - 1] = Math.max(merged[size - 1], end);
            } else {
                merged[size++] = start;
                merged[size++] = end;
            }
        }
        return java.util.Arrays.copyOf(merged, size);
    }

    private record Route(String id, Part from, Part to, String outcome) {
        private RailixValue.ObjectValue value(final String identity, final Part start, final Part end,
                                             final List<RailixValue> points) {
            final var value = RailixValue.object(Map.of("id", RailixValue.string(identity),
                    "from", RailixValue.string(start.id), "to", RailixValue.string(end.id),
                    "station_scale", decimal(Math.min(1, Math.min(start.stationScale(), end.stationScale()))),
                    "outcome", RailixValue.string(outcome), "points", RailixValue.array(points)));
            return "app".equals(start.kind) ? CreatorChanges.with(value, "power", RailixValue.bool(true)) : value;
        }

        private Box bounds() {
            Box bounds = bounds(from, to);
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

        private static Box bounds(final Part from, final Part to) {
            return from.box.union(to.box);
        }

        private static RailixValue point(final double x, final double y) {
            return RailixValue.array(List.of(decimal(x), decimal(y)));
        }
    }

    private static final class Connection {
        private final Part from;
        private final Part to;
        private String outcome;
        private final Set<String> outcomes = new java.util.LinkedHashSet<>();
        private int[] members = new int[0];
        private int[] metricMembers = new int[0];
        private boolean unknownMetrics;
        private boolean unknownSelection;

        private Connection(final Part from, final Part to, final String outcome) {
            this.from = from;
            this.to = to;
            this.outcome = outcome;
        }

        private void add(final Routes routes) {
            if (!routes.outcome.isEmpty()) outcomes.add(routes.outcome);
            if (!outcome.equals(routes.outcome)) outcome = "";
            members = union(members, routes.members);
            metricMembers = union(metricMembers, routes.metricMembers);
            unknownMetrics |= routes.unknownMetrics;
            unknownSelection |= routes.unknownSelection;
        }

        private String id() {
            return from.id + "." + outcome + ">" + to.id;
        }

        private RailixValue.ObjectValue value(final List<RailixValue> points, final List<RailixValue> bridges) {
            final var value = new Route(id(), from, to, outcome).value(id(), from, to, points);
            final var described = from.children.isEmpty() && outcome.isEmpty() ? CreatorChanges.with(value, "outcomes",
                    RailixValue.array(outcomes.stream().<RailixValue>map(RailixValue::string).toList())) : value;
            return bridges.isEmpty() ? described : CreatorChanges.with(described, "bridges", RailixValue.array(bridges));
        }
    }

    /** Deterministic, footprint-aware routing for one complete semantic detail level. */
    private static final class Routing {
        private static final int MAX_SEARCH_CELLS = 16_384;
        private final Map<String, List<RailixValue>> paths = new HashMap<>();
        private final Map<String, List<RailixValue>> bridges = new HashMap<>();
        private final java.util.NavigableMap<Double, List<Part>> columns = new java.util.TreeMap<>();
        private final List<java.util.NavigableMap<Double, List<Segment>>> tracks = List.of(new java.util.TreeMap<>(), new java.util.TreeMap<>());
        private final Map<Connection, Double> outlets = new java.util.IdentityHashMap<>();
        private final Map<Part, List<List<double[]>>> approaches = new java.util.IdentityHashMap<>();

        private Routing(final Set<Part> stations, final java.util.Collection<Connection> connections) {
            stations.forEach(part -> columns.computeIfAbsent(part.box.x + part.box.width / 2, ignored -> new ArrayList<>()).add(part));
            final List<Connection> ordered = connections.stream().sorted(Comparator
                    .comparingDouble((Connection link) -> link.from.box.x)
                    .thenComparingDouble(link -> link.from.box.y)
                    .thenComparingDouble(link -> link.to.box.y).thenComparing(Connection::id)).toList();
            final Map<String, List<Connection>> ports = new LinkedHashMap<>();
            ordered.stream().filter(link -> link.from.exits > 1 && !"app".equals(link.from.kind)).forEach(link ->
                    ports.computeIfAbsent(link.from.id + ":" + Math.signum(link.to.box.y - link.from.box.y), ignored -> new ArrayList<>()).add(link));
            ports.values().forEach(port -> {
                port.sort(Comparator.comparingDouble((Connection link) -> link.to.box.y).thenComparing(Connection::id));
                final Connection first = port.getFirst();
                final double direction = first.to.box.y > first.from.box.y ? -1 : 1;
                for (int index = 0; index < port.size(); index++) outlets.put(port.get(index), (index - (port.size() - 1) / 2.0) * 36 * direction);
            });
            for (final Connection connection : ordered) {
                final List<double[]> path = path(connection);
                if (path.isEmpty()) continue;
                approaches.computeIfAbsent(connection.to, ignored -> new ArrayList<>()).add(path);
                paths.put(connection.from.id + ">" + connection.to.id,
                        path.stream().map(point -> Route.point(point[0], point[1])).toList());
                for (int index = 1; index < path.size(); index++) {
                    final Segment segment = new Segment(path.get(index - 1), path.get(index), connection, index == 1);
                    tracks.get(segment.axis()).computeIfAbsent(segment.fixed(), ignored -> new ArrayList<>()).add(segment);
                }
            }
            for (final var verticals : tracks.get(1).values()) for (final Segment vertical : verticals) {
                if ("app".equals(vertical.link.from.kind)) continue;
                for (final var horizontals : tracks.get(0).subMap(vertical.low(), false, vertical.high(), false).values())
                    for (final Segment horizontal : horizontals) {
                        if (vertical.link == horizontal.link || vertical.link.to == horizontal.link.to || "app".equals(horizontal.link.from.kind)
                                || vertical.fixed() <= horizontal.low() || vertical.fixed() >= horizontal.high()) continue;
                        final String key = horizontal.link.from.id + ">" + horizontal.link.to.id;
                        final var bridge = RailixValue.object(Map.of("at", Route.point(vertical.fixed(), horizontal.fixed()),
                                "under", RailixValue.string(vertical.link.id())));
                        final var crossings = bridges.computeIfAbsent(key, ignored -> new ArrayList<>());
                        if (!crossings.contains(bridge)) crossings.add(bridge);
                    }
            }
        }

        private List<double[]> path(final Connection link) {
            final double unit = Math.min(1, Math.min(link.from.stationScale(), link.to.stationScale()));
            final Box from = link.from.box, to = link.to.box;
            final double fromWidth = NODE_WIDTH * Math.min(1, link.from.stationScale());
            final double fromHeight = NODE_HEIGHT * Math.min(1, link.from.stationScale());
            final double toWidth = NODE_WIDTH * Math.min(1, link.to.stationScale());
            final double sx = from.x + from.width / 2, sy = from.y + from.height / 2;
            final double tx = to.x + to.width / 2, ty = to.y + to.height / 2;
            final double direction = Math.copySign(1, tx - sx);
            final boolean branch = link.from.exits > 1 && !"app".equals(link.from.kind) && Math.abs(ty - sy) > .000001;
            if (branch && Math.abs(outlets.getOrDefault(link, 0.0) * unit) > fromWidth / 2) return List.of();
            final double[] start = branch ? new double[]{sx + outlets.getOrDefault(link, 0.0) * unit, sy + Math.copySign(fromHeight / 2, ty - sy)}
                    : new double[]{sx + direction * fromWidth / 2, sy};
            final double[] end = {tx - direction * toWidth / 2, ty};
            final List<List<double[]>> candidates = new ArrayList<>();
            if (Math.abs(start[1] - end[1]) < .000001 * unit) {
                end[1] = start[1];
                candidates.add(List.of(start, end));
            }
            if (branch) candidates.add(List.of(start, new double[]{start[0], ty}, end));
            final double left = sx + direction * (fromWidth / 2 + 36 * unit);
            final double right = tx - direction * (toWidth / 2 + 36 * unit);
            final double exit = sy + Math.copySign(100 * unit, ty - sy);
            for (final double fraction : new double[]{0, 1, .5, .25, .75}) {
                final double x = left + (right - left) * fraction;
                if (!branch) candidates.add(List.of(start, new double[]{x, sy}, new double[]{x, ty}, end));
                else candidates.add(List.of(start, new double[]{start[0], exit}, new double[]{x, exit}, new double[]{x, ty}, end));
            }
            // Long bypasses use the empty row gutters, not the neighbouring machine's centre line.
            for (final double y : new double[]{sy - 112 * unit, sy + 112 * unit, ty - 112 * unit, ty + 112 * unit}) {
                for (final double fraction : new double[]{.5, 0, 1}) {
                    final double x1 = left + direction * fraction * 32 * unit;
                    final double x2 = right - direction * fraction * 32 * unit;
                    candidates.add(branch ? List.of(start, new double[]{start[0], exit}, new double[]{x1, exit},
                            new double[]{x1, y}, new double[]{x2, y}, new double[]{x2, ty}, end)
                            : List.of(start, new double[]{x1, sy}, new double[]{x1, y}, new double[]{x2, y}, new double[]{x2, ty}, end));
                }
            }
            return candidates.stream().map(Routing::simplify)
                    .sorted(Comparator.<List<double[]>>comparingInt(List::size).thenComparingDouble(Routing::length))
                    .map(path -> merge(path, link))
                    .filter(path -> clear(path, link, unit))
                    .findFirst()
                    .orElseGet(() -> detour(link, start, end, unit, branch));
        }

        private List<double[]> merge(final List<double[]> path, final Connection link) {
            for (int index = 1; index < path.size(); index++) {
                final double[] a = path.get(index - 1), b = path.get(index);
                final int axis = a[1] == b[1] ? 0 : 1;
                double nearest = Double.POSITIVE_INFINITY;
                List<double[]> suffix = null;
                double[] junction = null;
                for (final List<double[]> existing : approaches.getOrDefault(link.to, List.of())) {
                    for (int next = 1; next < existing.size(); next++) {
                        final double[] c = existing.get(next - 1), d = existing.get(next);
                        final int other = c[1] == d[1] ? 0 : 1;
                        final double[] point = a.clone();
                        if (axis == other) {
                            if (a[1-axis] != c[1-axis]) continue;
                            point[axis] = Math.clamp(a[axis], Math.min(c[axis], d[axis]), Math.max(c[axis], d[axis]));
                        } else point[axis] = c[axis];
                        if (point[axis] < Math.min(a[axis], b[axis]) || point[axis] > Math.max(a[axis], b[axis])
                                || point[other] < Math.min(c[other], d[other]) || point[other] > Math.max(c[other], d[other])) continue;
                        final double distance = Math.abs(point[axis] - a[axis]);
                        if (distance >= nearest) continue;
                        nearest = distance;junction = point;suffix = existing.subList(next, existing.size());
                    }
                }
                if (junction != null) {
                    final List<double[]> merged = new ArrayList<>(path.subList(0, index));
                    merged.add(junction);merged.addAll(suffix);
                    return simplify(merged);
                }
            }
            return path;
        }

        private List<double[]> detour(final Connection link, final double[] start, final double[] end,
                                      final double unit, final boolean branch) {
            final var xs = new java.util.TreeSet<Double>();
            final var ys = new java.util.TreeSet<Double>();
            xs.add(start[0]);xs.add(end[0]);ys.add(start[1]);ys.add(end[1]);
            final double lowX = Math.min(start[0], end[0]), highX = Math.max(start[0], end[0]);
            final double lowY = Math.min(start[1], end[1]) - ROW * unit, highY = Math.max(start[1], end[1]) + ROW * unit;
            for (final var column : columns.subMap(lowX - 132, true, highX + 132, true).values()) for (final Part part : column) {
                final double cx = part.box.x + part.box.width / 2, cy = part.box.y + part.box.height / 2;
                final double half = 68 * Math.min(1, part.stationScale()) + 20 * unit;
                if (cy + half < lowY || cy - half > highY) continue;
                final double horizontal = NODE_WIDTH / 2 * Math.min(1, part.stationScale()) + 50 * unit;
                xs.add(cx - horizontal);xs.add(cx + horizontal);ys.add(cy - half);ys.add(cy + half);
            }
            for (int axis = 0; axis < 2; axis++) for (final var lane : tracks.get(axis).values()) for (final Segment segment : lane) {
                if (segment.high() < (axis == 0 ? lowX : lowY) || segment.low() > (axis == 0 ? highX : highY)
                        || segment.fixed() < (axis == 0 ? lowY : lowX) || segment.fixed() > (axis == 0 ? highY : highX)) continue;
                final var coordinates = axis == 0 ? ys : xs;
                coordinates.add(segment.fixed() - 36 * unit);coordinates.add(segment.fixed() + 36 * unit);
                coordinates.add(segment.fixed() - (axis == 0 ? 24 : 48) * unit);
                coordinates.add(segment.fixed() + (axis == 0 ? 24 : 48) * unit);
                final var along = axis == 0 ? xs : ys;
                along.add(segment.low() + (axis == 0 ? 48 : 24) * unit);
                along.add(segment.high() - (axis == 0 ? 48 : 24) * unit);
            }
            final double[] x = xs.stream().mapToDouble(Double::doubleValue).filter(value -> value >= lowX && value <= highX).toArray();
            final double[] y = ys.stream().mapToDouble(Double::doubleValue).filter(value -> value >= lowY && value <= highY).toArray();
            if ((long) x.length * y.length > MAX_SEARCH_CELLS) return List.of();
            final int width = x.length, origin = java.util.Arrays.binarySearch(y, start[1]) * width + java.util.Arrays.binarySearch(x, start[0]);
            final int destination = java.util.Arrays.binarySearch(y, end[1]) * width + java.util.Arrays.binarySearch(x, end[0]);
            final double[] costs = new double[width * y.length * 2];
            java.util.Arrays.fill(costs, Double.POSITIVE_INFINITY);
            final int[] previous = new int[costs.length];java.util.Arrays.fill(previous, -1);
            final var queue = new java.util.PriorityQueue<double[]>(Comparator.comparingDouble((double[] item) -> item[0]).thenComparingDouble(item -> item[1]));
            final int first = origin * 2 + (branch ? 1 : 0);
            final int direction = end[0] >= start[0] ? 1 : -1;
            costs[first] = 0;queue.add(new double[]{0, first});
            int searched = 0;
            while (!queue.isEmpty() && searched++ < MAX_SEARCH_CELLS * 4) {
                final double[] item = queue.remove();
                final int state = (int) item[1], cell = state / 2, ix = cell % width, iy = cell / width;
                if (item[0] != costs[state]) continue;
                if (cell == destination) {
                    final List<double[]> path = new ArrayList<>();
                    for (int cursor = state; cursor >= 0; cursor = previous[cursor]) path.add(new double[]{x[cursor / 2 % width], y[cursor / 2 / width]});
                    java.util.Collections.reverse(path);
                    final List<double[]> simplified = merge(simplify(path), link);
                    return clear(simplified, link, unit) ? simplified : List.of();
                }
                for (int move = 0; move < 4; move++) {
                    final int axis = move / 2, directionStep = (move % 2) * 2 - 1;
                    final int nx = ix + (axis == 0 ? directionStep : 0), ny = iy + (axis == 1 ? directionStep : 0);
                    if (nx < 0 || nx >= width || ny < 0 || ny >= y.length) continue;
                    final int next = ny * width + nx;
                    if (cell == origin && (axis != (branch ? 1 : 0)
                            || branch && directionStep != Math.signum(end[1] - start[1]) || !branch && directionStep != direction)) continue;
                    if (next == destination && (axis != 0 || directionStep != direction)) continue;
                    final double[] a = {x[ix], y[iy]}, b = {x[next % width], y[next / width]};
                    if (state % 2 != axis && !turnClear(a, link, unit)) continue;
                    if (!clear(new Segment(a, b, link, cell == origin), unit)) continue;
                    final double cost = item[0] + Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + (state % 2 == axis ? 0 : 64 * unit);
                    final int target = next * 2 + axis;
                    if (cost >= costs[target]) continue;
                    costs[target] = cost;previous[target] = state;queue.add(new double[]{cost, target});
                }
            }
            return List.of();
        }

        private boolean clear(final List<double[]> points, final Connection link, final double unit) {
            if (points.size() < 2) return false;
            for (int index = 1; index < points.size(); index++) {
                final Segment segment = new Segment(points.get(index - 1), points.get(index), link, index == 1);
                if (!clear(segment, unit)) return false;
                if ("app".equals(link.from.kind)) continue;
                for (final var lane : tracks.get(1 - segment.axis()).subMap(segment.low(), false, segment.high(), false).values())
                    for (final Segment other : lane) {
                        if (other.link.to == link.to || "app".equals(other.link.from.kind) || segment.fixed() <= other.low() || segment.fixed() >= other.high()) continue;
                        final double crossingUnit = Math.max(unit, Math.min(1, Math.min(other.link.from.stationScale(), other.link.to.stationScale())));
                        if (Math.min(other.fixed() - segment.low(), segment.high() - other.fixed()) < (segment.axis() == 0 ? 48 : 24) * crossingUnit - .000001) return false;
                    }
            }
            return true;
        }

        private boolean turnClear(final double[] point, final Connection link, final double unit) {
            if ("app".equals(link.from.kind)) return true;
            for (int axis = 0; axis < 2; axis++) {
                final double clearance = (axis == 0 ? 48 : 24) * unit;
                for (final var lane : tracks.get(1 - axis).subMap(point[axis] - clearance + .000001, true, point[axis] + clearance - .000001, true).values())
                    for (final Segment other : lane) if (other.link.to != link.to && !"app".equals(other.link.from.kind)
                            && point[1-axis] > other.low() && point[1-axis] < other.high()) return false;
            }
            return true;
        }

        private boolean clear(final Segment segment, final double unit) {
            final Connection link = segment.link;
            final double x1 = Math.min(segment.a[0], segment.b[0]) - 132;
            final double x2 = Math.max(segment.a[0], segment.b[0]) + 132;
            for (final var column : columns.subMap(x1, true, x2, true).values()) for (final Part part : column) {
                if (part == link.from || part == link.to) continue;
                // Keep the socket approaches free for later routes and their crossing ramps.
                final double scale = Math.min(1, part.stationScale());
                final double[] half = {NODE_WIDTH / 2 * scale + 48 * unit, 68 * scale + 18 * unit};
                final double[] center = {part.box.x + part.box.width / 2, part.box.y + part.box.height / 2};
                if (segment.fixed() > center[1 - segment.axis()] - half[1 - segment.axis()] + .000001
                        && segment.fixed() < center[1 - segment.axis()] + half[1 - segment.axis()] - .000001
                        && segment.high() > center[segment.axis()] - half[segment.axis()] + .000001
                        && segment.low() < center[segment.axis()] + half[segment.axis()] - .000001) {
                    return false;
                }
            }
            final double pitch = 36 * unit;
            for (final var lane : tracks.get(segment.axis()).subMap(segment.fixed() - pitch + .000001, true,
                    segment.fixed() + pitch - .000001, true).values()) for (final Segment other : lane) {
                if (Math.min(segment.high(), other.high()) - Math.max(segment.low(), other.low()) <= .000001) continue;
                if ((segment.first && other.first || "app".equals(link.from.kind))
                        && link.from == other.link.from && link.outcome.equals(other.link.outcome)
                        || link.to == other.link.to && segment.fixed() == other.fixed()) continue;
                return false;
            }
            if (!"app".equals(link.from.kind)) for (final var lane : tracks.get(1 - segment.axis())
                    .subMap(segment.low(), false, segment.high(), false).values()) for (final Segment other : lane) {
                if (other.link.to == link.to || "app".equals(other.link.from.kind) || segment.fixed() <= other.low() || segment.fixed() >= other.high()) continue;
                final double clearance = (other.axis() == 0 ? 48 : 24) * Math.max(unit, Math.min(1, Math.min(other.link.from.stationScale(), other.link.to.stationScale())));
                if (Math.min(segment.fixed() - other.low(), other.high() - segment.fixed()) < clearance - .000001) return false;
            }
            return true;
        }

        private static List<double[]> simplify(final List<double[]> points) {
            final List<double[]> result = new ArrayList<>();
            for (final double[] point : points) {
                if (!result.isEmpty() && java.util.Arrays.equals(result.getLast(), point)) continue;
                while (result.size() > 1) {
                    final double[] a = result.get(result.size() - 2), b = result.getLast();
                    if (!(a[0] == b[0] && b[0] == point[0] || a[1] == b[1] && b[1] == point[1])) break;
                    result.removeLast();
                }
                result.add(point);
            }
            return result;
        }

        private static double length(final List<double[]> points) {
            double length = 0;
            for (int index = 1; index < points.size(); index++) length += Math.abs(points.get(index)[0] - points.get(index - 1)[0])
                    + Math.abs(points.get(index)[1] - points.get(index - 1)[1]);
            return length;
        }

        private record Segment(double[] a, double[] b, Connection link, boolean first) {
            private int axis() { return a[1] == b[1] ? 0 : 1; }
            private double fixed() { return a[1 - axis()]; }
            private double low() { return Math.min(a[axis()], b[axis()]); }
            private double high() { return Math.max(a[axis()], b[axis()]); }
        }
    }

    /** Bounding-volume route index also prunes whole batches internal to a collapsed region. */
    private static final class Routes {
        private final Routes left;
        private final Routes right;
        private final Box box;
        private final int first;
        private final int last;
        private final int fromFirst;
        private final int fromLast;
        private final int toFirst;
        private final int toLast;
        private final String outcome;
        private final int[] members;
        private final int[] metricMembers;
        private final boolean unknownMetrics;
        private final boolean unknownSelection;

        private Routes(final List<Route> edges) {
            this(sorted(edges), 0, edges.size());
        }

        private Routes(final List<Route> edges, final int start, final int end) {
            if (end - start == 1) {
                final Route edge = edges.get(start);
                left = null;
                right = null;
                box = edge.bounds();
                first = Math.min(edge.from.first, edge.to.first);
                last = Math.max(edge.from.last, edge.to.last);
                fromFirst = edge.from.first;
                fromLast = edge.from.last;
                toFirst = edge.to.first;
                toLast = edge.to.last;
                outcome = edge.outcome;
                metricMembers = edge.to.metricMembers;
                unknownMetrics = edge.to.node < 0 || !edge.to.metrics;
                unknownSelection = edge.from.node < 0 || edge.to.node < 0 || "app".equals(edge.from.kind);
                members = unknownSelection ? new int[0] : edge.to.members;
            } else {
                final int middle = (start + end) >>> 1;
                left = new Routes(edges, start, middle);
                right = new Routes(edges, middle, end);
                box = left.box.union(right.box);
                first = Math.min(left.first, right.first);
                last = Math.max(left.last, right.last);
                fromFirst = Math.min(left.fromFirst, right.fromFirst);
                fromLast = Math.max(left.fromLast, right.fromLast);
                toFirst = Math.min(left.toFirst, right.toFirst);
                toLast = Math.max(left.toLast, right.toLast);
                outcome = left.outcome.equals(right.outcome) ? left.outcome : "";
                members = union(left.members, right.members);
                metricMembers = union(left.metricMembers, right.metricMembers);
                unknownMetrics = left.unknownMetrics || right.unknownMetrics;
                unknownSelection = left.unknownSelection || right.unknownSelection;
            }
        }

        private static List<Route> sorted(final List<Route> edges) {
            final List<Route> sorted = new ArrayList<>(edges);
            sorted.sort(Comparator.comparingInt((Route route) -> route.from.first)
                    .thenComparingInt(route -> route.to.first).thenComparing(Route::id));
            return sorted;
        }
    }

    private static RailixValue.NumberValue decimal(final double value) {
        return RailixValue.number(BigDecimal.valueOf(value));
    }
}
