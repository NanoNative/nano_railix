package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One Creator-owned development JVM. */
final class DevelopmentApplication implements AutoCloseable {
    private static final int OUTPUT_LIMIT = 16_384;
    private static final int CONTROL_FRAME_LIMIT = 128;
    private static final int ACCEPT_POLL_MILLIS = 250;
    private static final int FRAME_READ_MILLIS = 1_000;
    private static final int RESPONSE_LIMIT = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration OUTPUT_JOIN_TIMEOUT = Duration.ofSeconds(2);
    private final String fingerprint;
    private final long builtAt;
    private final String buildPath;
    private final String sourcePath;
    private final String classesPath;
    private final String token;
    private final Process process;
    private final OutputStream ownership;
    private final Thread outputThread;
    private final ApplicationBuilder.Artifact artifact;
    private final URI baseUri;
    private final HttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object requestLock = new Object();
    private final Object closeLock = new Object();
    private final Map<Long, ProcessHandle> ownedProcesses = new LinkedHashMap<>();
    private boolean cleanupComplete;
    private boolean drainAttempted;
    private int activeRequests;

    private DevelopmentApplication(
            final String fingerprint,
            final long builtAt,
            final String buildPath,
            final String sourcePath,
            final String classesPath,
            final String token,
            final Process process,
            final OutputStream ownership,
            final Thread outputThread,
            final ApplicationBuilder.Artifact artifact,
            final URI baseUri,
            final HttpClient client
    ) {
        this.fingerprint = fingerprint;
        this.builtAt = builtAt;
        this.buildPath = buildPath;
        this.sourcePath = sourcePath;
        this.classesPath = classesPath;
        this.token = token;
        this.process = process;
        this.ownership = ownership;
        this.outputThread = outputThread;
        this.artifact = artifact;
        this.baseUri = baseUri;
        this.client = client;
    }

