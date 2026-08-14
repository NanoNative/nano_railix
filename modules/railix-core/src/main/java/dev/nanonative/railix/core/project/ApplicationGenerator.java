package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically lowers one validated executable plan to plain Java source. */
final class ApplicationGenerator {
    static final String APPLICATION_CLASS = "dev.nanonative.railix.core.project.RailixApplication";
    static final String DEVELOPMENT_LAUNCHER_CLASS =
            "dev.nanonative.railix.core.project.RailixDevelopmentApplication";
    private static final int PLAN_PARTITION_SIZE = 16;
    private static final int ROUTE_PARTITION_SIZE = 128;
    private static final int MAX_APPLICATION_NODES = 16_384;
    private static final int MAX_APPLICATION_TRIGGERS = 512;
    private static final int MAX_PLAN_SOURCE_CHARACTERS = 32_768;

    private ApplicationGenerator() {
    }

    static Result generate(
            final ApplicationPlan plan,
            final StepCatalog catalog
    ) {
        final List<ApplicationPlan.NodePlan> nodes = plan.nodes();
        final List<ApplicationPlan.TriggerPlan> triggers = plan.triggers();
        if (triggers.size() > MAX_APPLICATION_TRIGGERS) {
            return new Result("", "", "", List.of(Diagnostic.atPath(
                    "PROJECT_APPLICATION_TRIGGER_LIMIT",
                    "Generated applications support at most " + MAX_APPLICATION_TRIGGERS + " Triggers.",
                    "nodes"
            )), List.of());
        }
        final Map<String, StepCatalog.Implementation> implementations = new LinkedHashMap<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        for (final ApplicationPlan.NodePlan node : nodes) {
            collect(node.step(), node.path() + ".use", catalog, implementations, diagnostics);
            collect(node.inputs(), catalog, implementations, diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return new Result("", "", "", diagnostics, List.of());
        }
        if (nodes.size() > MAX_APPLICATION_NODES) {
            return new Result("", "", "", List.of(Diagnostic.atPath(
                    "PROJECT_APPLICATION_NODE_LIMIT",
                    "Generated applications support at most " + MAX_APPLICATION_NODES + " nodes.",
                    "nodes"
            )), List.of());
        }
        final Map<String, Integer> handlerIndexes = handlerIndexes(implementations);
        for (int index = 0; index < nodes.size(); index++) {
            final ApplicationPlan.NodePlan node = nodes.get(index);
            if (node.step().kind() != StepDefinition.Kind.APP
                    && compiledNode(index, node, handlerIndexes).length() > MAX_PLAN_SOURCE_CHARACTERS) {
                return new Result("", "", "", List.of(Diagnostic.atPath(
                        "PROJECT_APPLICATION_STEP_LIMIT",
                        "One compiled Step exceeds the " + MAX_PLAN_SOURCE_CHARACTERS
                                + "-character generated-code limit.",
                        node.path()
                )), List.of());
            }
        }
        return new Result(
                source(plan.projectId(), nodes, triggers, implementations, handlerIndexes, Variant.PRODUCTION),
                source(plan.projectId(), nodes, triggers, implementations, handlerIndexes, Variant.DEVELOPMENT),
                developmentLauncherSource(),
                List.of(),
                implementations.values().stream().distinct().toList()
        );
    }

    private static void collect(
            final ApplicationPlan.ExecutableStep step,
            final String path,
            final StepCatalog catalog,
            final Map<String, StepCatalog.Implementation> implementations,
            final List<Diagnostic> diagnostics
    ) {
        if (step.kind() == StepDefinition.Kind.APP || implementations.containsKey(step.use())) {
            return;
        }
        final StepCatalog.Implementation implementation = catalog.implementation(step.use()).orElse(null);
        if (implementation == null) {
            diagnostics.add(Diagnostic.atPath(
                    "PROJECT_STEP_IMPLEMENTATION_ADDRESS_REQUIRED",
                    "Step must declare a named Java handler class for application generation: "
                            + step.use() + ".",
                    path
            ));
        } else {
            implementations.put(step.use(), implementation);
        }
    }

    private static void collect(
            final Map<String, ApplicationPlan.Binding> bindings,
            final StepCatalog catalog,
            final Map<String, StepCatalog.Implementation> implementations,
            final List<Diagnostic> diagnostics
    ) {
        for (final ApplicationPlan.Binding binding : bindings.values()) {
            switch (binding) {
                case ApplicationPlan.JsonBinding ignored -> {
                }
                case ApplicationPlan.PathBinding ignored -> {
                }
                case ApplicationPlan.ChoiceBinding choice ->
                        collect(choice.inputs(), catalog, implementations, diagnostics);
                case ApplicationPlan.CandidatesBinding candidates -> candidates.candidates().forEach(candidate -> {
                    collect(candidate.source().inputs(), catalog, implementations, diagnostics);
                    collect(candidate.transforms(), catalog, implementations, diagnostics);
                    candidate.predicates().forEach(program ->
                            collect(program, catalog, implementations, diagnostics)
                    );
                });
                case ApplicationPlan.MatcherGroupsBinding groups -> groups.groups().forEach(group ->
                        group.forEach(candidate -> {
                            collect(candidate.source().inputs(), catalog, implementations, diagnostics);
                            collect(candidate.transforms(), catalog, implementations, diagnostics);
                            candidate.predicates().forEach(program ->
                                    collect(program, catalog, implementations, diagnostics)
                            );
                        })
                );
                case ApplicationPlan.StepsBinding steps ->
                        collect(steps.steps(), catalog, implementations, diagnostics);
            }
        }
    }

    private static void collect(
            final List<ApplicationPlan.NestedStepPlan> steps,
            final StepCatalog catalog,
            final Map<String, StepCatalog.Implementation> implementations,
            final List<Diagnostic> diagnostics
    ) {
        for (final ApplicationPlan.NestedStepPlan step : steps) {
            collect(step.step(), step.path() + ".use", catalog, implementations, diagnostics);
            collect(step.inputs(), catalog, implementations, diagnostics);
        }
    }

    private static String source(
            final String projectId,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Map<String, StepCatalog.Implementation> implementations,
            final Map<String, Integer> handlerIndexes,
            final Variant variant
    ) {
        final StringBuilder source = new StringBuilder(Math.max(16_384, nodes.size() * 512));
        source.append("""
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.step.StepInput;
                import dev.nanonative.railix.core.step.StepResult;
                import dev.nanonative.railix.core.value.RailixJson;
                import dev.nanonative.railix.core.value.RailixValue;
                import dev.nanonative.railix.core.value.ValueRefinement;
                import dev.nanonative.railix.core.value.ValueShape;
                import java.math.BigDecimal;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                """);
        if (variant == Variant.DEVELOPMENT) {
            source.append("import dev.nanonative.railix.development.DevelopmentRuntime;\n");
        }
        source.append("\n/** Executable application generated from one validated Railix project. */\n")
                .append("public final class RailixApplication implements ")
                .append(variant == Variant.PRODUCTION
                        ? "RuntimeApplication"
                        : "DevelopmentRuntime.Application")
                .append(" {\n")
                .append("    private static final int END = -1;\n")
                .append("    private static final int UNROUTED = -2;\n")
                .append("    private static final int NODE_PARTITION_SIZE =\n")
                .append(indent(Integer.toString(ROUTE_PARTITION_SIZE), 3)).append(";\n")
                .append("    private static final String PROJECT_ID =\n")
                .append(indent(quote(projectId), 3)).append(";\n");
        int handlerIndex = 0;
        for (final StepCatalog.Implementation implementation : implementations.values()) {
            source.append("    private static final ").append(implementation.className())
                    .append(" HANDLER_").append(handlerIndex).append(" = new ")
                    .append(implementation.className()).append("();\n");
            handlerIndex++;
        }
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            source.append("    private static final List<WorkflowRuntime.ResultPlan> RESULTS_")
                    .append(trigger.node()).append(" = ").append(results(trigger)).append(";\n")
                    .append("    private static final Map<String, String> RESPONSE_SLOTS_")
                    .append(trigger.node()).append(" = ")
                    .append(stringMap(node.step().responses())).append(";\n");
        }
        source.append("    private static final RailixApplication APPLICATION = new RailixApplication();\n\n");
        handlerIndex = 0;
        for (final String ignored : implementations.keySet()) {
            source.append("    private static final WorkflowRuntime.StepCall CALL_").append(handlerIndex)
                    .append(" = RailixApplication::handler_").append(handlerIndex).append(";\n");
            source.append("    private static StepResult handler_").append(handlerIndex)
                    .append("(final StepInput input) throws InterruptedException {\n")
                    .append("        return HANDLER_").append(handlerIndex).append(".run(input);\n")
                    .append("    }\n\n");
            handlerIndex++;
        }
        appendPlans(source, nodes, handlerIndexes);
        source.append("    private RailixApplication() {\n    }\n\n")
                .append("    static ")
                .append(variant == Variant.PRODUCTION
                        ? "RuntimeApplication"
                        : "DevelopmentRuntime.Application")
                .append(" runtime() {\n        return APPLICATION;\n    }\n\n")
                .append("    public static void main(final String[] arguments) {\n")
                .append("        final int status = runCli(arguments);\n")
                .append("        if (status != 0) {\n            System.exit(status);\n        }\n    }\n\n")
                .append("    @Override\n")
                .append("    public String projectId() {\n        return PROJECT_ID;\n    }\n\n");
        if (variant == Variant.DEVELOPMENT) {
            appendDevelopmentRun(source, nodes, triggers);
            appendObservation(source, nodes, triggers);
        }
        appendSources(source, nodes, triggers, handlerIndexes, variant);
        appendExecutors(source, nodes, triggers, handlerIndexes, variant);
        appendDispatch(source, nodes, handlerIndexes, variant);
        if (variant == Variant.DEVELOPMENT) {
            appendObservationCapture(source);
        }
        source.append("    static int runCli(final String[] arguments) {\n")
                .append("        final List<RailixValue> values = new ArrayList<>(arguments.length);\n")
                .append("        for (final String argument : arguments) {\n")
                .append("            values.add(RailixValue.string(argument));\n        }\n")
                .append("        final WorkflowRuntime.SourceResult source = APPLICATION.runSource(\n")
                .append("                \"application.arguments\",\n")
                .append("                Map.of(\"arguments\", RailixValue.array(values))\n        );\n")
                .append("        return switch (source.result()) {\n")
                .append("            case RunResult.Succeeded ignored -> cliSuccess(source.responses());\n")
                .append("            case RunResult.Rejected rejected -> {\n")
                .append("                rejected.diagnostics().forEach(diagnostic -> System.err.println(\n")
                .append("                        diagnostic.code() + \" \" + diagnostic.path() + \" \" + diagnostic.message()\n")
                .append("                ));\n                yield 2;\n            }\n")
                .append("            case RunResult.Failed failed -> {\n")
                .append("                System.err.println(failed.failure().code() + \" \" + failed.failure().stepId()\n")
                .append("                        + \" \" + failed.failure().message());\n")
                .append("                yield 1;\n            }\n")
                .append("            case RunResult.Cancelled ignored -> 130;\n        };\n    }\n\n")
                .append("    private static int cliSuccess(final Map<String, RailixValue> responses) {\n")
                .append("        if (!(responses.get(\"status\") instanceof RailixValue.NumberValue status)) {\n")
                .append("            System.err.println(\"CLI exit code must be a number.\");\n")
                .append("            return 2;\n        }\n        try {\n")
                .append("            final int code = status.value().intValueExact();\n")
                .append("            if (code < 0 || code > 255) {\n")
                .append("                System.err.println(\"CLI exit code must be from 0 through 255.\");\n")
                .append("                return 2;\n            }\n")
                .append("            final RailixValue output = responses.get(\"output\");\n")
                .append("            if (!(output instanceof RailixValue.NullValue)) {\n")
                .append("                System.out.println(RailixJson.write(output));\n            }\n")
                .append("            return code;\n")
                .append("        } catch (final ArithmeticException exception) {\n")
                .append("            System.err.println(\"CLI exit code must be an integer.\");\n")
                .append("            return 2;\n        }\n    }\n\n")
                .append("    @SafeVarargs\n")
                .append("    private static <K, V> Map<K, V> map(final Map.Entry<K, V>... entries) {\n")
                .append("        final Map<K, V> values = new LinkedHashMap<>();\n")
                .append("        for (final Map.Entry<K, V> entry : entries) {\n")
                .append("            values.put(entry.getKey(), entry.getValue());\n        }\n")
                .append("        return Collections.unmodifiableMap(values);\n    }\n\n")
                .append("    private static <K, V> Map.Entry<K, V> entry(final K key, final V value) {\n")
                .append("        return Map.entry(key, value);\n    }\n")
                .append("}\n");
        return source.toString();
    }

