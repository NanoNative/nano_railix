package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import thirdparty.conformance.ProcessTreeStepHandler;
import thirdparty.conformance.SlowStepHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(90)
final class CreatorForwardingLifecycleE2eTest {
    private static final int FORWARD_LIMIT = CreatorServer.MAX_CONCURRENT_FORWARDS;
    private static final String CONTEXT = "{\"payload\":{\"arguments\":[\"Hello RAILIX\"]}}";

    @TempDir
    Path directory;

    @Test
    void thirtyThirdConcurrentForwardIsRejectedWithoutReadingItsBody() throws Exception {
        try (CreatorServer creator = start("concurrency-limit");
             SaturatedRequests requests = SaturatedRequests.open(creator.baseUri(), FORWARD_LIMIT + 1)) {
            assertThat(requests.awaitRejection())
                    .contains(" 503 ", "{\"reason\":\"saturated\",\"status\":\"unavailable\"}");

            assertThat(requests.release()).filteredOn(response -> response.contains(" 503 ")).hasSize(1);
        }
    }

    @Test
    void forwardingRecoversAfterAnActualSaturationRejection() throws Exception {
        try (CreatorServer creator = start("saturation-recovery");
             SaturatedRequests requests = SaturatedRequests.open(creator.baseUri(), FORWARD_LIMIT + 1)) {
            assertThat(requests.awaitRejection()).contains(" 503 ");
            requests.release();

            try (HttpClient client = HttpClient.newHttpClient()) {
                assertThat(request(client, creator.baseUri(), CONTEXT))
                        .extracting(HttpResponse::statusCode, HttpResponse::body)
                        .containsExactly(200, "{\"context\":{\"exit_code\":0,"
                                + "\"payload\":{\"arguments\":[\"Hello RAILIX\"]},"
                                + "\"result\":\"hello railix\",\"runtime\":{\"test\":true,"
                                + "\"trigger\":\"command\"}},\"status\":\"succeeded\",\"steps\":["
                                + "{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]}");
            }
        }
    }

    @Test
    void sixtyFifthConcurrentRequestIsRejectedBeforeItsBodyIsRead() throws Exception {
        try (CreatorServer creator = start("request-limit");
             SaturatedRequests requests = SaturatedRequests.openProject(
                     creator.baseUri(),
                     CreatorServer.MAX_CONCURRENT_REQUESTS + 1
             )) {
            assertThat(requests.awaitRejection())
                    .contains(" 503 ", "{\"reason\":\"request-saturated\",\"status\":\"unavailable\"}");
            requests.release();
        }
    }

