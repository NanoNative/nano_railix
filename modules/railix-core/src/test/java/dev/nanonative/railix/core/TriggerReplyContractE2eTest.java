package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TriggerReplyContractE2eTest {
    private static final String FLOW = """
            {
              "id": "trigger-reply-contract",
              "triggers": [],
              "entry": "copy",
              "inputs": {"text": "string"},
              "outputs": {"text": "string"},
              "steps": [
                {"id": "copy", "use": "copy", "config": {}, "on": {"ok": "end"}}
              ],
              "connections": [
                {"from": "input.text", "to": "copy.text"},
                {"from": "copy.text", "to": "output.text"}
              ]
            }
            """;
    private static final StepCatalog CATALOG = StepCatalog.of(copyStep());

    @Test
    void triggerlessFlowCompilesRunsAnObjectEventAndReturnsTheCanonicalReply() {
        final CompileResult result = FlowCompiler.compile(FLOW, CATALOG);
        final CompileResult.Compiled compiled = result instanceof CompileResult.Compiled value ? value : null;
        final RunResult reply = compiled == null
                ? null
                : compiled.flow().run(RailixValue.object(Map.of("text", RailixValue.string("Railix"))));

        assertThat(new FlowObservation(
                compiled == null ? "" : compiled.source(),
                reply
        )).isEqualTo(new FlowObservation(
                "{\"connections\":[{\"from\":\"input.text\",\"to\":\"copy.text\"},"
                        + "{\"from\":\"copy.text\",\"to\":\"output.text\"}],\"entry\":\"copy\","
                        + "\"id\":\"trigger-reply-contract\",\"inputs\":{\"text\":\"string\"},"
                        + "\"outputs\":{\"text\":\"string\"},\"steps\":[{\"config\":{},\"id\":\"copy\","
                        + "\"on\":{\"ok\":\"end\"},\"use\":\"copy\"}],\"triggers\":[]}",
                new RunResult.Succeeded(
                        RailixValue.object(Map.of("text", RailixValue.string("Railix"))),
                        List.of(new RunResult.StepExecution("copy", "ok"))
                )
        ));
    }

    @Test
    void compilerExposesOnlyImplementedTriggerTypes() {
        assertThat(FlowCompiler.supportedTriggers())
                .containsExactly("cli", "startup", "http", "socket", "scheduled");
    }

    @Test
    void missingTriggersArrayIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                FLOW.replace("  \"triggers\": [],\n", ""),
                "FLOW_TRIGGERS_ARRAY_REQUIRED",
                "triggers must be an array.",
                "triggers"
        );
    }

    @Test
    void objectTriggersValueIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                triggers("{}"),
                "FLOW_TRIGGERS_ARRAY_REQUIRED",
                "triggers must be an array.",
                "triggers"
        );
    }

    @Test
    void nonObjectTriggerEntryIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                triggers("[7]"),
                "FLOW_TRIGGER_OBJECT_REQUIRED",
                "Trigger must be an object.",
                "triggers[0]"
        );
    }

    @Test
    void triggerWithoutIdIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"type\":\"cli\",\"config\":{}}"),
                "FLOW_TRIGGER_ID_REQUIRED",
                "triggers[0].id must be a non-blank string.",
                "triggers[0].id"
        );
    }

    @Test
    void blankTriggerIdIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"\",\"type\":\"cli\",\"config\":{}}"),
                "FLOW_TRIGGER_ID_REQUIRED",
                "triggers[0].id must be a non-blank string.",
                "triggers[0].id"
        );
    }

    @Test
    void numericTriggerIdIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":7,\"type\":\"cli\",\"config\":{}}"),
                "FLOW_TRIGGER_ID_REQUIRED",
                "triggers[0].id must be a non-blank string.",
                "triggers[0].id"
        );
    }

    @Test
    void triggerWithoutTypeIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"config\":{}}"),
                "FLOW_TRIGGER_TYPE_REQUIRED",
                "triggers[0].type must be a non-blank string.",
                "triggers[0].type"
        );
    }

    @Test
    void blankTriggerTypeIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"type\":\"\",\"config\":{}}"),
                "FLOW_TRIGGER_TYPE_REQUIRED",
                "triggers[0].type must be a non-blank string.",
                "triggers[0].type"
        );
    }

    @Test
    void numericTriggerTypeIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"type\":7,\"config\":{}}"),
                "FLOW_TRIGGER_TYPE_REQUIRED",
                "triggers[0].type must be a non-blank string.",
                "triggers[0].type"
        );
    }

    @Test
    void triggerWithoutConfigIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"type\":\"cli\"}"),
                "FLOW_TRIGGER_CONFIG_OBJECT_REQUIRED",
                "triggers[0].config must be an object.",
                "triggers[0].config"
        );
    }

    @Test
    void arrayTriggerConfigIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"type\":\"cli\",\"config\":[]}"),
                "FLOW_TRIGGER_CONFIG_OBJECT_REQUIRED",
                "triggers[0].config must be an object.",
                "triggers[0].config"
        );
    }

    @Test
    void unknownTriggerFieldIsRejectedAtBuildIngress() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"command\",\"type\":\"cli\",\"config\":{},\"interval\":1000}"),
                "FLOW_TRIGGER_FIELD_UNKNOWN",
                "Unknown trigger field: interval",
                "triggers[0].interval"
        );
    }

    @Test
    void duplicateTriggerIdIsRejectedAtBuildIngress() {
        final String source = triggers("""
                [
                  {"id":"command","type":"cli","config":{}},
                  {"id":"command","type":"http","config":{}}
                ]
                """);

        assertThat(FlowCompiler.compile(source, CATALOG)).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_TRIGGER_ID_DUPLICATE",
                        "Trigger id is already declared: command.",
                        "triggers[1].id"
                )
        )));
    }

    @Test
    void triggerFieldOrderDoesNotChangeItsDiagnostic() {
        assertThat(List.of(
                FlowCompiler.compile(
                        declaration("{\"id\":\"command\",\"type\":\"custom\",\"config\":{}}"),
                        CATALOG
                ),
                FlowCompiler.compile(
                        declaration("{\"config\":{},\"type\":\"custom\",\"id\":\"command\"}"),
                        CATALOG
                )
        )).containsExactly(
                unsupportedTrigger("custom"),
                unsupportedTrigger("custom")
        );
    }

    @Test
    void singularTriggerFieldIsRejectedWithoutCompatibilityFallback() {
        assertThat(FlowCompiler.compile(
                FLOW.replace("\"triggers\": []", "\"trigger\":{\"type\":\"manual\"}"),
                CATALOG
        )).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_FIELD_UNKNOWN",
                        "Unknown flow field: trigger",
                        "trigger"
                ),
                Diagnostic.atPath(
                        "FLOW_TRIGGERS_ARRAY_REQUIRED",
                        "triggers must be an array.",
                        "triggers"
                )
        )));
    }

    @Test
    void incompleteHttpTriggerIsRejectedWithoutDefaults() {
        assertTriggerDiagnostic(
                declaration("{\"id\":\"source\",\"type\":\"http\",\"config\":{}}"),
                "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                "HTTP trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void arbitraryTriggerIsRejectedWithoutARegistryFallback() {
        assertUnsupportedTrigger("custom");
    }

    @Test
    void interruptionBeforeEventAdmissionReturnsCancelledWithoutRunningAStep() throws InterruptedException {
        final AtomicInteger runs = new AtomicInteger();
        final RunResult reply = onInterruptedThread(compiled(FLOW, copyStep(runs)));

        assertThat(new CancellationObservation(reply, runs.get())).isEqualTo(new CancellationObservation(
                new RunResult.Cancelled(List.of()),
                0
        ));
    }

    @Test
    void interruptionAfterACompletedStepCancelsBeforeTheNextStep() throws InterruptedException {
        final AtomicInteger secondRuns = new AtomicInteger();
        final String source = """
                {
                  "id": "cancel-between-steps",
                  "triggers": [],
                  "entry": "first",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id":"first","use":"interrupt","config":{},"on":{"ok":"second"}},
                    {"id":"second","use":"second","config":{},"on":{"ok":"end"}}
                  ],
                  "connections": [{"from":"input.text","to":"output.text"}]
                }
                """;
        final StepDefinition interrupt = StepDefinition.named("interrupt", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    Thread.currentThread().interrupt();
                    return StepResult.outcome("ok");
                });
        final StepDefinition second = StepDefinition.named("second", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    secondRuns.incrementAndGet();
                    return StepResult.outcome("ok");
                });
        final RunResult reply = onThread(compiled(source, interrupt, second), false);

        assertThat(new CancellationObservation(reply, secondRuns.get())).isEqualTo(new CancellationObservation(
                new RunResult.Cancelled(List.of(new RunResult.StepExecution("first", "ok"))),
                0
        ));
    }

    @Test
    void interruptionAfterTheFinalStepReturnsCancelledInsteadOfFlowOutputs() throws InterruptedException {
        final String source = FLOW.replace("\"use\": \"copy\"", "\"use\": \"interrupt-copy\"");
        final StepDefinition interrupt = StepDefinition.named("interrupt-copy", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    Thread.currentThread().interrupt();
                    return StepResult.outcome("ok").output("text", input.value("text"));
                });
        final RunResult reply = onThread(compiled(source, interrupt), false);

        assertThat(reply).isEqualTo(new RunResult.Cancelled(List.of(
                new RunResult.StepExecution("copy", "ok")
        )));
    }

    @Test
    void interruptionStopsABlockingStepAndReturnsCancelledOnlyAfterTermination() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final StepDefinition blocking = StepDefinition.named("blocking", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    started.countDown();
                    release.await();
                    return StepResult.outcome("ok");
                });
        final String source = """
                {
                  "id":"blocking-cancellation",
                  "triggers":[],
                  "entry":"blocking",
                  "inputs":{},
                  "outputs":{},
                  "steps":[{"id":"blocking","use":"blocking","config":{},"on":{"ok":"end"}}],
                  "connections":[]
                }
                """;
        final var flow = compiled(source, blocking);
        final AtomicReference<RunResult> reply = new AtomicReference<>();
        final AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        final Thread thread = Thread.ofVirtual().start(() -> {
            reply.set(flow.run(RailixValue.object(Map.of())));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        final boolean stepStarted;
        final boolean threadAlive;
        try {
            stepStarted = started.await(1, TimeUnit.SECONDS);
            thread.interrupt();
            thread.join(1_000);
            threadAlive = thread.isAlive();
        } finally {
            release.countDown();
            thread.interrupt();
            thread.join(1_000);
        }

        assertThat(new BlockingCancellationObservation(reply.get(), stepStarted, threadAlive, interrupted.get()))
                .isEqualTo(new BlockingCancellationObservation(
                        new RunResult.Cancelled(List.of()),
                        true,
                        false,
                        true
                ));
    }

    @Test
    void interruptedStepPreservesEarlierCompletedStepHistory() throws InterruptedException {
        final String source = """
                {
                  "id":"interrupted-after-completion",
                  "triggers":[],
                  "entry":"first",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"first","use":"first","config":{},"on":{"ok":"interrupted"}},
                    {"id":"interrupted","use":"interrupted","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[{"from":"input.text","to":"output.text"}]
                }
                """;
        final StepDefinition first = StepDefinition.named("first", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
        final StepDefinition interrupted = StepDefinition.named("interrupted", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    throw new InterruptedException("cancelled");
                });
        final RunResult reply = onThread(compiled(source, first, interrupted), false);

        assertThat(reply).isEqualTo(new RunResult.Cancelled(List.of(
                new RunResult.StepExecution("first", "ok")
        )));
    }

    @Test
    void stepContractFailureWinsWhenTheStepAlsoInterrupts() throws InterruptedException {
        final String source = FLOW.replace("\"use\": \"copy\"", "\"use\": \"broken\"");
        final StepDefinition broken = StepDefinition.named("broken", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    Thread.currentThread().interrupt();
                    return StepResult.outcome("ok");
                });
        final RunResult reply = onThread(compiled(source, broken), false);

        assertThat(reply).isEqualTo(new RunResult.Failed(
                new dev.nanonative.railix.core.runtime.RunFailure(
                        "STEP_OUTPUT_REQUIRED",
                        "Step did not produce required output: text",
                        "copy"
                ),
                List.of()
        ));
    }

    @Test
    void runtimeFaultWinsWhenAStepInterruptsThenThrows() throws InterruptedException {
        final StepDefinition broken = StepDefinition.named("broken", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("broken");
                });
        final String source = """
                {
                  "id":"fault-precedence",
                  "triggers":[],
                  "entry":"broken",
                  "inputs":{},
                  "outputs":{},
                  "steps":[{"id":"broken","use":"broken","config":{},"on":{"ok":"end"}}],
                  "connections":[]
                }
                """;
        final AtomicReference<RunResult> reply = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() ->
                reply.set(compiled(source, broken).run(RailixValue.object(Map.of())))
        );
        thread.join();

        assertThat(reply.get()).isEqualTo(new RunResult.Failed(
                new dev.nanonative.railix.core.runtime.RunFailure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "broken"
                ),
                List.of()
        ));
    }

    private static void assertUnsupportedTrigger(final String type) {
        assertThat(FlowCompiler.compile(
                declaration("{\"id\":\"source\",\"type\":\"" + type + "\",\"config\":{}}"),
                CATALOG
        )).isEqualTo(unsupportedTrigger(type));
    }

    private static CompileResult.Rejected unsupportedTrigger(final String type) {
        return new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_TRIGGER_TYPE_UNSUPPORTED",
                "Trigger type is not implemented: " + type + ".",
                "triggers[0].type"
        )));
    }

    private static void assertTriggerDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(source, CATALOG)).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(code, message, path)
        )));
    }

    private static String declaration(final String trigger) {
        return triggers("[" + trigger + "]");
    }

    private static String triggers(final String triggers) {
        return FLOW.replace("[]", triggers);
    }

    private static StepDefinition copyStep() {
        return copyStep(new AtomicInteger());
    }

    private static StepDefinition copyStep(final AtomicInteger runs) {
        return StepDefinition.named("copy", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    runs.incrementAndGet();
                    return StepResult.outcome("ok").output("text", input.value("text"));
                });
    }

    private static dev.nanonative.railix.core.flow.CompiledFlow compiled(
            final String source,
            final StepDefinition... steps
    ) {
        return ((CompileResult.Compiled) FlowCompiler.compile(source, StepCatalog.of(steps))).flow();
    }

    private static RunResult onInterruptedThread(
            final dev.nanonative.railix.core.flow.CompiledFlow flow
    ) throws InterruptedException {
        return onThread(flow, true);
    }

    private static RunResult onThread(
            final dev.nanonative.railix.core.flow.CompiledFlow flow,
            final boolean interruptBeforeRun
    ) throws InterruptedException {
        final AtomicReference<RunResult> reply = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> {
            if (interruptBeforeRun) {
                Thread.currentThread().interrupt();
            }
            reply.set(flow.run(RailixValue.object(Map.of("text", RailixValue.string("Railix")))));
        });
        thread.join();
        return reply.get();
    }

    private record FlowObservation(String canonicalSource, RunResult reply) {
    }

    private record CancellationObservation(RunResult reply, int runs) {
    }

    private record BlockingCancellationObservation(
            RunResult reply,
            boolean stepStarted,
            boolean threadAlive,
            boolean interrupted
    ) {
    }
}
