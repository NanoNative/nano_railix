package dev.nanonative.railix.core;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void duplicateObjectFieldsAreRejectedAtTheSecondField() {
        assertThat(RailixJson.parse("{\"a\":1,\"a\":2}"))
                .isEqualTo(new RailixJson.Invalid("Duplicate object field: a", 1, 8));
    }

    @Test
    void contentAfterTheRootValueIsRejected() {
        assertThat(RailixJson.parse("true false"))
                .isEqualTo(new RailixJson.Invalid("Unexpected content after the JSON value.", 1, 6));
    }

    @Test
    void unsupportedStringEscapesAreRejected() {
        assertThat(RailixJson.parse("\"\\x\""))
                .isEqualTo(new RailixJson.Invalid("Unsupported JSON escape: \\x", 1, 4));
    }

    @Test
    void anExponentCannotContainTwoSigns() {
        assertThat(RailixJson.parse("1e+-2"))
                .isEqualTo(new RailixJson.Invalid("Expected a digit.", 1, 4));
    }

    @Test
    void leadingZeroesAreRejected() {
        assertThat(RailixJson.parse("01"))
                .isEqualTo(new RailixJson.Invalid(
                        "Leading zeroes are not allowed in JSON numbers.",
                        1,
                        2
                ));
    }

    @Test
    void nonAsciiDigitsAreRejected() {
        assertThat(RailixJson.parse("\u0661"))
                .isEqualTo(new RailixJson.Invalid("Expected a JSON value.", 1, 1));
    }

    @Test
    void whitespaceOutsideTheJsonGrammarIsRejected() {
        assertThat(RailixJson.parse("\u000bnull"))
                .isEqualTo(new RailixJson.Invalid("Expected a JSON value.", 1, 1));
    }

    @Test
    void negativeZeroWritesAsCanonicalZero() {
        assertThat(RailixJson.write(RailixValue.number(new BigDecimal("-0.00"))))
                .isEqualTo("0");
    }

    @Test
    void exponentNotationWritesAsPlainCanonicalDecimal() {
        assertThat(RailixJson.write(RailixValue.number(new BigDecimal("1E+3"))))
                .isEqualTo("1000");
    }

    @Test
    void invalidUnicodeEscapeDigitsAreRejected() {
        assertThat(RailixJson.parse("\"\\u12xz\""))
                .isEqualTo(new RailixJson.Invalid("Invalid Unicode escape: 12xz", 1, 8));
    }

    @Test
    void unicodeSurrogatePairsRoundTripWithoutChangingTheValue() {
        final RailixJson.Result parsed = RailixJson.parse("\"\\uD83D\\uDE00\"");

        assertThat(parsed)
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\uD83D\uDE00")));
    }

    @Test
    void unicodeSurrogatePairsWriteAsTheSameCanonicalValue() {
        assertThat(RailixJson.write(RailixValue.string("\uD83D\uDE00")))
                .isEqualTo("\"\uD83D\uDE00\"");
    }

    @Test
    void loneHighSurrogateEscapeIsRejected() {
        assertThat(RailixJson.parse("\"\\uD800\""))
                .isEqualTo(new RailixJson.Invalid(
                        "Unpaired Unicode surrogate is not allowed in JSON strings.",
                        1,
                        9
                ));
    }

    @Test
    void loneLowSurrogateEscapeIsRejected() {
        assertThat(RailixJson.parse("\"\\uDC00\""))
                .isEqualTo(new RailixJson.Invalid(
                        "Unpaired Unicode surrogate is not allowed in JSON strings.",
                        1,
                        9
                ));
    }

    @Test
    void writerRejectsLoneHighSurrogate() {
        assertThatThrownBy(() -> RailixJson.write(RailixValue.string("\uD800")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unpaired Unicode surrogate is not allowed in JSON strings.");
    }

    @Test
    void writerRejectsLoneLowSurrogate() {
        assertThatThrownBy(() -> RailixJson.write(RailixValue.string("\uDC00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unpaired Unicode surrogate is not allowed in JSON strings.");
    }

    @Test
    void carriageReturnAdvancesTheDiagnosticLine() {
        assertThat(RailixJson.parse("\r{"))
                .isEqualTo(new RailixJson.Invalid("Expected an object field name.", 2, 2));
    }

    @Test
    void carriageReturnLineFeedAdvancesTheDiagnosticLineOnce() {
        assertThat(RailixJson.parse("\r\n{"))
                .isEqualTo(new RailixJson.Invalid("Expected an object field name.", 2, 2));
    }

    @Test
    void missingJsonSourceIsAnExplicitParseResult() {
        assertThat(RailixJson.parse(null))
                .isEqualTo(new RailixJson.Invalid("JSON source is missing.", 1, 1));
    }

    @Test
    void jsonNullWritesAsNullLiteral() {
        assertThat(RailixJson.write(RailixValue.nullValue())).isEqualTo("null");
    }

    @Test
    void jsonBooleanWritesAsBooleanLiteral() {
        assertThat(RailixJson.write(RailixValue.bool(false))).isEqualTo("false");
    }

    @Test
    void jsonArrayWritesValuesInDeclaredOrder() {
        assertThat(RailixJson.write(RailixValue.array(List.of(
                RailixValue.number(1),
                RailixValue.string("two"),
                RailixValue.array(List.of())
        )))).isEqualTo("[1,\"two\",[]]");
    }

    @Test
    void jsonWriterEscapesBackslash() {
        assertThat(RailixJson.write(RailixValue.string("\\"))).isEqualTo("\"\\\\\"");
    }

    @Test
    void jsonWriterEscapesBackspace() {
        assertThat(RailixJson.write(RailixValue.string("\b"))).isEqualTo("\"\\b\"");
    }

    @Test
    void jsonWriterEscapesFormFeed() {
        assertThat(RailixJson.write(RailixValue.string("\f"))).isEqualTo("\"\\f\"");
    }

    @Test
    void jsonWriterEscapesCarriageReturn() {
        assertThat(RailixJson.write(RailixValue.string("\r"))).isEqualTo("\"\\r\"");
    }

    @Test
    void jsonWriterEscapesTab() {
        assertThat(RailixJson.write(RailixValue.string("\t"))).isEqualTo("\"\\t\"");
    }

    @Test
    void jsonWriterEscapesOtherControlCharactersAsUnicode() {
        assertThat(RailixJson.write(RailixValue.string("\u0001"))).isEqualTo("\"\\u0001\"");
    }

    @Test
    void falseLiteralParsesAsBoolean() {
        assertThat(RailixJson.parse("false"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.bool(false)));
    }

    @Test
    void emptyArrayParsesAsCanonicalArray() {
        assertThat(RailixJson.parse("[]"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.array(List.of())));
    }

    @Test
    void rawControlCharactersInStringsAreRejected() {
        assertThat(RailixJson.parse("\"\u0001\""))
                .isEqualTo(new RailixJson.Invalid(
                        "Control characters are not allowed in JSON strings.",
                        1,
                        3
                ));
    }

    @Test
    void unterminatedStringIsRejected() {
        assertThat(RailixJson.parse("\"abc"))
                .isEqualTo(new RailixJson.Invalid("Unterminated JSON string.", 1, 5));
    }

    @Test
    void unterminatedStringEscapeIsRejected() {
        assertThat(RailixJson.parse("\"abc\\"))
                .isEqualTo(new RailixJson.Invalid("Unterminated JSON escape.", 1, 6));
    }

    @Test
    void incompleteUnicodeEscapeIsRejected() {
        assertThat(RailixJson.parse("\"\\u12"))
                .isEqualTo(new RailixJson.Invalid("Incomplete Unicode escape.", 1, 4));
    }

    @Test
    void fractionParsesWithoutLosingDecimalScale() {
        assertThat(RailixJson.parse("-12.50"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(new BigDecimal("-12.50"))));
    }

    @Test
    void signedExponentParsesAsCanonicalNumber() {
        assertThat(RailixJson.parse("1e-2"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(new BigDecimal("1e-2"))));
    }

    @Test
    void overflowingExponentIsRejected() {
        assertThat(RailixJson.parse("1e9999999999"))
                .isEqualTo(new RailixJson.Invalid("Invalid JSON number: 1e9999999999", 1, 13));
    }

    @Test
    void minusWithoutIntegerDigitsIsRejected() {
        assertThat(RailixJson.parse("-"))
                .isEqualTo(new RailixJson.Invalid("Expected a digit.", 1, 2));
    }

    @Test
    void malformedLiteralIsRejected() {
        assertThat(RailixJson.parse("truX"))
                .isEqualTo(new RailixJson.Invalid("Expected JSON literal: true", 1, 4));
    }

    @Test
    void objectFieldRequiresAColon() {
        assertThat(RailixJson.parse("{\"a\" 1}"))
                .isEqualTo(new RailixJson.Invalid("Expected ':'.", 1, 6));
    }

    @Test
    void arrayValuesRequireAComma() {
        assertThat(RailixJson.parse("[1 2]"))
                .isEqualTo(new RailixJson.Invalid("Expected ','.", 1, 4));
    }

    @Test
    void escapedQuoteParsesAsAQuote() {
        assertThat(RailixJson.parse("\"\\\"\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\"")));
    }

    @Test
    void escapedBackslashParsesAsABackslash() {
        assertThat(RailixJson.parse("\"\\\\\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\\")));
    }

    @Test
    void escapedSlashParsesAsASlash() {
        assertThat(RailixJson.parse("\"\\/\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("/")));
    }

    @Test
    void escapedBackspaceParsesAsBackspace() {
        assertThat(RailixJson.parse("\"\\b\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\b")));
    }

    @Test
    void escapedFormFeedParsesAsFormFeed() {
        assertThat(RailixJson.parse("\"\\f\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\f")));
    }

    @Test
    void escapedNewlineParsesAsNewline() {
        assertThat(RailixJson.parse("\"\\n\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\n")));
    }

    @Test
    void escapedCarriageReturnParsesAsCarriageReturn() {
        assertThat(RailixJson.parse("\"\\r\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\r")));
    }

    @Test
    void escapedTabParsesAsTab() {
        assertThat(RailixJson.parse("\"\\t\""))
                .isEqualTo(new RailixJson.Parsed(RailixValue.string("\t")));
    }

    @Test
    void uppercaseExponentMarkerParsesAsANumber() {
        assertThat(RailixJson.parse("1E2"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(new BigDecimal("1E2"))));
    }

    @Test
    void positiveExponentSignParsesAsANumber() {
        assertThat(RailixJson.parse("1e+2"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(new BigDecimal("1e+2"))));
    }

    @Test
    void truncatedExponentIsRejected() {
        assertThat(RailixJson.parse("1e"))
                .isEqualTo(new RailixJson.Invalid("Expected a digit.", 1, 3));
    }

    @Test
    void minusMustBeFollowedByAnAsciiDigit() {
        assertThat(RailixJson.parse("-x"))
                .isEqualTo(new RailixJson.Invalid("Expected a digit.", 1, 2));
    }

    @Test
    void zeroParsesAsANumber() {
        assertThat(RailixJson.parse("0"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.number(0)));
    }

    @Test
    void zeroFollowedByUnexpectedContentIsRejected() {
        assertThat(RailixJson.parse("0x"))
                .isEqualTo(new RailixJson.Invalid("Unexpected content after the JSON value.", 1, 2));
    }

    @Test
    void truncatedLiteralIsRejected() {
        assertThat(RailixJson.parse("tru"))
                .isEqualTo(new RailixJson.Invalid("Expected JSON literal: true", 1, 4));
    }

    @Test
    void truncatedArrayIsRejected() {
        assertThat(RailixJson.parse("[1"))
                .isEqualTo(new RailixJson.Invalid("Expected ','.", 1, 3));
    }

    @Test
    void unquotedObjectFieldIsRejected() {
        assertThat(RailixJson.parse("{1}"))
                .isEqualTo(new RailixJson.Invalid("Expected an object field name.", 1, 2));
    }

    @Test
    void leadingLineFeedAdvancesTheDiagnosticLine() {
        assertThat(RailixJson.parse("\n{"))
                .isEqualTo(new RailixJson.Invalid("Expected an object field name.", 2, 2));
    }

    @Test
    void tabIsAcceptedAsJsonWhitespace() {
        assertThat(RailixJson.parse("\tnull"))
                .isEqualTo(new RailixJson.Parsed(RailixValue.nullValue()));
    }

    @Test
    void highSurrogateFollowedByANonSurrogateIsRejected() {
        assertThat(RailixJson.parse("\"\\uD800a\""))
                .isEqualTo(new RailixJson.Invalid(
                        "Unpaired Unicode surrogate is not allowed in JSON strings.",
                        1,
                        10
                ));
    }
}
