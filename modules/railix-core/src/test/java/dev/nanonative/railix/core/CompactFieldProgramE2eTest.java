package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunFailure;
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

final class CompactFieldProgramE2eTest {
    @Test
    void firstCandidateAcceptedByItsPredicateProgramBecomesTheOrdinaryStepValue() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) ProjectCompiler.compile(project(), catalog());

        final RunResult.Succeeded result = (RunResult.Succeeded) compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("name", RailixValue.string("existing")))
                )))
        );

        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) result.context().values().get("payload");
        assertThat(payload.values().get("name")).isEqualTo(RailixValue.string("fallback"));
        assertThat(result.steps()).containsExactly(
                new RunResult.StepExecution("value.equals", "ok"),
                new RunResult.StepExecution("value.identity", "ok"),
                new RunResult.StepExecution("change", "next")
        );
    }

    @Test
    void previewReportsWhichOrderedCandidateSuppliedTheValue() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) ProjectCompiler.compile(project(), catalog());

        final CompiledProject.PreviewResult preview = compiled.project().preview(
                "command",
                "change",
                new CompiledProject.StreamItem(true, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("name", RailixValue.string("existing")))
                )))
        );

        assertThat(preview.selectedCandidates()).containsExactlyEntriesOf(Map.of("nodes[2].inputs.value", 1));
    }

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
    void candidatePredicateProgramMustBeAnArray() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":true}]",
                "PROJECT_CANDIDATE_PREDICATE_ARRAY_REQUIRED",
                "nodes[2].inputs.value[0].when"
        );
    }

    @Test
    void candidatePredicateStepsCannotBranch() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}]}]",
                "PROJECT_CANDIDATE_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.value[0].when[0].use"
        );
    }

    @Test
    void candidatePredicateProgramMustEndWithABoolean() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.identity\",\"inputs\":{}}]}]",
                "PROJECT_CANDIDATE_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.value[0].when[0].use"
        );
    }

    @Test
    void incompatibleUnreachedPredicateOperationsAreRejectedBeforeExamplesRun() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"hidden\"},\"when\":["
                        + "{\"use\":\"text.length\",\"inputs\":{}},"
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]}]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.value[1].when[1].use"
        );
    }

    @Test
    void incompatibleUnreachedCandidateSourceIsRejectedBeforeExamplesRun() {
        assertRejected(
                "[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"hidden\"},\"when\":["
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]}]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.value[1].when[0].use"
        );
    }

    @Test
    void failedPredicateRuntimeDiagnosticNamesTheExecutedCandidateOccurrence() {
        final String candidates = "[{\"option\":\"field\",\"inputs\":{"
                + "\"source\":[\"context\",\"payload\",\"missing\"]},\"when\":["
                + "{\"use\":\"value.fault\",\"inputs\":{}}]},"
                + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":["
                + "{\"use\":\"value.fault\",\"inputs\":{}}]}]";
        final CompileResult.Compiled compiled =
                (CompileResult.Compiled) ProjectCompiler.compile(project(candidates), catalog());

        final RunResult result = compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("name", RailixValue.string("existing")))
                )))
        );

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure()).isEqualTo(new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault",
                        "nodes[2].inputs.value[1].when[0]"
                )));
    }

    @Test
    void canonicalJsonNullIsAcceptedWhenNoPredicateRejectsIt() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":null}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "name", RailixValue.string("existing")
                ))))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void projectPredicateCanRejectCanonicalJsonNullAndUseTheNextCandidate() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":null},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":null}},"
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "name", RailixValue.string("existing")
                ))))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void projectPredicateCanDefineEmptyTextAndUseTheNextCandidate() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"\"}},"
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "name", RailixValue.string("")
                ))))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void booleanFalseIsAcceptedWhenNoPredicateRejectsIt() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":false}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void numberZeroIsAcceptedWhenNoPredicateRejectsIt() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":0}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void missingFieldCandidateFallsThroughToTheNextLiteral() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"missing\"]}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void anotherFieldCandidateCopiesTheCurrentValueWithoutCreatingALiveAlias() {
        final RailixValue.ObjectValue context = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of(
                        "alias", RailixValue.string("copied"),
                        "name", RailixValue.string("existing")
                ))
        ));
        final RunResult.Succeeded result = run(
                "[{\"option\":\"field\",\"inputs\":{"
                        + "\"source\":[\"context\",\"payload\",\"alias\"]}}]",
                context
        );

        assertThat(payload(result).values()).containsEntry("alias", RailixValue.string("copied"))
                .containsEntry("name", RailixValue.string("copied"));
    }

    @Test
    void allUnresolvedCandidatesSelectTheExplicitOutcomeWithoutWriting() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"field\",\"inputs\":{"
                        + "\"source\":[\"context\",\"payload\",\"missing\"]}}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "name", RailixValue.string("unchanged")
                ))))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.string("unchanged"));
        assertThat(result.steps()).containsExactly(new RunResult.StepExecution("change", "unresolved"));
    }

    @Test
    void firstAcceptedCandidateStopsEvaluationOfLaterPredicates() {
        final RunResult.Succeeded result = run(
                "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"first\"}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"second\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"second\"}}]}]",
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        );

        assertThat(payload(result).values().get("name")).isEqualTo(RailixValue.string("first"));
        assertThat(result.steps()).containsExactly(
                new RunResult.StepExecution("value.identity", "ok"),
                new RunResult.StepExecution("change", "next")
        );
    }

    @Test
    void interruptionAfterAPredicateHandlerCancelsBeforeTheOuterStepWrites() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) ProjectCompiler.compile(project(
                "[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"},\"when\":["
                        + "{\"use\":\"value.interrupt\",\"inputs\":{}}]}]"
        ).replace(
                "\"steps\":[{\"use\":\"value.identity\",\"inputs\":{}}]",
                "\"steps\":[]"
        ), catalog());

        final RunResult result = compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of())
                )))
        );
        final boolean interrupted = Thread.interrupted();

        assertThat(interrupted).isTrue();
        assertThat(result).isInstanceOf(RunResult.Cancelled.class);
    }

    @Test
    void predicateImplementationFaultStopsBeforeTheOuterStepRuns() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) ProjectCompiler.compile(project(
                "[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]}]"
        ), catalog());

        final RunResult result = compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("name", RailixValue.string("fault")))
                )))
        );

        assertThat(result).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault",
                        "nodes[2].inputs.value[0].when[0]"
                ),
                List.of()
        ));
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
                .run(input -> StepResult.outcome(input.primaryOutcome()));
        final StepDefinition equals = StepDefinition.named("value.equals", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .input("expected", StepDefinition.Input.json(ValueShape.ANY))
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "value",
                        RailixValue.bool(input.value("value").equals(input.value("expected")))
                ));
        final StepDefinition identity = StepDefinition.named("value.identity", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", input.value("value")));
        final StepDefinition not = StepDefinition.named("boolean.not", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.BOOLEAN)
                .returns("value", ValueShape.BOOLEAN)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "value",
                        RailixValue.bool(!((RailixValue.BooleanValue) input.value("value")).value())
                ));
        final StepDefinition length = StepDefinition.named("text.length", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "value",
                        RailixValue.number(((RailixValue.StringValue) input.value("value")).value().length())
                ));
        final StepDefinition interrupt = StepDefinition.named("value.interrupt", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .run(input -> {
                    Thread.currentThread().interrupt();
                    return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
                });
        final StepDefinition fault = StepDefinition.named("value.fault", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .run(input -> {
                    if (RailixValue.string("fault").equals(input.value("value"))) {
                        throw new IllegalStateException("predicate fault");
                    }
                    return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
                });
        final StepDefinition branchingPredicate = StepDefinition.named("value.branching-predicate", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .outcome("rejected")
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true)));
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
                .run(input -> input.run("steps").write("field"));
        return StepCatalog.of(
                app,
                trigger,
                equals,
                identity,
                not,
                length,
                interrupt,
                fault,
                branchingPredicate,
                change
        );
    }

    private static String project() {
        return project("""
                [
                  {"option":"current","inputs":{},"when":[
                    {"use":"value.equals","inputs":{"expected":"different"}}
                  ]},
                  {"option":"literal","inputs":{"literal":"fallback"},"when":[]}
                ]
                """);
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
        final CompileResult result = ProjectCompiler.compile(project(candidates), catalog());

        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final CompileResult.Rejected rejected = (CompileResult.Rejected) result;
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.path()).isEqualTo(path);
        });
    }

    private static RunResult.Succeeded run(
            final String candidates,
            final RailixValue.ObjectValue context
    ) {
        final CompileResult.Compiled compiled =
                (CompileResult.Compiled) ProjectCompiler.compile(project(candidates), catalog());
        return (RunResult.Succeeded) compiled.project().run(
                "command",
                new CompiledProject.StreamItem(false, context)
        );
    }

    private static RailixValue.ObjectValue payload(final RunResult.Succeeded result) {
        return (RailixValue.ObjectValue) result.context().values().get("payload");
    }
}
