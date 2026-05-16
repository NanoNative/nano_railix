package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailEachTest {

    @Test
    void each_withCollectionPath_shouldExposeIndexNullKeyAndValue() {
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", List.of(1, 2, 3)));

        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:null:1", "1:null:2", "2:null:3"));
    }

    @Test
    void each_withNestedPath_shouldVisitNestedItems() {
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + value),
                "payload", "items");

        final Result result = rail.fire(Map.of("payload", Map.of("items", List.of("a", "b"))));

        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:a", "1:b"));
    }

    @Test
    void each_withMapPath_shouldExposeEncounterIndexKeyAndValue() {
        final LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
        items.put("a", 1);
        items.put("b", 2);

        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", items));

        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:a:1", "1:b:2"));
    }

    @Test
    void each_withIterablePath_shouldVisitItemsInEncounterOrder() {
        final LinkedHashSet<String> items = new LinkedHashSet<>(List.of("x", "y"));
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + key + ":" + value),
                "items");

        final Result result = rail.fire(Map.of("items", items));

        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:null:x", "1:null:y"));
    }

    @Test
    void each_withArrayPath_shouldVisitAllItems() {
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + value), "items");

        final Result result = rail.fire(Map.of("items", new String[]{"left", "right"}));

        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:left", "1:right"));
    }

    @Test
    void each_withScalarPath_shouldVisitSingleItemWithNullKey() {
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", index + ":" + key + ":" + value),
                "item");

        final Result result = rail.fire(Map.of("item", "value"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asList(String.class, "seen")).isEqualTo(List.of("0:null:value"));
    }

    @Test
    void each_withMissingPath_shouldSkipWithoutFailure() {
        final Rail rail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", value), "missing");

        final Result result = rail.fire(Map.of("items", List.of(1, 2, 3)));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.ctx().asList(Object.class, "seen")).isEmpty();
    }

    @Test
    void each_withNullPathOrLogic_shouldSkipWithoutFailure() {
        final Rail nullPathRail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each((stepRail, index, key, value) -> stepRail.ctxMap().addPath("seen", value), (Object[]) null);
        final Result nullPath = nullPathRail.fire(Map.of("items", List.of(1, 2, 3)));

        final Rail nullLogicRail = Rail.of()
            .ctxSet("seen", new ArrayList<>())
            .each(null, "items");
        final Result nullLogic = nullLogicRail.fire(Map.of("items", List.of(1, 2, 3)));

        assertThat(nullPath.ctx().asList(Object.class, "seen")).isEmpty();
        assertThat(nullLogic.ctx().asList(Object.class, "seen")).isEmpty();
    }

    @Test
    void each_withLogicException_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .each((stepRail, index, key, value) -> {
                throw new IllegalStateException("boom");
            }, "items");

        final Result result = rail.fire(Map.of("items", List.of(1)));

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("each_failed");
        assertThat(result.causeOpt()).isPresent();
    }
}
