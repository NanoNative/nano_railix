package dev.nanonative.railix.creator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.runtime.ObservationServer;
import dev.nanonative.railix.mesh.MeshRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ConstantDescs;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;

import static dev.nanonative.railix.creator.LiveApplicationE2eTest.await;
import static dev.nanonative.railix.creator.ProjectSourceArtifactsE2eTest.stop;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(60)
final class RailixMeshPackageIT {
    private static final Path CLI = Path.of("target/railix.jar").toAbsolutePath();
    private static final Path BIN = Path.of(System.getProperty("java.home"), "bin");
    private static final UUID MESH = UUID.randomUUID();
    private static final UUID SEED = UUID.randomUUID();
    private static final UUID PEER = new UUID(0, 1);

    @TempDir static Path credentials;
    @TempDir Path directory;

    @BeforeAll
    static void provisionTrust() throws Exception {
        for (String store : List.of("issuer", "trust", "seed", "peer")) {
            Files.writeString(credentials.resolve(store + ".password"), UUID.randomUUID() + "\n");
        }
        keytool("issuer", "-genkeypair", "-alias", "root", "-dname", "CN=Railix test issuer",
                "-keyalg", "EC", "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign");
        keytool("issuer", "-exportcert", "-alias", "root", "-file", "root.cer");
        keytool("trust", "-importcert", "-noprompt", "-alias", "root", "-file", "root.cer");
        issue("seed", SEED);
        issue("peer", PEER);
    }

