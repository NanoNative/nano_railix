package dev.nanonative.railix.railixstdmetrics;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.ContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal standard metrics pack provider for the first honest custom metric slice.
 */
public final class StandardMetricsStepProvider implements StepProvider {

    static final String METRIC_EMIT_USE = "railix.std.metrics.MetricEmit";

    private static final String METRIC_NAME = "railix.metric.value";
    private static final String METRIC_TYPE = "gauge";
    private static final String METRIC_UNIT = "count";

    private static final RailixPath CONTEXT_VALUE_PATH = RailixPath.parse("ctx.metric.value");
    private static final RailixPath PAYLOAD_VALUE_PATH = RailixPath.parse("payload.metric.value");
    private static final RailixPath CONTEXT_SOURCE_PATH = RailixPath.parse("ctx.metric.source");
    private static final RailixPath PAYLOAD_SOURCE_PATH = RailixPath.parse("payload.metric.source");
    private static final RailixPath CONTEXT_RESULT_PATH = RailixPath.parse("ctx.metric.result");
    private static final RailixPath PAYLOAD_RESULT_PATH = RailixPath.parse("payload.metric.result");
    private static final RailixPath EMITTED_NAME_PATH = RailixPath.parse("ctx.metric.name");
    private static final RailixPath EMITTED_UNIT_PATH = RailixPath.parse("ctx.metric.unit");
    private static final RailixPath EMITTED_FLAG_PATH = RailixPath.parse("ctx.metric.emitted");

    @Override
    public Optional<Step> resolve(final String use) {
        return METRIC_EMIT_USE.equals(use) ? Optional.of(metricEmitStep()) : Optional.empty();
    }

    @Override
    public List<String> supportedUses() {
        return List.of(METRIC_EMIT_USE);
    }

    private static Step metricEmitStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        METRIC_EMIT_USE,
                        "0.1.0",
                        "MetricEmit",
                        "Emits the first standard metric sample into the run signal log.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("value", "number", CONTEXT_VALUE_PATH.toString(), true, List.of()),
                                new StepContract.Port("source", "string", CONTEXT_SOURCE_PATH.toString(), false, List.of()),
                                new StepContract.Port("result", "string", CONTEXT_RESULT_PATH.toString(), false, List.of())
                        ),
                        List.of(
                                new StepContract.Port("name", "string", EMITTED_NAME_PATH.toString(), false, List.of()),
                                new StepContract.Port("unit", "string", EMITTED_UNIT_PATH.toString(), false, List.of()),
                                new StepContract.Port("emitted", "bool", EMITTED_FLAG_PATH.toString(), false, List.of())
                        ),
                        Map.of("ok", new StepContract.Outcome("Metric emitted")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of(
                                new StepContract.MetricDefinition(METRIC_NAME, METRIC_TYPE, METRIC_UNIT, List.of("source", "result"))
                        )),
                        Map.of("editor", new RailixValue.StringValue("metrics.emit"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final RailixValue.NumberValue value = metricValue(input.context());
                final Map<String, String> labels = metricLabels(input.context());
                input.metrics().emit(METRIC_NAME, value, labels);
                return new Result(
                        "ok",
                        List.of(
                                new Patch.Set(EMITTED_NAME_PATH, new Patch.LiteralSource(new RailixValue.StringValue(METRIC_NAME))),
                                new Patch.Set(EMITTED_UNIT_PATH, new Patch.LiteralSource(new RailixValue.StringValue(METRIC_UNIT))),
                                new Patch.Set(EMITTED_FLAG_PATH, new Patch.LiteralSource(new RailixValue.BoolValue(true)))
                        )
                );
            }
        };
    }

    private static RailixValue.NumberValue metricValue(final ContextDoc context) {
        final RailixValue value = context.get(CONTEXT_VALUE_PATH) != RailixValue.NULL
                ? context.get(CONTEXT_VALUE_PATH)
                : context.get(PAYLOAD_VALUE_PATH);
        if (!(value instanceof RailixValue.NumberValue numberValue)) {
            if (value == RailixValue.NULL) {
                throw new IllegalStateException("MetricEmit requires ctx.metric.value or payload.metric.value");
            }
            throw new IllegalStateException("MetricEmit value must be numeric");
        }
        final BigDecimal numericValue = numberValue.value();
        if (numericValue == null) {
            throw new IllegalStateException("MetricEmit value must be numeric");
        }
        return numberValue;
    }

    private static Map<String, String> metricLabels(final ContextDoc context) {
        final Map<String, String> labels = new LinkedHashMap<>();
        optionalNonBlankString(context, CONTEXT_SOURCE_PATH)
                .or(() -> optionalNonBlankString(context, PAYLOAD_SOURCE_PATH))
                .ifPresent(source -> labels.put("source", source));
        optionalNonBlankString(context, CONTEXT_RESULT_PATH)
                .or(() -> optionalNonBlankString(context, PAYLOAD_RESULT_PATH))
                .ifPresent(result -> labels.put("result", result));
        return Map.copyOf(labels);
    }

    private static Optional<String> optionalNonBlankString(final ContextDoc context, final RailixPath path) {
        final RailixValue value = context.get(path);
        if (value == RailixValue.NULL) {
            return Optional.empty();
        }
        if (!(value instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException(path + " must be a string");
        }
        final String normalized = stringValue.value().trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException(path + " must not be blank");
        }
        return Optional.of(normalized);
    }
}
