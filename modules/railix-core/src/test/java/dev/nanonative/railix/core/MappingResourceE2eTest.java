package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MappingResourceE2eTest {
    @Test
    void extremeStringToNumberRejectsInside64MiB() throws Exception {
        assertThat(probe("string-extreme")).isEqualTo(new ProbeResult(
                true, 0, "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED"
        ));
    }

    @Test
    void extremeNumberToStringRejectsInside64MiB() throws Exception {
        assertThat(probe("number-extreme")).isEqualTo(new ProbeResult(
                true, 0, "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED"
        ));
    }

    @Test
    void repeated512LeafAssemblyStaysInside64MiB() throws Exception {
        assertThat(probe("wide")).isEqualTo(new ProbeResult(true, 0, "wide-ok"));
    }

    @Test
    void extremeExponentDefaultRejectsInside64MiB() throws Exception {
        assertThat(probe("flow-number-extreme")).isEqualTo(new ProbeResult(
                true, 0, "FLOW_NUMBER_LIMIT_EXCEEDED"
        ));
    }

    @Test
    void scaleOverflowDefaultRejectsInside64MiB() throws Exception {
        assertThat(probe("flow-number-scale-overflow")).isEqualTo(new ProbeResult(
                true, 0, "FLOW_NUMBER_LIMIT_EXCEEDED"
        ));
    }

    @Test
    void deeplyNestedDefaultRejectsInside64MiB() throws Exception {
        assertThat(probe("flow-deep")).isEqualTo(new ProbeResult(
                true, 0, "FLOW_DEPTH_EXCEEDED"
        ));
    }

    @Test
    void millionDigitNumberTextRejectsWithinFiveSeconds() throws Exception {
        assertThat(probe("million-digit", 5)).isEqualTo(new ProbeResult(
                true, 0, "FLOW_MAPPING_CONVERSION_LIMIT_EXCEEDED"
        ));
    }

    public static void main(final String[] arguments) {
        final String result = switch (arguments[0]) {
            case "string-extreme" -> rejectionCode(runConversion(
                    ValueShape.STRING,
                    ValueShape.NUMBER,
                    "string-to-number",
                    RailixValue.string("1e-2147483647")
            ));
            case "number-extreme" -> rejectionCode(runConversion(
                    ValueShape.NUMBER,
                    ValueShape.STRING,
                    "number-to-string",
                    RailixValue.number(new BigDecimal("1e-2147483647"))
            ));
            case "wide" -> wideProbe();
            case "flow-number-extreme" -> compilationCode(NestedDataMappingFlowE2eTest.flow(
                    ValueShape.OBJECT,
                    ValueShape.NUMBER,
                    "{\"from\":\"input.source\",\"sourcePath\":[\"missing\"],\"default\":1e2147483647,\"to\":\"sink.value\"}"
            ), ValueShape.NUMBER);
            case "flow-number-scale-overflow" -> compilationCode(NestedDataMappingFlowE2eTest.flow(
                    ValueShape.OBJECT,
                    ValueShape.NUMBER,
                    "{\"from\":\"input.source\",\"sourcePath\":[\"missing\"],\"default\":100e2147483647,\"to\":\"sink.value\"}"
            ), ValueShape.NUMBER);
            case "flow-deep" -> compilationCode(NestedDataMappingFlowE2eTest.flow(
                    ValueShape.OBJECT,
                    ValueShape.ANY,
                    "{\"from\":\"input.source\",\"sourcePath\":[\"missing\"],\"default\":"
                            + "[".repeat(20_000) + "0" + "]".repeat(20_000)
                            + ",\"to\":\"sink.value\"}"
            ), ValueShape.ANY);
            case "million-digit" -> rejectionCode(runConversion(
                    ValueShape.STRING,
                    ValueShape.NUMBER,
                    "string-to-number",
                    RailixValue.string("1".repeat(1_048_576))
            ));
            default -> "unknown-probe";
        };
        System.out.print(result);
    }

    private static ProbeResult probe(final String mode) throws Exception {
        return probe(mode, 20);
    }

    private static ProbeResult probe(final String mode, final int timeoutSeconds) throws Exception {
        final String classpath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-cp",
                classpath,
                MappingResourceE2eTest.class.getName(),
                mode
        ).redirectErrorStream(true).start();
        final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                return new ProbeResult(false, -1, "probe-timeout");
            }
            return new ProbeResult(false, process.exitValue(), "probe-timeout");
        }
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new ProbeResult(finished, finished ? process.exitValue() : -1, output);
    }

    private static RunResult runConversion(
            final ValueShape sourceShape,
            final ValueShape targetShape,
            final String conversion,
            final RailixValue value
    ) {
        final String mapping = "{\"from\":\"input.source\",\"convert\":\"" + conversion
                + "\",\"to\":\"sink.value\"}";
        return compiled(NestedDataMappingFlowE2eTest.flow(sourceShape, targetShape, mapping), targetShape)
                .run(RailixValue.object(Map.of("source", value)));
    }

    private static String wideProbe() {
        final StringBuilder mappings = new StringBuilder();
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        for (int index = 0; index < 512; index++) {
            final String field = "field" + index;
            if (index > 0) {
                mappings.append(',');
            }
            mappings.append("{\"from\":\"input.source\",\"sourcePath\":[\"")
                    .append(field)
                    .append("\"],\"to\":\"sink.value\",\"targetPath\":[\"")
                    .append(field)
                    .append("\"]}");
            fields.put(field, RailixValue.number(index));
        }
        final CompiledFlow flow = compiled(NestedDataMappingFlowE2eTest.flow(
                ValueShape.OBJECT,
                ValueShape.OBJECT,
                mappings.toString()
        ), ValueShape.OBJECT);
        final RailixValue.ObjectValue event = RailixValue.object(Map.of(
                "source", RailixValue.object(fields)
        ));
        final RunResult expected = new RunResult.Succeeded(
                RailixValue.object(Map.of("result", RailixValue.object(fields))),
                List.of(new RunResult.StepExecution("sink", "ok"))
        );
        for (int run = 0; run < 100; run++) {
            if (!expected.equals(flow.run(event))) {
                return "wide-mismatch";
            }
        }
        return "wide-ok";
    }

    private static CompiledFlow compiled(final String source, final ValueShape sinkShape) {
        final CompileResult result = FlowCompiler.compile(source, StepCatalog.of(sink(sinkShape)));
        if (result instanceof CompileResult.Compiled compiled) {
            return compiled.flow();
        }
        throw new AssertionError(result);
    }

    private static String compilationCode(final String source, final ValueShape sinkShape) {
        final CompileResult result = FlowCompiler.compile(source, StepCatalog.of(sink(sinkShape)));
        return result instanceof CompileResult.Rejected rejected
                ? rejected.diagnostics().getFirst().code()
                : result.getClass().getSimpleName();
    }

    private static StepDefinition sink(final ValueShape shape) {
        return StepDefinition.named("sink", "1.0.0")
                .input("value", shape)
                .output("value", shape)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("value", input.value("value")));
    }

    private static String rejectionCode(final RunResult result) {
        return result instanceof RunResult.Rejected rejected
                ? rejected.diagnostics().getFirst().code()
                : result.getClass().getSimpleName();
    }

    private record ProbeResult(boolean finished, int exitCode, String output) {
    }
}
