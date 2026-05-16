package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailParallelTest {

    @Test
    void parallel_withIndependentBranches_shouldMergePayloadAndCtx() {
        final Rail rail = Rail.of()
            .parallel(
                stepRail -> stepRail.payload().putR("left", true),
                stepRail -> stepRail.ctxMap().putR("trace_id", "trace-1"));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("left")).isTrue();
        assertThat(result.ctx().asString("trace_id")).isEqualTo("trace-1");
    }

    @Test
    void parallel_withFailingBranch_shouldReturnUnexpectedAndKeepSuccessfulSiblingData() {
        final Rail rail = Rail.of()
            .parallel(
                stepRail -> {
                    throw new IllegalStateException("boom");
                },
                stepRail -> stepRail.payload().putR("success", true));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.payload().asBoolean("success")).isTrue();
        assertThat(result.causeOpt()).isPresent();
    }

    @Test
    void parallel_withNullAndEmptySteps_shouldSkipWithoutFailure() {
        final Rail nullStepsRail = Rail.of().parallel((org.nanonative.railix.fn.Step<Rail>[]) null);
        final Rail nullNamedStepRail = Rail.of().parallel("noop", (org.nanonative.railix.fn.Step<Rail>) null);
        final Rail mixedRail = Rail.of().parallel("mixed", null, stepRail -> stepRail.payload().putR("ok", true));

        assertThat(nullStepsRail.fire().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(nullNamedStepRail.fire().outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(mixedRail.fire().payload().asBoolean("ok")).isTrue();
    }

    @Test
    void parallelEach_withCollectionPath_shouldExposeIndexNullKeyAndValue() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item_" + index, key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", List.of("a", "b", "c")));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asString("item_0")).isEqualTo("null:a");
        assertThat(result.ctx().asString("item_1")).isEqualTo("null:b");
        assertThat(result.ctx().asString("item_2")).isEqualTo("null:c");
    }

    @Test
    void parallelEach_withMapPath_shouldExposeEncounterIndexKeyAndValue() {
        final LinkedHashMap<String, String> items = new LinkedHashMap<>();
        items.put("a", "x");
        items.put("b", "y");

        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item_" + index, key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", items));

        assertThat(result.ctx().asString("item_0")).isEqualTo("a:x");
        assertThat(result.ctx().asString("item_1")).isEqualTo("b:y");
    }

    @Test
    void parallelEach_withScalarPath_shouldVisitSingleItem() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item_" + index, key + ":" + value),
                "item");

        final Result result = rail.fire(Map.of("item", "solo"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asString("item_0")).isEqualTo("null:solo");
    }

    @Test
    void parallelEach_withArrayPath_shouldVisitItemsInEncounterOrder() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item_" + index, key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", new String[]{"a", "b"}));

        assertThat(result.ctx().asString("item_0")).isEqualTo("null:a");
        assertThat(result.ctx().asString("item_1")).isEqualTo("null:b");
    }

    @Test
    void parallel_withInternalCtxKeys_shouldNotLeakIntoMergedResultCtx() {
        final Rail rail = Rail.of()
            .parallel(stepRail -> stepRail.ctxMap().putR("_hidden", true),
                stepRail -> stepRail.ctxMap().putR("visible", true));

        final Result result = rail.fire();

        assertThat(result.ctx().asBoolean("visible")).isTrue();
        assertThat(result.ctx().asBoolean("_hidden")).isNull();
    }

    @Test
    void parallelEach_withMissingPath_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item", value), "missing");

        final Result result = rail.fire(Map.of("items", List.of("x")));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asString("item")).isNull();
    }

    @Test
    void parallelEach_withNullPathOrLogic_shouldSkipWithoutFailure() {
        final Rail nullPathRail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("item", value), (Object[]) null);
        final Result nullPath = nullPathRail.fire(Map.of("items", List.of("x")));

        final Rail nullLogicRail = Rail.of().parallelEach(null, "items");
        final Result nullLogic = nullLogicRail.fire(Map.of("items", List.of("x")));

        assertThat(nullPath.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(nullLogic.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(nullPath.ctx().asString("item")).isNull();
    }

    @Test
    void parallelEach_withConflictingWrites_shouldMergeInSourceOrder() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> stepRail.ctxMap().putR("winner", value), "items");

        final Result result = rail.fire(Map.of("items", List.of("first", "second", "third")));

        assertThat(result.ctx().asString("winner")).isEqualTo("third");
    }

    @Test
    void parallelEach_withFailingChild_shouldReturnUnexpectedAndKeepSuccessfulSiblingData() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> {
                if ("bad".equals(value)) {
                    throw new IllegalStateException("boom");
                }
                stepRail.ctxMap().putR("good_" + value, true);
            }, "items");

        final Result result = rail.fire(Map.of("items", List.of("good", "bad", "also_good")));

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.ctx().asBoolean("good_good")).isTrue();
        assertThat(result.ctx().asBoolean("good_also_good")).isTrue();
        assertThat(result.causeOpt()).isPresent();
    }

    @Test
    void parallelEach_withBusinessFailure_shouldReturnErrorAndKeepSuccessfulSiblingData() {
        final Rail rail = Rail.of()
            .parallelEach((stepRail, index, key, value) -> {
                if ("bad".equals(value)) {
                    stepRail.fail("bad_item", 422);
                    return;
                }
                stepRail.ctxMap().putR("good_" + value, true);
            }, "items");

        final Result result = rail.fire(Map.of("items", List.of("good", "bad", "also_good")));

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEqualTo("bad_item");
        assertThat(result.code()).isEqualTo(422);
        assertThat(result.ctx().asBoolean("good_good")).isTrue();
        assertThat(result.ctx().asBoolean("good_also_good")).isTrue();
    }

    @Test
    void parallel_withUnexpectedAndErrorChildren_shouldPreferUnexpected() {
        final Rail rail = Rail.of()
            .parallel(
                stepRail -> stepRail.fail("business_error", 409),
                stepRail -> {
                    throw new IllegalStateException("boom");
                });

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("step_failed");
        assertThat(result.causeOpt()).isPresent();
    }

    @Test
    void parallel_withTwoErrorChildren_shouldKeepFirstFailureBySourceOrder() {
        final Rail rail = Rail.of()
            .parallel(
                stepRail -> stepRail.fail("first_error", 409),
                stepRail -> stepRail.fail("second_error", 422));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEqualTo("first_error");
        assertThat(result.code()).isEqualTo(409);
    }

    @Test
    void parallel_withUnexpectedBeforeError_shouldKeepFirstUnexpected() {
        final Rail rail = Rail.of()
            .parallel(
                stepRail -> {
                    throw new IllegalStateException("first");
                },
                stepRail -> stepRail.fail("second_error", 422));

        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("step_failed");
        assertThat(result.causeOpt()).isPresent();
        assertThat(result.causeOpt().orElseThrow()).hasMessage("first");
    }
}
