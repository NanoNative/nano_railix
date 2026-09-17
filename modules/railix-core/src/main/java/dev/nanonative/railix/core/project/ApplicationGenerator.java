package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.IntStream;

/** Deterministically lowers one validated executable plan to plain Java source. */
final class ApplicationGenerator {
    static final String APPLICATION_CLASS = "dev.nanonative.railix.core.project.RailixApplication";
    static final String DEVELOPMENT_LAUNCHER_CLASS =
            "dev.nanonative.railix.core.project.RailixDevelopmentApplication";
    private static final String DEVELOPMENT_EXAMPLES_RESOURCE = "META-INF/railix/examples.json";
    private static final int PLAN_PARTITION_SIZE = 16;
    private static final int ROUTE_PARTITION_SIZE = 128;
    private static final int MAX_PLAN_SOURCE_CHARACTERS = 32_768;
    private static final int MAX_DEVELOPMENT_EXAMPLES_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES * 4;

    private ApplicationGenerator() {
    }

    static Result generate(
            final ApplicationPlan plan,
            final StepCatalog catalog
    ) {
        final List<ApplicationPlan.NodePlan> nodes = plan.nodes();
        final List<ApplicationPlan.TriggerPlan> triggers = plan.triggers();
        final Map<String, StepCatalog.Implementation> implementations = new LinkedHashMap<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        for (final ApplicationPlan.NodePlan node : nodes) {
            collect(node.step(), node.path() + ".use", catalog, implementations, diagnostics);
            collect(node.inputs(), catalog, implementations, diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return new Result("", "", "", Map.of(), diagnostics, List.of());
        }
        final Map<String, Integer> handlerIndexes = handlerIndexes(implementations);
        final String[] compiledNodes = new String[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            final ApplicationPlan.NodePlan node = nodes.get(index);
            if (node.step().kind() == StepDefinition.Kind.APP) {
                continue;
            }
            compiledNodes[index] = compiledNode(index, node, handlerIndexes);
            if (compiledNodes[index].length() > MAX_PLAN_SOURCE_CHARACTERS) {
                return new Result("", "", "", Map.of(), List.of(Diagnostic.atPath(
                        "PROJECT_APPLICATION_STEP_LIMIT",
                        "One compiled Step exceeds the " + MAX_PLAN_SOURCE_CHARACTERS
                                + "-character generated-code limit.",
                        node.path()
                )), List.of());
            }
        }
        final String developmentExamples = developmentExamples(plan);
        if (developmentExamples == null) {
            return new Result("", "", "", Map.of(), List.of(Diagnostic.atPath(
                    "PROJECT_EXAMPLES_RESOURCE_TOO_LARGE",
                    "Compiled development Examples exceed 4194304 bytes.",
                    "nodes"
            )), List.of());
        }
        return new Result(
                source(plan.projectId(), nodes, triggers, implementations, handlerIndexes,
                        compiledNodes, Variant.PRODUCTION),
                source(plan.projectId(), nodes, triggers, implementations, handlerIndexes,
                        compiledNodes, Variant.DEVELOPMENT),
                developmentLauncherSource(),
                Map.of(DEVELOPMENT_EXAMPLES_RESOURCE, developmentExamples),
                List.of(),
                implementations.values().stream().distinct().toList()
        );
    }

    private static String developmentExamples(final ApplicationPlan plan) {
        final List<RailixValue> examples = plan.examples().stream().<RailixValue>map(example ->
                RailixValue.object(Map.of(
                        "context", example.context(),
                        "id", RailixValue.string(example.id()),
                        "index", RailixValue.number(example.index()),
                        "name", RailixValue.string(example.name()),
                        "node", RailixValue.number(example.triggerNode()),
                        "trigger", RailixValue.string(example.trigger())
                ))
        ).toList();
        return RailixJson.write(RailixValue.object(Map.of(
                "examples", RailixValue.array(examples),
                "format", RailixValue.number(1),
                "node_count", RailixValue.number(plan.nodes().size())
        )), MAX_DEVELOPMENT_EXAMPLES_BYTES).orElse(null);
    }

    private static void collect(
            final StepDefinition step,
            final String path,
            final StepCatalog catalog,
            final Map<String, StepCatalog.Implementation> implementations,
            final List<Diagnostic> diagnostics
    ) {
        if (step.kind() == StepDefinition.Kind.APP || implementations.containsKey(step.id())) {
            return;
        }
        final StepCatalog.Implementation implementation = catalog.implementation(step.id()).orElse(null);
        if (implementation == null) {
            diagnostics.add(Diagnostic.atPath(
                    "PROJECT_STEP_IMPLEMENTATION_ADDRESS_REQUIRED",
                    "Step must declare a named Java handler class for application generation: "
                            + step.id() + ".",
                    path
            ));
        } else {
            implementations.put(step.id(), implementation);
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

    private static void appendDevelopmentExecution(final StringBuilder source) {
        source.append("""
                    static final class TraceExecution extends WorkflowRuntime.Execution {
                        private String owner;

                        TraceExecution(
                                final List<WorkflowRuntime.ResultPlan> results,
                                final RailixValue.ObjectValue context,
                                final RailixValue.ObjectValue runtime
                        ) {
                            super(results, context, runtime);
                        }

                        @Override
                        int call(
                                final WorkflowRuntime.StepPlan plan,
                                final WorkflowRuntime.StepCall implementation,
                                final Map<String, RailixValue> received,
                                final WorkflowRuntime.InputResolver resolver
                        ) {
                            final String previous = owner;
                            owner = plan.id();
                            try {
                                return super.call(plan, implementation, received, resolver);
                            } finally {
                                owner = previous;
                            }
                        }

                        @Override
                        StepResult nested(
                                final WorkflowRuntime.NestedStep step,
                                final StepInput input
                        ) throws InterruptedException {
                            final int inputs = step.path().indexOf(".inputs.");
                            final String invocation = owner
                                    + (inputs < 0 ? ".nested" : step.path().substring(inputs));
                            return DevelopmentRuntime.Trace.invoke(
                                    invocation, step.step().use(), input, step.handler()
                            );
                        }
                    }

                """);
    }

    private static String source(
            final String projectId,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Map<String, StepCatalog.Implementation> implementations,
            final Map<String, Integer> handlerIndexes,
            final String[] compiledNodes,
            final Variant variant
    ) {
        final int[] metricIndexes = variant == Variant.DEVELOPMENT ? metricIndexes(nodes) : new int[0];
        final StringBuilder source = new StringBuilder(8_192);
        final StringBuilder classes = new StringBuilder();
        final Map<Integer, StringBuilder> flows = new LinkedHashMap<>();
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
                import static dev.nanonative.railix.core.project.RailixApplication.*;
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
                .append("    static final int END = -1;\n")
                .append("    static final int UNROUTED = -2;\n")
                .append("    static final int NODE_PARTITION_SIZE =\n")
                .append(indent(Integer.toString(ROUTE_PARTITION_SIZE), 3)).append(";\n")
                .append("    private static final String PROJECT_ID =\n")
                .append(indent(quote(projectId), 3)).append(";\n");
        if (variant == Variant.DEVELOPMENT) {
            source.append("    static final DevelopmentRuntime.Metrics METRICS = ")
                    .append("new DevelopmentRuntime.Metrics(PROJECT_ID, ")
                    .append(array(classes, "Flows", "String", triggers.stream()
                            .map(trigger -> quote(nodes.get(trigger.node()).id())).toList()))
                    .append(", ")
                    .append(array(classes, "Steps", "String", IntStream.range(0, nodes.size())
                            .filter(index -> metricIndexes[index] >= 0)
                            .mapToObj(index -> quote(nodes.get(index).id())).toList()))
                    .append(");\n");
        }
        int handlerIndex = 0;
        final List<String> calls = new ArrayList<>();
        final List<String> traceCalls = new ArrayList<>();
        for (final StepCatalog.Implementation implementation : implementations.values()) {
            if (handlerIndex % PLAN_PARTITION_SIZE == 0) {
                classes.append("final class Handlers_").append(handlerIndex / PLAN_PARTITION_SIZE).append(" {\n");
            }
            classes.append("    static final ").append(implementation.className())
                    .append(" HANDLER_").append(handlerIndex).append(" = new ")
                    .append(implementation.className()).append("();\n");
            if (variant == Variant.PRODUCTION) {
                classes.append("    static final WorkflowRuntime.StepCall CALL_").append(handlerIndex)
                        .append(" = HANDLER_").append(handlerIndex).append("::run;\n");
            } else {
                classes.append("    static StepResult trace_").append(handlerIndex)
                        .append("(final StepInput input) throws InterruptedException {\n")
                        .append("        return DevelopmentRuntime.Trace.invoke(input, HANDLER_")
                        .append(handlerIndex).append(");\n    }\n");
                calls.add(handlerReference(handlerIndex) + "::run");
                traceCalls.add("Handlers_" + handlerIndex / PLAN_PARTITION_SIZE + "::trace_" + handlerIndex);
            }
            handlerIndex++;
            if (handlerIndex % PLAN_PARTITION_SIZE == 0 || handlerIndex == implementations.size()) {
                classes.append("}\n");
            }
        }
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            final StringBuilder flow = new StringBuilder("final class Flow_" + trigger.node() + " {\n");
            flows.put(trigger.node(), flow);
            flow.append("    private static final List<WorkflowRuntime.ResultPlan> RESULTS_")
                    .append(trigger.node()).append(" = ").append(results(node)).append(";\n")
                    .append("    private static final Map<String, String> RESPONSE_SLOTS_")
                    .append(trigger.node()).append(" = ")
                    .append(stringMap(node.step().source().orElseThrow().responses())).append(";\n");
        }
        source.append("    private static final RailixApplication APPLICATION = new RailixApplication();\n\n");
        if (variant == Variant.DEVELOPMENT) {
            source.append("    static final WorkflowRuntime.StepCall[] CALLS = ")
                    .append(array(classes, "Calls", "WorkflowRuntime.StepCall", calls)).append(";\n")
                    .append("    static final WorkflowRuntime.StepCall[] TRACE_CALLS = ")
                    .append(array(classes, "TraceCalls", "WorkflowRuntime.StepCall", traceCalls)).append(";\n");
            appendDevelopmentExecution(source);
        }
        appendPlans(classes, nodes, compiledNodes);
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
            source.append("    @Override\n")
                    .append("    public DevelopmentRuntime.Metrics metrics() {\n")
                    .append("        return METRICS;\n    }\n\n");
            appendDevelopmentRun(source, classes, nodes, triggers);
            appendTrace(source, classes, flows, nodes, triggers);
        }
        appendSources(source, classes, flows, nodes, triggers, handlerIndexes, metricIndexes, variant);
        appendExecutors(flows, nodes, triggers, variant);
        appendDispatch(source, flows, classes, nodes, handlerIndexes, metricIndexes, variant);
        flows.values().forEach(flow -> classes.append(flow).append("}\n"));
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
                .append("    static <K, V> Map<K, V> map(final Map.Entry<K, V>... entries) {\n")
                .append("        final Map<K, V> values = new LinkedHashMap<>();\n")
                .append("        for (final Map.Entry<K, V> entry : entries) {\n")
                .append("            values.put(entry.getKey(), entry.getValue());\n        }\n")
                .append("        return Collections.unmodifiableMap(values);\n    }\n\n")
                .append("    static <K, V> Map.Entry<K, V> entry(final K key, final V value) {\n")
                .append("        return Map.entry(key, value);\n    }\n")
                .append("}\n");
        // Keep only one project-sized assembly buffer; the launcher remains bounded.
        return classes.insert(0, source).toString();
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
            final String[] compiledNodes
    ) {
        for (int start = 0; start < nodes.size(); start += PLAN_PARTITION_SIZE) {
            final int end = Math.min(nodes.size(), start + PLAN_PARTITION_SIZE);
            source.append("final class Plans_")
                    .append(start / PLAN_PARTITION_SIZE).append(" {\n");
            for (int index = start; index < end; index++) {
                final ApplicationPlan.NodePlan node = nodes.get(index);
                if (node.step().kind() == StepDefinition.Kind.APP) {
                    continue;
                }
                source.append(indent(compiledNodes[index], 2));
            }
            source.append("\n        private Plans_").append(start / PLAN_PARTITION_SIZE)
                    .append("() {\n        }\n")
                    .append("    }\n\n");
            for (int index = start; index < end; index++) {
                final ApplicationPlan.NodePlan node = nodes.get(index);
                if (node.outcomes().size() <= PLAN_PARTITION_SIZE) continue;
                final Map<Integer, String> cases = new LinkedHashMap<>();
                final int[] destinations = node.destinations();
                for (int outcome = 0; outcome < destinations.length; outcome++) {
                    cases.put(outcome, Integer.toString(destinations[outcome]));
                }
                final String expression = selector(source, "outcome_" + index, "int", "final int outcome", "outcome",
                        new ArrayList<>(cases.entrySet()), "UNROUTED", "outcome", Object::toString,
                        key -> "outcome < " + key);
                source.append("final class Outcomes_").append(index)
                        .append(" {\n    static int destination(final int outcome) {\n        return ")
                        .append(expression).append(";\n    }\n}\n");
            }
        }
    }

    private static void appendDevelopmentRun(
            final StringBuilder source,
            final StringBuilder classes,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers
    ) {
        source.append("    @Override\n")
                .append("    public RunResult run(final String triggerId,\n")
                .append("            final RailixValue.ObjectValue context, final boolean test) {\n")
                .append("        if (triggerId == null || triggerId.isBlank()) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_REQUIRED\", ")
                .append("\"Trigger id must be a non-blank string.\", \"trigger\");\n        }\n")
                .append("        if (context == null) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_INPUT_REQUIRED\", ")
                .append("\"Workflow context must be supplied.\", \"input\");\n        }\n")
                .append("        return ");
        final Map<String, String> cases = new TreeMap<>();
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            cases.put(nodes.get(trigger.node()).id(), "Flow_" + trigger.node() + ".run_" + trigger.node() + "(context, test)");
        }
        source.append(keySelector(classes, "run", "RunResult",
                "final String triggerId, final RailixValue.ObjectValue context, final boolean test",
                "triggerId, context, test", "triggerId", cases,
                "WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_UNKNOWN\", \"Trigger is not part of this project: \" + triggerId + \".\", \"trigger\")"))
                .append(";\n    }\n\n");
    }

