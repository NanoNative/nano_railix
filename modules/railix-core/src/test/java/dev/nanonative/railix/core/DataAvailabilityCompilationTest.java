package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataAvailabilityCompilationTest {
    @Test
    void aStepCannotConsumeDataFromAProducerThatExecutesLater() {
        final String flow = """
                {
                  "id": "future-data",
                  "triggers": [],
                  "entry": "consumer",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "consumer", "use": "worker", "config": {}, "on": {"ok": "producer"}},
                    {"id": "producer", "use": "worker", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "input.text", "to": "producer.text"},
                    {"from": "producer.text", "to": "consumer.text"},
                    {"from": "producer.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(worker()));

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_DATA_SOURCE_UNAVAILABLE",
                "Step output producer.text is not available whenever consumer executes.",
                "connections[1]"
        ))));
    }

    @Test
    void aStepCannotConsumeDataProducedOnlyOnAnotherOutcomePath() {
        final String flow = """
                {
                  "id": "branch-data",
                  "triggers": [],
                  "entry": "branch",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "branch", "use": "branch", "config": {}, "on": {"left": "producer", "right": "consumer"}},
                    {"id": "producer", "use": "worker", "config": {}, "on": {"ok": "end"}},
                    {"id": "consumer", "use": "worker", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "input.text", "to": "branch.text"},
                    {"from": "branch.text", "to": "producer.text"},
                    {"from": "producer.text", "to": "consumer.text"},
                    {"from": "branch.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(branch(), worker()));

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_DATA_SOURCE_UNAVAILABLE",
                "Step output producer.text is not available whenever consumer executes.",
                "connections[2]"
        ))));
    }

    @Test
    void aStepCannotFeedItsOwnInputFromItsOutput() {
        final String flow = """
                {
                  "id": "self-data",
                  "triggers": [],
                  "entry": "worker",
                  "inputs": {},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "worker", "use": "worker", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "worker.text", "to": "worker.text"},
                    {"from": "worker.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(worker()));

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_DATA_SOURCE_UNAVAILABLE",
                "Step output worker.text is not available whenever worker executes.",
                "connections[0]"
        ))));
    }

    @Test
    void aFlowOutputMustExistOnEveryPathToEnd() {
        final String flow = """
                {
                  "id": "branch-output",
                  "triggers": [],
                  "entry": "branch",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "branch", "use": "branch", "config": {}, "on": {"left": "producer", "right": "end"}},
                    {"id": "producer", "use": "worker", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "input.text", "to": "branch.text"},
                    {"from": "branch.text", "to": "producer.text"},
                    {"from": "producer.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(branch(), worker()));

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_DATA_SOURCE_UNAVAILABLE",
                "Step output producer.text is not available whenever flow completion executes.",
                "connections[2]"
        ))));
    }

    @Test
    void unreachableFanInReportsBothGraphErrorsWithoutFalseDataDiagnostic() {
        final String flow = """
                {
                  "id": "unreachable-predecessor",
                  "triggers": [],
                  "entry": "producer",
                  "inputs": {},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "producer", "use": "source", "config": {}, "on": {"ok": "consumer"}},
                    {"id": "unreachable", "use": "source", "config": {}, "on": {"ok": "consumer"}},
                    {"id": "consumer", "use": "worker", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "producer.text", "to": "consumer.text"},
                    {"from": "consumer.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(source(), worker()));

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_CONTROL_FAN_IN_UNSUPPORTED",
                        "Step cannot have more than one control predecessor: consumer",
                        "steps.unreachable.on.ok"
                ),
                Diagnostic.atPath(
                        "FLOW_STEP_UNREACHABLE",
                        "Step is unreachable from entry: unreachable",
                        "steps.unreachable"
                )
        )));
    }

    private static StepDefinition worker() {
        return StepDefinition.named("worker", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", input.value("text")));
    }

    private static StepDefinition branch() {
        return StepDefinition.named("branch", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("left")
                .outcome("right")
                .run(input -> StepResult.outcome("left").output("text", RailixValue.string("unused")));
    }

    private static StepDefinition source() {
        return StepDefinition.named("source", "1.0.0")
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", RailixValue.string("value")));
    }
}
