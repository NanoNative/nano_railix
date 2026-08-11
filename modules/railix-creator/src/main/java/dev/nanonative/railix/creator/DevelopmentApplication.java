package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One Creator-owned development JVM. */
final class DevelopmentApplication implements AutoCloseable {
    private static final int OUTPUT_LIMIT = 16_384;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final String fingerprint;
    private final long builtAt;
    private final String buildPath;
    private final String token;
    private final Process process;
    private final URI baseUri;
    private final HttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DevelopmentApplication(
            final String fingerprint,
            final long builtAt,
            final String buildPath,
            final String token,
            final Process process,
            final URI baseUri,
            final HttpClient client
    ) {
        this.fingerprint = fingerprint;
        this.builtAt = builtAt;
        this.buildPath = buildPath;
        this.token = token;
        this.process = process;
        this.baseUri = baseUri;
        this.client = client;
    }

    static DevelopmentApplication start(
            final long generation,
            final String source,
            final String executableSource
    ) throws IOException {
        final Path project = Files.createTempFile("railix-development-", ".json");
        final String token = UUID.randomUUID().toString();
        final String buildPath = System.getProperty("java.class.path");
        Process process = null;
        try {
            Files.writeString(project, source, StandardCharsets.UTF_8);
            process = new ProcessBuilder(command(project, token, buildPath))
                    .redirectErrorStream(true)
                    .start();
            final CompletableFuture<Integer> port = new CompletableFuture<>();
            final StringBuilder output = new StringBuilder();
            final Process owned = process;
            Thread.ofVirtual().name("railix-development-output-" + generation).start(() ->
                    drain(owned, port, output)
            );
            final int readyPort;
            try {
                readyPort = port.get(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final Exception exception) {
                throw new IOException("Development application did not become ready. " + bounded(output), exception);
            }
            Files.delete(project);
            final String fingerprint = fingerprint(executableSource);
            final URI baseUri = URI.create("http://127.0.0.1:" + readyPort);
            final HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
            return new DevelopmentApplication(
                    fingerprint,
                    System.currentTimeMillis(),
                    buildPath,
                    token,
                    process,
                    baseUri,
                    client
            );
        } catch (final IOException | RuntimeException | Error exception) {
            rollback(process, project, exception);
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
        if (closed.get() || !process.isAlive()) {
            return new Response(503, "{\"status\":\"unavailable\"}");
        }
        final HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("X-Railix-Test", Boolean.toString(test))
                .POST(HttpRequest.BodyPublishers.ofByteArray(context))
                .build();
        try {
            final HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return new Response(response.statusCode(), response.body());
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Response(409, "{\"status\":\"cancelled\"}");
        }
    }

    RailixValue.ObjectValue snapshot() {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("state", RailixValue.string(process.isAlive() && !closed.get() ? "running" : "stopped"));
        value.put("fingerprint", RailixValue.string(fingerprint));
        value.put("built_at", RailixValue.number(builtAt));
        value.put("build_path", RailixValue.string(buildPath));
        value.put("pid", RailixValue.number(process.pid()));
        return RailixValue.object(value);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            boolean interrupted = Thread.interrupted();
            try {
                if (!terminate(process)) {
                    closed.set(false);
                    throw new IllegalStateException(
                            "Development application process " + process.pid() + " did not terminate."
                    );
                }
                interrupted |= Thread.interrupted();
                client.close();
                interrupted |= Thread.interrupted();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static List<String> command(final Path project, final String token, final String buildPath) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                buildPath,
                RailixMain.class.getName(),
                "application",
                project.toString(),
                token
        );
    }

    private static void drain(
            final Process process,
            final CompletableFuture<Integer> port,
            final StringBuilder output
    ) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < OUTPUT_LIMIT) {
                        output.append(line).append('\n');
                    }
                }
                if (line.startsWith("RAILIX_READY ")) {
                    port.complete(Integer.parseInt(line.substring("RAILIX_READY ".length())));
                }
            }
            port.completeExceptionally(new IOException("Development application exited before readiness."));
        } catch (final IOException | RuntimeException exception) {
            port.completeExceptionally(exception);
        }
    }

    private static String bounded(final StringBuilder output) {
        synchronized (output) {
            return output.toString().strip();
        }
    }

    private static String fingerprint(final String source) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))
            );
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static boolean terminate(final Process process) {
        if (!process.isAlive()) {
            return true;
        }
        boolean interrupted = false;
        process.destroy();
        for (int attempt = 0; process.isAlive() && attempt < 2; attempt++) {
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !process.isAlive();
    }

    private static void rollback(
            final Process process,
            final Path project,
            final Throwable failure
    ) {
        if (process != null && !terminate(process)) {
            failure.addSuppressed(new IOException(
                    "Development application process " + process.pid() + " did not terminate."
            ));
        }
        try {
            Files.deleteIfExists(project);
        } catch (final IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    record Response(int status, String body) {
    }
}
