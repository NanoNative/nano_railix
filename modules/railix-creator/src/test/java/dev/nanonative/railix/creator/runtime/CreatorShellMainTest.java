package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
import dev.nanonative.railix.kernel.runtime.AppPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorShellMainTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDeclareEmbeddedFaviconAtCreatorHttpBoundary() throws Exception {
        final Path repoRoot = tempDir.resolve("repo");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of("--repo-root", repoRoot.toString(), "--port", "0"),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("<link rel=\"icon\" href=\"data:image/svg+xml,");
        }
    }

    @Test
    void shouldServeCreatorShellAndWorkspaceApiFromLaunchBoundary() throws Exception {
        final Path repoRoot = tempDir.resolve("repo");
        final Path runsRoot = tempDir.resolve("runs");
        seedRepo(repoRoot);
        seedRuns(
                runsRoot,
                """
                {"timestamp":"2026-07-11T09:10:00Z","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-1","method":"POST","path":"/orders","responseStatus":201,"durationMs":7,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );

        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(stdout),
                new PrintStream(stderr)
        )) {
            assertThat(runningCreator.result().succeeded()).isTrue();
            assertThat(stderr.toString()).isEmpty();
            assertThat(stdout.toString()).contains("creatorUrl=http://127.0.0.1:");

            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> indexResponse = client.send(
                    HttpRequest.newBuilder(baseUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> appJsResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/assets/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(indexResponse.statusCode()).isEqualTo(200);
            assertThat(appJsResponse.statusCode()).isEqualTo(200);
            assertThat(indexResponse.body())
                    .contains("Slice 10 / Remote Execution Boundary")
                    .contains("Railix Creator Shell")
                    .contains("Graph Editor Shell")
                    .contains("Run Signal Viewer")
                    .contains("Recent Remote Requests")
                    .contains("Recent HTTP Traffic")
                    .contains("Instance Discovery")
                    .contains("Launch Persisted Run")
                    .contains("Authoring Spec Editor")
                    .contains("Create Example Spec")
                    .contains("Export JSON")
                    .contains("repo-root-free packaged Creator launch")
                    .contains("Scanning workspace for runnable persisted inputs, supported authoring specs, and packaging evidence");
            assertThat(appJsResponse.body())
                    .doesNotContain("Raw signal preview stays on the live/latest workspace surface in the current slice.")
                    .contains("Terminal failure summary:");

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat(workspace)
                    .containsEntry("title", "Railix Creator Shell")
                    .containsEntry("controlEndpointReady", true)
                    .containsEntry("controlEndpointStatus", "creator-owned-live-run-sessions");
            assertThat((String) workspace.get("repoRoot")).isEqualTo(repoRoot.toAbsolutePath().normalize().toString());
            assertThat((java.util.List<?>) workspace.get("packs")).hasSize(1);
            assertThat((java.util.List<?>) workspace.get("runs")).hasSize(1);
            final Map<String, Object> controlSessions = mapValue(workspace, "controlSessions");
            assertThat(controlSessions)
                    .containsEntry("ready", true)
                    .containsEntry("activeCount", 0)
                    .containsEntry("trackedCount", 0);
            final Map<String, Object> instances = mapValue(workspace, "instances");
            assertThat(instances)
                    .containsEntry("title", "Instance Discovery")
                    .containsEntry("status", "shared-filesystem-instance-registry")
                    .containsEntry("totalCount", 1)
                    .containsEntry("activeCount", 1)
                    .containsEntry("staleCount", 0);
            assertThat(currentInstance(instances))
                    .containsEntry("repoRoot", repoRoot.toAbsolutePath().normalize().toString())
                    .containsEntry("runsRoot", runsRoot.toAbsolutePath().normalize().toString())
                    .containsEntry("loopbackControlUrl", baseUri.toString())
                    .containsEntry("state", "active")
                    .containsEntry("currentInstance", true);
            final Map<String, Object> stepProviders = mapValue(currentInstance(instances), "stepProviders");
            assertThat(numberValue(stepProviders, "providerCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "reportedProviderCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "unreportedProviderCount")).isEqualTo(0);
            assertThat(numberValue(stepProviders, "providerModuleCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "supportedUseCount")).isGreaterThanOrEqualTo(15);
            assertThat(numberValue(stepProviders, "supportedResourceRefPatternCount")).isGreaterThanOrEqualTo(1);
            assertThat(listOfStrings(stepProviders, "providerModuleIds"))
                    .contains("railix.std.data", "railix.std.http", "railix.std.store", "railix.std.trigger");
            assertThat(listOfStrings(stepProviders, "supportedUses"))
                    .contains(
                            "railix.std.data.DataTransform",
                            "railix.std.http.HttpTrigger",
                            "railix.std.store.StoreRead",
                            "railix.std.trigger.ManualTrigger"
                    );
            assertThat(listOfStrings(stepProviders, "stepKinds")).contains("NORMAL", "TRIGGER");
            assertThat(listOfStrings(stepProviders, "supportedResourceRefPatterns")).contains("store/*");
            final Map<String, Object> runtimeIdentity = mapValue(currentInstance(instances), "runtimeIdentity");
            assertThat(runtimeIdentity)
                    .containsEntry("contractVersion", 1)
                    .containsEntry("status", "provider-backed")
                    .containsEntry("identitySource", "service-loader-step-providers")
                    .containsEntry("reportsCompleteUseCatalog", true)
                    .containsEntry("remoteExecutionCompatible", false);
            assertThat(numberValue(runtimeIdentity, "providerModuleCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(runtimeIdentity, "supportedUseCount")).isGreaterThanOrEqualTo(15);
            assertThat(numberValue(runtimeIdentity, "supportedResourceRefPatternCount")).isGreaterThanOrEqualTo(1);
            assertThat(listOfStrings(runtimeIdentity, "providerModuleIds"))
                    .contains("railix.std.data", "railix.std.http", "railix.std.store", "railix.std.trigger");
            assertThat(listOfStrings(runtimeIdentity, "supportedResourceRefPatterns")).contains("store/*");
            assertThat(stringValue(runtimeIdentity, "capabilityDigest")).startsWith("sha256:");
            assertThat(mapValue(currentInstance(instances), "load"))
                    .containsEntry("activeControlSessions", 0)
                    .containsEntry("maxActiveSessions", 4);
            assertThat(mapValue(currentInstance(instances), "runtime"))
                    .containsEntry("osName", System.getProperty("os.name"))
                    .containsEntry("osArch", System.getProperty("os.arch"))
                    .containsEntry("javaVersion", System.getProperty("java.version"));
            assertThat(mapValue(currentInstance(instances), "trust"))
                    .containsEntry("mode", "loopback-only")
                    .containsEntry("sharedRegistryDiscovery", true)
                    .containsEntry("remoteControlSupported", false)
                    .containsEntry("remoteExecutionSupported", false);
            final Map<String, Object> workspaceAlignment = mapValue(currentInstance(instances), "workspaceRuntimeAlignment");
            assertThat(workspaceAlignment)
                    .containsEntry("workspacePackCount", 1)
                    .containsEntry("runtimeProviderModuleCount", 4)
                    .containsEntry("matchedPackCount", 0)
                    .containsEntry("workspaceOnlyPackCount", 1)
                    .containsEntry("runtimeOnlyModuleCount", 4)
                    .containsEntry("fullyAligned", false);
            assertThat(listOfStrings(workspaceAlignment, "workspacePackIds")).containsExactly("railix.demo.pack");
            assertThat(listOfStrings(workspaceAlignment, "matchedPackIds")).isEmpty();
            assertThat(listOfStrings(workspaceAlignment, "workspaceOnlyPackIds")).containsExactly("railix.demo.pack");
            assertThat(listOfStrings(workspaceAlignment, "runtimeOnlyModuleIds"))
                    .contains("railix.std.data", "railix.std.http", "railix.std.store", "railix.std.trigger");
            assertThat(mapValue(currentInstance(instances), "executionModel"))
                    .containsEntry("localStepExecutionSupported", true)
                    .containsEntry("remoteStepExecutionSupported", false)
                    .containsEntry("queuedStepExecutionSupported", true)
                    .containsEntry("crossInstancePermissionSharingSupported", false)
                    .containsEntry("cacheShareSupported", false);
            assertThat(mapValue(currentInstance(instances), "distributedSupport"))
                    .containsEntry("status", "foundation-only")
                    .containsEntry("runtimePackIdentitySupported", false)
                    .containsEntry("remoteExecutionBoundarySupported", true)
                    .containsEntry("distributedQueueSupported", false)
                    .containsEntry("crossInstancePermissionPropagationSupported", false)
                    .containsEntry("sharedCacheDigestSupported", false);
            final Map<String, Object> latestRunDetails = mapValue(workspace, "latestRunDetails");
            assertThat(latestRunDetails)
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("runId", "run-creator-1")
                    .containsEntry("outcome", "ok")
                    .containsEntry("replyMode", "immediate");
            final Map<String, Object> graphActivity = mapValue(latestRunDetails, "graphActivity");
            assertThat(graphActivity).containsEntry("available", true);
            assertThat(findGraphNode(latestRunDetails, "trigger")).containsEntry("state", "complete");
            assertThat(findGraphNode(latestRunDetails, "step")).containsEntry("state", "complete");
            assertThat(findGraphNode(latestRunDetails, "patch")).containsEntry("state", "complete");
            assertThat(findGraphNode(latestRunDetails, "reply")).containsEntry("state", "complete");
            assertThat(findGraphNode(latestRunDetails, "finish")).containsEntry("state", "complete");
            assertThat(listOfMaps(latestRunDetails, "timeline")).isNotEmpty();
            assertThat(findTimelineEvent(latestRunDetails, "context.patched"))
                    .containsEntry("stepId", "build-reply")
                    .containsEntry("attempt", 1)
                    .containsEntry("changedPathCount", 2);
            final Map<String, Object> latestContextDiff = mapValue(latestRunDetails, "contextDiff");
            assertThat(latestContextDiff).containsEntry("available", true);
            assertThat(listOfStrings(latestContextDiff, "changedPaths"))
                    .containsExactly("reply.mode", "reply.payload.message");
            final Map<String, Object> latestMetrics = mapValue(latestRunDetails, "metrics");
            assertThat(latestMetrics).containsEntry("count", 1);
            assertThat(listOfMaps(latestMetrics, "series")).hasSize(1);
            assertThat(listOfMaps(latestMetrics, "series").getFirst())
                    .containsEntry("name", "railix.metric.value")
                    .containsEntry("count", 1)
                    .containsEntry("unit", "items");
            final Map<String, Object> latestAudits = mapValue(latestRunDetails, "audits");
            assertThat(latestAudits).containsEntry("count", 1);
            assertThat(listOfMaps(latestAudits, "events")).hasSize(1);
            assertThat(listOfMaps(latestAudits, "events").getFirst())
                    .containsEntry("eventName", "creator.seeded")
                    .containsEntry("count", 1);
            final Map<String, Object> httpTraffic = mapValue(workspace, "httpTraffic");
            assertThat(httpTraffic)
                    .containsEntry("title", "HTTP Traffic")
                    .containsEntry("columns", List.of("timestamp", "method", "path", "status", "durationMs"));
            assertThat(numberValue(httpTraffic, "totalRows")).isEqualTo(1);
            assertThat(numberValue(httpTraffic, "handledRows")).isEqualTo(1);
            assertThat(numberValue(httpTraffic, "rejectedRows")).isZero();
            assertThat(numberValue(httpTraffic, "failedRows")).isZero();
            assertThat((List<?>) httpTraffic.get("rows")).hasSize(1);
            assertThat(workspaceResponse.body()).contains("railix.demo.pack").contains("run-creator-1");
            assertThat(workspaceResponse.body()).contains("\"path\":\"/orders\"").contains("\"captureKind\":\"handled-run\"");
        }
    }

    @Test
    void shouldBuildCreatorAppImageThroughRailixPackageCommandAndServeWorkspaceApi() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-packaged-creator");
        final Path runsRoot = tempDir.resolve("runs-packaged-creator");
        final Path imageDir = tempDir.resolve("creator-runtime");
        final Path packageDir = tempDir.resolve("creator-package");
        final Path reportFile = tempDir.resolve("creator-package-report.json");
        seedRepo(repoRoot);

        final String buildOutput = runProcess(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--mode", "creator",
                "--dest", packageDir.toString(),
                "--runtime-image", imageDir.toString(),
                "--report", reportFile.toString()
        ));
        assertThat(buildOutput).contains("Created " + packageDir).contains("Wrote " + reportFile);

        final Map<String, Object> report = KernelContractCodec.parseStableJsonObject(Files.readString(reportFile));
        assertThat(report)
                .containsEntry("type", "app-image")
                .containsEntry("mode", "creator")
                .containsEntry("appName", "RailixCreator");
        final Path launcherPath = Path.of(stringValue(mapValue(report, "artifacts"), "launcherPath"));
        assertThat(Files.exists(launcherPath)).isTrue();

        try (RunningPackagedCreator runningCreator = startPackagedCreator(List.of(
                launcherPath.toString(),
                "--repo-root", repoRoot.toString(),
                "--runs-root", runsRoot.toString(),
                "--port", "0"
        ))) {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpResponse<String> indexResponse = client.send(
                    HttpRequest.newBuilder(runningCreator.baseUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> appJsResponse = client.send(
                    HttpRequest.newBuilder(runningCreator.baseUri().resolve("/assets/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(runningCreator.baseUri().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(indexResponse.statusCode()).isEqualTo(200);
            assertThat(appJsResponse.statusCode()).isEqualTo(200);
            assertThat(indexResponse.body())
                    .contains("Railix Creator Shell")
                    .contains("Launch Persisted Run");
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat(workspace)
                    .containsEntry("title", "Railix Creator Shell")
                    .containsEntry("repoRoot", repoRoot.toAbsolutePath().normalize().toString())
                    .containsEntry("runsRoot", runsRoot.toAbsolutePath().normalize().toString());
            assertThat((List<?>) workspace.get("packs")).hasSize(1);
        }
    }

    @Test
    void shouldExposeSharedInstanceRegistryAcrossCreatorShells() throws Exception {
        final Path repoRootA = tempDir.resolve("repo-instance-a");
        final Path runsRootA = tempDir.resolve("runs-instance-a");
        final Path repoRootB = tempDir.resolve("repo-instance-b");
        final Path runsRootB = tempDir.resolve("runs-instance-b");
        final Path registryRoot = tempDir.resolve("instance-registry-shared");
        seedRepo(repoRootA);
        seedRepo(repoRootB);
        seedRunsWithoutHttpCapture(runsRootA);
        seedRunsWithoutHttpCapture(runsRootB);

        try (CreatorShellMain.RunningCreator firstCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRootA.toString(),
                        "--runs-root", runsRootA.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );
             CreatorShellMain.RunningCreator secondCreator = CreatorShellMain.launch(
                     java.util.List.of(
                             "--repo-root", repoRootB.toString(),
                             "--runs-root", runsRootB.toString(),
                             "--instance-registry-root", registryRoot.toString(),
                             "--port", "0"
                     ),
                     new PrintStream(new ByteArrayOutputStream()),
                     new PrintStream(new ByteArrayOutputStream())
             )) {
            final HttpClient client = HttpClient.newHttpClient();

            final Map<String, Object> firstWorkspace = awaitWorkspaceInstances(
                    client,
                    firstCreator.result().creatorUrl(),
                    2,
                    0
            );
            final Map<String, Object> firstInstances = mapValue(firstWorkspace, "instances");
            assertThat(firstInstances)
                    .containsEntry("title", "Instance Discovery")
                    .containsEntry("status", "shared-filesystem-instance-registry")
                    .containsEntry("registryRoot", registryRoot.toAbsolutePath().normalize().toString())
                    .containsEntry("totalCount", 2)
                    .containsEntry("activeCount", 2)
                    .containsEntry("staleCount", 0);
            assertThat(listOfMaps(firstInstances, "entries")).hasSize(2);
            assertThat(currentInstance(firstInstances))
                    .containsEntry("repoRoot", repoRootA.toAbsolutePath().normalize().toString())
                    .containsEntry("runsRoot", runsRootA.toAbsolutePath().normalize().toString())
                    .containsEntry("loopbackControlUrl", firstCreator.result().creatorUrl().toString())
                    .containsEntry("state", "active")
                    .containsEntry("currentInstance", true);
            assertThat(mapValue(currentInstance(firstInstances), "load"))
                    .containsEntry("activeControlSessions", 0)
                    .containsEntry("maxActiveSessions", 4);
            assertThat(mapValue(currentInstance(firstInstances), "runtime"))
                    .containsEntry("osName", System.getProperty("os.name"))
                    .containsEntry("osArch", System.getProperty("os.arch"))
                    .containsEntry("javaVersion", System.getProperty("java.version"));
            assertThat(mapValue(currentInstance(firstInstances), "trust"))
                    .containsEntry("mode", "loopback-only")
                    .containsEntry("sharedRegistryDiscovery", true)
                    .containsEntry("remoteControlSupported", false)
                    .containsEntry("remoteExecutionSupported", false);
            assertThat(mapValue(currentInstance(firstInstances), "workspaceRuntimeAlignment"))
                    .containsEntry("workspacePackCount", 1)
                    .containsEntry("runtimeProviderModuleCount", 4)
                    .containsEntry("matchedPackCount", 0)
                    .containsEntry("workspaceOnlyPackCount", 1)
                    .containsEntry("runtimeOnlyModuleCount", 4)
                    .containsEntry("fullyAligned", false);
            final String firstRuntimeIdentityDigest = stringValue(
                    mapValue(currentInstance(firstInstances), "runtimeIdentity"),
                    "capabilityDigest"
            );
            assertThat(mapValue(currentInstance(firstInstances), "runtimeIdentity"))
                    .containsEntry("status", "provider-backed")
                    .containsEntry("identitySource", "service-loader-step-providers")
                    .containsEntry("reportsCompleteUseCatalog", true)
                    .containsEntry("remoteExecutionCompatible", false);
            assertThat(firstRuntimeIdentityDigest).startsWith("sha256:");
            assertThat(mapValue(currentInstance(firstInstances), "executionModel"))
                    .containsEntry("localStepExecutionSupported", true)
                    .containsEntry("remoteStepExecutionSupported", false)
                    .containsEntry("queuedStepExecutionSupported", true)
                    .containsEntry("crossInstancePermissionSharingSupported", false)
                    .containsEntry("cacheShareSupported", false);
            assertThat(mapValue(currentInstance(firstInstances), "distributedSupport"))
                    .containsEntry("status", "foundation-only")
                    .containsEntry("runtimePackIdentitySupported", false)
                    .containsEntry("remoteExecutionBoundarySupported", true)
                    .containsEntry("distributedQueueSupported", false)
                    .containsEntry("crossInstancePermissionPropagationSupported", false)
                    .containsEntry("sharedCacheDigestSupported", false);
            assertThat(listOfMaps(firstInstances, "entries")).anySatisfy(instance -> assertThat(instance)
                    .containsEntry("repoRoot", repoRootB.toAbsolutePath().normalize().toString())
                    .containsEntry("runsRoot", runsRootB.toAbsolutePath().normalize().toString())
                    .containsEntry("loopbackControlUrl", secondCreator.result().creatorUrl().toString())
                    .containsEntry("state", "active")
                    .containsEntry("currentInstance", false));
            assertThat(listOfMaps(firstInstances, "entries")).anySatisfy(instance -> {
                assertThat(instance)
                        .containsEntry("repoRoot", repoRootB.toAbsolutePath().normalize().toString())
                        .containsEntry("currentInstance", false);
                assertThat(mapValue(instance, "load"))
                        .containsEntry("activeControlSessions", 0)
                        .containsEntry("maxActiveSessions", 4);
                assertThat(mapValue(instance, "runtime"))
                        .containsEntry("osName", System.getProperty("os.name"))
                        .containsEntry("osArch", System.getProperty("os.arch"))
                        .containsEntry("javaVersion", System.getProperty("java.version"));
                assertThat(mapValue(instance, "trust"))
                        .containsEntry("mode", "loopback-only")
                        .containsEntry("sharedRegistryDiscovery", true)
                        .containsEntry("remoteControlSupported", false)
                        .containsEntry("remoteExecutionSupported", false);
                assertThat(mapValue(instance, "workspaceRuntimeAlignment"))
                        .containsEntry("workspacePackCount", 1)
                        .containsEntry("runtimeProviderModuleCount", 4)
                        .containsEntry("matchedPackCount", 0)
                        .containsEntry("workspaceOnlyPackCount", 1)
                        .containsEntry("runtimeOnlyModuleCount", 4)
                        .containsEntry("fullyAligned", false);
                assertThat(mapValue(instance, "runtimeIdentity"))
                        .containsEntry("status", "provider-backed")
                        .containsEntry("identitySource", "service-loader-step-providers")
                        .containsEntry("reportsCompleteUseCatalog", true)
                        .containsEntry("remoteExecutionCompatible", false);
                assertThat(stringValue(mapValue(instance, "runtimeIdentity"), "capabilityDigest"))
                        .isEqualTo(firstRuntimeIdentityDigest);
                assertThat(mapValue(instance, "executionModel"))
                        .containsEntry("localStepExecutionSupported", true)
                        .containsEntry("remoteStepExecutionSupported", false)
                        .containsEntry("queuedStepExecutionSupported", true)
                        .containsEntry("crossInstancePermissionSharingSupported", false)
                        .containsEntry("cacheShareSupported", false);
                assertThat(mapValue(instance, "distributedSupport"))
                        .containsEntry("status", "foundation-only")
                        .containsEntry("runtimePackIdentitySupported", false)
                        .containsEntry("remoteExecutionBoundarySupported", true)
                        .containsEntry("distributedQueueSupported", false)
                        .containsEntry("crossInstancePermissionPropagationSupported", false)
                        .containsEntry("sharedCacheDigestSupported", false);
            });

            final Map<String, Object> secondWorkspace = awaitWorkspaceInstances(
                    client,
                    secondCreator.result().creatorUrl(),
                    2,
                    0
            );
            final Map<String, Object> secondInstances = mapValue(secondWorkspace, "instances");
            assertThat(currentInstance(secondInstances))
                    .containsEntry("repoRoot", repoRootB.toAbsolutePath().normalize().toString())
                    .containsEntry("runsRoot", runsRootB.toAbsolutePath().normalize().toString())
                    .containsEntry("loopbackControlUrl", secondCreator.result().creatorUrl().toString())
                    .containsEntry("state", "active")
                    .containsEntry("currentInstance", true);
            assertThat(mapValue(currentInstance(secondInstances), "load"))
                    .containsEntry("activeControlSessions", 0)
                    .containsEntry("maxActiveSessions", 4);
            assertThat(mapValue(currentInstance(secondInstances), "trust"))
                    .containsEntry("mode", "loopback-only")
                    .containsEntry("sharedRegistryDiscovery", true)
                    .containsEntry("remoteControlSupported", false)
                    .containsEntry("remoteExecutionSupported", false);
            assertThat(mapValue(currentInstance(secondInstances), "workspaceRuntimeAlignment"))
                    .containsEntry("workspacePackCount", 1)
                    .containsEntry("runtimeProviderModuleCount", 4)
                    .containsEntry("matchedPackCount", 0)
                    .containsEntry("workspaceOnlyPackCount", 1)
                    .containsEntry("runtimeOnlyModuleCount", 4)
                    .containsEntry("fullyAligned", false);
            assertThat(mapValue(currentInstance(secondInstances), "runtimeIdentity"))
                    .containsEntry("status", "provider-backed")
                    .containsEntry("identitySource", "service-loader-step-providers")
                    .containsEntry("reportsCompleteUseCatalog", true)
                    .containsEntry("remoteExecutionCompatible", false);
            assertThat(stringValue(mapValue(currentInstance(secondInstances), "runtimeIdentity"), "capabilityDigest"))
                    .isEqualTo(firstRuntimeIdentityDigest);
            assertThat(mapValue(currentInstance(secondInstances), "executionModel"))
                    .containsEntry("localStepExecutionSupported", true)
                    .containsEntry("remoteStepExecutionSupported", false)
                    .containsEntry("queuedStepExecutionSupported", true)
                    .containsEntry("crossInstancePermissionSharingSupported", false)
                    .containsEntry("cacheShareSupported", false);
            assertThat(mapValue(currentInstance(secondInstances), "distributedSupport"))
                    .containsEntry("status", "foundation-only")
                    .containsEntry("runtimePackIdentitySupported", false)
                    .containsEntry("remoteExecutionBoundarySupported", true)
                    .containsEntry("distributedQueueSupported", false)
                    .containsEntry("crossInstancePermissionPropagationSupported", false)
                    .containsEntry("sharedCacheDigestSupported", false);

            try (var files = Files.list(registryRoot)) {
                assertThat(files.toList()).hasSize(2);
            }
        }
    }

    @Test
    void shouldMarkExpiredRegistryEntriesAsStaleWhileKeepingCurrentCreatorInstanceActive() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-instance-stale");
        final Path runsRoot = tempDir.resolve("runs-instance-stale");
        final Path registryRoot = tempDir.resolve("instance-registry-stale");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);
        seedInstanceRegistryEntry(
                registryRoot,
                "stale-peer",
                Instant.parse("2026-07-11T09:10:00Z"),
                5,
                tempDir.resolve("repo-stale-peer"),
                tempDir.resolve("runs-stale-peer"),
                "http://127.0.0.1:39999/"
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final Map<String, Object> workspace = awaitWorkspaceInstances(
                    HttpClient.newHttpClient(),
                    runningCreator.result().creatorUrl(),
                    1,
                    1
            );
            final Map<String, Object> instances = mapValue(workspace, "instances");
            assertThat(instances)
                    .containsEntry("totalCount", 2)
                    .containsEntry("activeCount", 1)
                    .containsEntry("staleCount", 1);
            assertThat(currentInstance(instances))
                    .containsEntry("repoRoot", repoRoot.toAbsolutePath().normalize().toString())
                    .containsEntry("loopbackControlUrl", runningCreator.result().creatorUrl().toString())
                    .containsEntry("state", "active")
                    .containsEntry("currentInstance", true);
            assertThat(listOfMaps(instances, "entries")).anySatisfy(instance -> assertThat(instance)
                    .containsEntry("instanceId", "stale-peer")
                    .containsEntry("loopbackControlUrl", "http://127.0.0.1:39999/")
                    .containsEntry("state", "stale")
                    .containsEntry("currentInstance", false));
        }
    }

    @Test
    void shouldAdvertiseLiveCreatorSessionLoadThroughInstanceRegistry() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-instance-load");
        final Path runsRoot = tempDir.resolve("runs-instance-load");
        final Path registryRoot = tempDir.resolve("instance-registry-load");
        seedRepo(repoRoot);
        final Path planFile = writeLaunchPlan(tempDir.resolve("load-plan.json"));
        final Path envelopeFile = writeLaunchEnvelope(tempDir.resolve("load-envelope.json"));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", planFile.toString(),
                                    "envelopeLocation", envelopeFile.toString(),
                                    "runId", "run-creator-instance-load-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(startResponse.statusCode()).isEqualTo(202);
            final Map<String, Object> started = KernelContractCodec.parseStableJsonObject(startResponse.body());
            final Map<String, Object> activeWorkspace = awaitWorkspaceInstanceLoad(client, baseUri, 1);
            assertThat(mapValue(currentInstance(mapValue(activeWorkspace, "instances")), "load"))
                    .containsEntry("activeControlSessions", 1)
                    .containsEntry("maxActiveSessions", 4);

            awaitWorkspaceSession(client, baseUri, stringValue(started, "sessionId"), session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            final Map<String, Object> idleWorkspace = awaitWorkspaceInstanceLoad(client, baseUri, 0);
            assertThat(mapValue(currentInstance(mapValue(idleWorkspace, "instances")), "load"))
                    .containsEntry("activeControlSessions", 0)
                    .containsEntry("maxActiveSessions", 4);
        }
    }

    @Test
    void shouldAdvertiseInstalledStepProviderCapabilitiesThroughInstanceRegistry() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-instance-capabilities");
        final Path runsRoot = tempDir.resolve("runs-instance-capabilities");
        final Path registryRoot = tempDir.resolve("instance-registry-capabilities");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final Map<String, Object> workspace = awaitWorkspaceInstances(
                    HttpClient.newHttpClient(),
                    runningCreator.result().creatorUrl(),
                    1,
                    0
            );
            final Map<String, Object> stepProviders = mapValue(currentInstance(mapValue(workspace, "instances")), "stepProviders");
            assertThat(numberValue(stepProviders, "providerCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "reportedProviderCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "unreportedProviderCount")).isEqualTo(0);
            assertThat(numberValue(stepProviders, "providerModuleCount")).isGreaterThanOrEqualTo(4);
            assertThat(numberValue(stepProviders, "supportedUseCount")).isGreaterThanOrEqualTo(15);
            assertThat(numberValue(stepProviders, "supportedResourceRefPatternCount")).isGreaterThanOrEqualTo(1);
            assertThat(listOfStrings(stepProviders, "providerModuleIds"))
                    .contains("railix.std.data", "railix.std.http", "railix.std.store", "railix.std.trigger");
            assertThat(listOfStrings(stepProviders, "supportedUses"))
                    .contains(
                            "railix.std.data.DataAggregate",
                            "railix.std.data.DataForEach",
                            "railix.std.http.HttpTrigger",
                            "railix.std.store.StoreList",
                            "railix.std.trigger.CliTrigger",
                            "railix.std.trigger.ManualTrigger"
                    );
            assertThat(listOfStrings(stepProviders, "stepKinds")).contains("NORMAL", "TRIGGER");
            assertThat(listOfStrings(stepProviders, "supportedResourceRefPatterns")).contains("store/*");
            assertThat(listOfMaps(stepProviders, "providers")).anySatisfy(provider -> assertThat(provider)
                    .containsEntry("moduleId", "railix.std.data")
                    .containsEntry("providerClassName", "dev.nanonative.railix.railixstddata.StandardDataStepProvider")
                    .containsEntry("reportsSupportedUses", true));
            final Map<String, Object> runtimeIdentity = mapValue(currentInstance(mapValue(workspace, "instances")), "runtimeIdentity");
            assertThat(runtimeIdentity)
                    .containsEntry("status", "provider-backed")
                    .containsEntry("identitySource", "service-loader-step-providers")
                    .containsEntry("reportsCompleteUseCatalog", true)
                    .containsEntry("remoteExecutionCompatible", false);
            assertThat(stringValue(runtimeIdentity, "capabilityDigest")).startsWith("sha256:");
            assertThat(listOfStrings(runtimeIdentity, "providerModuleIds"))
                    .contains("railix.std.data", "railix.std.http", "railix.std.store", "railix.std.trigger");
        }
    }

    @Test
    void shouldAdvertiseSameInstanceInlineRemoteExecutionBoundaryThroughInstanceRegistry() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-boundary-advertisement");
        final Path runsRoot = tempDir.resolve("runs-remote-boundary-advertisement");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final Map<String, Object> workspace = awaitWorkspaceInstances(
                    HttpClient.newHttpClient(),
                    runningCreator.result().creatorUrl(),
                    1,
                    0
            );
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            assertThat(mapValue(currentInstance, "remoteExecutionBoundary"))
                    .containsEntry("status", "same-instance-inline-and-deferred")
                    .containsEntry("endpointPath", "/api/remote-execution/requests")
                    .containsEntry("preflightEndpointPath", "/api/remote-execution/preflight")
                    .containsEntry("requestContractVersion", 1)
                    .containsEntry("responseContractVersion", 1)
                    .containsEntry("acceptedExecutionSupported", true)
                    .containsEntry("acceptedExecutionScope", "same-instance-inline-or-deferred")
                    .containsEntry("preflightSupported", true)
                    .containsEntry("deterministicRejectionSupported", true);
            assertThat(listOfStrings(mapValue(currentInstance, "remoteExecutionBoundary"), "supportedReplyModes"))
                    .containsExactly("NONE", "IMMEDIATE", "DEFERRED");
            assertThat(listOfStrings(mapValue(currentInstance, "remoteExecutionBoundary"), "supportedDecisionStatuses"))
                    .containsExactly("accepted", "rejected");
            assertThat(listOfStrings(mapValue(currentInstance, "remoteExecutionBoundary"), "supportedPreflightDecisionStatuses"))
                    .containsExactly("admitted", "rejected");
            assertThat(listOfStrings(mapValue(currentInstance, "remoteExecutionBoundary"), "supportedRejectionReasons"))
                    .contains(
                            "unknown-target-instance",
                            "runtime-identity-mismatch",
                            "cross-instance-delivery-unsupported",
                            "permission-propagation-unsupported",
                            "deferred-queue-unavailable",
                            "timeout-unsupported",
                            "runtime-capability-drift",
                            "invalid-request"
                    );
            assertThat(mapValue(currentInstance, "distributedSupport"))
                    .containsEntry("remoteExecutionBoundarySupported", true);
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForUnknownTargetInstance() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-wrong-target");
        final Path runsRoot = tempDir.resolve("runs-remote-request-wrong-target");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "creator-instance-other");

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "rejected")
                    .containsEntry("accepted", false)
                    .containsEntry("rejectionCode", "unknown-target-instance");
            assertThat(mapValue(body, "result")).containsEntry("status", "not-executed");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForUnsupportedStepUse() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-unsupported-use");
        final Path runsRoot = tempDir.resolve("runs-remote-request-unsupported-use");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "railix.std.fake.UnknownStep");

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "unsupported-step-use");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForUnsupportedResourceRef() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-unsupported-resource");
        final Path runsRoot = tempDir.resolve("runs-remote-request-unsupported-resource");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("resourceRefs", List.of("file/output.txt"));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "unsupported-resource-ref");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForRuntimeIdentityMismatch() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-runtime-mismatch");
        final Path runsRoot = tempDir.resolve("runs-remote-request-runtime-mismatch");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    "sha256:not-the-current-runtime"
            );

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "runtime-identity-mismatch")
                    .containsEntry("runtimeIdentityMatched", false)
                    .containsEntry("capabilityDigestMatched", false);
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForCrossInstanceTrust() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-cross-instance");
        final Path runsRoot = tempDir.resolve("runs-remote-request-cross-instance");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("requesterInstanceId", "creator-instance-elsewhere");

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "trust-loopback-only");
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionRequestForDiscoveredPeerWithCapabilityDiagnostics() throws Exception {
        final Path repoRootA = tempDir.resolve("repo-remote-request-known-peer-a");
        final Path runsRootA = tempDir.resolve("runs-remote-request-known-peer-a");
        final Path repoRootB = tempDir.resolve("repo-remote-request-known-peer-b");
        final Path runsRootB = tempDir.resolve("runs-remote-request-known-peer-b");
        final Path registryRoot = tempDir.resolve("instance-registry-known-peer");
        seedRepo(repoRootA);
        seedRepo(repoRootB);
        seedRunsWithoutHttpCapture(runsRootA);
        seedRunsWithoutHttpCapture(runsRootB);

        try (CreatorShellMain.RunningCreator firstCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRootA.toString(),
                        "--runs-root", runsRootA.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );
             CreatorShellMain.RunningCreator secondCreator = CreatorShellMain.launch(
                     java.util.List.of(
                             "--repo-root", repoRootB.toString(),
                             "--runs-root", runsRootB.toString(),
                             "--instance-registry-root", registryRoot.toString(),
                             "--port", "0"
                     ),
                     new PrintStream(new ByteArrayOutputStream()),
                     new PrintStream(new ByteArrayOutputStream())
             )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, firstCreator.result().creatorUrl(), 2, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> remoteInstance = listOfMaps(mapValue(workspace, "instances"), "entries").stream()
                    .filter(instance -> !Boolean.TRUE.equals(instance.get("currentInstance")))
                    .findFirst()
                    .orElseThrow();
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(remoteInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", stringValue(remoteInstance, "instanceId"));

            final HttpResponse<String> response = remoteExecutionRequest(client, firstCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "rejected")
                    .containsEntry("accepted", false)
                    .containsEntry("rejectionCode", "cross-instance-delivery-unsupported")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", stringValue(remoteInstance, "instanceId"))
                    .containsEntry("discoveredTargetState", "active")
                    .containsEntry("discoveredTargetCurrentInstance", false)
                    .containsEntry("discoveredTargetLoopbackControlUrl", secondCreator.result().creatorUrl().toString())
                    .containsEntry("discoveredTargetTrustMode", "loopback-only")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", true)
                    .containsEntry("discoveredTargetSupportsRequestedUse", true)
                    .containsEntry("discoveredTargetSupportsRequestedResourceRefs", true);
            assertThat(body.get("message")).asString().contains("cross-instance remote execution delivery is still unsupported");
        }
    }

    @Test
    void shouldAdmitSameInstanceRemoteExecutionPreflightWithoutExecuting() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-same-instance");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-same-instance");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "std.data.capture-payload");
            request.put("resourceRefs", List.of());
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-preflight"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(false, List.of())
            )));

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "admitted")
                    .containsEntry("admitted", true)
                    .containsEntry("executionStarted", false)
                    .containsEntry("executionScheduled", false)
                    .containsEntry("deliverySupported", true)
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "active")
                    .containsEntry("preflightScope", "same-instance-only")
                    .containsEntry("queueSupported", true)
                    .containsEntry("runtimeIdentityMatched", true)
                    .containsEntry("capabilityDigestMatched", true)
                    .containsEntry("supportedHere", true);
            final Map<String, Object> report = remoteExecutionRequestReportModel(client, runningCreator.result().creatorUrl(), 10);
            assertThat(listOfMaps(report, "requests")).isEmpty();
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForDiscoveredPeerWithCapabilityDiagnostics() throws Exception {
        final Path repoRootA = tempDir.resolve("repo-remote-preflight-known-peer-a");
        final Path runsRootA = tempDir.resolve("runs-remote-preflight-known-peer-a");
        final Path repoRootB = tempDir.resolve("repo-remote-preflight-known-peer-b");
        final Path runsRootB = tempDir.resolve("runs-remote-preflight-known-peer-b");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-known-peer");
        seedRepo(repoRootA);
        seedRepo(repoRootB);
        seedRunsWithoutHttpCapture(runsRootA);
        seedRunsWithoutHttpCapture(runsRootB);

        try (CreatorShellMain.RunningCreator firstCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRootA.toString(),
                        "--runs-root", runsRootA.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        );
             CreatorShellMain.RunningCreator secondCreator = CreatorShellMain.launch(
                     java.util.List.of(
                             "--repo-root", repoRootB.toString(),
                             "--runs-root", runsRootB.toString(),
                             "--instance-registry-root", registryRoot.toString(),
                             "--port", "0"
                     ),
                     new PrintStream(new ByteArrayOutputStream()),
                     new PrintStream(new ByteArrayOutputStream())
             )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, firstCreator.result().creatorUrl(), 2, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> remoteInstance = listOfMaps(mapValue(workspace, "instances"), "entries").stream()
                    .filter(instance -> !Boolean.TRUE.equals(instance.get("currentInstance")))
                    .findFirst()
                    .orElseThrow();
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(remoteInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", stringValue(remoteInstance, "instanceId"));

            final HttpResponse<String> response = remoteExecutionPreflight(client, firstCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "rejected")
                    .containsEntry("admitted", false)
                    .containsEntry("deliverySupported", false)
                    .containsEntry("rejectionCode", "cross-instance-delivery-unsupported")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", stringValue(remoteInstance, "instanceId"))
                    .containsEntry("discoveredTargetState", "active")
                    .containsEntry("discoveredTargetCurrentInstance", false)
                    .containsEntry("discoveredTargetLoopbackControlUrl", secondCreator.result().creatorUrl().toString())
                    .containsEntry("discoveredTargetTrustMode", "loopback-only")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", true)
                    .containsEntry("discoveredTargetSupportsRequestedUse", true)
                    .containsEntry("discoveredTargetSupportsRequestedResourceRefs", true);
            assertThat(body.get("message")).asString().contains("cross-instance remote execution delivery is still unsupported");
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForInactiveDiscoveredPeer() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-inactive-peer");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-inactive-peer");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-inactive-peer");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            seedInstanceRegistryEntry(
                    registryRoot,
                    "inactive-peer",
                    Instant.parse("2026-07-11T09:10:00Z"),
                    5,
                    tempDir.resolve("repo-inactive-peer"),
                    tempDir.resolve("runs-inactive-peer"),
                    "http://127.0.0.1:39998/",
                    discoveredPeerRuntimeIdentity(
                            stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest"),
                            List.of("railix.std.store.StoreRead"),
                            List.of("store/*"),
                            true
                    )
            );
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "inactive-peer");

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "target-instance-inactive")
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "stale")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", "inactive-peer")
                    .containsEntry("discoveredTargetState", "stale")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", true);
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForIncompleteDiscoveredPeerIdentity() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-incomplete-peer");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-incomplete-peer");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-incomplete-peer");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            seedInstanceRegistryEntry(
                    registryRoot,
                    "incomplete-peer",
                    Instant.now(),
                    60,
                    tempDir.resolve("repo-incomplete-peer"),
                    tempDir.resolve("runs-incomplete-peer"),
                    "http://127.0.0.1:39997/",
                    Map.of(
                            "supportedUses", List.of("railix.std.store.StoreRead"),
                            "supportedResourceRefPatterns", List.of("store/*"),
                            "reportsCompleteUseCatalog", false
                    )
            );
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "incomplete-peer");

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "target-runtime-identity-incomplete")
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "active")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", "incomplete-peer")
                    .containsEntry("discoveredTargetState", "active")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", false);
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForDiscoveredPeerDigestMismatch() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-digest-mismatch-peer");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-digest-mismatch-peer");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-digest-mismatch-peer");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            seedInstanceRegistryEntry(
                    registryRoot,
                    "digest-mismatch-peer",
                    Instant.now(),
                    60,
                    tempDir.resolve("repo-digest-mismatch-peer"),
                    tempDir.resolve("runs-digest-mismatch-peer"),
                    "http://127.0.0.1:39996/",
                    discoveredPeerRuntimeIdentity(
                            "sha256:different-target-runtime",
                            List.of("railix.std.store.StoreRead"),
                            List.of("store/*"),
                            true
                    )
            );
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "digest-mismatch-peer");

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "target-runtime-identity-mismatch")
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "active")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", "digest-mismatch-peer")
                    .containsEntry("discoveredTargetCapabilityDigest", "sha256:different-target-runtime")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", false);
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForDiscoveredPeerUnsupportedStepUse() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-unsupported-use-peer");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-unsupported-use-peer");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-unsupported-use-peer");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            seedInstanceRegistryEntry(
                    registryRoot,
                    "unsupported-use-peer",
                    Instant.now(),
                    60,
                    tempDir.resolve("repo-unsupported-use-peer"),
                    tempDir.resolve("runs-unsupported-use-peer"),
                    "http://127.0.0.1:39995/",
                    discoveredPeerRuntimeIdentity(
                            stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest"),
                            List.of("railix.std.data.DataTransform"),
                            List.of("store/*"),
                            true
                    )
            );
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "unsupported-use-peer");

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "target-unsupported-step-use")
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "active")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", "unsupported-use-peer")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", true)
                    .containsEntry("discoveredTargetSupportsRequestedUse", false)
                    .containsEntry("discoveredTargetSupportsRequestedResourceRefs", true);
        }
    }

    @Test
    void shouldRejectCrossInstanceRemoteExecutionPreflightForDiscoveredPeerUnsupportedResourceRef() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-unsupported-resource-peer");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-unsupported-resource-peer");
        final Path registryRoot = tempDir.resolve("instance-registry-preflight-unsupported-resource-peer");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--instance-registry-root", registryRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            seedInstanceRegistryEntry(
                    registryRoot,
                    "unsupported-resource-peer",
                    Instant.now(),
                    60,
                    tempDir.resolve("repo-unsupported-resource-peer"),
                    tempDir.resolve("runs-unsupported-resource-peer"),
                    "http://127.0.0.1:39994/",
                    discoveredPeerRuntimeIdentity(
                            stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest"),
                            List.of("railix.std.store.StoreRead"),
                            List.of("file/*"),
                            true
                    )
            );
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("targetInstanceId", "unsupported-resource-peer");

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("rejectionCode", "target-unsupported-resource-ref")
                    .containsEntry("targetKnown", true)
                    .containsEntry("targetState", "active")
                    .containsEntry("discoveredTargetKnown", true)
                    .containsEntry("discoveredTargetInstanceId", "unsupported-resource-peer")
                    .containsEntry("discoveredTargetCapabilityDigestMatched", true)
                    .containsEntry("discoveredTargetSupportsRequestedUse", true)
                    .containsEntry("discoveredTargetSupportsRequestedResourceRefs", false);
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForRequestedPermissionPropagation() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-permissions");
        final Path runsRoot = tempDir.resolve("runs-remote-request-permissions");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("permissions", KernelContractCodec.toUiModel(new PermissionSet(
                    Map.of("store.read", List.of("store/orders")),
                    Map.of(),
                    List.of()
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "permission-propagation-unsupported");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForPermissionDecisionPropagation() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-permission-decisions");
        final Path runsRoot = tempDir.resolve("runs-remote-request-permission-decisions");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("permissions", KernelContractCodec.toUiModel(new PermissionSet(
                    Map.of(),
                    Map.of("store.read", List.of("store/*")),
                    List.of(new PermissionSet.Decision(
                            "store.read",
                            "store/orders",
                            PermissionSet.DecisionResult.GRANTED,
                            "transferred-from-remote"
                    ))
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "permission-propagation-unsupported");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForUnsupportedReplyMode() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-reply-mode");
        final Path runsRoot = tempDir.resolve("runs-remote-request-reply-mode");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("replyMode", "STREAM");

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "reply-mode-unsupported");
        }
    }

    @Test
    void shouldAcceptDeferredRemoteExecutionRequestAndPersistQueuedThenExecutedHistory() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-deferred");
        final Path runsRoot = tempDir.resolve("runs-remote-request-deferred");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = deferredCapturePayloadRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest"),
                    "remote-deferred-1",
                    "order-deferred-1"
            );

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(202);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "accepted")
                    .containsEntry("accepted", true)
                    .containsEntry("queueSupported", true);
            final String executionId = stringValue(body, "executionId");
            assertThat(executionId).isNotBlank();
            final Map<String, Object> result = mapValue(body, "result");
            assertThat(result)
                    .containsEntry("status", "queued")
                    .containsEntry("appId", "creator-remote-execution")
                    .containsEntry("flowId", "std-data-capture-payload");
            final String queuedRunId = stringValue(result, "runId");
            assertThat(queuedRunId).isNotBlank();

            final Map<String, Object> queuedReport = remoteExecutionRequestReportModel(client, runningCreator.result().creatorUrl(), 10);
            assertThat(findRemoteExecutionRequest(queuedReport, executionId))
                    .containsEntry("requestId", "remote-deferred-1")
                    .containsEntry("requestStatus", "queued")
                    .containsEntry("decision", "accepted")
                    .containsEntry("replyMode", "DEFERRED")
                    .containsEntry("runId", queuedRunId);

            final Map<String, Object> completedRow = awaitRemoteExecutionRequest(client, runningCreator.result().creatorUrl(), executionId, row ->
                    "executed".equals(row.get("requestStatus")) || "failed".equals(row.get("requestStatus")));
            assertThat(completedRow)
                    .containsEntry("requestId", "remote-deferred-1")
                    .containsEntry("requestStatus", "executed")
                    .containsEntry("decision", "accepted")
                    .containsEntry("runOutcome", "ok")
                    .containsEntry("runId", queuedRunId);
        }
    }

    @Test
    void shouldRejectDeferredRemoteExecutionRequestWhenDeferredQueueIsUnavailable() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-deferred-capacity");
        final Path runsRoot = tempDir.resolve("runs-remote-request-deferred-capacity");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, baseUri, 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final String currentInstanceId = stringValue(currentInstance, "instanceId");
            final String capabilityDigest = stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest");
            final java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
            final List<CompletableFuture<HttpResponse<String>>> pendingResponses = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try {
                            startGate.await(5, TimeUnit.SECONDS);
                            return remoteExecutionRequest(
                                    client,
                                    baseUri,
                                    deferredCapturePayloadRemoteExecutionRequest(
                                            currentInstanceId,
                                            capabilityDigest,
                                            "remote-deferred-capacity-" + index,
                                            "order-capacity-" + index
                                    )
                            );
                        } catch (final Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }))
                    .toList();

            startGate.countDown();
            final List<HttpResponse<String>> responses = pendingResponses.stream()
                    .map(response -> response.orTimeout(10, TimeUnit.SECONDS).join())
                    .toList();
            final List<HttpResponse<String>> acceptedResponses = responses.stream()
                    .filter(response -> response.statusCode() == 202)
                    .toList();
            final List<HttpResponse<String>> rejectedResponses = responses.stream()
                    .filter(response -> response.statusCode() == 409)
                    .toList();

            final List<String> responseSummaries = responses.stream()
                    .map(response -> response.statusCode() + ":" + response.body())
                    .toList();
            assertThat(responseSummaries).allMatch(
                    summary -> summary.startsWith("202:") || summary.startsWith("409:"),
                    responseSummaries.toString()
            );
            assertThat(acceptedResponses.size() + rejectedResponses.size()).isEqualTo(5);
            assertThat(acceptedResponses).hasSizeLessThanOrEqualTo(4);
            assertThat(rejectedResponses).isNotEmpty();
            for (final HttpResponse<String> rejectedResponse : rejectedResponses) {
                final Map<String, Object> rejectedBody = KernelContractCodec.parseStableJsonObject(rejectedResponse.body());
                assertThat(rejectedBody)
                        .containsEntry("decision", "rejected")
                        .containsEntry("accepted", false)
                        .containsEntry("rejectionCode", "deferred-queue-unavailable")
                        .containsEntry("queueSupported", true);
                assertThat(mapValue(rejectedBody, "result")).containsEntry("status", "not-executed");
            }

            final List<String> executionIds = acceptedResponses.stream()
                    .map(HttpResponse::body)
                    .map(KernelContractCodec::parseStableJsonObject)
                    .map(body -> stringValue(body, "executionId"))
                    .toList();
            for (final String executionId : executionIds) {
                final Map<String, Object> completedRow = awaitRemoteExecutionRequest(client, baseUri, executionId, row ->
                        "executed".equals(row.get("requestStatus")) || "failed".equals(row.get("requestStatus")));
                assertThat(completedRow).containsEntry("decision", "accepted");
            }

            final Map<String, Object> report = remoteExecutionRequestReportModel(client, baseUri, 10);
            assertThat(numberValue(report, "totalCount")).isEqualTo(5);
            assertThat(numberValue(report, "rejectedCount")).isEqualTo(rejectedResponses.size());
            assertThat(numberValue(report, "executedCount")).isEqualTo(acceptedResponses.size());
        }
    }

    @Test
    void shouldAcceptSameInstanceRemoteExecutionRequestForStoreReadWithGrantedPermissionsAndSettingsLocation() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-store-read");
        final Path runsRoot = tempDir.resolve("runs-remote-request-store-read");
        final Path storeRoot = tempDir.resolve("remote-store-root");
        final Path settingsFile = tempDir.resolve("remote-store-settings.json");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);
        writeRemoteSettings(settingsFile, storeRootSettingsTree(storeRoot));
        writeRemoteStoreEntry(storeRoot, "orders", new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("READY"),
                "amount", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(42))
        )));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("permissions", grantedRemoteExecutionPermissions(Map.of(
                    "settings.read", List.of("settings.store.rootDir"),
                    "store.read", List.of("store/*")
            )));
            request.put("settingsLocation", settingsFile.toString());
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of(
                            "store", new RailixValue.ObjectValue(Map.of(
                                    "key", new RailixValue.StringValue("orders")
                            ))
                    )),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "accepted")
                    .containsEntry("accepted", true)
                    .containsEntry("remoteExecutionSupported", false)
                    .containsEntry("sameInstanceExecutionSupported", true)
                    .containsEntry("runtimeIdentityMatched", true)
                    .containsEntry("capabilityDigestMatched", true)
                    .containsEntry("supportedHere", true);
            final Map<String, Object> result = mapValue(body, "result");
            assertThat(result)
                    .containsEntry("status", "executed")
                    .containsEntry("flowId", "railix-std-store-storeread")
                    .containsEntry("outcome", "ok")
                    .containsEntry("executedSteps", 1);
            final RailixValue.ObjectValue outputContext = (RailixValue.ObjectValue) KernelContractCodec.railixValueFromUiModel(mapValue(result, "outputContext"));
            final RailixValue.ObjectValue ctx = (RailixValue.ObjectValue) outputContext.values().get("ctx");
            final RailixValue.ObjectValue store = (RailixValue.ObjectValue) ctx.values().get("store");
            assertThat(store.values().get("key")).isEqualTo(new RailixValue.StringValue("orders"));
            assertThat(store.values().get("result")).isEqualTo(new RailixValue.ObjectValue(Map.of(
                    "status", new RailixValue.StringValue("READY"),
                    "amount", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(42))
            )));
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForUnsupportedTimeout() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-timeout");
        final Path runsRoot = tempDir.resolve("runs-remote-request-timeout");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("timeoutMillis", 1000);

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "timeout-unsupported");
        }
    }

    @Test
    void shouldRejectRemoteExecutionRequestForNegativeUnsupportedTimeout() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-negative-timeout");
        final Path runsRoot = tempDir.resolve("runs-remote-request-negative-timeout");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("timeoutMillis", -1);

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "timeout-unsupported");
        }
    }

    @Test
    void shouldRejectRemoteExecutionPreflightForNegativeUnsupportedTimeout() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-preflight-negative-timeout");
        final Path runsRoot = tempDir.resolve("runs-remote-preflight-negative-timeout");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "std.data.capture-payload");
            request.put("resourceRefs", List.of());
            request.put("timeoutMillis", -1);
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-negative-timeout"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(false, List.of())
            )));

            final HttpResponse<String> response = remoteExecutionPreflight(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(409);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body).containsEntry("rejectionCode", "timeout-unsupported");
        }
    }

    @Test
    void shouldAcceptSameInstanceRemoteExecutionRequestForPermissionedSettingStep() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-permissioned-step");
        final Path runsRoot = tempDir.resolve("runs-remote-request-permissioned-step");
        final Path settingsFile = tempDir.resolve("remote-app-settings.json");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);
        writeRemoteSettings(settingsFile, appModeSettingsTree("remote-mode"));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "std.data.capture-setting");
            request.put("resourceRefs", List.of());
            request.put("replyMode", "IMMEDIATE");
            request.put("permissions", grantedRemoteExecutionPermissions(Map.of(
                    "settings.read", List.of("settings.app.mode")
            )));
            request.put("settingsLocation", settingsFile.toString());
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("requestId", new RailixValue.StringValue("remote-setting-1"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "accepted")
                    .containsEntry("accepted", true);
            final Map<String, Object> result = mapValue(body, "result");
            assertThat(result)
                    .containsEntry("status", "executed")
                    .containsEntry("outcome", "ok")
                    .containsEntry("executedSteps", 1);
            final RailixValue.ObjectValue outputContext = (RailixValue.ObjectValue) KernelContractCodec.railixValueFromUiModel(mapValue(result, "outputContext"));
            final RailixValue.ObjectValue ctx = (RailixValue.ObjectValue) outputContext.values().get("ctx");
            final RailixValue.ObjectValue settings = (RailixValue.ObjectValue) ctx.values().get("settings");
            assertThat(settings.values().get("mode")).isEqualTo(new RailixValue.StringValue("remote-mode"));
            final Reply reply = KernelContractCodec.replyFromUiModel(mapValue(result, "reply"));
            assertThat(reply.mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        }
    }

    @Test
    void shouldAcceptSameInstanceRemoteExecutionRequestForInlinePayloadCapture() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-inline-capture");
        final Path runsRoot = tempDir.resolve("runs-remote-request-inline-capture");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "std.data.capture-payload");
            request.put("resourceRefs", List.of());
            request.put("replyMode", "NONE");
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-1"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(false, List.of())
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "accepted")
                    .containsEntry("accepted", true)
                    .containsEntry("remoteExecutionSupported", false)
                    .containsEntry("sameInstanceExecutionSupported", true);
            final Map<String, Object> result = mapValue(body, "result");
            assertThat(result)
                    .containsEntry("status", "executed")
                    .containsEntry("appId", "creator-remote-execution")
                    .containsEntry("flowId", "std-data-capture-payload")
                    .containsEntry("outcome", "ok")
                    .containsEntry("executedSteps", 1);
            final RailixValue.ObjectValue outputContext = (RailixValue.ObjectValue) KernelContractCodec.railixValueFromUiModel(mapValue(result, "outputContext"));
            final RailixValue.ObjectValue ctx = (RailixValue.ObjectValue) outputContext.values().get("ctx");
            final RailixValue.ObjectValue capturedPayload = (RailixValue.ObjectValue) ctx.values().get("payload");
            assertThat(capturedPayload.values().get("orderId")).isEqualTo(new RailixValue.StringValue("order-1"));
            final Path runFolder = Path.of(stringValue(result, "runFolder"));
            assertThat(Files.isDirectory(runFolder)).isTrue();
            assertThat(Files.isRegularFile(runFolder.resolve("summary.json"))).isTrue();
        }
    }

    @Test
    void shouldAcceptSameInstanceRemoteExecutionRequestAndReturnImmediateReply() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-inline-reply");
        final Path runsRoot = tempDir.resolve("runs-remote-request-inline-reply");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final Map<String, Object> request = validRemoteExecutionRequest(
                    stringValue(currentInstance, "instanceId"),
                    stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest")
            );
            request.put("stepUse", "railix.std.data.DataTransform");
            request.put("resourceRefs", List.of());
            request.put("replyMode", "IMMEDIATE");
            request.put("stepConfig", KernelContractCodec.toUiModel(new RailixValue.ObjectValue(Map.of(
                    "mappings", new RailixValue.ListValue(List.of(
                            mapping("reply.mode", new RailixValue.StringValue("immediate")),
                            mapping("reply.payload.message", new RailixValue.StringValue("hello from remote boundary"))
                    ))
            ))));
            request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("user", new RailixValue.StringValue("Creator"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
            )));

            final HttpResponse<String> response = remoteExecutionRequest(client, runningCreator.result().creatorUrl(), request);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("decision", "accepted")
                    .containsEntry("accepted", true);
            final Map<String, Object> result = mapValue(body, "result");
            assertThat(result).containsEntry("status", "executed");
            final Reply reply = KernelContractCodec.replyFromUiModel(mapValue(result, "reply"));
            assertThat(reply.mode()).isEqualTo(Reply.Mode.IMMEDIATE);
            final RailixValue.ObjectValue replyPayload = (RailixValue.ObjectValue) reply.payload();
            assertThat(replyPayload.values().get("message")).isEqualTo(new RailixValue.StringValue("hello from remote boundary"));
        }
    }

    @Test
    void shouldExposeEmptyRemoteExecutionRequestHistoryWhenNoRequestsWereRecorded() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-history-empty");
        final Path runsRoot = tempDir.resolve("runs-remote-request-history-empty");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();

            final HttpResponse<String> response = remoteExecutionRequestReport(client, runningCreator.result().creatorUrl(), 8);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("title", "Remote Execution Requests")
                    .containsEntry("source", "artifact-backed-remote-execution-requests")
                    .containsEntry("error", "")
                    .containsEntry("totalCount", 0)
                    .containsEntry("acceptedCount", 0)
                    .containsEntry("rejectedCount", 0)
                    .containsEntry("unsupportedCount", 0);
            assertThat(listOfMaps(body, "requests")).isEmpty();
        }
    }

    @Test
    void shouldExposeRecentRemoteExecutionRequestHistoryWithLinkedRunEvidence() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-remote-request-history");
        final Path runsRoot = tempDir.resolve("runs-remote-request-history");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final Map<String, Object> workspace = awaitWorkspaceInstances(client, runningCreator.result().creatorUrl(), 1, 0);
            final Map<String, Object> currentInstance = currentInstance(mapValue(workspace, "instances"));
            final String currentInstanceId = stringValue(currentInstance, "instanceId");
            final String capabilityDigest = stringValue(mapValue(currentInstance, "runtimeIdentity"), "capabilityDigest");

            final Map<String, Object> acceptedRequest = validRemoteExecutionRequest(currentInstanceId, capabilityDigest);
            acceptedRequest.put("requestId", "remote-history-accepted");
            acceptedRequest.put("stepUse", "std.data.capture-payload");
            acceptedRequest.put("resourceRefs", List.of());
            acceptedRequest.put("replyMode", "NONE");
            acceptedRequest.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-9"))),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(false, List.of())
            )));
            assertThat(remoteExecutionRequest(client, runningCreator.result().creatorUrl(), acceptedRequest).statusCode()).isEqualTo(200);

            final Map<String, Object> rejectedRequest = validRemoteExecutionRequest(currentInstanceId, capabilityDigest);
            rejectedRequest.put("requestId", "remote-history-rejected");
            rejectedRequest.put("targetInstanceId", "creator-instance-other");
            assertThat(remoteExecutionRequest(client, runningCreator.result().creatorUrl(), rejectedRequest).statusCode()).isEqualTo(409);

            final Path storeRoot = tempDir.resolve("remote-history-store-root");
            final Path settingsFile = tempDir.resolve("remote-history-settings.json");
            writeRemoteSettings(settingsFile, storeRootSettingsTree(storeRoot));
            writeRemoteStoreEntry(storeRoot, "orders", new RailixValue.ObjectValue(Map.of(
                    "status", new RailixValue.StringValue("QUEUED")
            )));
            final Map<String, Object> storeRequest = validRemoteExecutionRequest(currentInstanceId, capabilityDigest);
            storeRequest.put("requestId", "remote-history-store");
            storeRequest.put("permissions", grantedRemoteExecutionPermissions(Map.of(
                    "settings.read", List.of("settings.store.rootDir"),
                    "store.read", List.of("store/*")
            )));
            storeRequest.put("settingsLocation", settingsFile.toString());
            storeRequest.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                    "remote.dev",
                    "remote",
                    new RailixValue.ObjectValue(Map.of(
                            "store", new RailixValue.ObjectValue(Map.of(
                                    "key", new RailixValue.StringValue("orders")
                            ))
                    )),
                    new RailixValue.ObjectValue(Map.of()),
                    Map.of(),
                    new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
            )));
            assertThat(remoteExecutionRequest(client, runningCreator.result().creatorUrl(), storeRequest).statusCode()).isEqualTo(200);

            final HttpResponse<String> response = remoteExecutionRequestReport(client, runningCreator.result().creatorUrl(), 10);

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> body = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(body)
                    .containsEntry("title", "Remote Execution Requests")
                    .containsEntry("error", "")
                    .containsEntry("totalCount", 3)
                    .containsEntry("acceptedCount", 2)
                    .containsEntry("rejectedCount", 1)
                    .containsEntry("unsupportedCount", 0);
            assertThat(listOfMaps(body, "requests")).anySatisfy(request -> assertThat(request)
                    .containsEntry("requestId", "remote-history-accepted")
                    .containsEntry("requestStatus", "executed")
                    .containsEntry("decision", "accepted")
                    .containsEntry("stepUse", "std.data.capture-payload")
                    .containsEntry("appId", "creator-remote-execution")
                    .containsEntry("flowId", "std-data-capture-payload")
                    .containsEntry("runOutcome", "ok"));
            assertThat(listOfMaps(body, "requests")).anySatisfy(request -> assertThat(request)
                    .containsEntry("requestId", "remote-history-rejected")
                    .containsEntry("requestStatus", "rejected")
                    .containsEntry("decision", "rejected")
                    .containsEntry("rejectionCode", "unknown-target-instance"));
            assertThat(listOfMaps(body, "requests")).anySatisfy(request -> assertThat(request)
                    .containsEntry("requestId", "remote-history-store")
                    .containsEntry("requestStatus", "executed")
                    .containsEntry("decision", "accepted")
                    .containsEntry("stepUse", "railix.std.store.StoreRead")
                    .containsEntry("runOutcome", "ok"));
        }
    }

    @Test
    void shouldExposeHistoricalRunDetailsForSelectedPersistedRun() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-history");
        final Path runsRoot = tempDir.resolve("runs-history");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-old-1",
                "Older run payload",
                "railix.metric.old",
                11,
                "creator.old",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-new-2",
                "Newest run payload",
                "railix.metric.new",
                29,
                "creator.new",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> detailsResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-old-1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat((List<?>) workspace.get("runs")).hasSize(2);
            assertThat(mapValue(workspace, "latestRunDetails")).containsEntry("runId", "run-creator-new-2");

            assertThat(detailsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("runId", "run-creator-old-1")
                    .containsEntry("sourceKind", "historical-persisted-run")
                    .containsEntry("lastSignalType", "run.finished")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat(listOfMaps(details, "latestSignals")).hasSize(8);
            assertThat(listOfMaps(details, "latestSignals").getFirst()).containsEntry("type", "run.started");
            assertThat(listOfMaps(details, "latestSignals").get(6))
                    .containsEntry("type", "reply.produced")
                    .containsEntry("mode", "immediate");
            assertThat(mapValue(listOfMaps(details, "latestSignals").get(6), "metadata"))
                    .containsEntry("source", "Older run payload");
            assertThat(listOfMaps(details, "latestSignals").getLast()).containsEntry("type", "run.finished");
            assertThat(findTimelineEvent(details, "reply.produced"))
                    .containsEntry("replyMode", "immediate");
            assertThat(mapValue(details, "contextDiff")).containsEntry("available", true);
            assertThat(listOfStrings(mapValue(details, "contextDiff"), "changedPaths"))
                    .containsExactly("reply.mode", "reply.payload.message");
            assertThat(listOfMaps(mapValue(details, "metrics"), "series").getFirst())
                    .containsEntry("name", "railix.metric.old")
                    .containsEntry("latestValuePreview", "11");
            assertThat(listOfMaps(mapValue(details, "audits"), "events").getFirst())
                    .containsEntry("eventName", "creator.old");
        }
    }

    @Test
    void shouldExposeHistoricalRunTerminalFailureAndSignals() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-history-terminal-failure");
        final Path runsRoot = tempDir.resolve("runs-history-terminal-failure");
        seedRepo(repoRoot);
        seedTerminalFailureRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-failed-terminal-1",
                Instant.parse("2026-07-11T09:10:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-failed-terminal-1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("runId", "run-creator-failed-terminal-1")
                    .containsEntry("outcome", "failed")
                    .containsEntry("sourceKind", "historical-persisted-run")
                    .containsEntry("lastSignalType", "run.finished")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat(listOfMaps(details, "latestSignals")).hasSize(7);
            assertThat(listOfMaps(details, "latestSignals").getLast())
                    .containsEntry("type", "run.finished")
                    .containsEntry("outcome", "failed");
            assertThat(mapValue(details, "terminalFailure"))
                    .containsEntry("phase", "reply.validation")
                    .containsEntry("type", "java.lang.IllegalStateException")
                    .containsEntry("message", "Reply mode IMMEDIATE was not permitted by the final reply channel.");
            assertThat((String) details.get("failureSummary"))
                    .contains("\"phase\":\"reply.validation\"")
                    .contains("\"message\":\"Reply mode IMMEDIATE was not permitted by the final reply channel.\"");
            assertThat(findTimelineEvent(details, "run.finished")).containsEntry("outcome", "failed");
            assertThat(findGraphNode(details, "finish")).containsEntry("state", "failed");
        }
    }

    @Test
    void shouldDiscoverAndExposeHistoricalRunDetailsWhenSummaryIsMissing() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-missing-summary");
        final Path runsRoot = tempDir.resolve("runs-missing-summary");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-complete-1",
                "Completed payload",
                "railix.metric.complete",
                3,
                "creator.complete",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedMissingSummaryRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-missing-summary-2",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> queryResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/runs?appId=demo-app&flowId=demo-flow&summaryReadStatus=missing&limit=5"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final HttpResponse<String> detailsResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-missing-summary-2"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat((List<?>) workspace.get("runs")).hasSize(2);
            assertThat(listOfMaps(workspace, "runs").getFirst())
                    .containsEntry("runId", "run-creator-missing-summary-2")
                    .containsEntry("summaryReadStatus", "missing");
            final Map<String, Object> latestRunDetails = mapValue(workspace, "latestRunDetails");
            assertThat(latestRunDetails)
                    .containsEntry("runId", "run-creator-missing-summary-2")
                    .containsEntry("summaryReadStatus", "missing")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat(findTimelineEvent(latestRunDetails, "reply.produced"))
                    .containsEntry("replyMode", "immediate");

            assertThat(queryResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(queryResponse.body());
            assertThat(mapValue(query, "filters"))
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("outcome", "")
                    .containsEntry("summaryReadStatus", "missing");
            assertThat(query)
                    .containsEntry("totalMatchedCount", 1)
                    .containsEntry("returnedCount", 1);
            assertThat(listOfMaps(query, "runs").getFirst())
                    .containsEntry("runId", "run-creator-missing-summary-2")
                    .containsEntry("summaryReadStatus", "missing");

            assertThat(detailsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("runId", "run-creator-missing-summary-2")
                    .containsEntry("summaryReadStatus", "missing")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat(findTimelineEvent(details, "run.finished"))
                    .containsEntry("outcome", "ok");
            assertThat(findGraphNode(details, "finish")).containsEntry("state", "complete");
        }
    }

    @Test
    void shouldRejectHistoricalRunDetailsTraversalSegments() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-history-invalid");
        final Path runsRoot = tempDir.resolve("runs-history-invalid");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=..&flowId=demo-flow&runId=run-creator-1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) details.get("message")).contains("appId");
        }
    }

    @Test
    void shouldReturnNotFoundForMissingHistoricalRunDetails() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-history-missing");
        final Path runsRoot = tempDir.resolve("runs-history-missing");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=missing-run"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(404);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("exitCode", 1)
                    .containsEntry("succeeded", false);
            assertThat((String) details.get("message")).contains("Persisted run not found");
        }
    }

    @Test
    void shouldRejectNonGetHistoricalRunDetailsRequests() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-history-method");
        final Path runsRoot = tempDir.resolve("runs-history-method");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-1"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(405);
            assertThat(detailsResponse.body()).isEmpty();
        }
    }

    @Test
    void shouldExposeBoundedHistoricalRunsQueryWithFilters() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query");
        final Path runsRoot = tempDir.resolve("runs-runs-query");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-old-1",
                "Old payload",
                "railix.metric.old",
                1,
                "creator.old",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-mid-2",
                "Mid payload",
                "railix.metric.mid",
                2,
                "creator.mid",
                Instant.parse("2026-07-11T09:20:06Z")
        );
        seedMalformedSummaryRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-new-3",
                Instant.parse("2026-07-11T09:30:06Z")
        );
        seedNamedRun(
                runsRoot,
                "other-app",
                "other-flow",
                "run-other-1",
                "Other payload",
                "railix.metric.other",
                9,
                "creator.other",
                Instant.parse("2026-07-11T09:40:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&limit=2"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(query)
                    .containsEntry("sourceKind", "historical-persisted-run-query")
                    .containsEntry("cursorApplied", false)
                    .containsEntry("limit", 2)
                    .containsEntry("totalMatchedCount", 3)
                    .containsEntry("returnedCount", 2)
                    .containsEntry("hasMore", true);
            assertThat((String) query.get("nextCursor")).isNotBlank();
            assertThat(mapValue(query, "filters"))
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("outcome", "")
                    .containsEntry("summaryReadStatus", "");
            final List<Map<String, Object>> runs = listOfMaps(query, "runs");
            assertThat(runs).hasSize(2);
            assertThat(runs.getFirst())
                    .containsEntry("runId", "run-creator-new-3")
                    .containsEntry("summaryReadStatus", "invalid")
                    .doesNotContainKey("runFolder");
            assertThat(runs.get(1))
                    .containsEntry("runId", "run-creator-mid-2")
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow");
            assertThat(runsResponse.body()).doesNotContain("run-other-1").doesNotContain("run-creator-old-1");
        }
    }

    @Test
    void shouldExposeCursorForHistoricalRunsQueryAndPageOlderResultsDeterministically() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-cursor");
        final Path runsRoot = tempDir.resolve("runs-runs-query-cursor");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-a",
                "Payload A",
                "railix.metric.a",
                1,
                "creator.a",
                Instant.parse("2026-07-11T09:30:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-b",
                "Payload B",
                "railix.metric.b",
                2,
                "creator.b",
                Instant.parse("2026-07-11T09:30:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-c",
                "Payload C",
                "railix.metric.c",
                3,
                "creator.c",
                Instant.parse("2026-07-11T09:30:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-d",
                "Payload D",
                "railix.metric.d",
                4,
                "creator.d",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> firstPageResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&limit=2"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(firstPageResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> firstPage = KernelContractCodec.parseStableJsonObject(firstPageResponse.body());
            final List<Map<String, Object>> firstPageRuns = listOfMaps(firstPage, "runs");
            assertThat(firstPageRuns).extracting(run -> run.get("runId"))
                    .containsExactly("run-a", "run-b");
            assertThat(firstPage)
                    .containsEntry("cursorApplied", false)
                    .containsEntry("totalMatchedCount", 4)
                    .containsEntry("returnedCount", 2)
                    .containsEntry("hasMore", true);
            final String nextCursor = (String) firstPage.get("nextCursor");
            assertThat(nextCursor).isNotBlank();

            final HttpResponse<String> secondPageResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&limit=2&cursor=" + nextCursor))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(secondPageResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> secondPage = KernelContractCodec.parseStableJsonObject(secondPageResponse.body());
            final List<Map<String, Object>> secondPageRuns = listOfMaps(secondPage, "runs");
            assertThat(secondPageRuns).extracting(run -> run.get("runId"))
                    .containsExactly("run-c", "run-d");
            assertThat(secondPage)
                    .containsEntry("cursorApplied", true)
                    .containsEntry("totalMatchedCount", 4)
                    .containsEntry("returnedCount", 2)
                    .containsEntry("hasMore", false)
                    .containsEntry("nextCursor", "");
        }
    }

    @Test
    void shouldApplyHistoricalRunsSummaryFiltersAndIgnoreNestedSummaryPaths() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-filter");
        final Path runsRoot = tempDir.resolve("runs-runs-query-filter");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-valid-1",
                "Valid payload",
                "railix.metric.valid",
                3,
                "creator.valid",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedMalformedSummaryRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-invalid-2",
                Instant.parse("2026-07-11T09:20:06Z")
        );
        final Path nestedSummary = runsRoot.resolve("demo-app")
                .resolve("demo-flow")
                .resolve("run-creator-valid-1")
                .resolve("extra")
                .resolve("summary.json");
        Files.createDirectories(nestedSummary.getParent());
        Files.writeString(nestedSummary, KernelContractCodec.toStableJson(Map.of(
                "outcome", "ok",
                "executedSteps", 99,
                "replyMode", "immediate"
        )));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&summaryReadStatus=invalid&limit=5"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(mapValue(query, "filters"))
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("outcome", "")
                    .containsEntry("summaryReadStatus", "invalid");
            assertThat(query)
                    .containsEntry("totalMatchedCount", 1)
                    .containsEntry("returnedCount", 1);
            final List<Map<String, Object>> runs = listOfMaps(query, "runs");
            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst())
                    .containsEntry("runId", "run-creator-invalid-2")
                    .containsEntry("summaryReadStatus", "invalid");
            assertThat(runsResponse.body()).doesNotContain("\"executedSteps\":99");
        }
    }

    @Test
    void shouldApplyHistoricalRunsOutcomeFilter() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-outcome");
        final Path runsRoot = tempDir.resolve("runs-runs-query-outcome");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-ok-1",
                "Approved payload",
                "railix.metric.ok",
                4,
                "creator.ok",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedRunWithOutcome(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-failed-2",
                "failed",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&outcome=failed&limit=5"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(mapValue(query, "filters"))
                    .containsEntry("appId", "demo-app")
                    .containsEntry("flowId", "demo-flow")
                    .containsEntry("outcome", "failed")
                    .containsEntry("summaryReadStatus", "");
            assertThat(query)
                    .containsEntry("totalMatchedCount", 1)
                    .containsEntry("returnedCount", 1);
            final List<Map<String, Object>> runs = listOfMaps(query, "runs");
            assertThat(runs).hasSize(1);
            assertThat(runs.getFirst())
                    .containsEntry("runId", "run-creator-failed-2")
                    .containsEntry("outcome", "failed");
            assertThat(runsResponse.body()).doesNotContain("run-creator-ok-1");
        }
    }

    @Test
    void shouldRejectHistoricalRunsQueryWithInvalidLimit() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-invalid-limit");
        final Path runsRoot = tempDir.resolve("runs-runs-query-invalid-limit");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?limit=51"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(query)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) query.get("message")).contains("limit");
        }
    }

    @Test
    void shouldRejectHistoricalRunsQueryWithInvalidSummaryReadStatus() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-invalid-summary-status");
        final Path runsRoot = tempDir.resolve("runs-runs-query-invalid-summary-status");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?summaryReadStatus=broken"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(query)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) query.get("message")).contains("summaryReadStatus");
        }
    }

    @Test
    void shouldRejectHistoricalRunsQueryWithInvalidCursor() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-invalid-cursor");
        final Path runsRoot = tempDir.resolve("runs-runs-query-invalid-cursor");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?cursor=not_base64!"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(query)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) query.get("message")).contains("cursor");
        }
    }

    @Test
    void shouldRejectHistoricalRunsQueryWhenCursorDoesNotMatchFilters() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-mismatched-cursor");
        final Path runsRoot = tempDir.resolve("runs-runs-query-mismatched-cursor");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-a",
                "Payload A",
                "railix.metric.a",
                1,
                "creator.a",
                Instant.parse("2026-07-11T09:30:06Z")
        );
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-b",
                "Payload B",
                "railix.metric.b",
                2,
                "creator.b",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> firstPageResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app&flowId=demo-flow&limit=1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(firstPageResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> firstPage = KernelContractCodec.parseStableJsonObject(firstPageResponse.body());
            final String nextCursor = (String) firstPage.get("nextCursor");
            assertThat(nextCursor).isNotBlank();

            final HttpResponse<String> mismatchedResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=other-app&flowId=demo-flow&limit=1&cursor=" + nextCursor))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(mismatchedResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(mismatchedResponse.body());
            assertThat(query)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) query.get("message")).contains("cursor");
        }
    }

    @Test
    void shouldReturnEmptyHistoricalRunsQueryWhenRunsRootDoesNotExist() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-missing-root");
        final Path runsRoot = tempDir.resolve("runs-runs-query-missing-root");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?limit=5"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> query = KernelContractCodec.parseStableJsonObject(runsResponse.body());
            assertThat(query)
                    .containsEntry("totalMatchedCount", 0)
                    .containsEntry("returnedCount", 0)
                    .containsEntry("hasMore", false);
            assertThat(listOfMaps(query, "runs")).isEmpty();
        }
    }

    @Test
    void shouldRejectNonGetHistoricalRunsQueryRequests() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-runs-query-method");
        final Path runsRoot = tempDir.resolve("runs-runs-query-method");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> runsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs?appId=demo-app"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(runsResponse.statusCode()).isEqualTo(405);
            assertThat(runsResponse.body()).isEmpty();
        }
    }

    @Test
    void shouldKeepWorkspaceApiAvailableWhenLatestSummaryIsMalformed() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-bad-summary-workspace");
        final Path runsRoot = tempDir.resolve("runs-bad-summary-workspace");
        seedRepo(repoRoot);
        seedNamedRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-valid-1",
                "Older valid payload",
                "railix.metric.valid",
                5,
                "creator.valid",
                Instant.parse("2026-07-11T09:10:06Z")
        );
        seedMalformedSummaryRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-invalid-summary-2",
                Instant.parse("2026-07-11T09:20:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat((List<?>) workspace.get("runs")).hasSize(2);
            assertThat(listOfMaps(workspace, "runs").getFirst())
                    .containsEntry("runId", "run-creator-invalid-summary-2")
                    .containsEntry("summaryReadStatus", "invalid");
            final Map<String, Object> latestRunDetails = mapValue(workspace, "latestRunDetails");
            assertThat(latestRunDetails)
                    .containsEntry("runId", "run-creator-invalid-summary-2")
                    .containsEntry("summaryReadStatus", "invalid")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat((String) latestRunDetails.get("summaryReadError")).contains("summary.json");
            assertThat(findTimelineEvent(latestRunDetails, "reply.produced"))
                    .containsEntry("replyMode", "immediate");
            assertThat(listOfMaps(mapValue(latestRunDetails, "metrics"), "series").getFirst())
                    .containsEntry("name", "railix.metric.value");
        }
    }

    @Test
    void shouldExposeHistoricalRunDetailsWhenSummaryIsMalformed() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-bad-summary-detail");
        final Path runsRoot = tempDir.resolve("runs-bad-summary-detail");
        seedRepo(repoRoot);
        seedMalformedSummaryRun(
                runsRoot,
                "demo-app",
                "demo-flow",
                "run-creator-invalid-summary-1",
                Instant.parse("2026-07-11T09:10:06Z")
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-invalid-summary-1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("runId", "run-creator-invalid-summary-1")
                    .containsEntry("summaryReadStatus", "invalid")
                    .containsEntry("signalStreamStatus", "complete");
            assertThat((String) details.get("summaryReadError")).contains("summary.json");
            assertThat(findTimelineEvent(details, "reply.produced"))
                    .containsEntry("replyMode", "immediate");
            assertThat(listOfMaps(mapValue(details, "audits"), "events").getFirst())
                    .containsEntry("eventName", "creator.seeded");
        }
    }

    @Test
    void shouldKeepWorkspaceApiAvailableWhenHttpCaptureIsMalformed() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-bad-capture");
        final Path runsRoot = tempDir.resolve("runs-bad-capture");
        seedRepo(repoRoot);
        seedRuns(
                runsRoot,
                """
                {"timestamp":"not-an-instant","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-1","method":"POST","path":"/orders","responseStatus":201,"durationMs":7,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            assertThat(runningCreator.result().succeeded()).isTrue();
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> httpTraffic = mapValue(workspace, "httpTraffic");
            assertThat(numberValue(httpTraffic, "totalRows")).isZero();
            assertThat((List<?>) httpTraffic.get("rows")).isEmpty();
            assertThat((String) httpTraffic.get("error")).contains("timestamp");
        }
    }

    @Test
    void shouldExposePartialSignalReadMetadataWhenSignalsContainMalformedTrailingLine() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-partial-signals");
        final Path runsRoot = tempDir.resolve("runs-partial-signals");
        seedRepo(repoRoot);
        final Path runDir = runsRoot.resolve("demo-app").resolve("demo-flow").resolve("run-creator-partial-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("summary.json"), KernelContractCodec.toStableJson(Map.of(
                "outcome", "ok",
                "executedSteps", 2,
                "replyMode", "immediate"
        )));
        Files.writeString(runDir.resolve("signals.ndjson"), """
                {"signalId":"signal-1","timestamp":"2026-07-11T09:10:00Z","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-partial-1","type":"step.started","step":"build-reply","attemptNumber":1}
                {"signalId":"signal-2","timestamp":"2026-07-11T09:10:01Z","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-partial-1","type":"metric.emitted","step":"build-reply","attemptNumber":1,"name":"railix.metric.value","value":1,"unit":"items","labels":{"result":"ok"}}
                {not-valid-json
                """);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> latestRunDetails = mapValue(workspace, "latestRunDetails");
            assertThat(latestRunDetails)
                    .containsEntry("runId", "run-creator-partial-1")
                    .containsEntry("signalStreamStatus", "partial");
            assertThat((String) latestRunDetails.get("signalReadError")).contains("signals.ndjson line 3");
            assertThat(numberValue(latestRunDetails, "signalCount")).isEqualTo(2);
            assertThat(listOfMaps(mapValue(latestRunDetails, "metrics"), "series")).hasSize(1);
        }
    }

    @Test
    void shouldExposeHistoricalPartialSignalPreviewWhenSignalsContainMalformedTrailingLine() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-partial-signals-history");
        final Path runsRoot = tempDir.resolve("runs-partial-signals-history");
        seedRepo(repoRoot);
        final Path runDir = runsRoot.resolve("demo-app").resolve("demo-flow").resolve("run-creator-partial-history-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("summary.json"), KernelContractCodec.toStableJson(Map.of(
                "outcome", "ok",
                "executedSteps", 2,
                "replyMode", "immediate"
        )));
        Files.writeString(runDir.resolve("signals.ndjson"), """
                {"signalId":"signal-1","timestamp":"2026-07-11T09:10:00Z","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-partial-history-1","type":"step.started","step":"build-reply","attemptNumber":1}
                {"signalId":"signal-2","timestamp":"2026-07-11T09:10:01Z","appId":"demo-app","flowId":"demo-flow","runId":"run-creator-partial-history-1","type":"metric.emitted","step":"build-reply","attemptNumber":1,"name":"railix.metric.value","value":1,"unit":"items","labels":{"result":"ok"}}
                {not-valid-json
                """);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> detailsResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/runs/detail?appId=demo-app&flowId=demo-flow&runId=run-creator-partial-history-1"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(detailsResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> details = KernelContractCodec.parseStableJsonObject(detailsResponse.body());
            assertThat(details)
                    .containsEntry("runId", "run-creator-partial-history-1")
                    .containsEntry("signalStreamStatus", "partial");
            assertThat((String) details.get("signalReadError")).contains("signals.ndjson line 3");
            assertThat(numberValue(details, "signalCount")).isEqualTo(2);
            assertThat(listOfMaps(details, "latestSignals")).hasSize(2);
            assertThat(listOfMaps(details, "latestSignals").getFirst()).containsEntry("type", "step.started");
        }
    }

    @Test
    void shouldExposeZeroedTrafficWhenNoHttpCaptureArtifactsExist() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-no-capture");
        final Path runsRoot = tempDir.resolve("runs-no-capture");
        seedRepo(repoRoot);
        seedRunsWithoutHttpCapture(runsRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat(workspace).containsEntry("controlEndpointReady", true);
            final Map<String, Object> httpTraffic = mapValue(workspace, "httpTraffic");
            assertThat(httpTraffic)
                    .containsEntry("title", "HTTP Traffic")
                    .containsEntry("columns", List.of("timestamp", "method", "path", "status", "durationMs"))
                    .containsEntry("error", "");
            assertThat(numberValue(httpTraffic, "totalRows")).isZero();
            assertThat(numberValue(httpTraffic, "handledRows")).isZero();
            assertThat(numberValue(httpTraffic, "rejectedRows")).isZero();
            assertThat(numberValue(httpTraffic, "failedRows")).isZero();
            assertThat((List<?>) httpTraffic.get("rows")).isEmpty();
        }
    }

    @Test
    void shouldExposeBoundedNewestFirstHttpTrafficRows() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-traffic-limit");
        final Path runsRoot = tempDir.resolve("runs-traffic-limit");
        seedRepo(repoRoot);
        for (int index = 1; index <= 13; index++) {
            writeHttpCapture(
                    runsRoot,
                    "run-" + index,
                    """
                    {"timestamp":"2026-07-11T09:%s:00Z","appId":"demo-app","flowId":"demo-flow","runId":"run-%s","method":"POST","path":"/orders/%s","responseStatus":200,"durationMs":%s,"runOutcome":"ok","captureKind":"handled-run"}
                    """.formatted(String.format("%02d", index), index, index, index)
            );
        }

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> httpTraffic = mapValue(workspace, "httpTraffic");
            assertThat(numberValue(httpTraffic, "totalRows")).isEqualTo(12);
            assertThat((List<?>) httpTraffic.get("rows")).hasSize(12);
            final List<Map<String, Object>> rows = listOfMaps(httpTraffic, "rows");
            assertThat(rows.getFirst()).containsEntry("runId", "run-13").containsEntry("path", "/orders/13");
            assertThat(rows.getLast()).containsEntry("runId", "run-2").containsEntry("path", "/orders/2");
            assertThat(workspaceResponse.body()).doesNotContain("\"runId\":\"run-1\"");
        }
    }

    @Test
    void shouldExposeLaunchPrepDiscoveryForRunnableInputsSpecsAndPackageReports() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-launch-prep");
        final Path runsRoot = tempDir.resolve("runs-launch-prep");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path planFile = repoRoot.resolve("launch-ready").resolve("plan.json");
        final Path envelopeFile = repoRoot.resolve("launch-ready").resolve("request.envelope.json");
        writeLaunchPlan(planFile);
        writeLaunchEnvelope(envelopeFile);
        seedPackageReport(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> launchPrep = mapValue(workspace, "launchPrep");
            assertThat(launchPrep)
                    .containsEntry("title", "Launch Prep");
            final List<Map<String, Object>> runnableInputs = listOfMaps(launchPrep, "runnableInputs");
            assertThat(runnableInputs).hasSize(1);
            assertThat(runnableInputs.getFirst())
                    .containsEntry("launchable", true)
                    .containsEntry("planLocation", planFile.toString())
                    .containsEntry("envelopeLocation", envelopeFile.toString());
            final List<Map<String, Object>> authoringSpecs = listOfMaps(launchPrep, "authoringSpecs");
            assertThat(authoringSpecs).hasSize(1);
            assertThat(authoringSpecs.getFirst())
                    .containsEntry("appId", "example.manual-hello")
                    .containsEntry("launchable", true)
                    .containsEntry("planLocation", repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml").toString())
                    .containsEntry("envelopeLocation", repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml").toString());
            assertThat((String) authoringSpecs.getFirst().get("blocker")).isEmpty();
            assertThat(listOfStrings(authoringSpecs.getFirst(), "envelopeExamples"))
                    .contains(repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml").toString());
            final List<Map<String, Object>> packageReports = listOfMaps(launchPrep, "packageReports");
            assertThat(packageReports).hasSize(1);
            assertThat(packageReports.getFirst())
                    .containsEntry("type", "app-image")
                    .containsEntry("mode", "headless")
                    .containsEntry("launchable", false);
            assertThat((String) packageReports.getFirst().get("blocker")).contains("plan.json and envelope.json");
        }
    }

    @Test
    void shouldExposeRawAuthoringSpecTextThroughCreatorApi() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-read");
        final Path runsRoot = tempDir.resolve("runs-authoring-read");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/authoring-spec?path=" + java.net.URLEncoder.encode(appSpecFile.toString(), StandardCharsets.UTF_8)))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            final Map<String, Object> document = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(document)
                    .containsEntry("path", appSpecFile.toString())
                    .containsEntry("relativePath", "examples/manual-hello/railix.app.yaml")
                    .containsEntry("saved", false)
                    .containsEntry("parseStatus", "valid")
                    .containsEntry("appId", "example.manual-hello")
                    .containsEntry("flowId", "hello")
                    .containsEntry("message", "");
            assertThat((String) document.get("content"))
                    .contains("app:")
                    .contains("name: Manual Hello")
                    .contains("railix.std.trigger.ManualTrigger");
        }
    }

    @Test
    void shouldRejectAuthoringSpecReadsOutsideExamplesTree() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-read-invalid");
        final Path runsRoot = tempDir.resolve("runs-authoring-read-invalid");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path invalidPath = repoRoot.resolve("packs").resolve("railix.demo.pack").resolve("pack.yaml");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/authoring-spec?path=" + java.net.URLEncoder.encode(invalidPath.toString(), StandardCharsets.UTF_8)))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(400);
            final Map<String, Object> error = KernelContractCodec.parseStableJsonObject(response.body());
            assertThat(error)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) error.get("message")).contains("repoRoot/examples");
        }
    }

    @Test
    void shouldSaveUpdatedAuthoringSpecThroughCreatorApiAndRefreshWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-save");
        final Path runsRoot = tempDir.resolve("runs-authoring-save");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final Path envelopeFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml");
        final String updatedSpec = """
                app:
                  id: example.manual-hello-edited
                  name: Manual Hello Edited
                  version: 0.1.0
                  dependencies:
                    - { id: railix.std.trigger, version: 0.1.0 }
                    - { id: railix.std.data, version: 0.1.0 }
                  flows:
                    - id: hello-edited
                      trigger: manual
                      steps:
                        - id: manual
                          use: railix.std.trigger.ManualTrigger
                          next: build-reply
                        - id: build-reply
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: ctx.name
                                expression:
                                  op: default
                                  value: { path: payload.name }
                                  default: { const: World }
                              - target: reply.mode
                                expression: { const: immediate }
                              - target: reply.status
                                expression: { const: ok }
                              - target: reply.payload.message
                                expression:
                                  op: template
                                  template: "Hello again ${ctx.name}"
                          next: end
                """;

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> saveResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec"))
                            .header("Content-Type", "application/json")
                            .method("PUT", HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "path", appSpecFile.toString(),
                                    "content", updatedSpec
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(saveResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> saved = KernelContractCodec.parseStableJsonObject(saveResponse.body());
            assertThat(saved)
                    .containsEntry("saved", true)
                    .containsEntry("parseStatus", "valid")
                    .containsEntry("appId", "example.manual-hello-edited")
                    .containsEntry("flowId", "hello-edited");
            assertThat(Files.readString(appSpecFile)).isEqualTo(updatedSpec);

            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final List<Map<String, Object>> authoringSpecs = listOfMaps(mapValue(workspace, "launchPrep"), "authoringSpecs");
            assertThat(authoringSpecs.getFirst())
                    .containsEntry("appId", "example.manual-hello-edited")
                    .containsEntry("appName", "Manual Hello Edited")
                    .containsEntry("launchable", true);

            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", appSpecFile.toString(),
                                    "envelopeLocation", envelopeFile.toString(),
                                    "runId", "run-creator-authoring-save-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(startResponse.statusCode()).isEqualTo(202);
            final String sessionId = (String) KernelContractCodec.parseStableJsonObject(startResponse.body()).get("sessionId");
            final Map<String, Object> terminalControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            assertThat(findSession(terminalControlSessions, sessionId))
                    .containsEntry("appId", "example.manual-hello-edited")
                    .containsEntry("flowId", "hello-edited")
                    .containsEntry("state", "succeeded");
        }
    }

    @Test
    void shouldSaveInvalidAuthoringSpecDraftAndExposeBlockedDiagnostics() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-save-invalid");
        final Path runsRoot = tempDir.resolve("runs-authoring-save-invalid");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final String invalidSpec = """
                app:
                  id: example.manual-hello
                  name: Manual Hello Broken
                  flows:
                    - id: one
                      trigger: manual
                      steps:
                        - id: first
                          use: railix.std.trigger.ManualTrigger
                          next: end
                    - id: two
                      trigger: manual
                      steps:
                        - id: second
                          use: railix.std.trigger.ManualTrigger
                          next: end
                """;

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> saveResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec"))
                            .header("Content-Type", "application/json")
                            .method("PUT", HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "path", appSpecFile.toString(),
                                    "content", invalidSpec
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(saveResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> saved = KernelContractCodec.parseStableJsonObject(saveResponse.body());
            assertThat(saved)
                    .containsEntry("saved", true)
                    .containsEntry("parseStatus", "invalid");
            assertThat((String) saved.get("message")).contains("exactly one flow");
            assertThat(Files.readString(appSpecFile)).isEqualTo(invalidSpec);

            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final List<Map<String, Object>> authoringSpecs = listOfMaps(mapValue(workspace, "launchPrep"), "authoringSpecs");
            assertThat(authoringSpecs.getFirst())
                    .containsEntry("appName", "Manual Hello Broken")
                    .containsEntry("launchable", false);
            assertThat((String) authoringSpecs.getFirst().get("blocker")).contains("exactly one flow");
        }
    }

    @Test
    void shouldCreateAuthoringSpecExampleThroughCreatorApiAndRefreshWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-create");
        final Path runsRoot = tempDir.resolve("runs-authoring-create");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> createResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec/create"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "exampleId", "fresh-hello",
                                    "appId", "example.fresh-hello",
                                    "appName", "Fresh Hello",
                                    "flowId", "hello-fresh"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(createResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> created = KernelContractCodec.parseStableJsonObject(createResponse.body());
            final Path createdSpecFile = repoRoot.resolve("examples").resolve("fresh-hello").resolve("railix.app.yaml");
            final Path createdEnvelopeFile = repoRoot.resolve("examples").resolve("fresh-hello").resolve("sample.envelope.yaml");
            assertThat(created)
                    .containsEntry("created", true)
                    .containsEntry("parseStatus", "valid")
                    .containsEntry("path", createdSpecFile.toString())
                    .containsEntry("envelopePath", createdEnvelopeFile.toString())
                    .containsEntry("appId", "example.fresh-hello")
                    .containsEntry("flowId", "hello-fresh");
            assertThat(Files.exists(createdSpecFile)).isTrue();
            assertThat(Files.exists(createdEnvelopeFile)).isTrue();
            assertThat(Files.readString(createdSpecFile))
                    .contains("id: example.fresh-hello")
                    .contains("name: Fresh Hello")
                    .contains("id: hello-fresh");
            assertThat(Files.readString(createdEnvelopeFile))
                    .contains("source: creator.manual")
                    .contains("name: Ada");

            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final List<Map<String, Object>> authoringSpecs = listOfMaps(mapValue(workspace, "launchPrep"), "authoringSpecs");
            assertThat(authoringSpecs).anySatisfy(spec -> assertThat(spec)
                    .containsEntry("path", createdSpecFile.toString())
                    .containsEntry("appId", "example.fresh-hello")
                    .containsEntry("appName", "Fresh Hello")
                    .containsEntry("launchable", true)
                    .containsEntry("envelopeLocation", createdEnvelopeFile.toString()));
        }
    }

    @Test
    void shouldLaunchCreatedAuthoringSpecThroughCreatorControlSession() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-create-launch");
        final Path runsRoot = tempDir.resolve("runs-authoring-create-launch");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final Map<String, Object> created = KernelContractCodec.parseStableJsonObject(client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec/create"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "exampleId", "created-launch",
                                    "appId", "example.created-launch",
                                    "appName", "Created Launch",
                                    "flowId", "launch-flow"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body());

            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", created.get("path"),
                                    "envelopeLocation", created.get("envelopePath"),
                                    "runId", "run-creator-created-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(startResponse.statusCode()).isEqualTo(202);
            final String sessionId = (String) KernelContractCodec.parseStableJsonObject(startResponse.body()).get("sessionId");
            final Map<String, Object> terminalControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            assertThat(findSession(terminalControlSessions, sessionId))
                    .containsEntry("appId", "example.created-launch")
                    .containsEntry("flowId", "launch-flow")
                    .containsEntry("runId", "run-creator-created-1")
                    .containsEntry("state", "succeeded");
        }
    }

    @Test
    void shouldRejectAuthoringSpecCreateWhenExampleIdIsUnsafe() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-create-invalid");
        final Path runsRoot = tempDir.resolve("runs-authoring-create-invalid");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> createResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/authoring-spec/create"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "exampleId", "../escape",
                                    "appId", "example.escape",
                                    "appName", "Escape",
                                    "flowId", "hello"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(createResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> error = KernelContractCodec.parseStableJsonObject(createResponse.body());
            assertThat(error)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) error.get("message")).contains("exampleId");
        }
    }

    @Test
    void shouldExportAuthoringSpecToPersistedJsonPairAndRefreshWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-export");
        final Path runsRoot = tempDir.resolve("runs-authoring-export");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final Path envelopeFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml");
        final Path exportDir = repoRoot.resolve("examples").resolve("manual-hello").resolve("creator-export").resolve("sample.envelope");
        final Path exportedPlan = exportDir.resolve("plan.json");
        final Path exportedEnvelope = exportDir.resolve("sample.envelope.json");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> exportResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec/export"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "path", appSpecFile.toString(),
                                    "envelopePath", envelopeFile.toString()
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(exportResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> exported = KernelContractCodec.parseStableJsonObject(exportResponse.body());
            assertThat(exported)
                    .containsEntry("path", appSpecFile.toString())
                    .containsEntry("envelopePath", envelopeFile.toString())
                    .containsEntry("exported", true)
                    .containsEntry("appId", "example.manual-hello")
                    .containsEntry("flowId", "hello")
                    .containsEntry("planLocation", exportedPlan.toString())
                    .containsEntry("envelopeLocation", exportedEnvelope.toString())
                    .containsEntry("message", "");
            assertThat(Files.exists(exportedPlan)).isTrue();
            assertThat(Files.exists(exportedEnvelope)).isTrue();
            assertThat(KernelContractCodec.appPlanFromUiModel(KernelContractCodec.parseStableJsonObject(Files.readString(exportedPlan))))
                    .extracting(AppPlan::appId, AppPlan::flowId)
                    .containsExactly("example.manual-hello", "hello");
            assertThat(KernelContractCodec.envelopeFromUiModel(KernelContractCodec.parseStableJsonObject(Files.readString(exportedEnvelope))))
                    .extracting(Envelope::source, Envelope::protocol)
                    .containsExactly("creator.manual", "manual");

            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final List<Map<String, Object>> runnableInputs = listOfMaps(mapValue(workspace, "launchPrep"), "runnableInputs");
            assertThat(runnableInputs).anySatisfy(candidate -> assertThat(candidate)
                    .containsEntry("planLocation", exportedPlan.toString())
                    .containsEntry("envelopeLocation", exportedEnvelope.toString())
                    .containsEntry("launchable", true)
                    .containsEntry("source", "persisted-json-pair"));
        }
    }

    @Test
    void shouldLaunchExportedAuthoringSpecPairThroughCreatorLaunchBoundary() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-export-launch");
        final Path runsRoot = tempDir.resolve("runs-authoring-export-launch");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final Path envelopeFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final Map<String, Object> exported = KernelContractCodec.parseStableJsonObject(client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/authoring-spec/export"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "path", appSpecFile.toString(),
                                    "envelopePath", envelopeFile.toString()
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body());

            final HttpResponse<String> launchResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/launch-run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", exported.get("planLocation"),
                                    "envelopeLocation", exported.get("envelopeLocation"),
                                    "runId", "run-creator-export-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(launchResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> launch = KernelContractCodec.parseStableJsonObject(launchResponse.body());
            assertThat(launch)
                    .containsEntry("exitCode", 0)
                    .containsEntry("succeeded", true)
                    .containsEntry("runId", "run-creator-export-1")
                    .containsEntry("outcome", "ok");
            final Path runFolder = Path.of((String) launch.get("runFolder"));
            assertThat(Files.readString(runFolder.resolve("summary.json")))
                    .contains("\"appId\":\"example.manual-hello\"")
                    .contains("\"flowId\":\"hello\"")
                    .contains("\"outcome\":\"ok\"");
        }
    }

    @Test
    void shouldRejectAuthoringSpecExportWhenEnvelopeLeavesExampleDirectory() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-export-invalid");
        final Path runsRoot = tempDir.resolve("runs-authoring-export-invalid");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final Path invalidEnvelope = repoRoot.resolve("examples").resolve("other").resolve("sample.envelope.yaml");
        Files.createDirectories(invalidEnvelope.getParent());
        Files.writeString(invalidEnvelope, """
                envelope:
                  source: creator.other
                  protocol: manual
                  payload: {}
                  metadata: {}
                  replyChannel:
                    supported: true
                    modes: [immediate]
                """);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> exportResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/authoring-spec/export"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "path", appSpecFile.toString(),
                                    "envelopePath", invalidEnvelope.toString()
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(exportResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> error = KernelContractCodec.parseStableJsonObject(exportResponse.body());
            assertThat(error)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) error.get("message")).contains("same example directory");
        }
    }

    @Test
    void shouldExposePackagedCreatorReportAsRepoRootBoundLaunchEvidence() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-creator-package-report");
        final Path runsRoot = tempDir.resolve("runs-creator-package-report");
        seedRepo(repoRoot);
        seedCreatorPackageReport(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> launchPrep = mapValue(workspace, "launchPrep");
            final List<Map<String, Object>> packageReports = listOfMaps(launchPrep, "packageReports");
            assertThat(packageReports).hasSize(1);
            assertThat(packageReports.getFirst())
                    .containsEntry("type", "app-image")
                    .containsEntry("mode", "creator")
                    .containsEntry("appName", "RailixCreator")
                    .containsEntry("launchable", false);
            assertThat((String) packageReports.getFirst().get("blocker")).contains("--repo-root");
        }
    }

    @Test
    void shouldIgnoreOrphanAndIgnoredLaunchCandidatesWhileChoosingFirstSortedRunnablePair() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-launch-candidates");
        final Path runsRoot = tempDir.resolve("runs-launch-candidates");
        seedRepo(repoRoot);
        writeLaunchPlan(repoRoot.resolve("launch-orphan").resolve("plan.json"));
        writeLaunchPlan(repoRoot.resolve("launch-ready").resolve("a.plan.json"));
        writeLaunchPlan(repoRoot.resolve("launch-ready").resolve("z.plan.json"));
        writeLaunchEnvelope(repoRoot.resolve("launch-ready").resolve("a.envelope.json"));
        writeLaunchEnvelope(repoRoot.resolve("launch-ready").resolve("z.envelope.json"));
        writeLaunchPlan(repoRoot.resolve("target").resolve("ignored").resolve("plan.json"));
        writeLaunchEnvelope(repoRoot.resolve("target").resolve("ignored").resolve("request.envelope.json"));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> launchPrep = mapValue(workspace, "launchPrep");
            final List<Map<String, Object>> runnableInputs = listOfMaps(launchPrep, "runnableInputs");
            assertThat(runnableInputs).hasSize(1);
            assertThat(runnableInputs.getFirst())
                    .containsEntry("directory", repoRoot.resolve("launch-ready").toString())
                    .containsEntry("planLocation", repoRoot.resolve("launch-ready").resolve("a.plan.json").toString())
                    .containsEntry("envelopeLocation", repoRoot.resolve("launch-ready").resolve("a.envelope.json").toString());
            assertThat(workspaceResponse.body()).doesNotContain(repoRoot.resolve("target").resolve("ignored").toString());
            assertThat(workspaceResponse.body()).doesNotContain(repoRoot.resolve("launch-orphan").toString());
        }
    }

    @Test
    void shouldExposeMalformedPackageReportAsBlockedLaunchPrepEvidence() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-invalid-package-report");
        final Path runsRoot = tempDir.resolve("runs-invalid-package-report");
        seedRepo(repoRoot);
        seedInvalidPackageReport(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> workspaceResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(workspaceResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            final Map<String, Object> launchPrep = mapValue(workspace, "launchPrep");
            final List<Map<String, Object>> packageReports = listOfMaps(launchPrep, "packageReports");
            assertThat(packageReports).hasSize(1);
            assertThat(packageReports.getFirst())
                    .containsEntry("type", "invalid")
                    .containsEntry("launchable", false)
                    .containsEntry("reportPath", repoRoot.resolve("build").resolve("reports").resolve("package-app-image.json").toString());
            assertThat((String) packageReports.getFirst().get("blocker")).contains("Package report is malformed");
        }
    }

    @Test
    void shouldExposeLiveCreatorControlSessionAcrossControlApiAndWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-live-control");
        final Path runsRoot = tempDir.resolve("runs-live-control");
        seedRepo(repoRoot);
        final Path planFile = writeLaunchPlan(tempDir.resolve("live-plan.json"));
        final Path envelopeFile = writeLaunchEnvelope(tempDir.resolve("live-envelope.json"));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", planFile.toString(),
                                    "envelopeLocation", envelopeFile.toString(),
                                    "runId", "run-creator-live-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(startResponse.statusCode()).isEqualTo(202);
            final Map<String, Object> started = KernelContractCodec.parseStableJsonObject(startResponse.body());
            assertThat(started)
                    .containsEntry("runId", "run-creator-live-1")
                    .containsEntry("cancelSupported", false);
            assertThat((String) started.get("state")).isIn("accepted", "running");
            final String sessionId = (String) started.get("sessionId");

            final Map<String, Object> liveControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "accepted".equals(session.get("state")) || "running".equals(session.get("state")));
            assertThat(numberValue(liveControlSessions, "activeCount")).isEqualTo(1);

            final Map<String, Object> terminalControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            final Map<String, Object> terminalSession = findSession(terminalControlSessions, sessionId);
            assertThat(terminalSession)
                    .containsEntry("appId", "creator-launch-app")
                    .containsEntry("flowId", "manual-flow")
                    .containsEntry("runId", "run-creator-live-1")
                    .containsEntry("state", "succeeded")
                    .containsEntry("succeeded", true)
                    .containsEntry("stdoutTruncated", false)
                    .containsEntry("stderrTruncated", false)
                    .containsEntry("exitCode", 0);
            assertThat((String) terminalSession.get("runFolder")).contains("run-creator-live-1");
            assertThat(numberValue(terminalSession, "signalCount")).isGreaterThan(0);
            assertThat(listOfMaps(terminalSession, "latestSignals")).isNotEmpty();
            assertThat(listOfMaps(terminalSession, "timeline")).isNotEmpty();
            assertThat(mapValue(terminalSession, "graphActivity")).containsEntry("available", true);
            assertThat(findGraphNode(terminalSession, "finish")).containsEntry("state", "complete");
            assertThat(findTimelineEvent(terminalSession, "context.patched"))
                    .containsEntry("stepId", "build-reply");
            assertThat((String) terminalSession.get("lastSignalType")).isNotBlank();
            final Map<String, Object> terminalContextDiff = mapValue(terminalSession, "contextDiff");
            assertThat(terminalContextDiff).containsEntry("available", true);
            assertThat(listOfStrings(terminalContextDiff, "changedPaths"))
                    .contains("reply.mode", "reply.payload");

            final Map<String, Object> workspace = awaitWorkspaceSession(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            assertThat(workspace)
                    .containsEntry("controlEndpointReady", true)
                    .containsEntry("controlEndpointStatus", "creator-owned-live-run-sessions");
            final Map<String, Object> workspaceControlSessions = mapValue(workspace, "controlSessions");
            assertThat(numberValue(workspaceControlSessions, "activeCount")).isZero();
            assertThat(findSession(workspaceControlSessions, sessionId))
                    .containsEntry("state", "succeeded")
                    .containsEntry("exitCode", 0);
            assertThat(numberValue(findSession(workspaceControlSessions, sessionId), "signalCount")).isGreaterThan(0);
            assertThat((List<?>) workspace.get("runs")).hasSize(1);
        }
    }

    @Test
    void shouldExposeAcceptedThenFailedCreatorControlSessionAcrossControlApiAndWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-live-control-failed");
        final Path runsRoot = tempDir.resolve("runs-live-control-failed");
        seedRepo(repoRoot);
        final Path planFile = writeLaunchPlan(tempDir.resolve("live-plan-failed.json"));
        final Path missingEnvelope = tempDir.resolve("missing-envelope.json");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", planFile.toString(),
                                    "envelopeLocation", missingEnvelope.toString(),
                                    "runId", "run-creator-live-failed-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(startResponse.statusCode()).isEqualTo(202);
            final Map<String, Object> started = KernelContractCodec.parseStableJsonObject(startResponse.body());
            assertThat(started)
                    .containsEntry("runId", "run-creator-live-failed-1")
                    .containsEntry("cancelSupported", false);
            assertThat((String) started.get("state")).isIn("accepted", "running");
            final String sessionId = (String) started.get("sessionId");

            final Map<String, Object> activeControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "accepted".equals(session.get("state")) || "running".equals(session.get("state")));
            assertThat(numberValue(activeControlSessions, "activeCount")).isEqualTo(1);

            final Map<String, Object> terminalControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "failed".equals(session.get("state")));
            final Map<String, Object> terminalSession = findSession(terminalControlSessions, sessionId);
            assertThat(terminalSession)
                    .containsEntry("appId", "creator-launch-app")
                    .containsEntry("flowId", "manual-flow")
                    .containsEntry("runId", "run-creator-live-failed-1")
                    .containsEntry("state", "failed")
                    .containsEntry("succeeded", false)
                    .containsEntry("stdoutTruncated", false)
                    .containsEntry("stderrTruncated", false);
            assertThat(numberValue(terminalSession, "exitCode")).isNotEqualTo(0);
            assertThat((String) terminalSession.get("message")).isNotBlank();
            assertThat((String) terminalSession.get("message")).contains("envelope");
            assertThat(numberValue(terminalSession, "signalCount")).isZero();
            assertThat((String) terminalSession.get("signalStreamStatus")).isEqualTo("empty");
            assertThat(mapValue(terminalSession, "graphActivity")).containsEntry("available", false);
            assertThat(listOfMaps(terminalSession, "latestSignals")).isEmpty();
            assertThat(mapValue(terminalSession, "terminalFailure")).isEmpty();
            assertThat((String) terminalSession.get("failureSummary")).isEmpty();

            final Map<String, Object> workspace = awaitWorkspaceSession(client, baseUri, sessionId, session ->
                    "failed".equals(session.get("state")));
            final Map<String, Object> workspaceControlSessions = mapValue(workspace, "controlSessions");
            assertThat(numberValue(workspaceControlSessions, "activeCount")).isZero();
            final Map<String, Object> workspaceSession = findSession(workspaceControlSessions, sessionId);
            assertThat(workspaceSession)
                    .containsEntry("state", "failed")
                    .containsEntry("succeeded", false);
            assertThat(numberValue(workspaceSession, "signalCount")).isZero();
            assertThat((String) workspaceSession.get("signalStreamStatus")).isEqualTo("empty");
            assertThat(mapValue(workspaceSession, "graphActivity")).containsEntry("available", false);
            assertThat((List<?>) workspace.get("runs")).isEmpty();
        }
    }

    @Test
    void shouldLaunchBuiltAppRunThroughCreatorApiAndRefreshWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-launch");
        final Path runsRoot = tempDir.resolve("runs-launch");
        seedRepo(repoRoot);
        final Path planFile = writeLaunchPlan(tempDir.resolve("launch-plan.json"));
        final Path envelopeFile = writeLaunchEnvelope(tempDir.resolve("launch-envelope.json"));

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpResponse<String> launchResponse = client.send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/launch-run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", planFile.toString(),
                                    "envelopeLocation", envelopeFile.toString(),
                                    "runId", "run-creator-launch-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(launchResponse.statusCode()).isEqualTo(200);
            final Map<String, Object> launch = KernelContractCodec.parseStableJsonObject(launchResponse.body());
            assertThat(launch)
                    .containsEntry("exitCode", 0)
                    .containsEntry("succeeded", true)
                    .containsEntry("runId", "run-creator-launch-1")
                    .containsEntry("outcome", "ok")
                    .containsEntry("stdoutTruncated", false)
                    .containsEntry("stderrTruncated", false);
            assertThat(numberValue(launch, "stdoutSize")).isGreaterThan(0);
            assertThat(numberValue(launch, "stderrSize")).isZero();
            assertThat((String) launch.get("stdout")).contains("runId=run-creator-launch-1").contains("outcome=ok");
            final Path runFolder = Path.of((String) launch.get("runFolder"));
            assertThat(Files.readString(runFolder.resolve("summary.json")))
                    .contains("\"outcome\":\"ok\"")
                    .contains("\"executedSteps\":1");

            final HttpResponse<String> workspaceResponse = client.send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            final Map<String, Object> workspace = KernelContractCodec.parseStableJsonObject(workspaceResponse.body());
            assertThat((List<?>) workspace.get("runs")).hasSize(1);
            final Map<String, Object> httpTraffic = mapValue(workspace, "httpTraffic");
            assertThat(numberValue(httpTraffic, "totalRows")).isZero();
            assertThat(workspaceResponse.body()).contains("run-creator-launch-1");
        }
    }

    @Test
    void shouldLaunchAuthoringSpecYamlThroughCreatorControlSessionAndRefreshWorkspace() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-authoring-launch");
        final Path runsRoot = tempDir.resolve("runs-authoring-launch");
        seedRepo(repoRoot);
        seedAuthoringSpec(repoRoot);
        final Path appSpecFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("railix.app.yaml");
        final Path envelopeFile = repoRoot.resolve("examples").resolve("manual-hello").resolve("sample.envelope.yaml");

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpClient client = HttpClient.newHttpClient();
            final URI baseUri = runningCreator.result().creatorUrl();
            final HttpResponse<String> startResponse = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "planLocation", appSpecFile.toString(),
                                    "envelopeLocation", envelopeFile.toString(),
                                    "runId", "run-creator-authoring-1"
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(startResponse.statusCode()).isEqualTo(202);
            final Map<String, Object> started = KernelContractCodec.parseStableJsonObject(startResponse.body());
            final String sessionId = (String) started.get("sessionId");
            assertThat(started)
                    .containsEntry("runId", "run-creator-authoring-1")
                    .containsEntry("cancelSupported", false);

            final Map<String, Object> terminalControlSessions = awaitControlSessions(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            final Map<String, Object> terminalSession = findSession(terminalControlSessions, sessionId);
            assertThat(terminalSession)
                    .containsEntry("appId", "example.manual-hello")
                    .containsEntry("flowId", "hello")
                    .containsEntry("runId", "run-creator-authoring-1")
                    .containsEntry("state", "succeeded")
                    .containsEntry("succeeded", true)
                    .containsEntry("exitCode", 0);
            assertThat(numberValue(terminalSession, "signalCount")).isGreaterThan(0);
            assertThat(findTimelineEvent(terminalSession, "reply.produced"))
                    .containsEntry("replyMode", "immediate");

            final Map<String, Object> workspace = awaitWorkspaceSession(client, baseUri, sessionId, session ->
                    "succeeded".equals(session.get("state")) || "failed".equals(session.get("state")));
            assertThat((List<?>) workspace.get("runs")).hasSize(1);
            assertThat(mapValue(workspace, "latestRunDetails"))
                    .containsEntry("appId", "example.manual-hello")
                    .containsEntry("flowId", "hello")
                    .containsEntry("runId", "run-creator-authoring-1");
        }
    }

    @Test
    void shouldRejectInvalidCreatorControlSessionRequestsWithoutPlanLocation() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-control-invalid");
        final Path runsRoot = tempDir.resolve("runs-control-invalid");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> launchResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/control/sessions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "envelopeLocation", tempDir.resolve("missing-envelope.json").toString()
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(launchResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> launch = KernelContractCodec.parseStableJsonObject(launchResponse.body());
            assertThat(launch)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) launch.get("message")).contains("planLocation");
        }
    }

    @Test
    void shouldRejectCreatorPackagingModeWhenCombinedWithAppSpec() throws Exception {
        final Path appSpecFile = tempDir.resolve("creator-app-spec").resolve("railix.app.yaml");
        final Path packageDir = tempDir.resolve("creator-mode-package");
        final Path reportFile = tempDir.resolve("creator-mode-package-report.json");
        Files.createDirectories(appSpecFile.getParent());
        Files.writeString(appSpecFile, """
                app:
                  id: example.manual-hello
                  name: Manual Hello
                """);

        final FailedProcess failedProcess = runProcessExpectFailure(List.of(
                "sh",
                repoRoot().resolve("tools").resolve("railix.sh").toString(),
                "package",
                "--mode", "creator",
                "--app", appSpecFile.toString(),
                "--dest", packageDir.toString(),
                "--report", reportFile.toString()
        ));

        assertThat(failedProcess.output()).contains("--app is not supported with --mode creator");
        assertThat(Files.exists(reportFile)).isFalse();
    }

    @Test
    void shouldRejectCreatorLaunchRequestsWithoutPlanLocation() throws Exception {
        final Path repoRoot = tempDir.resolve("repo-launch-invalid");
        final Path runsRoot = tempDir.resolve("runs-launch-invalid");
        seedRepo(repoRoot);

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of(
                        "--repo-root", repoRoot.toString(),
                        "--runs-root", runsRoot.toString(),
                        "--port", "0"
                ),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        )) {
            final HttpResponse<String> launchResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runningCreator.result().creatorUrl().resolve("/api/launch-run"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(Map.of(
                                    "envelopeLocation", tempDir.resolve("missing-envelope.json").toString()
                            ))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(launchResponse.statusCode()).isEqualTo(400);
            final Map<String, Object> launch = KernelContractCodec.parseStableJsonObject(launchResponse.body());
            assertThat(launch)
                    .containsEntry("exitCode", 2)
                    .containsEntry("succeeded", false);
            assertThat((String) launch.get("message")).contains("planLocation");
        }
    }

    @Test
    void shouldRejectMissingRepoRootAndDefaultRunsRootWhenOmitted() {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try (CreatorShellMain.RunningCreator runningCreator = CreatorShellMain.launch(
                java.util.List.of("--port", "0"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        )) {
            assertThat(runningCreator.result().succeeded()).isFalse();
            assertThat(runningCreator.result().exitCode()).isEqualTo(2);
            assertThat(stdout.toString()).isEmpty();
            assertThat(stderr.toString())
                    .contains("Usage: railix-creator")
                    .contains("Missing required argument: --repo-root");
        }
    }

    private static void seedRepo(final Path repoRoot) throws Exception {
        seedPack(repoRoot, "railix.demo.pack", "Demo pack for Creator shell tests.");
    }

    private static void seedPack(final Path repoRoot, final String packId, final String summary) throws Exception {
        final Path packDir = repoRoot.resolve("packs").resolve(packId);
        Files.createDirectories(packDir.resolve("steps"));
        Files.writeString(packDir.resolve("pack.yaml"), """
                id: %s
                summary: %s
                """.formatted(packId, summary));
        Files.writeString(packDir.resolve("steps").resolve("ManualTrigger.step.yaml"), """
                id: %s.ManualTrigger
                displayName: Manual Trigger
                summary: Produces a sample envelope.
                """.formatted(packId));
    }

    private static void seedAuthoringSpec(final Path repoRoot) throws Exception {
        final Path exampleDir = repoRoot.resolve("examples").resolve("manual-hello");
        Files.createDirectories(exampleDir);
        Files.writeString(exampleDir.resolve("railix.app.yaml"), """
                app:
                  id: example.manual-hello
                  name: Manual Hello
                  version: 0.1.0
                  dependencies:
                    - { id: railix.std.trigger, version: 0.1.0 }
                    - { id: railix.std.data, version: 0.1.0 }
                  flows:
                    - id: hello
                      trigger: manual
                      steps:
                        - id: manual
                          use: railix.std.trigger.ManualTrigger
                          next: build-reply
                        - id: build-reply
                          use: railix.std.data.DataTransform
                          config:
                            mappings:
                              - target: ctx.name
                                expression:
                                  op: default
                                  value: { path: payload.name }
                                  default: { const: World }
                              - target: reply.mode
                                expression: { const: immediate }
                              - target: reply.status
                                expression: { const: ok }
                              - target: reply.payload.message
                                expression:
                                  op: template
                                  template: "Hello ${ctx.name}"
                          next: end
                """);
        Files.writeString(exampleDir.resolve("sample.envelope.yaml"), """
                envelope:
                  source: creator.manual
                  protocol: manual
                  payload:
                    name: Ada
                  metadata:
                    actor: developer
                  replyChannel:
                    supported: true
                    modes: [immediate]
                """);
    }

    private static void seedPackageReport(final Path repoRoot) throws Exception {
        final Path reportFile = repoRoot.resolve("build").resolve("reports").resolve("package-app-image.json");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, KernelContractCodec.toStableJson(Map.of(
                "type", "app-image",
                "appName", "RailixII",
                "artifacts", Map.of(
                        "launcherPath", repoRoot.resolve("build").resolve("package").resolve("RailixII").toString()
                )
        )));
    }

    private static void seedInvalidPackageReport(final Path repoRoot) throws Exception {
        final Path reportFile = repoRoot.resolve("build").resolve("reports").resolve("package-app-image.json");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, "{not-valid-json");
    }

    private static void seedCreatorPackageReport(final Path repoRoot) throws Exception {
        final Path reportFile = repoRoot.resolve("build").resolve("reports").resolve("package-creator-app-image.json");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, KernelContractCodec.toStableJson(Map.of(
                "type", "app-image",
                "mode", "creator",
                "appName", "RailixCreator",
                "artifacts", Map.of(
                        "launcherPath", repoRoot.resolve("build").resolve("package-creator").resolve("RailixCreator.app").resolve("Contents").resolve("MacOS").resolve("RailixCreator").toString()
                )
        )));
    }

    private static void seedRuns(final Path runsRoot, final String httpCaptureJson) throws Exception {
        final Path runDir = runsRoot.resolve("demo-app").resolve("demo-flow").resolve("run-creator-1");
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir);
        Files.writeString(runDir.resolve("http-capture.json"), httpCaptureJson);
    }

    private static void seedRunsWithoutHttpCapture(final Path runsRoot) throws Exception {
        final Path runDir = runsRoot.resolve("demo-app").resolve("demo-flow").resolve("run-creator-1");
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir);
    }

    private static void seedRunArtifacts(final Path runDir) throws Exception {
        seedRunArtifacts(
                runDir,
                "demo-app",
                "demo-flow",
                "run-creator-1",
                "seeded",
                "railix.metric.value",
                7,
                "creator.seeded",
                Instant.parse("2026-07-11T09:10:06Z")
        );
    }

    private static void seedNamedRun(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId,
            final String payloadMessage,
            final String metricName,
            final int metricValue,
            final String auditEventName,
            final Instant updatedAt
    ) throws Exception {
        final Path runDir = runsRoot.resolve(appId).resolve(flowId).resolve(runId);
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir, appId, flowId, runId, payloadMessage, metricName, metricValue, auditEventName, updatedAt);
    }

    private static void seedMalformedSummaryRun(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId,
            final Instant updatedAt
    ) throws Exception {
        final Path runDir = runsRoot.resolve(appId).resolve(flowId).resolve(runId);
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir, appId, flowId, runId, "Malformed summary payload", "railix.metric.value", 7, "creator.seeded", updatedAt);
        Files.writeString(runDir.resolve("summary.json"), "{not-valid-json");
        Files.setLastModifiedTime(runDir.resolve("summary.json"), FileTime.from(updatedAt));
    }

    private static void seedMissingSummaryRun(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId,
            final Instant updatedAt
    ) throws Exception {
        final Path runDir = runsRoot.resolve(appId).resolve(flowId).resolve(runId);
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir, appId, flowId, runId, "Missing summary payload", "railix.metric.value", 7, "creator.seeded", updatedAt);
        Files.delete(runDir.resolve("summary.json"));
        Files.setLastModifiedTime(runDir.resolve("signals.ndjson"), FileTime.from(updatedAt));
    }

    private static void seedRunWithOutcome(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId,
            final String outcome,
            final Instant updatedAt
    ) throws Exception {
        final Path runDir = runsRoot.resolve(appId).resolve(flowId).resolve(runId);
        Files.createDirectories(runDir);
        seedRunArtifacts(runDir, appId, flowId, runId, "Outcome payload", "railix.metric.value", 7, "creator.seeded", updatedAt);
        Files.writeString(runDir.resolve("summary.json"), KernelContractCodec.toStableJson(Map.of(
                "outcome", outcome,
                "executedSteps", 2,
                "replyMode", "immediate"
        )));
        Files.setLastModifiedTime(runDir.resolve("summary.json"), FileTime.from(updatedAt));
    }

    private static void seedTerminalFailureRun(
            final Path runsRoot,
            final String appId,
            final String flowId,
            final String runId,
            final Instant updatedAt
    ) throws Exception {
        final Path runDir = runsRoot.resolve(appId).resolve(flowId).resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("summary.json"), KernelContractCodec.toStableJson(Map.of(
                "outcome", "failed",
                "executedSteps", 2,
                "replyMode", "immediate",
                "terminalFailure", Map.of(
                        "phase", "reply.validation",
                        "type", "java.lang.IllegalStateException",
                        "message", "Reply mode IMMEDIATE was not permitted by the final reply channel."
                )
        )));
        Files.writeString(runDir.resolve("signals.ndjson"), String.join("\n",
                "{\"signalId\":\"signal-1\",\"timestamp\":\"2026-07-11T09:10:00Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"run.started\",\"inputEnvelopeSummary\":{\"actor\":\"developer\"}}",
                "{\"signalId\":\"signal-2\",\"timestamp\":\"2026-07-11T09:10:01Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"step.started\",\"step\":\"build-reply\",\"attemptNumber\":1}",
                "{\"signalId\":\"signal-3\",\"timestamp\":\"2026-07-11T09:10:02Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"context.patched\",\"step\":\"build-reply\",\"attemptNumber\":1,\"patchSummary\":{\"count\":2,\"ops\":[\"set\",\"set\"]},\"changedPaths\":[\"reply.mode\",\"reply.payload.message\"]}",
                "{\"signalId\":\"signal-4\",\"timestamp\":\"2026-07-11T09:10:03Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"step.finished\",\"step\":\"build-reply\",\"attemptNumber\":1,\"outcome\":\"ok\",\"durationMs\":5}",
                "{\"signalId\":\"signal-5\",\"timestamp\":\"2026-07-11T09:10:04Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"metric.emitted\",\"step\":\"build-reply\",\"attemptNumber\":1,\"name\":\"railix.metric.failed\",\"value\":13,\"unit\":\"items\",\"labels\":{\"result\":\"failed\"}}",
                "{\"signalId\":\"signal-6\",\"timestamp\":\"2026-07-11T09:10:04Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"audit.emitted\",\"step\":\"build-reply\",\"attemptNumber\":1,\"eventName\":\"creator.failed\",\"data\":{\"kind\":\"demo\",\"status\":\"failed\"}}",
                "{\"signalId\":\"signal-7\",\"timestamp\":\"2026-07-11T09:10:06Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"run.finished\",\"outcome\":\"failed\",\"durationMs\":9}",
                ""
        ));
        Files.setLastModifiedTime(runDir.resolve("summary.json"), FileTime.from(updatedAt));
        Files.setLastModifiedTime(runDir.resolve("signals.ndjson"), FileTime.from(updatedAt));
    }

    private static void seedRunArtifacts(
            final Path runDir,
            final String appId,
            final String flowId,
            final String runId,
            final String payloadMessage,
            final String metricName,
            final int metricValue,
            final String auditEventName,
            final Instant updatedAt
    ) throws Exception {
        Files.writeString(runDir.resolve("summary.json"), KernelContractCodec.toStableJson(Map.of(
                "outcome", "ok",
                "executedSteps", 2,
                "replyMode", "immediate"
        )));
        Files.writeString(runDir.resolve("signals.ndjson"), String.join("\n",
                "{\"signalId\":\"signal-1\",\"timestamp\":\"2026-07-11T09:10:00Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"run.started\",\"inputEnvelopeSummary\":{\"actor\":\"developer\"}}",
                "{\"signalId\":\"signal-2\",\"timestamp\":\"2026-07-11T09:10:01Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"step.started\",\"step\":\"build-reply\",\"attemptNumber\":1}",
                "{\"signalId\":\"signal-3\",\"timestamp\":\"2026-07-11T09:10:02Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"context.patched\",\"step\":\"build-reply\",\"attemptNumber\":1,\"patchSummary\":{\"count\":2,\"ops\":[\"set\",\"set\"]},\"changedPaths\":[\"reply.mode\",\"reply.payload.message\"]}",
                "{\"signalId\":\"signal-4\",\"timestamp\":\"2026-07-11T09:10:03Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"step.finished\",\"step\":\"build-reply\",\"attemptNumber\":1,\"outcome\":\"ok\",\"durationMs\":5}",
                "{\"signalId\":\"signal-5\",\"timestamp\":\"2026-07-11T09:10:04Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"metric.emitted\",\"step\":\"build-reply\",\"attemptNumber\":1,\"name\":\"" + metricName + "\",\"value\":" + metricValue + ",\"unit\":\"items\",\"labels\":{\"result\":\"ok\"}}",
                "{\"signalId\":\"signal-6\",\"timestamp\":\"2026-07-11T09:10:04Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"audit.emitted\",\"step\":\"build-reply\",\"attemptNumber\":1,\"eventName\":\"" + auditEventName + "\",\"data\":{\"kind\":\"demo\",\"status\":\"ok\"}}",
                "{\"signalId\":\"signal-7\",\"timestamp\":\"2026-07-11T09:10:05Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"reply.produced\",\"mode\":\"immediate\",\"status\":200,\"metadata\":{\"source\":\"" + payloadMessage + "\"}}",
                "{\"signalId\":\"signal-8\",\"timestamp\":\"2026-07-11T09:10:06Z\",\"appId\":\"" + appId + "\",\"flowId\":\"" + flowId + "\",\"runId\":\"" + runId + "\",\"type\":\"run.finished\",\"outcome\":\"ok\",\"durationMs\":9}",
                ""
        ));
        Files.setLastModifiedTime(runDir.resolve("summary.json"), FileTime.from(updatedAt));
        Files.setLastModifiedTime(runDir.resolve("signals.ndjson"), FileTime.from(updatedAt));
    }

    private static void writeHttpCapture(final Path runsRoot, final String runId, final String httpCaptureJson) throws Exception {
        final Path runDir = runsRoot.resolve("demo-app").resolve("demo-flow").resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("http-capture.json"), httpCaptureJson);
    }

    private static void seedInstanceRegistryEntry(
            final Path registryRoot,
            final String instanceId,
            final Instant heartbeatAt,
            final int ttlSeconds,
            final Path repoRoot,
            final Path runsRoot,
            final String loopbackControlUrl
    ) throws Exception {
        seedInstanceRegistryEntry(
                registryRoot,
                instanceId,
                heartbeatAt,
                ttlSeconds,
                repoRoot,
                runsRoot,
                loopbackControlUrl,
                Map.of()
        );
    }

    private static void seedInstanceRegistryEntry(
            final Path registryRoot,
            final String instanceId,
            final Instant heartbeatAt,
            final int ttlSeconds,
            final Path repoRoot,
            final Path runsRoot,
            final String loopbackControlUrl,
            final Map<String, Object> runtimeIdentity
    ) throws Exception {
        Files.createDirectories(registryRoot);
        final LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("schemaVersion", 1);
        entry.put("instanceId", instanceId);
        entry.put("heartbeatAt", heartbeatAt.toString());
        entry.put("expiresAt", heartbeatAt.plusSeconds(ttlSeconds).toString());
        entry.put("ttlSeconds", ttlSeconds);
        entry.put("creatorState", "creator-owned-live-run-sessions");
        entry.put("repoRoot", repoRoot.toAbsolutePath().normalize().toString());
        entry.put("runsRoot", runsRoot.toAbsolutePath().normalize().toString());
        entry.put("loopbackControlUrl", loopbackControlUrl);
        entry.put("runtimeIdentity", runtimeIdentity);
        entry.put("remoteExecutionBoundary", Map.of(
                "status", "same-instance-inline-and-deferred",
                "acceptedExecutionScope", "same-instance-inline-or-deferred"
        ));
        entry.put("trust", Map.of(
                "mode", "loopback-only"
        ));
        Files.writeString(
                registryRoot.resolve(instanceId + ".json"),
                KernelContractCodec.toStableJson(entry)
        );
    }

    private static Map<String, Object> discoveredPeerRuntimeIdentity(
            final String capabilityDigest,
            final List<String> supportedUses,
            final List<String> supportedResourceRefPatterns,
            final boolean reportsCompleteUseCatalog
    ) {
        return Map.of(
                "capabilityDigest", capabilityDigest,
                "supportedUses", supportedUses,
                "supportedResourceRefPatterns", supportedResourceRefPatterns,
                "reportsCompleteUseCatalog", reportsCompleteUseCatalog
        );
    }

    private static Path writeLaunchPlan(final Path path) throws Exception {
        Files.createDirectories(path.getParent());
        final AppPlan plan = new AppPlan(
                "creator-launch-app",
                "manual-flow",
                "build-reply",
                List.of(new AppPlan.StepInvocation(
                        "build-reply",
                        "railix.std.data.DataTransform",
                        new RailixValue.ObjectValue(Map.of(
                                "mappings", new RailixValue.ListValue(List.of(
                                        mapping("reply.mode", new RailixValue.StringValue("immediate")),
                                        mapping("reply.payload.message", new RailixValue.StringValue("Launched from Creator"))
                                ))
                        )),
                        Map.of()
                ))
        );
        Files.writeString(path, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(plan)));
        return path;
    }

    private static Path writeLaunchEnvelope(final Path path) throws Exception {
        Files.createDirectories(path.getParent());
        final Envelope envelope = new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("Creator"))),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
        Files.writeString(path, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(envelope)));
        return path;
    }

    private static RailixValue.ObjectValue mapping(final String target, final RailixValue value) {
        final LinkedHashMap<String, RailixValue> expression = new LinkedHashMap<>();
        expression.put("const", value);
        return new RailixValue.ObjectValue(Map.of(
                "target", new RailixValue.StringValue(target),
                "expression", new RailixValue.ObjectValue(expression)
        ));
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("tools").resolve("railix.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not resolve repo root from " + Path.of("").toAbsolutePath().normalize());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(final Map<String, Object> source, final String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(final Map<String, Object> source, final String key) {
        return (List<Map<String, Object>>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(final Map<String, Object> source, final String key) {
        return (List<String>) source.get(key);
    }

    private static int numberValue(final Map<String, Object> source, final String key) {
        return ((Number) source.get(key)).intValue();
    }

    private static String stringValue(final Map<String, Object> source, final String key) {
        return (String) source.get(key);
    }

    private static String runProcess(final List<String> command) throws Exception {
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        assertThat(process.waitFor()).isEqualTo(0);
        return output;
    }

    private static HttpResponse<String> remoteExecutionRequest(
            final HttpClient client,
            final URI baseUri,
            final Map<String, Object> request
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/remote-execution/requests"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(request)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> remoteExecutionPreflight(
            final HttpClient client,
            final URI baseUri,
            final Map<String, Object> request
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/remote-execution/preflight"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(KernelContractCodec.toStableJson(request)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static HttpResponse<String> remoteExecutionRequestReport(
            final HttpClient client,
            final URI baseUri,
            final int limit
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/remote-execution/requests?limit=" + limit))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static Map<String, Object> remoteExecutionRequestReportModel(
            final HttpClient client,
            final URI baseUri,
            final int limit
    ) throws Exception {
        final HttpResponse<String> response = remoteExecutionRequestReport(client, baseUri, limit);
        assertThat(response.statusCode()).isEqualTo(200);
        return KernelContractCodec.parseStableJsonObject(response.body());
    }

    private static Map<String, Object> awaitRemoteExecutionRequest(
            final HttpClient client,
            final URI baseUri,
            final String executionId,
            final java.util.function.Predicate<Map<String, Object>> predicate
    ) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        Map<String, Object> latest = Map.of();
        while (Instant.now().isBefore(deadline)) {
            latest = remoteExecutionRequestReportModel(client, baseUri, 10);
            final Map<String, Object> row = findRemoteExecutionRequest(latest, executionId);
            if (!row.isEmpty() && predicate.test(row)) {
                return row;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for remote execution request state: " + latest);
    }

    private static Map<String, Object> findRemoteExecutionRequest(final Map<String, Object> report, final String executionId) {
        return listOfMaps(report, "requests").stream()
                .filter(row -> executionId.equals(row.get("executionId")))
                .findFirst()
                .orElse(Map.of());
    }

    private static Map<String, Object> validRemoteExecutionRequest(
            final String currentInstanceId,
            final String capabilityDigest
    ) {
        final LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("requestContractVersion", 1);
        request.put("requestId", "remote-request-1");
        request.put("requesterInstanceId", currentInstanceId);
        request.put("targetInstanceId", currentInstanceId);
        request.put("stepUse", "railix.std.store.StoreRead");
        request.put("resourceRefs", List.of("store/orders"));
        request.put("capabilityDigest", capabilityDigest);
        request.put("replyMode", "IMMEDIATE");
        request.put("permissions", grantedRemoteExecutionPermissions(Map.of()));
        request.put("stepConfig", KernelContractCodec.toUiModel(new RailixValue.ObjectValue(Map.of())));
        request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                "remote.dev",
                "remote",
                new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue("order-1"))),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        )));
        request.put("timeoutMillis", 0);
        return request;
    }

    private static Map<String, Object> deferredCapturePayloadRemoteExecutionRequest(
            final String currentInstanceId,
            final String capabilityDigest,
            final String requestId,
            final String orderId
    ) {
        final Map<String, Object> request = validRemoteExecutionRequest(currentInstanceId, capabilityDigest);
        request.put("requestId", requestId);
        request.put("stepUse", "std.data.capture-payload");
        request.put("resourceRefs", List.of());
        request.put("replyMode", "DEFERRED");
        request.put("inputEnvelope", KernelContractCodec.toUiModel(new Envelope(
                "remote.dev",
                "remote",
                new RailixValue.ObjectValue(Map.of("orderId", new RailixValue.StringValue(orderId))),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        )));
        return request;
    }

    private static Map<String, Object> grantedRemoteExecutionPermissions(final Map<String, List<String>> granted) {
        return KernelContractCodec.toUiModel(new PermissionSet(Map.of(), granted, List.of()));
    }

    private static void writeRemoteSettings(final Path settingsFile, final SettingsTree settingsTree) throws Exception {
        Files.writeString(settingsFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(settingsTree)));
    }

    private static SettingsTree appModeSettingsTree(final String mode) {
        return new SettingsTree(
                "remote-app-mode",
                Map.of(
                        RailixPath.parse("settings.app.mode"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.app.mode"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue(mode)),
                                true,
                                false,
                                false,
                                "creator-remote-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }

    private static SettingsTree storeRootSettingsTree(final Path storeRoot) {
        return new SettingsTree(
                "remote-store-root",
                Map.of(
                        RailixPath.parse("settings.store.rootDir"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.store.rootDir"),
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue(storeRoot.toString())),
                                true,
                                false,
                                false,
                                "creator-remote-test",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
    }

    private static void writeRemoteStoreEntry(final Path storeRoot, final String key, final RailixValue value) throws Exception {
        final Path entryFile = storeRoot.resolve(key).resolve("entry.json");
        Files.createDirectories(entryFile.getParent());
        Files.writeString(entryFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(value)));
    }

    private static FailedProcess runProcessExpectFailure(final List<String> command) throws Exception {
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
        final int exitCode = process.waitFor();
        assertThat(exitCode).isNotZero();
        return new FailedProcess(exitCode, output);
    }

    private static RunningPackagedCreator startPackagedCreator(final List<String> command) throws Exception {
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final StringBuffer output = new StringBuffer();
        final CompletableFuture<URI> baseUri = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() > 0) {
                            output.append(System.lineSeparator());
                        }
                        output.append(line);
                    }
                    if (line.startsWith("creatorUrl=") && !baseUri.isDone()) {
                        baseUri.complete(URI.create(line.substring("creatorUrl=".length())));
                    }
                }
                if (!baseUri.isDone()) {
                    baseUri.completeExceptionally(new IllegalStateException(
                            "Packaged Creator launcher exited before reporting creatorUrl. Output: " + output
                    ));
                }
            } catch (final Exception exception) {
                if (!baseUri.isDone()) {
                    baseUri.completeExceptionally(exception);
                }
            }
        });
        return new RunningPackagedCreator(process, baseUri.get(20, TimeUnit.SECONDS), output);
    }

    private static Map<String, Object> awaitControlSessions(
            final HttpClient client,
            final URI baseUri,
            final String sessionId,
            final java.util.function.Predicate<Map<String, Object>> predicate
    ) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        Map<String, Object> latest = Map.of();
        while (Instant.now().isBefore(deadline)) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/control/sessions")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(200);
            latest = KernelContractCodec.parseStableJsonObject(response.body());
            final Map<String, Object> session = findSession(latest, sessionId);
            if (predicate.test(session)) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Creator control session state: " + latest);
    }

    private static Map<String, Object> awaitWorkspaceSession(
            final HttpClient client,
            final URI baseUri,
            final String sessionId,
            final java.util.function.Predicate<Map<String, Object>> predicate
    ) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        Map<String, Object> latest = Map.of();
        while (Instant.now().isBefore(deadline)) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(200);
            latest = KernelContractCodec.parseStableJsonObject(response.body());
            final Map<String, Object> session = findSession(mapValue(latest, "controlSessions"), sessionId);
            if (predicate.test(session)) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Creator workspace session state: " + latest);
    }

    private static Map<String, Object> awaitWorkspaceInstances(
            final HttpClient client,
            final URI baseUri,
            final int activeCount,
            final int staleCount
    ) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        Map<String, Object> latest = Map.of();
        while (Instant.now().isBefore(deadline)) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(200);
            latest = KernelContractCodec.parseStableJsonObject(response.body());
            final Map<String, Object> instances = mapValue(latest, "instances");
            if (numberValue(instances, "activeCount") == activeCount
                    && numberValue(instances, "staleCount") == staleCount) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Creator instance registry state: " + latest);
    }

    private static Map<String, Object> awaitWorkspaceInstanceLoad(
            final HttpClient client,
            final URI baseUri,
            final int activeControlSessions
    ) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
        Map<String, Object> latest = Map.of();
        while (Instant.now().isBefore(deadline)) {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/api/workspace")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode()).isEqualTo(200);
            latest = KernelContractCodec.parseStableJsonObject(response.body());
            final Map<String, Object> load = mapValue(currentInstance(mapValue(latest, "instances")), "load");
            if (numberValue(load, "activeControlSessions") == activeControlSessions) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for Creator instance load state: " + latest);
    }

    private static Map<String, Object> findSession(final Map<String, Object> controlSessions, final String sessionId) {
        return listOfMaps(controlSessions, "sessions").stream()
                .filter(session -> sessionId.equals(session.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found: " + sessionId + " in " + controlSessions));
    }

    private static Map<String, Object> currentInstance(final Map<String, Object> instances) {
        return listOfMaps(instances, "entries").stream()
                .filter(instance -> Boolean.TRUE.equals(instance.get("currentInstance")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Current instance not found in " + instances));
    }

    private static Map<String, Object> findTimelineEvent(final Map<String, Object> source, final String type) {
        return listOfMaps(source, "timeline").stream()
                .filter(event -> type.equals(event.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Timeline event not found: " + type + " in " + source));
    }

    private static Map<String, Object> findGraphNode(final Map<String, Object> source, final String id) {
        return listOfMaps(mapValue(source, "graphActivity"), "nodes").stream()
                .filter(node -> id.equals(node.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Graph node not found: " + id + " in " + source));
    }

    private record FailedProcess(int exitCode, String output) {}

    private record RunningPackagedCreator(Process process, URI baseUri, StringBuffer output) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
