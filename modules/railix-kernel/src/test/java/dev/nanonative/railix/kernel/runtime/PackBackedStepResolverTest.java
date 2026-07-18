package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackBackedStepResolverTest {

    @Test
    void shouldResolveSingleMatchingProvider() {
        final Step step = step("std.data.capture-payload");
        final PackBackedStepResolver resolver = new PackBackedStepResolver(List.of(
                use -> Optional.empty(),
                use -> "std.data.capture-payload".equals(use) ? Optional.of(step) : Optional.empty()
        ));

        assertThat(resolver.resolve("std.data.capture-payload")).isSameAs(step);
    }

    @Test
    void shouldReturnNullWhenNoProviderMatches() {
        final PackBackedStepResolver resolver = new PackBackedStepResolver(List.of(use -> Optional.empty()));

        assertThat(resolver.resolve("std.data.missing")).isNull();
    }

    @Test
    void shouldRejectDuplicateProviderMatches() {
        final PackBackedStepResolver resolver = new PackBackedStepResolver(List.of(
                use -> Optional.of(step("std.data.capture-payload")),
                use -> Optional.of(step("std.data.capture-payload.duplicate"))
        ));

        assertThatThrownBy(() -> resolver.resolve("std.data.capture-payload"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple step providers resolved use: std.data.capture-payload");
    }

    @Test
    void shouldRejectBlankUse() {
        final PackBackedStepResolver resolver = new PackBackedStepResolver(List.of());

        assertThatThrownBy(() -> resolver.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use");
    }

    private static Step step(final String id) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        id,
                        "0.1.0",
                        id,
                        id,
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("ok")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result("ok", List.of());
            }
        };
    }
}