    @Test
    void requestAdmissionRecoversAfterEveryStalledBodyDeadline() throws Exception {
        try (CreatorServer creator = start("request-deadline-recovery");
             SaturatedRequests requests = SaturatedRequests.openProject(
                     creator.baseUri(),
                     CreatorServer.MAX_CONCURRENT_REQUESTS
             )) {
            assertThat(requests.awaitAll())
                    .hasSize(CreatorServer.MAX_CONCURRENT_REQUESTS)
                    .allSatisfy(response -> assertThat(response).contains(
                            " 408 ",
                            "\"code\":\"REQUEST_BODY_TIMEOUT\"",
                            "\"status\":\"rejected\""
                    ));
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertThat(client.send(
                        HttpRequest.newBuilder(creator.baseUri().resolve("/api/project"))
                                .header("X-Railix-Creator-Token", token(creator.baseUri()))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                ).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void forwardingPermitsAreReleasedAfterSuccessfulCompletion() throws Exception {
        try (CreatorServer creator = start("completion-release")) {
            assertWave(creator.baseUri(), CONTEXT, 200);
            assertWave(creator.baseUri(), CONTEXT, 200);
        }
    }

    @Test
    void forwardingPermitsAreReleasedAfterApplicationRejection() throws Exception {
        try (CreatorServer creator = start("rejection-release")) {
            assertWave(creator.baseUri(), "{", 422);
            assertWave(creator.baseUri(), "{", 422);
        }
    }

    @Test
    void forwardingPermitsAreReleasedAfterChildProcessFailure() throws Exception {
        try (CreatorServer creator = start("process-failure-release");
             SaturatedRequests requests = SaturatedRequests.open(creator.baseUri(), FORWARD_LIMIT)) {
            stop(pid(creator.baseUri()));

            assertThat(requests.complete()).allSatisfy(response ->
                    assertThat(response).contains(" 503 ", "{\"status\":\"unavailable\"}")
            );
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertThat(request(client, creator.baseUri(), CONTEXT))
                        .extracting(HttpResponse::statusCode, HttpResponse::body)
                        .containsExactly(503, "{\"status\":\"unavailable\"}");
            }
        }
    }

    @Test
    void closeDuringActiveForwardingSynchronouslyStopsTheChildAndReleasesThePort() throws Exception {
        final CreatorServer creator = start("active-close");
        final URI uri = creator.baseUri();
        final long pid = pid(uri);
        try (SaturatedRequests requests = SaturatedRequests.open(uri, FORWARD_LIMIT + 1)) {
            assertThat(requests.awaitRejection()).contains(" 503 ");

            final Thread closer = Thread.ofPlatform().start(creator::close);
            closer.join(Duration.ofSeconds(15));

            assertThat(closer.isAlive()).isFalse();
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.setReuseAddress(true);
                replacement.bind(new InetSocketAddress("127.0.0.1", uri.getPort()));
                assertThat(replacement.isBound()).isTrue();
            }
        } finally {
            creator.close();
        }
    }

    @Test
    void concurrentRepeatedCloseWaitsForProcessShutdownAndPreservesInterruption() throws Exception {
        final CreatorServer creator = start("concurrent-close");
        final long pid = pid(creator.baseUri());
        try {
            assertConcurrentClose(pid, creator::close);
        } finally {
            creator.close();
        }
    }

    @Test
    void developmentApplicationCloseIsSynchronousIdempotentAndInterruptPreserving() throws Exception {
        final DevelopmentApplication application = application("application-close", 1);
        final long pid = number(application.snapshot(), "pid");
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            final Future<CloseResult> closer = executor.submit(() -> {
                Thread.currentThread().interrupt();
                application.close();
                application.close();
                return result(pid);
            });

            assertClosed(closer.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentDevelopmentApplicationCloseWaitsForProcessShutdownAndPreservesInterruption() throws Exception {
        final DevelopmentApplication application = application("application-concurrent-close", 2);
        final long pid = number(application.snapshot(), "pid");
        try {
            assertConcurrentClose(pid, application::close);
        } finally {
            application.close();
        }
    }

    @Test
    void closedDevelopmentApplicationRejectsForwardDeterministically() throws Exception {
        final DevelopmentApplication application = application("application-closed", 3);
        application.close();

        assertThat(application.run("command", CONTEXT.getBytes(StandardCharsets.UTF_8), true))
                .extracting(DevelopmentApplication.Response::status, DevelopmentApplication.Response::body)
                .containsExactly(503, "{\"status\":\"unavailable\"}");
    }

    @Test
    void interruptedDevelopmentApplicationRequestReturnsCancelledAndPreservesInterruption() throws Exception {
        final Path workspace = directory.resolve("application-interrupted-request");
        final Path marker = workspace.resolve("slow.started");
        Files.createDirectories(workspace);
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(slowStep(750)),
                SlowStepHandler.class
        );
        final String source = slowProject("application-interrupted-request", marker, 750);
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);

        final DevelopmentApplication application = DevelopmentApplication.start(
                4,
                project,
                (CompileResult.Compiled) result
        );
        final CompletableFuture<InterruptedRun> completed = new CompletableFuture<>();
        final Thread caller = Thread.ofPlatform().start(() -> {
            try {
                final DevelopmentApplication.Response response = application.run(
                        "command",
                        ("{\"payload\":{\"marker\":\"" + json(marker.toString()) + "\"}}")
                                .getBytes(StandardCharsets.UTF_8),
                        true
                );
                completed.complete(new InterruptedRun(response, Thread.currentThread().isInterrupted()));
            } catch (final Throwable failure) {
                completed.completeExceptionally(failure);
            }
        });
        try {
            await(marker);
            caller.interrupt();

            final InterruptedRun interrupted = completed.get(5, TimeUnit.SECONDS);
            assertThat(interrupted.response())
                    .extracting(DevelopmentApplication.Response::status, DevelopmentApplication.Response::body)
                    .containsExactly(409, "{\"status\":\"cancelled\"}");
            assertThat(interrupted.interrupted()).isTrue();
        } finally {
            caller.interrupt();
            caller.join(Duration.ofSeconds(5));
            application.close();
        }
    }

    @Test
    void developmentApplicationCloseTerminatesStepChildAndGrandchildProcesses() throws Exception {
        final Path marker = directory.resolve("established-process-tree.pids");
        final GeneratedApplicationFixture application = processTreeApplication(
                "established-process-tree",
                "established",
                marker
        );
        List<Long> pids = List.of();
        try {
            assertThat(application.run(
                    "command",
                    "{\"payload\":{\"marker\":\"" + json(marker.toString()) + "\"}}"
            ).status()).isEqualTo(200);
            pids = awaitPids(marker, 2);

            application.close();

            assertThat(pids).allSatisfy(pid -> assertThat(awaitExit(pid)).isTrue());
        } finally {
            application.close();
            pids.forEach(CreatorForwardingLifecycleE2eTest::stopIfAlive);
        }
    }

    @Test
    void developmentApplicationClosePreventsAChildForkAfterApplicationExit() throws Exception {
        final Path marker = directory.resolve("late-process-tree.pids");
        final GeneratedApplicationFixture application = processTreeApplication(
                "late-process-tree",
                "after-exit",
                marker
        );
        List<Long> pids = List.of();
        try {
            assertThat(application.run(
                    "command",
                    "{\"payload\":{\"marker\":\"" + json(marker.toString()) + "\"}}"
            ).status()).isEqualTo(200);
            pids = awaitPids(marker, 1);

            application.close();
            Thread.sleep(500);
            pids = Files.readAllLines(marker, StandardCharsets.UTF_8).stream()
                    .filter(value -> !value.isBlank())
                    .map(Long::parseLong)
                    .toList();

            assertThat(pids).hasSize(1);
            assertThat(awaitExit(pids.getFirst())).isTrue();
        } finally {
            application.close();
            pids.forEach(CreatorForwardingLifecycleE2eTest::stopIfAlive);
        }
    }

    @Test
    void developmentApplicationCloseLetsAnActiveStepFinishBeforeStoppingTheChild() throws Exception {
        final Path marker = directory.resolve("graceful-close.started");
        final StepDefinition slow = slowStep(750);
        final GeneratedApplicationFixture application = GeneratedApplicationFixture.start(
                directory.resolve("graceful-close"),
                slowProject("graceful-close", marker, 750),
                List.of(slow),
                SlowStepHandler.class
        );
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<DevelopmentApplication.Response> response = executor.submit(() ->
                    application.run("command", "{\"payload\":{\"marker\":\""
                            + json(marker.toString()) + "\"}}")
            );
            await(marker);

            application.close();

            assertThat(response.get(5, TimeUnit.SECONDS))
                    .extracting(DevelopmentApplication.Response::status)
                    .isEqualTo(200);
        } finally {
            application.close();
        }
    }

