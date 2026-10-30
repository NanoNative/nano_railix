package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Complete editor changes over materialized Steps. Reuse is an editing relationship only. */
final class CreatorChanges {
    private CreatorChanges() { }

    record Edit(RailixValue.ObjectValue project, RailixValue.ObjectValue creator, String selection) { }
    record Delta(RailixValue.ObjectValue changes, Map<String, Map<String, Integer>> positions) { }

    static Delta delta(final RailixValue.ObjectValue before, final RailixValue.ObjectValue after) {
        final Map<String, Map<String, Integer>> positions = new LinkedHashMap<>();
        for (final String field : List.of("nodes", "links", "groups")) {
            final Set<String> old = new LinkedHashSet<>();
            array(before, field).forEach(value -> old.add(entryId(object(value), field)));
            final Map<String, Integer> restored = new LinkedHashMap<>();
            final List<RailixValue> target = array(after, field);
            for (int index = 0; index < target.size(); index++) {
                final String id = entryId(object(target.get(index)), field);
                if (!old.contains(id)) restored.put(id, index);
            }
            if (!restored.isEmpty()) positions.put(field, restored);
        }
        return new Delta(diff(before, after), positions);
    }

    static RailixValue.ObjectValue restore(final RailixValue.ObjectValue document, final Delta delta, final boolean project) {
        var result = CreatorEditor.apply(document, delta.changes(), project);
        for (final var field : delta.positions().entrySet()) {
            final List<RailixValue> values = new ArrayList<>(array(result, field.getKey()));
            final Map<String, RailixValue> restored = new LinkedHashMap<>();
            values.removeIf(value -> {
                final String id = entryId(object(value), field.getKey());
                if (!field.getValue().containsKey(id)) return false;
                restored.put(id, value);
                return true;
            });
            field.getValue().entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> {
                if (restored.containsKey(entry.getKey())) values.add(Math.min(entry.getValue(), values.size()), restored.get(entry.getKey()));
            });
            result = with(result, field.getKey(), RailixValue.array(values));
        }
        return result;
    }

    private static String entryId(final RailixValue.ObjectValue value, final String field) {
        return field.equals("links") ? text(value, "from") + ">" + text(value, "to") : text(value, "id");
    }

    static Edit apply(final RailixValue.ObjectValue project, final RailixValue.ObjectValue creator,
                      final RailixValue.ObjectValue command, final StepCatalog catalog) {
        final String kind = text(command, "kind");
        for (final var field : command.values().entrySet()) {
            if (!Set.of("kind", "project", "creator", "node", "group", "from", "to", "id", "nodes", "start", "end", "name", "entry", "exit").contains(field.getKey()))
                throw new IllegalArgumentException("Unknown editor field: " + field.getKey() + ".");
            if (Set.of("project", "creator").contains(field.getKey())) {
                if (!(field.getValue() instanceof RailixValue.ObjectValue)) throw new IllegalArgumentException(field.getKey() + " must be an object.");
            } else if (field.getKey().equals("nodes")) {
                if (!(field.getValue() instanceof RailixValue.ArrayValue values)
                        || values.values().stream().anyMatch(value -> !(value instanceof RailixValue.StringValue)))
                    throw new IllegalArgumentException("nodes must contain Step IDs.");
            } else if (!(field.getValue() instanceof RailixValue.StringValue)) throw new IllegalArgumentException(field.getKey() + " must be text.");
        }
        if (kind.equals("patch")) {
            if (object(command, "creator").values().containsKey("reuse"))
                throw new IllegalArgumentException("Use Copy, Reuse or Detach to change editing relationships.");
            final var updated = CreatorEditor.apply(project, object(command, "project"), true);
            final var metadata = CreatorEditor.apply(creator, object(command, "creator"), false);
            final Edit structure = propagateStructure(project, updated, metadata);
            final var retained = prune(structure.creator(), structure.project());
            final var propagated = propagate(project, structure.project(), retained);
            final Set<String> known = new LinkedHashSet<>(keyed(array(project, "nodes"), "id").keySet());
            known.addAll(keyed(array(propagated, "nodes"), "id").keySet());
            for (final String id : object(structure.creator(), "steps").values().keySet()) {
                if (!known.contains(id)) throw new IllegalArgumentException("Creator presentation references an unknown Step: " + id + ".");
            }
            return new Edit(propagated, prune(retained, propagated), text(command, "node"));
        }
        final Map<String, RailixValue> nodes = keyed(array(project, "nodes"), "id");
        final Map<String, RailixValue> links = keyed(array(project, "links"), "from");
        final Map<String, RailixValue> groups = keyed(array(creator, "groups"), "id");
        final Map<String, RailixValue> styles = new LinkedHashMap<>(object(creator, "steps").values());
        final List<RailixValue> reuse = new ArrayList<>(array(creator, "reuse"));
        final String node = text(command, "node");
        final String group = text(command, "group");
        final String from = text(command, "from");
        final String id = text(command, "id");
        String selection = node;
        switch (kind) {
            case "delete" -> {
                ordinary(nodes, node, catalog);
                final var definition = catalog.find(text(object(nodes.get(node)), "use")).orElseThrow();
                final String primary = node + "." + definition.primaryOutcome();
                final String next = text(object(links.get(primary)), "to");
                if (outgoing(links, node).stream().map(CreatorChanges::object)
                        .anyMatch(edge -> !text(edge, "from").equals(primary) && !text(edge, "to").equals("end")))
                    throw new IllegalArgumentException("Reconnect the other outputs before deleting this Step.");
                for (final var edge : new ArrayList<>(links.entrySet())) {
                    if (owner(text(object(edge.getValue()), "from")).equals(node)) links.remove(edge.getKey());
                    else if (text(object(edge.getValue()), "to").equals(node)) {
                        links.put(edge.getKey(), link(text(object(edge.getValue()), "from"), next));
                        selection = owner(text(object(edge.getValue()), "from"));
                    }
                }
                nodes.remove(node); styles.remove(node);
            }
            case "connect" -> {
                if (!links.containsKey(from)) throw new IllegalArgumentException("Choose an existing output.");
                links.put(from, link(from, required(command, "to")));
            }
            case "group" -> {
                if (id.isEmpty() || groups.containsKey(id)) throw new IllegalArgumentException("A new group needs an unused ID.");
                final Set<String> members = new LinkedHashSet<>();
                for (final RailixValue value : array(command, "nodes")) members.add(((RailixValue.StringValue) value).value());
                if (!text(command, "start").isEmpty()) {
                    final Set<String> after = reachable(text(command, "start"), links, false);
                    after.retainAll(reachable(required(command, "end"), links, true));
                    if(after.isEmpty()) {
                        after.addAll(reachable(text(command, "end"), links, false));
                        after.retainAll(reachable(text(command, "start"), links, true));
                    }
                    if(after.isEmpty()) throw new IllegalArgumentException("The selected Steps do not share a directed path.");
                    members.addAll(after);
                }
                if (members.isEmpty() && !node.isEmpty()) members.add(node);
                if (members.isEmpty()) throw new IllegalArgumentException("Select Steps to group.");
                for (final String member : members) {
                    ordinary(nodes, member, catalog);
                    styles.put(member, with(object(styles.get(member)), "group", RailixValue.string(id)));
                }
                groups.put(id, RailixValue.object(Map.of("id", RailixValue.string(id), "name",
                        RailixValue.string(text(command, "name").isEmpty() ? "Group" : text(command, "name")))));
                selection = members.iterator().next();
            }
            case "copy", "reuse" -> {
                if (id.isEmpty() || !links.containsKey(from)) throw new IllegalArgumentException("Choose a destination belt.");
                final Map<String, RailixValue> originals = new LinkedHashMap<>();
                if (!group.isEmpty()) {
                    if (!groups.containsKey(group)) throw new IllegalArgumentException("Group is no longer available.");
                    nodes.forEach((key, value) -> {
                        if (group.equals(text(object(styles.get(key)), "group"))) originals.put(key, value);
                    });
                } else {
                    ordinary(nodes, node, catalog);
                    originals.put(node, nodes.get(node));
                }
                if (originals.isEmpty()) throw new IllegalArgumentException("The selected group is empty.");
                if (kind.equals("reuse")) for (final RailixValue relationship : reuse) {
                    for (final RailixValue value : array(object(relationship), "uses")) {
                        final Set<String> existing = memberIds(object(value));
                        if (!existing.equals(originals.keySet()) && existing.stream().anyMatch(originals::containsKey))
                            throw new IllegalArgumentException("This selection contains another linked selection. Copy it, or detach that selection before reusing it as a whole.");
                    }
                }
                final Set<String> entries = new LinkedHashSet<>(originals.keySet());
                links.values().forEach(value -> {
                    final var edge = object(value);
                    if (originals.containsKey(owner(text(edge, "from")))) entries.remove(text(edge, "to"));
                });
                if (entries.size() > 1) throw new IllegalArgumentException("This group has several independent entrances. Place a single-entrance section instead. Multi-entrance placement is not supported yet.");
                String entry = text(command, "entry");
                if (entry.isEmpty() && entries.size() == 1) entry = entries.iterator().next();
                if (!entries.contains(entry)) throw new IllegalArgumentException("Choose a group entrance: " + String.join(", ", entries));
                final String continuation = text(object(links.get(from)), "to");
                final List<String> exits = links.values().stream().map(CreatorChanges::object)
                        .filter(edge -> originals.containsKey(owner(text(edge, "from"))) && !originals.containsKey(text(edge, "to")))
                        .map(edge -> text(edge, "from")).toList();
                String exit = text(command, "exit");
                if (exit.isEmpty() && exits.size() == 1) exit = exits.getFirst();
                if (exit.isEmpty() && group.isEmpty()) exit = node + "." + catalog.find(text(object(originals.get(node)), "use")).orElseThrow().primaryOutcome();
                if (!continuation.equals("end") && !exits.contains(exit)) {
                    throw new IllegalArgumentException("Choose which output continues this belt: " + String.join(", ", exits));
                }
                final Map<String, RailixValue> copied = new LinkedHashMap<>();
                originals.keySet().forEach(key -> copied.put(key, RailixValue.string(identity(id, key))));
                final String newGroup = group.isEmpty() ? "" : identity(id, "group");
                if (copied.values().stream().anyMatch(value -> nodes.containsKey(((RailixValue.StringValue) value).value()))
                        || !newGroup.isEmpty() && groups.containsKey(newGroup))
                    throw new IllegalArgumentException("This placement already exists. Start a new Copy or Reuse action.");
                if (!newGroup.isEmpty()) groups.put(newGroup, with(object(groups.get(group)), "id", RailixValue.string(newGroup)));
                for (final var original : originals.entrySet()) {
                    final String newId = ((RailixValue.StringValue) copied.get(original.getKey())).value();
                    nodes.put(newId, with(object(original.getValue()), "id", RailixValue.string(newId)));
                    final Map<String, RailixValue> style = new LinkedHashMap<>(object(styles.get(original.getKey())).values());
                    style.remove("group");
                    if (!newGroup.isEmpty()) style.put("group", RailixValue.string(newGroup));
                    if (!style.isEmpty()) styles.put(newId, RailixValue.object(style));
                }
                for (final RailixValue value : List.copyOf(links.values())) {
                    final var edge = object(value);
                    final String port = text(edge, "from");
                    if (!originals.containsKey(owner(port))) continue;
                    final String newPort = ((RailixValue.StringValue) copied.get(owner(port))).value() + port.substring(port.lastIndexOf('.'));
                    final String target = text(edge, "to");
                    final String newTarget = copied.containsKey(target) ? ((RailixValue.StringValue) copied.get(target)).value()
                            : port.equals(exit) ? continuation : "end";
                    links.put(newPort, link(newPort, newTarget));
                }
                selection = ((RailixValue.StringValue) copied.get(entry)).value();
                links.put(from, link(from, selection));
                if (kind.equals("reuse")) {
                    int family = -1;
                    RailixValue.ObjectValue sourceUse = null;
                    for (int index = 0; index < reuse.size() && family < 0; index++) {
                        for (final RailixValue value : array(object(reuse.get(index)), "uses")) {
                            final var use = object(value);
                            final Set<String> members = new LinkedHashSet<>();
                            object(use, "members").values().values().forEach(member -> members.add(((RailixValue.StringValue) member).value()));
                            if (members.equals(originals.keySet())) { family = index; sourceUse = use; break; }
                        }
                    }
                    final Map<String, RailixValue> originalMembers = new LinkedHashMap<>();
                    originals.keySet().forEach(key -> originalMembers.put(key, RailixValue.string(key)));
                    final List<RailixValue> uses = family < 0 ? new ArrayList<>(List.of(use(identity(id, "original"), group, originalMembers)))
                            : new ArrayList<>(array(object(reuse.get(family)), "uses"));
                    final Map<String, RailixValue> aliases = new LinkedHashMap<>();
                    final var sourceMembers = sourceUse == null ? originalMembers : object(sourceUse, "members").values();
                    sourceMembers.forEach((role, member) -> aliases.put(role, copied.get(((RailixValue.StringValue) member).value())));
                    uses.add(use(identity(id, "use"), newGroup, aliases));
                    final var relationship = RailixValue.object(Map.of("id", family < 0 ? RailixValue.string(identity(id, "reuse"))
                            : object(reuse.get(family)).values().get("id"), "uses", RailixValue.array(uses)));
                    if (family < 0) reuse.add(relationship); else reuse.set(family, relationship);
                }
            }
            case "detach" -> {
                for (int index = reuse.size() - 1; index >= 0; index--) {
                    final var relationship = object(reuse.get(index));
                    final List<RailixValue> uses = array(relationship, "uses").stream().filter(value -> {
                        final var use = object(value);
                        return group.isEmpty() ? !object(use, "members").values().containsValue(RailixValue.string(node))
                                : !group.equals(text(use, "group"));
                    }).toList();
                    if (uses.size() < 2) reuse.remove(index);
                    else reuse.set(index, with(relationship, "uses", RailixValue.array(uses)));
                }
            }
            default -> throw new IllegalArgumentException("Unknown editor action: " + kind + ".");
        }
        final var updated = with(with(project, "nodes", RailixValue.array(new ArrayList<>(nodes.values()))),
                "links", RailixValue.array(new ArrayList<>(links.values())));
        var metadata = with(with(creator, "groups", RailixValue.array(new ArrayList<>(groups.values()))), "steps", RailixValue.object(styles));
        metadata = reuse.isEmpty() ? without(metadata, "reuse") : with(metadata, "reuse", RailixValue.array(reuse));
        if (kind.equals("delete") || kind.equals("connect")) {
            final Edit structure = propagateStructure(project, updated, metadata);
            return new Edit(structure.project(), prune(structure.creator(), structure.project()), selection);
        }
        return new Edit(updated, metadata, selection);
    }

    static RailixValue.ObjectValue propagate(final RailixValue.ObjectValue before, final RailixValue.ObjectValue after,
                                             final RailixValue.ObjectValue creator) {
        if (before.equals(after) || array(creator, "reuse").isEmpty()) return after;
        final Map<String, RailixValue> original = keyed(array(before, "nodes"), "id");
        final Map<String, RailixValue> nodes = keyed(array(after, "nodes"), "id");
        final Map<String, RailixValue> desired = new LinkedHashMap<>();
        final Map<String, Set<String>> related = counterparts(creator);
        final Map<String, RailixValue> links = keyed(array(after, "links"), "from");
        final Map<String, RailixValue> oldLinks = keyed(array(before, "links"), "from");
        final Map<String, List<RailixValue>> oldOutgoing = outgoingIndex(oldLinks), newOutgoing = outgoingIndex(links);
        nodes.forEach((id, value) -> {
            if (original.containsKey(id) && !original.get(id).equals(value)) desired.put(id, without(object(value), "id"));
        });
        final Map<String, String> templates = new LinkedHashMap<>();
        desired.keySet().forEach(id -> templates.put(id, id));
        final ArrayDeque<String> pending = new ArrayDeque<>(desired.keySet());
        while (!pending.isEmpty()) {
            final String source = pending.removeFirst();
            final String template = templates.get(source);
            for (final String target : related.getOrDefault(source, Set.of())) {
                if (!nodes.containsKey(target)) continue;
                final RailixValue existing = desired.putIfAbsent(target, desired.get(source));
                if (existing != null && !existing.equals(desired.get(source))) {
                    throw new IllegalArgumentException("Linked uses contain conflicting edits. Apply one shared change.");
                }
                if (existing != null) continue;
                templates.put(target, template);
                pending.addLast(target);
                nodes.put(target, with(object(desired.get(source)), "id", RailixValue.string(target)));
                final Set<String> ports = new LinkedHashSet<>(), continued = new LinkedHashSet<>();
                for (final RailixValue edgeValue : newOutgoing.getOrDefault(template, List.of())) {
                    final var edge = object(edgeValue);
                    final String suffix = text(edge, "from").substring(template.length());
                    final String port = target + suffix;
                    ports.add(port);
                    if (links.containsKey(port)) continue;
                    final Set<String> destinations = new LinkedHashSet<>();
                    for (final RailixValue previous : oldOutgoing.getOrDefault(template, List.of())) {
                        final var old = object(previous);
                        if (!text(edge, "to").equals(text(old, "to"))) continue;
                        final String oldPort = target + text(old, "from").substring(template.length());
                        if (oldLinks.containsKey(oldPort)) {
                            destinations.add(text(object(oldLinks.get(oldPort)), "to"));
                            continued.add(oldPort);
                        }
                    }
                    if (destinations.size() > 1) throw new IllegalArgumentException("Choose the continuation in every linked use before replacing its outputs.");
                    links.put(port, link(port, destinations.isEmpty() ? "end" : destinations.iterator().next()));
                }
                for (final RailixValue previous : oldOutgoing.getOrDefault(target, List.of())) {
                    final var edge = object(previous);
                    final String port = text(edge, "from");
                    if (ports.contains(port)) continue;
                    if (!text(edge, "to").equals("end") && !continued.contains(port))
                        throw new IllegalArgumentException("Output " + port + " is connected in another use. Reconnect it or detach before removing it.");
                    links.remove(port);
                }
            }
        }
        return with(with(after, "nodes", RailixValue.array(new ArrayList<>(nodes.values()))), "links", RailixValue.array(new ArrayList<>(links.values())));
    }

    private static Edit propagateStructure(final RailixValue.ObjectValue before, final RailixValue.ObjectValue after,
                                            final RailixValue.ObjectValue metadata) {
        if (before.equals(after) || array(metadata, "reuse").isEmpty()) return new Edit(after, metadata, "");
        final Map<String, RailixValue> oldNodes = keyed(array(before, "nodes"), "id");
        final Map<String, RailixValue> nodes = keyed(array(after, "nodes"), "id");
        final Map<String, RailixValue> oldLinks = keyed(array(before, "links"), "from");
        final Map<String, RailixValue> links = keyed(array(after, "links"), "from");
        final Map<String, RailixValue> sourceLinks = Map.copyOf(links);
        final Map<String, List<RailixValue>> oldOutgoing = outgoingIndex(oldLinks), newOutgoing = outgoingIndex(links);
        final Map<String, RailixValue> styles = new LinkedHashMap<>(object(metadata, "steps").values());
        final Map<String, List<String>> additions = new LinkedHashMap<>();
        nodes.keySet().stream().filter(id -> !oldNodes.containsKey(id)).forEach(id ->
                additions.computeIfAbsent(text(object(styles.get(id)), "group"), ignored -> new ArrayList<>()).add(id));
        final List<RailixValue> families = new ArrayList<>();
        for (final RailixValue value : array(metadata, "reuse")) {
            final var family = object(value);
            final List<RailixValue> uses = new ArrayList<>(array(family, "uses"));
            RailixValue.ObjectValue source = null;
            Map<String, RailixValue> members = Map.of();
            for (final RailixValue candidate : uses) {
                final var use = object(candidate);
                final Map<String, RailixValue> current = new LinkedHashMap<>(object(use, "members").values());
                current.entrySet().removeIf(entry -> !nodes.containsKey(((RailixValue.StringValue) entry.getValue()).value()));
                // Removing a whole occurrence does not remove the shared processing from the others.
                if (current.isEmpty()) continue;
                if (text(use, "group").isEmpty() && current.size() == object(use, "members").values().size()) continue;
                if (!text(use, "group").isEmpty()) additions.getOrDefault(text(use, "group"), List.of())
                        .forEach(id -> current.put(id, RailixValue.string(id)));
                final Set<String> oldIds = memberIds(use);
                final boolean structural = !current.equals(object(use, "members").values())
                        || oldIds.stream().anyMatch(id -> !oldOutgoing.getOrDefault(id, List.of()).equals(newOutgoing.getOrDefault(id, List.of())));
                if (!structural) continue;
                // External rewiring is local. Only topology inside the occurrence is shared.
                if (current.equals(object(use, "members").values()) && oldIds.stream().allMatch(id ->
                        oldOutgoing.getOrDefault(id, List.of()).stream().filter(edge -> oldIds.contains(text(object(edge), "to")))
                                .collect(java.util.stream.Collectors.toSet()).equals(newOutgoing.getOrDefault(id, List.of()).stream()
                                        .filter(edge -> oldIds.contains(text(object(edge), "to"))).collect(java.util.stream.Collectors.toSet())))) continue;
                if (source != null) throw new IllegalArgumentException("Change one linked occurrence at a time.");
                source = use;
                members = current;
            }
            if (source == null) { families.add(value); continue; }
            final Map<String, String> sourceRoles = new LinkedHashMap<>();
            members.forEach((role, id) -> sourceRoles.put(((RailixValue.StringValue) id).value(), role));
            final var origin = source;
            for (int index = 0; index < uses.size(); index++) {
                final var target = object(uses.get(index));
                if (target.equals(source)) { uses.set(index, with(source, "members", RailixValue.object(members))); continue; }
                final Set<String> targetMembers = memberIds(target);
                if (targetMembers.stream().noneMatch(nodes::containsKey)) continue;
                final Map<String, RailixValue> aliases = new LinkedHashMap<>();
                for (final var member : members.entrySet()) aliases.put(member.getKey(), object(target, "members").values()
                        .getOrDefault(member.getKey(), RailixValue.string(identity(text(target, "id"), member.getKey()))));
                final Set<String> removed = new LinkedHashSet<>(targetMembers);
                aliases.values().forEach(id -> removed.remove(((RailixValue.StringValue) id).value()));
                removed.forEach(id -> { nodes.remove(id); styles.remove(id); });
                final Set<String> ports = members.entrySet().stream().flatMap(member ->
                        newOutgoing.getOrDefault(((RailixValue.StringValue)member.getValue()).value(),List.of()).stream().map(edge -> {
                            final String port = text(object(edge),"from");
                            return ((RailixValue.StringValue)aliases.get(member.getKey())).value() + port.substring(port.lastIndexOf('.'));
                        })).collect(java.util.stream.Collectors.toSet());
                links.entrySet().removeIf(entry -> targetMembers.contains(owner(text(object(entry.getValue()), "from")))
                        && !ports.contains(text(object(entry.getValue()),"from")));
                for (final var member : members.entrySet()) {
                    final String sourceId = ((RailixValue.StringValue) member.getValue()).value();
                    final String targetId = ((RailixValue.StringValue) aliases.get(member.getKey())).value();
                    nodes.put(targetId, with(object(nodes.get(sourceId)), "id", RailixValue.string(targetId)));
                    if (!oldNodes.containsKey(targetId)) {
                        final var presentation = text(target,"group").isEmpty() ? without(object(styles.get(sourceId)),"group")
                                : with(object(styles.get(sourceId)),"group",target.values().get("group"));
                        if (!presentation.values().isEmpty()) styles.put(targetId,presentation);
                    }
                    for (final RailixValue edgeValue : newOutgoing.getOrDefault(sourceId, List.of())) {
                        final var edge = object(edgeValue);
                        final String sourcePort = text(edge, "from"), destination = text(edge, "to");
                        final String targetPort = targetId + sourcePort.substring(sourcePort.lastIndexOf('.'));
                        final String role = sourceRoles.get(destination);
                        String mapped = role == null ? destination : ((RailixValue.StringValue) aliases.get(role)).value();
                        if (role == null) {
                            // Preserve the corresponding external continuation, including when a deleted last member is bypassed.
                            final List<String> boundary = new ArrayList<>();
                            for (final var old : object(origin, "members").values().entrySet()) {
                                final String oldId = ((RailixValue.StringValue) old.getValue()).value();
                                for (final RailixValue oldEdge : oldOutgoing.getOrDefault(oldId, List.of())) if (destination.equals(text(object(oldEdge), "to"))) {
                                    final RailixValue targetMember = object(target, "members").values().get(old.getKey());
                                    final String port = ((RailixValue.StringValue) targetMember).value()
                                            + text(object(oldEdge), "from").substring(text(object(oldEdge), "from").lastIndexOf('.'));
                                    if (oldLinks.containsKey(port)) boundary.add(text(object(oldLinks.get(port)), "to"));
                                }
                            }
                            if (boundary.stream().distinct().count() > 1) throw new IllegalArgumentException("Choose the affected external output before changing this linked group.");
                            if (!boundary.isEmpty()) mapped = boundary.getFirst();
                        }
                        links.put(targetPort, link(targetPort, mapped));
                    }
                }
                // Arrivals into a removed entrance follow the same bypass as the edited occurrence.
                for (final var entry : new ArrayList<>(links.entrySet())) {
                    final var edge = object(entry.getValue());
                    if (!removed.contains(text(edge, "to"))) continue;
                    final String role = object(target, "members").values().entrySet().stream()
                            .filter(member -> member.getValue().equals(edge.values().get("to"))).map(Map.Entry::getKey).findFirst().orElseThrow();
                    final String removedSource = ((RailixValue.StringValue) object(origin, "members").values().get(role)).value();
                    final List<String> bypasses = oldLinks.values().stream().map(CreatorChanges::object)
                            .filter(old -> removedSource.equals(text(old, "to"))).map(old -> sourceLinks.get(text(old, "from")))
                            .filter(java.util.Objects::nonNull).map(CreatorChanges::object).map(afterEdge -> text(afterEdge, "to"))
                            .distinct().toList();
                    if (bypasses.size() != 1 || !sourceRoles.containsKey(bypasses.getFirst()))
                        throw new IllegalArgumentException("Choose a remaining entrance before deleting this linked Step, or detach this use.");
                    links.put(entry.getKey(), link(text(edge, "from"), ((RailixValue.StringValue) aliases.get(sourceRoles.get(bypasses.getFirst()))).value()));
                }
                uses.set(index, with(target, "members", RailixValue.object(aliases)));
            }
            families.add(with(family, "uses", RailixValue.array(uses)));
        }
        var result = with(with(after, "nodes", RailixValue.array(new ArrayList<>(nodes.values()))), "links", RailixValue.array(new ArrayList<>(links.values())));
        return new Edit(result, with(with(metadata, "steps", RailixValue.object(styles)), "reuse", RailixValue.array(families)), "");
    }

    private static List<RailixValue> outgoing(final Map<String, RailixValue> links, final String id) {
        return links.values().stream().filter(value -> owner(text(object(value), "from")).equals(id)).toList();
    }
    private static Map<String, List<RailixValue>> outgoingIndex(final Map<String, RailixValue> links) {
        final Map<String, List<RailixValue>> result = new LinkedHashMap<>();
        links.values().forEach(value -> result.computeIfAbsent(owner(text(object(value), "from")), ignored -> new ArrayList<>()).add(value));
        return result;
    }
    private static Set<String> memberIds(final RailixValue.ObjectValue use) {
        final Set<String> result = new LinkedHashSet<>();
        object(use, "members").values().values().forEach(value -> result.add(((RailixValue.StringValue) value).value()));
        return result;
    }

    private static Map<String, Set<String>> counterparts(final RailixValue.ObjectValue creator) {
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        for (final RailixValue value : array(creator, "reuse")) {
            final List<RailixValue> uses = array(object(value), "uses");
            final var first = object(object(uses.getFirst()), "members");
            for (final var member : first.values().entrySet()) for (int index = 1; index < uses.size(); index++) {
                final String a = ((RailixValue.StringValue) member.getValue()).value();
                final String b = ((RailixValue.StringValue) object(object(uses.get(index)), "members").values().get(member.getKey())).value();
                result.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
                result.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
            }
        }
        return result;
    }

    static RailixValue.ObjectValue prune(final RailixValue.ObjectValue creator, final RailixValue.ObjectValue project) {
        final Set<String> nodes = keyed(array(project, "nodes"), "id").keySet();
        final Set<String> groups = keyed(array(creator,"groups"),"id").keySet();
        final Map<String, RailixValue> styles = new LinkedHashMap<>(object(creator, "steps").values());
        styles.keySet().retainAll(nodes);
        final List<RailixValue> relationships = new ArrayList<>();
        for (final RailixValue value : array(creator, "reuse")) {
            final List<RailixValue> uses = new ArrayList<>();
            for (final RailixValue item : array(object(value), "uses")) {
                final var use = object(item);
                if (object(use, "members").values().values().stream().allMatch(member -> nodes.contains(((RailixValue.StringValue) member).value())))
                    uses.add(use.values().containsKey("group") && !groups.contains(text(use,"group")) ? without(use,"group") : use);
            }
            if (uses.size() > 1) relationships.add(with(object(value), "uses", RailixValue.array(uses)));
        }
        var result = with(creator, "steps", RailixValue.object(styles));
        return relationships.isEmpty() ? without(result, "reuse") : with(result, "reuse", RailixValue.array(relationships));
    }

    static RailixValue.ObjectValue diff(final RailixValue.ObjectValue before, final RailixValue.ObjectValue after) {
        final Map<String, RailixValue> changes = new LinkedHashMap<>();
        final Set<String> fields = new LinkedHashSet<>(before.values().keySet());
        fields.addAll(after.values().keySet());
        for (final String field : fields) {
            final RailixValue left = before.values().get(field), right = after.values().get(field);
            if (java.util.Objects.equals(left, right)) continue;
            if (Set.of("nodes", "links", "groups", "steps").contains(field)) {
                final Map<String, RailixValue> a = index(before, field);
                final Map<String, RailixValue> b = index(after, field);
                final Map<String, RailixValue> entries = new LinkedHashMap<>();
                final Set<String> keys = new LinkedHashSet<>(a.keySet());
                keys.addAll(b.keySet());
                for (final String key : keys) if (!java.util.Objects.equals(a.get(key), b.get(key))) {
                    final RailixValue value = b.getOrDefault(key, RailixValue.nullValue());
                    entries.put(key, value);
                }
                changes.put(field, RailixValue.object(entries));
            } else changes.put(field, right == null ? RailixValue.nullValue() : right);
        }
        return RailixValue.object(changes);
    }

    private static Set<String> reachable(final String start, final Map<String, RailixValue> links, final boolean reverse) {
        final Map<String, List<String>> edges = new LinkedHashMap<>();
        links.values().forEach(value -> {
            final var edge = object(value);
            final String from = owner(text(edge, "from")), to = text(edge, "to");
            if (!to.equals("end")) edges.computeIfAbsent(reverse ? to : from, ignored -> new ArrayList<>()).add(reverse ? from : to);
        });
        final Set<String> result = new LinkedHashSet<>();
        final ArrayDeque<String> pending = new ArrayDeque<>(List.of(start));
        while (!pending.isEmpty()) {
            final String next = pending.removeFirst();
            if (result.add(next)) pending.addAll(edges.getOrDefault(next, List.of()));
        }
        return result;
    }

    private static RailixValue.ObjectValue use(final String id, final String group, final Map<String, RailixValue> members) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        result.put("id", RailixValue.string(id));
        result.put("members", RailixValue.object(members));
        if (!group.isEmpty()) result.put("group", RailixValue.string(group));
        return RailixValue.object(result);
    }

    private static RailixValue.ObjectValue link(final String from, final String to) {
        return RailixValue.object(Map.of("from", RailixValue.string(from), "to", RailixValue.string(to)));
    }

    private static String identity(final String seed, final String role) {
        return "n" + UUID.nameUUIDFromBytes((seed + ":" + role).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static String owner(final String port) {
        final int separator = port.lastIndexOf('.');
        if (separator < 1 || separator == port.length() - 1) throw new IllegalArgumentException("Choose a Step output.");
        return port.substring(0, separator);
    }
    private static void ordinary(final Map<String, RailixValue> nodes, final String id, final StepCatalog catalog) {
        if (!nodes.containsKey(id) || catalog.find(text(object(nodes.get(id)), "use")).orElseThrow().kind() != StepDefinition.Kind.STEP)
            throw new IllegalArgumentException("Select an ordinary Step.");
    }
    static RailixValue.ObjectValue object(final RailixValue value) {
        return value instanceof RailixValue.ObjectValue object ? object : RailixValue.object(Map.of());
    }
    static RailixValue.ObjectValue object(final RailixValue.ObjectValue value, final String key) { return object(value.values().get(key)); }
    static List<RailixValue> array(final RailixValue.ObjectValue value, final String key) {
        return value.values().get(key) instanceof RailixValue.ArrayValue array ? array.values() : List.of();
    }
    static String text(final RailixValue.ObjectValue value, final String key) {
        return value.values().get(key) instanceof RailixValue.StringValue text ? text.value() : "";
    }
    private static String required(final RailixValue.ObjectValue value, final String key) {
        final String result = text(value, key);
        if (result.isEmpty()) throw new IllegalArgumentException("Missing " + key + ".");
        return result;
    }
    private static Map<String, RailixValue> keyed(final List<RailixValue> entries, final String key) {
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        entries.forEach(value -> {
            final String id = text(object(value), key);
            result.put(key.equals("from") && id.equals("app.start") ? id + ">" + text(object(value), "to") : id, value);
        });
        return result;
    }
    private static Map<String, RailixValue> index(final RailixValue.ObjectValue document, final String field) {
        if (field.equals("steps")) return object(document, field).values();
        if (!field.equals("links")) return keyed(array(document, field), "id");
        final Map<String, List<RailixValue>> byPort = new LinkedHashMap<>();
        array(document, field).forEach(value -> byPort.computeIfAbsent(text(object(value), "from"), ignored -> new ArrayList<>()).add(value));
        final Map<String, RailixValue> result = new LinkedHashMap<>();
        byPort.forEach((port, values) -> result.put(port, RailixValue.array(values)));
        return result;
    }
    static RailixValue.ObjectValue with(final RailixValue.ObjectValue value, final String key, final RailixValue field) {
        final Map<String, RailixValue> result = new LinkedHashMap<>(value.values());
        result.put(key, field);
        return RailixValue.object(result);
    }
    private static RailixValue.ObjectValue without(final RailixValue.ObjectValue value, final String key) {
        final Map<String, RailixValue> result = new LinkedHashMap<>(value.values());
        result.remove(key);
        return RailixValue.object(result);
    }
}
