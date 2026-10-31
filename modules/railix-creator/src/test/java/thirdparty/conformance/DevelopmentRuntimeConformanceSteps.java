package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.development.DevelopmentRuntime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/** Real locked-bundle implementations used to verify the packaged development runtime. */
public final class DevelopmentRuntimeConformanceSteps {
    private static final int OVERSIZED_CONTEXT_BYTES = 1_048_577;

    /** Starts a real child before a fatal startup/cleanup failure. */
    public static final class StartupChild {
        public static StepResult pre(final StepInput configuration, final StepInput context) {
            try {
                final Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"),
                        ProcessTreeStepHandler.class.getName(), "sleep")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(Path.of(configuration.string("marker")), Long.toString(child.pid()));
            } catch (final IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            if (configuration.string("failure").equals("pre")) {
                throw new AssertionError("Fatal PRE after starting a child.");
            }
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult process(final StepInput configuration, final StepInput context) {
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult post(final StepInput configuration, final StepInput context) {
            if (configuration.string("failure").equals("post")) {
                throw new AssertionError("Fatal POST during startup rejection.");
            }
            return StepResult.outcome(context.primaryOutcome());
        }
    }

    public static final class Lifecycle {
        private Lifecycle() {
            throw new AssertionError("Lifecycle Steps are static functions.");
        }

        public static StepResult pre(final StepInput configuration, final StepInput context) {
            if (!context.values().isEmpty() || !context.options().isEmpty()) {
                throw new AssertionError("Lifecycle context must not contain an invocation payload.");
            }
            record(configuration, "pre");
            if (configuration.string("mode").equals("pre-fatal")) {
                throw new AssertionError("Requested fatal PRE failure.");
            }
            if (configuration.string("mode").equals("pre-failure")) {
                throw new IllegalStateException("Requested PRE failure.");
            }
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult process(final StepInput configuration, final StepInput context) {
            if (context.value("test") instanceof RailixValue.BooleanValue test && !test.value()) {
                record(configuration, "process");
            }
            return context.optionalValue("value")
                    .map(value -> StepResult.outcome(context.primaryOutcome()).output("value", value))
                    .orElseGet(() -> StepResult.outcome(context.primaryOutcome()));
        }

        public static StepResult post(final StepInput configuration, final StepInput context) {
            if (!context.values().isEmpty()) {
                throw new AssertionError("POST retained request data.");
            }
            record(configuration, "post");
            if (configuration.string("mode").equals("post-fatal")) {
                throw new AssertionError("Requested fatal POST failure.");
            }
            if (configuration.string("mode").equals("post-failure")) {
                throw new IllegalStateException("Requested POST failure.");
            }
            return StepResult.outcome(context.primaryOutcome());
        }

        private static void record(final StepInput configuration, final String phase) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(configuration.string("log")),
                        phase + ":" + configuration.string("name") + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (final java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
    }

    private DevelopmentRuntimeConformanceSteps() {
    }

    /** Passes a Trigger or ordinary Step to its primary outcome. */
    public static final class Pass {
        /** Creates one stateless handler. */
        public Pass() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Writes the executing generated application's process identifier through a declared path. */
    public static final class ProcessId {
        /** Creates one stateless handler. */
        public ProcessId() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.number(ProcessHandle.current().pid()));
        }
    }

    /** Records process liveness from inside the replacement application. */
    public static final class ProcessStatus {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            final long pid = ((RailixValue.NumberValue) input.value("pid")).value().longValueExact();
            return StepResult.outcome(input.primaryOutcome()).write("target",
                    RailixValue.bool(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)));
        }
    }

    /** Simulates artifact loss or denied access during process handoff, once per workspace. */
    public static final class ArtifactLoss {
        public static StepResult process(final StepInput configuration, final StepInput input)
                throws InterruptedException {
            if (!((RailixValue.BooleanValue) input.value("test")).value()) {
                return StepResult.outcome(input.primaryOutcome());
            }
            final Path workspace = Path.of(input.string("workspace"));
            try {
                try {
                    Files.createFile(workspace.resolve("fault.started"));
                } catch (final java.nio.file.FileAlreadyExistsException alreadyInjected) {
                    return StepResult.outcome(input.primaryOutcome());
                }
                try {
                    new CountDownLatch(1).await();
                } catch (final InterruptedException stopping) {
                    final Path current = Path.of(System.getProperty("java.class.path")).toAbsolutePath();
                    final boolean losePrevious = ((RailixValue.BooleanValue) input.value("lose_previous")).value();
                    try (var builds = Files.list(workspace.resolve(".railix/build"))) {
                        for (final Path build : builds.toList()) {
                            final Path jar = build.resolve("application.jar");
                            if (losePrevious || !jar.equals(current)) {
                                Files.deleteIfExists(jar);
                            }
                        }
                    }
                    if (((RailixValue.BooleanValue) input.value("block_previous")).value()) {
                        Files.setPosixFilePermissions(current, java.util.Set.of());
                    }
                    throw stopping;
                }
                throw new AssertionError("Artifact fault requires application shutdown.");
            } catch (final IOException failure) {
                throw new IllegalStateException("Cannot inject the artifact loss.", failure);
            }
        }
    }

