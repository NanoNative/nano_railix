package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailChoiceTest {

    @Test
    void choose_whenConditionTrueInPayload_shouldExecuteThenStep() {
        final Rail rail = Rail.of()
            .choose("route", stepRail -> Boolean.TRUE.equals(stepRail.payload().asBoolean("condition")),
                stepRail -> stepRail.payload().putR("then", true),
                stepRail -> stepRail.payload().putR("else", true));
        final Result result = rail.fire(Map.of("condition", true));

        assertThat(result.payload().asBoolean("then")).isTrue();
        assertThat(result.payload().asBoolean("else")).isNull();
    }

    @Test
    void choose_withUnnamedThenOnlyOverload_shouldExecuteThenStep() {
        final Rail rail = Rail.of()
            .choose(stepRail -> true, stepRail -> stepRail.payload().putR("then", true));
        final Result result = rail.fire();

        assertThat(result.payload().asBoolean("then")).isTrue();
    }

    @Test
    void choose_withNamedThenOnlyOverload_shouldExecuteThenStep() {
        final Rail rail = Rail.of()
            .choose("named_route", stepRail -> true, stepRail -> stepRail.payload().putR("then", true));
        final Result result = rail.fire();

        assertThat(result.payload().asBoolean("then")).isTrue();
    }

    @Test
    void choose_withUnnamedElseOverload_shouldExecuteElseStep() {
        final Rail rail = Rail.of()
            .choose(stepRail -> false,
                stepRail -> stepRail.payload().putR("then", true),
                stepRail -> stepRail.payload().putR("else", true));
        final Result result = rail.fire();

        assertThat(result.payload().asBoolean("else")).isTrue();
        assertThat(result.payload().asBoolean("then")).isNull();
    }

    @Test
    void choose_whenConditionFalseInPayload_shouldExecuteElseStep() {
        final Rail rail = Rail.of()
            .choose("route", stepRail -> Boolean.TRUE.equals(stepRail.payload().asBoolean("condition")),
                stepRail -> stepRail.payload().putR("then", true),
                stepRail -> stepRail.payload().putR("else", true));
        final Result result = rail.fire(Map.of("condition", false));

        assertThat(result.payload().asBoolean("else")).isTrue();
        assertThat(result.payload().asBoolean("then")).isNull();
    }

    @Test
    void choose_whenConditionMissingInPayload_shouldExecuteElseStep() {
        final Rail rail = Rail.of()
            .choose("route", stepRail -> Boolean.TRUE.equals(stepRail.payload().asBoolean("condition")),
                stepRail -> stepRail.payload().putR("then", true),
                stepRail -> stepRail.payload().putR("else", true));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("else")).isTrue();
        assertThat(result.payload().asBoolean("then")).isNull();
    }

    @Test
    void choose_withNullCondition_shouldSkipBothBranches() {
        final Rail rail = Rail.of()
            .choose("route", null,
                stepRail -> stepRail.payload().putR("then", true),
                stepRail -> stepRail.payload().putR("else", true));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("then")).isNull();
        assertThat(result.payload().asBoolean("else")).isNull();
    }

    @Test
    void choose_withNullBranches_shouldEvaluateConditionAndSkipMissingSteps() {
        final Rail thenMissing = Rail.of()
            .choose("route", stepRail -> true, null, stepRail -> stepRail.payload().putR("else", true));
        final Result thenMissingResult = thenMissing.fire();

        final Rail elseMissing = Rail.of()
            .choose("route", stepRail -> false, stepRail -> stepRail.payload().putR("then", true), null);
        final Result elseMissingResult = elseMissing.fire();

        assertThat(thenMissingResult.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(thenMissingResult.payload().asBoolean("else")).isNull();
        assertThat(elseMissingResult.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(elseMissingResult.payload().asBoolean("then")).isNull();
    }

    @Test
    void choose_whenConditionThrows_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .choose("route", stepRail -> {
                throw new IllegalStateException("boom");
            }, stepRail -> stepRail.payload().putR("then", true));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("choose_failed");
        assertThat(result.causeOpt()).isPresent();
    }
}
