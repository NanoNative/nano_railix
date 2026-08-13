package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.List;

/** Small, stateless unary Steps usable as mapped graph nodes or in an ordered nested Step input. */
public final class PrimitiveSteps {
    private PrimitiveSteps() {
    }

    /** Returns every built-in unary Step in stable catalog order. */
    public static List<StepDefinition> definitions() {
        return List.of(
                primitive("text.lowercase", ValueShape.STRING, ValueShape.STRING,
                        PrimitiveStepHandlers.Lowercase.class),
                primitive("text.uppercase", ValueShape.STRING, ValueShape.STRING,
                        PrimitiveStepHandlers.Uppercase.class),
                primitive("text.trim", ValueShape.STRING, ValueShape.STRING,
                        PrimitiveStepHandlers.Trim.class),
                primitive("text.normalize-space", ValueShape.STRING, ValueShape.STRING,
                        PrimitiveStepHandlers.NormalizeSpace.class),
                primitive("text.normalize-nfc", ValueShape.STRING, ValueShape.STRING,
                        PrimitiveStepHandlers.NormalizeNfc.class),
                primitive("text.length", ValueShape.STRING, ValueShape.NUMBER,
                        PrimitiveStepHandlers.TextLength.class),
                primitive("text.is-empty", ValueShape.STRING, ValueShape.BOOLEAN,
                        PrimitiveStepHandlers.TextIsEmpty.class),
                textPredicate("text.contains", "needle", PrimitiveStepHandlers.Contains.class),
                textPredicate("text.starts-with", "prefix", PrimitiveStepHandlers.StartsWith.class),
                textPredicate("text.ends-with", "suffix", PrimitiveStepHandlers.EndsWith.class),
                textToNumber(),
                primitive("number.floor", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Floor.class),
                primitive("number.ceil", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Ceil.class),
                primitive("number.round", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Round.class),
                primitive("number.abs", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Absolute.class),
                primitive("number.negate", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Negate.class),
                primitive("number.sign", ValueShape.NUMBER, ValueShape.NUMBER,
                        PrimitiveStepHandlers.Sign.class),
                comparison("number.greater-than", "Greater Than",
                        PrimitiveStepHandlers.GreaterThan.class, "gt"),
                comparison(
                        "number.greater-or-equal",
                        "Greater Or Equal",
                        PrimitiveStepHandlers.GreaterOrEqual.class,
                        "gte",
                        "ge"
                ),
                comparison("number.less-than", "Less Than",
                        PrimitiveStepHandlers.LessThan.class, "lt"),
                comparison(
                        "number.less-or-equal",
                        "Less Or Equal",
                        PrimitiveStepHandlers.LessOrEqual.class,
                        "lte",
                        "le"
                ),
                primitive("boolean.not", ValueShape.BOOLEAN, ValueShape.BOOLEAN,
                        PrimitiveStepHandlers.BooleanNot.class),
                primitive("list.size", ValueShape.ARRAY, ValueShape.NUMBER,
                        PrimitiveStepHandlers.ListSize.class),
                primitive("list.is-empty", ValueShape.ARRAY, ValueShape.BOOLEAN,
                        PrimitiveStepHandlers.ListIsEmpty.class),
                listReverse(),
                listSum(),
                listExtreme("list.min", true),
                listExtreme("list.max", false),
                listPercentile(),
                utcMillis(),
                numberToText(),
                primitive("boolean.to-text", ValueShape.BOOLEAN, ValueShape.STRING,
                        PrimitiveStepHandlers.BooleanToText.class),
                primitive("boolean.to-number", ValueShape.BOOLEAN, ValueShape.NUMBER,
                        PrimitiveStepHandlers.BooleanToNumber.class),
                valueWrapList(),
                valueToJson(),
                valuePredicate("value.equals", "Equals",
                        PrimitiveStepHandlers.ValueEquals.class, "eq"),
                valuePredicate(
                        "value.not-equals",
                        "Not Equals",
                        PrimitiveStepHandlers.ValueNotEquals.class,
                        "neq",
                        "ne"
                )
        );
    }

    private static StepDefinition primitive(
            final String id,
            final ValueShape input,
            final ValueShape output,
            final Class<? extends StepHandler> implementation
    ) {
        return primitive(
                id,
                input,
                ValueRefinement.none(),
                output,
                ValueRefinement.none(),
                implementation
        );
    }

