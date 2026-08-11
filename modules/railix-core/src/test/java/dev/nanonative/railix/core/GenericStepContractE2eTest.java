package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class GenericStepContractE2eTest {
    @Test
    void thirdPartyOrdinaryStepExecutesPathOptionsAndNestedPrimitiveInputs() {
        final CompileResult.Compiled compiled = compiled(project("current", "{}"));
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("Hello RAILIX")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("text")).isEqualTo(RailixValue.string("hello railix"));
    }

    @Test
    void canonicalProjectPersistsOnlyInputsAndExplicitOptionTags() {
        final CompileResult.Compiled compiled = compiled(project("literal", "{\"literal\":\"Fixed\"}"));

        assertThat(compiled.source())
                .contains("\"inputs\"")
                .contains("\"option\":\"literal\"")
                .contains("\"literal\":\"Fixed\"")
                .doesNotContain("\"config\"")
                .doesNotContain("primitive_pipeline");
    }

    @Test
    void missingSourceUsesTheExplicitMissingOutcome() {
        final CompileResult.Compiled compiled = compiled(project("field", "{\"source\":[\"context\",\"payload\",\"absent\"]}"));
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("unchanged")))
                )))
        );

        assertThat(result.steps()).contains(new RunResult.StepExecution("transform", "missing"));
    }

    @Test
    void absentPrimarySourceUsesTheDeclaredFallback() {
        final String source = projectCandidates(
                "{\"option\":\"field\",\"inputs\":{"
                        + "\"source\":[\"context\",\"payload\",\"absent\"]}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"FALLBACK\"}}"
        );
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled(source).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("unchanged")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("text")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void explicitJsonNullDoesNotActivateTheFallback() {
        final String source = projectCandidates(
                "{\"option\":\"literal\",\"inputs\":{\"literal\":null}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"wrong\"}}"
        )
                .replace("example.lowercase", "example.identity");
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled(source).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("unchanged")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("text")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void parentOptionSourceIgnoresASameNamedOwnedInput() {
        final String source = project("parent-shadow", "{\"field\":\"wrong\"}")
                .replace("example.lowercase", "example.identity");
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled(source).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("parent")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("text")).isEqualTo(RailixValue.string("parent"));
    }

    @Test
    void ownedOptionSourceIgnoresASameNamedParentInput() {
        final String source = project("owned-shadow", "{\"field\":\"owned\"}")
                .replace("example.lowercase", "example.identity");
        final RunResult.Succeeded result = (RunResult.Succeeded) compiled(source).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("text", RailixValue.string("wrong")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("text")).isEqualTo(RailixValue.string("owned"));
    }

    private static CompileResult.Compiled compiled(final String project) {
        return (CompileResult.Compiled) ProjectCompiler.compile(project, catalog());
    }

    private static StepCatalog catalog() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition trigger = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("example.input")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "payload"))
                .exampleTarget("target")
                .example("example", RailixValue.object(Map.of(
                        "text", RailixValue.string("Hello RAILIX")
                )))
                .run(input -> StepResult.outcome(input.primaryOutcome()));
        final StepDefinition lowercase = StepDefinition.named("example.lowercase", "1")
                .primaryOutcome("ok")
                .receive("source", ValueShape.STRING)
                .returns("result", ValueShape.STRING)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "result",
                        RailixValue.string(input.string("source").toLowerCase(java.util.Locale.ROOT))
                ));
        final StepDefinition identity = StepDefinition.named("example.identity", "1")
                .primaryOutcome("ok")
                .receive("source", ValueShape.ANY)
                .returns("result", ValueShape.ANY)
                .run(input -> StepResult.outcome(input.primaryOutcome())
                        .output("result", input.value("source")));
        final StepDefinition operation = StepDefinition.named("example.generic-operation", "1")
                .input("field", StepDefinition.Input.path(READ_WRITE))
                .input("value", StepDefinition.Input.candidates(
                        StepDefinition.Input.option("current").fromParent("field"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal"),
                        StepDefinition.Input.option("field")
                                .input("source", StepDefinition.Input.path(READ))
                                .fromOwned("source"),
                        StepDefinition.Input.option("parent-shadow")
                                .input("field", StepDefinition.Input.json(ValueShape.ANY))
                                .fromParent("field"),
                        StepDefinition.Input.option("owned-shadow")
                                .input("field", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("field")
                ))
                .input("operations", StepDefinition.Input.steps(
                        StepDefinition.ValueSource.from("value")
                                .onMissing("missing")
                )
                        .propagateOutcomes())
                .run(input -> input.run("operations").write("field"));
        return StepCatalog.of(app, trigger, operation, lowercase, identity);
    }

    private static String project(final String option, final String optionInputs) {
        return projectCandidates("{\"option\":\"%s\",\"inputs\":%s}".formatted(option, optionInputs));
    }

    private static String projectCandidates(final String candidates) {
        return """
                {"format":1,"id":"generic-contract","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"example.trigger","inputs":{},"examples":[
                    {"name":"example","payload":{},"context":{"payload":{"text":"Hello RAILIX"}}}
                  ]},
                  {"id":"transform","use":"example.generic-operation","inputs":{
                    "field":["context","payload","text"],
                    "value":[%s],
                    "operations":[{"use":"example.lowercase","inputs":{}}]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"transform"},
                  {"from":"transform.next","to":"end"},
                  {"from":"transform.missing","to":"end"}
                ]}
                """.formatted(candidates);
    }
}