    private static void appendTrace(
            final StringBuilder root,
            final StringBuilder classes,
            final Map<Integer, StringBuilder> flows,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers
    ) {
        root.append("    @Override\n")
                .append("    public RunResult trace(final String triggerId,\n")
                .append("            final RailixValue.ObjectValue context, final boolean test,\n")
                .append("            final DevelopmentRuntime.TraceSink sink) {\n")
                .append("        if (triggerId == null || triggerId.isBlank()) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_REQUIRED\", ")
                .append("\"Trigger id must be a non-blank string.\", \"trigger\");\n        }\n")
                .append("        if (context == null) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"RUN_INPUT_REQUIRED\", ")
                .append("\"Workflow context must be supplied.\", \"input\");\n        }\n")
                .append("        if (sink == null) {\n")
                .append("            return WorkflowRuntime.rejectedResult(\"TRACE_SINK_REQUIRED\", ")
                .append("\"Trace sink must be supplied.\", \"sink\");\n        }\n")
                .append("        return ");
        final Map<String, String> cases = new TreeMap<>();
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            cases.put(nodes.get(trigger.node()).id(), "Flow_" + trigger.node() + ".trace_" + trigger.node() + "(context, test, sink)");
        }
        root.append(keySelector(classes, "trace", "RunResult",
                "final String triggerId, final RailixValue.ObjectValue context, final boolean test, final DevelopmentRuntime.TraceSink sink",
                "triggerId, context, test, sink", "triggerId", cases,
                "WorkflowRuntime.rejectedResult(\"RUN_TRIGGER_UNKNOWN\", \"Trigger is not part of this project: \" + triggerId + \".\", \"trigger\")"))
                .append(";\n    }\n\n");

