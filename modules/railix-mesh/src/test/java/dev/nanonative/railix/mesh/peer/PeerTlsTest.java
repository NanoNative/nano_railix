package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshRuntime;
import dev.nanonative.railix.mesh.PeerEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class PeerTlsTest {
    private static final UUID MESH_ID = UUID.randomUUID();
    private static final UUID OTHER_MESH = UUID.randomUUID();
    private static final UUID SERVER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CLIENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String TRUST_PASSWORD = UUID.randomUUID().toString();

    @TempDir
    static Path credentials;

    @BeforeAll
    static void issueCertificates() throws Exception {
        writePassword(passwordFile("server"), PASSWORD);
        writePassword(passwordFile("trust"), TRUST_PASSWORD);
        keytool("issuer", "-genkeypair", "-alias", "root", "-dname", "CN=Railix test issuer",
                "-keyalg", "EC", "-groupname", "secp256r1", "-ext", "bc:c=ca:true",
                "-ext", "ku:c=keyCertSign,cRLSign", "-validity", "30");
        keytool("issuer", "-exportcert", "-alias", "root", "-file", "root.cer");
        keytool("trust", "-importcert", "-noprompt", "-alias", "root", "-file", "root.cer");
        issue("server", MESH_ID, SERVER_ID);
        issue("client", MESH_ID, CLIENT_ID);
        issue("other-mesh", OTHER_MESH, UUID.randomUUID());
        keytool("untrusted", "-genkeypair", "-alias", "peer", "-dname", "CN=Untrusted Railix peer",
                "-keyalg", "EC", "-groupname", "secp256r1", "-ext", identity(MESH_ID, UUID.randomUUID()),
                "-ext", "eku=serverAuth,clientAuth");
    }

    @Test
    void letsTheReceiverAdmitWorkWhenItsAdvertisedCapacityIsZero() throws Exception {
        final InetSocketAddress address;
        try (ServerSocket port = listener()) {
            address = endpoint(port);
        }
        final String build = "sha256-" + "a".repeat(64);
        final RailixValue.ObjectValue input = RailixValue.object(Map.of());
        final RunResult completed = new RunResult.Succeeded(input);
        final Path config = meshConfig(address.getPort(), "server", List.of(), 1, Optional.of("http"));
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
             PeerSession receiver = PeerSession.open(MESH_ID, build, new PeerEndpoint("127.0.0.1", 7401),
                     1, Optional.of("http"), store("client"), passwordFile("client"),
                     store("trust"), passwordFile("trust"));
             MeshRuntime ingress = MeshRuntime.open(config.toString(), build, "http"::equals,
                     ignored -> Optional.empty()).orElseThrow()) {
            receiver.updateState("ready", 0);
            receiver.setFlowAdmission(ignored -> Optional.of(new AdmittedFlow(() -> Optional.of(completed), () -> {})));
            ingress.start();
            threads.submit(() -> receiver.dial(address, deadline(), ignored -> {}, () -> true,
                    ignored -> true, ignored -> {}));

            final RailixValue.ObjectValue peer = awaitObservedPeer(ingress);
            assertEquals(RailixValue.number(0), peer.values().get("availableFlowSlots"));

            final CountDownLatch localStarted = new CountDownLatch(1);
            final CompletableFuture<RunResult> finishLocal = new CompletableFuture<>();
            final Future<RunResult> local = threads.submit(() -> ingress.place("http", 0, () -> input, () -> {
                localStarted.countDown();
                return finishLocal.join();
            }, ignored -> {}));
            try {
                assertTrue(localStarted.await(2, SECONDS));
                final RunResult offloaded = ingress.place("http", 0, () -> input,
                        () -> { throw new AssertionError("Local slot was already taken."); }, ignored -> {});
                assertEquals(completed, offloaded);
            } finally {
                finishLocal.complete(completed);
                local.get(2, SECONDS);
            }
        }
    }

    private static List<RailixValue> observedPeers(final MeshRuntime runtime) {
        return assertInstanceOf(RailixValue.ArrayValue.class, runtime.status().values().get("peers")).values();
    }

    @Test
    void duplicateConnectionLeavesAnInFlightOffloadOnItsOriginalSocket() throws Exception {
        final InetSocketAddress address;
        try (ServerSocket listener = listener()) {
            address = endpoint(listener);
        }
        final String build = "sha256-" + "a".repeat(64);
        final UUID incarnation = UUID.randomUUID();
        final PeerTls client = tls("client");
        final Path config = meshConfig(address.getPort(), "server", List.of(), 1, Optional.of("http"));
        final RailixValue.ObjectValue input = RailixValue.object(Map.of());
        final RunResult completed = new RunResult.Succeeded(input);
        final CompletableFuture<RunResult> finishLocal = new CompletableFuture<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
             MeshRuntime ingress = MeshRuntime.open(config.toString(), build, "http"::equals,
                     ignored -> Optional.empty()).orElseThrow()) {
            ingress.start();
            try (PeerTlsConnection original = client.dial(address, deadline())) {
                exchangeClientHello(original.channel(), build, incarnation);
                assertTrue(PeerProtocol.read(original.channel()).filter(PeerProtocol::validState).isPresent());
                PeerProtocol.write(original.channel(), PeerProtocol.state(new PeerEndpoint("127.0.0.1", 7401),
                        1, "ready", RailixValue.string("http")));
                awaitObservedPeer(ingress);

                final CountDownLatch localStarted = new CountDownLatch(1);
                final Future<RunResult> local = threads.submit(() -> ingress.place("http", 0, () -> input, () -> {
                    localStarted.countDown();
                    return finishLocal.join();
                }, ignored -> {}));
                try {
                    assertTrue(localStarted.await(2, SECONDS));
                    final Future<RunResult> offloaded = threads.submit(() -> ingress.place("http", 0, () -> input,
                            () -> { throw new AssertionError("Local slot was already taken."); }, ignored -> {}));
                    RailixValue.ObjectValue invocation;
                    do {
                        invocation = PeerProtocol.read(original.channel()).orElseThrow();
                    } while (PeerProtocol.validState(invocation));
                    assertEquals(RailixValue.string("invoke"), invocation.values().get("type"));

                    try (PeerTlsConnection duplicate = client.dial(address, deadline())) {
                        exchangeClientHello(duplicate.channel(), build, incarnation);
                        assertEquals(FrameReadStatus.END_OF_STREAM, duplicate.channel().read(
                                PeerFrames.MAX_CONTROL_BODY_BYTES, byte[]::new));
                    }
                    final String id = PeerProtocol.text(invocation.values().get("id"));
                    PeerProtocol.write(original.channel(), FlowWire.encodeOutcome(id, completed));
                    assertEquals(completed, offloaded.get(2, SECONDS));
                    assertEquals(1, observedPeers(ingress).size());
                } finally {
                    finishLocal.complete(completed);
                    local.get(2, SECONDS);
                }
            }
        }
    }

    private static void exchangeClientHello(final PeerChannel channel, final String build, final UUID incarnation)
            throws IOException, InterruptedException {
        assertEquals(RailixValue.string("hello.v1"), PeerProtocol.read(channel).orElseThrow().values().get("type"));
        PeerProtocol.write(channel, PeerProtocol.hello(build, MESH_ID, CLIENT_ID, incarnation));
    }

    private static RailixValue.ObjectValue awaitObservedPeer(final MeshRuntime runtime) throws InterruptedException {
        final long joinedBy = deadline();
        List<RailixValue> peers = observedPeers(runtime);
        while (peers.isEmpty() && joinedBy - System.nanoTime() > 0) {
            Thread.sleep(Duration.ofMillis(10));
            peers = observedPeers(runtime);
        }
        assertEquals(1, peers.size());
        return assertInstanceOf(RailixValue.ObjectValue.class, peers.getFirst());
    }

    @Test
    void crossedDialsReserveOneSocketAndRetryWhenItsConfirmationFails() throws Exception {
        final InetSocketAddress address;
        try (ServerSocket listener = listener()) {
            address = endpoint(listener);
        }
        final String build = "sha256-" + "a".repeat(64);
        final UUID incarnation = UUID.randomUUID();
        final PeerTls client = tls("client");
        try (ServerSocket seedListener = listener()) {
            final PeerEndpoint seed = new PeerEndpoint("127.0.0.1", seedListener.getLocalPort());
            final Path config = meshConfig(address.getPort(), "server", List.of(seed.value()));
            try (MeshRuntime runtime = openMembership(config, build)) {
                runtime.start();
                try (PeerTlsConnection first = accept(client, seedListener)) {
                    receiveProposal(first.channel(), build, incarnation);
                    assertTrue(observedPeers(runtime).isEmpty());
                    try (PeerTlsConnection crossed = client.dial(address, deadline())) {
                        exchangeClientHello(crossed.channel(), build, incarnation);
                        assertEquals(FrameReadStatus.END_OF_STREAM, crossed.channel().read(
                                PeerFrames.MAX_CONTROL_BODY_BYTES, byte[]::new));
                    }
                }

                try (PeerTlsConnection retry = accept(client, seedListener)) {
                    receiveProposal(retry.channel(), build, incarnation);
                    PeerProtocol.write(retry.channel(), PeerProtocol.state(seed, 2, "ready", RailixValue.nullValue()));
                    assertEquals(RailixValue.string(CLIENT_ID.toString()),
                            awaitObservedPeer(runtime).values().get("replicaId"));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(CrossedDialOrder.class)
    void crossedDialRetriesWithoutBackoffAfterTheRetainedConnectionReceivesAHeartbeat(
            final CrossedDialOrder order) throws Exception {
        final boolean loserClosesBeforeConfirmation = order != CrossedDialOrder.WINNER_CONFIRMS_FIRST;
        final boolean winnerClosesDuringBackoff = order == CrossedDialOrder.WINNER_CLOSES_DURING_BACKOFF;
        final InetSocketAddress address;
        try (ServerSocket listener = listener()) {
            address = endpoint(listener);
        }
        final String build = "sha256-" + "a".repeat(64);
        final byte[] hello = PeerProtocol.hello(build, MESH_ID, SERVER_ID, UUID.randomUUID());
        final PeerTls server = tls("server");
        try (ServerSocket seedListener = listener()) {
            final PeerEndpoint seed = new PeerEndpoint("127.0.0.1", seedListener.getLocalPort());
            final Path config = meshConfig(address.getPort(), "client", List.of(seed.value()));
            try (MeshRuntime runtime = openMembership(config, build)) {
                runtime.start();
                if (winnerClosesDuringBackoff) {
                    try (Socket failed = seedListener.accept()) {
                        // One failed dial gives the next backoff a minimum of one second.
                    }
                }
                try (PeerTlsConnection losing = accept(server, seedListener)) {
                    PeerProtocol.read(losing.channel()).orElseThrow();
                    PeerProtocol.write(losing.channel(), hello);

                    final long disconnectedAt;
                    try (PeerTlsConnection retained = server.dial(address, deadline())) {
                        final PeerChannel channel = retained.channel();
                        PeerProtocol.read(channel).orElseThrow();
                        PeerProtocol.write(channel, hello);
                        if (loserClosesBeforeConfirmation) {
                            losing.close();
                            // Delay the proposal while the losing dial enters its first backoff.
                            Thread.sleep(Duration.ofMillis(250));
                        }
                        PeerProtocol.write(channel, PeerProtocol.state(seed, 2, "ready", RailixValue.nullValue()));
                        assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
                        RailixValue.ObjectValue peer = awaitObservedPeer(runtime);
                        if (!loserClosesBeforeConfirmation) {
                            losing.close();
                        }

                        final RailixValue.NumberValue available = RailixValue.number(1);
                        PeerProtocol.write(channel, PeerProtocol.state(seed, 1, "ready", RailixValue.nullValue()));
                        final long heartbeatBy = deadline();
                        while (!available.equals(peer.values().get("availableFlowSlots"))
                                && heartbeatBy - System.nanoTime() > 0) {
                            Thread.sleep(Duration.ofMillis(10));
                            peer = awaitObservedPeer(runtime);
                        }
                        assertEquals(available, peer.values().get("availableFlowSlots"));

                        if (!winnerClosesDuringBackoff) {
                            // A losing dial must stay parked even past the full first retry interval.
                            seedListener.setSoTimeout(1_100);
                            assertThrows(SocketTimeoutException.class, () -> {
                                try (Socket retry = seedListener.accept()) {
                                    throw new AssertionError("Redialed while the retained socket was connected.");
                                }
                            });
                        }
                        disconnectedAt = System.nanoTime();
                    }
                    seedListener.setSoTimeout(5_000);
                    try (Socket retry = seedListener.accept()) {
                        final Duration elapsed = Duration.ofNanos(System.nanoTime() - disconnectedAt);
                        assertTrue(elapsed.compareTo(Duration.ofMillis(500)) < 0,
                                () -> "Redial still waited for failure backoff: " + elapsed);
                    }
                }
            }
        }
    }

    private enum CrossedDialOrder {
        WINNER_CONFIRMS_FIRST,
        LOSER_CLOSES_FIRST,
        WINNER_CLOSES_DURING_BACKOFF
    }

    private static void receiveProposal(final PeerChannel channel, final String build, final UUID incarnation)
            throws IOException, InterruptedException {
        exchangeClientHello(channel, build, incarnation);
        assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
    }

    @Test
    void mismatchedBuildClosesBeforeSendingState() throws Exception {
        final InetSocketAddress endpoint;
        try (ServerSocket port = listener()) {
            endpoint = endpoint(port);
        }
        final Path config = meshConfig(endpoint.getPort(), "server", List.of());
        try (MeshRuntime runtime = openMembership(config, "sha256-" + "a".repeat(64))) {
            runtime.start();
            try (PeerTlsConnection peer = tls("client").dial(endpoint, deadline())) {
                final PeerChannel channel = peer.channel();
                final RailixValue.ObjectValue opening = PeerProtocol.read(channel).orElseThrow();
                assertEquals(RailixValue.string("hello.v1"), opening.values().get("type"));
                final byte[] differentBuild = PeerProtocol.hello("sha256-" + "b".repeat(64), MESH_ID,
                        CLIENT_ID, UUID.randomUUID());
                PeerProtocol.write(channel, differentBuild);
                assertEquals(FrameReadStatus.END_OF_STREAM,
                        channel.read(PeerFrames.MAX_CONTROL_BODY_BYTES, byte[]::new));
                assertEquals(RailixValue.string("build-mismatch"), runtime.status().values().get("rejection"));
            }
        }
    }

    @Test
    void seedAliasesShareBackoffWhenSeedClosesBeforeHeartbeat() throws Exception {
        final int localPort;
        try (ServerSocket port = listener()) {
            localPort = port.getLocalPort();
        }
        final String build = "sha256-" + "a".repeat(64);
        final PeerTls seed = tls("server");
        try (ServerSocket seedListener = listener()) {
            final int seedPort = seedListener.getLocalPort();
            final PeerEndpoint seedEndpoint = new PeerEndpoint("127.0.0.1", seedPort);
            final PeerEndpoint seedAlias = new PeerEndpoint("::ffff:127.0.0.1", seedPort);
            final List<RailixValue> bootstrap = List.of(seedEndpoint.value(), seedAlias.value());
            final Path config = meshConfig(localPort, "client", bootstrap);
            try (MeshRuntime runtime = openMembership(config, build)) {
                runtime.start();
                final long initialStateSent;
                try (PeerTlsConnection connection = accept(seed, seedListener)) {
                    final PeerChannel channel = connection.channel();
                    assertTrue(PeerProtocol.read(channel).isPresent());
                    PeerProtocol.write(channel, PeerProtocol.hello(build, MESH_ID,
                            SERVER_ID, UUID.randomUUID()));
                    PeerProtocol.write(channel, PeerProtocol.state(seedEndpoint, 2, "ready", RailixValue.nullValue()));
                    assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
                    initialStateSent = System.nanoTime();
                }
                try (Socket retry = seedListener.accept()) {
                    final Duration delay = Duration.ofNanos(System.nanoTime() - initialStateSent);
                    assertTrue(delay.compareTo(Duration.ofMillis(500)) >= 0,
                            () -> "Seed was retried after only " + delay);
                }
            }
        }
    }

    @Test
    void healthyPeerDisconnectPreservesTheLatestRejection() throws Exception {
        final InetSocketAddress address;
        try (ServerSocket port = listener()) {
            address = endpoint(port);
        }
        final String build = "sha256-" + "a".repeat(64);
        final Path config = meshConfig(address.getPort(), "server", List.of());
        try (MeshRuntime runtime = openMembership(config, build)) {
            runtime.start();
            try (PeerTlsConnection healthy = tls("client").dial(address, deadline())) {
                final PeerChannel channel = healthy.channel();
                exchangeClientHello(channel, build, UUID.randomUUID());
                final PeerEndpoint advertised = new PeerEndpoint("127.0.0.1", healthy.transport().getLocalPort());
                assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
                PeerProtocol.write(channel, PeerProtocol.state(advertised, 2, "ready", RailixValue.nullValue()));
                assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());

                try (PeerTlsConnection wrongIdentity = tls("server").dial(address, deadline())) {
                    assertTrue(PeerProtocol.read(wrongIdentity.channel()).isPresent());
                    PeerProtocol.write(wrongIdentity.channel(), PeerProtocol.hello(build, MESH_ID, SERVER_ID,
                            UUID.randomUUID()));
                    assertTrue(PeerProtocol.read(wrongIdentity.channel()).isEmpty());
                }
                assertEquals(RailixValue.string("identity-mismatch"), runtime.status().values().get("rejection"));
            }
            final RailixValue.StringValue waiting = RailixValue.string("waiting");
            final long deadline = deadline();
            while (!waiting.equals(runtime.status().values().get("status")) && deadline - System.nanoTime() > 0) {
                Thread.sleep(Duration.ofMillis(10));
            }
            final Map<String, RailixValue> status = runtime.status().values();
            assertEquals(waiting, status.get("status"));
            assertEquals(RailixValue.string("identity-mismatch"), status.get("rejection"));
        }
    }

    @ParameterizedTest
    @EnumSource(RejectedClient.class)
    void inboundRejectsInvalidPeerBeforeOpeningAChannel(final RejectedClient credentials) throws Exception {
        final PeerTls server = tls("server");
        final SSLContext client = clientContext(credentials.identity);

        try (ServerSocket listener = listener(); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> incoming = threads.submit(() -> {
                try (Socket transport = listener.accept()) {
                    final SSLHandshakeException failure = assertThrows(SSLHandshakeException.class,
                            () -> server.accept(transport, deadline()));
                    assertTrue(transport.isClosed());
                    return failure.getMessage();
                }
            });
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.connect(endpoint(listener), 5_000);
                socket.setSoTimeout(5_000);
                try {
                    socket.startHandshake();
                    assertEquals(-1, socket.getInputStream().read());
                } catch (final IOException closed) {
                    assertFalse(closed instanceof SocketTimeoutException);
                }
            }
            final String reason = incoming.get(8, SECONDS);
            if (credentials == RejectedClient.OTHER_MESH) {
                assertEquals("Mesh certificate belongs to a different mesh.", reason);
            }
        }
    }

    @Test
    void outboundRejectsAnUntrustedServer() throws Exception {
        final PeerTls client = tls("client");
        final SSLContext untrusted = clientContext(Optional.of("untrusted"));
        try (ServerSocket listener = listener(); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<Object> incoming = threads.submit(() -> {
                try (Socket tcp = listener.accept();
                     SSLSocket socket = (SSLSocket) untrusted.getSocketFactory().createSocket(tcp, null, true)) {
                    socket.setSoTimeout(5_000);
                    socket.startHandshake();
                }
                return null;
            });
            assertThrows(SSLHandshakeException.class, () -> client.dial(endpoint(listener), deadline()));
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> incoming.get(8, SECONDS)).getCause());
        }
    }

    @Test
    void closesAnAcceptedSocketWhenTheJoinBudgetExpires() throws Exception {
        final PeerTls server = tls("server");
        try (ServerSocket listener = listener(); Socket remote = new Socket();
             ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<Object> incoming = threads.submit(() -> {
                try (Socket transport = listener.accept()) {
                    final long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                    final SocketTimeoutException timeout = assertThrows(SocketTimeoutException.class, () -> server.accept(transport, deadline));
                    assertInstanceOf(IOException.class, timeout.getCause());
                    assertTrue(transport.isClosed());
                }
                return null;
            });
            try {
                remote.connect(endpoint(listener), 5_000);
                incoming.get(4, SECONDS);
            } finally {
                incoming.cancel(true);
            }
        }
    }

    @Test
    void rejectsALocalIdentityForAnotherMesh() {
        final CertificateException failure = assertThrows(CertificateException.class, () -> tls("other-mesh"));
        assertEquals("Mesh certificate belongs to a different mesh.", failure.getMessage());
    }

    @Test
    void rejectsAnIdentityStoreWithoutAPrivateKey() {
        final CertificateException failure = assertThrows(CertificateException.class, () -> tls("trust"));
        assertEquals("Mesh identity store is missing its private-key identity.", failure.getMessage());
    }

    @Test
    void rejectsAPrivateKeyEntryInTheTrustStore() {
        final CertificateException failure = assertThrows(CertificateException.class, () ->
                PeerTls.load(MESH_ID, store("server"), passwordFile("server"), store("server"), passwordFile("server")));
        assertEquals("Mesh trust store must contain only trusted CA certificates.", failure.getMessage());
    }

    @Test
    void rejectsStartupWithoutACurrentTrustAnchor() throws Exception {
        final X509Certificate root = (X509Certificate) readCertificate("root.cer");
        final InstantSource expired = InstantSource.fixed(root.getNotAfter().toInstant().plusSeconds(1));
        final CertificateException failure = assertThrows(CertificateException.class, () ->
                PeerTls.load(MESH_ID, store("server"), passwordFile("server"),
                        store("trust"), passwordFile("trust"), expired));
        assertEquals("Mesh trust store has no currently valid CA certificate.", failure.getMessage());
    }

    @Test
    @Timeout(40)
    void aRunningReplicaStopsTrustingTheExpiredAnchorAndKeepsTheValidOne() throws Exception {
        final UUID replacementReplica = UUID.randomUUID();
        keytool("replacement-issuer", "-genkeypair", "-alias", "root", "-dname", "CN=Railix replacement issuer",
                "-keyalg", "EC", "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign", "-validity", "180");
        keytool("replacement-issuer", "-exportcert", "-alias", "root", "-file", "replacement.cer");
        keytool("rotating-trust", "-importcert", "-noprompt", "-alias", "root", "-file", "root.cer");
        keytool("rotating-trust", "-importcert", "-noprompt", "-alias", "replacement", "-file", "replacement.cer");
        issue("replacement-client", MESH_ID, replacementReplica, "eku=serverAuth,clientAuth",
                "replacement-issuer", "replacement.cer");

        final X509Certificate root = (X509Certificate) readCertificate("root.cer");
        final AtomicReference<Instant> now = new AtomicReference<>(InstantSource.system().instant());
        final PeerTls server = PeerTls.load(MESH_ID, store("server"), passwordFile("server"),
                store("rotating-trust"), passwordFile("rotating-trust"), now::get);
        final PeerTls client = tls("client");
        final PeerTls replacement = tls("replacement-client");
        try (ServerSocket listener = listener(); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            assertAuthenticated(server, client, listener, threads);

            now.set(root.getNotAfter().toInstant().plusSeconds(1));
            final Future<SSLHandshakeException> rejected = threads.submit(() -> assertThrows(SSLHandshakeException.class, () -> {
                try (PeerTlsConnection unexpected = accept(server, listener)) { }
            }));
            // An outbound TLS 1.3 handshake can complete before the server's rejection arrives.
            try {
                client.dial(endpoint(listener), deadline()).close();
            } catch (final IOException expected) {
                assertFalse(expected instanceof SocketTimeoutException);
            }
            rejected.get(5, SECONDS);
            assertAuthenticated(server, replacement, listener, threads);
        }
    }

    private static void assertAuthenticated(final PeerTls server, final PeerTls client, final ServerSocket listener,
                                            final ExecutorService threads) throws Exception {
        final Future<PeerTlsConnection> accepted = threads.submit(() -> accept(server, listener));
        try (PeerTlsConnection outgoing = client.dial(endpoint(listener), deadline());
             PeerTlsConnection incoming = accepted.get(5, SECONDS)) {
            assertEquals(server.replicaId(), outgoing.replicaId());
            assertEquals(client.replicaId(), incoming.replicaId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"eku=clientAuth", "eku=serverAuth", "ku=keyEncipherment"})
    void rejectsLocalCertificatesWithoutBothTlsRoles(final String usage) throws Exception {
        final String alias = "restricted-" + UUID.randomUUID();
        issue(alias, MESH_ID, UUID.randomUUID(), usage);

        assertThrows(CertificateException.class, () -> tls(alias));
    }

    @Test
    void rejectsLocalPrivateKeyThatDoesNotMatchCertificate() throws Exception {
        final char[] password = PASSWORD.toCharArray();
        try {
            final Key privateKey = readStore("client", password).getKey("peer", password);
            final Certificate[] certificateChain = readStore("server", password).getCertificateChain("peer");
            writeIdentity("mismatched-key", privateKey, password, certificateChain);
        } finally {
            Arrays.fill(password, '\0');
        }

        assertThrows(CertificateException.class, () -> tls("mismatched-key"));
    }

    @Test
    void rejectsAnExpiredIntermediateWhenLoadingCredentials() throws Exception {
        keytool("intermediate", "-genkeypair", "-alias", "ca", "-dname", "CN=Railix test intermediate",
                "-keyalg", "EC", "-groupname", "secp256r1", "-ext", "bc:c=ca:true");
        keytool("intermediate", "-certreq", "-alias", "ca", "-file", "intermediate.csr");
        keytool("issuer", "-gencert", "-alias", "root", "-infile", "intermediate.csr",
                "-outfile", "intermediate.cer", "-startdate", "-2d", "-validity", "1",
                "-ext", "bc:c=ca:true", "-ext", "ku:c=keyCertSign,cRLSign");
        keytool("intermediate", "-importcert", "-noprompt", "-alias", "root", "-file", "root.cer");
        keytool("intermediate", "-importcert", "-noprompt", "-alias", "ca", "-file", "intermediate.cer");
        keytool("intermediate", "-gencert", "-alias", "ca", "-infile", "server.csr", "-outfile", "new-leaf.cer",
                "-ext", identity(MESH_ID, SERVER_ID), "-ext", "eku=serverAuth,clientAuth");

        final Certificate leaf = readCertificate("new-leaf.cer");
        final Certificate intermediate = readCertificate("intermediate.cer");
        final Certificate root = readCertificate("root.cer");
        final char[] password = PASSWORD.toCharArray();
        try {
            final Key key = readStore("server", password).getKey("peer", password);
            writeIdentity("expired-chain", key, password, leaf, intermediate, root);
            writeIdentity("expired-chain-without-root", key, password, leaf, intermediate);
        } finally {
            Arrays.fill(password, '\0');
        }

        final CertPathValidatorException failure = assertThrows(CertPathValidatorException.class, () -> tls("expired-chain"));
        assertEquals(CertPathValidatorException.BasicReason.EXPIRED, failure.getReason());
        assertThrows(CertificateExpiredException.class, () -> tls("expired-chain-without-root"));
    }

    private static PeerTlsConnection accept(final PeerTls server, final ServerSocket listener) throws IOException {
        return server.accept(listener.accept(), deadline());
    }

    private static ServerSocket listener() throws IOException {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(5_000);
        return listener;
    }

    private static InetSocketAddress endpoint(final ServerSocket listener) {
        return (InetSocketAddress) listener.getLocalSocketAddress();
    }

    private static long deadline() {
        return System.nanoTime() + Duration.ofSeconds(5).toNanos();
    }

    private static MeshRuntime openMembership(final Path config, final String build) throws IOException {
        return MeshRuntime.open(config.toString(), build, ignored -> false, ignored -> Optional.empty()).orElseThrow();
    }

    private static Path meshConfig(final int port, final String identity, final List<RailixValue> bootstrap)
            throws IOException {
        return meshConfig(port, identity, bootstrap, 2, Optional.empty());
    }

    private static Path meshConfig(final int port, final String identity, final List<RailixValue> bootstrap,
                                   final int capacity, final Optional<String> offloadFlow) throws IOException {
        final Path config = Files.createTempFile(credentials, "mesh-", ".json");
        final String offload = offloadFlow.map(flow -> "\"offloadFlow\":" + RailixJson.write(RailixValue.string(flow)) + ",")
                .orElse("");
        return Files.writeString(config, """
                {"meshId":"%s",
                 "listenEndpoint":{"host":"127.0.0.1","port":%d},
                 "advertisedEndpoint":{"host":"127.0.0.1","port":%d},
                 "bootstrapEndpoints":%s,"flowCapacity":%d,%s
                 "identityStore":{"path":"%s.p12","passwordFile":"identity-password"},
                 "trustStore":{"path":"trust.p12","passwordFile":"trust-password"}}
                """.formatted(MESH_ID, port, port, RailixJson.write(RailixValue.array(bootstrap)), capacity, offload, identity));
    }

    private static PeerTls tls(final String alias) throws Exception {
        return PeerTls.load(MESH_ID, store(alias), passwordFile(alias), store("trust"), passwordFile("trust"));
    }

    private static SSLContext clientContext(final Optional<String> identity) throws Exception {
        final char[] password = PASSWORD.toCharArray();
        final char[] trustSecret = TRUST_PASSWORD.toCharArray();
        try {
            final Optional<KeyManager[]> keys;
            if (identity.isPresent()) {
                final KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                manager.init(readStore(identity.orElseThrow(), password), password);
                keys = Optional.of(manager.getKeyManagers());
            } else {
                keys = Optional.empty();
            }
            final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(readStore("trust", trustSecret));
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.orElseGet(() -> new KeyManager[0]), trust.getTrustManagers(), null);
            return context;
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(trustSecret, '\0');
        }
    }

    private static KeyStore readStore(final String alias, final char[] password) throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(store(alias))) {
            keys.load(input, password);
        }
        return keys;
    }

    private static Certificate readCertificate(final String name) throws Exception {
        try (InputStream input = Files.newInputStream(credentials.resolve(name))) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void writeIdentity(final String alias, final Key key, final char[] password,
                                      final Certificate... chain) throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, password);
        keys.setKeyEntry("peer", key, password, chain);
        try (OutputStream output = Files.newOutputStream(store(alias))) {
            keys.store(output, password);
        }
    }

    private static void issue(final String alias, final UUID mesh, final UUID replica) throws Exception {
        issue(alias, mesh, replica, "eku=serverAuth,clientAuth");
    }

    private static void issue(final String alias, final UUID mesh, final UUID replica, final String usage) throws Exception {
        issue(alias, mesh, replica, usage, "issuer", "root.cer");
    }

    private static void issue(final String alias, final UUID mesh, final UUID replica, final String usage,
                              final String issuer, final String issuerCertificate) throws Exception {
        keytool(alias, "-genkeypair", "-alias", "peer", "-dname", "CN=" + alias,
                "-keyalg", "EC", "-groupname", "secp256r1");
        keytool(alias, "-certreq", "-alias", "peer", "-file", alias + ".csr");
        keytool(issuer, "-gencert", "-alias", "root", "-infile", alias + ".csr", "-outfile", alias + ".cer",
                "-ext", identity(mesh, replica), "-ext", usage);
        keytool(alias, "-importcert", "-noprompt", "-alias", "root", "-file", issuerCertificate);
        keytool(alias, "-importcert", "-noprompt", "-alias", "peer", "-file", alias + ".cer");
    }

    private static String identity(final UUID mesh, final UUID replica) {
        return "san=uri:https://github.com/NanoNative/nano_railix/mesh/v1/" + mesh + "/replicas/" + replica;
    }

    private static void keytool(final String alias, final String... arguments) throws Exception {
        final ArrayList<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-storetype", "PKCS12", "-keystore", store(alias).toString(),
                "-storepass:file", passwordFile(alias).toString()));
        command.addAll(List.of(arguments));
        final Path log = credentials.resolve("keytool.log");
        final Process process = new ProcessBuilder(command).directory(credentials.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, SECONDS), "keytool timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(log);
                } catch (final IOException missing) {
                    return missing.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, SECONDS);
            }
        }
    }

    private static void writePassword(final Path path, final String value) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value + "\n");
    }

    private static Path passwordFile(final String store) {
        return credentials.resolve(store.equals("trust") ? "trust-password" : "identity-password");
    }

    private static Path store(final String alias) {
        return credentials.resolve(alias + ".p12");
    }

    private enum RejectedClient {
        NO_CERTIFICATE(Optional.empty()), UNTRUSTED(Optional.of("untrusted")), OTHER_MESH(Optional.of("other-mesh"));

        private final Optional<String> identity;

        RejectedClient(final Optional<String> identity) {
            this.identity = identity;
        }
    }
}
