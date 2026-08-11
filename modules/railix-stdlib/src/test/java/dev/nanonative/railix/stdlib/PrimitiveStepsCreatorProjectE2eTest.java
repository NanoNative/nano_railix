package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrimitiveStepsCreatorProjectE2eTest {
    @Test
    void builtInValueOperationsAreOrdinaryUnarySteps() {
        assertThat(PrimitiveSteps.definitions()).allSatisfy(definition -> {
            assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
            assertThat(definition.receives()).hasSize(1);
            assertThat(definition.returns()).hasSize(1);
            assertThat(definition.handler()).isPresent();
            assertThat(definition.source()).isEmpty();
            assertThat(definition.results()).isEmpty();
        });
    }

    @ParameterizedTest(name = "{0} exposes {1} with search terms {2}")
    @CsvSource({
            "value.equals, Equals, eq",
            "value.not-equals, Not Equals, 'neq ne'",
            "number.greater-than, Greater Than, gt",
            "number.greater-or-equal, Greater Or Equal, 'gte ge'",
            "number.less-than, Less Than, lt",
            "number.less-or-equal, Less Or Equal, 'lte le'"
    })
    void matcherCatalogExposesProfessionalNamesAndSearchTerms(
            final String primitive,
            final String displayName,
            final String searchTerms
    ) {
        final StepDefinition definition = StandardLibrary.catalog().find(primitive).orElseThrow();

        assertThat(definition.displayName()).isEqualTo(displayName);
        assertThat(definition.searchTerms()).containsExactly(searchTerms.split(" "));
    }

    @Test
    void fieldManipulationDeclaresOnlyGenericAuthoredInputs() {
        final StepDefinition definition = StandardLibrary.catalog()
                .find("railix.field-manipulation")
                .orElseThrow();

        assertThat(definition.version()).isEqualTo("2");
        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
        assertThat(definition.inputs()).extracting(StepDefinition.Field::name)
                .containsExactly("field", "value", "steps");
        assertThat(definition.inputs().get(0).input()).isInstanceOf(StepDefinition.PathInput.class);
        assertThat(definition.inputs().get(1).input()).isInstanceOf(StepDefinition.CandidatesInput.class);
        assertThat(definition.inputs().get(2).input()).isInstanceOf(StepDefinition.StepsInput.class);
        assertThat(definition.outcomes()).containsExactly("next");
    }

    @Test
    void filterDeclaresAnOrdinaryStepWithGenericConditions() {
        final StepDefinition definition = StandardLibrary.catalog()
                .find("railix.filter")
                .orElseThrow();

        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
        assertThat(definition.receives()).isEmpty();
        assertThat(definition.returns()).isEmpty();
        assertThat(definition.inputs()).extracting(StepDefinition.Field::name)
                .containsExactly("conditions");
        assertThat(definition.inputs().getFirst().input())
                .isInstanceOf(StepDefinition.CandidatesInput.class);
        assertThat(definition.outcomes()).containsExactly("match", "otherwise");
    }

    @Test
    void choiceDeclaresAnOrdinaryStepWithGenericMatcherGroups() {
        final StepDefinition definition = StandardLibrary.catalog()
                .find("railix.choice")
                .orElseThrow();

        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
        assertThat(definition.receives()).isEmpty();
        assertThat(definition.returns()).isEmpty();
        assertThat(definition.inputs()).extracting(StepDefinition.Field::name)
                .containsExactly("conditions");
        assertThat(definition.inputs().getFirst().input())
                .isInstanceOf(StepDefinition.MatcherGroupsInput.class);
        assertThat(definition.outcomes()).containsExactly("match", "otherwise");
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
                   "when":[{"use":"value.equals","inputs":{"expected":"never"}}]},
                  {"option":"literal","inputs":{"value":"fallback"}}
                ]
                """), "allow");

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void filterRejectsANonBooleanPredicateBeforeExecution() {
        final CompileResult compiled = ProjectCompiler.compile(filterProject("""
                [{"option":"literal","inputs":{"value":"HELLO"},
                  "when":[{"use":"text.lowercase","inputs":{}}]}]
                """), StandardLibrary.catalog());

        assertThat(compiled).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "PROJECT_CANDIDATE_PREDICATE_RESULT_INVALID",
                "Candidate predicate program must return boolean.",
                "nodes[2].inputs.conditions[0].when[0].use"
        ))));
    }

    @Test
    void repeatedFilterRunsRetainNoEarlierMatch() {
        final CompiledProject application = filterApplication(filterCondition());

        assertThat(List.of(
                run(application, "allow").context().values().get("result"),
                run(application, "deny").context().values().get("result")
        )).containsExactly(RailixValue.string("matched"), RailixValue.string("otherwise"));
    }

    @Test
    void missingFieldLeavesTheContextUnchangedAndContinues() {
        final CompileResult compiled = ProjectCompiler.compile(linearFieldProject("[]"), StandardLibrary.catalog());

        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final RunResult.Succeeded result = (RunResult.Succeeded) ((CompileResult.Compiled) compiled).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of("payload", RailixValue.object(Map.of()))))
        );

        assertThat(result.context().values().get("payload")).isEqualTo(RailixValue.object(Map.of()));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("continued"));
    }

    @Test
    void invalidPrimitiveLeavesTheFieldUnchangedAndContinues() {
        final CompileResult compiled = ProjectCompiler.compile(
                linearFieldProject("[{\"use\":\"text.to-number\",\"inputs\":{}}]"),
                StandardLibrary.catalog()
        );

        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final RunResult.Succeeded result = (RunResult.Succeeded) ((CompileResult.Compiled) compiled).project().run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("missing", RailixValue.string("not-a-number")))
                )))
        );

        assertThat(((RailixValue.ObjectValue) result.context().values().get("payload")).values().get("missing"))
                .isEqualTo(RailixValue.string("not-a-number"));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("continued"));
        assertThat(result.steps()).contains(new RunResult.StepExecution("text.to-number", "invalid"));
    }

    @Test
    void cliTriggerDeclaresOneInstanceAndSilentDefaults() {
        final StepDefinition definition = StandardLibrary.catalog()
                .find("railix.trigger.cli")
                .orElseThrow();

        assertThat(definition.maximumInstances()).isEqualTo(1);
        assertThat(definition.source()).contains(new StepDefinition.Source(
                "application.arguments",
                Map.of("output", "result", "status", "exit_code")
        ));
        assertThat(definition.receives()).containsExactly(
                new StepDefinition.Port("arguments", ValueShape.ARRAY)
        );
        assertThat(definition.inputs()).containsExactly(new StepDefinition.Field(
                "target",
                Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "payload", "arguments")
        ));
        assertThat(definition.outcomes()).containsExactly("next");
        assertThat(definition.examples()).containsExactly(
                new StepDefinition.Example(
                        "no-arguments",
                        RailixValue.array(List.of()),
                        RailixValue.object(Map.of())
                ),
                new StepDefinition.Example(
                        "one-argument",
                        RailixValue.array(List.of(RailixValue.string("railix"))),
                        RailixValue.object(Map.of())
                ),
                new StepDefinition.Example(
                        "multiple-arguments",
                        RailixValue.array(List.of(
                                RailixValue.string("hello"),
                                RailixValue.string("railix")
                        )),
                        RailixValue.object(Map.of())
                )
        );
        assertThat(definition.results()).containsExactly(
                new StepDefinition.Result("result", ValueShape.ANY, List.of(RailixValue.nullValue())),
                new StepDefinition.Result("exit_code", ValueShape.NUMBER, List.of(RailixValue.number(0)))
        );
    }

    @Test
    void secondCliTriggerIsRejectedAtTheSecondNode() {
        assertThat(ProjectCompiler.compile(twoCliProject(), StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_STEP_INSTANCE_LIMIT",
                        "Step railix.trigger.cli allows at most 1 instance.",
                        "nodes[2].use"
                ))));
    }

    @Test
    void cliTriggerWithoutResultWritesNothingAndExitsSuccessfully() {
        final CompiledProject application = ((CompileResult.Compiled) ProjectCompiler.compile(
                cliOnlyProject(),
                StandardLibrary.catalog()
        )).project();
        final RunResult.Succeeded result = (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context("[]"))
        );

        assertThat(result.context().values())
                .containsEntry("result", RailixValue.nullValue())
                .containsEntry("exit_code", RailixValue.number(0));
    }

    @Test
    void lowercaseUsesLocaleIndependentTextRules() {
        assertThat(result("text.lowercase", "\"Hello RAILIX\""))
                .isEqualTo(RailixValue.string("hello railix"));
    }

    @Test
    void uppercaseUsesRootLocaleRules() {
        assertThat(result("text.uppercase", "\"i\"")).isEqualTo(RailixValue.string("I"));
    }

    @Test
    void uppercaseRetainsUnicodeCaseExpansion() {
        assertThat(result("text.uppercase", "\"stra\\u00dfe\""))
                .isEqualTo(RailixValue.string("STRASSE"));
    }

    @Test
    void uppercaseKeepsEmptyTextEmpty() {
        assertThat(result("text.uppercase", "\"\"")).isEqualTo(RailixValue.string(""));
    }

    @Test
    void uppercaseDeclaresATotalStringContract() {
        assertTotalContract("text.uppercase", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void uppercaseRejectsANumberWithoutCoercion() {
        assertIncompatible("text.uppercase", "7", "string", "number");
    }

    @Test
    void repeatedUppercaseRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.uppercase", "\"one\"");

        assertThat(List.of(result(application, "\"one\""), result(application, "\"two\"")))
                .containsExactly(RailixValue.string("ONE"), RailixValue.string("TWO"));
    }

    @Test
    void uppercaseComposesWithLowercase() {
        assertThat(result(List.of("text.uppercase", "text.lowercase"), "\"RaIlIx\""))
                .isEqualTo(RailixValue.string("railix"));
    }

    @Test
    void trimUsesUnicodeStripRules() {
        assertThat(result("text.trim", "\"\\u2003Railix\\u2003\""))
                .isEqualTo(RailixValue.string("Railix"));
    }

    @Test
    void trimKeepsEmptyTextEmpty() {
        assertThat(result("text.trim", "\"\"")).isEqualTo(RailixValue.string(""));
    }

    @Test
    void trimPreservesInternalWhitespace() {
        assertThat(result("text.trim", "\"left\\t  right\""))
                .isEqualTo(RailixValue.string("left\t  right"));
    }

    @Test
    void trimDeclaresATotalStringContract() {
        assertTotalContract("text.trim", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void trimRejectsANumberWithoutCoercion() {
        assertIncompatible("text.trim", "7", "string", "number");
    }

    @Test
    void repeatedTrimRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.trim", "\" one \"");

        assertThat(List.of(result(application, "\" one \""), result(application, "\" two \\t\"")))
                .containsExactly(RailixValue.string("one"), RailixValue.string("two"));
    }

    @Test
    void trimComposesWithUppercase() {
        assertThat(result(List.of("text.trim", "text.uppercase"), "\" railix \""))
                .isEqualTo(RailixValue.string("RAILIX"));
    }

    @Test
    void normalizeSpaceUsesCharacterWhitespace() {
        assertThat(result("text.normalize-space", "\"left\\u2003right\""))
                .isEqualTo(RailixValue.string("left right"));
    }

    @Test
    void normalizeSpaceStripsOuterWhitespace() {
        assertThat(result("text.normalize-space", "\"\\t  Railix\\n\""))
                .isEqualTo(RailixValue.string("Railix"));
    }

    @Test
    void normalizeSpaceCollapsesInternalWhitespaceRuns() {
        assertThat(result("text.normalize-space", "\"left\\t \\nright\""))
                .isEqualTo(RailixValue.string("left right"));
    }

    @Test
    void normalizeSpaceTurnsOnlyWhitespaceIntoEmptyText() {
        assertThat(result("text.normalize-space", "\"\\t \\u2003\\n\""))
                .isEqualTo(RailixValue.string(""));
    }

    @Test
    void normalizeSpaceDeclaresATotalStringContract() {
        assertTotalContract("text.normalize-space", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void normalizeSpaceRejectsANumberWithoutCoercion() {
        assertIncompatible("text.normalize-space", "7", "string", "number");
    }

    @Test
    void repeatedNormalizeSpaceRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.normalize-space", "\"one  value\"");

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
    void normalizeNfcComposesDecomposedText() {
        assertThat(result("text.normalize-nfc", "\"e\\u0301\""))
                .isEqualTo(RailixValue.string("\u00e9"));
    }

    @Test
    void normalizeNfcKeepsNormalizedTextUnchanged() {
        assertThat(result("text.normalize-nfc", "\"\\u00e9\""))
                .isEqualTo(RailixValue.string("\u00e9"));
    }

    @Test
    void normalizeNfcDeclaresATotalStringContract() {
        assertTotalContract("text.normalize-nfc", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void normalizeNfcRejectsANumberWithoutCoercion() {
        assertIncompatible("text.normalize-nfc", "7", "string", "number");
    }

    @Test
    void repeatedNormalizeNfcRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.normalize-nfc", "\"e\\u0301\"");

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
    void textLengthCountsUnicodeCodePoints() {
        assertThat(result("text.length", "\"Railix\"")).isEqualTo(RailixValue.number(6));
    }

    @Test
    void textLengthCountsASupplementaryCharacterOnce() {
        assertThat(result("text.length", "\"A\\ud83d\\ude80B\""))
                .isEqualTo(RailixValue.number(3));
    }

    @Test
    void textLengthOfEmptyTextIsZero() {
        assertThat(result("text.length", "\"\"")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void textLengthDeclaresATotalStringToNumberContract() {
        assertTotalContract("text.length", ValueShape.STRING, ValueShape.NUMBER);
    }

    @Test
    void textLengthRejectsANumberWithoutCoercion() {
        assertIncompatible("text.length", "7", "string", "number");
    }

    @Test
    void repeatedTextLengthRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.length", "\"one\"");

        assertThat(List.of(result(application, "\"one\""), result(application, "\"twelve\"")))
                .containsExactly(RailixValue.number(3), RailixValue.number(6));
    }

    @Test
    void textLengthComposesWithNumberSign() {
        assertThat(result(List.of("text.length", "number.sign"), "\"Railix\""))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void textIsEmptyReturnsTrueForEmptyText() {
        assertThat(result("text.is-empty", "\"\"")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textIsEmptyReturnsFalseForWhitespace() {
        assertThat(result("text.is-empty", "\" \"")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void textIsEmptyDeclaresATotalStringToBooleanContract() {
        assertTotalContract("text.is-empty", ValueShape.STRING, ValueShape.BOOLEAN);
    }

    @Test
    void textIsEmptyRejectsANumberWithoutCoercion() {
        assertIncompatible("text.is-empty", "7", "string", "number");
    }

    @Test
    void repeatedTextIsEmptyRunsRetainNoEarlierText() {
        final CompiledProject application = application("text.is-empty", "\"\"");

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
    void textContainsDeclaresADefaultEmptyStringConfiguration() {
        final StepDefinition definition = StandardLibrary.catalog().find("text.contains").orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", ValueShape.STRING)),
                List.of(new StepDefinition.Port("value", ValueShape.BOOLEAN)),
                List.of(new StepDefinition.Field(
                        "needle",
                        Input.json(ValueShape.STRING).defaultValue(RailixValue.string(""))
                )),
                List.of("ok")
        );
    }

    @Test
    void textContainsUsesAnEmptyNeedleWhenConfigurationIsOmitted() {
        assertThat(configuredResult("text.contains", "{}", "\"Railix\""))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsRejectsWrongConfigurationShape() {
        assertConfiguredRejection(
                "text.contains",
                "{\"needle\":7}",
                "PROJECT_INPUT_SHAPE_INVALID",
                "Step input must be string.",
                "nodes[2].inputs.steps[0].inputs.needle"
        );
    }

    @Test
    void textContainsRejectsUnknownConfiguration() {
        assertConfiguredRejection(
                "text.contains",
                "{\"needle\":\"Rail\",\"noise\":true}",
                "PROJECT_INPUT_UNKNOWN",
                "Step input is not declared: noise.",
                "nodes[2].inputs.steps[0].inputs.noise"
        );
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
        final CompiledProject application = configuredApplication(
                List.of("text.contains"),
                "text.contains",
                "{\"needle\":\"7\"}",
                "\"Railix\""
        );

        assertThat(application.run(
                "command",
                new CompiledProject.StreamItem(false, context("7"))
        )).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step text.contains requires string but receives number.",
                "nodes[2].inputs.steps[0]"
        )), List.of()));
    }

    @Test
    void repeatedTextContainsRunsRetainNoEarlierInput() {
        final CompiledProject application = configuredApplication(
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
        final CompiledProject application = configuredApplication(
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

    @ParameterizedTest(name = "{0} declares default empty {1}")
    @CsvSource({
            "text.starts-with, prefix",
            "text.ends-with, suffix"
    })
    void textBoundaryDeclaresADefaultEmptyStringConfiguration(
            final String primitive,
            final String input
    ) {
        final StepDefinition definition = StandardLibrary.catalog().find(primitive).orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", ValueShape.STRING)),
                List.of(new StepDefinition.Port("value", ValueShape.BOOLEAN)),
                List.of(new StepDefinition.Field(
                        input,
                        Input.json(ValueShape.STRING).defaultValue(RailixValue.string(""))
                )),
                List.of("ok")
        );
    }

    @ParameterizedTest(name = "{0} uses an empty boundary when configuration is omitted")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryUsesAnEmptyStringWhenConfigurationIsOmitted(final String primitive) {
        assertThat(configuredResult(primitive, "{}", "\"Railix\""))
                .isEqualTo(RailixValue.bool(true));
    }

    @ParameterizedTest(name = "{0} rejects wrong configuration shape")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryRejectsWrongConfigurationShape(final String primitive) {
        final String input = textBoundaryInput(primitive);

        assertConfiguredRejection(
                primitive,
                "{\"" + input + "\":7}",
                "PROJECT_INPUT_SHAPE_INVALID",
                "Step input must be string.",
                "nodes[2].inputs.steps[0].inputs." + input
        );
    }

    @ParameterizedTest(name = "{0} rejects unknown configuration")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void textBoundaryRejectsUnknownConfiguration(final String primitive) {
        final String input = textBoundaryInput(primitive);

        assertConfiguredRejection(
                primitive,
                "{\"" + input + "\":\"Railix\",\"noise\":true}",
                "PROJECT_INPUT_UNKNOWN",
                "Step input is not declared: noise.",
                "nodes[2].inputs.steps[0].inputs.noise"
        );
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
        final CompiledProject application = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"" + input + "\":\"Railix\"}",
                "\"Railix\""
        );

        assertThat(application.run(
                "command",
                new CompiledProject.StreamItem(false, context("7"))
        )).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step " + primitive + " requires string but receives number.",
                "nodes[2].inputs.steps[0]"
        )), List.of()));
    }

    @ParameterizedTest(name = "{0} retains no earlier input")
    @ValueSource(strings = {"text.starts-with", "text.ends-with"})
    void repeatedTextBoundaryRunsRetainNoEarlierInput(final String primitive) {
        final String input = textBoundaryInput(primitive);
        final CompiledProject application = configuredApplication(
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
        final CompiledProject application = configuredApplication(
                List.of(primitive, "boolean.not"),
                primitive,
                "{\"" + input + "\":\"Railix\"}",
                "\"Railix\""
        );

        assertThat(result(application, "\"Railix\"")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void floorRoundsAPositiveFractionDown() {
        assertThat(result("number.floor", "1.9")).isEqualTo(RailixValue.number(1));
    }

    @Test
    void floorRoundsANegativeFractionDown() {
        assertThat(result("number.floor", "-1.2")).isEqualTo(RailixValue.number(-2));
    }

    @Test
    void floorKeepsAWholeNumber() {
        assertThat(result("number.floor", "7")).isEqualTo(RailixValue.number(7));
    }

    @Test
    void floorHandlesAnArbitrarilyPreciseDecimal() {
        assertThat(result("number.floor", "1.0000000000000000000000000000000000001"))
                .isEqualTo(RailixValue.number(1));
    }

    @Test
    void floorCarryAtTheMaximumMagnitudeRemainsInDomain() {
        final String digits = "9".repeat(1_022);

        assertThat(RailixJson.write(result("number.floor", "-" + digits + ".9")))
                .isEqualTo("-1" + "0".repeat(1_022));
    }

    @Test
    void ceilRoundsAPositiveFractionUp() {
        assertThat(result("number.ceil", "1.2")).isEqualTo(RailixValue.number(2));
    }

    @Test
    void ceilRoundsANegativeFractionUp() {
        assertThat(result("number.ceil", "-1.2")).isEqualTo(RailixValue.number(-1));
    }

    @Test
    void ceilKeepsAWholeNumber() {
        assertThat(result("number.ceil", "7")).isEqualTo(RailixValue.number(7));
    }

    @Test
    void ceilHandlesAnArbitrarilyPreciseDecimal() {
        assertThat(result("number.ceil", "1.0000000000000000000000000000000000001"))
                .isEqualTo(RailixValue.number(2));
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
        final CompiledProject application = application("number.ceil", "1.1");

        assertThat(List.of(result(application, "1.1"), result(application, "9.1")))
                .containsExactly(RailixValue.number(2), RailixValue.number(10));
    }

    @Test
    void roundMovesAPositiveExactHalfAwayFromZero() {
        assertThat(result("number.round", "1.5")).isEqualTo(RailixValue.number(2));
    }

    @Test
    void roundMovesANegativeExactHalfAwayFromZero() {
        assertThat(result("number.round", "-1.5")).isEqualTo(RailixValue.number(-2));
    }

    @Test
    void roundMovesAValueBelowHalfTowardZero() {
        assertThat(result("number.round", "1.49")).isEqualTo(RailixValue.number(1));
    }

    @Test
    void roundMovesAPositiveValueAboveHalfAwayFromZero() {
        assertThat(result("number.round", "1.51")).isEqualTo(RailixValue.number(2));
    }

    @Test
    void roundMovesANegativeValueBelowHalfTowardZero() {
        assertThat(result("number.round", "-1.49")).isEqualTo(RailixValue.number(-1));
    }

    @Test
    void roundMovesANegativeValueAboveHalfAwayFromZero() {
        assertThat(result("number.round", "-1.51")).isEqualTo(RailixValue.number(-2));
    }

    @Test
    void roundKeepsAWholeNumber() {
        assertThat(result("number.round", "7")).isEqualTo(RailixValue.number(7));
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
        final CompiledProject application = application("number.round", "1.4");

        assertThat(List.of(result(application, "1.4"), result(application, "9.6")))
                .containsExactly(RailixValue.number(1), RailixValue.number(10));
    }

    @Test
    void absoluteValueMakesANegativeNumberPositive() {
        assertThat(result("number.abs", "-12.5"))
                .isEqualTo(RailixValue.number(new BigDecimal("12.5")));
    }

    @Test
    void absoluteValueKeepsAPositiveNumberUnchanged() {
        assertThat(result("number.abs", "12.50"))
                .isEqualTo(RailixValue.number(new BigDecimal("12.50")));
    }

    @Test
    void absoluteValueKeepsZeroUnchanged() {
        assertThat(result("number.abs", "0.00"))
                .isEqualTo(RailixValue.number(new BigDecimal("0.00")));
    }

    @Test
    void absoluteValueRejectsTextWithoutCoercion() {
        assertIncompatible("number.abs", "\"7\"", "number", "string");
    }

    @Test
    void repeatedAbsoluteValueRunsRetainNoEarlierValue() {
        final CompiledProject application = application("number.abs", "-1");

        assertThat(List.of(result(application, "-1"), result(application, "-9")))
                .containsExactly(RailixValue.number(1), RailixValue.number(9));
    }

    @Test
    void negateMakesAPositiveNumberNegative() {
        assertThat(result("number.negate", "12.5"))
                .isEqualTo(RailixValue.number(new BigDecimal("-12.5")));
    }

    @Test
    void negateMakesANegativeNumberPositive() {
        assertThat(result("number.negate", "-12.5"))
                .isEqualTo(RailixValue.number(new BigDecimal("12.5")));
    }

    @Test
    void negateReturnsCanonicalZero() {
        assertThat(result("number.negate", "0.00")).isEqualTo(RailixValue.number(0));
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
        final CompiledProject application = application("number.negate", "1");

        assertThat(List.of(result(application, "1"), result(application, "-9")))
                .containsExactly(RailixValue.number(-1), RailixValue.number(9));
    }

    @Test
    void signReturnsNegativeOneForANegativeNumber() {
        assertThat(result("number.sign", "-0.1")).isEqualTo(RailixValue.number(-1));
    }

    @Test
    void signReturnsZeroForZero() {
        assertThat(result("number.sign", "0.00")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void signReturnsPositiveOneForAPositiveNumber() {
        assertThat(result("number.sign", "0.1")).isEqualTo(RailixValue.number(1));
    }

    @Test
    void signRejectsTextWithoutCoercion() {
        assertIncompatible("number.sign", "\"7\"", "number", "string");
    }

    @Test
    void repeatedSignRunsRetainNoEarlierValue() {
        final CompiledProject application = application("number.sign", "-1");

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

    @ParameterizedTest(name = "{0} declares a zero-defaulted numeric comparison contract")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonDeclaresATotalZeroDefaultedConfigurationContract(final String primitive) {
        final StepDefinition definition = StandardLibrary.catalog().find(primitive).orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", ValueShape.NUMBER)),
                List.of(new StepDefinition.Port("value", ValueShape.BOOLEAN)),
                List.of(new StepDefinition.Field(
                        "than",
                        Input.json(ValueShape.NUMBER).defaultValue(RailixValue.number(0))
                )),
                List.of("ok")
        );
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

    @ParameterizedTest(name = "{0} rejects string than configuration")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonRejectsWrongConfigurationShape(final String primitive) {
        assertConfiguredRejection(
                primitive,
                "{\"than\":\"5\"}",
                "PROJECT_INPUT_SHAPE_INVALID",
                "Step input must be number.",
                "nodes[2].inputs.steps[0].inputs.than"
        );
    }

    @ParameterizedTest(name = "{0} rejects unknown configuration")
    @ValueSource(strings = {
            "number.greater-than",
            "number.greater-or-equal",
            "number.less-than",
            "number.less-or-equal"
    })
    void numberComparisonRejectsUnknownConfiguration(final String primitive) {
        assertConfiguredRejection(
                primitive,
                "{\"noise\":true,\"than\":5}",
                "PROJECT_INPUT_UNKNOWN",
                "Step input is not declared: noise.",
                "nodes[2].inputs.steps[0].inputs.noise"
        );
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
        final RunResult result = configuredApplication(
                List.of(primitive),
                primitive,
                "{\"than\":5}",
                "6"
        ).run(
                "command",
                new CompiledProject.StreamItem(false, context("\"6\""))
        );

        assertThat(result).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step " + primitive + " requires number but receives string.",
                "nodes[2].inputs.steps[0]"
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
        final CompiledProject application = configuredApplication(
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
        final CompiledProject application = configuredApplication(
                List.of("number.abs", primitive, "boolean.not"),
                primitive,
                "{\"than\":5}",
                input
        );

        assertThat(result(application, input)).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void booleanNotChangesTrueToFalse() {
        assertThat(result("boolean.not", "true")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void booleanNotChangesFalseToTrue() {
        assertThat(result("boolean.not", "false")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void listSizeCountsHeterogeneousItems() {
        assertThat(result("list.size", "[1,\"two\",true]")).isEqualTo(RailixValue.number(3));
    }

    @Test
    void listSizeOfAnEmptyListIsZero() {
        assertThat(result("list.size", "[]")).isEqualTo(RailixValue.number(0));
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
    void listIsEmptyReturnsTrueForAnEmptyList() {
        assertThat(result("list.is-empty", "[]")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void listIsEmptyReturnsFalseForANonEmptyList() {
        assertThat(result("list.is-empty", "[null]")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void listIsEmptyDeclaresATotalArrayToBooleanContract() {
        assertTotalContract("list.is-empty", ValueShape.ARRAY, ValueShape.BOOLEAN);
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
    void listIsEmptyRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("list.is-empty", "[]");
    }

    @Test
    void listIsEmptyDoesNotReadOrPropagateAnInvalidDescendant() {
        final CompiledProject application = application("list.is-empty", "[]");
        final RailixValue.ObjectValue context = RailixValue.object(Map.of(
                "payload",
                RailixValue.object(Map.of(
                        "value",
                        RailixValue.array(List.of(RailixValue.string("\ud800")))
                ))
        ));
        final RunResult.Succeeded result = (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context)
        );

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void repeatedListIsEmptyRunsRetainNoEarlierList() {
        final CompiledProject application = application("list.is-empty", "[]");

        assertThat(List.of(result(application, "[]"), result(application, "[1]")))
                .containsExactly(RailixValue.bool(true), RailixValue.bool(false));
    }

    @Test
    void listIsEmptyComposesWithBooleanNot() {
        assertThat(result(List.of("list.is-empty", "boolean.not"), "[]"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void zeroIsAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "0")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void aNegativeWholeNumberIsAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "-1")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void minimumLongIsAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", Long.toString(Long.MIN_VALUE)))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void maximumLongIsAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", Long.toString(Long.MAX_VALUE)))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void aFractionIsNotAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "1.1")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void anIntegralDecimalIsAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "1.0")).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void aFractionalExponentIsNotAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "1E-1")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void aNumberAboveLongRangeIsNotAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "9223372036854775808"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void aNumberBelowLongRangeIsNotAUtcMillisecondTimestamp() {
        assertThat(result("date.is-utc-millis", "-9223372036854775809"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void booleanToTextReturnsLowercaseTrue() {
        assertThat(result("boolean.to-text", "true")).isEqualTo(RailixValue.string("true"));
    }

    @Test
    void booleanToTextReturnsLowercaseFalse() {
        assertThat(result("boolean.to-text", "false")).isEqualTo(RailixValue.string("false"));
    }

    @Test
    void booleanToTextDeclaresATotalBooleanToStringContract() {
        assertTotalContract("boolean.to-text", ValueShape.BOOLEAN, ValueShape.STRING);
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
    void booleanToTextRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("boolean.to-text", "true");
    }

    @Test
    void repeatedBooleanToTextRunsRetainNoEarlierBoolean() {
        final CompiledProject application = application("boolean.to-text", "true");

        assertThat(List.of(result(application, "true"), result(application, "false")))
                .containsExactly(RailixValue.string("true"), RailixValue.string("false"));
    }

    @Test
    void booleanToTextComposesWithUppercase() {
        assertThat(result(List.of("boolean.to-text", "text.uppercase"), "true"))
                .isEqualTo(RailixValue.string("TRUE"));
    }

    @Test
    void booleanToNumberReturnsOneForTrue() {
        assertThat(result("boolean.to-number", "true")).isEqualTo(RailixValue.number(1));
    }

    @Test
    void booleanToNumberReturnsZeroForFalse() {
        assertThat(result("boolean.to-number", "false")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void booleanToNumberDeclaresATotalBooleanToNumberContract() {
        assertTotalContract("boolean.to-number", ValueShape.BOOLEAN, ValueShape.NUMBER);
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
    void booleanToNumberRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("boolean.to-number", "true");
    }

    @Test
    void repeatedBooleanToNumberRunsRetainNoEarlierBoolean() {
        final CompiledProject application = application("boolean.to-number", "true");

        assertThat(List.of(result(application, "true"), result(application, "false")))
                .containsExactly(RailixValue.number(1), RailixValue.number(0));
    }

    @Test
    void booleanToNumberComposesWithNumberNegate() {
        assertThat(result(List.of("boolean.to-number", "number.negate"), "true"))
                .isEqualTo(RailixValue.number(-1));
    }

    @Test
    void valueEqualsMatchesNestedJsonRecursively() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":{\"items\":[1,{\"active\":true}]}}",
                "{\"items\":[1,{\"active\":true}]}"
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsTreatsArrayOrderAsSignificant() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":[1,2]}",
                "[2,1]"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsIgnoresObjectFieldOrder() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":{\"first\":1,\"second\":2}}",
                "{\"second\":2,\"first\":1}"
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsComparesNumbersByValueRatherThanScale() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":1.0}",
                "1.00"
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsRejectsDifferentJsonTypes() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":1}",
                "\"1\""
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsMatchesJsonNull() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":null}",
                "null"
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsRejectsDifferentBooleans() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":true}",
                "false"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsMatchesExactText() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":\"Railix\"}",
                "\"Railix\""
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsRejectsDifferentText() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":\"Railix\"}",
                "\"Other\""
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsRejectsDifferentArrayLengths() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":[1]}",
                "[1,2]"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsRejectsDifferentObjectFields() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":{\"first\":1}}",
                "{\"second\":1}"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsRejectsDifferentNestedObjectValues() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":{\"user\":{\"id\":1}}}",
                "{\"user\":{\"id\":2}}"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsRejectsNullAgainstAValue() {
        assertThat(configuredResult(
                "value.equals",
                "{\"expected\":null}",
                "false"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueEqualsDeclaresADefaultJsonNullConfiguration() {
        final StepDefinition definition = StandardLibrary.catalog().find("value.equals").orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", ValueShape.ANY)),
                List.of(new StepDefinition.Port("value", ValueShape.BOOLEAN)),
                List.of(new StepDefinition.Field(
                        "expected",
                        Input.json(ValueShape.ANY).defaultValue(RailixValue.nullValue())
                )),
                List.of("ok")
        );
    }

    @Test
    void valueEqualsUsesJsonNullWhenConfigurationIsOmitted() {
        assertThat(configuredResult("value.equals", "{}", "null"))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsRejectsUnknownConfiguration() {
        assertConfiguredRejection(
                "value.equals",
                "{\"expected\":1,\"noise\":true}",
                "PROJECT_INPUT_UNKNOWN",
                "Step input is not declared: noise.",
                "nodes[2].inputs.steps[0].inputs.noise"
        );
    }

    @Test
    void repeatedValueEqualsRunsRetainNoEarlierInput() {
        final CompiledProject application = configuredApplication(
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
        final CompiledProject application = configuredApplication(
                List.of("value.equals", "boolean.not"),
                "value.equals",
                "{\"expected\":1}",
                "1"
        );

        assertThat(result(application, "1.00")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueNotEqualsReturnsFalseForRecursivelyEqualNestedJson() {
        assertThat(configuredResult(
                "value.not-equals",
                "{\"expected\":{\"items\":[1,{\"active\":true}]}}",
                "{\"items\":[1,{\"active\":true}]}"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueNotEqualsReturnsTrueForDifferentNestedJson() {
        assertThat(configuredResult(
                "value.not-equals",
                "{\"expected\":{\"items\":[1,{\"active\":true}]}}",
                "{\"items\":[1,{\"active\":false}]}"
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueNotEqualsTreatsNumbersWithDifferentScalesAsEqual() {
        assertThat(configuredResult(
                "value.not-equals",
                "{\"expected\":1.0}",
                "1.00"
        )).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueNotEqualsReturnsTrueForDifferentJsonTypes() {
        assertThat(configuredResult(
                "value.not-equals",
                "{\"expected\":1}",
                "\"1\""
        )).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueNotEqualsDeclaresADefaultJsonNullConfiguration() {
        final StepDefinition definition = StandardLibrary.catalog().find("value.not-equals").orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", ValueShape.ANY)),
                List.of(new StepDefinition.Port("value", ValueShape.BOOLEAN)),
                List.of(new StepDefinition.Field(
                        "expected",
                        Input.json(ValueShape.ANY).defaultValue(RailixValue.nullValue())
                )),
                List.of("ok")
        );
    }

    @Test
    void valueNotEqualsUsesJsonNullWhenConfigurationIsOmitted() {
        assertThat(configuredResult("value.not-equals", "{}", "null"))
                .isEqualTo(RailixValue.bool(false));
    }

    @Test
    void valueNotEqualsMatchesANonNullValueWhenConfigurationIsOmitted() {
        assertThat(configuredResult("value.not-equals", "{}", "false"))
                .isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueNotEqualsRejectsUnknownConfiguration() {
        assertConfiguredRejection(
                "value.not-equals",
                "{\"expected\":1,\"noise\":true}",
                "PROJECT_INPUT_UNKNOWN",
                "Step input is not declared: noise.",
                "nodes[2].inputs.steps[0].inputs.noise"
        );
    }

    @Test
    void repeatedValueNotEqualsRunsRetainNoEarlierInput() {
        final CompiledProject application = configuredApplication(
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
        final CompiledProject application = configuredApplication(
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
    void listReverseDeclaresCanonicalArrayDepthClosure() {
        assertRefinedTotalContract(
                "list.reverse",
                new StepDefinition.Port(
                        "value", ValueShape.ARRAY, ValueRefinement.canonical().withMaxDepth(64)),
                new StepDefinition.Port(
                        "value", ValueShape.ARRAY, ValueRefinement.canonical().withMaxDepth(64))
        );
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
    void listReverseRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("list.reverse", "[]");
    }

    @Test
    void listReverseRejectsANoncanonicalDescendantAtRuntime() {
        final RailixValue invalid = RailixValue.array(List.of(RailixValue.string("\uD800")));

        assertRuntimeRefinementIncompatible(
                "list.reverse",
                "[]",
                invalid,
                "Value contains an unpaired Unicode surrogate."
        );
    }

    @Test
    void listReverseAcceptsTheMaximumCanonicalDepth() {
        assertThat(result(application("list.reverse", "[]"), nestedArrays(64)))
                .isEqualTo(nestedArrays(64));
    }

    @Test
    void listReverseRejectsTheNextContainerDepth() {
        assertRuntimeRefinementIncompatible(
                "list.reverse",
                "[]",
                nestedArrays(65),
                "Value exceeds maximum container depth 64."
        );
    }

    @Test
    void repeatedListReverseRunsRetainNoEarlierList() {
        final CompiledProject application = application("list.reverse", "[]");

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
    void numberToTextUsesCanonicalPlainJsonNumberForm() {
        assertThat(result("number.to-text", "1.2300")).isEqualTo(RailixValue.string("1.23"));
    }

    @Test
    void numberToTextExpandsExponentNotation() {
        assertThat(result("number.to-text", "1e3")).isEqualTo(RailixValue.string("1000"));
    }

    @Test
    void numberToTextCanonicalizesNegativeZero() {
        assertThat(result("number.to-text", "-0.0")).isEqualTo(RailixValue.string("0"));
    }

    @Test
    void numberToTextDeclaresCanonicalNumberIngress() {
        assertRefinedTotalContract(
                "number.to-text",
                new StepDefinition.Port("value", ValueShape.NUMBER, ValueRefinement.canonical()),
                new StepDefinition.Port("value", ValueShape.STRING, ValueRefinement.canonical())
        );
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
    void numberToTextRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("number.to-text", "1");
    }

    @Test
    void numberToTextRejectsAProgrammaticNumberOutsideTheCanonicalDomain() {
        assertRuntimeRefinementIncompatible(
                "number.to-text",
                "1",
                RailixValue.number(BigDecimal.TEN.pow(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)),
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
        final CompiledProject application = application("number.to-text", "1");

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
    void valueWrapListDeclaresOneContainerOfDepthHeadroom() {
        assertRefinedTotalContract(
                "value.wrap-list",
                new StepDefinition.Port(
                        "value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(63)),
                new StepDefinition.Port(
                        "value", ValueShape.ARRAY, ValueRefinement.canonical().withMaxDepth(64))
        );
    }

    @Test
    void valueWrapListRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("value.wrap-list", "null");
    }

    @Test
    void valueWrapListAcceptsInputAtItsMaximumDepth() {
        assertThat(result(application("value.wrap-list", "null"), nestedArrays(63)))
                .isEqualTo(nestedArrays(64));
    }

    @Test
    void valueWrapListRejectsInputWithoutContainerHeadroom() {
        assertRuntimeRefinementIncompatible(
                "value.wrap-list",
                "null",
                nestedArrays(64),
                "Value exceeds maximum container depth 63."
        );
    }

    @Test
    void valueWrapListRejectsANoncanonicalDescendantAtRuntime() {
        final RailixValue invalid = RailixValue.object(Map.of("value", RailixValue.string("\uD800")));

        assertRuntimeRefinementIncompatible(
                "value.wrap-list",
                "null",
                invalid,
                "Value contains an unpaired Unicode surrogate."
        );
    }

    @Test
    void repeatedValueWrapListRunsRetainNoEarlierValue() {
        final CompiledProject application = application("value.wrap-list", "null");

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
    void valueToJsonDeclaresBoundedCanonicalInputAndOutput() {
        assertRefinedTotalContract(
                "value.to-json",
                new StepDefinition.Port(
                        "value",
                        ValueShape.ANY,
                        ValueRefinement.canonical()
                                .withMaxDepth(64)
                                .withMaxJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES)
                ),
                new StepDefinition.Port(
                        "value",
                        ValueShape.STRING,
                        ValueRefinement.canonical().withMaxJsonBytes(
                                RailixData.DEFAULT_MAX_SOURCE_BYTES * 2 + 2)
                )
        );
    }

    @Test
    void valueToJsonRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("value.to-json", "null");
    }

    @Test
    void valueToJsonAcceptsItsExactCanonicalJsonByteLimit() {
        final String text = "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2);

        assertThat(result(application("value.to-json", "null"), RailixValue.string(text)))
                .isEqualTo(RailixValue.string(RailixJson.write(RailixValue.string(text))));
    }

    @Test
    void valueToJsonAcceptsItsWorstCaseEscapedOutputByteLimit() {
        final String text = "\\".repeat((RailixData.DEFAULT_MAX_SOURCE_BYTES - 2) / 2);
        final RailixValue input = RailixValue.string(text);
        final String inputJson = RailixJson.write(input);
        final RailixValue output = result(application("value.to-json", "null"), input);

        assertThat(inputJson.getBytes(StandardCharsets.UTF_8))
                .hasSize(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        assertThat(output).isEqualTo(RailixValue.string(inputJson));
        assertThat(RailixJson.write(output).getBytes(StandardCharsets.UTF_8))
                .hasSize(RailixData.DEFAULT_MAX_SOURCE_BYTES * 2 + 2);
    }

    @Test
    void valueToJsonRejectsTheNextCanonicalJsonByte() {
        final String text = "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 1);

        assertRuntimeRefinementIncompatible(
                "value.to-json",
                "null",
                RailixValue.string(text),
                "Canonical JSON exceeds 1048576 bytes."
        );
    }

    @Test
    void valueToJsonAcceptsTheMaximumCanonicalDepth() {
        final RailixValue value = nestedArrays(64);

        assertThat(result(application("value.to-json", "null"), value))
                .isEqualTo(RailixValue.string(RailixJson.write(value)));
    }

    @Test
    void valueToJsonRejectsTheNextContainerDepth() {
        assertRuntimeRefinementIncompatible(
                "value.to-json",
                "null",
                nestedArrays(65),
                "Value exceeds maximum container depth 64."
        );
    }

    @Test
    void repeatedValueToJsonRunsRetainNoEarlierValue() {
        final CompiledProject application = application("value.to-json", "null");

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
    void textToNumberDeclaresTheFallibleStringToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("text.to-number").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.STRING));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.outcomes()).containsExactly("ok", "invalid");
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
        final CompiledProject application = fallibleApplication(List.of("text.to-number"), "1");

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
        final CompileResult compiled = ProjectCompiler.compile(
                fallibleProject(List.of("text.to-number"), "7"),
                StandardLibrary.catalog()
        );

        assertRuntimeIncompatible(
                ((CompileResult.Compiled) compiled).project(),
                "text.to-number",
                "7",
                "string",
                "number"
        );
    }

    @Test
    void listSumAddsExactNumbers() {
        assertThat(collectionResult("list.sum", "{}", "[1,2.5,-0.5]"))
                .isEqualTo(RailixValue.number(new BigDecimal("3.0")));
    }

    @Test
    void listSumDeclaresTheFallibleArrayToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("list.sum").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.ARRAY));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.inputs()).isEmpty();
        assertThat(definition.outcomes()).containsExactly("ok", "invalid");
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
        final CompiledProject application = collectionApplication("list.sum", "{}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[1,2]").context().values().get("result"),
                collectionRun(application, "[10,20]").context().values().get("result")
        )).containsExactly(RailixValue.number(3), RailixValue.number(30));
    }

    @Test
    void listMinReturnsTheFirstExistingLeastNumber() {
        assertThat(collectionResult("list.min", "{}", "[2,1.0,1,3]"))
                .isEqualTo(RailixValue.number(new BigDecimal("1.0")));
    }

    @Test
    void listMinDeclaresTheEmptyAwareArrayToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("list.min").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.ARRAY));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.inputs()).isEmpty();
        assertThat(definition.outcomes()).containsExactly("ok", "empty", "invalid");
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
        final CompiledProject application = collectionApplication("list.min", "{}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[3,2]").context().values().get("result"),
                collectionRun(application, "[9,8]").context().values().get("result")
        )).containsExactly(RailixValue.number(2), RailixValue.number(8));
    }

    @Test
    void listMaxReturnsTheFirstExistingGreatestNumber() {
        assertThat(collectionResult("list.max", "{}", "[2,3.0,3,1]"))
                .isEqualTo(RailixValue.number(new BigDecimal("3.0")));
    }

    @Test
    void listMaxDeclaresTheEmptyAwareArrayToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("list.max").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.ARRAY));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.inputs()).isEmpty();
        assertThat(definition.outcomes()).containsExactly("ok", "empty", "invalid");
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
        final CompiledProject application = collectionApplication("list.max", "{}", "[1]");

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
    void listPercentileDeclaresItsEmptyAwareContractAndDefault() {
        final StepDefinition definition =
                StandardLibrary.catalog().find("list.percentile").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.ARRAY));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.inputs()).hasSize(1);
        assertThat(definition.inputs().getFirst().name()).isEqualTo("percentile");
        final StepDefinition.JsonInput percentile =
                (StepDefinition.JsonInput) definition.inputs().getFirst().input();
        assertThat(percentile.shape()).isEqualTo(ValueShape.NUMBER);
        assertThat(percentile.defaultValue()).contains(RailixValue.number(95));
        assertThat(definition.outcomes()).containsExactly("ok", "empty", "invalid");
    }

    @Test
    void listPercentileRangeIsMachineReadable() {
        final StepDefinition.JsonInput config = (StepDefinition.JsonInput) StandardLibrary.catalog()
                .find("list.percentile")
                .orElseThrow()
                .inputs()
                .getFirst()
                .input();

        assertThat(config.range()).containsExactly(RailixValue.number(0), RailixValue.number(100));
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
        final CompiledProject application =
                collectionApplication("list.percentile", "{\"percentile\":50}", "[1]");

        assertThat(List.of(
                collectionRun(application, "[4,1,3,2]").context().values().get("result"),
                collectionRun(application, "[40,10,30,20]").context().values().get("result")
        )).containsExactly(RailixValue.number(2), RailixValue.number(20));
    }

    @Test
    void listPercentileRejectsConfigurationBelowZeroBeforeExecution() {
        assertThat(collectionCompile("list.percentile", "{\"percentile\":-0.1}", "[1]"))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_INPUT_RANGE_INVALID",
                        "Step input must be from 0 through 100.",
                        "nodes[2].inputs.steps[0].inputs.percentile"
                ))));
    }

    @Test
    void listPercentileRejectsConfigurationAboveOneHundredBeforeExecution() {
        assertThat(collectionCompile("list.percentile", "{\"percentile\":100.1}", "[1]"))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_INPUT_RANGE_INVALID",
                        "Step input must be from 0 through 100.",
                        "nodes[2].inputs.steps[0].inputs.percentile"
                ))));
    }

    @Test
    void listPercentileRejectsTextConfigurationBeforeExecution() {
        assertThat(collectionCompile("list.percentile", "{\"percentile\":\"95\"}", "[1]"))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_INPUT_SHAPE_INVALID",
                        "Step input must be number.",
                        "nodes[2].inputs.steps[0].inputs.percentile"
                ))));
    }

    @Test
    void listPercentileRejectsUnknownConfigurationBeforeExecution() {
        assertThat(collectionCompile("list.percentile", "{\"noise\":95}", "[1]"))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_INPUT_UNKNOWN",
                        "Step input is not declared: noise.",
                        "nodes[2].inputs.steps[0].inputs.noise"
                ))));
    }

    @Test
    void listPercentileNeedsNoEmptyGraphConnection() {
        final CompileResult result = ProjectCompiler.compile(
                collectionProject("list.percentile", "{}", "[1]"),
                StandardLibrary.catalog()
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).source()).doesNotContain("apply.empty");
    }

    @Test
    void listPercentileNeedsNoInvalidGraphConnection() {
        final CompileResult result = ProjectCompiler.compile(
                collectionProject("list.percentile", "{}", "[1]"),
                StandardLibrary.catalog()
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).executableSource()).doesNotContain("apply.invalid");
    }

    @Test
    void nestedPrimitiveOutcomeCannotBeUsedAsAGraphConnection() {
        final String project = collectionProject("list.percentile", "{}", "[1]")
                .replace(
                        "{\"from\":\"apply.next\",\"to\":\"end\"}",
                        "{\"from\":\"apply.next\",\"to\":\"end\"},"
                                + "{\"from\":\"apply.empty\",\"to\":\"end\"}"
                );

        assertThat(ProjectCompiler.compile(project, StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_LINK_OUTCOME_UNKNOWN",
                        "Link source outcome is not declared: apply.empty.",
                        "links[3].from"
                ))));
    }

    @Test
    void listPercentileEmptyOutcomeRemainsInExecutionEvidence() {
        final RunResult.Succeeded result = collectionRun(
                collectionApplication("list.percentile", "{}", "[1]"),
                "[]"
        );

        assertThat(result.steps()).contains(new RunResult.StepExecution("list.percentile", "empty"));
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void listPercentileEmptyProgramUsesTheTriggerDefaults() {
        final String project = collectionProject("list.percentile", "{}", "[1]");
        final CompiledProject application = ((CompileResult.Compiled) ProjectCompiler.compile(
                project,
                StandardLibrary.catalog()
        )).project();
        final RunResult.Succeeded result = (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context("[]"))
        );

        assertThat(result.context().values())
                .containsEntry("result", RailixValue.nullValue())
                .containsEntry("exit_code", RailixValue.number(0));
    }

    private static RailixValue textBoundaryResult(
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

    private static String textBoundaryInput(final String primitive) {
        return primitive.equals("text.starts-with") ? "prefix" : "suffix";
    }

    private static void assertTotalContract(
            final String id,
            final ValueShape input,
            final ValueShape output
    ) {
        final StepDefinition definition = StandardLibrary.catalog().find(id).orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(new StepDefinition.Port("value", input)),
                List.of(new StepDefinition.Port("value", output)),
                List.of(),
                List.of("ok")
        );
    }

    private static void assertRefinedTotalContract(
            final String id,
            final StepDefinition.Port input,
            final StepDefinition.Port output
    ) {
        final StepDefinition definition = StandardLibrary.catalog().find(id).orElseThrow();

        assertThat(List.of(
                definition.receives(),
                definition.returns(),
                definition.inputs(),
                definition.outcomes()
        )).containsExactly(
                List.of(input),
                List.of(output),
                List.of(),
                List.of("ok")
        );
    }

    private static void assertIncompatible(
            final String primitive,
            final String input,
            final String expected,
            final String actual
    ) {
        assertRuntimeIncompatible(primitive, input, input, expected, actual);
    }

    private static void assertRuntimeIncompatible(
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

    private static void assertRuntimeIncompatible(
            final CompiledProject application,
            final String primitive,
            final String runtimeInput,
            final String expected,
            final String actual
    ) {
        final RunResult result = application.run(
                "command",
                new CompiledProject.StreamItem(false, context(runtimeInput))
        );

        assertThat(result).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + primitive + " requires " + expected + " but receives " + actual + ".",
                        "nodes[2].inputs.steps[0]"
                )),
                List.of()
        ));
    }

    private static void assertRuntimeRefinementIncompatible(
            final String primitive,
            final String compiledInput,
            final RailixValue runtimeInput,
            final String rejection
    ) {
        final RunResult result = application(primitive, compiledInput).run(
                "command",
                new CompiledProject.StreamItem(false, context(runtimeInput))
        );

        assertThat(result).isEqualTo(new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "RUN_NESTED_INPUT_INCOMPATIBLE",
                        "Nested Step " + primitive + " rejects value: " + rejection,
                        "nodes[2].inputs.steps[0]"
                )),
                List.of()
        ));
    }

    private static void assertUnknownConfiguration(final String primitive, final String input) {
        final String source = project(List.of(primitive), input).replace(
                "{\"use\":\"" + primitive + "\",\"inputs\":{}}",
                "{\"use\":\"" + primitive + "\",\"inputs\":{\"noise\":true}}"
        );

        assertThat(ProjectCompiler.compile(source, StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_INPUT_UNKNOWN",
                        "Step input is not declared: noise.",
                        "nodes[2].inputs.steps[0].inputs.noise"
                ))));
    }

    private static void assertConfiguredRejection(
            final String primitive,
            final String config,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(ProjectCompiler.compile(
                configuredProject(List.of(primitive), primitive, config, "6"),
                StandardLibrary.catalog()
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path))));
    }

    private static void assertCollectionIncompatible(final String primitive) {
        assertRuntimeIncompatible(
                collectionApplication(primitive, "{}", "{}"),
                primitive,
                "{}",
                "array",
                "object"
        );
    }

    private static RailixValue result(final String primitive, final String input) {
        return result(List.of(primitive), input);
    }

    private static RailixValue result(final List<String> primitives, final String input) {
        return run(primitives, input).context().values().get("result");
    }

    private static RailixValue result(final CompiledProject application, final String input) {
        return runValue(application, input).context().values().get("result");
    }

    private static RailixValue result(final CompiledProject application, final RailixValue input) {
        final RunResult.Succeeded run = (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context(input))
        );
        return run.context().values().get("result");
    }

    private static RailixValue configuredResult(
            final String primitive,
            final String config,
            final String input
    ) {
        return result(configuredApplication(List.of(primitive), primitive, config, input), input);
    }

    private static CompiledProject configuredApplication(
            final List<String> primitives,
            final String configuredPrimitive,
            final String config,
            final String input
    ) {
        final CompileResult compiled = ProjectCompiler.compile(
                configuredProject(primitives, configuredPrimitive, config, input),
                StandardLibrary.catalog()
        );
        return ((CompileResult.Compiled) compiled).project();
    }

    private static RunResult.Succeeded run(final String primitive, final String input) {
        return run(List.of(primitive), input);
    }

    private static RunResult.Succeeded run(final List<String> primitives, final String input) {
        return runValue(application(primitives, input), input);
    }

    private static CompiledProject application(final String primitive, final String input) {
        return application(List.of(primitive), input);
    }

    private static CompiledProject application(final List<String> primitives, final String input) {
        final CompileResult compiled = ProjectCompiler.compile(project(primitives, input), StandardLibrary.catalog());
        return ((CompileResult.Compiled) compiled).project();
    }

    private static RunResult.Succeeded runValue(
            final CompiledProject application,
            final String input
    ) {
        return (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context(input))
        );
    }

    private static RailixValue fallibleResult(final String input) {
        return fallibleResult(List.of("text.to-number"), input);
    }

    private static void assertFallibleInvalid(final String input) {
        final RunResult.Succeeded result = fallibleRun(List.of("text.to-number"), input);

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string(input));
        assertThat(result.steps()).contains(new RunResult.StepExecution("text.to-number", "invalid"));
    }

    private static RailixValue fallibleResult(final List<String> primitives, final String input) {
        return fallibleRun(primitives, input).context().values().get("result");
    }

    private static RunResult.Succeeded fallibleRun(final List<String> primitives, final String input) {
        return run(fallibleApplication(primitives, input), input);
    }

    private static CompiledProject fallibleApplication(
            final List<String> primitives,
            final String example
    ) {
        final String encoded = RailixJson.write(RailixValue.string(example));
        final CompileResult compiled = ProjectCompiler.compile(
                fallibleProject(primitives, encoded),
                StandardLibrary.catalog()
        );
        return ((CompileResult.Compiled) compiled).project();
    }

    private static RunResult.Succeeded run(final CompiledProject application, final String input) {
        return (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, RailixValue.object(Map.of(
                        "payload", RailixValue.object(Map.of("value", RailixValue.string(input)))
                )))
        );
    }

    private static RailixValue collectionResult(
            final String primitive,
            final String config,
            final String input
    ) {
        return collectionRun(collectionApplication(primitive, config, input), input)
                .context()
                .values()
                .get("result");
    }

    private static CompiledProject collectionApplication(
            final String primitive,
            final String config,
            final String input
    ) {
        return ((CompileResult.Compiled) collectionCompile(primitive, config, input)).project();
    }

    private static RunResult.Succeeded collectionRun(
            final CompiledProject application,
            final String input
    ) {
        return (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, context(input))
        );
    }

    private static CompileResult collectionCompile(
            final String primitive,
            final String config,
            final String input
    ) {
        return ProjectCompiler.compile(
                collectionProject(primitive, config, input),
                StandardLibrary.catalog()
        );
    }

    private static RailixValue collectionTarget(
            final String primitive,
            final String config,
            final String input
    ) {
        final CompileResult compiled = ProjectCompiler.compile(
                collectionNoWriteProject(primitive, config, input),
                StandardLibrary.catalog()
        );
        final CompiledProject application = ((CompileResult.Compiled) compiled).project();
        final RunResult.Succeeded result = (RunResult.Succeeded) application.run(
                "command",
                new CompiledProject.StreamItem(false, (RailixValue.ObjectValue) (
                        (RailixJson.Parsed) RailixJson.parse(
                                "{\"payload\":{\"value\":" + input + ",\"target\":\"kept\"}}"
                        )
                ).value())
        );
        return ((RailixValue.ObjectValue) result.context().values().get("payload"))
                .values()
                .get("target");
    }

    private static RailixValue.ObjectValue context(final String input) {
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(
                "{\"payload\":{\"value\":" + input + "}}"
        )).value();
    }

    private static RailixValue.ObjectValue context(final RailixValue input) {
        return RailixValue.object(Map.of(
                "payload",
                RailixValue.object(Map.of("value", input))
        ));
    }

    private static RailixValue nestedArrays(final int depth) {
        RailixValue value = RailixValue.nullValue();
        for (int index = 0; index < depth; index++) {
            value = RailixValue.array(List.of(value));
        }
        return value;
    }

    private static String project(final List<String> primitives, final String input) {
        final String steps = String.join(",", primitives.stream()
                .map(primitive -> "{\"use\":\"" + primitive + "\",\"inputs\":{}}")
                .toList());
        return """
                {
                  "format":1,
                  "id":"primitive-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"example","payload":[],"context":{"payload":{"value":%s}}}
                    ]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[%s]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"end"}
                  ]
                }
                """.formatted(input, steps);
    }

    private static String configuredProject(
            final List<String> primitives,
            final String primitive,
            final String config,
            final String input
    ) {
        return project(primitives, input).replace(
                "{\"use\":\"" + primitive + "\",\"inputs\":{}}",
                "{\"use\":\"" + primitive + "\",\"inputs\":" + config + "}"
        );
    }

    private static String filterCondition() {
        return "[{\"option\":\"field\",\"inputs\":{"
                + "\"field\":[\"context\",\"payload\",\"value\"]},"
                + "\"when\":[{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"allow\"}}]}]";
    }

    private static String choiceCondition() {
        return "[" + filterCondition() + "]";
    }

    private static CompiledProject filterApplication(final String conditions) {
        final CompileResult compiled = ProjectCompiler.compile(filterProject(conditions), StandardLibrary.catalog());
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) compiled).project();
    }

    private static CompiledProject choiceApplication(final String conditions) {
        final CompileResult compiled = ProjectCompiler.compile(choiceProject(conditions), StandardLibrary.catalog());
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) compiled).project();
    }

    private static String filterProject(final String conditions) {
        return branchProject("filter-proof", "filter", "railix.filter", conditions);
    }

    private static String choiceProject(final String conditions) {
        return branchProject("choice-proof", "choice", "railix.choice", conditions);
    }

    private static String branchProject(
            final String projectId,
            final String branchId,
            final String branchStep,
            final String conditions
    ) {
        return """
                {
                  "format":1,
                  "id":"%s",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"match","payload":[],"context":{"payload":{"value":"allow"}}},
                      {"name":"otherwise","payload":[],"context":{"payload":{"value":"deny"}}}
                    ]},
                    {"id":"%s","use":"%s","inputs":{"conditions":%s}},
                    {"id":"matched","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"literal","inputs":{"literal":"matched"}}],
                      "steps":[]
                    }},
                    {"id":"otherwise","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"literal","inputs":{"literal":"otherwise"}}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"%s"},
                    {"from":"%s.match","to":"matched"},
                    {"from":"%s.otherwise","to":"otherwise"},
                    {"from":"matched.next","to":"end"},
                    {"from":"otherwise.next","to":"end"}
                  ]
                }
                """.formatted(
                        projectId,
                        branchId,
                        branchStep,
                        conditions,
                        branchId,
                        branchId,
                        branchId
                );
    }

    private static String cliOnlyProject() {
        return """
                {
                  "format":1,
                  "id":"cli-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"no-arguments","payload":[]}
                    ]}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"end"}
                  ]
                }
                """;
    }

    private static String linearFieldProject(final String steps) {
        return """
                {
                  "format":1,
                  "id":"linear-field-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"missing","payload":[],"context":{"payload":{}}}
                    ]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","payload","missing"],
                      "value":[{"option":"current","inputs":{}}],
                      "steps":%s
                    }},
                    {"id":"continued","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"literal","inputs":{"literal":"continued"}}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"continued"},
                    {"from":"continued.next","to":"end"}
                  ]
                }
                """.formatted(steps);
    }

    private static String twoCliProject() {
        return cliOnlyProject()
                .replace(
                        """
                            {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[""",
                        """
                            {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                              {"name":"first","payload":[]}
                            ]},
                            {"id":"second","use":"railix.trigger.cli","inputs":{},"examples":["""
                )
                .replace(
                        """
                            {"from":"app.start","to":"command"},""",
                        """
                            {"from":"app.start","to":"command"},
                            {"from":"app.start","to":"second"},
                            {"from":"second.next","to":"end"},"""
                );
    }

    private static String fallibleProject(final List<String> primitives, final String input) {
        final String steps = String.join(",", primitives.stream()
                .map(primitive -> "{\"use\":\"" + primitive + "\",\"inputs\":{}}")
                .toList());
        return """
                {
                  "format":1,
                  "id":"fallible-primitive-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"example","payload":[],"context":{"payload":{"value":%s}}}
                    ]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","payload","value"],
                      "value":[{"option":"current","inputs":{}}],
                      "steps":[%s]
                    }},
                    {"id":"ok-result","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"ok-result"},
                    {"from":"ok-result.next","to":"end"}
                  ]
                }
                """.formatted(input, steps);
    }

    private static String collectionProject(
            final String primitive,
            final String config,
            final String input
    ) {
        return """
                {
                  "format":1,
                  "id":"collection-primitive-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"example","payload":[],"context":{"payload":{"value":%s}}}
                    ]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[{"use":"%s","inputs":%s}]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"end"}
                  ]
                }
                """.formatted(input, primitive, config);
    }

    private static String collectionNoWriteProject(
            final String primitive,
            final String config,
            final String input
    ) {
        return """
                {
                  "format":1,
                  "id":"collection-no-write-proof",
                  "nodes":[
                    {"id":"app","use":"railix.app","inputs":{}},
                    {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                      {"name":"example","payload":[],"context":{"payload":{"value":%s,"target":"kept"}}}
                    ]},
                    {"id":"apply","use":"railix.field-manipulation","inputs":{
                      "field":["context","payload","target"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","value"]
                      }}],
                      "steps":[{"use":"%s","inputs":%s}]
                    }},
                    {"id":"ok-result","use":"railix.field-manipulation","inputs":{
                      "field":["context","result"],
                      "value":[{"option":"field","inputs":{
                        "source":["context","payload","target"]
                      }}],
                      "steps":[]
                    }}
                  ],
                  "links":[
                    {"from":"app.start","to":"command"},
                    {"from":"command.next","to":"apply"},
                    {"from":"apply.next","to":"ok-result"},
                    {"from":"ok-result.next","to":"end"}
                  ]
                }
                """.formatted(input, primitive, config);
    }
}
