package example.railix.welcome;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.creator.RailixRunner;
import dev.nanonative.railix.stdlib.text.LowercaseStep;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GreetingStepFlowIT {
    @Test
    void customStepRunsInsideTheCommittedFlow() throws Exception {
        final String expected = Files.readString(Path.of("expected-output.json")).strip();
        final RailixRunner.Outcome outcome = RailixRunner.run(
                Files.readString(Path.of("src/main/resources/railix.flow.json")),
                Files.readString(Path.of("input.json")),
                StepCatalog.of(LowercaseStep.definition(), GreetingStep.definition())
        );

        assertThat(outcome).isInstanceOfSatisfying(
                RailixRunner.Completed.class,
                completed -> assertThat(RailixJson.write(completed.outputs()))
                        .isEqualTo(expected)
        );
    }
}
