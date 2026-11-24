package dev.nanonative.railix.creator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nanonative.railix.core.runtime.ObservationServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ConstantDescs;
import java.net.InetAddress;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private static final UUID PEER = UUID.randomUUID();

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
                }
                assertThat(seed.isAlive()).isTrue();
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
    void startsWithMeshInactiveWhenConfigurationIsAbsent() throws Exception {
        final Path jar = build(project("activation"), "--mesh");
        final Process disabled = launch(jar, "disabled", null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            final ObservationServer.Connection app = connection(disabled, "disabled");
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(app.origin().resolve("/v1/status"))
                    .header("Authorization", "Bearer " + app.token()).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
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
        final String host = "fallback.railix.test";
        final JsonObject document = json(Files.readString(peerConfig));
        document.getAsJsonArray("bootstrapEndpoints").get(0).getAsJsonObject().addProperty("host", host);
        Files.writeString(peerConfig, document.toString());
        final Path hosts = Files.writeString(directory.resolve("hosts"),
                "127.0.0.2 " + host + "\n127.0.0.1 " + host + "\n");

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
    void rejectsDifferentBuildsWithoutPublishingEitherPeersState() throws Exception {
        final Path firstJar = build(project("first"), "--mesh");
        final Path secondJar = build(project("second"), "--mesh");
        assertBuildMismatch(firstJar, secondJar);
    }

    @Test
    void rejectsDifferentPackagedCoreForTheSameProject() throws Exception {
        final Path project = project("runtime-identity");
        final Path firstJar = build(project, "--mesh");
        final Path secondJar = build(cliWithDifferentCore(), project, "--mesh");
        assertBuildMismatch(firstJar, secondJar);
    }

    private void assertBuildMismatch(final Path firstJar, final Path secondJar) throws Exception {
        final List<Path> configurations = configurations();
        final Process seed = launch(firstJar, "seed", configurations.getFirst());
        try {
            final Process peer = launch(secondJar, "peer", configurations.getLast());
            try (HttpClient client = HttpClient.newHttpClient()) {
                final ObservationServer.Connection seedConnection = connection(seed, "seed");
                final ObservationServer.Connection peerConnection = connection(peer, "peer");
                await(() -> {
                    final JsonObject seedStatus = status(client, seedConnection);
                    final JsonObject peerStatus = status(client, peerConnection);
                    assertThat(seedStatus.get("logicalBuildId")).isNotEqualTo(peerStatus.get("logicalBuildId"));
                    for (final JsonObject view : List.of(seedStatus, peerStatus)) {
                        assertThat(view.get("rejection").getAsString()).isEqualTo("build-mismatch");
                        assertThat(view.get("peer").isJsonNull()).isTrue();
                    }
                });
            } finally {
                stop(peer);
            }
        } finally {
            stop(seed);
        }
    }

    private static void awaitPeersJoined(final HttpClient client, final ObservationServer.Connection seed,
                                         final ObservationServer.Connection peer) throws Exception {
        await(() -> {
            assertReady(status(client, seed), PEER);
            assertReady(status(client, peer), SEED);
        });
    }

    private static void assertReady(final JsonObject status, final UUID remote) {
        assertThat(status.get("status").getAsString()).isEqualTo("joined");
        final JsonObject peer = status.getAsJsonObject("peer");
        assertThat(peer.get("replicaId").getAsString()).isEqualTo(remote.toString());
        assertThat(peer.get("status").getAsString()).isEqualTo("ready");
        assertThat(peer.get("availableFlowSlots").getAsInt()).isEqualTo(2);
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
        final String entry = "dev/nanonative/railix/core/project/WorkflowRuntime.class";
        final Path cli = Files.copy(CLI, directory.resolve("changed-core-cli.jar"));
        final Path classes = directory.resolve("classes");
        final Path runtimeClass = classes.resolve(entry);
        Files.createDirectories(runtimeClass.getParent());
        try (JarFile jar = new JarFile(cli.toFile()); InputStream input = jar.getInputStream(jar.getJarEntry(entry))) {
            final ClassFile classFile = ClassFile.of();
            final byte[] changed = classFile.transformClass(classFile.parse(input.readAllBytes()),
                    ClassTransform.endHandler(builder -> builder.withField("MESH_BUILD_TEST_MARKER",
                            ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC)));
            Files.write(runtimeClass, changed);
        }
        run(List.of(BIN.resolve("jar").toString(), "--update", "--file", cli.toString(),
                "-C", classes.toString(), entry), directory, directory.resolve("core-variant.log"));
        return cli;
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
