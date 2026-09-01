package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.PrimitiveSteps;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the real generated applications used by the built-in Step conformance suite. */
final class PrimitiveGeneratedApplications implements AutoCloseable {
    private static final String DEFAULT_CONFIG = "{}";
    private final Map<String, Application> applications;
    private final List<GeneratedApplicationFixture> fixtures;
    private final SourceProbe sourceProbe;

    private PrimitiveGeneratedApplications(
            final Map<String, Application> applications,
            final List<GeneratedApplicationFixture> fixtures,
            final SourceProbe sourceProbe
    ) {
        this.applications = Map.copyOf(applications);
        this.fixtures = List.copyOf(fixtures);
        this.sourceProbe = sourceProbe;
    }

    static PrimitiveGeneratedApplications start(final Path workspace) throws IOException {
        final List<Scenario> scenarios = scenarios();
        final Map<String, Application> applications = new LinkedHashMap<>();
        final List<GeneratedApplicationFixture> fixtures = new ArrayList<>();
        final Map<String, Path> jars = new LinkedHashMap<>();
        try {
            for (final String family : List.of("scalar", "structure", "control")) {
                final List<Scenario> selected = scenarios.stream()
                        .filter(scenario -> scenario.family().equals(family))
                        .toList();
                final BuiltProject project = project(family, selected);
                final Path familyWorkspace = workspace.resolve(family);
                final GeneratedApplicationFixture fixture = GeneratedApplicationFixture.start(
                        familyWorkspace,
                        project.source()
                );
                fixtures.add(fixture);
                jars.put(family, generatedJar(familyWorkspace));
                project.evidence().forEach((key, evidence) -> applications.put(
                        key,
                        new Application(
                                fixture,
                                family,
                                family + "-command",
                                evidence.scenario(),
                                evidence.step(),
                                evidence.nestedPath()
                        )
                ));
            }
            return new PrimitiveGeneratedApplications(
                    applications,
                    fixtures,
                    SourceProbe.compile(workspace.resolve("source-probe"), jars)
            );
        } catch (final IOException | RuntimeException exception) {
            fixtures.forEach(GeneratedApplicationFixture::close);
            throw exception;
        }
    }

    Application operation(final List<String> primitives) {
        return required(key(Mode.RESULT, primitives, "", DEFAULT_CONFIG));
    }

    Application configured(
            final List<String> primitives,
            final String configuredPrimitive,
            final String config
    ) {
        return required(key(Mode.RESULT, primitives, configuredPrimitive, config));
    }

    Application fallible(final List<String> primitives) {
        return required(key(Mode.PRESERVE_SOURCE, primitives, "", DEFAULT_CONFIG));
    }

    Application target(final String primitive, final String config) {
        return required(key(Mode.PRESERVE_TARGET, List.of(primitive), primitive, config));
    }

    Application branch(final String primitive, final String conditions) {
        return required(key(Mode.BRANCH, List.of(primitive), primitive, conditions));
    }

    Application linear(final String steps) {
        return required(key(Mode.LINEAR, List.of(), "", steps));
    }

    Application cliDefaults() {
        return required(key(Mode.CLI_DEFAULTS, List.of(), "", DEFAULT_CONFIG));
    }

    RunResult run(final Application application, final RailixValue.ObjectValue context) {
        return run(application, context, false);
    }

    RunResult runTest(final Application application, final RailixValue.ObjectValue context) {
        return run(application, context, true);
    }

    RunResult runSource(final Application application, final String input) {
        return sourceProbe.run(application.family(), application.scenario(), input);
    }

    @Override
    public void close() {
        fixtures.forEach(GeneratedApplicationFixture::close);
    }

    private RunResult run(
            final Application application,
            final RailixValue.ObjectValue context,
            final boolean test
    ) {
        final Map<String, RailixValue> root = new LinkedHashMap<>(context.values());
        final RailixValue payloadValue = root.getOrDefault("payload", RailixValue.object(Map.of()));
        if (!(payloadValue instanceof RailixValue.ObjectValue payload)) {
            throw new IllegalArgumentException("Primitive conformance context payload must be an object.");
        }
        final Map<String, RailixValue> values = new LinkedHashMap<>(payload.values());
        values.put("scenario", RailixValue.string(application.scenario()));
        root.put("payload", RailixValue.object(values));
        final String source = RailixJson.write(RailixValue.object(root));
        try {
            final DevelopmentApplication.Response response = test
                    ? application.fixture().runTest(application.trigger(), source)
                    : application.fixture().run(application.trigger(), source);
            return response(response, application.scenario());
        } catch (final IOException exception) {
            throw new AssertionError("Generated primitive application request failed.", exception);
        }
    }

