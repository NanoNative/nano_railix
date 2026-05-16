package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class RailStepTest {

    @Test
    void step_withUncheckedException_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .step(stepRail -> {
                throw new IllegalStateException("boom");
            });
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("step_failed");
        assertThat(result.causeOpt()).isPresent();
    }

    @Test
    void step_withVarargs_shouldSkipNullEntriesAndRunRemainingSteps() {
        final AtomicInteger counter = new AtomicInteger(0);

        final Rail rail = Rail.of()
            .step(
                stepRail -> counter.incrementAndGet(),
                null,
                stepRail -> counter.incrementAndGet(),
                stepRail -> counter.incrementAndGet());
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void step_withName_shouldExposeCurrentStepNameInCtx() {
        final Rail rail = Rail.of()
            .step("my_custom_step", stepRail ->
                assertThat(stepRail.ctxMap().asString(Rail.KEY_CURRENT_STEP_NAME)).isEqualTo("my_custom_step"));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asString(Rail.KEY_CURRENT_STEP_NAME)).isNull();
    }
}
