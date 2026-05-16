package org.nanonative.railix;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class RailVerifyTest {

    @Test
    void verify_whenConditionMatches_shouldContinue() {
        final Rail rail = Rail.of()
            .verify("email_present", stepRail -> stepRail.payload().asString("email") != null)
            .step(stepRail -> stepRail.payload().putR("verified", true));
        final Result result = rail.fire(Map.of("email", "user@example.com"));

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.payload().asBoolean("verified")).isTrue();
    }

    @Test
    void verify_withoutName_whenConditionFails_shouldUseDefaultMessageAndAbsentCode() {
        final Rail rail = Rail.of().verify(stepRail -> false);
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEqualTo("verification_failed");
        assertThat(result.codeOpt()).isEmpty();
    }

    @Test
    void verify_withCustomMessageOverload_whenConditionFails_shouldReturnError() {
        final Rail rail = Rail.of()
            .verify(stepRail -> stepRail.payload().asString("email") != null, "email_required", 400)
            .step(stepRail -> stepRail.payload().putR("after", true));
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEqualTo("email_required");
        assertThat(result.code()).isEqualTo(400);
        assertThat(result.payload().asBoolean("after")).isNull();
    }

    @Test
    void verify_whenConditionThrows_shouldReturnUnexpected() {
        final Rail rail = Rail.of()
            .verify("explodes", stepRail -> {
                throw new IllegalStateException("boom");
            });
        final Result result = rail.fire();

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEqualTo("verify_failed");
        assertThat(result.causeOpt()).isPresent();
    }

    @Test
    void verify_withNullCondition_shouldSkipAndWithNullMessage_shouldFallback() {
        final Rail skipped = Rail.of()
            .verify("skip", null)
            .step(stepRail -> stepRail.payload().putR("after", true));
        final Result skippedResult = skipped.fire();

        final Rail fallback = Rail.of()
            .verify("named", stepRail -> false, null, 418);
        final Result fallbackResult = fallback.fire();

        assertThat(skippedResult.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(skippedResult.payload().asBoolean("after")).isTrue();
        assertThat(fallbackResult.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(fallbackResult.message()).isEqualTo("verification_failed");
        assertThat(fallbackResult.code()).isEqualTo(418);
    }
}
