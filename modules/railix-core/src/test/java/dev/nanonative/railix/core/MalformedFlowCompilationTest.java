package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MalformedFlowCompilationTest {
    @Test
    void malformedJsonReturnsSourceDiagnosticInsteadOfThrowing() {
        final CompileResult result = FlowCompiler.compile(
                "{\"id\":",
                StepCatalog.of(LowercaseStep.definition())
        );
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final CompileResult.Rejected rejected = (CompileResult.Rejected) result;

        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("FLOW_JSON_INVALID");
            assertThat(diagnostic.line()).isEqualTo(1);
            assertThat(diagnostic.column()).isGreaterThan(1);
        });
    }
}
