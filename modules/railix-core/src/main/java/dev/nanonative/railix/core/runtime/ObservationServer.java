package dev.nanonative.railix.core.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/** Owns a standalone application's authenticated loopback observation and ephemeral discovery. */
public final class ObservationServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final MetricsEndpoint metrics;
    private final Path registration;
    private final byte[] authorization;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    ObservationServer(final HttpServer server, final ExecutorService executor, final MetricsEndpoint metrics,
                      final Path registration, final byte[] authorization) {
        this.server = server;
        this.executor = executor;
        this.metrics = metrics;
        this.registration = registration;
        this.authorization = authorization;
    }

    /**
     * Opens observation for an executable JAR unless its build disabled this capability.
     * Failure to publish optional observation never changes application execution.
     * The normal launcher must close the returned owner on every termination path.
     * @param applicationClass generated application's class, used only to locate its own artifact
     * @param measurements the same operational metric store used by application execution
     * @return the listener owner, or empty for disabled, unpackaged or unavailable observation
     */
    public static Optional<ObservationServer> open(final Class<?> applicationClass, final RuntimeMetrics measurements) {
        return open(applicationClass, measurements, Map::of);
    }

    /**
     * Opens the same authenticated observation with optional read-only runtime details.
     * @param applicationClass generated application class used to locate the artifact
     * @param measurements application metric store
     * @param details immutable, non-secret details captured per request
     * @return the listener owner, or empty when observation is unavailable or disabled
     */
    public static Optional<ObservationServer> open(final Class<?> applicationClass, final RuntimeMetrics measurements,
            final Supplier<Map<String, RailixValue>> details) {
        HttpServer server = null;
        ExecutorService executor = null;
        MetricsEndpoint endpoint = null;
        Path registration = null;
        Path pending = null;
        try {
            final var location = applicationClass.getProtectionDomain().getCodeSource();
            if (location == null) return Optional.empty();
            final Path artifact = Path.of(location.getLocation().toURI()).toRealPath();
            if (!Files.isRegularFile(artifact)) return Optional.empty();
            final String revision;
            try (var jar = new JarFile(artifact.toFile())) {
                final var manifest = jar.getManifest();
                if (manifest == null) return Optional.empty();
                final var attributes = manifest.getMainAttributes();
                if ("false".equals(attributes.getValue("Railix-Observation"))) return Optional.empty();
                revision = attributes.getValue("Railix-Project-Id");
                if (revision == null || !revision.matches("sha256-[a-f0-9]{64}")) return Optional.empty();
            }
            final var started = ProcessHandle.current().info().startInstant();
            if (started.isEmpty()) return Optional.empty();
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(artifact)) {
                final byte[] buffer = new byte[65_536];
                for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            }
            final Path directory = directory(Path.of(System.getProperty("user.home"), ".railix"), true);
            final byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            final String token = HexFormat.of().formatHex(secret);
            Arrays.fill(secret, (byte) 0);
            final byte[] authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            endpoint = new MetricsEndpoint(measurements, token);
            final Connection connection = new Connection(UUID.randomUUID().toString(), ProcessHandle.current().pid(),
                    started.get(), server.getAddress().getPort(), token,
                    revision, "sha256-" + HexFormat.of().formatHex(digest.digest()), artifact);
            final RailixValue.ObjectValue identity = connection.identity();
            final Semaphore identityAdmission = new Semaphore(2);
            final Semaphore bodies = new Semaphore(4);
            server.createContext("/v1/application", exchange -> statusRead(exchange, "/v1/application",
                    authorization, identityAdmission, bodies, () -> identity));
            if (!details.get().isEmpty()) {
                final Semaphore statusAdmission = new Semaphore(1);
                server.createContext("/v1/status", exchange -> statusRead(exchange, "/v1/status",
                        authorization, statusAdmission, bodies, () -> RailixValue.object(details.get())));
            }
            server.createContext("/v1/metrics", endpoint);
            server.setExecutor(executor);
            server.start();
            final var document = new java.util.LinkedHashMap<>(identity.values());
            document.put("port", RailixValue.number(connection.port()));
            document.put("token", RailixValue.string(token));
            document.put("artifact", RailixValue.string(artifact.toString()));
            pending = Files.createTempFile(directory, ".registering-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(pending, RailixJson.write(RailixValue.object(document)), StandardCharsets.UTF_8);
            registration = directory.resolve(connection.instance() + ".json");
            Files.move(pending, registration, StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(new ObservationServer(server, executor, endpoint, registration, authorization));
        } catch (final IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException
                       | UnsupportedOperationException | IllegalArgumentException | SecurityException failure) {
            if (server != null) server.stop(0);
            if (endpoint != null) endpoint.close();
            if (executor != null) executor.shutdownNow();
            for (final Path file : new Path[]{pending, registration}) {
                if (file != null) try { Files.deleteIfExists(file); } catch (final IOException ignored) { }
            }
            System.err.println("Local observation unavailable. Application execution is unchanged.");
            return Optional.empty();
        }
    }

    private static void statusRead(final HttpExchange exchange, final String path,
            final byte[] authorization, final Semaphore admission, final Semaphore bodies,
            final Supplier<RailixValue.ObjectValue> payload) throws IOException {
        try (exchange) {
            if (!ManagementHttp.authorized(exchange, authorization)) {
                if (ManagementHttp.admittedBody(exchange, bodies).isPresent()) {
                    ManagementHttp.send(exchange, 401, RailixValue.object(Map.of("status", RailixValue.string("unauthorized"))));
                }
            } else if (!exchange.getRequestURI().getPath().equals(path)) {
                ManagementHttp.send(exchange, 404, RailixValue.object(Map.of("status", RailixValue.string("not-found"))));
            } else if (!exchange.getRequestMethod().equals("GET")) {
                ManagementHttp.send(exchange, 405, RailixValue.object(Map.of("status", RailixValue.string("method-not-allowed"))));
            } else if (admission.tryAcquire()) {
                try {
                    ManagementHttp.send(exchange, 200, payload.get());
                } finally {
                    admission.release();
                }
            }
        }
    }

    /**
     * Reads bounded, owner-only local connection records. Dead processes and replaced PIDs are ignored.
     * Reading does not authenticate an endpoint. A caller must verify {@link Connection#identity()} over HTTP.
     * @param home Railix home, normally the current user's {@code ~/.railix}
     * @return live candidates, without modifying another application's registrations
     * @throws IOException if the discovery directory cannot be read safely
     */
    public static List<Connection> connections(final Path home) throws IOException {
        if (!Files.exists(home.resolve("observations"), LinkOption.NOFOLLOW_LINKS)) return List.of();
        final Path directory = directory(home, false);
        final List<Connection> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (final Path file : files) {
                try {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16_384
                            || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(directory))
                            || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                            .equals(PosixFilePermissions.fromString("rw-------"))) continue;
                    final String source;
                    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        final byte[] bytes = input.readNBytes(16_385);
                        if (bytes.length > 16_384) continue;
                        source = new String(bytes, StandardCharsets.UTF_8);
                    }
                    if (!(RailixJson.parse(source) instanceof RailixJson.Parsed parsed)
                            || !(parsed.value() instanceof RailixValue.ObjectValue object)) continue;
                    final var values = object.values();
                    final String instance = ((RailixValue.StringValue) values.get("instance")).value();
                    final long pid = ((RailixValue.NumberValue) values.get("application_pid")).value().longValueExact();
                    final Instant started = Instant.parse(((RailixValue.StringValue) values.get("started_at")).value());
                    final int port = ((RailixValue.NumberValue) values.get("port")).value().intValueExact();
                    final String token = ((RailixValue.StringValue) values.get("token")).value();
                    final String revision = ((RailixValue.StringValue) values.get("source_revision")).value();
                    final String fingerprint = ((RailixValue.StringValue) values.get("artifact_digest")).value();
                    final Path artifact = Path.of(((RailixValue.StringValue) values.get("artifact")).value());
                    if (!instance.matches("[a-f0-9-]{36}") || !file.getFileName().toString().equals(instance + ".json")
                            || port <= 0 || port > 65_535 || !token.matches("[a-f0-9]{64}")
                            || !revision.matches("sha256-[a-f0-9]{64}") || !fingerprint.matches("sha256-[a-f0-9]{64}")
                            || !artifact.isAbsolute()) continue;
                    final var process = ProcessHandle.of(pid);
                    if (process.isPresent() && process.get().isAlive()
                            && process.get().info().startInstant().filter(started::equals).isPresent())
                        result.add(new Connection(instance, pid, started, port, token, revision, fingerprint, artifact));
                } catch (final IOException | IllegalArgumentException | ArithmeticException | ClassCastException | NullPointerException ignoredInvalidRecord) {
                    // Another process may be removing its registration. Never repair or delete its files.
                }
                if (result.size() > 512) throw new IOException("More than 512 live local applications. Close unused applications before connecting.");
            }
        }
        return List.copyOf(result);
    }

    static Path directory(final Path home, final boolean create) throws IOException {
        final Path absolute = home.toAbsolutePath().normalize();
        final var owner = absolute.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        if (create) Files.createDirectories(absolute,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(absolute).equals(owner)
                || Files.getPosixFilePermissions(absolute).stream().anyMatch(permission ->
                permission == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
                        || permission == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
            throw new IOException("Railix home must be owned and writable only by the current user.");
        final Path directory = absolute.resolve("observations");
        if (create)
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(directory).equals(owner)
                || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Observation discovery must be an owner-only directory, not a link.");
        return directory;
    }

    /** Stops only observation and removes this owner's registration. It never stops application work. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        metrics.stopAdmission();
        server.stop(0);
        executor.shutdownNow();
        metrics.close();
        Arrays.fill(authorization, (byte) 0);
        try { Files.deleteIfExists(registration); }
        catch (final IOException failure) { System.err.println("Cannot remove local observation registration."); }
    }

    /**
     * A transient same-user discovery candidate. The credential must never be sent to the browser or logged.
     * @param instance unique listener lifetime, distinct from PID and build revision
     * @param pid process ID
     * @param started process start instant, used to reject PID reuse
     * @param port ephemeral IPv4 loopback port
     * @param token private bearer credential
     * @param revision functional source revision
     * @param fingerprint digest of the actual executable artifact
     * @param artifact local executable JAR path
     */
    public record Connection(String instance, long pid, Instant started, int port, String token,
                             String revision, String fingerprint, Path artifact) {
        /** @return the literal loopback origin, never a caller-supplied host or redirect destination */
        public URI origin() { return URI.create("http://127.0.0.1:" + port); }

        /** @return non-secret identity to compare with the authenticated endpoint before using observations */
        public RailixValue.ObjectValue identity() {
            return RailixValue.object(Map.of("instance", RailixValue.string(instance),
                    "application_pid", RailixValue.number(pid), "started_at", RailixValue.string(started.toString()),
                    "source_revision", RailixValue.string(revision), "artifact_digest", RailixValue.string(fingerprint)));
        }

        /** @return a non-secret description, safe for diagnostics */
        @Override public String toString() { return "Application " + pid + " (" + instance + ")"; }
    }
}
