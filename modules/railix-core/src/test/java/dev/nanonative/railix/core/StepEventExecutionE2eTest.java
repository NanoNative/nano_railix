package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StepEventExecutionE2eTest {
    @Test
    void declaredStepEventBypassesUpstreamAndFinishesTheRealFlow() {
        final AtomicInteger sourceRuns = new AtomicInteger();
        final CompiledFlow flow = compiled(catalog(sourceRuns, false));

        assertThat(flow.runStepEvent("middle", event("HELLO"))).isEqualTo(new RunResult.Succeeded(
                RailixValue.object(Map.of("text", RailixValue.string("hello!"))),
                List.of(
                        new RunResult.StepExecution("middle", "ok"),
                        new RunResult.StepExecution("sink", "ok")
                )
        ));
        assertThat(sourceRuns.get()).isZero();
    }

    @Test
    void undeclaredStepEventIsRejectedBeforeAnyStepRuns() {
        final AtomicInteger sourceRuns = new AtomicInteger();
        final CompiledFlow flow = compiled(catalog(sourceRuns, false));

        assertThat(flow.runStepEvent("source", event("HELLO"))).isEqualTo(new RunResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_STEP_EVENT_NOT_DECLARED",
                        "Step event is not declared: source.",
                        "step"
                )
        )));
        assertThat(sourceRuns.get()).isZero();
    }

    @Test
    void missingStepEventIdIsRejectedWithoutThrowing() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent(null, event("HELLO"))).isEqualTo(new RunResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_STEP_EVENT_REQUIRED",
                        "Step event id must be a non-blank string.",
                        "step"
                )
        )));
    }

    @Test
    void blankStepEventIdIsRejectedWithoutGuessing() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent(" ", event("HELLO"))).isEqualTo(new RunResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_STEP_EVENT_REQUIRED",
                        "Step event id must be a non-blank string.",
                        "step"
                )
        )));
    }

    @Test
    void missingStepEventObjectIsRejectedBeforeExecution() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent("middle", null)).isEqualTo(new RunResult.Rejected(List.of(
                Diagnostic.atPath(
                        "STEP_INPUT_OBJECT_REQUIRED",
                        "Step inputs must be an object.",
                        "inputs"
                )
        )));
    }

    @Test
    void missingRequiredStepInputIsRejectedBeforeExecution() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent("middle", RailixValue.object(Map.of())))
                .isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                        "STEP_INPUT_REQUIRED",
                        "Required Step input is missing: text",
                        "inputs.text"
                ))));
    }

    @Test
    void wrongStepInputShapeIsRejectedBeforeExecution() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent("middle", RailixValue.object(Map.of(
                "text", RailixValue.number(7)
        )))).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_INPUT_TYPE_MISMATCH",
                "Step input text requires STRING but received NUMBER.",
                "inputs.text"
        ))));
    }

    @Test
    void unknownStepInputIsRejectedBeforeExecution() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));

        assertThat(flow.runStepEvent("middle", RailixValue.object(Map.of(
                "text", RailixValue.string("HELLO"),
                "extra", RailixValue.bool(true)
        )))).isEqualTo(new RunResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_INPUT_UNKNOWN",
                "Unknown Step input: extra",
                "inputs.extra"
        ))));
    }

    @Test
    void selectedStepImplementationFaultUsesTheCanonicalFailure() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), true));

        assertThat(flow.runStepEvent("middle", event("HELLO"))).isEqualTo(new RunResult.Failed(
                new RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "middle"
                ),
                List.of()
        ));
    }

    @Test
    void interruptedStepEventUsesTheCanonicalCancellation() {
        final CompiledFlow flow = compiled(catalog(new AtomicInteger(), false));
        Thread.currentThread().interrupt();
        try {
            assertThat(flow.runStepEvent("middle", event("HELLO")))
                    .isEqualTo(new RunResult.Cancelled(List.of()));
        } finally {
            Thread.interrupted();
        }
    }

    private static RailixValue.ObjectValue event(final String text) {
        return RailixValue.object(Map.of("text", RailixValue.string(text)));
    }

    private static StepCatalog catalog(final AtomicInteger sourceRuns, final boolean middleFails) {
        return StepCatalog.of(
                StepDefinition.named("source", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(input -> {
                            sourceRuns.incrementAndGet();
                            return StepResult.outcome("ok").output("text", input.value("text"));
                        }),
                StepDefinition.named("middle", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(input -> {
                            if (middleFails) {
                                throw new IllegalStateException("fixture fault");
                            }
                            final String value = ((RailixValue.StringValue) input.value("text")).value();
                            return StepResult.outcome("ok").output(
                                    "text",
                                    RailixValue.string(value.toLowerCase(java.util.Locale.ROOT))
                            );
                        }),
                StepDefinition.named("sink", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(input -> {
                            final String value = ((RailixValue.StringValue) input.value("text")).value();
                            return StepResult.outcome("ok").output("text", RailixValue.string(value + "!"));
                        })
        );
    }

    private static CompiledFlow compiled(final StepCatalog catalog) {
        return ((CompileResult.Compiled) FlowCompiler.compile("""
                {
                  "id":"step-events",
                  "triggers":[
                    {"id":"middle-events","type":"http","config":{"port":8080,"step":"middle"}}
                  ],
                  "entry":"source",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"source","use":"source","config":{},"on":{"ok":"middle"}},
                    {"id":"middle","use":"middle","config":{},"on":{"ok":"sink"}},
                    {"id":"sink","use":"sink","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"source.text"},
                    {"from":"source.text","to":"middle.text"},
                    {"from":"middle.text","to":"sink.text"},
                    {"from":"sink.text","to":"output.text"}
                  ]
                }
                """, catalog)).flow();
    }
}
