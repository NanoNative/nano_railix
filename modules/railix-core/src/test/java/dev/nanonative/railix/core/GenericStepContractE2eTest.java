package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

final class GenericStepContractE2eTest {
    @Test
    void canonicalProjectPersistsOnlyInputsAndExplicitOptionTags() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(
                project(),
                catalog()
        );

        assertThat(compiled.source())
                .contains("\"inputs\"")
                .contains("\"option\":\"literal\"")
                .contains("\"literal\":\"Fixed\"")
                .doesNotContain("\"config\"")
                .doesNotContain("primitive_pipeline");
    }

    private static StepCatalog catalog() {
        return StepCatalog.of(
                StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP)
                        .define(),
                StepDefinition.named("example.trigger", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("example.input")
                        .input("target", StepDefinition.Input.path(WRITE)
                                .defaultPath("context", "payload"))
                        .exampleTarget("target")
                        .example("example", RailixValue.object(Map.of()))
                        .run(TestStepHandlers.PrimaryOutcome.class),
                StepDefinition.named("example.identity", "1")
                        .primaryOutcome("ok")
                        .receive("source", ValueShape.ANY)
                        .returns("result", ValueShape.ANY)
                        .run(TestStepHandlers.SourceResultIdentity.class),
                StepDefinition.named("example.generic-operation", "1")
                        .input("field", StepDefinition.Input.path(READ_WRITE))
                        .input("value", StepDefinition.Input.candidates(
                                StepDefinition.Input.option("literal")
                                        .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                        .fromOwned("literal"),
                                StepDefinition.Input.option("field")
                                        .input("source", StepDefinition.Input.path(READ))
                                        .fromOwned("source")
                        ))
                        .input("operations", StepDefinition.Input.steps(
                                StepDefinition.ValueSource.from("value").onMissing("missing")
                        ).propagateOutcomes())
                        .run(TestStepHandlers.RunOperationsWriteField.class)
        );
    }

    private static String project() {
        return """
                {"format":1,"id":"generic-contract","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"example.trigger","inputs":{},"examples":[
                    {"name":"example","payload":{}}
                  ]},
                  {"id":"transform","use":"example.generic-operation","inputs":{
                    "field":["context","payload","text"],
                    "value":[{"option":"literal","inputs":{"literal":"Fixed"}}],
                    "operations":[{"use":"example.identity","inputs":{}}]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"transform"},
                  {"from":"transform.next","to":"end"},
                  {"from":"transform.missing","to":"end"}
                ]}
                """;
    }
}
