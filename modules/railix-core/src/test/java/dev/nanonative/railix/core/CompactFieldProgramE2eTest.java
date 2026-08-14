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
import static org.assertj.core.api.Assertions.assertThat;

final class CompactFieldProgramE2eTest {
    @Test
    void candidatesInputMustBeAnArray() {
        assertRejected("{}", "PROJECT_CANDIDATES_ARRAY_REQUIRED", "nodes[2].inputs.value");
    }

    @Test
    void eachCandidateMustBeAnObject() {
        assertRejected("[true]", "PROJECT_CANDIDATE_OBJECT_REQUIRED", "nodes[2].inputs.value[0]");
    }

    @Test
    void unknownCandidateFieldsAreRejected() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"unknown\":true}]",
                "PROJECT_CANDIDATE_FIELD_UNKNOWN",
                "nodes[2].inputs.value[0].unknown"
        );
    }

    @Test
    void candidateOptionIsRequired() {
        assertRejected(
                "[{\"inputs\":{}}]",
                "PROJECT_CANDIDATE_REQUIRED",
                "nodes[2].inputs.value[0].option"
        );
    }

    @Test
    void candidateOptionMustBeDeclaredByTheStep() {
        assertRejected(
                "[{\"option\":\"unknown\",\"inputs\":{}}]",
                "PROJECT_CANDIDATE_UNKNOWN",
                "nodes[2].inputs.value[0].option"
        );
    }

    @Test
    void candidateInputsMustBeAnObject() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":[]}]",
                "PROJECT_CANDIDATE_INPUTS_OBJECT_REQUIRED",
                "nodes[2].inputs.value[0].inputs"
        );
    }

    @Test
    void candidateConditionMustBeAnObject() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":true}]",
                "PROJECT_CANDIDATE_CONDITION_OBJECT_REQUIRED",
                "nodes[2].inputs.value[0].when"
        );
    }

    @Test
    void candidatePredicateStepsCannotBranch() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}]]}}]",
                "PROJECT_CANDIDATE_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.value[0].when.all[0][0].use"
        );
    }

    @Test
    void candidatePredicateProgramMustEndWithABoolean() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.identity\",\"inputs\":{}}]]}}]",
                "PROJECT_CANDIDATE_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.value[0].when.all[0][0].use"
        );
    }

    @Test
    void incompatibleUnreachedPredicateOperationsAreRejectedBeforeExamplesRun() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"hidden\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]]}}]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.value[1].when.all[0][0].use"
        );
    }

    @Test
    void incompatibleUnreachedCandidateSourceIsRejectedBeforeExamplesRun() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"hidden\"},\"when\":{"
                        + "\"transforms\":[],\"all\":[[{\"use\":\"boolean.not\",\"inputs\":{}}]]}}]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.value[1].when.all[0][0].use"
        );
    }

    private static StepCatalog catalog() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition trigger = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("example.source")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "payload"))
                .exampleTarget("target")
                .example("example", RailixValue.object(Map.of(
                        "name", RailixValue.string("existing")
                )))
                .run(TestStepHandlers.PrimaryOutcome.class);
        final StepDefinition identity = StepDefinition.named("value.identity", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(TestStepHandlers.PrimaryIdentity.class);
        final StepDefinition not = StepDefinition.named("boolean.not", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.BOOLEAN)
                .returns("value", ValueShape.BOOLEAN)
                .run(TestStepHandlers.BooleanNot.class);
        final StepDefinition length = StepDefinition.named("text.length", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .run(TestStepHandlers.TextLength.class);
        final StepDefinition branchingPredicate = StepDefinition.named("value.branching-predicate", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .outcome("rejected")
                .run(TestStepHandlers.True.class);
        final StepDefinition change = StepDefinition.named("example.change-field", "1")
                .input("field", StepDefinition.Input.path(READ_WRITE))
                .input("value", StepDefinition.Input.candidates(
                        StepDefinition.Input.option("current").fromParent("field"),
                        StepDefinition.Input.option("field")
                                .input("source", StepDefinition.Input.path(READ))
                                .fromOwned("source"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("steps", StepDefinition.Input.steps(
                        StepDefinition.ValueSource.from("value").onMissing("unresolved")
                ))
                .run(TestStepHandlers.RunStepsWriteField.class);
        return StepCatalog.of(app, trigger, identity, not, length, branchingPredicate, change);
    }

    private static String project(final String candidates) {
        return """
                {"format":1,"id":"compact-field-program","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"example.trigger","inputs":{},"examples":[
                    {"name":"example","payload":{},"context":{"payload":{"name":"existing","alias":"copied"}}}
                  ]},
                  {"id":"change","use":"example.change-field","inputs":{
                    "field":["context","payload","name"],
                    "value":%s,
                    "steps":[{"use":"value.identity","inputs":{}}]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"change"},
                  {"from":"change.next","to":"end"},
                  {"from":"change.unresolved","to":"end"}
                ]}
                """.formatted(candidates);
    }

    private static void assertRejected(final String candidates, final String code, final String path) {
        final CompileResult result = ProjectCompiler.compileApplication(project(candidates), catalog());

        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final CompileResult.Rejected rejected = (CompileResult.Rejected) result;
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.path()).isEqualTo(path);
        });
    }
}
