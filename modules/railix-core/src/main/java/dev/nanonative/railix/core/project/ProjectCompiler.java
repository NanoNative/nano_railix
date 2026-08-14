package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Sole parser and compiler for one Creator project. */
public final class ProjectCompiler {
    private static final Set<String> PROJECT_FIELDS = Set.of("format", "id", "nodes", "links");
    private static final Set<String> NODE_FIELDS = Set.of(
            "id", "use", "inputs", "receives", "returns", "examples"
    );
    private static final Set<String> EXAMPLE_FIELDS = Set.of("name", "payload", "context");
    private static final Set<String> LINK_FIELDS = Set.of("from", "to");
    private static final Set<String> OPTION_FIELDS = Set.of("option", "inputs");
    private static final Set<String> CANDIDATE_FIELDS = Set.of("option", "inputs", "when");
    private static final Set<String> NESTED_FIELDS = Set.of("use", "inputs");
    private static final Set<String> CONDITION_FIELDS = Set.of("transforms", "all");

    private ProjectCompiler() {
    }

    /** Parses, validates, and lowers one complete project to a directly compilable Java application. */
    public static CompileResult compileApplication(final String source, final StepCatalog catalog) {
        if (source == null || source.isBlank()) {
            return rejected("PROJECT_SOURCE_REQUIRED", "Project source must be supplied.", "");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("Step catalog cannot be Java null.");
        }
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (parsed instanceof RailixJson.Invalid invalid) {
            return new CompileResult.Rejected(List.of(new Diagnostic(
                    "PROJECT_JSON_INVALID",
                    invalid.message(),
                    "",
                    invalid.line(),
                    invalid.column()
            )));
        }
        final RailixValue value = ((RailixJson.Parsed) parsed).value();
        if (!(value instanceof RailixValue.ObjectValue authored)) {
            return rejected("PROJECT_OBJECT_REQUIRED", "Project must be an object.", "");
        }
        final Optional<Diagnostic> unknown = unknown(
                authored,
                PROJECT_FIELDS,
                "PROJECT_FIELD_UNKNOWN",
                "Unknown project field: ",
                ""
        );
        if (unknown.isPresent()) {
            return rejected(unknown.get());
        }
        final Optional<Diagnostic> format = format(authored);
        if (format.isPresent()) {
            return rejected(format.get());
        }
        final TextRead id = text(
                authored,
                "id",
                "PROJECT_ID_REQUIRED",
                "Project id must be a non-blank string.",
                "id"
        );
        if (!id.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(id.diagnostics());
        }
        if (!safeId(id.value())) {
            return rejected(
                    "PROJECT_ID_INVALID",
                    "Project id must start with a lowercase letter and contain only lowercase letters, "
                            + "numbers, or hyphens; maximum length is 64.",
                    "id"
            );
        }
        final ArrayRead nodeValues = array(
                authored,
                "nodes",
                "PROJECT_NODES_ARRAY_REQUIRED",
                "nodes must be an array.",
                "nodes"
        );
        if (!nodeValues.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(nodeValues.diagnostics());
        }
        final NodeRead nodeRead = nodes(nodeValues.value(), catalog);
        if (!nodeRead.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(nodeRead.diagnostics());
        }
        final ArrayRead linkValues = array(
                authored,
                "links",
                "PROJECT_LINKS_ARRAY_REQUIRED",
                "links must be an array.",
                "links"
        );
        if (!linkValues.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(linkValues.diagnostics());
        }
        final LinkRead linkRead = links(linkValues.value(), nodeRead.nodes());
        if (!linkRead.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(linkRead.diagnostics());
        }
        final GraphIndex index = linkRead.index();
        final Optional<Diagnostic> graph = graph(index);
        if (graph.isPresent()) {
            return rejected(graph.get());
        }

        final int[] owners = owners(index);
        final List<ApplicationPlan.NodePlan> plans = nodeRead.nodes().stream()
                .map(node -> plan(node, index.destinations()[node.index()], owners[node.index()]))
                .toList();
        final List<ApplicationPlan.TriggerPlan> triggers = new ArrayList<>();
        for (final Node trigger : nodeRead.nodes()) {
            if (trigger.definition().kind() != StepDefinition.Kind.TRIGGER) {
                continue;
            }
            final ApplicationPlan.TriggerPlan triggerPlan = new ApplicationPlan.TriggerPlan(
                    trigger.index(),
                    trigger.definition().results().stream()
                            .map(result -> new ApplicationPlan.ResultPlan(
                                    result.name(),
                                    result.shape(),
                                    result.defaults()
                            ))
                            .toList(),
                    index.destination(trigger.index(), trigger.definition().primaryOutcome()),
                    "nodes[" + trigger.index() + "]"
            );
            triggers.add(triggerPlan);
        }

        final ApplicationGenerator.Result application = ApplicationGenerator.generate(
                new ApplicationPlan(id.value(), plans, triggers),
                catalog
        );
        if (!application.diagnostics().isEmpty()) {
            return new CompileResult.Rejected(application.diagnostics());
        }
        return new CompileResult.Compiled(
                RailixJson.write(authored),
                ApplicationGenerator.APPLICATION_CLASS,
                application.productionApplicationSource(),
                application.developmentApplicationSource(),
                ApplicationGenerator.DEVELOPMENT_LAUNCHER_CLASS,
                application.developmentLauncherSource(),
                application.dependencies()
        );
    }

    private static Optional<Diagnostic> format(final RailixValue.ObjectValue root) {
        final RailixValue value = root.values().get("format");
        if (!(value instanceof RailixValue.NumberValue number) || !BigDecimal.ONE.equals(number.value())) {
            return Optional.of(Diagnostic.atPath(
                    "PROJECT_FORMAT_UNSUPPORTED",
                    "Project format must be the number 1.",
                    "format"
            ));
        }
        return Optional.empty();
    }

