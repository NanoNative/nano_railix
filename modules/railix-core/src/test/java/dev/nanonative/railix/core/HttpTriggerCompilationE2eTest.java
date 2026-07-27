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

class HttpTriggerCompilationE2eTest {
    private static final StepCatalog CATALOG = StepCatalog.of(
            step("source"),
            step("middle"),
            step("sink")
    );

    @Test
    void staticHttpPathCompilesAndRemainsInTheExecutablePlan() {
        final CompileResult.Compiled compiled = compiled(flow(
                """
                [{"id":"orders","type":"http","config":{"port":8080,"path":"/orders"}}]
                """,
                "http-flow",
                "middle"
        ));

        assertThat(compiled.flow().triggers()).singleElement().satisfies(trigger -> {
            assertThat(trigger.id()).isEqualTo("orders");
            assertThat(trigger.type()).isEqualTo("http");
            assertThat(trigger.config().values().get("path")).isEqualTo(RailixValue.string("/orders"));
            assertThat(((RailixValue.NumberValue) trigger.config().values().get("port")).value().intValueExact())
                    .isEqualTo(8080);
        });
    }

    @Test
    void fixedFlowEventPathCompilesOnlyWhenExplicitlyDeclared() {
        assertCompiled(flow(
                """
                [{"id":"flow-events","type":"http","config":{"port":8080,"flow":true}}]
                """,
                "http-flow",
                "middle"
        ));
    }

    @Test
    void fixedStepEventPathCompilesForASafeDownstreamGraph() {
        assertCompiled(flow(
                """
                [{"id":"middle-events","type":"http","config":{"port":8080,"step":"middle"}}]
                """,
                "http-flow",
                "middle"
        ));
    }

