package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlowInputAdmissionComponentTest {
    @Test
    void missingRequiredFlowInputIsRejectedBeforeStepExecution() {
        final RunResult result = compiledFlow().run(RailixValue.object(Map.of()));

        assertThat(result).isInstanceOf(RunResult.Rejected.class);
        final RunResult.Rejected rejected = (RunResult.Rejected) result;
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("FLOW_INPUT_REQUIRED");
            assertThat(diagnostic.path()).isEqualTo("inputs.text");
        });
    }

    @Test
    void explicitNullForRequiredStringIsRejectedBeforeStepExecution() {
        final RunResult result = compiledFlow().run(
                RailixValue.object(Map.of("text", RailixValue.nullValue()))
        );

        assertThat(result).isInstanceOf(RunResult.Rejected.class);
        final RunResult.Rejected rejected = (RunResult.Rejected) result;
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("FLOW_INPUT_TYPE_MISMATCH"));
    }

    @Test
    void wrongFlowInputShapeIsRejectedBeforeStepExecution() {
        final RunResult result = compiledFlow().run(
                RailixValue.object(Map.of("text", RailixValue.number(42)))
        );

        assertThat(result).isInstanceOf(RunResult.Rejected.class);
        final RunResult.Rejected rejected = (RunResult.Rejected) result;
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("FLOW_INPUT_TYPE_MISMATCH"));
    }

    @Test
    void missingFlowInputObjectIsRejectedBeforeStepExecution() {
        final RunResult result = compiledFlow().run(null);

        assertThat(result).isEqualTo(new RunResult.Rejected(java.util.List.of(
                dev.nanonative.railix.core.flow.Diagnostic.atPath(
                        "FLOW_INPUT_OBJECT_REQUIRED",
                        "Flow inputs must be an object.",
                        "inputs"
                )
        )));
    }

    @Test
    void unknownFlowInputIsRejectedBeforeStepExecution() {
        final RunResult result = compiledFlow().run(RailixValue.object(Map.of(
                "text", RailixValue.string("value"),
                "extra", RailixValue.string("unused")
        )));

        assertThat(result).isEqualTo(new RunResult.Rejected(java.util.List.of(
                dev.nanonative.railix.core.flow.Diagnostic.atPath(
                        "FLOW_INPUT_UNKNOWN",
                        "Unknown flow input: extra",
                        "inputs.extra"
                )
        )));
    }

    @Test
    void canonicalSourceKeepsFlowInputDiagnosticsOrdered() {
        final String source = """
                {
                  "id":"canonical-input-admission",
                  "triggers":[],
                  "entry":"tick",
                  "inputs":{"z":"string","a":"string"},
                  "outputs":{"result":"string"},
                  "steps":[{"id":"tick","use":"tick","config":{},"on":{"ok":"end"}}],
                  "connections":[{"from":"input.a","to":"output.result"}]
                }
                """;
        final CompileResult.Compiled first = compiled(source, tick());
        final CompileResult.Compiled reopened = compiled(first.source(), tick());

        assertThat(reopened.flow().run(RailixValue.object(Map.of())))
                .isEqualTo(first.flow().run(RailixValue.object(Map.of())));
    }

    @Test
    void unknownFlowInputDiagnosticsIgnoreCallerObjectOrder() {
        final Map<String, RailixValue> firstInputs = new LinkedHashMap<>();
        firstInputs.put("text", RailixValue.string("value"));
        firstInputs.put("z", RailixValue.string("unused"));
        firstInputs.put("a", RailixValue.string("unused"));
        final Map<String, RailixValue> reorderedInputs = new LinkedHashMap<>();
        reorderedInputs.put("text", RailixValue.string("value"));
        reorderedInputs.put("a", RailixValue.string("unused"));
        reorderedInputs.put("z", RailixValue.string("unused"));

        assertThat(compiledFlow().run(RailixValue.object(firstInputs)))
                .isEqualTo(compiledFlow().run(RailixValue.object(reorderedInputs)));
    }

    private static CompiledFlow compiledFlow() {
        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(LowercaseStep.definition())
        );
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).flow();
    }

    private static CompileResult.Compiled compiled(final String source, final StepDefinition definition) {
        final CompileResult result = FlowCompiler.compile(source, StepCatalog.of(definition));
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static StepDefinition tick() {
        return StepDefinition.named("tick", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }
}
