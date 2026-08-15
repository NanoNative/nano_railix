package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

final class IndexedExecutionPlanE2eTest {
    @Test
    void generatedExecutionStateDoesNotUseANullSentinel() {
        assertThat(compiled(linearProject(1)).productionApplicationSource())
                .doesNotContain("ExecutionResult", "execution() == null");
    }

    @Test
    @Timeout(20)
    void compilationGrowthRemainsNearLinearForLargeFlatProjects() {
        compiled(linearProject(512));

        final long small = compileNanos(2_000);
        final long large = compileNanos(8_000);

        assertThat(Duration.ofNanos(large))
                .isLessThan(Duration.ofNanos(small * 8 + Duration.ofMillis(500).toNanos()));
    }

    private static long compileNanos(final int steps) {
        final String project = linearProject(steps);
        final long started = System.nanoTime();
        compiled(project);
        return System.nanoTime() - started;
    }

    private static CompileResult.Compiled compiled(final String project) {
        return (CompileResult.Compiled) ProjectCompiler.compileApplication(
                project,
                CreatorFirstProjectCompilationE2eTest.contextCatalog()
        );
    }

    private static String linearProject(final int steps) {
        final StringBuilder project = new StringBuilder("""
                {"format":1,"id":"indexed-plan","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]}
                """);
        for (int index = 0; index < steps; index++) {
            project.append(",{\"id\":\"step-").append(index)
                    .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
        }
        project.append("],\"links\":[{\"from\":\"app.start\",\"to\":\"command\"},")
                .append("{\"from\":\"command.next\",\"to\":\"")
                .append(steps == 0 ? "end" : "step-0").append("\"}");
        for (int index = 0; index < steps; index++) {
            project.append(",{\"from\":\"step-").append(index).append(".next\",\"to\":\"")
                    .append(index + 1 == steps ? "end" : "step-" + (index + 1)).append("\"}");
        }
        return project.append("]}").toString();
    }
}
