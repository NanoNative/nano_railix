package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RailixRunnerContractTest {
    private static final String LOWERCASE_FLOW = """
            {
              "id": "runner-contract",
              "triggers": [],
              "entry": "lowercase",
              "inputs": {"text": "string"},
              "outputs": {"text": "string"},
              "steps": [{"id": "lowercase", "use": "text.lowercase", "config": {}, "on": {"ok": "end"}}],
              "connections": [
                {"from": "input.text", "to": "lowercase.text"},
                {"from": "lowercase.text", "to": "output.text"}
              ]
            }
            """;

    @Test
    void runtimeAdmissionRejectionUsesExitCodeTwo() {
        final RailixRunner.Outcome outcome = RailixRunner.run(
                LOWERCASE_FLOW,
                "{\"text\":\"value\",\"extra\":true}"
        );

        assertThat(outcome).isInstanceOfSatisfying(RailixRunner.Rejected.class, rejected -> {
            assertThat(rejected.exitCode()).isEqualTo(2);
            assertThat(RailixJson.write(rejected.payload())).contains(
                    "\"status\":\"run-rejected\"",
                    "\"code\":\"FLOW_INPUT_UNKNOWN\""
            );
        });
    }

    @Test
    void missingProgrammaticInputReturnsAnExplicitInputDiagnostic() {
        assertThat(rejection(null)).isEqualTo(new RejectionObservation(
                2,
                "{\"diagnostics\":[{\"code\":\"INPUT_SOURCE_REQUIRED\",\"column\":0,\"line\":0,"
                        + "\"message\":\"Data source is required.\",\"path\":\"$\"}],"
                        + "\"status\":\"input-rejected\"}"
        ));
    }

    @Test
    void oversizedProgrammaticInputReturnsAnExplicitInputDiagnostic() {
        assertThat(rejection(" ".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1)))
                .isEqualTo(new RejectionObservation(
                        2,
                        "{\"diagnostics\":[{\"code\":\"INPUT_SOURCE_TOO_LARGE\","
                                + "\"column\":0,\"line\":0,\"message\":\"Input source exceeds the "
                                + "1048576-byte limit.\",\"path\":\"$\"}],"
                                + "\"status\":\"input-rejected\"}"
                ));
    }

    @Test
    void unpairedProgrammaticUnicodeReturnsAnExplicitInputDiagnostic() {
        assertThat(rejection("{\"text\":\"\ud800\"}")).isEqualTo(new RejectionObservation(
                2,
                "{\"diagnostics\":[{\"code\":\"INPUT_JSON_INVALID\",\"column\":1,\"line\":1,"
                        + "\"message\":\"Unpaired Unicode surrogate is not allowed in JSON strings.\","
                        + "\"path\":\"$\"}],\"status\":\"input-rejected\"}"
        ));
    }

    @Test
    void unsupportedTriggerUsesCompileRejectionThroughTheRunnerBoundary() {
        final RailixRunner.Rejected rejected = (RailixRunner.Rejected) RailixRunner.run(
                LOWERCASE_FLOW.replace(
                        "\"triggers\": []",
                        "\"triggers\":[{\"id\":\"source\",\"type\":\"custom\",\"config\":{}}]"
                ),
                "{\"text\":\"value\"}"
        );

        assertThat(new RejectionObservation(rejected.exitCode(), RailixJson.write(rejected.payload())))
                .isEqualTo(new RejectionObservation(
                        2,
                        "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_TYPE_UNSUPPORTED\","
                                + "\"column\":0,\"line\":0,\"message\":\"Trigger type is not implemented: custom.\","
                                + "\"path\":\"triggers[0].type\"}],\"status\":\"compile-rejected\"}"
                ));
    }

    @Test
    void stepContractFailureUsesExitCodeThree() {
        final StepDefinition broken = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", RailixValue.number(7)));

        final RailixRunner.Outcome outcome = RailixRunner.run(
                LOWERCASE_FLOW,
                "{\"text\":\"value\"}",
                StepCatalog.of(broken)
        );

        assertThat(outcome).isInstanceOfSatisfying(RailixRunner.Rejected.class, rejected -> {
            assertThat(rejected.exitCode()).isEqualTo(3);
            assertThat(RailixJson.write(rejected.payload())).contains(
                    "\"status\":\"run-failed\"",
                    "\"code\":\"STEP_OUTPUT_TYPE_MISMATCH\""
            );
        });
    }

    @Test
    void interruptedManualEventUsesExitCodeOneHundredThirtyAndACancelledReply()
            throws InterruptedException {
        final AtomicReference<RailixRunner.Outcome> outcome = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            outcome.set(RailixRunner.run(LOWERCASE_FLOW, "{\"text\":\"value\"}"));
        });
        thread.join();

        assertThat(outcome.get()).isEqualTo(new RailixRunner.Rejected(
                130,
                RailixValue.object(java.util.Map.of(
                        "status", RailixValue.string("run-cancelled"),
                        "steps", RailixValue.array(java.util.List.of())
                ))
        ));
    }

    @Test
    void cancelledRunnerReplyPreservesCompletedStepHistory() throws InterruptedException {
        final String flow = """
                {
                  "id":"runner-cancelled-history",
                  "triggers":[],
                  "entry":"first",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"first","use":"first","config":{},"on":{"ok":"second"}},
                    {"id":"second","use":"second","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[{"from":"input.text","to":"output.text"}]
                }
                """;
        final StepDefinition first = StepDefinition.named("first", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    Thread.currentThread().interrupt();
                    return StepResult.outcome("ok");
                });
        final StepDefinition second = StepDefinition.named("second", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
        final AtomicReference<RailixRunner.Outcome> outcome = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> outcome.set(RailixRunner.run(
                flow,
                "{\"text\":\"value\"}",
                StepCatalog.of(first, second)
        )));
        thread.join();
        final RailixRunner.Rejected rejected = (RailixRunner.Rejected) outcome.get();

        assertThat(new RejectionObservation(rejected.exitCode(), RailixJson.write(rejected.payload())))
                .isEqualTo(new RejectionObservation(
                        130,
                        "{\"status\":\"run-cancelled\",\"steps\":[{\"outcome\":\"ok\",\"step\":\"first\"}]}"
                ));
    }

    private static RejectionObservation rejection(final String input) {
        final RailixRunner.Rejected rejected = (RailixRunner.Rejected) RailixRunner.run(
                LOWERCASE_FLOW,
                input
        );
        return new RejectionObservation(rejected.exitCode(), RailixJson.write(rejected.payload()));
    }

    private record RejectionObservation(int exitCode, String payload) {
    }
}
