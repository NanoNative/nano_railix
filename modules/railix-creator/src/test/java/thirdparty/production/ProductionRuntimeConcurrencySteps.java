package thirdparty.production;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.runtime.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real bundle-owned filesystem Step used by production concurrency acceptance tests. */
public final class ProductionRuntimeConcurrencySteps {
    private ProductionRuntimeConcurrencySteps() {
    }

    /** A real bundle-owned socket source; its virtual acceptor relies on generic source lifetime. */
    public static final class SocketSource {
        public static StepResult pre(final StepInput configuration, final StepInput context) {
            final StepInput.Source source = context.source();
            final Path marker = Path.of(configuration.string("marker"));
            try {
                final var server = new java.net.ServerSocket();
                context.resource("listener", java.net.ServerSocket.class, () -> server, listener -> {
                    try {
                        listener.close();
                        Files.writeString(marker.resolveSibling("closed"), "closed");
                    } catch (final IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
                source.onStart(() -> {
                    try {
                        if (configuration.string("mode").equals("fail")) {
                            throw new IllegalStateException("Requested source activation failure.");
                        }
                        server.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
                        Files.writeString(marker, Integer.toString(server.getLocalPort()));
                    } catch (final IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                    Thread.startVirtualThread(() -> {
                        try {
                            while (!server.isClosed()) {
                                final var socket = server.accept();
                                Thread.startVirtualThread(() -> respond(source, socket, marker));
                            }
                        } catch (final IOException failure) {
                            if (!server.isClosed()) throw new java.io.UncheckedIOException(failure);
                        }
                    });
                });
                return StepResult.outcome(context.primaryOutcome());
            } catch (final IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        private static void respond(final StepInput.Source source, final java.net.Socket socket, final Path marker) {
            try (socket) {
                final String value = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine();
                source.run(java.util.Map.of("value", RailixValue.string(value)), result -> {
                    try {
                        if (value.equals("hold") && result.result() instanceof RunResult.Succeeded) {
                            Files.writeString(marker.resolveSibling("responding"), "responding");
                            while (!Files.exists(marker.resolveSibling("release"))) Thread.sleep(5);
                        }
                        final String response = result.result() instanceof RunResult.Succeeded
                                ? RailixJson.write(result.responses().get("output"))
                                : "rejected";
                        socket.getOutputStream().write((response + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    } catch (final IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    } catch (final InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Response interrupted.", failure);
                    }
                });
                if (value.equals("finish")) source.complete(0).complete(0);
            } catch (final IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        public static StepResult process(final StepInput configuration, final StepInput context) {
            if (context.resource("listener", java.net.ServerSocket.class).isClosed()) {
                throw new AssertionError("Source resource closed while serving a request.");
            }
            try {
                Files.writeString(Path.of(configuration.string("marker")).resolveSibling("invoked"),
                        context.string("value") + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (final IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return StepResult.outcome(context.primaryOutcome()).write("target", context.value("value"));
        }
    }

    /** Creates one file per invocation and returns that invocation's identifier. */
    public static final class CreateFile {
        /** Creates one stateless handler. */
        public CreateFile() {
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            final String id = input.string("id");
            try {
                Files.writeString(
                        Path.of(input.string("file")),
                        id,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                return StepResult.outcome(input.primaryOutcome())
                        .write("target", RailixValue.string(id));
            } catch (final IOException exception) {
                throw new IllegalStateException("Production concurrency file creation failed.", exception);
            }
        }
    }

    /** Holds configured invocations inside the real Step boundary until the probe releases them. */
    public static final class Barrier {
        private static final AtomicReference<CountDownLatch> ENTERED =
                new AtomicReference<>(new CountDownLatch(0));
        private static final AtomicReference<CountDownLatch> RELEASE =
                new AtomicReference<>(new CountDownLatch(0));
        private static final AtomicBoolean IGNORE_CANCELLATION = new AtomicBoolean();
        private static final AtomicInteger CLOSED = new AtomicInteger();

        /** Creates one stateless handler. */
        public Barrier() {
        }

        /** Arms the next {@code calls} invocations and returns that count. */
        public static int arm(final int calls) {
            if (calls < 1) {
                throw new IllegalArgumentException("Barrier calls must be positive.");
            }
            RELEASE.set(new CountDownLatch(1));
            ENTERED.set(new CountDownLatch(calls));
            return calls;
        }

        /** Waits for every armed invocation to enter the handler. */
        public static boolean awaitEntered(final long timeoutMillis) throws InterruptedException {
            return ENTERED.get().await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** Releases every entered invocation. */
        public static boolean release() {
            RELEASE.get().countDown();
            return true;
        }

        public static boolean ignoreCancellation() {
            return IGNORE_CANCELLATION.getAndSet(true);
        }

        public static int closed() {
            return CLOSED.get();
        }

        public static StepResult post(final StepInput configuration, final StepInput context) {
            CLOSED.incrementAndGet();
            return StepResult.outcome(context.primaryOutcome());
        }

        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            final CountDownLatch entered = ENTERED.get();
            if (entered.getCount() > 0) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        RELEASE.get().await();
                        released = true;
                    } catch (final InterruptedException exception) {
                        if (!IGNORE_CANCELLATION.get()) {
                            throw exception;
                        }
                    }
                }
            }
            return StepResult.outcome(input.primaryOutcome());
        }
    }
}