    static DevelopmentApplication start(
            final long generation,
            final Path projectFile,
            final CompileResult.Compiled compiled
    ) throws IOException {
        final ApplicationBuilder.Artifact artifact = ApplicationBuilder.build(projectFile, compiled);
        final String token = UUID.randomUUID().toString();
        Process process = null;
        Thread outputThread = null;
        HttpClient client = null;
        try (ServerSocket readiness = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            process = new ProcessBuilder(command(artifact.jar()))
                    .redirectErrorStream(true)
                    .start();
            final OutputStream ownership = process.getOutputStream();
            ownership.write(("START " + token + " " + readiness.getLocalPort() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            ownership.flush();
            final ByteArrayOutputStream output = new ByteArrayOutputStream(OUTPUT_LIMIT);
            final Process owned = process;
            outputThread = Thread.ofVirtual().name("railix-development-output-" + generation).start(() ->
                    drain(owned, output)
            );
            final int readyPort = awaitReady(readiness, token, owned, output);
            final URI baseUri = URI.create("http://127.0.0.1:" + readyPort);
            client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
            return new DevelopmentApplication(
                    artifact.fingerprint(),
                    artifact.builtAt(),
                    artifact.jar().toString(),
                    artifact.source().toString(),
                    artifact.classes().toString(),
                    token,
                    process,
                    ownership,
                    outputThread,
                    artifact,
                    baseUri,
                    client
            );
        } catch (final IOException | RuntimeException | Error exception) {
            rollback(process, outputThread, client, artifact, exception);
            throw exception;
        }
    }

    Response run(final String trigger, final byte[] context, final boolean test) throws IOException {
        return request("/v1/run/" + trigger, context, test);
    }

    Response preview(
            final String trigger,
            final String step,
            final byte[] context,
            final boolean test
    ) throws IOException {
        return request("/v1/preview/" + trigger + "/" + step, context, test);
    }

    private Response request(final String path, final byte[] context, final boolean test) throws IOException {
        if (!acquire()) {
            return new Response(503, "{\"status\":\"unavailable\"}");
        }
        try {
            final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("X-Railix-Test", Boolean.toString(test))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(context))
                    .build();
            try {
                final HttpResponse<java.io.InputStream> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                try (var body = response.body()) {
                    final byte[] bytes = body.readNBytes(RESPONSE_LIMIT + 1);
                    if (bytes.length > RESPONSE_LIMIT) {
                        return new Response(502, "{\"status\":\"invalid\",\"message\":\"Application response exceeded 1048576 bytes.\"}");
                    }
                    return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new Response(409, "{\"status\":\"cancelled\"}");
            }
        } finally {
            release();
        }
    }

    private boolean acquire() {
        synchronized (requestLock) {
            if (closed.get() || !process.isAlive()) {
                return false;
            }
            activeRequests++;
            return true;
        }
    }

    private void release() {
        synchronized (requestLock) {
            activeRequests--;
            requestLock.notifyAll();
        }
    }

    RailixValue.ObjectValue snapshot() {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("state", RailixValue.string(process.isAlive() && !closed.get() ? "running" : "stopped"));
        value.put("fingerprint", RailixValue.string(fingerprint));
        value.put("built_at", RailixValue.number(builtAt));
        value.put("build_path", RailixValue.string(buildPath));
        value.put("source_path", RailixValue.string(sourcePath));
        value.put("classes_path", RailixValue.string(classesPath));
        value.put("pid", RailixValue.number(process.pid()));
        return RailixValue.object(value);
    }

    @Override
    public void close() {
        boolean interrupted = Thread.interrupted();
        RuntimeException failure = null;
        synchronized (closeLock) {
            if (cleanupComplete) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            closed.set(true);
            if (!drainAttempted) {
                interrupted |= drainRequests();
                drainAttempted = true;
            }
            capture(process, ownedProcesses);
            try {
                ownership.close();
            } catch (final IOException exception) {
                failure = merge(failure, new IllegalStateException(
                        "Development application ownership pipe did not close.",
                        exception
                ));
            }
            if (!terminate(process, ownedProcesses)) {
                failure = merge(failure, new IllegalStateException(
                        "Development application process " + process.pid() + " did not terminate."
                ));
            }
            interrupted |= Thread.interrupted();
            try {
                client.shutdownNow();
            } catch (final RuntimeException exception) {
                failure = merge(failure, exception);
            }
            try {
                client.close();
            } catch (final RuntimeException exception) {
                failure = merge(failure, exception);
            }
            interrupted |= Thread.interrupted();
            closeProcessOutput(process);
            outputThread.interrupt();
            final Join join = join(outputThread);
            interrupted |= join.interrupted();
            if (!join.complete()) {
                failure = merge(failure, new IllegalStateException(
                        "Development application output reader did not terminate."
                ));
            }
            if (failure == null) {
                cleanupComplete = true;
            }
        }
        interrupted |= Thread.interrupted();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException merge(
            final RuntimeException failure,
            final RuntimeException next
    ) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    ApplicationBuilder.Artifact artifact() {
        return artifact;
    }

    boolean running() {
        return !closed.get() && process.isAlive();
    }

    private boolean drainRequests() {
        final long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        boolean interrupted = false;
        synchronized (requestLock) {
            while (activeRequests > 0) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(requestLock, remaining);
                } catch (final InterruptedException exception) {
                    interrupted = true;
                }
            }
            return interrupted;
        }
    }

    private static List<String> command(final Path jar) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jar.toString()
        );
    }

    private static int awaitReady(
            final ServerSocket readiness,
            final String token,
            final Process process,
            final ByteArrayOutputStream output
    ) throws IOException {
        final long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        final byte[] expectedToken = token.getBytes(StandardCharsets.UTF_8);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw readinessFailure("Development application readiness was interrupted.", output);
            }
            if (!process.isAlive()) {
                throw readinessFailure("Development application exited before readiness.", output);
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw readinessFailure("Development application did not become ready.", output);
            }
            readiness.setSoTimeout((int) Math.max(1, Math.min(
                    ACCEPT_POLL_MILLIS,
                    TimeUnit.NANOSECONDS.toMillis(remaining)
            )));
            try {
                final Socket connection = readiness.accept();
                try (connection) {
                    if (!connection.getInetAddress().isLoopbackAddress()) {
                        continue;
                    }
                    connection.setSoTimeout((int) Math.max(1, Math.min(
                            FRAME_READ_MILLIS,
                            TimeUnit.NANOSECONDS.toMillis(remaining)
                    )));
                    final int port = readyPort(readFrame(connection.getInputStream()), expectedToken);
                    if (port > 0) {
                        return port;
                    }
                } catch (final IOException | RuntimeException ignoredInvalidFrame) {
                    // Invalid local callbacks cannot consume the one authenticated readiness slot.
                }
            } catch (final SocketTimeoutException ignoredPoll) {
                // Recheck deadline and child liveness.
            }
        }
    }

    private static String readFrame(final InputStream input) throws IOException {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(CONTROL_FRAME_LIMIT);
        for (int index = 0; ; index++) {
            final int next = input.read();
            if (next < 0) {
                throw new IOException("Readiness callback ended before a complete frame.");
            }
            if (next == '\n') {
                return frame.toString(StandardCharsets.UTF_8);
            }
            if (index == CONTROL_FRAME_LIMIT || next < 0x20 || next > 0x7e) {
                throw new IOException("Readiness callback frame is invalid.");
            }
            frame.write(next);
        }
    }

    private static int readyPort(final String frame, final byte[] expectedToken) {
        if (!frame.startsWith("READY ")) {
            return -1;
        }
        final int separator = frame.indexOf(' ', "READY ".length());
        if (separator <= "READY ".length() || separator != frame.lastIndexOf(' ')) {
            return -1;
        }
        final byte[] candidateToken = frame.substring("READY ".length(), separator)
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, candidateToken)) {
            return -1;
        }
        final String value = frame.substring(separator + 1);
        if (value.isEmpty() || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            return -1;
        }
        try {
            final int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535 ? port : -1;
        } catch (final NumberFormatException invalidPort) {
            return -1;
        }
    }

    private static IOException readinessFailure(
            final String message,
            final ByteArrayOutputStream output
    ) {
        final String diagnostic = bounded(output);
        return new IOException(diagnostic.isEmpty() ? message : message + " " + diagnostic);
    }

    private static void drain(
            final Process process,
            final ByteArrayOutputStream output
    ) {
        try (var input = process.getInputStream()) {
            final byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                append(output, buffer, read);
            }
        } catch (final IOException ignoredClosedOutput) {
            // Process liveness and termination are authoritative.
        }
    }

    private static void append(
            final ByteArrayOutputStream output,
            final byte[] bytes,
            final int length
    ) {
        synchronized (output) {
            output.write(bytes, 0, Math.min(length, OUTPUT_LIMIT - output.size()));
        }
    }

    private static String bounded(final ByteArrayOutputStream output) {
        synchronized (output) {
            return output.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static boolean terminate(final Process process, final Map<Long, ProcessHandle> owned) {
        capture(process, owned);
        boolean interrupted = false;
        for (int attempt = 0; alive(owned) && attempt < 3; attempt++) {
            interrupted |= waitForExit(owned, Duration.ofSeconds(2));
            capture(process, owned);
            if (alive(owned) && attempt < 2) {
                final boolean force = attempt == 1;
                owned.values().stream().filter(ProcessHandle::isAlive).toList().reversed().forEach(handle -> {
                    if (force) {
                        handle.destroyForcibly();
                    } else {
                        handle.destroy();
                    }
                });
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !alive(owned);
    }

    private static void capture(final Process process, final Map<Long, ProcessHandle> owned) {
        process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), handle));
        owned.putIfAbsent(process.pid(), process.toHandle());
    }

    private static boolean alive(final Map<Long, ProcessHandle> owned) {
        return owned.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private static boolean waitForExit(
            final Map<Long, ProcessHandle> owned,
            final Duration timeout
    ) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (alive(owned) && System.nanoTime() < deadline) {
            final CompletableFuture<?>[] exits = owned.values().stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::onExit)
                    .toArray(CompletableFuture<?>[]::new);
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                CompletableFuture.allOf(exits).get(remaining, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException exception) {
                interrupted = true;
            } catch (final ExecutionException | TimeoutException exception) {
                break;
            }
        }
        return interrupted;
    }

    private static Join join(final Thread thread) {
        final long deadline = System.nanoTime() + OUTPUT_JOIN_TIMEOUT.toNanos();
        boolean interrupted = false;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                final long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                }
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        return new Join(!thread.isAlive(), interrupted);
    }

    private static void closeProcessOutput(final Process process) {
        try {
            process.getInputStream().close();
        } catch (final IOException ignored) {
            // Process termination is authoritative; this only unblocks its output reader.
        }
        try {
            process.getErrorStream().close();
        } catch (final IOException ignored) {
            // Error output is merged into the process input stream.
        }
    }

    private static void rollback(
            final Process process,
            final Thread outputThread,
            final HttpClient client,
            final ApplicationBuilder.Artifact artifact,
            final Throwable failure
    ) {
        if (client != null) {
            try {
                client.shutdownNow();
            } catch (final RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                client.close();
            } catch (final RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        if (process != null) {
            final Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
            capture(process, owned);
            try {
                process.getOutputStream().close();
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (!terminate(process, owned)) {
                failure.addSuppressed(new IOException(
                        "Development application process " + process.pid() + " did not terminate."
                ));
            }
            closeProcessOutput(process);
        }
        if (outputThread != null) {
            outputThread.interrupt();
            final Join join = join(outputThread);
            if (!join.complete()) {
                failure.addSuppressed(new IOException("Development application output reader did not terminate."));
            }
            if (join.interrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        if (!artifact.reused()) {
            try {
                ApplicationBuilder.delete(artifact);
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    record Response(int status, String body) {
    }

    private record Join(boolean complete, boolean interrupted) {
    }
}
