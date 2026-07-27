package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationHttpServerE2eTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void staticHttpTriggerRunsTheCompiledFlow() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/lowercase", json("HELLO"))).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    """
                    {"outputs":{"text":"hello"},"status":"succeeded","steps":[{"outcome":"ok","step":"lowercase"}]}\
                    """
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void explicitFlowEventRouteRunsTheCompiledFlow() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/v1/flows/http-app/events", json("HELLO")).body())
                    .contains("\"text\":\"hello\"", "\"status\":\"succeeded\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void explicitStepEventRouteRunsFromThatStep() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/v1/flows/http-app/steps/lowercase/events", json("HELLO")).body())
                    .contains("\"text\":\"hello\"", "\"step\":\"lowercase\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void undeclaredFlowEventRouteIsNotExposed() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), false, true));
        try {
            assertThat(post(server, "/v1/flows/http-app/events", json("HELLO")).status()).isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    @Test
    void undeclaredStepEventRouteIsNotExposed() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, false));
        try {
            assertThat(post(server, "/v1/flows/http-app/steps/lowercase/events", json("HELLO")).status())
                    .isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    @Test
    void wrongFlowIdDoesNotReachAnEnabledEventRoute() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/v1/flows/other/events", json("HELLO")).status()).isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownRouteReturnsProblemJson() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/missing", json("HELLO"))).isEqualTo(new Response(
                    404,
                    "application/problem+json; charset=utf-8",
                    problem(404, "HTTP_ROUTE_NOT_FOUND", "Not Found", "HTTP route is not enabled.")
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void wrongMethodReturnsAllowHeader() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            final HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("lowercase"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(List.of(
                    response.statusCode(),
                    response.headers().firstValue("Allow").orElse(""),
                    response.headers().firstValue("Content-Type").orElse("")
            )).containsExactly(405, "POST", "application/problem+json; charset=utf-8");
        } finally {
            server.stop();
        }
    }

    @Test
    void queryParametersAreRejectedInsteadOfBecomingEventData() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/lowercase?hidden=true", json("HELLO"))).isEqualTo(new Response(
                    400,
                    "application/problem+json; charset=utf-8",
                    problem(400, "HTTP_QUERY_UNSUPPORTED", "Bad Request", "HTTP event routes do not accept a query.")
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void missingContentTypeIsRejected() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(server, "/lowercase", json("HELLO").getBytes(StandardCharsets.UTF_8), null).status())
                    .isEqualTo(415);
        } finally {
            server.stop();
        }
    }

    @Test
    void unsupportedContentTypeIsRejected() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(
                    server,
                    "/lowercase",
                    json("HELLO").getBytes(StandardCharsets.UTF_8),
                    "text/plain"
            ).status()).isEqualTo(415);
        } finally {
            server.stop();
        }
    }

    @Test
    void emptyJsonBodyIsRejectedBeforeFlowAdmission() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(server, "/lowercase", new byte[0], "application/json").status()).isEqualTo(400);
        } finally {
            server.stop();
        }
    }

    @Test
    void malformedJsonBodyIsRejectedBeforeFlowAdmission() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(
                    server,
                    "/lowercase",
                    "{".getBytes(StandardCharsets.UTF_8),
                    "application/json"
            ).status()).isEqualTo(400);
        } finally {
            server.stop();
        }
    }

    @Test
    void invalidUtf8BodyIsRejectedBeforeJsonParsing() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(
                    server,
                    "/lowercase",
                    new byte[]{'{', '"', (byte) 0xc3, '(', '"', ':', '1', '}'},
                    "application/json"
            ).body()).contains("\"code\":\"HTTP_EVENT_UTF8_INVALID\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void nonObjectJsonBodyIsRejectedAsAnEvent() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(send(
                    server,
                    "/lowercase",
                    "[]".getBytes(StandardCharsets.UTF_8),
                    "application/json"
            )).isEqualTo(new Response(
                    422,
                    "application/json; charset=utf-8",
                    """
                    {"diagnostics":[{"code":"FLOW_INPUT_OBJECT_REQUIRED","column":0,"line":0,"message":"Flow inputs must be an object.","path":"inputs"}],"status":"run-rejected","steps":[]}\
                    """
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void missingFlowInputReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/lowercase", "{}").body())
                    .contains("\"code\":\"FLOW_INPUT_REQUIRED\"", "\"status\":\"run-rejected\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void wrongFlowInputShapeReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/lowercase", "{\"text\":7}").body())
                    .contains("\"code\":\"FLOW_INPUT_TYPE_MISMATCH\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownFlowInputReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(post(server, "/lowercase", "{\"text\":\"HELLO\",\"extra\":true}").body())
                    .contains("\"code\":\"FLOW_INPUT_UNKNOWN\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void exactMaximumEventBodyIsAccepted() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        final byte[] event = paddedEvent(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        try {
            assertThat(send(server, "/lowercase", event, "application/json").status()).isEqualTo(200);
        } finally {
            server.stop();
        }
    }

    @Test
    void oversizedEventBodyIsRejectedAfterOneProbeByte() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        final byte[] event = paddedEvent(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);
        try {
            assertThat(send(server, "/lowercase", event, "application/json").status()).isEqualTo(413);
        } finally {
            server.stop();
        }
    }

    @Test
    void overDepthEventIsRejectedBeforeRecursiveExecution() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        final String event = "{\"text\":" + "[".repeat(65) + "\"HELLO\"" + "]".repeat(65) + "}";
        try {
            assertThat(post(server, "/lowercase", event).body()).contains("\"code\":\"HTTP_EVENT_DEPTH_EXCEEDED\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void overLimitNumberIsRejectedBeforeFlowAdmission() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        final String event = "{\"text\":" + "1" + "0".repeat(1_024) + "}";
        try {
            assertThat(post(server, "/lowercase", event).body())
                    .contains("\"code\":\"HTTP_EVENT_NUMBER_LIMIT_EXCEEDED\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void stepImplementationFaultReturnsCanonicalFailure() throws Exception {
        final ApplicationHttpServer server = start(actionFlow(
                freePort(),
                input -> {
                    throw new IllegalStateException("fixture fault");
                }
        ));
        try {
            assertThat(post(server, "/action", json("HELLO"))).isEqualTo(new Response(
                    500,
                    "application/json; charset=utf-8",
                    """
                    {"failure":{"code":"STEP_IMPLEMENTATION_FAULT","message":"Step implementation threw an unexpected exception.","step":"action"},"status":"run-failed","steps":[]}\
                    """
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void interruptedStepReturnsCanonicalCancellation() throws Exception {
        final ApplicationHttpServer server = start(actionFlow(
                freePort(),
                input -> {
                    throw new InterruptedException("fixture cancellation");
                }
        ));
        try {
            assertThat(post(server, "/action", json("HELLO"))).isEqualTo(new Response(
                    503,
                    "application/json; charset=utf-8",
                    "{\"status\":\"run-cancelled\",\"steps\":[]}"
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void oversizedReplyReturnsOneProblemWithoutPartialOutput() throws Exception {
        final String oversized = "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        final ApplicationHttpServer server = start(actionFlow(
                freePort(),
                input -> StepResult.outcome("ok").output("text", RailixValue.string(oversized))
        ));
        try {
            assertThat(post(server, "/action", json("HELLO"))).isEqualTo(new Response(
                    500,
                    "application/problem+json; charset=utf-8",
                    problem(
                            500,
                            "HTTP_REPLY_TOO_LARGE",
                            "Internal Server Error",
                            "HTTP reply exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                    )
            ));
        } finally {
            server.stop();
        }
    }

    @Test
    void repeatedEventsDoNotRetainPriorInput() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(List.of(
                    post(server, "/lowercase", json("FIRST")).body(),
                    post(server, "/lowercase", json("SECOND")).body()
            )).allSatisfy(body -> assertThat(body).doesNotContain("FIRSTSECOND"))
                    .anySatisfy(body -> assertThat(body).contains("\"text\":\"first\""))
                    .anySatisfy(body -> assertThat(body).contains("\"text\":\"second\""));
        } finally {
            server.stop();
        }
    }

    @Test
    void concurrentEventsRemainIsolated() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<Response>> requests = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final int value = index;
                requests.add(executor.submit(() -> post(server, "/lowercase", json("VALUE-" + value))));
            }
            for (int index = 0; index < requests.size(); index++) {
                assertThat(requests.get(index).get().body()).contains("\"text\":\"value-" + index + "\"");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void concurrentRequestLimitRejectsExcessWithoutQueueing() throws Exception {
        final CountDownLatch entered = new CountDownLatch(32);
        final CountDownLatch release = new CountDownLatch(1);
        final ApplicationHttpServer server = start(actionFlow(
                freePort(),
                input -> {
                    entered.countDown();
                    release.await();
                    return StepResult.outcome("ok").output("text", input.value("text"));
                }
        ));
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<Response>> active = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                active.add(clients.submit(() -> post(server, "/action", json("ACTIVE"))));
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(post(server, "/action", json("EXCESS"))).isEqualTo(new Response(
                    503,
                    "application/problem+json; charset=utf-8",
                    problem(
                            503,
                            "HTTP_BACKPRESSURE",
                            "Service Unavailable",
                            "HTTP request limit is reached."
                    )
            ));
            release.countDown();
            for (final Future<Response> response : active) {
                assertThat(response.get(2, TimeUnit.SECONDS).status()).isEqualTo(200);
            }
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    void serverBindsOnlyIpv4Loopback() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        try {
            assertThat(server.baseUri().getHost()).isEqualTo("127.0.0.1");
        } finally {
            server.stop();
        }
    }

    @Test
    void stopIsIdempotentAndReturnsTheServer() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));

        assertThat(List.of(server.stop(), server.stop())).containsOnly(server);
    }

    @Test
    void readinessAfterStopIsRejectedDeterministically() throws Exception {
        final ApplicationHttpServer server = ApplicationHttpServer.start(standardFlow(freePort(), true, true));

        server.stop();

        assertThatThrownBy(server::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application HTTP server is not running.");
    }

    @Test
    void gatedServerRejectsEventsAndStopsWithoutLeakingItsPort() throws Exception {
        final int port = freePort();
        final ApplicationHttpServer server = ApplicationHttpServer.start(standardFlow(port, true, true));

        assertThat(post(server, "/lowercase", json("HELLO")).status()).isEqualTo(503);
        assertThat(server.stop()).isSameAs(server);
        assertThat(canBind(port)).isTrue();
    }

    @Test
    void awaitStopUnblocksOnlyAfterServerStop() throws Exception {
        final ApplicationHttpServer server = start(standardFlow(freePort(), true, true));
        final FutureTask<ApplicationHttpServer> waiting = new FutureTask<>(server::awaitStop);
        Thread.startVirtualThread(waiting);

        assertThat(waiting.isDone()).isFalse();
        server.stop();
        assertThat(waiting.get(2, TimeUnit.SECONDS)).isSameAs(server);
    }

    @Test
    void stopInterruptsAnActiveStepAndReleasesItsRequestThread() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ApplicationHttpServer server = start(actionFlow(
                freePort(),
                input -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException exception) {
                        interrupted.countDown();
                        throw exception;
                    }
                    return StepResult.outcome("ok").output("text", input.value("text"));
                }
        ));
        final FutureTask<Response> request = new FutureTask<>(() -> post(server, "/action", json("HELLO")));
        final Thread requestThread = Thread.startVirtualThread(request);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(server.stop()).isSameAs(server);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            server.stop();
            requestThread.join(Duration.ofSeconds(2));
        }
        assertThat(requestThread.isAlive()).isFalse();
    }

    @Test
    void stopDoesNotWaitForAnActiveStepThatIgnoresInterruption() throws Exception {
        final int port = freePort();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ApplicationHttpServer server = start(actionFlow(
                port,
                input -> {
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (final InterruptedException ignored) {
                            interrupted.countDown();
                        }
                    }
                    return StepResult.outcome("ok").output("text", input.value("text"));
                }
        ));
        final FutureTask<Response> request = new FutureTask<>(() -> post(server, "/action", json("HELLO")));
        final Thread requestThread = Thread.startVirtualThread(request);
        final FutureTask<ApplicationHttpServer> stopping = new FutureTask<>(server::stop);
        Thread stoppingThread = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            stoppingThread = Thread.startVirtualThread(stopping);
            assertThat(stopping.get(2, TimeUnit.SECONDS)).isSameAs(server);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(canBind(port)).isTrue();
        } finally {
            release.countDown();
            if (stoppingThread != null) {
                stoppingThread.join(Duration.ofSeconds(2));
            }
            server.stop();
            requestThread.join(Duration.ofSeconds(2));
        }
        assertThat(List.of(stopping.isDone(), requestThread.isAlive())).containsExactly(true, false);
    }

    private static ApplicationHttpServer start(final CompiledFlow flow) throws IOException {
        return ApplicationHttpServer.start(flow).ready();
    }

    private static Response post(
            final ApplicationHttpServer server,
            final String path,
            final String body
    ) throws Exception {
        return send(server, path, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static Response send(
            final ApplicationHttpServer server,
            final String path,
            final byte[] body,
            final String contentType
    ) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path.substring(1)))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        final HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body()
        );
    }

    private static CompiledFlow standardFlow(
            final int port,
            final boolean flowEvents,
            final boolean stepEvents
    ) {
        final List<String> triggers = new ArrayList<>();
        triggers.add("""
                {"id":"lowercase-http","type":"http","config":{"port":%d,"path":"/lowercase"}}\
                """.formatted(port));
        if (flowEvents) {
            triggers.add("""
                    {"id":"flow-events","type":"http","config":{"port":%d,"flow":true}}\
                    """.formatted(port));
        }
        if (stepEvents) {
            triggers.add("""
                    {"id":"step-events","type":"http","config":{"port":%d,"step":"lowercase"}}\
                    """.formatted(port));
        }
        return compiled("""
                {
                  "id":"http-app",
                  "triggers":[%s],
                  "entry":"lowercase",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"lowercase","use":"lowercase","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"lowercase.text"},
                    {"from":"lowercase.text","to":"output.text"}
                  ]
                }
                """.formatted(String.join(",", triggers)), StepCatalog.of(
                StepDefinition.named("lowercase", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(input -> {
                            final String text = ((RailixValue.StringValue) input.value("text")).value();
                            return StepResult.outcome("ok").output(
                                    "text",
                                    RailixValue.string(text.toLowerCase(java.util.Locale.ROOT))
                            );
                        })
        ));
    }

    private static CompiledFlow actionFlow(final int port, final dev.nanonative.railix.core.step.StepHandler handler) {
        return compiled("""
                {
                  "id":"action-app",
                  "triggers":[
                    {"id":"action-http","type":"http","config":{"port":%d,"path":"/action"}}
                  ],
                  "entry":"action",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[{"id":"action","use":"action","config":{},"on":{"ok":"end"}}],
                  "connections":[
                    {"from":"input.text","to":"action.text"},
                    {"from":"action.text","to":"output.text"}
                  ]
                }
                """.formatted(port), StepCatalog.of(
                StepDefinition.named("action", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(handler)
        ));
    }

    private static CompiledFlow compiled(final String source, final StepCatalog catalog) {
        return ((CompileResult.Compiled) FlowCompiler.compile(source, catalog)).flow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            return socket.getLocalPort();
        }
    }

    private static boolean canBind(final int port) {
        try (ServerSocket ignored = new ServerSocket(
                port,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private static byte[] paddedEvent(final int bytes) {
        final byte[] prefix = json("HELLO").getBytes(StandardCharsets.UTF_8);
        final byte[] result = new byte[bytes];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        java.util.Arrays.fill(result, prefix.length, result.length, (byte) ' ');
        return result;
    }

    private static String json(final String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    private static String problem(
            final int status,
            final String code,
            final String title,
            final String detail
    ) {
        return """
                {"code":"%s","detail":"%s","status":%d,"title":"%s","type":"about:blank"}\
                """.formatted(code, detail, status, title);
    }

    private record Response(int status, String contentType, String body) {
    }
}
