package org.nanonative.railix;

import berlin.yuna.typemap.model.TypeMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ResultTest {

    @Test
    void canonicalConstructor_withNullOutcomeMessagePayloadAndCtx_shouldNormalizeDefaults() {
        final Result result = new Result(null, null, Result.CODE_ABSENT, null, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(result.message()).isEmpty();
        assertThat(result.payload()).isNotNull();
        assertThat(result.ctx()).isNotNull();
    }

    @Test
    void codeOpt_withAbsentCode_shouldBeEmpty() {
        final Result result = Result.error("x", Result.CODE_ABSENT, new TypeMap(), new TypeMap());

        assertThat(result.codeOpt()).isEmpty();
    }

    @Test
    void messageCodeAndCauseOptionals_withPresentValues_shouldExposeThem() {
        final IllegalStateException cause = new IllegalStateException("boom");
        final Result result = new Result(Outcome.ERROR, "failed", 409, cause, null, null);

        assertThat(result.messageOpt()).contains("failed");
        assertThat(result.codeOpt()).contains(409);
        assertThat(result.causeOpt()).contains(cause);
    }

    @Test
    void messageOpt_withBlankMessage_shouldBeEmpty() {
        final Result result = new Result(Outcome.SUCCESS, " ", Result.CODE_ABSENT, null, null, null);

        assertThat(result.messageOpt()).isEmpty();
    }

    @Test
    void causeOpt_withCause_shouldBePresent() {
        final IllegalStateException error = new IllegalStateException("boom");
        final Result result = Result.unexpected(error, "nope", 500, new TypeMap(), new TypeMap());

        assertThat(result.causeOpt().orElseThrow()).isSameAs(error);
    }

    @Test
    void error_withNullMessageAndNullState_shouldNormalizeDefaults() {
        final Result result = Result.error(null, 400, null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).isEmpty();
        assertThat(result.payload()).isEmpty();
        assertThat(result.ctx()).isEmpty();
    }

    @Test
    void causeOpt_withoutCause_shouldBeEmpty() {
        final Result result = Result.success(null, null);

        assertThat(result.causeOpt()).isEmpty();
    }

    @Test
    void success_withPayloadAndCtx_shouldSnapshotBothStates() {
        final TypeMap payload = new TypeMap().putR("email", "user@example.com");
        final TypeMap ctx = new TypeMap().putR("trace_id", "trace-1");

        final Result result = Result.success(payload, ctx);

        payload.putR("email", "changed@example.com");
        ctx.putR("trace_id", "trace-2");

        assertThat(result.payload().asString("email")).isEqualTo("user@example.com");
        assertThat(result.ctx().asString("trace_id")).isEqualTo("trace-1");
    }
}
