package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.CreatorFirstExecutionSteps;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(180)
final class CreatorFirstExecutionGeneratedE2eTest {
    private GeneratedApplicationFixture application;
    private Path orderedCliJar;
    private Path triggerOnlyCliJar;
    private CreatorFirstExecutionArtifacts.ProductionProbe productionProbe;
    private CreatorFirstExecutionArtifacts.DevelopmentProbe developmentProbe;

    @BeforeAll
    void startGeneratedApplications(@TempDir final Path workspace) throws Exception {
        final Path generated = workspace.resolve("generated");
        application = GeneratedApplicationFixture.start(
                generated,
                CreatorFirstExecutionProject.source(),
                CreatorFirstExecutionProject.definitions(),
                CreatorFirstExecutionSteps.Trigger.class,
                CreatorFirstExecutionSteps.Probe.class
        );
        final Path generatedJar = CreatorFirstExecutionArtifacts.generatedJar(generated);
        orderedCliJar = CreatorFirstExecutionArtifacts.production(
                workspace.resolve("ordered-cli"),
                CreatorFirstExecutionProject.orderedCliSource()
        );
        triggerOnlyCliJar = CreatorFirstExecutionArtifacts.production(
                workspace.resolve("trigger-only-cli"),
                CreatorFirstExecutionProject.triggerOnlyCliSource()
        );
        productionProbe = CreatorFirstExecutionArtifacts.compileProductionProbe(
                workspace.resolve("production-probe"),
                triggerOnlyCliJar
        );
        developmentProbe = CreatorFirstExecutionArtifacts.compileDevelopmentProbe(
                workspace.resolve("development-probe"),
                generatedJar
        );
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void unaryGraphStepReadsAndWritesTheMappedContextPath() throws Exception {
        final DevelopmentApplication.Response response = application.runTest(
                "graph", "{\"payload\":{\"name\":\"Hello RAILIX\"}}"
        );
        final RailixValue.ObjectValue body = body(response);
        final RailixValue.ObjectValue context = (RailixValue.ObjectValue) path(body, "context");

        assertThat(response.status()).isEqualTo(200);
        assertThat(path(body, "status")).isEqualTo(RailixValue.string("succeeded"));
        assertThat(path(context, "payload", "name")).isEqualTo(RailixValue.string("hello railix"));
    }

    @Test
    void applicationArgumentsReachTheExclusiveCliTriggerInOrder() throws Exception {
        final CreatorFirstExecutionArtifacts.ProcessResult result = CreatorFirstExecutionArtifacts.runJar(
                orderedCliJar,
                "first",
                "second"
        );

        assertThat(result).isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(
                0,
                "[\"first\",\"second\"]"
        ));
    }

