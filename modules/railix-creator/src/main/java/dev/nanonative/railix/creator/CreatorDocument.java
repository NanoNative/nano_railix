package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Parser and validator for presentation-only Creator metadata. */
final class CreatorDocument {
    static final String EMPTY = "{\"format\":1,\"groups\":[],\"steps\":{}}";
    private static final Set<String> FIELDS = Set.of("format", "groups", "steps");
    private static final Set<String> PRESENTATION_FIELDS = Set.of("name", "color", "icon", "outcomes");
    private static final Set<String> GROUP_FIELDS = Set.of(
            "id", "name", "color", "icon", "occurrences"
    );
    private static final Set<String> OCCURRENCE_FIELDS = Set.of("id", "flow", "parent", "steps");
    private static final Set<String> ICON_FIELDS = Set.of("media_type", "data");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_ICON_BYTES = 65_536;

    private CreatorDocument() {
    }

    static Result parse(
            final String source,
            final String projectSource,
            final StepCatalog catalog
    ) {
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (parsed instanceof RailixJson.Invalid invalid) {
            return Result.rejected(new Diagnostic(
                    "CREATOR_JSON_INVALID",
                    invalid.message(),
                    "",
                    invalid.line(),
                    invalid.column()
            ));
        }
        final RailixValue value = ((RailixJson.Parsed) parsed).value();
        if (!(value instanceof RailixValue.ObjectValue document)) {
            return Result.rejected("CREATOR_OBJECT_REQUIRED", "Creator metadata must be an object.", "");
        }
        final Optional<Diagnostic> unknown = unknown(
                document,
                FIELDS,
                "CREATOR_FIELD_UNKNOWN",
                "Unknown Creator metadata field: ",
                ""
        );
        if (unknown.isPresent()) {
            return Result.rejected(unknown.get());
        }
        if (!(document.values().get("format") instanceof RailixValue.NumberValue format)
                || !BigDecimal.ONE.equals(format.value())) {
            return Result.rejected(
                    "CREATOR_FORMAT_UNSUPPORTED",
                    "Creator metadata format must be the number 1.",
                    "format"
            );
        }
        final RailixValue.ObjectValue project = project(projectSource);
        final Graph graph = graph(project, catalog);
        final RailixValue stepsValue = document.values().get("steps");
        if (!(stepsValue instanceof RailixValue.ObjectValue steps)) {
            return Result.rejected(
                    "CREATOR_STEPS_OBJECT_REQUIRED",
                    "Creator steps must be an object.",
                    "steps"
            );
        }
        final Result stepResult = presentations(steps, graph);
        if (!stepResult.diagnostics().isEmpty()) {
            return stepResult;
        }
        final RailixValue groupsValue = document.values().get("groups");
        if (!(groupsValue instanceof RailixValue.ArrayValue groups)) {
            return Result.rejected(
                    "CREATOR_GROUPS_ARRAY_REQUIRED",
                    "Creator groups must be an array.",
                    "groups"
            );
        }
        final Result groupResult = groups(groups, graph);
        if (!groupResult.diagnostics().isEmpty()) {
            return groupResult;
        }
        final Map<String, RailixValue> canonicalFields = new LinkedHashMap<>();
        canonicalFields.put("format", RailixValue.number(1));
        canonicalFields.put("groups", groups);
        canonicalFields.put("steps", steps);
        final RailixValue.ObjectValue canonical = RailixValue.object(canonicalFields);
        return new Result(RailixJson.write(canonical), canonical, List.of());
    }