    /** Executes the declared nested program and writes its final value through the declared path. */
    public static final class Operation {
        /** Creates one stateless handler. */
        public Operation() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("operations").writeWhenPresent("field");
        }
    }

    /** Returns a configured outcome to exercise generated result validation. */
    public static final class ConfiguredOutcome {
        /** Returns the configured outcome without changing context. */
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(configuration.string("outcome"));
        }
    }

    /** Raises one implementation fault through the real generated dispatch path. */
    public static final class Fault {
        /** Creates one stateless handler. */
        public Fault() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            throw new IllegalStateException("private conformance detail");
        }
    }

    /** Escapes the ordinary Step exception contract to prove process-boundary example finalization. */
    public static final class Fatal {
        /** Creates one stateless handler. */
        public Fatal() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            throw new AssertionError("fatal conformance detail");
        }
    }

    /** Cancels one execution through the real generated dispatch path. */
    public static final class Cancel {
        /** Creates one stateless handler. */
        public Cancel() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            throw new InterruptedException("conformance cancellation");
        }
    }

    /** Appends one byte to a real file so observation invocation count is externally provable. */
    public static final class Append {
        /** Creates one stateless handler. */
        public Append() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            try {
                Files.writeString(
                        Path.of(input.string("file")),
                        "x",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                return StepResult.outcome(input.primaryOutcome());
            } catch (final IOException exception) {
                throw new IllegalStateException("Conformance append failed.", exception);
            }
        }
    }

    /** Writes a value larger than the development response boundary. */
    public static final class Oversized {
        /** Creates one stateless handler. */
        public Oversized() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("x".repeat(OVERSIZED_CONTEXT_BYTES)));
        }
    }

    /** Writes enough data to exceed one development trace event while remaining valid workflow state. */
    public static final class OversizedTrace {
        /** Creates one stateless handler. */
        public OversizedTrace() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome())
                    .write("target", RailixValue.string("x".repeat(3 * 1_024 * 1_024)));
        }
    }

    /** Signals entry over loopback and then waits until runtime shutdown interrupts the invocation. */
    public static final class Block {
        /** Creates one stateless handler. */
        public Block() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            signal(port(input, "entered_port"));
            new CountDownLatch(1).await();
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Completes normally after interruption so cancellation acknowledgement cannot be optimistic. */
    public static final class IgnoreInterrupt {
        /** Creates one stateless handler. */
        public IgnoreInterrupt() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            signal(port(input, "entered_port"));
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException ignored) {
                // The contract permits third-party code to consume interruption; Railix must report what happened.
            }
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Keeps working briefly after interruption to prove bounded cancellation acknowledgement. */
    public static final class DelayedIgnoreInterrupt {
        /** Creates one stateless handler. */
        public DelayedIgnoreInterrupt() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            signal(port(input, "entered_port"));
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException ignored) {
                input.optionalValue("cancelled_port").ifPresent(value ->
                        signal(port(value, "cancelled_port"))
                );
                try {
                    Thread.sleep(2_500);
                } catch (final InterruptedException repeated) {
                    Thread.interrupted();
                }
            }
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Holds one invocation on a real socket until the test releases it. */
    public static final class Hold {
        /** Creates one stateless handler. */
        public Hold() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            signal(port(input, "entered_port"));
            awaitRelease(port(input, "release_port"));
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    /** Forked public-boundary probe proving null is rejected before stdin ownership is read. */
    public static final class NullApplicationMain {
        private NullApplicationMain() {
        }

        /** Runs the null-boundary assertion from the real generated application artifact. */
        public static void main(final String[] arguments) {
            try {
                DevelopmentRuntime.run(null);
                throw new AssertionError("DevelopmentRuntime accepted a null application.");
            } catch (final IllegalArgumentException exception) {
                System.out.println(exception.getMessage());
            }
        }
    }

    /** Starts one persistent child so the forked-process harness must reclaim the complete tree. */
    public static final class ProcessTreeMain {
        private ProcessTreeMain() {
        }

        /** Prints the child PID and waits until the owning harness closes this process. */
        public static void main(final String[] arguments) throws Exception {
            final Process child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeStepHandler.class.getName(),
                    "sleep"
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println(child.pid());
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }

    /** Emits one unterminated output burst for forked process-drain verification. */
    public static final class NoisyMain {
        private NoisyMain() {
        }

        /** Writes enough output to exceed the test harness retention boundary. */
        public static void main(final String[] arguments) {
            System.out.print("x".repeat(1_048_576));
        }
    }

    private static int port(final StepInput input, final String name) {
        return port(input.value(name), name);
    }

    private static int port(final RailixValue value, final String name) {
        return switch (value) {
            case RailixValue.NumberValue number -> number.value().intValueExact();
            default -> throw new IllegalArgumentException("Signal port must be a number: " + name);
        };
    }

    private static void signal(final int port) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write(1);
        } catch (final IOException exception) {
            throw new IllegalStateException("Development runtime signal failed.", exception);
        }
    }

    private static void awaitRelease(final int port) throws InterruptedException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            if (socket.getInputStream().read() < 0) {
                throw new IllegalStateException("Development runtime release closed without a byte.");
            }
        } catch (final IOException exception) {
            throw new IllegalStateException("Development runtime release failed.", exception);
        }
    }
}
