package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepContractRuntimeComponentTest {
    @Test
    void unexpectedStepExceptionBecomesStableImplementationFault() {
        final StepDefinition step = baseStep(input -> {
            throw new IllegalStateException("must not leak");
        });

        final RunResult result = run(step);

        assertThat(result).isInstanceOf(RunResult.Failed.class);
        final RunResult.Failed failed = (RunResult.Failed) result;
        assertThat(failed.failure().code()).isEqualTo("STEP_IMPLEMENTATION_FAULT");
        assertThat(failed.failure().message()).doesNotContain("must not leak", "IllegalStateException");
    }

    @Test
    void undeclaredStepOutcomeBecomesStableContractFailure() {
        final StepDefinition step = baseStep(input ->
                StepResult.outcome("surprise").output("text", RailixValue.string("value")));

        final RunResult result = run(step);

        assertThat(result).isInstanceOf(RunResult.Failed.class);
        assertThat(((RunResult.Failed) result).failure().code()).isEqualTo("STEP_OUTCOME_UNDECLARED");
    }

    @Test
    void missingStepOutcomeBecomesStableContractFailureInsteadOfNullPointer() {
        final StepDefinition step = baseStep(input ->
                StepResult.outcome(null).output("text", RailixValue.string("value")));

        final RunResult result = run(step);

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_OUTCOME_MISSING"));
    }

    @Test
    void wrongStepOutputShapeBecomesStableContractFailure() {
        final StepDefinition step = baseStep(input ->
                StepResult.outcome("ok").output("text", RailixValue.number(7)));

        final RunResult result = run(step);

        assertThat(result).isInstanceOf(RunResult.Failed.class);
        assertThat(((RunResult.Failed) result).failure().code()).isEqualTo("STEP_OUTPUT_TYPE_MISMATCH");
    }

    @Test
    void missingStepResultBecomesStableContractFailure() {
        final StepDefinition step = baseStep(input -> null);

        final RunResult result = run(step);

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_RESULT_MISSING"));
    }

    @Test
    void missingDeclaredStepOutputBecomesStableContractFailure() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok"));

        final RunResult result = run(step);

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_OUTPUT_REQUIRED"));
    }

    @Test
    void undeclaredStepOutputBecomesStableContractFailure() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", RailixValue.string("value"))
                .output("extra", RailixValue.string("unused")));

        final RunResult result = run(step);

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_OUTPUT_UNDECLARED"));
    }

    @Test
    void undeclaredStepOutputFailureUsesCanonicalOutputOrder() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", RailixValue.string("value"))
                .output("zzzzzz", RailixValue.string("unused"))
                .output("aaaaaa", RailixValue.string("unused")));

        assertThat(run(step)).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().message())
                        .isEqualTo("Step produced undeclared output: aaaaaa"));
    }

    @Test
    void lockEquivalentDeclaredOutputOrderKeepsFirstMissingOutputDiagnostic() {
        final String source = """
                {
                  "id":"ordered-output",
                  "triggers":[],
                  "entry":"ordered",
                  "inputs":{"value":"string"},
                  "outputs":{"result":"string"},
                  "steps":[{"id":"ordered","use":"ordered","config":{},"on":{"ok":"end"}}],
                  "connections":[{"from":"input.value","to":"output.result"}]
                }
                """;
        final CompileResult.Compiled first = compiled(source, orderedOutputs(false));
        final CompileResult.Compiled reordered = compiled(source, orderedOutputs(true));
        final RailixValue.ObjectValue input = RailixValue.object(Map.of("value", RailixValue.string("text")));
        final RunResult expected = new RunResult.Failed(
                new RunFailure("STEP_OUTPUT_REQUIRED", "Step did not produce required output: a", "ordered"),
                List.of()
        );

        assertThat(new OutputOrderObservation(
                first.lock().equals(reordered.lock()),
                first.flow().run(input),
                reordered.flow().run(input)
        )).isEqualTo(new OutputOrderObservation(true, expected, expected));
    }

    @Test
    void readingAnUndeclaredStepInputBecomesAnImplementationFault() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", input.value("typo")));

        final RunResult result = run(step);

        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_IMPLEMENTATION_FAULT"));
    }

    @Test
    void readingAStepInputWithANullNameBecomesAnImplementationFault() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", input.value(null)));

        assertImplementationFault(run(step));
    }

    @Test
    void readingANumberThroughTheStringAccessorBecomesAnImplementationFault() {
        final StepDefinition step = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", ValueShape.NUMBER)
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok")
                        .output("text", RailixValue.string(input.string("text"))));
        final String flow = FlowFixtures.lowercaseFlow().replace(
                "\"inputs\": { \"text\": \"string\" }",
                "\"inputs\": { \"text\": \"number\" }"
        );

        assertImplementationFault(run(
                flow,
                step,
                RailixValue.object(Map.of("text", RailixValue.number(1)))
        ));
    }

    @Test
    void anyShapePassesNestedJsonWithoutMapping() {
        assertRoundTrip(
                ValueShape.ANY,
                RailixValue.object(Map.of("items", RailixValue.array(List.of(RailixValue.number(1)))))
        );
    }

    @Test
    void booleanShapePassesABooleanThroughAFlow() {
        assertRoundTrip(ValueShape.BOOLEAN, RailixValue.bool(true));
    }

    @Test
    void arrayShapePassesAnArrayThroughAFlow() {
        assertRoundTrip(ValueShape.ARRAY, RailixValue.array(List.of(RailixValue.string("value"))));
    }

    @Test
    void objectShapePassesAnObjectThroughAFlow() {
        assertRoundTrip(ValueShape.OBJECT, RailixValue.object(Map.of("value", RailixValue.string("text"))));
    }

    @Test
    void readingConfigurationWithANullNameBecomesAnImplementationFault() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", input.configValue(null)));

        assertImplementationFault(run(step));
    }

    @Test
    void readingUndeclaredConfigurationBecomesAnImplementationFault() {
        final StepDefinition step = baseStep(input -> StepResult.outcome("ok")
                .output("text", input.configValue("typo")));

        assertImplementationFault(run(step));
    }

    @Test
    void readingConfigurationThroughTheWrongShapeAccessorBecomesAnImplementationFault() {
        final StepDefinition step = StepDefinition.named("text.lowercase", "1.0.0")
                .config("count", ValueShape.number(), RailixValue.number(1))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok")
                        .output("text", RailixValue.string(input.configString("count"))));

        assertImplementationFault(run(step));
    }

    private static void assertImplementationFault(final RunResult result) {
        assertThat(result).isInstanceOfSatisfying(RunResult.Failed.class, failed ->
                assertThat(failed.failure().code()).isEqualTo("STEP_IMPLEMENTATION_FAULT"));
    }

    private static void assertRoundTrip(final ValueShape shape, final RailixValue value) {
        final String shapeName = shape.name().toLowerCase(Locale.ROOT);
        final String flow = FlowFixtures.lowercaseFlow().replace("\"string\"", "\"" + shapeName + "\"");
        final StepDefinition step = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", shape)
                .output("text", shape)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", input.value("text")));

        assertThat(run(flow, step, RailixValue.object(Map.of("text", value)))).isEqualTo(
                new RunResult.Succeeded(
                        RailixValue.object(Map.of("text", value)),
                        List.of(new RunResult.StepExecution("lowercase", "ok"))
                )
        );
    }

    private static StepDefinition baseStep(final dev.nanonative.railix.core.step.StepHandler handler) {
        return StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(handler);
    }

    private static RunResult run(final StepDefinition step) {
        return run(
                FlowFixtures.lowercaseFlow(),
                step,
                RailixValue.object(Map.of("text", RailixValue.string("Hello")))
        );
    }

    private static RunResult run(
            final String source,
            final StepDefinition step,
            final RailixValue.ObjectValue input
    ) {
        final CompileResult compileResult = FlowCompiler.compile(
                source,
                StepCatalog.of(step)
        );
        assertThat(compileResult).isInstanceOf(CompileResult.Compiled.class);
        final CompiledFlow flow = ((CompileResult.Compiled) compileResult).flow();
        return flow.run(input);
    }

    private static CompileResult.Compiled compiled(final String source, final StepDefinition definition) {
        final CompileResult result = FlowCompiler.compile(source, StepCatalog.of(definition));
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static StepDefinition orderedOutputs(final boolean reverse) {
        final StepDefinition.Builder builder = StepDefinition.named("ordered", "1.0.0");
        if (reverse) {
            builder.output("z", ValueShape.STRING).output("a", ValueShape.STRING);
        } else {
            builder.output("a", ValueShape.STRING).output("z", ValueShape.STRING);
        }
        return builder.outcome("ok").run(input -> StepResult.outcome("ok"));
    }

    private record OutputOrderObservation(boolean locksEqual, RunResult first, RunResult reordered) {
    }
}
