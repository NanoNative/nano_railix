package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class CreatorFirstProjectCompilationE2eTest {
    @Test
    void genericPathOptionsAndStepsProjectCompiles() {
        assertThat(ProjectCompiler.compileApplication(contextProject(), contextCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void appDefaultsToStartOutcome() {
        assertThat(StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define()
                .outcomes()).containsExactly("start");
    }

    @Test
    void triggerDefaultsToNextOutcome() {
        assertThat(StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .define()
                .outcomes()).containsExactly("next");
    }

    @Test
    void ordinaryStepDefaultsToNextOutcome() {
        assertThat(StepDefinition.named("example.step", "1").define().outcomes())
                .containsExactly("next");
    }

    @Test
    void additionalOutcomeFollowsPrimaryOutcome() {
        assertThat(StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .outcome("invalid")
                .define()
                .outcomes()).containsExactly("ok", "invalid");
    }

    @Test
    void explicitPrimaryOutcomeReplacesDefault() {
        assertThat(StepDefinition.named("example.step", "1")
                .primaryOutcome("continue")
                .define()
                .outcomes()).containsExactly("continue");
    }

    @Test
    void triggerExamplesRetainPayloadAndOptionalContextInDeclarationOrder() {
        final StepDefinition definition = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .input("target", Input.path(WRITE))
                .exampleTarget("target")
                .example("first", RailixValue.string("one"))
                .example("second", RailixValue.string("two"), context("context"))
                .define();

        assertThat(definition.examples()).containsExactly(
                new StepDefinition.Example(
                        "first",
                        RailixValue.string("one"),
                        RailixValue.object(Map.of())
                ),
                new StepDefinition.Example(
                        "second",
                        RailixValue.string("two"),
                        context("context")
                )
        );
    }

    @Test
    void unaryStepCompilesAsAnOrdinaryGraphNodeWithExplicitPortPaths() {
        final CompileResult.Compiled compiled = compiled(graphPrimitiveProject(), contextCatalog());

        assertThat(compiled.source())
                .contains("\"receives\":{\"value\":[\"context\",\"payload\",\"name\"]}")
                .contains("\"returns\":{\"value\":[\"context\",\"payload\",\"name\"]}");
    }

    @Test
    void displayNameDefaultsToReadableFinalIdSegment() {
        assertThat(StepDefinition.named("example.value-pipeline", "1").define().displayName())
                .isEqualTo("Value Pipeline");
    }

    @Test
    void displayNameMayDeclareProductSpelling() {
        assertThat(StepDefinition.named("example.cli", "1")
                .displayName("CLI")
                .define()
                .displayName()).isEqualTo("CLI");
    }

    @Test
    void emptyFinalIdSegmentHasDeterministicDisplayName() {
        assertThat(StepDefinition.named("example.", "1").define().displayName())
                .isEqualTo("example.");
    }

    @Test
    void canonicalProjectContainsOnlyGenericInputs() {
        final CompileResult.Compiled compiled = compiled(contextProject(), contextCatalog());

        assertThat(compiled.source())
                .contains("\"inputs\"", "\"option\":\"current\"", "\"use\":\"text.lowercase\"")
                .doesNotContain("\"config\"", "primitive_pipeline");
    }

    @Test
    void canonicalProjectIsStableAfterRecompilation() {
        final CompileResult.Compiled first = compiled(contextProject(), contextCatalog());
        final CompileResult.Compiled second = compiled(first.source(), contextCatalog());

        assertThat(second.source()).isEqualTo(first.source());
    }

    @Test
    void applicationWithoutInstalledTriggersCompiles() {
        final String source = """
                {"format":1,"id":"empty-project","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}}
                ],"links":[]}
                """;

        assertThat(ProjectCompiler.compileApplication(source, contextCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void compilingAProjectNeverInvokesTriggerOrStepHandlers() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition trigger = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("application.example")
                .run(TestStepHandlers.PrimaryOutcome.class);
        final StepDefinition step = StepDefinition.named("example.step", "1")
                .run(TestStepHandlers.PrimaryOutcome.class);
        final String source = """
                {"format":1,"id":"pure-compilation","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":[
                    {"name":"proof","payload":[],"context":{"payload":{}}}
                  ]},
                  {"id":"step","use":"example.step","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"end"}
                ]}
                """;

        assertThat(ProjectCompiler.compileApplication(source, StepCatalog.of(app, trigger, step)))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void applicationCompilationRejectsStepBeyondGeneratedCodeLimit() {
        final String nested = IntStream.range(0, 256)
                .mapToObj(index -> "{\"use\":\"text.lowercase\",\"inputs\":{}}")
                .collect(Collectors.joining(",", "[", "]"));
        final String source = contextProject().replace(
                "[{\"use\":\"text.lowercase\",\"inputs\":{}}]",
                nested
        );

        assertThat(ProjectCompiler.compileApplication(source, contextCatalog()))
                .isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic ->
                                assertThat(diagnostic).extracting(
                                        Diagnostic::code,
                                        Diagnostic::message,
                                        Diagnostic::path
                                ).containsExactly(
                                        "PROJECT_APPLICATION_STEP_LIMIT",
                                        "One compiled Step exceeds the 32768-character generated-code limit.",
                                        "nodes[2]"
                                )
                        )
                );
    }

    @Test
    void distinctTriggerSourcesCompileIntoOneApplication() {
        final StepDefinition timer = trigger("example.trigger.timer", "timer.tick");
        final StepCatalog catalog = with(contextCatalog(), timer);
        final String source = """
                {"format":1,"id":"two-sources","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"cli","payload":[],"context":{"payload":{}}}
                  ]},
                  {"id":"timer","use":"example.trigger.timer","inputs":{},"examples":[
                    {"name":"tick","payload":[],"context":{"payload":{}}}
                  ]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"},
                  {"from":"app.start","to":"timer"},
                  {"from":"timer.next","to":"end"}
                ]}
                """;

        assertThat(ProjectCompiler.compileApplication(source, catalog))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void nestedObjectAndArrayPathCompiles() {
        final String source = contextProject()
                .replace("\"name\":\"Hello RAILIX\"", "\"names\":[\"Hello RAILIX\"]")
                .replace("[\"context\",\"payload\",\"name\"]", "[\"context\",\"payload\",\"names\",0]");

        assertThat(ProjectCompiler.compileApplication(source, contextCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void nestedStepInputDefaultCompilesWithoutAuthoredValue() {
        final StepDefinition lowercase = StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .input("locale", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("root")))
                .run(TestStepHandlers.LowercaseValue.class);

        assertThat(ProjectCompiler.compileApplication(contextProject(), replacePrimitive(lowercase)))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void fallibleNestedPrimitiveCompilesWithoutBranchRouting() {
        assertThat(ProjectCompiler.compileApplication(fallibleProject(), fallibleCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void triggerResultDefaultsAllowAFlowToFinishImmediately() {
        assertThat(ProjectCompiler.compileApplication(triggerOnlyProject(), contextCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    static StepCatalog contextCatalog() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition cli = StepDefinition.named("railix.trigger.cli", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("application.arguments")
                .receive("arguments", ValueShape.ARRAY)
                .input("target", Input.path(WRITE).defaultPath("context", "payload", "arguments"))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .result("exit_code", ValueShape.NUMBER, RailixValue.number(0))
                .response("output", "result")
                .response("status", "exit_code")
                .run(TestStepHandlers.WriteArguments.class);
        final StepDefinition manipulation = StepDefinition.named("railix.field-manipulation", "1")
                .input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
                .input("value", Input.candidates(
                                Input.option("current").fromParent("field"),
                                Input.option("literal")
                                        .input("literal", Input.json(ValueShape.ANY))
                                        .fromOwned("literal"),
                                Input.option("field")
                                        .input("source", Input.path(READ))
                                        .fromOwned("source")
                        ).defaultCandidate("current"))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
                .run(TestStepHandlers.RunStepsWriteWhenPresentField.class);
        final StepDefinition lowercase = StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .run(TestStepHandlers.LowercaseValue.class);
        final StepDefinition branchingTransform = StepDefinition.named("test.branching-transform", "1")
                .input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
                .input("value", Input.candidates(
                                Input.option("current").fromParent("field"),
                                Input.option("literal")
                                        .input("literal", Input.json(ValueShape.ANY))
                                        .fromOwned("literal"),
                                Input.option("field")
                                        .input("source", Input.path(READ))
                                        .fromOwned("source")
                        ).defaultCandidate("current"))
                .input("steps", Input.steps(
                        StepDefinition.ValueSource.from("value").onMissing("missing")
                ).propagateOutcomes())
                .run(TestStepHandlers.RunStepsWriteField.class);
        return StepCatalog.of(app, cli, manipulation, lowercase, branchingTransform);
    }

    static StepCatalog fallibleCatalog() {
        final StepDefinition toNumber = StepDefinition.named("text.to-number", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .outcome("invalid")
                .run(TestStepHandlers.ToNumber.class);
        return with(contextCatalog(), toNumber);
    }

    static String contextProject() {
        return """
                {"format":1,"id":"atomic-byte-forge","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"hello","payload":[],"context":{"payload":{"name":"Hello RAILIX"},"metadata":{"trace_id":"trace-1"}}}
                  ]},
                  {"id":"normalise-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","name"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }},
                  {"id":"return-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","name"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"normalise-name"},
                  {"from":"normalise-name.next","to":"return-name"},
                  {"from":"return-name.next","to":"end"}
                ]}
                """;
    }

    static String triggerOnlyProject() {
        return """
                {"format":1,"id":"trigger-only","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"empty","payload":[],"context":{"payload":{}}}
                  ]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    static String fallibleProject() {
        return """
                {"format":1,"id":"fallible-number","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"number","payload":[],"context":{"payload":{"value":"12.50"}}}
                  ]},
                  {"id":"convert","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.to-number","inputs":{}}]
                  }},
                  {"id":"number-result","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","value"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"convert"},
                  {"from":"convert.next","to":"number-result"},
                  {"from":"number-result.next","to":"end"}
                ]}
                """;
    }

    static String graphPrimitiveProject() {
        return """
                {"format":1,"id":"graph-primitive","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"hello","payload":[],"context":{"payload":{"name":"Hello RAILIX"}}}
                  ]},
                  {"id":"lowercase","use":"text.lowercase","inputs":{},
                   "receives":{"value":["context","payload","name"]},
                   "returns":{"value":["context","payload","name"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase"},
                  {"from":"lowercase.ok","to":"end"}
                ]}
                """;
    }

    private static StepDefinition trigger(final String id, final String source) {
        return StepDefinition.named(id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source(source)
                .run(TestStepHandlers.PrimaryOutcome.class);
    }

    private static StepCatalog replacePrimitive(final StepDefinition replacement) {
        final List<StepDefinition> definitions = contextCatalog().definitions();
        return StepCatalog.of(
                definitions.get(0),
                definitions.get(1),
                definitions.get(2),
                replacement,
                definitions.get(4)
        );
    }

    private static StepCatalog with(final StepCatalog catalog, final StepDefinition definition) {
        final List<StepDefinition> definitions = new java.util.ArrayList<>(catalog.definitions());
        definitions.add(definition);
        return StepCatalog.of(definitions.toArray(StepDefinition[]::new));
    }

    private static RailixValue.ObjectValue context(final String value) {
        return RailixValue.object(Map.of(
                "payload",
                RailixValue.object(Map.of("value", RailixValue.string(value)))
        ));
    }

    private static CompileResult.Compiled compiled(final String source, final StepCatalog catalog) {
        return (CompileResult.Compiled) ProjectCompiler.compileApplication(source, catalog);
    }
}