    private Application required(final String key) {
        final Application application = applications.get(key);
        if (application == null) {
            throw new IllegalArgumentException("Generated primitive scenario is not registered: " + key + ".");
        }
        return application;
    }

    private static List<Scenario> scenarios() {
        final Map<String, Scenario> scenarios = new LinkedHashMap<>();
        PrimitiveSteps.definitions().forEach(definition -> add(
                scenarios,
                Mode.RESULT,
                List.of(definition.id()),
                "",
                DEFAULT_CONFIG
        ));

        List.of(
                List.of("text.uppercase", "text.lowercase"),
                List.of("text.trim", "text.uppercase"),
                List.of("text.normalize-space", "text.uppercase"),
                List.of("text.normalize-nfc", "text.length"),
                List.of("text.length", "number.sign"),
                List.of("text.is-empty", "boolean.not"),
                List.of("number.ceil", "number.negate", "number.abs", "number.round", "number.sign"),
                List.of("list.is-empty", "boolean.not"),
                List.of("boolean.to-text", "text.uppercase"),
                List.of("boolean.to-number", "number.negate"),
                List.of("list.reverse", "list.reverse"),
                List.of("number.to-text", "text.length"),
                List.of("value.wrap-list", "list.size"),
                List.of("value.to-json", "text.length"),
                List.of("list.size", "number.floor"),
                List.of("date.is-utc-millis", "boolean.not")
        ).forEach(primitives -> add(scenarios, Mode.RESULT, primitives, "", DEFAULT_CONFIG));

        textScenarios(scenarios);
        numberScenarios(scenarios);
        valueScenarios(scenarios);
        collectionScenarios(scenarios);
        controlScenarios(scenarios);
        return List.copyOf(scenarios.values());
    }

    private static void textScenarios(final Map<String, Scenario> scenarios) {
        for (final String needle : List.of("", "Rail", "Other", "railix", "7")) {
            add(scenarios, Mode.RESULT, List.of("text.contains"), "text.contains", text("needle", needle));
        }
        add(scenarios, Mode.RESULT, List.of("text.contains"), "text.contains", DEFAULT_CONFIG);
        add(scenarios, Mode.RESULT, List.of("text.contains", "boolean.not"), "text.contains", text("needle", "Rail"));

        boundaryScenarios(scenarios, "text.starts-with", "prefix", List.of("", "Nano", "Other", "nano", "7", "Railix"));
        boundaryScenarios(scenarios, "text.ends-with", "suffix", List.of("", "Railix", "Other", "railix", "7"));
        add(scenarios, Mode.PRESERVE_SOURCE, List.of("text.to-number"), "", DEFAULT_CONFIG);
        add(scenarios, Mode.PRESERVE_SOURCE, List.of("text.to-number", "number.floor"), "", DEFAULT_CONFIG);
    }

    private static void boundaryScenarios(
            final Map<String, Scenario> scenarios,
            final String primitive,
            final String input,
            final List<String> values
    ) {
        add(scenarios, Mode.RESULT, List.of(primitive), primitive, DEFAULT_CONFIG);
        values.forEach(value -> add(scenarios, Mode.RESULT, List.of(primitive), primitive, text(input, value)));
        add(
                scenarios,
                Mode.RESULT,
                List.of(primitive, "boolean.not"),
                primitive,
                text(input, "Railix")
        );
    }

    private static void numberScenarios(final Map<String, Scenario> scenarios) {
        final List<String> comparisons = List.of(
                "number.greater-than",
                "number.greater-or-equal",
                "number.less-than",
                "number.less-or-equal"
        );
        final List<String> values = List.of("0.1", "0.1000000000000000000001", "5");
        for (final String primitive : comparisons) {
            add(scenarios, Mode.RESULT, List.of(primitive), primitive, DEFAULT_CONFIG);
            values.forEach(value -> add(
                    scenarios,
                    Mode.RESULT,
                    List.of(primitive),
                    primitive,
                    "{\"than\":" + value + "}"
            ));
            add(
                    scenarios,
                    Mode.RESULT,
                    List.of("number.abs", primitive, "boolean.not"),
                    primitive,
                    "{\"than\":5}"
            );
        }
    }