    private static Result presentations(
            final RailixValue.ObjectValue steps,
            final Graph graph
    ) {
        for (final Map.Entry<String, RailixValue> entry : steps.values().entrySet()) {
            final String path = "steps." + entry.getKey();
            if (!graph.kinds().containsKey(entry.getKey())) {
                return Result.rejected(
                        "CREATOR_STEP_UNKNOWN",
                        "Creator presentation references an unknown Step: " + entry.getKey() + ".",
                        path
                );
            }
            if (!(entry.getValue() instanceof RailixValue.ObjectValue presentation)) {
                return Result.rejected(
                        "CREATOR_PRESENTATION_OBJECT_REQUIRED",
                        "Step presentation must be an object.",
                        path
                );
            }
            final Optional<Diagnostic> unknown = unknown(
                    presentation,
                    PRESENTATION_FIELDS,
                    "CREATOR_PRESENTATION_FIELD_UNKNOWN",
                    "Unknown presentation field: ",
                    path
            );
            if (unknown.isPresent()) {
                return Result.rejected(unknown.get());
            }
            final Optional<Diagnostic> diagnostic = presentation(
                    presentation,
                    path,
                    graph.outgoing().getOrDefault(entry.getKey(), Map.of()).keySet()
            );
            if (diagnostic.isPresent()) {
                return Result.rejected(diagnostic.get());
            }
        }
        return Result.accepted();
    }

