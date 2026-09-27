package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

final class GeneratedApplicationVariantsE2eTest {
    @ParameterizedTest
    @ValueSource(classes = {MissingProcess.class, InstanceProcess.class, WrongProcess.class,
            PrivateProcess.class, WrongReturn.class})
    void compilerRejectsInvalidProcessBeforeGeneratingJava(final Class<?> implementation) {
        final CompileResult result = compileWith(implementation);

        assertThat(result).isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                assertThat(rejected.diagnostics()).anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("PROJECT_STEP_PROCESS_INVALID");
                    assertThat(diagnostic.message()).contains("public static StepResult process(StepInput, StepInput)");
                    assertThat(diagnostic.path()).contains(".inputs.steps[0].use");
                }));
    }

    @Test
    void compilerAcceptsAnUnrelatedProcessOverload() {
        assertThat(compileWith(OverloadedProcess.class)).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void compilerRejectsInvalidOptionalLifecycleMethods() {
        assertThat(compileWith(InvalidPre.class)).isInstanceOfSatisfying(CompileResult.Rejected.class,
                rejected -> assertThat(rejected.diagnostics()).anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("PROJECT_STEP_LIFECYCLE_INVALID");
                    assertThat(diagnostic.message()).contains("public static StepResult pre(StepInput, StepInput)");
                }));
    }

    public static final class InvalidPre {
        public static StepResult process(final StepInput configuration, final StepInput context) {
            return StepResult.outcome("ok");
        }

        public static String pre(final StepInput configuration, final StepInput context) {
            return "ok";
        }
    }

    private static CompileResult compileWith(final Class<?> implementation) {
        final StepDefinition replacement = StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok").receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING).implementation(implementation);
        final StepCatalog catalog = StepCatalog.of(CreatorFirstProjectCompilationE2eTest.contextCatalog()
                .definitions().stream().map(step -> step.id().equals("text.lowercase") ? replacement : step)
                .toArray(StepDefinition[]::new));

        return ProjectCompiler.compileApplication(
                CreatorFirstProjectCompilationE2eTest.contextProject(), catalog);
    }

    public static final class MissingProcess {
    }

    public static final class InstanceProcess {
        public StepResult process(final StepInput configuration, final StepInput context) {
            return StepResult.outcome("ok");
        }
    }

    public static final class WrongProcess {
        public static StepResult process(final StepInput context) {
            return StepResult.outcome("ok");
        }
    }

    public static final class PrivateProcess {
        private static StepResult process(final StepInput configuration, final StepInput context) {
            return StepResult.outcome("ok");
        }
    }

    public static final class WrongReturn {
        public static String process(final StepInput configuration, final StepInput context) {
            return "ok";
        }
    }

    public static final class OverloadedProcess {
        public static StepResult process(final StepInput configuration, final StepInput context) {
            return process(context);
        }

        private static StepResult process(final StepInput context) {
            return StepResult.outcome("ok");
        }
    }

    @Test
    void generatedApplicationsCallStaticStepFunctionsWithoutHandlerInstances() {
        final CompileResult.Compiled application = compiled();

        for (final String source : java.util.List.of(
                application.productionApplicationSource(), application.developmentApplicationSource())) {
            assertThat(source).contains("::process")
                    .doesNotContain(" HANDLER_", "new dev.nanonative.railix.core.TestStepHandlers");
        }
    }

    @Test
    void generatedLaunchersDoNotImplementBuiltinTriggerBehavior() {
        final CompileResult.Compiled application = compiled();

        for (final String source : java.util.List.of(
                application.productionApplicationSource(), application.developmentApplicationSource())) {
            assertThat(source).doesNotContain("runCli(", "runHttp(", "cliSuccess(",
                    "--railix-http", "CLI exit code must", "HttpStepHandlers.Trigger.serve(");
        }
    }

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
                .doesNotContain("Metrics.invoke(", "execution.test()", "new boolean[]{",
                        "final boolean measure", "if (!measure)", "if (test)");
    }

    @Test
    void traceExecutionRecordsNormalFlowMetrics() {
        final String source = compiled().developmentApplicationSource();
        final String trace = source.substring(
                source.indexOf("static RunResult trace_1"),
                source.indexOf("static WorkflowRuntime.SourceResult source_1")
        );

        assertThat(trace).contains("METRICS.startFlow(0)", "METRICS.finishFlow(0, metric, result)");
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
                .containsPattern("WorkflowRuntime\\.StepCall CALL_0 = [\\w.]+::process;")
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
                        "static int dispatch("
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
                        "static int dispatch("
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
                        "static final WorkflowRuntime.StepCall[] CALLS = new WorkflowRuntime.StepCall[]{",
                        "static final WorkflowRuntime.StepCall[] TRACE_CALLS = new WorkflowRuntime.StepCall[]{",
                        "(execution, 2, CALLS);",
                        "(execution, 2, TRACE_CALLS));"
                )
                .containsPattern("Handlers_\\d+\\.CALL_\\d+")
                .containsPattern("Handlers_\\d+::trace_\\d+");
    }

    @Test
    void normalDevelopmentExecutorDoesNotEnterTheTraceRuntime() {
        final String source = compiled().developmentApplicationSource();
        final String executor = source.substring(
                source.indexOf("private static RunResult execute_1"),
                source.indexOf("private static RunResult traceExecute_1")
        );

        assertThat(executor)
                .contains("dispatch_1(execution, current, calls)")
                .doesNotContain("DevelopmentRuntime.Trace", "TRACE_CALLS");
    }

    @Test
    void nestedStepsShareOneInputPlanAndTraceOnlyThroughTheExecutionBoundary() {
        final CompileResult.Compiled compiled = compiled();
        final String development = compiled.developmentApplicationSource();
        final String production = compiled.productionApplicationSource();
        final String developmentPlans = development.substring(
                development.indexOf("final class Plans_0"),
                development.indexOf("final class Routes_")
        );
        final String productionPlans = production.substring(
                production.indexOf("final class Plans_0"),
                production.indexOf("final class Routes_")
        );

        assertThat(development)
                .contains(
                        "static final class TraceExecution extends WorkflowRuntime.Execution",
                        "new TraceExecution(",
                        "DevelopmentRuntime.Trace.invoke("
                );
        assertThat(developmentPlans)
                .contains("WorkflowRuntime.InputResolver INPUTS_2")
                .containsPattern("new WorkflowRuntime\\.NestedStep\\([^;]+CALL_\\d+\\)")
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
