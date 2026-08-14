package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.DevelopmentRuntimeConformanceSteps;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(240)
final class DevelopmentRuntimeGeneratedApplicationE2eTest {
    private static final int MAX_CONTEXT_BYTES = 1_048_576;
    private static final int ADMISSION_LIMIT = 32;
    private static final String TOKEN = "development-runtime-token";
    private static final Duration WAIT = Duration.ofSeconds(20);

    private DevelopmentRuntimeGeneratedProcess.Build build;
    private DevelopmentRuntimeGeneratedProcess.Child shared;
    private URI sharedUri;

    @BeforeAll
    void buildAndStartGeneratedApplication(@TempDir final Path workspace) throws Exception {
        build = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                project(),
                definitions(),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                DevelopmentRuntimeConformanceSteps.Operation.class,
                DevelopmentRuntimeConformanceSteps.Fault.class,
                DevelopmentRuntimeConformanceSteps.Cancel.class,
                DevelopmentRuntimeConformanceSteps.Append.class,
                DevelopmentRuntimeConformanceSteps.Oversized.class,
                DevelopmentRuntimeConformanceSteps.Block.class,
                DevelopmentRuntimeConformanceSteps.Hold.class,
                DevelopmentRuntimeConformanceSteps.NullApplicationMain.class,
                DevelopmentRuntimeConformanceSteps.NoisyMain.class
        );
        shared = started();
        sharedUri = shared.awaitReady();
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (shared != null) {
            shared.close();
        }
    }

    @Test
    void generatedApplicationIsSuppliedBeforeRuntimeStartup() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void absentStartupTokenIsRejectedBeforeServerStartup() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator closed stdin before sending a complete startup frame.");
        }
    }

    @Test
    void blankStartupTokenIsRejectedBeforeServerStartup() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input("\n".getBytes(StandardCharsets.UTF_8));
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame is required.");
        }
    }

    @Test
    void oversizedUtf8StartupTokenIsRejectedBeforeServerStartup() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(("ä".repeat(65) + "\n").getBytes(StandardCharsets.UTF_8));
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame exceeds 128 bytes.");
        }
    }

    @Test
    void malformedStartupFrameIsRejectedBeforeServerStartup() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input("BROKEN\n".getBytes(StandardCharsets.UTF_8));
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame is invalid.");
        }
    }

    @Test
    void invalidStartupCallbackPortIsRejectedBeforeServerStartup() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(("START " + TOKEN + " 70000\n").getBytes(StandardCharsets.UTF_8));
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup callback port is invalid.");
        }
    }

    @Test
    void unavailableStartupCallbackClosesTheGeneratedRuntime() throws Exception {
        final int unavailablePort;
        try (ServerSocket callback = signalServer(1)) {
            unavailablePort = callback.getLocalPort();
        }
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(("START " + TOKEN + " " + unavailablePort + "\n").getBytes(StandardCharsets.UTF_8));
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(3);
            assertThat(child.error()).contains("Cannot start development application:");
        }
    }

    @Test
    void missingAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(shared, sharedUri, null, "POST", "/v1/run/success", "{}", "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void incorrectAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(shared, sharedUri, "wrong", "POST", "/v1/run/success", "{}", "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void missingPreviewAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, null, "POST", "/v1/preview/details/details-step", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void validAuthorizationExecutesTheGeneratedApplication() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{\"payload\":\"input\"}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"payload\":\"input\"")
                .contains("\"id\":\"success-step\",\"outcome\":\"next\"");
    }

    @Test
    void responseDisablesCachingAndDeclaresJson() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{}", "true"
        );

        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
    }

    @Test
    void unsupportedMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/run/success", "", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void unsupportedPreviewMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/preview/details/details-step", "", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void blankRunTargetIsNotFound() throws Exception {
        final HttpResponse<String> response = request(shared, sharedUri, TOKEN, "POST", "/v1/run/", "{}", "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void nestedRunTargetIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/one/two", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void incompletePreviewTargetIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/preview/details/", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void invalidTestHeaderIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{}", "sometimes"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_TEST_FLAG_INVALID\","
                                + "\"message\":\"X-Railix-Test must be true or false.\","
                                + "\"path\":\"X-Railix-Test\"}],\"status\":\"rejected\"}"
                );
    }

    @Test
    void missingTestHeaderDefaultsToProductionExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{}", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        200,
                        "{\"context\":{\"runtime\":{\"test\":false,\"trigger\":\"success\"}},"
                                + "\"status\":\"succeeded\",\"steps\":[]}"
                );
    }

    @Test
    void falseTestHeaderUsesProductionExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{}", "false"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        200,
                        "{\"context\":{\"runtime\":{\"test\":false,\"trigger\":\"success\"}},"
                                + "\"status\":\"succeeded\",\"steps\":[]}"
                );
    }

    @Test
    void maximumSizedRequestBodyIsAccepted() throws Exception {
        final String body = "{}" + " ".repeat(MAX_CONTEXT_BYTES - 2);

        assertThat(request(shared, sharedUri, TOKEN, "POST", "/v1/run/success", body, "true").statusCode())
                .isEqualTo(200);
    }

    @Test
    void rejectedGeneratedResultIsReturnedExplicitly() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/rejected", "{}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains("\"code\":\"RUN_RESULT_REQUIRED\"")
                .contains("\"path\":\"context.result\"")
                .contains("\"status\":\"rejected\"");
    }

    @Test
    void failedGeneratedResultIsReturnedExplicitly() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/failed", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        500,
                        "{\"failure\":{\"code\":\"STEP_IMPLEMENTATION_FAULT\","
                                + "\"message\":\"Step implementation threw an unexpected exception.\","
                                + "\"step\":\"failed-step\"},\"status\":\"failed\",\"steps\":[]}"
                );
    }

    @Test
    void cancelledGeneratedResultIsReturnedExplicitly() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/cancelled", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(409, "{\"status\":\"cancelled\",\"steps\":[]}");
    }

    @Test
    void interruptedNestedStepRecordsExactlyOneTerminalCancellationStage() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/preview/nested-cancelled/nested-cancelled-step",
                "{\"payload\":{\"value\":\"input\"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body())
                .contains("\"stages\":[{\"input\":\"operations\"")
                .contains("\"status\":\"cancelled\",\"use\":\"development.runtime.nested-cancel\"}]")
                .doesNotContain("}],{");
    }

    @Test
    void observationExecutesARealSideEffectExactlyOnce(@TempDir final Path output) throws Exception {
        final Path file = output.resolve("observed.txt");
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/preview/side-effect/side-effect-step",
                "{\"payload\":{\"file\":\"" + escaped(file.toString()) + "\"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("x");
    }

    @Test
    void previewReturnsRealResolvedInputsStagesAndCandidates() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/preview/details/details-step",
                "{\"payload\":{\"value\":\" Hello RAILIX \"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"inputs\":{\"field\":\" Hello RAILIX \",\"value\":\" Hello RAILIX \"}")
                .contains("\"selected_candidates\":{\"nodes[")
                .contains("].inputs.value\":0}")
                .contains("\"stages\":[{\"input\":\"operations\"")
                .contains("\"use\":\"text.trim\"")
                .contains("\"use\":\"text.lowercase\"");
    }

    @Test
    void malformedJsonIsRejectedBeforeGeneratedExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{", "true"
        );

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"status\":\"rejected\"");
    }

    @Test
    void malformedPreviewJsonIsRejectedBeforeGeneratedExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/preview/details/details-step", "{", "true"
        );

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"status\":\"rejected\"");
    }

    @Test
    void nonObjectContextIsRejectedBeforeGeneratedExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "[]", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"CONTEXT_OBJECT_REQUIRED\","
                                + "\"message\":\"Workflow context must be an object.\",\"path\":\"\"}],"
                                + "\"status\":\"rejected\"}"
                );
    }

    @Test
    void oversizedRequestBodyIsRejectedAtTheHttpBoundary() throws Exception {
        final String body = "{\"payload\":\"" + "x".repeat(MAX_CONTEXT_BYTES) + "\"}";
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", body, "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        413,
                        "{\"diagnostics\":[{\"code\":\"CONTEXT_TOO_LARGE\","
                                + "\"message\":\"Workflow context exceeds the 1048576-byte limit.\","
                                + "\"path\":\"\"}],\"status\":\"rejected\"}"
                );
    }

    @Test
    void oversizedGeneratedResponseIsReplacedByABoundedProblem() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/oversized", "{}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"RUN_RESPONSE_TOO_LARGE\"");
    }

    @Test
    void oversizedGeneratedPreviewIsReplacedByABoundedProblem() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/preview/oversized/oversized-step", "{}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"PREVIEW_RESPONSE_TOO_LARGE\"");
    }

    @Test
    void repeatedCreatorCloseIsIdempotentForARealChild() throws Exception {
        final DevelopmentApplication application = DevelopmentApplication.start(20, build.project(), build.compiled());

        application.close();

        assertThatCode(application::close).doesNotThrowAnyException();
    }

    @Test
    void creatorClosePreservesCallerInterruptStateForARealChild() throws Exception {
        final DevelopmentApplication application = DevelopmentApplication.start(21, build.project(), build.compiled());
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            application.close();
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        closer.join(WAIT);

        assertThat(closer.isAlive()).isFalse();
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void interruptedHarnessCloseStillReclaimsTheRealGeneratedChild() throws Exception {
        try (var child = started()) {
            child.awaitReady();
            final AtomicBoolean completed = new AtomicBoolean();
            final AtomicBoolean interrupted = new AtomicBoolean();
            final Thread closer = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                child.close();
                completed.set(true);
                interrupted.set(Thread.currentThread().isInterrupted());
            });

            closer.join(WAIT);

            assertThat(closer.isAlive()).isFalse();
            assertThat(completed.get()).isTrue();
            assertThat(interrupted.get()).isTrue();
            assertThat(child.process().isAlive()).isFalse();
        }
    }

    @Test
    void ownershipCloseSynchronouslyReleasesTheLoopbackPort() throws Exception {
        try (var child = started()) {
            final int port = child.awaitReady().getPort();
            child.closeOwner();
            assertThat(child.awaitExit()).isZero();

            try (ServerSocket replacement = new ServerSocket()) {
                replacement.setReuseAddress(true);
                replacement.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                assertThat(replacement.isBound()).isTrue();
            }
        }
    }

    @Test
    void repeatedGeneratedLifecycleDoesNotRetainChildProcesses() throws Exception {
        final List<Long> pids = new ArrayList<>();
        for (int cycle = 0; cycle < 64; cycle++) {
            try (var child = started()) {
                child.awaitReady();
                pids.add(child.process().pid());
                child.closeOwner();
                assertThat(child.awaitExit()).isZero();
            }
        }

        assertThat(pids).allSatisfy(pid -> assertThat(
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        ).isFalse());
    }

    @Test
    void ownershipCloseInterruptsAndJoinsAnActiveGeneratedRequest() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final CompletableFuture<HttpResponse<String>> request = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/run/block",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}"
            );
            acceptSignal(entered);

            child.closeOwner();

            assertRequestCancelledOrClosed(request);
            assertThat(child.awaitExit()).isZero();
            assertThat(child.process().isAlive()).isFalse();
        }
    }

    @Test
    void concurrentOwnershipAndJvmCloseWaitForTheSameGeneratedShutdown() throws Exception {
        try (ServerSocket entered = signalServer();
             ServerSocket release = signalServer();
             var child = started()) {
            final URI uri = child.awaitReady();
            final CompletableFuture<HttpResponse<String>> request = hold(child, uri, entered, release);
            acceptSignal(entered);
            try (Socket releaseConnection = release.accept()) {
                final Thread ownerClose = Thread.ofPlatform().start(() -> closeOwner(child));
                final Thread jvmClose = Thread.ofPlatform().start(child.process()::destroy);

                assertThat(child.process().isAlive()).isTrue();
                releaseConnection.getOutputStream().write(1);
                ownerClose.join(WAIT);
                jvmClose.join(WAIT);

                assertThat(ownerClose.isAlive()).isFalse();
                assertThat(jvmClose.isAlive()).isFalse();
                assertRequestSucceededOrClosed(request);
                child.awaitExit();
                assertThat(child.process().isAlive()).isFalse();
            }
        }
    }

    @Test
    void nullApplicationIsRejectedInsideTheGeneratedArtifact() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launchMain(
                build,
                DevelopmentRuntimeConformanceSteps.NullApplicationMain.class.getName()
        )) {
            assertThat(child.awaitExit()).isZero();
            assertThat(child.output()).contains("Development application cannot be Java null.");
        }
    }

    @Test
    void nullApplicationIsRejectedBeforeForkedOwnershipInputIsRead() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launchMain(
                build,
                DevelopmentRuntimeConformanceSteps.NullApplicationMain.class.getName()
        )) {
            assertThat(child.awaitExit()).isZero();
            assertThat(child.output()).isEqualTo("Development application cannot be Java null.\n");
        }
    }

    @Test
    void generatedHarnessDrainsAllOutputWhileRetainingOnlyItsBoundedPrefix() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launchMain(
                build,
                DevelopmentRuntimeConformanceSteps.NoisyMain.class.getName()
        )) {
            assertThat(child.awaitExit()).isZero();
            assertThat(child.output()).isEqualTo("x".repeat(16_384));
        }
    }

    @Test
    void closedOwnershipInputIsRejectedByTheGeneratedProcess() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator closed stdin before sending a complete startup frame.");
        }
    }

    @Test
    void blankOwnershipTokenIsRejectedByTheGeneratedProcess() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input("\n".getBytes(StandardCharsets.UTF_8));
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame is required.");
        }
    }

    @Test
    void oversizedOwnershipTokenIsRejectedByTheGeneratedProcess() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(("x".repeat(129) + "\n").getBytes(StandardCharsets.UTF_8));
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame exceeds 128 bytes.");
        }
    }

    @Test
    void ownershipEofDuringTokenReadIsRejectedByTheGeneratedProcess() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input("partial-token".getBytes(StandardCharsets.UTF_8));
            child.closeOwner();
            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator closed stdin before sending a complete startup frame.");
        }
    }

    @Test
    void ownershipFrameWithTheWrongCommandIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("READY token 1\n", "Creator startup frame is invalid.");
    }

    @Test
    void ownershipFrameWithoutACallbackPortIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token\n", "Creator startup frame is invalid.");
    }

    @Test
    void ownershipFrameWithABlankTokenIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START  1\n", "Creator startup frame is invalid.");
    }

    @Test
    void ownershipFrameWithAnExtraFieldIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token 1 extra\n", "Creator startup frame is invalid.");
    }

    @Test
    void ownershipFrameWithAnEmptyCallbackPortIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token \n", "Creator startup callback port is invalid.");
    }

    @Test
    void ownershipFrameWithANonNumericCallbackPortIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token one\n", "Creator startup callback port is invalid.");
    }

    @Test
    void ownershipFrameWithCallbackPortZeroIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token 0\n", "Creator startup callback port is invalid.");
    }

    @Test
    void ownershipFrameWithCallbackPortAbove65535IsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token 65536\n", "Creator startup callback port is invalid.");
    }

    @Test
    void ownershipFrameWithAnOverflowingCallbackPortIsRejectedByTheGeneratedProcess() throws Exception {
        assertStartupRejected("START token 999999999999999999999\n", "Creator startup callback port is invalid.");
    }

    @Test
    void ownershipFrameWithInvalidUtf8IsRejectedByTheGeneratedProcess() throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(new byte[]{(byte) 0xc3, (byte) 0x28, '\n'});
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator startup frame is not valid UTF-8.");
        }
    }

    @Test
    void ownerEofAfterReadinessStopsTheGeneratedRuntimeSuccessfully() throws Exception {
        try (var child = started()) {
            child.awaitReady();
            child.closeOwner();

            assertThat(child.awaitExit()).isZero();
            assertThat(child.output()).doesNotContain("RAILIX_READY");
            assertThat(child.error()).doesNotContain("Cannot start development application");
        }
    }

    @Test
    void brokenOwnershipPipeAfterACompletedRequestStopsTheGeneratedRuntime() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true").statusCode())
                    .isEqualTo(200);

            child.closeOwner();

            assertThat(child.awaitExit()).isZero();
        }
    }

    @Test
    void completedGeneratedRunDoesNotRetainItsApplicationProcess() throws Exception {
        final long pid;
        try (var child = started()) {
            final URI uri = child.awaitReady();
            pid = child.process().pid();
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true").statusCode())
                    .isEqualTo(200);
            child.closeOwner();
            assertThat(child.awaitExit()).isZero();
        }

        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void jvmShutdownClosesTheGeneratedRuntimeBeforeReturning() throws Exception {
        try (var child = started()) {
            final int port = child.awaitReady().getPort();

            child.process().destroy();
            child.awaitExit();

            assertThat(child.process().isAlive()).isFalse();
            assertThatCode(() -> connect(port)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void requestThirtyThreeIsRejectedWhileThirtyTwoGeneratedExecutionsAreActive() throws Exception {
        try (ServerSocket entered = signalServer(ADMISSION_LIMIT + 1);
             ServerSocket release = signalServer(ADMISSION_LIMIT);
             var child = started()) {
            final URI uri = child.awaitReady();
            final List<CompletableFuture<HttpResponse<String>>> admitted = new ArrayList<>();
            for (int index = 0; index < ADMISSION_LIMIT; index++) {
                admitted.add(hold(child, uri, entered, release));
            }
            for (int index = 0; index < ADMISSION_LIMIT; index++) {
                acceptSignal(entered);
            }
            final List<Socket> releases = new ArrayList<>();
            try {
                for (int index = 0; index < ADMISSION_LIMIT; index++) {
                    releases.add(release.accept());
                }

                final HttpResponse<String> saturated = hold(child, uri, entered, release)
                        .get(2, TimeUnit.SECONDS);

                assertThat(saturated).extracting(HttpResponse::statusCode, HttpResponse::body)
                        .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            } finally {
                for (final Socket socket : releases) {
                    socket.getOutputStream().write(1);
                    socket.close();
                }
            }
            for (final CompletableFuture<HttpResponse<String>> response : admitted) {
                assertThat(response.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void ownershipCloseDrainsAnActiveGeneratedRequestBeforeStopping() throws Exception {
        try (ServerSocket entered = signalServer();
             ServerSocket release = signalServer();
             var child = started()) {
            final URI uri = child.awaitReady();
            final CompletableFuture<HttpResponse<String>> request = hold(child, uri, entered, release);
            acceptSignal(entered);
            try (Socket releaseConnection = release.accept()) {
                final Thread close = Thread.ofPlatform().start(() -> closeOwner(child));

                assertThat(child.process().isAlive()).isTrue();
                releaseConnection.getOutputStream().write(1);

                assertThat(request.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
                close.join(WAIT);
                assertThat(close.isAlive()).isFalse();
                assertThat(child.awaitExit()).isZero();
            }
        }
    }

    private DevelopmentRuntimeGeneratedProcess.Child started() throws IOException {
        return DevelopmentRuntimeGeneratedProcess.launch(build).token(TOKEN);
    }

    private static HttpResponse<String> request(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final String token,
            final String method,
            final String path,
            final String body,
            final String test
    ) throws IOException, InterruptedException {
        return child.request(uri, token, method, path, body, test);
    }

    private static CompletableFuture<HttpResponse<String>> hold(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final ServerSocket entered,
            final ServerSocket release
    ) {
        return child.requestAsync(
                uri,
                TOKEN,
                "/v1/run/hold",
                "{\"payload\":{\"entered_port\":" + entered.getLocalPort()
                        + ",\"release_port\":" + release.getLocalPort() + "}}"
        );
    }

    private static ServerSocket signalServer() throws IOException {
        return signalServer(50);
    }

    private static ServerSocket signalServer(final int backlog) throws IOException {
        final ServerSocket server = new ServerSocket(0, backlog, InetAddress.getLoopbackAddress());
        server.setSoTimeout((int) WAIT.toMillis());
        return server;
    }

    private static void acceptSignal(final ServerSocket server) throws IOException {
        try (Socket signal = server.accept()) {
            assertThat(signal.getInputStream().read()).isEqualTo(1);
        }
    }

    private static void closeOwner(final DevelopmentRuntimeGeneratedProcess.Child child) {
        try {
            child.closeOwner();
        } catch (final IOException exception) {
            throw new IllegalStateException("Ownership pipe did not close.", exception);
        }
    }

    private void assertStartupRejected(final String frame, final String message) throws Exception {
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(build)) {
            child.input(frame.getBytes(StandardCharsets.UTF_8));
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains(message);
        }
    }

    private static void assertRequestCancelledOrClosed(
            final CompletableFuture<HttpResponse<String>> request
    ) throws InterruptedException, TimeoutException {
        try {
            assertThat(request.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(409);
        } catch (final ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(IOException.class);
        }
    }

    private static void assertRequestSucceededOrClosed(
            final CompletableFuture<HttpResponse<String>> request
    ) throws InterruptedException, TimeoutException {
        try {
            assertThat(request.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
        } catch (final ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(IOException.class);
        }
    }

    private static boolean connect(final int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 250);
            return socket.isConnected();
        }
    }

    private static List<StepDefinition> definitions() {
        return List.of(
                trigger("success", false),
                trigger("details", false),
                trigger("rejected", true),
                trigger("failed", false),
                trigger("cancelled", false),
                trigger("nested-cancelled", false),
                trigger("side-effect", false),
                trigger("oversized", false),
                trigger("block", false),
                trigger("hold", false),
                StepDefinition.named("development.runtime.pass", "1")
                        .run(DevelopmentRuntimeConformanceSteps.Pass.class),
                StepDefinition.named("development.runtime.operation", "1")
                        .input("field", StepDefinition.Input.path(READ_WRITE))
                        .input("value", StepDefinition.Input.candidates(
                                StepDefinition.Input.option("current").fromParent("field")
                        ))
                        .input("operations", StepDefinition.Input.steps(
                                StepDefinition.ValueSource.from("value")
                        ))
                        .run(DevelopmentRuntimeConformanceSteps.Operation.class),
                StepDefinition.named("development.runtime.fault", "1")
                        .run(DevelopmentRuntimeConformanceSteps.Fault.class),
                StepDefinition.named("development.runtime.cancel", "1")
                        .run(DevelopmentRuntimeConformanceSteps.Cancel.class),
                StepDefinition.named("development.runtime.nested-cancel", "1")
                        .receive("value", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
                        .run(DevelopmentRuntimeConformanceSteps.Cancel.class),
                StepDefinition.named("development.runtime.append", "1")
                        .input("file", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "file"))
                        .run(DevelopmentRuntimeConformanceSteps.Append.class),
                StepDefinition.named("development.runtime.oversized", "1")
                        .input("target", StepDefinition.Input.path(READ_WRITE)
                                .defaultPath("context", "payload", "oversized"))
                        .run(DevelopmentRuntimeConformanceSteps.Oversized.class),
                StepDefinition.named("development.runtime.block", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .run(DevelopmentRuntimeConformanceSteps.Block.class),
                StepDefinition.named("development.runtime.hold", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .input("release_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "release_port"))
                        .run(DevelopmentRuntimeConformanceSteps.Hold.class)
        );
    }

    private static StepDefinition trigger(final String id, final boolean requiredResult) {
        final StepDefinition.Builder definition = StepDefinition.named("development.runtime.trigger." + id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("development.runtime.source." + id);
        if (requiredResult) {
            definition.requiredResult("result", ValueShape.ANY);
        }
        return definition.run(DevelopmentRuntimeConformanceSteps.Pass.class);
    }

    private static String project() {
        return """
                {"format":1,"id":"development-runtime-conformance","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  {"id":"success-step","use":"development.runtime.pass","inputs":{}},
                  {"id":"details-step","use":"development.runtime.operation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "operations":[
                      {"use":"text.trim","inputs":{}},
                      {"use":"text.lowercase","inputs":{}}
                    ]
                  }},
                  {"id":"failed-step","use":"development.runtime.fault","inputs":{}},
                  {"id":"cancelled-step","use":"development.runtime.cancel","inputs":{}},
                  {"id":"nested-cancelled-step","use":"development.runtime.operation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "operations":[{"use":"development.runtime.nested-cancel","inputs":{}}]
                  }},
                  {"id":"side-effect-step","use":"development.runtime.append","inputs":{}},
                  {"id":"oversized-step","use":"development.runtime.oversized","inputs":{}},
                  {"id":"block-step","use":"development.runtime.block","inputs":{}},
                  {"id":"hold-step","use":"development.runtime.hold","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"success"},
                  {"from":"success.next","to":"success-step"},
                  {"from":"success-step.next","to":"end"},
                  {"from":"app.start","to":"details"},
                  {"from":"details.next","to":"details-step"},
                  {"from":"details-step.next","to":"end"},
                  {"from":"app.start","to":"rejected"},
                  {"from":"rejected.next","to":"end"},
                  {"from":"app.start","to":"failed"},
                  {"from":"failed.next","to":"failed-step"},
                  {"from":"failed-step.next","to":"end"},
                  {"from":"app.start","to":"cancelled"},
                  {"from":"cancelled.next","to":"cancelled-step"},
                  {"from":"cancelled-step.next","to":"end"},
                  {"from":"app.start","to":"nested-cancelled"},
                  {"from":"nested-cancelled.next","to":"nested-cancelled-step"},
                  {"from":"nested-cancelled-step.next","to":"end"},
                  {"from":"app.start","to":"side-effect"},
                  {"from":"side-effect.next","to":"side-effect-step"},
                  {"from":"side-effect-step.next","to":"end"},
                  {"from":"app.start","to":"oversized"},
                  {"from":"oversized.next","to":"oversized-step"},
                  {"from":"oversized-step.next","to":"end"},
                  {"from":"app.start","to":"block"},
                  {"from":"block.next","to":"block-step"},
                  {"from":"block-step.next","to":"end"},
                  {"from":"app.start","to":"hold"},
                  {"from":"hold.next","to":"hold-step"},
                  {"from":"hold-step.next","to":"end"}
                ]}
                """.formatted(
                triggerNode("success", "{}"),
                triggerNode("details", "{\"value\":\" Hello RAILIX \"}"),
                triggerNode("rejected", "{}"),
                triggerNode("failed", "{}"),
                triggerNode("cancelled", "{}"),
                triggerNode("nested-cancelled", "{\"value\":\"input\"}"),
                triggerNode("side-effect", "{\"file\":\"observed.txt\"}"),
                triggerNode("oversized", "{}"),
                triggerNode("block", "{\"entered_port\":1}"),
                triggerNode("hold", "{\"entered_port\":1,\"release_port\":2}")
        );
    }

    private static String escaped(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String triggerNode(final String id, final String payload) {
        return """
                {"id":"%1$s","use":"development.runtime.trigger.%1$s","inputs":{},"examples":[
                  {"name":"default","payload":%2$s}
                ]}
                """.formatted(id, payload);
    }
}