    @Test
    void rollingReplacementForciblyStopsADrainedOutChildAndCommitsTheCandidate() throws Exception {
        final Path workspace = directory.resolve("drain-timeout-roll");
        final Path project = workspace.resolve("railix.project.json");
        final Path marker = workspace.resolve("slow.started");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(slowStep(20_000)),
                SlowStepHandler.class
        );
        Files.writeString(
                project,
                slowProject("drain-timeout-roll", marker, 20_000),
                StandardCharsets.UTF_8
        );

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));
             HttpClient client = HttpClient.newHttpClient();
             ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            final long previousPid = pid(creator.baseUri());
            final Future<HttpResponse<String>> active = requests.submit(() -> request(
                    client,
                    creator.baseUri(),
                    "{\"payload\":{\"marker\":\"" + json(marker.toString()) + "\"}}"
            ));
            await(marker);

            final String replacement = CreatorProjects.lowercaseCli();
            final Future<HttpResponse<String>> rolling = requests.submit(() ->
                    postProject(creator.baseUri(), replacement)
            );

            assertThatThrownBy(() -> active.get(11, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            final HttpResponse<String> response = rolling.get(20, TimeUnit.SECONDS);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(pid(creator.baseUri())).isNotEqualTo(previousPid);
            assertThat(awaitExit(previousPid)).isTrue();
            assertThat(Files.readString(project)).contains("\"id\":\"atomic-byte-forge\"");
            assertThat(response.body()).doesNotContain("retirement");
            assertThat(active.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(500);
        }
    }

    @Test
    void applicationReadsDoNotReportCleanupFailureWhileRetirementIsDraining() throws Exception {
        final Path workspace = directory.resolve("ordinary-retirement");
        final Path project = workspace.resolve("railix.project.json");
        final Path marker = workspace.resolve("slow.started");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(slowStep(4_000)),
                SlowStepHandler.class
        );
        Files.writeString(
                project,
                slowProject("ordinary-retirement", marker, 4_000),
                StandardCharsets.UTF_8
        );

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));
             HttpClient client = HttpClient.newHttpClient();
             ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            final long previousPid = pid(creator.baseUri());
            final Future<HttpResponse<String>> active = requests.submit(() -> request(
                    client,
                    creator.baseUri(),
                    "{\"payload\":{\"marker\":\"" + json(marker.toString()) + "\"}}"
            ));
            await(marker);
            final Future<HttpResponse<String>> rolling = requests.submit(() ->
                    postProject(creator.baseUri(), CreatorProjects.lowercaseCli())
            );

            awaitPidChange(creator.baseUri(), previousPid);
            assertThat(application(creator.baseUri()).values()).doesNotContainKey("retirement");
            assertThat(active.get(6, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(rolling.get(6, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void committedReplacementReportsRealArtifactCleanupFailureWithoutRollingBack() throws Exception {
        final Path project = directory.resolve("cleanup-warning/railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, CreatorProjects.empty("cleanup-warning-before"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final RailixValue.ObjectValue before = application(creator.baseUri());
            final long previousPid = number(before, "pid");
            final Path previousArtifact = Path.of(text(before, "build_path")).getParent();
            final Set<PosixFilePermission> permissions = makeReadOnly(previousArtifact);
            try {
                final String replacement = CreatorProjects.empty("cleanup-warning-after");
                final HttpResponse<String> response = postProject(creator.baseUri(), replacement);

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains(
                        "\"retirement\":{\"phase\":\"artifact-deletion\","
                                + "\"state\":\"cleanup-pending\"}"
                );
                assertThat(pid(creator.baseUri())).isNotEqualTo(previousPid);
                assertThat(awaitExit(previousPid)).isTrue();
                assertThat(Files.readString(project)).contains("cleanup-warning-after");
                assertThat(previousArtifact).exists();
            } finally {
                restore(previousArtifact, permissions);
            }
        }
    }

    @Test
    void unresolvedArtifactCleanupBlocksTheNextBuildWithoutChangingDeployedState() throws Exception {
        final Path project = directory.resolve("cleanup-block/railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, CreatorProjects.empty("cleanup-block-before"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final Path previousArtifact = Path.of(text(application(creator.baseUri()), "build_path")).getParent();
            final Set<PosixFilePermission> permissions = makeReadOnly(previousArtifact);
            try {
                assertThat(postProject(creator.baseUri(), CreatorProjects.empty("cleanup-block-active")).statusCode())
                        .isEqualTo(200);
                final long activePid = pid(creator.baseUri());
                final String persisted = Files.readString(project);
                final List<String> artifacts = artifacts(project);

                final HttpResponse<String> blocked = postProject(
                        creator.baseUri(),
                        CreatorProjects.empty("cleanup-block-rejected")
                );

                assertThat(blocked.statusCode()).isEqualTo(503);
                assertThat(blocked.body()).contains(
                        "\"reason\":\"cleanup-pending\"",
                        "\"phase\":\"artifact-deletion\""
                );
                assertThat(pid(creator.baseUri())).isEqualTo(activePid);
                assertThat(Files.readString(project)).isEqualTo(persisted);
                assertThat(artifacts(project)).isEqualTo(artifacts);
            } finally {
                restore(previousArtifact, permissions);
            }
        }
    }

    @Test
    void restoredArtifactCleanupAllowsExactlyOneReplacementAndClearsTheWarning() throws Exception {
        final Path project = directory.resolve("cleanup-recovery/railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, CreatorProjects.empty("cleanup-recovery-before"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final Path retiredArtifact = Path.of(text(application(creator.baseUri()), "build_path")).getParent();
            final Set<PosixFilePermission> permissions = makeReadOnly(retiredArtifact);
            assertThat(postProject(creator.baseUri(), CreatorProjects.empty("cleanup-recovery-active")).statusCode())
                    .isEqualTo(200);
            final RailixValue.ObjectValue active = application(creator.baseUri());
            final long activePid = number(active, "pid");
            final Path activeArtifact = Path.of(text(active, "build_path")).getParent();
            restore(retiredArtifact, permissions);

            final HttpResponse<String> recovered = postProject(
                    creator.baseUri(),
                    CreatorProjects.empty("cleanup-recovery-after")
            );

            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(recovered.body()).doesNotContain("retirement");
            assertThat(pid(creator.baseUri())).isNotEqualTo(activePid);
            assertThat(awaitExit(activePid)).isTrue();
            assertThat(retiredArtifact).doesNotExist();
            assertThat(activeArtifact).doesNotExist();
            assertThat(artifacts(project)).hasSize(1);
            assertThat(Files.readString(project)).contains("cleanup-recovery-after");
        }
    }

    @Test
    void creatorCloseRetainsFailedRetirementCleanupUntilARealRetrySucceeds() throws Exception {
        final Path project = directory.resolve("close-cleanup-retry/railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, CreatorProjects.empty("close-cleanup-before"), StandardCharsets.UTF_8);
        final CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"));
        final URI uri = creator.baseUri();
        final Path retiredArtifact = Path.of(text(application(uri), "build_path")).getParent();
        final Set<PosixFilePermission> permissions = makeReadOnly(retiredArtifact);
        assertThat(postProject(uri, CreatorProjects.empty("close-cleanup-active")).statusCode()).isEqualTo(200);
        final long activePid = pid(uri);

        assertThatThrownBy(creator::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Retired generated application did not clean up");
        assertThat(retiredArtifact).exists();
        assertThat(ProcessHandle.of(activePid).map(ProcessHandle::isAlive).orElse(false)).isFalse();

        restore(retiredArtifact, permissions);
        creator.close();

        assertThat(retiredArtifact).doesNotExist();
        try (ServerSocket replacement = new ServerSocket()) {
            replacement.setReuseAddress(true);
            replacement.bind(new InetSocketAddress("127.0.0.1", uri.getPort()));
            assertThat(replacement.isBound()).isTrue();
        }
    }

    @Test
    void repeatedCreatorLifecycleLeavesNoOwnedProcessesOrListeningPorts() throws Exception {
        final List<Long> pids = new ArrayList<>();
        for (int cycle = 0; cycle < 8; cycle++) {
            final CreatorServer creator = start("repeated-" + cycle);
            final URI uri = creator.baseUri();
            pids.add(pid(uri));
            creator.close();
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.setReuseAddress(true);
                replacement.bind(new InetSocketAddress("127.0.0.1", uri.getPort()));
            }
        }

        assertThat(pids).allSatisfy(pid ->
                assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse()
        );
    }

    @Test
    void closedCreatorIsNotRetainedByItsServerExecutorOrApplication() throws Exception {
        final ReferenceQueue<CreatorServer> collected = new ReferenceQueue<>();
        final WeakReference<CreatorServer> creator = closedCreator(collected);

        assertThat(awaitCollected(creator, collected)).isTrue();
    }

    @Test
    void closedDevelopmentApplicationIsNotRetainedByItsProcessOrOutputReader() throws Exception {
        final ReferenceQueue<DevelopmentApplication> collected = new ReferenceQueue<>();
        final WeakReference<DevelopmentApplication> application = closedApplication(collected);

        assertThat(awaitCollected(application, collected)).isTrue();
    }

    private CreatorServer start(final String id) throws IOException {
        final Path project = directory.resolve(id).resolve("railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, CreatorProjects.lowercaseCli().replace("lowercase-cli", id), StandardCharsets.UTF_8);
        return CreatorServer.start(0, project, directory.resolve("railix-home"));
    }

    private static StepDefinition slowStep(final long delayMillis) {
        return StepDefinition.named("test.slow", "1")
                .input("marker", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "marker"))
                .input("delay_millis", StepDefinition.Input.json(ValueShape.NUMBER)
                        .defaultValue(RailixValue.number(delayMillis)))
                .run(SlowStepHandler.class);
    }

    private static String slowProject(final String id, final Path marker, final long delayMillis) {
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"slow","payload":{"marker":"%s"}
                  }]},
                  {"id":"slow","use":"test.slow","inputs":{"delay_millis":%d}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"slow"},
                  {"from":"slow.next","to":"end"}
                ]}
                """.formatted(id, json(marker.toString()), delayMillis);
    }

    private GeneratedApplicationFixture processTreeApplication(
            final String id,
            final String mode,
            final Path marker
    ) throws IOException {
        final StepDefinition processTree = StepDefinition.named("test.process-tree", "1")
                .input("mode", StepDefinition.Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string(mode)))
                .input("marker", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "marker"))
                .run(ProcessTreeStepHandler.class);
        final String project = """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"process-tree","payload":{"marker":"%s"}
                  }]},
                  {"id":"process-tree","use":"test.process-tree","inputs":{
                    "mode":"%s","marker":["context","payload","marker"]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"process-tree"},
                  {"from":"process-tree.next","to":"end"}
                ]}
                """.formatted(id, json(marker.toString()), mode);
        return GeneratedApplicationFixture.start(
                directory.resolve(id),
                project,
                List.of(processTree),
                ProcessTreeStepHandler.class
        );
    }

    private static void await(final Path marker) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(marker) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(marker).exists();
    }

    private static String json(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static HttpResponse<String> postProject(final URI uri, final String source) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(uri.resolve("/api/project"))
                            .timeout(Duration.ofSeconds(40))
                            .header("Content-Type", "application/json")
                            .header("X-Railix-Creator-Token", token(uri))
                            .POST(HttpRequest.BodyPublishers.ofString(source, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        }
    }

    private static Set<PosixFilePermission> makeReadOnly(final Path directory) throws IOException {
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, permissions.stream()
                .filter(permission -> permission != PosixFilePermission.OWNER_WRITE
                        && permission != PosixFilePermission.GROUP_WRITE
                        && permission != PosixFilePermission.OTHERS_WRITE)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return permissions;
    }

    private static void restore(final Path directory, final Set<PosixFilePermission> permissions) throws IOException {
        if (Files.exists(directory)) {
            Files.setPosixFilePermissions(directory, permissions);
        }
    }

    private static List<String> artifacts(final Path project) throws IOException {
        final Path build = project.getParent().resolve(".railix/build");
        try (var entries = Files.list(build)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private DevelopmentApplication application(final String id, final long generation) throws IOException {
        final String source = CreatorProjects.lowercaseCli().replace("lowercase-cli", id);
        final CompileResult result = ProjectCompiler.compileApplication(source, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path project = directory.resolve(id).resolve("railix.project.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, source, StandardCharsets.UTF_8);
        return DevelopmentApplication.start(generation, project, (CompileResult.Compiled) result);
    }

    private WeakReference<CreatorServer> closedCreator(final ReferenceQueue<CreatorServer> collected)
            throws Exception {
        final CreatorServer creator = start("creator-retention");
        creator.close();
        return new WeakReference<>(creator, collected);
    }

    private WeakReference<DevelopmentApplication> closedApplication(
            final ReferenceQueue<DevelopmentApplication> collected
    ) throws Exception {
        final DevelopmentApplication application = application("application-retention", 2);
        application.close();
        return new WeakReference<>(application, collected);
    }

    private static boolean awaitCollected(
            final WeakReference<?> reference,
            final ReferenceQueue<?> collected
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20 && reference.get() != null; attempt++) {
            System.gc();
            if (collected.remove(100) == reference) {
                return true;
            }
        }
        return reference.get() == null;
    }

    private static void assertWave(final URI uri, final String body, final int expectedStatus) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int request = 0; request < FORWARD_LIMIT; request++) {
                responses.add(executor.submit(() -> {
                    start.await();
                    return request(client, uri, body);
                }));
            }
            start.countDown();
            for (final Future<HttpResponse<String>> response : responses) {
                assertThat(response.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(expectedStatus);
            }
        }
    }

    private static HttpResponse<String> request(
            final HttpClient client,
            final URI uri,
            final String body
    ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(uri.resolve("/api/run/command"))
                        .timeout(Duration.ofSeconds(40))
                        .header("Content-Type", "application/json")
                        .header("X-Railix-Creator-Token", token(uri))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private static long pid(final URI uri) throws Exception {
        return number(application(uri), "pid");
    }

    private static RailixValue.ObjectValue application(final URI uri) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri.resolve("/api/application"))
                            .header("X-Railix-Creator-Token", token(uri))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            final RailixValue.ObjectValue value = (RailixValue.ObjectValue) (
                    (dev.nanonative.railix.core.value.RailixJson.Parsed)
                            dev.nanonative.railix.core.value.RailixJson.parse(response.body())
            ).value();
            return value;
        }
    }

    private static long number(final RailixValue.ObjectValue value, final String field) {
        return ((RailixValue.NumberValue) value.values().get(field)).value().longValueExact();
    }

    private static String text(final RailixValue.ObjectValue value, final String field) {
        return ((RailixValue.StringValue) value.values().get(field)).value();
    }

    private static boolean awaitExit(final long pid) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false;
    }

    private static List<Long> awaitPids(final Path marker, final int count) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<String> values = List.of();
        while (values.size() < count && System.nanoTime() < deadline) {
            if (Files.exists(marker)) {
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

    private static void stopIfAlive(final long pid) {
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
    }

    private static void awaitPidChange(final URI uri, final long previousPid) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pid(uri) == previousPid && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(pid(uri)).isNotEqualTo(previousPid);
    }

    private static void assertConcurrentClose(final long pid, final Runnable close) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory())) {
            final List<Future<CloseResult>> closers = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                closers.add(executor.submit(() -> {
                    start.await();
                    Thread.currentThread().interrupt();
                    close.run();
                    return result(pid);
                }));
            }
            start.countDown();
            for (final Future<CloseResult> closer : closers) {
                assertClosed(closer.get(15, TimeUnit.SECONDS));
            }
        }
    }

    private static CloseResult result(final long pid) {
        return new CloseResult(
                Thread.currentThread().isInterrupted(),
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        );
    }

    private static void assertClosed(final CloseResult result) {
        assertThat(result.interrupted()).isTrue();
        assertThat(result.processAlive()).isFalse();
    }

    private static void stop(final long pid) throws Exception {
        final ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        process.destroyForcibly();
        process.onExit().get(5, TimeUnit.SECONDS);
    }

    private static final class SaturatedRequests implements AutoCloseable {
        private final List<Socket> sockets;
        private final ExecutorService readers;
        private final CompletionService<String> responses;
        private final List<String> completed = new ArrayList<>();

        private SaturatedRequests(
                final List<Socket> sockets,
                final ExecutorService readers,
                final CompletionService<String> responses
        ) {
            this.sockets = sockets;
            this.readers = readers;
            this.responses = responses;
        }

        static SaturatedRequests open(final URI uri, final int count) throws IOException {
            return open(uri, count, "/api/run/command");
        }

        static SaturatedRequests openProject(final URI uri, final int count) throws IOException {
            return open(uri, count, "/api/project");
        }

        private static SaturatedRequests open(
                final URI uri,
                final int count,
                final String path
        ) throws IOException {
            final List<Socket> sockets = new ArrayList<>();
            final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
            final CompletionService<String> responses = new ExecutorCompletionService<>(readers);
            try {
                for (int index = 0; index < count; index++) {
                    final Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
                    socket.getOutputStream().write(("""
                            POST %s HTTP/1.1\r
                            Host: %s:%d\r
                            Content-Type: application/json\r
                            X-Railix-Creator-Token: %s\r
                            Content-Length: 1048576\r
                            Connection: close\r
                            \r
                            {""".formatted(path, uri.getHost(), uri.getPort(), token(uri)))
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    sockets.add(socket);
                    responses.submit(() -> {
                        try {
                            return response(socket.getInputStream());
                        } catch (final IOException exception) {
                            return "connection-closed";
                        }
                    });
                }
                return new SaturatedRequests(sockets, readers, responses);
            } catch (final IOException | RuntimeException failure) {
                sockets.forEach(SaturatedRequests::close);
                readers.shutdownNow();
                readers.close();
                throw failure;
            }
        }

        String awaitRejection() throws Exception {
            final Future<String> response = responses.poll(10, TimeUnit.SECONDS);
            assertThat(response).as("one excess forwarding request must be rejected").isNotNull();
            final String value = response.get();
            completed.add(value);
            return value;
        }

        List<String> release() throws Exception {
            for (final Socket socket : sockets) {
                try {
                    socket.shutdownOutput();
                } catch (final IOException ignored) {
                    // The saturated connection may already be closed by the server.
                }
            }
            return collect();
        }

        List<String> complete() throws Exception {
            final byte[] remainder = new byte[1_048_575];
            java.util.Arrays.fill(remainder, (byte) ' ');
            remainder[remainder.length - 1] = '}';
            for (final Socket socket : sockets) {
                socket.getOutputStream().write(remainder);
                socket.getOutputStream().flush();
            }
            return collect();
        }

        List<String> awaitAll() throws Exception {
            return collect();
        }

        private List<String> collect() throws Exception {
            while (completed.size() < sockets.size()) {
                final Future<String> response = responses.poll(20, TimeUnit.SECONDS);
                assertThat(response).as("every accepted request must leave the server").isNotNull();
                completed.add(response.get());
            }
            return List.copyOf(completed);
        }

        @Override
        public void close() {
            sockets.forEach(SaturatedRequests::close);
            readers.shutdownNow();
            readers.close();
        }

        private static void close(final Socket socket) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // All observable lifecycle assertions happen before cleanup.
            }
        }

        private static String response(final InputStream input) throws IOException {
            final byte[] separator = {'\r', '\n', '\r', '\n'};
            final ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < separator.length && header.size() < 16_384) {
                final int next = input.read();
                if (next < 0) {
                    throw new IOException("HTTP response ended before its headers completed.");
                }
                header.write(next);
                matched = next == separator[matched] ? matched + 1 : next == separator[0] ? 1 : 0;
            }
            if (matched < separator.length) {
                throw new IOException("HTTP response headers exceeded 16384 bytes.");
            }
            final String headers = header.toString(StandardCharsets.US_ASCII);
            int length = -1;
            for (final String line : headers.split("\r\n")) {
                if (line.regionMatches(true, 0, "Content-length:", 0, "Content-length:".length())) {
                    length = Integer.parseInt(line.substring("Content-length:".length()).strip());
                }
            }
            if (length < 0) {
                throw new IOException("HTTP response did not declare Content-Length.");
            }
            final byte[] body = input.readNBytes(length);
            if (body.length != length) {
                throw new IOException("HTTP response ended before its body completed.");
            }
            return headers + new String(body, StandardCharsets.UTF_8);
        }
    }

    private record CloseResult(boolean interrupted, boolean processAlive) {
    }

    private record InterruptedRun(DevelopmentApplication.Response response, boolean interrupted) {
    }

    private static String token(final URI uri) {
        return uri.getRawFragment().substring("token=".length());
    }
}
