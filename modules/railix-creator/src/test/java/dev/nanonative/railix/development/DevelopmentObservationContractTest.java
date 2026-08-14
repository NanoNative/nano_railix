package dev.nanonative.railix.development;

import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevelopmentObservationContractTest {
    private static final RailixValue.ObjectValue EMPTY_CONTEXT = RailixValue.object(Map.of());
    private static final RunResult RESULT = new RunResult.Succeeded(EMPTY_CONTEXT, List.of());
    private static final DevelopmentRuntime.Stage STAGE = new DevelopmentRuntime.Stage(
            "steps",
            "nodes[1].inputs.steps[0]",
            "text.lowercase",
            "succeeded",
            List.of(RailixValue.string("railix"))
    );

    @Test
    void observationSnapshotsInputs() {
        final Map<String, RailixValue> inputs = new LinkedHashMap<>();
        inputs.put("value", RailixValue.string("before"));
        final DevelopmentRuntime.Observation observation = observation(inputs, List.of(), Map.of());

        inputs.put("value", RailixValue.string("after"));

        assertThat(observation.inputs()).containsExactly(
                Map.entry("value", RailixValue.string("before"))
        );
    }

    @Test
    void observationInputsAreReadOnly() {
        final DevelopmentRuntime.Observation observation = observation(
                Map.of("value", RailixValue.string("before")),
                List.of(),
                Map.of()
        );

        assertThatThrownBy(() -> observation.inputs().put("other", RailixValue.string("value")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void observationSnapshotsStages() {
        final List<DevelopmentRuntime.Stage> stages = new ArrayList<>(List.of(STAGE));
        final DevelopmentRuntime.Observation observation = observation(Map.of(), stages, Map.of());

        stages.clear();

        assertThat(observation.stages()).containsExactly(STAGE);
    }

    @Test
    void observationStagesAreReadOnly() {
        final DevelopmentRuntime.Observation observation = observation(Map.of(), List.of(STAGE), Map.of());

        assertThatThrownBy(() -> observation.stages().add(STAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void observationSnapshotsSelectedCandidates() {
        final Map<String, Integer> candidates = new LinkedHashMap<>();
        candidates.put("nodes[1].inputs.value", 1);
        final DevelopmentRuntime.Observation observation = observation(Map.of(), List.of(), candidates);

        candidates.put("nodes[1].inputs.value", 2);

        assertThat(observation.selectedCandidates()).containsExactly(
                Map.entry("nodes[1].inputs.value", 1)
        );
    }

    @Test
    void observationSelectedCandidatesAreReadOnly() {
        final DevelopmentRuntime.Observation observation = observation(
                Map.of(),
                List.of(),
                Map.of("nodes[1].inputs.value", 1)
        );

        assertThatThrownBy(() -> observation.selectedCandidates().put("other", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stageSnapshotsValues() {
        final List<RailixValue> values = new ArrayList<>(List.of(RailixValue.string("before")));
        final DevelopmentRuntime.Stage stage = new DevelopmentRuntime.Stage(
                "steps",
                "nodes[1].inputs.steps[0]",
                "text.lowercase",
                "succeeded",
                values
        );

        values.set(0, RailixValue.string("after"));

        assertThat(stage.values()).containsExactly(RailixValue.string("before"));
    }

    @Test
    void stageValuesAreReadOnly() {
        assertThatThrownBy(() -> STAGE.values().add(RailixValue.string("other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void observationRejectsNullValuesDeterministically() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Observation(
                        null,
                        "step",
                        EMPTY_CONTEXT,
                        Map.of(),
                        List.of(),
                        Map.of()
                ))
                .withMessage("Development observation values cannot be Java null.");
    }

    @ParameterizedTest(name = "observation rejects null {0}")
    @MethodSource("remainingNullObservationValues")
    void observationRejectsEveryRemainingNullTopLevelValue(
            final String ignoredField,
            final Runnable construction
    ) {
        assertThatIllegalArgumentException().isThrownBy(construction::run)
                .withMessage("Development observation values cannot be Java null.");
    }

    @Test
    void observationRejectsNullCollectionEntriesDeterministically() {
        final Map<String, RailixValue> inputs = new LinkedHashMap<>();
        inputs.put("value", null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> observation(inputs, List.of(), Map.of()))
                .withMessage("Development observation input cannot contain Java null.");
    }

    @Test
    void stageRejectsNullValuesDeterministically() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Stage(
                        null,
                        "nodes[1].inputs.steps[0]",
                        "text.lowercase",
                        "succeeded",
                        List.of()
                ))
                .withMessage("Development observation stage values cannot be Java null.");
    }

    @ParameterizedTest(name = "stage rejects null {0}")
    @MethodSource("remainingNullStageValues")
    void stageRejectsEveryRemainingNullTopLevelValue(
            final String ignoredField,
            final Runnable construction
    ) {
        assertThatIllegalArgumentException().isThrownBy(construction::run)
                .withMessage("Development observation stage values cannot be Java null.");
    }

    @Test
    void stageRejectsNullCollectionEntriesDeterministically() {
        final List<RailixValue> values = new ArrayList<>();
        values.add(null);

        assertThatIllegalArgumentException().isThrownBy(() -> new DevelopmentRuntime.Stage(
                        "steps",
                        "nodes[1].inputs.steps[0]",
                        "text.lowercase",
                        "succeeded",
                        values
                ))
                .withMessage("Development observation stage output cannot be Java null.");
    }

    private static DevelopmentRuntime.Observation observation(
            final Map<String, RailixValue> inputs,
            final List<DevelopmentRuntime.Stage> stages,
            final Map<String, Integer> selectedCandidates
    ) {
        return new DevelopmentRuntime.Observation(
                RESULT,
                "step",
                EMPTY_CONTEXT,
                inputs,
                stages,
                selectedCandidates
        );
    }

    private static Stream<Arguments> remainingNullObservationValues() {
        return Stream.of(
                Arguments.of("step", (Runnable) () -> new DevelopmentRuntime.Observation(
                        RESULT, null, EMPTY_CONTEXT, Map.of(), List.of(), Map.of()
                )),
                Arguments.of("input context", (Runnable) () -> new DevelopmentRuntime.Observation(
                        RESULT, "step", null, Map.of(), List.of(), Map.of()
                )),
                Arguments.of("inputs", (Runnable) () -> new DevelopmentRuntime.Observation(
                        RESULT, "step", EMPTY_CONTEXT, null, List.of(), Map.of()
                )),
                Arguments.of("stages", (Runnable) () -> new DevelopmentRuntime.Observation(
                        RESULT, "step", EMPTY_CONTEXT, Map.of(), null, Map.of()
                )),
                Arguments.of("selected candidates", (Runnable) () -> new DevelopmentRuntime.Observation(
                        RESULT, "step", EMPTY_CONTEXT, Map.of(), List.of(), null
                ))
        );
    }

    private static Stream<Arguments> remainingNullStageValues() {
        return Stream.of(
                Arguments.of("invocation", (Runnable) () -> new DevelopmentRuntime.Stage(
                        "steps", null, "text.lowercase", "succeeded", List.of()
                )),
                Arguments.of("use", (Runnable) () -> new DevelopmentRuntime.Stage(
                        "steps", "nodes[1].inputs.steps[0]", null, "succeeded", List.of()
                )),
                Arguments.of("status", (Runnable) () -> new DevelopmentRuntime.Stage(
                        "steps", "nodes[1].inputs.steps[0]", "text.lowercase", null, List.of()
                )),
                Arguments.of("values", (Runnable) () -> new DevelopmentRuntime.Stage(
                        "steps", "nodes[1].inputs.steps[0]", "text.lowercase", "succeeded", null
                ))
        );
    }
}
