package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.development.ExampleSuiteTestAccess;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.ChunkGateStepHandler;
import thirdparty.conformance.DevelopmentRuntimeConformanceSteps;
import thirdparty.conformance.ProcessTreeStepHandler;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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
    private static final int TRACE_ADMISSION_LIMIT = 16;
    private static final int BODY_ADMISSION_LIMIT = ADMISSION_LIMIT + TRACE_ADMISSION_LIMIT;
    private static final int UNAUTHORIZED_BODY_ADMISSION_LIMIT = 4;
    private static final int CANCELLATION_ADMISSION_LIMIT = 16;
    private static final int METRIC_CARDINALITY_STEPS = 4_096;
    private static final String TOKEN = "development-runtime-token";
    private static final Duration WAIT = Duration.ofSeconds(20);

    private DevelopmentRuntimeGeneratedProcess.Build build;
    private DevelopmentRuntimeGeneratedProcess.Child shared;
    private URI sharedUri;

    @BeforeAll
    void buildAndStartGeneratedApplication(@TempDir final Path workspace) throws Exception {
        build = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                project(workspace),
                definitions(),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                DevelopmentRuntimeConformanceSteps.Operation.class,
                DevelopmentRuntimeConformanceSteps.Fault.class,
                DevelopmentRuntimeConformanceSteps.Cancel.class,
                DevelopmentRuntimeConformanceSteps.Append.class,
                DevelopmentRuntimeConformanceSteps.Oversized.class,
                DevelopmentRuntimeConformanceSteps.OversizedTrace.class,
                DevelopmentRuntimeConformanceSteps.Block.class,
                DevelopmentRuntimeConformanceSteps.IgnoreInterrupt.class,
                DevelopmentRuntimeConformanceSteps.DelayedIgnoreInterrupt.class,
                DevelopmentRuntimeConformanceSteps.Hold.class,
                ProcessTreeStepHandler.class,
                DevelopmentRuntimeConformanceSteps.NullApplicationMain.class,
                DevelopmentRuntimeConformanceSteps.ProcessTreeMain.class,
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
    void automaticExamplesRemainDormantUntilTheCreatorActivatesTheApplication(@TempDir final Path workspace)
            throws Exception {
        final Path marker = workspace.resolve("example.marker");
        final DevelopmentRuntimeGeneratedProcess.Build candidate = markerBuild(workspace, marker);

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(candidate).token(TOKEN)) {
            final URI uri = child.awaitInactive();
            final HttpResponse<String> response = request(
                    child,
                    uri,
                    TOKEN,
                    "GET",
                    "/v1/examples/status",
                    "",
                    null
            );
            final RailixValue.ObjectValue status = object(response.body());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(string(status, "state")).isEqualTo("queued");
            assertThat(number(status, "completed")).isZero();
            assertThat(marker).doesNotExist();

            child.activate();
            awaitContent(marker, "x", WAIT);
        }
    }

    @Test
    void ownerClosureBeforeActivationStopsWithoutExecutingExamples(@TempDir final Path workspace)
            throws Exception {
        final Path marker = workspace.resolve("example.marker");
        final DevelopmentRuntimeGeneratedProcess.Build candidate = markerBuild(workspace, marker);

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(candidate).token(TOKEN)) {
            child.awaitInactive();
            final Path runtime = runtimeDirectory(candidate, child);
            child.closeOwner();

            assertThat(child.awaitExit()).isZero();
            assertThat(runtime).doesNotExist();
            assertThat(marker).doesNotExist();
        }

        try (var recovered = DevelopmentRuntimeGeneratedProcess.launch(candidate).token(TOKEN)) {
            recovered.awaitReady();
            awaitSignal(marker, WAIT);
        }
    }

    @Test
    void invalidActivationTokenIsRejectedBeforeExamplesExecute(@TempDir final Path workspace) throws Exception {
        final Path marker = workspace.resolve("example.marker");
        final DevelopmentRuntimeGeneratedProcess.Build candidate = markerBuild(workspace, marker);

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(candidate).token(TOKEN)) {
            child.awaitInactive();
            final Path runtime = runtimeDirectory(candidate, child);
            child.input("ACTIVATE invalid-token\n".getBytes(StandardCharsets.UTF_8));

            assertThat(child.awaitExit()).isEqualTo(2);
            assertThat(child.error()).contains("Creator activation frame is invalid.");
            assertThat(runtime).doesNotExist();
            assertThat(marker).doesNotExist();
        }

        try (var recovered = DevelopmentRuntimeGeneratedProcess.launch(candidate).token(TOKEN)) {
            recovered.awaitReady();
            awaitSignal(marker, WAIT);
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
    void developmentArtifactWithoutCompiledExamplesFailsBeforeReadiness(@TempDir final Path workspace)
            throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build corrupt = withoutExampleManifest(build, workspace);
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(corrupt).token(TOKEN)) {
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(3);
            assertThat(child.error()).contains("Compiled development Example manifest is missing.");
        }
    }

    @Test
    void oversizedCompiledExampleManifestFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                " ".repeat(4_194_305).getBytes(StandardCharsets.UTF_8),
                "Compiled development Examples exceed 4194304 bytes."
        );
    }

    @Test
    void malformedCompiledExampleJsonFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, "{", "Compiled development Examples are invalid.");
    }

    @Test
    void compiledExampleArrayRootFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, "[]", "Compiled development Examples are invalid.");
    }

    @Test
    void compiledExampleFormatMustBeNumeric(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":\"1\",\"node_count\":1,\"examples\":[]}",
                "Compiled development Examples are invalid."
        );
    }

    @Test
    void compiledExampleNodeCountMustBeNumeric(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":\"1\",\"examples\":[]}",
                "Compiled development Examples are invalid."
        );
    }

    @Test
    void compiledExamplesMustBeAnArray(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":1,\"examples\":{}}",
                "Compiled development Examples are invalid."
        );
    }

    @Test
    void unsupportedCompiledExampleFormatFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":2,\"node_count\":1,\"examples\":[]}",
                "Compiled development Example format is unsupported."
        );
    }

    @Test
    void fractionalCompiledExampleNodeCountFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":0.5,\"examples\":[]}",
                "Compiled development Example node count is invalid."
        );
    }

    @Test
    void negativeCompiledExampleNodeCountFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":-1,\"examples\":[]}",
                "Compiled development Example node count is invalid."
        );
    }

    @Test
    void compiledExampleEntryMustBeAnObject(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":1,\"examples\":[null]}",
                "Compiled development Example is invalid."
        );
    }

    @Test
    void compiledExampleContextMustBeAnObject(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("context", "[]"),
                "Compiled development Example is invalid.");
    }

    @Test
    void compiledExampleIdentifierMustBeAString(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("id", "1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void compiledExampleTriggerMustBeAString(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("trigger", "1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void compiledExampleNameMustBeAString(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("name", "1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void compiledExampleIndexMustBeNumeric(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("index", "\"0\""),
                "Compiled development Example is invalid.");
    }

    @Test
    void compiledExampleNodeMustBeNumeric(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("node", "\"0\""),
                "Compiled development Example is invalid.");
    }

    @Test
    void blankCompiledExampleIdentifierFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("id", "\"\""),
                "Compiled development Example is invalid.");
    }

    @Test
    void blankCompiledExampleTriggerFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("trigger", "\"\""),
                "Compiled development Example is invalid.");
    }

    @Test
    void negativeCompiledExampleIndexFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("index", "-1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void negativeCompiledExampleNodeFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("node", "-1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void outOfRangeCompiledExampleNodeFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("node", "1"),
                "Compiled development Example is invalid.");
    }

    @Test
    void duplicateCompiledExampleIdentifierFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        final String example = validExample();
        assertInvalidExampleManifest(
                workspace,
                "{\"format\":1,\"node_count\":1,\"examples\":[" + example + "," + example + "]}",
                "Compiled development Example is invalid."
        );
    }

    @Test
    void fractionalCompiledExampleIndexFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("index", "0.5"),
                "Compiled development Example is invalid.");
    }

    @Test
    void fractionalCompiledExampleNodeFailsBeforeReadiness(@TempDir final Path workspace) throws Exception {
        assertInvalidExampleManifest(workspace, exampleManifest("node", "0.5"),
                "Compiled development Example is invalid.");
    }

    @Test
    void nonCooperativeExampleTerminatesItsGeneratedProcessAfterTheGracePeriod(
            @TempDir final Path workspace
    ) throws Exception {
        final Path gate = workspace.resolve("gate");
        final DevelopmentRuntimeGeneratedProcess.Build timeoutBuild = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                nonCooperativeExample(gate),
                List.of(trigger("noncooperative", false), exampleGate()),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                ChunkGateStepHandler.class
        );

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(timeoutBuild).token(TOKEN)) {
            child.awaitReady();
            awaitSignal(gate.resolve("case-1.started"), Duration.ofSeconds(10));
            awaitSignal(gate.resolve("case-1.interrupted"), Duration.ofSeconds(40));
            awaitStopped(child.process(), Duration.ofSeconds(20));

            assertThat(child.awaitExit()).isEqualTo(6);
            assertThat(child.process().isAlive()).isFalse();
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void nonCooperativeHttpWorkerFailsGeneratedProcessShutdown(
            @TempDir final Path workspace
    ) throws Exception {
        final Path gate = workspace.resolve("http-gate");
        final Path release = gate.resolve("release");
        Files.createDirectories(gate);
        Files.writeString(release, "release", StandardCharsets.UTF_8);
        final DevelopmentRuntimeGeneratedProcess.Build httpBuild = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                nonCooperativeRun(gate),
                List.of(trigger("noncooperative-http", false), exampleGate()),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                ChunkGateStepHandler.class
        );
        CompletableFuture<HttpResponse<String>> request = null;

        try (var child = DevelopmentRuntimeGeneratedProcess.launch(httpBuild).token(TOKEN)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 1, WAIT);
            Files.delete(release);
            request = child.requestAsync(
                    uri,
                    TOKEN,
                    "POST",
                    "/v1/run/noncooperative-http",
                    "{\"payload\":{\"case\":\"http-run\",\"root\":\""
                            + escaped(gate.toString()) + "\"}}",
                    "false"
            );
            awaitSignal(gate.resolve("http-run.started"), Duration.ofSeconds(10));

            child.closeOwner();

            awaitSignal(gate.resolve("http-run.interrupted"), Duration.ofSeconds(20));
            awaitStopped(child.process(), Duration.ofSeconds(20));
            assertThat(child.awaitExit()).isEqualTo(5);
            assertThat(child.error()).contains("Development HTTP workers did not stop within one second.");
        } finally {
            if (request != null) {
                request.cancel(true);
            }
            Files.createDirectories(gate);
            Files.writeString(release, "release", StandardCharsets.UTF_8);
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
    void generatedApplicationOwnsAndRunsItsCompiledExamples() throws Exception {
        final String snapshot = awaitExample(shared, sharedUri, "success:0", "succeeded");

        assertThat(snapshot)
                .contains("\"application_pid\":" + shared.process().pid())
                .contains("\"id\":\"success:0\"")
                .contains("\"trigger\":\"success\"")
                .contains("\"name\":\"default\"");
    }

    @Test
    void generatedApplicationServesCompactExampleStatusWithoutTheCaseInventory() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/status", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"state\":\"completed\"")
                .contains("\"revision\":")
                .contains("\"total\":")
                .doesNotContain("\"cases\"")
                .doesNotContain("\"coverage_bits\"")
                .doesNotContain("\"covered_steps\"")
                .doesNotContain("\"id\":\"success:0\"");
    }

    @Test
    void generatedApplicationServesExampleCoverageSeparatelyFromPollingStatus() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/coverage", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"application_pid\":" + shared.process().pid())
                .contains("\"revision\":")
                .contains("\"covered_steps\":")
                .contains("\"coverage_bits\":")
                .doesNotContain("\"cases\"")
                .doesNotContain("\"storage_bytes\"");
    }

    @Test
    void generatedApplicationMetricsIdentifyTheirOwningProcess() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/application", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"application_pid\":" + shared.process().pid());
    }

    @Test
    void generatedApplicationServesOneSelectedExampleWithoutTheSuiteInventory() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/success%3A0", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"success:0\"")
                .contains("\"status\":\"succeeded\"")
                .doesNotContain("\"cases\"")
                .doesNotContain("\"coverage_bits\"");
    }

    @Test
    void generatedApplicationServesItsCompletedExampleTrace() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/success%3A0/view", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("application/json; charset=utf-8");
        assertThat(response.body())
                .contains("\"initial_context\"")
                .contains("\"nodes\":[15]")
                .contains("\"result\":{\"context\"")
                .contains("\"status\":\"succeeded\"")
                .doesNotContain("\"type\"");
    }

    @Test
    void generatedApplicationProjectsOneStepFromItsCompletedExampleTrace() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/success%3A0/steps/15", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"success-step\"")
                .contains("\"input_context\"")
                .contains("\"context\"")
                .contains("\"stages\":[]")
                .doesNotContain("\"changes\"")
                .doesNotContain("\"type\"");
    }

    @Test
    void generatedApplicationProjectsEveryNestedStageFromItsCompiledExample() throws Exception {
        awaitExample(shared, sharedUri, "details:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/details%3A0/steps/16", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"details-step\"")
                .contains("\"input\":\"operations\"")
                .contains("\"invocation\":\"text.trim\"")
                .contains("\"invocation\":\"text.lowercase\"")
                .contains("\"value\":\"hello railix\"")
                .doesNotContain("\"type\"");
    }

    @Test
    void generatedApplicationProjectsTheFailedStepOutcomeFromItsCompiledExample() throws Exception {
        awaitExample(shared, sharedUri, "failed:0", "failed");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/failed%3A0/steps/17", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"failed-step\"")
                .contains("\"status\":\"failed\"")
                .contains("\"input_context\"")
                .contains("\"context\"")
                .doesNotContain("\"code\"")
                .doesNotContain("\"type\"");
    }

    @Test
    void generatedApplicationProjectsAllExamplesForOneSelectedStep() throws Exception {
        awaitExample(shared, sharedUri, "success:0", "succeeded");

        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/examples/steps/15", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"node\":15")
                .contains("\"id\":\"success:0\"")
                .contains("\"trigger\":\"success\"")
                .contains("\"initial_context\"")
                .contains("\"projection\":{")
                .contains("\"id\":\"success-step\"")
                .doesNotContain("\"type\"")
                .doesNotContain("\"changes\"");
    }

    @Test
    void unknownCompiledExampleIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/unknown");
    }

    @Test
    void siblingExamplesPrefixIsNotRoutedAsAnExampleEndpoint() throws Exception {
        assertExampleRouteNotFound("/v1/examples-other");
    }

    @Test
    void unknownCompiledExampleViewIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/unknown/view");
    }

    @Test
    void unknownCompiledExampleStepIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/unknown/steps/15");
    }

    @Test
    void compiledExampleStepOutsideTheGraphIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/success%3A0/steps/999");
    }

    @Test
    void negativeCompiledExampleStepIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/success%3A0/steps/-1");
    }

    @Test
    void blankCompiledExampleStepIdentifierIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples//steps/0");
    }

    @Test
    void malformedCompiledExampleStepIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/success%3A0/steps/not-a-number");
    }

    @Test
    void aggregateExampleStepOutsideTheGraphIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/steps/999");
    }

    @Test
    void negativeAggregateExampleStepIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/steps/-1");
    }

    @Test
    void malformedAggregateExampleStepIsNotFound() throws Exception {
        assertExampleRouteNotFound("/v1/examples/steps/not-a-number");
    }

    @Test
    void compiledExampleTraceStopsAtItsSixtyFourMebibyteStorageLimit(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build bounded = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                largeTraceExamples("bounded-trace", 1, 40),
                StandardLibrary.catalog()
        );
        try (var child = started(bounded)) {
            final URI uri = child.awaitReady();
            final String snapshot = awaitExamples(child, uri, 1, Duration.ofSeconds(60));
            final RailixValue.ObjectValue summary = object(snapshot);
            final RailixValue.ObjectValue example = values(snapshot, "cases").getFirst();
            final Path trace = runtimeDirectory(bounded, child).resolve("0.ndjson");
            final TraceEvidence evidence = traceEvidence(trace);

            assertThat(number(summary, "failed")).isEqualTo(1);
            assertThat(string(example, "status")).isEqualTo("failed");
            assertThat(string(example, "message"))
                    .isEqualTo("Example trace exceeded the 64 MiB storage limit.");
            assertThat(number(example, "storage_bytes"))
                    .isGreaterThan(60L * 1_024 * 1_024)
                    .isLessThanOrEqualTo(ExampleSuiteTestAccess.MAX_EXAMPLE_BYTES);
            assertThat(number(summary, "storage_bytes")).isEqualTo(number(example, "storage_bytes"));
            assertThat(Files.size(trace)).isEqualTo(number(example, "storage_bytes"));
            assertThat(evidence.terminals()).isEqualTo(1);
            assertThat(evidence.last()).contains(
                    "\"code\":\"TRACE_CASE_STORAGE_LIMIT\"",
                    "\"message\":\"Example trace exceeded the 64 MiB storage limit.\"",
                    "\"type\":\"trace_error\""
            );
        }
    }

    @Test
    void concurrentCompiledExampleTracesStopAtTheAggregateStorageLimit(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build bounded = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                aggregateStorageExamples(),
                List.of(aggregateStorageStep()),
                DevelopmentRuntimeConformanceSteps.Oversized.class
        );
        try (var child = started(bounded)) {
            final URI uri = child.awaitReady();
            final String snapshot = awaitExamples(child, uri, 5, Duration.ofSeconds(120));
            final RailixValue.ObjectValue summary = object(snapshot);
            final List<RailixValue.ObjectValue> cases = values(snapshot, "cases");
            final Path traces = runtimeDirectory(bounded, child);
            final List<Path> files;
            try (var paths = Files.list(traces)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".ndjson"))
                        .toList();
            }
            final long physicalBytes = files.stream()
                    .mapToLong(DevelopmentRuntimeGeneratedApplicationE2eTest::size)
                    .sum();

            assertThat(files).hasSize(5);
            assertThat(number(summary, "storage_bytes"))
                    .isGreaterThan(240L * 1_024 * 1_024)
                    .isLessThanOrEqualTo(ExampleSuiteTestAccess.MAX_SUITE_BYTES);
            assertThat(physicalBytes)
                    .isEqualTo(number(summary, "storage_bytes"))
                    .isLessThanOrEqualTo(ExampleSuiteTestAccess.MAX_SUITE_BYTES);
            assertThat(number(summary, "failed")).isPositive();
            assertThat(cases.stream()
                    .map(value -> value.values().get("message"))
                    .filter(RailixValue.StringValue.class::isInstance)
                    .map(RailixValue.StringValue.class::cast)
                    .map(RailixValue.StringValue::value))
                    .contains("Example traces exceeded the 256 MiB suite storage limit.");
            assertThat(cases).allSatisfy(example -> assertThat(number(example, "storage_bytes"))
                    .isLessThanOrEqualTo(ExampleSuiteTestAccess.MAX_EXAMPLE_BYTES));
            assertThat(files).allSatisfy(file -> {
                final TraceEvidence evidence = traceEvidence(file);
                assertThat(evidence.terminals()).isEqualTo(1);
                assertThat(evidence.last()).containsAnyOf(
                        "\"type\":\"result\"",
                        "\"type\":\"trace_error\""
                );
            });
            assertThat(files.stream().map(DevelopmentRuntimeGeneratedApplicationE2eTest::traceEvidence)
                    .map(TraceEvidence::last))
                    .anyMatch(line -> line.contains("\"code\":\"TRACE_SUITE_STORAGE_LIMIT\""));
        }
    }

    @Test
    void aggregateStepReplayRejectsRetainedExampleTracesAboveSixtyFourMebibytes(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build bounded = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                largeTraceExamples("bounded-replay", 2, 28),
                StandardLibrary.catalog()
        );
        try (var child = started(bounded)) {
            final URI uri = child.awaitReady();
            final String snapshot = awaitExamples(child, uri, 2, Duration.ofSeconds(60));
            final RailixValue.ObjectValue summary = object(snapshot);

            assertThat(number(summary, "storage_bytes")).isGreaterThan(64L * 1_024 * 1_024);
            assertThat(values(snapshot, "cases")).allSatisfy(example ->
                    assertThat(number(example, "storage_bytes"))
                            .isLessThanOrEqualTo(ExampleSuiteTestAccess.MAX_EXAMPLE_BYTES));

            assertThat(request(
                    child, uri, TOKEN, "GET", "/v1/examples/steps/2", "", null
            )).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(413, "{\"diagnostics\":[{"
                            + "\"code\":\"EXAMPLE_STEP_REPLAY_TOO_LARGE\","
                            + "\"message\":\"Application Step Example replay exceeds the 67108864-byte limit.\","
                            + "\"path\":\"\"}],\"status\":\"rejected\"}");
        }
    }

    @Test
    void aggregateStepProjectionRejectsAResponseAboveSixteenMebibytes(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build bounded = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                oversizedTraceExamples("bounded-projection", 8, 0),
                List.of(aggregateStorageStep()),
                DevelopmentRuntimeConformanceSteps.Oversized.class
        );
        try (var child = started(bounded)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 8, Duration.ofSeconds(60));

            assertThat(request(
                    child, uri, TOKEN, "GET", "/v1/examples/steps/2", "", null
            )).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(413, "{\"diagnostics\":[{"
                            + "\"code\":\"EXAMPLE_STEP_PROJECTION_TOO_LARGE\","
                            + "\"message\":\"Application Step Example projection exceeds the 16777216-byte limit.\","
                            + "\"path\":\"\"}],\"status\":\"rejected\"}");
        }
    }

    @Test
    void aggregateProjectionLimitReleasesExampleReadAdmission(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build bounded = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                oversizedTraceExamples("projection-admission", 8, 0),
                List.of(aggregateStorageStep()),
                DevelopmentRuntimeConformanceSteps.Oversized.class
        );
        try (var child = started(bounded)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 8, Duration.ofSeconds(60));

            assertThat(request(
                    child, uri, TOKEN, "GET", "/v1/examples/steps/2", "", null
            ).statusCode()).isEqualTo(413);
            assertThat(request(
                    child, uri, TOKEN, "GET", "/v1/examples/status", "", null
            ).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void missingRetainedExampleTraceReturnsExplicitUnavailable(@TempDir final Path workspace) throws Exception {
        final Path marker = workspace.resolve("example.marker");
        final DevelopmentRuntimeGeneratedProcess.Build candidate = markerBuild(workspace, marker);

        try (var child = started(candidate)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 1, Duration.ofSeconds(60));
            Files.delete(runtimeDirectory(candidate, child).resolve("0.ndjson"));

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(request(
                        child, uri, TOKEN, "GET", "/v1/examples/steps/2", "", null
                )).extracting(HttpResponse::statusCode, HttpResponse::body)
                        .containsExactly(503, "{\"reason\":\"trace\",\"status\":\"unavailable\"}");
            }
        }
    }

    @Test
    void slowProjectionReaderRetainsSingleProjectionAllocationUntilTransportEnds(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build projected = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                largeTraceExamples("projection-owner-close", 1, 34),
                StandardLibrary.catalog()
        );
        try (var child = started(projected)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 1, Duration.ofSeconds(60));
            final Path runtime = runtimeDirectory(projected, child);
            final int port = uri.getPort();
            final String path = "/v1/examples/steps/2";
            final HttpResponse<InputStream> projection = child.requestStreamAsync(
                    uri, TOKEN, "GET", path, "", null
            ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            try (InputStream body = projection.body()) {
                assertThat(projection.statusCode()).isEqualTo(200);
                assertThat(projection.headers().firstValueAsLong("Content-Length").orElseThrow())
                        .isGreaterThan(1_048_576);

                assertThat(request(
                        child, uri, TOKEN, "GET", path, "", null
                )).extracting(HttpResponse::statusCode, HttpResponse::body)
                        .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            }

            assertThat(awaitManagementReadAvailable(child, uri, path).statusCode()).isEqualTo(200);
            child.closeOwner();
            assertThat(child.awaitExit()).isZero();
            assertThat(child.process().isAlive()).isFalse();
            assertThat(runtime).doesNotExist();
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.setReuseAddress(true);
                replacement.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                assertThat(replacement.isBound()).isTrue();
            }
        }
    }

    @Test
    void thirdExampleReadIsRejectedWhileTwoLargeResponsesAreBackpressured(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build projected = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                expandingContextExample(7),
                List.of(aggregateStorageStep()),
                DevelopmentRuntimeConformanceSteps.Oversized.class
        );
        try (var child = started(projected)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 1, Duration.ofSeconds(60));
            try (Socket aggregate = managementGet(uri, "/v1/examples/steps/8")) {
                assertThat(readHeaders(aggregate.getInputStream())).startsWith("HTTP/1.1 200");
                try (Socket view = managementGet(uri, "/v1/examples/command%3A0/steps/8")) {
                    assertThat(readHeaders(view.getInputStream())).startsWith("HTTP/1.1 200");
                    assertThat(request(
                            child, uri, TOKEN, "GET", "/v1/examples/status", "", null
                    )).extracting(HttpResponse::statusCode, HttpResponse::body)
                            .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
                }
            }

            assertThat(awaitManagementReadAvailable(
                    child, uri, "/v1/examples/status"
            ).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void ownershipCloseReclaimsTwoBackpressuredExampleResponses(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build projected = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                expandingContextExample(7),
                List.of(aggregateStorageStep()),
                DevelopmentRuntimeConformanceSteps.Oversized.class
        );
        try (var child = started(projected)) {
            final URI uri = child.awaitReady();
            awaitExamples(child, uri, 1, Duration.ofSeconds(60));
            try (Socket aggregate = managementGet(uri, "/v1/examples/steps/8")) {
                assertThat(readHeaders(aggregate.getInputStream())).startsWith("HTTP/1.1 200");
                try (Socket view = managementGet(uri, "/v1/examples/command%3A0/steps/8")) {
                    assertThat(readHeaders(view.getInputStream())).startsWith("HTTP/1.1 200");

                    child.closeOwner();

                    assertThat(child.awaitExit()).isZero();
                    assertThat(child.process().isAlive()).isFalse();
                }
            }
        }
    }

    @Test
    void generatedApplicationRejectsExampleReadsWithoutItsManagementToken() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, null, "GET", "/v1/examples", "", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void generatedApplicationExampleEndpointIsReadOnly() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/examples", "{}", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void missingTraceAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, null, "POST", "/v1/trace/details", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void missingTraceExecutionIdentifierIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = shared.requestAsync(
                sharedUri,
                TOKEN,
                "/v1/trace/success",
                "{}",
                null
        ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains("\"code\":\"TRACE_ID_INVALID\"")
                .contains("\"path\":\"X-Railix-Trace-Id\"");
    }

    @Test
    void invalidTraceExecutionIdentifierIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = shared.requestAsync(
                sharedUri,
                TOKEN,
                "/v1/trace/success",
                "{}",
                "trace/id"
        ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"TRACE_ID_INVALID\"");
    }

    @Test
    void traceExecutionIdentifierAboveThePortableAsciiRangeIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = shared.requestAsync(
                sharedUri,
                TOKEN,
                "/v1/trace/success",
                "{}",
                "trace{id"
        ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"TRACE_ID_INVALID\"");
    }

    @Test
    void oversizedTraceExecutionIdentifierIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = shared.requestAsync(
                sharedUri,
                TOKEN,
                "/v1/trace/success",
                "{}",
                "x".repeat(129)
        ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"TRACE_ID_INVALID\"");
    }

    @Test
    void traceIdentifierAcceptsEveryDocumentedPortableCharacter() throws Exception {
        final HttpResponse<String> response = shared.requestAsync(
                sharedUri,
                TOKEN,
                "/v1/trace/success",
                "{}",
                "Trace_ID.09-"
        ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void missingTraceCancellationAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                null,
                "DELETE",
                "/v1/traces/not-active",
                "",
                null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void unsupportedTraceCancellationMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/traces/not-active",
                "{}",
                null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void unknownTraceCancellationIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "DELETE",
                "/v1/traces/not-active",
                "",
                null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void blankTraceCancellationIdentifierIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "DELETE",
                "/v1/traces/",
                "",
                null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void invalidTraceCancellationIdentifierIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "DELETE",
                "/v1/traces/trace/id",
                "",
                null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void missingMetricsAuthorizationIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, null, "GET", "/v1/metrics", "", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void unsupportedMetricsMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/metrics", "{}", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void unknownMetricsFormatIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/unknown", "", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void applicationMetricsEndpointOmitsPerFlowAndPerStepSeries() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/application", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"flows\":[]", "\"steps\":[]", "\"process\":");
    }

    @Test
    void nodeMetricsEndpointReturnsOnlyMatchingFlowAndStepSeries() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/nodes/success", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"flows\":[{\"id\":\"success\"", "\"steps\":[{\"id\":\"success\"")
                .doesNotContain("\"id\":\"details\"");
    }

    @Test
    void blankNodeMetricsPathIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/nodes/", "", null
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void thirdMetricsReadIsRejectedWhileTwoLargeStreamsAreBackpressured(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build metrics = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                metricCardinalityProject(METRIC_CARDINALITY_STEPS),
                List.of(trigger("metrics", false), pass()),
                DevelopmentRuntimeConformanceSteps.Pass.class
        );
        try (var child = started(metrics)) {
            final URI uri = child.awaitReady();
            try (Socket first = managementGet(uri, "/v1/metrics/prometheus")) {
                assertThat(readHeaders(first.getInputStream())).startsWith("HTTP/1.1 200");
                try (Socket second = managementGet(uri, "/v1/metrics/prometheus")) {
                    assertThat(readHeaders(second.getInputStream())).startsWith("HTTP/1.1 200");
                    assertThat(request(
                            child, uri, TOKEN, "GET", "/v1/metrics/application", "", null
                    )).extracting(HttpResponse::statusCode, HttpResponse::body)
                            .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
                }
            }

            assertThat(awaitManagementReadAvailable(
                    child, uri, "/v1/metrics/application"
            ).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void ownershipCloseReclaimsTwoBackpressuredMetricResponses(
            @TempDir final Path workspace
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build metrics = DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                metricCardinalityProject(METRIC_CARDINALITY_STEPS),
                List.of(trigger("metrics", false), pass()),
                DevelopmentRuntimeConformanceSteps.Pass.class
        );
        try (var child = started(metrics)) {
            final URI uri = child.awaitReady();
            try (Socket first = managementGet(uri, "/v1/metrics/prometheus")) {
                assertThat(readHeaders(first.getInputStream())).startsWith("HTTP/1.1 200");
                try (Socket second = managementGet(uri, "/v1/metrics/prometheus")) {
                    assertThat(readHeaders(second.getInputStream())).startsWith("HTTP/1.1 200");

                    child.closeOwner();

                    assertThat(child.awaitExit()).isZero();
                    assertThat(child.process().isAlive()).isFalse();
                }
            }
        }
    }

    @Test
    void automaticExamplesDoNotChangeOperationalMetrics() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            awaitAutomaticExamples(child, uri);

            assertExecutions(child, uri, 0);
        }
    }

    @Test
    void explicitTestRunDoesNotChangeOperationalMetrics() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            awaitAutomaticExamples(child, uri);
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true").statusCode())
                    .isEqualTo(200);

            assertExecutions(child, uri, 0);
        }
    }

    @Test
    void developmentTraceDoesNotChangeOperationalMetrics() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            awaitAutomaticExamples(child, uri);
            assertThat(request(child, uri, TOKEN, "POST", "/v1/trace/success", "{}", "false").statusCode())
                    .isEqualTo(200);

            assertExecutions(child, uri, 0);
        }
    }

    @Test
    void productionRunChangesOperationalMetricsExactlyOnce() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            awaitAutomaticExamples(child, uri);
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "false").statusCode())
                    .isEqualTo(200);

            assertExecutions(child, uri, 1);
        }
    }

    @Test
    void metricEndpointsExposeExactExecutionsAndSampledStepTimingWithoutStepInflight() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            for (int execution = 0; execution < 1_025; execution++) {
                assertThat(request(
                        child,
                        uri,
                        TOKEN,
                        "POST",
                        "/v1/run/success",
                        "{}",
                        "false"
                ).statusCode()).isEqualTo(200);
            }

            final String json = request(child, uri, TOKEN, "GET", "/v1/metrics", "", null).body();
            final RailixValue.ObjectValue step = series(json, "steps", "success-step");
            final String prometheus = request(
                    child,
                    uri,
                    TOKEN,
                    "GET",
                    "/v1/metrics/prometheus",
                    "",
                    null
            ).body();
            final String influx = request(
                    child,
                    uri,
                    TOKEN,
                    "GET",
                    "/v1/metrics/influx",
                    "",
                    null
            ).body();

            assertThat(metric(json, "steps", "success-step", "executions")).isEqualTo(1_025);
            assertThat(metric(json, "steps", "success-step", "duration_samples")).isEqualTo(2);
            assertThat(((RailixValue.ObjectValue) step.values().get("metrics")).values())
                    .doesNotContainKey("in_flight");
            assertThat(prometheus)
                    .contains(
                            "railix_step_executions_total{project=\"development-runtime-conformance\","
                                    + "step=\"success-step\"} 1025",
                            "railix_step_duration_seconds_count{project=\"development-runtime-conformance\","
                                    + "step=\"success-step\"} 2"
                    )
                    .doesNotContain("railix_step_in_flight");
            assertThat(influx)
                    .contains(
                            "railix_step,project=development-runtime-conformance,step=success-step ",
                            "executions=1025i",
                            "duration_samples=2i"
                    )
                    .doesNotContain("railix_step_in_flight");
        }
    }

    @Test
    void cancelledRunIsClassifiedWithoutCountingAnError() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            assertThat(request(child, uri, TOKEN, "POST", "/v1/run/cancelled", "{}", "false").statusCode())
                    .isEqualTo(409);

            final String metrics = request(child, uri, TOKEN, "GET", "/v1/metrics", "", null).body();

            assertThat(metric(metrics, "flows", "cancelled", "cancelled")).isEqualTo(1);
            assertThat(metric(metrics, "flows", "cancelled", "errors")).isZero();
            assertThat(metric(metrics, "steps", "cancelled-step", "cancelled")).isEqualTo(1);
            assertThat(metric(metrics, "steps", "cancelled-step", "errors")).isZero();
        }
    }

    @Test
    void disabledStepIsAbsentFromMetricsAfterRealExecution() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            assertThat(request(
                    child,
                    uri,
                    TOKEN,
                    "POST",
                    "/v1/run/details",
                    "{\"payload\":{\"value\":\" RAILIX \"}}",
                    "false"
            ).statusCode()).isEqualTo(200);

            final String metrics = request(child, uri, TOKEN, "GET", "/v1/metrics", "", null).body();

            assertThat(metric(metrics, "flows", "details", "executions")).isEqualTo(1);
            assertThat(ids(metrics, "steps")).doesNotContain("details-step");
        }
    }

    @Test
    void prometheusMetricsEndpointExportsCumulativeSeries() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/prometheus", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("text/plain; version=0.0.4; charset=utf-8");
        assertThat(response.body()).contains(
                "railix_application_executions_total{project=\"development-runtime-conformance\"}",
                "railix_flow_executions_total{project=\"development-runtime-conformance\",flow=\"success\"}",
                "railix_step_executions_total{project=\"development-runtime-conformance\",step=\"success-step\"}"
        );
    }

    @Test
    void influxMetricsEndpointExportsLineProtocol() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/metrics/influx", "", null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("text/plain; charset=utf-8");
        assertThat(response.body()).contains(
                "railix_application,project=development-runtime-conformance ",
                "railix_flow,project=development-runtime-conformance,flow=success ",
                "railix_step,project=development-runtime-conformance,step=success-step "
        );
    }

    @Test
    void validAuthorizationExecutesTheGeneratedApplication() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/success", "{\"payload\":\"input\"}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"payload\":\"input\"")
                .doesNotContain("\"steps\"");
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
    void unsupportedTraceMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "GET", "/v1/trace/details", "", "true"
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
    void blankTraceTargetIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/trace/", "{}", "true"
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
                                + "\"status\":\"succeeded\"}"
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
                                + "\"status\":\"succeeded\"}"
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
                                + "\"step\":\"failed-step\"},\"status\":\"failed\"}"
                );
    }

    @Test
    void nestedFailedGeneratedResultIncludesItsStablePath() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/run/nested-failed",
                "{\"payload\":{\"value\":\"input\"}}",
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        500,
                        "{\"failure\":{\"code\":\"STEP_IMPLEMENTATION_FAULT\","
                                + "\"message\":\"Step implementation threw an unexpected exception.\","
                                + "\"path\":\"nodes[29].inputs.operations[0]\","
                                + "\"step\":\"development.runtime.nested-fault\"},"
                                + "\"status\":\"failed\"}"
                );
    }

    @Test
    void cancelledGeneratedResultIsReturnedExplicitly() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/run/cancelled", "{}", "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(409, "{\"status\":\"cancelled\"}");
    }

    @Test
    void interruptedNestedStepRecordsExactlyOneNestedCancellation() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/trace/nested-cancelled",
                "{\"payload\":{\"value\":\"input\"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"nested-cancelled-step.inputs.operations[0]\"")
                .contains("\"status\":\"cancelled\"");
        assertThat(response.body().split("nested-cancelled-step.inputs.operations\\[0]", -1)).hasSize(3);
    }

    @Test
    void traceExecutesARealSideEffectExactlyOnce(@TempDir final Path output) throws Exception {
        final Path file = output.resolve("observed.txt");
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/trace/side-effect",
                "{\"payload\":{\"file\":\"" + escaped(file.toString()) + "\"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("x");
    }

    @Test
    void traceStreamsItsFirstEventWhileTheStepIsStillRunning() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-live-stream";
            final CompletableFuture<HttpResponse<InputStream>> trace = child.requestStreamAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/block",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);
            final HttpResponse<InputStream> response = trace.get(2, TimeUnit.SECONDS);
            try (InputStream body = response.body();
                 BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                final CompletableFuture<String> firstLine = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        firstLine.complete(lines.readLine());
                    } catch (final IOException exception) {
                        firstLine.completeExceptionally(exception);
                    }
                });

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(firstLine.get(2, TimeUnit.SECONDS)).contains("\"type\":\"trace\"");
            } finally {
                request(child, uri, TOKEN, "DELETE", "/v1/traces/" + traceId, "", null);
            }
        }
    }

    @Test
    void authenticatedCancellationInterruptsExactlyOneActiveTrace() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-cancel-one";
            final CompletableFuture<HttpResponse<String>> trace = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/block",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);

            final HttpResponse<String> cancellation = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );

            assertThat(cancellation).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(200, "{\"status\":\"cancelled\"}");
            assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                    .contains("\"status\":\"cancelled\"");
            assertThat(request(child, uri, TOKEN, "POST", "/v1/trace/success", "{}", "true").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void cancelledBackpressuredTraceEndsWithValidHttpChunks() throws Exception {
        try (var child = started(); Socket trace = new Socket()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-backpressure-cancel";
            trace.setReceiveBufferSize(1_024);
            trace.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), uri.getPort()), 1_000);
            trace.setSoTimeout((int) WAIT.toMillis());
            final byte[] body = "{\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
            trace.getOutputStream().write(("""
                    POST /v1/trace/oversized HTTP/1.1\r
                    Host: 127.0.0.1:%d\r
                    Authorization: Bearer %s\r
                    Content-Type: application/json\r
                    X-Railix-Test: true\r
                    X-Railix-Trace-Id: %s\r
                    Content-Length: %d\r
                    Connection: close\r
                    \r
                    """.formatted(uri.getPort(), TOKEN, traceId, body.length))
                    .getBytes(StandardCharsets.US_ASCII));
            trace.getOutputStream().write(body);
            trace.getOutputStream().flush();

            final InputStream input = trace.getInputStream();
            final String headers = readHeaders(input);
            assertThat(headers).startsWith("HTTP/1.1 200").containsIgnoringCase("Transfer-encoding: chunked");
            final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
            while (chunks.toString(StandardCharsets.UTF_8).lines().count() < 2) {
                chunks.write(readChunk(input));
            }
            final int blockedChunk = readChunkSize(input);
            assertThat(blockedChunk).isGreaterThan(1_024);
            final byte[] prefix = input.readNBytes(16);
            assertThat(prefix).hasSize(16);

            final HttpResponse<String> cancellation = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );
            assertThat(cancellation).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(202, "{\"status\":\"cancellation-requested\"}");

            chunks.write(prefix);
            chunks.write(readExact(input, blockedChunk - prefix.length));
            requireCrlf(input);
            while (true) {
                final int size = readChunkSize(input);
                if (size == 0) {
                    assertThat(readAsciiLine(input)).isEmpty();
                    break;
                }
                chunks.write(readExact(input, size));
                requireCrlf(input);
            }
            final List<RailixJson.Result> parsed = chunks.toString(StandardCharsets.UTF_8).lines()
                    .map(RailixJson::parse)
                    .toList();
            assertThat(parsed).allSatisfy(event -> assertThat(event).isInstanceOf(RailixJson.Parsed.class));
            final List<RailixValue.ObjectValue> events = parsed.stream()
                    .map(RailixJson.Parsed.class::cast)
                    .map(RailixJson.Parsed::value)
                    .map(RailixValue.ObjectValue.class::cast)
                    .toList();
            assertThat(events).isNotEmpty();
            assertThat(events.stream()
                    .filter(event -> RailixValue.string("result").equals(event.values().get("type"))))
                    .singleElement()
                    .isSameAs(events.getLast());
            assertThat(events.getLast().values())
                    .containsEntry("type", RailixValue.string("result"))
                    .containsEntry("status", RailixValue.string("cancelled"));
            assertThat(request(child, uri, TOKEN, "POST", "/v1/trace/success", "{}", "true").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void cancellationDoesNotClaimSuccessWhenTheStepCompletesNormallyAfterInterrupt() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-ignore-interrupt";
            final CompletableFuture<HttpResponse<String>> trace = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/ignore-cancel",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);

            final HttpResponse<String> cancellation = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );

            assertThat(cancellation).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(409, "{\"status\":\"completed\"}");
            assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                    .contains("\"status\":\"succeeded\"");
        }
    }

    @Test
    void cancellationReturnsRequestedWhileAThirdPartyStepKeepsWorking() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-delayed-interrupt";
            final CompletableFuture<HttpResponse<String>> trace = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/delayed-cancel",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);

            final HttpResponse<String> cancellation = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );

            assertThat(cancellation).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(202, "{\"status\":\"cancellation-requested\"}");
            assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                    .contains("\"status\":\"succeeded\"");
        }
    }

    @Test
    void repeatedCancellationOfTheSameWorkingTraceRemainsIdempotentlyRequested() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-repeated-cancel";
            final CompletableFuture<HttpResponse<String>> trace = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/delayed-cancel",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);

            final HttpResponse<String> first = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );
            final HttpResponse<String> repeated = request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            );

            assertThat(first).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(202, "{\"status\":\"cancellation-requested\"}");
            assertThat(repeated).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(202, "{\"status\":\"cancellation-requested\"}");
            assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                    .contains("\"status\":\"succeeded\"");
        }
    }

    @Test
    void cancellationSeventeenIsRejectedWhileSixteenCancellationsAreActive() throws Exception {
        try (ServerSocket entered = signalServer(CANCELLATION_ADMISSION_LIMIT);
             ServerSocket cancelled = signalServer(CANCELLATION_ADMISSION_LIMIT);
             var child = started()) {
            final URI uri = child.awaitReady();
            final List<CompletableFuture<HttpResponse<String>>> traces = new ArrayList<>();
            for (int index = 0; index < CANCELLATION_ADMISSION_LIMIT; index++) {
                traces.add(child.requestAsync(
                        uri,
                        TOKEN,
                        "/v1/trace/delayed-cancel",
                        "{\"payload\":{\"entered_port\":" + entered.getLocalPort()
                                + ",\"cancelled_port\":" + cancelled.getLocalPort() + "}}",
                        "cancel-saturation-" + index
                ));
            }
            for (int index = 0; index < CANCELLATION_ADMISSION_LIMIT; index++) {
                acceptSignal(entered);
            }

            final List<CompletableFuture<HttpResponse<String>>> cancellations = new ArrayList<>();
            for (int index = 0; index < CANCELLATION_ADMISSION_LIMIT; index++) {
                cancellations.add(child.requestAsync(
                        uri,
                        TOKEN,
                        "DELETE",
                        "/v1/traces/cancel-saturation-" + index,
                        "",
                        null
                ));
            }
            for (int index = 0; index < CANCELLATION_ADMISSION_LIMIT; index++) {
                acceptSignal(cancelled);
            }

            final HttpResponse<String> saturated = child.requestAsync(
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/cancel-saturation-0",
                    "",
                    null
            ).get(2, TimeUnit.SECONDS);

            assertThat(saturated).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            for (final CompletableFuture<HttpResponse<String>> cancellation : cancellations) {
                assertThat(cancellation.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(202);
            }
            for (final CompletableFuture<HttpResponse<String>> trace : traces) {
                assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                        .contains("\"status\":\"succeeded\"");
            }
        }
    }

    @Test
    void duplicateActiveTraceIdentifierIsRejectedWithoutDisturbingItsOwner() throws Exception {
        try (ServerSocket entered = signalServer(); var child = started()) {
            final URI uri = child.awaitReady();
            final String traceId = "trace-duplicate";
            final CompletableFuture<HttpResponse<String>> owner = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/block",
                    "{\"payload\":{\"entered_port\":" + entered.getLocalPort() + "}}",
                    traceId
            );
            acceptSignal(entered);

            final HttpResponse<String> duplicate = child.requestAsync(
                    uri,
                    TOKEN,
                    "/v1/trace/success",
                    "{}",
                    traceId
            ).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(duplicate).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(409, "{\"status\":\"conflict\"}");
            assertThat(request(
                    child,
                    uri,
                    TOKEN,
                    "DELETE",
                    "/v1/traces/" + traceId,
                    "",
                    null
            ).statusCode()).isEqualTo(200);
            assertThat(owner.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).body())
                    .contains("\"status\":\"cancelled\"");
        }
    }

    @Test
    void traceReturnsResolvedInputsNestedStepsAndContextChangesWithoutDuration() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/trace/details",
                "{\"payload\":{\"value\":\" Hello RAILIX \"}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains("application/x-ndjson; charset=utf-8");
        assertThat(response.body())
                .contains("\"inputs\":{\"field\":\" Hello RAILIX \",\"value\":\" Hello RAILIX \"}")
                .contains("\"id\":\"details-step.inputs.operations[0]\"")
                .contains("\"use\":\"text.trim\"")
                .contains("\"id\":\"details-step.inputs.operations[1]\"")
                .contains("\"use\":\"text.lowercase\"")
                .contains("\"changes\"")
                .doesNotContain("duration");
    }

    @Test
    void traceReportsAnObjectReplacedByAScalarAsOneChangedField() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/trace/oversized",
                "{\"payload\":{\"oversized\":{\"nested\":true}}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"path\":[\"context\",\"payload\",\"oversized\"]")
                .contains("\"kind\":\"changed\"")
                .contains("\"before\":{\"nested\":true}");
    }

    @Test
    void traceReportsAnArrayReplacedByAScalarAsOneChangedField() throws Exception {
        final HttpResponse<String> response = request(
                shared,
                sharedUri,
                TOKEN,
                "POST",
                "/v1/trace/oversized",
                "{\"payload\":{\"oversized\":[1,2]}}",
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"path\":[\"context\",\"payload\",\"oversized\"]")
                .contains("\"kind\":\"changed\"")
                .contains("\"before\":[1,2]");
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
    void malformedTraceJsonIsRejectedBeforeGeneratedExecution() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/trace/details", "{", "true"
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
    void largeTraceStreamsBoundedEventsWithoutBufferingTheWholeResponse() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/trace/oversized", "{}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"oversized-step\"")
                .contains("\"status\":\"succeeded\"");
    }

    @Test
    void oversizedTraceEventEndsTheStreamWithADeterministicTerminalError() throws Exception {
        final HttpResponse<String> response = request(
                shared, sharedUri, TOKEN, "POST", "/v1/trace/trace-too-large", "{}", "true"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"type\":\"trace_error\"")
                .contains("\"code\":\"TRACE_EVENT_TOO_LARGE\"")
                .contains("\"status\":\"failed\"")
                .doesNotContain("\"type\":\"result\"");
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
    void harnessCloseReclaimsARealGeneratedProcessAndItsChild() throws Exception {
        long childPid = -1;
        try (var child = DevelopmentRuntimeGeneratedProcess.launchMain(
                build,
                DevelopmentRuntimeConformanceSteps.ProcessTreeMain.class.getName()
        )) {
            childPid = awaitOutputPid(child);

            child.close();

            assertThat(child.process().isAlive()).isFalse();
            assertThat(awaitExit(childPid)).isTrue();
        } finally {
            if (childPid > 0) {
                stopIfAlive(childPid);
            }
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
    void ownershipEofTerminatesTheGeneratedStepsChildAndGrandchild(@TempDir final Path output) throws Exception {
        final Path marker = output.resolve("process-tree.pids");
        List<Long> pids = List.of();
        try (var child = started()) {
            final URI uri = child.awaitReady();
            final HttpResponse<String> response = request(
                    child,
                    uri,
                    TOKEN,
                    "POST",
                    "/v1/run/process-tree",
                    "{\"payload\":{\"marker\":\"" + escaped(marker.toString()) + "\"}}",
                    "true"
            );
            pids = awaitPids(marker, 2);

            assertThat(response.statusCode()).isEqualTo(200);
            child.closeOwner();
            assertThat(child.awaitExit()).isZero();
            assertThat(pids).allSatisfy(pid -> assertThat(awaitExit(pid)).isTrue());
        } finally {
            pids.forEach(DevelopmentRuntimeGeneratedApplicationE2eTest::stopIfAlive);
        }
    }

    @Test
    void ownershipEofForciblyTerminatesADescendantThatIgnoresGracefulTermination(
            @TempDir final Path output
    ) throws Exception {
        final Path marker = output.resolve("ignore-termination.pids");
        final DevelopmentRuntimeGeneratedProcess.Build forced = DevelopmentRuntimeGeneratedProcess.build(
                output,
                ignoringTerminationProject(marker),
                List.of(
                        trigger("process-tree-ignore", false),
                        StepDefinition.named("development.runtime.process-tree", "1")
                                .input("mode", StepDefinition.Input.json(ValueShape.STRING))
                                .input("marker", StepDefinition.Input.path(READ)
                                        .defaultPath("context", "payload", "marker"))
                                .run(ProcessTreeStepHandler.class)
                ),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                ProcessTreeStepHandler.class
        );
        List<Long> pids = List.of();
        try (var child = started(forced)) {
            child.awaitReady();
            pids = awaitPids(marker, 1);

            child.closeOwner();

            assertThat(child.awaitExit()).isZero();
            assertThat(awaitPids(
                    marker.resolveSibling(marker.getFileName() + ".terminating"), 1
            )).containsExactlyElementsOf(pids);
            assertThat(pids).allSatisfy(pid -> assertThat(awaitExit(pid)).isTrue());
        } finally {
            pids.forEach(DevelopmentRuntimeGeneratedApplicationE2eTest::stopIfAlive);
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
    void incompleteBodiesCannotConsumeRunAdmissionAndAreClosedAtTheirDeadline() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            final List<Socket> partial = new ArrayList<>();
            try {
                for (int index = 0; index < ADMISSION_LIMIT; index++) {
                    partial.add(partialRequest(uri, "/v1/run/success"));
                }
                Thread.sleep(500);

                assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true")
                        .statusCode()).isEqualTo(200);
                for (final Socket socket : partial) {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                }
                assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true")
                        .statusCode()).isEqualTo(200);
            } finally {
                for (final Socket socket : partial) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void incompleteBodyFortyNineIsClosedWhileFortyEightReadersAreActive() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            final List<Socket> admitted = new ArrayList<>();
            try {
                for (int index = 0; index < BODY_ADMISSION_LIMIT; index++) {
                    admitted.add(partialRequest(uri, "/v1/run/success"));
                }
                Thread.sleep(500);

                try (Socket saturated = partialRequest(uri, "/v1/run/success")) {
                    saturated.setSoTimeout(2_000);
                    assertThat(saturated.getInputStream().read()).isEqualTo(-1);
                }
            } finally {
                for (final Socket socket : admitted) {
                    socket.close();
                }
            }
            awaitSuccessfulRun(child, uri);
        }
    }

    @Test
    void invalidTokenBodiesCannotConsumeAuthenticatedBodyAdmission() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            final List<Socket> unauthorized = new ArrayList<>();
            try {
                for (int index = 0; index < UNAUTHORIZED_BODY_ADMISSION_LIMIT; index++) {
                    unauthorized.add(partialRequest(uri, "/v1/run/success", "wrong"));
                }
                Thread.sleep(500);

                assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true")
                        .statusCode()).isEqualTo(200);
                try (Socket saturated = partialRequest(uri, "/v1/run/success", "wrong")) {
                    saturated.setSoTimeout(2_000);
                    assertThat(saturated.getInputStream().read()).isEqualTo(-1);
                }
                for (final Socket socket : unauthorized) {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                }
            } finally {
                for (final Socket socket : unauthorized) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void missingTokenBodiesCannotConsumeAuthenticatedBodyAdmission() throws Exception {
        try (var child = started()) {
            final URI uri = child.awaitReady();
            final List<Socket> unauthorized = new ArrayList<>();
            try {
                for (int index = 0; index < UNAUTHORIZED_BODY_ADMISSION_LIMIT; index++) {
                    unauthorized.add(partialRequestWithoutToken(uri, "/v1/run/success"));
                }
                Thread.sleep(500);

                assertThat(request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true")
                        .statusCode()).isEqualTo(200);
                try (Socket saturated = partialRequestWithoutToken(uri, "/v1/run/success")) {
                    saturated.setSoTimeout(2_000);
                    assertThat(saturated.getInputStream().read()).isEqualTo(-1);
                }
                for (final Socket socket : unauthorized) {
                    assertThat(socket.getInputStream().read()).isEqualTo(-1);
                }
            } finally {
                for (final Socket socket : unauthorized) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void activeTraceDoesNotConsumeAnyOfTheThirtyTwoRunSlots() throws Exception {
        try (ServerSocket entered = signalServer(ADMISSION_LIMIT + 2);
             ServerSocket release = signalServer(ADMISSION_LIMIT + 1);
             var child = started()) {
            final URI uri = child.awaitReady();
            final CompletableFuture<HttpResponse<String>> trace = hold(
                    child, uri, entered, release, "/v1/trace/hold"
            );
            acceptSignal(entered);

            final List<CompletableFuture<HttpResponse<String>>> runs = new ArrayList<>();
            for (int index = 0; index < ADMISSION_LIMIT; index++) {
                runs.add(hold(child, uri, entered, release));
            }
            for (int index = 0; index < ADMISSION_LIMIT; index++) {
                acceptSignal(entered);
            }

            final List<Socket> releases = new ArrayList<>();
            try {
                for (int index = 0; index <= ADMISSION_LIMIT; index++) {
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

            assertThat(trace.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
            for (final CompletableFuture<HttpResponse<String>> run : runs) {
                assertThat(run.get(WAIT.toMillis(), TimeUnit.MILLISECONDS).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void traceSeventeenIsRejectedWhileSixteenGeneratedTracesAreActive() throws Exception {
        try (ServerSocket entered = signalServer(TRACE_ADMISSION_LIMIT + 1);
             ServerSocket release = signalServer(TRACE_ADMISSION_LIMIT);
             var child = started()) {
            final URI uri = child.awaitReady();
            final List<CompletableFuture<HttpResponse<String>>> admitted = new ArrayList<>();
            for (int index = 0; index < TRACE_ADMISSION_LIMIT; index++) {
                admitted.add(hold(child, uri, entered, release, "/v1/trace/hold"));
            }
            for (int index = 0; index < TRACE_ADMISSION_LIMIT; index++) {
                acceptSignal(entered);
            }
            final List<Socket> releases = new ArrayList<>();
            try {
                for (int index = 0; index < TRACE_ADMISSION_LIMIT; index++) {
                    releases.add(release.accept());
                }

                final HttpResponse<String> saturated = hold(
                        child,
                        uri,
                        entered,
                        release,
                        "/v1/trace/hold"
                ).get(2, TimeUnit.SECONDS);

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
        return started(build);
    }

    private static DevelopmentRuntimeGeneratedProcess.Child started(
            final DevelopmentRuntimeGeneratedProcess.Build source
    ) throws IOException {
        return DevelopmentRuntimeGeneratedProcess.launch(source).token(TOKEN);
    }

    private void assertInvalidExampleManifest(
            final Path workspace,
            final String manifest,
            final String message
    ) throws Exception {
        assertInvalidExampleManifest(workspace, manifest.getBytes(StandardCharsets.UTF_8), message);
    }

    private void assertInvalidExampleManifest(
            final Path workspace,
            final byte[] manifest,
            final String message
    ) throws Exception {
        final DevelopmentRuntimeGeneratedProcess.Build corrupt = rewriteExampleManifest(
                build,
                workspace,
                manifest,
                "invalid-examples"
        );
        try (var child = DevelopmentRuntimeGeneratedProcess.launch(corrupt).token(TOKEN)) {
            child.closeOwner();

            assertThat(child.awaitExit()).isEqualTo(3);
            assertThat(child.error()).contains(message);
        }
    }

    private static String exampleManifest(final String field, final String value) {
        final String example = "{\"context\":" + ("context".equals(field) ? value : "{}")
                + ",\"id\":" + ("id".equals(field) ? value : "\"case:0\"")
                + ",\"trigger\":" + ("trigger".equals(field) ? value : "\"case\"")
                + ",\"name\":" + ("name".equals(field) ? value : "\"case\"")
                + ",\"index\":" + ("index".equals(field) ? value : "0")
                + ",\"node\":" + ("node".equals(field) ? value : "0") + "}";
        return "{\"format\":1,\"node_count\":1,\"examples\":[" + example + "]}";
    }

    private static String validExample() {
        return "{\"context\":{},\"id\":\"case:0\",\"trigger\":\"case\","
                + "\"name\":\"case\",\"index\":0,\"node\":0}";
    }

    private static DevelopmentRuntimeGeneratedProcess.Build withoutExampleManifest(
            final DevelopmentRuntimeGeneratedProcess.Build source,
            final Path workspace
    ) throws IOException {
        return rewriteExampleManifest(source, workspace, null, "missing-examples");
    }

    private static DevelopmentRuntimeGeneratedProcess.Build rewriteExampleManifest(
            final DevelopmentRuntimeGeneratedProcess.Build source,
            final Path workspace,
            final byte[] replacement,
            final String fingerprint
    ) throws IOException {
        final Path jar = workspace.resolve("application-with-rewritten-examples.jar");
        try (JarFile input = new JarFile(source.artifact().jar().toFile());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (final JarEntry entry : input.stream().toList()) {
                final boolean exampleManifest = "META-INF/railix/examples.json".equals(entry.getName());
                if (exampleManifest && replacement == null) {
                    continue;
                }
                final JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                if (exampleManifest) {
                    output.write(replacement);
                } else if (!entry.isDirectory()) {
                    try (var bytes = input.getInputStream(entry)) {
                        bytes.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
        final ApplicationBuilder.Artifact original = source.artifact();
        return new DevelopmentRuntimeGeneratedProcess.Build(
                source.project(),
                source.compiled(),
                new ApplicationBuilder.Artifact(
                        workspace,
                        original.source(),
                        original.classes(),
                        jar,
                        fingerprint,
                        Files.getLastModifiedTime(jar).toMillis(),
                        false
                )
        );
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

    private void assertExampleRouteNotFound(final String path) throws Exception {
        assertThat(request(shared, sharedUri, TOKEN, "GET", path, "", null))
                .extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    private static String awaitExample(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final String id,
            final String status
    ) throws Exception {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        String snapshot = "";
        do {
            final HttpResponse<String> response = request(
                    child, uri, TOKEN, "GET", "/v1/examples", "", null
            );
            assertThat(response.statusCode()).isEqualTo(200);
            snapshot = response.body();
            final boolean matched = values(snapshot, "cases").stream().anyMatch(value ->
                    id.equals(((RailixValue.StringValue) value.values().get("id")).value())
                            && status.equals(((RailixValue.StringValue) value.values().get("status")).value())
            );
            if (matched) {
                return snapshot;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Generated application Example did not reach " + status + ": " + snapshot);
    }

    private static String awaitExamples(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final long completed,
            final Duration timeout
    ) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        String status = "";
        do {
            final HttpResponse<String> response = request(
                    child, uri, TOKEN, "GET", "/v1/examples/status", "", null
            );
            assertThat(response.statusCode()).isEqualTo(200);
            status = response.body();
            final RailixValue.ObjectValue summary = object(status);
            if (number(summary, "completed") == completed && "completed".equals(string(summary, "state"))) {
                final HttpResponse<String> inventory = request(
                        child, uri, TOKEN, "GET", "/v1/examples", "", null
                );
                assertThat(inventory.statusCode()).isEqualTo(200);
                return inventory.body();
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Generated application Example suite did not complete: " + status);
    }

    private static void awaitAutomaticExamples(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri
    ) throws Exception {
        final HttpResponse<String> response = request(
                child, uri, TOKEN, "GET", "/v1/examples/status", "", null
        );
        assertThat(response.statusCode()).isEqualTo(200);
        awaitExamples(child, uri, number(object(response.body()), "total"), WAIT);
    }

    private static void assertExecutions(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final long expected
    ) throws Exception {
        final HttpResponse<String> response = request(
                child, uri, TOKEN, "GET", "/v1/metrics", "", null
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(metric(response.body(), "flows", "success", "executions")).isEqualTo(expected);
        assertThat(metric(response.body(), "steps", "success-step", "executions")).isEqualTo(expected);
    }

    private static CompletableFuture<HttpResponse<String>> hold(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final ServerSocket entered,
            final ServerSocket release
    ) {
        return hold(child, uri, entered, release, "/v1/run/hold");
    }

    private static CompletableFuture<HttpResponse<String>> hold(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final ServerSocket entered,
            final ServerSocket release,
            final String path
    ) {
        return child.requestAsync(
                uri,
                TOKEN,
                path,
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

    private static Socket partialRequest(final URI uri, final String path) throws IOException {
        return partialRequest(uri, path, TOKEN);
    }

    private static Socket partialRequest(
            final URI uri,
            final String path,
            final String token
    ) throws IOException {
        return partialRequestWithAuthorization(uri, path, "Authorization: Bearer " + token + "\r\n");
    }

    private static Socket partialRequestWithoutToken(final URI uri, final String path) throws IOException {
        return partialRequestWithAuthorization(uri, path, "");
    }

    private static Socket managementGet(final URI uri, final String path) throws IOException {
        final Socket socket = new Socket();
        try {
            socket.setReceiveBufferSize(1_024);
            socket.setSoTimeout((int) WAIT.toMillis());
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), uri.getPort()), 1_000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + uri.getPort() + "\r\n"
                    + "Authorization: Bearer " + TOKEN + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return socket;
        } catch (final IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (final IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static HttpResponse<String> awaitManagementReadAvailable(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri,
            final String path
    ) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        HttpResponse<String> response;
        do {
            response = request(child, uri, TOKEN, "GET", path, "", null);
            if (response.statusCode() == 200) {
                return response;
            }
            assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                    .containsExactly(503, "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new IOException("Application management read remained saturated.");
    }

    private static Socket partialRequestWithAuthorization(
            final URI uri,
            final String path,
            final String authorization
    ) throws IOException {
        final Socket socket = new Socket();
        socket.setSoTimeout((int) WAIT.toMillis());
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), uri.getPort()), 1_000);
        socket.getOutputStream().write(("POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + uri.getPort() + "\r\n"
                + authorization
                + "Content-Type: application/json\r\n"
                + "Content-Length: 100\r\n"
                + "Connection: close\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static String readHeaders(final InputStream input) throws IOException {
        final ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        while (headers.size() < 16_384) {
            final int next = input.read();
            if (next < 0) {
                throw new IOException("HTTP response ended before its headers.");
            }
            headers.write(next);
            matched = switch (matched) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return headers.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("HTTP response headers exceed 16384 bytes.");
    }

    private static byte[] readChunk(final InputStream input) throws IOException {
        final int size = readChunkSize(input);
        if (size == 0) {
            throw new IOException("HTTP response ended before the expected data chunk.");
        }
        final byte[] chunk = readExact(input, size);
        requireCrlf(input);
        return chunk;
    }

    private static int readChunkSize(final InputStream input) throws IOException {
        final String line = readAsciiLine(input);
        final int extension = line.indexOf(';');
        final String size = extension < 0 ? line : line.substring(0, extension);
        try {
            return Integer.parseInt(size, 16);
        } catch (final NumberFormatException exception) {
            throw new IOException("HTTP chunk size is invalid: " + line, exception);
        }
    }

    private static String readAsciiLine(final InputStream input) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 128) {
            final int next = input.read();
            if (next < 0) {
                throw new IOException("HTTP chunked response ended before CRLF.");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("HTTP chunked response contains an invalid line ending.");
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            line.write(next);
        }
        throw new IOException("HTTP chunk line exceeds 128 bytes.");
    }

    private static byte[] readExact(final InputStream input, final int size) throws IOException {
        final byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("HTTP chunked response ended inside a chunk.");
        }
        return bytes;
    }

    private static void requireCrlf(final InputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("HTTP chunk payload has no trailing CRLF.");
        }
    }

    private static void awaitSuccessfulRun(
            final DevelopmentRuntimeGeneratedProcess.Child child,
            final URI uri
    ) throws Exception {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        do {
            try {
                if (request(child, uri, TOKEN, "POST", "/v1/run/success", "{}", "true")
                        .statusCode() == 200) {
                    return;
                }
            } catch (final IOException ignoredClosingBodies) {
                // Retry until every closed partial body has released its reader slot.
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Generated application did not recover its request-body admission.");
    }

    private static List<StepDefinition> definitions() {
        return List.of(
                trigger("success", false),
                trigger("details", false),
                trigger("rejected", true),
                trigger("failed", false),
                trigger("nested-failed", false),
                trigger("cancelled", false),
                trigger("nested-cancelled", false),
                trigger("side-effect", false),
                trigger("oversized", false),
                trigger("trace-too-large", false),
                trigger("block", false),
                trigger("ignore-cancel", false),
                trigger("delayed-cancel", false),
                trigger("hold", false),
                trigger("process-tree", false),
                pass(),
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
                StepDefinition.named("development.runtime.nested-fault", "1")
                        .receive("value", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
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
                StepDefinition.named("development.runtime.oversized-trace", "1")
                        .input("target", StepDefinition.Input.path(READ_WRITE)
                                .defaultPath("context", "payload", "oversized"))
                        .run(DevelopmentRuntimeConformanceSteps.OversizedTrace.class),
                StepDefinition.named("development.runtime.block", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .run(DevelopmentRuntimeConformanceSteps.Block.class),
                StepDefinition.named("development.runtime.ignore-interrupt", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .run(DevelopmentRuntimeConformanceSteps.IgnoreInterrupt.class),
                StepDefinition.named("development.runtime.delayed-ignore-interrupt", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .input("cancelled_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "cancelled_port")
                                .optional())
                        .run(DevelopmentRuntimeConformanceSteps.DelayedIgnoreInterrupt.class),
                StepDefinition.named("development.runtime.hold", "1")
                        .input("entered_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "entered_port"))
                        .input("release_port", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "release_port"))
                        .run(DevelopmentRuntimeConformanceSteps.Hold.class),
                StepDefinition.named("development.runtime.process-tree", "1")
                        .input("mode", StepDefinition.Input.json(ValueShape.STRING)
                                .defaultValue(RailixValue.string("established")))
                        .input("marker", StepDefinition.Input.path(READ)
                                .defaultPath("context", "payload", "marker"))
                        .run(ProcessTreeStepHandler.class)
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

    private static StepDefinition pass() {
        return StepDefinition.named("development.runtime.pass", "1")
                .run(DevelopmentRuntimeConformanceSteps.Pass.class);
    }

    private static StepDefinition exampleGate() {
        return StepDefinition.named("development.runtime.example-gate", "1")
                .input("root", StepDefinition.Input.path(READ)
                        .defaultPath("context", "payload", "root"))
                .input("case", StepDefinition.Input.path(READ)
                        .defaultPath("context", "payload", "case"))
                .input("ignore_interrupt", StepDefinition.Input.json(ValueShape.BOOLEAN)
                        .defaultValue(RailixValue.bool(true)))
                .run(ChunkGateStepHandler.class);
    }

    private static StepDefinition aggregateStorageStep() {
        return StepDefinition.named("development.runtime.aggregate-storage", "1")
                .input("target", StepDefinition.Input.path(READ_WRITE)
                        .defaultPath("context", "payload", "large"))
                .run(DevelopmentRuntimeConformanceSteps.Oversized.class);
    }

    private static String nonCooperativeExample(final Path gate) {
        return """
                {"format":1,"id":"noncooperative-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"noncooperative","use":"development.runtime.trigger.noncooperative","inputs":{},
                    "examples":[{"name":"case-1","payload":{"case":"case-1","root":"%s"}}]},
                  {"id":"gate","use":"development.runtime.example-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"noncooperative"},
                  {"from":"noncooperative.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(escaped(gate.toString()));
    }

    private static String nonCooperativeRun(final Path gate) {
        return """
                {"format":1,"id":"noncooperative-http","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"noncooperative-http","use":"development.runtime.trigger.noncooperative-http","inputs":{},
                    "examples":[{"name":"automatic","payload":{"case":"automatic","root":"%s"}}]},
                  {"id":"gate","use":"development.runtime.example-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"noncooperative-http"},
                  {"from":"noncooperative-http.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(escaped(gate.toString()));
    }

    private static String largeTraceExamples(
            final String id,
            final int exampleCount,
            final int stepCount
    ) {
        final String examples = java.util.stream.IntStream.range(0, exampleCount)
                .mapToObj(index -> "{\"name\":\"large-" + index + "\",\"payload\":[\""
                        + "x".repeat(900_000) + "\"]}")
                .collect(java.util.stream.Collectors.joining(","));
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]}
                """.formatted(id, examples));
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-0"}
                """);
        for (int index = 0; index < stepCount; index++) {
            nodes.append("""
                    ,{"id":"lowercase-%d","use":"text.lowercase","inputs":{},
                      "receives":{"value":["context","payload","arguments",0]},
                      "returns":{"value":["context","payload","arguments",0]}}
                    """.formatted(index));
            links.append("""
                    ,{"from":"lowercase-%d.ok","to":%s}
                    """.formatted(
                    index,
                    index == stepCount - 1 ? "\"end\"" : "\"lowercase-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String metricCardinalityProject(final int stepCount) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"metric-cardinality","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"metrics","use":"development.runtime.trigger.metrics","inputs":{},
                    "examples":[{"name":"default","payload":{}}]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"metrics"},
                  {"from":"metrics.next","to":"metric-0"}
                """);
        for (int index = 0; index < stepCount; index++) {
            nodes.append("""
                    ,{"id":"metric-%d","use":"development.runtime.pass","inputs":{}}
                    """.formatted(index));
            links.append("""
                    ,{"from":"metric-%d.next","to":%s}
                    """.formatted(
                    index,
                    index == stepCount - 1 ? "\"end\"" : "\"metric-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String ignoringTerminationProject(final Path marker) {
        return """
                {"format":1,"id":"ignore-termination","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"process-tree-ignore","use":"development.runtime.trigger.process-tree-ignore",
                    "inputs":{},"examples":[{"name":"default","payload":{"marker":"%s"}}]},
                  {"id":"tree","use":"development.runtime.process-tree",
                    "inputs":{"mode":"ignore-termination"}}
                ],"links":[
                  {"from":"app.start","to":"process-tree-ignore"},
                  {"from":"process-tree-ignore.next","to":"tree"},
                  {"from":"tree.next","to":"end"}
                ]}
                """.formatted(escaped(marker.toString()));
    }

    private static String aggregateStorageExamples() {
        return oversizedTraceExamples("aggregate-storage-limit", 5, 25);
    }

    private static String oversizedTraceExamples(
            final String id,
            final int exampleCount,
            final int stepCount
    ) {
        final String examples = java.util.stream.IntStream.range(0, exampleCount)
                .mapToObj(index -> "{\"name\":\"case-" + index + "\",\"payload\":[]}")
                .collect(java.util.stream.Collectors.joining(","));
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]},
                  {"id":"expand","use":"development.runtime.aggregate-storage","inputs":{}}
                """.formatted(id, examples));
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"expand"},
                  {"from":"expand.next","to":%s}
                """.formatted(stepCount == 0 ? "\"end\"" : "\"lowercase-0\""));
        for (int index = 0; index < stepCount; index++) {
            nodes.append("""
                    ,{"id":"lowercase-%d","use":"text.lowercase","inputs":{},
                      "receives":{"value":["context","payload","large"]},
                      "returns":{"value":["context","payload","large"]}}
                    """.formatted(index));
            links.append("""
                    ,{"from":"lowercase-%d.ok","to":%s}
                    """.formatted(
                    index,
                    index == stepCount - 1 ? "\"end\"" : "\"lowercase-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String expandingContextExample(final int stepCount) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"example-admission","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},
                    "examples":[{"name":"default","payload":[]}]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"expand-0"}
                """);
        for (int index = 0; index < stepCount; index++) {
            nodes.append("""
                    ,{"id":"expand-%d","use":"development.runtime.aggregate-storage",
                      "inputs":{"target":["context","payload","large-%d"]}}
                    """.formatted(index, index));
            links.append("""
                    ,{"from":"expand-%d.next","to":%s}
                    """.formatted(
                    index,
                    index == stepCount - 1 ? "\"end\"" : "\"expand-" + (index + 1) + "\""
            ));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String project(final Path workspace) {
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
                  %s,
                  %s,
                  %s,
                  %s,
                  {"id":"success-step","use":"development.runtime.pass","inputs":{}},
                  {"id":"details-step","use":"development.runtime.operation","metrics":false,"inputs":{
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
                  {"id":"trace-too-large-step","use":"development.runtime.oversized-trace","inputs":{}},
                  {"id":"block-step","use":"development.runtime.block","inputs":{}},
                  {"id":"ignore-cancel-step","use":"development.runtime.ignore-interrupt","inputs":{}},
                  {"id":"delayed-cancel-step","use":"development.runtime.delayed-ignore-interrupt","inputs":{}},
                  {"id":"hold-step","use":"development.runtime.hold","inputs":{}},
                  {"id":"process-tree-step","use":"development.runtime.process-tree","inputs":{}},
                  %s,
                  {"id":"nested-failed-step","use":"development.runtime.operation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "operations":[{"use":"development.runtime.nested-fault","inputs":{}}]
                  }}
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
                  {"from":"app.start","to":"nested-failed"},
                  {"from":"nested-failed.next","to":"nested-failed-step"},
                  {"from":"nested-failed-step.next","to":"end"},
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
                  {"from":"app.start","to":"trace-too-large"},
                  {"from":"trace-too-large.next","to":"trace-too-large-step"},
                  {"from":"trace-too-large-step.next","to":"end"},
                  {"from":"app.start","to":"block"},
                  {"from":"block.next","to":"block-step"},
                  {"from":"block-step.next","to":"end"},
                  {"from":"app.start","to":"ignore-cancel"},
                  {"from":"ignore-cancel.next","to":"ignore-cancel-step"},
                  {"from":"ignore-cancel-step.next","to":"end"},
                  {"from":"app.start","to":"delayed-cancel"},
                  {"from":"delayed-cancel.next","to":"delayed-cancel-step"},
                  {"from":"delayed-cancel-step.next","to":"end"},
                  {"from":"app.start","to":"hold"},
                  {"from":"hold.next","to":"hold-step"},
                  {"from":"hold-step.next","to":"end"},
                  {"from":"app.start","to":"process-tree"},
                  {"from":"process-tree.next","to":"process-tree-step"},
                  {"from":"process-tree-step.next","to":"end"}
                ]}
                """.formatted(
                triggerNode("success", "{}"),
                triggerNode("details", "{\"value\":\" Hello RAILIX \"}"),
                triggerNode("rejected", "{}"),
                triggerNode("failed", "{}"),
                triggerNode("cancelled", "{}"),
                triggerNode("nested-cancelled", "{\"value\":\"input\"}"),
                triggerNode(
                        "side-effect",
                        "{\"file\":\"" + escaped(workspace.resolve("observed.txt").toString()) + "\"}"
                ),
                triggerNode("oversized", "{}"),
                triggerNode("trace-too-large", "{}"),
                triggerNode("block", "{\"entered_port\":1}"),
                triggerNode("ignore-cancel", "{\"entered_port\":1}"),
                triggerNode("delayed-cancel", "{\"entered_port\":1}"),
                triggerNode("hold", "{\"entered_port\":1,\"release_port\":2}"),
                triggerNode(
                        "process-tree",
                        "{\"marker\":\"" + escaped(workspace.resolve("process-tree.pids").toString()) + "\"}"
                ),
                triggerNode("nested-failed", "{\"value\":\"input\"}")
        );
    }

    private static String markerExample(final Path marker) {
        return """
                {"format":1,"id":"activation-boundary","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"marker","use":"development.runtime.trigger.marker","inputs":{},"examples":[
                    {"name":"marker","payload":{"file":"%s"}}
                  ]},
                  {"id":"append","use":"development.runtime.append","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"marker"},
                  {"from":"marker.next","to":"append"},
                  {"from":"append.next","to":"end"}
                ]}
                """.formatted(escaped(marker.toString()));
    }

    private static DevelopmentRuntimeGeneratedProcess.Build markerBuild(
            final Path workspace,
            final Path marker
    ) throws IOException {
        return DevelopmentRuntimeGeneratedProcess.build(
                workspace,
                markerExample(marker),
                List.of(
                        trigger("marker", false),
                        StepDefinition.named("development.runtime.append", "1")
                                .input("file", StepDefinition.Input.path(READ)
                                        .defaultPath("context", "payload", "file"))
                                .run(DevelopmentRuntimeConformanceSteps.Append.class)
                ),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                DevelopmentRuntimeConformanceSteps.Append.class
        );
    }

    private static List<Long> awaitPids(final Path marker, final int count) throws Exception {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        List<String> values = List.of();
        while (values.size() < count && System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) {
                values = Files.readAllLines(marker, StandardCharsets.UTF_8).stream()
                        .filter(value -> !value.isBlank())
                        .toList();
            }
            if (values.size() < count) {
                Thread.sleep(10);
            }
        }
        assertThat(values).hasSize(count);
        return values.stream().map(Long::parseLong).toList();
    }

    private static void awaitSignal(final Path signal, final Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.isRegularFile(signal) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(signal).isRegularFile();
    }

    private static void awaitContent(final Path file, final String expected, final Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        String actual = "";
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                actual = Files.readString(file, StandardCharsets.UTF_8);
                if (expected.equals(actual)) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static void awaitStopped(final Process process, final Duration timeout) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(process.isAlive()).isFalse();
    }

    private static boolean awaitExit(final long pid) throws InterruptedException {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }

    private static long awaitOutputPid(
            final DevelopmentRuntimeGeneratedProcess.Child child
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        String value = "";
        while (value.isEmpty() && System.nanoTime() < deadline) {
            value = child.output().strip();
            if (value.isEmpty()) {
                Thread.sleep(10);
            }
        }
        assertThat(value).containsOnlyDigits();
        return Long.parseLong(value);
    }

    private static void stopIfAlive(final long pid) {
        final var process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.orElseThrow().isAlive()) {
            return;
        }
        final ProcessHandle handle = process.orElseThrow();
        handle.destroyForcibly();
        try {
            assertThat(awaitExit(pid)).isTrue();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test process cleanup was interrupted: " + pid, exception);
        }
    }

    private static String escaped(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Path runtimeDirectory(
            final DevelopmentRuntimeGeneratedProcess.Build source,
            final DevelopmentRuntimeGeneratedProcess.Child child
    ) {
        return source.artifact().jar().getParent()
                .resolve(".railix-runtime")
                .resolve(Long.toString(child.process().pid()));
    }

    private static TraceEvidence traceEvidence(final Path path) {
        try (BufferedReader lines = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String last = "";
            long terminals = 0;
            String line;
            while ((line = lines.readLine()) != null) {
                last = line;
                if (line.contains("\"type\":\"result\"")
                        || line.contains("\"type\":\"trace_error\"")) {
                    terminals++;
                }
            }
            return new TraceEvidence(last, terminals);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long size(final Path path) {
        try {
            return Files.size(path);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long number(final RailixValue.ObjectValue source, final String field) {
        return ((RailixValue.NumberValue) source.values().get(field)).value().longValueExact();
    }

    private static String string(final RailixValue.ObjectValue source, final String field) {
        return ((RailixValue.StringValue) source.values().get(field)).value();
    }

    private static long metric(
            final String source,
            final String group,
            final String id,
            final String name
    ) {
        return ((RailixValue.NumberValue) ((RailixValue.ObjectValue) series(source, group, id)
                .values().get("metrics")).values().get(name)).value().longValueExact();
    }

    private static RailixValue.ObjectValue series(
            final String source,
            final String group,
            final String id
    ) {
        return values(source, group).stream()
                .filter(item -> id.equals(((RailixValue.StringValue) item.values().get("id")).value()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> ids(final String source, final String group) {
        return values(source, group).stream()
                .map(value -> ((RailixValue.StringValue) value.values().get("id")).value())
                .toList();
    }

    private static List<RailixValue.ObjectValue> values(final String source, final String group) {
        final RailixValue.ObjectValue root = object(source);
        return ((RailixValue.ArrayValue) root.values().get(group)).values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .toList();
    }

    private static RailixValue.ObjectValue object(final String source) {
        final RailixData.Result normalized = RailixData.normalize(
                RailixData.Format.JSON,
                source.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(normalized).isInstanceOf(RailixData.Normalized.class);
        return (RailixValue.ObjectValue) ((RailixData.Normalized) normalized).value();
    }

    private static String triggerNode(final String id, final String payload) {
        return """
                {"id":"%1$s","use":"development.runtime.trigger.%1$s","inputs":{},"examples":[
                  {"name":"default","payload":%2$s}
                ]}
                """.formatted(id, payload);
    }

    private record TraceEvidence(String last, long terminals) {
    }
}
