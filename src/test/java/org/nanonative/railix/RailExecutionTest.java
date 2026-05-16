package org.nanonative.railix;

import berlin.yuna.typemap.model.ConcurrentTypeMap;
import berlin.yuna.typemap.model.TypeMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class RailExecutionTest {

    @Test
    void of_withSource_shouldExposeBoundSource() {
        assertThat(Rail.of("http").source()).isEqualTo("http");
    }

    @Test
    void newRail_withStateAccessors_shouldExposeUnsealedAndNoResultYet() {
        final Rail rail = Rail.of();

        assertThat(rail.sealed()).isFalse();
        assertThat(rail.done()).isFalse();
        assertThat(rail.resultOpt()).isEmpty();
    }

    @Test
    void runtimeAndGlobalConfig_shouldExposeSingletonRuntimeState() {
        final Rail rail = Rail.of();

        assertThat(rail.runtime()).isSameAs(RailixRuntime.global());
        assertThat(rail.config()).isSameAs(Rail.globalConfig());
        assertThat(rail.actors()).isSameAs(RailixRuntime.global().actors());
    }

    @Test
    void of_withNullConfigurerAndNullRailConfig_shouldStillCreateUsableRail() {
        final Rail configured = Rail.of((java.util.function.Consumer<RailConfig.Builder>) null);
        final Rail constructed = new Rail("source", null);

        assertThat(configured.fire().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(constructed.source()).isEqualTo("source");
        assertThat(constructed.fire().outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void fire_withoutExplicitTerminal_shouldReturnSuccess() {
        final Rail rail = Rail.of();
        final Result result = rail.fire(Map.of("email", "user@example.com"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asString("email")).isEqualTo("user@example.com");
    }

    @Test
    void fire_withPayloadAndCtxSeeds_shouldKeepScopesSeparate() {
        final Rail rail = Rail.of().step(stepRail -> stepRail.ctxMap().putR("ctx_only", true));
        final Result result = rail.fire(Map.of("email", "user@example.com"), Map.of("trace_id", "t-1"));

        assertThat(result.payload().asString("email")).isEqualTo("user@example.com");
        assertThat(result.ctx().asString("trace_id")).isEqualTo("t-1");
        assertThat(result.ctx().asBoolean("ctx_only")).isTrue();
        assertThat(result.payload().asString("trace_id")).isNull();
    }

    @Test
    void fire_withScalarPayload_shouldStoreValueAtRootKey() {
        final Rail rail = Rail.of();
        final Result result = rail.fire("hello");

        assertThat(result.payload().asString("")).isEqualTo("hello");
    }

    @Test
    void fire_onConcurrentRail_withTypeMapSeeds_shouldUseConcurrentMaps() {
        final AtomicReference<Class<?>> payloadType = new AtomicReference<>();
        final AtomicReference<Class<?>> ctxType = new AtomicReference<>();

        final Rail rail = Rail.of(true)
            .step(stepRail -> {
                payloadType.set(stepRail.payload().getClass());
                ctxType.set(stepRail.ctxMap().getClass());
            });
        final Result result =
            rail.fire(new TypeMap().putR("email", "user@example.com"), new ConcurrentTypeMap().putR("trace_id", "t-1"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(payloadType.get()).isEqualTo(ConcurrentTypeMap.class);
        assertThat(ctxType.get()).isEqualTo(ConcurrentTypeMap.class);
    }

    @Test
    void step_duringExecution_shouldScheduleFollowUpUnit() {
        final Rail rail = Rail.of()
            .step(stepRail -> stepRail.step("late", next -> next.payload().putR("late", true)));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("late")).isTrue();
    }

    @Test
    void step_withNullVarargsArray_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of().step((org.nanonative.railix.fn.Step<Rail>[]) null);
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
    }

    @Test
    void fire_multipleTimes_onReusableBlueprint_shouldNotLeakStateAcrossExecutions() {
        final AtomicInteger counter = new AtomicInteger();
        final RailDef rail = Rail.of()
            .set(counter::incrementAndGet, "trace_id")
            .ctxSet(() -> UUID.randomUUID().toString(), "trace_id")
            .seal();

        final Result first = rail.fire();
        final Result second = rail.fire();

        assertThat(first.payload().asInt("trace_id")).isEqualTo(1);
        assertThat(second.payload().asInt("trace_id")).isEqualTo(2);
        assertThat(first.ctx().asString("trace_id")).isNotEqualTo(second.ctx().asString("trace_id"));
        assertThat(rail.metrics()).isSameAs(RailixRuntime.global().metrics());
    }

    @Test
    void seal_shouldExposeReadOnlyRuntimeAccessAndSupportAllFireOverloads() {
        final Executor executor = Runnable::run;
        final RailDef rail = Rail.of("http", RailConfig.builder()
                .name("sealed_rail")
                .executor(executor)
                .build())
            .set("from_blueprint", true)
            .ctxSet("ctx_seeded", true)
            .seal();

        final Result noPayload = rail.fire();
        final Result withPayload = rail.fire(Map.of("email", "user@example.com"));
        final Result withPayloadAndCtx = rail.fire(Map.of("email", "user@example.com"), Map.of("trace_id", "t-1"));

        assertThat(rail.source()).isEqualTo("http");
        assertThat(rail.executor()).isSameAs(executor);
        assertThat(rail.isConcurrent()).isFalse();
        assertThat(rail.config()).isSameAs(RailixRuntime.global().config());
        assertThat(rail.metrics()).isSameAs(RailixRuntime.global().metrics());
        assertThat(rail.actors()).isSameAs(RailixRuntime.global().actors());
        assertThat(rail.railConfig().asString(RailConfig.KEY_NAME)).isEqualTo("sealed_rail");

        assertThat(noPayload.payload().asBoolean("from_blueprint")).isTrue();
        assertThat(withPayload.payload().asString("email")).isEqualTo("user@example.com");
        assertThat(withPayload.payload().asBoolean("from_blueprint")).isTrue();
        assertThat(withPayloadAndCtx.ctx().asString("trace_id")).isEqualTo("t-1");
        assertThat(withPayloadAndCtx.ctx().asBoolean("ctx_seeded")).isTrue();
    }

    @Test
    void executor_withRailConfigOverride_shouldPreferCustomExecutor() {
        final Executor executor = Runnable::run;
        final Rail rail = Rail.of(config -> config.executor(executor));

        assertThat(rail.executor()).isSameAs(executor);
    }

    @Test
    void ok_withFollowingStep_shouldStopExecution() {
        final Rail rail = Rail.of()
            .step(stepRail -> stepRail.payload().putR("before", true))
            .ok()
            .step(stepRail -> stepRail.payload().putR("after", true));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("before")).isTrue();
        assertThat(result.payload().asBoolean("after")).isNull();
    }

    @Test
    void fail_withFollowingStep_shouldStopExecution() {
        final Rail rail = Rail.of()
            .step(stepRail -> stepRail.payload().putR("before", true))
            .fail("boom", 409)
            .step(stepRail -> stepRail.payload().putR("after", true));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEqualTo("boom");
        assertThat(result.code()).isEqualTo(409);
        assertThat(result.payload().asBoolean("before")).isTrue();
        assertThat(result.payload().asBoolean("after")).isNull();
    }

    @Test
    void seal_withExtendedCopy_shouldKeepOriginalReusable() {
        final RailDef base = Rail.of()
            .set("base", true)
            .seal();

        final Rail extended = Rail.of()
            .step(base)
            .set("extended", true);

        final Result baseResult = base.fire();
        final Result extendedResult = extended.fire();

        assertThat(baseResult.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(baseResult.payload().asBoolean("base")).isTrue();
        assertThat(baseResult.payload().asBoolean("extended")).isNull();
        assertThat(extendedResult.payload().asBoolean("base")).isTrue();
        assertThat(extendedResult.payload().asBoolean("extended")).isTrue();
    }

    @Test
    void step_withIncludedRail_shouldReuseNestedUnits() {
        final RailDef nested = Rail.of()
            .set("nested", true)
            .seal();

        final Rail rail = Rail.of().step("include_nested", nested);
        final Result result = rail.fire(Map.of("root", true));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("root")).isTrue();
        assertThat(result.payload().asBoolean("nested")).isTrue();
    }

    @Test
    void step_withMutableIncludedRail_shouldReuseNestedUnits() {
        final Rail nested = Rail.of()
            .set("nested", true);

        final Rail rail = Rail.of().step("include_nested", nested);
        final Result result = rail.fire(Map.of("root", true));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("root")).isTrue();
        assertThat(result.payload().asBoolean("nested")).isTrue();
    }

    @Test
    void step_withNullOrEmptyIncludedRail_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of()
            .step((Rail) null)
            .step(Rail.of())
            .step((RailDef) null)
            .step(Rail.of().seal());
        final Result result = rail.fire(Map.of("root", true));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("root")).isTrue();
    }
}
