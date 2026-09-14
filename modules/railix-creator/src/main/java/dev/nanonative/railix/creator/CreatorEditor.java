package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Stable-ID editing and its derived read index. Neither compiler nor persistence reads projections. */
final class CreatorEditor {
    private final String source;
    private final RailixValue.ObjectValue metadata;
    private final RailixValue.ObjectValue project;
    private final Map<String, RailixValue.ObjectValue> nodes = new LinkedHashMap<>();
    private final Map<String, List<RailixValue>> outgoing = new HashMap<>();
    private final Map<String, String> previous = new HashMap<>();
    private final Map<String, RailixValue> info = new LinkedHashMap<>();
    private final Map<String, RailixValue> used = new LinkedHashMap<>();
    private final Map<String, RailixValue> groupCounts = new HashMap<>();
    private final Set<String> triggers = new LinkedHashSet<>();

    CreatorEditor(final String source, final RailixValue.ObjectValue metadata, final StepCatalog catalog) {
        this.source = source;
        this.metadata = metadata;
        project = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
        final Map<String, Integer> uses = new HashMap<>();
        for (final RailixValue value : array(project, "nodes")) {
            final var node = (RailixValue.ObjectValue) value;
            final String id = text(node, "id");
            nodes.put(id, node);
            final String use = text(node, "use");
            uses.merge(use, 1, Integer::sum);
            final var definition = catalog.find(use).orElseThrow();
            if (definition.kind() == StepDefinition.Kind.TRIGGER) {
                triggers.add(id);
            }
        }
        uses.forEach((id, count) -> used.put(id, RailixValue.number(count)));
        for (final RailixValue value : array(project, "links")) {
            final var link = (RailixValue.ObjectValue) value;
            final String from = owner(text(link, "from"));
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(value);
            if (!text(link, "to").equals("end")) previous.put(text(link, "to"), from);
        }
        final Map<String, String> owners = new HashMap<>();
        for (final String trigger : triggers) {
            final ArrayDeque<String> pending = new ArrayDeque<>();
            pending.add(trigger);
            while (!pending.isEmpty()) {
                final String id = pending.removeFirst();
                owners.put(id, trigger);
                outgoing.getOrDefault(id, List.of()).forEach(value -> {
                    final String to = text((RailixValue.ObjectValue) value, "to");
                    if (!to.equals("end")) pending.addLast(to);
                });
            }
        }
        int index = 0;
        final var styles = object(metadata, "steps");
        final Map<String, int[]> groups = new HashMap<>();
        for (final String id : nodes.keySet()) {
            info.put(id, RailixValue.object(Map.of("index", RailixValue.number(index++),
                    "trigger", RailixValue.string(owners.getOrDefault(id, "")))));
            final String group = text(object(styles, id), "group");
            if (!group.isEmpty()) {
                final int[] counts = groups.computeIfAbsent(group, ignored -> new int[2]);
                counts[0]++;
                if (!group.equals(text(object(styles, previous.getOrDefault(id, "")), "group"))) counts[1]++;
            }
        }
        groups.forEach((id, counts) -> groupCounts.put(id, RailixValue.object(Map.of(
                "steps", RailixValue.number(counts[0]), "regions", RailixValue.number(counts[1])))));
    }

    boolean matches(final String source, final RailixValue.ObjectValue metadata) {
        return this.source.equals(source) && List.of("groups", "steps").stream()
                .allMatch(field -> this.metadata.values().get(field).equals(metadata.values().get(field)));
    }

    RailixValue.ObjectValue project() {
        return project;
    }

    int flowCount() {
        return triggers.size();
    }

    int stepCount() {
        return nodes.size();
    }

