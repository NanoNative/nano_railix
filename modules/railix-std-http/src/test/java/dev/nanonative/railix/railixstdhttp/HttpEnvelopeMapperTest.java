package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.RailixValue;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpEnvelopeMapperTest {

    private final HttpEnvelopeMapper mapper = new HttpEnvelopeMapper();

    @Test
    void shouldMapJsonRequestIntoGenericEnvelope() {
        final Envelope envelope = mapper.map(
                "POST",
                URI.create("/orders?tag=alpha&tag=beta"),
                new InetSocketAddress("127.0.0.1", 8080),
                new InetSocketAddress("127.0.0.1", 58321),
                Map.of(
                        "content-type", List.of("application/json; charset=utf-8"),
                        "x-request-id", List.of("req-1", "req-2")
                ),
                "{\"orderId\":\"A-1\",\"priority\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        assertThat(envelope.protocol()).isEqualTo("http");
        assertThat(envelope.source()).isEqualTo("http.127.0.0.1:8080");
        assertThat(envelope.replyChannel().modes()).containsExactly(dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE);
        assertThat(envelope.payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "orderId", new RailixValue.StringValue("A-1"),
                "priority", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(2))
        )));
        assertThat(envelope.metadata().values().get("method")).isEqualTo(new RailixValue.StringValue("POST"));
        assertThat(envelope.metadata().values().get("path")).isEqualTo(new RailixValue.StringValue("/orders"));
        assertThat(((RailixValue.ObjectValue) envelope.metadata().values().get("query")).values().get("tag"))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("alpha"),
                        new RailixValue.StringValue("beta")
                )));
    }

    @Test
    void shouldPreserveLiteralPlusAndDecodePercentEncodedQueryValues() {
        final Envelope envelope = mapper.map(
                "POST",
                URI.create("/search?q=a+b&q=a%2Bb&q=a%20b&tag=x&tag=y"),
                new InetSocketAddress("127.0.0.1", 8080),
                new InetSocketAddress("127.0.0.1", 58321),
                Map.of(),
                new byte[0]
        );

        assertThat(((RailixValue.ObjectValue) envelope.metadata().values().get("query")).values().get("q"))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("a+b"),
                        new RailixValue.StringValue("a+b"),
                        new RailixValue.StringValue("a b")
                )));
        assertThat(((RailixValue.ObjectValue) envelope.metadata().values().get("query")).values().get("tag"))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("x"),
                        new RailixValue.StringValue("y")
                )));
    }

    @Test
    void shouldRejectUnsupportedContentType() {
        assertThatThrownBy(() -> mapper.map(
                "POST",
                URI.create("/orders"),
                new InetSocketAddress("127.0.0.1", 8080),
                new InetSocketAddress("127.0.0.1", 58321),
                Map.of("content-type", List.of("text/plain")),
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ))
                .isInstanceOf(HttpProblemException.class)
                .hasMessage("HTTP request bodies require Content-Type application/json");
    }

    @Test
    void shouldRejectNonObjectJsonBodies() {
        assertThatThrownBy(() -> mapper.map(
                "POST",
                URI.create("/orders"),
                new InetSocketAddress("127.0.0.1", 8080),
                new InetSocketAddress("127.0.0.1", 58321),
                Map.of("content-type", List.of("application/json")),
                "[1,2,3]".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ))
                .isInstanceOf(HttpProblemException.class)
                .hasMessage("HTTP request body must decode to a JSON object");
    }
}