    private static void valueScenarios(final Map<String, Scenario> scenarios) {
        final List<String> expected = List.of(
                "null",
                "1",
                "true",
                "\"Railix\"",
                "[1]",
                "[1,2]",
                "{\"first\":1}",
                "{\"first\":1,\"second\":2}",
                "{\"items\":[1,{\"active\":true}]}",
                "{\"user\":{\"id\":1}}"
        );
        for (final String primitive : List.of("value.equals", "value.not-equals")) {
            add(scenarios, Mode.RESULT, List.of(primitive), primitive, DEFAULT_CONFIG);
            expected.forEach(value -> add(
                    scenarios,
                    Mode.RESULT,
                    List.of(primitive),
                    primitive,
                    "{\"expected\":" + value + "}"
            ));
            add(
                    scenarios,
                    Mode.RESULT,
                    List.of(primitive, "boolean.not"),
                    primitive,
                    "{\"expected\":1}"
            );
        }
    }

    private static void collectionScenarios(final Map<String, Scenario> scenarios) {
        for (final String primitive : List.of("list.sum", "list.min", "list.max", "list.percentile")) {
            add(scenarios, Mode.RESULT, List.of(primitive), primitive, DEFAULT_CONFIG);
            add(scenarios, Mode.PRESERVE_TARGET, List.of(primitive), primitive, DEFAULT_CONFIG);
        }
        for (final String percentile : List.of("0", "50", "100")) {
            add(
                    scenarios,
                    Mode.RESULT,
                    List.of("list.percentile"),
                    "list.percentile",
                    "{\"percentile\":" + percentile + "}"
            );
        }
    }