    /** Returns the selected Step, its predecessor and Trigger, and shallow connection targets. */
    RailixValue.ObjectValue view(final String query, final RailixValue.ObjectValue settings) {
        final Map<String, String> parameters = parameters(query);
        final String selected = parameters.getOrDefault("node", "app");
        if (!nodes.containsKey(selected)) throw new NoSuchElementException("Step does not exist: " + selected + ".");
        final String trigger = text((RailixValue.ObjectValue) info.get(selected), "trigger");
        final Set<String> full = new LinkedHashSet<>(List.of("app", selected));
        if (!trigger.isEmpty()) full.add(trigger);
        if (previous.containsKey(selected)) full.add(previous.get(selected));
        final Map<String, RailixValue> visible = new LinkedHashMap<>();
        full.forEach(id -> visible.put(id, nodes.get(id)));
        final List<RailixValue> links = new ArrayList<>();
        for (final String id : full) {
            for (final RailixValue value : outgoing.getOrDefault(id, List.of())) {
                links.add(value);
                final String to = text((RailixValue.ObjectValue) value, "to");
                if (!to.equals("end") && !visible.containsKey(to)) {
                    final var target = nodes.get(to);
                    visible.put(to, RailixValue.object(Map.of("id", target.values().get("id"),
                            "use", target.values().get("use"))));
                }
            }
        }
        final Map<String, RailixValue> indexes = new LinkedHashMap<>();
        final Map<String, RailixValue> styles = new LinkedHashMap<>();
        final var allStyles = object(metadata, "steps");
        visible.keySet().forEach(id -> {
            indexes.put(id, info.get(id));
            if (allStyles.values().containsKey(id)) styles.put(id, allStyles.values().get(id));
        });
        final String group = parameters.getOrDefault("group", "").isEmpty()
                ? text(object(allStyles, selected), "group") : parameters.get("group");
        final String search = parameters.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        final int offset = Integer.parseInt(parameters.getOrDefault("offset", "0"));
        final List<RailixValue> matching = array(metadata, "groups").stream()
                .filter(value -> {
                    final String name = text((RailixValue.ObjectValue) value, "name");
                    return (name.isEmpty() ? "Group" : name).toLowerCase(Locale.ROOT).contains(search);
                }).toList();
        final List<RailixValue> groups = new ArrayList<>(matching.stream().skip(offset).limit(64).toList());
        final String assignedGroup = text(object(allStyles, selected), "group");
        array(metadata, "groups").stream().filter(value -> {
            final String id = text((RailixValue.ObjectValue) value, "id");
            return id.equals(group) || id.equals(assignedGroup);
        }).filter(value -> !groups.contains(value)).forEach(groups::add);
        final Map<String, RailixValue> counts = new LinkedHashMap<>();
        groups.forEach(value -> {
            final String id = text((RailixValue.ObjectValue) value, "id");
            counts.put(id, groupCounts.getOrDefault(id, RailixValue.object(Map.of(
                    "steps", RailixValue.number(0), "regions", RailixValue.number(0)))));
        });
        final Map<String, RailixValue> presentation = new LinkedHashMap<>(settings.values());
        presentation.put("groups", RailixValue.array(groups));
        presentation.put("steps", RailixValue.object(styles));
        return RailixValue.object(Map.of(
                "project", RailixValue.object(Map.of("format", project.values().get("format"), "id", project.values().get("id"),
                        "nodes", RailixValue.array(new ArrayList<>(visible.values())), "links", RailixValue.array(links))),
                "creator", RailixValue.object(presentation),
                "editor", RailixValue.object(Map.of("nodes", RailixValue.object(indexes), "full", RailixValue.array(full.stream().<RailixValue>map(RailixValue::string).toList()),
                        "used", RailixValue.object(used), "groups", RailixValue.object(counts),
                        "group_count", RailixValue.number(array(metadata, "groups").size()),
                        "group_matches", RailixValue.number(matching.size()), "offset", RailixValue.number(offset)))));
    }

