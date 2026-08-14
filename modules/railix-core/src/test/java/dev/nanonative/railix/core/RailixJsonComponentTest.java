package dev.nanonative.railix.core;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RailixJsonComponentTest {
    @Test
    void nestedJsonPrimitivesParseIntoCanonicalRailixValues() {
        final RailixJson.Result result = RailixJson.parse(
                "{\"active\":true,\"count\":2,\"items\":[null,\"x\"],\"metadata\":{}}"
        );

        assertThat(result).isEqualTo(new RailixJson.Parsed(RailixValue.object(Map.of(
                "active", RailixValue.bool(true),
                "count", RailixValue.number(2),
                "items", RailixValue.array(List.of(RailixValue.nullValue(), RailixValue.string("x"))),
                "metadata", RailixValue.object(Map.of())
        ))));
    }

    @Test
    void writingCanonicalValuesSortsObjectKeysAndNormalizesNumbers() {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put("z", RailixValue.number(new BigDecimal("2.5000")));
        values.put("a", RailixValue.string("line\n\"quoted\""));

        assertThat(RailixJson.write(RailixValue.object(values)))
                .isEqualTo("{\"a\":\"line\\n\\\"quoted\\\"\",\"z\":2.5}");
    }

    @Test
    void boundedWriterAcceptsAnExactUtf8ByteLimit() {
        assertThat(RailixJson.write(RailixValue.string("\u20ac"), 5)).contains("\"\u20ac\"");
    }

    @Test
    void boundedWriterRejectsCanonicalJsonOneByteOverTheLimit() {
        assertThat(RailixJson.write(RailixValue.string("\u20ac"), 4)).isEmpty();
    }

    @Test
    void utf8FitCheckAcceptsTheExactCanonicalLimitWithoutWritingJson() {
        assertThat(RailixJson.fitsUtf8(RailixValue.string("\u20ac"), 5)).isTrue();
    }

    @Test
    void utf8FitCheckRejectsCanonicalJsonOneByteOverTheLimit() {
        assertThat(RailixJson.fitsUtf8(RailixValue.string("\u20ac"), 4)).isFalse();
    }

    @Test
    void boundedWriterRequiresAPositiveByteLimit() {
        assertThatThrownBy(() -> RailixJson.write(RailixValue.nullValue(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum JSON bytes must be at least 1.");
    }

    @Test
    void jsonNumberSourceLimitRejectsBeforeBigDecimalParsing() {
        assertThat(RailixJson.parse("1".repeat(1_025)))
                .isEqualTo(new RailixJson.Invalid(
                        "JSON number exceeds the 1024-character source limit.",
                        1,
                        1_026
                ));
    }

    @Test
    void jsonParserAcceptsAMinusBeforeTheMaximumMagnitude() {
        final String source = "-" + "1".repeat(1_024);

        assertThat(RailixJson.parse(source))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(new BigDecimal(source))));
    }

    @Test
    void jsonParserRejectsANegativeMagnitudeOverTheLimit() {
        assertThat(RailixJson.parse("-" + "1".repeat(1_025)))
                .isEqualTo(new RailixJson.Invalid(
                        "JSON number exceeds the 1024-character source limit.",
                        1,
                        1_027
                ));
    }

    @Test
    void jsonWriterAcceptsAMinusBeforeTheMaximumMagnitude() {
        final String source = "-" + "1".repeat(1_024);

        assertThat(RailixJson.write(RailixValue.number(new BigDecimal(source))))
                .isEqualTo(source);
    }

    @Test
    void boundedJsonWriterCountsTheMinusAsATransportByte() {
        final String source = "-" + "1".repeat(1_024);
        final RailixValue.NumberValue value = RailixValue.number(new BigDecimal(source));

        assertThat(RailixJson.write(value, 1_024)).isEmpty();
        assertThat(RailixJson.write(value, 1_025)).contains(source);
    }

    @Test
    void jsonWriterRejectsCanonicalNumberExpansionBeyondTheLimit() {
        assertThatThrownBy(() -> RailixJson.write(RailixValue.number(new BigDecimal("1e1024"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Number exceeds the 1024-character canonical limit.");
    }

    @Test
    void jsonWriterRejectsScaleOverflowWithTheCanonicalNumberLimit() {
        assertThatThrownBy(() -> RailixJson.write(
                RailixValue.number(new BigDecimal("100e2147483647"))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Number exceeds the 1024-character canonical limit.");
    }

    @Test
    void jsonWriterCanonicalizesExtremeScaleZero() {
        assertThat(RailixJson.write(RailixValue.number(new BigDecimal("0e-2147483647"))))
                .isEqualTo("0");
    }

    @Test
    void jsonWriterStripsEnoughFractionalZeroesBeforeApplyingTheCanonicalLimit() {
        final BigDecimal value = new BigDecimal("1" + "0".repeat(1_000) + "e-1500");

        assertThat(RailixJson.write(RailixValue.number(value)))
                .isEqualTo("0." + "0".repeat(499) + "1");
    }

    @Test
    void jsonParserRejectsTheSixtyFifthContainerBeforeRecursing() {
        final String source = "[".repeat(65) + "0" + "]".repeat(65);

        assertThat(RailixJson.parse(source)).isEqualTo(new RailixJson.Invalid(
                "JSON exceeds the maximum container depth of 64.",
                1,
                65
        ));
    }

    @Test
    void jsonWriterAcceptsExactlySixtyFourContainers() {
        RailixValue value = RailixValue.number(0);
        for (int depth = 0; depth < 64; depth++) {
            value = RailixValue.array(List.of(value));
        }

        assertThat(RailixJson.write(value)).isEqualTo("[".repeat(64) + "0" + "]".repeat(64));
    }

    @Test
    void jsonWriterRejectsTheSixtyFifthContainerBeforeRecursing() {
        RailixValue value = RailixValue.number(0);
        for (int depth = 0; depth < 65; depth++) {
            value = RailixValue.array(List.of(value));
        }
        final RailixValue nested = value;

        assertThatThrownBy(() -> RailixJson.write(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Railix JSON exceeds the maximum container depth of 64.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixedParseCases")
    void fixedJsonSourcesProduceTheirExactResult(
            final String scenario,
            final String source,
            final RailixJson.Result expected
    ) {
        assertThat(RailixJson.parse(source)).isEqualTo(expected);
    }

    static Stream<Arguments> fixedParseCases() {
        return Stream.of(
                invalid("duplicateObjectFieldsAreRejectedAtTheSecondField", "{\"a\":1,\"a\":2}", "Duplicate object field: a", 1, 8),
                invalid("contentAfterTheRootValueIsRejected", "true false", "Unexpected content after the JSON value.", 1, 6),
                invalid("unsupportedStringEscapesAreRejected", "\"\\x\"", "Unsupported JSON escape: \\x", 1, 4),
                invalid("anExponentCannotContainTwoSigns", "1e+-2", "Expected a digit.", 1, 4),
                invalid("leadingZeroesAreRejected", "01", "Leading zeroes are not allowed in JSON numbers.", 1, 2),
                invalid("nonAsciiDigitsAreRejected", "\u0661", "Expected a JSON value.", 1, 1),
                invalid("whitespaceOutsideTheJsonGrammarIsRejected", "\u000bnull", "Expected a JSON value.", 1, 1),
                invalid("invalidUnicodeEscapeDigitsAreRejected", "\"\\u12xz\"", "Invalid Unicode escape: 12xz", 1, 8),
                parsed("unicodeSurrogatePairsRoundTripWithoutChangingTheValue", "\"\\uD83D\\uDE00\"", RailixValue.string("\uD83D\uDE00")),
                invalid("loneHighSurrogateEscapeIsRejected", "\"\\uD800\"", "Unpaired Unicode surrogate is not allowed in JSON strings.", 1, 9),
                invalid("loneLowSurrogateEscapeIsRejected", "\"\\uDC00\"", "Unpaired Unicode surrogate is not allowed in JSON strings.", 1, 9),
                invalid("carriageReturnAdvancesTheDiagnosticLine", "\r{", "Expected an object field name.", 2, 2),
                invalid("carriageReturnLineFeedAdvancesTheDiagnosticLineOnce", "\r\n{", "Expected an object field name.", 2, 2),
                invalid("missingJsonSourceIsAnExplicitParseResult", null, "JSON source is missing.", 1, 1),
                parsed("falseLiteralParsesAsBoolean", "false", RailixValue.bool(false)),
                parsed("emptyArrayParsesAsCanonicalArray", "[]", RailixValue.array(List.of())),
                invalid("rawControlCharactersInStringsAreRejected", "\"\u0001\"", "Control characters are not allowed in JSON strings.", 1, 3),
                invalid("unterminatedStringIsRejected", "\"abc", "Unterminated JSON string.", 1, 5),
                invalid("unterminatedStringEscapeIsRejected", "\"abc\\", "Unterminated JSON escape.", 1, 6),
                invalid("incompleteUnicodeEscapeIsRejected", "\"\\u12", "Incomplete Unicode escape.", 1, 4),
                parsed("fractionParsesWithoutLosingDecimalScale", "-12.50", RailixValue.number(new BigDecimal("-12.50"))),
                parsed("signedExponentParsesAsCanonicalNumber", "1e-2", RailixValue.number(new BigDecimal("1e-2"))),
                invalid("overflowingExponentIsRejected", "1e9999999999", "Invalid JSON number: 1e9999999999", 1, 13),
                invalid("minusWithoutIntegerDigitsIsRejected", "-", "Expected a digit.", 1, 2),
                invalid("malformedLiteralIsRejected", "truX", "Expected JSON literal: true", 1, 4),
                invalid("objectFieldRequiresAColon", "{\"a\" 1}", "Expected ':'.", 1, 6),
                invalid("arrayValuesRequireAComma", "[1 2]", "Expected ','.", 1, 4),
                parsed("escapedQuoteParsesAsAQuote", "\"\\\"\"", RailixValue.string("\"")),
                parsed("escapedBackslashParsesAsABackslash", "\"\\\\\"", RailixValue.string("\\")),
                parsed("escapedSlashParsesAsASlash", "\"\\/\"", RailixValue.string("/")),
                parsed("escapedBackspaceParsesAsBackspace", "\"\\b\"", RailixValue.string("\b")),
                parsed("escapedFormFeedParsesAsFormFeed", "\"\\f\"", RailixValue.string("\f")),
                parsed("escapedNewlineParsesAsNewline", "\"\\n\"", RailixValue.string("\n")),
                parsed("escapedCarriageReturnParsesAsCarriageReturn", "\"\\r\"", RailixValue.string("\r")),
                parsed("escapedTabParsesAsTab", "\"\\t\"", RailixValue.string("\t")),
                parsed("uppercaseExponentMarkerParsesAsANumber", "1E2", RailixValue.number(new BigDecimal("1E2"))),
                parsed("positiveExponentSignParsesAsANumber", "1e+2", RailixValue.number(new BigDecimal("1e+2"))),
                invalid("truncatedExponentIsRejected", "1e", "Expected a digit.", 1, 3),
                invalid("minusMustBeFollowedByAnAsciiDigit", "-x", "Expected a digit.", 1, 2),
                parsed("zeroParsesAsANumber", "0", RailixValue.number(0)),
                invalid("zeroFollowedByUnexpectedContentIsRejected", "0x", "Unexpected content after the JSON value.", 1, 2),
                invalid("truncatedLiteralIsRejected", "tru", "Expected JSON literal: true", 1, 4),
                invalid("truncatedArrayIsRejected", "[1", "Expected ','.", 1, 3),
                invalid("unquotedObjectFieldIsRejected", "{1}", "Expected an object field name.", 1, 2),
                invalid("leadingLineFeedAdvancesTheDiagnosticLine", "\n{", "Expected an object field name.", 2, 2),
                parsed("tabIsAcceptedAsJsonWhitespace", "\tnull", RailixValue.nullValue()),
                invalid("highSurrogateFollowedByANonSurrogateIsRejected", "\"\\uD800a\"", "Unpaired Unicode surrogate is not allowed in JSON strings.", 1, 10)
        );
    }

    static Arguments invalid(
            final String scenario,
            final String source,
            final String message,
            final int line,
            final int column
    ) {
        return Arguments.of(scenario, source, new RailixJson.Invalid(message, line, column));
    }

    static Arguments parsed(final String scenario, final String source, final RailixValue value) {
        return Arguments.of(scenario, source, new RailixJson.Parsed(value));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixedWriterCases")
    void fixedCanonicalValuesWriteTheirExactJson(
            final String scenario,
            final RailixValue value,
            final String expected
    ) {
        assertThat(RailixJson.write(value)).isEqualTo(expected);
    }

    static Stream<Arguments> fixedWriterCases() {
        return Stream.of(
                Arguments.of(
                        "negativeZeroWritesAsCanonicalZero",
                        RailixValue.number(new BigDecimal("-0.00")),
                        "0"
                ),
                Arguments.of(
                        "exponentNotationWritesAsPlainCanonicalDecimal",
                        RailixValue.number(new BigDecimal("1E+3")),
                        "1000"
                ),
                Arguments.of(
                        "unicodeSurrogatePairsWriteAsTheSameCanonicalValue",
                        RailixValue.string("\uD83D\uDE00"),
                        "\"\uD83D\uDE00\""
                ),
                Arguments.of("jsonNullWritesAsNullLiteral", RailixValue.nullValue(), "null"),
                Arguments.of("jsonBooleanWritesAsBooleanLiteral", RailixValue.bool(false), "false"),
                Arguments.of(
                        "jsonArrayWritesValuesInDeclaredOrder",
                        RailixValue.array(List.of(
                                RailixValue.number(1),
                                RailixValue.string("two"),
                                RailixValue.array(List.of())
                        )),
                        "[1,\"two\",[]]"
                ),
                Arguments.of("jsonWriterEscapesBackslash", RailixValue.string("\\"), "\"\\\\\""),
                Arguments.of("jsonWriterEscapesBackspace", RailixValue.string("\b"), "\"\\b\""),
                Arguments.of("jsonWriterEscapesFormFeed", RailixValue.string("\f"), "\"\\f\""),
                Arguments.of("jsonWriterEscapesCarriageReturn", RailixValue.string("\r"), "\"\\r\""),
                Arguments.of("jsonWriterEscapesTab", RailixValue.string("\t"), "\"\\t\""),
                Arguments.of(
                        "jsonWriterEscapesOtherControlCharactersAsUnicode",
                        RailixValue.string("\u0001"),
                        "\"\\u0001\""
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unpairedSurrogates")
    void writerRejectsUnpairedSurrogates(final String scenario, final String value) {
        assertThatThrownBy(() -> RailixJson.write(RailixValue.string(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unpaired Unicode surrogate is not allowed in JSON strings.");
    }

    static Stream<Arguments> unpairedSurrogates() {
        return Stream.of(
                Arguments.of("writerRejectsLoneHighSurrogate", "\uD800"),
                Arguments.of("writerRejectsLoneLowSurrogate", "\uDC00")
        );
    }
}
