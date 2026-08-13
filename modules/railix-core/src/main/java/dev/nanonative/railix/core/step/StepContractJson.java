package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical, executable-code-free JSON representation of one Step contract. */
public final class StepContractJson {
    private static final Set<String> CONTRACT_FIELDS = Set.of(
            "display_name", "example_target", "examples", "id", "inputs", "kind",
            "maximum_instances", "outcomes", "primary_outcome", "receives", "results",
            "returns", "search_terms", "source", "version"
    );

    private StepContractJson() {
    }

    /** Returns the canonical contract object shared by manifests, hashing, and Creator. */
    public static RailixValue.ObjectValue value(final StepDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Step definition cannot be Java null.");
        }
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("id", RailixValue.string(definition.id()));
        value.put("version", RailixValue.string(definition.version()));
        value.put("display_name", RailixValue.string(definition.displayName()));
        value.put("search_terms", strings(definition.searchTerms()));
        value.put("kind", RailixValue.string(definition.kind().name().toLowerCase(Locale.ROOT)));
        value.put("primary_outcome", RailixValue.string(definition.primaryOutcome()));
        value.put("maximum_instances", RailixValue.number(definition.maximumInstances()));
        definition.source().ifPresent(source -> value.put("source", RailixValue.object(Map.of(
                "name", RailixValue.string(source.name()),
                "responses", RailixValue.object(source.responses().entrySet().stream().collect(
                        LinkedHashMap::new,
                        (responses, entry) -> responses.put(entry.getKey(), RailixValue.string(entry.getValue())),
                        LinkedHashMap::putAll
                ))
        ))));
        definition.exampleTarget().ifPresent(target -> value.put("example_target", RailixValue.string(target)));
        value.put("examples", RailixValue.array(definition.examples().stream()
                .<RailixValue>map(StepContractJson::example).toList()));
        value.put("receives", ports(definition.receives()));
        value.put("returns", ports(definition.returns()));
        value.put("inputs", fields(definition.inputs()));
        value.put("outcomes", strings(definition.outcomes()));
        value.put("results", RailixValue.array(definition.results().stream().<RailixValue>map(result -> {
            final Map<String, RailixValue> item = new LinkedHashMap<>();
            item.put("name", RailixValue.string(result.name()));
            item.put("shape", RailixValue.string(shape(result.shape())));
            result.defaultValue().ifPresent(defaultValue -> item.put("default", defaultValue));
            return RailixValue.object(item);
        }).toList()));
        return RailixValue.object(value);
    }

    /** Returns canonical JSON bytes for contract identity checks. */
    public static String write(final StepDefinition definition) {
        return RailixJson.write(value(definition));
    }

    /**
     * Generates the canonical bundle manifest from real Step definitions.
     *
     * @param definitions executable definitions owned by one root bundle
     * @return canonical {@code META-INF/railix/steps.json} content
     */
    public static String writeManifest(final List<StepDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("Step bundle manifest must contain at least one definition.");
        }
        final List<StepDefinition> sorted = definitions.stream()
                .sorted(java.util.Comparator.comparing(StepDefinition::id))
                .toList();
        if (sorted.stream().map(StepDefinition::id).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("Step bundle manifest ids must be unique.");
        }
        return RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "steps", RailixValue.array(sorted.stream().<RailixValue>map(definition -> {
                    final StepDefinition.ImplementationAddress implementation =
                            definition.implementationAddress().orElseThrow(() ->
                            new IllegalArgumentException("Bundle Step must declare an implementation: "
                                    + definition.id() + ".")
                    );
                    final RailixValue.ObjectValue contract = value(definition);
                    final String digest = sha256(RailixJson.write(contract).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return RailixValue.object(Map.of(
                            "contract", contract,
                            "contract_digest", RailixValue.string("sha256:" + digest),
                            "implementation", RailixValue.string(implementation.sourceName()),
                            "implementation_entry", RailixValue.string(implementation.classEntry())
                    ));
                }).toList())
        )));
    }

    /** Reconstructs one external executable definition without loading its implementation class. */
    static StepDefinition read(
            final RailixValue.ObjectValue contract,
            final String implementation,
            final String implementationEntry
    ) {
        object(contract, CONTRACT_FIELDS, "contract");
        final StepDefinition.Kind kind = enumeration(
                StepDefinition.Kind.class,
                text(contract, "kind", "contract"),
                "contract.kind"
        );
        final StepDefinition.Builder builder = StepDefinition.named(
                        text(contract, "id", "contract"),
                        text(contract, "version", "contract")
                )
                .kind(kind)
                .displayName(text(contract, "display_name", "contract"))
                .maximumInstances(integer(contract, "maximum_instances", "contract"));
        final List<String> terms = strings(contract, "search_terms", "contract");
        builder.searchTerms(terms.toArray(String[]::new));
        for (final StepDefinition.Port port : ports(contract, "receives", "contract")) {
            builder.receive(port.name(), port.shape(), port.refinement());
        }
        for (final StepDefinition.Port port : ports(contract, "returns", "contract")) {
            builder.returns(port.name(), port.shape(), port.refinement());
        }
        final List<StepDefinition.Field> inputs = fields(contract, "inputs", "contract");
        for (final StepDefinition.Field field : inputs) {
            builder.input(field.name(), field.input());
        }
        final List<String> outcomes = strings(contract, "outcomes", "contract");
        final String primary = text(contract, "primary_outcome", "contract");
        if (outcomes.isEmpty() || !outcomes.getFirst().equals(primary)) {
            throw invalid("contract.outcomes", "must start with primary_outcome");
        }
        builder.primaryOutcome(primary);
        final Set<String> derivedOutcomes = inputOutcomes(inputs);
        outcomes.stream().skip(1).filter(outcome -> !derivedOutcomes.contains(outcome)).forEach(builder::outcome);
        optionalObject(contract, "source", "contract").ifPresent(source -> {
            object(source, Set.of("name", "responses"), "contract.source");
            builder.source(text(source, "name", "contract.source"));
            final RailixValue.ObjectValue responses = requiredObject(source, "responses", "contract.source");
            responses.values().forEach((slot, result) -> builder.response(
                    slot,
                    string(result, "contract.source.responses." + slot)
            ));
        });
        optionalText(contract, "example_target", "contract").ifPresent(builder::exampleTarget);
        for (final RailixValue value : array(contract, "examples", "contract").values()) {
            final RailixValue.ObjectValue item = object(value, "contract.examples[]");
            object(item, Set.of("context", "name", "payload"), "contract.examples[]", Set.of("context"));
            final RailixValue.ObjectValue context = optionalObject(item, "context", "contract.examples[]")
                    .orElseGet(() -> RailixValue.object(Map.of()));
            builder.example(text(item, "name", "contract.examples[]"), required(item, "payload", "contract.examples[]"), context);
        }
        for (final RailixValue value : array(contract, "results", "contract").values()) {
            final RailixValue.ObjectValue item = object(value, "contract.results[]");
            object(item, Set.of("default", "name", "shape"), "contract.results[]", Set.of("default"));
            final String name = text(item, "name", "contract.results[]");
            final ValueShape shape = shape(text(item, "shape", "contract.results[]"), "contract.results[].shape");
            if (item.values().containsKey("default")) {
                builder.result(name, shape, item.values().get("default"));
            } else {
                builder.requiredResult(name, shape);
            }
        }
        return kind == StepDefinition.Kind.APP
                ? builder.define()
                : builder.implementedBy(implementation, implementationEntry);
    }

    private static String sha256(final byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (final java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static RailixValue example(final StepDefinition.Example example) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("name", RailixValue.string(example.name()));
        value.put("payload", example.payload());
        if (!example.context().values().isEmpty()) {
            value.put("context", example.context());
        }
        return RailixValue.object(value);
    }

    private static RailixValue.ArrayValue ports(final List<StepDefinition.Port> ports) {
        return RailixValue.array(ports.stream().<RailixValue>map(port -> {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("name", RailixValue.string(port.name()));
            value.put("shape", RailixValue.string(shape(port.shape())));
            if (port.refinement().canonicalValues()) {
                value.put("canonical", RailixValue.bool(true));
            }
            if (port.refinement().maxDepth() > 0) {
                value.put("max_depth", RailixValue.number(port.refinement().maxDepth()));
            }
            if (port.refinement().maxJsonBytes() > 0) {
                value.put("max_json_bytes", RailixValue.number(port.refinement().maxJsonBytes()));
            }
            return RailixValue.object(value);
        }).toList());
    }

    private static RailixValue.ArrayValue fields(final List<StepDefinition.Field> fields) {
        return RailixValue.array(fields.stream().<RailixValue>map(field -> {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("name", RailixValue.string(field.name()));
            value.putAll(input(field.input()));
            return RailixValue.object(value);
        }).toList());
    }

    private static Map<String, RailixValue> input(final StepDefinition.Input input) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        switch (input) {
            case StepDefinition.JsonInput json -> {
                value.put("type", RailixValue.string("json"));
                value.put("shape", RailixValue.string(shape(json.shape())));
                value.put("required", RailixValue.bool(json.required()));
                json.defaultValue().ifPresent(item -> value.put("default", item));
                if (!json.range().isEmpty()) {
                    value.put("minimum", json.range().getFirst());
                    value.put("maximum", json.range().getLast());
                }
            }
            case StepDefinition.PathInput path -> {
                value.put("type", RailixValue.string("path"));
                value.put("access", RailixValue.string(path.access().name().toLowerCase(Locale.ROOT)));
                value.put("required", RailixValue.bool(path.required()));
                path.defaultValue().ifPresent(item -> value.put("default", item));
            }
            case StepDefinition.OptionsInput options -> {
                value.put("type", RailixValue.string("options"));
                value.put("required", RailixValue.bool(options.required()));
                options.defaultOption().ifPresent(item -> value.put("default", RailixValue.string(item)));
                value.put("options", options(options.options()));
            }
            case StepDefinition.CandidatesInput candidates -> {
                value.put("type", RailixValue.string("candidates"));
                candidates.defaultCandidate().ifPresent(item -> value.put("default", RailixValue.string(item)));
                value.put("options", options(candidates.options()));
            }
            case StepDefinition.MatcherGroupsInput groups -> {
                value.put("type", RailixValue.string("matcher_groups"));
                value.put("options", options(groups.options()));
            }
            case StepDefinition.StepsInput steps -> {
                value.put("type", RailixValue.string("steps"));
                final Map<String, RailixValue> source = new LinkedHashMap<>();
                source.put("input", RailixValue.string(steps.valueSource().input()));
                steps.valueSource().missingOutcome().ifPresent(outcome ->
                        source.put("missing_outcome", RailixValue.string(outcome))
                );
                value.put("value_source", RailixValue.object(source));
                if (steps.propagatesOutcomes()) {
                    value.put("propagates_outcomes", RailixValue.bool(true));
                }
            }
        }
        return value;
    }

    private static RailixValue.ArrayValue options(final List<StepDefinition.Option> options) {
        return RailixValue.array(options.stream().<RailixValue>map(option -> {
            final Map<String, RailixValue> value = new LinkedHashMap<>();
            value.put("name", RailixValue.string(option.name()));
            value.put("inputs", fields(option.inputs()));
            option.valueSource().ifPresent(source -> value.put("value_source", RailixValue.object(Map.of(
                    "input", RailixValue.string(source.input()),
                    "scope", RailixValue.string(source.scope().name().toLowerCase(Locale.ROOT))
            ))));
            return RailixValue.object(value);
        }).toList());
    }

    private static List<StepDefinition.Port> ports(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        final List<StepDefinition.Port> ports = new ArrayList<>();
        for (final RailixValue value : array(owner, name, path).values()) {
            final RailixValue.ObjectValue item = object(value, path + "." + name + "[]");
            object(item, Set.of("canonical", "max_depth", "max_json_bytes", "name", "shape"),
                    path + "." + name + "[]", Set.of("canonical", "max_depth", "max_json_bytes"));
            ports.add(new StepDefinition.Port(
                    text(item, "name", path + "." + name + "[]"),
                    shape(text(item, "shape", path + "." + name + "[]"), path + "." + name + "[].shape"),
                    new ValueRefinement(
                            optionalBoolean(item, "canonical", path).orElse(false),
                            optionalInteger(item, "max_depth", path).orElse(0),
                            optionalInteger(item, "max_json_bytes", path).orElse(0)
                    )
            ));
        }
        return List.copyOf(ports);
    }

    private static List<StepDefinition.Field> fields(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        final List<StepDefinition.Field> fields = new ArrayList<>();
        for (final RailixValue value : array(owner, name, path).values()) {
            final RailixValue.ObjectValue item = object(value, path + "." + name + "[]");
            fields.add(new StepDefinition.Field(
                    text(item, "name", path + "." + name + "[]"),
                    input(item, path + "." + name + "[]")
            ));
        }
        return List.copyOf(fields);
    }

    private static Set<String> inputOutcomes(final List<StepDefinition.Field> fields) {
        final Set<String> outcomes = new LinkedHashSet<>();
        for (final StepDefinition.Field field : fields) {
            switch (field.input()) {
                case StepDefinition.StepsInput steps -> steps.valueSource().missingOutcome().ifPresent(outcomes::add);
                case StepDefinition.OptionsInput options -> options.options().forEach(option ->
                        outcomes.addAll(inputOutcomes(option.inputs()))
                );
                case StepDefinition.CandidatesInput candidates -> candidates.options().forEach(option ->
                        outcomes.addAll(inputOutcomes(option.inputs()))
                );
                case StepDefinition.MatcherGroupsInput groups -> groups.options().forEach(option ->
                        outcomes.addAll(inputOutcomes(option.inputs()))
                );
                default -> {
                }
            }
        }
        return Set.copyOf(outcomes);
    }

    private static StepDefinition.Input input(final RailixValue.ObjectValue value, final String path) {
        final String type = text(value, "type", path);
        return switch (type) {
            case "json" -> {
                object(value, Set.of("default", "maximum", "minimum", "name", "required", "shape", "type"),
                        path, Set.of("default", "maximum", "minimum", "name"));
                yield new StepDefinition.JsonInput(
                        shape(text(value, "shape", path), path + ".shape"),
                        value.values().containsKey("default") ? List.of(value.values().get("default")) : List.of(),
                        range(value, path),
                        bool(value, "required", path)
                );
            }
            case "path" -> {
                object(value, Set.of("access", "default", "name", "required", "type"),
                        path, Set.of("default", "name"));
                yield new StepDefinition.PathInput(
                        enumeration(StepDefinition.PathAccess.class, text(value, "access", path), path + ".access"),
                        value.values().containsKey("default")
                                ? List.of(array(value.values().get("default"), path + ".default")) : List.of(),
                        bool(value, "required", path)
                );
            }
            case "options" -> {
                object(value, Set.of("default", "name", "options", "required", "type"),
                        path, Set.of("default", "name"));
                yield new StepDefinition.OptionsInput(
                        options(value, path), optionalText(value, "default", path).map(List::of).orElseGet(List::of),
                        bool(value, "required", path)
                );
            }
            case "candidates" -> {
                object(value, Set.of("default", "name", "options", "type"), path, Set.of("default", "name"));
                yield new StepDefinition.CandidatesInput(
                        options(value, path), optionalText(value, "default", path).map(List::of).orElseGet(List::of)
                );
            }
            case "matcher_groups" -> {
                object(value, Set.of("name", "options", "type"), path, Set.of("name"));
                yield new StepDefinition.MatcherGroupsInput(options(value, path));
            }
            case "steps" -> {
                object(value, Set.of("name", "propagates_outcomes", "type", "value_source"),
                        path, Set.of("name", "propagates_outcomes"));
                final RailixValue.ObjectValue source = requiredObject(value, "value_source", path);
                object(source, Set.of("input", "missing_outcome"), path + ".value_source",
                        Set.of("missing_outcome"));
                final StepDefinition.ValueSource valueSource = new StepDefinition.ValueSource(
                        text(source, "input", path + ".value_source"),
                        optionalText(source, "missing_outcome", path + ".value_source")
                                .map(List::of).orElseGet(List::of)
                );
                yield new StepDefinition.StepsInput(
                        valueSource,
                        optionalBoolean(value, "propagates_outcomes", path).orElse(false)
                );
            }
            default -> throw invalid(path + ".type", "unsupported input type " + type);
        };
    }

    private static List<StepDefinition.Option> options(final RailixValue.ObjectValue owner, final String path) {
        final List<StepDefinition.Option> options = new ArrayList<>();
        for (final RailixValue value : array(owner, "options", path).values()) {
            final RailixValue.ObjectValue item = object(value, path + ".options[]");
            object(item, Set.of("inputs", "name", "value_source"), path + ".options[]",
                    Set.of("value_source"));
            final List<StepDefinition.InputReference> sources = optionalObject(item, "value_source", path)
                    .map(source -> {
                        object(source, Set.of("input", "scope"), path + ".value_source");
                        return List.of(new StepDefinition.InputReference(
                                enumeration(StepDefinition.ReferenceScope.class,
                                        text(source, "scope", path), path + ".value_source.scope"),
                                text(source, "input", path + ".value_source")
                        ));
                    })
                    .orElseGet(List::of);
            options.add(new StepDefinition.Option(
                    text(item, "name", path + ".options[]"),
                    fields(item, "inputs", path + ".options[]"),
                    sources
            ));
        }
        return List.copyOf(options);
    }

    private static List<RailixValue> range(final RailixValue.ObjectValue value, final String path) {
        final boolean minimum = value.values().containsKey("minimum");
        final boolean maximum = value.values().containsKey("maximum");
        if (minimum != maximum) {
            throw invalid(path, "minimum and maximum must be supplied together");
        }
        return minimum ? List.of(value.values().get("minimum"), value.values().get("maximum")) : List.of();
    }

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(Locale.ROOT);
    }

    private static ValueShape shape(final String value, final String path) {
        return enumeration(ValueShape.class, value, path);
    }

    private static <T extends Enum<T>> T enumeration(
            final Class<T> type,
            final String value,
            final String path
    ) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw invalid(path, "unsupported value " + value);
        }
    }

    private static RailixValue.ArrayValue strings(final List<String> values) {
        return RailixValue.array(values.stream().<RailixValue>map(RailixValue::string).toList());
    }

    private static List<String> strings(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return array(owner, name, path).values().stream()
                .map(value -> string(value, path + "." + name + "[]"))
                .toList();
    }

    private static RailixValue required(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        final RailixValue value = owner.values().get(name);
        if (value == null) {
            throw invalid(path + "." + name, "is required");
        }
        return value;
    }

    private static String text(final RailixValue.ObjectValue owner, final String name, final String path) {
        return string(required(owner, name, path), path + "." + name);
    }

    private static java.util.Optional<String> optionalText(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return owner.values().containsKey(name)
                ? java.util.Optional.of(string(owner.values().get(name), path + "." + name))
                : java.util.Optional.empty();
    }

    private static String string(final RailixValue value, final String path) {
        if (value instanceof RailixValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw invalid(path, "must be a non-blank string");
    }

    private static boolean bool(final RailixValue.ObjectValue owner, final String name, final String path) {
        final RailixValue value = required(owner, name, path);
        if (value instanceof RailixValue.BooleanValue bool) {
            return bool.value();
        }
        throw invalid(path + "." + name, "must be boolean");
    }

    private static java.util.Optional<Boolean> optionalBoolean(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return owner.values().containsKey(name)
                ? java.util.Optional.of(bool(owner, name, path))
                : java.util.Optional.empty();
    }

    private static int integer(final RailixValue.ObjectValue owner, final String name, final String path) {
        final RailixValue value = required(owner, name, path);
        if (value instanceof RailixValue.NumberValue number) {
            try {
                return number.value().intValueExact();
            } catch (final ArithmeticException exception) {
                throw invalid(path + "." + name, "must be a 32-bit integer");
            }
        }
        throw invalid(path + "." + name, "must be a number");
    }

    private static java.util.Optional<Integer> optionalInteger(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return owner.values().containsKey(name)
                ? java.util.Optional.of(integer(owner, name, path))
                : java.util.Optional.empty();
    }

    private static RailixValue.ArrayValue array(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return array(required(owner, name, path), path + "." + name);
    }

    private static RailixValue.ArrayValue array(final RailixValue value, final String path) {
        if (value instanceof RailixValue.ArrayValue array) {
            return array;
        }
        throw invalid(path, "must be an array");
    }

    private static RailixValue.ObjectValue object(final RailixValue value, final String path) {
        if (value instanceof RailixValue.ObjectValue object) {
            return object;
        }
        throw invalid(path, "must be an object");
    }

    private static RailixValue.ObjectValue requiredObject(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return object(required(owner, name, path), path + "." + name);
    }

    private static java.util.Optional<RailixValue.ObjectValue> optionalObject(
            final RailixValue.ObjectValue owner,
            final String name,
            final String path
    ) {
        return owner.values().containsKey(name)
                ? java.util.Optional.of(object(owner.values().get(name), path + "." + name))
                : java.util.Optional.empty();
    }

    private static void object(
            final RailixValue.ObjectValue value,
            final Set<String> fields,
            final String path
    ) {
        object(value, fields, path, Set.of("example_target", "source"));
    }

    private static void object(
            final RailixValue.ObjectValue value,
            final Set<String> fields,
            final String path,
            final Set<String> optional
    ) {
        final Set<String> unknown = new LinkedHashSet<>(value.values().keySet());
        unknown.removeAll(fields);
        if (!unknown.isEmpty()) {
            throw invalid(path + "." + unknown.iterator().next(), "is unknown");
        }
        final Set<String> missing = new LinkedHashSet<>(fields);
        missing.removeAll(optional);
        missing.removeAll(value.values().keySet());
        if (!missing.isEmpty()) {
            throw invalid(path + "." + missing.iterator().next(), "is required");
        }
    }

    private static IllegalArgumentException invalid(final String path, final String message) {
        return new IllegalArgumentException(path + " " + message + ".");
    }
}
