package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded loopback TCP ingress for one compiler-validated socket trigger. */
final class ApplicationSocketIngress {
    private static final int MAX_EVENT_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    private static final int MAX_REPLY_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;

    private final ServerSocketChannel server;
    private final CompiledFlow flow;
    private final AtomicBoolean ready;
    private final int port;
    private final int timeoutMillis;
    private final Semaphore connections;
    private final Set<SocketChannel> clients = new HashSet<>();
    private final Set<Thread> workers = new HashSet<>();
    private final Thread acceptor;
    private boolean open = true;

    private ApplicationSocketIngress(
            final ServerSocketChannel server,
            final CompiledFlow flow,
            final AtomicBoolean ready,
            final int port,
            final int timeoutMillis,
            final int maxConnections,
            final String triggerId
    ) {
        this.server = server;
        this.flow = flow;
        this.ready = ready;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        connections = new Semaphore(maxConnections);
        acceptor = Thread.ofVirtual()
                .name("railix-socket-" + triggerId)
                .unstarted(this::accept);
    }

    static ApplicationSocketIngress start(
            final CompiledFlow flow,
            final CompiledFlow.Trigger trigger,
            final AtomicBoolean ready
    ) throws IOException {
        if (flow == null || trigger == null || ready == null) {
            throw new IllegalArgumentException("Socket ingress requires a compiled trigger and readiness.");
        }
        final Map<String, RailixValue> config = trigger.config().values();
        final int port = integer(config, "port");
        final int timeoutMillis = integer(config, "timeoutMillis");
        final int maxConnections = integer(config, "maxConnections");
        final ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.bind(
                    new InetSocketAddress(
                            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                            port
                    ),
                    maxConnections
            );
            final ApplicationSocketIngress ingress = new ApplicationSocketIngress(
                    server,
                    flow,
                    ready,
                    port,
                    timeoutMillis,
                    maxConnections,
                    trigger.id()
            );
            ingress.acceptor.start();
            return ingress;
        } catch (final IOException | RuntimeException exception) {
            close(server);
            throw exception;
        }
    }

    synchronized ApplicationSocketIngress stop() {
        if (open) {
            open = false;
            close(server);
            acceptor.interrupt();
            clients.forEach(ApplicationSocketIngress::close);
            workers.forEach(Thread::interrupt);
        }
        return this;
    }

    int port() {
        return port;
    }

    private void accept() {
        while (isOpen()) {
            final SocketChannel client;
            try {
                client = server.accept();
            } catch (final IOException exception) {
                if (!isOpen()) {
                    return;
                }
                continue;
            }
            try {
                client.configureBlocking(false);
                if (!ready.get()) {
                    rejectAndClose(
                            client,
                            error(
                                    "APPLICATION_STARTING",
                                    "Application startup has not completed."
                            )
                    );
                } else if (!connections.tryAcquire()) {
                    rejectAndClose(
                            client,
                            error(
                                    "SOCKET_BACKPRESSURE",
                                    "Socket connection limit is reached."
                            )
                    );
                } else {
                    start(client);
                }
            } catch (final IOException | RuntimeException exception) {
                close(client);
            }
        }
    }

    private synchronized boolean isOpen() {
        return open;
    }

    private synchronized void start(final SocketChannel client) {
        if (!open) {
            connections.release();
            close(client);
            return;
        }
        final Thread worker = Thread.ofVirtual()
                .name("railix-socket-request")
                .unstarted(() -> handle(client));
        clients.add(client);
        workers.add(worker);
        try {
            worker.start();
        } catch (final RuntimeException exception) {
            clients.remove(client);
            workers.remove(worker);
            connections.release();
            close(client);
            throw exception;
        }
    }

    private void handle(final SocketChannel client) {
        final Thread worker = Thread.currentThread();
        try (client; Selector selector = Selector.open()) {
            client.register(selector, 0);
            while (isOpen() && client.isOpen()) {
                final Frame frame = readFrame(client, selector);
                if (frame.disconnected()) {
                    return;
                }
                if (frame.reply().length > 0) {
                    writeFrame(client, selector, frame.reply());
                    if (frame.close()) {
                        return;
                    }
                    continue;
                }
                if (!writeFrame(client, selector, eventReply(frame.event()))) {
                    return;
                }
            }
        } catch (final IOException | RuntimeException ignored) {
            // A disconnected peer owns transport completion.
        } finally {
            synchronized (this) {
                clients.remove(client);
                workers.remove(worker);
            }
            connections.release();
        }
    }

    private Frame readFrame(final SocketChannel client, final Selector selector) throws IOException {
        final long deadline = deadline();
        final ByteBuffer length = ByteBuffer.allocate(Integer.BYTES);
        final Transfer prefix = read(client, selector, length, deadline);
        if (prefix == Transfer.EMPTY_EOF || prefix == Transfer.INTERRUPTED) {
            return Frame.disconnectedFrame();
        }
        if (prefix == Transfer.TIMEOUT) {
            return Frame.rejected(
                    error(
                            "SOCKET_FRAME_TIMEOUT",
                            "Socket frame was not received within " + timeoutMillis + " milliseconds."
                    ),
                    true
            );
        }
        if (prefix != Transfer.COMPLETE) {
            return Frame.rejected(
                    error("SOCKET_FRAME_TRUNCATED", "Socket frame ended before its declared length."),
                    true
            );
        }
        final int bytes = length.flip().getInt();
        if (bytes <= 0) {
            return Frame.rejected(
                    error(
                            "SOCKET_FRAME_LENGTH_INVALID",
                            "Socket frame length must be between 1 and " + MAX_EVENT_BYTES + "."
                    ),
                    true
            );
        }
        if (bytes > MAX_EVENT_BYTES) {
            return Frame.rejected(
                    error(
                            "SOCKET_FRAME_TOO_LARGE",
                            "Socket event exceeds the " + MAX_EVENT_BYTES + "-byte limit."
                    ),
                    true
            );
        }
        final ByteBuffer event = ByteBuffer.allocate(bytes);
        final Transfer body = read(client, selector, event, deadline);
        if (body == Transfer.TIMEOUT) {
            return Frame.rejected(
                    error(
                            "SOCKET_FRAME_TIMEOUT",
                            "Socket frame was not received within " + timeoutMillis + " milliseconds."
                    ),
                    true
            );
        }
        if (body != Transfer.COMPLETE) {
            return Frame.rejected(
                    error("SOCKET_FRAME_TRUNCATED", "Socket frame ended before its declared length."),
                    true
            );
        }
        return Frame.event(event.array());
    }

    private byte[] eventReply(final byte[] source) {
        final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, source);
        if (normalized instanceof RailixData.Invalid invalid) {
            return error(socketCode(invalid.code()), invalid.message());
        }
        final RailixValue value = ((RailixData.Normalized) normalized).value();
        final RailixRunner.Outcome outcome = value instanceof RailixValue.ObjectValue event
                ? RailixRunner.run(flow, event)
                : new RailixRunner.Rejected(
                        2,
                        RailixProtocol.diagnostics(
                                "run-rejected",
                                List.of(Diagnostic.atPath(
                                        "FLOW_INPUT_OBJECT_REQUIRED",
                                        "Flow inputs must be an object.",
                                        "inputs"
                                )),
                                List.of()
                        )
                );
        return RailixJson.write(outcome.payload(), MAX_REPLY_BYTES)
                .map(text -> text.getBytes(StandardCharsets.UTF_8))
                .orElseGet(() -> error(
                        "SOCKET_REPLY_TOO_LARGE",
                        "Socket reply exceeds the " + MAX_REPLY_BYTES + "-byte limit."
                ));
    }

    private static String socketCode(final String code) {
        return switch (code) {
            case "DATA_SOURCE_UTF8_INVALID" -> "SOCKET_EVENT_UTF8_INVALID";
            case "DATA_DEPTH_EXCEEDED" -> "SOCKET_EVENT_DEPTH_EXCEEDED";
            case "DATA_NUMBER_LIMIT_EXCEEDED" -> "SOCKET_EVENT_NUMBER_LIMIT_EXCEEDED";
            case "DATA_BOM_UNSUPPORTED" -> "SOCKET_EVENT_BOM_UNSUPPORTED";
            default -> "SOCKET_EVENT_JSON_INVALID";
        };
    }

    private void rejectAndClose(final SocketChannel client, final byte[] reply) {
        try (client; Selector selector = Selector.open()) {
            client.register(selector, 0);
            writeFrame(client, selector, reply);
        } catch (final IOException ignored) {
            // A disconnected peer needs no fallback transport.
        }
    }

    private boolean writeFrame(
            final SocketChannel client,
            final Selector selector,
            final byte[] source
    ) throws IOException {
        final ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + source.length)
                .putInt(source.length)
                .put(source)
                .flip();
        return write(client, selector, frame, deadline()) == Transfer.COMPLETE;
    }

    private static Transfer read(
            final SocketChannel channel,
            final Selector selector,
            final ByteBuffer target,
            final long deadline
    ) throws IOException {
        int transferred = 0;
        while (target.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) {
                return Transfer.INTERRUPTED;
            }
            if (System.nanoTime() >= deadline) {
                return Transfer.TIMEOUT;
            }
            final int count = channel.read(target);
            if (count < 0) {
                return transferred == 0 ? Transfer.EMPTY_EOF : Transfer.PARTIAL_EOF;
            }
            transferred += count;
            if (count == 0 && !select(selector, SelectionKey.OP_READ, deadline)) {
                return Transfer.TIMEOUT;
            }
        }
        return System.nanoTime() < deadline ? Transfer.COMPLETE : Transfer.TIMEOUT;
    }

    private static Transfer write(
            final SocketChannel channel,
            final Selector selector,
            final ByteBuffer source,
            final long deadline
    ) throws IOException {
        while (source.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) {
                return Transfer.INTERRUPTED;
            }
            if (System.nanoTime() >= deadline) {
                return Transfer.TIMEOUT;
            }
            final int count = channel.write(source);
            if (count == 0 && !select(selector, SelectionKey.OP_WRITE, deadline)) {
                return Transfer.TIMEOUT;
            }
        }
        return System.nanoTime() < deadline ? Transfer.COMPLETE : Transfer.TIMEOUT;
    }

    private static boolean select(
            final Selector selector,
            final int operation,
            final long deadline
    ) throws IOException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        selector.keys().iterator().next().interestOps(operation);
        final long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
        selector.select(millis);
        selector.selectedKeys().clear();
        return System.nanoTime() < deadline;
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static int integer(final Map<String, RailixValue> config, final String field) {
        return ((RailixValue.NumberValue) config.get(field)).value().intValueExact();
    }

    private static byte[] error(final String code, final String message) {
        return RailixJson.write(RailixProtocol.error("event-rejected", code, message))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void close(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception ignored) {
            // Closing is idempotent and the resource is already unusable.
        }
    }

    private enum Transfer {
        COMPLETE,
        EMPTY_EOF,
        PARTIAL_EOF,
        TIMEOUT,
        INTERRUPTED
    }

    private record Frame(byte[] event, byte[] reply, boolean close, boolean disconnected) {
        private static Frame event(final byte[] source) {
            return new Frame(source, new byte[0], false, false);
        }

        private static Frame rejected(final byte[] reply, final boolean close) {
            return new Frame(new byte[0], reply, close, false);
        }

        private static Frame disconnectedFrame() {
            return new Frame(new byte[0], new byte[0], true, true);
        }
    }
}