    /** Expands explicit flow deletion into ordinary stable-ID changes at the owning graph boundary. */
    RailixValue.ObjectValue changes(final RailixValue.ObjectValue changes) {
        if (!changes.values().containsKey("remove_flows")) return changes;
        if (!(changes.values().get("remove_flows") instanceof RailixValue.ArrayValue removals)) {
            throw new IllegalArgumentException("remove_flows must contain Trigger IDs.");
        }
        final Set<String> removedTriggers = new LinkedHashSet<>();
        for (final RailixValue value : removals.values()) {
            if (!(value instanceof RailixValue.StringValue id) || !triggers.contains(id.value())) {
                throw new IllegalArgumentException("Only existing Triggers identify flows to remove.");
            }
            removedTriggers.add(id.value());
        }
        final Set<String> removed = new LinkedHashSet<>();
        info.forEach((node, fields) -> {
            if (removedTriggers.contains(text((RailixValue.ObjectValue) fields, "trigger"))) removed.add(node);
        });
        final Map<String, RailixValue> fields = new LinkedHashMap<>(changes.values());
        fields.remove("remove_flows");
        // Validate explicit edits before deletion can replace them, and filter their effective connections.
        final RailixValue.ObjectValue edited = apply(project, RailixValue.object(fields), true);
        final Map<String, RailixValue> nodeChanges = new LinkedHashMap<>(object(changes, "nodes").values());
        final Map<String, RailixValue> linkChanges = new LinkedHashMap<>(object(changes, "links").values());
        removed.forEach(id -> nodeChanges.put(id, RailixValue.nullValue()));
        final Map<String, List<RailixValue>> byPort = new LinkedHashMap<>();
        array(edited, "links").forEach(value -> byPort.computeIfAbsent(text((RailixValue.ObjectValue) value, "from"),
                ignored -> new ArrayList<>()).add(value));
        byPort.forEach((from, values) -> {
            if (removed.contains(owner(from))) linkChanges.put(from, RailixValue.nullValue());
            else if (values.stream().anyMatch(value -> removed.contains(text((RailixValue.ObjectValue) value, "to")))) {
                linkChanges.put(from, RailixValue.array(values.stream().filter(value ->
                        !removed.contains(text((RailixValue.ObjectValue) value, "to"))).toList()));
            }
        });
        fields.put("nodes", RailixValue.object(nodeChanges));
        fields.put("links", RailixValue.object(linkChanges));
        return RailixValue.object(fields);
    }

