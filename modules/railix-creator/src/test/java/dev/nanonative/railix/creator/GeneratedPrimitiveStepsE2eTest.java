package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(300)
final class GeneratedPrimitiveStepsE2eTest {
    private PrimitiveGeneratedApplications applications;

    @BeforeAll
    void startGeneratedApplications(@TempDir final Path workspace) throws Exception {
        applications = PrimitiveGeneratedApplications.start(workspace);
    }

    @AfterAll
    void stopGeneratedApplications() {
        if (applications != null) {
            applications.close();
        }
    }

    @Test
    void choiceTrueConditionExecutesOnlyTheMatchingBranch() {
        final RunResult.Succeeded result = run(choiceApplication(choiceCondition()), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("choice", "match"));
        assertThat(result.steps()).doesNotContain(new RunResult.StepExecution("otherwise", "next"));
    }

    @Test
    void choiceFalseConditionExecutesOnlyTheOtherwiseBranch() {
        final RunResult.Succeeded result = run(choiceApplication(choiceCondition()), "deny");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("otherwise"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("choice", "otherwise"));
        assertThat(result.steps()).doesNotContain(new RunResult.StepExecution("matched", "next"));
    }

    @Test
    void choiceWithoutMatcherGroupsExecutesTheOtherwiseBranch() {
        final RunResult.Succeeded result = run(choiceApplication("[]"), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void filterMatchExecutesOnlyTheMatchingBranch() {
        final RunResult.Succeeded result = run(filterApplication(filterCondition()), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("filter", "match"));
        assertThat(result.steps()).doesNotContain(new RunResult.StepExecution("otherwise", "next"));
    }

    @Test
    void filterMismatchExecutesOnlyTheOtherwiseBranch() {
        final RunResult.Succeeded result = run(filterApplication(filterCondition()), "deny");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("otherwise"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("filter", "otherwise"));
        assertThat(result.steps()).doesNotContain(new RunResult.StepExecution("matched", "next"));
    }

    @Test
    void filterWithoutConditionsExecutesTheOtherwiseBranch() {
        final RunResult.Succeeded result = run(filterApplication("[]"), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void filterLiteralCandidateMatchesWithoutReadingContext() {
        final RunResult.Succeeded result = run(filterApplication("""
                [{"option":"literal","inputs":{"value":"constant"}}]
                """), "deny");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void filterPresentFieldMatchesWithoutAPredicate() {
        final RunResult.Succeeded result = run(filterApplication("""
                [{"option":"field","inputs":{"field":["context","payload","value"]}}]
                """), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void filterMissingFieldUsesTheOtherwiseRoute() {
        final RunResult.Succeeded result = run(filterApplication("""
                [{"option":"field","inputs":{"field":["context","payload","missing"]}}]
                """), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void filterPresentJsonNullMatchesWithoutAPredicate() {
        final RunResult.Succeeded result = run(filterApplication("""
                [{"option":"literal","inputs":{"value":null}}]
                """), "deny");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void filterChecksTheNextCandidateWhenTheFirstPredicateRejects() {
        final RunResult.Succeeded result = run(filterApplication("""
                [
                  {"option":"field","inputs":{"field":["context","payload","value"]},
                   "when":{"transforms":[],"all":[[
                     {"use":"value.equals","inputs":{"expected":"never"}}]]}},
                  {"option":"literal","inputs":{"value":"fallback"}}
                ]
                """), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void repeatedFilterRunsRetainNoEarlierMatch() {
        final PrimitiveGeneratedApplications.Application application = filterApplication(filterCondition());

        assertThat(List.of(
                run(application, "allow").context().values().get("result"),
                run(application, "deny").context().values().get("result")
        )).containsExactly(RailixValue.string("matched"), RailixValue.string("otherwise"));
    }

    @Test
    void missingFieldLeavesTheContextUnchangedAndContinues() {
        final RunResult.Succeeded result = (RunResult.Succeeded) applications.run(
                applications.linear("[]"),
                RailixValue.object(Map.of("payload", RailixValue.object(Map.of())))
        );

        assertThat(result.context().values().get("payload")).isEqualTo(RailixValue.object(Map.of()));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("continued"));
    }

    @Test
    void invalidPrimitiveLeavesTheFieldUnchangedAndContinues() {
        final RunResult.Succeeded result = (RunResult.Succeeded) applications.runTest(
                applications.linear("[{\"use\":\"text.to-number\",\"inputs\":{}}]"),
                RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("missing", RailixValue.string("not-a-number")))
                ))
        );

        assertThat(((RailixValue.ObjectValue) result.context().values().get("payload")).values().get("missing"))
                .isEqualTo(RailixValue.string("not-a-number"));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("continued"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("text.to-number", "invalid"));
    }

    @Test
    void cliTriggerWithoutResultWritesNothingAndExitsSuccessfully() {
        final RunResult.Succeeded result = (RunResult.Succeeded) applications.run(
                applications.cliDefaults(),
                context("[]")
        );

        assertThat(result.context().values())
                .containsEntry("result", RailixValue.nullValue())
                .containsEntry("exit_code", RailixValue.number(0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            lowercaseUsesLocaleIndependentTextRules | text.lowercase       | "Hello RAILIX"                                           | "hello railix"
            uppercaseUsesRootLocaleRules                  | text.uppercase       | "i"                                                      | "I"
            uppercaseRetainsUnicodeCaseExpansion          | text.uppercase       | "stra\\u00dfe"                                           | "STRASSE"
            uppercaseKeepsEmptyTextEmpty                  | text.uppercase       | ""                                                       | ""
            trimUsesUnicodeStripRules                     | text.trim            | "\\u2003Railix\\u2003"                                    | "Railix"
            trimKeepsEmptyTextEmpty                       | text.trim            | ""                                                       | ""
            trimPreservesInternalWhitespace               | text.trim            | "left\\t  right"                                          | "left\\t  right"
            normalizeSpaceUsesCharacterWhitespace         | text.normalize-space | "left\\u2003right"                                        | "left right"
            normalizeSpaceStripsOuterWhitespace           | text.normalize-space | "\\t  Railix\\n"                                          | "Railix"
            normalizeSpaceCollapsesInternalWhitespaceRuns | text.normalize-space | "left\\t \\nright"                                       | "left right"
            normalizeSpaceTurnsOnlyWhitespaceIntoEmptyText| text.normalize-space | "\\t \\u2003\\n"                                         | ""
            normalizeNfcComposesDecomposedText            | text.normalize-nfc   | "e\\u0301"                                                | "\\u00e9"
            normalizeNfcKeepsNormalizedTextUnchanged      | text.normalize-nfc   | "\\u00e9"                                                 | "\\u00e9"
            textLengthCountsUnicodeCodePoints              | text.length          | "Railix"                                                   | 6
            textLengthCountsASupplementaryCharacterOnce   | text.length          | "A\\ud83d\\ude80B"                                         | 3
            textLengthOfEmptyTextIsZero                   | text.length          | ""                                                       | 0
            textIsEmptyReturnsTrueForEmptyText             | text.is-empty        | ""                                                       | true
            textIsEmptyReturnsFalseForWhitespace           | text.is-empty        | " "                                                      | false

            floorRoundsAPositiveFractionDown              | number.floor         | 1.9                                                        | 1
            floorRoundsANegativeFractionDown              | number.floor         | -1.2                                                       | -2
            floorKeepsAWholeNumber                        | number.floor         | 7                                                          | 7
            floorHandlesAnArbitrarilyPreciseDecimal       | number.floor         | 1.0000000000000000000000000000000000001                  | 1
            ceilRoundsAPositiveFractionUp                 | number.ceil          | 1.2                                                        | 2
            ceilRoundsANegativeFractionUp                 | number.ceil          | -1.2                                                       | -1
            ceilKeepsAWholeNumber                         | number.ceil          | 7                                                          | 7
            ceilHandlesAnArbitrarilyPreciseDecimal        | number.ceil          | 1.0000000000000000000000000000000000001                  | 2
            roundMovesAPositiveExactHalfAwayFromZero      | number.round         | 1.5                                                        | 2
            roundMovesANegativeExactHalfAwayFromZero      | number.round         | -1.5                                                       | -2
            roundMovesAValueBelowHalfTowardZero           | number.round         | 1.49                                                       | 1
            roundMovesAPositiveValueAboveHalfAwayFromZero | number.round         | 1.51                                                       | 2
            roundMovesANegativeValueBelowHalfTowardZero   | number.round         | -1.49                                                      | -1
            roundMovesANegativeValueAboveHalfAwayFromZero | number.round         | -1.51                                                      | -2
            roundKeepsAWholeNumber                        | number.round         | 7                                                          | 7
            absoluteValueMakesANegativeNumberPositive     | number.abs           | -12.5                                                      | 12.5
            absoluteValueKeepsAPositiveNumberUnchanged    | number.abs           | 12.50                                                      | 12.5
            absoluteValueKeepsZeroUnchanged               | number.abs           | 0.00                                                       | 0
            negateMakesAPositiveNumberNegative            | number.negate        | 12.5                                                       | -12.5
            negateMakesANegativeNumberPositive            | number.negate        | -12.5                                                      | 12.5
            negateReturnsCanonicalZero                   | number.negate        | 0.00                                                       | 0
            signReturnsNegativeOneForANegativeNumber      | number.sign          | -0.1                                                       | -1
            signReturnsZeroForZero                        | number.sign          | 0.00                                                       | 0
            signReturnsPositiveOneForAPositiveNumber      | number.sign          | 0.1                                                        | 1

            booleanNotChangesTrueToFalse                 | boolean.not          | true                                                       | false
            booleanNotChangesFalseToTrue                 | boolean.not          | false                                                      | true
            listSizeCountsHeterogeneousItems             | list.size            | [1,"two",true]                                             | 3
            listSizeOfAnEmptyListIsZero                  | list.size            | []                                                         | 0
            listIsEmptyReturnsTrueForAnEmptyList          | list.is-empty        | []                                                         | true
            listIsEmptyReturnsFalseForANonEmptyList       | list.is-empty        | [null]                                                     | false
            zeroIsAUtcMillisecondTimestamp               | date.is-utc-millis   | 0                                                          | true
            aNegativeWholeNumberIsAUtcMillisecondTimestamp| date.is-utc-millis   | -1                                                         | true
            minimumLongIsAUtcMillisecondTimestamp        | date.is-utc-millis   | -9223372036854775808                                       | true
            maximumLongIsAUtcMillisecondTimestamp        | date.is-utc-millis   | 9223372036854775807                                        | true
            aFractionIsNotAUtcMillisecondTimestamp       | date.is-utc-millis   | 1.1                                                        | false
            anIntegralDecimalIsAUtcMillisecondTimestamp  | date.is-utc-millis   | 1.0                                                        | true
            aFractionalExponentIsNotAUtcMillisecondTimestamp | date.is-utc-millis | 1E-1                                                       | false
            aNumberAboveLongRangeIsNotAUtcMillisecondTimestamp | date.is-utc-millis | 9223372036854775808                                      | false
            aNumberBelowLongRangeIsNotAUtcMillisecondTimestamp | date.is-utc-millis | -9223372036854775809                                     | false
            booleanToTextReturnsLowercaseTrue            | boolean.to-text      | true                                                       | "true"
            booleanToTextReturnsLowercaseFalse           | boolean.to-text      | false                                                      | "false"
            booleanToNumberReturnsOneForTrue             | boolean.to-number    | true                                                       | 1
            booleanToNumberReturnsZeroForFalse           | boolean.to-number    | false                                                      | 0
            numberToTextUsesCanonicalPlainJsonNumberForm | number.to-text       | 1.2300                                                     | "1.23"
            numberToTextExpandsExponentNotation          | number.to-text       | 1e3                                                        | "1000"
            numberToTextCanonicalizesNegativeZero        | number.to-text       | -0.0                                                       | "0"
            """)
    void simplePrimitiveReturnsExpectedResult(
            final String scenario,
            final String primitive,
            final String input,
            final String expected
    ) {
        assertThat(result(primitive, input))
                .isEqualTo(((RailixJson.Parsed) RailixJson.parse(expected)).value());
    }

    @Test
    void uppercaseRejectsANumberWithoutCoercion() {
        assertIncompatible("text.uppercase", "7", "string", "number");
    }

    @Test
    void repeatedUppercaseRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.uppercase", "\"one\"");

        assertThat(List.of(result(application, "\"one\""), result(application, "\"two\"")))
                .containsExactly(RailixValue.string("ONE"), RailixValue.string("TWO"));
    }

    @Test
    void uppercaseComposesWithLowercase() {
        assertThat(result(List.of("text.uppercase", "text.lowercase"), "\"RaIlIx\""))
                .isEqualTo(RailixValue.string("railix"));
    }

    @Test
    void trimRejectsANumberWithoutCoercion() {
        assertIncompatible("text.trim", "7", "string", "number");
    }

    @Test
    void repeatedTrimRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.trim", "\" one \"");

        assertThat(List.of(result(application, "\" one \""), result(application, "\" two \\t\"")))
                .containsExactly(RailixValue.string("one"), RailixValue.string("two"));
    }

    @Test
    void trimComposesWithUppercase() {
        assertThat(result(List.of("text.trim", "text.uppercase"), "\" railix \""))
                .isEqualTo(RailixValue.string("RAILIX"));
    }

    @Test
    void normalizeSpaceRejectsANumberWithoutCoercion() {
        assertIncompatible("text.normalize-space", "7", "string", "number");
    }

    @Test
    void repeatedNormalizeSpaceRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.normalize-space", "\"one  value\"");

        assertThat(List.of(
                result(application, "\"one  value\""),
                result(application, "\"two\\tvalue\"")
        )).containsExactly(RailixValue.string("one value"), RailixValue.string("two value"));
    }

    @Test
    void normalizeSpaceComposesWithUppercase() {
        assertThat(result(List.of("text.normalize-space", "text.uppercase"), "\"railix  creator\""))
                .isEqualTo(RailixValue.string("RAILIX CREATOR"));
    }

    @Test
    void normalizeNfcRejectsANumberWithoutCoercion() {
        assertIncompatible("text.normalize-nfc", "7", "string", "number");
    }

    @Test
    void repeatedNormalizeNfcRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.normalize-nfc", "\"e\\u0301\"");

        assertThat(List.of(
                result(application, "\"e\\u0301\""),
                result(application, "\"a\\u0308\"")
        )).containsExactly(RailixValue.string("\u00e9"), RailixValue.string("\u00e4"));
    }

    @Test
    void normalizeNfcComposesWithLength() {
        assertThat(result(List.of("text.normalize-nfc", "text.length"), "\"e\\u0301\""))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void textLengthRejectsANumberWithoutCoercion() {
        assertIncompatible("text.length", "7", "string", "number");
    }

    @Test
    void repeatedTextLengthRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.length", "\"one\"");

        assertThat(List.of(result(application, "\"one\""), result(application, "\"twelve\"")))
                .containsExactly(RailixValue.number(3), RailixValue.number(6));
    }

    @Test
    void textLengthComposesWithNumberSign() {
        assertThat(result(List.of("text.length", "number.sign"), "\"Railix\""))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void textIsEmptyRejectsANumberWithoutCoercion() {
        assertIncompatible("text.is-empty", "7", "string", "number");
    }

    @Test
    void repeatedTextIsEmptyRunsRetainNoEarlierText() {
        final PrimitiveGeneratedApplications.Application application = application("text.is-empty", "\"\"");

        assertThat(List.of(result(application, "\"\""), result(application, "\"value\"")))
                .containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @Test
    void textIsEmptyComposesWithBooleanNot() {
        assertThat(result(List.of("text.is-empty", "boolean.not"), "\"\""))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void textContainsFindsALiteralNeedle() {
        assertThat(configuredResult(
                "text.contains",
                "{\"needle\":\"Rail\"}",
                "\"Nano Railix\""
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsReturnsFalseWhenTheNeedleIsAbsent() {
        assertThat(configuredResult(
                "text.contains",
                "{\"needle\":\"Other\"}",
                "\"Nano Railix\""
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void textContainsIsCaseSensitive() {
        assertThat(configuredResult(
                "text.contains",
                "{\"needle\":\"railix\"}",
                "\"Railix\""
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void textContainsAcceptsAnEmptyNeedle() {
        assertThat(configuredResult(
                "text.contains",
                "{\"needle\":\"\"}",
                "\"Railix\""
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsUsesAnEmptyNeedleWhenConfigurationIsOmitted() {
        assertThat(configuredResult("text.contains", "{}", "\"Railix\""))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsRejectsAuthoredNumberWithoutCoercion() {
        assertRuntimeIncompatible(
                configuredApplication(
                        List.of("text.contains"),
                        "text.contains",
                        "{\"needle\":\"7\"}",
                        "7"
                ),
                "text.contains",
                "7",
                "string",
                "number"
        );
    }

    @Test
    void textContainsRejectsRuntimeNumberWithoutInvokingItsHandler() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("text.contains"),
                "text.contains",
                "{\"needle\":\"7\"}",
                "\"Railix\""
        );

        assertThat(applications.run(application, context("7"))).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step text.contains requires string but receives number.",
                application.nestedPath()
        )), List.of()));
    }

    @Test
    void repeatedTextContainsRunsRetainNoEarlierInput() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("text.contains"),
                "text.contains",
                "{\"needle\":\"Rail\"}",
                "\"Railix\""
        );

        assertThat(List.of(
                result(application, "\"Railix\""),
                result(application, "\"Other\"")
        )).containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @Test
    void textContainsComposesWithBooleanNot() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("text.contains", "boolean.not"),
                "text.contains",
                "{\"needle\":\"Rail\"}",
                "\"Railix\""
        );

        assertThat(result(application, "\"Railix\"")).isEqualTo(RailixValue.bool(false));
    }

    @ParameterizedTest(name = "{0} finds its literal boundary")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryFindsALiteralMatch(final String primitive) {
        final String boundary = primitive.equals("text.starts-with") ? "Nano" : "Railix";

        assertThat(textBoundaryResult(primitive, boundary, "Nano Railix"))
                .isEqualTo(RailixValue.bool(true));
    }

    @ParameterizedTest(name = "{0} reports an absent boundary")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryReturnsFalseWhenAbsent(final String primitive) {
        assertThat(textBoundaryResult(primitive, "Other", "Nano Railix"))
                .isEqualTo(RailixValue.bool(false));
    }

    @ParameterizedTest(name = "{0} is case-sensitive")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryIsCaseSensitive(final String primitive) {
        final String boundary = primitive.equals("text.starts-with") ? "nano" : "railix";

        assertThat(textBoundaryResult(primitive, boundary, "Nano Railix"))
                .isEqualTo(RailixValue.bool(false));
    }

    @ParameterizedTest(name = "{0} accepts an empty boundary")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryAcceptsAnEmptyConfiguration(final String primitive) {
        assertThat(textBoundaryResult(primitive, "", "Railix"))
                .isEqualTo(RailixValue.bool(true));
    }

    @ParameterizedTest(name = "{0} uses an empty boundary when configuration is omitted")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryUsesAnEmptyStringWhenConfigurationIsOmitted(final String primitive) {
        assertThat(configuredResult(primitive, "{}", "\"Railix\""))
                .isEqualTo(RailixValue.bool(true));
    }

    @ParameterizedTest(name = "{0} rejects authored number")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryRejectsAuthoredNumberWithoutCoercion(final String primitive) {
        final String input = textBoundaryInput(primitive);

        assertRuntimeIncompatible(
                configuredApplication(
                        List.of(primitive),
                        primitive,
                        "{\"" + input + "\":\"7\"}",
                        "7"
                ),
                primitive,
                "7",
                "string",
                "number"
        );
    }

    @ParameterizedTest(name = "{0} rejects runtime number")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryRejectsRuntimeNumberWithoutInvokingItsHandler(final String primitive) {
        final String input = textBoundaryInput(primitive);
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"" + input + "\":\"Railix\"}",
                "\"Railix\""
        );

        assertThat(applications.run(application, context("7"))).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step " + primitive + " requires string but receives number.",
                application.nestedPath()
        )), List.of()));
    }

    @ParameterizedTest(name = "{0} retains no earlier input")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void repeatedTextBoundaryRunsRetainNoEarlierInput(final String primitive) {
        final String input = textBoundaryInput(primitive);
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"" + input + "\":\"Railix\"}",
                "\"Railix\""
        );

        assertThat(List.of(
                result(application, "\"Railix\""),
                result(application, "\"Other\"")
        )).containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @ParameterizedTest(name = "{0} composes with boolean.not")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryComposesWithBooleanNot(final String primitive) {
        final String input = textBoundaryInput(primitive);
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of(primitive, "boolean.not"),
                primitive,
                "{\"" + input + "\":\"Railix\"}",
                "\"Railix\""
        );

        assertThat(result(application, "\"Railix\"")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void floorCarryAtTheMaximumMagnitudeRemainsInDomain() {
        final String digits = "9".repeat(1_022);

        assertThat(RailixJson.write(result("number.floor", "-" + digits + ".9")))
                .isEqualTo("-1" + "0".repeat(1_022));
    }

    @Test
    void ceilCarryAtTheMaximumMagnitudeRemainsInDomain() {
        final String digits = "9".repeat(1_022);

        assertThat(RailixJson.write(result("number.ceil", digits + ".1")))
                .isEqualTo("1" + "0".repeat(1_022));
    }

    @Test
    void ceilRejectsTextWithoutCoercion() {
        assertIncompatible("number.ceil", "\"7\"", "number", "string");
    }

    @Test
    void repeatedCeilRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("number.ceil", "1.1");

        assertThat(List.of(result(application, "1.1"), result(application, "9.1")))
                .containsExactly(RailixValue.number(2), RailixValue.number(10));
    }

    @Test
    void roundCarryAtTheMaximumMagnitudeRemainsInDomain() {
        final String digits = "9".repeat(1_022);

        assertThat(RailixJson.write(result("number.round", digits + ".5")))
                .isEqualTo("1" + "0".repeat(1_022));
    }

    @Test
    void roundRejectsTextWithoutCoercion() {
        assertIncompatible("number.round", "\"7\"", "number", "string");
    }

    @Test
    void repeatedRoundRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("number.round", "1.4");

        assertThat(List.of(result(application, "1.4"), result(application, "9.6")))
                .containsExactly(RailixValue.number(1), RailixValue.number(10));
    }

    @Test
    void absoluteValueRejectsTextWithoutCoercion() {
        assertIncompatible("number.abs", "\"7\"", "number", "string");
    }

    @Test
    void repeatedAbsoluteValueRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("number.abs", "-1");

        assertThat(List.of(result(application, "-1"), result(application, "-9")))
                .containsExactly(RailixValue.number(1), RailixValue.number(9));
    }

    @Test
    void negateMaximumPositiveMagnitudeRoundTripsAsNegative() {
        final String magnitude = "1".repeat(1_024);

        assertThat(RailixJson.write(result("number.negate", magnitude)))
                .isEqualTo("-" + magnitude);
    }

    @Test
    void negateMaximumNegativeMagnitudeRoundTripsAsPositive() {
        final String magnitude = "1".repeat(1_024);

        assertThat(RailixJson.write(result("number.negate", "-" + magnitude)))
                .isEqualTo(magnitude);
    }

    @Test
    void negateRejectsTextWithoutCoercion() {
        assertIncompatible("number.negate", "\"7\"", "number", "string");
    }

    @Test
    void repeatedNegateRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("number.negate", "1");

        assertThat(List.of(result(application, "1"), result(application, "-9")))
                .containsExactly(RailixValue.number(-1), RailixValue.number(9));
    }

    @Test
    void signRejectsTextWithoutCoercion() {
        assertIncompatible("number.sign", "\"7\"", "number", "string");
    }

    @Test
    void repeatedSignRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("number.sign", "-1");

        assertThat(List.of(result(application, "-1"), result(application, "9")))
                .containsExactly(RailixValue.number(-1), RailixValue.number(1));
    }

    @Test
    void totalNumberOperationsComposeInDeclaredOrder() {
        assertThat(result(List.of(
                "number.ceil",
                "number.negate",
                "number.abs",
                "number.round",
                "number.sign"
        ), "1.2")).isEqualTo(RailixValue.number(1));
    }

    @ParameterizedTest(name = "{0} compares {1} to {2} as {3}")
    @CsvSource({
            "number.greater-than, 6, 5, true",
            "number.greater-than, 5.00, 5, false",
            "number.greater-than, 4, 5, false",
            "number.greater-than, 0.1000000000000000000001, 0.1, true",
            "number.greater-or-equal, 6, 5, true",
            "number.greater-or-equal, 5.00, 5, true",
            "number.greater-or-equal, 4, 5, false",
            "number.greater-or-equal, 0.1, 0.1000000000000000000001, false",
            "number.less-than, 4, 5, true",
            "number.less-than, 5.00, 5, false",
            "number.less-than, 6, 5, false",
            "number.less-than, 0.1, 0.1000000000000000000001, true",
            "number.less-or-equal, 4, 5, true",
            "number.less-or-equal, 5.00, 5, true",
            "number.less-or-equal, 6, 5, false",
            "number.less-or-equal, 0.1000000000000000000001, 0.1, false"
    })
    void numberComparisonUsesExactConfiguredOrdering(
            final String primitive,
            final String value,
            final String than,
            final boolean expected
    ) {
        assertThat(configuredResult(primitive, "{\"than\":" + than + "}", value))
                .isEqualTo(RailixValue.bool(expected));
    }

    @ParameterizedTest(name = "{0} compares {1} to default zero as {2}")
    @CsvSource({
            "number.greater-than, 1, true",
            "number.greater-or-equal, 0.00, true",
            "number.less-than, -1, true",
            "number.less-or-equal, 0.00, true"
    })
    void numberComparisonUsesZeroWhenConfigurationIsOmitted(
            final String primitive,
            final String value,
            final boolean expected
    ) {
        assertThat(configuredResult(primitive, "{}", value))
                .isEqualTo(RailixValue.bool(expected));
    }

    @ParameterizedTest(name = "{0} rejects authored text input")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonRejectsAuthoredTextWithoutCoercion(final String primitive) {
        assertRuntimeIncompatible(
                configuredApplication(List.of(primitive), primitive, "{\"than\":5}", "\"6\""),
                primitive,
                "\"6\"",
                "number",
                "string"
        );
    }

    @ParameterizedTest(name = "{0} rejects runtime text input")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonRejectsRuntimeTextWithoutInvokingItsHandler(final String primitive) {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"than\":5}",
                "6"
        );
        final RunResult result = applications.run(application, context("\"6\""));

        assertThat(result).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step " + primitive + " requires number but receives string.",
                application.nestedPath()
        )), List.of()));
    }

    @ParameterizedTest(name = "{0} retains no value between runs")
    @CsvSource({
            "number.greater-than, 6, true, 4, false",
            "number.greater-or-equal, 5, true, 4, false",
            "number.less-than, 4, true, 6, false",
            "number.less-or-equal, 5, true, 6, false"
    })
    void repeatedNumberComparisonRunsRetainNoEarlierValue(
            final String primitive,
            final String first,
            final boolean firstExpected,
            final String second,
            final boolean secondExpected
    ) {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"than\":5}",
                first
        );

        assertThat(List.of(result(application, first), result(application, second)))
                .containsExactly(RailixValue.bool(firstExpected), RailixValue.bool(secondExpected));
    }

    @ParameterizedTest(name = "{0} composes between absolute value and boolean not")
    @CsvSource({
            "number.greater-than, -6",
            "number.greater-or-equal, -5",
            "number.less-than, -4",
            "number.less-or-equal, -5"
    })
    void numberComparisonComposesWithCompatiblePrimitives(
            final String primitive,
            final String input
    ) {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("number.abs", primitive, "boolean.not"),
                primitive,
                "{\"than\":5}",
                input
        );

        assertThat(result(application, input)).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void listSizeDoesNotMutateTheSourceList() {
        final RunResult.Succeeded run = run("list.size", "[1,2]");
        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) run.context().values().get("payload");

        assertThat(payload.values().get("value")).isEqualTo(RailixValue.array(List.of(
                RailixValue.number(1),
                RailixValue.number(2)
        )));
    }

    @Test
    void listIsEmptyRejectsAnObjectWithoutCoercion() {
        assertIncompatible("list.is-empty", "{}", "array", "object");
    }

    @Test
    void listIsEmptyRejectsAnObjectAtRuntimeWithoutInvokingTheHandler() {
        assertRuntimeIncompatible("list.is-empty", "[]", "{}", "array", "object");
    }

    @Test
    void listIsEmptyDoesNotReadOrPropagateAnInvalidDescendant() {
        final PrimitiveGeneratedApplications.Application application = application("list.is-empty", "[]");

        assertThat(programmaticResult(application, "unpaired-list"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void repeatedListIsEmptyRunsRetainNoEarlierList() {
        final PrimitiveGeneratedApplications.Application application = application("list.is-empty", "[]");

        assertThat(List.of(result(application, "[]"), result(application, "[1]")))
                .containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @Test
    void listIsEmptyComposesWithBooleanNot() {
        assertThat(result(List.of("list.is-empty", "boolean.not"), "[]"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void booleanToTextRejectsTextWithoutCoercion() {
        assertIncompatible("boolean.to-text", "\"true\"", "boolean", "string");
    }

    @Test
    void booleanToTextRejectsTextAtRuntimeWithoutInvokingTheHandler() {
        assertRuntimeIncompatible("boolean.to-text", "true", "\"true\"", "boolean", "string");
    }

    @Test
    void repeatedBooleanToTextRunsRetainNoEarlierBoolean() {
        final PrimitiveGeneratedApplications.Application application = application("boolean.to-text", "true");

        assertThat(List.of(result(application, "true"), result(application, "false")))
                .containsExactly(RailixValue.string("true"), RailixValue.string("false"));
    }

    @Test
    void booleanToTextComposesWithUppercase() {
        assertThat(result(List.of("boolean.to-text", "text.uppercase"), "true"))
                .isEqualTo(RailixValue.string("TRUE"));
    }

    @Test
    void booleanToNumberRejectsTextWithoutCoercion() {
        assertIncompatible("boolean.to-number", "\"true\"", "boolean", "string");
    }

    @Test
    void booleanToNumberRejectsTextAtRuntimeWithoutInvokingTheHandler() {
        assertRuntimeIncompatible("boolean.to-number", "true", "\"true\"", "boolean", "string");
    }

    @Test
    void repeatedBooleanToNumberRunsRetainNoEarlierBoolean() {
        final PrimitiveGeneratedApplications.Application application = application("boolean.to-number", "true");

        assertThat(List.of(result(application, "true"), result(application, "false")))
                .containsExactly(RailixValue.number(1), RailixValue.number(0));
    }

    @Test
    void booleanToNumberComposesWithNumberNegate() {
        assertThat(result(List.of("boolean.to-number", "number.negate"), "true"))
                .isEqualTo(RailixValue.number(-1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueComparisonCases")
    void valueComparisonsExecuteAgainstCanonicalJson(
            final String scenario,
            final String primitive,
            final String configuration,
            final String input,
            final boolean expected
    ) {
        assertThat(configuredResult(primitive, configuration, input))
                .isEqualTo(RailixValue.bool(expected));
    }

    static Stream<Arguments> valueComparisonCases() {
        return Stream.of(
                valueCase("valueEqualsMatchesNestedJsonRecursively", "value.equals", "{\"expected\":{\"items\":[1,{\"active\":true}]}}", "{\"items\":[1,{\"active\":true}]}", true),
                valueCase("valueEqualsTreatsArrayOrderAsSignificant", "value.equals", "{\"expected\":[1,2]}", "[2,1]", false),
                valueCase("valueEqualsIgnoresObjectFieldOrder", "value.equals", "{\"expected\":{\"first\":1,\"second\":2}}", "{\"second\":2,\"first\":1}", true),
                valueCase("valueEqualsComparesNumbersByValueRatherThanScale", "value.equals", "{\"expected\":1.0}", "1.00", true),
                valueCase("valueEqualsRejectsDifferentJsonTypes", "value.equals", "{\"expected\":1}", "\"1\"", false),
                valueCase("valueEqualsMatchesJsonNull", "value.equals", "{\"expected\":null}", "null", true),
                valueCase("valueEqualsRejectsDifferentBooleans", "value.equals", "{\"expected\":true}", "false", false),
                valueCase("valueEqualsMatchesExactText", "value.equals", "{\"expected\":\"Railix\"}", "\"Railix\"", true),
                valueCase("valueEqualsRejectsDifferentText", "value.equals", "{\"expected\":\"Railix\"}", "\"Other\"", false),
                valueCase("valueEqualsRejectsDifferentArrayLengths", "value.equals", "{\"expected\":[1]}", "[1,2]", false),
                valueCase("valueEqualsRejectsDifferentObjectFields", "value.equals", "{\"expected\":{\"first\":1}}", "{\"second\":1}", false),
                valueCase("valueEqualsRejectsDifferentNestedObjectValues", "value.equals", "{\"expected\":{\"user\":{\"id\":1}}}", "{\"user\":{\"id\":2}}", false),
                valueCase("valueEqualsRejectsNullAgainstAValue", "value.equals", "{\"expected\":null}", "false", false),
                valueCase("valueEqualsUsesJsonNullWhenConfigurationIsOmitted", "value.equals", "{}", "null", true),
                valueCase("valueNotEqualsReturnsFalseForRecursivelyEqualNestedJson", "value.not-equals", "{\"expected\":{\"items\":[1,{\"active\":true}]}}", "{\"items\":[1,{\"active\":true}]}", false),
                valueCase("valueNotEqualsReturnsTrueForDifferentNestedJson", "value.not-equals", "{\"expected\":{\"items\":[1,{\"active\":true}]}}", "{\"items\":[1,{\"active\":false}]}", true),
                valueCase("valueNotEqualsTreatsNumbersWithDifferentScalesAsEqual", "value.not-equals", "{\"expected\":1.0}", "1.00", false),
                valueCase("valueNotEqualsReturnsTrueForDifferentJsonTypes", "value.not-equals", "{\"expected\":1}", "\"1\"", true),
                valueCase("valueNotEqualsUsesJsonNullWhenConfigurationIsOmitted", "value.not-equals", "{}", "null", false),
                valueCase("valueNotEqualsMatchesANonNullValueWhenConfigurationIsOmitted", "value.not-equals", "{}", "false", true)
        );
    }

    static Arguments valueCase(
            final String scenario,
            final String primitive,
            final String configuration,
            final String input,
            final boolean expected
    ) {
        return Arguments.of(scenario, primitive, configuration, input, expected);
    }

    @Test
    void repeatedValueEqualsRunsRetainNoEarlierInput() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("value.equals"),
                "value.equals",
                "{\"expected\":1}",
                "1"
        );

        assertThat(List.of(result(application, "1.0"), result(application, "2")))
                .containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @Test
    void valueEqualsComposesWithBooleanNot() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("value.equals", "boolean.not"),
                "value.equals",
                "{\"expected\":1}",
                "1"
        );

        assertThat(result(application, "1.00")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void repeatedValueNotEqualsRunsRetainNoEarlierInput() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("value.not-equals"),
                "value.not-equals",
                "{\"expected\":1}",
                "1"
        );

        assertThat(List.of(result(application, "1.0"), result(application, "2")))
                .containsExactly(RailixValue.bool(false), RailixValue.bool(true));
    }

    @Test
    void valueNotEqualsComposesWithBooleanNot() {
        final PrimitiveGeneratedApplications.Application application = configuredApplication(
                List.of("value.not-equals", "boolean.not"),
                "value.not-equals",
                "{\"expected\":1}",
                "2"
        );

        assertThat(result(application, "2")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void listReversePreservesAndReversesHeterogeneousValues() {
        assertThat(result("list.reverse", "[1,{\"name\":\"Railix\"},null]"))
                .isEqualTo(RailixValue.array(List.of(
                        RailixValue.nullValue(),
                        RailixValue.object(Map.of("name", RailixValue.string("Railix"))),
                        RailixValue.number(1)
                )));
    }

    @Test
    void listReverseKeepsAnEmptyListEmpty() {
        assertThat(result("list.reverse", "[]")).isEqualTo(RailixValue.array(List.of()));
    }

    @Test
    void listReverseKeepsASingleValue() {
        assertThat(result("list.reverse", "[true]")).isEqualTo(RailixValue.array(List.of(
                RailixValue.bool(true)
        )));
    }

    @Test
    void listReverseRejectsAnObjectWithoutCoercion() {
        assertIncompatible("list.reverse", "{}", "array", "object");
    }

    @Test
    void listReverseRejectsAnObjectAtRuntimeWithoutInvokingTheHandler() {
        assertRuntimeIncompatible("list.reverse", "[]", "{}", "array", "object");
    }

    @Test
    void listReverseRejectsANoncanonicalDescendantAtRuntime() {
        assertProgrammaticRefinementIncompatible(
                "list.reverse",
                application("list.reverse", "[]"),
                "unpaired-list",
                "Value contains an unpaired Unicode surrogate."
        );
    }

    @Test
    void listReverseAcceptsTheMaximumCanonicalDepth() {
        assertThat(programmaticResult(application("list.reverse", "[]"), "depth-64"))
                .isEqualTo(nestedArrays(64));
    }

    @Test
    void listReverseRejectsTheNextContainerDepth() {
        assertProgrammaticRefinementIncompatible(
                "list.reverse",
                application("list.reverse", "[]"),
                "depth-65",
                "Value exceeds maximum container depth 64."
        );
    }

    @Test
    void repeatedListReverseRunsRetainNoEarlierList() {
        final PrimitiveGeneratedApplications.Application application = application("list.reverse", "[]");

        assertThat(List.of(result(application, "[1,2]"), result(application, "[3,4,5]")))
                .containsExactly(
                        RailixValue.array(List.of(RailixValue.number(2), RailixValue.number(1))),
                        RailixValue.array(List.of(
                                RailixValue.number(5), RailixValue.number(4), RailixValue.number(3)))
                );
    }

    @Test
    void listReverseComposesWithItself() {
        assertThat(result(List.of("list.reverse", "list.reverse"), "[1,2,3]"))
                .isEqualTo(RailixValue.array(List.of(
                        RailixValue.number(1), RailixValue.number(2), RailixValue.number(3)
                )));
    }

    @Test
    void numberToTextRejectsTextWithoutCoercion() {
        assertIncompatible("number.to-text", "\"1\"", "number", "string");
    }

    @Test
    void numberToTextRejectsTextAtRuntimeWithoutInvokingTheHandler() {
        assertRuntimeIncompatible("number.to-text", "1", "\"1\"", "number", "string");
    }

    @Test
    void numberToTextRejectsAProgrammaticNumberOutsideTheCanonicalDomain() {
        assertProgrammaticRefinementIncompatible(
                "number.to-text",
                application("number.to-text", "1"),
                "number-over-domain",
                "Value contains a number outside the canonical 1024-character domain."
        );
    }

    @Test
    void numberToTextAcceptsTheMaximumPositiveCanonicalMagnitude() {
        final String number = "9".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS);

        assertThat(result(application("number.to-text", "1"), RailixValue.number(new BigDecimal(number))))
                .isEqualTo(RailixValue.string(number));
    }

    @Test
    void numberToTextAcceptsTheMaximumNegativeCanonicalMagnitude() {
        final String number = "-" + "9".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS);

        assertThat(result(application("number.to-text", "1"), RailixValue.number(new BigDecimal(number))))
                .isEqualTo(RailixValue.string(number));
    }

    @Test
    void repeatedNumberToTextRunsRetainNoEarlierNumber() {
        final PrimitiveGeneratedApplications.Application application = application("number.to-text", "1");

        assertThat(List.of(result(application, "1.20"), result(application, "-2.50")))
                .containsExactly(RailixValue.string("1.2"), RailixValue.string("-2.5"));
    }

    @Test
    void numberToTextComposesWithTextLength() {
        assertThat(result(List.of("number.to-text", "text.length"), "1e3"))
                .isEqualTo(RailixValue.number(4));
    }

    @Test
    void valueWrapListWrapsAScalar() {
        assertThat(result("value.wrap-list", "true"))
                .isEqualTo(RailixValue.array(List.of(RailixValue.bool(true))));
    }

    @Test
    void valueWrapListNestsAnExistingList() {
        assertThat(result("value.wrap-list", "[1,2]"))
                .isEqualTo(RailixValue.array(List.of(RailixValue.array(List.of(
                        RailixValue.number(1), RailixValue.number(2)
                )))));
    }

    @Test
    void valueWrapListRetainsJsonNull() {
        assertThat(result("value.wrap-list", "null"))
                .isEqualTo(RailixValue.array(List.of(RailixValue.nullValue())));
    }

    @Test
    void valueWrapListAcceptsInputAtItsMaximumDepth() {
        assertThat(programmaticResult(application("value.wrap-list", "null"), "depth-63"))
                .isEqualTo(nestedArrays(64));
    }

    @Test
    void valueWrapListRejectsInputWithoutContainerHeadroom() {
        assertProgrammaticRefinementIncompatible(
                "value.wrap-list",
                application("value.wrap-list", "null"),
                "depth-64",
                "Value exceeds maximum container depth 63."
        );
    }

    @Test
    void valueWrapListRejectsANoncanonicalDescendantAtRuntime() {
        assertProgrammaticRefinementIncompatible(
                "value.wrap-list",
                application("value.wrap-list", "null"),
                "unpaired-object",
                "Value contains an unpaired Unicode surrogate."
        );
    }

    @Test
    void repeatedValueWrapListRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("value.wrap-list", "null");

        assertThat(List.of(result(application, "1"), result(application, "false")))
                .containsExactly(
                        RailixValue.array(List.of(RailixValue.number(1))),
                        RailixValue.array(List.of(RailixValue.bool(false)))
                );
    }

    @Test
    void valueWrapListComposesWithListSize() {
        assertThat(result(List.of("value.wrap-list", "list.size"), "{\"value\":1}"))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void valueToJsonWritesCanonicalObjectOrder() {
        assertThat(result("value.to-json", "{\"z\":2,\"a\":1}"))
                .isEqualTo(RailixValue.string("{\"a\":1,\"z\":2}"));
    }

    @Test
    void valueToJsonKeepsStringsQuoted() {
        assertThat(result("value.to-json", "\"Railix\""))
                .isEqualTo(RailixValue.string("\"Railix\""));
    }

    @Test
    void valueToJsonCanonicalizesExactDecimals() {
        assertThat(result("value.to-json", "{\"value\":1.2300}"))
                .isEqualTo(RailixValue.string("{\"value\":1.23}"));
    }

    @Test
    void valueToJsonWritesJsonNull() {
        assertThat(result("value.to-json", "null")).isEqualTo(RailixValue.string("null"));
    }

    @Test
    void valueToJsonAcceptsItsExactCanonicalJsonByteLimit() {
        final String text = "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2);

        assertThat(programmaticResult(application("value.to-json", "null"), "json-byte-limit"))
                .isEqualTo(RailixValue.string(RailixJson.write(RailixValue.string(text))));
    }

    @Test
    void valueToJsonAcceptsItsWorstCaseEscapedOutputByteLimit() {
        final String text = "\\".repeat((RailixData.DEFAULT_MAX_SOURCE_BYTES - 2) / 2);
        final RailixValue input = RailixValue.string(text);
        final String inputJson = RailixJson.write(input);
        final RailixValue output = programmaticResult(
                application("value.to-json", "null"),
                "json-escaped-byte-limit"
        );

        assertThat(inputJson.getBytes(StandardCharsets.UTF_8))
                .hasSize(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        assertThat(output).isEqualTo(RailixValue.string(inputJson));
        assertThat(RailixJson.write(output).getBytes(StandardCharsets.UTF_8))
                .hasSize(RailixData.DEFAULT_MAX_SOURCE_BYTES * 2 + 2);
    }

    @Test
    void valueToJsonRejectsTheNextCanonicalJsonByte() {
        assertProgrammaticRefinementIncompatible(
                "value.to-json",
                application("value.to-json", "null"),
                "json-byte-over-limit",
                "Canonical JSON exceeds 1048576 bytes."
        );
    }

    @Test
    void valueToJsonAcceptsTheMaximumCanonicalDepth() {
        final RailixValue value = nestedArrays(64);

        assertThat(programmaticResult(application("value.to-json", "null"), "depth-64"))
                .isEqualTo(RailixValue.string(RailixJson.write(value)));
    }

    @Test
    void valueToJsonRejectsTheNextContainerDepth() {
        assertProgrammaticRefinementIncompatible(
                "value.to-json",
                application("value.to-json", "null"),
                "depth-65",
                "Value exceeds maximum container depth 64."
        );
    }

    @Test
    void repeatedValueToJsonRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = application("value.to-json", "null");

        assertThat(List.of(result(application, "true"), result(application, "[1]")))
                .containsExactly(RailixValue.string("true"), RailixValue.string("[1]"));
    }

    @Test
    void valueToJsonComposesWithTextLength() {
        assertThat(result(List.of("value.to-json", "text.length"), "{\"a\":1}"))
                .isEqualTo(RailixValue.number(7));
    }

    @Test
    void lowercaseRejectsANumberWithoutCoercion() {
        assertIncompatible("text.lowercase", "7", "string", "number");
    }

    @Test
    void floorRejectsTextWithoutCoercion() {
        assertIncompatible("number.floor", "\"7\"", "number", "string");
    }

    @Test
    void booleanNotRejectsTextWithoutCoercion() {
        assertIncompatible("boolean.not", "\"true\"", "boolean", "string");
    }

    @Test
    void listSizeRejectsAnObjectWithoutCoercion() {
        assertIncompatible("list.size", "{}", "array", "object");
    }

    @Test
    void utcMillisRejectsTextWithoutCoercion() {
        assertIncompatible("date.is-utc-millis", "\"0\"", "number", "string");
    }

    @Test
    void listSizeComposesWithNumberFloor() {
        assertThat(result(List.of("list.size", "number.floor"), "[1,2,3]"))
                .isEqualTo(RailixValue.number(3));
    }

    @Test
    void utcMillisValidationComposesWithBooleanNot() {
        assertThat(result(List.of("date.is-utc-millis", "boolean.not"), "0"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void textToNumberAcceptsExactJsonFractionAndExponentGrammar() {
        assertThat(RailixJson.write(fallibleResult("-12.50e+2"))).isEqualTo("-1250");
    }

    @Test
    void textToNumberAcceptsTheMaximumCanonicalNumberLength() {
        final String number = "1".repeat(1_024);

        assertThat(fallibleResult(number)).isEqualTo(RailixValue.number(new BigDecimal(number)));
    }

    @Test
    void textToNumberRejectsLeadingWhitespace() {
        assertFallibleInvalid(" 1");
    }

    @Test
    void textToNumberRejectsTrailingWhitespace() {
        assertFallibleInvalid("1 ");
    }

    @Test
    void textToNumberRejectsALeadingPlus() {
        assertFallibleInvalid("+1");
    }

    @Test
    void textToNumberRejectsALeadingZero() {
        assertFallibleInvalid("01");
    }

    @Test
    void textToNumberRejectsNan() {
        assertFallibleInvalid("NaN");
    }

    @Test
    void textToNumberRejectsInfinity() {
        assertFallibleInvalid("Infinity");
    }

    @Test
    void textToNumberRejectsMalformedExponentText() {
        assertFallibleInvalid("1e");
    }

    @Test
    void textToNumberRejectsNumberSourceBeyondTheBound() {
        assertFallibleInvalid("1".repeat(1_025));
    }

    @Test
    void textToNumberRejectsCanonicalNumberBeyondTheBound() {
        assertFallibleInvalid("1e1024");
    }

    @Test
    void textToNumberInvalidPreservesTheSelectedSourceValue() {
        final RunResult.Succeeded run = fallibleRun(List.of("text.to-number"), "NaN");
        final RailixValue.ObjectValue payload =
                (RailixValue.ObjectValue) run.context().values().get("payload");

        assertThat(payload.values().get("value")).isEqualTo(RailixValue.string("NaN"));
    }

    @Test
    void textToNumberComposesWithNumberFloor() {
        assertThat(fallibleResult(List.of("text.to-number", "number.floor"), "12.9"))
                .isEqualTo(RailixValue.number(12));
    }

    @Test
    void repeatedTextToNumberRunsRetainNoEarlierValue() {
        final PrimitiveGeneratedApplications.Application application = fallibleApplication(List.of("text.to-number"), "1");

        assertThat(List.of(
                run(application, "2.5").context().values().get("result"),
                run(application, "3.5").context().values().get("result")
        )).containsExactly(
                RailixValue.number(new BigDecimal("2.5")),
                RailixValue.number(new BigDecimal("3.5"))
        );
    }

    @Test
    void textToNumberRejectsANumberInputWithoutCoercion() {
        assertRuntimeIncompatible(
                fallibleApplication(List.of("text.to-number"), "7"),
                "text.to-number",
                "7",
                "string",
                "number"
        );
    }

    @Test
    void listSumAddsExactNumbers() {
        assertThat(collectionResult("list.sum", "{}", "[1,2.5,-0.5]"))
                .isEqualTo(RailixValue.number(3));
    }

    @Test
    void listSumReturnsZeroForAnEmptyList() {
        assertThat(collectionResult("list.sum", "{}", "[]")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void listSumHeterogeneousListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.sum", "{}", "[1,\"two\"]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listSumOutOfDomainExactResultLeavesTheResultUnsetAndContinues() {
        final String maximum = "9".repeat(1_024);

        assertThat(collectionResult("list.sum", "{}", "[" + maximum + "," + maximum + "]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listSumOutOfDomainResultDoesNotWriteItsTarget() {
        final String maximum = "9".repeat(1_024);

        assertThat(collectionTarget("list.sum", "{}", "[" + maximum + "," + maximum + "]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listSumAcceptsAResultAtTheMaximumMagnitude() {
        final String near = "9".repeat(1_023);

        assertThat(RailixJson.write(collectionResult("list.sum", "{}", "[" + near + ",1]")))
                .isEqualTo("1" + "0".repeat(1_023));
    }

    @Test
    void listSumChecksTheFinalResultAfterExactCancellation() {
        final String maximum = "9".repeat(1_024);

        assertThat(RailixJson.write(collectionResult(
                "list.sum",
                "{}",
                "[" + maximum + "," + maximum + ",-" + maximum + "]"
        ))).isEqualTo(maximum);
    }

    @Test
    void listSumInvalidOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.sum", "{}", "[1,\"two\"]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listSumRejectsAnObjectWithoutCoercion() {
        assertCollectionIncompatible("list.sum");
    }

    @Test
    void repeatedListSumRunsRetainNoEarlierTotal() {
        final PrimitiveGeneratedApplications.Application application = collectionApplication("list.sum", "{}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[1,2]").context().values().get("result"),
                collectionRun(application, "[10,20]").context().values().get("result")
        )).containsExactly(RailixValue.number(3), RailixValue.number(30));
    }

    @Test
    void listMinReturnsTheFirstExistingLeastNumber() {
        assertThat(collectionResult("list.min", "{}", "[2,1.0,1,3]"))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void listMinEmptyListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.min", "{}", "[]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listMinEmptyOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.min", "{}", "[]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listMinHeterogeneousListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.min", "{}", "[1,false]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listMinInvalidOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.min", "{}", "[1,false]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listMinRejectsAnObjectWithoutCoercion() {
        assertCollectionIncompatible("list.min");
    }

    @Test
    void repeatedListMinRunsRetainNoEarlierMinimum() {
        final PrimitiveGeneratedApplications.Application application = collectionApplication("list.min", "{}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[3,2]").context().values().get("result"),
                collectionRun(application, "[9,8]").context().values().get("result")
        )).containsExactly(RailixValue.number(2), RailixValue.number(8));
    }

    @Test
    void listMaxReturnsTheFirstExistingGreatestNumber() {
        assertThat(collectionResult("list.max", "{}", "[2,3.0,3,1]"))
                .isEqualTo(RailixValue.number(3));
    }

    @Test
    void listMaxEmptyListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.max", "{}", "[]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listMaxEmptyOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.max", "{}", "[]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listMaxHeterogeneousListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.max", "{}", "[1,null]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listMaxInvalidOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.max", "{}", "[1,null]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listMaxRejectsAnObjectWithoutCoercion() {
        assertCollectionIncompatible("list.max");
    }

    @Test
    void repeatedListMaxRunsRetainNoEarlierMaximum() {
        final PrimitiveGeneratedApplications.Application application = collectionApplication("list.max", "{}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[2,3]").context().values().get("result"),
                collectionRun(application, "[8,9]").context().values().get("result")
        )).containsExactly(RailixValue.number(3), RailixValue.number(9));
    }

    @Test
    void listPercentileDefaultsToNinetyFive() {
        assertThat(collectionResult(
                "list.percentile",
                "{}",
                "[20,19,18,17,16,15,14,13,12,11,10,9,8,7,6,5,4,3,2,1]"
        )).isEqualTo(RailixValue.number(19));
    }

    @Test
    void listPercentileZeroSelectsTheMinimum() {
        assertThat(collectionResult("list.percentile", "{\"percentile\":0}", "[4,1,3,2]"))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void listPercentileHundredSelectsTheMaximum() {
        assertThat(collectionResult("list.percentile", "{\"percentile\":100}", "[4,1,3,2]"))
                .isEqualTo(RailixValue.number(4));
    }

    @Test
    void listPercentileUsesNearestRankWithoutInterpolation() {
        assertThat(collectionResult("list.percentile", "{\"percentile\":50}", "[4,1,3,2]"))
                .isEqualTo(RailixValue.number(2));
    }

    @Test
    void listPercentileEmptyListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.percentile", "{}", "[]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listPercentileEmptyOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.percentile", "{}", "[]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listPercentileHeterogeneousListLeavesTheResultUnsetAndContinues() {
        assertThat(collectionResult("list.percentile", "{}", "[1,\"two\"]"))
                .isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listPercentileInvalidOutcomeDoesNotWriteItsTarget() {
        assertThat(collectionTarget("list.percentile", "{}", "[1,\"two\"]"))
                .isEqualTo(RailixValue.string("kept"));
    }

    @Test
    void listPercentileRejectsAnObjectWithoutCoercion() {
        assertCollectionIncompatible("list.percentile");
    }

    @Test
    void repeatedListPercentileRunsRetainNoEarlierRank() {
        final PrimitiveGeneratedApplications.Application application =
                collectionApplication("list.percentile", "{\"percentile\":50}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[4,1,3,2]").context().values().get("result"),
                collectionRun(application, "[40,10,30,20]").context().values().get("result")
        )).containsExactly(RailixValue.number(2), RailixValue.number(20));
    }

    @Test
    void listPercentileEmptyOutcomeRemainsInExecutionEvidence() {
        final RunResult.Succeeded result = (RunResult.Succeeded) applications.runTest(
                collectionApplication("list.percentile", "{}", "[1]"),
                context("[]")
        );

        assertThat(result.steps()).contains(new RunResult.StepExecution("list.percentile", "empty"));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listPercentileEmptyProgramUsesTheTriggerDefaults() {
        final RunResult.Succeeded result = collectionRun(
                collectionApplication("list.percentile", "{}", "[1]"),
                "[]"
        );

        assertThat(result.context().values())
                .containsEntry("result", RailixValue.nullValue())
                .containsEntry("exit_code", RailixValue.number(0));
    }

    private RailixValue textBoundaryResult(
            final String primitive,
            final String boundary,
            final String input
    ) {
        return configuredResult(
                primitive,
                "{\"" + textBoundaryInput(primitive) + "\":"
                        + RailixJson.write(RailixValue.string(boundary)) + "}",
                RailixJson.write(RailixValue.string(input))
        );
    }

    private String textBoundaryInput(final String primitive) {
        return primitive.equals("text.starts-with") ? "prefix" : "suffix";
    }

    private void assertIncompatible(
            final String primitive,
            final String input,
            final String expected,
            final String actual
    ) {
        assertRuntimeIncompatible(primitive, input, input, expected, actual);
    }

    private void assertRuntimeIncompatible(
            final String primitive,
            final String compiledInput,
            final String runtimeInput,
            final String expected,
            final String actual
    ) {
        assertRuntimeIncompatible(
                application(primitive, compiledInput),
                primitive,
                runtimeInput,
                expected,
                actual
        );
    }

    private void assertRuntimeIncompatible(
            final PrimitiveGeneratedApplications.Application application,
            final String primitive,
            final String runtimeInput,
            final String expected,
            final String actual
    ) {
        final RunResult result = applications.run(application, context(runtimeInput));

        assertThat(result).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + primitive + " requires " + expected + " but receives " + actual + ".",
                        application.nestedPath()
                )),
                List.of()
        ));
    }

    private void assertProgrammaticRefinementIncompatible(
            final String primitive,
            final PrimitiveGeneratedApplications.Application application,
            final String input,
            final String rejection
    ) {
        final RunResult result = applications.runSource(application, input);

        assertThat(result).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + primitive + " rejects value: " + rejection,
                        application.nestedPath()
                )),
                List.of()
        ));
    }

    private void assertCollectionIncompatible(final String primitive) {
        assertRuntimeIncompatible(
                collectionApplication(primitive, "{}", "{}"),
                primitive,
                "{}",
                "array",
                "object"
        );
    }

    private RailixValue result(final String primitive, final String input) {
        return result(List.of(primitive), input);
    }

    private RailixValue result(final List<String> primitives, final String input) {
        return run(primitives, input).context().values().get("result");
    }

    private RailixValue result(
            final PrimitiveGeneratedApplications.Application application,
            final String input
    ) {
        return runValue(application, input).context().values().get("result");
    }

    private RailixValue result(
            final PrimitiveGeneratedApplications.Application application,
            final RailixValue input
    ) {
        final RunResult.Succeeded run = (RunResult.Succeeded) applications.run(application, context(input));
        return run.context().values().get("result");
    }

    private RailixValue programmaticResult(
            final PrimitiveGeneratedApplications.Application application,
            final String input
    ) {
        final RunResult.Succeeded run = (RunResult.Succeeded) applications.runSource(application, input);
        return run.context().values().get("result");
    }

    private RailixValue configuredResult(
            final String primitive,
            final String config,
            final String input
    ) {
        return result(configuredApplication(List.of(primitive), primitive, config, input), input);
    }

    private PrimitiveGeneratedApplications.Application configuredApplication(
            final List<String> primitives,
            final String configuredPrimitive,
            final String config,
            final String input
    ) {
        return applications.configured(primitives, configuredPrimitive, config);
    }

    private RunResult.Succeeded run(final String primitive, final String input) {
        return run(List.of(primitive), input);
    }

    private RunResult.Succeeded run(final List<String> primitives, final String input) {
        return runValue(application(primitives, input), input);
    }

    private PrimitiveGeneratedApplications.Application application(final String primitive, final String input) {
        return application(List.of(primitive), input);
    }

    private PrimitiveGeneratedApplications.Application application(
            final List<String> primitives,
            final String input
    ) {
        return applications.operation(primitives);
    }

    private RunResult.Succeeded runValue(
            final PrimitiveGeneratedApplications.Application application,
            final String input
    ) {
        return (RunResult.Succeeded) applications.run(application, context(input));
    }

    private RailixValue fallibleResult(final String input) {
        return fallibleResult(List.of("text.to-number"), input);
    }

    private void assertFallibleInvalid(final String input) {
        final RunResult.Succeeded result = fallibleRunTest(List.of("text.to-number"), input);

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string(input));
        assertThat(result.steps()).contains(new RunResult.StepExecution("text.to-number", "invalid"));
    }

    private RailixValue fallibleResult(final List<String> primitives, final String input) {
        return fallibleRun(primitives, input).context().values().get("result");
    }

    private RunResult.Succeeded fallibleRun(final List<String> primitives, final String input) {
        return (RunResult.Succeeded) applications.run(
                fallibleApplication(primitives, input),
                RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("value", RailixValue.string(input)))
                ))
        );
    }

    private RunResult.Succeeded fallibleRunTest(final List<String> primitives, final String input) {
        return run(fallibleApplication(primitives, input), input);
    }

    private PrimitiveGeneratedApplications.Application fallibleApplication(
            final List<String> primitives,
            final String example
    ) {
        return applications.fallible(primitives);
    }

    private RunResult.Succeeded run(
            final PrimitiveGeneratedApplications.Application application,
            final String input
    ) {
        return (RunResult.Succeeded) applications.runTest(application, RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("value", RailixValue.string(input)))
        )));
    }

    private RailixValue collectionResult(
            final String primitive,
            final String config,
            final String input
    ) {
        return collectionRun(collectionApplication(primitive, config, input), input)
                .context()
                .values()
                .get("result");
    }

    private PrimitiveGeneratedApplications.Application collectionApplication(
            final String primitive,
            final String config,
            final String input
    ) {
        return applications.configured(List.of(primitive), primitive, config);
    }

    private RunResult.Succeeded collectionRun(
            final PrimitiveGeneratedApplications.Application application,
            final String input
    ) {
        return (RunResult.Succeeded) applications.run(application, context(input));
    }

    private RailixValue collectionTarget(
            final String primitive,
            final String config,
            final String input
    ) {
        final PrimitiveGeneratedApplications.Application application = applications.target(primitive, config);
        final RunResult.Succeeded result = (RunResult.Succeeded) applications.run(
                application,
                (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                        "{\"payload\":{\"value\":" + input + ",\"target\":\"kept\"}}"
                )).value()
        );
        return ((RailixValue.ObjectValue) result.context().values().get("payload"))
                .values()
                .get("target");
    }

    private RailixValue.ObjectValue context(final String input) {
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                "{\"payload\":{\"value\":" + input + "}}"
        )).value();
    }

    private RailixValue.ObjectValue context(final RailixValue input) {
        return RailixValue.object(Map.of(
                "payload",
                RailixValue.object(Map.of("value", input))
        ));
    }

    private RailixValue nestedArrays(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }

    private String filterCondition() {
        return "[{\"option\":\"field\",\"inputs\":{"
                + "\"field\":[\"context\",\"payload\",\"value\"]},"
                + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                + "\"inputs\":{\"expected\":\"allow\"}}]]}}]";
    }

    private String choiceCondition() {
        return "[" + filterCondition() + "]";
    }

    private PrimitiveGeneratedApplications.Application filterApplication(final String conditions) {
        return applications.branch("railix.filter", conditions);
    }

    private PrimitiveGeneratedApplications.Application choiceApplication(final String conditions) {
        return applications.branch("railix.choice", conditions);
    }
}
