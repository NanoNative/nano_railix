package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class GeneratedApplicationVariantsE2eTest {
    @Test
    void productionApplicationExposesOnlyTheProductionRuntimeBoundary() {
        final String source = compiled().productionApplicationSource();

        assertThat(source)
                .contains("public final class RailixApplication implements RuntimeApplication")
                .contains("public String projectId()")
                .contains("public WorkflowRuntime.SourceResult runSource(")
                .doesNotContain("DevelopmentRuntime");
    }

    @Test
    void productionApplicationOmitsEveryObservationCapability() {
        assertThat(compiled().productionApplicationSource())
                .doesNotContain(
                        "public RunResult run(",
                        "DevelopmentRuntime.Metrics",
                        "startFlow(",
                        "startStep(",
                        "TraceExecution",
                        " observe(",
                        "WorkflowRuntime.Capture",
                        "ObservationCapture",
                        "select_",
                        "preview",
                        "railix.development",
                        "jdk.httpserver"
                );
    }

    @Test
    void developmentApplicationOwnsMetricsOutsideStepHandlers() {
        assertThat(compiled().developmentApplicationSource())
                .contains(
                        "DevelopmentRuntime.Metrics",
                        "startFlow(",
                        "startStep("
                )
                .doesNotContain("Metrics.invoke(", "execution.test()", "new boolean[]{");
    }

    @Test
    void traceExecutionNeverRecordsOperationalMetrics() {
        final String source = compiled().developmentApplicationSource();
        final String trace = source.substring(
                source.indexOf("private static RunResult trace_1"),
                source.indexOf("private static WorkflowRuntime.SourceResult source_1")
        );

        assertThat(trace).doesNotContain("METRICS");
    }

    @Test
    void productionSuccessPathDoesNotResolveOrRecordHistoryStrings() {
        final String source = compiled().productionApplicationSource();
        final int executor = source.indexOf("private static RunResult execute_");
        final int unrouted = source.indexOf("if (destination == UNROUTED)", executor);
        final int outcomeName = source.indexOf("final String outcomeName", executor);

        assertThat(source).doesNotContain("execution.record(");
        assertThat(executor).isNotNegative();
        assertThat(outcomeName).isGreaterThan(unrouted);
    }

    @Test
    void productionStepCallsContainNoTraceOnlyArgumentsOrSelectors() {
        assertThat(compiled().productionApplicationSource())
                .contains("WorkflowRuntime.StepCall CALL_0 = HANDLER_0::run;")
                .doesNotContain(
                        "final String invocation",
                        "private static String use_",
                        "DevelopmentRuntime.Trace"
                );
    }

    @Test
    void generatedApplicationsRouteWithPrimitiveOutcomeCodes() {
        final CompileResult.Compiled compiled = compiled();

        assertThat(compiled.productionApplicationSource())
                .contains(
                        "final int outcome = execution.call(",
                        "private static int dispatch_",
                        "private static int dispatch("
                )
                .doesNotContain(
                        "WorkflowRuntime.CallResult",
                        "WorkflowRuntime.Called",
                        "WorkflowRuntime.Aborted"
                );
        assertThat(compiled.developmentApplicationSource())
                .contains(
                        "final int outcome = dispatch_",
                        "private static int dispatch_",
                        "private static int dispatch("
                )
                .doesNotContain(
                        "WorkflowRuntime.CallResult",
                        "WorkflowRuntime.Called",
                        "WorkflowRuntime.Aborted"
                );
    }

    @Test
    void productionApplicationCompilesInputsInsteadOfPackagingTheAuthoringModel() {
        assertThat(compiled().productionApplicationSource()).doesNotContain(
                "StepDefinition",
                "WorkflowRuntime.Binding",
                "WorkflowRuntime.CallPlan"
        );
    }

    @Test
    void developmentApplicationAddsOnePassWholeFlowTracing() {
        assertThat(compiled().developmentApplicationSource())
                .contains("public final class RailixApplication implements DevelopmentRuntime.Application")
                .contains("public RunResult run(")
                .contains("public RunResult trace(")
                .contains("DevelopmentRuntime.Trace.start(")
                .contains("execution.call(")
                .doesNotContain(
                        "DevelopmentRuntime.Observation",
                        "WorkflowRuntime.Capture",
                        "execution.observe(",
                        "preview"
                );
    }

    @Test
    void developmentDispatchHasNoPerStepObservationBranch() {
        assertThat(compiled().developmentApplicationSource())
                .contains(
                        "DevelopmentRuntime.Trace.before(",
                        "DevelopmentRuntime.Trace.after(",
                        "execution.call("
                )
                .doesNotContain(
                        "if (observe)",
                        "selected",
                        "ObservationCapture"
                );
    }

    @Test
    void developmentSelectsNormalOrTraceHandlersOnceAtTheFlowBoundary() {
        final String source = compiled().developmentApplicationSource();

        assertThat(source)
                .contains(
                        "private static final WorkflowRuntime.StepCall[] CALLS = calls();",
                        "private static final WorkflowRuntime.StepCall[] TRACE_CALLS = traceCalls();",
                        "(execution, 2, CALLS, false);",
                        "(execution, 2, TRACE_CALLS));"
                )
                .containsPattern("calls\\[\\d+] = HANDLER_\\d+::run;")
                .containsPattern("calls\\[\\d+] = RailixApplication::traceHandler_\\d+;");
    }

    @Test
    void normalDevelopmentExecutorDoesNotEnterTheTraceRuntime() {
        final String source = compiled().developmentApplicationSource();
        final String executor = source.substring(
                source.indexOf("private static RunResult execute_1"),
                source.indexOf("private static RunResult traceExecute_1")
        );

        assertThat(executor)
                .contains("dispatch_1(execution, current, calls, measure)")
                .doesNotContain("DevelopmentRuntime.Trace", "TRACE_CALLS");
    }

    @Test
    void nestedStepsShareOneInputPlanAndTraceOnlyThroughTheExecutionBoundary() {
        final CompileResult.Compiled compiled = compiled();
        final String development = compiled.developmentApplicationSource();
        final String production = compiled.productionApplicationSource();
        final String developmentPlans = development.substring(
                development.indexOf("private static final class Plans_0"),
                development.indexOf("private RailixApplication()")
        );
        final String productionPlans = production.substring(
                production.indexOf("private static final class Plans_0"),
                production.indexOf("private RailixApplication()")
        );

        assertThat(development)
                .contains(
                        "private static final class TraceExecution extends WorkflowRuntime.Execution",
                        "new TraceExecution(",
                        "DevelopmentRuntime.Trace.invoke("
                );
        assertThat(developmentPlans)
                .contains("WorkflowRuntime.InputResolver INPUTS_2")
                .containsPattern("new WorkflowRuntime\\.NestedStep\\([^;]+HANDLER_\\d+\\)")
                .doesNotContain(
                        "TRACE_INPUTS_",
                        "TRACE_DATA_",
                        "traceResolve_",
                        "DevelopmentRuntime.Trace.invoke(",
                        "CALLS["
                );
        assertThat(developmentPlans).isEqualTo(productionPlans);
    }

    @Test
    void developmentLauncherStartsTheDevelopmentRuntimeDirectly() {
        assertThat(compiled().developmentLauncherSource())
                .contains("final int status = DevelopmentRuntime.run(RailixApplication.runtime());")
                .doesNotContain("Boolean.getBoolean", "System.getProperty", "runCli");
    }

    @Test
    void productionAndDevelopmentVariantsKeepTheSameApplicationClass() {
        final CompileResult.Compiled compiled = compiled();

        assertThat(compiled.applicationClass())
                .isEqualTo("dev.nanonative.railix.core.project.RailixApplication");
        assertThat(compiled.productionApplicationSource())
                .contains("public final class RailixApplication");
        assertThat(compiled.developmentApplicationSource())
                .contains("public final class RailixApplication");
    }

    private static CompileResult.Compiled compiled() {
        return (CompileResult.Compiled) ProjectCompiler.compileApplication(
                CreatorFirstProjectCompilationE2eTest.contextProject(),
                CreatorFirstProjectCompilationE2eTest.contextCatalog()
        );
    }
}
