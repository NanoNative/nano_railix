package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardHttpStepProviderTest {

    @Test
    void shouldNormalizeHttpEnvelopeIntoContext() {
        final StandardHttpStepProvider provider = new StandardHttpStepProvider();
        final Envelope envelope = httpEnvelope("http.local:8080", "http");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardHttpStepProvider.HTTP_TRIGGER_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("request");
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.kind")))
                .isEqualTo(new RailixValue.StringValue("http"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.protocol")))
                .isEqualTo(new RailixValue.StringValue("http"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.reply.modes")))
                .isEqualTo(new RailixValue.ListValue(List.of(new RailixValue.StringValue("immediate"))));
        assertThat(updatedContext.get(RailixPath.parse("ctx.http.request.method")))
                .isEqualTo(new RailixValue.StringValue("POST"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.http.request.path")))
                .isEqualTo(new RailixValue.StringValue("/orders"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.http.request.headers.content-type")))
                .isEqualTo(new RailixValue.StringValue("application/json"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.http.request.query.tag")))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("alpha"),
                        new RailixValue.StringValue("beta")
                )));
    }

    @Test
    void shouldRejectNonHttpProtocolEnvelope() {
        final StandardHttpStepProvider provider = new StandardHttpStepProvider();
        final Envelope envelope = httpEnvelope("manual.dev", "manual");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardHttpStepProvider.HTTP_TRIGGER_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HTTP trigger requires envelope.protocol=http");
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        assertThat(new StandardHttpStepProvider().resolve("railix.std.http.Missing")).isEmpty();
    }

    private static Envelope httpEnvelope(final String source, final String protocol) {
        return new Envelope(
                source,
                protocol,
                new RailixValue.ObjectValue(Map.of(
                        "orderId", new RailixValue.StringValue("A-1")
                )),
                new RailixValue.ObjectValue(Map.of(
                        "method", new RailixValue.StringValue("POST"),
                        "path", new RailixValue.StringValue("/orders"),
                        "headers", new RailixValue.ObjectValue(Map.of(
                                "content-type", new RailixValue.StringValue("application/json")
                        )),
                        "query", new RailixValue.ObjectValue(Map.of(
                                "tag", new RailixValue.ListValue(List.of(
                                        new RailixValue.StringValue("alpha"),
                                        new RailixValue.StringValue("beta")
                                ))
                        )),
                        "remoteAddress", new RailixValue.StringValue("127.0.0.1")
                )),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }
}
