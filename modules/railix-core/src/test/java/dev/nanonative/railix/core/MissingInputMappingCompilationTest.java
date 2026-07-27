package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissingInputMappingCompilationTest {
    @Test
    void requiredStepInputWithoutConnectionBlocksCompilation() {
        final String source = FlowFixtures.lowercaseFlow()
                .replace("{ \"from\": \"input.text\", \"to\": \"lowercase.text\" },", "");

        final CompileResult result = FlowCompiler.compile(
                source,
                StepCatalog.of(LowercaseStep.definition())
        );
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final CompileResult.Rejected rejected = (CompileResult.Rejected) result;

        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("FLOW_REQUIRED_INPUT_UNMAPPED");
            assertThat(diagnostic.path()).isEqualTo("steps.lowercase.inputs.text");
        });
    }
}