        for (int flow = 0; flow < triggers.size(); flow++) {
            final ApplicationPlan.TriggerPlan trigger = triggers.get(flow);
            final StringBuilder source = flows.get(trigger.node());
            source.append("    static RunResult trace_").append(trigger.node())
                    .append("(final RailixValue.ObjectValue context, final boolean test,\n")
                    .append("            final DevelopmentRuntime.TraceSink sink) {\n")
                    .append("        if (context.values().containsKey(\"runtime\")) {\n")
                    .append("            return WorkflowRuntime.rejectedResult(\"RUN_RUNTIME_RESERVED\",\n")
                    .append("                    \"context.runtime is supplied by Railix.\", ")
                    .append("\"context.runtime\");\n        }\n")
                    .append("        final long metric = METRICS.startFlow(").append(flow).append(");\n")
                    .append("        RunResult result = null;\n")
                    .append("        try {\n")
                    .append("            final WorkflowRuntime.Execution execution = new TraceExecution(")
                    .append(resultsReference(trigger)).append(", context, ")
                    .append(runtime(nodes.get(trigger.node()).id(), Variant.DEVELOPMENT, "test"))
                    .append(");\n")
                    .append("            result = DevelopmentRuntime.Trace.start(execution.context(), sink,\n")
                    .append("                    () -> traceExecute_").append(trigger.node())
                    .append("(execution, ").append(trigger.start()).append(", TRACE_CALLS));\n")
                    .append("            return result;\n")
                    .append("        } finally {\n")
                    .append("            METRICS.finishFlow(").append(flow).append(", metric, result);\n")
                    .append("        }\n")
                    .append("    }\n\n");
        }
    }

    private static void appendSources(
            final StringBuilder root,
            final StringBuilder classes,
            final Map<Integer, StringBuilder> flows,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Map<String, Integer> handlers,
            final int[] metricIndexes,
            final Variant variant
    ) {
        root.append("    @Override\n")
                .append("    public WorkflowRuntime.SourceResult runSource(final String source,\n")
                .append("            final Map<String, RailixValue> values) {\n")
                .append("        if (source == null || source.isBlank()) {\n")
                .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\n")
                .append("                    \"RUN_SOURCE_REQUIRED\", \"Trigger source must be a non-blank string.\",\n")
                .append("                    \"source\"), Map.of());\n        }\n")
                .append("        if (values == null) {\n")
                .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\n")
                .append("                    \"RUN_SOURCE_VALUES_REQUIRED\", \"Trigger source values must be supplied.\",\n")
                .append("                    \"values\"), Map.of());\n        }\n")
                .append("        return ");
        final Map<String, String> cases = new TreeMap<>();
        for (final ApplicationPlan.TriggerPlan trigger : triggers) {
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            cases.put(node.step().source().orElseThrow().name(), "Flow_" + trigger.node() + "."
                    + (variant == Variant.DEVELOPMENT ? "measuredSource_" : "source_") + trigger.node() + "(values)");
        }
        root.append(keySelector(classes, "source", "WorkflowRuntime.SourceResult",
                "final String source, final Map<String, RailixValue> values", "source, values", "source", cases,
                "new WorkflowRuntime.SourceResult(WorkflowRuntime.rejectedResult(\"RUN_SOURCE_UNKNOWN\", \"Project has no Trigger for source: \" + source + \".\", \"source\"), Map.of())"))
                .append(";\n    }\n\n");

        for (int flow = 0; flow < triggers.size(); flow++) {
            final ApplicationPlan.TriggerPlan trigger = triggers.get(flow);
            final StringBuilder source = flows.get(trigger.node());
            final ApplicationPlan.NodePlan node = nodes.get(trigger.node());
            final int handler = handlers.get(node.step().id());
            source.append("    static WorkflowRuntime.SourceResult source_").append(trigger.node())
                    .append("(final Map<String, RailixValue> values) {\n")
                    .append("        final var invalid = WorkflowRuntime.validateSource(")
                    .append(planReference(trigger.node())).append(", values, ")
                    .append(quote(node.path())).append(");\n")
                    .append("        if (invalid.isPresent()) {\n")
                    .append("            return new WorkflowRuntime.SourceResult(invalid.orElseThrow(), Map.of());\n")
                    .append("        }\n")
                    .append("        final WorkflowRuntime.Execution execution = WorkflowRuntime.execution(")
                    .append(resultsReference(trigger)).append(", RailixValue.object(Map.of()),\n")
                    .append("                ").append(runtime(node.id(), variant, "false")).append(");\n");
            if (variant == Variant.DEVELOPMENT && metricIndexes[trigger.node()] >= 0) {
                final int metricIndex = metricIndexes[trigger.node()];
                source.append("        final long stepMetric = METRICS.startStep(")
                        .append(metricIndex).append(");\n")
                        .append("        int outcome = Integer.MIN_VALUE;\n")
                        .append("        RunResult stepResult = null;\n")
                        .append("        try {\n")
                        .append("            outcome = execution.call(")
                        .append(planReference(trigger.node())).append(", ")
                        .append(call(handler, variant, false)).append(", values, ")
                        .append(inputsReference(trigger.node()))
                        .append(");\n")
                        .append("            if (outcome < 0) {\n")
                        .append("                stepResult = execution.finish();\n")
                        .append("            }\n")
                        .append("        } finally {\n")
                        .append("            METRICS.finishStep(").append(metricIndex)
                        .append(", stepMetric, outcome, stepResult);\n")
                        .append("        }\n");
            } else {
                source.append("        final int outcome = execution.call(")
                        .append(planReference(trigger.node())).append(", ")
                        .append(call(handler, variant, false)).append(", values, ")
                        .append(inputsReference(trigger.node())).append(");\n");
            }
            source
                    .append("        if (outcome < 0) {\n")
                    .append(variant == Variant.DEVELOPMENT && metricIndexes[trigger.node()] >= 0
                            ? "            return new WorkflowRuntime.SourceResult(stepResult, Map.of());\n        }\n"
                            : "            return new WorkflowRuntime.SourceResult(execution.finish(), Map.of());\n        }\n")
                    .append("        final int destination = ").append(destination(trigger.node(), node)).append(";\n")
                    .append("        if (destination == UNROUTED) {\n")
                    .append("            return new WorkflowRuntime.SourceResult(WorkflowRuntime.failedResult(\n")
                    .append("                    \"STEP_OUTCOME_UNROUTED\", \"Trigger returned an outcome without a connection: \"\n")
                    .append("                            + ").append(planReference(trigger.node()))
                    .append(".outcomes().get(outcome) + \".\", ")
                    .append(quote(node.id())).append("), Map.of());\n        }\n")
                    .append("        final RunResult result = execute_").append(trigger.node())
                    .append(variant == Variant.PRODUCTION
                            ? "(execution, destination);\n"
                            : "(execution, destination, CALLS);\n")
                    .append("        return result instanceof RunResult.Succeeded\n")
                    .append("                ? new WorkflowRuntime.SourceResult(result, execution.responses(")
                    .append(responseSlotsReference(trigger)).append("))\n")
                    .append("                : new WorkflowRuntime.SourceResult(result, Map.of());\n")
                    .append("    }\n\n");
            if (variant == Variant.DEVELOPMENT) {
                source.append("    static WorkflowRuntime.SourceResult measuredSource_")
                        .append(trigger.node()).append("(final Map<String, RailixValue> values) {\n")
                        .append("        final long metric = METRICS.startFlow(").append(flow).append(");\n")
                        .append("        WorkflowRuntime.SourceResult result = null;\n")
                        .append("        try {\n")
                        .append("            result = source_").append(trigger.node()).append("(values);\n")
                        .append("            return result;\n")
                        .append("        } finally {\n")
                        .append("            METRICS.finishFlow(").append(flow).append(", metric,\n")
                        .append("                    result == null ? null : result.result());\n")
                        .append("        }\n")
                        .append("    }\n\n");
            }
        }
    }

    private static void appendExecutors(
            final Map<Integer, StringBuilder> flows,
            final List<ApplicationPlan.NodePlan> nodes,
            final List<ApplicationPlan.TriggerPlan> triggers,
            final Variant variant
    ) {
        for (int flow = 0; flow < triggers.size(); flow++) {
            final ApplicationPlan.TriggerPlan trigger = triggers.get(flow);
            final StringBuilder source = flows.get(trigger.node());
            final ApplicationPlan.NodePlan triggerNode = nodes.get(trigger.node());
            if (variant == Variant.DEVELOPMENT) {
                source.append("    static RunResult run_").append(trigger.node())
                        .append("(final RailixValue.ObjectValue context, final boolean test) {\n")
                        .append("        if (context.values().containsKey(\"runtime\")) {\n")
                        .append("            return WorkflowRuntime.rejectedResult(\"RUN_RUNTIME_RESERVED\",\n")
                        .append("                    \"context.runtime is supplied by Railix.\", ")
                        .append("\"context.runtime\");\n        }\n")
                        .append("        final long metric = METRICS.startFlow(").append(flow).append(");\n")
                        .append("        RunResult result = null;\n")
                        .append("        try {\n")
                        .append("            final WorkflowRuntime.Execution execution = WorkflowRuntime.execution(")
                        .append(resultsReference(trigger)).append(", context, ")
                        .append(runtime(triggerNode.id(), Variant.DEVELOPMENT, "test")).append(");\n")
                        .append("            result = execute_").append(trigger.node())
                        .append("(execution, ").append(trigger.start()).append(", CALLS);\n")
                        .append("            return result;\n")
                        .append("        } finally {\n")
                        .append("            METRICS.finishFlow(").append(flow).append(", metric, result);\n")
                        .append("        }\n")
                        .append("    }\n\n");
            }
            appendExecutor(source, trigger.node(), false, variant);
            if (variant == Variant.DEVELOPMENT) {
                appendExecutor(source, trigger.node(), true, variant);
            }
        }
    }

    private static void appendExecutor(
            final StringBuilder source,
            final int trigger,
            final boolean trace,
            final Variant variant
    ) {
        source.append("    private static RunResult ")
                .append(trace ? "traceExecute_" : "execute_").append(trigger)
                .append("(final WorkflowRuntime.Execution execution, int current")
                .append(variant == Variant.DEVELOPMENT ? ", final WorkflowRuntime.StepCall[] calls" : "")
                .append(") {\n")
                .append("        while (current != END) {\n");
        if (trace) {
            source.append("            DevelopmentRuntime.Trace.before(current, step_").append(trigger)
                    .append("(current), use_").append(trigger)
                    .append("(current), execution.context());\n");
        }
        source.append("            final int outcome = dispatch_").append(trigger)
                .append(variant == Variant.PRODUCTION
                        ? "(execution, current);\n"
                        : "(execution, current, calls);\n")
                .append("            if (outcome < 0) {\n")
                .append("                final RunResult result = execution.finish();\n");
        if (trace) {
            source.append("                DevelopmentRuntime.Trace.after(step_").append(trigger)
                    .append("(current), result, execution.context());\n");
        }
        source.append("                return result;\n            }\n");
        if (trace) {
            source.append("            final String outcomeName = outcome_").append(trigger)
                    .append("(current, outcome);\n")
                    .append("            DevelopmentRuntime.Trace.after(step_").append(trigger)
                    .append("(current), outcomeName, execution.context());\n");
        }
        source.append("            final int destination = destination_").append(trigger)
                .append("(current, outcome);\n")
                .append("            if (destination == UNROUTED) {\n");
        if (!trace) {
            source.append("                final String outcomeName = outcome_").append(trigger)
                    .append("(current, outcome);\n");
        }
        source.append("                return WorkflowRuntime.failedResult(\"STEP_OUTCOME_UNROUTED\",\n")
                .append("                        \"Step returned an outcome without a connection: \" + outcomeName + \".\",\n")
                .append("                        step_").append(trigger).append("(current));\n")
                .append("            }\n")
                .append("            current = destination;\n        }\n")
                .append("        return execution.finish();\n    }\n\n");
    }

    private static void appendDispatch(
            final StringBuilder root,
            final Map<Integer, StringBuilder> flows,
            final StringBuilder classes,
            final List<ApplicationPlan.NodePlan> nodes,
            final Map<String, Integer> handlers,
            final int[] metricIndexes,
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
        root.append("    static int missingPlan(\n")
                .append("            final WorkflowRuntime.Execution execution, final int current) {\n")
                .append("        return execution.abort(WorkflowRuntime.failedResult(\n")
                .append("                \"RUN_PLAN_MISSING\", \"Compiled Step plan is missing.\",\n")
                .append("                Integer.toString(current)));\n")
                .append("    }\n\n");
        for (final Map.Entry<Integer, List<Integer>> entry : owned.entrySet()) {
            final int trigger = entry.getKey();
            final StringBuilder source = flows.get(trigger);
            final Map<Integer, List<Integer>> partitions = partitions(entry.getValue());
            final Map<Integer, String> cases = new LinkedHashMap<>();
            final String arguments = variant == Variant.DEVELOPMENT
                    ? "execution, current, calls" : "execution, current";
            for (final int partition : partitions.keySet()) {
                cases.put(partition, "Routes_" + trigger + "_" + partition + ".dispatch(" + arguments + ")");
            }
            appendSelector(source, classes, "dispatch_" + trigger, "int",
                    "final WorkflowRuntime.Execution execution, final int current"
                            + (variant == Variant.DEVELOPMENT ? ", final WorkflowRuntime.StepCall[] calls" : ""),
                    arguments, cases, "missingPlan(execution, current)");
            appendRouting(source, classes, trigger, partitions, nodes, handlers, metricIndexes, variant);
        }
    }

    private static void appendRouting(
            final StringBuilder root,
            final StringBuilder classes,
            final int trigger,
            final Map<Integer, List<Integer>> partitions,
            final List<ApplicationPlan.NodePlan> nodes,
            final Map<String, Integer> handlers,
            final int[] metricIndexes,
            final Variant variant
    ) {
        appendRouteSelector(
                root, classes, trigger, partitions, "String", "step", "current", "Integer.toString(current)", false
        );
        if (variant == Variant.DEVELOPMENT) {
            appendRouteSelector(
                    root, classes, trigger, partitions, "String", "use", "current", "\"\"", false
            );
        }
        appendRouteSelector(
                root, classes, trigger, partitions, "String", "outcome", "current, outcome", "\"unknown\"", true
        );
        appendRouteSelector(
                root, classes, trigger, partitions, "int", "destination", "current, outcome", "UNROUTED", true
        );

        final StringBuilder source = classes;
        for (final Map.Entry<Integer, List<Integer>> partition : partitions.entrySet()) {
            final String owner = "Routes_" + trigger + "_" + partition.getKey();
            source.append("final class ").append(owner).append(" {\n");
            source.append("        static int dispatch(\n")
                    .append("                final WorkflowRuntime.Execution execution, final int current")
                    .append(variant == Variant.DEVELOPMENT
                            ? ", final WorkflowRuntime.StepCall[] calls"
                            : "")
                    .append(") {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                final ApplicationPlan.NodePlan node = nodes.get(index);
                source.append("                case ").append(index).append(" -> ");
                if (variant == Variant.DEVELOPMENT && metricIndexes[index] >= 0) {
                    final int metricIndex = metricIndexes[index];
                    source.append("{\n")
                            .append("                    final long metric = METRICS.startStep(")
                            .append(metricIndex).append(");\n")
                            .append("                    int outcome = Integer.MIN_VALUE;\n")
                            .append("                    RunResult stepResult = null;\n")
                            .append("                    try {\n")
                            .append("                        outcome = execution.call(")
                            .append(planReference(index)).append(", ")
                            .append(call(handlers.get(node.step().id()), variant, true)).append(", Map.of(), ")
                            .append(inputsReference(index)).append(");\n")
                            .append("                        if (outcome < 0) {\n")
                            .append("                            stepResult = execution.finish();\n")
                            .append("                        }\n")
                            .append("                        yield outcome;\n")
                            .append("                    } finally {\n")
                            .append("                        METRICS.finishStep(").append(metricIndex)
                            .append(", metric, outcome, stepResult);\n")
                            .append("                    }\n")
                            .append("                }\n");
                } else {
                    source.append("execution.call(").append(planReference(index)).append(", ")
                            .append(call(handlers.get(node.step().id()), variant, true)).append(", Map.of(), ")
                            .append(inputsReference(index)).append(");\n");
                }
            }
            source.append("                default -> missingPlan(execution, current);\n")
                    .append("            };\n        }\n\n")
                    .append("        static String step(final int current) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(quote(nodes.get(index).id())).append(";\n");
            }
            source.append("                default -> Integer.toString(current);\n")
                    .append("            };\n        }\n\n");
            if (variant == Variant.DEVELOPMENT) {
                source.append("        static String use(final int current) {\n")
                        .append("            return switch (current) {\n");
                for (final int index : partition.getValue()) {
                    source.append("                case ").append(index).append(" -> ")
                            .append(quote(nodes.get(index).step().id())).append(";\n");
                }
                source.append("                default -> \"\";\n")
                        .append("            };\n        }\n\n");
            }
            source.append("        static String outcome(final int current, final int outcome) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(planReference(index)).append(".outcomes().get(outcome);\n");
            }
            source.append("                default -> \"unknown\";\n            };\n        }\n\n")
                    .append("        static int destination(final int current, final int outcome) {\n")
                    .append("            return switch (current) {\n");
            for (final int index : partition.getValue()) {
                source.append("                case ").append(index).append(" -> ")
                        .append(destination(index, nodes.get(index)))
                        .append(";\n");
            }
            source.append("                default -> UNROUTED;\n            };\n        }\n\n")
                    .append("        private ").append(owner).append("() {\n        }\n")
                    .append("    }\n\n");
        }
    }

    private static void appendRouteSelector(
            final StringBuilder source,
            final StringBuilder classes,
            final int trigger,
            final Map<Integer, List<Integer>> partitions,
            final String type,
            final String name,
            final String arguments,
            final String fallback,
            final boolean usesOutcome
    ) {
        final Map<Integer, String> cases = new LinkedHashMap<>();
        for (final int partition : partitions.keySet()) {
            cases.put(partition, "Routes_" + trigger + "_" + partition + "." + name + "(" + arguments + ")");
        }
        appendSelector(source, classes, name + "_" + trigger, type,
                "final int current" + (usesOutcome ? ", final int outcome" : ""), arguments, cases, fallback);
    }

    private static void appendSelector(
            final StringBuilder source, final StringBuilder classes, final String name, final String type,
            final String parameters, final String arguments, final Map<Integer, String> cases, final String fallback
    ) {
        source.append("    private static ").append(type).append(' ').append(name).append('(')
                .append(parameters).append(") {\n        return ")
                .append(selector(classes, name, type, parameters, arguments, new ArrayList<>(cases.entrySet()), fallback,
                        "current / NODE_PARTITION_SIZE", Object::toString, key -> "current / NODE_PARTITION_SIZE < " + key))
                .append(";\n    }\n\n");
    }

    private static String keySelector(
            final StringBuilder classes, final String name, final String type, final String parameters,
            final String arguments, final String key, final Map<String, String> cases, final String fallback
    ) {
        return selector(classes, name, type, parameters, arguments, new ArrayList<>(cases.entrySet()), fallback,
                key, ApplicationGenerator::quote, value -> key + ".compareTo(" + quote(value) + ") < 0");
    }

    // Bound both method bytecode and each class's constant pool, including the dispatch tree itself.
    private static <K> String selector(
            final StringBuilder classes, final String name, final String type, final String parameters,
            final String arguments, final List<Map.Entry<K, String>> cases, final String fallback,
            final String key, final Function<K, String> literal, final Function<K, String> below
    ) {
        if (cases.size() <= ROUTE_PARTITION_SIZE) {
            final StringBuilder result = new StringBuilder("switch (" + key + ") {\n");
            cases.forEach(entry -> result.append("            case ").append(literal.apply(entry.getKey()))
                    .append(" -> ").append(entry.getValue()).append(";\n"));
            return result.append("            default -> ").append(fallback).append(";\n        }").toString();
        }
        final int middle = cases.size() / 2;
        final String owner = "Select_" + name;
        final String left = selector(classes, name + "L", type, parameters, arguments, cases.subList(0, middle), fallback, key, literal, below);
        final String right = selector(classes, name + "R", type, parameters, arguments, cases.subList(middle, cases.size()), fallback, key, literal, below);
        classes.append("final class ").append(owner).append(" {\n    static ").append(type)
                .append(" select(").append(parameters).append(") {\n        return ")
                .append(below.apply(cases.get(middle).getKey())).append(" ? ").append(left).append(" : ")
                .append(right).append(";\n    }\n}\n");
        return owner + ".select(" + arguments + ")";
    }

    private static Map<Integer, List<Integer>> partitions(final List<Integer> indexes) {
        final Map<Integer, List<Integer>> partitions = new LinkedHashMap<>();
        for (final int index : indexes) {
            partitions.computeIfAbsent(index / ROUTE_PARTITION_SIZE, ignored -> new ArrayList<>()).add(index);
        }
        return partitions;
    }

    private static int[] metricIndexes(final List<ApplicationPlan.NodePlan> nodes) {
        final int[] indexes = new int[nodes.size()];
        int next = 0;
        for (int node = 0; node < nodes.size(); node++) {
            indexes[node] = nodes.get(node).step().kind() != StepDefinition.Kind.APP && nodes.get(node).metrics()
                    ? next++ : -1;
        }
        return indexes;
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

        private NodeCompiler(
                final int node,
                final Map<String, Integer> handlers
        ) {
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
                            receives, returns, plan.outcomes(), plan.path()
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
                        .append(candidates(candidates.candidates())).append(", execution);\n");
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
                                + quote(candidate.outcome()) + ", "
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
                        runtimeStep(
                                step.step().id(), step.step(), false, "Map.of()", "Map.of()",
                                step.step().outcomes(), step.path()
                        )
                );
                nested.add(field(
                        "WorkflowRuntime.NestedStep",
                        "new WorkflowRuntime.NestedStep(" + plan + ", " + resolver + ", " + quote(step.path())
                                + ", " + handlerReference(handlers.get(step.step().id())) + ")"
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
            fields.append("static final ").append(type).append(' ').append(name)
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
            final StepDefinition step,
            final boolean mappedReceives,
            final String receives,
            final String returns,
            final List<String> outcomes,
            final String path
    ) {
        return "new WorkflowRuntime.StepPlan(\n"
                + indent(quote(id), 1) + ",\n"
                + indent(quote(step.id()), 1) + ",\n"
                + indent(Boolean.toString(mappedReceives), 1) + ",\n"
                + indent(list(step.receives().stream().map(ApplicationGenerator::port).toList()), 1) + ",\n"
                + indent(list(step.returns().stream().map(ApplicationGenerator::port).toList()), 1) + ",\n"
                + indent(strings(outcomes), 1) + ",\n"
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

    private static String results(final ApplicationPlan.NodePlan trigger) {
        return list(trigger.step().results().stream().map(result ->
                "new WorkflowRuntime.ResultPlan(" + quote(result.name())
                        + ", ValueShape." + result.shape().name() + ", "
                        + values(result.defaults()) + ")").toList());
    }

    private static String resultsReference(final ApplicationPlan.TriggerPlan trigger) {
        return "RESULTS_" + trigger.node();
    }

    private static String runtime(
            final String trigger,
            final Variant variant,
            final String test
    ) {
        final String triggerValue = "\"trigger\", RailixValue.string(" + quote(trigger) + ")";
        return "RailixValue.object(Map.of(" + (variant == Variant.PRODUCTION
                ? triggerValue
                : "\"test\", RailixValue.bool(" + test + "), " + triggerValue) + "))";
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

    private static String call(final int handler, final Variant variant, final boolean routed) {
        if (variant == Variant.PRODUCTION) {
            return "Handlers_" + handler / PLAN_PARTITION_SIZE + ".CALL_" + handler;
        }
        return (routed ? "calls" : "CALLS") + "[" + handler + "]";
    }

    private static String handlerReference(final int handler) {
        return "Handlers_" + handler / PLAN_PARTITION_SIZE + ".HANDLER_" + handler;
    }

    private static String destination(final int index, final ApplicationPlan.NodePlan node) {
        if (node.outcomes().size() > PLAN_PARTITION_SIZE) {
            return "Outcomes_" + index + ".destination(outcome)";
        }
        final int[] destinations = node.destinations();
        final StringBuilder result = new StringBuilder("switch (outcome) {");
        for (int outcome = 0; outcome < destinations.length; outcome++) {
            result.append(" case ").append(outcome).append(" -> ").append(destinations[outcome]).append(';');
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

    private static String array(final StringBuilder classes, final String name, final String type, final List<String> values) {
        if (values.size() <= PLAN_PARTITION_SIZE) {
            return "new " + type + "[]{" + String.join(", ", values) + "}";
        }
        appendArray(classes, name, type, values, 0, values.size());
        classes.append("final class Constants_").append(name).append(" {\n    static ").append(type).append("[] create() {\n")
                .append("        final ").append(type).append("[] values = new ").append(type).append('[').append(values.size()).append("];\n")
                .append("        Constants_").append(name).append("_0_").append(values.size()).append(".fill(values);\n")
                .append("        return values;\n    }\n}\n");
        return "Constants_" + name + ".create()";
    }

    private static void appendArray(
            final StringBuilder classes, final String name, final String type, final List<String> values, final int start, final int end
    ) {
        final String owner = "Constants_" + name + "_" + start + "_" + end;
        final StringBuilder body = new StringBuilder();
        if (end - start <= ROUTE_PARTITION_SIZE) {
            for (int index = start; index < end; index++) {
                body.append("        values[").append(index).append("] = ").append(values.get(index)).append(";\n");
            }
        } else {
            final int middle = (start + end) >>> 1;
            appendArray(classes, name, type, values, start, middle);
            appendArray(classes, name, type, values, middle, end);
            body.append("        Constants_").append(name).append('_').append(start).append('_').append(middle)
                    .append(".fill(values);\n        Constants_").append(name).append('_').append(middle).append('_')
                    .append(end).append(".fill(values);\n");
        }
        classes.append("final class ").append(owner).append(" {\n    static void fill(final ").append(type).append("[] values) {\n")
                .append(body).append("    }\n}\n");
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
            Map<String, String> developmentResources,
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
