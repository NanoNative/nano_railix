package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.project.FlowContinuation;
import dev.nanonative.railix.core.project.WorkflowRuntime;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.peer.AdmittedFlow;
import dev.nanonative.railix.mesh.peer.PeerConnection;
import dev.nanonative.railix.mesh.peer.PeerFlowCalls;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Owns the mesh listener and configured peer sessions for a generated application. */
public final class MeshRuntime implements AutoCloseable {
    private static final long LISTEN_LOOKUP_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long JOIN_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long INVOCATION_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long DRAIN_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int MAX_PENDING_OFFLOADS = 12;
    // Leave room in the 16 KiB observation response for multibyte hostnames and status metadata.
    private static final int MAX_CONNECTED_PEERS = 12;
    private static final RailixValue ABSENT = RailixValue.nullValue();
    private static final RailixValue READY = RailixValue.string("ready");
    private final MeshConfig config;
    private final PeerSession peerSession;
    private final String build;
    private final Semaphore inboundJoin = new Semaphore(1);
    private final Semaphore outboundJoin = new Semaphore(1, true);
    private final Semaphore pendingFlows = new Semaphore(MAX_PENDING_OFFLOADS);
    private final BootstrapRetries bootstrap = new BootstrapRetries();
    private final Map<UUID, ConnectionAttempt> peers = new TreeMap<>();
    private final Map<UUID, ConnectionAttempt> reservations = new HashMap<>();
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("railix-mesh-connection-", 0).factory());
    private final PeerAddressResolver resolver;
    private final ServerSocket listener;
    private final Thread acceptor;
    private volatile boolean closed;
    private volatile RailixValue.ObjectValue status;
    private RailixValue lastRejection = ABSENT;
    private final Function<FlowContinuation, Optional<RunResult>> runContinuation;
    private int available;
    private boolean draining;
    private long drainDeadline;

    private MeshRuntime(final MeshConfig config, final PeerSession peerSession, final String build,
                        final ServerSocket listener, final PeerAddressResolver resolver,
                        final Function<FlowContinuation, Optional<RunResult>> runContinuation) {
        this.config = config;
        this.peerSession = peerSession;
        this.build = build;
        this.listener = listener;
        this.resolver = resolver;
        this.runContinuation = runContinuation;
        available = config.flowCapacity();
        peerSession.setFlowAdmission(this::admitRemote);
        acceptor = Thread.ofVirtual().name("railix-mesh-listener").unstarted(this::accept);
        publish("starting");
    }

    /**
     * Opens a mesh replica with the generated application's continuation entrypoint.
     * @param configurationPath startup path or Java null to keep mesh inactive
     * @param logicalBuildId identity embedded by the production build
     * @param knownTrigger checks the configured Flow against the compiled application
     * @param runContinuation runs the compiled continuation or returns empty before execution if declined
     * @return the mesh owner, or empty when mesh is inactive
     * @throws IOException for unusable configuration, credentials or listener
     */
    public static Optional<MeshRuntime> open(final String configurationPath, final String logicalBuildId,
            final Predicate<String> knownTrigger, final Function<FlowContinuation, Optional<RunResult>> runContinuation) throws IOException {
        if (configurationPath == null) {
            return Optional.empty();
        }
        if (configurationPath.isBlank()) {
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + MeshErrors.CONFIG_PATH_REQUIRED);
        }
        try {
            return Optional.of(openConfigured(Path.of(configurationPath), logicalBuildId, knownTrigger, runContinuation));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + MeshErrors.LISTEN_LOOKUP_INTERRUPTED, interrupted);
        } catch (final IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + failure.getMessage(), failure);
        }
    }

    private static MeshRuntime openConfigured(final Path configurationPath, final String logicalBuildId,
            final Predicate<String> knownTrigger, final Function<FlowContinuation, Optional<RunResult>> runContinuation)
            throws IOException, GeneralSecurityException, InterruptedException {
        final MeshConfig config = MeshConfig.load(configurationPath);
        if (config.offloadFlow().filter(knownTrigger.negate()).isPresent()) {
            throw new IOException(MeshErrors.INVALID_OFFLOAD_FLOW);
        }
        final PeerSession peerSession = PeerSession.open(config.meshId(), logicalBuildId,
                config.advertised(), config.flowCapacity(), config.offloadFlow(),
                config.identity().path(), config.identity().passwordFile(),
                config.trust().path(), config.trust().passwordFile());
        final PeerAddressResolver resolver = new PeerAddressResolver();
        final InetSocketAddress listen = resolver.resolve(config.listen(), System.nanoTime() + LISTEN_LOOKUP_NANOS)
                .getFirst();
        final ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(listen, 1);
            return new MeshRuntime(config, peerSession, logicalBuildId, listener, resolver, runContinuation);
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
        if (closed || draining || acceptor.getState() != Thread.State.NEW) {
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

    /** @return whether the startup configuration enables Flow offloading */
    public boolean offloadEnabled() {
        return config.offloadFlow().isPresent();
    }

    /** @return the Flow selected for offloading, or empty for membership-only operation */
    public Optional<String> offloadFlow() {
        return config.offloadFlow();
    }

    /** Selects local admission or places the selected Flow at one connected replica. */
    public RunResult place(final String trigger, final int outcome, final Supplier<RailixValue.ObjectValue> input,
                           final Supplier<RunResult> local, final Consumer<Boolean> recordOffload) {
        if (!offloadEnabled()) {
            return local.get();
        }
        // The pilot allows every Flow locally. Only the configured Flow can cross a peer connection.
        if (reserveFlow()) {
            try {
                return local.get();
            } finally {
                releaseFlow();
            }
        }
        if (!isOffloadFlow(trigger) || !pendingFlows.tryAcquire()) {
            return WorkflowRuntime.sourceUnavailable(trigger);
        }
        try {
            return offload(trigger, outcome, input, recordOffload);
        } finally {
            pendingFlows.release();
        }
    }

    private RunResult offload(final String trigger, final int outcome, final Supplier<RailixValue.ObjectValue> input,
                              final Consumer<Boolean> recordOffload) {
        final long deadline = System.nanoTime() + INVOCATION_NANOS;
        final List<PeerConnection> targets = rankEligiblePeers(trigger);
        return PeerFlowCalls.offload(targets, () -> new FlowContinuation(trigger, outcome, input.get()), deadline, recordOffload)
                .orElseGet(() -> WorkflowRuntime.sourceUnavailable(trigger));
    }

    private synchronized List<PeerConnection> rankEligiblePeers(final String trigger) {
        if (draining || closed) {
            return List.of();
        }
        final RailixValue.StringValue flow = RailixValue.string(trigger);
        return peers.values().stream()
                .filter(attempt -> READY.equals(attempt.state.values().get("status")))
                .filter(attempt -> flow.equals(attempt.state.values().get("offloadFlow")))
                .sorted(Comparator.comparingInt(ConnectionAttempt::advertisedSlots).reversed())
                .map(attempt -> attempt.peer)
                .toList();
    }

    private boolean isOffloadFlow(final String trigger) {
        final Optional<String> configured = config.offloadFlow();
        return configured.isPresent() && trigger.equals(configured.orElseThrow());
    }

    private Optional<AdmittedFlow> admitRemote(final FlowContinuation continuation) {
        if (!isOffloadFlow(continuation.trigger()) || !reserveFlow()) {
            return Optional.empty();
        }
        return Optional.of(new AdmittedFlow(() -> runContinuation.apply(continuation), this::releaseFlow));
    }

    private synchronized boolean reserveFlow() {
        if (draining || closed || available == 0) {
            return false;
        }
        available--;
        peerSession.updateState("ready", available);
        return true;
    }

    private synchronized void releaseFlow() {
        available++;
        if (!draining && !closed) {
            peerSession.updateState("ready", available);
        }
        if (draining) {
            notifyAll();
        }
    }

    /** Stops new admission while allowing admitted work to return outcomes. */
    public synchronized void drain() {
        if (closed || draining) {
            return;
        }
        draining = true;
        drainDeadline = System.nanoTime() + DRAIN_NANOS;
        peerSession.updateState("draining", 0);
        publish("draining");
    }

    /** Releases the listener and interrupts peer I/O and bootstrap waits on application shutdown. */
    @Override
    public void close() {
        try {
            awaitDrain();
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        final List<PeerConnection> closing;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            closing = Stream.concat(peers.values().stream(), reservations.values().stream())
                    .map(attempt -> attempt.peer)
                    .toList();
            peers.clear();
            reservations.clear();
            publish("draining");
            notifyAll();
        }
        acceptor.interrupt();
        connections.shutdownNow();
        closing.forEach(PeerConnection::close);
        peerSession.close();
        try {
            listener.close();
        } catch (final IOException ignored) {
            // The acceptor has already been interrupted.
        }
    }

    private List<PeerConnection> peerConnections() {
        return peers.values().stream().map(attempt -> attempt.peer).toList();
    }

    private void awaitDrain() throws InterruptedException {
        final List<PeerConnection> drainingPeers;
        final long deadline;
        synchronized (this) {
            while (!closed && draining && available < config.flowCapacity()) {
                final long remaining = drainDeadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            if (closed || !draining) {
                return;
            }
            drainingPeers = peerConnections();
            deadline = drainDeadline;
        }
        for (final PeerConnection peer : drainingPeers) {
            if (!peer.flows().awaitReplies(deadline)) {
                return;
            }
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
                attempt.heartbeatReceived = peerSession.accept(
                        transport, deadline, attempt::identify, attempt::reserve, attempt::update, this::reject);
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
        try {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !outboundJoin.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
                return true;
            }
            final ConnectionAttempt attempt = new ConnectionAttempt(outboundJoin);
            ConnectionAttempt retained;
            try (attempt) {
                // The winning socket can join during the losing dial's backoff.
                retained = retainedConnection(target.replicaId());
                if (retained == null) {
                    // Leave time for the other addresses and carry unused time forward.
                    final long started = System.nanoTime();
                    final long budget = deadline - started;
                    if (budget <= 0) {
                        rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
                        return true;
                    }
                    final long attemptDeadline = started + Math.max(1, budget / addressesLeft);
                    attempt.heartbeatReceived = peerSession.dial(target.address(), attemptDeadline, connection -> {
                        attempt.identify(connection);
                        bootstrap.identify(target, connection.replicaId());
                    }, attempt::reserve, attempt::update, this::reject);
                }
            }
            if (attempt.peer != null) {
                retained = retainedConnection(attempt.peer.replicaId());
            }
            return (retained != null && awaitDisconnection(retained)) || attempt.heartbeatReceived;
        } finally {
            bootstrap.finish(target);
        }
    }

    private synchronized ConnectionAttempt retainedConnection(final UUID replica) {
        return replica == null ? null : connectionFor(replica);
    }

    private synchronized boolean awaitDisconnection(final ConnectionAttempt retained) throws InterruptedException {
        final UUID replica = retained.peer.replicaId();
        while (!closed && connectionFor(replica) != null) {
            wait();
        }
        return !closed && !retained.joining;
    }

    private ConnectionAttempt connectionFor(final UUID replica) {
        return peers.getOrDefault(replica, reservations.get(replica));
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
                "status", RailixValue.string(draining ? "draining" : phase),
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
        private boolean heartbeatReceived;

        private ConnectionAttempt(final Semaphore permit) {
            this.permit = permit;
        }

        private int advertisedSlots() {
            final RailixValue.NumberValue slots = (RailixValue.NumberValue) state.values().get("availableFlowSlots");
            return slots.value().intValue();
        }

        private void identify(final PeerConnection connection) {
            // A losing dial still needs the peer ID to wait for the retained connection.
            peer = connection;
        }

        private boolean reserve() {
            synchronized (MeshRuntime.this) {
                if (closed || draining) {
                    return false;
                }
                final UUID replica = peer.replicaId();
                final ConnectionAttempt current = connectionFor(replica);
                if (current != null) {
                    if (!peer.incarnationId().equals(current.peer.incarnationId())) {
                        reject(MeshErrors.REPLICA_ALREADY_CONNECTED);
                    }
                    return false;
                }
                if (peers.size() + reservations.size() >= MAX_CONNECTED_PEERS) {
                    reject(MeshErrors.PEER_LIMIT_REACHED);
                    return false;
                }
                reservations.put(replica, this);
                return true;
            }
        }

        private boolean update(final RailixValue.ObjectValue advertised) {
            synchronized (MeshRuntime.this) {
                if (closed) {
                    return false;
                }
                if (joining) {
                    final UUID replica = peer.replicaId();
                    reservations.remove(replica, this);
                    peers.put(replica, this);
                    joining = false;
                    permit.release();
                }
                state = advertised;
                publish("joined");
                return true;
            }
        }

        @Override
        public void close() {
            synchronized (MeshRuntime.this) {
                if (joining) {
                    permit.release();
                    if (peer != null && reservations.remove(peer.replicaId(), this)) {
                        MeshRuntime.this.notifyAll();
                    }
                    return;
                }
                if (peers.remove(peer.replicaId(), this)) {
                    if (heartbeatReceived) {
                        bootstrap.healthySessionEnded(peer.replicaId());
                    }
                    publish(peers.isEmpty() ? "waiting" : "joined");
                    MeshRuntime.this.notifyAll();
                }
            }
        }
    }
}
