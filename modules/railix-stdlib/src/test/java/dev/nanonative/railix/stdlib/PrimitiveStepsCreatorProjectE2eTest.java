package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
            assertThat(definition.executable()).isTrue();
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
    void filterRejectsANonBooleanPredicateBeforeExecution() {
        final CompileResult compiled = ProjectCompiler.compileApplication(filterProject("""
                [{"option":"literal","inputs":{"value":"HELLO"},
                  "when":{"transforms":[],"all":[[{"use":"text.lowercase","inputs":{}}]]}}]
                """), StandardLibrary.catalog());

        assertThat(compiled).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "PROJECT_CANDIDATE_PREDICATE_RESULT_INVALID",
                "Candidate predicate program must return boolean.",
                "nodes[2].inputs.conditions[0].when.all[0][0].use"
        ))));
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
        assertThat(ProjectCompiler.compileApplication(twoCliProject(), StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_STEP_INSTANCE_LIMIT",
                        "Step railix.trigger.cli allows at most 1 instance.",
                        "nodes[2].use"
                ))));
    }

    @Test
    void uppercaseDeclaresATotalStringContract() {
        assertTotalContract("text.uppercase", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void trimDeclaresATotalStringContract() {
        assertTotalContract("text.trim", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void normalizeSpaceDeclaresATotalStringContract() {
        assertTotalContract("text.normalize-space", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void normalizeNfcDeclaresATotalStringContract() {
        assertTotalContract("text.normalize-nfc", ValueShape.STRING, ValueShape.STRING);
    }

    @Test
    void textLengthDeclaresATotalStringToNumberContract() {
        assertTotalContract("text.length", ValueShape.STRING, ValueShape.NUMBER);
    }

    @Test
    void textIsEmptyDeclaresATotalStringToBooleanContract() {
        assertTotalContract("text.is-empty", ValueShape.STRING, ValueShape.BOOLEAN);
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

    @Test
    void listIsEmptyDeclaresATotalArrayToBooleanContract() {
        assertTotalContract("list.is-empty", ValueShape.ARRAY, ValueShape.BOOLEAN);
    }

    @Test
    void listIsEmptyRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("list.is-empty", "[]");
    }

    @Test
    void booleanToTextDeclaresATotalBooleanToStringContract() {
        assertTotalContract("boolean.to-text", ValueShape.BOOLEAN, ValueShape.STRING);
    }

    @Test
    void booleanToTextRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("boolean.to-text", "true");
    }

    @Test
    void booleanToNumberDeclaresATotalBooleanToNumberContract() {
        assertTotalContract("boolean.to-number", ValueShape.BOOLEAN, ValueShape.NUMBER);
    }

    @Test
    void booleanToNumberRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("boolean.to-number", "true");
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
    void listReverseRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("list.reverse", "[]");
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
    void numberToTextRejectsUnknownConfigurationBeforeExecution() {
        assertUnknownConfiguration("number.to-text", "1");
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
    void textToNumberDeclaresTheFallibleStringToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("text.to-number").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.STRING));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.outcomes()).containsExactly("ok", "invalid");
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
    void listMinDeclaresTheEmptyAwareArrayToNumberContract() {
        final StepDefinition definition = StandardLibrary.catalog().find("list.min").orElseThrow();

        assertThat(definition.receives()).containsExactly(new StepDefinition.Port("value", ValueShape.ARRAY));
        assertThat(definition.returns()).containsExactly(new StepDefinition.Port("value", ValueShape.NUMBER));
        assertThat(definition.inputs()).isEmpty();
        assertThat(definition.outcomes()).containsExactly("ok", "empty", "invalid");
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
        final CompileResult result = ProjectCompiler.compileApplication(
                collectionProject("list.percentile", "{}", "[1]"),
                StandardLibrary.catalog()
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).source()).doesNotContain("apply.empty");
    }

    @Test
    void listPercentileNeedsNoInvalidGraphConnection() {
        final CompileResult result = ProjectCompiler.compileApplication(
                collectionProject("list.percentile", "{}", "[1]"),
                StandardLibrary.catalog()
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).productionApplicationSource()).doesNotContain("apply.invalid");
    }

    @Test
    void nestedPrimitiveOutcomeCannotBeUsedAsAGraphConnection() {
        final String project = collectionProject("list.percentile", "{}", "[1]")
                .replace(
                        "{\"from\":\"apply.next\",\"to\":\"end\"}",
                        "{\"from\":\"apply.next\",\"to\":\"end\"},"
                                + "{\"from\":\"apply.empty\",\"to\":\"end\"}"
                );

        assertThat(ProjectCompiler.compileApplication(project, StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "PROJECT_LINK_OUTCOME_UNKNOWN",
                        "Link source outcome is not declared: apply.empty.",
                        "links[3].from"
                ))));
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

    private static void assertUnknownConfiguration(final String primitive, final String input) {
        final String source = project(List.of(primitive), input).replace(
                "{\"use\":\"" + primitive + "\",\"inputs\":{}}",
                "{\"use\":\"" + primitive + "\",\"inputs\":{\"noise\":true}}"
        );

        assertThat(ProjectCompiler.compileApplication(source, StandardLibrary.catalog()))
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
        assertThat(ProjectCompiler.compileApplication(
                configuredProject(List.of(primitive), primitive, config, "6"),
                StandardLibrary.catalog()
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path))));
    }

    private static CompileResult collectionCompile(
            final String primitive,
            final String config,
            final String input
    ) {
        return ProjectCompiler.compileApplication(
                collectionProject(primitive, config, input),
                StandardLibrary.catalog()
        );
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

    private static String filterProject(final String conditions) {
        return branchProject("filter-proof", "filter", "railix.filter", conditions);
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
}
