package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NestedDataMappingFlowE2eTest {
    @Test
    void selectsAnObjectFieldIntoAStepInput() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"profile\",\"name\"],\"to\":\"sink.value\"}",
                object("profile", object("name", RailixValue.string("Ada")))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("Ada")));
    }

    @Test
    void selectorTokensKeepDotsAndBracketsInsideObjectKeys() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"a.b\",\"[0]\"],\"to\":\"sink.value\"}",
                object("a.b", object("[0]", RailixValue.string("literal")))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("literal")));
    }

    @Test
    void anEmptyStringPathTokenSelectsAnEmptyObjectKey() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"\"],\"to\":\"sink.value\"}",
                object("", RailixValue.string("empty-key"))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("empty-key")));
    }

    @Test
    void aNumericStringPathTokenSelectsAnObjectKeyNotAnArrayIndex() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"0\"],\"to\":\"sink.value\"}",
                object("0", RailixValue.string("object-key"))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("object-key")));
    }

    @Test
    void selectsAnArrayIndexIntoAStepInput() {
        final RunResult result = run(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[1],\"to\":\"sink.value\"}",
                RailixValue.array(List.of(RailixValue.string("zero"), RailixValue.string("one")))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("one")));
    }

    @Test
    void aMathematicallyIntegralDecimalIsAnArrayIndex() {
        final RunResult result = run(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[1.0],\"to\":\"sink.value\"}",
                RailixValue.array(List.of(RailixValue.string("zero"), RailixValue.string("one")))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("one")));
    }

    @Test
    void theLargestSupportedSourceIndexDoesNotAllocateOrInventArrayItems() {
        final RunResult result = run(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[2147483647],\"default\":\"missing\",\"to\":\"sink.value\"}",
                RailixValue.array(List.of())
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("missing")));
    }

    @Test
    void selectsThroughMixedObjectAndArrayContainers() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"items\",1,\"price\"],\"to\":\"sink.value\"}",
                object("items", RailixValue.array(List.of(
                        object("price", RailixValue.number(10)),
                        object("price", RailixValue.number(20))
                )))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(20)));
    }

    @Test
    void assemblesMultipleLeavesIntoOneObjectInput() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["person","first"]},
                        {"from":"input.source","sourcePath":["last"],"to":"sink.value","targetPath":["person","last"]}
                        """,
                RailixValue.object(Map.of(
                        "first", RailixValue.string("Ada"),
                        "last", RailixValue.string("Lovelace")
                ))
        );

        assertThat(result).isEqualTo(succeeded(object("person", RailixValue.object(Map.of(
                "first", RailixValue.string("Ada"),
                "last", RailixValue.string("Lovelace")
        )))));
    }

    @Test
    void assemblesContiguousArrayElements() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.ARRAY,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":[0]},
                        {"from":"input.source","sourcePath":["second"],"to":"sink.value","targetPath":[1]}
                        """,
                RailixValue.object(Map.of(
                        "first", RailixValue.string("zero"),
                        "second", RailixValue.string("one")
                ))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.array(List.of(
                RailixValue.string("zero"),
                RailixValue.string("one")
        ))));
    }

    @Test
    void assemblesNestedArraysAndObjectsIndependentlyOfConnectionOrder() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["second"],"to":"sink.value","targetPath":["items",1,"name"]},
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["items",0,"name"]}
                        """,
                RailixValue.object(Map.of(
                        "first", RailixValue.string("zero"),
                        "second", RailixValue.string("one")
                ))
        );

        assertThat(result).isEqualTo(succeeded(object("items", RailixValue.array(List.of(
                object("name", RailixValue.string("zero")),
                object("name", RailixValue.string("one"))
        )))));
    }

    @Test
    void usesAnExplicitDefaultOnlyWhenAFieldIsMissing() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"default\":\"unknown\",\"to\":\"sink.value\"}",
                RailixValue.object(Map.of())
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("unknown")));
    }

    @Test
    void explicitNullAtASelectedPathDoesNotUseTheDefault() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.ANY,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"default\":\"unknown\",\"to\":\"sink.value\"}",
                object("name", RailixValue.nullValue())
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.nullValue()));
    }

    @Test
    void anExplicitNullDefaultIsDifferentFromNoDefault() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.ANY,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"default\":null,\"to\":\"sink.value\"}",
                RailixValue.object(Map.of())
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.nullValue()));
    }

    @Test
    void convertsANumberToItsCanonicalString() {
        final RunResult result = run(
                ValueShape.NUMBER,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"number-to-string\",\"to\":\"sink.value\"}",
                RailixValue.number(42)
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("42")));
    }

    @Test
    void convertsABooleanToItsCanonicalString() {
        final RunResult result = run(
                ValueShape.BOOLEAN,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"boolean-to-string\",\"to\":\"sink.value\"}",
                RailixValue.bool(true)
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("true")));
    }

    @Test
    void convertsFalseToItsCanonicalString() {
        final RunResult result = run(
                ValueShape.BOOLEAN,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"boolean-to-string\",\"to\":\"sink.value\"}",
                RailixValue.bool(false)
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("false")));
    }

    @Test
    void convertsAJsonNumberStringToANumber() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("1.25e2")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(125)));
    }

    @Test
    void convertsTheExactTrueStringToABoolean() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.BOOLEAN,
                "{\"from\":\"input.source\",\"convert\":\"string-to-boolean\",\"to\":\"sink.value\"}",
                RailixValue.string("true")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.bool(true)));
    }

    @Test
    void convertsTheExactFalseStringToABoolean() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.BOOLEAN,
                "{\"from\":\"input.source\",\"convert\":\"string-to-boolean\",\"to\":\"sink.value\"}",
                RailixValue.string("false")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.bool(false)));
    }

    @Test
    void anExplicitDefaultBypassesConversionBecauseItIsAlreadyTargetTyped() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"count\"],\"default\":7,\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.object(Map.of())
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(7)));
    }

    @Test
    void assemblesNestedMappingsDirectlyIntoAFlowOutput() {
        final String source = outputFlow("""
                {"from":"input.source","sourcePath":["name"],"to":"output.result","targetPath":["person","name"]}
                """);
        final RunResult result = compiled(source, StepCatalog.of(tick())).run(object(
                "source", object("name", RailixValue.string("Ada"))
        ));

        assertThat(result).isEqualTo(new RunResult.Succeeded(
                object("result", object("person", object("name", RailixValue.string("Ada")))),
                List.of(new RunResult.StepExecution("tick", "ok"))
        ));
    }

    @Test
    void aMissingPathWithoutDefaultFailsBeforeTheStepRuns() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"profile\",\"name\"],\"to\":\"sink.value\"}",
                RailixValue.object(Map.of())
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_SOURCE_MISSING",
                "Source path [\"profile\",\"name\"] does not exist.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void aDefaultDoesNotHideAnIntermediateContainerConflict() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"profile\",\"name\"],\"default\":\"unknown\",\"to\":\"sink.value\"}",
                object("profile", RailixValue.string("not-an-object"))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_SOURCE_TYPE_MISMATCH",
                "Source path [\"profile\",\"name\"] requires an object at [\"profile\"] but found STRING.",
                "connections[0].sourcePath[1]"
        ));
    }

    @Test
    void anOutOfBoundsArraySelectionIsAMissingPath() {
        final RunResult result = run(
                ValueShape.ARRAY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[2],\"to\":\"sink.value\"}",
                RailixValue.array(List.of(RailixValue.string("zero")))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_SOURCE_MISSING",
                "Source path [2] does not exist.",
                "connections[0].sourcePath[0]"
        ));
    }

    @Test
    void aSelectedLeafWithTheWrongTargetShapeFailsBeforeTheStepRuns() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"to\":\"sink.value\"}",
                object("value", RailixValue.number(7))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_TARGET_TYPE_MISMATCH",
                "Mapped input sink.value requires STRING but produced NUMBER.",
                "connections[0]"
        ));
    }

    @Test
    void anInvalidNumberConversionFailsBeforeTheStepRuns() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("seven")
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_INVALID",
                "Cannot convert STRING to NUMBER.",
                "connections[0].convert"
        ));
    }

    @Test
    void anInvalidBooleanConversionFailsBeforeTheStepRuns() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.BOOLEAN,
                "{\"from\":\"input.source\",\"convert\":\"string-to-boolean\",\"to\":\"sink.value\"}",
                RailixValue.string("TRUE")
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_INVALID",
                "Cannot convert STRING to BOOLEAN.",
                "connections[0].convert"
        ));
    }

    @Test
    void aFlowOutputMappingRejectsAfterControlFlowCompletes() {
        final AtomicInteger executions = new AtomicInteger();
        final StepDefinition countedTick = StepDefinition.named("tick", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    executions.incrementAndGet();
                    return StepResult.outcome("ok");
                });
        final RunResult result = compiled(
                outputFlow("""
                        {"from":"input.source","sourcePath":["name"],"to":"output.result","targetPath":["person","name"]}
                        """),
                StepCatalog.of(countedTick)
        ).run(object("source", RailixValue.object(Map.of())));

        assertThat(new LateRejectionObservation(executions.get(), result)).isEqualTo(
                new LateRejectionObservation(1, new RunResult.Rejected(
                        List.of(dev.nanonative.railix.core.flow.Diagnostic.atPath(
                                "FLOW_MAPPING_SOURCE_MISSING",
                                "Source path [\"name\"] does not exist.",
                                "connections[0].sourcePath[0]"
                        )),
                        List.of(new RunResult.StepExecution("tick", "ok"))
                ))
        );
    }

    @Test
    void aNestedIndexRequiresAnArrayContainer() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"items\",0],\"to\":\"sink.value\"}",
                object("items", RailixValue.object(Map.of()))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_SOURCE_TYPE_MISMATCH",
                "Source path [\"items\",0] requires an array at [\"items\"] but found OBJECT.",
                "connections[0].sourcePath[1]"
        ));
    }

    @Test
    void numberToStringRejectsADynamicallySelectedString() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"convert\":\"number-to-string\",\"to\":\"sink.value\"}",
                object("value", RailixValue.string("7"))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_TYPE_MISMATCH",
                "Conversion number-to-string requires NUMBER but selected STRING.",
                "connections[0].convert"
        ));
    }

    @Test
    void booleanToStringRejectsADynamicallySelectedNumber() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"convert\":\"boolean-to-string\",\"to\":\"sink.value\"}",
                object("value", RailixValue.number(1))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_TYPE_MISMATCH",
                "Conversion boolean-to-string requires BOOLEAN but selected NUMBER.",
                "connections[0].convert"
        ));
    }

    @Test
    void stringToNumberRejectsADynamicallySelectedBoolean() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                object("value", RailixValue.bool(true))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_TYPE_MISMATCH",
                "Conversion string-to-number requires STRING but selected BOOLEAN.",
                "connections[0].convert"
        ));
    }

    @Test
    void stringToBooleanRejectsADynamicallySelectedBoolean() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.BOOLEAN,
                "{\"from\":\"input.source\",\"sourcePath\":[\"value\"],\"convert\":\"string-to-boolean\",\"to\":\"sink.value\"}",
                object("value", RailixValue.bool(true))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_TYPE_MISMATCH",
                "Conversion string-to-boolean requires STRING but selected BOOLEAN.",
                "connections[0].convert"
        ));
    }

    @Test
    void stringToNumberRejectsSurroundingWhitespace() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string(" 7")
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_INVALID",
                "Cannot convert STRING to NUMBER.",
                "connections[0].convert"
        ));
    }

    @Test
    void stringToNumberRejectsAValueAboveTheCanonicalNumberLimit() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("1e1024")
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED",
                "Number conversion exceeds the 1024-character source or canonical limit.",
                "connections[0].convert"
        ));
    }

    @Test
    void numberToStringRejectsAValueAboveTheCanonicalNumberLimit() {
        final RunResult result = run(
                ValueShape.NUMBER,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"number-to-string\",\"to\":\"sink.value\"}",
                RailixValue.number(new BigDecimal("1e1024"))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED",
                "Number conversion exceeds the 1024-character source or canonical limit.",
                "connections[0].convert"
        ));
    }

    @Test
    void stringToNumberAcceptsTheExactCanonicalNumberLimit() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("1e1023")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(new BigDecimal("1e1023"))));
    }

    @Test
    void stringToNumberAcceptsExactly1024SourceCharacters() {
        final String number = "1" + "0".repeat(1_023);
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string(number)
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(new BigDecimal("1e1023"))));
    }

    @Test
    void stringToNumberRejects1025SourceCharacters() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("1" + "0".repeat(1_024))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED",
                "Number conversion exceeds the 1024-character source or canonical limit.",
                "connections[0].convert"
        ));
    }

    @Test
    void numberToStringAcceptsTheExactCanonicalNumberLimit() {
        final RunResult result = run(
                ValueShape.NUMBER,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"convert\":\"number-to-string\",\"to\":\"sink.value\"}",
                RailixValue.number(new BigDecimal("1e1023"))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("1" + "0".repeat(1023))));
    }

    @Test
    void multipleFieldsCanAssembleIntoTheSameArrayElement() {
        final RunResult result = run(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                """
                        {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["items",0,"first"]},
                        {"from":"input.source","sourcePath":["last"],"to":"sink.value","targetPath":["items",0,"last"]}
                        """,
                RailixValue.object(Map.of(
                        "first", RailixValue.string("Ada"),
                        "last", RailixValue.string("Lovelace")
                ))
        );

        assertThat(result).isEqualTo(succeeded(object("items", RailixValue.array(List.of(
                RailixValue.object(Map.of(
                        "first", RailixValue.string("Ada"),
                        "last", RailixValue.string("Lovelace")
                ))
        )))));
    }

    @Test
    void aCompiledMappingCanBeReusedWithoutRetainingAssemblyState() {
        final CompiledFlow flow = compiled(
                flow(
                        ValueShape.OBJECT,
                        ValueShape.OBJECT,
                        "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"to\":\"sink.value\",\"targetPath\":[\"person\",\"name\"]}"
                ),
                StepCatalog.of(sink(ValueShape.OBJECT))
        );
        final List<RunResult> results = List.of(
                flow.run(object("source", object("name", RailixValue.string("Ada")))),
                flow.run(object("source", object("name", RailixValue.string("Grace"))))
        );

        assertThat(results).containsExactly(
                succeeded(object("person", object("name", RailixValue.string("Ada")))),
                succeeded(object("person", object("name", RailixValue.string("Grace"))))
        );
    }

    @Test
    void concurrentMappingRunsKeepAllAssemblyStateInvocationLocal() throws Exception {
        final CompiledFlow flow = compiled(
                flow(
                        ValueShape.OBJECT,
                        ValueShape.OBJECT,
                        """
                                {"from":"input.source","sourcePath":["first"],"to":"sink.value","targetPath":["person","first"]},
                                {"from":"input.source","sourcePath":["last"],"to":"sink.value","targetPath":["person","last"]}
                                """
                ),
                StepCatalog.of(sink(ValueShape.OBJECT))
        );
        final List<Callable<RunResult>> tasks = new ArrayList<>();
        final List<RunResult> expected = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            final String first = "first-" + index;
            final String last = "last-" + index;
            tasks.add(() -> flow.run(object("source", RailixValue.object(Map.of(
                    "first", RailixValue.string(first),
                    "last", RailixValue.string(last)
            )))));
            expected.add(succeeded(object("person", RailixValue.object(Map.of(
                    "first", RailixValue.string(first),
                    "last", RailixValue.string(last)
            )))));
        }
        final List<RunResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = executor.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (final Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(results).containsExactlyElementsOf(expected);
    }

    @Test
    void anExact64TokenTargetPathExecutesWithoutTruncation() {
        final String mapping = "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":"
                + repeatedPath("level", 64) + "}";
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.OBJECT,
                mapping,
                RailixValue.string("leaf")
        );

        assertThat(result).isEqualTo(succeeded(nested("level", 64, RailixValue.string("leaf"))));
    }

    @Test
    void anAnySourceCanBeSelectedAtRuntime() {
        final RunResult result = run(
                ValueShape.ANY,
                ValueShape.STRING,
                "{\"from\":\"input.source\",\"sourcePath\":[\"name\"],\"to\":\"sink.value\"}",
                object("name", RailixValue.string("Ada"))
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.string("Ada")));
    }

    @Test
    void anAnyTargetCanBeAssembledAsAnArray() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.ANY,
                "{\"from\":\"input.source\",\"to\":\"sink.value\",\"targetPath\":[0]}",
                RailixValue.string("first")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.array(List.of(RailixValue.string("first")))));
    }

    @Test
    void anAnySourceCanUseAnExplicitConversion() {
        final RunResult result = run(
                ValueShape.ANY,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("7")
        );

        assertThat(result).isEqualTo(succeeded(RailixValue.number(7)));
    }

    @Test
    void validNonNumberJsonTextIsNotANumberConversion() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("true")
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_INVALID",
                "Cannot convert STRING to NUMBER.",
                "connections[0].convert"
        ));
    }

    @Test
    void oversizedNumberTextRejectsBeforeAllocationHeavyConversion() {
        final RunResult result = run(
                ValueShape.STRING,
                ValueShape.NUMBER,
                "{\"from\":\"input.source\",\"convert\":\"string-to-number\",\"to\":\"sink.value\"}",
                RailixValue.string("1".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1))
        );

        assertThat(result).isEqualTo(rejected(
                "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED",
                "Number conversion exceeds the 1024-character source or canonical limit.",
                "connections[0].convert"
        ));
    }

    @Test
    void mappingsForAnUntakenControlBranchAreNotEvaluated() {
        final String source = """
                {
                  "id": "lazy-mapping",
                  "triggers": [],
                  "entry": "branch",
                  "inputs": {"source": "object"},
                  "outputs": {"result": "object"},
                  "steps": [
                    {"id":"branch","use":"branch","config":{},"on":{"left":"left","right":"right"}},
                    {"id":"left","use":"leaf","config":{},"on":{"ok":"end"}},
                    {"id":"right","use":"leaf","config":{},"on":{"ok":"end"}}
                  ],
                  "connections": [
                    {"from":"input.source","sourcePath":["left"],"to":"left.value"},
                    {"from":"input.source","sourcePath":["missing"],"to":"right.value"},
                    {"from":"input.source","to":"output.result"}
                  ]
                }
                """;
        final StepDefinition branch = StepDefinition.named("branch", "1.0.0")
                .outcome("left")
                .outcome("right")
                .run(input -> StepResult.outcome("left"));
        final StepDefinition leaf = StepDefinition.named("leaf", "1.0.0")
                .input("value", ValueShape.STRING)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
        final RailixValue.ObjectValue event = object("source", object(
                "left", RailixValue.string("selected")
        ));
        final RunResult result = compiled(source, StepCatalog.of(branch, leaf)).run(event);

        assertThat(result).isEqualTo(new RunResult.Succeeded(
                object("result", object("left", RailixValue.string("selected"))),
                List.of(
                        new RunResult.StepExecution("branch", "left"),
                        new RunResult.StepExecution("left", "ok")
                )
        ));
    }

    @Test
    void canonicalFlowOutputOrderDoesNotChangeTheFirstRuntimeDiagnostic() {
        final String source = """
                {
                  "id":"canonical-output-order",
                  "triggers":[],
                  "entry":"tick",
                  "inputs":{"source":"object"},
                  "outputs":{"z":"string","a":"string"},
                  "steps":[{"id":"tick","use":"tick","config":{},"on":{"ok":"end"}}],
                  "connections":[
                    {"from":"input.source","sourcePath":["z"],"to":"output.z"},
                    {"from":"input.source","sourcePath":["a"],"to":"output.a"}
                  ]
                }
                """;
        final CompileResult.Compiled first = compiledResult(source, StepCatalog.of(tick()));
        final CompileResult.Compiled reopened = compiledResult(first.source(), StepCatalog.of(tick()));
        final RailixValue.ObjectValue input = object("source", RailixValue.object(Map.of()));
        final RunResult expected = new RunResult.Rejected(
                List.of(Diagnostic.atPath(
                        "FLOW_MAPPING_SOURCE_MISSING",
                        "Source path [\"a\"] does not exist.",
                        "connections[1].sourcePath[0]"
                )),
                List.of(new RunResult.StepExecution("tick", "ok"))
        );

        assertThat(List.of(first.flow().run(input), reopened.flow().run(input)))
                .containsExactly(expected, expected);
    }

    @Test
    void lockEquivalentStepInputOrderDoesNotChangeTheFirstRuntimeDiagnostic() {
        final String source = """
                {
                  "id":"canonical-input-order",
                  "triggers":[],
                  "entry":"sink",
                  "inputs":{"source":"object"},
                  "outputs":{"result":"object"},
                  "steps":[{"id":"sink","use":"ordered","config":{},"on":{"ok":"end"}}],
                  "connections":[
                    {"from":"input.source","sourcePath":["a"],"to":"sink.a"},
                    {"from":"input.source","sourcePath":["b"],"to":"sink.b"},
                    {"from":"input.source","to":"output.result"}
                  ]
                }
                """;
        final CompileResult.Compiled first = compiledResult(source, StepCatalog.of(orderedSink(false)));
        final CompileResult.Compiled reordered = compiledResult(source, StepCatalog.of(orderedSink(true)));
        final RailixValue.ObjectValue input = object("source", RailixValue.object(Map.of()));
        final RunResult expected = rejected(
                "FLOW_MAPPING_SOURCE_MISSING",
                "Source path [\"a\"] does not exist.",
                "connections[0].sourcePath[0]"
        );

        assertThat(new CanonicalOrderObservation(
                first.lock().equals(reordered.lock()),
                first.flow().run(input),
                reordered.flow().run(input)
        )).isEqualTo(new CanonicalOrderObservation(true, expected, expected));
    }

    private static RunResult run(
            final ValueShape sourceShape,
            final ValueShape targetShape,
            final String mappings,
            final RailixValue sourceValue
    ) {
        return compiled(
                flow(sourceShape, targetShape, mappings),
                StepCatalog.of(sink(targetShape))
        ).run(object("source", sourceValue));
    }

    private static CompiledFlow compiled(final String source, final StepCatalog catalog) {
        return compiledResult(source, catalog).flow();
    }

    private static CompileResult.Compiled compiledResult(final String source, final StepCatalog catalog) {
        final CompileResult result = FlowCompiler.compile(source, catalog);
        if (result instanceof CompileResult.Compiled compiled) {
            return compiled;
        }
        throw new AssertionError("Expected flow to compile but got " + result);
    }

    static String flow(
            final ValueShape sourceShape,
            final ValueShape targetShape,
            final String mappings
    ) {
        return """
                {
                  "id": "mapping-app",
                  "triggers": [],
                  "entry": "sink",
                  "inputs": {"source": "%s"},
                  "outputs": {"result": "%s"},
                  "steps": [
                    {"id": "sink", "use": "sink", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    %s,
                    {"from": "sink.value", "to": "output.result"}
                  ]
                }
                """.formatted(shape(sourceShape), shape(targetShape), mappings);
    }

    private static String repeatedPath(final String field, final int depth) {
        return "[" + ("\"" + field + "\",").repeat(depth - 1) + "\"" + field + "\"]";
    }

    private static RailixValue nested(
            final String field,
            final int depth,
            final RailixValue leaf
    ) {
        RailixValue value = leaf;
        for (int index = 0; index < depth; index++) {
            value = object(field, value);
        }
        return value;
    }

    private static String outputFlow(final String mappings) {
        return """
                {
                  "id": "output-mapping-app",
                  "triggers": [],
                  "entry": "tick",
                  "inputs": {"source": "object"},
                  "outputs": {"result": "object"},
                  "steps": [
                    {"id": "tick", "use": "tick", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [%s]
                }
                """.formatted(mappings);
    }

    private static StepDefinition sink(final ValueShape shape) {
        return StepDefinition.named("sink", "1.0.0")
                .input("value", shape)
                .output("value", shape)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));
    }

    private static StepDefinition tick() {
        return StepDefinition.named("tick", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition orderedSink(final boolean reverse) {
        final StepDefinition.Builder builder = StepDefinition.named("ordered", "1.0.0");
        if (reverse) {
            builder.input("b", ValueShape.STRING).input("a", ValueShape.STRING);
        } else {
            builder.input("a", ValueShape.STRING).input("b", ValueShape.STRING);
        }
        return builder.outcome("ok").run(input -> StepResult.outcome("ok"));
    }

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(Locale.ROOT);
    }

    private static RailixValue.ObjectValue object(final String key, final RailixValue value) {
        return RailixValue.object(Map.of(key, value));
    }

    private static RunResult.Succeeded succeeded(final RailixValue value) {
        return new RunResult.Succeeded(
                object("result", value),
                List.of(new RunResult.StepExecution("sink", "ok"))
        );
    }

    private static RunResult.Rejected rejected(final String code, final String message, final String path) {
        return new RunResult.Rejected(List.of(dev.nanonative.railix.core.flow.Diagnostic.atPath(code, message, path)));
    }

    private record LateRejectionObservation(int handlerRuns, RunResult result) {
    }

    private record CanonicalOrderObservation(boolean locksEqual, RunResult first, RunResult reordered) {
    }
}