    @Test
    void cliSourceReturnsDeclaredResponseSlots() throws Exception {
        final CreatorFirstExecutionArtifacts.ProcessResult cli = CreatorFirstExecutionArtifacts.runJar(
                triggerOnlyCliJar
        );
        final CreatorFirstExecutionArtifacts.ProcessResult source = sourceProbe("source-response-slots");

        assertThat(cli).isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(0, ""));
        assertThat(source).isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(
                0,
                "succeeded|output-null=true|status-zero=true"
        ));
    }

    @Test
    void missingSourceNameReturnsAnExplicitRejection() throws Exception {
        assertProductionProbe("missing-source-name", "rejected:RUN_SOURCE_REQUIRED");
    }

    @Test
    void unknownSourceReturnsAnExplicitRejection() throws Exception {
        assertProductionProbe("unknown-source", "rejected:RUN_SOURCE_UNKNOWN");
    }

    @Test
    void missingSourceValuesReturnAnExplicitRejection() throws Exception {
        assertProductionProbe("missing-source-values", "rejected:RUN_SOURCE_VALUES_REQUIRED");
    }

    @Test
    void unknownSourceValueReturnsAnExplicitRejection() throws Exception {
        assertProductionProbe("unknown-source-value", "rejected:RUN_SOURCE_VALUE_UNKNOWN");
    }

    @Test
    void missingRequiredSourceValueReturnsAnExplicitRejection() throws Exception {
        assertProductionProbe("missing-required-source-value", "rejected:RUN_SOURCE_VALUE_REQUIRED");
    }

    @Test
    void incompatibleSourceValueReturnsAnExplicitRejection() throws Exception {
        assertProductionProbe("incompatible-source-value", "rejected:RUN_SOURCE_VALUE_INCOMPATIBLE");
    }

    @Test
    void genericFieldManipulationTransformsCopiesAndPreservesContext() throws Exception {
        final RailixValue.ObjectValue context = successful(run("context", named("Hello RAILIX")));

        assertThat(path(context, "payload", "name")).isEqualTo(RailixValue.string("hello railix"));
        assertThat(path(context, "result")).isEqualTo(RailixValue.string("hello railix"));
        assertThat(path(context, "metadata", "trace_id")).isEqualTo(RailixValue.string("actual"));
    }

    @Test
    void repeatedItemsDoNotRetainEarlierValues() throws Exception {
        final RailixValue.ObjectValue first = successful(run("context", named("First")));
        final RailixValue.ObjectValue second = successful(run("context", named("Second")));

        assertThat(path(first, "result")).isEqualTo(RailixValue.string("first"));
        assertThat(path(second, "result")).isEqualTo(RailixValue.string("second"));
    }

    @Test
    void concurrentItemsUseIndependentWorkflowContexts() throws Exception {
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(() -> concurrentRun(ready, start, "Alpha"));
            final var second = executor.submit(() -> concurrentRun(ready, start, "Beta"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(path(first.get(), "result")).isEqualTo(RailixValue.string("alpha"));
            assertThat(path(second.get(), "result")).isEqualTo(RailixValue.string("beta"));
        }
    }

    @Test
    void missingSelectedFieldLeavesTheTargetUnchangedAndContinues() throws Exception {
        final RailixValue.ObjectValue body = body(application.runTest("context", "{\"payload\":{}}"));

        assertThat(path(body, "status")).isEqualTo(RailixValue.string("succeeded"));
        assertThat(path(body, "context", "result")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void missingFallbackCreatesTheSelectedField() throws Exception {
        assertThat(path(successful(run("fallback", "{\"payload\":{}}")), "payload", "created"))
                .isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void literalOptionReplacesTheSelectedField() throws Exception {
        assertThat(path(successful(run("literal", "{\"payload\":{\"name\":\"before\"}}")), "payload", "name"))
                .isEqualTo(RailixValue.string("fixed"));
    }

    @Test
    void fieldOptionCopiesAnotherContextValue() throws Exception {
        assertThat(path(successful(run("copy", "{\"payload\":{\"name\":\"source\"}}")), "payload", "copy"))
                .isEqualTo(RailixValue.string("source"));
    }

    @Test
    void writablePathCreatesMissingObjectAndArrayContainers() throws Exception {
        assertThat(path(
                successful(run("containers", "{\"payload\":{}}")),
                "payload", "groups", 0, "name"
        )).isEqualTo(RailixValue.string("created"));
    }

    @Test
    void writablePathConflictReturnsAnExplicitRejection() throws Exception {
        assertDiagnostic(
                run("conflict", "{\"payload\":{\"parent\":\"occupied\"}}"),
                "RUN_FIELD_TARGET_CONFLICT"
        );
    }

    @Test
    void readablePathMaySelectRuntimeValues() throws Exception {
        assertThat(path(successful(run("command", "{\"payload\":{}}")), "result"))
                .isEqualTo(RailixValue.string("command"));
    }

    @Test
    void triggerDefaultsReplaceIncomingResultValues() throws Exception {
        assertThat(path(successful(run("defaults", "{\"payload\":{},\"result\":\"untrusted\"}")), "result"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void falliblePrimitiveSuccessWritesItsOutputAndFollowsNext() throws Exception {
        final RailixValue.ObjectValue body = body(application.runTest(
                "fallible", "{\"payload\":{\"value\":\"12.50\"}}"
        ));

        assertThat(((RailixValue.NumberValue) path(body, "context", "payload", "value")).value())
                .isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    void falliblePrimitivePreservesInputAndContinuesItsHostProgram() throws Exception {
        final RailixValue.ObjectValue body = body(application.runTest(
                "fallible", "{\"payload\":{\"value\":\"not-a-number\"}}"
        ));

        assertThat(path(body, "context", "payload", "value"))
                .isEqualTo(RailixValue.string("not-a-number"));
        assertThat(path(body, "context", "result")).isEqualTo(RailixValue.string("not-a-number"));
    }

    @Test
    void incompatibleNestedInputReturnsAnExplicitRejection() throws Exception {
        assertDiagnostic(run("context", "{\"payload\":{\"name\":7}}"), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsANoncanonicalDescendantBeforeCallingTheHandler() throws Exception {
        assertDevelopmentProbe("canonical-input", "rejected:RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsAValueBeyondItsDeclaredDepth() throws Exception {
        assertDiagnostic(run("depth-input", "{\"payload\":{\"value\":[[]]}}"), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedInputRejectsCanonicalJsonBeyondItsDeclaredBytes() throws Exception {
        assertDiagnostic(run("bytes-input", "{\"payload\":{\"value\":\"longer\"}}"), "RUN_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void refinedNestedOutputRejectsANoncanonicalDescendant() throws Exception {
        assertDevelopmentProbe(
                "canonical-output-detail",
                "failed:STEP_OUTPUT_INVALID|message=Nested Step returned an incompatible value: "
                        + "Value contains a number outside the canonical 1024-character domain."
                        + "|step=test.probe.canonical-output|path=nodes[2].inputs.steps[0]"
        );
    }

    @Test
    void refinedNestedOutputRejectsAValueBeyondItsDeclaredDepth() throws Exception {
        assertFailure(run("depth-output", value("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void refinedNestedOutputRejectsCanonicalJsonBeyondItsDeclaredBytes() throws Exception {
        assertFailure(run("bytes-output", value("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void unrefinedNestedInputReachesTheHandlerWithoutDescendantTraversal() throws Exception {
        assertDevelopmentProbe("unrefined-input", "succeeded:equal=true");
    }

    @Test
    void unrefinedNestedOutputDoesNotTraverseAnInvalidDescendant() throws Exception {
        assertDevelopmentProbe("unrefined-output", "succeeded:equal=true");
    }

    @Test
    void validRefinedNestedValueExecutesThroughTheCompiledFlow() throws Exception {
        final RailixValue expected = RailixValue.array(List.of(RailixValue.object(
                java.util.Map.of("ok", RailixValue.bool(true))
        )));

        assertThat(path(successful(run(
                "valid-refined", "{\"payload\":{\"value\":[{\"ok\":true}]}}"
        )), "payload", "value")).isEqualTo(expected);
    }

    @Test
    void JavaNullNestedResultBecomesAnExplicitFailure() throws Exception {
        assertFailure(run("null-result", value("fault")), "STEP_RESULT_REQUIRED");
    }

    @Test
    void undeclaredNestedOutcomeBecomesAnExplicitFailure() throws Exception {
        assertFailure(run("undeclared-outcome", value("fault")), "STEP_OUTCOME_INVALID");
    }

    @Test
    void missingNestedOutputBecomesAnExplicitFailure() throws Exception {
        assertFailure(run("missing-output", value("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void incompatibleNestedOutputBecomesAnExplicitFailure() throws Exception {
        assertFailure(run("number-output", value("fault")), "STEP_OUTPUT_INVALID");
    }

    @Test
    void nestedImplementationExceptionBecomesAnExplicitFailure() throws Exception {
        assertFailure(run("implementation-exception", value("fault")), "STEP_IMPLEMENTATION_FAULT");
    }

    @Test
    void traceReportsJavaNullNestedResultFailure() throws Exception {
        assertTraceFailure("null-result", "STEP_RESULT_REQUIRED");
    }

    @Test
    void traceReportsUndeclaredNestedOutcomeFailure() throws Exception {
        assertTraceFailure("undeclared-outcome", "STEP_OUTCOME_INVALID");
    }

    @Test
    void traceReportsMissingNestedOutputFailure() throws Exception {
        assertTraceFailure("missing-output", "STEP_OUTPUT_INVALID");
    }

    @Test
    void traceReportsIncompatibleNestedOutputFailure() throws Exception {
        assertTraceFailure("number-output", "STEP_OUTPUT_INVALID");
    }

    @Test
    void traceReportsNestedImplementationFailure() throws Exception {
        assertTraceFailure("implementation-exception", "STEP_IMPLEMENTATION_FAULT");
    }

    @Test
    void traceReportsUnknownTriggerRejection() throws Exception {
        final DevelopmentApplication.Response response = application.traceResponse("missing", named("Hello"));
        final RailixValue.ObjectValue body = body(response);

        assertThat(response.status()).isEqualTo(422);
        assertThat(path(body, "status")).isEqualTo(RailixValue.string("rejected"));
        assertThat(path(body, "diagnostics", 0, "code"))
                .isEqualTo(RailixValue.string("RUN_TRIGGER_UNKNOWN"));
    }

    @Test
    void missingRunTriggerReturnsAnExplicitRejection() throws Exception {
        assertDevelopmentProbe("missing-run-trigger", "rejected:RUN_TRIGGER_REQUIRED");
    }

    @Test
    void unknownRunTriggerReturnsAnExplicitRejection() throws Exception {
        assertDiagnostic(run("missing", named("Hello")), "RUN_TRIGGER_UNKNOWN");
    }

    @Test
    void unknownRunTriggerIsReportedBeforeReservedRuntimeContext() throws Exception {
        assertDiagnostic(run("missing", "{\"runtime\":{}}"), "RUN_TRIGGER_UNKNOWN");
    }

    @Test
    void missingStreamItemReturnsAnExplicitRejection() throws Exception {
        assertDevelopmentProbe("missing-stream-item", "rejected:RUN_INPUT_REQUIRED");
    }

    @Test
    void callerCannotSupplyTheRuntimeNamespace() throws Exception {
        assertDiagnostic(run("context", "{\"runtime\":{}}"), "RUN_RUNTIME_RESERVED");
    }

    @Test
    void interruptionBeforeExecutionReturnsCancellation() throws Exception {
        assertDevelopmentProbe("interrupted", "cancelled");
    }

    private RailixValue.ObjectValue concurrentRun(
            final CountDownLatch ready,
            final CountDownLatch start,
            final String name
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent requests did not start together.");
        }
        return successful(run("context", named(name)));
    }

    private DevelopmentApplication.Response run(final String trigger, final String context) throws Exception {
        return application.run(trigger, context);
    }

    private CreatorFirstExecutionArtifacts.ProcessResult sourceProbe(final String scenario) throws Exception {
        return productionProbe.run(scenario);
    }

    private void assertProductionProbe(final String scenario, final String expected) throws Exception {
        assertThat(productionProbe.run(scenario))
                .isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(0, expected));
    }

    private void assertDevelopmentProbe(final String scenario, final String expected) throws Exception {
        assertThat(developmentProbe.run(scenario))
                .isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(0, expected));
    }

    private void assertDiagnostic(final DevelopmentApplication.Response response, final String code) {
        assertThat(response.status()).isEqualTo(422);
        assertThat(path(body(response), "diagnostics", 0, "code")).isEqualTo(RailixValue.string(code));
    }

    private void assertFailure(final DevelopmentApplication.Response response, final String code) {
        assertThat(response.status()).isEqualTo(500);
        assertThat(path(body(response), "failure", "code")).isEqualTo(RailixValue.string(code));
    }

    private void assertTraceFailure(final String trigger, final String code) throws Exception {
        final List<RailixValue.ObjectValue> trace = application.trace(trigger, value("fault"));
        final RailixValue.ObjectValue terminal = trace.getLast();

        assertThat(path(terminal, "type")).isEqualTo(RailixValue.string("result"));
        assertThat(path(terminal, "status")).isEqualTo(RailixValue.string("failed"));
        assertThat(path(terminal, "failure", "code")).isEqualTo(RailixValue.string(code));
        assertThat(trace.stream().filter(event -> RailixValue.string("step_result")
                .equals(event.values().get("type"))).count()).isGreaterThanOrEqualTo(1);
    }

    private static RailixValue.ObjectValue successful(final DevelopmentApplication.Response response) {
        assertThat(response.status()).isEqualTo(200);
        final RailixValue.ObjectValue body = body(response);
        assertThat(path(body, "status")).isEqualTo(RailixValue.string("succeeded"));
        return (RailixValue.ObjectValue) path(body, "context");
    }

    private static RailixValue.ObjectValue body(final DevelopmentApplication.Response response) {
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
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

    private static String named(final String name) {
        return "{\"payload\":{\"name\":" + json(name)
                + "},\"metadata\":{\"trace_id\":\"actual\"}}";
    }

    private static String value(final String value) {
        return "{\"payload\":{\"value\":" + json(value) + "}}";
    }

    private static String json(final String value) {
        return RailixJson.write(RailixValue.string(value));
    }
}