    /**
     * Applies changed entries to the canonical document, retaining existing array positions.
     * Nodes and groups use their ID; connections use their source port and replace its targets.
     * A JSON null removes an entry. No array offsets, recursive value patches, or persisted edit log.
     *
     * @return a new document for compilation or presentation validation; the original is unchanged
     * @throws IllegalArgumentException when an edit field, entry shape, or stable key is invalid
     */
    static RailixValue.ObjectValue apply(
            final RailixValue.ObjectValue document,
            final RailixValue.ObjectValue changes,
            final boolean project
    ) {
        final Set<String> fields = project ? Set.of("format", "id", "nodes", "links") : Set.of("groups", "steps", "theme");
        final Map<String, RailixValue> result = new LinkedHashMap<>(document.values());
        for (final var entry : changes.values().entrySet()) {
            final String field = entry.getKey();
            if (!fields.contains(field)) {
                throw new IllegalArgumentException("Unknown edit field: " + field + ".");
            }
            if (field.equals("id") || field.equals("format") || field.equals("theme")) {
                if (field.equals("theme") && entry.getValue() instanceof RailixValue.NullValue) result.remove(field);
                else result.put(field, entry.getValue());
                continue;
            }
            if (!(entry.getValue() instanceof RailixValue.ObjectValue edits)) {
                throw new IllegalArgumentException(field + " edits must be an object keyed by stable ID.");
            }
            if (field.equals("steps")) {
                final Map<String, RailixValue> steps = new LinkedHashMap<>(
                        ((RailixValue.ObjectValue) document.values().get(field)).values());
                edits.values().forEach((id, value) -> {
                    if (value instanceof RailixValue.NullValue) {
                        steps.remove(id);
                    } else {
                        steps.put(id, value);
                    }
                });
                result.put(field, RailixValue.object(steps));
            } else {
                result.put(field, entries((RailixValue.ArrayValue) document.values().get(field), edits,
                        field.equals("links") ? "from" : "id"));
            }
        }
        if (!project && changes.values().get("groups") instanceof RailixValue.ObjectValue groups) {
            final Set<String> removed = groups.values().entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof RailixValue.NullValue)
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            final Map<String, RailixValue> steps = new LinkedHashMap<>();
            ((RailixValue.ObjectValue) result.get("steps")).values().forEach((id, value) -> {
                if (value instanceof RailixValue.ObjectValue style
                        && style.values().get("group") instanceof RailixValue.StringValue group
                        && removed.contains(group.value())) {
                    final Map<String, RailixValue> retained = new LinkedHashMap<>(style.values());
                    retained.remove("group");
                    if (!retained.isEmpty()) steps.put(id, RailixValue.object(retained));
                } else steps.put(id, value);
            });
            result.put("steps", RailixValue.object(steps));
        }
        return RailixValue.object(result);
    }

    private static RailixValue.ArrayValue entries(
            final RailixValue.ArrayValue current,
            final RailixValue.ObjectValue edits,
            final String key
    ) {
        final Map<String, List<RailixValue>> pending = new LinkedHashMap<>();
        for (final var edit : edits.values().entrySet()) {
            if (edit.getValue() instanceof RailixValue.NullValue) {
                pending.put(edit.getKey(), List.of());
                continue;
            }
            final List<RailixValue> replacements;
            if (key.equals("from")) {
                if (!(edit.getValue() instanceof RailixValue.ArrayValue links)) {
                    throw new IllegalArgumentException("Connection edits must contain a list for each source port.");
                }
                replacements = links.values();
            } else {
                replacements = List.of(edit.getValue());
            }
            for (final RailixValue value : replacements) {
                if (!(value instanceof RailixValue.ObjectValue entry)
                        || !(entry.values().get(key) instanceof RailixValue.StringValue id)
                        || !id.value().equals(edit.getKey())) {
                    throw new IllegalArgumentException("Edited " + key + " must match its key: " + edit.getKey() + ".");
                }
            }
            pending.put(edit.getKey(), replacements);
        }
        final List<RailixValue> result = new ArrayList<>(current.values().size());
        for (final RailixValue value : current.values()) {
            final var entry = (RailixValue.ObjectValue) value;
            final String id = ((RailixValue.StringValue) entry.values().get(key)).value();
            if (!edits.values().containsKey(id)) {
                result.add(value);
            } else {
                final List<RailixValue> replacement = pending.remove(id);
                if (replacement != null) {
                    result.addAll(replacement);
                }
            }
        }
        pending.values().forEach(result::addAll);
        return RailixValue.array(result);
    }

    private static Map<String, String> parameters(final String query) {
        final Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) return result;
        if (query.length() > 8192) throw new IllegalArgumentException("Editor query exceeds 8192 characters.");
        for (final String pair : query.split("&", -1)) {
            final String[] parts = pair.split("=", 2);
            final String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            final String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (!Set.of("node", "group", "q", "offset").contains(key) || result.putIfAbsent(key, value) != null
                    || key.equals("node") && value.isBlank()) throw new IllegalArgumentException("Invalid editor query.");
        }
        if (result.containsKey("offset") && !result.get("offset").matches("[0-9]{1,6}")) {
            throw new IllegalArgumentException("Group offset must be a non-negative integer.");
        }
        return result;
    }

    private static String owner(final String from) {
        final int separator = from.lastIndexOf('.');
        return separator < 0 ? "" : from.substring(0, separator);
    }

    private static String text(final RailixValue.ObjectValue value, final String key) {
        return value.values().get(key) instanceof RailixValue.StringValue text ? text.value() : "";
    }

    private static RailixValue.ObjectValue object(final RailixValue.ObjectValue value, final String key) {
        return value.values().get(key) instanceof RailixValue.ObjectValue object ? object : RailixValue.object(Map.of());
    }

    private static List<RailixValue> array(final RailixValue.ObjectValue value, final String key) {
        return ((RailixValue.ArrayValue) value.values().get(key)).values();
    }
}
