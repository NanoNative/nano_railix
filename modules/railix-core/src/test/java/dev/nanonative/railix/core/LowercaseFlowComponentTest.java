package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LowercaseFlowComponentTest {
    @Test
    void namedStepRunsInsideCompiledFlowAndProducesExactOutput() {
        final CompileResult compileResult = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(LowercaseStep.definition())
        );
        assertThat(compileResult).isInstanceOf(CompileResult.Compiled.class);
        final CompileResult.Compiled compiled = (CompileResult.Compiled) compileResult;

        final RunResult runResult = compiled.flow().run(
                RailixValue.object(Map.of("text", RailixValue.string("HeLLo")))
        );

        assertThat(runResult).isInstanceOf(RunResult.Succeeded.class);
        final RunResult.Succeeded succeeded = (RunResult.Succeeded) runResult;
        assertThat(succeeded.outputs()).isEqualTo(
                RailixValue.object(Map.of("text", RailixValue.string("hello")))
        );
    }
}
