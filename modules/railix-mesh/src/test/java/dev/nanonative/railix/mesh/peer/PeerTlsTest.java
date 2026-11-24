package dev.nanonative.railix.mesh.peer;

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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class PeerTlsTest {
    private static final UUID MESH_ID = UUID.randomUUID();
    private static final UUID OTHER_MESH = UUID.randomUUID();
    private static final UUID SERVER_ID = UUID.randomUUID();
    private static final UUID CLIENT_ID = UUID.randomUUID();
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
    void authenticatesBothReplicasAndExchangesFrames() throws Exception {
        final PeerTls server = tls("server");
        final PeerTls client = tls("client");
        assertEquals(SERVER_ID, server.replicaId());
        assertEquals(CLIENT_ID, client.replicaId());

        try (ServerSocket listener = listener(); ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<Object> incoming = threads.submit(() -> {
                try (PeerTlsConnection connection = accept(server, listener)) {
                    assertEquals(CLIENT_ID, connection.replicaId());
                    assertArrayEquals(new byte[]{1, 2, 3}, read(connection.channel()));
                    assertTrue(connection.channel().write(new byte[]{4, 5}, 64));
                }
                return null;
            });

            final Future<Object> outgoing = threads.submit(() -> {
                try (PeerTlsConnection connection = client.dial(endpoint(listener), deadline())) {
                    assertEquals(SERVER_ID, connection.replicaId());
                    assertTrue(connection.channel().write(new byte[]{1, 2, 3}, 64));
                    assertArrayEquals(new byte[]{4, 5}, read(connection.channel()));
                }
                return null;
            });
            try {
                outgoing.get(8, SECONDS);
                incoming.get(8, SECONDS);
            } finally {
                outgoing.cancel(true);
                incoming.cancel(true);
            }
        }
    }

    @Test
    void mismatchedBuildClosesBeforeSendingState() throws Exception {
        final InetSocketAddress endpoint;
        try (ServerSocket port = listener()) {
            endpoint = endpoint(port);
        }
        final Path config = meshConfig(endpoint.getPort(), "server", List.of());
        try (MeshRuntime runtime = MeshRuntime.open(config.toString(), "sha256-" + "a".repeat(64)).orElseThrow()) {
            runtime.start();
            try (PeerTlsConnection peer = tls("client").dial(endpoint, deadline())) {
                final PeerChannel channel = peer.channel();
                final RailixValue.ObjectValue opening = PeerProtocol.read(channel).orElseThrow();
                assertEquals(RailixValue.string("hello.v1"), opening.values().get("type"));
                final byte[] differentBuild = PeerProtocol.hello("sha256-" + "b".repeat(64), MESH_ID,
                        CLIENT_ID, UUID.randomUUID(), Optional.of(UUID.randomUUID()));
                PeerProtocol.write(channel, differentBuild);
                assertEquals(FrameReadStatus.END_OF_STREAM,
                        channel.read(PeerFrames.MAX_CONTROL_BODY_BYTES, size -> Optional.of(new byte[size])));
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
            try (MeshRuntime runtime = MeshRuntime.open(config.toString(), build).orElseThrow()) {
                runtime.start();
                final long initialStateSent;
                try (PeerTlsConnection connection = accept(seed, seedListener)) {
                    final PeerChannel channel = connection.channel();
                    assertTrue(PeerProtocol.read(channel).isPresent());
                    PeerProtocol.write(channel, PeerProtocol.hello(build, MESH_ID,
                            SERVER_ID, UUID.randomUUID(), Optional.empty()));
                    assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
                    PeerProtocol.write(channel, PeerProtocol.state(seedEndpoint, 2));
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
        try (MeshRuntime runtime = MeshRuntime.open(config.toString(), build).orElseThrow()) {
            runtime.start();
            try (PeerTlsConnection healthy = tls("client").dial(address, deadline())) {
                final PeerChannel channel = healthy.channel();
                assertTrue(PeerProtocol.read(channel).isPresent());
                PeerProtocol.write(channel, PeerProtocol.hello(build, MESH_ID, CLIENT_ID,
                        UUID.randomUUID(), Optional.of(UUID.randomUUID())));
                final PeerEndpoint advertised = new PeerEndpoint("127.0.0.1", healthy.transport().getLocalPort());
                PeerProtocol.write(channel, PeerProtocol.state(advertised, 2));
                assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());
                assertTrue(PeerProtocol.read(channel).filter(PeerProtocol::validState).isPresent());

                try (PeerTlsConnection wrongIdentity = tls("server").dial(address, deadline())) {
                    assertTrue(PeerProtocol.read(wrongIdentity.channel()).isPresent());
                    PeerProtocol.write(wrongIdentity.channel(), PeerProtocol.hello(build, MESH_ID, SERVER_ID,
                            UUID.randomUUID(), Optional.of(UUID.randomUUID())));
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

    private static byte[] read(final PeerChannel channel) throws IOException, InterruptedException {
        return assertInstanceOf(FrameBody.class, channel.read(64, size -> Optional.of(new byte[size]))).bytes();
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

    private static Path meshConfig(final int port, final String identity, final List<RailixValue> bootstrap)
            throws IOException {
        final Path config = Files.createTempFile(credentials, "mesh-", ".json");
        return Files.writeString(config, """
                {"meshId":"%s",
                 "listenEndpoint":{"host":"127.0.0.1","port":%d},
                 "advertisedEndpoint":{"host":"127.0.0.1","port":%d},
                 "bootstrapEndpoints":%s,"flowCapacity":2,
                 "identityStore":{"path":"%s.p12","passwordFile":"identity-password"},
                 "trustStore":{"path":"trust.p12","passwordFile":"trust-password"}}
                """.formatted(MESH_ID, port, port, RailixJson.write(RailixValue.array(bootstrap)), identity));
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
