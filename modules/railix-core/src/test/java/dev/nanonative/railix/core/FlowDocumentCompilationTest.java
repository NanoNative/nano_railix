package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlowDocumentCompilationTest {
    private static final String CANONICAL_FLOW = "{\"connections\":[{\"from\":\"input.text\","
            + "\"to\":\"lowercase.text\"},{\"from\":\"lowercase.text\",\"to\":\"output.text\"}],"
            + "\"entry\":\"lowercase\",\"id\":\"lowercase-app\",\"inputs\":{\"text\":\"string\"},"
            + "\"outputs\":{\"text\":\"string\"},\"steps\":[{\"config\":{},\"id\":\"lowercase\","
            + "\"on\":{\"ok\":\"end\"},\"use\":\"text.lowercase\"}],\"triggers\":[]}";

    @Test
    void compiledFlowCarriesCanonicalJsonSource() {
        assertThat(compiled(FlowFixtures.lowercaseFlow()).source()).isEqualTo(CANONICAL_FLOW);
    }

    @Test
    void canonicalFlowSourceIsStableAcrossRepeatedCompilation() {
        final String first = compiled(FlowFixtures.lowercaseFlow()).source();

        assertThat(compiled(first).source()).isEqualTo(first);
    }

    @Test
    void compiledFlowExposesItsDeclaredId() {
        assertThat(compiled(FlowFixtures.lowercaseFlow()).flow().id()).isEqualTo("lowercase-app");
    }

    @Test
    void compiledFlowExposesItsDeclaredOutputs() {
        assertThat(compiled(FlowFixtures.lowercaseFlow()).flow().outputs())
                .isEqualTo(Map.of("text", ValueShape.STRING));
    }

    @Test
    void flowDocumentMustBeAnObject() {
        assertDiagnostic("[]", "FLOW_DOCUMENT_OBJECT_REQUIRED", "Flow document must be an object.", "$");
    }

    @Test
    void missingFlowSourceReturnsAStableJsonDiagnostic() {
        assertThat(FlowCompiler.compile(null, StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_JSON_INVALID",
                        "JSON source is missing.",
                        "$",
                        1,
                        1
                ))));
    }

    @Test
    void flowSourceCharacterLimitRejectsBeforeEncoding() {
        assertDiagnostic(
                "x".repeat(1_048_577),
                "FLOW_SOURCE_TOO_LARGE",
                "Flow source exceeds the 1048576-byte limit.",
                "$"
        );
    }

    @Test
    void flowSourceUtf8ByteLimitRejectsMultiByteText() {
        assertDiagnostic(
                "\"" + "€".repeat(350_000) + "\"",
                "FLOW_SOURCE_TOO_LARGE",
                "Flow source exceeds the 1048576-byte limit.",
                "$"
        );
    }

    @Test
    void rawUnpairedSurrogateReturnsAStableJsonDiagnostic() {
        assertThat(FlowCompiler.compile("{\"id\":\"\uD800\"}", StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_JSON_INVALID",
                        "Unpaired Unicode surrogate is not allowed in JSON strings.",
                        "$",
                        1,
                        1
                ))));
    }

    @Test
    void flowDocumentDepthLimitRejectsTheSixtyFifthContainer() {
        final String source = "[".repeat(65) + "0" + "]".repeat(65);

        assertThat(FlowCompiler.compile(source, StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_DEPTH_EXCEEDED",
                        "Data exceeds the maximum container depth of 64.",
                        "$",
                        1,
                        65
                ))));
    }

    @Test
    void flowNumberSourceLimitRejectsBeforeBigDecimalParsing() {
        final String source = "1".repeat(1_025);

        assertThat(FlowCompiler.compile(source, StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_NUMBER_LIMIT_EXCEEDED",
                        "JSON number exceeds the 1024-character source limit.",
                        "$",
                        1,
                        1_026
                ))));
    }

    @Test
    void flowCanonicalNumberLimitRejectsExtremeExponentDefaults() {
        final String source = NestedDataMappingFlowE2eTest.flow(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"missing\"],\"default\":1e2147483647,\"to\":\"sink.value\"}"
        );

        assertThat(FlowCompiler.compile(source, StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_NUMBER_LIMIT_EXCEEDED",
                        "Number exceeds the 1024-character canonical limit.",
                        "$",
                        0,
                        0
                ))));
    }

    @Test
    void flowCanonicalNumberLimitRejectsScaleOverflowDefaults() {
        final String source = NestedDataMappingFlowE2eTest.flow(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"missing\"],\"default\":100e2147483647,\"to\":\"sink.value\"}"
        );

        assertThat(FlowCompiler.compile(source, StepCatalog.of(LowercaseStep.definition())))
                .isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                        "FLOW_NUMBER_LIMIT_EXCEEDED",
                        "Number exceeds the 1024-character canonical limit.",
                        "$",
                        0,
                        0
                ))));
    }

    @Test
    void sampleInputIsNotAnExecutableFlowField() {
        assertDiagnostic(
                replace("\"entry\": \"lowercase\"", "\"sampleInput\": {}, \"entry\": \"lowercase\""),
                "FLOW_FIELD_UNKNOWN",
                "Unknown flow field: sampleInput",
                "sampleInput"
        );
    }

    @Test
    void flowInputsMustBeAnObject() {
        assertDiagnostic(
                replace("\"inputs\": { \"text\": \"string\" }", "\"inputs\": []"),
                "FLOW_FIELD_OBJECT_REQUIRED",
                "inputs must be an object of names to shapes.",
                "inputs"
        );
    }

    @Test
    void flowShapeNamesMustBeStrings() {
        assertDiagnostic(
                replace("\"inputs\": { \"text\": \"string\" }", "\"inputs\": { \"text\": 1 }"),
                "FLOW_SHAPE_NAME_REQUIRED",
                "Shape must be a string.",
                "inputs.text"
        );
    }

    @Test
    void unknownFlowShapesAreRejected() {
        assertDiagnostic(
                replace("\"inputs\": { \"text\": \"string\" }", "\"inputs\": { \"text\": \"mystery\" }"),
                "FLOW_SHAPE_UNKNOWN",
                "Unknown shape: mystery",
                "inputs.text"
        );
    }

    @Test
    void flowStepsMustBeAnArray() {
        assertDiagnostic(
                replaceSteps("{}"),
                "FLOW_STEPS_ARRAY_REQUIRED",
                "steps must be an array.",
                "steps"
        );
    }

    @Test
    void everyFlowStepMustBeAnObject() {
        assertDiagnostic(
                replaceSteps("[1]"),
                "FLOW_STEP_OBJECT_REQUIRED",
                "Step must be an object.",
                "steps[0]"
        );
    }

    @Test
    void unknownStepFieldsAreRejected() {
        assertDiagnostic(
                replace("\"config\": {}", "\"config\": {}, \"with\": {}"),
                "FLOW_STEP_FIELD_UNKNOWN",
                "Unknown Step field: with",
                "steps[0].with"
        );
    }

    @Test
    void stepConfigurationMustBeAnObject() {
        assertDiagnostic(
                replace("\"config\": {}", "\"config\": []"),
                "FLOW_FIELD_OBJECT_REQUIRED",
                "config must be an object.",
                "steps[0].config"
        );
    }

    @Test
    void stepConfigurationMustBeExplicit() {
        assertDiagnostic(
                replace("\"config\": {},\n", ""),
                "FLOW_FIELD_OBJECT_REQUIRED",
                "config must be an object.",
                "steps[0].config"
        );
    }

    @Test
    void flowConnectionsMustBeAnArray() {
        assertDiagnostic(
                replaceConnections("{}"),
                "FLOW_CONNECTIONS_ARRAY_REQUIRED",
                "connections must be an array.",
                "connections"
        );
    }

    @Test
    void everyFlowConnectionMustBeAnObject() {
        assertDiagnostic(
                replaceConnections("[1]"),
                "FLOW_CONNECTION_OBJECT_REQUIRED",
                "Connection must be an object.",
                "connections[0]"
        );
    }

    @Test
    void unknownConnectionFieldsAreRejected() {
        assertDiagnostic(
                replace(
                        "{ \"from\": \"input.text\", \"to\": \"lowercase.text\" }",
                        "{ \"from\": \"input.text\", \"to\": \"lowercase.text\", \"fallback\": true }"
                ),
                "FLOW_CONNECTION_FIELD_UNKNOWN",
                "Unknown connection field: fallback",
                "connections[0].fallback"
        );
    }

    @Test
    void stepOutcomeMappingsMustBeAnObject() {
        assertDiagnostic(
                replace("\"on\": { \"ok\": \"end\" }", "\"on\": []"),
                "FLOW_FIELD_OBJECT_REQUIRED",
                "on must be an object.",
                "steps[0].on"
        );
    }

    @Test
    void stepTransitionTargetsMustBeStrings() {
        assertDiagnostic(
                replace("\"on\": { \"ok\": \"end\" }", "\"on\": { \"ok\": 1 }"),
                "FLOW_FIELD_STRING_REQUIRED",
                "Transition target must be a string.",
                "steps[0].on.ok"
        );
    }

    @Test
    void flowIdMustBeANonBlankString() {
        assertDiagnostic(
                replace("\"id\": \"lowercase-app\"", "\"id\": null"),
                "FLOW_FIELD_STRING_REQUIRED",
                "id must be a non-blank string.",
                "id"
        );
    }

    @Test
    void flowIdCannotContainOnlyWhitespace() {
        assertDiagnostic(
                replace("\"id\": \"lowercase-app\"", "\"id\": \" \""),
                "FLOW_FIELD_STRING_REQUIRED",
                "id must be a non-blank string.",
                "id"
        );
    }

    private static void assertDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        final CompileResult result = FlowCompiler.compile(
                source,
                StepCatalog.of(LowercaseStep.definition())
        );

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(code, message, path)
        )));
    }

    private static CompileResult.Compiled compiled(final String source) {
        final CompileResult result = FlowCompiler.compile(source, StepCatalog.of(LowercaseStep.definition()));
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private static String replace(final String original, final String replacement) {
        return FlowFixtures.lowercaseFlow().replace(original, replacement);
    }

    private static String replaceSteps(final String replacement) {
        return document(replacement, """
                [
                  {"from": "input.text", "to": "lowercase.text"},
                  {"from": "lowercase.text", "to": "output.text"}
                ]
                """);
    }

    private static String replaceConnections(final String replacement) {
        return document("""
                [
                  {"id": "lowercase", "use": "text.lowercase", "config": {}, "on": {"ok": "end"}}
                ]
                """, replacement);
    }

    private static String document(final String steps, final String connections) {
        return """
                {
                  "id": "lowercase-app",
                  "triggers": [],
                  "entry": "lowercase",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": %s,
                  "connections": %s
                }
                """.formatted(steps, connections);
    }
}
