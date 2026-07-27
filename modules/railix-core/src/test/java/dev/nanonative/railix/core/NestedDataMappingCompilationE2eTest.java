package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NestedDataMappingCompilationE2eTest {
    @Test
    void sourcePathMustBeAnArray() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":\"name\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_ARRAY_REQUIRED",
                "sourcePath must be an array of field names and array indexes.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void targetPathMustBeAnArray() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":\"name\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_ARRAY_REQUIRED",
                "targetPath must be an array of field names and array indexes.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void aPresentPathMustNotBeEmpty() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_EMPTY",
                "sourcePath must not be empty when present.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void aPresentTargetPathMustNotBeEmpty() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_EMPTY",
                "targetPath must not be empty when present.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void pathTokensMustBeFieldNamesOrIndexes() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[true],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void nullIsNotAPathToken() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[null],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void anObjectIsNotAPathToken() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[{}],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void anArrayIsNotAPathToken() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[[]],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void targetPathUsesTheSameTypedTokenContract() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[false]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].targetPath[0]"
        ));
    }

    @Test
    void arrayIndexesMustNotBeNegative() {
        final CompileResult result = compile(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[-1],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void arrayIndexesMustBeIntegers() {
        final CompileResult result = compile(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[1.5],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_TOKEN_INVALID",
                "Path elements must be field names or non-negative integer array indexes.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void arrayIndexesHaveAnExplicitIntegerLimit() {
        final CompileResult result = compile(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[2147483648],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_INDEX_LIMIT_EXCEEDED",
                "Array indexes must not exceed 2147483647.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void pathsHaveAnExplicitDepthLimit() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":" + path(65) + ",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_DEPTH_EXCEEDED",
                "sourcePath exceeds the maximum depth of 64.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void targetPathsHaveTheSameExplicitDepthLimit() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":" + path(65) + "}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_PATH_DEPTH_EXCEEDED",
                "targetPath exceeds the maximum depth of 64.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void aKnownScalarSourceCannotHaveANestedSelector() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_SOURCE_PATH_TYPE_MISMATCH",
                "Cannot select a sourcePath from STRING.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void anObjectSourceMustStartWithAFieldToken() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[0],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_SOURCE_PATH_TYPE_MISMATCH",
                "Selector root requires ARRAY but source is OBJECT.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void anArraySourceMustStartWithAnIndexToken() {
        final CompileResult result = compile(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_SOURCE_PATH_TYPE_MISMATCH",
                "Selector root requires OBJECT but source is ARRAY.",
                "connections[0].sourcePath"
        ));
    }

    @Test
    void aKnownScalarTargetCannotHaveANestedPath() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[\"name\"]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_PATH_TYPE_MISMATCH",
                "Cannot assemble a targetPath into STRING.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void anObjectTargetMustStartWithAFieldToken() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[0]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_PATH_TYPE_MISMATCH",
                "Target path root requires ARRAY but target is OBJECT.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void anArrayTargetMustStartWithAnIndexToken() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.ARRAY,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[\"name\"]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_PATH_TYPE_MISMATCH",
                "Target path root requires OBJECT but target is ARRAY.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void theSameNestedTargetPathCannotBeMappedTwice() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["name"]},
                        {"from":"input.source","sourcePath":["second"],"to":"sink.value","targetPath":["name"]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_DUPLICATE",
                "Target path is already mapped: [\"name\"].",
                "connections[1]"
        ));
    }

    @Test
    void aWholeTargetMappingCannotOverlapANestedMapping() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","to":"sink.value"},
                        {"from":"input.source","sourcePath":["name"],"to":"sink.value","targetPath":["name"]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_CONFLICT",
                "Target paths overlap: [] and [\"name\"].",
                "connections[1]"
        ));
    }

    @Test
    void aParentTargetPathCannotOverlapAChildPath() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["user"],"to":"sink.value","targetPath":["user"]},
                        {"from":"input.source","sourcePath":["name"],"to":"sink.value","targetPath":["user","name"]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_CONFLICT",
                "Target paths overlap: [\"user\"] and [\"user\",\"name\"].",
                "connections[1]"
        ));
    }

    @Test
    void aChildTargetPathCannotPrecedeItsParentPath() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["name"],"to":"sink.value","targetPath":["user","name"]},
                        {"from":"input.source","sourcePath":["user"],"to":"sink.value","targetPath":["user"]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_CONFLICT",
                "Target paths overlap: [\"user\",\"name\"] and [\"user\"].",
                "connections[1]"
        ));
    }

    @Test
    void oneTargetContainerCannotBeBothObjectAndArray() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["value",0]},
                        {"from":"input.source","sourcePath":["second"],"to":"sink.value","targetPath":["value","name"]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_CONFLICT",
                "Target path requires both ARRAY and OBJECT at [\"value\"].",
                "connections[1]"
        ));
    }

    @Test
    void mixedTargetContainerKindsRejectInReverseConnectionOrder() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["value","name"]},
                        {"from":"input.source","sourcePath":["second"],"to":"sink.value","targetPath":["value",0]}
                        """
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_CONFLICT",
                "Target path requires both ARRAY and OBJECT at [\"value\"].",
                "connections[1]"
        ));
    }

    @Test
    void targetArraysCannotContainInventedHoles() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.ARRAY,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[1]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TARGET_ARRAY_HOLE",
                "Target array at [] is missing index 0 before index 1.",
                "connections[0].targetPath"
        ));
    }

    @Test
    void defaultRequiresASelectorThatCanActuallyBeMissing() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"default\":\"unused\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_DEFAULT_REQUIRES_SOURCE_PATH",
                "default requires a sourcePath.",
                "connections[0].default"
        ));
    }

    @Test
    void conversionMustBeAString() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":true,\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_CONVERSION_INVALID",
                "convert must be a non-blank string.",
                "connections[0].convert"
        ));
    }

    @Test
    void conversionMustNotBeBlank() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\" \",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_CONVERSION_INVALID",
                "convert must be a non-blank string.",
                "connections[0].convert"
        ));
    }

    @Test
    void conversionMustNameASupportedTargetShape() {
        final CompileResult result = compile(
                ValueShape.STRING,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"date\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_CONVERSION_INVALID",
                "Unknown conversion: date. Supported conversions: string-to-number, number-to-string, string-to-boolean, boolean-to-string.",
                "connections[0].convert"
        ));
    }

    @Test
    void aKnownObjectCannotBeConvertedToANumber() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_CONVERSION_TYPE_MISMATCH",
                "Conversion string-to-number requires STRING but source is OBJECT.",
                "connections[0].convert"
        ));
    }

    @Test
    void aConversionMustProduceTheWholeTargetShape() {
        final CompileResult result = compile(
                ValueShape.NUMBER,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"number-to-string\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_TYPE_MISMATCH",
                "Cannot connect STRING to NUMBER.",
                "connections[0]"
        ));
    }

    @Test
    void anExplicitDefaultMustAlreadyMatchTheWholeTargetShape() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"default\":\"zero\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_DEFAULT_TYPE_MISMATCH",
                "Default requires NUMBER but is STRING.",
                "connections[0].default"
        ));
    }

    @Test
    void aConversionDefaultMustMatchTheConversionTargetForAnyPort() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.ANY,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"default\":\"zero\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_DEFAULT_TYPE_MISMATCH",
                "Default requires NUMBER but is STRING.",
                "connections[0].default"
        ));
    }

    @Test
    void aNestedConversionDefaultMustMatchTheConversionTarget() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"default\":\"zero\",\"convert\":\"string-to-number\",\"to\":\"sink.value\",\"targetPath\":[\"number\"]}"
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_CONNECTION_DEFAULT_TYPE_MISMATCH",
                "Default requires NUMBER but is STRING.",
                "connections[0].default"
        ));
    }

    @Test
    void canonicalSourceKeepsEveryExplicitMappingField() {
        final CompileResult result = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"sourcePath\":[\"age\"],\"default\":0,\"convert\":\"string-to-number\",\"to\":\"sink.value\",\"targetPath\":[\"age\"]}"
        );
        final String source = ((CompileResult.Compiled) result).source();

        assertThat(source).contains(
                "\"sourcePath\":[\"age\"]",
                "\"default\":0",
                "\"convert\":\"string-to-number\"",
                "\"targetPath\":[\"age\"]"
        );
    }

    @Test
    void canonicalMappingSourceRecompilesByteForByte() {
        final CompileResult first = compile(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                "{\"from\":\"input.source\",\"sourcePath\":[\"age\"],\"default\":0,\"convert\":\"string-to-number\",\"to\":\"sink.value\",\"targetPath\":[\"age\"]}"
        );
        final CompileResult.Compiled compiled = (CompileResult.Compiled) first;
        final CompileResult second = FlowCompiler.compile(compiled.source(), StepCatalog.of(sink(ValueShape.OBJECT)));

        assertThat(((CompileResult.Compiled) second).source()).isEqualTo(compiled.source());
    }

    private static CompileResult compile(
            final ValueShape sourceShape,
            final ValueShape targetShape,
            final String mappings
    ) {
        return FlowCompiler.compile(
                NestedDataMappingFlowE2eTest.flow(sourceShape, targetShape, mappings),
                StepCatalog.of(sink(targetShape))
        );
    }

    private static StepDefinition sink(final ValueShape shape) {
        return StepDefinition.named("sink", "1.0.0")
                .input("value", shape)
                .output("value", shape)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));
    }

    private static CompileResult.Rejected rejected(
            final String code,
            final String message,
            final String path
    ) {
        return new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path)));
    }

    private static String path(final int depth) {
        final StringBuilder path = new StringBuilder("[");
        for (int index = 0; index < depth; index++) {
            if (index > 0) {
                path.append(',');
            }
            path.append('"').append(index).append('"');
        }
        return path.append(']').toString();
    }
}
