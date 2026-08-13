package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class GeneratedApplicationParityE2eTest {
    private GeneratedApplicationFixture development;
    private CreatorFirstExecutionArtifacts.ProductionProbe production;

    @BeforeAll
    void buildBothApplicationVariants(@TempDir final Path workspace) throws Exception {
        final String project = CreatorProjects.lowercaseCli();
        development = GeneratedApplicationFixture.start(workspace.resolve("development"), project);
        final Path productionJar = CreatorFirstExecutionArtifacts.production(
                workspace.resolve("production"), project
        );
        production = CreatorFirstExecutionArtifacts.compileProductionProbe(
                workspace.resolve("production-probe"), productionJar
        );
    }

    @AfterAll
    void stopDevelopmentApplication() {
        if (development != null) {
            development.close();
        }
    }

    @Test
    void successfulProductionAndDevelopmentExecutionsReturnTheSameContext() throws Exception {
        final CreatorFirstExecutionArtifacts.ProcessResult productionResult = production.run("parity-success");
        final RailixValue.ObjectValue developmentBody = body(development.run(
                "command", "{\"payload\":{\"arguments\":[\"Hello RAILIX\"]}}"
        ));

        assertThat(productionResult.exitCode()).isZero();
        assertThat(productionResult.output()).isEqualTo(
                "succeeded|" + RailixJson.write(developmentBody.values().get("context"))
        );
        assertThat(((RailixValue.ArrayValue) developmentBody.values().get("steps")).values()).isEmpty();
    }

    @Test
    void incompatibleProductionAndDevelopmentInputsReturnTheSameRejection() throws Exception {
        final CreatorFirstExecutionArtifacts.ProcessResult productionResult = production.run("parity-incompatible");
        final RailixValue.ObjectValue developmentBody = body(development.run(
                "command", "{\"payload\":{\"arguments\":[1]}}"
        ));

        assertThat(productionResult).isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(
                0,
                rejection(developmentBody)
        ));
        assertThat(((RailixValue.ArrayValue) developmentBody.values().get("steps")).values()).isEmpty();
    }

    @Test
    void missingProductionAndDevelopmentInputsReturnTheSameRejection() throws Exception {
        final CreatorFirstExecutionArtifacts.ProcessResult productionResult = production.run("parity-missing");
        final RailixValue.ObjectValue developmentBody = body(development.run(
                "command", "{\"payload\":{\"arguments\":[]}}"
        ));

        assertThat(productionResult).isEqualTo(new CreatorFirstExecutionArtifacts.ProcessResult(
                0,
                rejection(developmentBody)
        ));
        assertThat(((RailixValue.ArrayValue) developmentBody.values().get("steps")).values()).isEmpty();
    }

    private static String rejection(final RailixValue.ObjectValue body) {
        final RailixValue.ObjectValue diagnostic = (RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                body.values().get("diagnostics")).values().getFirst();
        return "rejected|" + text(diagnostic, "code") + "|" + text(diagnostic, "path");
    }

    private static RailixValue.ObjectValue body(final DevelopmentApplication.Response response) {
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static String text(final RailixValue.ObjectValue value, final String field) {
        return ((RailixValue.StringValue) value.values().get(field)).value();
    }
}
