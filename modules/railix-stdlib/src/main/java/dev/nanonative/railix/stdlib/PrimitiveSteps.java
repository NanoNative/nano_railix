package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntPredicate;

/** Small, stateless unary Steps usable as mapped graph nodes or in an ordered nested Step input. */
public final class PrimitiveSteps {
    private static final BigDecimal MIN_MILLIS = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal MAX_MILLIS = BigDecimal.valueOf(Long.MAX_VALUE);

    private PrimitiveSteps() {
    }

    /** Returns every built-in unary Step in stable catalog order. */
    public static List<StepDefinition> definitions() {
        return List.of(
                primitive("text.lowercase", ValueShape.STRING, ValueShape.STRING, value ->
                        RailixValue.string(text(value).toLowerCase(Locale.ROOT))),
                primitive("text.uppercase", ValueShape.STRING, ValueShape.STRING, value ->
                        RailixValue.string(text(value).toUpperCase(Locale.ROOT))),
                primitive("text.trim", ValueShape.STRING, ValueShape.STRING, value ->
                        RailixValue.string(text(value).strip())),
                primitive("text.normalize-space", ValueShape.STRING, ValueShape.STRING, value ->
                        RailixValue.string(normalizeSpace(text(value)))),
                primitive("text.normalize-nfc", ValueShape.STRING, ValueShape.STRING, value ->
                        RailixValue.string(Normalizer.normalize(text(value), Normalizer.Form.NFC))),
                primitive("text.length", ValueShape.STRING, ValueShape.NUMBER, value ->
                        RailixValue.number(textLength(value))),
                primitive("text.is-empty", ValueShape.STRING, ValueShape.BOOLEAN, value ->
                        RailixValue.bool(text(value).isEmpty())),
                textPredicate("text.contains", "needle", String::contains),
                textPredicate("text.starts-with", "prefix", String::startsWith),
                textPredicate("text.ends-with", "suffix", String::endsWith),
                textToNumber(),
                primitive("number.floor", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).setScale(0, RoundingMode.FLOOR))),
                primitive("number.ceil", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).setScale(0, RoundingMode.CEILING))),
                primitive("number.round", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).setScale(0, RoundingMode.HALF_UP))),
                primitive("number.abs", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).abs())),
                primitive("number.negate", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).signum() == 0
                                ? BigDecimal.ZERO : number(value).negate())),
                primitive("number.sign", ValueShape.NUMBER, ValueShape.NUMBER, value ->
                        RailixValue.number(number(value).signum())),
                comparison("number.greater-than", "Greater Than", value -> value > 0, "gt"),
                comparison(
                        "number.greater-or-equal",
                        "Greater Or Equal",
                        value -> value >= 0,
                        "gte",
                        "ge"
                ),
                comparison("number.less-than", "Less Than", value -> value < 0, "lt"),
                comparison(
                        "number.less-or-equal",
                        "Less Or Equal",
                        value -> value <= 0,
                        "lte",
                        "le"
                ),
                primitive("boolean.not", ValueShape.BOOLEAN, ValueShape.BOOLEAN, value ->
                        RailixValue.bool(!((RailixValue.BooleanValue) value).value())),
                primitive("list.size", ValueShape.ARRAY, ValueShape.NUMBER, value ->
                        RailixValue.number(((RailixValue.ArrayValue) value).values().size())),
                primitive("list.is-empty", ValueShape.ARRAY, ValueShape.BOOLEAN, value ->
                        RailixValue.bool(((RailixValue.ArrayValue) value).values().isEmpty())),
                listReverse(),
                listSum(),
                listExtreme("list.min", true),
                listExtreme("list.max", false),
                listPercentile(),
                utcMillis(),
                numberToText(),
                primitive("boolean.to-text", ValueShape.BOOLEAN, ValueShape.STRING, value ->
                        RailixValue.string(Boolean.toString(((RailixValue.BooleanValue) value).value()))),
                primitive("boolean.to-number", ValueShape.BOOLEAN, ValueShape.NUMBER, value ->
                        RailixValue.number(((RailixValue.BooleanValue) value).value() ? 1 : 0)),
                valueWrapList(),
                valueToJson(),
                valuePredicate("value.equals", "Equals", PrimitiveSteps::equalValues, "eq"),
                valuePredicate(
                        "value.not-equals",
                        "Not Equals",
                        (value, expected) -> !equalValues(value, expected),
                        "neq",
                        "ne"
                )
        );
    }

    private static StepDefinition primitive(
            final String id,
            final ValueShape input,
            final ValueShape output,
            final Function<RailixValue, RailixValue> operation
    ) {
        return primitive(
                id,
                input,
                ValueRefinement.none(),
                output,
                ValueRefinement.none(),
                operation
        );
    }

    private static StepDefinition primitive(
            final String id,
            final ValueShape input,
            final ValueRefinement inputRefinement,
            final ValueShape output,
            final ValueRefinement outputRefinement,
            final Function<RailixValue, RailixValue> operation
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", input, inputRefinement)
                .returns("value", output, outputRefinement)
                .run(step -> StepResult.outcome("ok").output("value", operation.apply(step.value("value"))));
    }

    private static StepDefinition listReverse() {
        return primitive(
                "list.reverse",
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                value -> {
                    final List<RailixValue> source = ((RailixValue.ArrayValue) value).values();
                    final List<RailixValue> reversed = new ArrayList<>(source.size());
                    for (int index = source.size() - 1; index >= 0; index--) {
                        reversed.add(source.get(index));
                    }
                    return RailixValue.array(reversed);
                }
        );
    }

    private static StepDefinition numberToText() {
        return primitive(
                "number.to-text",
                ValueShape.NUMBER,
                ValueRefinement.canonical(),
                ValueShape.STRING,
                ValueRefinement.canonical(),
                value -> {
                    final BigDecimal source = number(value);
                    final BigDecimal canonical = source.signum() == 0
                            ? BigDecimal.ZERO
                            : source.stripTrailingZeros();
                    return RailixValue.string(canonical.toPlainString());
                }
        );
    }

    private static StepDefinition valueWrapList() {
        return primitive(
                "value.wrap-list",
                ValueShape.ANY,
                ValueRefinement.canonical().withMaxDepth(63),
                ValueShape.ARRAY,
                ValueRefinement.canonical().withMaxDepth(64),
                value -> RailixValue.array(List.of(value))
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
                value -> RailixValue.string(RailixJson.write(value))
        );
    }

    private static StepDefinition comparison(
            final String id,
            final String displayName,
            final IntPredicate operation,
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
                .run(step -> StepResult.outcome("ok").output("value", RailixValue.bool(operation.test(
                        number(step.value("value")).compareTo(number(step.value("than")))
                ))));
    }

    private static StepDefinition textPredicate(
            final String id,
            final String input,
            final BiPredicate<String, String> operation
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.BOOLEAN)
                .input(input, StepDefinition.Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string("")))
                .run(step -> StepResult.outcome("ok").output(
                        "value",
                        RailixValue.bool(operation.test(
                                text(step.value("value")),
                                text(step.value(input))
                        ))
                ));
    }

    private static StepDefinition valuePredicate(
            final String id,
            final String displayName,
            final BiPredicate<RailixValue, RailixValue> operation,
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
                .run(step -> StepResult.outcome("ok").output(
                        "value",
                        RailixValue.bool(operation.test(
                                step.value("value"),
                                step.value("expected")
                        ))
                ));
    }

    private static boolean equalValues(final RailixValue first, final RailixValue second) {
        final List<RailixValue> firstValues = new ArrayList<>(List.of(first));
        final List<RailixValue> secondValues = new ArrayList<>(List.of(second));
        for (int index = 0; index < firstValues.size(); index++) {
            final RailixValue left = firstValues.get(index);
            final RailixValue right = secondValues.get(index);
            if (ValueShape.shapeOf(left) != ValueShape.shapeOf(right)) {
                return false;
            }
            switch (left) {
                case RailixValue.NullValue ignored -> {
                }
                case RailixValue.BooleanValue value -> {
                    if (value.value() != ((RailixValue.BooleanValue) right).value()) {
                        return false;
                    }
                }
                case RailixValue.NumberValue value -> {
                    if (value.value().compareTo(((RailixValue.NumberValue) right).value()) != 0) {
                        return false;
                    }
                }
                case RailixValue.StringValue value -> {
                    if (!value.value().equals(((RailixValue.StringValue) right).value())) {
                        return false;
                    }
                }
                case RailixValue.ArrayValue value -> {
                    final List<RailixValue> other = ((RailixValue.ArrayValue) right).values();
                    if (value.values().size() != other.size()) {
                        return false;
                    }
                    firstValues.addAll(value.values());
                    secondValues.addAll(other);
                }
                case RailixValue.ObjectValue value -> {
                    final var other = ((RailixValue.ObjectValue) right).values();
                    if (!value.values().keySet().equals(other.keySet())) {
                        return false;
                    }
                    for (final String name : value.values().keySet()) {
                        firstValues.add(value.values().get(name));
                        secondValues.add(other.get(name));
                    }
                }
            }
        }
        return true;
    }

    private static StepDefinition textToNumber() {
        return StepDefinition.named("text.to-number", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.NUMBER)
                .outcome("invalid")
                .run(step -> {
                    final String source = ((RailixValue.StringValue) step.value("value")).value();
                    if (source.isEmpty()
                            || source.charAt(source.length() - 1) < '0'
                            || source.charAt(source.length() - 1) > '9'
                            || (source.charAt(0) != '-'
                                    && (source.charAt(0) < '0' || source.charAt(0) > '9'))) {
                        return StepResult.outcome("invalid");
                    }
                    final RailixData.Result parsed = RailixData.normalize(
                            RailixData.Format.JSON,
                            source.getBytes(StandardCharsets.UTF_8)
                    );
                    if (parsed instanceof RailixData.Normalized normalized
                            && normalized.value() instanceof RailixValue.NumberValue number) {
                        return StepResult.outcome("ok").output("value", number);
                    }
                    return StepResult.outcome("invalid");
                });
    }

    private static StepDefinition utcMillis() {
        return StepDefinition.named("date.is-utc-millis", "1")
                .displayName("Is UTC Millis")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.BOOLEAN)
                .run(step -> StepResult.outcome("ok").output(
                        "value",
                        RailixValue.bool(isUtcMillis(number(step.value("value"))))
                ));
    }

    private static StepDefinition listSum() {
        return collection("list.sum", false, numbers -> {
            BigDecimal sum = BigDecimal.ZERO;
            for (final RailixValue.NumberValue number : numbers) {
                sum = sum.add(number.value());
            }
            if (!RailixData.fitsCanonicalNumber(sum)) {
                return StepResult.outcome("invalid");
            }
            return StepResult.outcome("ok").output("value", RailixValue.number(sum));
        });
    }

    private static StepDefinition listExtreme(final String id, final boolean minimum) {
        return collection(id, true, numbers -> {
            RailixValue.NumberValue result = numbers.getFirst();
            for (final RailixValue.NumberValue candidate : numbers) {
                final int comparison = candidate.value().compareTo(result.value());
                if (minimum ? comparison < 0 : comparison > 0) {
                    result = candidate;
                }
            }
            return StepResult.outcome("ok").output("value", result);
        });
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
                .run(step -> {
                    final Optional<List<RailixValue.NumberValue>> numbers = numbers(step.value("value"));
                    if (numbers.isEmpty()) {
                        return StepResult.outcome("invalid");
                    }
                    if (numbers.get().isEmpty()) {
                        return StepResult.outcome("empty");
                    }
                    final List<RailixValue.NumberValue> ordered = new ArrayList<>(numbers.get());
                    ordered.sort(Comparator.comparing(RailixValue.NumberValue::value));
                    final BigDecimal percentile =
                            ((RailixValue.NumberValue) step.value("percentile")).value();
                    final int rank = percentile.signum() == 0
                            ? 0
                            : percentile.multiply(BigDecimal.valueOf(ordered.size()))
                                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                                    .intValueExact() - 1;
                    return StepResult.outcome("ok").output("value", ordered.get(rank));
                });
    }

    private static StepDefinition collection(
            final String id,
            final boolean emptyAware,
            final Function<List<RailixValue.NumberValue>, StepResult> operation
    ) {
        final StepDefinition.Builder builder = StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ARRAY)
                .returns("value", ValueShape.NUMBER);
        if (emptyAware) {
            builder.outcome("empty");
        }
        return builder.outcome("invalid").run(step -> {
            final Optional<List<RailixValue.NumberValue>> numbers = numbers(step.value("value"));
            if (numbers.isEmpty()) {
                return StepResult.outcome("invalid");
            }
            if (emptyAware && numbers.get().isEmpty()) {
                return StepResult.outcome("empty");
            }
            return operation.apply(numbers.get());
        });
    }

    private static Optional<List<RailixValue.NumberValue>> numbers(final RailixValue value) {
        final List<RailixValue.NumberValue> numbers = new ArrayList<>();
        for (final RailixValue item : ((RailixValue.ArrayValue) value).values()) {
            if (!(item instanceof RailixValue.NumberValue number)) {
                return Optional.empty();
            }
            numbers.add(number);
        }
        return Optional.of(numbers);
    }

    private static BigDecimal number(final RailixValue value) {
        return ((RailixValue.NumberValue) value).value();
    }

    private static String text(final RailixValue value) {
        return ((RailixValue.StringValue) value).value();
    }

    private static int textLength(final RailixValue value) {
        final String source = text(value);
        return source.codePointCount(0, source.length());
    }

    private static String normalizeSpace(final String source) {
        final StringBuilder normalized = new StringBuilder(source.length());
        boolean whitespace = false;
        for (int offset = 0; offset < source.length();) {
            final int codePoint = source.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                whitespace = !normalized.isEmpty();
            } else {
                if (whitespace) {
                    normalized.append(' ');
                }
                normalized.appendCodePoint(codePoint);
                whitespace = false;
            }
        }
        return normalized.toString();
    }

    private static boolean isUtcMillis(final BigDecimal value) {
        final BigDecimal integer = value.stripTrailingZeros();
        return integer.scale() <= 0
                && integer.compareTo(MIN_MILLIS) >= 0
                && integer.compareTo(MAX_MILLIS) <= 0;
    }
}
