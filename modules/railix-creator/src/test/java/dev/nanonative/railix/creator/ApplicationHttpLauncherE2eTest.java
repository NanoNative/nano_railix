package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationHttpLauncherE2eTest {
    @Test
    void noArgumentsStartAnHttpOnlyApplicationUntilCallerInterruption() throws Exception {
        final int port = freePort();
        final StepCatalog catalog = StepCatalog.of(constantStep());
        final RunningApplication application = start(flow(http(port)), catalog);
        try {
            application.awaitReady();
            final HttpResponse<String> response = post(port);

            application.interruptAndJoin();
            assertThat(new LaunchObservation(
                    application.exitCode(),
                    application.stdout(),
                    application.stderr(),
                    response.statusCode(),
                    response.body(),
                    application.flowClosed(),
                    application.lockClosed()
            )).isEqualTo(new LaunchObservation(
                    130,
                    "{\"httpUrl\":\"http://127.0.0.1:" + port
                            + "/\",\"status\":\"application-ready\"}\n",
                    "",
                    200,
                    """
                    {"outputs":{"text":"ready"},"status":"succeeded","steps":[{"outcome":"ok","step":"step"}]}\
                    """,
                    true,
                    true
            ));
        } finally {
            application.stopIfRunning();
        }
    }

    @Test
    void callerInterruptionReturnsWhileAnHttpStepIgnoresCancellation() throws Exception {
        final int port = freePort();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RunningApplication application = start(
                flow(http(port)),
                StepCatalog.of(ignoringCancellationStep(entered, interrupted, release))
        );
        application.awaitReady();
        final Thread request = Thread.startVirtualThread(() -> {
            try {
                post(port);
            } catch (final Exception ignored) {
                // Closing the listener owns the in-flight transport.
            }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            application.interruptAndJoin();

            assertThat(List.of(
                    application.exitCode(),
                    interrupted.await(2, TimeUnit.SECONDS),
                    canBind(port),
                    application.flowClosed(),
                    application.lockClosed()
            )).containsExactly(130, true, true, true, true);
        } finally {
            release.countDown();
            application.stopIfRunning();
            request.join(Duration.ofSeconds(2));
        }
        assertThat(request.isAlive()).isFalse();
    }

    @Test
    void listenerBindsBeforeStartupAndAcceptsOnlyAfterStartup() throws Exception {
        final int port = freePort();
        final CountDownLatch startupEntered = new CountDownLatch(1);
        final CountDownLatch releaseStartup = new CountDownLatch(1);
        final StepCatalog catalog = StepCatalog.of(blockingStep(startupEntered, releaseStartup));
        final RunningApplication application = start(flow(
                "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}," + http(port).substring(1)
        ), catalog);
        try {
            assertThat(startupEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(canBind(port)).isFalse();
            assertThat(post(port).statusCode()).isEqualTo(503);

            releaseStartup.countDown();
            application.awaitReady();
            assertThat(post(port).statusCode()).isEqualTo(200);
        } finally {
            releaseStartup.countDown();
            application.stopIfRunning();
        }
    }

    @Test
    void startupFailureClosesThePreboundListener() throws Exception {
        final int port = freePort();
        final StepCatalog catalog = StepCatalog.of(brokenStep());

        final Invocation invocation = invoke(flow(
                "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}," + http(port).substring(1)
        ), catalog, List.of());

        assertThat(new FailureObservation(
                invocation.exitCode(),
                invocation.stderr().contains("\"status\":\"run-failed\""),
                canBind(port)
        )).isEqualTo(new FailureObservation(3, true, true));
    }

    @Test
    void bindFailurePreventsStartupExecution() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(countingStep(runs));
        try (ServerSocket occupied = socket(freePort())) {
            final int port = occupied.getLocalPort();

            final Invocation invocation = invoke(flow(
                    "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}," + http(port).substring(1)
            ), catalog, List.of());

            assertThat(new BindFailureObservation(
                    invocation.exitCode(),
                    invocation.stderr().contains("\"code\":\"HTTP_BIND_FAILED\""),
                    runs.get()
            )).isEqualTo(new BindFailureObservation(4, true, 0));
        }
    }

    @Test
    void cliInvocationNeverStartsDeclaredHttpIngress() throws Exception {
        final int port = freePort();
        final StepCatalog catalog = StepCatalog.of(constantStep());
        final String triggers = """
                [
                  {"id":"command","type":"cli","config":{"stdin":false}},
                  {"id":"http","type":"http","config":{"port":%d,"path":"/event"}}
                ]
                """.formatted(port);

        final Invocation invocation = invoke(flow(triggers), catalog, List.of("cli", "command"));

        assertThat(new CliObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr(),
                canBind(port)
        )).isEqualTo(new CliObservation(0, "{\"text\":\"ready\"}\n", "", true));
    }

    @Test
    void lockCheckNeverStartsDeclaredHttpIngress() throws Exception {
        final int port = freePort();
        final StepCatalog catalog = StepCatalog.of(constantStep());

        final Invocation invocation = invoke(flow(http(port)), catalog, List.of("lock", "--check"));

        assertThat(new AdminObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr(),
                canBind(port)
        )).isEqualTo(new AdminObservation(0, "", "", true));
    }

    @Test
    void startupOnlyApplicationStillRunsAndReturns() throws Exception {
        final StepCatalog catalog = StepCatalog.of(constantStep());

        assertThat(invoke(flow(
                "[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"
        ), catalog, List.of())).isEqualTo(new Invocation(
                0,
                "{\"text\":\"ready\"}\n",
                ""
        ));
    }

    private static RunningApplication start(final String flow, final StepCatalog catalog) {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(flow, catalog);
        final TrackingInputStream flowSource = new TrackingInputStream(flow);
        final TrackingInputStream lockSource = new TrackingInputStream(compiled.lock());
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        final Thread thread = Thread.ofVirtual().start(() -> exitCode.set(RailixMain.executeApplication(
                List.of(),
                flowSource,
                lockSource,
                catalog,
                InputStream.nullInputStream(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        )));
        return new RunningApplication(thread, exitCode, stdout, stderr, flowSource, lockSource);
    }

    private static Invocation invoke(
            final String flow,
            final StepCatalog catalog,
            final List<String> arguments
    ) {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(flow, catalog);
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode = RailixMain.executeApplication(
                arguments,
                source(flow),
                source(compiled.lock()),
                catalog,
                InputStream.nullInputStream(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static StepDefinition constantStep() {
        return StepDefinition.named("step", "1.0.0")
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", RailixValue.string("ready")));
    }

    private static StepDefinition brokenStep() {
        return StepDefinition.named("step", "1.0.0")
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition countingStep(final AtomicInteger runs) {
        return StepDefinition.named("step", "1.0.0")
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> {
                    runs.incrementAndGet();
                    return StepResult.outcome("ok").output("text", RailixValue.string("ready"));
                });
    }

    private static StepDefinition blockingStep(
            final CountDownLatch entered,
            final CountDownLatch release
    ) {
        return StepDefinition.named("step", "1.0.0")
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> {
                    entered.countDown();
                    release.await();
                    return StepResult.outcome("ok").output("text", RailixValue.string("ready"));
                });
    }

    private static StepDefinition ignoringCancellationStep(
            final CountDownLatch entered,
            final CountDownLatch interrupted,
            final CountDownLatch release
    ) {
        return StepDefinition.named("step", "1.0.0")
                .output("text", ValueShape.STRING)
                .outcome("ok")
                .run(input -> {
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (final InterruptedException ignored) {
                            interrupted.countDown();
                        }
                    }
                    return StepResult.outcome("ok").output("text", RailixValue.string("ready"));
                });
    }

    private static String http(final int port) {
        return """
                [{"id":"http","type":"http","config":{"port":%d,"path":"/event"}}]\
                """.formatted(port);
    }

    private static String flow(final String triggers) {
        return """
                {
                  "id":"http-launcher",
                  "triggers":%s,
                  "entry":"step",
                  "inputs":{},
                  "outputs":{"text":"string"},
                  "steps":[{"id":"step","use":"step","config":{},"on":{"ok":"end"}}],
                  "connections":[{"from":"step.text","to":"output.text"}]
                }
                """.formatted(triggers);
    }

    private static HttpResponse<String> post(final int port) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/event"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = socket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean canBind(final int port) {
        try (ServerSocket ignored = socket(port)) {
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private static ServerSocket socket(final int port) throws IOException {
        final ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                port
        ));
        return socket;
    }

    private static ByteArrayInputStream source(final String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private record LaunchObservation(
            int exitCode,
            String stdout,
            String stderr,
            int httpStatus,
            String httpBody,
            boolean flowClosed,
            boolean lockClosed
    ) {
    }

    private record FailureObservation(int exitCode, boolean failed, boolean portReleased) {
    }

    private record BindFailureObservation(int exitCode, boolean bindFailed, int startupRuns) {
    }

    private record CliObservation(int exitCode, String stdout, String stderr, boolean portFree) {
    }

    private record AdminObservation(int exitCode, String stdout, String stderr, boolean portFree) {
    }

    private static final class RunningApplication {
        private final Thread thread;
        private final AtomicInteger exitCode;
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;
        private final TrackingInputStream flow;
        private final TrackingInputStream lock;

        private RunningApplication(
                final Thread thread,
                final AtomicInteger exitCode,
                final ByteArrayOutputStream stdout,
                final ByteArrayOutputStream stderr,
                final TrackingInputStream flow,
                final TrackingInputStream lock
        ) {
            this.thread = thread;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.flow = flow;
            this.lock = lock;
        }

        private void awaitReady() throws InterruptedException {
            final long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!stdout().contains("\"status\":\"application-ready\"")
                    && thread.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(stdout()).contains("\"status\":\"application-ready\"");
        }

        private void interruptAndJoin() throws InterruptedException {
            thread.interrupt();
            thread.join(Duration.ofSeconds(3));
            assertThat(thread.isAlive()).isFalse();
        }

        private void stopIfRunning() throws InterruptedException {
            if (thread.isAlive()) {
                interruptAndJoin();
            }
        }

        private int exitCode() {
            return exitCode.get();
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        private boolean flowClosed() {
            return flow.closed.get();
        }

        private boolean lockClosed() {
            return lock.closed.get();
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingInputStream(final String source) {
            super(source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
