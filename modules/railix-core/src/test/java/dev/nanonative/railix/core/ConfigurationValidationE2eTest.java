package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConfigurationValidationE2eTest {
    @ParameterizedTest
    @ValueSource(strings = {"8080.5", "2147483648", "-2147483649", "0.0000000000000000000001"})
    void integerRejectionPrecedesUserCode(final String value) {
        final var input = Input.json(ValueShape.NUMBER).validation(Integer.class, ignored -> {
            throw new AssertionError("Must not round or truncate.");
        });
        rejected(compile(input, "{\"value\":" + value + "}"), "PROJECT_INPUT_VALIDATION_TYPE", "nodes[2].inputs.value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "2147483647", "8080.0", "0"})
    void exactIntegersArePassedAsIntegers(final String value) {
        final AtomicInteger calls = new AtomicInteger();
        final var input = Input.json(ValueShape.NUMBER).validation(Integer.class, number -> {
            assertThat(number).isEqualTo(new BigDecimal(value).intValueExact());
            calls.incrementAndGet();
            return Optional.empty();
        });
        assertThat(compile(input, "{\"value\":" + value + "}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void customMessageHasTheOwningFieldAndDoesNotEchoTheValue() {
        final var input = Input.json(ValueShape.STRING).validation(String.class, value -> Optional.of("Use a local address."));
        final var result = rejected(compile(input, "{\"value\":\"secret\"}"),
                "PROJECT_INPUT_VALIDATION_INVALID", "nodes[2].inputs.value");
        assertThat(result.diagnostics().getFirst().message()).isEqualTo("Use a local address.");
    }

    @Test
    void defaultAndAuthoredValuesFollowTheSameValidation() {
        final var input = Input.json(ValueShape.NUMBER).validation(Integer.class, value -> value > 0
                ? Optional.empty() : Optional.of("Must be positive.")).defaultValue(RailixValue.number(-1));
        rejected(compile(input, "{}"), "PROJECT_INPUT_VALIDATION_INVALID", "nodes[2].inputs.value");
        assertThat(compile(input, "{\"value\":1}")).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void absentOptionalInputSkipsValidationButJsonNullRemainsPresent() {
        final AtomicInteger calls = new AtomicInteger();
        final var input = Input.json(ValueShape.ANY).optional().validation(RailixValue.class, value -> {
            calls.incrementAndGet();
            assertThat(value).isEqualTo(RailixValue.nullValue());
            return Optional.empty();
        });
        assertThat(compile(input, "{}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(calls).hasValue(0);
        assertThat(compile(input, "{\"value\":null}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void failuresAreDiagnosticsInsteadOfAcceptanceOrUncaughtExceptions() {
        rejected(compile(Input.json(ValueShape.STRING).validation(String.class, value -> null), "{\"value\":\"x\"}"),
                "PROJECT_INPUT_VALIDATION_FAILED", "nodes[2].inputs.value");
        rejected(compile(Input.json(ValueShape.STRING).validation(String.class, value -> Optional.of(" ")), "{\"value\":\"x\"}"),
                "PROJECT_INPUT_VALIDATION_FAILED", "nodes[2].inputs.value");
        rejected(compile(Input.json(ValueShape.STRING).validation(String.class, value -> {
            throw new IllegalStateException("Do not leak secrets.");
        }), "{\"value\":\"x\"}"), "PROJECT_INPUT_VALIDATION_FAILED", "nodes[2].inputs.value");
    }

    @Test
    void rangeAndShapeStillRejectBeforeTheCallback() {
        final var input = Input.json(ValueShape.NUMBER).validation(Integer.class, value -> {
            throw new AssertionError("Invalid values must not reach the callback.");
        }).between(RailixValue.number(1), RailixValue.number(10));
        rejected(compile(input, "{\"value\":0}"), "PROJECT_INPUT_RANGE_INVALID", "nodes[2].inputs.value");
        rejected(compile(input, "{\"value\":\"wrong\"}"), "PROJECT_INPUT_SHAPE_INVALID", "nodes[2].inputs.value");
        rejected(compile(input, "{}"), "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.value");
    }

    @Test
    void decimalLongBooleanAndContainersKeepTheirJavaTypes() {
        assertThat(compile(Input.json(ValueShape.NUMBER).validation(BigDecimal.class, value -> {
            assertThat(value).isEqualByComparingTo("0.1234567890123456789");
            return Optional.empty();
        }), "{\"value\":0.1234567890123456789}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(compile(Input.json(ValueShape.NUMBER).validation(Long.class, value -> {
            assertThat(value).isEqualTo(Long.MAX_VALUE);
            return Optional.empty();
        }), "{\"value\":9223372036854775807}")).isInstanceOf(CompileResult.Compiled.class);
        rejected(compile(Input.json(ValueShape.NUMBER).validation(Long.class, value -> Optional.empty()),
                "{\"value\":9223372036854775808}"), "PROJECT_INPUT_VALIDATION_TYPE", "nodes[2].inputs.value");
        assertThat(compile(Input.json(ValueShape.BOOLEAN).validation(Boolean.class, value -> {
            assertThat(value).isFalse();
            return Optional.empty();
        }), "{\"value\":false}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(compile(Input.json(ValueShape.OBJECT).validation(RailixValue.ObjectValue.class,
                value -> Optional.empty()), "{\"value\":{}}")).isInstanceOf(CompileResult.Compiled.class);
        assertThat(compile(Input.json(ValueShape.ARRAY).validation(RailixValue.ArrayValue.class,
                value -> Optional.empty()), "{\"value\":[]}")).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void ownedInputRetainsItsExactPath() {
        final var input = Input.options(Input.option("custom").input("name", Input.json(ValueShape.STRING)
                .validation(String.class, value -> Optional.of("Name is reserved."))));
        rejected(compile(input, "{\"value\":{\"option\":\"custom\",\"inputs\":{\"name\":\"root\"}}}"),
                "PROJECT_INPUT_VALIDATION_INVALID", "nodes[2].inputs.value.inputs.name");
    }

    @Test
    void definitionsRejectUnsupportedTypesAndExportRequiresAProvider() {
        assertThatIllegalArgumentException().isThrownBy(() -> Input.json(ValueShape.STRING).validation(Integer.class, value -> Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> Input.json(ValueShape.NUMBER).validation(Double.class, value -> Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> Input.json(ValueShape.NUMBER).validation(Integer.class, null));
        final var definition = step(Input.json(ValueShape.STRING).validation(String.class, value -> Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> StepContractJson.writeManifest(List.of(definition)))
                .withMessageContaining("definitions provider");
        assertThat(StepContractJson.writeManifest(ConfigurationValidationE2eTest.class, List.of(definition)))
                .contains("\"validation\":\"String\"", "\"definitions\"");
    }

    private static CompileResult compile(final StepDefinition.Input input, final String values) {
        return ProjectCompiler.compileApplication("""
                {"format":1,"id":"validation-test","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"trigger","use":"test.trigger","inputs":{},"examples":[{"name":"default","payload":null}]},
                  {"id":"step","use":"test.step","inputs":%s}],
                 "links":[{"from":"app.start","to":"trigger"},
                          {"from":"trigger.next","to":"step"},{"from":"step.next","to":"end"}]}
                """.formatted(values), StepCatalog.of(
                StepDefinition.named("railix.app", "1").kind(StepDefinition.Kind.APP).define(),
                StepDefinition.named("test.trigger", "1").kind(StepDefinition.Kind.TRIGGER).source("test")
                        .implementation(TestStepHandlers.PrimaryOutcome.class), step(input)));
    }

    private static StepDefinition step(final StepDefinition.Input input) {
        return StepDefinition.named("test.step", "1").input("value", input).implementation(TestStepHandlers.PrimaryOutcome.class);
    }

    private static CompileResult.Rejected rejected(final CompileResult result, final String code, final String path) {
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        final var rejected = (CompileResult.Rejected) result;
        assertThat(rejected.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(code);
            assertThat(diagnostic.path()).isEqualTo(path);
        });
        return rejected;
    }
}
