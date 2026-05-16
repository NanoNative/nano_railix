package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailRuntimeTest {

    @Test
    void updateConfig_withChangesAndTombstones_shouldApplyDiff() {
        final RailixRuntime runtime = RailixRuntime.global();
        final String key = "railix_test_runtime_update_key";

        try {
            final HashMap<String, Object> first = new HashMap<>();
            first.put(key, "a");

            final Map<Object, Object> firstDiff = runtime.updateConfig(first);
            assertThat(firstDiff.get(key)).isEqualTo("a");
            assertThat(runtime.config().asString(key)).isEqualTo("a");

            final HashMap<String, Object> unchanged = new HashMap<>();
            unchanged.put(key, "a");
            assertThat(runtime.updateConfig(unchanged)).isEmpty();

            final HashMap<String, Object> second = new HashMap<>();
            second.put(key, "b");

            final Map<Object, Object> secondDiff = runtime.updateConfig(second);
            assertThat(secondDiff.get(key)).isEqualTo("b");
            assertThat(runtime.config().asString(key)).isEqualTo("b");

            final HashMap<Object, Object> remove = new HashMap<>();
            remove.put(key, null);

            final Map<Object, Object> removeDiff = runtime.updateConfig(remove);
            assertThat(removeDiff).containsKey(key);
            assertThat(removeDiff.get(key)).isNull();
            assertThat(runtime.config().containsKey(key)).isFalse();
        } finally {
            final HashMap<Object, Object> cleanup = new HashMap<>();
            cleanup.put(key, null);
            runtime.updateConfig(cleanup);
        }
    }

    @Test
    void global_withExecutorMetricsActorsAndConfig_shouldExposeStableSingletonComponents() {
        final RailixRuntime runtime = RailixRuntime.global();

        assertThat(RailixRuntime.global()).isSameAs(runtime);
        assertThat(runtime.config()).isSameAs(Rail.globalConfig());
        assertThat(runtime.executor()).isNotNull();
        assertThat(runtime.metrics()).isNotNull();
        assertThat(runtime.actors()).isNotNull();
    }

    @Test
    void updateConfig_withNullOrEmptyChanges_shouldReturnEmptyDiff() {
        final RailixRuntime runtime = RailixRuntime.global();

        assertThat(runtime.updateConfig(null)).isEmpty();
        assertThat(runtime.updateConfig(Map.of())).isEmpty();
    }

    @Test
    void updateConfig_withSanitizedAndInvalidKeys_shouldNormalizeAndIgnoreBlanks() {
        final RailixRuntime runtime = RailixRuntime.global();
        final String sanitizedKey = "railix_runtime_flag";

        try {
            final Map<Object, Object> diff = runtime.updateConfig(Map.of(
                "railix runtime flag", "on",
                "   ", "ignored",
                42, "answer"));

            assertThat(diff.get(sanitizedKey)).isEqualTo("on");
            assertThat(diff.get(42)).isEqualTo("answer");
            assertThat(diff).doesNotContainKey("   ");
            assertThat(runtime.config().asString(sanitizedKey)).isEqualTo("on");
            assertThat(runtime.config().get(42)).isEqualTo("answer");
        } finally {
            final HashMap<Object, Object> cleanup = new HashMap<>();
            cleanup.put(sanitizedKey, null);
            cleanup.put(42, null);
            runtime.updateConfig(cleanup);
        }
    }

    @Test
    void actorLookup_withNullInputsAndDirectRegistration_shouldBehavePredictably() {
        final RailixRuntime runtime = RailixRuntime.global();

        assertThat(runtime.actorByName(null)).isNull();
        assertThat(runtime.actorByType(null)).isNull();

        final class RuntimeActor {
        }
        final RuntimeActor actor = new RuntimeActor();

        runtime.registerActor(null, RuntimeActor.class, actor);
        assertThat(runtime.actorByType(RuntimeActor.class)).isSameAs(actor);

        runtime.registerActor("runtime actor", null, actor);
        assertThat(runtime.actorByName("runtime actor")).isSameAs(actor);
        assertThat(runtime.actorByName("runtime_actor")).isNull();

        runtime.registerActor("runtime_actor", RuntimeActor.class, null);
        assertThat(runtime.actorByName("runtime_actor")).isNull();
    }
}