    private static Result hierarchy(final Map<String, Occurrence> declared) {
        for (final Occurrence occurrence : declared.values()) {
            if (!occurrence.parent().isEmpty()
                    && (!declared.containsKey(occurrence.parent())
                    || occurrence.id().equals(occurrence.parent()))) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_PARENT_UNKNOWN",
                        "Occurrence parent must reference a different declared occurrence.",
                        occurrence.path() + ".parent"
                );
            }
        }
        for (final Occurrence occurrence : declared.values()) {
            final Set<String> seen = new LinkedHashSet<>();
            Occurrence current = occurrence;
            while (!current.parent().isEmpty()) {
                if (!seen.add(current.id())) {
                    return Result.rejected(
                            "CREATOR_OCCURRENCE_PARENT_CYCLE",
                            "Occurrence parents must not contain a cycle.",
                            occurrence.path() + ".parent"
                    );
                }
                current = declared.get(current.parent());
            }
        }
        for (final Occurrence occurrence : declared.values()) {
            if (occurrence.parent().isEmpty()) {
                continue;
            }
            final Occurrence parent = declared.get(occurrence.parent());
            if (!parent.flow().equals(occurrence.flow()) || !parent.steps().containsAll(occurrence.steps())) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_OUTSIDE_PARENT",
                        "Nested occurrence Steps must belong to its parent occurrence and Trigger flow.",
                        occurrence.path() + ".steps"
                );
            }
        }
        final Map<String, Set<String>> scopes = new LinkedHashMap<>();
        for (final Occurrence occurrence : declared.values()) {
            final String scope = occurrence.flow() + "\u0000" + occurrence.parent();
            final Set<String> used = scopes.computeIfAbsent(scope, ignored -> new LinkedHashSet<>());
            if (occurrence.steps().stream().anyMatch(used::contains)) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_STEP_OVERLAP",
                        "Sibling group occurrences must not contain the same Step.",
                        occurrence.path() + ".steps"
                );
            }
            used.addAll(occurrence.steps());
        }
        return Result.accepted();
    }

    private static Result groups(
            final RailixValue.ArrayValue groups,
            final Graph graph
    ) {
        final Set<String> groupIds = new LinkedHashSet<>();
        final Set<String> occurrenceIds = new LinkedHashSet<>();
        final Map<String, Occurrence> declared = new LinkedHashMap<>();
        for (int groupIndex = 0; groupIndex < groups.values().size(); groupIndex++) {
            final String path = "groups[" + groupIndex + "]";
            final RailixValue value = groups.values().get(groupIndex);
            if (!(value instanceof RailixValue.ObjectValue group)) {
                return Result.rejected("CREATOR_GROUP_OBJECT_REQUIRED", "Group must be an object.", path);
            }
            final Optional<Diagnostic> unknown = unknown(
                    group, GROUP_FIELDS, "CREATOR_GROUP_FIELD_UNKNOWN", "Unknown group field: ", path
            );
            if (unknown.isPresent()) {
                return Result.rejected(unknown.get());
            }
            final Read id = text(group, "id", path + ".id");
            if (id.diagnostic().isPresent()) {
                return Result.rejected(id.diagnostic().get());
            }
            if (!groupIds.add(id.value())) {
                return Result.rejected(
                        "CREATOR_GROUP_ID_DUPLICATE",
                        "Group id is already declared: " + id.value() + ".",
                        path + ".id"
                );
            }
            final Optional<Diagnostic> presentation = presentation(group, path);
            if (presentation.isPresent()) {
                return Result.rejected(presentation.get());
            }
            final RailixValue occurrencesValue = group.values().get("occurrences");
            if (!(occurrencesValue instanceof RailixValue.ArrayValue occurrences)
                    || occurrences.values().isEmpty()) {
                return Result.rejected(
                        "CREATOR_GROUP_OCCURRENCES_REQUIRED",
                        "Group occurrences must be a non-empty array.",
                        path + ".occurrences"
                );
            }
            Set<String> slots = null;
            OccurrenceShape shape = null;
            for (int occurrenceIndex = 0; occurrenceIndex < occurrences.values().size(); occurrenceIndex++) {
                final String occurrencePath = path + ".occurrences[" + occurrenceIndex + "]";
                final RailixValue occurrenceValue = occurrences.values().get(occurrenceIndex);
                if (!(occurrenceValue instanceof RailixValue.ObjectValue occurrence)) {
                    return Result.rejected(
                            "CREATOR_OCCURRENCE_OBJECT_REQUIRED",
                            "Group occurrence must be an object.",
                            occurrencePath
                    );
                }
                final Result occurrenceResult = occurrence(
                        occurrence,
                        occurrencePath,
                        graph,
                        occurrenceIds,
                        declared
                );
                if (!occurrenceResult.diagnostics().isEmpty()) {
                    return occurrenceResult;
                }
                final Set<String> occurrenceSlots = ((RailixValue.ObjectValue) occurrence.values().get("steps"))
                        .values().keySet();
                if (slots == null) {
                    slots = Set.copyOf(occurrenceSlots);
                    shape = declared.get(textValue(occurrence, "id")).shape();
                } else if (!slots.equals(occurrenceSlots)) {
                    return Result.rejected(
                            "CREATOR_OCCURRENCE_SLOTS_MISMATCH",
                            "Every occurrence of a shared group must declare the same slots.",
                            occurrencePath + ".steps"
                    );
                } else if (!shape.equals(declared.get(textValue(occurrence, "id")).shape())) {
                    return Result.rejected(
                            "CREATOR_OCCURRENCE_TOPOLOGY_MISMATCH",
                            "Every occurrence of a shared group must have the same Step and route topology.",
                            occurrencePath + ".steps"
                    );
                }
            }
        }
        return hierarchy(declared);
    }

    private static Result occurrence(
            final RailixValue.ObjectValue occurrence,
            final String path,
            final Graph graph,
            final Set<String> occurrenceIds,
            final Map<String, Occurrence> declared
    ) {
        final Optional<Diagnostic> unknown = unknown(
                occurrence,
                OCCURRENCE_FIELDS,
                "CREATOR_OCCURRENCE_FIELD_UNKNOWN",
                "Unknown occurrence field: ",
                path
        );
        if (unknown.isPresent()) {
            return Result.rejected(unknown.get());
        }
        final Read id = text(occurrence, "id", path + ".id");
        if (id.diagnostic().isPresent()) {
            return Result.rejected(id.diagnostic().get());
        }
        if (!occurrenceIds.add(id.value())) {
            return Result.rejected(
                    "CREATOR_OCCURRENCE_ID_DUPLICATE",
                    "Occurrence id is already declared: " + id.value() + ".",
                    path + ".id"
            );
        }
        final Read flow = text(occurrence, "flow", path + ".flow");
        if (flow.diagnostic().isPresent()) {
            return Result.rejected(flow.diagnostic().get());
        }
        if (graph.kinds().get(flow.value()) != StepDefinition.Kind.TRIGGER) {
            return Result.rejected(
                    "CREATOR_OCCURRENCE_FLOW_UNKNOWN",
                    "Occurrence flow must reference a Trigger: " + flow.value() + ".",
                    path + ".flow"
            );
        }
        final RailixValue parentValue = occurrence.values().get("parent");
        final String parent;
        if (parentValue instanceof RailixValue.NullValue) {
            parent = "";
        } else if (parentValue instanceof RailixValue.StringValue text && validId(text.value())) {
            parent = text.value();
        } else {
            return Result.rejected(
                    "CREATOR_OCCURRENCE_PARENT_INVALID",
                    "Occurrence parent must be null or a non-blank id.",
                    path + ".parent"
            );
        }
        final RailixValue stepsValue = occurrence.values().get("steps");
        if (!(stepsValue instanceof RailixValue.ObjectValue steps) || steps.values().isEmpty()) {
            return Result.rejected(
                    "CREATOR_OCCURRENCE_STEPS_REQUIRED",
                    "Occurrence steps must be a non-empty slot object.",
                    path + ".steps"
            );
        }
        final Set<String> referenced = new LinkedHashSet<>();
        for (final Map.Entry<String, RailixValue> step : steps.values().entrySet()) {
            final String stepPath = path + ".steps." + step.getKey();
            if (!validId(step.getKey()) || !(step.getValue() instanceof RailixValue.StringValue target)) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_STEP_INVALID",
                        "Occurrence slots and Step ids must be non-blank strings up to 128 characters.",
                        stepPath
                );
            }
            final StepDefinition.Kind kind = graph.kinds().get(target.value());
            if (kind == null || kind == StepDefinition.Kind.APP || kind == StepDefinition.Kind.TRIGGER) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_STEP_UNKNOWN",
                        "Occurrence slot references an unknown ordinary Step: " + target.value() + ".",
                        stepPath
                );
            }
            if (!referenced.add(target.value())) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_STEP_DUPLICATE",
                        "A Step can occupy only one slot in an occurrence: " + target.value() + ".",
                        stepPath
                );
            }
            final Set<String> owners = graph.owners().getOrDefault(target.value(), Set.of());
            if (owners.size() != 1 || !owners.contains(flow.value())) {
                return Result.rejected(
                        "CREATOR_OCCURRENCE_STEP_OUTSIDE_FLOW",
                        "Occurrence Step must belong only to its declared Trigger flow: "
                                + target.value() + ".",
                        stepPath
                );
            }
        }
        final Optional<Diagnostic> region = region(referenced, graph, path + ".steps");
        if (region.isPresent()) {
            return Result.rejected(region.get());
        }
        declared.put(id.value(), new Occurrence(
                id.value(),
                flow.value(),
                parent,
                Set.copyOf(referenced),
                shape(steps, graph),
                path
        ));
        return Result.accepted();
    }

    private static Optional<Diagnostic> region(
            final Set<String> referenced,
            final Graph graph,
            final String path
    ) {
        final Set<String> connected = new LinkedHashSet<>();
        final List<String> pending = new ArrayList<>(List.of(referenced.iterator().next()));
        for (int index = 0; index < pending.size(); index++) {
            final String current = pending.get(index);
            if (!connected.add(current)) {
                continue;
            }
            graph.neighbors().getOrDefault(current, Set.of()).stream()
                    .filter(referenced::contains)
                    .filter(candidate -> !connected.contains(candidate))
                    .forEach(pending::add);
        }
        if (connected.size() != referenced.size()) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_OCCURRENCE_RANGE_INVALID",
                    "Occurrence Steps must form one connected region.",
                    path
            ));
        }
        return Optional.empty();
    }

    private static OccurrenceShape shape(
            final RailixValue.ObjectValue steps,
            final Graph graph
    ) {
        final Map<String, String> concreteSlots = new LinkedHashMap<>();
        steps.values().forEach((slot, value) ->
                concreteSlots.put(((RailixValue.StringValue) value).value(), slot)
        );
        final String entry = concreteSlots.entrySet().stream()
                .filter(candidate -> graph.incoming().getOrDefault(candidate.getKey(), List.of()).stream()
                        .anyMatch(source -> !concreteSlots.containsKey(source)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        final Map<String, NodeShape> nodes = new LinkedHashMap<>();
        steps.values().forEach((slot, value) -> {
            final String concrete = ((RailixValue.StringValue) value).value();
            final Map<String, String> routes = new LinkedHashMap<>();
            graph.outgoing().getOrDefault(concrete, Map.of()).forEach((outcome, target) ->
                    routes.put(
                            graph.outcomeSlots().getOrDefault(concrete, Map.of()).getOrDefault(outcome, outcome),
                            concreteSlots.getOrDefault(target, "")
                    )
            );
            nodes.put(slot, new NodeShape(graph.uses().get(concrete), Map.copyOf(routes)));
        });
        return new OccurrenceShape(entry, Map.copyOf(nodes));
    }

    private static Optional<Diagnostic> presentation(
            final RailixValue.ObjectValue value,
            final String path
    ) {
        return presentation(value, path, Set.of());
    }

    private static Optional<Diagnostic> presentation(
            final RailixValue.ObjectValue value,
            final String path,
            final Set<String> outcomes
    ) {
        final RailixValue name = value.values().get("name");
        if (name != null && (!(name instanceof RailixValue.StringValue text)
                || text.value().isBlank() || text.value().length() > MAX_NAME_LENGTH)) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_NAME_INVALID",
                    "Presentation name must be a non-blank string up to 128 characters.",
                    path + ".name"
            ));
        }
        final RailixValue color = value.values().get("color");
        if (color != null && (!(color instanceof RailixValue.StringValue text)
                || !COLOR.matcher(text.value()).matches())) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_COLOR_INVALID",
                    "Presentation color must use #RRGGBB.",
                    path + ".color"
            ));
        }
        final RailixValue icon = value.values().get("icon");
        if (icon != null) {
            final Optional<Diagnostic> diagnostic = icon(icon, path + ".icon");
            if (diagnostic.isPresent()) {
                return diagnostic;
            }
        }
        final RailixValue labels = value.values().get("outcomes");
        if (labels == null) {
            return Optional.empty();
        }
        if (!(labels instanceof RailixValue.ObjectValue object)) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_OUTCOMES_INVALID",
                    "Presentation outcomes must be an object of route labels.",
                    path + ".outcomes"
            ));
        }
        for (final Map.Entry<String, RailixValue> label : object.values().entrySet()) {
            final String labelPath = path + ".outcomes." + label.getKey();
            if (!outcomes.contains(label.getKey())) {
                return Optional.of(Diagnostic.atPath(
                        "CREATOR_PRESENTATION_OUTCOME_UNKNOWN",
                        "Presentation outcome must reference a connected Step route: " + label.getKey() + ".",
                        labelPath
                ));
            }
            if (!(label.getValue() instanceof RailixValue.StringValue text)
                    || text.value().isBlank() || text.value().length() > MAX_NAME_LENGTH) {
                return Optional.of(Diagnostic.atPath(
                        "CREATOR_PRESENTATION_OUTCOME_LABEL_INVALID",
                        "Presentation outcome label must be a non-blank string up to 128 characters.",
                        labelPath
                ));
            }
        }
        return Optional.empty();
    }

    private static Optional<Diagnostic> icon(final RailixValue value, final String path) {
        if (!(value instanceof RailixValue.ObjectValue icon)) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_ICON_INVALID",
                    "Presentation icon must contain only media_type and base64 data.",
                    path
            ));
        }
        final Optional<Diagnostic> unknown = unknown(
                icon,
                ICON_FIELDS,
                "CREATOR_PRESENTATION_ICON_INVALID",
                "Unknown icon field: ",
                path
        );
        if (unknown.isPresent()
                || icon.values().size() != 2
                || !(icon.values().get("media_type") instanceof RailixValue.StringValue mediaType)
                || !(icon.values().get("data") instanceof RailixValue.StringValue data)
                || !("image/svg+xml".equals(mediaType.value()) || "image/png".equals(mediaType.value()))) {
            return unknown.isPresent() ? unknown : Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_ICON_INVALID",
                    "Presentation icon must contain valid SVG or PNG base64 data.",
                    path
            ));
        }
        try {
            final byte[] decoded = Base64.getDecoder().decode(data.value().getBytes(StandardCharsets.US_ASCII));
            if (decoded.length == 0 || decoded.length > MAX_ICON_BYTES
                    || !IconLibrary.valid(mediaType.value(), decoded)) {
                throw new IllegalArgumentException("Invalid icon data.");
            }
        } catch (final IllegalArgumentException exception) {
            return Optional.of(Diagnostic.atPath(
                    "CREATOR_PRESENTATION_ICON_INVALID",
                    "Presentation icon must contain valid SVG or PNG base64 data up to 65536 bytes.",
                    path + ".data"
            ));
        }
        return Optional.empty();
    }

    private static Read text(
            final RailixValue.ObjectValue object,
            final String name,
            final String path
    ) {
        final RailixValue value = object.values().get(name);
        if (!(value instanceof RailixValue.StringValue text) || !validId(text.value())) {
            return new Read("", Optional.of(Diagnostic.atPath(
                    "CREATOR_ID_INVALID",
                    name + " must be a non-blank string up to 128 characters.",
                    path
            )));
        }
        return new Read(text.value(), Optional.empty());
    }

    private static String textValue(final RailixValue.ObjectValue object, final String field) {
        return ((RailixValue.StringValue) object.values().get(field)).value();
    }

    private static Optional<Diagnostic> unknown(
            final RailixValue.ObjectValue value,
            final Set<String> allowed,
            final String code,
            final String message,
            final String path
    ) {
        return value.values().keySet().stream()
                .filter(field -> !allowed.contains(field))
                .findFirst()
                .map(field -> Diagnostic.atPath(
                        code,
                        message + field + ".",
                        path.isEmpty() ? field : path + "." + field
                ));
    }

    private static RailixValue.ObjectValue project(final String source) {
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(source)).value();
    }

    private static Graph graph(
            final RailixValue.ObjectValue project,
            final StepCatalog catalog
    ) {
        final Map<String, StepDefinition.Kind> kinds = new LinkedHashMap<>();
        final Map<String, String> uses = new LinkedHashMap<>();
        final Map<String, Map<String, String>> outcomeSlots = new LinkedHashMap<>();
        final RailixValue.ArrayValue nodes = (RailixValue.ArrayValue) project.values().get("nodes");
        nodes.values().stream().map(RailixValue.ObjectValue.class::cast).forEach(node -> {
            final String id = ((RailixValue.StringValue) node.values().get("id")).value();
            final String use = ((RailixValue.StringValue) node.values().get("use")).value();
            final StepDefinition definition = catalog.find(use).orElseThrow();
            kinds.put(id, definition.kind());
            uses.put(id, use);
            final Map<String, String> slots = authoredOutcomeSlots(node, definition);
            if (!slots.isEmpty()) {
                outcomeSlots.put(id, slots);
            }
        });
        final Map<String, List<String>> links = new LinkedHashMap<>();
        final Map<String, List<String>> incoming = new LinkedHashMap<>();
        final Map<String, Set<String>> neighbors = new LinkedHashMap<>();
        final Map<String, Map<String, String>> outgoing = new LinkedHashMap<>();
        final RailixValue.ArrayValue projectLinks = (RailixValue.ArrayValue) project.values().get("links");
        projectLinks.values().stream().map(RailixValue.ObjectValue.class::cast).forEach(link -> {
            final String from = ((RailixValue.StringValue) link.values().get("from")).value();
            final String to = ((RailixValue.StringValue) link.values().get("to")).value();
            final int separator = from.lastIndexOf('.');
            final String node = from.substring(0, separator);
            final String outcome = from.substring(separator + 1);
            links.computeIfAbsent(node, ignored -> new ArrayList<>()).add(to);
            outgoing.computeIfAbsent(node, ignored -> new LinkedHashMap<>()).put(outcome, to);
            if (!"end".equals(to)) {
                incoming.computeIfAbsent(to, ignored -> new ArrayList<>()).add(node);
                neighbors.computeIfAbsent(node, ignored -> new LinkedHashSet<>()).add(to);
                neighbors.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(node);
            }
        });
        final Map<String, Set<String>> owners = new LinkedHashMap<>();
        kinds.entrySet().stream()
                .filter(entry -> entry.getValue() == StepDefinition.Kind.TRIGGER)
                .forEach(trigger -> owners(trigger.getKey(), kinds, links, owners));
        return new Graph(kinds, uses, owners, neighbors, incoming, outgoing, outcomeSlots);
    }

    private static Map<String, String> authoredOutcomeSlots(
            final RailixValue.ObjectValue node,
            final StepDefinition definition
    ) {
        final Optional<StepDefinition.Field> authored = definition.inputs().stream()
                .filter(field -> field.input() instanceof StepDefinition.CandidatesInput candidates
                        && candidates.authoredOutcomes())
                .findFirst();
        final RailixValue inputs = node.values().get("inputs");
        if (authored.isEmpty() || !(inputs instanceof RailixValue.ObjectValue configured)
                || !(configured.values().get(authored.get().name()) instanceof RailixValue.ArrayValue candidates)) {
            return Map.of();
        }
        final Map<String, String> slots = new LinkedHashMap<>();
        for (int index = 0; index < candidates.values().size(); index++) {
            final RailixValue candidate = candidates.values().get(index);
            if (candidate instanceof RailixValue.ObjectValue object
                    && object.values().get("outcome") instanceof RailixValue.StringValue outcome) {
                slots.put(outcome.value(), "@case[" + index + "]");
            }
        }
        return Map.copyOf(slots);
    }

    private static void owners(
            final String trigger,
            final Map<String, StepDefinition.Kind> kinds,
            final Map<String, List<String>> links,
            final Map<String, Set<String>> owners
    ) {
        final List<String> pending = new ArrayList<>(List.of(trigger));
        final Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            final String current = pending.get(index);
            if (!seen.add(current)) {
                continue;
            }
            for (final String next : links.getOrDefault(current, List.of())) {
                if ("end".equals(next) || !kinds.containsKey(next)) {
                    continue;
                }
                if (kinds.get(next) == StepDefinition.Kind.STEP) {
                    owners.computeIfAbsent(next, ignored -> new LinkedHashSet<>()).add(trigger);
                }
                pending.add(next);
            }
        }
    }

    private static boolean validId(final String value) {
        return !value.isBlank() && value.length() <= MAX_ID_LENGTH;
    }

    record Result(String source, RailixValue.ObjectValue value, List<Diagnostic> diagnostics) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted() {
            return new Result("", RailixValue.object(Map.of()), List.of());
        }

        static Result rejected(final String code, final String message, final String path) {
            return rejected(Diagnostic.atPath(code, message, path));
        }

        static Result rejected(final Diagnostic diagnostic) {
            return new Result("", RailixValue.object(Map.of()), List.of(diagnostic));
        }
    }

    private record Read(String value, Optional<Diagnostic> diagnostic) {
    }

    private record Graph(
            Map<String, StepDefinition.Kind> kinds,
            Map<String, String> uses,
            Map<String, Set<String>> owners,
            Map<String, Set<String>> neighbors,
            Map<String, List<String>> incoming,
            Map<String, Map<String, String>> outgoing,
            Map<String, Map<String, String>> outcomeSlots
    ) {
    }

    private record Occurrence(
            String id,
            String flow,
            String parent,
            Set<String> steps,
            OccurrenceShape shape,
            String path
    ) {
    }

    private record OccurrenceShape(String entry, Map<String, NodeShape> nodes) {
    }

    private record NodeShape(String use, Map<String, String> routes) {
    }
}
