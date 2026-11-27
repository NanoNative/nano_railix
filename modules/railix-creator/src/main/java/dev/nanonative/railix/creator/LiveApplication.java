package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.runtime.ObservationServer;
import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only connection. It deliberately has no process, activation, execution or deployment methods. */
final class LiveApplication implements AutoCloseable {
    private static final String STATUS_PATH = "/v1/status";
    final ObservationServer.Connection connection;
    final ProjectFiles.Snapshot project;
    final CreatorScene scene;
    final CreatorEditor editor;
    private final HttpClient client;
    private final Semaphore metricReaders = new Semaphore(2);
    private final Semaphore statusReader = new Semaphore(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile boolean runtimeDetailsAvailable = true;
    private String comparedSource = "";
    private RailixValue.ObjectValue comparison = RailixValue.object(Map.of());

    LiveApplication(final ObservationServer.Connection connection, final ProjectFiles.Snapshot project,
                    final HttpClient client) {
        this.connection = connection;
        this.project = project;
        this.client = client;
        scene = new CreatorScene(project.source(), project.creator(), project.catalog());
        editor = new CreatorEditor(project.source(), project.creator(), project.catalog());
    }

    static LiveApplication connect(final ObservationServer.Connection connection, final Path local, final Path home)
            throws IOException {
        final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        boolean accepted = false;
        try {
            final var response = ApplicationReads.response(client, HttpRequest.newBuilder(connection.origin().resolve("/v1/application"))
                    .header("Authorization", "Bearer " + connection.token()).timeout(Duration.ofSeconds(5)).GET().build(), 16_384, "Application identity");
            if (response.status() != 200 || !(RailixJson.parse(new String(response.body(), StandardCharsets.UTF_8)) instanceof RailixJson.Parsed parsed)
                    || !connection.identity().equals(parsed.value()))
                throw new IOException("LIVE_IDENTITY_MISMATCH: This application is no longer available. Refresh the connection list.");
            if (!connection.fingerprint().equals("sha256-" + Sha256.hexDigest(connection.artifact())))
                throw new IOException("LIVE_ARTIFACT_CHANGED: The running application's original JAR has been replaced.");
            final var project = ProjectFiles.snapshot(connection.artifact(), local, home, connection.revision());
            final var result = new LiveApplication(connection, project, client);
            if (!result.alive()) throw new IOException("LIVE_STOPPED: The application stopped while connecting.");
            accepted = true;
            return result;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Live connection interrupted.", interrupted);
        } finally { if (!accepted) client.shutdownNow(); }
    }

    boolean alive() {
        return open.get() && ProcessHandle.of(connection.pid()).filter(ProcessHandle::isAlive)
                .flatMap(process -> process.info().startInstant()).filter(connection.started()::equals).isPresent();
    }

    DevelopmentApplication.ObservationResponse read(final String path, final RailixValue.ObjectValue query,
                                                   final long deadline) throws IOException {
        final boolean details = STATUS_PATH.equals(path);
        if (!details && !path.equals("/v1/metrics") && !path.startsWith("/v1/metrics/")) {
            throw new IllegalArgumentException("Only runtime status and operational metric reads are permitted.");
        }
        final Semaphore readers = details ? statusReader : metricReaders;
        if (!alive() || !readers.tryAcquire()) {
            return new DevelopmentApplication.ObservationResponse(503, "application/json; charset=utf-8",
                    "{\"status\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8));
        }
        try {
            final var request = HttpRequest.newBuilder(connection.origin().resolve(path))
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                    .header("Authorization", "Bearer " + connection.token());
            if (query == null) {
                request.GET();
            } else if (path.equals("/v1/metrics/query")) {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(RailixJson.write(query)));
            } else {
                throw new IllegalArgumentException("Only metric queries accept bodies.");
            }
            return ApplicationReads.response(client, request.build(), details ? 16_384 : RailixData.DEFAULT_MAX_SOURCE_BYTES,
                    details ? "Application runtime status" : "Application metrics");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Live observation interrupted.", interrupted);
        } finally {
            readers.release();
        }
    }

    RailixValue readMeshStatus(final long deadline) throws IOException {
        if (!runtimeDetailsAvailable) {
            return RailixValue.nullValue();
        }
        final DevelopmentApplication.ObservationResponse response = read(STATUS_PATH, null, deadline);
        if (response.status() == 404) {
            runtimeDetailsAvailable = false;
        }
        if (response.status() != 200) {
            return RailixValue.nullValue();
        }
        final RailixJson.Result document = RailixJson.parse(new String(response.body(), StandardCharsets.UTF_8));
        if (document instanceof RailixJson.Parsed(RailixValue.ObjectValue status)
                && status.values().get("mesh") instanceof RailixValue.ObjectValue mesh) {
            return mesh;
        }
        return RailixValue.nullValue();
    }

    RailixValue.ObjectValue snapshot() {
        return snapshot(RailixValue.nullValue());
    }

    RailixValue.ObjectValue snapshot(final RailixValue mesh) {
        final boolean running = alive();
        final LinkedHashMap<String, RailixValue> fields = new LinkedHashMap<>(connection.identity().values());
        fields.put("pid", RailixValue.number(connection.pid()));
        fields.put("state", RailixValue.string(running ? "running" : "stopped"));
        fields.put("fingerprint", RailixValue.string(connection.instance() + "/" + connection.fingerprint()));
        fields.put("readonly", RailixValue.bool(true));
        fields.put("build_path", RailixValue.string(connection.artifact().toString()));
        fields.put("mesh", running ? mesh : RailixValue.nullValue());
        return RailixValue.object(fields);
    }

    synchronized RailixValue.ObjectValue comparison(final String draft) {
        if (!comparedSource.equals(draft)) {
            comparison = ProjectComparison.between(project.source(), draft);
            comparedSource = draft;
        }
        return comparison;
    }

    @Override public void close() {
        if (open.compareAndSet(true, false)) client.shutdownNow();
    }
}
