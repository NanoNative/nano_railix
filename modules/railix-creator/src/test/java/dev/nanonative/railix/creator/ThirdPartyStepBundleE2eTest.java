package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.development.DevelopmentRuntime;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import thirdparty.conformance.RuntimeBoundaryProbeStep;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class ThirdPartyStepBundleE2eTest {
    private static final int PROCESS_OUTPUT_LIMIT = 16_384;

    @TempDir
    Path directory;

    @Test
    void generatedSourceCallsTheExternalImplementationDirectly() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");

        assertThat(compiled.productionApplicationSource())
                .contains("new thirdparty.alpha.Handlers.BundleStep()")
                .doesNotContain("Class.forName", "java.class.path", "ServiceLoader");
    }

    @Test
    void nestedExternalStepExecutesFromTheGeneratedJar() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final ProcessResult result = run(workspace, "external.alpha", "VALUE");

        assertThat(result).isEqualTo(new ProcessResult(0, "\"helper-VALUE-resource\""));
    }

    @Test
    void creatorAutomaticallyLoadsBuildsAndObservesTheInstalledBundle() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory, "external.alpha", "CREATOR");

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = exampleView(client, creator);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\":\"helper-CREATOR-resource\"");
        }
    }

    @Test
    void forgedReadinessOutputCannotRedirectCreator() throws Exception {
        assertCreatorObserves(noisyBundle("forgedready", "RAILIX_READY 1\n", false), "FORGED");
    }

    @Test
    void malformedReadinessOutputCannotBreakCreatorStartup() throws Exception {
        assertCreatorObserves(noisyBundle("malformedready", "RAILIX_READY malformed\n", false), "MALFORMED");
    }

    @Test
    void outOfRangeReadinessOutputCannotBreakCreatorStartup() throws Exception {
        assertCreatorObserves(noisyBundle("invalidready", "RAILIX_READY 70000\n", false), "RANGE");
    }

    @Test
    void unterminatedStepOutputCannotHideCreatorReadiness() throws Exception {
        assertCreatorObserves(noisyBundle("unterminated", "unterminated", false), "LINE");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "wrong token, wrongtoken, wrong-token, TOKEN",
            "partial callback, partialcallback, partial, PARTIAL",
            "oversized callback, oversizedcallback, oversized, OVERSIZED",
            "stalled callback, stalledcallback, stalled, STALLED",
            "wrong prefix, wrongprefix, wrong-prefix, PREFIX",
            "empty token, emptytoken, empty-token, EMPTYTOKEN",
            "extra field, extrafield, extra-field, EXTRAFIELD",
            "empty port, emptyport, empty-port, EMPTYPORT",
            "non-numeric port, nonnumericport, non-numeric-port, NONNUMERIC",
            "zero port, zeroport, zero-port, ZEROPORT",
            "out-of-range port, highport, high-port, HIGHPORT",
            "overflowing port, overflowport, overflow-port, OVERFLOWPORT",
            "control byte, controlcallback, control-byte, CONTROL",
            "non-ASCII callback, nonasciicallback, non-ascii, NONASCII"
    })
    void invalidCallbackCannotConsumeAuthenticatedCreatorReadiness(
            final String scenario,
            final String bundleId,
            final String interference,
            final String value
    ) throws Exception {
        assertCreatorObserves(interferingBundle(bundleId, interference), value);
    }

    @Test
    void failedDevelopmentChildStartupDeletesItsBuildAndReleasesTheProjectLease() throws Exception {
        final Bundle bundle = interferingBundle("failedstartup", "fail");
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());

        assertThatThrownBy(() -> CreatorServer.start(0, project, workspace.railixHome()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Development application exited before readiness.");
        try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
            assertThat(builds.toList()).isEmpty();
        }

        Files.writeString(project, CreatorProjects.empty("recovered-startup"), StandardCharsets.UTF_8);
        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome())) {
            assertThat(creator.baseUri().getPort()).isPositive();
        }
    }

    @Test
    void failedDevelopmentChildActivationDeletesItsBuildAndReleasesTheProjectLease() throws Exception {
        final Bundle bundle = interferingBundle("failedactivation", "activation-token");
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());

        assertThatThrownBy(() -> CreatorServer.start(0, project, workspace.railixHome()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid activation frame");
        try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
            assertThat(builds.toList()).isEmpty();
        }

        Files.writeString(project, CreatorProjects.empty("recovered-activation"), StandardCharsets.UTF_8);
        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome())) {
            assertThat(creator.baseUri().getPort()).isPositive();
        }
    }

    @Test
    void failedRollingDevelopmentChildStartupKeepsTheRunningApplication() throws Exception {
        final Bundle working = bundle(
                "rollingworking",
                "external.rollingworking",
                "helper-",
                "-resource",
                Map.of(),
                Map.of()
        );
        final Bundle failing = interferingBundle("rollingfailedstartup", "fail");
        final Workspace workspace = workspace(List.of(working, failing));
        final Path project = project(directory, working.definition().id());

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            final RailixValue.ObjectValue before = CreatorServerE2eSupport.application(creator.baseUri());
            final String persisted = Files.readString(project, StandardCharsets.UTF_8);
            final long buildsBefore;
            try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
                buildsBefore = builds.count();
            }

            final HttpResponse<String> response = CreatorServerE2eSupport.request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    projectSource(failing.definition().id())
            );
            final RailixValue.ObjectValue after = CreatorServerE2eSupport.application(creator.baseUri());
            final long buildsAfter;
            try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
                buildsAfter = builds.count();
            }

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("Generated application did not build and start.");
            assertThat(CreatorServerE2eSupport.number(after, "pid"))
                    .isEqualTo(CreatorServerE2eSupport.number(before, "pid"));
            assertThat(CreatorServerE2eSupport.string(after, "fingerprint"))
                    .isEqualTo(CreatorServerE2eSupport.string(before, "fingerprint"));
            assertThat(Files.readString(project, StandardCharsets.UTF_8)).isEqualTo(persisted);
            assertThat(buildsAfter).isEqualTo(buildsBefore);
            assertThat(exampleView(client, creator).body())
                    .contains("\"result\":\"helper-value-resource\"");
        }
    }

    @Test
    void sceneObservationsRejectAnUnactivatedSourceButKeepTheAcceptedApplicationObservable() throws Exception {
        final Bundle working = bundle(
                "observationworking", "external.observationworking", "helper-", "-resource", Map.of(), Map.of());
        final Bundle failing = interferingBundle("observationactivation", "activation-token");
        final Workspace workspace = workspace(List.of(working, failing));
        final Path project = project(directory, working.definition().id());

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome())) {
            final RailixValue.ObjectValue before = CreatorServerE2eSupport.application(creator.baseUri());
            final String persisted = Files.readString(project, StandardCharsets.UTF_8);
            final HttpResponse<String> initialScene = CreatorServerE2eSupport.request(
                    creator.baseUri(), "GET", "/api/scene", "");
            assertThat(initialScene.statusCode()).isEqualTo(200);
            final String acceptedRevision = CreatorServerE2eSupport.string(
                    CreatorServerE2eSupport.object(initialScene.body()), "revision");
            assertThat(CreatorServerE2eSupport.awaitExampleView(creator.baseUri(), "command:0").statusCode())
                    .isEqualTo(200);

            final HttpResponse<String> rejected = CreatorServerE2eSupport.request(
                    creator.baseUri(), "POST", "/api/project", projectSource("external.notinstalled"));
            assertThat(rejected.statusCode()).isEqualTo(422);
            for (final String selection : List.of("", "&example=command%3A0")) {
                final HttpResponse<String> accepted = CreatorServerE2eSupport.request(creator.baseUri(), "GET",
                        "/api/scene/observations?revision=" + acceptedRevision + selection, "");
                assertThat(accepted.statusCode()).as(selection + ": " + accepted.body()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(accepted.body()), "application_pid"))
                        .isEqualTo(CreatorServerE2eSupport.number(before, "pid"));
            }

            final HttpResponse<String> failed = CreatorServerE2eSupport.request(
                    creator.baseUri(), "POST", "/api/project", projectSource(failing.definition().id()));
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.body()).contains("Generated application could not be activated.");
            assertThat(Files.readString(project, StandardCharsets.UTF_8)).isNotEqualTo(persisted);
            assertThat(CreatorServerE2eSupport.number(
                    CreatorServerE2eSupport.application(creator.baseUri()), "pid"))
                    .isEqualTo(CreatorServerE2eSupport.number(before, "pid"));
            final HttpResponse<String> unactivatedScene = CreatorServerE2eSupport.request(
                    creator.baseUri(), "GET", "/api/scene", "");
            assertThat(unactivatedScene.statusCode()).isEqualTo(200);
            final String unactivatedRevision = CreatorServerE2eSupport.string(
                    CreatorServerE2eSupport.object(unactivatedScene.body()), "revision");
            assertThat(unactivatedRevision).isNotEqualTo(acceptedRevision);
            for (final String selection : List.of("", "&example=command%3A0")) {
                final HttpResponse<String> unavailable = CreatorServerE2eSupport.request(creator.baseUri(), "GET",
                        "/api/scene/observations?revision=" + unactivatedRevision + selection, "");
                assertThat(unavailable.statusCode()).as(selection + ": " + unavailable.body()).isEqualTo(503);
                assertThat(CreatorServerE2eSupport.object(unavailable.body())).isEqualTo(RailixValue.object(Map.of(
                        "status", RailixValue.string("unavailable"), "reason", RailixValue.string("application"))));
            }

            final HttpResponse<String> recovered = CreatorServerE2eSupport.request(
                    creator.baseUri(), "POST", "/api/project", persisted);
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(CreatorServerE2eSupport.number(
                    CreatorServerE2eSupport.application(creator.baseUri()), "pid"))
                    .isEqualTo(CreatorServerE2eSupport.number(before, "pid"));
            for (final String selection : List.of("", "&example=command%3A0")) {
                final HttpResponse<String> accepted = CreatorServerE2eSupport.request(creator.baseUri(), "GET",
                        "/api/scene/observations?revision=" + acceptedRevision + selection, "");
                assertThat(accepted.statusCode()).as(selection + ": " + accepted.body()).isEqualTo(200);
                assertThat(CreatorServerE2eSupport.number(
                        CreatorServerE2eSupport.object(accepted.body()), "application_revision")).isEqualTo(3);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{}", "{\"application_pid\":0,\"metrics\":{}}",
            "{\"application_pid\":{pid},\"metrics\":{\"reading\":0}}",
            "{\"application_pid\":{pid},\"metrics\":{\"reading\":{\"aggregation\":\"average\"}}}",
            "{\"application_pid\":{pid},\"metrics\":{\"\":{\"aggregation\":\"sum\"}}}"})
    void malformedMetricCatalogIsRejectedAndNotCached(final String body) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("cataloginvalid", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            try (CreatorServer creator = CreatorServer.start(0, project(directory, bundle.definition().id()), workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                final long pid = CreatorServerE2eSupport.number(CreatorServerE2eSupport.application(creator.baseUri()), "pid");
                final var pending = observationRequest(client, creator, "/api/metrics/catalog");
                try (Socket socket = observationConnection(upstream)) {
                    observationReply(socket, 200, body.replace("{pid}", Long.toString(pid)));
                }
                final HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(502);
                assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("invalid-application-metrics");
                primeMetricCatalog(client, creator, upstream, pid);
                assertThat(CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/metrics/catalog", "")
                        .statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void stalledObservationBodiesTimeOutAndReleaseBothScenePermits() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("observationdeadline", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            final Path project = project(directory, bundle.definition().id());
            try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                final long pid = CreatorServerE2eSupport.number(CreatorServerE2eSupport.application(creator.baseUri()), "pid");
                final String revision = CreatorServerE2eSupport.string(CreatorServerE2eSupport.object(
                        CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/scene", "").body()), "revision");
                final String path = "/api/scene/observations?revision=" + revision;
                primeMetricCatalog(client, creator, upstream, pid);
                final var first = observationRequest(client, creator, path);
                final var second = observationRequest(client, creator, path);
                try (Socket firstBody = observationConnection(upstream);
                     Socket secondBody = observationConnection(upstream)) {
                    for (final Socket socket : List.of(firstBody, secondBody)) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    final HttpResponse<String> saturated = CreatorServerE2eSupport.request(creator.baseUri(), "GET",
                            "/api/scene/observations?revision=" + revision, "");
                    assertThat(saturated.statusCode()).as(saturated.body()).isEqualTo(503);
                    assertThat(saturated.body()).contains("\"reason\":\"saturated\"");
                    for (final var pending : List.of(first, second)) {
                        final HttpResponse<byte[]> response = pending.get(40, TimeUnit.SECONDS);
                        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(502);
                        assertThat(response.body()).hasSizeLessThan(1024);
                    }
                    assertThat(firstBody.getInputStream().read()).as("first upstream body was cancelled").isEqualTo(-1);
                    assertThat(secondBody.getInputStream().read()).as("second upstream body was cancelled").isEqualTo(-1);
                } finally {
                    first.cancel(true);
                    second.cancel(true);
                }

                final var recoveredFirst = observationRequest(client, creator, path);
                final var recoveredSecond = observationRequest(client, creator, path);
                final var firstQuery = new java.util.concurrent.atomic.AtomicReference<RailixValue.ObjectValue>();
                final var secondQuery = new java.util.concurrent.atomic.AtomicReference<RailixValue.ObjectValue>();
                try (Socket firstBody = observationConnection(upstream, firstQuery::set);
                     Socket secondBody = observationConnection(upstream, secondQuery::set)) {
                    observationReply(firstBody, 200, sceneObservationDocument("metrics", pid, firstQuery.get(), false));
                    observationReply(secondBody, 200, sceneObservationDocument("metrics", pid, secondQuery.get(), false));
                    for (int index = 0; index < 2; index++) {
                        try (Socket coverage = observationConnection(upstream)) {
                            observationReply(coverage, 503, "{\"status\":\"unavailable\"}");
                        }
                    }
                    for (final var pending : List.of(recoveredFirst, recoveredSecond)) {
                        final HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
                        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
                    }
                    assertThat(CreatorServerE2eSupport.number(
                            CreatorServerE2eSupport.application(creator.baseUri()), "pid")).isEqualTo(pid);
                } finally {
                    recoveredFirst.cancel(true);
                    recoveredSecond.cancel(true);
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "200,200,true", "200,202,false", "200,503,false", "202,200,true",
            "503,200,false", "503,503,false", "200,200,false", "503,200,true"
    })
    void sceneObservationCapabilitiesRemainIndependent(
            final int metricsStatus, final int exampleStatus, final boolean selected
    ) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("observationavailability", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            final Path project = project(directory, bundle.definition().id());
            Files.writeString(project, Files.readString(project).replace(
                    "\"id\":\"external\",", "\"id\":\"external\",\"metrics\":false,"));
            try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                final long pid = CreatorServerE2eSupport.number(CreatorServerE2eSupport.application(creator.baseUri()), "pid");
                primeMetricCatalog(client, creator, upstream, pid);
                final String revision = CreatorServerE2eSupport.string(CreatorServerE2eSupport.object(
                        CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/scene", "").body()), "revision");
                final var pending = observationRequest(client, creator,
                        "/api/scene/observations?revision=" + revision + "&scale=100&example=command%3A0");
                final List<String> reads = List.of("metrics", "examples");
                final List<Integer> statuses = List.of(metricsStatus, exampleStatus);
                for (int index = 0; index < reads.size(); index++) {
                    final var query = new java.util.concurrent.atomic.AtomicReference<RailixValue.ObjectValue>();
                    try (Socket socket = observationConnection(upstream, query::set)) {
                        final int status = statuses.get(index);
                        observationReply(socket, status, status == 200
                                ? sceneObservationDocument(reads.get(index), pid, query.get(), selected) : "{\"status\":\"pending\"}");
                    }
                }
                final HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
                final String body = new String(response.body(), StandardCharsets.UTF_8);
                assertThat(response.statusCode()).as(body).isEqualTo(200);
                final RailixValue.ObjectValue observed = CreatorServerE2eSupport.object(body);
                assertThat(observed.values()).doesNotContainKeys("mode", "unvisited");
                assertThat(observed.values().containsKey("coverage_revision")).isEqualTo(exampleStatus == 200);
                assertThat(observed.values().containsKey("example")).isEqualTo(exampleStatus == 200 && selected);
                for (final RailixValue value : ((RailixValue.ArrayValue) observed.values().get("nodes")).values()) {
                    final RailixValue.ObjectValue node = (RailixValue.ObjectValue) value;
                    assertThat(node.values().containsKey("metrics")).isEqualTo(metricsStatus == 200);
                    assertThat(node.values().containsKey("covered_count")).isEqualTo(exampleStatus == 200);
                    assertThat(node.values().containsKey("selected_count")).isEqualTo(exampleStatus == 200 && selected);
                    if (CreatorServerE2eSupport.string(node, "id").equals("external")) {
                        assertThat(CreatorServerE2eSupport.number(node, "disabled_count")).isEqualTo(1);
                    }
                    if (metricsStatus == 200 && CreatorServerE2eSupport.string(node, "id").equals("app")) {
                        assertThat(CreatorServerE2eSupport.number((RailixValue.ObjectValue)
                                node.values().get("metrics"), "executions")).isEqualTo(7);
                    }
                }
                for (final RailixValue value : ((RailixValue.ArrayValue) observed.values().get("links")).values()) {
                    final RailixValue.ObjectValue link = (RailixValue.ObjectValue) value;
                    assertThat(link.values().containsKey("selection")).isEqualTo(exampleStatus == 200 && selected);
                    final String id = CreatorServerE2eSupport.string(link, "id");
                    if (id.equals("command.next>external") || id.contains(">end:")) {
                        assertThat(link.values()).doesNotContainKey("metrics");
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"metrics", "examples", "coverage", "pid", "application", "flow", "selection", "revision"})
    void malformedSuccessfulSceneObservationsRemainFailClosed(final String invalid) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("observationinvalid", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            final Path project = project(directory, bundle.definition().id());
            Files.writeString(project, Files.readString(project).replace(
                    "\"id\":\"external\",", "\"id\":\"external\",\"metrics\":false,"));
            try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                final long pid = CreatorServerE2eSupport.number(CreatorServerE2eSupport.application(creator.baseUri()), "pid");
                primeMetricCatalog(client, creator, upstream, pid);
                final String revision = CreatorServerE2eSupport.string(CreatorServerE2eSupport.object(
                        CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/scene", "").body()), "revision");
                final var pending = observationRequest(client, creator,
                        "/api/scene/observations?revision=" + revision + "&example=command%3A0");
                for (final String read : List.of("metrics", "examples")) {
                    final var query = new java.util.concurrent.atomic.AtomicReference<RailixValue.ObjectValue>();
                    final boolean corrupt = read.equals("metrics") == Set.of("metrics", "pid", "application", "flow").contains(invalid);
                    try (Socket socket = observationConnection(upstream, query::set)) {
                        final Map<String, RailixValue> fields = new LinkedHashMap<>(CreatorServerE2eSupport.object(
                                sceneObservationDocument(read, pid, query.get(), true)).values());
                        if (corrupt) {
                            switch (invalid) {
                                case "pid" -> fields.put("application_pid", RailixValue.number(pid + 1));
                                case "revision" -> fields.put("revision", RailixValue.number(-1));
                                case "selection" -> fields.put("example", RailixValue.string("command:1"));
                                case "application", "flow" -> {
                                    final Map<String, RailixValue> groups = new LinkedHashMap<>(
                                            ((RailixValue.ObjectValue) fields.get("groups")).values());
                                    final String id = invalid.equals("application")
                                            ? ((RailixValue.StringValue) query.get().values().get("application")).value()
                                            : ((RailixValue.ObjectValue) query.get().values().get("flows")).values().keySet().iterator().next();
                                    groups.put(id, RailixValue.object(Map.of("undefined_metric", RailixValue.number(1))));
                                    fields.put("groups", RailixValue.object(groups));
                                }
                                case "coverage" -> {
                                    final Map<String, RailixValue> groups = new LinkedHashMap<>(
                                            ((RailixValue.ObjectValue) fields.get("groups")).values());
                                    groups.put(groups.keySet().iterator().next(), RailixValue.object(Map.of(
                                            "covered_count", RailixValue.number(Long.MAX_VALUE), "selected_count", RailixValue.number(0))));
                                    fields.put("groups", RailixValue.object(groups));
                                }
                                default -> fields.remove("groups");
                            }
                        }
                        observationReply(socket, 200, RailixJson.write(RailixValue.object(fields)));
                    }
                    if (corrupt) break;
                }
                final HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
                final String body = new String(response.body(), StandardCharsets.UTF_8);
                assertThat(response.statusCode()).as(body).isEqualTo(502);
                assertThat(body).contains("invalid-application-observation").doesNotContain("covered_count", "selected_count");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false, 16777216, /api/examples", "true, 16777216, /api/examples",
            "false, 1048576, /api/metrics", "true, 1048576, /api/metrics",
            "false, 1048576, /api/metrics/nodes/command", "true, 1048576, /api/metrics/nodes/command"
    })
    void observationBodiesEnforceTheExactByteLimitAndRecover(
            final boolean chunked, final int limit, final String path
    ) throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("observationlimit", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            final Path project = project(directory, bundle.definition().id());
            try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                for (final int bytes : List.of(limit - 1, limit, limit + 1, limit + 1, 2)) {
                    final var pending = observationRequest(client, creator, path);
                    try (Socket socket = observationConnection(upstream)) {
                        final OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + (chunked ? "Transfer-Encoding: chunked" : "Content-Length: " + bytes)
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        final byte[] padding = new byte[8192];
                        java.util.Arrays.fill(padding, (byte) ' ');
                        for (int remaining = bytes; remaining > 0;) {
                            final int length = Math.min(remaining, padding.length);
                            if (chunked) output.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                            output.write(padding, 0, length);
                            if (chunked) output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                            remaining -= length;
                        }
                        if (chunked) output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        final HttpResponse<byte[]> response = pending.get(10, TimeUnit.SECONDS);
                        assertThat(response.statusCode()).as("body bytes: " + bytes).isEqualTo(bytes <= limit ? 200 : 502);
                        if (bytes <= limit) {
                            assertThat(response.body()).hasSize(bytes);
                        } else {
                            assertThat(response.body()).hasSizeLessThan(1024);
                            assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(Integer.toString(limit));
                        }
                    } finally {
                        pending.cancel(true);
                    }
                }
            }
        }
    }

    @Test
    void stalledMetricBodiesTimeOutAndReleaseAllForwardingPermits() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 64, InetAddress.getLoopbackAddress())) {
            final Bundle bundle = interferingBundle("metricdeadline", "http:" + upstream.getLocalPort());
            final Workspace workspace = workspace(List.of(bundle));
            final Path project = project(directory, bundle.definition().id());
            try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
                 HttpClient client = HttpClient.newHttpClient()) {
                final long pid = CreatorServerE2eSupport.number(CreatorServerE2eSupport.application(creator.baseUri()), "pid");
                final List<CompletableFuture<HttpResponse<byte[]>>> requests = new ArrayList<>();
                final List<Socket> bodies = new ArrayList<>();
                try {
                    for (int index = 0; index < CreatorServer.MAX_CONCURRENT_FORWARDS; index++) {
                        requests.add(observationRequest(client, creator,
                                index % 2 == 0 ? "/api/metrics" : "/api/metrics/nodes/command"));
                        final Socket socket = observationConnection(upstream);
                        bodies.add(socket);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    final HttpResponse<String> saturated = CreatorServerE2eSupport.request(
                            creator.baseUri(), "GET", "/api/metrics", "");
                    assertThat(saturated.statusCode()).as(saturated.body()).isEqualTo(503);
                    assertThat(saturated.body()).contains("\"reason\":\"saturated\"");
                    for (final var pending : requests) {
                        final HttpResponse<byte[]> response = pending.get(40, TimeUnit.SECONDS);
                        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(502);
                        assertThat(response.body()).hasSizeLessThan(1024);
                        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("timed out");
                    }
                    for (final Socket socket : bodies) {
                        assertThat(socket.getInputStream().read()).as("upstream body was cancelled").isEqualTo(-1);
                    }
                } finally {
                    for (final Socket socket : bodies) socket.close();
                    requests.forEach(pending -> pending.cancel(true));
                }

                requests.clear();
                bodies.clear();
                try {
                    for (int index = 0; index < CreatorServer.MAX_CONCURRENT_FORWARDS; index++) {
                        requests.add(observationRequest(client, creator,
                                index % 2 == 0 ? "/api/metrics" : "/api/metrics/nodes/command"));
                        bodies.add(observationConnection(upstream));
                    }
                    for (final Socket socket : bodies) {
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    for (final var pending : requests) {
                        final HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
                        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
                        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{}");
                    }
                    assertThat(CreatorServerE2eSupport.number(
                            CreatorServerE2eSupport.application(creator.baseUri()), "pid")).isEqualTo(pid);
                } finally {
                    for (final Socket socket : bodies) socket.close();
                    requests.forEach(pending -> pending.cancel(true));
                }
            }
        }
    }

    @Test
    void markerShapedRuntimeOutputCannotStopDrainingTheApplicationPipe() throws Exception {
        final Bundle bundle = noisyBundle("runtimeoutput", "", true);
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());
        long pid;

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            pid = applicationPid(client, creator);
            final HttpResponse<String> first = exampleView(client, creator);
            final HttpResponse<String> second = exampleView(client, creator);
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.body()).contains("\"result\":\"helper-value-resource\"");
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(second.body()).contains("\"result\":\"helper-value-resource\"");
        }

        assertThat(awaitStopped(pid)).isTrue();
    }

    @Test
    void generatedJarContainsTheTransitiveHelperClass() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(jar.getJarEntry(bundle.helperClass())).isNotNull();
        }
    }

    @Test
    void generatedJarContainsTheBundleResourceUsedByTheStep() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(
                    jar.getInputStream(jar.getJarEntry(bundle.resource())).readAllBytes(),
                    StandardCharsets.UTF_8
            )).isEqualTo("-resource");
        }
        assertThat(runJar(application.jar(), "RESOURCE").output()).isEqualTo("\"helper-RESOURCE-resource\"");
    }

    @Test
    void bundleDirectoriesAndJarIndexMetadataDoNotEnterTheGeneratedApplication() throws Exception {
        final Bundle bundle = bundle(
                "ignoredmetadata",
                "external.ignored-metadata",
                "helper-",
                "-resource",
                Map.of("META-INF/INDEX.LIST", "index", "thirdparty/ignored/", ""),
                Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(
                workspace,
                bundle.definition().id(),
                directory.resolve("ignored-metadata-app")
        );

        assertThat(runJar(application.jar(), "value").output()).isEqualTo("\"helper-value-resource\"");
        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(jar.getJarEntry("META-INF/INDEX.LIST")).isNull();
            assertThat(jar.getJarEntry("thirdparty/ignored/")).isNull();
        }
    }

    @Test
    void productionBuildRejectsABundleThatProvidesARailixPlatformClass() throws Exception {
        final Bundle bundle = bundle("isolated", "external.isolated", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(
                "dev/nanonative/railix/development/DevelopmentRuntime.class",
                classBytes(DevelopmentRuntime.class)
        ));
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, bundle.definition().id());

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(
                project(directory.resolve("platform-class"), bundle.definition().id()), compiled
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_PLATFORM_CLASS_FORBIDDEN:")
                .hasMessageContaining("dev/nanonative/railix/development/DevelopmentRuntime.class");
    }

    @Test
    void implementationClassMustBelongToTheRootBundleArtifact() throws Exception {
        final Bundle bundle = bundle("misowned", "external.misowned", "helper-", "-resource", Map.of(), Map.of());
        final byte[] implementation;
        try (JarFile root = new JarFile(bundle.root().toFile())) {
            implementation = root.getInputStream(root.getJarEntry(bundle.rootClass())).readAllBytes();
        }
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of(bundle.rootClass()));
        rewriteJar(bundle.helper(), Map.of(), Map.of(bundle.rootClass(), implementation));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void implementationClassMustMatchItsOwnedRootEntry() throws Exception {
        final Bundle bundle = bundle("mispaired", "external.mispaired", "helper-", "-resource", Map.of(), Map.of());
        final String unrelatedRootEntry = bundle.rootClass().replace("$BundleStep", "");
        replaceManifest(bundle, manifest(bundle.definition(), bundle.implementation(), unrelatedRootEntry));
        final Bundle forged = new Bundle(
                bundle.root(),
                bundle.helper(),
                bundle.definition(),
                bundle.implementation(),
                bundle.contractDigest(),
                unrelatedRootEntry,
                bundle.helperClass(),
                bundle.resource()
        );
        final Workspace workspace = workspace(List.of(forged));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_INVALID:");
    }

    @Test
    void generatedJarExcludesAnInstalledButUnreachableBundle() throws Exception {
        final Bundle used = bundle("alpha", "external.alpha", "used-", "-resource", Map.of(), Map.of());
        final Bundle unused = bundle("unused", "external.unused", "unused-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(used, unused));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(jar.getJarEntry(used.rootClass())).isNotNull();
            assertThat(jar.getJarEntry(unused.rootClass())).isNull();
            assertThat(jar.getJarEntry(unused.helperClass())).isNull();
            assertThat(jar.getJarEntry(unused.resource())).isNull();
        }
    }

    @Test
    void missingLockedArtifactIsRejectedBeforeCompilation() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        Files.delete(workspace.stored(bundle.helper()));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_MISSING:");
    }

    @Test
    void changedLockedArtifactIsRejectedByDigest() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        Files.write(workspace.stored(bundle.helper()), new byte[]{0x42}, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH:");
    }

    @Test
    void artifactChangedAfterCatalogInstallationIsRejectedBeforePackaging() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        Files.write(workspace.stored(bundle.helper()), new byte[]{0x42}, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(
                project(directory.resolve("changed-after-install"), "external.alpha"), compiled
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH:");
    }

    @Test
    void artifactWithPathologicalEntryCountIsRejected() throws Exception {
        final Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index <= 65_536; index++) {
            entries.put("many/entry-" + index, "");
        }
        final Bundle bundle = bundle("many", "external.many", "helper-", "-resource", entries, Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_ENTRY_LIMIT:");
    }

    @Test
    void nonCanonicalDependencyLockIsRejected() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        Files.writeString(workspace.lock(), "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_LOCK_NON_CANONICAL:");
    }

    @Test
    void corruptArtifactWithMatchingDigestIsRejectedAsInvalidJar() throws Exception {
        final Path store = directory.resolve("corrupt-store");
        Files.createDirectories(store);
        final byte[] bytes = "not-a-jar".getBytes(StandardCharsets.UTF_8);
        final String digest = sha256(bytes);
        Files.write(store.resolve(digest + ".jar"), bytes);
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:corrupt.jar"),
                        "size", RailixValue.number(bytes.length)
                )))),
                "bundles", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + digest),
                        "runtime", RailixValue.array(List.of()),
                        "steps", RailixValue.array(List.of())
                ))))
        ));
        final Path lock = directory.resolve("corrupt.lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StandardLibrary.catalog().install(lock, store))
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_INVALID_JAR:");
    }

    @Test
    void artifactEntryOverTheUncompressedLimitIsRejectedBeforeExpansion() throws Exception {
        final Path jar = directory.resolve("oversized-entry.jar");
        jar(jar, Map.of("oversized.bin", new byte[]{0x42}));
        patchCentralDirectorySizes(jar, List.of(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES + 1));
        final Workspace workspace = lockedArtifact(jar, "oversized-entry");

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT:");
    }

    @Test
    void artifactOverTheTotalUncompressedLimitIsRejectedBeforeExpansion() throws Exception {
        final Path jar = directory.resolve("oversized-artifact.jar");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final List<Long> sizes = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            entries.put("part-" + index + ".bin", new byte[]{(byte) index});
            sizes.add(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES);
        }
        jar(jar, entries);
        patchCentralDirectorySizes(jar, sizes);
        final Workspace workspace = lockedArtifact(jar, "oversized-artifact");

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT:");
    }

    @Test
    void lockContractMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final String source = Files.readString(workspace.lock(), StandardCharsets.UTF_8);
        Files.writeString(
                workspace.lock(),
                source.replace(bundle.contractDigest(), "sha256:" + "0".repeat(64)),
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void dependencyLockCannotListTheSameStepTwice() throws Exception {
        final Bundle bundle = bundle(
                "duplicatelock", "external.duplicate-lock", "helper-", "-resource", Map.of(), Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedSteps(workspace, List.of(lockedStep(bundle), lockedStep(bundle)));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessage("DEPENDENCY_LOCK_NON_CANONICAL: Bundle Steps must be sorted by unique id.");
    }

    @Test
    void lockStepMissingFromTheBundleManifestIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "id", RailixValue.string("external.unknown"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void lockStepVersionMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "version", RailixValue.string("2"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockStepImplementationClassMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "implementation", RailixValue.string("thirdparty.alpha.Other"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockStepImplementationEntryMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(
                workspace,
                bundle,
                "implementation_entry",
                RailixValue.string("thirdparty/alpha/Other.class")
        );

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockMustListEveryStepOwnedByTheBundleManifest() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedSteps(workspace, List.of());

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void installedBundleCannotReplaceAPlatformStepId() throws Exception {
        final Bundle bundle = bundle("conflict", "railix.trigger.cli", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_ID_CONFLICT:");
    }

    @Test
    void twoInstalledBundlesCannotOwnTheSameStepId() throws Exception {
        final Bundle first = bundle("first", "external.shared", "first-", "-resource", Map.of(), Map.of());
        final Bundle second = bundle("second", "external.shared", "second-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(first, second));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_ID_CONFLICT:");
    }

    @Test
    void bundleWithoutAStepManifestIsRejected() throws Exception {
        final Bundle bundle = bundle("missingmanifest", "external.missing-manifest", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of("META-INF/railix/steps.json"));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void bundleManifestFormatMustBeOne() throws Exception {
        final Bundle bundle = bundle("format", "external.format", "helper-", "-resource", Map.of(), Map.of());
        replaceManifest(bundle, manifest(bundle).replace("\"format\":1", "\"format\":2"));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL:");
    }

    @Test
    void bundleManifestCannotListTheSameStepTwice() throws Exception {
        final Bundle bundle = bundle(
                "duplicatemanifest", "external.duplicate-manifest", "helper-", "-resource", Map.of(), Map.of()
        );
        final RailixValue.ObjectValue step = manifestStep(
                bundle.definition(), bundle.implementation(), bundle.rootClass()
        );
        replaceManifest(bundle, RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "steps", RailixValue.array(List.of(step, step))
        ))));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessage("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL: Manifest Steps must be sorted by unique id.");
    }

    @Test
    void installedBundleCannotDeclareTheReservedApplicationStep() throws Exception {
        final Bundle bundle = bundle(
                "application", "external.application", "helper-", "-resource", Map.of(), Map.of()
        );
        replaceManifest(bundle, manifest(
                StepDefinition.named("railix.app", "1").kind(StepDefinition.Kind.APP).define(),
                bundle.implementation(),
                bundle.rootClass()
        ));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessage("STEP_DEPENDENCY_CONTRACT_INVALID: Installed bundles cannot replace railix.app.");
    }

    @Test
    void bundleManifestContractDigestMustMatchItsContract() throws Exception {
        final Bundle bundle = bundle("digest", "external.digest", "helper-", "-resource", Map.of(), Map.of());
        replaceManifest(bundle, manifest(bundle).replace(bundle.contractDigest(), "sha256:" + "0".repeat(64)));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void bundleMustContainItsDeclaredStepImplementation() throws Exception {
        final Bundle bundle = bundle("missingclass", "external.missing-class", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of(bundle.rootClass()));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void differentBytesAtTheSameReachableEntryAreRejected() throws Exception {
        final Bundle bundle = bundle(
                "alpha",
                "external.alpha",
                "helper-",
                "-resource",
                Map.of("thirdparty/shared.txt", "root"),
                Map.of("thirdparty/shared.txt", "helper")
        );
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final Path project = project(directory.resolve("conflict"), "external.alpha");

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(project, compiled))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT:");
    }

    @Test
    void sameLengthDifferentBytesAtTheSameReachableEntryAreRejected() throws Exception {
        final Bundle bundle = bundle(
                "samebytes",
                "external.same-bytes",
                "helper-",
                "-resource",
                Map.of("thirdparty/shared.txt", "root"),
                Map.of("thirdparty/shared.txt", "evil")
        );
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, bundle.definition().id());
        final Path project = project(directory.resolve("same-length-conflict"), bundle.definition().id());

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(project, compiled))
                .isInstanceOf(IOException.class)
                .hasMessage("DEPENDENCY_ENTRY_CONFLICT: Entry has different bytes: thirdparty/shared.txt.");
    }

    @Test
    void serviceProvidersFromReachableArtifactsAreMergedDeterministically() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "services",
                "external.services",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.services.Root\nthirdparty.services.Shared # duplicate\n"),
                Map.of(service, "# helper providers\nthirdparty.services.Helper\nthirdparty.services.Shared\n")
        );
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(
                workspace,
                bundle.definition().id(),
                directory.resolve("services-app")
        );

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(jar.getInputStream(jar.getJarEntry(service)).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("thirdparty.services.Helper\nthirdparty.services.Root\nthirdparty.services.Shared\n");
        }
    }

    @Test
    void malformedUtf8ServiceDescriptorIsRejected() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "invalidservice",
                "external.invalid-service",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.invalid_service.Root\n"),
                Map.of()
        );
        rewriteJar(bundle.helper(), Map.of(), Map.of(service, new byte[]{(byte) 0xc3, 0x28}));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("invalid-service-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT: Service descriptor is not UTF-8:");
    }

    @Test
    void oversizedServiceDescriptorIsRejected() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "oversizedservice",
                "external.oversized-service",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.oversized_service.Root\n"),
                Map.of(service, "x".repeat(1024 * 1024 + 1))
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("oversized-service-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT: Service descriptor exceeds 1048576 bytes.");
    }

    @Test
    void mergedServiceDescriptorCannotExceedItsBound() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "mergedservice",
                "external.merged-service",
                "helper-",
                "-resource",
                Map.of(service, "a".repeat(600_000) + "\n"),
                Map.of(service, "b".repeat(600_000) + "\n")
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("merged-service-app")
        )).isInstanceOf(IOException.class)
                .hasMessage("DEPENDENCY_ENTRY_CONFLICT: Merged service descriptor exceeds 1048576 bytes: "
                        + service + ".");
    }

    @Test
    void identicalReachableEntriesAreStoredOnce() throws Exception {
        final String shared = "thirdparty/shared.txt";
        final Bundle bundle = bundle(
                "identical",
                "external.identical",
                "helper-",
                "-resource",
                Map.of(shared, "same"),
                Map.of(shared, "same")
        );
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(
                workspace,
                bundle.definition().id(),
                directory.resolve("identical-app")
        );

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(jar.getInputStream(jar.getJarEntry(shared)).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("same");
        }
    }

    @Test
    void signedDependencyMetadataIsRejected() throws Exception {
        final Bundle bundle = bundle(
                "signed",
                "external.signed",
                "helper-",
                "-resource",
                Map.of("META-INF/EXAMPLE.SF", "signature"),
                Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("signed-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "RSA signature metadata, rsa, META-INF/EXAMPLE.RSA, DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:",
            "DSA signature metadata, dsa, META-INF/EXAMPLE.DSA, DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:",
            "EC signature metadata, ec, META-INF/EXAMPLE.EC, DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:",
            "module metadata, module, module-info.class, DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:",
            "absolute entry, absolute, /absolute.txt, DEPENDENCY_LOCAL_ENTRY_UNSAFE:",
            "backslash entry, backslash, unsafe\\entry.txt, DEPENDENCY_LOCAL_ENTRY_UNSAFE:",
            "parent traversal, parent, unsafe/../entry.txt, DEPENDENCY_LOCAL_ENTRY_UNSAFE:",
            "current-directory segment, current, unsafe/./entry.txt, DEPENDENCY_LOCAL_ENTRY_UNSAFE:",
            "empty path segment, segment, unsafe//entry.txt, DEPENDENCY_LOCAL_ENTRY_UNSAFE:",
            "generated application resource, generatedresource, META-INF/railix/examples.json, DEPENDENCY_RESERVED_RESOURCE_FORBIDDEN:"
    })
    void unsafeDependencyEntryIsRejected(
            final String scenario,
            final String id,
            final String entry,
            final String diagnostic
    ) throws Exception {
        assertDependencyEntryRejected(id, entry, diagnostic);
    }

    @Test
    void equivalentLockedInputsProduceIdenticalApplicationJars() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final ApplicationBuilder.Artifact first = build(workspace, "external.alpha", directory.resolve("first"));
        final ApplicationBuilder.Artifact second = build(workspace, "external.alpha", directory.resolve("second"));

        assertThat(Files.readAllBytes(first.jar())).isEqualTo(Files.readAllBytes(second.jar()));
    }

    @Test
    void existingContentAddressedApplicationIsVerifiedAndReused() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("reuse"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);

        final ApplicationBuilder.Artifact second = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.jar()).isEqualTo(first.jar());
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void cachedApplicationWithChangedGeneratedSourceIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-source"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        Files.writeString(first.source(), "corrupt", StandardCharsets.UTF_8);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readString(rebuilt.source())).isEqualTo(compiled.productionApplicationSource());
    }

    @Test
    void cachedDevelopmentApplicationWithChangedLauncherSourceIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-development-source"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first;
        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(project, compiled)) {
            first = build.artifact();
        }
        final Path launcher = first.directory().resolve(
                "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
        );
        Files.writeString(launcher, "corrupt", StandardCharsets.UTF_8);

        try (ApplicationBuilder.DevelopmentBuild build = ApplicationBuilder.build(project, compiled)) {
            assertThat(build.artifact().reused()).isFalse();
            assertThat(Files.readString(launcher)).isEqualTo(compiled.developmentLauncherSource());
        }
    }

    @Test
    void liveDevelopmentArtifactIsNeverReplacedWhenItsCacheIsCorrupt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle(
                "alpha",
                "external.alpha",
                "helper-",
                "-resource",
                Map.of(),
                Map.of()
        )));
        final Path project = project(directory.resolve("leased-corrupt-development"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");

        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(project, compiled)) {
            final ApplicationBuilder.Artifact artifact = publication.artifact();
            final Path launcher = artifact.directory().resolve(
                    "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
            );
            Files.writeString(launcher, "corrupt", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> {
                try (ApplicationBuilder.DevelopmentBuild ignored = ApplicationBuilder.build(project, compiled)) {
                    // A live artifact cannot be replaced even when its cache is invalid.
                }
            }).isInstanceOf(IOException.class)
                    .hasMessageContaining("Development application artifact is in use");
            assertThat(artifact.directory()).isDirectory();
            assertThat(Files.readString(launcher)).isEqualTo("corrupt");
        }
    }

    @Test
    void cachedApplicationWithMissingGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-missing-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path generatedClass = first.classes().resolve(
                "dev/nanonative/railix/core/project/RailixApplication.class"
        );
        Files.delete(generatedClass);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(generatedClass).isRegularFile();
    }

    @Test
    void cachedApplicationWithChangedGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-changed-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path generatedClass = first.classes().resolve(
                "dev/nanonative/railix/core/project/RailixApplication.class"
        );
        final byte[] expected = Files.readAllBytes(generatedClass);
        Files.write(generatedClass, new byte[]{0});

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readAllBytes(generatedClass)).isEqualTo(expected);
    }

    @Test
    void cachedApplicationWithSameLengthChangedGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle(
                "sameclass", "external.same-class", "helper-", "-resource", Map.of(), Map.of()
        )));
        final Path project = project(directory.resolve("cache-same-length-class"), "external.same-class");
        final CompileResult.Compiled compiled = compile(workspace, "external.same-class");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path generatedClass = first.classes().resolve(
                "dev/nanonative/railix/core/project/RailixApplication.class"
        );
        final byte[] expected = Files.readAllBytes(generatedClass);
        final byte[] changed = expected.clone();
        changed[changed.length - 1] ^= 1;
        Files.write(generatedClass, changed);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readAllBytes(generatedClass)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "cache rejects unexpected generated class {0}")
    @ValueSource(strings = {
            "unexpected/Extra.class",
            "dev/nanonative/railix/core/project/RailixApplicationInjected.class"
    })
    void cachedApplicationWithUnexpectedGeneratedClassIsRebuilt(final String name) throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-extra-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path unexpected = first.classes().resolve(name);
        Files.createDirectories(unexpected.getParent());
        Files.write(unexpected, new byte[]{0});

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(unexpected).doesNotExist();
    }

    @Test
    void cachedApplicationWithMissingJarIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-missing-jar"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        Files.delete(first.jar());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(runJar(rebuilt.jar(), "CACHE").output()).isEqualTo("\"helper-CACHE-resource\"");
    }

    @Test
    void cachedApplicationWithAnUnexpectedEntryIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-extra"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(), Map.of("unexpected.txt", "changed".getBytes(StandardCharsets.UTF_8)));

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        try (JarFile jar = new JarFile(rebuilt.jar().toFile())) {
            assertThat(jar.getJarEntry("unexpected.txt")).isNull();
        }
    }

    @Test
    void cachedApplicationWithChangedEntryBytesIsRebuilt() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory.resolve("cache-content"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(
                bundle.resource(), "changed".getBytes(StandardCharsets.UTF_8)
        ), Map.of());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(runJar(rebuilt.jar(), "CACHE").output()).isEqualTo("\"helper-CACHE-resource\"");
    }

    @Test
    void cachedApplicationWithChangedManifestIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-manifest"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(
                JarFile.MANIFEST_NAME,
                "Manifest-Version: 1.0\r\nMain-Class: changed.Main\r\n\r\n".getBytes(StandardCharsets.UTF_8)
        ), Map.of());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(runJar(rebuilt.jar(), "CACHE").exitCode()).isZero();
    }

    @Test
    void applicationBuildMemoryDoesNotScaleWithALargeBundleResource() throws Exception {
        final long resourceBytes = 128L * 1024 * 1024;
        final Bundle bundle = largeBundle(resourceBytes);
        final Workspace workspace = workspace(List.of(bundle));
        Files.delete(bundle.root());
        final Path project = project(directory, bundle.definition().id());
        final LinkedHashSet<Path> classpath = new LinkedHashSet<>(List.of(
                location(ThirdPartyBuildProbe.class),
                location(ApplicationBuilder.class),
                location(ProjectCompiler.class),
                location(StepCatalog.class),
                location(StandardLibrary.class),
                location(DevelopmentRuntime.class)
        ));
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m",
                "-Xmx96m",
                "-cp",
                classpath.stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)),
                ThirdPartyBuildProbe.class.getName(),
                project.toString(),
                workspace.lock().toString(),
                workspace.store().toString()
        )).redirectErrorStream(true).start();
        final ProcessResult result = awaitProcess(process, Duration.ofSeconds(120));

        assertThat(result.exitCode()).as(result.output()).isZero();
        final Path application = Path.of(result.output().lines().reduce((first, second) -> second).orElseThrow());
        try (JarFile jar = new JarFile(application.toFile())) {
            assertThat(jar.getJarEntry("thirdparty/large-resource.bin").getSize()).isEqualTo(resourceBytes);
        }
    }

    private Workspace workspace(final List<Bundle> bundles) throws Exception {
        final Path store = directory.resolve("railix-home/artifacts");
        Files.createDirectories(store);
        final Map<String, RailixValue> artifacts = new TreeMap<>();
        for (final Bundle bundle : bundles) {
            for (final Path artifact : List.of(bundle.root(), bundle.helper())) {
                final long size = Files.size(artifact);
                final String digest = digest(artifact);
                Files.copy(artifact, store.resolve(digest + ".jar"));
                artifacts.put(digest, RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:" + artifact.getFileName()),
                        "size", RailixValue.number(size)
                )));
            }
        }
        final List<RailixValue> lockedBundles = bundles.stream()
                .sorted(Comparator.comparing(bundle -> digest(bundle.root())))
                .<RailixValue>map(bundle -> RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + digest(bundle.root())),
                        "runtime", RailixValue.array(List.of(
                                RailixValue.string("sha256:" + digest(bundle.helper()))
                        )),
                        "steps", RailixValue.array(List.of(lockedStep(bundle)))
                )))
                .toList();
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(new ArrayList<>(artifacts.values())),
                "bundles", RailixValue.array(lockedBundles)
        ));
        final Path lock = directory.resolve("railix.dependencies.lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);
        return new Workspace(lock, store);
    }

    private static RailixValue.ObjectValue lockedStep(final Bundle bundle) {
        return RailixValue.object(Map.of(
                "contract", RailixValue.string(bundle.contractDigest()),
                "id", RailixValue.string(bundle.definition().id()),
                "implementation", RailixValue.string(bundle.implementation()),
                "implementation_entry", RailixValue.string(bundle.rootClass()),
                "version", RailixValue.string(bundle.definition().version())
        ));
    }

    private static void replaceLockedStepField(
            final Workspace workspace,
            final Bundle bundle,
            final String field,
            final RailixValue value
    ) throws IOException {
        final Map<String, RailixValue> fields = new LinkedHashMap<>(lockedStep(bundle).values());
        fields.put(field, value);
        replaceLockedSteps(workspace, List.of(RailixValue.object(fields)));
    }

    private static void replaceLockedSteps(
            final Workspace workspace,
            final List<RailixValue> steps
    ) throws IOException {
        final RailixJson.Result parsed = RailixJson.parse(Files.readString(workspace.lock(), StandardCharsets.UTF_8));
        if (!(parsed instanceof RailixJson.Parsed valid)
                || !(valid.value() instanceof RailixValue.ObjectValue root)) {
            throw new IOException("Test dependency lock is invalid.");
        }
        final RailixValue.ArrayValue bundles = (RailixValue.ArrayValue) root.values().get("bundles");
        final RailixValue.ObjectValue bundle = (RailixValue.ObjectValue) bundles.values().getFirst();
        final Map<String, RailixValue> bundleFields = new LinkedHashMap<>(bundle.values());
        bundleFields.put("steps", RailixValue.array(steps));
        final Map<String, RailixValue> rootFields = new LinkedHashMap<>(root.values());
        rootFields.put("bundles", RailixValue.array(List.of(RailixValue.object(bundleFields))));
        Files.writeString(
                workspace.lock(),
                RailixJson.write(RailixValue.object(rootFields)),
                StandardCharsets.UTF_8
        );
    }

    private Workspace lockedArtifact(final Path artifact, final String name) throws IOException {
        final Path home = directory.resolve(name + "-home");
        final Path store = home.resolve("artifacts");
        Files.createDirectories(store);
        final String digest = digest(artifact);
        Files.copy(artifact, store.resolve(digest + ".jar"));
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:" + artifact.getFileName()),
                        "size", RailixValue.number(Files.size(artifact))
                )))),
                "bundles", RailixValue.array(List.of())
        ));
        final Path lock = directory.resolve(name + ".lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);
        return new Workspace(lock, store);
    }

    private Bundle bundle(
            final String name,
            final String stepId,
            final String prefix,
            final String resource,
            final Map<String, String> rootExtras,
            final Map<String, String> helperExtras
    ) throws Exception {
        return bundle(name, stepId, prefix, resource, rootExtras, helperExtras, "", false, "");
    }

    private Bundle noisyBundle(
            final String name,
            final String startupOutput,
            final boolean floodOnRun
    ) throws Exception {
        return bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(),
                Map.of(),
                startupOutput,
                floodOnRun,
                ""
        );
    }

    private Bundle interferingBundle(final String name, final String interference) throws Exception {
        return bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(),
                Map.of(),
                "",
                false,
                interference
        );
    }

    private Bundle bundle(
            final String name,
            final String stepId,
            final String prefix,
            final String resource,
            final Map<String, String> rootExtras,
            final Map<String, String> helperExtras,
            final String startupOutput,
            final boolean floodOnRun,
            final String callbackInterference
    ) throws Exception {
        final Path root = directory.resolve("fixture-" + name);
        final String packageName = "thirdparty." + name;
        final String packagePath = packageName.replace('.', '/');
        final Path helperSource = source(root, packageName, "Helper", """
                package %s;
                public final class Helper {
                    private Helper() {}
                    public static String prefix() { return %s; }
                }
                """.formatted(packageName, javaString(prefix)));
        final Path helperClasses = root.resolve("helper-classes");
        compileJava(helperSource, helperClasses, List.of());
        final Path helperJar = root.resolve("helper.jar");
        final Map<String, byte[]> helperEntries = classEntries(helperClasses);
        helperExtras.forEach((entry, value) -> helperEntries.put(entry, value.getBytes(StandardCharsets.UTF_8)));
        jar(helperJar, helperEntries);

        final String implementation = packageName + ".Handlers.BundleStep";
        final String implementationEntry = packagePath + "/Handlers$BundleStep.class";
        final StepDefinition definition = StepDefinition.named(stepId, "1")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .run(RuntimeBoundaryProbeStep.class);
        final String contractDigest = "sha256:" + sha256(
                StepContractJson.write(definition).getBytes(StandardCharsets.UTF_8)
        );
        final Path stepSource = source(root, packageName, "Handlers", """
                package %s;
                import dev.nanonative.railix.core.step.StepHandler;
                import dev.nanonative.railix.core.step.StepInput;
                import dev.nanonative.railix.core.step.StepResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                public final class Handlers {
                    private Handlers() {}
                    public static final class BundleStep implements StepHandler {
                        static {
                            final String output = %s;
                            if (!output.isEmpty()) {
                                System.out.print(output);
                                System.out.flush();
                            }
                            final String interference = %s;
                            if (!interference.isEmpty()) {
                                interfere(interference);
                            }
                        }
                        private static void interfere(final String interference) {
                            try {
                                final java.io.ByteArrayOutputStream startup = new java.io.ByteArrayOutputStream(128);
                                final java.io.InputStream ownership = System.in;
                                boolean complete = false;
                                for (int index = 0; index < 128; index++) {
                                    final int next = ownership.read();
                                    if (next < 0) {
                                        throw new java.io.IOException("Creator startup frame ended early.");
                                    }
                                    if (next == 10) {
                                        complete = true;
                                        break;
                                    }
                                    startup.write(next);
                                }
                                if (!complete) {
                                    throw new java.io.IOException("Creator startup frame exceeded 128 bytes.");
                                }
                                final String frame = startup.toString(StandardCharsets.UTF_8);
                                System.setIn(new java.io.SequenceInputStream(
                                        new java.io.ByteArrayInputStream(
                                                (frame + (char) 10).getBytes(StandardCharsets.UTF_8)
                                        ),
                                        ownership
                                ));
                                final String[] parts = frame.split(" ", -1);
                                try (java.net.Socket callback = new java.net.Socket(
                                        java.net.InetAddress.getLoopbackAddress(),
                                        Integer.parseInt(parts[2])
                                )) {
                                    if (interference.startsWith("http:")) {
                                        callback.getOutputStream().write(("READY " + parts[1] + " "
                                                + interference.substring("http:".length()) + (char) 10)
                                                .getBytes(StandardCharsets.UTF_8));
                                        callback.getOutputStream().flush();
                                        final java.io.ByteArrayOutputStream activation = new java.io.ByteArrayOutputStream();
                                        for (int next; (next = ownership.read()) != 10;) {
                                            if (next < 0 || activation.size() >= 128) {
                                                throw new java.io.IOException("Invalid Creator activation frame.");
                                            }
                                            activation.write(next);
                                        }
                                        if (!activation.toString(StandardCharsets.UTF_8).equals("ACTIVATE " + parts[1])) {
                                            throw new java.io.IOException("Invalid Creator activation token.");
                                        }
                                        callback.getOutputStream().write(("ACTIVATED " + parts[1] + (char) 10)
                                                .getBytes(StandardCharsets.UTF_8));
                                        callback.getOutputStream().flush();
                                        callback.shutdownOutput();
                                        ownership.transferTo(java.io.OutputStream.nullOutputStream());
                                        System.exit(0);
                                    }
                                    final String response = switch (interference) {
                                        case "activation-token" -> "READY " + parts[1] + " 1" + (char) 10;
                                        case "wrong-token" -> "READY wrong-token 1" + (char) 10;
                                        case "partial" -> "READY partial";
                                        case "oversized" -> "x".repeat(129) + (char) 10;
                                        case "stalled" -> "";
                                        case "wrong-prefix" -> "WRONG " + parts[1] + " 1" + (char) 10;
                                        case "empty-token" -> "READY  1" + (char) 10;
                                        case "extra-field" -> "READY " + parts[1] + " 1 extra" + (char) 10;
                                        case "empty-port" -> "READY " + parts[1] + " " + (char) 10;
                                        case "non-numeric-port" -> "READY " + parts[1] + " one" + (char) 10;
                                        case "zero-port" -> "READY " + parts[1] + " 0" + (char) 10;
                                        case "high-port" -> "READY " + parts[1] + " 65536" + (char) 10;
                                        case "overflow-port" -> "READY " + parts[1]
                                                + " 999999999999999999999" + (char) 10;
                                        case "control-byte" -> "READY " + parts[1] + " " + (char) 0 + (char) 10;
                                        case "non-ascii" -> "READY " + parts[1] + " " + (char) 128 + (char) 10;
                                        default -> throw new IllegalArgumentException(
                                                "Unknown callback interference: " + interference
                                        );
                                    };
                                    callback.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                                    callback.getOutputStream().flush();
                                    if ("activation-token".equals(interference)) {
                                        boolean activationComplete = false;
                                        for (int index = 0; index < 128; index++) {
                                            final int next = ownership.read();
                                            if (next < 0) {
                                                throw new java.io.IOException("Creator activation frame ended early.");
                                            }
                                            if (next == 10) {
                                                activationComplete = true;
                                                break;
                                            }
                                        }
                                        if (!activationComplete) {
                                            throw new java.io.IOException("Creator activation frame exceeded 128 bytes.");
                                        }
                                        callback.getOutputStream().write(
                                                ("ACTIVATED wrong-token" + (char) 10)
                                                        .getBytes(StandardCharsets.UTF_8)
                                        );
                                        callback.getOutputStream().flush();
                                        callback.shutdownOutput();
                                    } else {
                                        if (!"stalled".equals(interference)) {
                                            callback.shutdownOutput();
                                        }
                                        callback.setSoTimeout(5_000);
                                        if (callback.getInputStream().read() >= 0) {
                                            throw new java.io.IOException("Creator wrote to an invalid callback.");
                                        }
                                    }
                                }
                            } catch (final java.io.IOException | NumberFormatException failure) {
                                throw new ExceptionInInitializerError(failure);
                            }
                        }
                        public BundleStep() {}
                        @Override
                        public StepResult run(final StepInput input) {
                            if (%s) {
                                System.out.println("RAILIX_READY malformed");
                                final byte[] output = new byte[8192];
                                for (int chunk = 0; chunk < 4096; chunk++) {
                                    System.out.write(output, 0, output.length);
                                }
                                System.out.flush();
                            }
                            try (InputStream stream = BundleStep.class.getResourceAsStream(%s)) {
                                if (stream == null) {
                                    throw new IllegalStateException("Bundle resource is missing.");
                                }
                                final String suffix = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                                return StepResult.outcome(input.primaryOutcome()).output(
                                        "value", RailixValue.string(Helper.prefix() + input.string("value") + suffix)
                                );
                            } catch (final java.io.IOException failure) {
                                throw new IllegalStateException("Bundle resource cannot be read.", failure);
                            }
                        }
                    }
                }
                """.formatted(
                        packageName,
                        javaString(startupOutput),
                        javaString(callbackInterference),
                        floodOnRun,
                        javaString("/" + packagePath + "/message.txt")
                ));
        final Path stepClasses = root.resolve("step-classes");
        compileJava(stepSource, stepClasses, List.of(location(StepHandler.class), helperJar));
        final Path rootJar = root.resolve("bundle.jar");
        final Map<String, byte[]> rootEntries = classEntries(stepClasses);
        rootEntries.put(packagePath + "/message.txt", resource.getBytes(StandardCharsets.UTF_8));
        rootEntries.put("META-INF/railix/steps.json",
                manifest(definition, implementation, implementationEntry).getBytes(StandardCharsets.UTF_8));
        rootExtras.forEach((entry, value) -> rootEntries.put(entry, value.getBytes(StandardCharsets.UTF_8)));
        jar(rootJar, rootEntries);
        return new Bundle(
                rootJar,
                helperJar,
                definition,
                implementation,
                contractDigest,
                implementationEntry,
                packagePath + "/Helper.class",
                packagePath + "/message.txt"
        );
    }

    private static String manifest(final Bundle bundle) {
        return manifest(bundle.definition(), bundle.implementation(), bundle.rootClass());
    }

    private static void replaceManifest(final Bundle bundle, final String source) throws IOException {
        rewriteJar(
                bundle.root(),
                Map.of("META-INF/railix/steps.json", source.getBytes(StandardCharsets.UTF_8)),
                Map.of()
        );
    }

    private static String manifest(
            final StepDefinition definition,
            final String implementation,
            final String implementationEntry
    ) {
        return RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "steps", RailixValue.array(List.of(manifestStep(definition, implementation, implementationEntry)))
        )));
    }

    private static RailixValue.ObjectValue manifestStep(
            final StepDefinition definition,
            final String implementation,
            final String implementationEntry
    ) {
        final RailixValue.ObjectValue contract = StepContractJson.value(definition);
        final String contractDigest = "sha256:" + sha256(
                RailixJson.write(contract).getBytes(StandardCharsets.UTF_8)
        );
        return RailixValue.object(Map.of(
                "contract", contract,
                "contract_digest", RailixValue.string(contractDigest),
                "implementation", RailixValue.string(implementation),
                "implementation_entry", RailixValue.string(implementationEntry)
        ));
    }

    private Bundle largeBundle(final long bytes) throws Exception {
        final Bundle base = bundle("large", "external.large", "large-", "-resource", Map.of(), Map.of());
        final Path target = base.root().resolveSibling("bundle-large.jar");
        try (JarFile source = new JarFile(base.root().toFile());
             OutputStream output = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (final JarEntry entry : source.stream().filter(item -> !item.isDirectory()).toList()) {
                final JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(0L);
                jar.putNextEntry(copy);
                try (InputStream input = source.getInputStream(entry)) {
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
            final JarEntry resource = new JarEntry("thirdparty/large-resource.bin");
            resource.setTime(0L);
            jar.putNextEntry(resource);
            final byte[] buffer = new byte[8192];
            long state = 0x4d595df4d0f33173L;
            long remaining = bytes;
            while (remaining > 0) {
                final int length = (int) Math.min(buffer.length, remaining);
                for (int index = 0; index < length; index++) {
                    state ^= state << 13;
                    state ^= state >>> 7;
                    state ^= state << 17;
                    buffer[index] = (byte) state;
                }
                jar.write(buffer, 0, length);
                remaining -= length;
            }
            jar.closeEntry();
        }
        return new Bundle(
                target,
                base.helper(),
                base.definition(),
                base.implementation(),
                base.contractDigest(),
                base.rootClass(),
                base.helperClass(),
                base.resource()
        );
    }

    private CompileResult.Compiled compile(final Workspace workspace, final String stepId) throws Exception {
        final CompileResult result = ProjectCompiler.compileApplication(projectSource(stepId), workspace.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private ApplicationBuilder.Artifact build(
            final Workspace workspace,
            final String stepId,
            final Path directory
    ) throws Exception {
        return ApplicationBuilder.buildProduction(project(directory, stepId), compile(workspace, stepId));
    }

    private ProcessResult run(final Workspace workspace, final String stepId, final String argument) throws Exception {
        return runJar(build(workspace, stepId, directory.resolve("run")).jar(), argument);
    }

    private static Path project(final Path directory, final String stepId) throws IOException {
        return project(directory, stepId, "value");
    }

    private static Path project(
            final Path directory,
            final String stepId,
            final String example
    ) throws IOException {
        Files.createDirectories(directory);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, projectSource(stepId, example), StandardCharsets.UTF_8);
        return project;
    }

    private static String projectSource(final String stepId) {
        return projectSource(stepId, "value");
    }

    private static String projectSource(final String stepId, final String example) {
        return """
                {"format":1,"id":"external-step-app","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"external","payload":[%s]
                  }]},
                  {"id":"external","use":"%s","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"external"},
                  {"from":"external.next","to":"end"}
                ]}
                """.formatted(RailixJson.write(RailixValue.string(example)), stepId);
    }

    private static Path source(
            final Path root,
            final String packageName,
            final String className,
            final String source
    ) throws IOException {
        final Path file = root.resolve("src").resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static void compileJava(
            final Path source,
            final Path classes,
            final List<Path> dependencies
    ) throws IOException {
        Files.createDirectories(classes);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            final List<String> options = new ArrayList<>(List.of(
                    "-proc:none", "-g:none", "--release", Integer.toString(Runtime.version().feature()),
                    "-d", classes.toString()
            ));
            if (!dependencies.isEmpty()) {
                options.add("-classpath");
                options.add(dependencies.stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
            }
            final boolean success = Boolean.TRUE.equals(compiler.getTask(
                    null, files, diagnostics, options, null, files.getJavaFileObjects(source)
            ).call());
            assertThat(success).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
    }

    private static Map<String, byte[]> classEntries(final Path classes) throws IOException {
        final Map<String, byte[]> entries = new TreeMap<>();
        try (var files = Files.walk(classes)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                        Files.readAllBytes(file));
            }
        }
        return entries;
    }

    private static void jar(final Path target, final Map<String, byte[]> source) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(output)) {
            for (final Map.Entry<String, byte[]> entry : new TreeMap<>(source).entrySet()) {
                final JarEntry item = new JarEntry(entry.getKey());
                item.setTime(0L);
                jar.putNextEntry(item);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static void patchCentralDirectorySizes(final Path jar, final List<Long> sizes) throws IOException {
        final byte[] bytes = Files.readAllBytes(jar);
        int sizeIndex = 0;
        for (int index = 0; index <= bytes.length - 46; index++) {
            if (littleEndianInt(bytes, index) != 0x02014b50) {
                continue;
            }
            if (sizeIndex >= sizes.size()) {
                throw new IOException("Test JAR has more central-directory entries than expected.");
            }
            final long size = sizes.get(sizeIndex++);
            for (int offset = 0; offset < 4; offset++) {
                bytes[index + 24 + offset] = (byte) (size >>> (offset * 8));
            }
        }
        if (sizeIndex != sizes.size()) {
            throw new IOException("Test JAR central-directory entry count is invalid.");
        }
        Files.write(jar, bytes);
    }

    private static void rewriteJar(
            final Path source,
            final Map<String, byte[]> replacements,
            final Map<String, byte[]> additions
    ) throws IOException {
        rewriteJar(source, replacements, additions, Set.of());
    }

    private static void rewriteJar(
            final Path source,
            final Map<String, byte[]> replacements,
            final Map<String, byte[]> additions,
            final Set<String> removals
    ) throws IOException {
        final Path target = source.resolveSibling(source.getFileName() + ".changed");
        try (JarFile input = new JarFile(source.toFile());
             OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW);
             JarOutputStream jar = new JarOutputStream(output)) {
            final var entries = input.entries();
            while (entries.hasMoreElements()) {
                final JarEntry existing = entries.nextElement();
                if (existing.isDirectory() || removals.contains(existing.getName())) {
                    continue;
                }
                final JarEntry entry = new JarEntry(existing.getName());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                final byte[] replacement = replacements.get(existing.getName());
                if (replacement == null) {
                    try (InputStream content = input.getInputStream(existing)) {
                        content.transferTo(jar);
                    }
                } else {
                    jar.write(replacement);
                }
                jar.closeEntry();
            }
            for (final Map.Entry<String, byte[]> addition : new TreeMap<>(additions).entrySet()) {
                final JarEntry entry = new JarEntry(addition.getKey());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                jar.write(addition.getValue());
                jar.closeEntry();
            }
        }
        Files.move(target, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static int littleEndianInt(final byte[] bytes, final int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    private static Path location(final Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (final URISyntaxException exception) {
            throw new IOException("Test dependency location is invalid.", exception);
        }
    }

    private static ProcessResult runJar(final Path jar, final String argument) throws Exception {
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), argument
        )).redirectErrorStream(true).start();
        return awaitProcess(process, Duration.ofSeconds(20));
    }

    private static ProcessResult awaitProcess(final Process process, final Duration timeout) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(PROCESS_OUTPUT_LIMIT);
        final Thread reader = Thread.ofVirtual().name("railix-test-process-output").start(() ->
                drain(process.getInputStream(), output)
        );
        boolean interrupted = Thread.interrupted();
        final Wait execution = waitFor(process, timeout);
        interrupted |= execution.interrupted();
        Wait termination = execution;
        if (!execution.complete()) {
            process.destroyForcibly();
            termination = waitFor(process, Duration.ofSeconds(20));
            interrupted |= termination.interrupted();
        }
        Wait drained = waitFor(reader, Duration.ofSeconds(20));
        interrupted |= drained.interrupted();
        if (!drained.complete()) {
            close(process.getInputStream());
            final Wait forcedDrain = waitFor(reader, Duration.ofSeconds(20));
            interrupted |= forcedDrain.interrupted();
            drained = forcedDrain;
        }
        close(process.getOutputStream());
        close(process.getInputStream());
        close(process.getErrorStream());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        final String captured;
        synchronized (output) {
            captured = output.toString(StandardCharsets.UTF_8).strip();
        }
        if (!execution.complete()) {
            throw new IOException("Forked process exceeded " + timeout + ". " + captured);
        }
        if (!termination.complete()) {
            throw new IOException("Forked process did not terminate. " + captured);
        }
        if (!drained.complete()) {
            throw new IOException("Forked process output reader did not terminate. " + captured);
        }
        return new ProcessResult(process.exitValue(), captured);
    }

    private static void drain(final InputStream input, final ByteArrayOutputStream output) {
        try (input) {
            final byte[] buffer = new byte[8_192];
            for (int length = input.read(buffer); length >= 0; length = input.read(buffer)) {
                synchronized (output) {
                    final int retained = Math.min(length, PROCESS_OUTPUT_LIMIT - output.size());
                    if (retained > 0) {
                        output.write(buffer, 0, retained);
                    }
                }
            }
        } catch (final IOException ignored) {
            // Process termination remains the lifecycle authority.
        }
    }

    private static Wait waitFor(final Process process, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new Wait(!process.isAlive(), interrupted);
    }

    private static Wait waitFor(final Thread thread, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new Wait(!thread.isAlive(), interrupted);
    }

    private static void close(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception ignored) {
            // Process termination remains the lifecycle authority.
        }
    }

    private void assertDependencyEntryRejected(
            final String name,
            final String entry,
            final String diagnostic
    ) throws Exception {
        final Bundle bundle = bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(entry, "metadata"),
                Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve(name + "-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith(diagnostic);
    }

    private void assertCreatorObserves(final Bundle bundle, final String argument) throws Exception {
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id(), argument);
        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = exampleView(client, creator);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\":\"helper-" + argument + "-resource\"");
        }
    }

    private static HttpResponse<String> exampleView(
            final HttpClient client,
            final CreatorServer creator
    ) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpResponse<String> status;
        do {
            status = client.send(
                    HttpRequest.newBuilder(creator.baseUri().resolve("/api/examples"))
                        .timeout(Duration.ofSeconds(8))
                        .header(
                                "X-Railix-Creator-Token",
                                creator.baseUri().getRawFragment().substring("token=".length())
                        )
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            final RailixValue.ObjectValue examples = (RailixValue.ObjectValue)
                    ((RailixJson.Parsed) RailixJson.parse(status.body())).value();
            if (((RailixValue.NumberValue) examples.values().get("completed")).value().intValueExact() == 1) {
                break;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        return client.send(
                HttpRequest.newBuilder(creator.baseUri().resolve("/api/examples/command:0/view"))
                        .timeout(Duration.ofSeconds(8))
                        .header(
                                "X-Railix-Creator-Token",
                                creator.baseUri().getRawFragment().substring("token=".length())
                        )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private static long applicationPid(final HttpClient client, final CreatorServer creator)
            throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(creator.baseUri().resolve("/api/application"))
                        .timeout(Duration.ofSeconds(8))
                        .header(
                                "X-Railix-Creator-Token",
                                creator.baseUri().getRawFragment().substring("token=".length())
                        )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        final RailixValue.ObjectValue value = (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
        return ((RailixValue.NumberValue) value.values().get("pid")).value().longValueExact();
    }

    private static boolean awaitStopped(final long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static String digest(final Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] classBytes(final Class<?> type) throws IOException {
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) {
                throw new IOException("Test class bytes are unavailable: " + type.getName() + ".");
            }
            return input.readAllBytes();
        }
    }

    private static CompletableFuture<HttpResponse<byte[]>> observationRequest(
            final HttpClient client, final CreatorServer creator, final String path
    ) {
        return client.sendAsync(HttpRequest.newBuilder(creator.baseUri().resolve(path))
                        .header("X-Railix-Creator-Token", CreatorServerE2eSupport.tokenOrIncorrect(creator.baseUri()))
                        .timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void primeMetricCatalog(final HttpClient client, final CreatorServer creator,
                                          final ServerSocket upstream, final long pid) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        new dev.nanonative.railix.development.DevelopmentRuntime.Metrics("catalog", new String[0], new String[0])
                .writeCatalogJson(output);
        final Map<String, RailixValue> catalog = new LinkedHashMap<>(CreatorServerE2eSupport.object(
                output.toString(StandardCharsets.UTF_8)).values());
        catalog.put("application_pid", RailixValue.number(pid));
        final var request = observationRequest(client, creator, "/api/metrics/catalog");
        try (Socket socket = observationConnection(upstream)) {
            observationReply(socket, 200, RailixJson.write(RailixValue.object(catalog)));
        }
        assertThat(request.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    private static String sceneObservationDocument(final String read, final long pid,
                                                    final RailixValue.ObjectValue query, final boolean selected) {
        final Map<String, RailixValue> groups = new LinkedHashMap<>();
        if (read.equals("metrics")) {
            final RailixValue counters = CreatorServerE2eSupport.object(
                    "{\"executions\":7,\"errors\":0,\"cancelled\":0,\"duration_samples\":1,\"duration_nanos_total\":7}");
            for (final String category : List.of("steps", "flows")) {
                if (query.values().get(category) instanceof RailixValue.ObjectValue values) {
                    values.values().keySet().forEach(id -> groups.put(id, counters));
                }
            }
            if (query.values().get("application") instanceof RailixValue.StringValue id) groups.put(id.value(), counters);
        } else {
            final var requested = (RailixValue.ObjectValue) query.values().get("groups");
            requested.values().forEach((id, ranges) -> {
                long count = 0;
                for (final RailixValue range : ((RailixValue.ArrayValue) ranges).values()) {
                    final var bounds = ((RailixValue.ArrayValue) range).values();
                    final long from = ((RailixValue.NumberValue) bounds.getFirst()).value().longValueExact();
                    final long to = ((RailixValue.NumberValue) bounds.getLast()).value().longValueExact();
                    count += Math.max(0, Math.min(2, to) - Math.max(1, from) + 1);
                }
                final Map<String, RailixValue> counts = new LinkedHashMap<>();
                counts.put("covered_count", RailixValue.number(count));
                if (selected) counts.put("selected_count", RailixValue.number(count));
                groups.put(id, RailixValue.object(counts));
            });
        }
        final Map<String, RailixValue> document = new LinkedHashMap<>();
        document.put("application_pid", RailixValue.number(pid));
        document.put("groups", RailixValue.object(groups));
        if (read.equals("metrics")) {
            document.put("observed_at", RailixValue.number(1));
            document.put("elapsed_nanos", RailixValue.number(1));
        }
        if (!read.equals("metrics")) {
            document.put("revision", RailixValue.number(1));
            if (selected) document.put("example", RailixValue.string("command:0"));
        }
        return RailixJson.write(RailixValue.object(document));
    }

    private static void observationReply(final Socket socket, final int status, final String body) throws IOException {
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Observation\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static Socket observationConnection(final ServerSocket listener) throws IOException {
        return observationConnection(listener, ignored -> {});
    }

    private static Socket observationConnection(final ServerSocket listener,
                                               final java.util.function.Consumer<RailixValue.ObjectValue> query) throws IOException {
        listener.setSoTimeout(5000);
        final Socket socket = listener.accept();
        try {
            socket.setSoTimeout(5000);
            final ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int ending = 0;
            for (int count = 0; count < 16_384; count++) {
                final int next = socket.getInputStream().read();
                if (next < 0) throw new IOException("Observation request ended before headers.");
                headers.write(next);
                ending = (ending << 8) | next;
                if (ending == 0x0d0a0d0a) {
                    final String header = headers.toString(StandardCharsets.US_ASCII);
                    assertThat(header).contains("Authorization: Bearer ");
                    final int length = header.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                            .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElse(0);
                    final byte[] body = socket.getInputStream().readNBytes(length);
                    assertThat(body).hasSize(length);
                    query.accept(CreatorServerE2eSupport.object(length == 0 ? "{}" : new String(body, StandardCharsets.UTF_8)));
                    return socket;
                }
            }
            throw new IOException("Observation request headers exceeded 16384 bytes.");
        } catch (final IOException | RuntimeException | AssertionError failure) {
            socket.close();
            throw failure;
        }
    }

    private static String javaString(final String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    private record Workspace(Path lock, Path store) {
        StepCatalog catalog() throws StepCatalog.DependencyException {
            return StandardLibrary.catalog().install(lock, store);
        }

        Path stored(final Path artifact) {
            return store.resolve(digest(artifact) + ".jar");
        }

        Path railixHome() {
            return store.getParent();
        }
    }

    private record Bundle(
            Path root,
            Path helper,
            StepDefinition definition,
            String implementation,
            String contractDigest,
            String rootClass,
            String helperClass,
            String resource
    ) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record Wait(boolean complete, boolean interrupted) {
    }
}
