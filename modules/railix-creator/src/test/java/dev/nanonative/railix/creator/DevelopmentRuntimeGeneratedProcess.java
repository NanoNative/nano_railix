package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns real generated application artifacts and their forked development processes for tests. */
final class DevelopmentRuntimeGeneratedProcess {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);

    private DevelopmentRuntimeGeneratedProcess() {
    }

    static Build build(
            final Path workspace,
            final String source,
            final List<StepDefinition> definitions,
            final Class<?>... bundleClasses
    ) throws IOException {
        final StepCatalog catalog = GeneratedApplicationFixture.installedCatalog(
                workspace,
                definitions,
                bundleClasses
        );
        return build(workspace, source, catalog);
    }

    static Build build(
            final Path workspace,
            final String source,
            final StepCatalog catalog
    ) throws IOException {
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, source, StandardCharsets.UTF_8);
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IOException("Development runtime conformance project did not compile: " + result);
        }
        return new Build(project, compiled, ApplicationBuilder.build(project, compiled));
    }

    static Child launch(final Build build) throws IOException {
        if (build == null) {
            throw new IllegalArgumentException("Generated build cannot be Java null.");
        }
        return Child.start(RailixPackageIT.instrument(new ProcessBuilder(
                java(),
                "-jar",
                build.artifact().jar().toString()
        )));
    }

    static Child launchMain(final Build build, final String mainClass) throws IOException {
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("Forked main class must be non-blank.");
        }
        if (build == null) {
            throw new IllegalArgumentException("Generated build cannot be Java null.");
        }
        return Child.start(RailixPackageIT.instrument(new ProcessBuilder(
                java(),
                "-cp",
                build.artifact().jar().toString(),
                mainClass
        )));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    record Build(
            Path project,
            CompileResult.Compiled compiled,
            ApplicationBuilder.Artifact artifact
    ) {
        Build {
            if (project == null || compiled == null || artifact == null) {
                throw new IllegalArgumentException("Generated build must be complete.");
            }
        }
    }

    static final class Child implements AutoCloseable {
        private static final int OUTPUT_LIMIT = 16_384;

        private final Process process;
        private final OutputStream owner;
        private final HttpClient client;
        private final ServerSocket readiness;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(OUTPUT_LIMIT);
        private final ByteArrayOutputStream error = new ByteArrayOutputStream(OUTPUT_LIMIT);
        private final Thread outputThread;
        private final Thread errorThread;
        private final AtomicBoolean closed = new AtomicBoolean();
        private String startupToken = "";
        private URI ready;

        private Child(final Process process, final ServerSocket readiness) {
            this.process = process;
            this.readiness = readiness;
            owner = process.getOutputStream();
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            outputThread = Thread.ofVirtual().name("railix-test-runtime-output").start(() ->
                    read(process.getInputStream(), output)
            );
            errorThread = Thread.ofVirtual().name("railix-test-runtime-error").start(() ->
                    read(process.getErrorStream(), error)
            );
        }

        static Child start(final ProcessBuilder builder) throws IOException {
            final ServerSocket readiness = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            try {
                return new Child(builder.start(), readiness);
            } catch (final IOException | RuntimeException | Error failure) {
                try {
                    readiness.close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        Child token(final String token) throws IOException {
            startupToken = token;
            return input(("START " + token + " " + readiness.getLocalPort() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        Child input(final byte[] bytes) throws IOException {
            owner.write(bytes);
            owner.flush();
            return this;
        }

        synchronized URI awaitReady() throws IOException {
            if (ready != null) {
                return ready;
            }
            if (startupToken.isEmpty()) {
                throw new IOException("Generated application startup token was not sent.");
            }
            try {
                readiness.setSoTimeout((int) PROCESS_TIMEOUT.toMillis());
                try (Socket callback = readiness.accept()) {
                    callback.setSoTimeout((int) PROCESS_TIMEOUT.toMillis());
                    final String frame = readFrame(callback);
                    final String prefix = "READY " + startupToken + " ";
                    if (!frame.startsWith(prefix)
                            || !MessageDigest.isEqual(
                            startupToken.getBytes(StandardCharsets.UTF_8),
                            frame.substring("READY ".length(), frame.lastIndexOf(' '))
                                    .getBytes(StandardCharsets.UTF_8)
                    )) {
                        throw new IOException("Generated application returned an invalid readiness frame.");
                    }
                    final int port = Integer.parseInt(frame.substring(prefix.length()));
                    if (port < 1 || port > 65_535) {
                        throw new IOException("Generated application returned an invalid readiness port.");
                    }
                    ready = URI.create("http://127.0.0.1:" + port);
                    return ready;
                }
            } catch (final IOException | RuntimeException exception) {
                throw new IOException("Generated application did not become ready. " + output() + error(), exception);
            } finally {
                readiness.close();
            }
        }

        HttpResponse<String> request(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test
        ) throws IOException, InterruptedException {
            return client.send(requestMessage(uri, token, method, path, body, test),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        CompletableFuture<HttpResponse<String>> requestAsync(
                final URI uri,
                final String token,
                final String path,
                final String body
        ) {
            return client.sendAsync(
                    requestMessage(uri, token, "POST", path, body, "true"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        }

        void closeOwner() throws IOException {
            owner.close();
        }

        int awaitExit() throws IOException {
            try {
                if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("Generated application did not exit within " + PROCESS_TIMEOUT + ".");
                }
                join(outputThread);
                join(errorThread);
                return process.exitValue();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for generated application exit.", exception);
            }
        }

        Process process() {
            return process;
        }

        String output() {
            synchronized (output) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }

        String error() {
            synchronized (error) {
                return error.toString(StandardCharsets.UTF_8);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            try {
                readiness.close();
            } catch (final IOException ignored) {
                // Process shutdown below is authoritative.
            }
            try {
                owner.close();
            } catch (final IOException ignored) {
                // Process shutdown below is authoritative.
            }
            final Wait processWait = stop(process);
            interrupted |= processWait.interrupted();
            close(process.getInputStream());
            close(process.getErrorStream());
            final Wait outputWait = stop(outputThread);
            final Wait errorWait = stop(errorThread);
            interrupted |= outputWait.interrupted() || errorWait.interrupted();
            client.shutdownNow();
            client.close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!processWait.complete()) {
                throw new IllegalStateException("Generated application process did not terminate: " + process.pid());
            }
            if (!outputWait.complete() || !errorWait.complete()) {
                throw new IllegalStateException("Generated application output reader did not terminate.");
            }
        }

        private static HttpRequest requestMessage(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test
        ) {
            final HttpRequest.Builder request = HttpRequest.newBuilder(uri.resolve(path))
                    .timeout(PROCESS_TIMEOUT)
                    .header("Content-Type", "application/json");
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (test != null) {
                request.header("X-Railix-Test", test);
            }
            return request.method(
                    method,
                    body.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            ).build();
        }

        private static void read(
                final InputStream input,
                final ByteArrayOutputStream destination
        ) {
            try (input) {
                final byte[] buffer = new byte[8_192];
                for (int length = input.read(buffer); length >= 0; length = input.read(buffer)) {
                    synchronized (destination) {
                        final int retained = Math.min(length, OUTPUT_LIMIT - destination.size());
                        if (retained > 0) {
                            destination.write(buffer, 0, retained);
                        }
                    }
                }
            } catch (final IOException ignored) {
                // Process exit closes its output streams.
            }
        }

        private static String readFrame(final Socket callback) throws IOException {
            final ByteArrayOutputStream frame = new ByteArrayOutputStream(128);
            for (int index = 0; ; index++) {
                final int next = callback.getInputStream().read();
                if (next < 0) {
                    throw new IOException("Readiness callback ended before a complete frame.");
                }
                if (next == '\n') {
                    return frame.toString(StandardCharsets.UTF_8);
                }
                if (index == 128) {
                    throw new IOException("Readiness callback frame exceeds 128 bytes.");
                }
                frame.write(next);
            }
        }

        private static void join(final Thread thread) throws InterruptedException {
            thread.join(PROCESS_TIMEOUT);
            if (thread.isAlive()) {
                throw new IllegalStateException("Generated application output reader did not terminate.");
            }
        }

        private static Wait stop(final Process process) {
            boolean interrupted = false;
            Wait wait = waitFor(process);
            interrupted |= wait.interrupted();
            if (!wait.complete()) {
                process.destroy();
                wait = waitFor(process);
                interrupted |= wait.interrupted();
            }
            if (!wait.complete()) {
                process.destroyForcibly();
                wait = waitFor(process);
                interrupted |= wait.interrupted();
            }
            return new Wait(wait.complete(), interrupted);
        }

        private static Wait waitFor(final Process process) {
            final long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            boolean interrupted = false;
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    if (process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        return new Wait(true, interrupted);
                    }
                } catch (final InterruptedException ignored) {
                    interrupted = true;
                }
            }
            return new Wait(!process.isAlive(), interrupted);
        }

        private static Wait stop(final Thread thread) {
            final long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
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

        private static void close(final InputStream input) {
            try {
                input.close();
            } catch (final IOException ignored) {
                // Process termination remains the lifecycle authority.
            }
        }

        private record Wait(boolean complete, boolean interrupted) {
        }
    }
}