    private static StepDefinition primitive(
            final String id,
            final ValueShape input,
            final ValueRefinement inputRefinement,
            final ValueShape output,
            final ValueRefinement outputRefinement,
            final Class<? extends StepHandler> implementation
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", input, inputRefinement)
                .returns("value", output, outputRefinement)
                .run(implementation);
    }

    private static StepDefinition listReverse() {
        return primitive(
                "list.reverse",
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                PrimitiveStepHandlers.ListReverse.class
        );
    }

    private static StepDefinition numberToText() {
        return primitive(
                "number.to-text",
                ValueShape.NUMBER,
                ValueRefinement.canonical(),
                ValueShape.STRING,
                ValueRefinement.canonical(),
                PrimitiveStepHandlers.NumberToText.class
        );
    }

    private static StepDefinition valueWrapList() {
        return primitive(
                "value.wrap-list",
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(63),
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                PrimitiveStepHandlers.ValueWrapList.class
        );
    }

    private static StepDefinition valueToJson() {
        return primitive(
                "value.to-json",
                ValueShape.ANY,
                ValueRefinement.canonical()
                        .withMaxDepth(64)
                        .withMaxJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES),
                ValueShape.STRING,
                ValueRefinement.canonical().withMaxJsonBytes(
                        RailixData.DEFAULT_MAX_SOURCE_BYTES * 2 + 2
                ),
                PrimitiveStepHandlers.ValueToJson.class
        );
    }

    private static StepDefinition comparison(
            final String id,
            final String displayName,
            final Class<? extends StepHandler> implementation,
            final String... searchTerms
    ) {
        return StepDefinition.named(id, "1")
                .displayName(displayName)
                .searchTerms(searchTerms)
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .input("than", StepDefinition.Input.json(ValueShape.NUMBER)
                        .defaultValue(RailixValue.number(0)))
                .run(implementation);
    }

    private static StepDefinition textPredicate(
            final String id,
            final String input,
            final Class<? extends StepHandler> implementation
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.BOOLEAN)
                .input(input, StepDefinition.Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("")))
                .run(implementation);
    }

    private static StepDefinition valuePredicate(
            final String id,
            final String displayName,
            final Class<? extends StepHandler> implementation,
            final String... searchTerms
    ) {
        return StepDefinition.named(id, "1")
                .displayName(displayName)
                .searchTerms(searchTerms)
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .input("expected", StepDefinition.Input.json(ValueShape.ANY)
                        .defaultValue(RailixValue.nullValue()))
                .run(implementation);
    }

    private static StepDefinition textToNumber() {
        return StepDefinition.named("text.to-number", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .outcome("invalid")
                .run(PrimitiveStepHandlers.TextToNumber.class);
    }

    private static StepDefinition utcMillis() {
        return StepDefinition.named("date.is-utc-millis", "1")
                .displayName("Is UTC Millis")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .run(PrimitiveStepHandlers.UtcMillis.class);
    }

    private static StepDefinition listSum() {
        return collection("list.sum", false, PrimitiveStepHandlers.ListSum.class);
    }

    private static StepDefinition listExtreme(final String id, final boolean minimum) {
        return minimum
                ? collection(id, true, PrimitiveStepHandlers.ListMinimum.class)
                : collection(id, true, PrimitiveStepHandlers.ListMaximum.class);
    }

    private static StepDefinition listPercentile() {
        return StepDefinition.named("list.percentile", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ARRAY)
                .returns("value", ValueShape.NUMBER)
                .input("percentile", StepDefinition.Input.json(ValueShape.NUMBER)
                        .defaultValue(RailixValue.number(95))
                        .between(RailixValue.number(0), RailixValue.number(100)))
                .outcome("empty")
                .outcome("invalid")
                .run(PrimitiveStepHandlers.ListPercentile.class);
    }

    private static StepDefinition collection(
            final String id,
            final boolean emptyAware,
            final Class<? extends StepHandler> implementation
    ) {
        final StepDefinition.Builder builder = StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ARRAY)
                .returns("value", ValueShape.NUMBER);
        if (emptyAware) {
            builder.outcome("empty");
        }
        return builder.outcome("invalid").run(implementation);
    }

}
