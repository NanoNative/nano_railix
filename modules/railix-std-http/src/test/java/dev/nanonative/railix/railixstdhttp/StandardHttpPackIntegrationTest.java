package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixApp;
import dev.nanonative.railix.kernel.runtime.LocalExecutionKernel;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardHttpPackIntegrationTest {

    private static final String IMMEDIATE_REPLY_USE = "test.http.reply-immediate";
    private static final String SESSION_REPLY_USE = "test.http.reply-session";

    @TempDir
    Path tempDir;

    @Test
    void shouldMapHttpRequestToGenericEnvelopeAndReturnImmediateReply() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-immediate");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:40:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );
        final SettingsTree settingsTree = settingsTree("127.0.0.1", 0, false);

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree)) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/orders?tag=alpha&tag=beta"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"A-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("content-type")).hasValue("application/json");
            assertThat(response.headers().firstValue("x-railix-method")).hasValue("POST");
            assertThat(KernelContractCodec.parseStableJsonObject(response.body()))
                    .containsEntry("method", "POST")
                    .containsEntry("path", "/orders")
                    .containsEntry("orderId", "A-1");
            final String runId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path runFolder = runsRoot.resolve("railix-app").resolve("http-flow").resolve(runId);
            assertThat(Files.exists(runFolder.resolve("summary.json"))).isTrue();
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve("summary.json"))))
                    .containsEntry("outcome", "ok")
                    .containsEntry("executedSteps", 2);
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(runFolder.resolve(HttpCaptureArtifact.FILE_NAME))))
                    .containsEntry("protocol", "http")
                    .containsEntry("method", "POST")
                    .containsEntry("path", "/orders")
                    .containsEntry("responseStatus", 201)
                    .containsEntry("runOutcome", "ok")
                    .containsEntry("replyMode", "immediate")
                    .containsEntry("requestPayload", Map.of("orderId", "A-1"))
                    .containsEntry("query", Map.of("tag", List.of("alpha", "beta")));
            assertThat(Files.readString(runFolder.resolve("signals.ndjson")))
                    .contains("\"type\":\"reply.produced\"")
                    .contains("\"type\":\"run.finished\"");
        }
    }

    @Test
    void shouldQueryTrafficRowsFromCapturedHttpRuns() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-traffic");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:42:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpClient client = HttpClient.newHttpClient();
            client.send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/orders"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"A-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            client.send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/health"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"B-2\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        final List<HttpTrafficPanelQuery.HttpTrafficRow> rows = new HttpTrafficPanelQuery().read(runsRoot, 10);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(HttpTrafficPanelQuery.HttpTrafficRow::method).containsOnly("POST");
        assertThat(rows).extracting(HttpTrafficPanelQuery.HttpTrafficRow::path).containsExactlyInAnyOrder("/orders", "/health");
        assertThat(rows).extracting(HttpTrafficPanelQuery.HttpTrafficRow::status).containsOnly(201);
        assertThat(rows).extracting(HttpTrafficPanelQuery.HttpTrafficRow::captureKind).containsOnly("handled-run");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.appId()).isEqualTo("railix-app");
            assertThat(row.flowId()).isEqualTo("http-flow");
            assertThat(row.outcome()).isEqualTo("ok");
            assertThat(row.durationMs()).isGreaterThanOrEqualTo(0L);
        });
    }

    @Test
    void shouldPersistRejectedRequestCaptureAndExposeItThroughQueryAndReport() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-rejected");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:43:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/bad?tag=broken"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"broken\":"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(400);
            final String requestId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = HttpCaptureArtifact.rejectedCapturePath(runsRoot, "railix-app", "http-flow", requestId);
            assertThat(Files.exists(captureFile)).isTrue();
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(captureFile)))
                    .containsEntry("captureKind", "boundary-rejection")
                    .containsEntry("runOutcome", "rejected")
                    .containsEntry("method", "POST")
                    .containsEntry("path", "/bad")
                    .containsEntry("responseStatus", 400)
                    .containsEntry("problemType", "urn:railix:http:invalid-json")
                    .containsEntry("query", Map.of("tag", List.of("broken")));
        }

        final List<HttpTrafficPanelQuery.HttpTrafficRow> rows = new HttpTrafficPanelQuery().read(runsRoot, 10);
        final HttpTrafficReport.Report report = new HttpTrafficReport().read(runsRoot, 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().captureKind()).isEqualTo("boundary-rejection");
        assertThat(rows.getFirst().outcome()).isEqualTo("rejected");
        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.handledRows()).isEqualTo(0);
        assertThat(report.rejectedRows()).isEqualTo(1);
        assertThat(report.failedRows()).isEqualTo(0);
    }

    @Test
    void shouldPersistBoundaryFailureCaptureForUnexpectedRuntimeErrorAndExposeItThroughReportMain() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-boundary-failure");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:43:30Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(
                app,
                runsRoot,
                settingsTree("127.0.0.1", 0, false),
                new HttpEnvelopeMapper(),
                reply -> {
                    throw new IllegalStateException("simulated adapter crash");
                }
        )) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/boom?q=a+b&q=a%2Bb"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"A-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(KernelContractCodec.parseStableJsonObject(response.body()))
                    .containsEntry("type", "urn:railix:http:internal-error")
                    .containsEntry("status", 500);
            final String requestId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = HttpCaptureArtifact.failureCapturePath(runsRoot, "railix-app", "http-flow", requestId);
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(captureFile)))
                    .containsEntry("captureKind", "boundary-failure")
                    .containsEntry("runOutcome", "failed")
                    .containsEntry("responseStatus", 500)
                    .containsEntry("problemType", "urn:railix:http:internal-error")
                    .containsEntry("query", Map.of("q", List.of("a+b", "a+b")));
        }

        final HttpTrafficReport.Report report = new HttpTrafficReport().read(runsRoot, 10);
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final HttpTrafficReportMain.LaunchResult launchResult = HttpTrafficReportMain.launch(
                List.of("--runs-root", runsRoot.toString(), "--limit", "10"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.handledRows()).isEqualTo(0);
        assertThat(report.rejectedRows()).isEqualTo(0);
        assertThat(report.failedRows()).isEqualTo(1);
        assertThat(launchResult.succeeded()).isTrue();
        assertThat(stderr.toString()).isEmpty();
        assertThat(KernelContractCodec.parseStableJsonObject(stdout.toString().trim()))
                .containsEntry("totalRows", 1)
                .containsEntry("failedRows", 1);
    }

    @Test
    void shouldRenderTrafficPanelTableFromRealHttpBoundaryArtifacts() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-traffic-panel");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:44:15Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpClient client = HttpClient.newHttpClient();
            client.send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/orders"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"A-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            client.send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/health"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"B-2\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final HttpTrafficPanelMain.LaunchResult result = HttpTrafficPanelMain.launch(
                List.of("--runs-root", runsRoot.toString(), "--limit", "10"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(stderr.toString()).isEmpty();
        assertThat(stdout.toString())
                .contains("timestamp")
                .contains("method")
                .contains("path")
                .contains("status")
                .contains("durationMs")
                .contains("/orders")
                .contains("/health");
    }

    @Test
    void shouldPersistUnsupportedMediaTypeRejectionCapture() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-unsupported-media");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:44:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/media"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("plain text"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(415);
            final String requestId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = HttpCaptureArtifact.rejectedCapturePath(runsRoot, "railix-app", "http-flow", requestId);
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(captureFile)))
                    .containsEntry("captureKind", "boundary-rejection")
                    .containsEntry("runOutcome", "rejected")
                    .containsEntry("responseStatus", 415)
                    .containsEntry("problemType", "urn:railix:http:unsupported-media-type")
                    .containsEntry("requestBodyPreview", "plain text");
        }
    }

    @Test
    void shouldPersistNonObjectJsonRejectionCapture() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-non-object-json");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:45:30Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/array"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("[1,2,3]"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(400);
            final String requestId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = HttpCaptureArtifact.rejectedCapturePath(runsRoot, "railix-app", "http-flow", requestId);
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(captureFile)))
                    .containsEntry("captureKind", "boundary-rejection")
                    .containsEntry("runOutcome", "rejected")
                    .containsEntry("responseStatus", 400)
                    .containsEntry("problemType", "urn:railix:http:invalid-payload-shape")
                    .containsEntry("requestBodyPreview", "[1,2,3]");
        }
    }

    @Test
    void shouldRejectRequestBodyLargerThanFixedFirstSliceCapWith413AndBoundaryRejectionCapture() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-too-large");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:46:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );
        final String oversizedBody = oversizedJsonObject(HttpBoundarySettings.MAX_REQUEST_BYTES + 1);

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/too-large"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(oversizedBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(KernelContractCodec.parseStableJsonObject(response.body()))
                    .containsEntry("type", "urn:railix:http:payload-too-large")
                    .containsEntry("status", 413);
            final String requestId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = HttpCaptureArtifact.rejectedCapturePath(runsRoot, "railix-app", "http-flow", requestId);
            assertThat(KernelContractCodec.parseStableJsonObject(Files.readString(captureFile)))
                    .containsEntry("captureKind", "boundary-rejection")
                    .containsEntry("runOutcome", "rejected")
                    .containsEntry("responseStatus", 413)
                    .containsEntry("problemType", "urn:railix:http:payload-too-large")
                    .containsEntry("requestBodyTruncated", true);
        }
    }

    @Test
    void shouldAcceptRequestBodyExactlyAtFixedFirstSliceCap() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-cap-exact");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:46:30Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );
        final String exactCapBody = exactSizedJsonObject(HttpBoundarySettings.MAX_REQUEST_BYTES);

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/cap-exact"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(exactCapBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("x-railix-run-id")).isPresent();
        }
    }

    @Test
    void shouldPersistBoundedHandledRequestPreviewWithoutFullRequestPayloadForLargeSuccessfulBodies() throws Exception {
        final Path runsRoot = tempDir.resolve("runs-large-handled");
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:47:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );
        final String largeBody = exactSizedJsonObject(8_192);

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, runsRoot, settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/large-handled"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(largeBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(201);
            final String runId = response.headers().firstValue("x-railix-run-id").orElseThrow();
            final Path captureFile = runsRoot.resolve("railix-app").resolve("http-flow").resolve(runId).resolve(HttpCaptureArtifact.FILE_NAME);
            final Map<String, Object> capture = KernelContractCodec.parseStableJsonObject(Files.readString(captureFile));
            assertThat(capture)
                    .containsEntry("captureKind", "handled-run")
                    .containsEntry("requestBodyTruncated", true);
            assertThat(capture).containsKey("requestBodyPreview");
            assertThat(capture).doesNotContainKey("requestPayload");
        }
    }

    @Test
    void shouldRejectUnsupportedReplyModesWithDeterministicHttpError() throws Exception {
        final BuiltRailixApp app = new BuiltRailixApp(
                sessionReplyPlan(),
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-28T09:45:00Z"), ZoneOffset.UTC)),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        try (EmbeddedHttpBoundary boundary = new EmbeddedHttpBoundary(app, tempDir.resolve("runs-session"), settingsTree("127.0.0.1", 0, false))) {
            boundary.start();
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(boundary.baseUri().resolve("/sessions"))
                            .timeout(Duration.ofSeconds(10))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"open\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(501);
            assertThat(KernelContractCodec.parseStableJsonObject(response.body()))
                    .containsEntry("title", "Unsupported Reply Mode")
                    .containsEntry("status", 501);
            assertThat(response.headers().firstValue("x-railix-run-id")).isPresent();
        }
    }

    @Test
    void shouldFailFastOnInvalidHttpListenerSettings() {
        final BuiltRailixApp app = new BuiltRailixApp(
                immediateReplyPlan(),
                new LocalExecutionKernel(),
                new dev.nanonative.railix.kernel.runtime.PackBackedStepResolver(List.of(
                        new StandardHttpStepProvider(),
                        testStepProvider()
                ))
        );

        assertThatThrownBy(() -> new EmbeddedHttpBoundary(app, tempDir.resolve("runs-tls"), settingsTree("127.0.0.1", 8080, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings.http.tls.enabled=true is not supported by the first std.http slice");
    }

    private static AppPlan immediateReplyPlan() {
        return new AppPlan(
                "railix-app",
                "http-flow",
                "http-trigger",
                List.of(
                        new AppPlan.StepInvocation("http-trigger", StandardHttpStepProvider.HTTP_TRIGGER_USE, Map.of("request", "reply-step")),
                        new AppPlan.StepInvocation("reply-step", IMMEDIATE_REPLY_USE, Map.of())
                )
        );
    }

    private static AppPlan sessionReplyPlan() {
        return new AppPlan(
                "railix-app",
                "http-session-flow",
                "http-trigger",
                List.of(
                        new AppPlan.StepInvocation("http-trigger", StandardHttpStepProvider.HTTP_TRIGGER_USE, Map.of("request", "reply-step")),
                        new AppPlan.StepInvocation("reply-step", SESSION_REPLY_USE, Map.of())
                )
        );
    }

    private static StepProvider testStepProvider() {
        return use -> switch (use) {
            case IMMEDIATE_REPLY_USE -> Optional.of(immediateReplyStep());
            case SESSION_REPLY_USE -> Optional.of(sessionReplyStep());
            default -> Optional.empty();
        };
    }

    private static Step immediateReplyStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        IMMEDIATE_REPLY_USE,
                        "0.1.0",
                        "HTTP reply step",
                        "Test-only immediate reply step.",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("Reply produced")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result(
                        "ok",
                        List.of(),
                        new Reply(
                                Reply.Mode.IMMEDIATE,
                                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(201)),
                                new RailixValue.ObjectValue(Map.of(
                                        "contentType", new RailixValue.StringValue("application/json"),
                                        "headers", new RailixValue.ObjectValue(Map.of(
                                                "x-railix-method", input.context().get(RailixPath.parse("ctx.http.request.method"))
                                        ))
                                )),
                                new RailixValue.ObjectValue(Map.of(
                                        "method", input.context().get(RailixPath.parse("ctx.http.request.method")),
                                        "path", input.context().get(RailixPath.parse("ctx.http.request.path")),
                                        "orderId", input.context().get(RailixPath.parse("ctx.payload.orderId"))
                                )),
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL
                        )
                );
            }
        };
    }

    private static Step sessionReplyStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        SESSION_REPLY_USE,
                        "0.1.0",
                        "HTTP session step",
                        "Test-only unsupported reply step.",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("Unsupported reply produced")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result(
                        "ok",
                        List.of(),
                        new Reply(
                                Reply.Mode.SESSION,
                                RailixValue.NULL,
                                new RailixValue.ObjectValue(Map.of()),
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL,
                                new RailixValue.SessionRef("session-1", "http", Map.of(
                                        "path", input.context().get(RailixPath.parse("ctx.http.request.path"))
                                )),
                                RailixValue.NULL
                        )
                );
            }
        };
    }

    private static SettingsTree settingsTree(final String host, final int port, final boolean tlsEnabled) {
        return new SettingsTree(
                "http-settings",
                Map.of(
                        RailixPath.parse("settings.http.host"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.host"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue(host)),
                                true,
                                false,
                                false,
                                "integration-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        ),
                        RailixPath.parse("settings.http.port"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.port"),
                                "int",
                                new SettingsTree.PlainValue(new RailixValue.NumberValue(java.math.BigDecimal.valueOf(port))),
                                true,
                                false,
                                false,
                                "integration-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        ),
                        RailixPath.parse("settings.http.tls.enabled"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.tls.enabled"),
                                "bool",
                                new SettingsTree.PlainValue(new RailixValue.BoolValue(tlsEnabled)),
                                true,
                                false,
                                false,
                                "integration-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }

    private static String oversizedJsonObject(final int minimumBytes) {
        return exactSizedJsonObject(minimumBytes + 32);
    }

    private static String exactSizedJsonObject(final int targetBytes) {
        final String prefix = "{\"orderId\":\"";
        final String suffix = "\"}";
        final int payloadLength = targetBytes - prefix.length() - suffix.length();
        if (payloadLength < 0) {
            throw new IllegalArgumentException("targetBytes is too small");
        }
        return prefix + "a".repeat(payloadLength) + suffix;
    }
}
