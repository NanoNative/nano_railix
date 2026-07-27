package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.LowercaseStep;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowGraphCompilationDiagnosticsTest {
    private static final String STEP = """
            {"id": "lowercase", "use": "text.lowercase", "config": {}, "on": {"ok": "end"}}
            """;
    private static final String CONNECTIONS = """
            [
              {"from": "input.text", "to": "lowercase.text"},
              {"from": "lowercase.text", "to": "output.text"}
            ]
            """;

    @Test
    void compilerRequiresAStepCatalog() {
        final CompileResult result = FlowCompiler.compile(flow("lowercase", STEP, CONNECTIONS), null);

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_CATALOG_REQUIRED",
                "A Step catalog is required.",
                "catalog"
        ))));
    }

    @Test
    void flowEntryMustNameAnExistingStep() {
        assertDiagnostic(
                flow("missing", STEP, CONNECTIONS),
                "FLOW_ENTRY_UNKNOWN",
                "Entry Step does not exist: missing",
                "entry"
        );
    }

    @Test
    void flowStepDependencyMustExistInTheCatalog() {
        assertDiagnostic(
                flow("lowercase", STEP.replace("text.lowercase", "missing"), CONNECTIONS),
                "FLOW_STEP_UNKNOWN",
                "Unknown Step dependency: missing",
                "steps.lowercase.use"
        );
    }

    @Test
    void flowStepIdsMustBeUnique() {
        assertDiagnostic(
                flow("lowercase", STEP + "," + STEP, CONNECTIONS),
                "FLOW_STEP_ID_DUPLICATE",
                "Duplicate flow Step id: lowercase",
                "steps.lowercase"
        );
    }

    @Test
    void flowStepIdCannotUseTheReservedEndMarker() {
        assertDiagnostic(
                flow(
                        "end",
                        STEP.replace("\"lowercase\"", "\"end\""),
                        CONNECTIONS.replace("lowercase.", "end.")
                ),
                "FLOW_STEP_ID_RESERVED",
                "Flow Step id is reserved: end",
                "steps.end.id"
        );
    }

    @Test
    void connectionTargetsCanOnlyBeMappedOnce() {
        final String connections = """
                [
                  {"from": "input.text", "to": "lowercase.text"},
                  {"from": "input.text", "to": "lowercase.text"},
                  {"from": "lowercase.text", "to": "output.text"}
                ]
                """;

        assertDiagnostic(
                flow("lowercase", STEP, connections),
                "FLOW_CONNECTION_TARGET_DUPLICATE",
                "Connection target is already mapped: lowercase.text",
                "connections[1]"
        );
    }

    @Test
    void transitionsCannotUseUndeclaredOutcomes() {
        final String step = STEP.replace(
                "{\"ok\": \"end\"}",
                "{\"ok\": \"end\", \"surprise\": \"end\"}"
        );

        assertDiagnostic(
                flow("lowercase", step, CONNECTIONS),
                "FLOW_OUTCOME_UNKNOWN",
                "Transition uses an undeclared outcome: surprise",
                "steps.lowercase.on.surprise"
        );
    }

    @Test
    void transitionsMustTargetAnExistingStepOrEnd() {
        final String step = STEP.replace("{\"ok\": \"end\"}", "{\"ok\": \"missing\"}");

        assertDiagnostic(
                flow("lowercase", step, CONNECTIONS),
                "FLOW_TRANSITION_TARGET_UNKNOWN",
                "Transition target does not exist: missing",
                "steps.lowercase.on.ok"
        );
    }

    @Test
    void controlCyclesAreRejectedExplicitly() {
        final String step = STEP.replace("{\"ok\": \"end\"}", "{\"ok\": \"lowercase\"}");

        assertDiagnostic(
                flow("lowercase", step, CONNECTIONS),
                "FLOW_CONTROL_CYCLE_UNSUPPORTED",
                "Control cycles are not supported.",
                "steps.lowercase"
        );
    }

    @Test
    void controlFanInFromDifferentStepsIsRejected() {
        final String steps = """
                {"id":"branch","use":"test.branch","config":{},"on":{"left":"left","right":"right"}},
                {"id":"left","use":"test.unit","config":{},"on":{"ok":"join"}},
                {"id":"right","use":"test.unit","config":{},"on":{"ok":"join"}},
                {"id":"join","use":"test.unit","config":{},"on":{"ok":"end"}}
                """;

        assertControlDiagnostic(
                controlFlow("branch", steps),
                "Step cannot have more than one control predecessor: join",
                "steps.right.on.ok"
        );
    }

    @Test
    void mutuallyExclusiveOutcomesFromOneStepMayShareASuccessor() {
        final String steps = """
                {"id":"branch","use":"test.branch","config":{},"on":{"left":"join","right":"join"}},
                {"id":"join","use":"test.unit","config":{},"on":{"ok":"end"}}
                """;

        assertThat(FlowCompiler.compile(controlFlow("branch", steps), controlCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void endAcceptsMultipleIncomingControlTransitions() {
        final String steps = """
                {"id":"branch","use":"test.branch","config":{},"on":{"left":"end","right":"end"}}
                """;

        assertThat(FlowCompiler.compile(controlFlow("branch", steps), controlCatalog()))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void everyAdditionalIncomingControlTransitionHasItsOwnDiagnostic() {
        final String steps = """
                {"id":"root","use":"test.branch","config":{},"on":{"left":"left","right":"fork"}},
                {"id":"fork","use":"test.branch","config":{},"on":{"left":"middle","right":"right"}},
                {"id":"left","use":"test.unit","config":{},"on":{"ok":"join"}},
                {"id":"middle","use":"test.unit","config":{},"on":{"ok":"join"}},
                {"id":"right","use":"test.unit","config":{},"on":{"ok":"join"}},
                {"id":"join","use":"test.unit","config":{},"on":{"ok":"end"}}
                """;

        assertThat(FlowCompiler.compile(controlFlow("root", steps), controlCatalog())).isEqualTo(
                new CompileResult.Rejected(List.of(
                        Diagnostic.atPath(
                                "FLOW_CONTROL_FAN_IN_UNSUPPORTED",
                                "Step cannot have more than one control predecessor: join",
                                "steps.middle.on.ok"
                        ),
                        Diagnostic.atPath(
                                "FLOW_CONTROL_FAN_IN_UNSUPPORTED",
                                "Step cannot have more than one control predecessor: join",
                                "steps.right.on.ok"
                        )
                ))
        );
    }

    @Test
    void undeclaredOutcomeDoesNotCreateACollateralFanInDiagnostic() {
        final String steps = """
                {"id":"branch","use":"test.branch","config":{},"on":{"left":"join","bogus":"join","right":"end"}},
                {"id":"join","use":"test.unit","config":{},"on":{"ok":"end"}}
                """;

        assertThat(FlowCompiler.compile(controlFlow("branch", steps), controlCatalog())).isEqualTo(
                new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "FLOW_OUTCOME_UNKNOWN",
                        "Transition uses an undeclared outcome: bogus",
                        "steps.branch.on.bogus"
                )))
        );
    }

    @Test
    void sourceEndpointMustUseOwnerDotPortSyntax() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("input.text", "invalid")),
                "FLOW_ENDPOINT_INVALID",
                "Endpoint must be owner.port: invalid",
                "connections[0].from"
        );
    }

    @Test
    void sourceEndpointMustNameAPortAfterTheSeparator() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace(
                        "{\"from\": \"lowercase.text\", \"to\": \"output.text\"}",
                        "{\"from\": \"lowercase.\", \"to\": \"output.text\"}"
                )),
                "FLOW_ENDPOINT_INVALID",
                "Endpoint must be owner.port: lowercase.",
                "connections[1].from"
        );
    }

    @Test
    void targetEndpointMustUseOwnerDotPortSyntax() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("lowercase.text\"}", "invalid\"}")),
                "FLOW_ENDPOINT_INVALID",
                "Endpoint must be owner.port: invalid",
                "connections[0].to"
        );
    }

    @Test
    void connectionSourceFlowInputMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("input.text", "input.missing")),
                "FLOW_SOURCE_UNKNOWN",
                "Unknown flow input: missing",
                "connections[0].from"
        );
    }

    @Test
    void connectionSourceStepMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("lowercase.text\", \"to\": \"output", "ghost.text\", \"to\": \"output")),
                "FLOW_SOURCE_UNKNOWN",
                "Unknown source Step: ghost",
                "connections[1].from"
        );
    }

    @Test
    void connectionSourcePortMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("lowercase.text\", \"to\": \"output", "lowercase.missing\", \"to\": \"output")),
                "FLOW_SOURCE_PORT_UNKNOWN",
                "Unknown Step output: lowercase.missing",
                "connections[1].from"
        );
    }

    @Test
    void connectionTargetFlowOutputMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("output.text", "output.missing")),
                "FLOW_TARGET_UNKNOWN",
                "Unknown flow output: missing",
                "connections[1].to"
        );
    }

    @Test
    void connectionTargetStepMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("lowercase.text\"}", "ghost.text\"}")),
                "FLOW_TARGET_UNKNOWN",
                "Unknown target Step: ghost",
                "connections[0].to"
        );
    }

    @Test
    void connectionTargetPortMustExist() {
        assertDiagnostic(
                flow("lowercase", STEP, CONNECTIONS.replace("lowercase.text\"}", "lowercase.missing\"}")),
                "FLOW_TARGET_PORT_UNKNOWN",
                "Unknown Step input: lowercase.missing",
                "connections[0].to"
        );
    }

    @Test
    void everyDeclaredFlowOutputMustBeMapped() {
        final String connections = """
                [{"from": "input.text", "to": "lowercase.text"}]
                """;

        assertDiagnostic(
                flow("lowercase", STEP, connections),
                "FLOW_OUTPUT_UNMAPPED",
                "Flow output is not mapped: text",
                "outputs.text"
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

    private static void assertControlDiagnostic(
            final String source,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(source, controlCatalog())).isEqualTo(
                new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "FLOW_CONTROL_FAN_IN_UNSUPPORTED",
                        message,
                        path
                )))
        );
    }

    private static StepCatalog controlCatalog() {
        final StepDefinition branch = StepDefinition.named("test.branch", "1")
                .outcome("left")
                .outcome("right")
                .run(input -> StepResult.outcome("left"));
        final StepDefinition unit = StepDefinition.named("test.unit", "1")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
        return StepCatalog.of(branch, unit);
    }

    private static String controlFlow(final String entry, final String steps) {
        return """
                {
                  "id":"control-flow",
                  "triggers":[],
                  "entry":"%s",
                  "inputs":{},
                  "outputs":{},
                  "steps":[%s],
                  "connections":[]
                }
                """.formatted(entry, steps);
    }

    private static String flow(final String entry, final String steps, final String connections) {
        return """
                {
                  "id": "diagnostic-flow",
                  "triggers": [],
                  "entry": "%s",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [%s],
                  "connections": %s
                }
                """.formatted(entry, steps, connections);
    }
}
