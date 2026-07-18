package dev.nanonative.railix.railixstdmetrics;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardMetricsStepProviderTest {

    @Test
    void shouldEmitMetricFromPayloadAndPatchContext() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();
        final Envelope envelope = envelope(
                new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                "payload-source",
                "success"
        );
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardMetricsStepProvider.METRIC_EMIT_USE).orElseThrow();
        final List<EmittedMetric> emittedMetrics = new ArrayList<>();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                (name, value, labels) -> emittedMetrics.add(new EmittedMetric(name, value, labels))
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.metric.name")))
                .isEqualTo(new RailixValue.StringValue("railix.metric.value"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.metric.unit")))
                .isEqualTo(new RailixValue.StringValue("count"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.metric.emitted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(emittedMetrics).containsExactly(new EmittedMetric(
                "railix.metric.value",
                new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                Map.of("source", "payload-source", "result", "success")
        ));
    }

    @Test
    void shouldPreferContextMetricValueAndLabelsOverPayload() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();
        final Envelope envelope = envelope(
                new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                "payload-source",
                "success"
        );
        final InMemoryContextDoc context = (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.metric.value"),
                        new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(11)))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.metric.source"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ctx-source"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.metric.result"),
                        new Patch.LiteralSource(new RailixValue.StringValue("failure"))
                )
        ));
        final Step step = provider.resolve(StandardMetricsStepProvider.METRIC_EMIT_USE).orElseThrow();
        final List<EmittedMetric> emittedMetrics = new ArrayList<>();

        step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                (name, value, labels) -> emittedMetrics.add(new EmittedMetric(name, value, labels))
        ));

        assertThat(emittedMetrics).containsExactly(new EmittedMetric(
                "railix.metric.value",
                new RailixValue.NumberValue(BigDecimal.valueOf(11)),
                Map.of("source", "ctx-source", "result", "failure")
        ));
    }

    @Test
    void shouldRejectMissingMetricValue() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();
        final Envelope envelope = envelope(RailixValue.NULL, "payload-source", "success");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardMetricsStepProvider.METRIC_EMIT_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                (name, value, labels) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MetricEmit requires ctx.metric.value or payload.metric.value");
    }

    @Test
    void shouldRejectNonNumericMetricValue() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();
        final Envelope envelope = envelope(new RailixValue.StringValue("seven"), "payload-source", "success");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardMetricsStepProvider.METRIC_EMIT_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                (name, value, labels) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MetricEmit value must be numeric");
    }

    @Test
    void shouldRejectBlankMetricSource() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();
        final Envelope envelope = envelope(
                new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                "   ",
                "success"
        );
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardMetricsStepProvider.METRIC_EMIT_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                (name, value, labels) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payload.metric.source must not be blank");
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardMetricsStepProvider provider = new StandardMetricsStepProvider();

        assertThat(provider.resolve("railix.std.metrics.Missing")).isEmpty();
    }

    private static Envelope envelope(final RailixValue value, final String source, final String result) {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "metric", new RailixValue.ObjectValue(Map.ofEntries(
                                Map.entry("value", value),
                                Map.entry("source", new RailixValue.StringValue(source)),
                                Map.entry("result", new RailixValue.StringValue(result))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private record EmittedMetric(String name, RailixValue value, Map<String, String> labels) {
    }
}
