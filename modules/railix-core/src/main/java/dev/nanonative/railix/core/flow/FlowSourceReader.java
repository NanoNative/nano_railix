package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class FlowSourceReader {
    private static final Set<String> FLOW_FIELDS = Set.of(
            "id", "triggers", "entry", "inputs", "outputs", "steps", "connections"
    );
    private static final Set<String> TRIGGER_FIELDS = Set.of("id", "type", "config");
    private static final Set<String> STEP_FIELDS = Set.of("id", "use", "config", "on");
    private static final Set<String> CONNECTION_FIELDS = Set.of(
            "from", "sourcePath", "default", "convert", "to", "targetPath"
    );
    private static final int MAX_PATH_DEPTH = 64;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private FlowSourceReader() {
    }

    static Result read(final RailixValue value) {
        return new FlowSourceReader().readDocument(value);
    }

    private Result readDocument(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue root)) {
            diagnostics.add(Diagnostic.atPath("FLOW_DOCUMENT_OBJECT_REQUIRED", "Flow document must be an object.", "$"));
            return new Result(emptyFlow(), diagnostics);
        }
        unknownFields(root, FLOW_FIELDS, "", "FLOW_FIELD_UNKNOWN", "flow");
        final String id = string(root, "id", "id");
        final List<DraftFlow.DraftTrigger> triggers = triggers(root);
        final String entry = string(root, "entry", "entry");
        final Map<String, ValueShape> inputs = shapes(root, "inputs");
        final Map<String, ValueShape> outputs = shapes(root, "outputs");
        final List<DraftFlow.DraftStep> steps = steps(root);
        final List<DraftFlow.DraftConnection> connections = connections(root);
        return new Result(new DraftFlow(id, triggers, entry, inputs, outputs, steps, connections), diagnostics);
    }

    private List<DraftFlow.DraftTrigger> triggers(final RailixValue.ObjectValue root) {
        final RailixValue value = root.values().getOrDefault("triggers", RailixValue.nullValue());
        if (!(value instanceof RailixValue.ArrayValue array)) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_TRIGGERS_ARRAY_REQUIRED",
                    "triggers must be an array.",
                    "triggers"
            ));
            return List.of();
        }
        final Set<String> ids = new java.util.HashSet<>();
        final List<DraftFlow.DraftTrigger> result = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String path = "triggers[" + index + "]";
            final RailixValue triggerValue = array.values().get(index);
            if (!(triggerValue instanceof RailixValue.ObjectValue trigger)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_OBJECT_REQUIRED",
                        "Trigger must be an object.",
                        path
                ));
                continue;
            }
            final int firstDiagnostic = diagnostics.size();
            unknownFields(
                    trigger,
                    TRIGGER_FIELDS,
                    path + ".",
                    "FLOW_TRIGGER_FIELD_UNKNOWN",
                    "trigger"
            );
            final String id = triggerText(
                    trigger,
                    "id",
                    path + ".id",
                    "FLOW_TRIGGER_ID_REQUIRED"
            );
            final String type = triggerText(
                    trigger,
                    "type",
                    path + ".type",
                    "FLOW_TRIGGER_TYPE_REQUIRED"
            );
            final RailixValue configValue = trigger.values().getOrDefault(
                    "config",
                    RailixValue.nullValue()
            );
            final RailixValue.ObjectValue config;
            if (configValue instanceof RailixValue.ObjectValue object) {
                config = object;
            } else {
                config = RailixValue.object(Map.of());
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_OBJECT_REQUIRED",
                        path + ".config must be an object.",
                        path + ".config"
                ));
            }
            if (!id.isEmpty() && !ids.add(id)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_TRIGGER_ID_DUPLICATE",
                        "Trigger id is already declared: " + id + ".",
                        path + ".id"
                ));
            }
            if (diagnostics.size() == firstDiagnostic) {
                result.add(new DraftFlow.DraftTrigger(id, type, config, index));
            }
        }
        return result;
    }

    private String triggerText(
            final RailixValue.ObjectValue trigger,
            final String field,
            final String path,
            final String code
    ) {
        final RailixValue value = trigger.values().getOrDefault(field, RailixValue.nullValue());
        if (value instanceof RailixValue.StringValue text && !text.value().isBlank()) {
            return text.value();
        }
        diagnostics.add(Diagnostic.atPath(
                code,
                path + " must be a non-blank string.",
                path
        ));
        return "";
    }

    private Map<String, ValueShape> shapes(final RailixValue.ObjectValue root, final String field) {
        final RailixValue value = root.values().getOrDefault(field, RailixValue.nullValue());
        if (!(value instanceof RailixValue.ObjectValue object)) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_FIELD_OBJECT_REQUIRED",
                    field + " must be an object of names to shapes.",
                    field
            ));
            return Map.of();
        }
        final Map<String, ValueShape> result = new LinkedHashMap<>();
        for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
            if (!(entry.getValue() instanceof RailixValue.StringValue shapeName)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_SHAPE_NAME_REQUIRED",
                        "Shape must be a string.",
                        field + "." + entry.getKey()
                ));
                continue;
            }
            final Optional<ValueShape> shape = shape(shapeName.value());
            if (shape.isEmpty()) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_SHAPE_UNKNOWN",
                        "Unknown shape: " + shapeName.value(),
                        field + "." + entry.getKey()
                ));
                continue;
            }
            result.put(entry.getKey(), shape.get());
        }
        return result;
    }

    private List<DraftFlow.DraftStep> steps(final RailixValue.ObjectValue root) {
        final RailixValue value = root.values().getOrDefault("steps", RailixValue.nullValue());
        if (!(value instanceof RailixValue.ArrayValue array)) {
            diagnostics.add(Diagnostic.atPath("FLOW_STEPS_ARRAY_REQUIRED", "steps must be an array.", "steps"));
            return List.of();
        }
        final List<DraftFlow.DraftStep> result = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String path = "steps[" + index + "]";
            final RailixValue stepValue = array.values().get(index);
            if (!(stepValue instanceof RailixValue.ObjectValue step)) {
                diagnostics.add(Diagnostic.atPath("FLOW_STEP_OBJECT_REQUIRED", "Step must be an object.", path));
                continue;
            }
            unknownFields(step, STEP_FIELDS, path + ".", "FLOW_STEP_FIELD_UNKNOWN", "Step");
            result.add(new DraftFlow.DraftStep(
                    string(step, "id", path + ".id"),
                    string(step, "use", path + ".use"),
                    valueMap(step, "config", path + ".config"),
                    stringMap(step, "on", path + ".on")
            ));
        }
        return result;
    }

    private List<DraftFlow.DraftConnection> connections(final RailixValue.ObjectValue root) {
        final RailixValue value = root.values().getOrDefault("connections", RailixValue.nullValue());
        if (!(value instanceof RailixValue.ArrayValue array)) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTIONS_ARRAY_REQUIRED",
                    "connections must be an array.",
                    "connections"
            ));
            return List.of();
        }
        final List<DraftFlow.DraftConnection> result = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String path = "connections[" + index + "]";
            final RailixValue connectionValue = array.values().get(index);
            if (!(connectionValue instanceof RailixValue.ObjectValue connection)) {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_CONNECTION_OBJECT_REQUIRED",
                        "Connection must be an object.",
                        path
                ));
                continue;
            }
            unknownFields(
                    connection,
                    CONNECTION_FIELDS,
                    path + ".",
                    "FLOW_CONNECTION_FIELD_UNKNOWN",
                    "connection"
            );
            result.add(new DraftFlow.DraftConnection(
                    string(connection, "from", path + ".from"),
                    dataPath(connection, "sourcePath", path + ".sourcePath"),
                    optionalValue(connection, "default"),
                    optionalString(connection, "convert", path + ".convert"),
                    string(connection, "to", path + ".to"),
                    dataPath(connection, "targetPath", path + ".targetPath"),
                    index
            ));
        }
        return result;
    }

    private DraftFlow.Path dataPath(
            final RailixValue.ObjectValue owner,
            final String field,
            final String path
    ) {
        if (!owner.values().containsKey(field)) {
            return DraftFlow.Path.empty();
        }
        final RailixValue value = owner.values().get(field);
        if (!(value instanceof RailixValue.ArrayValue array)) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTION_PATH_ARRAY_REQUIRED",
                    field + " must be an array of field names and array indexes.",
                    path
            ));
            return DraftFlow.Path.empty();
        }
        if (array.values().isEmpty()) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTION_PATH_EMPTY",
                    field + " must not be empty when present.",
                    path
            ));
            return DraftFlow.Path.empty();
        }
        if (array.values().size() > MAX_PATH_DEPTH) {
            diagnostics.add(Diagnostic.atPath(
                    "FLOW_CONNECTION_PATH_DEPTH_EXCEEDED",
                    field + " exceeds the maximum depth of " + MAX_PATH_DEPTH + ".",
                    path
            ));
            return DraftFlow.Path.empty();
        }
        final List<DraftFlow.Path.Element> elements = new ArrayList<>(array.values().size());
        for (int index = 0; index < array.values().size(); index++) {
            final RailixValue element = array.values().get(index);
            final String elementPath = path + "[" + index + "]";
            if (element instanceof RailixValue.StringValue fieldName) {
                elements.add(new DraftFlow.Path.Field(fieldName.value()));
            } else if (element instanceof RailixValue.NumberValue number) {
                final var indexValue = number.value().stripTrailingZeros();
                if (indexValue.signum() < 0 || indexValue.scale() > 0) {
                    invalidPathToken(elementPath);
                } else if (indexValue.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    diagnostics.add(Diagnostic.atPath(
                            "FLOW_CONNECTION_PATH_INDEX_LIMIT_EXCEEDED",
                            "Array indexes must not exceed " + Integer.MAX_VALUE + ".",
                            elementPath
                    ));
                } else {
                    elements.add(new DraftFlow.Path.Index(indexValue.intValueExact()));
                }
            } else {
                invalidPathToken(elementPath);
            }
        }
        return new DraftFlow.Path(elements);
    }

    private void invalidPathToken(final String path) {
        diagnostics.add(Diagnostic.atPath(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                path
        ));
    }

    private static List<RailixValue> optionalValue(
            final RailixValue.ObjectValue owner,
            final String field
    ) {
        return owner.values().containsKey(field) ? List.of(owner.values().get(field)) : List.of();
    }

    private String optionalString(
            final RailixValue.ObjectValue owner,
            final String field,
            final String path
    ) {
        if (!owner.values().containsKey(field)) {
            return "";
        }
        final RailixValue value = owner.values().get(field);
        if (value instanceof RailixValue.StringValue text && !text.value().isBlank()) {
            return text.value();
        }
        diagnostics.add(Diagnostic.atPath(
                "FLOW_CONNECTION_CONVERSION_INVALID",
                field + " must be a non-blank string.",
                path
        ));
        return "";
    }

    private Map<String, RailixValue> valueMap(
            final RailixValue.ObjectValue owner,
            final String field,
            final String path
    ) {
        final RailixValue value = owner.values().getOrDefault(field, RailixValue.nullValue());
        if (value instanceof RailixValue.ObjectValue object) {
            return object.values();
        }
        diagnostics.add(Diagnostic.atPath(
                "FLOW_FIELD_OBJECT_REQUIRED",
                field + " must be an object.",
                path
        ));
        return Map.of();
    }

    private void unknownFields(
            final RailixValue.ObjectValue owner,
            final Set<String> allowed,
            final String path,
            final String code,
            final String ownerName
    ) {
        for (final String field : owner.values().keySet()) {
            if (!allowed.contains(field)) {
                diagnostics.add(Diagnostic.atPath(
                        code,
                        "Unknown " + ownerName + " field: " + field,
                        path + field
                ));
            }
        }
    }

    private Map<String, String> stringMap(
            final RailixValue.ObjectValue owner,
            final String field,
            final String path
    ) {
        final RailixValue value = owner.values().getOrDefault(field, RailixValue.nullValue());
        if (!(value instanceof RailixValue.ObjectValue object)) {
            diagnostics.add(Diagnostic.atPath("FLOW_FIELD_OBJECT_REQUIRED", field + " must be an object.", path));
            return Map.of();
        }
        final Map<String, String> result = new LinkedHashMap<>();
        for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
            if (entry.getValue() instanceof RailixValue.StringValue text) {
                result.put(entry.getKey(), text.value());
            } else {
                diagnostics.add(Diagnostic.atPath(
                        "FLOW_FIELD_STRING_REQUIRED",
                        "Transition target must be a string.",
                        path + "." + entry.getKey()
                ));
            }
        }
        return result;
    }

    private String string(final RailixValue.ObjectValue owner, final String field, final String path) {
        final RailixValue value = owner.values().getOrDefault(field, RailixValue.nullValue());
        if (value instanceof RailixValue.StringValue text && !text.value().isBlank()) {
            return text.value();
        }
        diagnostics.add(Diagnostic.atPath(
                "FLOW_FIELD_STRING_REQUIRED",
                field + " must be a non-blank string.",
                path
        ));
        return "";
    }

    private static Optional<ValueShape> shape(final String name) {
        try {
            return Optional.of(ValueShape.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static DraftFlow emptyFlow() {
        return new DraftFlow("", List.of(), "", Map.of(), Map.of(), List.of(), List.of());
    }

    record Result(DraftFlow flow, List<Diagnostic> diagnostics) {
        Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
