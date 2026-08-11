package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

final class MatcherGroupsE2eTest {
    @Test
    void canonicalMatcherGroupsCompileAsOrderedOrGroupsOfAndMatchers() {
        final CompileResult.Compiled compiled = compiled("""
                [[{"option":"literal","inputs":{"literal":"one"},"when":[]}],
                 [{"option":"literal","inputs":{"literal":"two"},"when":[]}]]
                """);

        assertThat(compiled.executableSource()).contains(
                "\"matches\":[[{\"inputs\":{\"literal\":\"one\"},\"option\":\"literal\",\"when\":[]}],[{\"inputs\":{\"literal\":\"two\"},\"option\":\"literal\",\"when\":[]}]]"
        );
    }

    @Test
    void canonicalMatcherPreservesAnExplicitAdditionalPredicate() {
        final CompileResult.Compiled compiled = compiled("""
                [[{"option":"literal","inputs":{"literal":"railix"},"when":{
                  "transforms":[{"use":"text.length","inputs":{}}],
                  "all":[
                    [{"use":"number.greater-than","inputs":{"than":1}}],
                    [{"use":"number.less-than","inputs":{"than":10}}]
                  ]
                }}]]
                """);

        assertThat(compiled.executableSource()).contains(
                "\"when\":{\"all\":[[{\"inputs\":{\"than\":1},\"use\":\"number.greater-than\"}],"
                        + "[{\"inputs\":{\"than\":10},\"use\":\"number.less-than\"}]],"
                        + "\"transforms\":[{\"inputs\":{},\"use\":\"text.length\"}]}"
        );
    }

    @Test
    void legacyPredicatePipelineNormalizesToTheStructuredContract() {
        final CompileResult.Compiled compiled = compiled("""
                [[{"option":"literal","inputs":{"literal":"railix"},"when":[
                  {"use":"text.length","inputs":{}},
                  {"use":"number.greater-than","inputs":{"than":1}}
                ]}]]
                """);

        assertThat(compiled.executableSource()).contains(
                "\"when\":{\"all\":[[{\"inputs\":{\"than\":1},\"use\":\"number.greater-than\"}]],"
                        + "\"transforms\":[{\"inputs\":{},\"use\":\"text.length\"}]}"
        );
    }

    @Test
    void matcherGroupsMustBeAnOuterArray() {
        assertRejected("{}", "PROJECT_MATCHER_GROUPS_ARRAY_REQUIRED", "nodes[2].inputs.matches");
    }

    @Test
    void matcherGroupMustBeAnArray() {
        assertRejected("[{}]", "PROJECT_MATCHER_GROUP_ARRAY_REQUIRED", "nodes[2].inputs.matches[0]");
    }

    @Test
    void matcherMustBeAnExistingCandidateObjectShape() {
        assertRejected("[[true]]", "PROJECT_MATCHER_OBJECT_REQUIRED", "nodes[2].inputs.matches[0][0]");
    }