    @Test
    void joinsMatchingJvmsAndReportsTheSelectedPeerInCreator() throws Exception {
        final Path project = project("matching");
        final Path standalone = build(project, "--include-project");
        final Path mesh = build(project, "--include-project", "--mesh");
        assertThat(mesh).isNotEqualTo(standalone);
        try (JarFile ordinary = new JarFile(standalone.toFile()); JarFile enabled = new JarFile(mesh.toFile())) {
            assertThat(ordinary.stream().map(entry -> entry.getName()))
                    .noneMatch(name -> name.startsWith("dev/nanonative/railix/mesh/"));
            final String projectPackage = "dev/nanonative/railix/core/project/";
            assertThat(ordinary.getEntry(projectPackage + "FlowContinuation.class")).isNull();
            assertThat(ordinary.getEntry(projectPackage + "FlowPlacement.class")).isNull();
            try (InputStream compiled = ordinary.getInputStream(
                    ordinary.getJarEntry(projectPackage + "RailixApplication.class"))) {
                assertThat(ClassFile.of().parse(compiled.readAllBytes()).methods())
                        .noneMatch(method -> method.methodName().equalsString("runContinuation"));
            }
            try (InputStream metrics = ordinary.getInputStream(ordinary.getJarEntry(
                    "dev/nanonative/railix/core/runtime/RuntimeMetrics.class"))) {
                assertThat(ClassFile.of().parse(metrics.readAllBytes()).constantPool())
                        .filteredOn(entry -> entry instanceof Utf8Entry name
                                && (name.equalsString("offload_ack") || name.equalsString("offload_no_ack")))
                        .isEmpty();
            }
            try (InputStream http = ordinary.getInputStream(ordinary.getJarEntry(
                    "dev/nanonative/railix/stdlib/HttpStepHandlers$Trigger.class"))) {
                assertThat(ClassFile.of().parse(http.readAllBytes()).constantPool())
                        .filteredOn(entry -> entry instanceof Utf8Entry name && name.stringValue().startsWith("MESH_"))
                        .isEmpty();
            }
            assertThat(enabled.getEntry("dev/nanonative/railix/mesh/MeshRuntime.class")).isNotNull();
            assertThat(enabled.stream().map(entry -> entry.getName()))
                    .noneMatch(name -> name.endsWith(".p12") || name.endsWith(".password"));
        }

        final List<Path> configurations = configurations();
        final Process seed = launch(mesh, "seed", configurations.getFirst());
        try (HttpClient client = HttpClient.newHttpClient()) {
            final ObservationServer.Connection seedConnection = connection(seed, "seed");
            final Process peer = launch(mesh, "peer", configurations.getLast());
            try {
                final ObservationServer.Connection peerConnection = connection(peer, "peer");
                final HttpResponse<String> unauthorized = client.send(
                        HttpRequest.newBuilder(seedConnection.origin().resolve("/v1/status"))
                                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(unauthorized.statusCode()).isEqualTo(401);
                awaitPeersJoined(client, seedConnection, peerConnection);
                try (CreatorServer creator = CreatorServer.start(0, project, home().resolve(".railix"))) {
                    final URI uri = creator.baseUri();
                    final String select = "{\"instance\":\"" + seedConnection.instance() + "\"}";
                    final String confirm = "{\"instance\":\"" + seedConnection.instance() + "\",\"confirm\":true}";
                    assertThat(CreatorServerE2eSupport.request(uri, "POST", "/api/live", select).statusCode()).isEqualTo(200);
                    assertThat(CreatorServerE2eSupport.request(uri, "POST", "/api/live", confirm).statusCode()).isEqualTo(200);
                    final HttpResponse<String> selected = LiveApplicationE2eTest.readLive(
                            uri, "/api/live/application", seedConnection.instance());
                    assertThat(selected.statusCode()).isEqualTo(200);
                    assertReady(json(selected.body()).getAsJsonObject("mesh"), PEER);
                    final HttpResponse<String> metrics = LiveApplicationE2eTest.readLive(
                            uri, "/api/live/metrics", seedConnection.instance());
                    final HttpResponse<String> catalog = LiveApplicationE2eTest.readLive(
                            uri, "/api/live/metrics/catalog", seedConnection.instance());
                    assertThat(metrics.statusCode()).isEqualTo(200);
                    assertThat(json(metrics.body()).getAsJsonObject("application").getAsJsonObject("metrics").asMap())
                            .doesNotContainKeys("offload_ack", "offload_no_ack");
                    assertThat(catalog.statusCode()).isEqualTo(200);
                    assertThat(json(catalog.body()).getAsJsonObject("metrics").asMap())
                            .doesNotContainKeys("offload_ack", "offload_no_ack");
                }
            } finally {
                stop(peer);
            }
            await(() -> assertThat(status(client, seedConnection).get("peer").isJsonNull()).isTrue());
        } finally {
            stop(seed);
        }
    }

    @Test
    void configuredReplicasStayConnectedAfterTheOriginalSeedLeaves() throws Exception {
        final Path jar = build(project("configured-peers"), "--mesh");
        final List<String> names = List.of("seed", "peer", "third", "fourth");
        final List<UUID> replicas = List.of(SEED, PEER, UUID.randomUUID(), UUID.randomUUID());
        for (int index = 2; index < names.size(); index++) {
            final String name = names.get(index);
            Files.writeString(credentials.resolve(name + ".password"), UUID.randomUUID() + "\n");
            issue(name, replicas.get(index));
        }

        final List<Path> configurations;
        try (ServerSocket seed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket third = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket fourth = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final String first = endpoint(seed.getLocalPort());
            final String second = endpoint(peer.getLocalPort());
            final String thirdEndpoint = endpoint(third.getLocalPort());
            configurations = List.of(
                    config("seed", seed.getLocalPort(), ""),
                    config("peer", peer.getLocalPort(), String.join(",", first, thirdEndpoint)),
                    config("third", third.getLocalPort(), String.join(",", first, second)),
                    config("fourth", fourth.getLocalPort(), String.join(",", first, second, thirdEndpoint))
            );
        }

        final List<Process> processes = new ArrayList<>();
        final List<ObservationServer.Connection> observations = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int index = 0; index < names.size(); index++) {
                final String name = names.get(index);
                final Process process = launch(jar, name, configurations.get(index));
                processes.add(process);
                observations.add(connection(process, name));
            }
            await(() -> assertPeerGroup(client, observations, replicas));
            stop(processes.getFirst());
            final List<ObservationServer.Connection> survivors = observations.subList(1, observations.size());
            final List<UUID> survivorIds = replicas.subList(1, replicas.size());
            await(() -> assertPeerGroup(client, survivors, survivorIds));

            stop(processes.getLast());
            processes.removeLast();
            survivors.removeLast();
            await(() -> assertPeerGroup(client, survivors, survivorIds.subList(0, survivorIds.size() - 1)));

            final Process rejoined = launch(jar, "fourth-rejoined", configurations.getLast());
            processes.add(rejoined);
            survivors.add(connection(rejoined, "fourth-rejoined"));
            await(() -> assertPeerGroup(client, survivors, survivorIds));
        } finally {
            for (final Process process : processes.reversed()) {
                stop(process);
            }
        }
    }

    @Test
    void returnsALargeOffloadedReplyAndRefusesExcessWork() throws Exception {
        withOffloadPair(pair -> {
            // The Flow context contains three copies of the payload and fits within 1 MiB.
            final String payload = "a".repeat(320 * 1024);
            final JsonObject request = new JsonObject();
            request.addProperty("request", payload);
            request.addProperty("runtime", "client data");
            final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), request);
            assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo(payload);
            final CompletableFuture<HttpResponse<String>> offloaded = pair.request(pair.seed(), request);
            assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo(payload);

            // Both slots are occupied, including the peer's slot used by the remote continuation.
            final HttpResponse<String> refused = pair.request(pair.peer(), "refused").get(5, TimeUnit.SECONDS);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
            assertThat(refused.body()).contains("APPLICATION_SOURCE_INACTIVE", "Source is not accepting work.");
            final ObservationServer.Connection peer = connection(pair.peerProcess(), "peer");
            assertSingleFlowExecution(pair.client(), pair.seedObservation(), 1, 0, 0);
            assertSingleFlowExecution(pair.client(), peer, 1, 0, 0);
            pair.release().countDown();

            assertFlowResponse(local.get(5, TimeUnit.SECONDS), payload);
            final HttpResponse<String> remoteResponse = offloaded.get(5, TimeUnit.SECONDS);
            assertThat(remoteResponse.statusCode()).as(remoteResponse.body()).isEqualTo(200);
            assertThat(json(remoteResponse.body())).isEqualTo(request);
            assertThat(pair.arrivals()).isEmpty();
            assertSingleFlowExecution(pair.client(), pair.seedObservation(), 0, 1, 0);
            assertSingleFlowExecution(pair.client(), peer, 0, 0, 0);
            final ObservationServer.Connection ingress = pair.seedObservation();
            final HttpResponse<String> query = pair.client().send(
                    HttpRequest.newBuilder(ingress.origin().resolve("/v1/metrics/query"))
                            .header("Authorization", "Bearer " + ingress.token())
                            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(2))
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"application":"total","flows":{"selected":"http"},"steps":{},
                                     "metrics":["offload_ack"]}
                                    """)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(query.statusCode()).as(query.body()).isEqualTo(200);
            assertThat(json(query.body()).get("groups"))
                    .isEqualTo(json("""
                            {"total":{"offload_ack":1},"selected":{"offload_ack":1}}
                            """));
        });
    }

    @Test
    void offloadsToTwoPeersWhileTheIngressExecutionSlotIsBusy() throws Exception {
        withOffloadPair(pair -> {
            final UUID thirdId = new UUID(0, 2);
            final Path thirdConfig = joiningConfiguration("third-offload-peer", thirdId, pair.seedMeshPort());
            configureOffload(thirdConfig, 1);
            final Process third = launch(pair.jar(), "third-offload-peer", thirdConfig);
            try {
                final ObservationServer.Connection firstPeer = connection(pair.peerProcess(), "peer");
                final ObservationServer.Connection secondPeer = connection(third, "third-offload-peer");
                await(() -> assertThat(status(pair.client(), pair.seedObservation()).getAsJsonArray("peers").asList())
                        .extracting(peer -> peer.getAsJsonObject().get("replicaId").getAsString())
                        .containsExactlyInAnyOrder(PEER.toString(), thirdId.toString()));

                final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "local");
                assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("local");
                final CompletableFuture<HttpResponse<String>> first = pair.request(pair.seed(), "first offload");
                assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("first offload");
                final CompletableFuture<HttpResponse<String>> second = pair.request(pair.seed(), "second offload");
                assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("second offload");
                pair.release().countDown();

                assertFlowResponse(local.get(5, TimeUnit.SECONDS), "local");
                assertFlowResponse(first.get(5, TimeUnit.SECONDS), "first offload");
                assertFlowResponse(second.get(5, TimeUnit.SECONDS), "second offload");
                assertSingleFlowExecution(pair.client(), pair.seedObservation(), 0, 2, 0);
                assertSingleFlowExecution(pair.client(), firstPeer, 0, 0, 0);
                assertSingleFlowExecution(pair.client(), secondPeer, 0, 0, 0);
            } finally {
                pair.release().countDown();
                stop(third);
            }
        });
    }

    @Test
    void preservesTheMaximumContextDepthWhenOffloaded() throws Exception {
        // The context, HTTP Client response, and its body enclose the 61 arrays.
        final String nested = "[".repeat(61) + "0" + "]".repeat(61);
        withOffloadPair(body -> {
            body.add("nested", JsonParser.parseString(nested));
            return body;
        }, pair -> {
            final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "boundary");
            assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("boundary");
            final CompletableFuture<HttpResponse<String>> offloaded = pair.request(pair.seed(), "boundary");
            assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("boundary");
            pair.release().countDown();

            final HttpResponse<String> localResponse = local.get(5, TimeUnit.SECONDS);
            final HttpResponse<String> remoteResponse = offloaded.get(5, TimeUnit.SECONDS);
            assertFlowResponse(localResponse, "boundary");
            assertThat(remoteResponse.statusCode()).as(remoteResponse.body()).isEqualTo(200);
            assertThat(remoteResponse.body()).isEqualTo(localResponse.body());
        });
    }

    @Test
    void returnsATerminalFailureWhenTheOffloadedResultExceedsTheWireLimit() throws Exception {
        withOffloadPair(pair -> {
            final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "local");
            assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("local");
            final String payload = "a".repeat(384 * 1024);
            final CompletableFuture<HttpResponse<String>> offloaded = pair.request(pair.seed(), payload);
            assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo(payload);
            pair.release().countDown();

            final HttpResponse<String> response = offloaded.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
            assertThat(json(response.body()).getAsJsonObject("error").get("code").getAsString())
                    .isEqualTo("MESH_FLOW_RESULT_INVALID");
            assertFlowResponse(local.get(5, TimeUnit.SECONDS), "local");
            assertThat(pair.arrivals()).isEmpty();
        });
    }

    @Test
    void skipsIneligiblePeersBeforeCountingOffloadAttempts() throws Exception {
        withOffloadPair(pair -> {
            final UUID membershipId = new UUID(0, 4);
            final UUID firstPeer = new UUID(0, 2);
            final UUID secondPeer = new UUID(0, 3);
            final Path membershipConfig = joiningConfiguration("membership-only-peer", membershipId, pair.seedMeshPort());
            final JsonObject membership = json(Files.readString(membershipConfig));
            // Spare capacity would rank this peer first if eligibility were ignored.
            membership.addProperty("flowCapacity", 4);
            Files.writeString(membershipConfig, membership.toString());
            final Path firstConfig = joiningConfiguration("first-declining-peer", firstPeer, pair.seedMeshPort());
            final Path secondConfig = joiningConfiguration("second-declining-peer", secondPeer, pair.seedMeshPort());
            configureOffload(firstConfig, 3);
            configureOffload(secondConfig, 2);
            final String build = status(pair.client(), pair.seedObservation()).get("logicalBuildId").getAsString();
            final BlockingQueue<UUID> refusals = new LinkedBlockingQueue<>();
            try (MeshRuntime member = MeshRuntime.open(membershipConfig.toString(), build,
                    "http"::equals, ignored -> Optional.empty()).orElseThrow();
                 MeshRuntime first = MeshRuntime.open(firstConfig.toString(), build, "http"::equals, ignored -> {
                     refusals.add(firstPeer);
                     return Optional.empty();
                 }).orElseThrow();
                 MeshRuntime second = MeshRuntime.open(secondConfig.toString(), build, "http"::equals, ignored -> {
                     refusals.add(secondPeer);
                     return Optional.empty();
                 }).orElseThrow()) {
                member.start();
                first.start();
                second.start();
                await(() -> assertThat(status(pair.client(), pair.seedObservation()).getAsJsonArray("peers").asList())
                        .extracting(peer -> peer.getAsJsonObject().get("replicaId").getAsString())
                        .containsExactlyInAnyOrder(PEER.toString(), firstPeer.toString(), secondPeer.toString(),
                                membershipId.toString()));
                final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "local");
                assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("local");
                final CompletableFuture<HttpResponse<String>> retried = pair.request(pair.seed(), "retried");
                assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("retried");
                assertThat(refusals).containsExactly(firstPeer, secondPeer);
                pair.release().countDown();
                assertFlowResponse(retried.get(5, TimeUnit.SECONDS), "retried");
                assertFlowResponse(local.get(5, TimeUnit.SECONDS), "local");
                assertThat(pair.arrivals()).isEmpty();
                assertSingleFlowExecution(pair.client(), pair.seedObservation(), 0, 1, 0);
            }
        });
    }

    @Test
    void reportsUncertainOutcomeWhenAnAdmittedPeerDisconnects() throws Exception {
        withOffloadPair(pair -> {
            final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "local");
            assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("local");
            final CompletableFuture<HttpResponse<String>> offloaded = pair.request(pair.seed(), "offloaded");
            assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("offloaded");

            pair.peerProcess().destroyForcibly();
            assertThat(pair.peerProcess().waitFor(4, TimeUnit.SECONDS)).isTrue();
            final HttpResponse<String> response = offloaded.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
            assertThat(response.body()).contains("MESH_FLOW_UNCERTAIN");
            pair.release().countDown();
            assertFlowResponse(local.get(5, TimeUnit.SECONDS), "local");
            assertThat(pair.arrivals()).isEmpty();
            assertSingleFlowExecution(pair.client(), pair.seedObservation(), 0, 0, 1);
        });
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void returnsTheOutcomeOfAdmittedWorkWhileThePeerDrains() throws Exception {
        withOffloadPair(pair -> {
            final CompletableFuture<HttpResponse<String>> local = pair.request(pair.seed(), "local");
            assertThat(pair.arrivals().poll(5, TimeUnit.SECONDS)).isEqualTo("local");
            final CompletableFuture<HttpResponse<String>> offloaded = pair.request(pair.seed(), "offloaded");
            assertThat(pair.arrivals().poll(4, TimeUnit.SECONDS)).isEqualTo("offloaded");

            final Process peer = pair.peerProcess();
            final ObservationServer.Connection observation = connection(peer, "peer");
            peer.destroy();
            await(() -> assertThat(status(pair.client(), observation).get("status").getAsString())
                    .isEqualTo("draining"));
            pair.release().countDown();

            assertFlowResponse(offloaded.get(5, TimeUnit.SECONDS), "offloaded");
            assertFlowResponse(local.get(5, TimeUnit.SECONDS), "local");
            assertThat(peer.waitFor(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    private void withOffloadPair(final OffloadScenario scenario) throws Exception {
        withOffloadPair(UnaryOperator.identity(), scenario);
    }

    private void withOffloadPair(final UnaryOperator<JsonObject> respond,
                                 final OffloadScenario scenario) throws Exception {
        final BlockingQueue<String> arrivals = new LinkedBlockingQueue<>();
        final CountDownLatch release = new CountDownLatch(1);
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
             HttpClient client = HttpClient.newHttpClient()) {
            upstream.setExecutor(workers);
            upstream.createContext("/", exchange -> {
                try (exchange) {
                    final byte[] request = exchange.getRequestBody().readAllBytes();
                    final JsonObject body = json(new String(request, java.nio.charset.StandardCharsets.UTF_8));
                    arrivals.add(body.get("request").getAsString());
                    if (!release.await(10, TimeUnit.SECONDS)) return;
                    final byte[] response = respond.apply(body).toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (final InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            upstream.start();
            try {
                final Path jar = build(offloadProject(upstream.getAddress().getPort()), "--mesh");
                final List<Path> configurations = configurations();
                for (final Path config : configurations) {
                    configureOffload(config, 1);
                }
                final int seedPort = json(Files.readString(configurations.getFirst()))
                        .getAsJsonObject("listenEndpoint").get("port").getAsInt();

                final Process seed = launch(jar, "seed", configurations.getFirst());
                try {
                    final Process peer = launch(jar, "peer", configurations.getLast());
                    try {
                        final ObservationServer.Connection seedConnection = connection(seed, "seed");
                        awaitPeersJoined(client, seedConnection, connection(peer, "peer"), 1);
                        scenario.run(new OffloadPair(client, httpOrigin("seed"), httpOrigin("peer"),
                                peer, seedConnection, seedPort, jar, arrivals, release));
                    } finally {
                        release.countDown();
                        if (peer.isAlive()) stop(peer);
                    }
                } finally {
                    stop(seed);
                }
            } finally {
                release.countDown();
                upstream.stop(0);
            }
        }
    }

    private Path joiningConfiguration(final String name, final UUID replica, final int seedPort) throws Exception {
        Files.writeString(credentials.resolve(name + ".password"), UUID.randomUUID() + "\n");
        issue(name, replica);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return config(name, listener.getLocalPort(), endpoint(seedPort));
        }
    }

    private static void configureOffload(final Path config, final int capacity) throws IOException {
        final JsonObject values = json(Files.readString(config));
        values.addProperty("flowCapacity", capacity);
        values.addProperty("offloadFlow", "http");
        Files.writeString(config, values.toString());
    }

    private Path offloadProject(final int upstream) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, """
                {"format":1,"id":"offload-checkpoint","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0,"target":["context","request"]},
                   "examples":[{"name":"request","payload":{
                     "method":"POST","path":"/","query":"","headers":{},"body":{"request":"example"}}}]},
                  {"id":"call","use":"railix.http.client","inputs":{
                    "url":"http://127.0.0.1:%d/","method":"POST","headers":{"Content-Type":"application/json"}},
                    "receives":{"body":["context","request","body"]},"returns":{"response":["context","response"]}},
                  {"id":"body","use":"railix.field-manipulation","inputs":{
                    "field":["context","body"],"steps":[],
                    "value":[{"inputs":{"source":["context","response","body"]},"option":"field"}]}}
                ],"links":[{"from":"app.start","to":"http"},{"from":"http.next","to":"call"},
                            {"from":"call.next","to":"body"},{"from":"body.next","to":"end"}]}
                """.formatted(upstream));
        return project;
    }

    private URI httpOrigin(final String name) throws Exception {
        final Path log = directory.resolve(name + ".log");
        final String prefix = "Railix HTTP ";
        await(() -> assertThat(Files.readString(log)).contains(prefix));
        return URI.create(Files.readAllLines(log).stream().filter(line -> line.startsWith(prefix))
                .findFirst().orElseThrow().substring(prefix.length()));
    }

    private static void assertFlowResponse(final HttpResponse<String> response, final String expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response.body()).get("request").getAsString()).isEqualTo(expected);
    }

    private static void assertSingleFlowExecution(final HttpClient client, final ObservationServer.Connection replica,
                                                  final int inFlight, final int acknowledged,
                                                  final int unacknowledged) throws Exception {
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(replica.origin().resolve("/v1/metrics/nodes/http"))
                .header("Authorization", "Bearer " + replica.token()).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        final JsonObject flow = json(response.body()).getAsJsonArray("flows").get(0).getAsJsonObject();
        final JsonObject metrics = flow.getAsJsonObject("metrics");
        assertThat(metrics.get("executions").getAsInt()).as("Flow executions on replica %s", replica.pid()).isEqualTo(1);
        assertThat(metrics.get("in_flight").getAsInt()).as("In-flight Flows on replica %s", replica.pid())
                .isEqualTo(inFlight);
        assertThat(metrics.get("offload_ack")).as("Acknowledged offloads on replica %s", replica.pid())
                .isEqualTo(new JsonPrimitive(acknowledged));
        assertThat(metrics.get("offload_no_ack")).as("Unacknowledged offloads on replica %s", replica.pid())
                .isEqualTo(new JsonPrimitive(unacknowledged));
    }

    @FunctionalInterface
    private interface OffloadScenario {
        void run(OffloadPair pair) throws Exception;
    }

    private record OffloadPair(HttpClient client, URI seed, URI peer, Process peerProcess,
                               ObservationServer.Connection seedObservation, int seedMeshPort, Path jar,
                               BlockingQueue<String> arrivals, CountDownLatch release) {
        private CompletableFuture<HttpResponse<String>> request(final URI origin, final String name) {
            final JsonObject body = new JsonObject();
            body.addProperty("request", name);
            return request(origin, body);
        }

        private CompletableFuture<HttpResponse<String>> request(final URI origin, final JsonObject body) {
            return client.sendAsync(HttpRequest.newBuilder(origin).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void startsWithMeshInactiveWhenConfigurationIsAbsent() throws Exception {
        final Path jar = build(project("activation"), "--mesh");
        final Process disabled = launch(jar, "disabled", null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            final ObservationServer.Connection app = connection(disabled, "disabled");
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(app.origin().resolve("/v1/status"))
                    .header("Authorization", "Bearer " + app.token()).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            final HttpResponse<String> catalog = client.send(HttpRequest.newBuilder(app.origin().resolve("/v1/metrics/catalog"))
                    .header("Authorization", "Bearer " + app.token()).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(catalog.statusCode()).isEqualTo(200);
            assertThat(json(catalog.body()).getAsJsonObject("metrics").asMap())
                    .doesNotContainKeys("offload_ack", "offload_no_ack");
        } finally {
            stop(disabled);
        }
    }

    @Test
    void rejectsAMissingMeshConfigurationBeforeStartup() throws Exception {
        final Path jar = build(project("missing-configuration"), "--mesh");
        assertInvalidStartup(jar, directory.resolve("missing-mesh-config.json"), "missing-mesh-config.json");
    }

    @Test
    void rejectsAUrlAsAnAdvertisedHost() throws Exception {
        final Path jar = build(project("invalid-advertised-host"), "--mesh");
        final Path config = configurations().getFirst();
        final JsonObject settings = json(Files.readString(config));
        settings.getAsJsonObject("advertisedEndpoint").addProperty("host", "https://127.0.0.1:7401/");
        Files.writeString(config, settings.toString());
        assertInvalidStartup(jar, config, "advertisedEndpoint requires a host");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsANamedPipeAsTheStartupConfiguration() throws Exception {
        final Path jar = build(project("invalid-config-type"), "--mesh");
        assertInvalidStartup(jar, fifo("mesh-config"), "requires a regular file");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void timesOutAnUnresponsiveListenHostLookupBeforeStartup() throws Exception {
        final Path jar = build(project("blocked-listen"), "--mesh");
        final Path seedConfig = configurations().getFirst();
        final JsonObject document = json(Files.readString(seedConfig));
        document.getAsJsonObject("listenEndpoint").addProperty("host", "listen.railix.test");
        Files.writeString(seedConfig, document.toString());

        final Path hosts = fifo("hosts");
        try (FileChannel heldOpen = FileChannel.open(hosts, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertInvalidStartup(jar, seedConfig, "Mesh hostname lookup exceeded its deadline.", "-Djdk.net.hosts.file=" + hosts);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void boundsABlockedBootstrapLookupAndStillAcceptsAnInboundPeer() throws Exception {
        final Path jar = build(project("blocked-lookup"), "--mesh");
        final List<Path> configurations = configurations();
        final Path seedConfig = configurations.getFirst();
        final JsonObject document = json(Files.readString(seedConfig));
        final JsonArray bootstrap = document.getAsJsonArray("bootstrapEndpoints");
        bootstrap.add(json("{\"host\":\"first.railix.test\",\"port\":7401}"));
        bootstrap.add(json("{\"host\":\"second.railix.test\",\"port\":7401}"));
        Files.writeString(seedConfig, document.toString());

        final Path hosts = fifo("hosts");
        // Keep the resolver's input open without delivering any data or EOF.
        try (FileChannel heldOpen = FileChannel.open(hosts, StandardOpenOption.READ, StandardOpenOption.WRITE);
             HttpClient client = HttpClient.newHttpClient()) {
            final long launchedAt = System.nanoTime();
            final Process seed = launch(jar, "seed", seedConfig, "-Djdk.net.hosts.file=" + hosts);
            try {
                final ObservationServer.Connection seedConnection = connection(seed, "seed");
                await(() -> assertThat(status(client, seedConnection).get("rejection").getAsString()).isEqualTo("join-timeout"));
                final Duration elapsed = Duration.ofNanos(System.nanoTime() - launchedAt);
                assertThat(elapsed).as("Time from launch to the first reported join timeout")
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
                final Process peer = launch(jar, "peer", configurations.getLast());
                try {
                    awaitPeersJoined(client, seedConnection, connection(peer, "peer"));
                } finally {
                    stop(peer);
                }
            } finally {
                stop(seed);
            }
        }
    }

    @Test
    void joinsThroughTheSecondAddressOfABootstrapHostname() throws Exception {
        final Path jar = build(project("bootstrap-address-fallback"), "--mesh");
        final List<Path> configurations = configurations();
        final Path peerConfig = configurations.getLast();
        final JsonObject document = json(Files.readString(peerConfig));
        document.getAsJsonArray("bootstrapEndpoints").get(0).getAsJsonObject().addProperty("host", "bücher.railix.test");
        Files.writeString(peerConfig, document.toString());
        final Path hosts = Files.writeString(directory.resolve("hosts"), """
                127.0.0.2 xn--bcher-kva.railix.test
                127.0.0.1 xn--bcher-kva.railix.test
                """);

        final Process seed = launch(jar, "seed", configurations.getFirst());
        try (HttpClient client = HttpClient.newHttpClient()) {
            final ObservationServer.Connection seedConnection = connection(seed, "seed");
            final Process peer = launch(jar, "peer", peerConfig, "-Djdk.net.hosts.file=" + hosts);
            try {
                awaitPeersJoined(client, seedConnection, connection(peer, "peer"));
            } finally {
                stop(peer);
            }
        } finally {
            stop(seed);
        }
    }

    @Test
    void rejectsProjectCoreAndMeshBuildDifferencesWithoutSharingState() throws Exception {
        final Path project = project("baseline");
        final Path baseline = build(project, "--mesh");
        final List<Map.Entry<String, Path>> variants = List.of(
                Map.entry("project", build(project("different-project"), "--mesh")),
                Map.entry("core", build(cliWithDifferentCore(), project, "--mesh")),
                Map.entry("mesh", build(cliWithDifferentMesh(), project, "--mesh")));
        final List<Path> configurations = configurations();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (final Map.Entry<String, Path> variant : variants) {
                final String name = "seed-" + variant.getKey();
                final Process seed = launch(baseline, name, configurations.getFirst());
                try {
                    assertBuildMismatch(client, connection(seed, name), configurations.getLast(), variant);
                } finally {
                    stop(seed);
                }
            }
        }
    }

    private Path cliWithDifferentMesh() throws Exception {
        final Path cli = Files.copy(CLI, directory.resolve("changed-mesh-cli.jar"));
        final String bundle = "META-INF/railix/railix-mesh.jar";
        final Path bundledMesh = directory.resolve(bundle);
        Files.createDirectories(bundledMesh.getParent());
        try (JarFile jar = new JarFile(cli.toFile()); InputStream input = jar.getInputStream(jar.getJarEntry(bundle))) {
            Files.copy(input, bundledMesh);
        }
        changeClass(bundledMesh, "dev/nanonative/railix/mesh/peer/PeerProtocol.class");
        replaceEntry(cli, directory, bundle);
        return cli;
    }

    private void assertBuildMismatch(final HttpClient client, final ObservationServer.Connection seedConnection,
                                     final Path configuration, final Map.Entry<String, Path> variant) throws Exception {
        final String name = "peer-" + variant.getKey();
        final Process peer = launch(variant.getValue(), name, configuration);
        try {
            final ObservationServer.Connection peerConnection = connection(peer, name);
            await(() -> {
                final JsonObject seedStatus = status(client, seedConnection);
                final JsonObject peerStatus = status(client, peerConnection);
                assertThat(peerStatus.get("logicalBuildId")).as(name).isNotEqualTo(seedStatus.get("logicalBuildId"));
                for (final JsonObject view : List.of(seedStatus, peerStatus)) {
                    assertThat(view.get("rejection").getAsString()).as(name).isEqualTo("build-mismatch");
                    assertThat(view.get("peer").isJsonNull()).as(name).isTrue();
                }
            });
        } finally {
            stop(peer);
        }
    }

    private static void awaitPeersJoined(final HttpClient client, final ObservationServer.Connection seed,
                                         final ObservationServer.Connection peer) throws Exception {
        awaitPeersJoined(client, seed, peer, 2);
    }

    private static void awaitPeersJoined(final HttpClient client, final ObservationServer.Connection seed,
                                         final ObservationServer.Connection peer, final int capacity) throws Exception {
        await(() -> {
            assertReady(status(client, seed), PEER, capacity);
            assertReady(status(client, peer), SEED, capacity);
        });
    }

    private static void assertReady(final JsonObject status, final UUID remote) {
        assertReady(status, remote, 2);
    }

    private static void assertReady(final JsonObject status, final UUID remote, final int capacity) {
        assertThat(status.get("status").getAsString()).isEqualTo("joined");
        final JsonObject peer = status.getAsJsonObject("peer");
        assertThat(peer.get("replicaId").getAsString()).isEqualTo(remote.toString());
        assertThat(peer.get("status").getAsString()).isEqualTo("ready");
        assertThat(peer.get("availableFlowSlots").getAsInt()).isEqualTo(capacity);
    }

    private void assertInvalidStartup(final Path jar, final Path path, final String diagnostic, final String... jvmOptions)
            throws Exception {
        final Process invalid = launch(jar, "invalid", path, jvmOptions);
        try {
            assertThat(invalid.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(invalid.exitValue()).isEqualTo(1);
            assertThat(Files.readString(directory.resolve("invalid.log"))).contains("MESH_STARTUP_FAILED", diagnostic);
            assertThat(ObservationServer.connections(home().resolve(".railix"))).isEmpty();
        } finally {
            if (invalid.isAlive()) {
                stop(invalid);
            }
        }
    }

    private Path project(final String id) throws Exception {
        final Path path = Files.createDirectories(directory.resolve(id)).resolve("railix.project.json");
        Files.writeString(path, """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"http","use":"railix.trigger.http","inputs":{"port":0},
                   "examples":[{"name":"request","payload":{}}]}
                ],"links":[{"from":"app.start","to":"http"},{"from":"http.next","to":"end"}]}
                """.formatted(id));
        return path;
    }

    private Path build(final Path project, final String... flags) throws Exception {
        return build(CLI, project, flags);
    }

    private Path build(final Path cli, final Path project, final String... flags) throws Exception {
        final ArrayList<String> command = new ArrayList<>(List.of(BIN.resolve("java").toString(), "-Duser.home=" + home(),
                "-jar", cli.toString(), "build", project.toString()));
        command.addAll(List.of(flags));
        String output = run(command, directory, directory.resolve("build.log"));
        return Path.of(output.lines().toList().getLast());
    }

    private Path cliWithDifferentCore() throws Exception {
        final Path cli = Files.copy(CLI, directory.resolve("changed-core-cli.jar"));
        changeClass(cli, "dev/nanonative/railix/core/project/WorkflowRuntime.class");
        return cli;
    }

    private void changeClass(final Path artifact, final String entry) throws Exception {
        final Path classes = directory.resolve("classes");
        final Path runtimeClass = classes.resolve(entry);
        Files.createDirectories(runtimeClass.getParent());
        try (JarFile jar = new JarFile(artifact.toFile()); InputStream input = jar.getInputStream(jar.getJarEntry(entry))) {
            final ClassFile classFile = ClassFile.of();
            final byte[] changed = classFile.transformClass(classFile.parse(input.readAllBytes()),
                    ClassTransform.endHandler(builder -> builder.withField("MESH_BUILD_TEST_MARKER",
                            ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC)));
            Files.write(runtimeClass, changed);
        }
        replaceEntry(artifact, classes, entry);
    }

    private void replaceEntry(final Path artifact, final Path root, final String entry) throws Exception {
        run(List.of(BIN.resolve("jar").toString(), "--update", "--file", artifact.toString(),
                "-C", root.toString(), entry), directory, directory.resolve("jar-update.log"));
    }

    private List<Path> configurations() throws Exception {
        try (ServerSocket seed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final String target = endpoint(seed.getLocalPort());
            return List.of(config("seed", seed.getLocalPort(), ""), config("peer", peer.getLocalPort(), target));
        }
    }

    private Path config(final String name, final int port, final String bootstrap) throws Exception {
        final String endpoint = endpoint(port);
        final JsonObject document = json("""
                {"meshId":"%s","listenEndpoint":%s,"advertisedEndpoint":%s,"bootstrapEndpoints":[%s],"flowCapacity":2}
                """.formatted(MESH, endpoint, endpoint, bootstrap));
        document.add("identityStore", store(name));
        document.add("trustStore", store("trust"));
        return Files.writeString(directory.resolve(name + ".json"), document.toString());
    }

    private static JsonObject store(final String name) {
        final JsonObject store = new JsonObject();
        store.addProperty("path", credentials.resolve(name + ".p12").toString());
        store.addProperty("passwordFile", credentials.resolve(name + ".password").toString());
        return store;
    }

    private static String endpoint(final int port) {
        return "{\"host\":\"127.0.0.1\",\"port\":" + port + "}";
    }

    private Path fifo(final String name) throws Exception {
        final Path fifo = directory.resolve(name);
        run(List.of("mkfifo", fifo.toString()), directory, directory.resolve("mkfifo.log"));
        return fifo;
    }

    private Process launch(final Path jar, final String name, final Path config, final String... jvmOptions) throws Exception {
        final ArrayList<String> command = new ArrayList<>(List.of(BIN.resolve("java").toString(), "-Duser.home=" + home()));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-jar", jar.toString()));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve(name + ".log").toFile());
        builder.environment().remove("RAILIX_MESH_CONFIG");
        if (config != null) builder.environment().put("RAILIX_MESH_CONFIG", config.toString());
        return RailixPackageIT.instrumentJava(builder).start();
    }

    private Path home() {
        return directory.resolve("home");
    }

    private ObservationServer.Connection connection(final Process process, final String name) throws Exception {
        await(() -> {
            assertThat(process.isAlive()).as(Files.readString(directory.resolve(name + ".log"))).isTrue();
            assertThat(ObservationServer.connections(home().resolve(".railix"))).anyMatch(found -> found.pid() == process.pid());
        });
        return ObservationServer.connections(home().resolve(".railix")).stream()
                .filter(found -> found.pid() == process.pid()).findFirst().orElseThrow();
    }

    private static JsonObject status(final HttpClient client, final ObservationServer.Connection connection) throws Exception {
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(connection.origin().resolve("/v1/status"))
                .header("Authorization", "Bearer " + connection.token()).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response.body()).getAsJsonObject("mesh");
    }

    private static JsonObject json(final String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void issue(final String name, final UUID replica) throws Exception {
        keytool(name, "-genkeypair", "-alias", "peer", "-dname", "CN=" + name, "-keyalg", "EC");
        keytool(name, "-certreq", "-alias", "peer", "-file", name + ".csr");
        keytool("issuer", "-gencert", "-alias", "root", "-infile", name + ".csr", "-outfile", name + ".cer",
                "-ext", "san=uri:https://github.com/NanoNative/nano_railix/mesh/v1/" + MESH + "/replicas/" + replica,
                "-ext", "eku=serverAuth,clientAuth");
        keytool(name, "-importcert", "-noprompt", "-alias", "root", "-file", "root.cer");
        keytool(name, "-importcert", "-noprompt", "-alias", "peer", "-file", name + ".cer");
    }

    private static void keytool(final String store, final String... arguments) throws Exception {
        final ArrayList<String> command = new ArrayList<>(List.of(BIN.resolve("keytool").toString(), "-storetype", "PKCS12",
                "-keystore", store + ".p12", "-storepass:file", store + ".password"));
        command.addAll(List.of(arguments));
        run(command, credentials, credentials.resolve("keytool.log"));
    }

    private static String run(final List<String> command, final Path cwd, final Path log) throws Exception {
        final Process process = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as(command.toString()).isTrue();
            final String output = Files.readString(log).strip();
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) stop(process);
        }
    }

    private static void assertPeerGroup(final HttpClient client, final List<ObservationServer.Connection> observations,
                                        final List<UUID> replicas) throws Exception {
        final List<JsonObject> statuses = new ArrayList<>();
        for (final ObservationServer.Connection observation : observations) {
            final JsonObject mesh = status(client, observation);
            assertThat(mesh.get("status").getAsString()).isEqualTo("joined");
            statuses.add(mesh);
        }
        for (int index = 0; index < replicas.size(); index++) {
            final UUID local = replicas.get(index);
            final List<String> expected = replicas.stream().filter(replica -> !replica.equals(local))
                .map(UUID::toString).toList();
            final JsonArray peers = statuses.get(index).getAsJsonArray("peers");
            assertThat(peers).as("peers of %s", local).isNotNull();
            final List<JsonObject> reported = peers.asList().stream().map(value -> value.getAsJsonObject()).toList();
            assertThat(reported).allSatisfy(peer -> assertThat(peer.get("status").getAsString()).isEqualTo("ready"));
            assertThat(reported).extracting(peer -> peer.get("replicaId").getAsString())
                .containsExactlyInAnyOrderElementsOf(expected);
        }
    }
}
