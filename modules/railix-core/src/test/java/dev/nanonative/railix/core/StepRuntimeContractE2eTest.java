package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static dev.nanonative.railix.core.step.StepDefinition.Input;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

final class StepRuntimeContractE2eTest {
    @Test
    void graphStepImplementationExceptionBecomesAnExplicitFailure() {
        assertGraphFailure(input -> {
            if (input.string("value").equals("fault")) {
                throw new IllegalStateException("private detail");
            }
            return StepResult.outcome(input.primaryOutcome());
        }, "STEP_IMPLEMENTATION_FAULT");
    }

    @Test
    void graphStepJavaNullResultBecomesAnExplicitFailure() {
        assertGraphFailure(input -> input.string("value").equals("fault")
                ? null
                : StepResult.outcome(input.primaryOutcome()), "STEP_RESULT_REQUIRED");
    }

    @Test
    void graphStepUndeclaredOutcomeBecomesAnExplicitFailure() {
        assertGraphFailure(input -> StepResult.outcome(input.string("value").equals("fault")
                ? "undeclared"
                : input.primaryOutcome()), "STEP_OUTCOME_INVALID");
    }

    @Test
    void graphStepNestedOutputBecomesAnExplicitFailure() {
        assertGraphFailure(input -> input.string("value").equals("fault")
                ? StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string("unexpected"))
                : StepResult.outcome(input.primaryOutcome()), "STEP_OUTPUT_INVALID");
    }

    @Test
    void graphStepUndeclaredWriteBecomesAnExplicitFailure() {
        assertGraphFailure(input -> input.string("value").equals("fault")
                ? StepResult.outcome(input.primaryOutcome()).write("unknown", RailixValue.string("unexpected"))
                : StepResult.outcome(input.primaryOutcome()), "STEP_WRITE_UNDECLARED");
    }

    @Test
    void graphStepWriteThroughReadOnlyInputBecomesAnExplicitFailure() {
        assertGraphFailure(input -> input.string("value").equals("fault")
                ? StepResult.outcome(input.primaryOutcome()).write("value", RailixValue.string("unexpected"))
                : StepResult.outcome(input.primaryOutcome()), "STEP_WRITE_UNDECLARED");
    }

    @Test
    void graphStepInterruptionCancelsTheStream() {
        final CompiledProject project = compiled(graphProject(), graphCatalog(input -> {
            if (input.string("value").equals("fault")) {
                throw new InterruptedException();
            }
            return StepResult.outcome(input.primaryOutcome());
        }));

        try {
            assertThat(run(project)).isInstanceOf(RunResult.Cancelled.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void incompatibleFinalTriggerResultBecomesAnExplicitRejection() {
        final StepDefinition probe = StepDefinition.named("test.graph", "1")
                .input("value", Input.path(READ).defaultPath("context", "payload", "value"))
                .input("result", Input.path(WRITE).defaultPath("context", "result"))
                .run(input -> StepResult.outcome(input.primaryOutcome())
                        .write("result", input.string("value").equals("fault")
                                ? RailixValue.string("wrong")
                                : RailixValue.number(1)));

        final RunResult result = compiled(graphProject(), catalog(numberTrigger(), probe)).run(
                "command",
                item("fault")
        );

        assertRejected(result, "RUN_RESULT_INCOMPATIBLE");
    }

    @Test
    void sourceTriggerImplementationExceptionBecomesAnExplicitFailure() {
        final StepDefinition trigger = sourceTrigger(input -> {
            throw new IllegalStateException("private detail");
        });
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", RailixValue.string("fault"))
        );

        assertFailed(source.result(), "STEP_IMPLEMENTATION_FAULT");
        assertThat(source.responses()).isEmpty();
    }

    @Test
    void downstreamFailureIsReturnedThroughTheSourceBoundary() {
        final StepDefinition trigger = sourceTrigger(input -> StepResult.outcome(input.primaryOutcome())
                .write("target", input.value("value")));
        final StepDefinition probe = graphStep(input -> {
            if (input.string("value").equals("fault")) {
                throw new IllegalStateException("private detail");
            }
            return StepResult.outcome(input.primaryOutcome());
        });
        final CompiledProject.SourceResult source = compiled(sourceProject(true), catalog(trigger, probe)).runSource(
                "test.source",
                Map.of("value", RailixValue.string("fault"))
        );

        assertFailed(source.result(), "STEP_IMPLEMENTATION_FAULT");
        assertThat(source.responses()).isEmpty();
    }

    @Test
    void refinedSourceRejectsANoncanonicalDescendantBeforeCallingTheHandler() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.number(BigDecimal.TEN.pow(1024))));
        final StepDefinition trigger = refinedSourceTrigger(
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(64),
                true
        );
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", invalid)
        );

        assertThat(source.result()).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value value is incompatible: "
                                + "Value contains a number outside the canonical 1024-character domain.",
                        "nodes[1].value"
                )),
                List.of()
        ));
    }

    @Test
    void unrefinedSourcePassesANoncanonicalDescendantToTheHandler() {
        final RailixValue invalid = RailixValue.array(List.of(
                RailixValue.number(BigDecimal.TEN.pow(1_024)),
                RailixValue.string("\uD800")
        ));
        final StepDefinition trigger = StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .receive("value", ValueShape.ANY)
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(input -> input.value("value").equals(invalid)
                        ? StepResult.outcome(input.primaryOutcome())
                        : StepResult.outcome("unexpected"));
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", invalid)
        );

        assertThat(source.result()).isInstanceOf(RunResult.Succeeded.class);
    }

    @Test
    void refinedSourceAcceptsItsExactCanonicalJsonByteLimit() {
        final StepDefinition trigger = refinedSourceTrigger(
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(4),
                false
        );
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", RailixValue.string("é"))
        );

        assertThat(source.result()).isInstanceOf(RunResult.Succeeded.class);
    }

    @Test
    void refinedSourceRejectsTheNextCanonicalJsonByte() {
        final StepDefinition trigger = refinedSourceTrigger(
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(4),
                false
        );
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", RailixValue.string("éé"))
        );

        assertThat(source.result()).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value value is incompatible: Canonical JSON exceeds 4 bytes.",
                        "nodes[1].value"
                )),
                List.of()
        ));
    }

    @Test
    void refinedSourceAcceptsItsExactContainerDepth() {
        final StepDefinition trigger = refinedSourceTrigger(
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(1),
                false
        );
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", RailixValue.array(List.of(RailixValue.nullValue())))
        );

        assertThat(source.result()).isInstanceOf(RunResult.Succeeded.class);
    }

    @Test
    void refinedSourceRejectsTheNextContainerDepth() {
        final StepDefinition trigger = refinedSourceTrigger(
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(1),
                false
        );
        final CompiledProject.SourceResult source = compiled(sourceProject(false), catalog(trigger)).runSource(
                "test.source",
                Map.of("value", RailixValue.array(List.of(RailixValue.array(List.of()))))
        );

        assertThat(source.result()).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_SOURCE_VALUE_INCOMPATIBLE",
                        "Trigger source value value is incompatible: Value exceeds maximum container depth 1.",
                        "nodes[1].value"
                )),
                List.of()
        ));
    }

    private static void assertGraphFailure(final StepHandler handler, final String code) {
        assertFailed(run(compiled(graphProject(), graphCatalog(handler))), code);
    }

    private static StepCatalog graphCatalog(final StepHandler handler) {
        return catalog(defaultTrigger(), graphStep(handler));
    }

    private static StepDefinition graphStep(final StepHandler handler) {
        return StepDefinition.named("test.graph", "1")
                .input("value", Input.path(READ).defaultPath("context", "payload", "value"))
                .run(handler);
    }

    private static StepDefinition defaultTrigger() {
        return StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(input -> StepResult.outcome(input.primaryOutcome()));
    }

    private static StepDefinition numberTrigger() {
        return StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .result("result", ValueShape.NUMBER, RailixValue.number(0))
                .run(input -> StepResult.outcome(input.primaryOutcome()));
    }

    private static StepDefinition sourceTrigger(final StepHandler handler) {
        return StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .receive("value", ValueShape.STRING)
                .input("target", Input.path(WRITE).defaultPath("context", "payload", "value"))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(handler);
    }

    private static StepDefinition refinedSourceTrigger(
            final ValueShape shape,
            final ValueRefinement refinement,
            final boolean failWhenCalled
    ) {
        return StepDefinition.named("test.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .receive("value", shape, refinement)
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(input -> {
                    if (failWhenCalled && input.optionalValue("value").isPresent()) {
                        throw new IllegalStateException("Refined source value reached the handler.");
                    }
                    return StepResult.outcome(input.primaryOutcome());
                });
    }

    private static StepCatalog catalog(final StepDefinition... definitions) {
        final StepDefinition app = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .define();
        final StepDefinition[] all = new StepDefinition[definitions.length + 1];
        all[0] = app;
        System.arraycopy(definitions, 0, all, 1, definitions.length);
        return StepCatalog.of(all);
    }

    private static CompiledProject compiled(final String source, final StepCatalog catalog) {
        final CompileResult result = ProjectCompiler.compile(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).project();
    }

    private static RunResult run(final CompiledProject project) {
        return project.run("command", item("fault"));
    }

    private static CompiledProject.StreamItem item(final String value) {
        return new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("value", RailixValue.string(value)))
        )));
    }

    private static void assertRejected(final RunResult result, final String code) {
        assertThat(result).isInstanceOf(RunResult.Rejected.class);
        assertThat(((RunResult.Rejected) result).diagnostics().getFirst().code()).isEqualTo(code);
    }

    private static void assertFailed(final RunResult result, final String code) {
        assertThat(result).isInstanceOf(RunResult.Failed.class);
        assertThat(((RunResult.Failed) result).failure().code()).isEqualTo(code);
    }

    private static String graphProject() {
        return """
                {"format":1,"id":"graph-contract","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger","inputs":{},"examples":[
                    {"name":"safe","payload":{},"context":{"payload":{"value":"safe"}}}
                  ]},
                  {"id":"probe","use":"test.graph","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"probe"},
                  {"from":"probe.next","to":"end"}
                ]}
                """;
    }

    private static String sourceProject(final boolean downstream) {
        final String node = downstream
                ? ",{\"id\":\"probe\",\"use\":\"test.graph\",\"inputs\":{}}"
                : "";
        final String destination = downstream ? "probe" : "end";
        final String link = downstream ? ",{\"from\":\"probe.next\",\"to\":\"end\"}" : "";
        return """
                {"format":1,"id":"source-contract","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger","inputs":{},"examples":[
                    {"name":"safe","payload":{},"context":{"payload":{"value":"safe"}}}
                  ]}%s
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"%s"}%s
                ]}
                """.formatted(node, destination, link);
    }
}
