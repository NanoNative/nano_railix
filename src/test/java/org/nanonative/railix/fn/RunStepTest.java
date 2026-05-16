package org.nanonative.railix.fn;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

final class RunStepTest {

    @Test
    void step_withNullDelegate_shouldReturnTrueWithoutFailure() throws Exception {
        assertThat(RunStep.step(null).run(new Object())).isTrue();
    }

    @Test
    void step_withDelegate_shouldExecuteAndReturnTrue() throws Exception {
        final AtomicBoolean called = new AtomicBoolean(false);

        final boolean result = RunStep.step((AtomicBoolean flag) -> flag.set(true)).run(called);

        assertThat(result).isTrue();
        assertThat(called.get()).isTrue();
    }
}
