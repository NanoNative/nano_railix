package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class ProjectConfigurationE2eTest {
    @ParameterizedTest
    @ValueSource(strings = {"\"00123\"", "\"\"", "false", "0", "9007199254740993", "0.123456789012345678901",
            "\"C:\\\\new\\\\test\""})
    void configurationCompilesToExactlyTheSameApplicationAsALiteral(final String value) {
        final var configured = compiled(project("{\"key\":" + value + "}", "{}",
                "{\"value\":{\"configuration\":\"key\"}}"));
        final var literal = compiled(project("{}", "{\"value\":" + value + "}", "{}"));
        assertThat(configured.productionApplicationSource()).isEqualTo(literal.productionApplicationSource());
        assertThat(configured.developmentApplicationSource()).isEqualTo(literal.developmentApplicationSource());
        assertThat(configured.source()).contains("\"configuration\":\"key\"");
    }

    @Test
    void referencesResolveInsideCandidatesTransformsAndMatchers() {
        final String literal = CreatorFirstProjectCompilationE2eTest.contextProject()
                .replace("\"option\":\"current\",\"inputs\":{}", "\"option\":\"literal\",\"inputs\":{\"literal\":\"RAILIX\"},"
                        + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"test.equals\",\"inputs\":{\"expected\":\"RAILIX\"}}]]}")
                .replace("\"use\":\"text.lowercase\",\"inputs\":{}", "\"use\":\"test.equals\",\"inputs\":{\"expected\":\"RAILIX\"}");
        final String configured = literal.replace("\"format\":1", "\"format\":1,\"configuration\":{\"name\":\"RAILIX\"}")
                .replace("\"inputs\":{\"literal\":\"RAILIX\"}", "\"inputs\":{},\"input_sources\":{\"literal\":{\"configuration\":\"name\"}}")
                .replace("\"inputs\":{\"expected\":\"RAILIX\"}", "\"inputs\":{},\"input_sources\":{\"expected\":{\"configuration\":\"name\"}}");
        final var a = compile(literal);
        final var b = compile(configured);
        assertThat(a).isInstanceOf(CompileResult.Compiled.class);
        assertThat(b).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) b).productionApplicationSource())
                .isEqualTo(((CompileResult.Compiled) a).productionApplicationSource());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referencesResolveInsideAuthoredRoutesAndTheirMatcherGroups(final boolean isolated) {
        final String literal = project("{}", """
                {"routes":[{"outcome":"match","inputs":{"isolated":%s},"groups":[[{
                  "option":"literal","inputs":{"value":true},"when":{"transforms":[],"all":[[{
                    "use":"test.equals","inputs":{"expected":true}}]]}}]]}]}
                """.formatted(isolated), "{}")
                .replace("\"use\":\"example.configuration\"", "\"use\":\"example.routes\"")
                .replace("{\"from\":\"configured.next\",\"to\":\"end\"}",
                        "{\"from\":\"configured.next\",\"to\":\"end\"},{\"from\":\"configured.match\",\"to\":\"end\"}");
        final String configured = literal.replace("\"configuration\":{}",
                        "\"configuration\":{\"isolated\":" + isolated + ",\"expected\":true}")
                .replace("\"inputs\":{\"isolated\":" + isolated + "}",
                        "\"inputs\":{},\"input_sources\":{\"isolated\":{\"configuration\":\"isolated\"}}")
                .replace("\"inputs\":{\"value\":true}",
                        "\"inputs\":{},\"input_sources\":{\"value\":{\"configuration\":\"expected\"}}")
                .replace("\"inputs\":{\"expected\":true}",
                        "\"inputs\":{},\"input_sources\":{\"expected\":{\"configuration\":\"expected\"}}");
        final var a = compiled(literal);
        final var b = compiled(configured);
        assertThat(b.productionApplicationSource()).isEqualTo(a.productionApplicationSource());
        assertThat(b.developmentApplicationSource()).isEqualTo(a.developmentApplicationSource());
        rejected(configured.replace("\"isolated\":" + isolated, "\"isolated\":\"wrong\""), "PROJECT_INPUT_SHAPE_INVALID");
        rejected(configured.replace("\"configuration\":\"isolated\"", "\"configuration\":\"missing\""),
                "PROJECT_CONFIGURATION_KEY_UNKNOWN");
    }

    @Test
    void literalObjectsWithBindingFieldNamesRemainLiteral() {
        assertThat(compile(project("{}", "{\"value\":{\"configuration\":\"missing\",\"input_sources\":false}}", "{}")))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}"})
    void unsupportedConfigurationValuesAreRejectedEvenWhenUnused(final String value) {
        rejected(project("{\"unused\":" + value + "}", "{}", "{}"), "PROJECT_CONFIGURATION_VALUE_INVALID");
    }

    @Test
    void explicitReferencesDoNotFallBackToDefaults() {
        rejected(project("{}", "{}", "{\"value\":{\"configuration\":\"missing\"}}"), "PROJECT_CONFIGURATION_KEY_UNKNOWN");
    }

    @Test
    void literalAndReferenceCannotBothSupplyAnInput() {
        rejected(project("{\"key\":true}", "{\"value\":false}", "{\"value\":{\"configuration\":\"key\"}}"),
                "PROJECT_INPUT_SOURCE_CONFLICT");
    }

    @Test
    void referencesHonorTheExistingShapeAndRangeConstraints() {
        rejected(project("{\"key\":\"0.2\"}", "{}", "{\"rate\":{\"configuration\":\"key\"}}"), "PROJECT_INPUT_SHAPE_INVALID");
        rejected(project("{\"key\":2}", "{}", "{\"rate\":{\"configuration\":\"key\"}}"), "PROJECT_INPUT_RANGE_INVALID");
    }

    @Test
    void unknownAndIneligibleInputSourcesAreRejected() {
        rejected(project("{\"key\":true}", "{}", "{\"missing\":{\"configuration\":\"key\"}}"), "PROJECT_INPUT_SOURCE_UNKNOWN");
        rejected(project("{\"key\":true}", "{}", "{\"path\":{\"configuration\":\"key\"}}"), "PROJECT_INPUT_SOURCE_INELIGIBLE");
        rejected(project("{\"key\":true}", "{}", "{\"value\":{\"configuration\":\"key\",\"other\":true}}"), "PROJECT_INPUT_SOURCE_INVALID");
    }

    @Test
    void keysAreExactAndDuplicateOrBlankNamesAreRejected() {
        assertThat(compile(project("{\"a_b\":true}", "{}", "{\"value\":{\"configuration\":\"a_b\"}}")))
                .isInstanceOf(CompileResult.Compiled.class);
        rejected(project("{\"Key\":true}", "{}", "{\"value\":{\"configuration\":\"key\"}}"), "PROJECT_CONFIGURATION_KEY_UNKNOWN");
        rejected(project("{\" \":true}", "{}", "{}"), "PROJECT_CONFIGURATION_KEY_INVALID");
        rejected(project("{\"key\":true,\"key\":false}", "{}", "{}"), "PROJECT_JSON_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", " leading", "trailing ", "line\nkey", "return\rkey", "tab\tkey",
            "form\fkey", "back\bkey", "null\0key", "delete\177key", "next\u0085line", "non\u00a0breaking",
            "line\u2028separator", "paragraph\u2029separator", "a.b", "a@b", "a/b", "a\\b", "a:b", "a\"b", "caf\u00e9"})
    void configurationKeysRejectCharactersOutsideTheNameAlphabet(final String key) {
        final String encoded = RailixJson.write(RailixValue.string(key));
        rejected(project("{" + encoded + ":true}", "{}", "{}"), "PROJECT_CONFIGURATION_KEY_INVALID");
        rejected(project("{\"key\":true}", "{}", "{\"value\":{\"configuration\":" + encoded + "}}"),
                "PROJECT_INPUT_SOURCE_INVALID");
    }

    @Test
    void validConfigurationNamesResolveExactly() {
        final String key = "09AZaz_-";
        final String encoded = RailixJson.write(RailixValue.string(key));
        final var configured = compiled(project("{" + encoded + ":true}", "{}",
                "{\"value\":{\"configuration\":" + encoded + "}}"));
        final var literal = compiled(project("{}", "{\"value\":true}", "{}"));
        assertThat(configured.productionApplicationSource()).isEqualTo(literal.productionApplicationSource());
        assertThat(configured.source()).contains(encoded);
    }

    @Test
    void configurationKeyLengthsAreEnforcedForDefinitionsAndReferences() {
        final String key = "k".repeat(64);
        final String encoded = RailixJson.write(RailixValue.string(key));
        assertThat(compile(project("{" + encoded + ":true}", "{}",
                "{\"value\":{\"configuration\":" + encoded + "}}"))).isInstanceOf(CompileResult.Compiled.class);
        final String oversized = RailixJson.write(RailixValue.string(key + "k"));
        rejected(project("{" + oversized + ":true}", "{}", "{}"), "PROJECT_CONFIGURATION_KEY_INVALID");
        rejected(project("{\"key\":true}", "{}", "{\"value\":{\"configuration\":" + oversized + "}}"),
                "PROJECT_INPUT_SOURCE_INVALID");
    }

    @Test
    void configurationStringLengthsCountDecodedCharactersAndLeaveLiteralsUnrestricted() {
        final String value = "\\\"".repeat(2_048);
        final String encoded = RailixJson.write(RailixValue.string(value));
        assertThat(encoded.length()).isGreaterThan(4_096);
        final var configured = compiled(project("{\"key\":" + encoded + "}", "{}",
                "{\"value\":{\"configuration\":\"key\"}}"));
        final var literal = compiled(project("{}", "{\"value\":" + encoded + "}", "{}"));
        assertThat(configured.productionApplicationSource()).isEqualTo(literal.productionApplicationSource());
        final String oversized = RailixJson.write(RailixValue.string(value + "x"));
        rejected(project("{\"unused\":" + oversized + "}", "{}", "{}"), "PROJECT_CONFIGURATION_VALUE_INVALID");
        assertThat(compile(project("{}", "{\"value\":" + oversized + "}", "{}")))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"with space", " leading", "trailing ", "line\nvalue", "return\rvalue", "tab\tvalue",
            "form\fvalue", "back\bvalue", "null\0value", "delete\177value", "next\u0085line", "non\u00a0breaking",
            "line\u2028separator", "paragraph\u2029separator", "caf\u00e9"})
    void configurationStringsRejectWhitespaceAndNonPrintableAsciiEvenWhenUnused(final String value) {
        final String encoded = RailixJson.write(RailixValue.string(value));
        rejected(project("{\"unused\":" + encoded + "}", "{}", "{}"), "PROJECT_CONFIGURATION_VALUE_INVALID");
        assertThat(compile(project("{}", "{\"value\":" + encoded + "}", "{}")))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void punctuationAndEscapedConfigurationStringsResolveExactly() {
        final String encoded = RailixJson.write(RailixValue.string("https://Aa09-_.@:/\\\"'!?%&+#=[]{}()<>|*^$~,;`"));
        final var configured = compiled(project("{\"url\":" + encoded + "}", "{}",
                "{\"value\":{\"configuration\":\"url\"}}"));
        final var literal = compiled(project("{}", "{\"value\":" + encoded + "}", "{}"));
        assertThat(configured.productionApplicationSource()).isEqualTo(literal.productionApplicationSource());
        assertThat(configured.developmentApplicationSource()).isEqualTo(literal.developmentApplicationSource());
        assertThat(configured.source()).contains(encoded);
    }

    private static String project(final String configuration, final String inputs, final String sources) {
        return """
                {"format":1,"id":"configuration-test","configuration":%s,"nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{"name":"empty","payload":[]}]},
                  {"id":"configured","use":"example.configuration","inputs":%s,"input_sources":%s}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"configured"},
                  {"from":"configured.next","to":"end"}]}
                """.formatted(configuration, inputs, sources);
    }

    @Test
    void configurationReferencesRunTheDeclaredTypedValidator() {
        final var declaration = StepDefinition.Input.json(ValueShape.NUMBER).validation(Integer.class,
                value -> value > 0 ? Optional.empty() : Optional.of("Must be positive."));
        final String source = project("{\"key\":1}", "{}", "{\"value\":{\"configuration\":\"key\"}}");
        assertThat(compile(source, declaration)).isInstanceOf(CompileResult.Compiled.class);
        for (final String value : new String[]{"0.5", "0"}) {
            final var result = compile(source.replace("\"key\":1", "\"key\":" + value), declaration);
            assertThat(result).isInstanceOf(CompileResult.Rejected.class);
            assertThat(((CompileResult.Rejected) result).diagnostics()).anySatisfy(issue -> {
                assertThat(issue.code()).isEqualTo(value.equals("0.5")
                        ? "PROJECT_INPUT_VALIDATION_TYPE" : "PROJECT_INPUT_VALIDATION_INVALID");
                assertThat(issue.path()).isEqualTo("nodes[2].inputs.value");
                assertThat(issue.message()).contains("Configuration \"key\"");
            });
        }
    }

    private static CompileResult compile(final String source) {
        return compile(source, StepDefinition.Input.json(ValueShape.ANY).defaultValue(RailixValue.bool(false)));
    }

    private static CompileResult compile(final String source, final StepDefinition.JsonInput value) {
        final var definitions = new ArrayList<>(CreatorFirstProjectCompilationE2eTest.contextCatalog().definitions());
        definitions.add(StepDefinition.named("example.configuration", "1")
                .input("value", value)
                .input("rate", StepDefinition.Input.json(ValueShape.NUMBER).between(RailixValue.number(0), RailixValue.number(1)).optional())
                .input("path", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE).defaultPath("context", "result"))
                .implementation(TestStepHandlers.PrimaryOutcome.class));
        definitions.add(StepDefinition.named("test.equals", "1")
                .receive("value", ValueShape.ANY).returns("value", ValueShape.BOOLEAN)
                .input("expected", StepDefinition.Input.json(ValueShape.ANY)).implementation(TestStepHandlers.EqualsExpected.class));
        definitions.add(StepDefinition.named("example.routes", "1")
                .input("routes", StepDefinition.Input.matcherGroups(StepDefinition.Input.option("literal")
                        .input("value", StepDefinition.Input.json(ValueShape.BOOLEAN)).fromOwned("value"))
                        .withAuthoredOutcomes().routeInput("isolated",
                                StepDefinition.Input.json(ValueShape.BOOLEAN).defaultValue(RailixValue.bool(false))))
                .withContinuations().implementation(TestStepHandlers.PrimaryOutcome.class));
        return ProjectCompiler.compileApplication(source, StepCatalog.of(definitions.toArray(StepDefinition[]::new)));
    }

    private static CompileResult.Compiled compiled(final String source) {
        final var result = compile(source);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static void rejected(final String source, final String code) {
        final var result = compile(source);
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        assertThat(((CompileResult.Rejected) result).diagnostics()).anySatisfy(issue -> assertThat(issue.code()).isEqualTo(code));
    }
}
