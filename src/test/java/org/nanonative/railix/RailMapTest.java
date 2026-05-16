package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailMapTest {

    @Test
    void map_withSinglePath_shouldReplaceExistingValue() {
        final Rail rail = Rail.of()
            .map(value -> value == null ? null : String.valueOf(value).trim().toLowerCase(), "email");
        final Result result = rail.fire(Map.of("email", "  USER@EXAMPLE.COM  "));

        assertThat(result.payload().asString("email")).isEqualTo("user@example.com");
    }

    @Test
    void map_withNamedSamePath_shouldWriteMappedValue() {
        final Rail rail = Rail.of()
            .map("normalize_email",
                value -> value == null ? null : String.valueOf(value).toUpperCase(),
                "email");
        final Result result = rail.fire(Map.of("email", "user@example.com"));

        assertThat(result.payload().asString("email")).isEqualTo("USER@EXAMPLE.COM");
    }

    @Test
    void map_withMissingPath_shouldSkipWrite() {
        final Rail rail = Rail.of()
            .map("missing", String::valueOf, "user", "email");
        final Result result = rail.fire();

        assertThat(result.payload().asString("user", "email")).isNull();
    }

    @Test
    void map_withPresentNullValueOrEmptyPath_shouldSkipWithoutCallingMapper() {
        final Rail nullValueRail = Rail.of()
            .map(value -> "changed", "email");
        final HashMap<String, Object> payload = new HashMap<>();
        payload.put("email", null);
        final Result nullValue = nullValueRail.fire(payload);

        final Rail emptyPathRail = Rail.of()
            .map(String::valueOf);
        final Result emptyPath = emptyPathRail.fire(Map.of("email", "x"));

        assertThat(nullValue.payload()).containsKey("email");
        assertThat(nullValue.payload().get("email")).isNull();
        assertThat(emptyPath.payload().asString("email")).isEqualTo("x");
    }

    @Test
    void map_withNullMapperOrNullPath_shouldSkipWithoutFailure() {
        final Rail nullMapperRail = Rail.of().map((java.util.function.UnaryOperator<Object>) null, "email");
        final Result nullMapper = nullMapperRail.fire(Map.of("email", "x"));
        final Rail nullPathRail = Rail.of().map(String::valueOf, (Object[]) null);
        final Result nullPath = nullPathRail.fire(Map.of("email", "x"));

        assertThat(nullMapper.payload().asString("email")).isEqualTo("x");
        assertThat(nullPath.payload().asString("email")).isEqualTo("x");
    }

    @Test
    void map_withNullMappedValue_shouldKeepExplicitNullAtPath() {
        final Rail rail = Rail.of()
            .map(value -> null, "email");
        final Result result = rail.fire(Map.of("email", "user@example.com"));

        assertThat(result.payload()).containsKey("email");
        assertThat(result.payload().get("email")).isNull();
    }

    @Test
    void map_withMapperException_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .map(value -> {
                throw new IllegalStateException("boom");
            }, "email");
        final Result result = rail.fire(Map.of("email", "user@example.com"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("map_failed");
        assertThat(result.causeOpt()).isPresent();
    }
}
