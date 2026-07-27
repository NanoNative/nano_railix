package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationRuntimeLauncherE2eTest {
    @Test
    void noArgumentsStartASocketOnlyApplicationUntilCallerInterruption() throws Exception {
        final int port = freePort();
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(countingStep(runs));
        final RunningApplication application = start(flow(
                socket(port),
                "{}"
        ), catalog);
        try {
            assertThat(application.awaitOutput("\"socketAddress\":\"127.0.0.1:" + port + "\"")).isTrue();
            try (Socket client = connect(port)) {
                final DataOutputStream output = new DataOutputStream(client.getOutputStream());
                final byte[] event = "{}".getBytes(StandardCharsets.UTF_8);
                output.writeInt(event.length);
                output.write(event);
                output.flush();
                final DataInputStream input = new DataInputStream(client.getInputStream());
                final String reply = new String(
                        input.readNBytes(input.readInt()),
                        StandardCharsets.UTF_8
                );
                assertThat(reply).contains("\"status\":\"succeeded\"");
            }
        } finally {
            application.interruptAndJoin();
        }

        assertThat(new LauncherStop(
                application.exitCode(),
                runs.get(),
                canBind(port)
        )).isEqualTo(new LauncherStop(130, 1, true));
    }

    @Test
    void noArgumentsStartAScheduledOnlyApplicationAndAnnounceBeforeItsFirstTick() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(countingStep(runs));
        final RunningApplication application = start(flow(
                schedule("refresh", 60_000, 0, 1),
                "{}"
        ), catalog);
        try {
            assertThat(application.awaitOutput("\"scheduledTriggers\":[\"refresh\"]")).isTrue();
            assertThat(await(() -> runs.get() == 1, Duration.ofSeconds(2))).isTrue();
        } finally {
            application.interruptAndJoin();
        }

        assertThat(new ScheduledLauncherObservation(
                application.exitCode(),
                application.stdout().lines().findFirst().orElse(""),
                runs.get()
        )).isEqualTo(new ScheduledLauncherObservation(
                130,
                "{\"scheduledTriggers\":[\"refresh\"],\"status\":\"application-ready\"}",
                1
        ));
    }

    @Test
    void socketBindFailurePreventsStartupExecution() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(countingStep(runs));
        try (ServerSocket occupied = loopbackServer()) {
            final Invocation invocation = invoke(
                    flow(startup() + "," + socket(occupied.getLocalPort()), "{}"),
                    catalog,
                    List.of()
            );

            assertThat(new BindFailure(
                    invocation.exitCode(),
                    invocation.stderr().contains("\"code\":\"SOCKET_BIND_FAILED\""),
                    runs.get()
            )).isEqualTo(new BindFailure(4, true, 0));
        }
    }

    @Test
    void socketBindFailureRollsBackAnAlreadyBoundHttpListener() throws Exception {
        final int httpPort = freePort();
        final StepCatalog catalog = StepCatalog.of(countingStep(new AtomicInteger()));
        try (ServerSocket occupied = loopbackServer()) {
            final Invocation invocation = invoke(
                    flow(http(httpPort) + "," + socket(occupied.getLocalPort()), "{}"),
                    catalog,
                    List.of()
            );

            assertThat(new RollbackObservation(
                    invocation.exitCode(),
                    invocation.stderr().contains("\"code\":\"SOCKET_BIND_FAILED\""),
                    canBind(httpPort)
            )).isEqualTo(new RollbackObservation(4, true, true));
        }
    }

    @Test
    void startupFailureClosesSocketAndPreventsScheduledTicks() throws Exception {
        final int port = freePort();
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(StepDefinition.named("action", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    runs.incrementAndGet();
                    return StepResult.outcome("missing");
                }));

        final Invocation invocation = invoke(
                flow(startup() + "," + socket(port) + "," + schedule("refresh", 1, 0, 1), "{}"),
                catalog,
                List.of()
        );
        TimeUnit.MILLISECONDS.sleep(40);

        assertThat(new StartupRollback(
                invocation.exitCode(),
                runs.get(),
                canBind(port)
        )).isEqualTo(new StartupRollback(3, 1, true));
    }

    @Test
    void cliInvocationNeverStartsDeclaredSocketOrScheduleIngress() throws Exception {
        final int port = freePort();
        final AtomicInteger runs = new AtomicInteger();
        final StepCatalog catalog = StepCatalog.of(countingStep(runs));

        final Invocation invocation = invoke(
                flow(
                        cli() + "," + socket(port) + "," + schedule("refresh", 1, 0, 1),
                        "{}"
                ),
                catalog,
                List.of("cli", "command")
        );
        TimeUnit.MILLISECONDS.sleep(40);

        assertThat(new CliIsolation(
                invocation.exitCode(),
                runs.get(),
                canBind(port)
        )).isEqualTo(new CliIsolation(0, 1, true));
    }

    private static RunningApplication start(
            final String flow,
            final StepCatalog catalog
    ) {
        final String lock = ((CompileResult.Compiled) FlowCompiler.compile(flow, catalog)).lock();
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final AtomicReference<Integer> exitCode = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> exitCode.set(RailixMain.executeApplication(
                List.of(),
                stream(flow),
                stream(lock),
                catalog,
                InputStreamHolder.EMPTY,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        )));
        return new RunningApplication(thread, exitCode, stdout, stderr);
    }

    private static Invocation invoke(
            final String flow,
            final StepCatalog catalog,
            final List<String> arguments
    ) {
        final String lock = ((CompileResult.Compiled) FlowCompiler.compile(flow, catalog)).lock();
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode = RailixMain.executeApplication(
                arguments,
                stream(flow),
                stream(lock),
                catalog,
                InputStreamHolder.EMPTY,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static StepDefinition countingStep(final AtomicInteger runs) {
        return StepDefinition.named("action", "1.0.0")
                .outcome("ok")
                .run(input -> {
                    runs.incrementAndGet();
                    return StepResult.outcome("ok");
                });
    }

    private static String flow(final String triggers, final String inputs) {
        return """
                {
                  "id":"runtime-app",
                  "triggers":[%s],
                  "entry":"action",
                  "inputs":%s,
                  "outputs":{},
                  "steps":[{"id":"action","use":"action","config":{},"on":{"ok":"end"}}],
                  "connections":[]
                }
                """.formatted(triggers, inputs);
    }

    private static String socket(final int port) {
        return """
                {"id":"events","type":"socket","config":{
                  "port":%d,"timeoutMillis":30000,"maxConnections":32
                }}\
                """.formatted(port);
    }

    private static String schedule(
            final String id,
            final long interval,
            final long initialDelay,
            final int maxConcurrentRuns
    ) {
        return """
                {"id":"%s","type":"scheduled","config":{
                  "intervalMillis":%d,"initialDelayMillis":%d,"maxConcurrentRuns":%d
                }}\
                """.formatted(id, interval, initialDelay, maxConcurrentRuns);
    }

    private static String http(final int port) {
        return """
                {"id":"web","type":"http","config":{"port":%d,"path":"/events"}}\
                """.formatted(port);
    }

    private static String startup() {
        return "{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}";
    }

    private static String cli() {
        return "{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}";
    }

    private static ByteArrayInputStream stream(final String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    private static Socket connect(final int port) throws IOException {
        final Socket socket = new Socket(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                port
        );
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = loopbackServer()) {
            return socket.getLocalPort();
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        );
    }

    private static boolean canBind(final int port) {
        try (ServerSocket ignored = new ServerSocket(
                port,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private static boolean await(
            final BooleanSupplier condition,
            final Duration timeout
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private record LauncherStop(int exitCode, int runs, boolean portReleased) {
    }

    private record ScheduledLauncherObservation(int exitCode, String firstLine, int runs) {
    }

    private record BindFailure(int exitCode, boolean bindFailed, int startupRuns) {
    }

    private record RollbackObservation(int exitCode, boolean bindFailed, boolean httpPortReleased) {
    }

    private record StartupRollback(int exitCode, int runs, boolean portReleased) {
    }

    private record CliIsolation(int exitCode, int runs, boolean portFree) {
    }

    private static final class RunningApplication {
        private final Thread thread;
        private final AtomicReference<Integer> exitCode;
        private final ByteArrayOutputStream stdout;
        private final ByteArrayOutputStream stderr;

        private RunningApplication(
                final Thread thread,
                final AtomicReference<Integer> exitCode,
                final ByteArrayOutputStream stdout,
                final ByteArrayOutputStream stderr
        ) {
            this.thread = thread;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private boolean awaitOutput(final String text) throws InterruptedException {
            return await(() -> stdout().contains(text) || !thread.isAlive(), Duration.ofSeconds(2))
                    && stdout().contains(text);
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        private int exitCode() {
            return exitCode.get();
        }

        private RunningApplication interruptAndJoin() throws InterruptedException {
            thread.interrupt();
            thread.join(2_000);
            assertThat(thread.isAlive()).isFalse();
            return this;
        }
    }

    private static final class InputStreamHolder {
        private static final ByteArrayInputStream EMPTY = new ByteArrayInputStream(new byte[0]);

        private InputStreamHolder() {
        }
    }
}
