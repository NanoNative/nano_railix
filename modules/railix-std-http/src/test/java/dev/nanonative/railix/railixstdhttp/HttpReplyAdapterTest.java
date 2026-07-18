package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReplyAdapterTest {

    private final HttpReplyAdapter adapter = new HttpReplyAdapter();

    @Test
    void shouldAdaptImmediateReplyWithStatusHeadersAndJsonBody() {
        final HttpReplyAdapter.AdaptedHttpReply adaptedReply = adapter.adapt(new Reply(
                Reply.Mode.IMMEDIATE,
                new RailixValue.NumberValue(BigDecimal.valueOf(201)),
                new RailixValue.ObjectValue(Map.of(
                        "contentType", new RailixValue.StringValue("application/json"),
                        "headers", new RailixValue.ObjectValue(Map.of(
                                "x-request-id", new RailixValue.StringValue("req-1"),
                                "cache-control", new RailixValue.ListValue(List.of(
                                        new RailixValue.StringValue("no-store"),
                                        new RailixValue.StringValue("max-age=0")
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of(
                        "ok", new RailixValue.BoolValue(true)
                )),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        ));

        assertThat(adaptedReply.status()).isEqualTo(201);
        assertThat(adaptedReply.headers()).containsEntry("content-type", List.of("application/json"));
        assertThat(adaptedReply.headers()).containsEntry("x-request-id", List.of("req-1"));
        assertThat(adaptedReply.headers()).containsEntry("cache-control", List.of("no-store", "max-age=0"));
        assertThat(KernelContractCodec.parseStableJsonObject(new String(adaptedReply.body(), StandardCharsets.UTF_8)))
                .containsEntry("ok", true);
    }

    @Test
    void shouldReturnNoContentForImmediateReplyWithNullPayload() {
        final HttpReplyAdapter.AdaptedHttpReply adaptedReply = adapter.adapt(new Reply(
                Reply.Mode.IMMEDIATE,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        ));

        assertThat(adaptedReply.status()).isEqualTo(204);
        assertThat(adaptedReply.headers()).isEmpty();
        assertThat(adaptedReply.body()).isEmpty();
    }

    @Test
    void shouldRejectUnsupportedReplyMode() {
        assertThatThrownBy(() -> adapter.adapt(new Reply(
                Reply.Mode.SESSION,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                new RailixValue.SessionRef("session-1", "http", Map.of()),
                RailixValue.NULL
        )))
                .isInstanceOf(HttpProblemException.class)
                .hasMessage("HTTP boundary does not support Reply.Mode.SESSION yet");
    }

    @Test
    void shouldRejectInvalidReplyHeaderShape() {
        assertThatThrownBy(() -> adapter.adapt(new Reply(
                Reply.Mode.IMMEDIATE,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of(
                        "headers", new RailixValue.ObjectValue(Map.of(
                                "x-bad", new RailixValue.NumberValue(BigDecimal.ONE)
                        ))
                )),
                new RailixValue.StringValue("ok"),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        )))
                .isInstanceOf(HttpProblemException.class)
                .hasMessage("reply.metadata.headers values must be strings or lists of strings");
    }
}
