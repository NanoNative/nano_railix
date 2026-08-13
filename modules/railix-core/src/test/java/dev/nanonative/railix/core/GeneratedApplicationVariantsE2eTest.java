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
    void developmentApplicationAddsRunAndSinglePassObservation() {
        assertThat(compiled().developmentApplicationSource())
                .contains("public final class RailixApplication implements DevelopmentRuntime.Application")
                .contains("public RunResult run(")
                .contains("public DevelopmentRuntime.Observation observe(")
                .contains("implements WorkflowRuntime.Capture")
                .contains("execution.observe(")
                .contains("execution.call(");
    }

    @Test
    void developmentDispatchAvoidsConditionalMethodReferenceCompilerCrash() {
        assertThat(compiled().developmentApplicationSource())
                .contains(
                        "if (observe) {",
                        "yield execution.observe(",
                        "yield execution.call("
                )
                .doesNotContain(
                        "observe ? execution.observe(",
                        ": execution.call("
                );
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
