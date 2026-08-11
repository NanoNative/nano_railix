package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.contextCatalog;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.contextProject;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.fallibleCatalog;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.fallibleProject;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.graphPrimitiveProject;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.triggerOnlyProject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CreatorFirstExecutionE2eTest {
    @Test
    void unaryGraphStepReadsAndWritesTheMappedContextPath() {
        final RunResult.Succeeded result = succeeded(run(
                compiled(graphPrimitiveProject(), contextCatalog()),
                RailixValue.object(Map.of(
                        "payload",
                        RailixValue.object(Map.of("name", RailixValue.string("Hello RAILIX")))
                ))
        ));

        assertThat(path(result.context(), "payload", "name"))
                .isEqualTo(RailixValue.string("hello railix"));
    }

    @Test
    void applicationArgumentsReachTheExclusiveCliTriggerInOrder() {
        final CompiledProject.SourceResult source = compiled(triggerOnlyProject(), contextCatalog()).runSource(
                "application.arguments",
                Map.of("arguments", RailixValue.array(List.of(
                        RailixValue.string("first"),
                        RailixValue.string("second")
                )))
        );

        final RunResult.Succeeded result = (RunResult.Succeeded) source.result();
        assertThat(path(result.context(), "payload", "arguments")).isEqualTo(RailixValue.array(List.of(
                RailixValue.string("first"),
                RailixValue.string("second")
        )));
    }

    @Test
    void cliSourceReturnsDeclaredResponseSlots() {
        final CompiledProject.SourceResult result = compiled(triggerOnlyProject(), contextCatalog()).runSource(
                "application.arguments",
                Map.of("arguments", RailixValue.array(List.of()))
        );

        assertThat(result.responses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "output", RailixValue.nullValue(),
                "status", RailixValue.number(0)
        ));
    }

    @Test
    void missingSourceNameReturnsAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog()).runSource(null, Map.of()).result(),
                "RUN_SOURCE_REQUIRED"
        );
    }

    @Test
    void unknownSourceReturnsAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog()).runSource("missing", Map.of()).result(),
                "RUN_SOURCE_UNKNOWN"
        );
    }

    @Test
    void missingSourceValuesReturnAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog())
                        .runSource("application.arguments", null)
                        .result(),
                "RUN_SOURCE_VALUES_REQUIRED"
        );
    }

    @Test
    void unknownSourceValueReturnsAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog()).runSource(
                        "application.arguments",
                        Map.of("other", RailixValue.array(List.of()))
                ).result(),
                "RUN_SOURCE_VALUE_UNKNOWN"
        );
    }

    @Test
    void missingRequiredSourceValueReturnsAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog())
                        .runSource("application.arguments", Map.of())
                        .result(),
                "RUN_SOURCE_VALUE_REQUIRED"
        );
    }

    @Test
    void incompatibleSourceValueReturnsAnExplicitRejection() {
        assertRejected(
                compiled(triggerOnlyProject(), contextCatalog()).runSource(
                        "application.arguments",
                        Map.of("arguments", RailixValue.string("wrong"))
                ).result(),
                "RUN_SOURCE_VALUE_INCOMPATIBLE"
        );
    }

    @Test
    void genericFieldManipulationTransformsCopiesAndPreservesContext() {
        final RunResult.Succeeded result = succeeded(run(
                compiled(contextProject(), contextCatalog()),
                context("Hello RAILIX")
        ));

        assertThat(path(result.context(), "payload", "name"))
                .isEqualTo(RailixValue.string("hello railix"));
        assertThat(result.context().values().get("result"))
                .isEqualTo(RailixValue.string("hello railix"));
        assertThat(path(result.context(), "metadata", "trace_id"))
                .isEqualTo(RailixValue.string("actual"));
    }

    @Test
    void repeatedItemsDoNotRetainEarlierValues() {
        final CompiledProject project = compiled(contextProject(), contextCatalog());

        final RunResult.Succeeded first = succeeded(run(project, context("First")));
        final RunResult.Succeeded second = succeeded(run(project, context("Second")));

        assertThat(first.context().values().get("result")).isEqualTo(RailixValue.string("first"));
        assertThat(second.context().values().get("result")).isEqualTo(RailixValue.string("second"));
    }

    @Test
    void concurrentItemsUseIndependentWorkflowContexts() throws Exception {
        final CompiledProject project = compiled(contextProject(), contextCatalog());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> succeeded(run(project, context("Alpha"))));
            final var second = executor.submit(() -> succeeded(run(project, context("Beta"))));

            assertThat(first.get().context().values().get("result"))
                    .isEqualTo(RailixValue.string("alpha"));
            assertThat(second.get().context().values().get("result"))
                    .isEqualTo(RailixValue.string("beta"));
        }
    }

    @Test
    void missingSelectedFieldLeavesTheTargetUnchangedAndContinues() {
        final RunResult.Succeeded result = succeeded(run(
                compiled(contextProject(), contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        ));

        assertThat(result.steps()).contains(
                new RunResult.StepExecution("normalise-name", "next"),
                new RunResult.StepExecution("return-name", "next")
        );
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void missingFallbackCreatesTheSelectedField() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"payload\",\"created\"],"
                        + "\"value\":[{\"option\":\"current\",\"inputs\":{}},"
                        + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fallback\"}}],"
                        + "\"steps\":[]}",
                "{\"payload\":{}}",
                ""
        );
        final RunResult.Succeeded result = succeeded(run(
                compiled(source, contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        ));

        assertThat(path(result.context(), "payload", "created"))
                .isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void literalOptionReplacesTheSelectedField() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"payload\",\"name\"],"
                        + "\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fixed\"}}],"
                        + "\"steps\":[]}",
                "{\"payload\":{\"name\":\"before\"}}",
                ""
        );
        final RunResult.Succeeded result = succeeded(run(
                compiled(source, contextCatalog()),
                context("before")
        ));

        assertThat(path(result.context(), "payload", "name"))
                .isEqualTo(RailixValue.string("fixed"));
    }

    @Test
    void fieldOptionCopiesAnotherContextValue() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"payload\",\"copy\"],"
                        + "\"value\":[{\"option\":\"field\",\"inputs\":{"
                        + "\"source\":[\"context\",\"payload\",\"name\"]}}],\"steps\":[]}",
                "{\"payload\":{\"name\":\"source\"}}",
                ""
        );
        final RunResult.Succeeded result = succeeded(run(
                compiled(source, contextCatalog()),
                context("source")
        ));

        assertThat(path(result.context(), "payload", "copy"))
                .isEqualTo(RailixValue.string("source"));
    }

    @Test
    void writablePathCreatesMissingObjectAndArrayContainers() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"payload\",\"groups\",1,\"name\"],"
                        + "\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"created\"}}],"
                        + "\"steps\":[]}",
                "{\"payload\":{}}",
                ""
        );
        final RunResult.Succeeded result = succeeded(run(
                compiled(source, contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        ));

        assertThat(path(result.context(), "payload", "groups", 1, "name"))
                .isEqualTo(RailixValue.string("created"));
    }

    @Test
    void writablePathConflictReturnsAnExplicitRejection() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"payload\",\"parent\",\"child\"],"
                        + "\"value\":[{\"option\":\"literal\",\"inputs\":{\"literal\":\"created\"}}],"
                        + "\"steps\":[]}",
                "{\"payload\":{\"parent\":{}}}",
                ""
        );
        final RunResult result = run(
                compiled(source, contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "parent", RailixValue.string("occupied")
                ))))
        );

        assertRejected(result, "RUN_FIELD_TARGET_CONFLICT");
    }

    @Test
    void readablePathMaySelectRuntimeValues() {
        final String source = manipulationProject(
                "{\"field\":[\"context\",\"result\"],"
                        + "\"value\":[{\"option\":\"field\",\"inputs\":{"
                        + "\"source\":[\"context\",\"runtime\",\"trigger\"]}}],\"steps\":[]}",
                "{\"payload\":{}}",
                ""
        );
        final RunResult.Succeeded result = succeeded(run(
                compiled(source, contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        ));

        assertThat(result.context().values().get("result"))
                .isEqualTo(RailixValue.string("command"));
    }

    @Test
    void triggerDefaultsReplaceIncomingResultValues() {
        final RunResult.Succeeded result = succeeded(compiled(triggerOnlyProject(), contextCatalog()).run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of()),
                        "result", RailixValue.string("untrusted")
                )))
        ));

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void falliblePrimitiveSuccessWritesItsOutputAndFollowsNext() {
        final RunResult.Succeeded result = succeeded(compiled(fallibleProject(), fallibleCatalog()).run(
                "command",
                new CompiledProject.StreamItem(false, valueContext(RailixValue.string("12.50")))
        ));

        assertThat(path(result.context(), "payload", "value"))
                .isEqualTo(RailixValue.number(new BigDecimal("12.50")));
        assertThat(result.steps()).contains(new RunResult.StepExecution("convert", "next"));
    }

    @Test
    void falliblePrimitivePreservesInputAndContinuesItsHostProgram() {
        final RunResult.Succeeded result = succeeded(compiled(fallibleProject(), fallibleCatalog()).run(
                "command",
                new CompiledProject.StreamItem(false, valueContext(RailixValue.string("not-a-number")))
        ));

        assertThat(path(result.context(), "payload", "value"))
                .isEqualTo(RailixValue.string("not-a-number"));
        assertThat(result.context().values().get("result"))
                .isEqualTo(RailixValue.string("not-a-number"));
        assertThat(result.steps()).contains(
                new RunResult.StepExecution("text.to-number", "invalid"),
                new RunResult.StepExecution("convert", "next"),
                new RunResult.StepExecution("number-result", "next")
        );
    }

    @Test
    void incompatibleNestedInputReturnsAnExplicitRejection() {
        final RunResult result = run(
                compiled(contextProject(), contextCatalog()),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of(
                        "name", RailixValue.number(7)
                ))))
        );

        assertRejected(result, "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsANoncanonicalDescendantBeforeCallingTheHandler() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.number(
                BigDecimal.TEN.pow(dev.nanonative.railix.core.value.RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
        )));
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(64))
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(64))
                .run(input -> {
                    if (input.value("value").equals(invalid)) {
                        throw new IllegalStateException("Refined input reached the handler.");
                    }
                    return StepResult.outcome("ok").output("value", input.value("value"));
                });

        assertRejected(runProbe(primitive, invalid), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsAValueBeyondItsDeclaredDepth() {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(1))
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(1))
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.array(List.of())));

        assertRejected(runProbe(primitive, invalid), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsCanonicalJsonBeyondItsDeclaredBytes() {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING, ValueRefinement.canonical().withMaxJsonBytes(6))
                .returns("value", ValueShape.STRING, ValueRefinement.canonical().withMaxJsonBytes(6))
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));

        assertRejected(runProbe(primitive, RailixValue.string("longer")), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedOutputRejectsANoncanonicalDescendant() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.number(
                BigDecimal.TEN.pow(dev.nanonative.railix.core.value.RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
        )));
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(64))
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        input.string("value").equals("fault") ? invalid : input.value("value")
                ));

        final RunResult result = runProbe(primitive, RailixValue.string("fault"));

        assertThat(((RunResult.Failed) result).failure()).isEqualTo(new RunFailure(
                "STEP_OUTPUT_INVALID",
                "Nested Step returned an incompatible value: "
                        + "Value contains a number outside the canonical 1024-character domain.",
                "test.probe",
                "nodes[2].inputs.steps[0]"
        ));
    }

    @Test
    void refinedNestedOutputRejectsAValueBeyondItsDeclaredDepth() {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(1))
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        input.string("value").equals("fault")
                                ? nestedArrays(2)
                                : input.value("value")
                ));

        assertFailed(runProbe(primitive, RailixValue.string("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void refinedNestedOutputRejectsCanonicalJsonBeyondItsDeclaredBytes() {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING, ValueRefinement.canonical().withMaxJsonBytes(6))
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        RailixValue.string(input.string("value").equals("fault") ? "longer" : "safe")
                ));

        assertFailed(runProbe(primitive, RailixValue.string("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void rejectedRefinedOutputIsNotCapturedAsAPreviewStage() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.string("\uD800")));
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(64))
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        input.string("value").equals("fault") ? invalid : input.value("value")
                ));
        final CompiledProject.PreviewResult preview = compiledProbe(primitive).preview(
                "command",
                "manipulate",
                new CompiledProject.StreamItem(true, valueContext(RailixValue.string("fault")))
        );

        assertThat(((RunResult.Failed) preview.result()).failure()).isEqualTo(new RunFailure(
                "STEP_OUTPUT_INVALID",
                "Nested Step returned an incompatible value: Value contains an unpaired Unicode surrogate.",
                "test.probe",
                "nodes[2].inputs.steps[0]"
        ));
        assertThat(preview.stages()).isEmpty();
    }

    @Test
    void unrefinedNestedInputReachesTheHandlerWithoutDescendantTraversal() {
        final RailixValue invalid = RailixValue.array(List.of(
                RailixValue.number(BigDecimal.TEN.pow(1_024)),
                RailixValue.string("\uD800")
        ));
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        input.value("value").equals(invalid) ? input.value("value") : RailixValue.nullValue()
                ));

        assertThat(path(succeeded(runProbe(primitive, invalid)).context(), "payload", "value"))
                .isEqualTo(invalid);
    }

    @Test
    void unrefinedNestedOutputDoesNotTraverseAnInvalidDescendant() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.string("\uD800")));
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.ANY)
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        input.string("value").equals("fault") ? invalid : input.value("value")
                ));

        assertThat(path(
                succeeded(runProbe(primitive, RailixValue.string("fault"))).context(),
                "payload",
                "value"
        )).isEqualTo(invalid);
    }

    @Test
    void validRefinedNestedValueExecutesThroughTheCompiledFlow() {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(2))
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(2))
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));
        final RailixValue value = RailixValue.array(List.of(RailixValue.object(Map.of("ok", RailixValue.bool(true)))));

        assertThat(succeeded(runProbe(primitive, value)).context().values().get("payload"))
                .isEqualTo(RailixValue.object(Map.of("value", value)));
    }

    @Test
    void JavaNullNestedResultBecomesAnExplicitFailure() {
        assertProbeFailure("fault", input -> input.string("value").equals("fault")
                ? null
                : StepResult.outcome("ok").output("value", input.value("value")), "STEP_RESULT_REQUIRED");
    }

    @Test
    void undeclaredNestedOutcomeBecomesAnExplicitFailure() {
        assertProbeFailure("fault", input -> input.string("value").equals("fault")
                ? StepResult.outcome("other")
                : StepResult.outcome("ok").output("value", input.value("value")), "STEP_OUTCOME_INVALID");
    }

    @Test
    void missingNestedOutputBecomesAnExplicitFailure() {
        assertProbeFailure("fault", input -> input.string("value").equals("fault")
                ? StepResult.outcome("ok")
                : StepResult.outcome("ok").output("value", input.value("value")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void incompatibleNestedOutputBecomesAnExplicitFailure() {
        assertProbeFailure("fault", input -> input.string("value").equals("fault")
                ? StepResult.outcome("ok").output("value", RailixValue.number(1))
                : StepResult.outcome("ok").output("value", input.value("value")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void nestedImplementationExceptionBecomesAnExplicitFailure() {
        assertProbeFailure("fault", input -> {
            if (input.string("value").equals("fault")) {
                throw new IllegalStateException("private detail");
            }
            return StepResult.outcome("ok").output("value", input.value("value"));
        }, "STEP_IMPLEMENTATION_FAULT");
    }

    @Test
    void previewCapturesResolvedGenericInputs() {
        final CompiledProject.PreviewResult preview = compiled(contextProject(), contextCatalog()).preview(
                "command",
                "normalise-name",
                new CompiledProject.StreamItem(true, context("Hello RAILIX"))
        );

        assertThat(preview.result()).isInstanceOf(RunResult.Succeeded.class);
        assertThat(preview.inputs()).containsEntry("field", RailixValue.string("Hello RAILIX"));
        assertThat(preview.inputs()).containsEntry("value", RailixValue.string("Hello RAILIX"));
    }

    @Test
    void previewCapturesContextBeforeTheRequestedStepRuns() {
        final CompiledProject.PreviewResult preview = compiled(contextProject(), contextCatalog()).preview(
                "command",
                "normalise-name",
                new CompiledProject.StreamItem(true, context("Hello RAILIX"))
        );

        assertThat(((RailixValue.ObjectValue) preview.inputContext().values().get("payload"))
                .values().get("name")).isEqualTo(RailixValue.string("Hello RAILIX"));
    }

    @Test
    void previewCapturesEachNestedStepStage() {
        final CompiledProject.PreviewResult preview = compiled(contextProject(), contextCatalog()).preview(
                "command",
                "normalise-name",
                new CompiledProject.StreamItem(true, context("Hello RAILIX"))
        );

        assertThat(preview.stages()).containsExactly(new CompiledProject.NestedStepStage(
                "steps",
                "nodes[2].inputs.steps[0]",
                "text.lowercase",
                "succeeded",
                List.of(RailixValue.string("hello railix"))
        ));
    }

    @Test
    void previewStopsAfterTheRequestedStep() {
        final RunResult.Succeeded result = succeeded(compiled(contextProject(), contextCatalog()).preview(
                "command",
                "normalise-name",
                new CompiledProject.StreamItem(true, context("Hello RAILIX"))
        ).result());

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
        assertThat(result.steps()).doesNotContain(new RunResult.StepExecution("return-name", "next"));
    }

    @Test
    void previewExecutesTheRequiredPrefixForALaterStep() {
        final CompiledProject.PreviewResult preview = compiled(contextProject(), contextCatalog()).preview(
                "command",
                "return-name",
                new CompiledProject.StreamItem(true, context("Hello RAILIX"))
        );

        assertThat(preview.inputs()).containsEntry("value", RailixValue.string("hello railix"));
    }

    @Test
    void unknownPreviewStepReturnsAnExplicitRejection() {
        assertRejected(compiled(contextProject(), contextCatalog()).preview(
                "command",
                "missing",
                new CompiledProject.StreamItem(true, context("Hello"))
        ).result(), "PREVIEW_STEP_UNKNOWN");
    }

    @Test
    void blankPreviewStepReturnsAnExplicitRejection() {
        assertRejected(compiled(contextProject(), contextCatalog()).preview(
                "command",
                " ",
                new CompiledProject.StreamItem(true, context("Hello"))
        ).result(), "PREVIEW_STEP_REQUIRED");
    }

    @Test
    void previewAfterAFalliblePrimitiveReceivesTheUnchangedValue() {
        final CompiledProject.PreviewResult preview = compiled(fallibleProject(), fallibleCatalog()).preview(
                "command",
                "number-result",
                new CompiledProject.StreamItem(true, valueContext(RailixValue.string("invalid")))
        );

        assertThat(preview.result()).isInstanceOf(RunResult.Succeeded.class);
        assertThat(preview.inputs()).containsEntry("value", RailixValue.string("invalid"));
    }

    @Test
    void missingRunTriggerReturnsAnExplicitRejection() {
        assertRejected(compiled(contextProject(), contextCatalog()).run(
                null,
                new CompiledProject.StreamItem(false, context("Hello"))
        ), "RUN_TRIGGER_REQUIRED");
    }

    @Test
    void unknownRunTriggerReturnsAnExplicitRejection() {
        assertRejected(compiled(contextProject(), contextCatalog()).run(
                "missing",
                new CompiledProject.StreamItem(false, context("Hello"))
        ), "RUN_TRIGGER_UNKNOWN");
    }

    @Test
    void missingStreamItemReturnsAnExplicitRejection() {
        assertRejected(compiled(contextProject(), contextCatalog()).run("command", null), "RUN_INPUT_REQUIRED");
    }

    @Test
    void callerCannotSupplyTheRuntimeNamespace() {
        assertRejected(compiled(contextProject(), contextCatalog()).run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "runtime", RailixValue.object(Map.of())
                )))
        ), "RUN_RUNTIME_RESERVED");
    }

    @Test
    void JavaNullContextIsRejectedAtTheStreamBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CompiledProject.StreamItem(false, null))
                .withMessage("Stream item context cannot be Java null.");
    }

    @Test
    void interruptionBeforeExecutionReturnsCancellation() {
        final CompiledProject project = compiled(contextProject(), contextCatalog());
        Thread.currentThread().interrupt();
        try {
            assertThat(run(project, context("Hello")))
                    .isInstanceOf(RunResult.Cancelled.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertProbeFailure(
            final String actual,
            final dev.nanonative.railix.core.step.StepHandler handler,
            final String code
    ) {
        final StepDefinition primitive = StepDefinition.named("test.probe", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .run(handler);
        final RunResult result = compiled(
                manipulationProject(
                        "{\"field\":[\"context\",\"payload\",\"value\"],"
                                + "\"value\":[{\"option\":\"current\",\"inputs\":{}}],"
                                + "\"steps\":[{\"use\":\"test.probe\",\"inputs\":{}}]}",
                        "{\"payload\":{\"value\":\"safe\"}}",
                        ""
                ),
                withPrimitive(primitive)
        ).run("command", new CompiledProject.StreamItem(false, valueContext(RailixValue.string(actual))));

        assertFailed(result, code);
    }

    private static RunResult runProbe(final StepDefinition primitive, final RailixValue actual) {
        return compiledProbe(primitive).run(
                "command",
                new CompiledProject.StreamItem(false, valueContext(actual))
        );
    }

    private static CompiledProject compiledProbe(final StepDefinition primitive) {
        return compiled(
                manipulationProject(
                        "{\"field\":[\"context\",\"payload\",\"value\"],"
                                + "\"value\":[{\"option\":\"current\",\"inputs\":{}}],"
                                + "\"steps\":[{\"use\":\"test.probe\",\"inputs\":{}}]}",
                        "{\"payload\":{\"value\":\"safe\"}}",
                        ""
                ),
                withPrimitive(primitive)
        );
    }

    private static String manipulationProject(
            final String inputs,
            final String exampleContext,
            final String additionalLinks
    ) {
        return """
                {"format":1,"id":"manipulation-test","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"valid","payload":[],"context":%s}
                  ]},
                  {"id":"manipulate","use":"railix.field-manipulation","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"manipulate"},
                  {"from":"manipulate.next","to":"end"}%s
                ]}
                """.formatted(exampleContext, inputs, additionalLinks);
    }

    private static StepCatalog withPrimitive(final StepDefinition primitive) {
        final List<StepDefinition> definitions = contextCatalog().definitions();
        return StepCatalog.of(definitions.get(0), definitions.get(1), definitions.get(2), primitive);
    }

    private static CompiledProject compiled(final String source, final StepCatalog catalog) {
        return ((CompileResult.Compiled) ProjectCompiler.compile(source, catalog)).project();
    }

    private static RunResult run(final CompiledProject project, final RailixValue.ObjectValue context) {
        return project.run("command", new CompiledProject.StreamItem(false, context));
    }

    private static RunResult.Succeeded succeeded(final RunResult result) {
        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        return (RunResult.Succeeded) result;
    }

    private static void assertRejected(final RunResult result, final String code) {
        assertThat(result).isInstanceOf(RunResult.Rejected.class);
        assertThat(((RunResult.Rejected) result).diagnostics().getFirst().code()).isEqualTo(code);
    }

    private static void assertFailed(final RunResult result, final String code) {
        assertThat(result).isInstanceOf(RunResult.Failed.class);
        assertThat(((RunResult.Failed) result).failure().code()).isEqualTo(code);
    }

    private static RailixValue.ObjectValue context(final String name) {
        return RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("name", RailixValue.string(name))),
                "metadata", RailixValue.object(Map.of("trace_id", RailixValue.string("actual")))
        ));
    }

    private static RailixValue.ObjectValue valueContext(final RailixValue value) {
        return RailixValue.object(Map.of("payload", RailixValue.object(Map.of("value", value))));
    }

    private static RailixValue path(final RailixValue root, final Object... elements) {
        RailixValue current = root;
        for (final Object element : elements) {
            current = element instanceof String field
                    ? ((RailixValue.ObjectValue) current).values().get(field)
                    : ((RailixValue.ArrayValue) current).values().get((Integer) element);
        }
        return current;
    }

    private static RailixValue nestedArrays(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }
}