    private static String developmentLauncherSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.development.DevelopmentRuntime;

                /** Optional local-development launcher for one generated Railix application. */
                public final class RailixDevelopmentApplication {
                    private RailixDevelopmentApplication() {
                    }

                    public static void main(final String[] arguments) {
                        final int status = DevelopmentRuntime.run(RailixApplication.runtime());
                        if (status != 0) {
                            System.exit(status);
                        }
                    }
                }
                """;
    }

    private static void appendPlans(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final Map<String, Integer> handlers
    ) {
        for (int start = 0; start < nodes.size(); start += PLAN_PARTITION_SIZE) {
            final int end = Math.min(nodes.size(), start + PLAN_PARTITION_SIZE);
            source.append("    private static final class Plans_")
                    .append(start / PLAN_PARTITION_SIZE).append(" {\n");
            for (int index = start; index < end; index++) {
                final ApplicationPlan.NodePlan node = nodes.get(index);
                if (node.step().kind() == StepDefinition.Kind.APP) {
                    continue;
                }
                source.append(indent(compiledNode(index, node, handlers), 2));
            }
            source.append("\n        private Plans_").append(start / PLAN_PARTITION_SIZE)
                    .append("() {\n        }\n")
                    .append("    }\n\n");
        }
    }

    private static void appendDevelopmentRun(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers
    ) {
        source.append("    @Override\n")
                .append("    public RunResult run(final String triggerId,\n")
                .append("            final RailixValue.ObjectValue context, final boolean test) {\n")
                .append("        if (triggerId == null || triggerId.isBlank()) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_REQUIRED\", ")
                .append("\"Trigger id must be a non-blank string.\", \"trigger\", List.of());\n        }\n")
                .append("        if (context == null) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_INPUT_REQUIRED\", ")
                .append("\"Workflow context must be supplied.\", \"input\", List.of());\n        }\n")
                .append("        return switch (triggerId) {\n");
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            source.append("            case ").append(quote(nodes.get(trigger.node()).id()))
                    .append(" -> run_").append(trigger.node()).append("(context, test);\n");
        }
        source.append("            default -> WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_UNKNOWN\",\n")
                .append("                    \"Trigger is not part of this project: \" + triggerId + \".\",\n")
                .append("                    \"trigger\", List.of());\n")
                .append("        };\n    }\n\n");
    }

    private static void appendObservation(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers
    ) {
        source.append("    @Override\n")
                .append("    public DevelopmentRuntime.Observation observe(final String triggerId, final String stepId,\n")
                .append("            final RailixValue.ObjectValue context, final boolean test) {\n")
                .append("        if (stepId == null || stepId.isBlank()) {\n")
                .append("            return observationError(WorkflowRuntime.rejectedResult(\"PREVIEW_STEP_REQUIRED\", ")
                .append("\"Step id must be a non-blank string.\", \"step\", List.of()), \"\");\n        }\n")
                .append("        if (triggerId == null || triggerId.isBlank()) {\n")
                .append("            return observationError(WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_REQUIRED\", ")
                .append("\"Trigger id must be a non-blank string.\", \"trigger\", List.of()), stepId);\n        }\n")
                .append("        if (context == null) {\n")
                .append("            return observationError(WorkflowRuntime.rejectedResult(\"RUN_INPUT_REQUIRED\", ")
                .append("\"Workflow context must be supplied.\", \"input\", List.of()), stepId);\n        }\n")
                .append("        return switch (triggerId) {\n");
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            source.append("            case ").append(quote(nodes.get(trigger.node()).id()))
                    .append(" -> observe_").append(trigger.node()).append("(stepId, context, test);\n");
        }
        source.append("            default -> observationError(WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_UNKNOWN\",\n")
                .append("                    \"Trigger is not part of this project: \" + triggerId + \".\",\n")
                .append("                    \"trigger\", List.of()), stepId);\n")
                .append("        };\n    }\n\n");

        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            source.append("    private static DevelopmentRuntime.Observation observe_").append(trigger.node())
                    .append("(final String stepId, final RailixValue.ObjectValue context, final boolean test) {\n")
                    .append("        final int selected = select_").append(trigger.node()).append("(stepId);\n")
                    .append("        if (selected == END) {\n")
                    .append("            return observationError(WorkflowRuntime.rejectedResult(\"PREVIEW_STEP_UNKNOWN\",\n")
                    .append("                    \"Step is not part of the selected Trigger branch: \" + stepId + \".\",\n")
                    .append("                    \"step\", List.of()), stepId);\n        }\n")
                    .append("        if (context.values().containsKey(\"runtime\")) {\n")
                    .append("            return observationError(WorkflowRuntime.rejectedResult(\"RUN_RUNTIME_RESERVED\",\n")
                    .append("                    \"context.runtime is supplied by Railix.\", ")
                    .append("\"context.runtime\", List.of()), stepId);\n        }\n")
                    .append("        final WorkflowRuntime.Execution execution = WorkflowRuntime.execution(")
                    .append(quote(nodes.get(trigger.node()).id())).append(", ")
                    .append(resultsReference(trigger)).append(", context, test, true);\n")
                    .append("        final ObservationCapture capture = new ObservationCapture(stepId);\n")
                    .append("        final RunResult result = execute_").append(trigger.node())
                    .append("(execution, ").append(trigger.start()).append(", selected, capture);\n")
                    .append("        if (result instanceof RunResult.Succeeded && !capture.reached()) {\n")
                    .append("            return capture.observation(WorkflowRuntime.rejectedResult(\n")
                    .append("                    \"PREVIEW_STEP_UNREACHED\",\n")
                    .append("                    \"Step was not reached by this flow execution: \" + stepId + \".\",\n")
                    .append("                    \"step\", execution.history()));\n        }\n")
                    .append("        return capture.observation(result);\n")
                    .append("    }\n\n");
        }
    }

    private static void appendObservationCapture(final StringBuilder source) {
        source.append("    private static DevelopmentRuntime.Observation observationError(\n")
                .append("            final RunResult result, final String step) {\n")
                .append("        return new DevelopmentRuntime.Observation(result, step,\n")
                .append("                RailixValue.object(Map.of()), Map.of(), List.of(), Map.of());\n")
                .append("    }\n\n")
                .append("    private static final class ObservationCapture implements WorkflowRuntime.Capture {\n")
                .append("        private final String step;\n")
                .append("        private RailixValue.ObjectValue inputContext = RailixValue.object(Map.of());\n")
                .append("        private Map<String, RailixValue> inputs = Map.of();\n")
                .append("        private final List<DevelopmentRuntime.Stage> stages = new ArrayList<>();\n")
                .append("        private final Map<String, Integer> selectedCandidates = new LinkedHashMap<>();\n")
                .append("        private boolean reached;\n\n")
                .append("        private ObservationCapture(final String step) {\n")
                .append("            this.step = step;\n        }\n\n")
                .append("        @Override\n")
                .append("        public void inputContext(final RailixValue.ObjectValue value) {\n")
                .append("            inputContext = value;\n            reached = true;\n        }\n\n")
                .append("        @Override\n")
                .append("        public void inputs(final Map<String, RailixValue> values) {\n")
                .append("            inputs = values;\n")
                .append("        }\n\n")
                .append("        @Override\n")
                .append("        public void stage(final String input, final String invocation, final String use,\n")
                .append("                final String status, final List<RailixValue> values) {\n")
                .append("            stages.add(new DevelopmentRuntime.Stage(input, invocation, use, status, values));\n")
                .append("        }\n\n")
                .append("        @Override\n")
                .append("        public void selectedCandidate(final String path, final int index) {\n")
                .append("            selectedCandidates.put(path, index);\n        }\n\n")
                .append("        private boolean reached() {\n            return reached;\n        }\n\n")
                .append("        private DevelopmentRuntime.Observation observation(final RunResult result) {\n")
                .append("            return new DevelopmentRuntime.Observation(\n")
                .append("                    result, step, inputContext, inputs, stages, selectedCandidates\n")
                .append("            );\n        }\n")
                .append("    }\n\n");
    }

    private static void appendSources(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Map<String, Integer> handlers,
            final Variant variant
    ) {
        source.append("    @Override\n")
                .append("    public WorkflowRuntime.SourceResult runSource(final String source,\n")
                .append("            final Map<String, RailixValue> values) {\n")
                .append("        if (source == null || source.isBlank()) {\n")
                .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\n")
                .append("                    \"RUN_SOURCE_REQUIRED\", \"Trigger source must be a non-blank string.\",\n")
                .append("                    \"source\", List.of()), Map.of());\n        }\n")
                .append("        if (values == null) {\n")
                .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\n")
                .append("                    \"RUN_SOURCE_VALUES_REQUIRED\", \"Trigger source values must be supplied.\",\n")
                .append("                    \"values\", List.of()), Map.of());\n        }\n")
                .append("        return switch (source) {\n");
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            source.append("            case ").append(quote(node.step().source()))
                    .append(" -> source_").append(trigger.node()).append("(values);\n");
        }
        source.append("            default -> new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\n")
                .append("                    \"RUN_SOURCE_UNKNOWN\", \"Project has no Trigger for source: \" + source + \".\",\n")
                .append("                    \"source\", List.of()), Map.of());\n")
                .append("        };\n    }\n\n");

        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            final int handler = handlers.get(node.step().use());
            source.append("    private static WorkflowRuntime.SourceResult source_").append(trigger.node())
                    .append("(final Map<String, RailixValue> values) {\n")
                    .append("        final var invalid = WorkflowRuntime.validateSource(")
                    .append(planReference(trigger.node())).append(", values, ")
                    .append(quote(trigger.path())).append(", List.of());\n")
                    .append("        if (invalid.isPresent()) {\n")
                    .append("            return new WorkflowRuntime.SourceResult(invalid.orElseThrow(), Map.of());\n")
                    .append("        }\n")
                    .append("        final WorkflowRuntime.Execution execution = WorkflowRuntime.execution(")
                    .append(quote(node.id())).append(", ").append(resultsReference(trigger)).append(",\n")
                    .append("                RailixValue.object(Map.of()), false, false);\n")
                    .append("        final int outcome = execution.call(")
                    .append(planReference(trigger.node())).append(", CALL_")
                    .append(handler).append(", values, ").append(inputsReference(trigger.node())).append(");\n")
                    .append("        if (outcome < 0) {\n")
                    .append("            return new WorkflowRuntime.SourceResult(execution.finish(), Map.of());\n        }\n")
                    .append("        final int destination = ").append(destination(node)).append(";\n")
                    .append("        if (destination == UNROUTED) {\n")
                    .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.failedResult(\n")
                    .append("                    \"STEP_OUTCOME_UNROUTED\", \"Trigger returned an outcome without a connection: \"\n")
                    .append("                            + ").append(outcome(node)).append(" + \".\", ")
                    .append(quote(node.id())).append(", execution.history()), Map.of());\n        }\n")
                    .append("        final RunResult result = execute_").append(trigger.node())
                    .append(variant == Variant.PRODUCTION
                            ? "(execution, destination);\n"
                            : "(execution, destination, END, null);\n")
                    .append("        return result instanceof RunResult.Succeeded\n")
                    .append("                ? new WorkflowRuntime.SourceResult(result, execution.responses(")
                    .append(responseSlotsReference(trigger)).append("))\n")
                    .append("                : new WorkflowRuntime.SourceResult(result, Map.of());\n")
                    .append("    }\n\n");
        }
    }

    private static void appendExecutors(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Map<String, Integer> handlers,
            final Variant variant
    ) {
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan triggerNode = nodes.get(trigger.node());
            if (variant == Variant.DEVELOPMENT) {
                source.append("    private static RunResult run_").append(trigger.node())
                        .append("(final RailixValue.ObjectValue context, final boolean test) {\n")
                        .append("        if (context.values().containsKey(\"runtime\")) {\n")
                        .append("            return WorkflowRuntime.rejectedResult(\"RUN_RUNTIME_RESERVED\",\n")
                        .append("                    \"context.runtime is supplied by Railix.\", ")
                        .append("\"context.runtime\", List.of());\n        }\n")
                        .append("        final WorkflowRuntime.Execution execution = WorkflowRuntime.execution(")
                        .append(quote(triggerNode.id())).append(", ").append(resultsReference(trigger))
                        .append(", context, test, test);\n")
                        .append("        return execute_").append(trigger.node())
                        .append("(execution, ").append(trigger.start()).append(", END, null);\n")
                        .append("    }\n\n")
                        .append("    private static RunResult execute_").append(trigger.node())
                        .append("(final WorkflowRuntime.Execution execution, int current,\n")
                        .append("            final int observed, final ObservationCapture capture) {\n");
            } else {
                source.append("    private static RunResult execute_").append(trigger.node())
                        .append("(final WorkflowRuntime.Execution execution, int current) {\n");
            }
            source.append("        while (current != END) {\n")
                    .append("            final int outcome = dispatch_").append(trigger.node());
            if (variant == Variant.DEVELOPMENT) {
                source.append("(execution, current, current == observed, capture);\n");
            } else {
                source.append("(execution, current);\n");
            }
            source.append("            if (outcome < 0) {\n")
                    .append("                return execution.finish();\n            }\n");
            if (variant == Variant.DEVELOPMENT) {
                source.append("            final String outcomeName = outcome_").append(trigger.node())
                        .append("(current, outcome);\n")
                        .append("            execution.record(step_").append(trigger.node())
                        .append("(current), outcomeName);\n");
            }
            source.append("            final int destination = destination_").append(trigger.node())
                    .append("(current, outcome);\n")
                    .append("            if (destination == UNROUTED) {\n");
            if (variant == Variant.PRODUCTION) {
                source.append("                final String outcomeName = outcome_").append(trigger.node())
                        .append("(current, outcome);\n");
            }
            source.append("                return WorkflowRuntime.failedResult(\"STEP_OUTCOME_UNROUTED\",\n")
                    .append("                        \"Step returned an outcome without a connection: \" + outcomeName + \".\",\n")
                    .append("                        step_").append(trigger.node())
                    .append("(current), execution.history());\n            }\n")
                    .append("            current = destination;\n        }\n")
                    .append("        return execution.finish();\n    }\n\n");
        }
    }

    private static void appendDispatch(
            final StringBuilder source,
            final List<ApplicationPlan.NodePlan> nodes,
            final Map<String, Integer> handlers,
            final Variant variant
    ) {
        final Map<Integer, List<Integer>> owned = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            final ApplicationPlan.NodePlan node = nodes.get(index);
            if (node.step().kind() == StepDefinition.Kind.TRIGGER) {
                owned.computeIfAbsent(index, ignored -> new ArrayList<>());
            }
            if (node.step().kind() == StepDefinition.Kind.STEP) {
                owned.computeIfAbsent(node.owner(), ignored -> new ArrayList<>()).add(index);
            }
        }
        source.append("    private static int missingPlan(\n")
                .append("            final WorkflowRuntime.Execution execution, final int current) {\n")
                .append("        return execution.abort(WorkflowRuntime.failedResult(\n")
                .append("                \"RUN_PLAN_MISSING\", \"Compiled Step plan is missing.\",\n")
                .append("                Integer.toString(current), execution.history()));\n")
                .append("    }\n\n");
        for (final Map.Entry<Integer, List<Integer>> entry : owned.entrySet()) {
            final int trigger = entry.getKey();
            final Map<Integer, List<Integer>> partitions = partitions(entry.getValue());
            source.append("    private static int dispatch_").append(trigger)
                    .append("(final WorkflowRuntime.Execution execution, final int current");
            if (variant == Variant.DEVELOPMENT) {
                source.append(", final boolean observe, final WorkflowRuntime.Capture capture");
            }
            source.append(") {\n")
                    .append("        return switch (current / NODE_PARTITION_SIZE) {\n");
            for (final int partition : partitions.keySet()) {
                source.append("            case ").append(partition).append(" -> Routes_")
                        .append(trigger).append('_').append(partition)
                        .append(".dispatch(execution, current");
                if (variant == Variant.DEVELOPMENT) {
                    source.append(", observe, capture");
                }
                source.append(");\n");
            }
            source.append("            default -> missingPlan(execution, current);\n")
                    .append("        };\n    }\n\n");
            appendRouting(source, trigger, partitions, nodes, handlers, variant);
        }
    }

    private static void appendRouting(
            final StringBuilder source,
            final int trigger,
            final Map<Integer, List<Integer>> partitions,
            final List<ApplicationPlan.NodePlan> nodes,
            final Map<String, Integer> handlers,
            final Variant variant
    ) {
        if (variant == Variant.DEVELOPMENT) {
            source.append("    private static int select_").append(trigger).append("(final String stepId) {\n");
            if (partitions.isEmpty()) {
                source.append("        return END;\n");
            } else {
                source.append("        int selected;\n");
                for (final int partition : partitions.keySet()) {
                    source.append("        selected = Routes_").append(trigger).append('_').append(partition)
                            .append(".select(stepId);\n")
                            .append("        if (selected != END) {\n            return selected;\n        }\n");
                }
                source.append("        return END;\n");
            }
            source.append("    }\n\n");
        }
        appendRouteSelector(
                source, trigger, partitions, "String", "step", "current", "Integer.toString(current)", false
        );
        appendRouteSelector(
                source, trigger, partitions, "String", "outcome", "current, outcome", "\"unknown\"", true
        );
        appendRouteSelector(
                source, trigger, partitions, "int", "destination", "current, outcome", "UNROUTED", true
        );

        for (final Map.Entry<Integer, List<Integer>> partition : partitions.entrySet()) {
            final String owner = "Routes_" + trigger + "_" + partition.getKey();
            source.append("    private static final class ").append(owner).append(" {\n");
            if (variant == Variant.DEVELOPMENT) {
                source.append("        private static int select(final String stepId) {\n")
                        .append("            return switch (stepId) {\n");
                for (final int index : partition.getValue()) {
                    source.append("                case ").append(quote(nodes.get(index).id()))
                            .append(" -> ").append(index).append(";\n");
                }
                source.append("                default -> END;\n            };\n        }\n\n");
            }
            source.append("        private static int dispatch(\n")
                    .append("                final WorkflowRuntime.Execution execution, final int current");
            if (variant == Variant.DEVELOPMENT) {
                source.append(",\n                final boolean observe, final WorkflowRuntime.Capture capture");
            }
            source.append(") {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                final ApplicationPlan.NodePlan node = nodes.get(index);
                source.append("                case ").append(index).append(" -> ");
                if (variant == Variant.DEVELOPMENT) {
                    source.append("{\n")
                            .append("                    if (observe) {\n")
                            .append("                        yield execution.observe(")
                            .append(planReference(index)).append(", CALL_")
                            .append(handlers.get(node.step().use())).append(", Map.of(), ")
                            .append(inputsReference(index)).append(", capture);\n")
                            .append("                    }\n")
                            .append("                    yield execution.call(");
                } else {
                    source.append("execution.call(");
                }
                source.append(planReference(index)).append(", CALL_")
                        .append(handlers.get(node.step().use())).append(", Map.of(), ")
                        .append(inputsReference(index)).append(");\n");
                if (variant == Variant.DEVELOPMENT) {
                    source.append("                }\n");
                }
            }
            source.append("                default -> missingPlan(execution, current);\n")
                    .append("            };\n        }\n\n")
                    .append("        private static String step(final int current) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(quote(nodes.get(index).id())).append(";\n");
            }
            source.append("                default -> Integer.toString(current);\n")
                    .append("            };\n        }\n\n")
                    .append("        private static String outcome(final int current, final int outcome) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(outcome(nodes.get(index))).append(";\n");
            }
            source.append("                default -> \"unknown\";\n            };\n        }\n\n")
                    .append("        private static int destination(final int current, final int outcome) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(destination(nodes.get(index))).append(";\n");
            }
            source.append("                default -> UNROUTED;\n            };\n        }\n\n")
                    .append("        private ").append(owner).append("() {\n        }\n")
                    .append("    }\n\n");
        }
    }

    private static void appendRouteSelector(
            final StringBuilder source,
            final int trigger,
            final Map<Integer, List<Integer>> partitions,
            final String type,
            final String name,
            final String arguments,
            final String fallback,
            final boolean usesOutcome
    ) {
        source.append("    private static ").append(type).append(' ').append(name).append('_').append(trigger)
                .append("(final int current")
                .append(usesOutcome ? ", final int outcome" : "")
                .append(") {\n        return switch (current / NODE_PARTITION_SIZE) {\n");
        for (final int partition : partitions.keySet()) {
            source.append("            case ").append(partition).append(" -> Routes_")
                    .append(trigger).append('_').append(partition).append('.').append(name)
                    .append('(').append(arguments).append(");\n");
        }
        source.append("            default -> ").append(fallback).append(";\n")
                .append("        };\n    }\n\n");
    }

    private static Map<Integer, List<Integer>> partitions(final List<Integer> indexes) {
        final Map<Integer, List<Integer>> partitions = new LinkedHashMap<>();
        for (final int index : indexes) {
            partitions.computeIfAbsent(index / ROUTE_PARTITION_SIZE, ignored -> new ArrayList<>()).add(index);
        }
        return partitions;
    }

    private static Map<String, Integer> handlerIndexes(
            final Map<String, StepCatalog.Implementation> implementations
    ) {
        final Map<String, Integer> indexes = new LinkedHashMap<>();
        int index = 0;
        for (final String use : implementations.keySet()) {
            indexes.put(use, index++);
        }
        return Map.copyOf(indexes);
    }

    private static String compiledNode(
            final int index,
            final ApplicationPlan.NodePlan node,
            final Map<String, Integer> handlers
    ) {
        return new NodeCompiler(index, handlers).compile(node);
    }

    private static final class NodeCompiler {
        private final int node;
        private final Map<String, Integer> handlers;
        private final StringBuilder fields = new StringBuilder();
        private final StringBuilder methods = new StringBuilder();
        private int sequence;

        private NodeCompiler(final int node, final Map<String, Integer> handlers) {
            this.node = node;
            this.handlers = handlers;
        }

        private String compile(final ApplicationPlan.NodePlan plan) {
            resolver("INPUTS_" + node, plan.inputs());
            final String receives = paths(plan.receives());
            final String returns = paths(plan.returns());
            constant(
                    "NODE_" + node,
                    "WorkflowRuntime.StepPlan",
                    runtimeStep(
                            plan.id(), plan.step(), plan.step().kind() == StepDefinition.Kind.STEP,
                            receives, returns, plan.path()
                    )
            );
            return fields.append(methods).toString();
        }

        private String resolver(
                final String fieldName,
                final Map<String, ApplicationPlan.Binding> bindings
        ) {
            final String methodName = "resolve_" + node + "_" + sequence++;
            constant(
                    fieldName,
                    "WorkflowRuntime.InputResolver",
                    "Plans_" + node / PLAN_PARTITION_SIZE + "::" + methodName
            );
            final StringBuilder statements = new StringBuilder();
            for (final Map.Entry<String, ApplicationPlan.Binding> entry : bindings.entrySet()) {
                binding(entry.getKey(), entry.getValue(), statements);
            }
            methods.append("private static WorkflowRuntime.Inputs ").append(methodName).append("(\n")
                    .append("        final WorkflowRuntime.Execution execution,\n")
                    .append("        final Map<String, RailixValue> received,\n")
                    .append("        final String primaryOutcome\n")
                    .append(") {\n")
                    .append("    final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, primaryOutcome);\n")
                    .append(statements)
                    .append("    return inputs;\n")
                    .append("}\n");
            return fieldName;
        }

        private void binding(
                final String name,
                final ApplicationPlan.Binding binding,
                final StringBuilder statements
        ) {
            switch (binding) {
                case ApplicationPlan.JsonBinding json -> {
                    if (!json.value().isEmpty()) {
                        statements.append("    inputs.value(").append(quote(name)).append(", ")
                                .append(field("RailixValue", value(json.value().getFirst()))).append(");\n");
                    }
                }
                case ApplicationPlan.PathBinding path -> {
                    final String runtimePath = path(path.path());
                    final String runtimeBinding = field(
                            "WorkflowRuntime.PathBinding",
                            "new WorkflowRuntime.PathBinding(" + runtimePath + ", " + path.access().readable()
                                    + ", " + path.access().writable() + ")"
                    );
                    statements.append("    inputs.path(").append(quote(name)).append(", ")
                            .append(runtimeBinding).append(", execution);\n");
                }
                case ApplicationPlan.ChoiceBinding choice -> statements
                        .append("    inputs.choice(").append(quote(name)).append(", ")
                        .append(quote(choice.option())).append(", ")
                        .append(resolver(dataName(), choice.inputs())).append(", ")
                        .append(references(choice.valueSources())).append(", execution);\n");
                case ApplicationPlan.CandidatesBinding candidates -> statements
                        .append("    inputs.candidates(").append(quote(name)).append(", ")
                        .append(candidates(candidates.candidates())).append(", ")
                        .append(quote(candidates.path())).append(", execution);\n");
                case ApplicationPlan.MatcherGroupsBinding groups -> {
                    final List<String> runtimeGroups = new ArrayList<>();
                    for (final List<ApplicationPlan.CandidatePlan> group : groups.groups()) {
                        runtimeGroups.add(candidates(group));
                    }
                    statements.append("    inputs.matcherGroups(").append(quote(name)).append(", ")
                            .append(field("List<List<WorkflowRuntime.CandidatePlan>>", list(runtimeGroups)))
                            .append(", execution);\n");
                }
                case ApplicationPlan.StepsBinding steps -> statements
                        .append("    inputs.program(").append(quote(name)).append(", ")
                        .append(program(steps.steps())).append(", ")
                        .append(quote(steps.valueSource().input())).append(", ")
                        .append(quote(steps.valueSource().missingOutcome().orElse("")))
                        .append(", execution);\n");
            }
        }

        private String candidates(final List<ApplicationPlan.CandidatePlan> candidates) {
            final List<String> plans = new ArrayList<>();
            for (final ApplicationPlan.CandidatePlan candidate : candidates) {
                final ApplicationPlan.ChoiceBinding source = candidate.source();
                final List<String> predicates = new ArrayList<>();
                for (final List<ApplicationPlan.NestedStepPlan> predicate : candidate.predicates()) {
                    predicates.add(program(predicate));
                }
                plans.add(field(
                        "WorkflowRuntime.CandidatePlan",
                        "new WorkflowRuntime.CandidatePlan(" + quote(source.option()) + ", "
                                + resolver(dataName(), source.inputs()) + ", "
                                + references(source.valueSources()) + ", " + program(candidate.transforms())
                                + ", " + list(predicates) + ")"
                ));
            }
            return field("List<WorkflowRuntime.CandidatePlan>", list(plans));
        }

        private String program(final List<ApplicationPlan.NestedStepPlan> steps) {
            final List<String> nested = new ArrayList<>();
            for (final ApplicationPlan.NestedStepPlan step : steps) {
                final String resolver = resolver(dataName(), step.inputs());
                final String plan = field(
                        "WorkflowRuntime.StepPlan",
                        runtimeStep(step.step().use(), step.step(), false, "Map.of()", "Map.of()", step.path())
                );
                nested.add(field(
                        "WorkflowRuntime.NestedStep",
                        "new WorkflowRuntime.NestedStep(" + plan + ", " + resolver + ", " + quote(step.path())
                                + ", CALL_" + handlers.get(step.step().use()) + ")"
                ));
            }
            return field(
                    "WorkflowRuntime.NestedProgram",
                    "new WorkflowRuntime.NestedProgram(" + list(nested) + ")"
            );
        }

        private String paths(final Map<String, ApplicationPlan.Path> paths) {
            final List<String> entries = new ArrayList<>();
            for (final Map.Entry<String, ApplicationPlan.Path> entry : paths.entrySet()) {
                entries.add(entry(quote(entry.getKey()), path(entry.getValue())));
            }
            return map(entries);
        }

        private String path(final ApplicationPlan.Path path) {
            return field("WorkflowRuntime.Path", runtimePath(path));
        }

        private String field(final String type, final String expression) {
            return constant(dataName(), type, expression);
        }

        private String constant(final String name, final String type, final String expression) {
            fields.append("private static final ").append(type).append(' ').append(name)
                    .append(" = init_").append(name).append("();\n");
            methods.append("private static ").append(type).append(" init_").append(name)
                    .append("() {\n    return ").append(expression).append(";\n}\n");
            return name;
        }

        private String dataName() {
            return "DATA_" + node + "_" + sequence++;
        }
    }

    private static String runtimeStep(
            final String id,
            final ApplicationPlan.ExecutableStep step,
            final boolean mappedReceives,
            final String receives,
            final String returns,
            final String path
    ) {
        return "new WorkflowRuntime.StepPlan(\n"
                + indent(quote(id), 1) + ",\n"
                + indent(quote(step.use()), 1) + ",\n"
                + indent(Boolean.toString(mappedReceives), 1) + ",\n"
                + indent(list(step.receives().stream().map(ApplicationGenerator::port).toList()), 1) + ",\n"
                + indent(list(step.returns().stream().map(ApplicationGenerator::port).toList()), 1) + ",\n"
                + indent(strings(step.outcomes()), 1) + ",\n"
                + indent(receives, 1) + ",\n"
                + indent(returns, 1) + ",\n"
                + indent(quote(path), 1) + "\n)";
    }

    private static String runtimePath(final ApplicationPlan.Path path) {
        return "new WorkflowRuntime.Path(" + list(path.elements().stream().map(element -> switch (element) {
            case ApplicationPlan.Field field -> "new WorkflowRuntime.Field(" + quote(field.name()) + ")";
            case ApplicationPlan.Index index -> "new WorkflowRuntime.Index(" + index.value() + ")";
        }).toList()) + ")";
    }

    private static String results(final ApplicationPlan.TriggerPlan trigger) {
        return list(trigger.results().stream().map(result ->
                "new WorkflowRuntime.ResultPlan(" + quote(result.name())
                        + ", ValueShape." + result.shape().name() + ", "
                        + values(result.defaults()) + ")").toList());
    }

    private static String resultsReference(final ApplicationPlan.TriggerPlan trigger) {
        return "RESULTS_" + trigger.node();
    }

    private static String responseSlotsReference(final ApplicationPlan.TriggerPlan trigger) {
        return "RESPONSE_SLOTS_" + trigger.node();
    }

    private static String planReference(final int node) {
        return "Plans_" + node / PLAN_PARTITION_SIZE + ".NODE_" + node;
    }

    private static String inputsReference(final int node) {
        return "Plans_" + node / PLAN_PARTITION_SIZE + ".INPUTS_" + node;
    }

    private static String outcome(final ApplicationPlan.NodePlan node) {
        final StringBuilder result = new StringBuilder("switch (outcome) {");
        for (int index = 0; index < node.outcomes().size(); index++) {
            result.append(" case ").append(index).append(" -> ").append(quote(node.outcomes().get(index))).append(';');
        }
        return result.append(" default -> \"unknown\"; }").toString();
    }

    private static String destination(final ApplicationPlan.NodePlan node) {
        final int[] destinations = node.destinations();
        final StringBuilder result = new StringBuilder("switch (outcome) {");
        for (int index = 0; index < destinations.length; index++) {
            result.append(" case ").append(index).append(" -> ").append(destinations[index]).append(';');
        }
        return result.append(" default -> UNROUTED; }").toString();
    }

    private static String port(final StepDefinition.Port port) {
        return "new WorkflowRuntime.Port(" + quote(port.name())
                + ", ValueShape." + port.shape().name()
                + ", new ValueRefinement(" + port.refinement().canonicalValues()
                + ", " + port.refinement().maxDepth()
                + ", " + port.refinement().maxJsonBytes() + "))";
    }

    private static String references(final List<StepDefinition.InputReference> references) {
        return list(references.stream().map(reference -> "new WorkflowRuntime.InputReference("
                + (reference.scope() == StepDefinition.ReferenceScope.OWNED) + ", "
                + quote(reference.input()) + ")").toList());
    }

    private static String values(final List<RailixValue> values) {
        return list(values.stream().map(ApplicationGenerator::value).toList());
    }

    private static String value(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> "RailixValue.nullValue()";
            case RailixValue.BooleanValue bool -> "RailixValue.bool(" + bool.value() + ")";
            case RailixValue.NumberValue number ->
                    "RailixValue.number(new BigDecimal(" + quote(number.value().toString()) + "))";
            case RailixValue.StringValue string -> "RailixValue.string(" + quote(string.value()) + ")";
            case RailixValue.ArrayValue array -> "RailixValue.array(" + values(array.values()) + ")";
            case RailixValue.ObjectValue object -> "RailixValue.object(" + map(object.values().entrySet().stream()
                    .map(entry -> entry(quote(entry.getKey()), value(entry.getValue())))
                    .toList()) + ")";
        };
    }

    private static String strings(final List<String> values) {
        return list(values.stream().map(ApplicationGenerator::quote).toList());
    }

    private static String stringMap(final Map<String, String> values) {
        return map(values.entrySet().stream()
                .map(entry -> entry(quote(entry.getKey()), quote(entry.getValue())))
                .toList());
    }

    private static String list(final List<String> values) {
        return "List.of(" + String.join(", ", values) + ")";
    }

    private static String map(final List<String> entries) {
        return entries.isEmpty() ? "Map.of()" : "map(" + String.join(", ", entries) + ")";
    }

    private static String entry(final String key, final String value) {
        return "entry(" + key + ", " + value + ")";
    }

    private static String indent(final String value, final int levels) {
        return "    ".repeat(levels) + value.replace("\n", "\n" + "    ".repeat(levels));
    }

    private static String quote(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20 || character > 0x7e) {
                        escaped.append("\\u").append("%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    record Result(
            String productionApplicationSource,
            String developmentApplicationSource,
            String developmentLauncherSource,
            List<Diagnostic> diagnostics,
            List<StepCatalog.Implementation> dependencies
    ) {
        Result {
            diagnostics = List.copyOf(diagnostics);
            dependencies = List.copyOf(dependencies);
        }
    }

    private enum Variant {
        PRODUCTION,
        DEVELOPMENT
    }
}
