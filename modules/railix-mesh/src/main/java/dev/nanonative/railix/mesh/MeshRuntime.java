package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.peer.PeerConnection;
import dev.nanonative.railix.mesh.peer.PeerSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Owns the mesh listener and configured peer sessions for a generated application. */
public final class MeshRuntime implements AutoCloseable {
    private static final long LISTEN_LOOKUP_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long JOIN_NANOS = Duration.ofSeconds(5).toNanos();
    // Leave room in the 16 KiB observation response for multibyte hostnames and status metadata.
    private static final int MAX_CONNECTED_PEERS = 12;
    private static final RailixValue ABSENT = RailixValue.nullValue();
    private static final RailixValue READY = RailixValue.string("ready");
    // Both replicas use the lexicographic order of the same UUIDs to pick a connection.
    private static final Comparator<PeerConnection> CONNECTION_ORDER = Comparator
            .comparing((final PeerConnection connection) -> connection.initiator().toString())
            .thenComparing(connection -> connection.connectionId().toString());

    private final MeshConfig config;
    private final PeerSession peerSession;
    private final String build;
    private final Semaphore inboundJoin = new Semaphore(1);
    private final Semaphore outboundJoin = new Semaphore(1, true);
    private final BootstrapRetries bootstrap = new BootstrapRetries();
    private final Map<UUID, ConnectionAttempt> peers = new TreeMap<>();
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("railix-mesh-connection-", 0).factory());
    private final PeerAddressResolver resolver;
    private final ServerSocket listener;
    private final Thread acceptor;
    private volatile boolean closed;
    private volatile RailixValue.ObjectValue status;
    private RailixValue lastRejection = ABSENT;

    private MeshRuntime(final MeshConfig config, final PeerSession peerSession, final String build,
                        final ServerSocket listener, final PeerAddressResolver resolver) {
        this.config = config;
        this.peerSession = peerSession;
        this.build = build;
        this.listener = listener;
        this.resolver = resolver;
        acceptor = Thread.ofVirtual().name("railix-mesh-listener").unstarted(this::accept);
        publish("starting");
    }

    /**
     * Validates deployment configuration and binds the local listener before application startup.
     * @param configurationPath process startup path, or Java null when mesh is inactive
     * @param logicalBuildId identity embedded by the production build
     * @return the mesh owner, or empty when startup did not select mesh
     * @throws IOException if configuration, credentials or the listen endpoint are unusable
     */
    public static Optional<MeshRuntime> open(final String configurationPath, final String logicalBuildId) throws IOException {
        if (configurationPath == null) {
            return Optional.empty();
        }
        if (configurationPath.isBlank()) {
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + MeshErrors.CONFIG_PATH_REQUIRED);
        }
        try {
            return Optional.of(openConfigured(Path.of(configurationPath), logicalBuildId));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + MeshErrors.LISTEN_LOOKUP_INTERRUPTED, interrupted);
        } catch (final IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + failure.getMessage(), failure);
        }
    }

    private static MeshRuntime openConfigured(final Path configurationPath, final String logicalBuildId)
            throws IOException, GeneralSecurityException, InterruptedException {
        final MeshConfig config = MeshConfig.load(configurationPath);
        final PeerSession peerSession = PeerSession.open(config.meshId(), logicalBuildId,
                config.advertised(), config.flowCapacity(), config.identity().path(), config.identity().passwordFile(),
                config.trust().path(), config.trust().passwordFile());
        final PeerAddressResolver resolver = new PeerAddressResolver();
        final InetSocketAddress listen = resolver.resolve(config.listen(), System.nanoTime() + LISTEN_LOOKUP_NANOS)
                .getFirst();
        final ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(listen, 1);
            return new MeshRuntime(config, peerSession, logicalBuildId, listener, resolver);
        } catch (final IOException | RuntimeException failure) {
            try {
                listener.close();
            } catch (final IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    /** Starts peer processing after the application's local sources launch successfully. */
    public synchronized void start() {
        if (closed || acceptor.getState() != Thread.State.NEW) {
            return;
        }
        publish("waiting");
        acceptor.start();
        for (final PeerEndpoint seed : config.bootstrap()) {
            connections.execute(() -> dial(seed));
        }
    }

    /** @return the immutable current view for authenticated local observation */
    public RailixValue.ObjectValue status() {
        return status;
    }

    /** Releases the listener and interrupts peer I/O and bootstrap waits on application shutdown. */
    @Override
    public void close() {
        final List<PeerConnection> active;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            active = peers.values().stream().map(attempt -> attempt.peer).toList();
            peers.clear();
            publish("draining");
            notifyAll();
        }
        acceptor.interrupt();
        connections.shutdownNow();
        active.forEach(PeerConnection::close);
        try {
            listener.close();
        } catch (final IOException ignored) {
            // The acceptor has already been interrupted.
        }
    }

    private void accept() {
        try {
            while (!closed) {
                final Socket transport = listener.accept();
                if (!accept(transport, System.nanoTime() + JOIN_NANOS)) {
                    transport.close();
                }
            }
        } catch (final IOException failure) {
            rejectWhileIdle(MeshErrors.LISTENER_UNAVAILABLE);
        }
    }

    private synchronized boolean accept(final Socket transport, final long deadline) {
        if (closed || !inboundJoin.tryAcquire()) {
            return false;
        }
        connections.execute(() -> {
            try (ConnectionAttempt attempt = new ConnectionAttempt(inboundJoin)) {
                peerSession.accept(transport, deadline, attempt::update, this::reject);
            } catch (final InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        return true;
    }

    private void dial(final PeerEndpoint seed) {
        final BootstrapRetries.Backoff lookupBackoff = new BootstrapRetries.Backoff();
        List<BootstrapRetries.Target> targets = List.of();
        try {
            while (!closed) {
                final long deadline = System.nanoTime() + JOIN_NANOS;
                final List<InetSocketAddress> addresses = resolveSeed(seed, deadline);
                if (addresses.isEmpty()) {
                    Thread.sleep(lookupBackoff.nextDelayMillis());
                    continue;
                }
                lookupBackoff.reset();
                targets = bootstrap.replace(targets, addresses);
                connect(targets, deadline);
                if (!closed) {
                    bootstrap.awaitRetry(targets);
                }
            }
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            bootstrap.replace(targets, List.of());
        }
    }

    private List<InetSocketAddress> resolveSeed(final PeerEndpoint seed, final long deadline) throws InterruptedException {
        try {
            return resolver.resolve(seed, deadline);
        } catch (final SocketTimeoutException expired) {
            rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
        } catch (final IOException failure) {
            rejectWhileIdle(MeshErrors.PEER_UNAVAILABLE);
        }
        return List.of();
    }

    private void connect(final List<BootstrapRetries.Target> targets, final long deadline) throws InterruptedException {
        for (int index = 0; index < targets.size() && !closed; index++) {
            final BootstrapRetries.Target target = targets.get(index);
            final int addressesLeft = targets.size() - index;
            if (tryAddress(target, deadline, addressesLeft)) {
                return;
            }
        }
    }

    /** @return whether the dial outcome or join deadline ends this address scan */
    private boolean tryAddress(final BootstrapRetries.Target target, final long deadline, final int addressesLeft)
            throws InterruptedException {
        if (!bootstrap.tryStart(target)) {
            return false;
        }
        boolean heartbeatReceived = false;
        try {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !outboundJoin.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
                return true;
            }
            final ConnectionAttempt attempt = new ConnectionAttempt(outboundJoin);
            try (attempt) {
                // Leave time for the other addresses and carry unused time forward.
                final long started = System.nanoTime();
                final long budget = deadline - started;
                if (budget <= 0) {
                    rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
                    return true;
                }
                final long attemptDeadline = started + Math.max(1, budget / addressesLeft);
                heartbeatReceived = peerSession.dial(
                        target.address(), attemptDeadline, attempt::update, this::reject);
            }
            if (attempt.peer == null) {
                return false;
            }

            // A simultaneous dial can leave another connection to this replica in use.
            final boolean retainedConnection = awaitDisconnection(attempt.peer.replicaId());
            return retainedConnection || heartbeatReceived;
        } finally {
            bootstrap.finish(target, heartbeatReceived);
        }
    }

    private synchronized boolean awaitDisconnection(final UUID replica) throws InterruptedException {
        final boolean connected = peers.containsKey(replica);
        while (!closed && peers.containsKey(replica)) {
            wait();
        }
        return connected;
    }

    private synchronized void reject(final String reason) {
        if (!closed) {
            lastRejection = RailixValue.string(reason);
            publish(peers.isEmpty() ? "rejected" : "joined");
        }
    }

    private synchronized void rejectWhileIdle(final String reason) {
        if (peers.isEmpty()) {
            reject(reason);
        }
    }

    private void publish(final String phase) {
        final List<RailixValue> observed = peers.values().stream().<RailixValue>map(attempt -> attempt.state).toList();
        status = RailixValue.object(Map.of(
                "status", RailixValue.string(phase),
                "logicalBuildId", RailixValue.string(build),
                "replicaId", RailixValue.string(peerSession.replicaId().toString()),
                "peer", observed.isEmpty() ? ABSENT : observed.getFirst(),
                "peers", RailixValue.array(observed),
                "rejection", lastRejection
        ));
    }

    private final class ConnectionAttempt implements AutoCloseable {
        private final Semaphore permit;
        private PeerConnection peer;
        private RailixValue.ObjectValue state;
        private boolean joining = true;

        private ConnectionAttempt(final Semaphore permit) {
            this.permit = permit;
        }

        private boolean update(final PeerConnection connection, final RailixValue.ObjectValue advertised) {
            final ConnectionAttempt current;
            synchronized (MeshRuntime.this) {
                if (closed) {
                    return false;
                }
                current = peers.get(connection.replicaId());
                if (!joining) {
                    return updateRegisteredPeer(current, advertised);
                }

                // The dialer needs the authenticated identity even when another connection wins.
                peer = connection;
                if (!register(current, advertised)) {
                    return false;
                }
            }
            if (current != null) {
                current.peer.close();
            }
            return true;
        }

        private boolean updateRegisteredPeer(final ConnectionAttempt current, final RailixValue.ObjectValue advertised) {
            if (current != this) {
                return false;
            }
            state = advertised;
            publish("joined");
            return true;
        }

        private boolean register(final ConnectionAttempt current, final RailixValue.ObjectValue advertised) {
            if (current != null) {
                if (!peer.incarnationId().equals(current.peer.incarnationId())) {
                    reject(MeshErrors.REPLICA_ALREADY_CONNECTED);
                    return false;
                }
                if (!canReplace(current, advertised)) {
                    return false;
                }
            } else if (peers.size() == MAX_CONNECTED_PEERS) {
                reject(MeshErrors.PEER_LIMIT_REACHED);
                return false;
            }
            state = advertised;
            peers.put(peer.replicaId(), this);
            joining = false;
            // The permit covers the handshake. An admitted peer keeps its session without it.
            permit.release();
            publish("joined");
            return true;
        }

        private boolean canReplace(final ConnectionAttempt current, final RailixValue.ObjectValue advertised) {
            final boolean currentReady = READY.equals(current.state.values().get("status"));
            final boolean incomingReady = READY.equals(advertised.values().get("status"));
            if (currentReady && !incomingReady) {
                return false;
            }
            return CONNECTION_ORDER.compare(peer, current.peer) < 0;
        }

        @Override
        public void close() {
            synchronized (MeshRuntime.this) {
                if (joining) {
                    permit.release();
                    return;
                }
                // The old session can exit after its replacement has registered.
                if (peers.remove(peer.replicaId(), this)) {
                    publish(peers.isEmpty() ? "waiting" : "joined");
                    MeshRuntime.this.notifyAll();
                }
            }
        }
    }
}
