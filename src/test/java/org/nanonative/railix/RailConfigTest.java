package org.nanonative.railix;

import org.junit.jupiter.api.Test;
import org.nanonative.railix.log.LogFormat;
import org.nanonative.railix.log.LogLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RailConfigTest {

    @Test
    void of_withConfigurer_shouldApplyRailConfigAndInvokeOnComplete() {
        final AtomicReference<Result> completed = new AtomicReference<>();

        final Rail rail = Rail.of(config -> config
            .name("test_rail")
            .logLevel(LogLevel.DEBUG)
            .onComplete(completed::set));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(rail.railConfig().asString(RailConfig.KEY_NAME)).isEqualTo("test_rail");
        assertThat(completed.get()).isSameAs(result);
    }

    @Test
    void of_withReusableOverrides_shouldReuseConfigAcrossRails() {
        final Map<String, Object> config = RailConfig.builder()
            .name("shared_config")
            .logLevel(LogLevel.INFO)
            .build();

        final Rail rail1 = Rail.of(config);
        final Rail rail2 = new Rail(config);
        final Rail rail3 = Rail.of("source", config);

        assertThat(rail1.railConfig().asString(RailConfig.KEY_NAME)).isEqualTo("shared_config");
        assertThat(rail2.railConfig().asString(RailConfig.KEY_NAME)).isEqualTo("shared_config");
        assertThat(rail3.railConfig().asString(RailConfig.KEY_NAME)).isEqualTo("shared_config");
    }

    @Test
    void fire_withNamedRail_shouldRecordMetrics() {
        final String railName = "metrics_test_rail_" + System.nanoTime();
        final Rail rail = Rail.of(config -> config.name(railName));

        final Result result = rail.fire();

        final String prometheus = rail.metrics().toPrometheus();
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(prometheus).contains("rail_executions_total", "rail=\"" + railName + "\"", "outcome=\"success\"");
    }

    @Test
    void fire_withGlobalMetricsSwitch_shouldInterpretStringNumberAndBlankValues() {
        final RailixRuntime runtime = RailixRuntime.global();
        final String key = RailConfig.KEY_METRICS_ENABLED;
        final String nameYes = "metrics_yes_" + System.nanoTime();
        final String nameZero = "metrics_zero_" + System.nanoTime();
        final String nameBlank = "metrics_blank_" + System.nanoTime();

        try {
            runtime.updateConfig(Map.of(key, "yes"));
            final Rail yesRail = Rail.of(config -> config.name(nameYes));
            yesRail.fire();

            runtime.updateConfig(Map.of(key, 0));
            final Rail zeroRail = Rail.of(config -> config.name(nameZero));
            zeroRail.fire();

            runtime.updateConfig(Map.of(key, "   "));
            final Rail blankRail = Rail.of(config -> config.name(nameBlank));
            blankRail.fire();

            final String prometheus = runtime.metrics().toPrometheus();
            assertThat(prometheus).contains("rail=\"" + nameYes + "\"", "rail=\"" + nameBlank + "\"");
            assertThat(prometheus).doesNotContain("rail=\"" + nameZero + "\"");
        } finally {
            final HashMap<String, Object> cleanup = new HashMap<>();
            cleanup.put(key, null);
            runtime.updateConfig(cleanup);
        }
    }

    @Test
    void fire_withGlobalMetricsFalseString_shouldDisableMetrics() {
        final RailixRuntime runtime = RailixRuntime.global();
        final String key = RailConfig.KEY_METRICS_ENABLED;
        final String railName = "metrics_false_" + System.nanoTime();

        try {
            runtime.updateConfig(Map.of(key, "false"));
            final Rail rail = Rail.of(config -> config.name(railName));
            rail.fire();

            assertThat(runtime.metrics().toPrometheus()).doesNotContain("rail=\"" + railName + "\"");
        } finally {
            final HashMap<String, Object> cleanup = new HashMap<>();
            cleanup.put(key, null);
            runtime.updateConfig(cleanup);
        }
    }

    @Test
    void fire_withMetricsDisabled_shouldNotRecordExecutionMetric() {
        final String railName = "metrics_disabled_" + System.nanoTime();
        final Rail rail = Rail.of(config -> config.name(railName).metrics(false));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(rail.metrics().toPrometheus()).doesNotContain("rail=\"" + railName + "\"");
    }

    @Test
    void onComplete_whenCallbackThrows_shouldNotBreakResultDelivery() {
        final AtomicInteger callbackCount = new AtomicInteger();
        final Rail rail = Rail.of(config -> config
            .onComplete(result -> {
                callbackCount.incrementAndGet();
                throw new IllegalStateException("boom");
            }));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(callbackCount.get()).isEqualTo(1);
    }

    @Test
    void builder_withBlankKeysAndNullValues_shouldSanitizeAndRemoveEntries() {
        final Map<String, Object> config = RailConfig.builder()
            .name("  custom_name  ")
            .kv(" railix custom key ", "value")
            .kv("   ", "ignored")
            .kv("railix_remove_me", "gone")
            .kv("railix_remove_me", null)
            .build();

        assertThat(config.get(RailConfig.KEY_NAME)).isEqualTo("custom_name");
        assertThat(config.get("railix_custom_key")).isEqualTo("value");
        assertThat(config).doesNotContainKey("railix_remove_me");
        assertThat(config).doesNotContainKey("");
    }

    @Test
    void builder_withLogFormatAndExecutor_shouldStoreOverrides() {
        final Executor executor = Runnable::run;
        final Map<String, Object> config = RailConfig.builder()
            .logFormat(LogFormat.JSON)
            .executor(executor)
            .build();

        assertThat(config.get(RailConfig.KEY_LOG_FORMAT)).isEqualTo(LogFormat.JSON);
        assertThat(config.get(RailConfig.KEY_EXECUTOR)).isSameAs(executor);
    }

    @Test
    void builder_whenEmpty_shouldBuildEmptyImmutableMap() {
        assertThat(RailConfig.builder().build()).isEqualTo(Map.of());
    }

    @Test
    void builder_withNullOverrides_shouldRemoveExistingEntriesAndRemainImmutable() {
        final Executor executor = Runnable::run;
        final Map<String, Object> config = RailConfig.builder()
            .name("rail_name")
            .logLevel(LogLevel.DEBUG)
            .logLevel(null)
            .logFormat(LogFormat.JSON)
            .logFormat(null)
            .executor(executor)
            .executor(null)
            .onComplete(result -> {
            })
            .onComplete(null)
            .metrics(true)
            .build();

        assertThat(config.get(RailConfig.KEY_NAME)).isEqualTo("rail_name");
        assertThat(config.get(RailConfig.KEY_METRICS_ENABLED)).isEqualTo(true);
        assertThat(config).doesNotContainKeys(
            RailConfig.KEY_LOG_LEVEL,
            RailConfig.KEY_LOG_FORMAT,
            RailConfig.KEY_EXECUTOR,
            RailConfig.KEY_ON_COMPLETE);
        assertThatThrownBy(() -> config.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builder_withNullNameAndNullKey_shouldDropEntries() {
        final Map<String, Object> config = RailConfig.builder()
            .name("initial")
            .name(null)
            .kv(null, "ignored")
            .build();

        assertThat(config).isEmpty();
    }

    @Test
    void builder_withBlankNameAndSanitizedBlankKey_shouldDropEntries() {
        final Map<String, Object> config = RailConfig.builder()
            .name("initial")
            .name("   ")
            .kv("", "ignored")
            .kv("___", "ignored")
            .build();

        assertThat(config).isEmpty();
    }

    @Test
    void constructor_withConcurrentFlag_shouldMarkRailConcurrent() {
        assertThat(Rail.of().isConcurrent()).isFalse();
        assertThat(new Rail(true).isConcurrent()).isTrue();
        assertThat(Rail.of(true).isConcurrent()).isTrue();
    }
}
