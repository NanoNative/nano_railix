package dev.nanonative.railix.railixstdtrigger;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StandardTriggerStepProviderTest {

    @Test
    void shouldResolveManualTriggerStepAndNormalizeEnvelopeIntoContext() {
        final StandardTriggerStepProvider provider = new StandardTriggerStepProvider();
        final Envelope envelope = envelope("manual.dev", "manual");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardTriggerStepProvider.MANUAL_TRIGGER_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("triggered");
        assertThat(updatedContext.get(RailixPath.parse("ctx.payload"))).isEqualTo(envelope.payload());
        assertThat(updatedContext.get(RailixPath.parse("ctx.metadata"))).isEqualTo(envelope.metadata());
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.kind")))
                .isEqualTo(new RailixValue.StringValue("manual"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.protocol")))
                .isEqualTo(new RailixValue.StringValue("manual"));
        assertThat(step.contract().kind()).isEqualTo(dev.nanonative.railix.kernel.model.StepContract.Kind.TRIGGER);
        assertThat(step.contract().ui()).containsEntry("editor", new RailixValue.StringValue("trigger.manual"));
    }

    @Test
    void shouldResolveCliTriggerStepAndPersistReplyChannelModes() {
        final StandardTriggerStepProvider provider = new StandardTriggerStepProvider();
        final Envelope envelope = envelope("cli.dev", "cli");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardTriggerStepProvider.CLI_TRIGGER_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("triggered");
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.kind")))
                .isEqualTo(new RailixValue.StringValue("cli"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.source")))
                .isEqualTo(new RailixValue.StringValue("cli.dev"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.reply.supported")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(updatedContext.get(RailixPath.parse("ctx.trigger.reply.modes")))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("immediate"),
                        new RailixValue.StringValue("stream")
                )));
        assertThat(step.contract().id()).isEqualTo(StandardTriggerStepProvider.CLI_TRIGGER_USE);
        assertThat(step.contract().ui()).containsEntry("editor", new RailixValue.StringValue("trigger.cli"));
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardTriggerStepProvider provider = new StandardTriggerStepProvider();

        assertThat(provider.resolve("railix.std.trigger.MissingTrigger")).isEmpty();
    }

    private static Envelope envelope(final String source, final String protocol) {
        return new Envelope(
                source,
                protocol,
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of(
                        "actor", new RailixValue.StringValue("developer")
                )),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(
                        dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE,
                        dev.nanonative.railix.kernel.model.Reply.Mode.STREAM
                ))
        );
    }
}