    private static void controlScenarios(final Map<String, Scenario> scenarios) {
        final String field = "[{\"option\":\"field\",\"inputs\":{"
                + "\"field\":[\"context\",\"payload\",\"value\"]},"
                + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                + "\"inputs\":{\"expected\":\"allow\"}}]]}}]";
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter", field);
        add(scenarios, Mode.BRANCH, List.of("railix.choice"), "railix.choice", "[" + field + "]");
        for (final String primitive : List.of("railix.filter", "railix.choice")) {
            add(scenarios, Mode.BRANCH, List.of(primitive), primitive, "[]");
        }
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter",
                "[{\"option\":\"literal\",\"inputs\":{\"value\":\"constant\"}}]");
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter",
                "[{\"option\":\"field\",\"inputs\":{\"field\":[\"context\",\"payload\",\"value\"]}}]");
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter",
                "[{\"option\":\"field\",\"inputs\":{\"field\":[\"context\",\"payload\",\"missing\"]}}]");
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter",
                "[{\"option\":\"literal\",\"inputs\":{\"value\":null}}]");
        add(scenarios, Mode.BRANCH, List.of("railix.filter"), "railix.filter",
                "[{\"option\":\"field\",\"inputs\":{\"field\":[\"context\",\"payload\",\"value\"]},"
                        + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                        + "\"inputs\":{\"expected\":\"never\"}}]]}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"value\":\"fallback\"}}]");
        add(scenarios, Mode.LINEAR, List.of(), "", "[]");
        add(scenarios, Mode.LINEAR, List.of(), "", "[{\"use\":\"text.to-number\",\"inputs\":{}}]");
        add(scenarios, Mode.CLI_DEFAULTS, List.of(), "", DEFAULT_CONFIG);
    }

    private static void add(
            final Map<String, Scenario> scenarios,
            final Mode mode,
            final List<String> primitives,
            final String configuredPrimitive,
            final String config
    ) {
        final String key = key(mode, primitives, configuredPrimitive, config);
        scenarios.putIfAbsent(key, new Scenario(
                key,
                family(mode, primitives),
                "scenario-" + scenarios.size(),
                mode,
                List.copyOf(primitives),
                configuredPrimitive,
                canonical(config)
        ));
    }

    private static String family(final Mode mode, final List<String> primitives) {
        if (mode == Mode.BRANCH || mode == Mode.LINEAR || mode == Mode.CLI_DEFAULTS) {
            return "control";
        }
        final String primitive = primitives.getFirst();
        return primitive.startsWith("list.") || primitive.startsWith("value.")
                ? "structure"
                : "scalar";
    }

    private static String key(
            final Mode mode,
            final List<String> primitives,
            final String configuredPrimitive,
            final String config
    ) {
        return mode + "|" + String.join(",", primitives) + "|" + configuredPrimitive + "|" + canonical(config);
    }

    private static String canonical(final String source) {
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed value)) {
            throw new IllegalArgumentException("Primitive conformance JSON is invalid: " + source + ".");
        }
        return RailixJson.write(value.value());
    }

    private static String text(final String name, final String value) {
        return "{\"" + name + "\":" + RailixJson.write(RailixValue.string(value)) + "}";
    }

    private static BuiltProject project(final String family, final List<Scenario> scenarios) {
        final List<String> nodes = new ArrayList<>();
        final List<String> links = new ArrayList<>();
        final Map<String, Evidence> evidence = new LinkedHashMap<>();
        nodes.add("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}");
        nodes.add("{\"id\":\"" + family + "-command\",\"use\":\"railix.trigger.cli\","
                + "\"inputs\":{},\"examples\":[{\"name\":\"default\",\"payload\":[],"
                + "\"context\":{\"payload\":{\"scenario\":\"" + scenarios.getFirst().id()
                + "\",\"value\":null}}}]}");
        links.add("{\"from\":\"app.start\",\"to\":\"" + family + "-command\"}");
        links.add("{\"from\":\"" + family + "-command.next\",\"to\":\"dispatch-0\"}");

        for (int index = 0; index < scenarios.size(); index++) {
            final Scenario scenario = scenarios.get(index);
            nodes.add(dispatch(index, scenario.id()));
        }
        int nodeIndex = 2 + scenarios.size();
        for (int index = 0; index < scenarios.size(); index++) {
            final Scenario scenario = scenarios.get(index);
            final Rendered rendered = render(scenario, nodeIndex);
            nodes.addAll(rendered.nodes());
            links.add("{\"from\":\"dispatch-" + index + ".match\",\"to\":\""
                    + rendered.entry() + "\"}");
            links.add("{\"from\":\"dispatch-" + index + ".otherwise\",\"to\":\""
                    + (index + 1 < scenarios.size() ? "dispatch-" + (index + 1) : "end") + "\"}");
            links.addAll(rendered.links());
            evidence.put(scenario.key(), new Evidence(
                    scenario.id(),
                    rendered.step(),
                    rendered.stepIndex() < 0
                            ? ""
                            : "nodes[" + rendered.stepIndex() + "].inputs.steps[0]"
            ));
            nodeIndex += rendered.nodes().size();
        }
        return new BuiltProject(
                "{\"format\":1,\"id\":\"primitive-" + family + "\",\"nodes\":["
                        + String.join(",", nodes) + "],\"links\":[" + String.join(",", links) + "]}",
                Map.copyOf(evidence)
        );
    }

    private static String dispatch(final int index, final String scenario) {
        final String expected = RailixJson.write(RailixValue.string(scenario));
        return "{\"id\":\"dispatch-" + index + "\",\"use\":\"railix.choice\",\"inputs\":{"
                + "\"conditions\":["
                + matcher("[\"context\",\"payload\",\"scenario\"]", expected) + ","
                + matcher("[\"context\",\"payload\",\"arguments\",0]", expected)
                + "]}}";
    }

    private static String matcher(final String path, final String expected) {
        return "[{\"option\":\"field\",\"inputs\":{\"field\":" + path
                + "},\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                + "\"inputs\":{\"expected\":" + expected + "}}]]}}]";
    }

    private static Rendered render(final Scenario scenario, final int nodeIndex) {
        final String base = scenario.id();
        return switch (scenario.mode()) {
            case RESULT -> new Rendered(
                    List.of(manipulation(base + "-apply", "[\"context\",\"result\"]", "field", steps(scenario))),
                    List.of(link(base + "-apply.next", "end")),
                    base + "-apply",
                    base + "-apply",
                    nodeIndex
            );
            case PRESERVE_SOURCE -> new Rendered(
                    List.of(
                            manipulation(base + "-apply", "[\"context\",\"payload\",\"value\"]", "current", steps(scenario)),
                            copy(base + "-result", "[\"context\",\"result\"]", "[\"context\",\"payload\",\"value\"]")
                    ),
                    List.of(link(base + "-apply.next", base + "-result"), link(base + "-result.next", "end")),
                    base + "-apply",
                    base + "-apply",
                    nodeIndex
            );
            case PRESERVE_TARGET -> new Rendered(
                    List.of(
                            manipulation(base + "-apply", "[\"context\",\"payload\",\"target\"]", "field", steps(scenario)),
                            copy(base + "-result", "[\"context\",\"result\"]", "[\"context\",\"payload\",\"target\"]")
                    ),
                    List.of(link(base + "-apply.next", base + "-result"), link(base + "-result.next", "end")),
                    base + "-apply",
                    base + "-apply",
                    nodeIndex
            );
            case BRANCH -> branch(scenario, nodeIndex);
            case LINEAR -> new Rendered(
                    List.of(
                            manipulation(base + "-apply", "[\"context\",\"payload\",\"missing\"]", "current", scenario.config()),
                            literal(base + "-continued", "[\"context\",\"result\"]", "\"continued\"")
                    ),
                    List.of(link(base + "-apply.next", base + "-continued"), link(base + "-continued.next", "end")),
                    base + "-apply",
                    base + "-apply",
                    nodeIndex
            );
            case CLI_DEFAULTS -> new Rendered(List.of(), List.of(), "end", "", -1);
        };
    }

    private static Rendered branch(final Scenario scenario, final int nodeIndex) {
        final String base = scenario.id();
        final String step = scenario.config().contains("\"expected\":\"allow\"")
                ? scenario.configuredPrimitive().substring("railix.".length())
                : base + "-branch";
        return new Rendered(
                List.of(
                        "{\"id\":\"" + step + "\",\"use\":\"" + scenario.configuredPrimitive()
                                + "\",\"inputs\":{\"conditions\":" + scenario.config() + "}}",
                        literal(base + "-matched", "[\"context\",\"result\"]", "\"matched\""),
                        literal(base + "-otherwise", "[\"context\",\"result\"]", "\"otherwise\"")
                ),
                List.of(
                        link(step + ".match", base + "-matched"),
                        link(step + ".otherwise", base + "-otherwise"),
                        link(base + "-matched.next", "end"),
                        link(base + "-otherwise.next", "end")
                ),
                step,
                step,
                nodeIndex
        );
    }

    private static String manipulation(
            final String id,
            final String field,
            final String source,
            final String steps
    ) {
        final String value = source.equals("current")
                ? "[{\"option\":\"current\",\"inputs\":{}}]"
                : "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"value\"]}},"
                        + "{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\","
                        + "\"arguments\",1]}}]";
        return "{\"id\":\"" + id + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                + "\"field\":" + field + ",\"value\":" + value + ",\"steps\":" + steps + "}}";
    }

    private static String copy(final String id, final String target, final String source) {
        return "{\"id\":\"" + id + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                + "\"field\":" + target + ",\"value\":[{\"option\":\"field\",\"inputs\":{"
                + "\"source\":" + source + "}}],\"steps\":[]}}";
    }

    private static String literal(final String id, final String target, final String value) {
        return "{\"id\":\"" + id + "\",\"use\":\"railix.field-manipulation\",\"inputs\":{"
                + "\"field\":" + target + ",\"value\":[{\"option\":\"literal\",\"inputs\":{"
                + "\"literal\":" + value + "}}],\"steps\":[]}}";
    }

    private static String steps(final Scenario scenario) {
        final List<String> steps = new ArrayList<>();
        for (final String primitive : scenario.primitives()) {
            final String config = primitive.equals(scenario.configuredPrimitive())
                    ? scenario.config()
                    : DEFAULT_CONFIG;
            steps.add("{\"use\":\"" + primitive + "\",\"inputs\":" + config + "}");
        }
        return "[" + String.join(",", steps) + "]";
    }

    private static String link(final String from, final String to) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    private static RunResult response(
            final DevelopmentApplication.Response response,
            final String scenario
    ) {
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        if (!(parsed instanceof RailixJson.Parsed value)
                || !(value.value() instanceof RailixValue.ObjectValue body)) {
            throw new AssertionError("Generated primitive application returned invalid JSON: " + response.body());
        }
        final String status = string(body, "status");
        return switch (status) {
            case "succeeded" -> new RunResult.Succeeded(context(body, scenario));
            case "rejected" -> new RunResult.Rejected(diagnostics(body));
            case "failed" -> {
                final RailixValue.ObjectValue failure = object(body, "failure");
                yield new RunResult.Failed(new RunFailure(
                        string(failure, "code"),
                        string(failure, "message"),
                        string(failure, "step")
                ));
            }
            case "cancelled" -> new RunResult.Cancelled();
            default -> throw new AssertionError("Unknown generated primitive status: " + status + ".");
        };
    }

    private static List<Diagnostic> diagnostics(final RailixValue.ObjectValue body) {
        if (!(body.values().get("diagnostics") instanceof RailixValue.ArrayValue values)) {
            return List.of();
        }
        return values.values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .map(value -> Diagnostic.atPath(
                        string(value, "code"),
                        string(value, "message"),
                        string(value, "path")
                ))
                .toList();
    }

    private static RailixValue.ObjectValue context(
            final RailixValue.ObjectValue body,
            final String scenario
    ) {
        final RailixValue.ObjectValue context = object(body, "context");
        if (!(context.values().get("payload") instanceof RailixValue.ObjectValue payload)
                || !payload.values().getOrDefault("scenario", RailixValue.nullValue())
                .equals(RailixValue.string(scenario))) {
            return context;
        }
        final Map<String, RailixValue> payloadValues = new LinkedHashMap<>(payload.values());
        payloadValues.remove("scenario");
        final Map<String, RailixValue> contextValues = new LinkedHashMap<>(context.values());
        contextValues.put("payload", RailixValue.object(payloadValues));
        return RailixValue.object(contextValues);
    }

    private static RailixValue.ObjectValue object(final RailixValue.ObjectValue value, final String name) {
        return (RailixValue.ObjectValue) value.values().get(name);
    }

    private static String string(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.StringValue) value.values().get(name)).value();
    }

    private static Path generatedJar(final Path workspace) throws IOException {
        try (var files = Files.find(
                workspace.resolve(".railix/build"),
                2,
                (path, attributes) -> attributes.isRegularFile()
                        && "application.jar".equals(path.getFileName().toString())
        )) {
            final List<Path> jars = files.toList();
            if (jars.size() != 1) {
                throw new IOException("Expected one generated primitive application JAR but found "
                        + jars.size() + ".");
            }
            return jars.getFirst();
        }
    }

    private static final class SourceProbe {
        private static final String CLASS_NAME =
                "dev.nanonative.railix.core.project.PrimitiveGeneratedSourceLauncher";
        private static final long PROCESS_TIMEOUT_SECONDS = 30;
        private static final String SOURCE = """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixData;
                import dev.nanonative.railix.core.value.RailixJson;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.math.BigDecimal;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;

                public final class PrimitiveGeneratedSourceLauncher {
                    private PrimitiveGeneratedSourceLauncher() {
                    }

                    public static void main(final String[] arguments) {
                        if (arguments.length != 2) {
                            throw new IllegalArgumentException("Scenario and input specification are required.");
                        }
                        final WorkflowRuntime.SourceResult source = RailixApplication.runtime().runSource(
                                "application.arguments",
                                Map.of("arguments", RailixValue.array(List.of(
                                        RailixValue.string(arguments[0]),
                                        input(arguments[1])
                                )))
                        );
                        write(source.result());
                    }

                    private static RailixValue input(final String specification) {
                        return switch (specification) {
                            case "unpaired-list" -> RailixValue.array(List.of(
                                    RailixValue.string(String.valueOf((char) 0xD800))
                            ));
                            case "unpaired-object" -> RailixValue.object(Map.of(
                                    "value", RailixValue.string(String.valueOf((char) 0xD800))
                            ));
                            case "depth-63" -> nestedArrays(63);
                            case "depth-64" -> nestedArrays(64);
                            case "depth-65" -> nestedArrays(65);
                            case "number-over-domain" -> RailixValue.number(
                                    BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
                            );
                            case "json-byte-limit" -> RailixValue.string(
                                    "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2)
                            );
                            case "json-escaped-byte-limit" -> RailixValue.string(
                                    "\\\\".repeat((RailixData.DEFAULT_MAX_SOURCE_BYTES - 2) / 2)
                            );
                            case "json-byte-over-limit" -> RailixValue.string(
                                    "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 1)
                            );
                            default -> throw new IllegalArgumentException(
                                    "Unknown programmatic input specification: " + specification + "."
                            );
                        };
                    }

                    private static RailixValue nestedArrays(final int depth) {
                        RailixValue value = RailixValue.nullValue();
                        for (int index = 0; index < depth; index++) {
                            value = RailixValue.array(List.of(value));
                        }
                        return value;
                    }

                    private static void write(final RunResult result) {
                        switch (result) {
                            case RunResult.Succeeded succeeded -> {
                                final RailixValue value = succeeded.context().values().get("result");
                                if (value == null) {
                                    throw new IllegalStateException(
                                            "Generated source run did not produce context.result."
                                    );
                                }
                                System.out.println("succeeded");
                                System.out.print(RailixJson.write(value));
                            }
                            case RunResult.Rejected rejected -> {
                                System.out.println("rejected");
                                System.out.print(RailixJson.write(diagnostics(rejected.diagnostics())));
                            }
                            case RunResult.Failed failed -> {
                                System.out.println("failed");
                                System.out.print(RailixJson.write(RailixValue.object(Map.of(
                                        "code", RailixValue.string(failed.failure().code()),
                                        "message", RailixValue.string(failed.failure().message()),
                                        "step", RailixValue.string(failed.failure().stepId()),
                                        "path", RailixValue.string(failed.failure().path())
                                ))));
                            }
                            case RunResult.Cancelled ignored -> System.out.println("cancelled");
                        }
                    }

                    private static RailixValue diagnostics(final List<Diagnostic> diagnostics) {
                        final List<RailixValue> values = new ArrayList<>(diagnostics.size());
                        for (final Diagnostic diagnostic : diagnostics) {
                            values.add(RailixValue.object(Map.of(
                                    "code", RailixValue.string(diagnostic.code()),
                                    "message", RailixValue.string(diagnostic.message()),
                                    "path", RailixValue.string(diagnostic.path()),
                                    "line", RailixValue.number(diagnostic.line()),
                                    "column", RailixValue.number(diagnostic.column())
                            )));
                        }
                        return RailixValue.array(values);
                    }
                }
                """;
        private final Path classes;
        private final Map<String, Path> applications;

        private SourceProbe(final Path classes, final Map<String, Path> applications) {
            this.classes = classes;
            this.applications = Map.copyOf(applications);
        }

        private static SourceProbe compile(
                final Path workspace,
                final Map<String, Path> applications
        ) throws IOException {
            final Path source = workspace.resolve(
                    "src/dev/nanonative/railix/core/project/PrimitiveGeneratedSourceLauncher.java"
            );
            final Path classes = workspace.resolve("classes");
            Files.createDirectories(source.getParent());
            Files.createDirectories(classes);
            Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
            final var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IOException("Generated primitive tests require the JDK Java compiler.");
            }
            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(
                    diagnostics,
                    java.util.Locale.ROOT,
                    StandardCharsets.UTF_8
            )) {
                final boolean compiled = Boolean.TRUE.equals(compiler.getTask(
                        null,
                        files,
                        diagnostics,
                        List.of(
                                "-proc:none",
                                "-encoding", "UTF-8",
                                "--release", Integer.toString(Runtime.version().feature()),
                                "-classpath", applications.values().iterator().next().toString(),
                                "-d", classes.toString()
                        ),
                        null,
                        files.getJavaFileObjects(source)
                ).call());
                if (!compiled) {
                    throw new IOException("Generated primitive source probe did not compile: "
                            + diagnostics.getDiagnostics());
                }
            }
            return new SourceProbe(classes, applications);
        }

        private RunResult run(final String family, final String scenario, final String input) {
            final Path application = applications.get(family);
            if (application == null) {
                throw new IllegalArgumentException("Generated primitive family is unavailable: " + family + ".");
            }
            final ProcessResult process;
            try {
                process = runProcess(List.of(
                        java(),
                        "-cp", classes + File.pathSeparator + application,
                        CLASS_NAME,
                        scenario,
                        input
                ));
            } catch (final IOException exception) {
                throw new AssertionError("Generated primitive source invocation failed.", exception);
            }
            if (process.exitCode() != 0) {
                throw new AssertionError("Generated primitive source invocation exited "
                        + process.exitCode() + ": " + process.output());
            }
            return parse(process.output());
        }

        private static RunResult parse(final String output) {
            final int separator = output.indexOf('\n');
            final String status = separator < 0 ? output.strip() : output.substring(0, separator).strip();
            final String payload = separator < 0 ? "" : output.substring(separator + 1);
            return switch (status) {
                case "succeeded" -> new RunResult.Succeeded(
                        RailixValue.object(Map.of("result", value(payload)))
                );
                case "rejected" -> new RunResult.Rejected(diagnostics(value(payload)));
                case "failed" -> new RunResult.Failed(failure(value(payload)));
                case "cancelled" -> new RunResult.Cancelled();
                default -> throw new AssertionError(
                        "Unknown generated primitive source status: " + status + "."
                );
            };
        }

        private static RailixValue value(final String source) {
            final RailixJson.Result parsed = RailixJson.parse(source);
            if (!(parsed instanceof RailixJson.Parsed value)) {
                throw new AssertionError("Generated primitive source returned invalid JSON.");
            }
            return value.value();
        }

        private static List<Diagnostic> diagnostics(final RailixValue value) {
            return ((RailixValue.ArrayValue) value).values().stream()
                    .map(RailixValue.ObjectValue.class::cast)
                    .map(diagnostic -> new Diagnostic(
                            string(diagnostic, "code"),
                            string(diagnostic, "message"),
                            string(diagnostic, "path"),
                            number(diagnostic, "line"),
                            number(diagnostic, "column")
                    ))
                    .toList();
        }

        private static RunFailure failure(final RailixValue value) {
            final RailixValue.ObjectValue failure = (RailixValue.ObjectValue) value;
            return new RunFailure(
                    string(failure, "code"),
                    string(failure, "message"),
                    string(failure, "step"),
                    string(failure, "path")
            );
        }

        private static int number(final RailixValue.ObjectValue value, final String name) {
            return ((RailixValue.NumberValue) value.values().get(name)).value().intValueExact();
        }

        private static ProcessResult runProcess(final List<String> command) throws IOException {
            final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(command))
                    .redirectErrorStream(true)
                    .start();
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final AtomicReference<IOException> readFailure = new AtomicReference<>();
            final Thread reader = Thread.ofVirtual().name("railix-primitive-source-output").start(() -> {
                try (var input = process.getInputStream()) {
                    input.transferTo(output);
                } catch (final IOException exception) {
                    readFailure.set(exception);
                }
            });
            try {
                process.getOutputStream().close();
                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("Generated primitive source invocation exceeded "
                            + PROCESS_TIMEOUT_SECONDS + " seconds.");
                }
                reader.join();
                if (readFailure.get() != null) {
                    throw readFailure.get();
                }
                return new ProcessResult(
                        process.exitValue(),
                        output.toString(StandardCharsets.UTF_8)
                );
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Generated primitive source invocation was interrupted.", exception);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        private static String java() {
            return Path.of(System.getProperty("java.home"), "bin", "java").toString();
        }

        private record ProcessResult(int exitCode, String output) {
        }
    }

    record Application(
            GeneratedApplicationFixture fixture,
            String family,
            String trigger,
            String scenario,
            String step,
            String nestedPath
    ) {
    }

    private enum Mode {
        RESULT,
        PRESERVE_SOURCE,
        PRESERVE_TARGET,
        BRANCH,
        LINEAR,
        CLI_DEFAULTS
    }

    private record Scenario(
            String key,
            String family,
            String id,
            Mode mode,
            List<String> primitives,
            String configuredPrimitive,
            String config
    ) {
    }

    private record Evidence(String scenario, String step, String nestedPath) {
    }

    private record BuiltProject(String source, Map<String, Evidence> evidence) {
    }

    private record Rendered(
            List<String> nodes,
            List<String> links,
            String entry,
            String step,
            int stepIndex
    ) {
    }
}
