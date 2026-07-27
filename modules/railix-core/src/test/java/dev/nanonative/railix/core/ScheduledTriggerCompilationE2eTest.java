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

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTriggerCompilationE2eTest {
    private static final StepCatalog CATALOG = StepCatalog.of(
            StepDefinition.named("noop", "1.0.0")
                    .outcome("ok")
                    .run(input -> StepResult.outcome("ok"))
    );

    @Test
    void scheduledTriggerCompilesAndRemainsInTheExecutablePlan() {
        final CompileResult.Compiled compiled = compiled(schedule(
                "{\"intervalMillis\":60000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"
        ));

        assertThat(compiled.flow().triggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.id()).isEqualTo("refresh");
            assertThat(trigger.type()).isEqualTo("scheduled");
            assertThat(number(trigger, "intervalMillis")).isEqualTo(60000);
            assertThat(number(trigger, "initialDelayMillis")).isZero();
            assertThat(number(trigger, "maxConcurrentRuns")).isEqualTo(1);
        });
    }

    @Test
    void multipleScheduledTriggersPreserveAuthoredOrder() {
        final CompileResult.Compiled compiled = compiled("""
                [
                  {"id":"fast","type":"scheduled","config":{
                    "intervalMillis":1000,"initialDelayMillis":0,"maxConcurrentRuns":1
                  }},
                  {"id":"slow","type":"scheduled","config":{
                    "intervalMillis":60000,"initialDelayMillis":5000,"maxConcurrentRuns":4
                  }}
                ]
                """);

        assertThat(compiled.flow().triggers()).extracting(CompiledFlow.Trigger::id)
                .containsExactly("fast", "slow");
    }

    @Test
    void scheduledIntervalIsRequired() {
        assertDiagnostic(
                schedule("{\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalRejectsNull() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":null,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalRejectsText() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":\"1000\",\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalRejectsFractions() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":0.5,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalRejectsNumbersBeyondLongRange() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":9223372036854775808,"
                        + "\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_REQUIRED",
                "Scheduled trigger config field intervalMillis must be an integer.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalCannotBeZero() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":0,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_OUT_OF_RANGE",
                "Scheduled trigger intervalMillis must be greater than zero.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledIntervalCannotBeNegative() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":-1,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INTERVAL_OUT_OF_RANGE",
                "Scheduled trigger intervalMillis must be greater than zero.",
                "triggers[0].config.intervalMillis"
        );
    }

    @Test
    void scheduledInitialDelayIsRequired() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledInitialDelayRejectsNull() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":null,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledInitialDelayRejectsText() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":\"0\",\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledInitialDelayRejectsFractions() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0.5,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledInitialDelayRejectsNumbersBeyondLongRange() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":9223372036854775808,"
                        + "\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_REQUIRED",
                "Scheduled trigger config field initialDelayMillis must be an integer.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledInitialDelayCannotBeNegative() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":-1,\"maxConcurrentRuns\":1}"),
                "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_OUT_OF_RANGE",
                "Scheduled trigger initialDelayMillis cannot be negative.",
                "triggers[0].config.initialDelayMillis"
        );
    }

    @Test
    void scheduledConcurrencyBoundIsRequired() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_REQUIRED",
                "Scheduled trigger config field maxConcurrentRuns must be an integer.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledConcurrencyBoundRejectsNull() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":null}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_REQUIRED",
                "Scheduled trigger config field maxConcurrentRuns must be an integer.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledConcurrencyBoundRejectsText() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":\"1\"}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_REQUIRED",
                "Scheduled trigger config field maxConcurrentRuns must be an integer.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledConcurrencyBoundRejectsFractions() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1.5}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_REQUIRED",
                "Scheduled trigger config field maxConcurrentRuns must be an integer.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledConcurrencyBoundCannotBeZero() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":0}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_OUT_OF_RANGE",
                "Scheduled trigger maxConcurrentRuns must be between 1 and 256.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledConcurrencyBoundCannotExceedTwoHundredFiftySix() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":257}"),
                "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_OUT_OF_RANGE",
                "Scheduled trigger maxConcurrentRuns must be between 1 and 256.",
                "triggers[0].config.maxConcurrentRuns"
        );
    }

    @Test
    void scheduledTriggerAcceptsOneConcurrentRun() {
        assertCompiled(schedule(
                "{\"intervalMillis\":1,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"
        ));
    }

    @Test
    void scheduledTriggerAcceptsBoundedOverlap() {
        assertCompiled(schedule(
                "{\"intervalMillis\":1,\"initialDelayMillis\":0,\"maxConcurrentRuns\":256}"
        ));
    }

    @Test
    void scheduledTriggerRejectsFlowInputsInsteadOfInventingAnEvent() {
        assertSourceDiagnostic(
                flow(
                        schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,\"maxConcurrentRuns\":1}"),
                        "{\"name\":\"string\"}"
                ),
                "FLOW_TRIGGER_SCHEDULED_INPUTS_UNSUPPORTED",
                "Scheduled trigger requires an inputless flow.",
                "triggers[0].config"
        );
    }

    @Test
    void scheduledTriggerRejectsUnknownConfiguration() {
        assertDiagnostic(
                schedule("{\"intervalMillis\":1000,\"initialDelayMillis\":0,"
                        + "\"maxConcurrentRuns\":1,\"timezone\":\"UTC\"}"),
                "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                "Unknown scheduled trigger config field: timezone",
                "triggers[0].config.timezone"
        );
    }

    @Test
    void scheduledDiagnosticOrderDoesNotDependOnFieldOrder() {
        final CompileResult first = FlowCompiler.compile(flow(
                """
                [{"id":"refresh","type":"scheduled","config":{
                  "zeta":true,"intervalMillis":1000,"initialDelayMillis":0,
                  "maxConcurrentRuns":1,"alpha":true
                }}]
                """,
                "{}"
        ), CATALOG);
        final CompileResult second = FlowCompiler.compile(flow(
                """
                [{"id":"refresh","type":"scheduled","config":{
                  "alpha":true,"maxConcurrentRuns":1,"initialDelayMillis":0,
                  "intervalMillis":1000,"zeta":true
                }}]
                """,
                "{}"
        ), CATALOG);
        final CompileResult.Rejected expected = new CompileResult.Rejected(List.of(
                Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                        "Unknown scheduled trigger config field: alpha",
                        "triggers[0].config.alpha"
                ),
                Diagnostic.atPath(
                        "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                        "Unknown scheduled trigger config field: zeta",
                        "triggers[0].config.zeta"
                )
        ));

        assertThat(List.of(first, second)).containsExactly(expected, expected);
    }

    private static long number(final CompiledFlow.Trigger trigger, final String field) {
        return ((RailixValue.NumberValue) trigger.config().values().get(field)).value().longValueExact();
    }

    private static String schedule(final String config) {
        return "[{\"id\":\"refresh\",\"type\":\"scheduled\",\"config\":" + config + "}]";
    }

    private static CompileResult.Compiled compiled(final String triggers) {
        return (CompileResult.Compiled) FlowCompiler.compile(flow(triggers, "{}"), CATALOG);
    }

    private static void assertCompiled(final String triggers) {
        assertThat(FlowCompiler.compile(flow(triggers, "{}"), CATALOG))
                .isInstanceOf(CompileResult.Compiled.class);
    }

    private static void assertDiagnostic(
            final String triggers,
            final String code,
            final String message,
            final String path
    ) {
        assertSourceDiagnostic(flow(triggers, "{}"), code, message, path);
    }

    private static void assertSourceDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(source, CATALOG))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path))));
    }

    private static String flow(final String triggers, final String inputs) {
        return """
                {
                  "id":"scheduled-flow",
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
