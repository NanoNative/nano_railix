package dev.nanonative.railix.core;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SocketTriggerCompilationE2eTest {
    private static final StepCatalog CATALOG = StepCatalog.of(
            StepDefinition.named("noop", "1.0.0")
                    .outcome("ok")
                    .run(input -> StepResult.outcome("ok"))
    );

    @Test
    void socketTriggerCompilesAndRemainsInTheExecutablePlan() {
        final CompileResult.Compiled compiled = compiled(socket(
                "{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":32}"
        ));

        assertThat(compiled.flow().triggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.id()).isEqualTo("events");
            assertThat(trigger.type()).isEqualTo("socket");
            assertThat(integer(trigger, "port")).isEqualTo(17000);
            assertThat(integer(trigger, "timeoutMillis")).isEqualTo(30000);
            assertThat(integer(trigger, "maxConnections")).isEqualTo(32);
        });
    }

    @Test
    void socketTriggerCanSupplyDeclaredFlowInputs() {
        assertThat(FlowCompiler.compile(flow(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "{\"name\":\"string\"}"
        ), CATALOG)).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void socketPortIsRequired() {
        assertDiagnostic(
                socket("{\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_REQUIRED",
                "Socket trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketPortRejectsNull() {
        assertDiagnostic(
                socket("{\"port\":null,\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_REQUIRED",
                "Socket trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketPortRejectsText() {
        assertDiagnostic(
                socket("{\"port\":\"17000\",\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_REQUIRED",
                "Socket trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketPortRejectsFractions() {
        assertDiagnostic(
                socket("{\"port\":17000.5,\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_REQUIRED",
                "Socket trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketPortCannotBeZero() {
        assertDiagnostic(
                socket("{\"port\":0,\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_OUT_OF_RANGE",
                "Socket trigger port must be between 1 and 65535.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketPortCannotExceedTcpRange() {
        assertDiagnostic(
                socket("{\"port\":65536,\"timeoutMillis\":30000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_PORT_OUT_OF_RANGE",
                "Socket trigger port must be between 1 and 65535.",
                "triggers[0].config.port"
        );
    }

    @Test
    void socketTimeoutIsRequired() {
        assertDiagnostic(
                socket("{\"port\":17000,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_REQUIRED",
                "Socket trigger config field timeoutMillis must be an integer.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketTimeoutRejectsNull() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":null,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_REQUIRED",
                "Socket trigger config field timeoutMillis must be an integer.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketTimeoutRejectsText() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":\"30000\",\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_REQUIRED",
                "Socket trigger config field timeoutMillis must be an integer.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketTimeoutRejectsFractions() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":0.5,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_REQUIRED",
                "Socket trigger config field timeoutMillis must be an integer.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketTimeoutCannotBeZero() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":0,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_OUT_OF_RANGE",
                "Socket trigger timeoutMillis must be between 1 and 300000.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketTimeoutCannotExceedFiveMinutes() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":300001,\"maxConnections\":32}"),
                "FLOW_TRIGGER_SOCKET_TIMEOUT_OUT_OF_RANGE",
                "Socket trigger timeoutMillis must be between 1 and 300000.",
                "triggers[0].config.timeoutMillis"
        );
    }

    @Test
    void socketConnectionBoundIsRequired() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_REQUIRED",
                "Socket trigger config field maxConnections must be an integer.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketConnectionBoundRejectsNull() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":null}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_REQUIRED",
                "Socket trigger config field maxConnections must be an integer.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketConnectionBoundRejectsText() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":\"32\"}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_REQUIRED",
                "Socket trigger config field maxConnections must be an integer.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketConnectionBoundRejectsFractions() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":1.5}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_REQUIRED",
                "Socket trigger config field maxConnections must be an integer.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketConnectionBoundCannotBeZero() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":0}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_OUT_OF_RANGE",
                "Socket trigger maxConnections must be between 1 and 256.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketConnectionBoundCannotExceedTwoHundredFiftySix() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":257}"),
                "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_OUT_OF_RANGE",
                "Socket trigger maxConnections must be between 1 and 256.",
                "triggers[0].config.maxConnections"
        );
    }

    @Test
    void socketAcceptsItsMinimumBounds() {
        assertCompiled(socket("{\"port\":1,\"timeoutMillis\":1,\"maxConnections\":1}"));
    }

    @Test
    void socketAcceptsItsMaximumBounds() {
        assertCompiled(socket("{\"port\":65535,\"timeoutMillis\":300000,\"maxConnections\":256}"));
    }

    @Test
    void socketRejectsUnknownConfiguration() {
        assertDiagnostic(
                socket("{\"port\":17000,\"timeoutMillis\":30000,\"maxConnections\":32,\"retry\":true}"),
                "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                "Unknown socket trigger config field: retry",
                "triggers[0].config.retry"
        );
    }

    @Test
    void aFlowCannotDeclareTwoSocketListeners() {
        assertDiagnostic(
                """
                [
                  {"id":"first","type":"socket","config":{
                    "port":17000,"timeoutMillis":30000,"maxConnections":32
                  }},
                  {"id":"second","type":"socket","config":{
                    "port":17001,"timeoutMillis":30000,"maxConnections":32
                  }}
                ]
                """,
                "FLOW_TRIGGER_SOCKET_DUPLICATE",
                "A flow can declare only one socket trigger.",
                "triggers[1].type"
        );
    }

    @Test
    void socketPortCannotConflictWithHttp() {
        assertDiagnostic(
                """
                [
                  {"id":"web","type":"http","config":{"port":17000,"path":"/events"}},
                  {"id":"events","type":"socket","config":{
                    "port":17000,"timeoutMillis":30000,"maxConnections":32
                  }}
                ]
                """,
                "FLOW_TRIGGER_NETWORK_PORT_CONFLICT",
                "Socket trigger port conflicts with the HTTP listener port: 17000.",
                "triggers[1].config.port"
        );
    }

    @Test
    void httpPortCannotConflictWithAnEarlierSocket() {
        assertDiagnostic(
                """
                [
                  {"id":"events","type":"socket","config":{
                    "port":17000,"timeoutMillis":30000,"maxConnections":32
                  }},
                  {"id":"web","type":"http","config":{"port":17000,"path":"/events"}}
                ]
                """,
                "FLOW_TRIGGER_NETWORK_PORT_CONFLICT",
                "HTTP trigger port conflicts with the socket listener port: 17000.",
                "triggers[1].config.port"
        );
    }

    @Test
    void socketAndHttpCanUseDifferentPorts() {
        assertCompiled("""
                [
                  {"id":"events","type":"socket","config":{
                    "port":17000,"timeoutMillis":30000,"maxConnections":32
                  }},
                  {"id":"web","type":"http","config":{"port":17001,"path":"/events"}}
                ]
                """);
    }

    private static int integer(final dev.nanonative.railix.core.flow.CompiledFlow.Trigger trigger,
                               final String field) {
        return ((RailixValue.NumberValue) trigger.config().values().get(field)).value().intValueExact();
    }

    private static String socket(final String config) {
        return "[{\"id\":\"events\",\"type\":\"socket\",\"config\":" + config + "}]";
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
        assertThat(FlowCompiler.compile(flow(triggers, "{}"), CATALOG))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path))));
    }

    private static String flow(final String triggers, final String inputs) {
        return """
                {
                  "id":"socket-flow",
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