    @Test
    void httpPortIsRequired() {
        assertDiagnostic(
                trigger("{\"path\":\"/orders\"}"),
                "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                "HTTP trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void httpPortMustBeANumber() {
        assertDiagnostic(
                trigger("{\"port\":\"8080\",\"path\":\"/orders\"}"),
                "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                "HTTP trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void httpPortMustBeIntegral() {
        assertDiagnostic(
                trigger("{\"port\":8080.5,\"path\":\"/orders\"}"),
                "FLOW_TRIGGER_HTTP_PORT_REQUIRED",
                "HTTP trigger config field port must be an integer.",
                "triggers[0].config.port"
        );
    }

    @Test
    void httpPortCannotBeZero() {
        assertDiagnostic(
                trigger("{\"port\":0,\"path\":\"/orders\"}"),
                "FLOW_TRIGGER_HTTP_PORT_OUT_OF_RANGE",
                "HTTP trigger port must be between 1 and 65535.",
                "triggers[0].config.port"
        );
    }

    @Test
    void httpPortCannotExceedTcpRange() {
        assertDiagnostic(
                trigger("{\"port\":65536,\"path\":\"/orders\"}"),
                "FLOW_TRIGGER_HTTP_PORT_OUT_OF_RANGE",
                "HTTP trigger port must be between 1 and 65535.",
                "triggers[0].config.port"
        );
    }

    @Test
    void everyHttpTriggerUsesTheSameListenerPort() {
        assertDiagnostic(
                """
                [
                  {"id":"orders","type":"http","config":{"port":8080,"path":"/orders"}},
                  {"id":"admin","type":"http","config":{"port":8081,"path":"/admin"}}
                ]
                """,
                "FLOW_TRIGGER_HTTP_PORT_CONFLICT",
                "HTTP trigger port must match the first HTTP trigger port: 8080.",
                "triggers[1].config.port"
        );
    }

    @Test
    void httpTriggerRequiresOneRouteSelector() {
        assertDiagnostic(
                trigger("{\"port\":8080}"),
                "FLOW_TRIGGER_HTTP_SELECTOR_REQUIRED",
                "HTTP trigger requires exactly one of path, flow, or step.",
                "triggers[0].config"
        );
    }

    @Test
    void httpTriggerRejectsConflictingRouteSelectors() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/orders\",\"flow\":true}"),
                "FLOW_TRIGGER_HTTP_SELECTOR_REQUIRED",
                "HTTP trigger requires exactly one of path, flow, or step.",
                "triggers[0].config"
        );
    }

    @Test
    void flowSelectorMustBeLiteralTrue() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"flow\":false}"),
                "FLOW_TRIGGER_HTTP_FLOW_REQUIRED",
                "HTTP trigger config field flow must be true.",
                "triggers[0].config.flow"
        );
    }

    @Test
    void flowSelectorMustBeBoolean() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"flow\":\"true\"}"),
                "FLOW_TRIGGER_HTTP_FLOW_REQUIRED",
                "HTTP trigger config field flow must be true.",
                "triggers[0].config.flow"
        );
    }

    @Test
    void staticPathMustBeAString() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":null}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotBeEmpty() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathMustStartWithSlash() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"orders\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotHaveATrailingSlash() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/orders/\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotContainParentTraversal() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/orders/../admin\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotContainCurrentDirectoryTraversal() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/orders/./admin\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotContainNonAsciiCharacters() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/grüße\"}"),
                "FLOW_TRIGGER_HTTP_PATH_INVALID",
                "HTTP trigger path must be a static ASCII path without a trailing slash.",
                "triggers[0].config.path"
        );
    }

    @Test
    void staticPathCannotClaimTheReservedApiPrefix() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/v1/orders\"}"),
                "FLOW_TRIGGER_HTTP_PATH_RESERVED",
                "HTTP trigger path cannot use the reserved /v1 API prefix.",
                "triggers[0].config.path"
        );
    }

    @Test
    void duplicateHttpRoutesAreRejected() {
        assertDiagnostic(
                """
                [
                  {"id":"orders","type":"http","config":{"port":8080,"path":"/orders"}},
                  {"id":"orders-again","type":"http","config":{"port":8080,"path":"/orders"}}
                ]
                """,
                "FLOW_TRIGGER_HTTP_ROUTE_DUPLICATE",
                "HTTP route is already declared: /orders.",
                "triggers[1].config"
        );
    }

    @Test
    void fixedFlowRouteRequiresAUrlSafeFlowId() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"flow-events","type":"http","config":{"port":8080,"flow":true}}]
                        """,
                        "unsafe flow",
                        "middle"
                ),
                "FLOW_TRIGGER_HTTP_FLOW_ID_UNSAFE",
                "HTTP event flow id must be a URL-safe segment: unsafe flow.",
                "id"
        );
    }

    @Test
    void stepSelectorMustNameAnExistingStep() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"step\":\"missing\"}"),
                "FLOW_TRIGGER_HTTP_STEP_UNKNOWN",
                "HTTP event Step does not exist: missing.",
                "triggers[0].config.step"
        );
    }

    @Test
    void stepSelectorCannotBeNull() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"step\":null}"),
                "FLOW_TRIGGER_HTTP_STEP_REQUIRED",
                "HTTP trigger config field step must name a Step.",
                "triggers[0].config.step"
        );
    }

    @Test
    void stepSelectorCannotBeBlank() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"step\":\"  \"}"),
                "FLOW_TRIGGER_HTTP_STEP_REQUIRED",
                "HTTP trigger config field step must name a Step.",
                "triggers[0].config.step"
        );
    }

    @Test
    void stepSelectorMustBeAUrlSafeSegment() {
        assertDiagnostic(
                flow(
                        """
                        [{"id":"middle-events","type":"http","config":{"port":8080,"step":"middle step"}}]
                        """,
                        "http-flow",
                        "middle step"
                ),
                "FLOW_TRIGGER_HTTP_STEP_ID_UNSAFE",
                "HTTP event Step id must be a URL-safe segment: middle step.",
                "triggers[0].config.step"
        );
    }

    @Test
    void httpTriggerRejectsUnknownConfiguration() {
        assertDiagnostic(
                trigger("{\"port\":8080,\"path\":\"/orders\",\"host\":\"0.0.0.0\"}"),
                "FLOW_TRIGGER_CONFIG_FIELD_UNKNOWN",
                "Unknown http trigger config field: host",
                "triggers[0].config.host"
        );
    }

    @Test
    void stepEventRejectsADownstreamFlowInputDependency() {
        assertDiagnostic(
                downstreamFlowInput(),
                "FLOW_TRIGGER_HTTP_STEP_SOURCE_UNAVAILABLE",
                "Step event middle cannot supply flow input suffix to downstream Step sink.",
                "connections[2]"
        );
    }

    @Test
    void stepEventRejectsASkippedUpstreamStepDependency() {
        assertDiagnostic(
                skippedUpstreamOutput(),
                "FLOW_TRIGGER_HTTP_STEP_SOURCE_UNAVAILABLE",
                "Step event middle skips required Step output source.text for downstream Step sink.",
                "connections[2]"
        );
    }

    @Test
    void httpDiagnosticsDoNotDependOnConfigurationFieldOrder() {
        final CompileResult first = FlowCompiler.compile(flow(
                """
                [{"id":"orders","type":"http","config":{"z":true,"path":"/orders","a":true}}]
                """,
                "http-flow",
                "middle"
        ), CATALOG);
        final CompileResult second = FlowCompiler.compile(flow(
                """
                [{"id":"orders","type":"http","config":{"a":true,"path":"/orders","z":true}}]
                """,
                "http-flow",
                "middle"
        ), CATALOG);

        assertThat(first).isEqualTo(second);
    }

    private static StepDefinition step(final String id) {
        return StepDefinition.named(id, "1.0.0")
                .input("text", ValueShape.STRING)
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", input.value("text")));
    }

    private static String trigger(final String config) {
        return "[{\"id\":\"http\",\"type\":\"http\",\"config\":" + config + "}]";
    }

    private static String flow(final String triggers, final String id, final String middleId) {
        return """
                {
                  "id":"%s",
                  "triggers":%s,
                  "entry":"source",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"source","use":"source","config":{},"on":{"ok":"%s"}},
                    {"id":"%s","use":"middle","config":{},"on":{"ok":"sink"}},
                    {"id":"sink","use":"sink","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"source.text"},
                    {"from":"source.text","to":"%s.text"},
                    {"from":"%s.text","to":"sink.text"},
                    {"from":"sink.text","to":"output.text"}
                  ]
                }
                """.formatted(id, triggers, middleId, middleId, middleId, middleId);
    }

    private static String downstreamFlowInput() {
        return """
                {
                  "id":"http-flow",
                  "triggers":[
                    {"id":"middle-events","type":"http","config":{"port":8080,"step":"middle"}}
                  ],
                  "entry":"source",
                  "inputs":{"text":"string","suffix":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"source","use":"source","config":{},"on":{"ok":"middle"}},
                    {"id":"middle","use":"middle","config":{},"on":{"ok":"sink"}},
                    {"id":"sink","use":"pair","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"source.text"},
                    {"from":"source.text","to":"middle.text"},
                    {"from":"input.suffix","to":"sink.right"},
                    {"from":"middle.text","to":"sink.left"},
                    {"from":"sink.text","to":"output.text"}
                  ]
                }
                """;
    }

    private static String skippedUpstreamOutput() {
        return """
                {
                  "id":"http-flow",
                  "triggers":[
                    {"id":"middle-events","type":"http","config":{"port":8080,"step":"middle"}}
                  ],
                  "entry":"source",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"source","use":"source","config":{},"on":{"ok":"middle"}},
                    {"id":"middle","use":"middle","config":{},"on":{"ok":"sink"}},
                    {"id":"sink","use":"pair","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"source.text"},
                    {"from":"source.text","to":"middle.text"},
                    {"from":"source.text","to":"sink.right"},
                    {"from":"middle.text","to":"sink.left"},
                    {"from":"sink.text","to":"output.text"}
                  ]
                }
                """;
    }

    private static CompileResult.Compiled compiled(final String source) {
        return (CompileResult.Compiled) FlowCompiler.compile(source, catalog());
    }

    private static void assertCompiled(final String source) {
        assertThat(FlowCompiler.compile(source, catalog())).isInstanceOf(CompileResult.Compiled.class);
    }

    private static void assertDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        final String flowSource = source.stripLeading().startsWith("[")
                ? flow(source, "http-flow", "middle")
                : source;
        assertThat(FlowCompiler.compile(flowSource, catalog())).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(code, message, path)
        )));
    }

    private static StepCatalog catalog() {
        return StepCatalog.of(
                CATALOG.definitions().get(0),
                CATALOG.definitions().get(1),
                CATALOG.definitions().get(2),
                StepDefinition.named("pair", "1.0.0")
                        .input("left", ValueShape.STRING)
                        .input("right", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok").output("text", input.value("left")))
        );
    }
}
