package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnhandledOutcomeCompilationTest {
    @Test
    void declaredOutcomeWithoutTransitionBlocksCompilation() {
        final var step = StepCatalog.of(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .config("languageTag", ValueShape.string(), RailixValue.string("und"))
                        .input("text", ValueShape.string())
                        .output("text", ValueShape.string())
                        .outcome("ok")
                        .outcome("rejected")
                        .run(input -> StepResult.outcome("ok").output("text", input.value("text")))
        );

        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                step
        );
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final CompileResult.Rejected rejected = (CompileResult.Rejected) result;

        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("FLOW_OUTCOME_UNHANDLED");
            assertThat(diagnostic.path()).isEqualTo("steps.lowercase.on.rejected");
        });
    }
}
