package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeTest {

    @Test
    void shouldCopyRefsAndRetainEnvelopeFields() {
        final Map<String, RailixValue> refs = new HashMap<>(Map.of(
                "file", new RailixValue.FileRef("file-1", "/tmp/invoice.pdf", "application/pdf", "sha256:abc", 12L)
        ));

        final Envelope envelope = new Envelope(
                "manual-ui",
                "manual",
                new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("Yuna"))),
                new RailixValue.ObjectValue(Map.of("traceId", new RailixValue.StringValue("trace-1"))),
                refs,
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );

        refs.clear();

        assertThat(envelope.source()).isEqualTo("manual-ui");
        assertThat(envelope.protocol()).isEqualTo("manual");
        assertThat(envelope.refs()).containsKey("file");
        assertThatThrownBy(() -> envelope.refs().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullAndBlankEnvelopeCoreFields() {
        assertThatThrownBy(() -> new Envelope(
                " ",
                "manual",
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");

        assertThatThrownBy(() -> new Envelope(
                "manual-ui",
                "manual",
                null,
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void shouldRejectIncoherentReplyChannelStates() {
        assertThatThrownBy(() -> new Envelope.ReplyChannel(false, List.of(Reply.Mode.IMMEDIATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported=false");

        assertThatThrownBy(() -> new Envelope.ReplyChannel(true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported=true");
    }
}