    @Test
    void unknownMatcherFieldsAreRejected() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"unknown\":true}]]",
                "PROJECT_MATCHER_FIELD_UNKNOWN",
                "nodes[2].inputs.matches[0][0].unknown"
        );
    }

    @Test
    void matcherOptionIsRequired() {
        assertRejected(
                "[[{\"inputs\":{}}]]",
                "PROJECT_MATCHER_REQUIRED",
                "nodes[2].inputs.matches[0][0].option"
        );
    }

    @Test
    void matcherGroupCannotBeEmpty() {
        assertRejected("[[]]", "PROJECT_MATCHER_GROUP_EMPTY", "nodes[2].inputs.matches[0]");
    }

    @Test
    void matcherOptionMustBeDeclared() {
        assertRejected(
                "[[{\"option\":\"unknown\",\"inputs\":{}}]]",
                "PROJECT_MATCHER_UNKNOWN",
                "nodes[2].inputs.matches[0][0].option"
        );
    }

    @Test
    void matcherOwnedInputRemainsRequired() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{}}]]",
                "PROJECT_INPUT_REQUIRED",
                "nodes[2].inputs.matches[0][0].inputs.literal"
        );
    }

    @Test
    void matcherInputsMustBeAnObject() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":[]}]]",
                "PROJECT_MATCHER_INPUTS_OBJECT_REQUIRED",
                "nodes[2].inputs.matches[0][0].inputs"
        );
    }

    @Test
    void matcherPredicateProgramMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":true}]]",
                "PROJECT_MATCHER_PREDICATE_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when"
        );
    }

    @Test
    void matcherTransformsMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":true,\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_MATCHER_TRANSFORMS_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.transforms"
        );
    }

    @Test
    void matcherPredicatesMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":true}}]]",
                "PROJECT_MATCHER_PREDICATES_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.all"
        );
    }

    @Test
    void matcherConditionRejectsUnknownFields() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]],\"extra\":true}}]]",
                "PROJECT_MATCHER_PREDICATE_FIELD_UNKNOWN",
                "nodes[2].inputs.matches[0][0].when.extra"
        );
    }

    @Test
    void matcherConditionRequiresAtLeastOnePredicate() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[]}}]]",
                "PROJECT_MATCHER_PREDICATE_EMPTY",
                "nodes[2].inputs.matches[0][0].when.all"
        );
    }

    @Test
    void matcherPredicateMustBeAnArray() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[true]}}]]",
                "PROJECT_MATCHER_PREDICATE_ARRAY_REQUIRED",
                "nodes[2].inputs.matches[0][0].when.all[0]"
        );
    }

    @Test
    void matcherPredicateCannotBeEmpty() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":[],\"all\":[[]]}}]]",
                "PROJECT_MATCHER_PREDICATE_EMPTY",
                "nodes[2].inputs.matches[0][0].when.all[0]"
        );
    }

    @Test
    void matcherPredicateStepsCannotBranch() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}]}]]",
                "PROJECT_MATCHER_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.matches[0][0].when[0].use"
        );
    }

    @Test
    void matcherTransformStepsCannotBranch() {
        assertRejected(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":["
                        + "{\"use\":\"value.branching-predicate\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_OUTCOME_INVALID",
                "nodes[2].inputs.matches[0][0].when.transforms[0].use"
        );
    }

    @Test
    void matcherFinalPredicateMustReturnBoolean() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}}]}]]",
                "PROJECT_MATCHER_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.matches[0][0].when[0].use"
        );
    }

    @Test
    void matcherAdditionalPredicateMustReturnBoolean() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":{"
                        + "\"transforms\":[],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}}],["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}}]]}}]]",
                "PROJECT_MATCHER_PREDICATE_RESULT_INVALID",
                "nodes[2].inputs.matches[0][0].when.all[1][0].use"
        );
    }

    @Test
    void incompatibleMatcherSourceIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":["
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when[0].use"
        );
    }

    @Test
    void incompatibleMatcherPredicateChainIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":["
                        + "{\"use\":\"text.length\",\"inputs\":{}},"
                        + "{\"use\":\"boolean.not\",\"inputs\":{}}]}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when[1].use"
        );
    }

    @Test
    void incompatibleMatcherTransformSourceIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"boolean.not\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.transforms[0].use"
        );
    }

    @Test
    void incompatibleAdditionalMatcherPredicateIsRejectedBeforeExecution() {
        assertRejected(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"text\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"number.greater-than\",\"inputs\":{\"than\":0}}],["
                        + "{\"use\":\"value.text-boolean\",\"inputs\":{}}]]}}]]",
                "PROJECT_NESTED_INPUT_INCOMPATIBLE",
                "nodes[2].inputs.matches[0][0].when.all[1][0].use"
        );
    }

    @Test
    void missingMatcherPathIsFalseWithoutRunningItsPredicate() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).containsExactly(new RunResult.StepExecution("probe", "next"));
    }

    @Test
    void missingMatcherPathSkipsStructuredTransformsAndPredicates() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":{\"transforms\":["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).containsExactly(new RunResult.StepExecution("probe", "next"));
    }

    @Test
    void canonicalJsonNullIsAPresentMatcherValue() {
        assertPresent(RailixValue.nullValue());
    }

    @Test
    void booleanFalseIsAPresentMatcherValue() {
        assertPresent(RailixValue.bool(false));
    }

    @Test
    void numberZeroIsAPresentMatcherValue() {
        assertPresent(RailixValue.number(0));
    }

    @Test
    void emptyStringIsAPresentMatcherValue() {
        assertPresent(RailixValue.string(""));
    }

    @Test
    void emptyArrayIsAPresentMatcherValue() {
        assertPresent(RailixValue.array(List.of()));
    }

    @Test
    void emptyObjectIsAPresentMatcherValue() {
        assertPresent(RailixValue.object(Map.of()));
    }

    @Test
    void andShortCircuitsAfterTheFirstFalseMatcher() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"two\"}}]},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "probe");
    }

    @Test
    void andGroupMatchesWhenEveryMatcherIsTrue() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"one\"}}]},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"two\"}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "value.equals", "probe");
    }

    @Test
    void andGroupIsFalseWhenALaterMatcherIsFalse() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"one\"}}]},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"other\"}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "value.equals", "probe");
    }

    @Test
    void falseGroupContinuesToTheNextOrGroup() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"two\"}}]}],"
                        + "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":[]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "probe");
    }

    @Test
    void trueGroupShortCircuitsLaterOrGroups() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":[]}],"
                        + "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
        assertThat(result.steps()).containsExactly(new RunResult.StepExecution("probe", "next"));
    }

    @Test
    void orGroupsAreFalseWhenEveryGroupIsFalse() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"other\"}}]}],"
                        + "[{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"other\"}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "value.equals", "probe");
    }

    @Test
    void matcherRunsMultipleUnaryStepsInAuthoredOrder() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("value.equals", "value.equals", "probe");
    }

    @Test
    void sharedTransformFeedsEveryAuthoredPredicateOnce() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
                        + "[{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],"
                        + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":10}}]]}}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("text.length", "number.greater-than", "number.less-than", "probe");
    }

    @Test
    void previewSeparatesSharedTransformAndMatcherStages() {
        final CompiledProject.PreviewResult preview = compiled(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],["
                        + "{\"use\":\"number.less-than\",\"inputs\":{\"than\":10}}]]}}]]"
        ).project().preview(
                "command",
                "probe",
                new CompiledProject.StreamItem(
                        true,
                        RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
                )
        );

        assertThat(preview.stages()).containsExactly(
                new CompiledProject.NestedStepStage(
                        "matches[0][0].when.transforms",
                        "nodes[2].inputs.matches[0][0].when.transforms[0]",
                        "text.length",
                        "succeeded",
                        List.of(RailixValue.number(6))
                ),
                new CompiledProject.NestedStepStage(
                        "matches[0][0].when.all[0]",
                        "nodes[2].inputs.matches[0][0].when.all[0][0]",
                        "number.greater-than",
                        "succeeded",
                        List.of(RailixValue.bool(true))
                ),
                new CompiledProject.NestedStepStage(
                        "matches[0][0].when.all[1]",
                        "nodes[2].inputs.matches[0][0].when.all[1][0]",
                        "number.less-than",
                        "succeeded",
                        List.of(RailixValue.bool(true))
                )
        );
    }

    @Test
    void falseFirstPredicateSkipsAdditionalPredicates() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
                        + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":1}}],"
                        + "[{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("text.length", "number.less-than", "probe");
    }

    @Test
    void falseAdditionalPredicateRejectsTheMatcher() {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
                        + "[{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],"
                        + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":5}}]]}}]]",
                Map.of()
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(false));
        assertThat(result.steps()).extracting(RunResult.StepExecution::stepId)
                .containsExactly("text.length", "number.greater-than", "number.less-than", "probe");
    }

    @Test
    void emptyOuterMatcherGroupsResolveFalse() {
        assertThat(match(run("[]", Map.of()))).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void repeatedRunsDoNotRetainMatcherState() {
        final CompiledProject project = compiled("[[{\"option\":\"current\",\"inputs\":{},\"when\":[]}]]").project();

        assertThat(match((RunResult.Succeeded) run(project, Map.of("source", RailixValue.string("first")))))
                .isEqualTo(RailixValue.bool(true));
        assertThat(match((RunResult.Succeeded) run(project, Map.of()))).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void concurrentRunsDoNotShareMatcherState() throws Exception {
        final CompiledProject project = compiled("[[{\"option\":\"current\",\"inputs\":{},\"when\":[]}]]").project();
        final List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            final boolean present = index % 2 == 0;
            calls.add(() -> match((RunResult.Succeeded) run(
                    project,
                    present ? Map.of("source", RailixValue.number(0)) : Map.of()
            )).equals(RailixValue.bool(present)));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (final Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList()).containsOnly(true);
        }
    }

    @Test
    void nestedPredicateInvalidOutputRemainsFailed() {
        final RunResult result = runResult(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                        + "{\"use\":\"value.invalid\",\"inputs\":{}}]}]]",
                Map.of()
        );

        assertThat(result).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step did not return its one declared compatible value.",
                        "value.invalid",
                        "nodes[2].inputs.matches[0][0].when[1]"
                ),
                List.of(new RunResult.StepExecution("value.equals", "ok"))
        ));
    }

    @Test
    void nestedPredicateInputRejectionRemainsRejected() {
        final RunResult result = runResult(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":["
                        + "{\"use\":\"value.non-boolean\",\"inputs\":{}},"
                        + "{\"use\":\"value.text-boolean\",\"inputs\":{}}]}]]",
                Map.of("source", RailixValue.number(0))
        );

        assertThat(result).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step value.text-boolean requires string but receives number.",
                        "nodes[2].inputs.matches[0][0].when[1]"
                )),
                List.of(new RunResult.StepExecution("value.non-boolean", "ok"))
        ));
    }

    @Test
    void nestedPredicateFaultRemainsFailed() {
        final RunResult result = runResult(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"fault\"}},"
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]}]]",
                Map.of()
        );

        assertThat(result).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault",
                        "nodes[2].inputs.matches[0][0].when[1]"
                ),
                List.of(new RunResult.StepExecution("value.equals", "ok"))
        ));
    }

    @Test
    void structuredTransformFaultRemainsFailed() {
        final RunResult result = runResult(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"value.fault\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                Map.of()
        );

        assertThat(result).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault",
                        "nodes[2].inputs.matches[0][0].when.transforms[0]"
                ),
                List.of()
        ));
    }

    @Test
    void additionalStructuredPredicateFaultRemainsFailed() {
        final RunResult result = runResult(
                "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                        + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                        + "{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],["
                        + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]",
                Map.of()
        );

        assertThat(result).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault",
                        "nodes[2].inputs.matches[0][0].when.all[1][0]"
                ),
                List.of(
                        new RunResult.StepExecution("text.length", "ok"),
                        new RunResult.StepExecution("number.greater-than", "ok")
                )
        ));
    }

    @Test
    void nestedPredicateInterruptionRemainsCancelled() {
        try {
            final RunResult result = runResult(
                    "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":["
                            + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                            + "{\"use\":\"value.interrupt\",\"inputs\":{}}]}]]",
                    Map.of()
            );

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result).isEqualTo(new RunResult.Cancelled(
                    List.of(new RunResult.StepExecution("value.equals", "ok"))
            ));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void structuredTransformInterruptionRemainsCancelled() {
        try {
            final RunResult result = runResult(
                    "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":{"
                            + "\"transforms\":[{\"use\":\"value.interrupt\",\"inputs\":{}}],\"all\":[["
                            + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]",
                    Map.of()
            );

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result).isEqualTo(new RunResult.Cancelled(List.of()));
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertPresent(final RailixValue value) {
        final RunResult.Succeeded result = run(
                "[[{\"option\":\"current\",\"inputs\":{},\"when\":[]}]]",
                Map.of("source", value)
        );

        assertThat(match(result)).isEqualTo(RailixValue.bool(true));
    }

    private static void assertRejected(final String groups, final String code, final String path) {
        final CompileResult result = ProjectCompiler.compile(project(groups), catalog());

        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        assertThat(((CompileResult.Rejected) result).diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.path()).isEqualTo(path);
        });
    }

    private static CompileResult.Compiled compiled(final String groups) {
        final CompileResult result = ProjectCompiler.compile(project(groups), catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static RunResult.Succeeded run(final String groups, final Map<String, RailixValue> payload) {
        return (RunResult.Succeeded) runResult(groups, payload);
    }

    private static RunResult runResult(final String groups, final Map<String, RailixValue> payload) {
        return run(compiled(groups).project(), payload);
    }

    private static RunResult run(final CompiledProject project, final Map<String, RailixValue> payload) {
        return project.run("command", new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                "payload", RailixValue.object(payload)
        ))));
    }

    private static RailixValue match(final RunResult.Succeeded result) {
        return ((RailixValue.ObjectValue) result.context().values().get("payload")).values().get("match");
    }

    private static StepCatalog catalog() {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition trigger = StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(input -> StepResult.outcome(input.primaryOutcome()));
        final StepDefinition equals = predicate("value.equals")
                .input("expected", StepDefinition.Input.json(ValueShape.ANY))
                .run(input -> StepResult.outcome(input.primaryOutcome())
                        .output("value", RailixValue.bool(input.value("value").equals(input.value("expected")))));
        final StepDefinition nonBoolean = StepDefinition.named("value.non-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", input.value("value")));
        final StepDefinition textBoolean = StepDefinition.named("value.text-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.BOOLEAN)
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true)));
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
        final StepDefinition greaterThan = StepDefinition.named("number.greater-than", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .input("than", StepDefinition.Input.json(ValueShape.NUMBER))
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "value",
                        RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                                ((RailixValue.NumberValue) input.value("than")).value()
                        ) > 0)
                ));
        final StepDefinition lessThan = StepDefinition.named("number.less-than", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .input("than", StepDefinition.Input.json(ValueShape.NUMBER))
                .run(input -> StepResult.outcome(input.primaryOutcome()).output(
                        "value",
                        RailixValue.bool(((RailixValue.NumberValue) input.value("value")).value().compareTo(
                                ((RailixValue.NumberValue) input.value("than")).value()
                        ) < 0)
                ));
        final StepDefinition branchingPredicate = predicate("value.branching-predicate")
                .outcome("rejected")
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true)));
        final StepDefinition fault = predicate("value.fault").run(input -> {
            throw new IllegalStateException("fault");
        });
        final StepDefinition interrupt = predicate("value.interrupt").run(input -> {
            Thread.currentThread().interrupt();
            return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.bool(true));
        });
        final StepDefinition invalid = predicate("value.invalid")
                .run(input -> StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("wrong")));
        final StepDefinition probe = StepDefinition.named("test.probe", "1")
                .input("source", StepDefinition.Input.path(READ).defaultPath("context", "payload", "source"))
                .input("matches", StepDefinition.Input.matcherGroups(
                        StepDefinition.Input.option("current").fromParent("source"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("target", StepDefinition.Input.path(WRITE).defaultPath("context", "payload", "match"))
                .run(input -> StepResult.outcome(input.primaryOutcome()).write("target", input.value("matches")));
        return StepCatalog.of(
                app,
                trigger,
                equals,
                nonBoolean,
                textBoolean,
                not,
                length,
                greaterThan,
                lessThan,
                branchingPredicate,
                fault,
                interrupt,
                invalid,
                probe
        );
    }

    private static StepDefinition.Builder predicate(final String id) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN);
    }

    private static String project(final String groups) {
        return """
                {"format":1,"id":"matcher-groups","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger","inputs":{},"examples":[
                    {"name":"example","payload":{},"context":{"payload":{}}}
                  ]},
                  {"id":"probe","use":"test.probe","inputs":{"matches":%s}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"probe"},
                  {"from":"probe.next","to":"end"}
                ]}
                """.formatted(groups);
    }
}
