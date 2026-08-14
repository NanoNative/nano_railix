package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
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
import java.util.function.Function;

/** Stateless primitive implementations kept separate from Creator catalog metadata. */
public final class PrimitiveStepHandlers {
    private static final BigDecimal MIN_MILLIS = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal MAX_MILLIS = BigDecimal.valueOf(Long.MAX_VALUE);

    private PrimitiveStepHandlers() {
    }

    public static final class Lowercase implements StepHandler {
        public Lowercase() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.string(text(value).toLowerCase(Locale.ROOT)));
        }
    }

    public static final class Uppercase implements StepHandler {
        public Uppercase() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.string(text(value).toUpperCase(Locale.ROOT)));
        }
    }

    public static final class Trim implements StepHandler {
        public Trim() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.string(text(value).strip()));
        }
    }

    public static final class NormalizeSpace implements StepHandler {
        public NormalizeSpace() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.string(normalizeSpace(text(value))));
        }
    }

    public static final class NormalizeNfc implements StepHandler {
        public NormalizeNfc() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.string(Normalizer.normalize(text(value), Normalizer.Form.NFC)));
        }
    }

    public static final class TextLength implements StepHandler {
        public TextLength() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(textLength(value)));
        }
    }

    public static final class TextIsEmpty implements StepHandler {
        public TextIsEmpty() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.bool(text(value).isEmpty()));
        }
    }

    public static final class Contains implements StepHandler {
        public Contains() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, text(input.value("value")).contains(text(input.value("needle"))));
        }
    }

    public static final class StartsWith implements StepHandler {
        public StartsWith() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, text(input.value("value")).startsWith(text(input.value("prefix"))));
        }
    }

    public static final class EndsWith implements StepHandler {
        public EndsWith() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, text(input.value("value")).endsWith(text(input.value("suffix"))));
        }
    }

    public static final class TextToNumber implements StepHandler {
        public TextToNumber() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final String source = text(input.value("value"));
            if (source.isEmpty()
                    || source.charAt(source.length() - 1) < '0'
                    || source.charAt(source.length() - 1) > '9'
                    || source.charAt(0) != '-' && (source.charAt(0) < '0' || source.charAt(0) > '9')) {
                return StepResult.outcome("invalid");
            }
            final RailixData.Result parsed = RailixData.normalize(
                    RailixData.Format.JSON,
                    source.getBytes(StandardCharsets.UTF_8)
            );
            return parsed instanceof RailixData.Normalized normalized
                    && normalized.value() instanceof RailixValue.NumberValue number
                    ? StepResult.outcome("ok").output("value", number)
                    : StepResult.outcome("invalid");
        }
    }

    public static final class Floor implements StepHandler {
        public Floor() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(number(value).setScale(0, RoundingMode.FLOOR)));
        }
    }

    public static final class Ceil implements StepHandler {
        public Ceil() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(number(value).setScale(0, RoundingMode.CEILING)));
        }
    }

    public static final class Round implements StepHandler {
        public Round() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(number(value).setScale(0, RoundingMode.HALF_UP)));
        }
    }

    public static final class Absolute implements StepHandler {
        public Absolute() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(number(value).abs()));
        }
    }

    public static final class Negate implements StepHandler {
        public Negate() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(
                    number(value).signum() == 0 ? BigDecimal.ZERO : number(value).negate()
            ));
        }
    }

    public static final class Sign implements StepHandler {
        public Sign() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(number(value).signum()));
        }
    }

    public static final class GreaterThan implements StepHandler {
        public GreaterThan() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, comparison(input) > 0);
        }
    }

    public static final class GreaterOrEqual implements StepHandler {
        public GreaterOrEqual() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, comparison(input) >= 0);
        }
    }

    public static final class LessThan implements StepHandler {
        public LessThan() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, comparison(input) < 0);
        }
    }

    public static final class LessOrEqual implements StepHandler {
        public LessOrEqual() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, comparison(input) <= 0);
        }
    }

    public static final class BooleanNot implements StepHandler {
        public BooleanNot() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.bool(!((RailixValue.BooleanValue) value).value()));
        }
    }

    public static final class ListSize implements StepHandler {
        public ListSize() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.number(((RailixValue.ArrayValue) value).values().size()));
        }
    }

    public static final class ListIsEmpty implements StepHandler {
        public ListIsEmpty() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, value -> RailixValue.bool(((RailixValue.ArrayValue) value).values().isEmpty()));
        }
    }

    public static final class ListReverse implements StepHandler {
        public ListReverse() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final List<RailixValue> source = ((RailixValue.ArrayValue) input.value("value")).values();
            final List<RailixValue> reversed = new ArrayList<>(source.size());
            for (int index = source.size() - 1; index >= 0; index--) {
                reversed.add(source.get(index));
            }
            return value(RailixValue.array(reversed));
        }
    }

    public static final class ListSum implements StepHandler {
        public ListSum() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final Optional<List<RailixValue.NumberValue>> values = numbers(input.value("value"));
            if (values.isEmpty()) {
                return StepResult.outcome("invalid");
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (final RailixValue.NumberValue number : values.get()) {
                sum = sum.add(number.value());
            }
            return RailixData.fitsCanonicalNumber(sum)
                    ? value(RailixValue.number(sum))
                    : StepResult.outcome("invalid");
        }
    }

    public static final class ListMinimum implements StepHandler {
        public ListMinimum() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return listExtreme(input, true);
        }
    }

    public static final class ListMaximum implements StepHandler {
        public ListMaximum() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return listExtreme(input, false);
        }
    }

    public static final class ListPercentile implements StepHandler {
        public ListPercentile() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final Optional<List<RailixValue.NumberValue>> values = numbers(input.value("value"));
            if (values.isEmpty()) {
                return StepResult.outcome("invalid");
            }
            if (values.get().isEmpty()) {
                return StepResult.outcome("empty");
            }
            final List<RailixValue.NumberValue> ordered = new ArrayList<>(values.get());
            ordered.sort(Comparator.comparing(RailixValue.NumberValue::value));
            final BigDecimal percentile = number(input.value("percentile"));
            final int rank = percentile.signum() == 0
                    ? 0
                    : percentile.multiply(BigDecimal.valueOf(ordered.size()))
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
                            .intValueExact() - 1;
            return value(ordered.get(rank));
        }
    }

    public static final class UtcMillis implements StepHandler {
        public UtcMillis() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final BigDecimal integer = number(input.value("value")).stripTrailingZeros();
            return predicate(input, integer.scale() <= 0
                    && integer.compareTo(MIN_MILLIS) >= 0
                    && integer.compareTo(MAX_MILLIS) <= 0);
        }
    }

    public static final class NumberToText implements StepHandler {
        public NumberToText() {
        }

        @Override
        public StepResult run(final StepInput input) {
            final BigDecimal source = number(input.value("value"));
            final BigDecimal canonical = source.signum() == 0 ? BigDecimal.ZERO : source.stripTrailingZeros();
            return value(RailixValue.string(canonical.toPlainString()));
        }
    }

    public static final class BooleanToText implements StepHandler {
        public BooleanToText() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, source -> RailixValue.string(
                    Boolean.toString(((RailixValue.BooleanValue) source).value())
            ));
        }
    }

    public static final class BooleanToNumber implements StepHandler {
        public BooleanToNumber() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, source -> RailixValue.number(
                    ((RailixValue.BooleanValue) source).value() ? 1 : 0
            ));
        }
    }

    public static final class ValueWrapList implements StepHandler {
        public ValueWrapList() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, source -> RailixValue.array(List.of(source)));
        }
    }

    public static final class ValueToJson implements StepHandler {
        public ValueToJson() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return unary(input, source -> RailixValue.string(RailixJson.write(source)));
        }
    }

    public static final class ValueEquals implements StepHandler {
        public ValueEquals() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, equalValues(input.value("value"), input.value("expected")));
        }
    }

    public static final class ValueNotEquals implements StepHandler {
        public ValueNotEquals() {
        }

        @Override
        public StepResult run(final StepInput input) {
            return predicate(input, !equalValues(input.value("value"), input.value("expected")));
        }
    }

    private static StepResult unary(
            final StepInput input,
            final Function<RailixValue, RailixValue> operation
    ) {
        return value(operation.apply(input.value("value")));
    }

    private static StepResult predicate(final StepInput input, final boolean result) {
        return value(RailixValue.bool(result));
    }

    private static int comparison(final StepInput input) {
        return number(input.value("value")).compareTo(number(input.value("than")));
    }

    private static StepResult listExtreme(final StepInput input, final boolean minimum) {
        final Optional<List<RailixValue.NumberValue>> values = numbers(input.value("value"));
        if (values.isEmpty()) {
            return StepResult.outcome("invalid");
        }
        if (values.get().isEmpty()) {
            return StepResult.outcome("empty");
        }
        RailixValue.NumberValue result = values.get().getFirst();
        for (final RailixValue.NumberValue candidate : values.get()) {
            final int compared = candidate.value().compareTo(result.value());
            if (minimum ? compared < 0 : compared > 0) {
                result = candidate;
            }
        }
        return value(result);
    }

    private static StepResult value(final RailixValue value) {
        return StepResult.outcome("ok").output("value", value);
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
}