    private static NodeRead nodes(
            final RailixValue.ArrayValue values,
            final StepCatalog catalog
    ) {
        final List<Node> nodes = new ArrayList<>();
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> sources = new LinkedHashSet<>();
        final Map<String, Integer> instances = new LinkedHashMap<>();
        boolean hasApp = false;
        for (int index = 0; index < values.values().size(); index++) {
            final String path = "nodes[" + index + "]";
            final RailixValue value = values.values().get(index);
            if (!(value instanceof RailixValue.ObjectValue object)) {
                return NodeRead.rejected("PROJECT_NODE_OBJECT_REQUIRED", "Node must be an object.", path);
            }
            final Optional<Diagnostic> unknown = unknown(
                    object,
                    NODE_FIELDS,
                    "PROJECT_NODE_FIELD_UNKNOWN",
                    "Unknown node field: ",
                    path
            );
            if (unknown.isPresent()) {
                return new NodeRead(List.of(), List.of(unknown.get()));
            }
            final TextRead id = text(
                    object,
                    "id",
                    "PROJECT_NODE_ID_REQUIRED",
                    "Node id must be a non-blank string.",
                    path + ".id"
            );
            if (!id.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), id.diagnostics());
            }
            if (!safeId(id.value())) {
                return NodeRead.rejected(
                        "PROJECT_NODE_ID_INVALID",
                        "Node id must start with a lowercase letter and contain only lowercase letters, "
                                + "numbers, or hyphens; maximum length is 64.",
                        path + ".id"
                );
            }
            if (!ids.add(id.value())) {
                return NodeRead.rejected(
                        "PROJECT_NODE_ID_DUPLICATE",
                        "Node id is already declared: " + id.value() + ".",
                        path + ".id"
                );
            }
            final TextRead use = text(
                    object,
                    "use",
                    "PROJECT_NODE_USE_REQUIRED",
                    "Node use must be a non-blank string.",
                    path + ".use"
            );
            if (!use.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), use.diagnostics());
            }
            final StepDefinition definition = catalog.find(use.value()).orElse(null);
            if (definition == null) {
                return NodeRead.rejected(
                        "PROJECT_STEP_UNKNOWN",
                        "Step definition is not registered: " + use.value() + ".",
                        path + ".use"
                );
            }
            if (definition.kind() == StepDefinition.Kind.APP && !validApp(definition)) {
                return NodeRead.rejected(
                        "PROJECT_APP_CONTRACT_INVALID",
                        "railix.app must be a structural App Step with only the start outcome.",
                        path + ".use"
                );
            }
            if (definition.kind() == StepDefinition.Kind.TRIGGER && !validTrigger(definition)) {
                return NodeRead.rejected(
                        "PROJECT_TRIGGER_CONTRACT_INVALID",
                        "Trigger Step must declare one unique source, executable behavior, and no returns.",
                        path + ".use"
                );
            }
            if (definition.kind() == StepDefinition.Kind.STEP && !validStep(definition)) {
                return NodeRead.rejected(
                        "PROJECT_STEP_CONTRACT_INVALID",
                        "Graph Step must declare executable behavior without value ports or a Trigger source.",
                        path + ".use"
                );
            }
            final int instanceCount = instances.getOrDefault(definition.id(), 0);
            if (instanceCount >= definition.maximumInstances()) {
                return NodeRead.rejected(
                        "PROJECT_STEP_INSTANCE_LIMIT",
                        "Step " + definition.id() + " allows at most "
                                + definition.maximumInstances() + " instance"
                                + (definition.maximumInstances() == 1 ? "." : "s."),
                        path + ".use"
                );
            }
            final ObjectRead inputs = object(
                    object,
                    "inputs",
                    "PROJECT_NODE_INPUTS_OBJECT_REQUIRED",
                    "Node inputs must be an object.",
                    path + ".inputs"
            );
            if (!inputs.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), inputs.diagnostics());
            }
            final InputsRead compiled = inputs(
                    inputs.value(),
                    definition.inputs(),
                    path + ".inputs",
                    catalog
            );
            if (!compiled.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), compiled.diagnostics());
            }
            final PortRead receives = ports(
                    object,
                    "receives",
                    definition.kind() == StepDefinition.Kind.STEP ? definition.receives() : List.of(),
                    false,
                    path
            );
            if (!receives.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), receives.diagnostics());
            }
            final PortRead returns = ports(
                    object,
                    "returns",
                    definition.kind() == StepDefinition.Kind.STEP ? definition.returns() : List.of(),
                    true,
                    path
            );
            if (!returns.diagnostics().isEmpty()) {
                return new NodeRead(List.of(), returns.diagnostics());
            }
            final List<Diagnostic> exampleDiagnostics = examples(object, definition, path);
            if (!exampleDiagnostics.isEmpty()) {
                return new NodeRead(List.of(), exampleDiagnostics);
            }
            final List<String> outcomes = outcomes(definition, compiled.bindings());
            if (new LinkedHashSet<>(outcomes).size() != outcomes.size()) {
                return NodeRead.rejected(
                        "PROJECT_NESTED_OUTCOME_COLLISION",
                        "Propagated nested Step outcome collides with its enclosing Step outcome.",
                        path + ".inputs"
                );
            }
            if (definition.kind() == StepDefinition.Kind.APP) {
                if (!"app".equals(id.value())) {
                    return NodeRead.rejected(
                            "PROJECT_APP_ID_INVALID",
                            "App Step node id must be app.",
                            path + ".id"
                    );
                }
                hasApp = true;
            } else if (definition.kind() == StepDefinition.Kind.TRIGGER) {
                final StepDefinition.Source source = definition.source().orElseThrow();
                if (!sources.add(source.name())) {
                    return NodeRead.rejected(
                            "PROJECT_TRIGGER_SOURCE_DUPLICATE",
                            "Project may contain only one Trigger for source: " + source.name() + ".",
                            path + ".use"
                    );
                }
            }
            if (definition.kind() == StepDefinition.Kind.TRIGGER
                    && definition.results().stream().anyMatch(result -> "runtime".equals(result.name()))) {
                return NodeRead.rejected(
                        "PROJECT_TRIGGER_RESULT_RESERVED",
                        "Trigger result cannot claim context.runtime.",
                        path + ".use"
                );
            }
            nodes.add(new Node(
                    id.value(),
                    definition,
                    compiled.bindings(),
                    receives.paths(),
                    returns.paths(),
                    outcomes,
                    index
            ));
            instances.put(definition.id(), instanceCount + 1);
        }
        if (!hasApp) {
            return NodeRead.rejected(
                    "PROJECT_APP_REQUIRED",
                    "Project must contain exactly one railix.app node.",
                    "nodes"
            );
        }
        return new NodeRead(nodes, List.of());
    }

    private static PortRead ports(
            final RailixValue.ObjectValue node,
            final String field,
            final List<StepDefinition.Port> declarations,
            final boolean write,
            final String nodePath
    ) {
        final RailixValue authored = node.values().get(field);
        if (authored == null && declarations.isEmpty()) {
            return new PortRead(Map.of(), List.of());
        }
        if (!(authored instanceof RailixValue.ObjectValue object)) {
            return PortRead.rejected(
                    "PROJECT_NODE_" + field.toUpperCase(Locale.ROOT) + "_OBJECT_REQUIRED",
                    "Node " + field + " must be an object.",
                    nodePath + "." + field
            );
        }
        final Set<String> names = declarations.stream()
                .map(StepDefinition.Port::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (final String name : object.values().keySet()) {
            if (!names.contains(name)) {
                return PortRead.rejected(
                        "PROJECT_NODE_PORT_UNKNOWN",
                        "Step " + field + " port is not declared: " + name + ".",
                        nodePath + "." + field + "." + name
                );
            }
        }
        final Map<String, ApplicationPlan.Path> paths = new LinkedHashMap<>();
        for (final StepDefinition.Port declaration : declarations) {
            final String path = nodePath + "." + field + "." + declaration.name();
            final RailixValue value = object.values().get(declaration.name());
            if (value == null) {
                return PortRead.rejected(
                        "PROJECT_NODE_PORT_REQUIRED",
                        "Step " + field + " port path is required: " + declaration.name() + ".",
                        path
                );
            }
            final PathRead parsed = path(value, path, write);
            if (!parsed.diagnostics().isEmpty()) {
                return new PortRead(Map.of(), parsed.diagnostics());
            }
            paths.put(declaration.name(), parsed.value());
        }
        return new PortRead(paths, List.of());
    }

    private static InputsRead inputs(
            final RailixValue.ObjectValue authored,
            final List<StepDefinition.Field> declarations,
            final String path,
            final StepCatalog catalog
    ) {
        final Map<String, StepDefinition.Field> fields = new LinkedHashMap<>();
        declarations.forEach(field -> fields.put(field.name(), field));
        for (final String name : authored.values().keySet()) {
            if (!fields.containsKey(name)) {
                return InputsRead.rejected(
                        "PROJECT_INPUT_UNKNOWN",
                        "Step input is not declared: " + name + ".",
                        path + "." + name
                );
            }
        }
        final Map<String, ApplicationPlan.Binding> bindings = new LinkedHashMap<>();
        for (final StepDefinition.Field field : declarations) {
            final String fieldPath = path + "." + field.name();
            final RailixValue value = authored.values().get(field.name());
            final InputRead input = input(value, field.input(), fieldPath, catalog, bindings);
            if (!input.diagnostics().isEmpty()) {
                return new InputsRead(Map.of(), input.diagnostics());
            }
            bindings.put(field.name(), input.binding());
        }
        return new InputsRead(bindings, List.of());
    }

    private static InputRead input(
            final RailixValue authored,
            final StepDefinition.Input declaration,
            final String path,
            final StepCatalog catalog,
            final Map<String, ApplicationPlan.Binding> previous
    ) {
        return switch (declaration) {
            case StepDefinition.JsonInput json -> json(authored, json, path);
            case StepDefinition.PathInput selectedPath -> path(authored, selectedPath, path);
            case StepDefinition.OptionsInput options -> options(authored, options, path, catalog);
            case StepDefinition.CandidatesInput candidates -> candidates(
                    authored, candidates, path, catalog, previous
            );
            case StepDefinition.MatcherGroupsInput matcherGroups -> matcherGroups(
                    authored, matcherGroups, path, catalog, previous
            );
            case StepDefinition.StepsInput steps -> steps(authored, steps, path, catalog);
        };
    }

    private static InputRead json(
            RailixValue value,
            final StepDefinition.JsonInput declaration,
            final String path
    ) {
        if (value == null) {
            value = declaration.defaultValue().orElse(null);
        }
        if (value == null && declaration.required()) {
            return InputRead.rejected("PROJECT_INPUT_REQUIRED", "Step input is required.", path);
        }
        if (value == null) {
            return InputRead.empty();
        }
        if (!declaration.shape().accepts(value)) {
            return InputRead.rejected(
                    "PROJECT_INPUT_SHAPE_INVALID",
                    "Step input must be " + shape(declaration.shape()) + ".",
                    path
            );
        }
        if (!declaration.withinRange(value)) {
            return InputRead.rejected(
                    "PROJECT_INPUT_RANGE_INVALID",
                    "Step input must be from " + RailixJson.write(declaration.range().getFirst())
                            + " through " + RailixJson.write(declaration.range().getLast()) + ".",
                    path
            );
        }
        return InputRead.value(new ApplicationPlan.JsonBinding(List.of(value)));
    }

    private static InputRead path(
            RailixValue value,
            final StepDefinition.PathInput declaration,
            final String path
    ) {
        if (value == null) {
            value = declaration.defaultValue().orElse(null);
        }
        if (value == null && declaration.required()) {
            return InputRead.rejected("PROJECT_INPUT_REQUIRED", "Step PATH input is required.", path);
        }
        if (value == null) {
            return InputRead.empty();
        }
        final PathRead parsed = path(value, path, declaration.access().writable());
        if (!parsed.diagnostics().isEmpty()) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), parsed.diagnostics());
        }
        return InputRead.value(new ApplicationPlan.PathBinding(parsed.value(), declaration.access()));
    }

    private static InputRead options(
            RailixValue value,
            final StepDefinition.OptionsInput declaration,
            final String path,
            final StepCatalog catalog
    ) {
        if (value == null && declaration.defaultOption().isPresent()) {
            value = RailixValue.object(Map.of(
                    "option", RailixValue.string(declaration.defaultOption().get()),
                    "inputs", RailixValue.object(Map.of())
            ));
        }
        if (value == null && declaration.required()) {
            return InputRead.rejected("PROJECT_INPUT_REQUIRED", "Step OPTIONS input is required.", path);
        }
        if (value == null) {
            return InputRead.empty();
        }
        if (!(value instanceof RailixValue.ObjectValue object)) {
            return InputRead.rejected(
                    "PROJECT_OPTION_OBJECT_REQUIRED",
                    "OPTIONS input must be an object containing option and inputs.",
                    path
            );
        }
        return choice(object, declaration.options(), path, catalog, OPTION_FIELDS, "OPTIONS", "OPTION");
    }

    private static InputRead choice(
            final RailixValue.ObjectValue object,
            final List<StepDefinition.Option> declaredOptions,
            final String path,
            final StepCatalog catalog,
            final Set<String> fields,
            final String label,
            final String code
    ) {
        final Optional<Diagnostic> unknown = unknown(
                object,
                fields,
                "PROJECT_" + code + "_FIELD_UNKNOWN",
                "Unknown " + label + " field: ",
                path
        );
        if (unknown.isPresent()) {
            return InputRead.rejected(unknown.get());
        }
        final TextRead optionName = text(
                object,
                "option",
                "PROJECT_" + code + "_REQUIRED",
                label + " input must name one option.",
                path + ".option"
        );
        if (!optionName.diagnostics().isEmpty()) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), optionName.diagnostics());
        }
        final StepDefinition.Option option = declaredOptions.stream()
                .filter(candidate -> candidate.name().equals(optionName.value()))
                .findFirst()
                .orElse(null);
        if (option == null) {
            return InputRead.rejected(
                    "PROJECT_" + code + "_UNKNOWN",
                    label + " input does not declare option: " + optionName.value() + ".",
                    path + ".option"
            );
        }
        final ObjectRead childInputs = object(
                object,
                "inputs",
                "PROJECT_" + code + "_INPUTS_OBJECT_REQUIRED",
                "Selected " + code.toLowerCase(java.util.Locale.ROOT) + " inputs must be an object.",
                path + ".inputs"
        );
        if (!childInputs.diagnostics().isEmpty()) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), childInputs.diagnostics());
        }
        final InputsRead children = inputs(childInputs.value(), option.inputs(), path + ".inputs", catalog);
        if (!children.diagnostics().isEmpty()) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), children.diagnostics());
        }
        return InputRead.value(new ApplicationPlan.ChoiceBinding(
                option.name(),
                children.bindings(),
                option.valueSources()
        ));
    }

    private static InputRead steps(
            final RailixValue value,
            final StepDefinition.StepsInput declaration,
            final String path,
            final StepCatalog catalog
    ) {
        final NestedRead nested = nestedSteps(value, path, catalog);
        if (!nested.diagnostics().isEmpty()) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), nested.diagnostics());
        }
        return InputRead.value(
                new ApplicationPlan.StepsBinding(
                        nested.plans(),
                        declaration.valueSource(),
                        declaration.propagatesOutcomes()
                )
        );
    }

    private static InputRead candidates(
            RailixValue value,
            final StepDefinition.CandidatesInput declaration,
            final String path,
            final StepCatalog catalog,
            final Map<String, ApplicationPlan.Binding> previous
    ) {
        if (value == null && declaration.defaultCandidate().isPresent()) {
            value = RailixValue.array(List.of(RailixValue.object(Map.of(
                    "option", RailixValue.string(declaration.defaultCandidate().get()),
                    "inputs", RailixValue.object(Map.of()),
                    "when", conditionValue(List.of(), List.of())
            ))));
        }
        final RailixValue resolved = value == null ? RailixValue.array(List.of()) : value;
        if (!(resolved instanceof RailixValue.ArrayValue array)) {
            return InputRead.rejected(
                    "PROJECT_CANDIDATES_ARRAY_REQUIRED",
                    "CANDIDATES input must be an array.",
                    path
            );
        }
        final List<ApplicationPlan.CandidatePlan> plans = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String candidatePath = path + "[" + index + "]";
            final CandidateRead candidate = candidate(
                    array.values().get(index), declaration.options(), candidatePath, catalog, previous, "Candidate", "CANDIDATE"
            );
            if (!candidate.diagnostics().isEmpty()) {
                return new InputRead(new ApplicationPlan.JsonBinding(List.of()), candidate.diagnostics());
            }
            plans.add(candidate.plan());
        }
        return InputRead.value(new ApplicationPlan.CandidatesBinding(plans, path));
    }

    private static InputRead matcherGroups(
            final RailixValue value,
            final StepDefinition.MatcherGroupsInput declaration,
            final String path,
            final StepCatalog catalog,
            final Map<String, ApplicationPlan.Binding> previous
    ) {
        final RailixValue resolved = value == null ? RailixValue.array(List.of()) : value;
        if (!(resolved instanceof RailixValue.ArrayValue groups)) {
            return InputRead.rejected(
                    "PROJECT_MATCHER_GROUPS_ARRAY_REQUIRED",
                    "Matcher groups input must be an array.",
                    path
            );
        }
        final List<List<ApplicationPlan.CandidatePlan>> plans = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groups.values().size(); groupIndex++) {
            final String groupPath = path + "[" + groupIndex + "]";
            final RailixValue group = groups.values().get(groupIndex);
            if (!(group instanceof RailixValue.ArrayValue matchers)) {
                return InputRead.rejected(
                        "PROJECT_MATCHER_GROUP_ARRAY_REQUIRED",
                        "Matcher group must be an array.",
                        groupPath
                );
            }
            if (matchers.values().isEmpty()) {
                return InputRead.rejected(
                        "PROJECT_MATCHER_GROUP_EMPTY",
                        "Matcher group must contain at least one matcher.",
                        groupPath
                );
            }
            final List<ApplicationPlan.CandidatePlan> matcherPlans = new ArrayList<>();
            for (int matcherIndex = 0; matcherIndex < matchers.values().size(); matcherIndex++) {
                final CandidateRead matcher = candidate(
                        matchers.values().get(matcherIndex),
                        declaration.options(),
                        groupPath + "[" + matcherIndex + "]",
                        catalog,
                        previous,
                        "Matcher",
                        "MATCHER"
                );
                if (!matcher.diagnostics().isEmpty()) {
                    return new InputRead(new ApplicationPlan.JsonBinding(List.of()), matcher.diagnostics());
                }
                matcherPlans.add(matcher.plan());
            }
            plans.add(List.copyOf(matcherPlans));
        }
        return InputRead.value(new ApplicationPlan.MatcherGroupsBinding(plans));
    }

    private static CandidateRead candidate(
            final RailixValue candidate,
            final List<StepDefinition.Option> options,
            final String path,
            final StepCatalog catalog,
            final Map<String, ApplicationPlan.Binding> previous,
            final String label,
            final String code
    ) {
        if (!(candidate instanceof RailixValue.ObjectValue object)) {
            return CandidateRead.rejected(
                    "PROJECT_" + code + "_OBJECT_REQUIRED",
                    label + " must be an object containing option, inputs, and when.",
                    path
            );
        }
        final InputRead source = choice(object, options, path, catalog, CANDIDATE_FIELDS, label, code);
        if (!source.diagnostics().isEmpty()) {
            return CandidateRead.rejected(source.diagnostics());
        }
        final ConditionRead condition = condition(object.values().get("when"), path + ".when", catalog, label, code);
        if (!condition.diagnostics().isEmpty()) {
            return CandidateRead.rejected(condition.diagnostics());
        }
        final List<ApplicationPlan.NestedStepPlan> transforms = condition.transforms();
        final List<List<ApplicationPlan.NestedStepPlan>> predicates = condition.predicates();
        final ApplicationPlan.ChoiceBinding selected = (ApplicationPlan.ChoiceBinding) source.binding();
        final ValueShape supplied = selected.valueSource()
                .map(reference -> reference.scope() == StepDefinition.ReferenceScope.OWNED
                        ? selected.inputs().get(reference.input())
                        : previous.get(reference.input()))
                .map(binding -> bindingShape(binding, previous))
                .orElse(ValueShape.ANY);
        final Optional<Diagnostic> transformMismatch = incompatible(supplied, transforms, label);
        if (transformMismatch.isPresent()) {
            return CandidateRead.rejected(transformMismatch.get());
        }
        final ValueShape prepared = transforms.isEmpty()
                ? supplied
                : transforms.getLast().step().returns().getFirst().shape();
        for (final List<ApplicationPlan.NestedStepPlan> program : predicates) {
            final Optional<Diagnostic> mismatch = incompatible(prepared, program, label);
            if (mismatch.isPresent()) {
                return CandidateRead.rejected(mismatch.get());
            }
        }
        return new CandidateRead(
                new ApplicationPlan.CandidatePlan(selected, transforms, predicates),
                List.of()
        );
    }

    private static ConditionRead condition(
            final RailixValue value,
            final String path,
            final StepCatalog catalog,
            final String label,
            final String code
    ) {
        if (value == null) {
            return ConditionRead.empty();
        }
        if (value instanceof RailixValue.ObjectValue structured) {
            return structuredCondition(structured, path, catalog, label, code);
        }
        return ConditionRead.rejected(
                "PROJECT_" + code + "_CONDITION_OBJECT_REQUIRED",
                label + " when must be a condition object.",
                path
        );
    }

    private static ConditionRead structuredCondition(
            final RailixValue.ObjectValue value,
            final String path,
            final StepCatalog catalog,
            final String label,
            final String code
    ) {
        final Optional<Diagnostic> unknown = unknown(
                value,
                CONDITION_FIELDS,
                "PROJECT_" + code + "_PREDICATE_FIELD_UNKNOWN",
                "Unknown " + label.toLowerCase(Locale.ROOT) + " predicate field: ",
                path
        );
        if (unknown.isPresent()) {
            return ConditionRead.rejected(unknown.get());
        }
        final RailixValue transformsValue = value.values().get("transforms");
        if (!(transformsValue instanceof RailixValue.ArrayValue transformsArray)) {
            return ConditionRead.rejected(
                    "PROJECT_" + code + "_TRANSFORMS_ARRAY_REQUIRED",
                    label + " transforms must be an array of unary Steps.",
                    path + ".transforms"
            );
        }
        final RailixValue predicatesValue = value.values().get("all");
        if (!(predicatesValue instanceof RailixValue.ArrayValue predicatesArray)) {
            return ConditionRead.rejected(
                    "PROJECT_" + code + "_PREDICATES_ARRAY_REQUIRED",
                    label + " all predicates must be an array.",
                    path + ".all"
            );
        }
        if (predicatesArray.values().isEmpty() && transformsArray.values().isEmpty()) {
            return ConditionRead.empty();
        }
        if (predicatesArray.values().isEmpty()) {
            return ConditionRead.rejected(
                    "PROJECT_" + code + "_PREDICATE_EMPTY",
                    label + " transforms require at least one predicate.",
                    path + ".all"
            );
        }
        final NestedRead transforms = nestedSteps(transformsArray, path + ".transforms", catalog);
        if (!transforms.diagnostics().isEmpty()) {
            return ConditionRead.rejected(transforms.diagnostics());
        }
        final Optional<Diagnostic> transformOutcome = predicateOutcomes(transforms.plans(), label, code);
        if (transformOutcome.isPresent()) {
            return ConditionRead.rejected(transformOutcome.get());
        }
        final List<List<ApplicationPlan.NestedStepPlan>> predicates = new ArrayList<>();
        for (int index = 0; index < predicatesArray.values().size(); index++) {
            final RailixValue authored = predicatesArray.values().get(index);
            final String predicatePath = path + ".all[" + index + "]";
            if (!(authored instanceof RailixValue.ArrayValue array)) {
                return ConditionRead.rejected(
                        "PROJECT_" + code + "_PREDICATE_ARRAY_REQUIRED",
                        label + " predicate must be an array of unary Steps.",
                        predicatePath
                );
            }
            if (array.values().isEmpty()) {
                return ConditionRead.rejected(
                        "PROJECT_" + code + "_PREDICATE_EMPTY",
                        label + " predicate must contain at least one Step.",
                        predicatePath
                );
            }
            final NestedRead predicate = nestedSteps(array, predicatePath, catalog);
            if (!predicate.diagnostics().isEmpty()) {
                return ConditionRead.rejected(predicate.diagnostics());
            }
            final Optional<Diagnostic> outcome = predicateOutcomes(predicate.plans(), label, code);
            if (outcome.isPresent()) {
                return ConditionRead.rejected(outcome.get());
            }
            final Optional<Diagnostic> result = predicateResult(predicate.plans(), label, code);
            if (result.isPresent()) {
                return ConditionRead.rejected(result.get());
            }
            predicates.add(predicate.plans());
        }
        return new ConditionRead(
                transforms.plans(),
                predicates,
                List.of()
        );
    }

    private static RailixValue conditionValue(
            final List<RailixValue> transforms,
            final List<List<RailixValue>> predicates
    ) {
        return RailixValue.object(Map.of(
                "transforms", RailixValue.array(transforms),
                "all", RailixValue.array(predicates.stream().<RailixValue>map(RailixValue::array).toList())
        ));
    }

    private static Optional<Diagnostic> predicateOutcomes(
            final List<ApplicationPlan.NestedStepPlan> program,
            final String label,
            final String code
    ) {
        return program.stream()
                .filter(step -> step.step().outcomes().size() != 1)
                .findFirst()
                .map(step -> Diagnostic.atPath(
                        "PROJECT_" + code + "_PREDICATE_OUTCOME_INVALID",
                        label + " condition Steps must declare only their primary outcome.",
                        step.path() + ".use"
                ));
    }

    private static Optional<Diagnostic> predicateResult(
            final List<ApplicationPlan.NestedStepPlan> program,
            final String label,
            final String code
    ) {
        if (!program.isEmpty()
                && program.getLast().step().returns().getFirst().shape() == ValueShape.BOOLEAN) {
            return Optional.empty();
        }
        final String path = program.isEmpty() ? "" : program.getLast().path() + ".use";
        return Optional.of(Diagnostic.atPath(
                "PROJECT_" + code + "_PREDICATE_RESULT_INVALID",
                label + " predicate program must return boolean.",
                path
        ));
    }

    private static Optional<Diagnostic> incompatible(
            final ValueShape supplied,
            final List<ApplicationPlan.NestedStepPlan> program,
            final String label
    ) {
        ValueShape previous = supplied;
        for (int index = 0; index < program.size(); index++) {
            final ApplicationPlan.NestedStepPlan step = program.get(index);
            final ValueShape required = step.step().receives().getFirst().shape();
            if (previous != ValueShape.ANY && required != ValueShape.ANY && previous != required) {
                final String source = index == 0
                        ? "the " + label.toLowerCase(Locale.ROOT) + " source"
                        : "the previous Step";
                return Optional.of(Diagnostic.atPath(
                        "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + step.step().use() + " cannot receive "
                                + previous.name().toLowerCase(Locale.ROOT) + " from " + source + ".",
                        step.path() + ".use"
                ));
            }
            previous = step.step().returns().getFirst().shape();
        }
        return Optional.empty();
    }

    private static ValueShape bindingShape(
            final ApplicationPlan.Binding binding,
            final Map<String, ApplicationPlan.Binding> parent
    ) {
        if (binding instanceof ApplicationPlan.JsonBinding json && !json.value().isEmpty()) {
            return ValueShape.shapeOf(json.value().getFirst());
        }
        if (binding instanceof ApplicationPlan.ChoiceBinding choice) {
            return choice.valueSource()
                    .map(reference -> reference.scope() == StepDefinition.ReferenceScope.OWNED
                            ? choice.inputs().get(reference.input())
                            : parent.get(reference.input()))
                    .map(source -> bindingShape(source, parent))
                    .orElse(ValueShape.ANY);
        }
        if (binding instanceof ApplicationPlan.MatcherGroupsBinding) {
            return ValueShape.BOOLEAN;
        }
        return ValueShape.ANY;
    }

    private static NestedRead nestedSteps(
            final RailixValue value,
            final String path,
            final StepCatalog catalog
    ) {
        final RailixValue resolved = value == null ? RailixValue.array(List.of()) : value;
        if (!(resolved instanceof RailixValue.ArrayValue array)) {
            return NestedRead.rejected("PROJECT_STEPS_ARRAY_REQUIRED", "STEPS input must be an array.", path);
        }
        final List<ApplicationPlan.NestedStepPlan> plans = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String stepPath = path + "[" + index + "]";
            final RailixValue nested = array.values().get(index);
            if (!(nested instanceof RailixValue.ObjectValue object)) {
                return NestedRead.rejected(
                        "PROJECT_NESTED_STEP_OBJECT_REQUIRED",
                        "Nested Step invocation must be an object.",
                        stepPath
                );
            }
            final Optional<Diagnostic> unknown = unknown(
                    object,
                    NESTED_FIELDS,
                    "PROJECT_NESTED_STEP_FIELD_UNKNOWN",
                    "Unknown nested Step field: ",
                    stepPath
            );
            if (unknown.isPresent()) {
                return NestedRead.rejected(unknown.get());
            }
            final TextRead use = text(
                    object,
                    "use",
                    "PROJECT_NESTED_STEP_USE_REQUIRED",
                    "Nested Step use must be a non-blank string.",
                    stepPath + ".use"
            );
            if (!use.diagnostics().isEmpty()) {
                return new NestedRead(List.of(), use.diagnostics());
            }
            final StepDefinition definition = catalog.find(use.value()).orElse(null);
            if (definition == null) {
                return NestedRead.rejected(
                        "PROJECT_NESTED_STEP_UNKNOWN",
                        "Nested Step is not registered: " + use.value() + ".",
                        stepPath + ".use"
                );
            }
            if (definition.kind() != StepDefinition.Kind.STEP) {
                return NestedRead.rejected(
                        "PROJECT_NESTED_STEP_KIND_INVALID",
                        "Nested value programs accept only ordinary Steps: " + use.value() + ".",
                        stepPath + ".use"
                );
            }
            if (!validNestedStep(definition)) {
                return NestedRead.rejected(
                        "PROJECT_NESTED_STEP_CONTRACT_INVALID",
                        "Nested Step must receive and return one value and declare a handler.",
                        stepPath + ".use"
                );
            }
            final ObjectRead nestedInputs = object(
                    object,
                    "inputs",
                    "PROJECT_NESTED_INPUTS_OBJECT_REQUIRED",
                    "Nested Step inputs must be an object.",
                    stepPath + ".inputs"
            );
            if (!nestedInputs.diagnostics().isEmpty()) {
                return new NestedRead(List.of(), nestedInputs.diagnostics());
            }
            final InputsRead compiled = inputs(
                    nestedInputs.value(),
                    definition.inputs(),
                    stepPath + ".inputs",
                    catalog
            );
            if (!compiled.diagnostics().isEmpty()) {
                return new NestedRead(List.of(), compiled.diagnostics());
            }
            if (!plans.isEmpty()) {
                final ValueShape previous = plans.getLast().step().returns().getFirst().shape();
                final ValueShape next = definition.receives().getFirst().shape();
                if (previous != ValueShape.ANY && next != ValueShape.ANY && previous != next) {
                    return NestedRead.rejected(
                            "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                            "Nested Step " + definition.id() + " cannot receive "
                                    + previous.name().toLowerCase(Locale.ROOT) + " from the previous Step.",
                            stepPath + ".use"
                    );
                }
            }
            plans.add(new ApplicationPlan.NestedStepPlan(
                    ApplicationPlan.ExecutableStep.from(definition),
                    compiled.bindings(),
                    stepPath
            ));
        }
        return new NestedRead(plans, List.of());
    }

    private static List<Diagnostic> examples(
            final RailixValue.ObjectValue node,
            final StepDefinition definition,
            final String nodePath
    ) {
        final RailixValue value = node.values().get("examples");
        if (definition.kind() != StepDefinition.Kind.TRIGGER) {
            return value == null
                    ? List.of()
                    : List.of(Diagnostic.atPath(
                            "PROJECT_NODE_EXAMPLES_UNSUPPORTED",
                            "Only Trigger Steps may declare examples.",
                            nodePath + ".examples"
                    ));
        }
        if (!(value instanceof RailixValue.ArrayValue array) || array.values().isEmpty()) {
            return List.of(Diagnostic.atPath(
                    "PROJECT_TRIGGER_EXAMPLE_REQUIRED",
                    "Trigger Step must define at least one payload example.",
                    nodePath + ".examples"
            ));
        }
        final Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < array.values().size(); index++) {
            final String path = nodePath + ".examples[" + index + "]";
            final RailixValue item = array.values().get(index);
            if (!(item instanceof RailixValue.ObjectValue object)) {
                return List.of(Diagnostic.atPath(
                        "PROJECT_TRIGGER_EXAMPLE_OBJECT_REQUIRED",
                        "Trigger example must be an object.",
                        path
                ));
            }
            final Optional<Diagnostic> unknown = unknown(
                    object,
                    EXAMPLE_FIELDS,
                    "PROJECT_TRIGGER_EXAMPLE_FIELD_UNKNOWN",
                    "Unknown Trigger example field: ",
                    path
            );
            if (unknown.isPresent()) {
                return List.of(unknown.get());
            }
            final TextRead name = text(
                    object,
                    "name",
                    "PROJECT_TRIGGER_EXAMPLE_NAME_REQUIRED",
                    "Trigger example name must be a non-blank string.",
                    path + ".name"
            );
            if (!name.diagnostics().isEmpty()) {
                return name.diagnostics();
            }
            if (!names.add(name.value())) {
                return List.of(Diagnostic.atPath(
                        "PROJECT_TRIGGER_EXAMPLE_NAME_DUPLICATE",
                        "Trigger example name is already declared: " + name.value() + ".",
                        path + ".name"
                ));
            }
            if (!object.values().containsKey("payload")) {
                return List.of(Diagnostic.atPath(
                        "PROJECT_TRIGGER_EXAMPLE_PAYLOAD_REQUIRED",
                        "Trigger example payload is required.",
                        path + ".payload"
                ));
            }
            final RailixValue contextValue = object.values().get("context");
            if (contextValue != null && !(contextValue instanceof RailixValue.ObjectValue)) {
                return List.of(Diagnostic.atPath(
                        "PROJECT_TRIGGER_EXAMPLE_CONTEXT_OBJECT_REQUIRED",
                        "Trigger example context must be an object when supplied.",
                        path + ".context"
                ));
            }
            if (contextValue instanceof RailixValue.ObjectValue context
                    && context.values().containsKey("runtime")) {
                return List.of(Diagnostic.atPath(
                        "PROJECT_TRIGGER_EXAMPLE_RUNTIME_RESERVED",
                        "context.runtime is supplied by Railix.",
                        path + ".context.runtime"
                ));
            }
        }
        return List.of();
    }

    private static LinkRead links(final RailixValue.ArrayValue values, final List<Node> nodes) {
        final Map<String, Integer> nodeIndexes = new HashMap<>();
        final Map<Endpoint, Integer> outcomeIndexes = new HashMap<>();
        final List<List<Link>> outgoing = new ArrayList<>(nodes.size());
        final int[] incoming = new int[nodes.size()];
        final int[][] connections = new int[nodes.size()][];
        final int[][] destinations = new int[nodes.size()][];
        int app = ApplicationPlan.END;
        for (final Node node : nodes) {
            nodeIndexes.put(node.id(), node.index());
            outgoing.add(new ArrayList<>());
            connections[node.index()] = new int[node.outcomes().size()];
            destinations[node.index()] = new int[node.outcomes().size()];
            Arrays.fill(destinations[node.index()], ApplicationPlan.UNROUTED);
            for (int outcome = 0; outcome < node.outcomes().size(); outcome++) {
                outcomeIndexes.put(new Endpoint(node.id(), node.outcomes().get(outcome)), outcome);
            }
            if (node.definition().kind() == StepDefinition.Kind.APP) {
                app = node.index();
            }
        }

        final List<Link> links = new ArrayList<>(values.values().size());
        for (int index = 0; index < values.values().size(); index++) {
            final String path = "links[" + index + "]";
            final RailixValue value = values.values().get(index);
            if (!(value instanceof RailixValue.ObjectValue object)) {
                return LinkRead.rejected("PROJECT_LINK_OBJECT_REQUIRED", "Link must be an object.", path);
            }
            final Optional<Diagnostic> unknown = unknown(
                    object,
                    LINK_FIELDS,
                    "PROJECT_LINK_FIELD_UNKNOWN",
                    "Unknown link field: ",
                    path
            );
            if (unknown.isPresent()) {
                return LinkRead.rejected(unknown.get());
            }
            final TextRead from = text(
                    object,
                    "from",
                    "PROJECT_LINK_FROM_REQUIRED",
                    "Link from must be a non-blank node.outcome string.",
                    path + ".from"
            );
            if (!from.diagnostics().isEmpty()) {
                return LinkRead.rejected(from.diagnostics());
            }
            final EndpointRead endpoint = endpoint(from.value(), path + ".from");
            if (!endpoint.diagnostics().isEmpty()) {
                return LinkRead.rejected(endpoint.diagnostics());
            }
            final Integer sourceIndex = nodeIndexes.get(endpoint.value().node());
            if (sourceIndex == null) {
                return LinkRead.rejected(
                        "PROJECT_LINK_SOURCE_UNKNOWN",
                        "Link source node is not declared: " + endpoint.value().node() + ".",
                        path + ".from"
                );
            }
            final Node source = nodes.get(sourceIndex);
            final Integer outcomeIndex = outcomeIndexes.get(endpoint.value());
            if (outcomeIndex == null) {
                return LinkRead.rejected(
                        "PROJECT_LINK_OUTCOME_UNKNOWN",
                        "Link source outcome is not declared: " + from.value() + ".",
                        path + ".from"
                );
            }
            if (source.definition().kind() != StepDefinition.Kind.APP
                    && connections[sourceIndex][outcomeIndex] != 0) {
                return LinkRead.rejected(
                        "PROJECT_PORT_CONNECTION_DUPLICATE",
                        "Step outcome may have only one connection: " + from.value() + ".",
                        path + ".from"
                );
            }
            final TextRead to = text(
                    object,
                    "to",
                    "PROJECT_LINK_TO_REQUIRED",
                    "Link to must be a non-blank node id or end.",
                    path + ".to"
            );
            if (!to.diagnostics().isEmpty()) {
                return LinkRead.rejected(to.diagnostics());
            }
            final Integer targetIndex;
            if ("end".equals(to.value())) {
                targetIndex = ApplicationPlan.END;
            } else {
                targetIndex = nodeIndexes.get(to.value());
            }
            if (targetIndex == null) {
                return LinkRead.rejected(
                        "PROJECT_LINK_TARGET_UNKNOWN",
                        "Link target node is not declared: " + to.value() + ".",
                        path + ".to"
                );
            }
            final Link link = new Link(sourceIndex, outcomeIndex, targetIndex, index);
            links.add(link);
            outgoing.get(sourceIndex).add(link);
            connections[sourceIndex][outcomeIndex]++;
            destinations[sourceIndex][outcomeIndex] = targetIndex;
            if (targetIndex != ApplicationPlan.END) {
                incoming[targetIndex]++;
            }
        }
        return new LinkRead(new GraphIndex(
                nodes,
                links,
                outgoing,
                incoming,
                connections,
                destinations,
                app
        ), List.of());
    }

    private static Optional<Diagnostic> graph(final GraphIndex index) {
        final List<Node> nodes = index.nodes();
        for (final Link link : index.links()) {
            final Node source = nodes.get(link.from());
            final Node target = link.to() == ApplicationPlan.END ? null : nodes.get(link.to());
            if (source.definition().kind() == StepDefinition.Kind.APP) {
                if (target == null || target.definition().kind() != StepDefinition.Kind.TRIGGER) {
                    return Optional.of(Diagnostic.atPath(
                            "PROJECT_APP_CONNECTION_INVALID",
                            "app.start may connect only to Trigger Steps.",
                            "links[" + link.index() + "]"
                    ));
                }
            } else if (source.definition().kind() == StepDefinition.Kind.TRIGGER) {
                if (target != null && target.definition().kind() != StepDefinition.Kind.STEP) {
                    return Optional.of(Diagnostic.atPath(
                            "PROJECT_TRIGGER_CONNECTION_INVALID",
                            "Trigger may connect only to an ordinary Step or end.",
                            "links[" + link.index() + "]"
                    ));
                }
            } else if (target != null && target.definition().kind() != StepDefinition.Kind.STEP) {
                return Optional.of(Diagnostic.atPath(
                        "PROJECT_STEP_CONNECTION_INVALID",
                        "Ordinary Step may connect only to another ordinary Step or end.",
                        "links[" + link.index() + "]"
                ));
            }
        }
        for (final Node current : nodes) {
            if (current.definition().kind() == StepDefinition.Kind.APP) {
                continue;
            }
            for (int outcome = 0; outcome < current.outcomes().size(); outcome++) {
                if (index.connections()[current.index()][outcome] != 1) {
                    return Optional.of(Diagnostic.atPath(
                            current.outcomes().size() == 1
                                    ? "PROJECT_NODE_OUTPUT_CONNECTION_REQUIRED"
                                    : "PROJECT_NODE_OUTCOME_CONNECTION_REQUIRED",
                            current.outcomes().size() == 1
                                    ? "Step must have exactly one outgoing connection: " + current.id() + "."
                                    : "Step outcome must have exactly one connection: "
                                            + current.id() + "." + current.outcomes().get(outcome) + ".",
                            "nodes[" + current.index() + "]"
                    ));
                }
            }
        }
        for (final Node current : nodes) {
            if (current.definition().kind() != StepDefinition.Kind.APP
                    && index.incoming()[current.index()] == 0) {
                return Optional.of(Diagnostic.atPath(
                        "PROJECT_NODE_INPUT_CONNECTION_REQUIRED",
                        "Step must have exactly one incoming connection: " + current.id() + ".",
                        "nodes[" + current.index() + "]"
                ));
            }
        }
        final boolean[] reachable = new boolean[nodes.size()];
        reachable[index.app()] = true;
        final List<Integer> pending = new ArrayList<>();
        for (final Link link : index.outgoing().get(index.app())) {
            if (link.to() != ApplicationPlan.END) {
                pending.add(link.to());
            }
        }
        for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
            final int current = pending.get(pendingIndex);
            if (!reachable[current]) {
                reachable[current] = true;
                for (final Link link : index.outgoing().get(current)) {
                    if (link.to() != ApplicationPlan.END) {
                        pending.add(link.to());
                    }
                }
            }
        }
        for (final Node current : nodes) {
            if (!reachable[current.index()]) {
                return Optional.of(Diagnostic.atPath(
                        "PROJECT_NODE_UNREACHABLE",
                        "Step is not reachable from app.start: " + current.id() + ".",
                        "nodes[" + current.index() + "]"
                ));
            }
        }
        final int[] remaining = index.incoming().clone();
        final List<Integer> acyclic = new ArrayList<>(nodes.size());
        for (final Node node : nodes) {
            if (remaining[node.index()] == 0) {
                acyclic.add(node.index());
            }
        }
        for (int acyclicIndex = 0; acyclicIndex < acyclic.size(); acyclicIndex++) {
            final int current = acyclic.get(acyclicIndex);
            for (final Link link : index.outgoing().get(current)) {
                if (link.to() != ApplicationPlan.END && --remaining[link.to()] == 0) {
                    acyclic.add(link.to());
                }
            }
        }
        if (acyclic.size() != nodes.size()) {
            Node cycle = null;
            for (final Node node : nodes) {
                if (remaining[node.index()] > 0) {
                    cycle = node;
                    break;
                }
            }
            return Optional.of(Diagnostic.atPath(
                    "PROJECT_GRAPH_CYCLE",
                    "Flow control links must not contain a cycle.",
                    "nodes[" + cycle.index() + "]"
            ));
        }
        for (final Node current : nodes) {
            if (current.definition().kind() == StepDefinition.Kind.APP) {
                continue;
            }
            if (index.incoming()[current.index()] != 1) {
                return Optional.of(Diagnostic.atPath(
                        "PROJECT_NODE_INPUT_CONNECTION_REQUIRED",
                        "Step must have exactly one incoming connection: " + current.id() + ".",
                        "nodes[" + current.index() + "]"
                ));
            }
        }
        return Optional.empty();
    }

    private static int[] owners(final GraphIndex index) {
        final int[] owners = new int[index.nodes().size()];
        Arrays.fill(owners, ApplicationPlan.END);
        final List<Integer> pending = new ArrayList<>();
        for (final Link link : index.outgoing().get(index.app())) {
            if (link.to() != ApplicationPlan.END) {
                owners[link.to()] = link.to();
                pending.add(link.to());
            }
        }
        for (int pendingIndex = 0; pendingIndex < pending.size(); pendingIndex++) {
            final int current = pending.get(pendingIndex);
            for (final Link link : index.outgoing().get(current)) {
                if (link.to() != ApplicationPlan.END) {
                    owners[link.to()] = owners[current];
                    pending.add(link.to());
                }
            }
        }
        return owners;
    }

    private static ApplicationPlan.NodePlan plan(
            final Node node,
            final int[] destinations,
            final int owner
    ) {
        return new ApplicationPlan.NodePlan(
                node.id(),
                ApplicationPlan.ExecutableStep.from(node.definition()),
                node.bindings(),
                node.receives(),
                node.returns(),
                destinations,
                node.outcomes(),
                owner,
                "nodes[" + node.index() + "]"
        );
    }

    private static boolean validApp(final StepDefinition definition) {
        return "railix.app".equals(definition.id())
                && "start".equals(definition.primaryOutcome())
                && definition.outcomes().size() == 1
                && definition.receives().isEmpty()
                && definition.returns().isEmpty()
                && definition.inputs().isEmpty()
                && !definition.executable()
                && definition.source().isEmpty()
                && definition.results().isEmpty();
    }

    private static boolean validTrigger(final StepDefinition definition) {
        return definition.outcomes().size() == 1
                && definition.returns().isEmpty()
                && definition.executable()
                && definition.source().isPresent();
    }

    private static boolean validStep(final StepDefinition definition) {
        return definition.kind() == StepDefinition.Kind.STEP
                && definition.executable()
                && definition.source().isEmpty()
                && definition.results().isEmpty();
    }

    private static boolean validNestedStep(final StepDefinition definition) {
        return definition.kind() == StepDefinition.Kind.STEP
                && definition.receives().size() == 1
                && definition.returns().size() == 1
                && definition.executable()
                && definition.source().isEmpty()
                && definition.results().isEmpty();
    }

    private static List<String> outcomes(
            final StepDefinition definition,
            final Map<String, ApplicationPlan.Binding> bindings
    ) {
        final List<String> outcomes = new ArrayList<>(definition.outcomes());
        propagated(bindings).forEach(outcomes::add);
        return List.copyOf(outcomes);
    }

    private static List<String> propagated(final Map<String, ApplicationPlan.Binding> bindings) {
        final List<String> outcomes = new ArrayList<>();
        bindings.values().forEach(binding -> {
            if (binding instanceof ApplicationPlan.StepsBinding steps && steps.propagatesOutcomes()) {
                steps.steps().forEach(step -> step.step().outcomes().stream()
                        .filter(outcome -> !step.step().primaryOutcome().equals(outcome))
                        .filter(outcome -> !outcomes.contains(outcome))
                        .forEach(outcomes::add));
            } else if (binding instanceof ApplicationPlan.ChoiceBinding choice) {
                propagated(choice.inputs()).stream()
                        .filter(outcome -> !outcomes.contains(outcome))
                        .forEach(outcomes::add);
            } else if (binding instanceof ApplicationPlan.CandidatesBinding candidates) {
                candidates.candidates().forEach(candidate -> propagated(candidate.source().inputs()).stream()
                        .filter(outcome -> !outcomes.contains(outcome))
                        .forEach(outcomes::add));
            } else if (binding instanceof ApplicationPlan.MatcherGroupsBinding matcherGroups) {
                matcherGroups.groups().forEach(group -> group.forEach(matcher ->
                        propagated(matcher.source().inputs()).stream()
                                .filter(outcome -> !outcomes.contains(outcome))
                                .forEach(outcomes::add)
                ));
            }
        });
        return outcomes;
    }

    private static PathRead path(final RailixValue value, final String path, final boolean write) {
        if (!(value instanceof RailixValue.ArrayValue array) || array.values().size() < 2) {
            return PathRead.rejected(
                    "PROJECT_CONTEXT_PATH_REQUIRED",
                    "Path must start with context and contain at least one field.",
                    path
            );
        }
        if (array.values().size() > RailixData.DEFAULT_MAX_DEPTH) {
            return PathRead.rejected(
                    "PROJECT_CONTEXT_PATH_TOO_DEEP",
                    "Path must not exceed " + RailixData.DEFAULT_MAX_DEPTH + " elements.",
                    path
            );
        }
        final List<ApplicationPlan.PathElement> elements = new ArrayList<>();
        for (int index = 0; index < array.values().size(); index++) {
            final RailixValue element = array.values().get(index);
            if (element instanceof RailixValue.StringValue string && !string.value().isBlank()) {
                elements.add(new ApplicationPlan.Field(string.value()));
            } else if (element instanceof RailixValue.NumberValue number) {
                try {
                    final int arrayIndex = number.value().intValueExact();
                    if (arrayIndex < 0) {
                        return invalidPathElement(path, index);
                    }
                    elements.add(new ApplicationPlan.Index(arrayIndex));
                } catch (final ArithmeticException exception) {
                    return invalidPathElement(path, index);
                }
            } else {
                return invalidPathElement(path, index);
            }
        }
        if (!(elements.getFirst() instanceof ApplicationPlan.Field root)
                || !"context".equals(root.name())) {
            return PathRead.rejected(
                    "PROJECT_CONTEXT_PATH_ROOT_REQUIRED",
                    "Path must start with context.",
                    path + "[0]"
            );
        }
        if (write
                && elements.get(1) instanceof ApplicationPlan.Field field
                && "runtime".equals(field.name())) {
            return PathRead.rejected(
                    "PROJECT_RUNTIME_READ_ONLY",
                    "context.runtime is read-only.",
                    path
            );
        }
        return new PathRead(new ApplicationPlan.Path(elements), List.of());
    }

    private static PathRead invalidPathElement(final String path, final int index) {
        return PathRead.rejected(
                "PROJECT_CONTEXT_PATH_ELEMENT_INVALID",
                "Path elements must be non-blank strings or non-negative integers.",
                path + "[" + index + "]"
        );
    }

    private static Optional<Diagnostic> unknown(
            final RailixValue.ObjectValue object,
            final Set<String> fields,
            final String code,
            final String message,
            final String path
    ) {
        for (final String field : object.values().keySet()) {
            if (!fields.contains(field)) {
                return Optional.of(Diagnostic.atPath(code, message + field + ".", child(path, field)));
            }
        }
        return Optional.empty();
    }

    private static TextRead text(
            final RailixValue.ObjectValue object,
            final String field,
            final String code,
            final String message,
            final String path
    ) {
        final RailixValue value = object.values().get(field);
        if (!(value instanceof RailixValue.StringValue string) || string.value().isBlank()) {
            return new TextRead("", List.of(Diagnostic.atPath(code, message, path)));
        }
        return new TextRead(string.value(), List.of());
    }

    private static ObjectRead object(
            final RailixValue.ObjectValue object,
            final String field,
            final String code,
            final String message,
            final String path
    ) {
        final RailixValue value = object.values().get(field);
        if (!(value instanceof RailixValue.ObjectValue child)) {
            return new ObjectRead(
                    RailixValue.object(Map.of()),
                    List.of(Diagnostic.atPath(code, message, path))
            );
        }
        return new ObjectRead(child, List.of());
    }

    private static ArrayRead array(
            final RailixValue.ObjectValue object,
            final String field,
            final String code,
            final String message,
            final String path
    ) {
        final RailixValue value = object.values().get(field);
        if (!(value instanceof RailixValue.ArrayValue child)) {
            return new ArrayRead(RailixValue.array(List.of()), List.of(Diagnostic.atPath(code, message, path)));
        }
        return new ArrayRead(child, List.of());
    }

    private static EndpointRead endpoint(final String value, final String path) {
        final int separator = value.lastIndexOf('.');
        if (separator < 1 || separator == value.length() - 1) {
            return EndpointRead.rejected(
                    "PROJECT_LINK_FROM_INVALID",
                    "Link from must use node.outcome.",
                    path
            );
        }
        return new EndpointRead(new Endpoint(value.substring(0, separator), value.substring(separator + 1)), List.of());
    }

    private static boolean safeId(final String value) {
        if (value.length() > 64 || value.isEmpty() || value.charAt(0) < 'a' || value.charAt(0) > 'z') {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            final char character = value.charAt(index);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String child(final String path, final String field) {
        return path.isEmpty() ? field : path + "." + field;
    }

    private static CompileResult.Rejected rejected(final String code, final String message, final String path) {
        return new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path)));
    }

    private static CompileResult.Rejected rejected(final Diagnostic diagnostic) {
        return new CompileResult.Rejected(List.of(diagnostic));
    }

    private record Node(
            String id,
            StepDefinition definition,
            Map<String, ApplicationPlan.Binding> bindings,
            Map<String, ApplicationPlan.Path> receives,
            Map<String, ApplicationPlan.Path> returns,
            List<String> outcomes,
            int index
    ) {
        Node {
            bindings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
            receives = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(receives));
            returns = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(returns));
            outcomes = List.copyOf(outcomes);
        }
    }

    private record Endpoint(String node, String outcome) {
    }

    private record Link(int from, int outcome, int to, int index) {
    }

    private record GraphIndex(
            List<Node> nodes,
            List<Link> links,
            List<List<Link>> outgoing,
            int[] incoming,
            int[][] connections,
            int[][] destinations,
            int app
    ) {
        static GraphIndex empty() {
            return new GraphIndex(
                    List.of(),
                    List.of(),
                    List.of(),
                    new int[0],
                    new int[0][],
                    new int[0][],
                    ApplicationPlan.END
            );
        }

        int destination(final int node, final String outcome) {
            return destinations[node][nodes.get(node).outcomes().indexOf(outcome)];
        }
    }

    private record NodeRead(List<Node> nodes, List<Diagnostic> diagnostics) {
        static NodeRead rejected(final String code, final String message, final String path) {
            return new NodeRead(List.of(), List.of(Diagnostic.atPath(code, message, path)));
        }
    }

    private record InputsRead(
            Map<String, ApplicationPlan.Binding> bindings,
            List<Diagnostic> diagnostics
    ) {
        static InputsRead rejected(final String code, final String message, final String path) {
            return new InputsRead(Map.of(), List.of(Diagnostic.atPath(code, message, path)));
        }
    }

    private record PortRead(
            Map<String, ApplicationPlan.Path> paths,
            List<Diagnostic> diagnostics
    ) {
        static PortRead rejected(final String code, final String message, final String path) {
            return new PortRead(Map.of(), List.of(Diagnostic.atPath(code, message, path)));
        }
    }

    private record InputRead(
            ApplicationPlan.Binding binding,
            List<Diagnostic> diagnostics
    ) {
        static InputRead value(final ApplicationPlan.Binding binding) {
            return new InputRead(binding, List.of());
        }

        static InputRead empty() {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), List.of());
        }

        static InputRead rejected(final String code, final String message, final String path) {
            return new InputRead(
                    new ApplicationPlan.JsonBinding(List.of()),
                    List.of(Diagnostic.atPath(code, message, path))
            );
        }

        static InputRead rejected(final Diagnostic diagnostic) {
            return new InputRead(new ApplicationPlan.JsonBinding(List.of()), List.of(diagnostic));
        }
    }

    private record CandidateRead(
            ApplicationPlan.CandidatePlan plan,
            List<Diagnostic> diagnostics
    ) {
        static CandidateRead rejected(final String code, final String message, final String path) {
            return rejected(List.of(Diagnostic.atPath(code, message, path)));
        }

        static CandidateRead rejected(final Diagnostic diagnostic) {
            return rejected(List.of(diagnostic));
        }

        static CandidateRead rejected(final List<Diagnostic> diagnostics) {
            return new CandidateRead(
                    new ApplicationPlan.CandidatePlan(
                            new ApplicationPlan.ChoiceBinding("rejected", Map.of(), List.of()),
                            List.of(),
                            List.of()
                    ),
                    List.copyOf(diagnostics)
            );
        }
    }

    private record ConditionRead(
            List<ApplicationPlan.NestedStepPlan> transforms,
            List<List<ApplicationPlan.NestedStepPlan>> predicates,
            List<Diagnostic> diagnostics
    ) {
        ConditionRead {
            transforms = List.copyOf(transforms);
            predicates = predicates.stream().map(List::copyOf).toList();
            diagnostics = List.copyOf(diagnostics);
        }

        static ConditionRead empty() {
            return new ConditionRead(List.of(), List.of(), List.of());
        }

        static ConditionRead rejected(final String code, final String message, final String path) {
            return rejected(Diagnostic.atPath(code, message, path));
        }

        static ConditionRead rejected(final Diagnostic diagnostic) {
            return rejected(List.of(diagnostic));
        }

        static ConditionRead rejected(final List<Diagnostic> diagnostics) {
            return new ConditionRead(List.of(), List.of(), diagnostics);
        }
    }

    private record NestedRead(
            List<ApplicationPlan.NestedStepPlan> plans,
            List<Diagnostic> diagnostics
    ) {
        static NestedRead rejected(final String code, final String message, final String path) {
            return new NestedRead(List.of(), List.of(Diagnostic.atPath(code, message, path)));
        }

        static NestedRead rejected(final Diagnostic diagnostic) {
            return new NestedRead(List.of(), List.of(diagnostic));
        }
    }

    private record LinkRead(GraphIndex index, List<Diagnostic> diagnostics) {
        static LinkRead rejected(final String code, final String message, final String path) {
            return rejected(List.of(Diagnostic.atPath(code, message, path)));
        }

        static LinkRead rejected(final Diagnostic diagnostic) {
            return rejected(List.of(diagnostic));
        }

        static LinkRead rejected(final List<Diagnostic> diagnostics) {
            return new LinkRead(GraphIndex.empty(), List.copyOf(diagnostics));
        }
    }

    private record TextRead(String value, List<Diagnostic> diagnostics) {
    }

    private record ObjectRead(RailixValue.ObjectValue value, List<Diagnostic> diagnostics) {
    }

    private record ArrayRead(RailixValue.ArrayValue value, List<Diagnostic> diagnostics) {
    }

    private record PathRead(ApplicationPlan.Path value, List<Diagnostic> diagnostics) {
        static PathRead rejected(final String code, final String message, final String path) {
            return new PathRead(
                    new ApplicationPlan.Path(List.of()),
                    List.of(Diagnostic.atPath(code, message, path))
            );
        }
    }

    private record EndpointRead(Endpoint value, List<Diagnostic> diagnostics) {
        static EndpointRead rejected(final String code, final String message, final String path) {
            return new EndpointRead(new Endpoint("", ""), List.of(Diagnostic.atPath(code, message, path)));
        }
    }
}
