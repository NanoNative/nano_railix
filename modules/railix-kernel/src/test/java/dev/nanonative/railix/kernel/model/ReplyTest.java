package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplyTest {

    @Test
    void shouldUseNullObjectRefsInNoneReply() {
        final Reply reply = Reply.none();

        assertThat(reply.mode()).isEqualTo(Reply.Mode.NONE);
        assertThat(reply.file()).isSameAs(RailixValue.NULL);
        assertThat(reply.stream()).isSameAs(RailixValue.NULL);
        assertThat(reply.session()).isSameAs(RailixValue.NULL);
        assertThat(reply.deferred()).isSameAs(RailixValue.NULL);
    }

    @Test
    void shouldRejectWrongReplyRefType() {
        assertThatThrownBy(() -> new Reply(
                Reply.Mode.FILE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                new RailixValue.StreamRef("stream-1", "json", Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file must be FileRef");
    }

    @Test
    void shouldRejectIncoherentReplyModeShapes() {
        assertThatThrownBy(() -> new Reply(
                Reply.Mode.NONE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.StringValue("payload"),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mode.NONE");

        assertThatThrownBy(() -> new Reply(
                Reply.Mode.FILE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mode.FILE");

        assertThatThrownBy(() -> new Reply(
                Reply.Mode.FILE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                new RailixValue.FileRef("file-1", "/tmp/out.txt", "text/plain", "sha256:abc", 10L),
                RailixValue.NULL,
                new RailixValue.SessionRef("session-1", "http", Map.of()),
                RailixValue.NULL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only populate file");
    }

    @Test
    void shouldAllowCoherentReplyModesAndRejectTransportRefsForImmediateModes() {
        final RailixValue.FileRef fileRef = new RailixValue.FileRef("file-1", "/tmp/out.txt", "text/plain", "sha256:abc", 10L);
        final RailixValue.StreamRef streamRef = new RailixValue.StreamRef("stream-1", "json", Map.of());
        final RailixValue.SessionRef sessionRef = new RailixValue.SessionRef("session-1", "http", Map.of());
        final RailixValue.DeferredRef deferredRef = new RailixValue.DeferredRef("deferred-1", "ctx.status", Map.of());

        assertThat(new Reply(
                Reply.Mode.IMMEDIATE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.StringValue("ok"),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        ).mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(new Reply(
                Reply.Mode.BROADCAST,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of("topic", new RailixValue.StringValue("orders"))),
                new RailixValue.StringValue("published"),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        ).mode()).isEqualTo(Reply.Mode.BROADCAST);
        assertThat(new Reply(
                Reply.Mode.FILE,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                fileRef,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        ).file()).isEqualTo(fileRef);
        assertThat(new Reply(
                Reply.Mode.STREAM,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                streamRef,
                RailixValue.NULL,
                RailixValue.NULL
        ).stream()).isEqualTo(streamRef);
        assertThat(new Reply(
                Reply.Mode.SESSION,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                sessionRef,
                RailixValue.NULL
        ).session()).isEqualTo(sessionRef);
        assertThat(new Reply(
                Reply.Mode.DEFERRED,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of()),
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL,
                deferredRef
        ).deferred()).isEqualTo(deferredRef);

        assertThatThrownBy(() -> new Reply(
                Reply.Mode.IMMEDIATE,
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.StringValue("ok"),
                fileRef,
                RailixValue.NULL,
                RailixValue.NULL,
                RailixValue.NULL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mode.IMMEDIATE");
    }
}
