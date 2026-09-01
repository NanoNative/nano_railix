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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Owns real generated application artifacts and their forked development processes for tests. */
final class DevelopmentRuntimeGeneratedProcess {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);

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
        try (ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.build(project, compiled)) {
            return new Build(project, compiled, publication.artifact());
        }
    }

    static Child launch(final Build build) throws IOException {
        if (build == null) {
            throw new IllegalArgumentException("Generated build cannot be Java null.");
        }
        final ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.reserve(build.artifact());
        return Child.start(RailixPackageIT.instrument(new ProcessBuilder(
                java(),
                "-jar",
                build.artifact().jar().toString()
        )), publication, true);
    }

    static Child launchMain(final Build build, final String mainClass) throws IOException {
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("Forked main class must be non-blank.");
        }
        if (build == null) {
            throw new IllegalArgumentException("Generated build cannot be Java null.");
        }
        final ApplicationBuilder.DevelopmentBuild publication = ApplicationBuilder.reserve(build.artifact());
        return Child.start(RailixPackageIT.instrument(new ProcessBuilder(
                java(),
                "-cp",
                build.artifact().jar().toString(),
                mainClass
        )), publication, false);
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
        private final ApplicationBuilder.DevelopmentBuild publication;
        private final boolean handoffOnReady;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(OUTPUT_LIMIT);
        private final ByteArrayOutputStream error = new ByteArrayOutputStream(OUTPUT_LIMIT);
        private final Thread outputThread;
        private final Thread errorThread;
        private final Map<Long, ProcessHandle> ownedProcesses = new LinkedHashMap<>();
        private boolean cleanupComplete;
        private boolean activated;
        private String startupToken = "";
        private URI ready;
        private Socket activationCallback;

        private Child(
                final Process process,
                final ServerSocket readiness,
                final ApplicationBuilder.DevelopmentBuild publication,
                final boolean handoffOnReady
        ) {
            this.process = process;
            this.readiness = readiness;
            this.publication = publication;
            this.handoffOnReady = handoffOnReady;
            owner = process.getOutputStream();
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Thread createdOutput = null;
            Thread createdError = null;
            try {
                createdOutput = Thread.ofVirtual().name("railix-test-runtime-output").unstarted(() ->
                        read(process.getInputStream(), output)
                );
                createdError = Thread.ofVirtual().name("railix-test-runtime-error").unstarted(() ->
                        read(process.getErrorStream(), error)
                );
                createdOutput.start();
                createdError.start();
            } catch (final RuntimeException | Error failure) {
                close(process.getInputStream());
                close(process.getErrorStream());
                final boolean outputStopped = stopStartedThread(createdOutput);
                final boolean errorStopped = stopStartedThread(createdError);
                if (!outputStopped || !errorStopped) {
                    failure.addSuppressed(new IllegalStateException(
                            "Generated application output reader did not terminate after startup failure."
                    ));
                }
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
                throw failure;
            }
            outputThread = createdOutput;
            errorThread = createdError;
            capture(process, ownedProcesses);
        }

        private static boolean stopStartedThread(final Thread thread) {
            if (thread == null) {
                return true;
            }
            thread.interrupt();
            final Wait wait = stop(thread);
            if (wait.interrupted()) {
                Thread.currentThread().interrupt();
            }
            return wait.complete();
        }

        static Child start(
                final ProcessBuilder builder,
                final ApplicationBuilder.DevelopmentBuild publication,
                final boolean handoffOnReady
        ) throws IOException {
            final ServerSocket readiness = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Process process = null;
            try {
                process = builder.start();
                return new Child(process, readiness, publication, handoffOnReady);
            } catch (final IOException | RuntimeException | Error failure) {
                if (process != null) {
                    try {
                        process.getOutputStream().close();
                    } catch (final IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    final Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
                    final Wait stopped = stop(process, owned);
                    if (stopped.interrupted()) {
                        Thread.currentThread().interrupt();
                    }
                    if (!stopped.complete()) {
                        failure.addSuppressed(new IOException(
                                "Generated application process did not terminate: " + process.pid()
                        ));
                    }
                    close(process.getInputStream());
                    close(process.getErrorStream());
                }
                try {
                    readiness.close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    publication.close();
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
            final URI uri = awaitInactive();
            activate();
            return uri;
        }

        synchronized URI awaitInactive() throws IOException {
            if (ready != null) {
                return ready;
            }
            if (startupToken.isEmpty()) {
                throw new IOException("Generated application startup token was not sent.");
            }
            try {
                readiness.setSoTimeout((int) PROCESS_TIMEOUT.toMillis());
                final Socket callback = readiness.accept();
                boolean retained = false;
                try {
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
                    activationCallback = callback;
                    retained = true;
                    if (handoffOnReady) {
                        publication.close();
                    }
                    return ready;
                } finally {
                    if (!retained) {
                        callback.close();
                    }
                }
            } catch (final IOException | RuntimeException exception) {
                throw new IOException("Generated application did not become ready. " + output() + error(), exception);
            } finally {
                readiness.close();
            }
        }

        synchronized Child activate() throws IOException {
            if (activated) {
                return this;
            }
            if (ready == null) {
                throw new IOException("Generated application is not ready for activation.");
            }
            input(("ACTIVATE " + startupToken + "\n").getBytes(StandardCharsets.UTF_8));
            final String frame = readFrame(activationCallback);
            if (!MessageDigest.isEqual(
                    ("ACTIVATED " + startupToken).getBytes(StandardCharsets.UTF_8),
                    frame.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new IOException("Generated application returned an invalid activation frame.");
            }
            activationCallback.setSoTimeout(1_000);
            if (activationCallback.getInputStream().read() != -1) {
                throw new IOException("Generated application retained its activation callback after acknowledgement.");
            }
            activationCallback.close();
            activated = true;
            return this;
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
            return requestAsync(uri, token, path, body, traceId(path));
        }

        CompletableFuture<HttpResponse<String>> requestAsync(
                final URI uri,
                final String token,
                final String path,
                final String body,
                final String traceId
        ) {
            return client.sendAsync(
                    requestMessage(uri, token, "POST", path, body, "true", traceId),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        }

        CompletableFuture<HttpResponse<InputStream>> requestStreamAsync(
                final URI uri,
                final String token,
                final String path,
                final String body,
                final String traceId
        ) {
            return client.sendAsync(
                    requestMessage(uri, token, "POST", path, body, "true", traceId),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
        }

        CompletableFuture<HttpResponse<InputStream>> requestStreamAsync(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test
        ) {
            return client.sendAsync(
                    requestMessage(uri, token, method, path, body, test),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
        }

        CompletableFuture<HttpResponse<String>> requestAsync(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test
        ) {
            return client.sendAsync(
                    requestMessage(uri, token, method, path, body, test),
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
                publication.close();
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
        public synchronized void close() {
            if (cleanupComplete) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            RuntimeException failure = null;
            try {
                readiness.close();
            } catch (final IOException exception) {
                failure = merge(failure, new IllegalStateException(
                        "Generated application readiness socket did not close.", exception
                ));
            }
            if (activationCallback != null) {
                try {
                    activationCallback.close();
                } catch (final IOException exception) {
                    failure = merge(failure, new IllegalStateException(
                            "Generated application activation callback did not close.", exception
                    ));
                }
            }
            try {
                publication.close();
            } catch (final IOException exception) {
                failure = merge(failure, new IllegalStateException(
                        "Generated application publication lease did not close.", exception
                ));
            }
            try {
                owner.close();
            } catch (final IOException exception) {
                failure = merge(failure, new IllegalStateException(
                        "Generated application ownership pipe did not close.", exception
                ));
            }
            final Wait processWait = stop(process, ownedProcesses);
            interrupted |= processWait.interrupted();
            close(process.getInputStream());
            close(process.getErrorStream());
            final Wait outputWait = stop(outputThread);
            final Wait errorWait = stop(errorThread);
            interrupted |= outputWait.interrupted() || errorWait.interrupted();
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
            if (!processWait.complete()) {
                failure = merge(failure, new IllegalStateException(
                        "Generated application process did not terminate: " + process.pid()
                ));
            }
            if (!outputWait.complete() || !errorWait.complete()) {
                failure = merge(failure, new IllegalStateException(
                        "Generated application output reader did not terminate."
                ));
            }
            if (failure == null) {
                cleanupComplete = true;
            }
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
            if (failure != next) {
                failure.addSuppressed(next);
            }
            return failure;
        }

        private static HttpRequest requestMessage(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test
        ) {
            return requestMessage(uri, token, method, path, body, test, traceId(path));
        }

        private static HttpRequest requestMessage(
                final URI uri,
                final String token,
                final String method,
                final String path,
                final String body,
                final String test,
                final String traceId
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
            if (traceId != null) {
                request.header("X-Railix-Trace-Id", traceId);
            }
            return request.method(
                    method,
                    body.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            ).build();
        }

        private static String traceId(final String path) {
            return path.startsWith("/v1/trace/") ? UUID.randomUUID().toString() : null;
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

        private static Wait stop(
                final Process process,
                final Map<Long, ProcessHandle> owned
        ) {
            boolean interrupted = false;
            capture(process, owned);
            Wait wait = waitFor(process, owned);
            interrupted |= wait.interrupted();
            if (!wait.complete()) {
                destroy(owned, false);
                wait = waitFor(process, owned);
                interrupted |= wait.interrupted();
            }
            if (!wait.complete()) {
                destroy(owned, true);
                wait = waitFor(process, owned);
                interrupted |= wait.interrupted();
            }
            return new Wait(wait.complete(), interrupted);
        }

        private static Wait waitFor(
                final Process process,
                final Map<Long, ProcessHandle> owned
        ) {
            final long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
            boolean interrupted = false;
            while (alive(owned) && System.nanoTime() < deadline) {
                capture(process, owned);
                try {
                    Thread.sleep(10);
                } catch (final InterruptedException ignored) {
                    interrupted = true;
                }
            }
            capture(process, owned);
            return new Wait(!alive(owned), interrupted);
        }

        private static void capture(
                final Process process,
                final Map<Long, ProcessHandle> owned
        ) {
            process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), handle));
            owned.putIfAbsent(process.pid(), process.toHandle());
        }

        private static boolean alive(final Map<Long, ProcessHandle> owned) {
            return owned.values().stream().anyMatch(ProcessHandle::isAlive);
        }

        private static void destroy(
                final Map<Long, ProcessHandle> owned,
                final boolean force
        ) {
            owned.values().stream().filter(ProcessHandle::isAlive).toList().reversed().forEach(handle -> {
                if (force) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            });
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
