package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTriggerCompilationE2eTest {
    private static final StepCatalog CATALOG = StepCatalog.of(
            StepDefinition.named("noop", "1.0.0")
                    .outcome("ok")
                    .run(input -> StepResult.outcome("ok"))
    );

    @Test
    void compilerPublishesEveryImplementedTrigger() {
        assertThat(FlowCompiler.supportedTriggers())
                .containsExactly("cli", "startup", "http", "socket", "scheduled");
    }

    @Test
    void cliTriggerAcceptsOneJsonObjectFromStdin() {
        assertCompiled(flow(
                "[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":true}}]",
                "{\"name\":\"string\"}"
        ));
    }

    @Test
    void cliTriggerCanDeclareThatItDoesNotReadStdin() {
        assertCompiled(flow(
                "[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]",
                "{}"
        ));
    }

    @Test
    void cliArgumentsCanSupplyAnArrayInputWithoutStdin() {
        assertCompiled(flow(
                """
                [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                """,
                "{\"arguments\":\"array\"}"
        ));
    }

    @Test
    void cliArgumentsCanSupplyAnAnyInputWithoutStdin() {
        assertCompiled(flow(
                """
                [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                """,
                "{\"arguments\":\"any\"}"
        ));
    }

    @Test
    void startupTriggerAcceptsOnlyAnInputlessFlow() {
        assertCompiled(flow(
                "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]",
                "{}"
        ));
    }

    @Test
    void multipleCliAndStartupTriggersCompileWithoutImplicitSelection() {
        assertCompiled(flow(
                """
                [
                  {"id":"initialize-database","type":"startup","config":{}},
                  {"id":"import","type":"cli","config":{"stdin":false}},
                  {"id":"initialize-cache","type":"startup","config":{}},
                  {"id":"export","type":"cli","config":{"stdin":false}}
                ]
                """,
                "{}"
        ));
    }

    @Test
    void compiledPlanPreservesTriggerOrderAndConfiguration() {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(flow(
                """
                [
                  {"id":"initialize","type":"startup","config":{}},
                  {"id":"command","type":"cli","config":{"stdin":false}}
                ]
                """,
                "{}"
        ), CATALOG);

        assertThat(compiled.flow().triggers()).containsExactly(
                new CompiledFlow.Trigger(
                        "initialize",
                        "startup",
                        RailixValue.object(Map.of())
                ),
                new CompiledFlow.Trigger(
                        "command",
                        "cli",
                        RailixValue.object(Map.of("stdin", RailixValue.bool(false)))
                )
        );
    }

    @Test
    void cliTriggerRequiresTheStdinDecision() {
        assertDiagnostic(
                flow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{}}]", "{}"),
                "FLOW_TRIGGER_CLI_STDIN_REQUIRED",
                "CLI trigger config field stdin must be a boolean.",
                "triggers[0].config.stdin"
        );
    }

    @Test
    void cliTriggerRejectsANonBooleanStdinDecision() {
        assertDiagnostic(
                flow(
                        "[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":\"yes\"}}]",
                        "{}"
                ),
                "FLOW_TRIGGER_CLI_STDIN_REQUIRED",
                "CLI trigger config field stdin must be a boolean.",
                "triggers[0].config.stdin"
        );
    }

    @Test
    void cliTriggerRejectsUnknownConfiguration() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":true,"retry":true}}]
                        """,
                        "{}"
                ),
                "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                "Unknown cli trigger config field: retry",
                "triggers[0].config.retry"
        );
    }

    @Test
    void startupTriggerRejectsUnknownConfiguration() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"initialize","type":"startup","config":{"event":{}}}]
                        """,
                        "{}"
                ),
                "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                "Unknown startup trigger config field: event",
                "triggers[0].config.event"
        );
    }

    @Test
    void cliArgumentsInputMustBeAString() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":7}}]
                        """,
                        "{\"arguments\":\"array\"}"
                ),
                "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_REQUIRED",
                "CLI trigger config field arguments must name a flow input.",
                "triggers[0].config.arguments"
        );
    }

    @Test
    void cliArgumentsInputMustNotBeBlank() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":" "}}]
                        """,
                        "{\"arguments\":\"array\"}"
                ),
                "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_REQUIRED",
                "CLI trigger config field arguments must name a flow input.",
                "triggers[0].config.arguments"
        );
    }

    @Test
    void cliArgumentsInputMustExist() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":"arguments"}}]
                        """,
                        "{}"
                ),
                "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_UNKNOWN",
                "CLI arguments input does not exist: arguments.",
                "triggers[0].config.arguments"
        );
    }

    @Test
    void cliArgumentsInputMustAcceptAnArray() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":"arguments"}}]
                        """,
                        "{\"arguments\":\"string\"}"
                ),
                "FLOW_TRIGGER_CLI_ARGUMENTS_INPUT_TYPE_MISMATCH",
                "CLI arguments input must be array or any: arguments.",
                "triggers[0].config.arguments"
        );
    }

    @Test
    void cliWithoutStdinMustSupplyEveryFlowInput() {
        assertDiagnostic(
                flow(
                        "[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]",
                        "{\"name\":\"string\"}"
                ),
                "FLOW_TRIGGER_CLI_INPUT_UNAVAILABLE",
                "CLI trigger cannot supply flow input without stdin: name.",
                "triggers[0].config.stdin"
        );
    }

    @Test
    void cliArgumentsMappingDoesNotInventOtherInputs() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """,
                        "{\"arguments\":\"array\",\"name\":\"string\"}"
                ),
                "FLOW_TRIGGER_CLI_INPUT_UNAVAILABLE",
                "CLI trigger cannot supply flow input without stdin: name.",
                "triggers[0].config.stdin"
        );
    }

    @Test
    void startupTriggerRejectsAFlowInputInsteadOfInventingAnEvent() {
        assertDiagnostic(
                flow(
                        "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]",
                        "{\"name\":\"string\"}"
                ),
                "FLOW_TRIGGER_STARTUP_INPUTS_UNSUPPORTED",
                "Startup trigger requires an inputless flow.",
                "triggers[0].config"
        );
    }

    @Test
    void triggerConfigurationDiagnosticOrderDoesNotDependOnFieldOrder() {
        final CompileResult first = FlowCompiler.compile(flow(
                """
                [{"id":"command","type":"cli","config":{"beta":true,"stdin":true,"alpha":true}}]
                """,
                "{}"
        ), CATALOG);
        final CompileResult second = FlowCompiler.compile(flow(
                """
                [{"id":"command","type":"cli","config":{"alpha":true,"stdin":true,"beta":true}}]
                """,
                "{}"
        ), CATALOG);
        final CompileResult.Rejected expected = new CompileResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                        "Unknown cli trigger config field: alpha",
                        "triggers[0].config.alpha"
                ),
                Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                        "Unknown cli trigger config field: beta",
                        "triggers[0].config.beta"
                )
        ));

        assertThat(List.of(first, second)).containsExactly(expected, expected);
    }

    private static void assertCompiled(final String source) {
        assertThat(FlowCompiler.compile(source, CATALOG)).isInstanceOf(CompileResult.Compiled.class);
    }

    private static void assertDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(source, CATALOG)).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(code, message, path)
        )));
    }

    private static String flow(final String triggers, final String inputs) {
        return """
                {
                  "id":"application-triggers",
                  "triggers":%s,
                  "entry":"noop",
                  "inputs":%s,
                  "outputs":{},
                  "steps":[{"id":"noop","use":"noop","config":{},"on":{"ok":"end"}}],
                  "connections":[]
                }
                """.formatted(triggers, inputs);
    }
}
