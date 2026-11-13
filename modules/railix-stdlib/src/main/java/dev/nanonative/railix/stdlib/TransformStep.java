package dev.nanonative.railix.stdlib;

import berlin.yuna.typemap.logic.TypeConverter;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;

/** Explicit JSON-native conversion with a typed, optional fallback and ordinary path writes. */
public final class TransformStep {
    private TransformStep() { }

    /** Returns the portable provider declaration consumed by Creator and the compiler. */
    public static StepDefinition definition() {
        return StepDefinition.named("railix.transform", "1")
                .displayName("Transform")
                .searchTerms("convert", "integer", "timestamp", "default", "fallback", "parse", "json")
                .primaryOutcome("ok")
                .outcome("invalid")
                .input("source", Input.path(READ).defaultPath("context", "payload"))
                .input("convert_to", Input.options(Arrays.stream(Conversion.values()).map(conversion ->
                        Input.option(conversion.name().toLowerCase(Locale.ROOT))
                                .input("fallback", Input.json(conversion.shape).optional()
                                        .validation(RailixValue.class, conversion::rejection)))
                        .toArray(StepDefinition.Option[]::new)).defaultOption("number"))
                .input("target", Input.path(WRITE).defaultPath("context", "payload"))
                .implementation(TransformStep.class, "java.sql");
    }

    /**
     * Converts a resolved source or uses an explicitly authored fallback without altering the source.
     *
     * @param configuration compiled conversion option and validated optional fallback
     * @param context invocation with a present or absent readable source
     * @return ok with one target write, or invalid without writes
     */
    public static StepResult process(final StepInput configuration, final StepInput context) {
        final Conversion conversion = Conversion.valueOf(configuration.option("convert_to").toUpperCase(Locale.ROOT));
        final int remainingDepth = RailixData.DEFAULT_MAX_DEPTH
                - ((RailixValue.ArrayValue) configuration.value("target")).values().size() + 1;
        final java.util.function.Predicate<RailixValue> fitsTarget = value -> remainingDepth >= 0
                && (!(value instanceof RailixValue.ArrayValue || value instanceof RailixValue.ObjectValue)
                || remainingDepth > 0 && ValueRefinement.canonical().withMaxDepth(remainingDepth).rejection(value).isEmpty());
        final Optional<RailixValue> result = context.optionalValue("source").flatMap(conversion::convert)
                .filter(fitsTarget)
                .or(() -> configuration.selected("convert_to").optionalValue("fallback").filter(fitsTarget));
        return result.map(value -> StepResult.outcome("ok").write("target", value))
                .orElseGet(() -> StepResult.outcome("invalid"));
    }

    /** The finite conversion family. These tags never introduce additional context value types. */
    public enum Conversion {
        TEXT(ValueShape.STRING), NUMBER(ValueShape.NUMBER), INTEGER(ValueShape.NUMBER),
        BOOLEAN(ValueShape.BOOLEAN), LIST(ValueShape.ARRAY), OBJECT(ValueShape.OBJECT),
        TIMESTAMP_UTC_MILLIS(ValueShape.NUMBER), JSON_TEXT(ValueShape.STRING), JSON_VALUE(ValueShape.ANY);

        final ValueShape shape;

        Conversion(final ValueShape shape) { this.shape = shape; }

        /**
         * Checks a destination value for both configuration and runtime output.
         * @param value JSON-native candidate
         * @return a concise rejection, or empty when this conversion can produce the value
         */
        public Optional<String> rejection(final RailixValue value) {
            if (value == null || value instanceof RailixValue.NullValue) return Optional.of("Choose a non-null fallback.");
            final Optional<String> canonical = ValueRefinement.canonical().rejection(value);
            if (canonical.isPresent()) return canonical;
            if (!shape.accepts(value)) return Optional.of("Expected " + shape.name().toLowerCase(Locale.ROOT) + ".");
            if (this == INTEGER || this == TIMESTAMP_UTC_MILLIS) {
                final BigDecimal number = ((RailixValue.NumberValue) value).value();
                if (number.stripTrailingZeros().scale() > 0) return Optional.of("Expected a whole number.");
                if (this == TIMESTAMP_UTC_MILLIS && (number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                        || number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0))
                    return Optional.of("Expected epoch milliseconds within the signed 64-bit range.");
            }
            return Optional.empty();
        }

        /**
         * Converts one canonical value without flattening containers or guessing document formats.
         * @param source source value, with null rejected rather than replaced implicitly
         * @return a valid destination value, or empty for unsupported or invalid input
         */
        public Optional<RailixValue> convert(final RailixValue source) {
            if (source == null || source instanceof RailixValue.NullValue) return Optional.empty();
            final Object scalar = switch (source) {
                case RailixValue.StringValue text -> text.value();
                case RailixValue.NumberValue number -> number.value();
                case RailixValue.BooleanValue bool -> bool.value();
                default -> source;
            };
            final Optional<RailixValue> converted = switch (this) {
                case TEXT -> source instanceof RailixValue.ArrayValue || source instanceof RailixValue.ObjectValue
                        ? Optional.empty() : Optional.ofNullable(TypeConverter.convertObj(
                                scalar instanceof BigDecimal number ? number.stripTrailingZeros().toPlainString() : scalar,
                                String.class)).map(RailixValue::string);
                case NUMBER, INTEGER -> source instanceof RailixValue.ArrayValue || source instanceof RailixValue.ObjectValue
                        ? Optional.empty() : Optional.ofNullable(TypeConverter.convertObj(
                                scalar instanceof Boolean bool ? (bool ? "1" : "0") : scalar, BigDecimal.class)).map(RailixValue::number);
                case BOOLEAN -> {
                    final boolean supported = scalar instanceof Boolean
                            || scalar instanceof String text && java.util.Set.of("true", "false", "0", "1").contains(text)
                            || scalar instanceof BigDecimal number && (number.compareTo(BigDecimal.ZERO) == 0
                            || number.compareTo(BigDecimal.ONE) == 0);
                    yield supported ? Optional.ofNullable(TypeConverter.convertObj(scalar, Boolean.class)).map(RailixValue::bool)
                            : Optional.empty();
                }
                case LIST -> Optional.of(source instanceof RailixValue.ArrayValue ? source
                        : RailixValue.array(List.of(source)));
                case OBJECT -> source instanceof RailixValue.ObjectValue ? Optional.of(source) : Optional.empty();
                case TIMESTAMP_UTC_MILLIS -> {
                    if (source instanceof RailixValue.BooleanValue) yield Optional.empty();
                    final Optional<RailixValue> number = NUMBER.convert(source);
                    if (number.isPresent()) yield number;
                    if (!(source instanceof RailixValue.StringValue text)) yield Optional.empty();
                    try {
                        yield Optional.ofNullable(TypeConverter.convertObj(OffsetDateTime.parse(text.value()), Long.class))
                                .map(RailixValue::number);
                    } catch (final DateTimeException | ArithmeticException invalid) {
                        yield Optional.empty();
                    }
                }
                case JSON_TEXT -> RailixJson.write(source, RailixData.DEFAULT_MAX_SOURCE_BYTES).map(RailixValue::string);
                case JSON_VALUE -> {
                    if (!(source instanceof RailixValue.StringValue text)) yield Optional.empty();
                    yield RailixData.normalize(RailixData.Format.JSON, text.value().getBytes(StandardCharsets.UTF_8))
                            instanceof RailixData.Normalized normalized ? Optional.of(normalized.value()) : Optional.empty();
                }
            };
            return converted.filter(value -> rejection(value).isEmpty());
        }
    }
}
