package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailReduceTest {

    @Test
    void reduce_withNumbers_shouldStoreSum() {
        final Rail rail = Rail.of()
            .reduce("sum_numbers", 0, (Integer sum, Object value) -> sum + ((Number) value).intValue(), "numbers");
        final Result result = rail.fire(Map.of("numbers", List.of(1, 2, 3, 4)));

        assertThat(result.payload().asInt("numbers")).isEqualTo(10);
    }

    @Test
    void reduce_withMissingPath_shouldWriteIdentityToTargetPath() {
        final Rail rail = Rail.of()
            .reduce("missing_defaults", 5, (Integer sum, Object value) -> sum + ((Number) value).intValue(), "numbers");
        final Result result = rail.fire();

        assertThat(result.payload().asInt("numbers")).isEqualTo(5);
    }

    @Test
    void reduce_withEmptyIterable_shouldReplacePathWithIdentity() {
        final Rail rail = Rail.of()
            .reduce("empty_identity", 0, (Integer sum, Object value) -> sum + ((Number) value).intValue(), "numbers");
        final Result result = rail.fire(Map.of("numbers", List.of()));

        assertThat(result.payload().asInt("numbers")).isZero();
    }

    @Test
    void reduce_withMapPath_shouldReduceValuesOnlyForCurrentSemantics() {
        final LinkedHashMap<String, Integer> items = new LinkedHashMap<>();
        items.put("a", 2);
        items.put("b", 3);

        final Rail rail = Rail.of()
            .reduce("sum_values", 0, (Integer sum, Object value) -> sum + ((Number) value).intValue(), "items");
        final Result result = rail.fire(Map.of("items", items));

        assertThat(result.payload().asInt("items")).isEqualTo(5);
    }

    @Test
    void reduce_withScalarInput_shouldApplyReducerOnce() {
        final Rail rail = Rail.of()
            .reduce("scalar_once", 1, (Integer sum, Object value) -> sum + ((Number) value).intValue(), "number");
        final Result result = rail.fire(Map.of("number", 4));

        assertThat(result.payload().asInt("number")).isEqualTo(5);
    }

    @Test
    void reduce_withNullReducerOrNullPath_shouldSkipWithoutFailure() {
        final Rail nullReducerRail = Rail.of().reduce(0, null, "numbers");
        final Result nullReducer = nullReducerRail.fire(Map.of("numbers", List.of(1, 2)));
        final Rail nullPathRail =
            Rail.of().reduce(0, (Integer sum, Object value) -> sum + ((Number) value).intValue(), (Object[]) null);
        final Result nullPath = nullPathRail.fire(Map.of("numbers", List.of(1, 2)));
        final Rail emptyPathRail =
            Rail.of().reduce(0, (Integer sum, Object value) -> sum + ((Number) value).intValue());
        final Result emptyPath = emptyPathRail.fire(Map.of("numbers", List.of(1, 2)));

        assertThat(nullReducer.payload().asList(Integer.class, "numbers")).isEqualTo(List.of(1, 2));
        assertThat(nullPath.payload().asList(Integer.class, "numbers")).isEqualTo(List.of(1, 2));
        assertThat(emptyPath.payload().asList(Integer.class, "numbers")).isEqualTo(List.of(1, 2));
    }

    @Test
    void reduce_withReducerException_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .reduce(0, (Integer sum, Object value) -> {
                throw new IllegalStateException("boom");
            }, "numbers");
        final Result result = rail.fire(Map.of("numbers", List.of(1)));

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("reduce_failed");
        assertThat(result.causeOpt()).isPresent();
    }
}
