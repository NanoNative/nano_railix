package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.project.FlowContinuation;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;
import dev.nanonative.railix.mesh.PeerEndpoint;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Runs authenticated peer connections for one mesh replica. */
public final class PeerSession implements AutoCloseable {
    private static final long STALE_NANOS = Duration.ofSeconds(8).toNanos();
    private static final Duration HEARTBEAT = Duration.ofSeconds(2);

    private final PeerTls tls;
    private final UUID meshId;
    private final String build;
    private final UUID incarnation = UUID.randomUUID();
    private final PeerEndpoint advertised;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("railix-mesh-flow-deadline").factory());
    private final Semaphore retainedBytes = new Semaphore(2 * PeerFrames.MAX_DATA_BODY_BYTES);
    private byte[] localStateBody;
    private String state = "ready";
    private int capacity;
    private final RailixValue offloadFlow;
    private Function<FlowContinuation, Optional<AdmittedFlow>> admit = ignored -> Optional.empty();

    private PeerSession(final PeerTls tls, final UUID meshId, final String build,
                        final PeerEndpoint advertised, final int flowCapacity,
                        final Optional<String> offloadFlow) {
        this.tls = tls;
        this.meshId = meshId;
        this.build = build;
        this.advertised = advertised;
        this.capacity = flowCapacity;
        this.offloadFlow = offloadFlow.<RailixValue>map(RailixValue::string).orElseGet(RailixValue::nullValue);
        deadlines.setRemoveOnCancelPolicy(true);
    }

    /**
     * Loads the local identity and trust material and prepares the local state advertisement.
     * @param meshId mesh UUID required by certificates and hello messages
     * @param build logical build ID required in the peer hello
     * @param advertised endpoint announced to the peer
     * @param flowCapacity configured positive local flow capacity
     * @param offloadFlow compiled Trigger this replica accepts from peers, if configured
     * @param identity local PKCS12 key store
     * @param identityPassword file containing the identity store password
     * @param trust PKCS12 store of trusted issuers
     * @param trustPassword file containing the trust store password
     * @return a session handler ready for incoming and outgoing connections
     * @throws IOException if a credential file cannot be read
     * @throws GeneralSecurityException if the credentials or trust material are invalid
     */
    public static PeerSession open(final UUID meshId, final String build, final PeerEndpoint advertised,
                                   final int flowCapacity, final Optional<String> offloadFlow,
                                   final Path identity, final Path identityPassword,
                                   final Path trust, final Path trustPassword)
            throws IOException, GeneralSecurityException {
        final PeerTls tls = PeerTls.load(meshId, identity, identityPassword, trust, trustPassword);
        return new PeerSession(tls, meshId, build, advertised, flowCapacity, offloadFlow);
    }

    /** Attaches the receiver admission callback before starting connections. */
    public void setFlowAdmission(final Function<FlowContinuation, Optional<AdmittedFlow>> receiver) {
        admit = receiver;
    }

    /** Publishes the next state message for every session. */
    public synchronized void updateState(final String status, final int capacity) {
        if (state.equals(status) && this.capacity == capacity) {
            return;
        }
        state = status;
        this.capacity = capacity;
        localStateBody = null;
    }

    private synchronized byte[] localState() {
        if (localStateBody == null) {
            localStateBody = PeerProtocol.state(advertised, capacity, state, offloadFlow);
        }
        return localStateBody;
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
    }

    /** @return the local replica UUID from the loaded certificate */
    public UUID replicaId() {
        return tls.replicaId();
    }

    /**
     * Owns an accepted TCP socket until authentication fails or the peer disconnects.
     * @param transport accepted TCP socket
     * @param deadline absolute join deadline on the {@link System#nanoTime()} clock
     * @param onAuthenticated records the authenticated peer before selection
     * @param reserve claims a peer slot before sending initial state
     * @param onState publishes the joined peer and subsequent state changes
     * @param onRejected receives the rejection reason if the peer cannot join
     * @return whether the peer sent a follow-up heartbeat after the join
     * @throws InterruptedException if the caller is interrupted during the message exchange
     */
    public boolean accept(final Socket transport, final long deadline,
                          final Consumer<PeerConnection> onAuthenticated, final BooleanSupplier reserve,
                          final Predicate<RailixValue.ObjectValue> onState,
                          final Consumer<String> onRejected)
            throws InterruptedException {
        try {
            return join(tls.accept(transport, deadline), deadline, onAuthenticated, reserve, onState, onRejected);
        } catch (final IOException failure) {
            onRejected.accept(transportFailure(failure));
            return false;
        }
    }

    /**
     * Connects to one resolved peer address and runs the session until it disconnects.
     * @param address resolved peer address
     * @param deadline absolute join deadline on the {@link System#nanoTime()} clock
     * @param onAuthenticated records the authenticated peer before selection
     * @param reserve claims a peer slot before sending initial state
     * @param onState publishes the joined peer and subsequent state changes
     * @param onRejected receives the rejection reason if the peer cannot join
     * @return whether the peer sent a follow-up heartbeat after the join
     * @throws InterruptedException if the caller is interrupted during the message exchange
     */
    public boolean dial(final InetSocketAddress address, final long deadline,
                        final Consumer<PeerConnection> onAuthenticated, final BooleanSupplier reserve,
                        final Predicate<RailixValue.ObjectValue> onState,
                        final Consumer<String> onRejected)
            throws InterruptedException {
        try {
            return join(tls.dial(address, deadline), deadline, onAuthenticated, reserve, onState, onRejected);
        } catch (final IOException failure) {
            onRejected.accept(transportFailure(failure));
            return false;
        }
    }

    private boolean join(final PeerTlsConnection connection, final long deadline,
                         final Consumer<PeerConnection> onAuthenticated, final BooleanSupplier reserve,
                         final Predicate<RailixValue.ObjectValue> onState,
                         final Consumer<String> onRejected)
            throws InterruptedException {
        final AtomicLong expires = new AtomicLong(deadline);
        final Thread deadlineGuard = Thread.ofVirtual().name("railix-mesh-peer-deadline")
                .start(() -> expire(connection, expires));
        boolean joined = false;
        boolean heartbeatReceived = false;
        final Runnable disconnect = () -> abort(connection);
        try (connection; PeerFlowCalls flows = new PeerFlowCalls(
                connection.channel(), disconnect, build, admit, deadlines, retainedBytes)) {
            final Optional<RailixValue.ObjectValue> opening = exchangeHello(connection, deadline, onRejected);
            if (opening.isEmpty()) {
                return false;
            }

            final UUID replica = connection.replicaId();
            final RailixValue.ObjectValue peerHello = opening.orElseThrow();
            final UUID peerIncarnation = UUID.fromString(PeerProtocol.text(peerHello.values().get("incarnationId")));
            final PeerConnection peer = new PeerConnection(replica, peerIncarnation, flows, disconnect);
            onAuthenticated.accept(peer);
            final boolean choosesConnection = tls.replicaId().toString().compareTo(replica.toString()) < 0;
            final Optional<RailixValue.ObjectValue> peerState = exchangeInitialState(
                    connection.channel(), choosesConnection, reserve, expires, onRejected);
            if (peerState.isEmpty()) {
                return false;
            }
            if (!refresh(expires)) {
                onRejected.accept(MeshErrors.JOIN_TIMEOUT);
                return false;
            }
            final RailixValue.ObjectValue initialState = peerState.orElseThrow();
            joined = onState.test(observedPeer(peer, initialState));
            if (!joined) {
                return false;
            }
            heartbeatReceived = exchangeHeartbeats(peer, initialState, expires, onState);
        } catch (final IOException failure) {
            if (!joined) {
                onRejected.accept(expires.get() == 0 ? MeshErrors.JOIN_TIMEOUT : MeshErrors.PEER_UNAVAILABLE);
            }
        } finally {
            deadlineGuard.interrupt();
        }
        return heartbeatReceived;
    }

    private Optional<RailixValue.ObjectValue> exchangeHello(final PeerTlsConnection connection,
            final long deadline, final Consumer<String> onRejected)
            throws IOException, InterruptedException {
        final PeerChannel channel = connection.channel();
        final UUID localReplica = tls.replicaId();
        final byte[] hello = PeerProtocol.hello(build, meshId, localReplica, incarnation);
        PeerProtocol.write(channel, hello);

        final Optional<RailixValue.ObjectValue> opening = PeerProtocol.read(channel);
        if (opening.isEmpty()) {
            onRejected.accept(MeshErrors.INVALID_HELLO);
            return Optional.empty();
        }
        final Optional<String> rejected = PeerProtocol.rejectHello(opening.orElseThrow(), build, meshId,
                connection.replicaId(), localReplica);
        if (rejected.isPresent()) {
            onRejected.accept(rejected.orElseThrow());
            return Optional.empty();
        }
        if (deadline - System.nanoTime() <= 0) {
            onRejected.accept(MeshErrors.JOIN_TIMEOUT);
            return Optional.empty();
        }
        return opening;
    }

    private Optional<RailixValue.ObjectValue> exchangeInitialState(final PeerChannel channel, final boolean choosesConnection,
            final BooleanSupplier reserve, final AtomicLong expires, final Consumer<String> onRejected)
            throws IOException, InterruptedException {
        if (choosesConnection) {
            if (!reserve.getAsBoolean()) {
                return Optional.empty();
            }
            PeerProtocol.write(channel, localState());
            return readInitialState(channel, expires, onRejected);
        }

        // Wait for the choosing replica's state before reserving or replying on this socket.
        final Optional<RailixValue.ObjectValue> proposal = readInitialState(channel, expires, onRejected);
        if (proposal.isEmpty() || !reserve.getAsBoolean()) {
            return Optional.empty();
        }
        PeerProtocol.write(channel, localState());
        return proposal;
    }

    private static Optional<RailixValue.ObjectValue> readInitialState(final PeerChannel channel, final AtomicLong expires,
            final Consumer<String> onRejected) throws IOException, InterruptedException {
        final FrameRead frame = channel.read(PeerFrames.MAX_CONTROL_BODY_BYTES, size -> Optional.of(new byte[size]));
        if (expires.get() == 0) {
            onRejected.accept(MeshErrors.JOIN_TIMEOUT);
            return Optional.empty();
        }
        if (frame == FrameReadStatus.END_OF_STREAM) {
            return Optional.empty();
        }
        final Optional<RailixValue.ObjectValue> peerState = frame instanceof FrameBody body
                ? PeerProtocol.decode(body).filter(PeerProtocol::validState) : Optional.empty();
        if (peerState.isEmpty()) {
            onRejected.accept(MeshErrors.INVALID_STATE);
        }
        return peerState;
    }

    private boolean exchangeHeartbeats(final PeerConnection peer,
                                       final RailixValue.ObjectValue initialState, final AtomicLong expires,
                                       final Predicate<RailixValue.ObjectValue> onState)
            throws InterruptedException {
        final PeerFlowCalls flows = peer.flows();
        final ScheduledFuture<?> heartbeat;
        try {
            heartbeat = deadlines.scheduleWithFixedDelay(() -> flows.queueHeartbeat(this::localState),
                    HEARTBEAT.toNanos(), HEARTBEAT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final RejectedExecutionException stopped) {
            return false;
        }
        boolean heartbeatReceived = false;
        try {
            RailixValue.ObjectValue publishedState = initialState;
            while (!Thread.currentThread().isInterrupted()) {
                final Optional<PeerMessage> next = flows.read();
                if (next.isEmpty()) {
                    break;
                }
                final PeerMessage message = next.orElseThrow();
                final RailixValue.ObjectValue state = message.value();
                if (!"state".equals(PeerProtocol.text(state.values().get("type")))) {
                    if (!flows.receive(message)) {
                        break;
                    }
                    continue;
                }
                if (message.bodyBytes() > PeerFrames.MAX_CONTROL_BODY_BYTES) {
                    break;
                }
                final boolean changed = !state.equals(publishedState);
                if ((changed && !PeerProtocol.validState(state)) || !refresh(expires)) {
                    break;
                }
                heartbeatReceived = true;
                if (!changed) {
                    continue;
                }
                if (!onState.test(observedPeer(peer, state))) {
                    break;
                }
                publishedState = state;
            }
        } catch (final IOException disconnected) {
            // The session ended. An earlier heartbeat still counts for retry backoff.
        } finally {
            heartbeat.cancel(false);
        }
        return heartbeatReceived;
    }

    private static RailixValue.ObjectValue observedPeer(final PeerConnection peer, final RailixValue.ObjectValue state) {
        final Map<String, RailixValue> fields = state.values();
        return RailixValue.object(Map.of(
                "replicaId", RailixValue.string(peer.replicaId().toString()),
                "incarnationId", RailixValue.string(peer.incarnationId().toString()),
                "status", fields.get("status"),
                "advertisedEndpoint", fields.get("advertisedEndpoint"),
                "availableFlowSlots", fields.get("availableFlowSlots"),
                "offloadFlow", fields.get("offloadFlow")
        ));
    }

    private static void expire(final PeerTlsConnection connection, final AtomicLong expires) {
        while (!Thread.currentThread().isInterrupted()) {
            final long deadline = expires.get();
            final long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            } else if (expires.compareAndSet(deadline, 0)) {
                abort(connection);
                return;
            }
        }
    }

    private static boolean refresh(final AtomicLong expires) {
        final long deadline = expires.get();
        final long now = System.nanoTime();
        return deadline != 0 && deadline - now > 0 && expires.compareAndSet(deadline, now + STALE_NANOS);
    }

    private static void abort(final PeerTlsConnection connection) {
        try {
            connection.abort();
        } catch (final IOException ignored) {
            // The session owner also closes the framed channel.
        }
    }

    private static String transportFailure(final IOException failure) {
        if (failure instanceof SocketTimeoutException) {
            return MeshErrors.JOIN_TIMEOUT;
        }
        if (failure instanceof SSLException) {
            return MeshErrors.TLS_AUTHENTICATION;
        }
        return MeshErrors.PEER_UNAVAILABLE;
    }
}
