package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeIsolationComponentTest {
    @Test
    void compiledFlowCanBeReusedWithoutRetainingRunState() {
        final CompiledFlow flow = compiledFlow();

        final RunResult first = run(flow, "FIRST");
        final RunResult second = run(flow, "SECOND");

        assertOutput(first, "first");
        assertOutput(second, "second");
    }

    @Test
    void concurrentRunsDoNotShareStepOutputs() throws Exception {
        final CompiledFlow flow = compiledFlow();
        final List<Callable<RunResult>> tasks = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            final int value = index;
            tasks.add(() -> run(flow, "VALUE-" + value));
        }

        final List<RunResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (final Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        for (int index = 0; index < results.size(); index++) {
            assertOutput(results.get(index), "value-" + index);
        }
    }

    private static CompiledFlow compiledFlow() {
        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(LowercaseStep.definition())
        );
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).flow();
    }

    private static RunResult run(final CompiledFlow flow, final String value) {
        return flow.run(RailixValue.object(Map.of("text", RailixValue.string(value))));
    }

    private static void assertOutput(final RunResult result, final String expected) {
        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        assertThat(((RunResult.Succeeded) result).outputs()).isEqualTo(
                RailixValue.object(Map.of("text", RailixValue.string(expected)))
        );
    }
}
