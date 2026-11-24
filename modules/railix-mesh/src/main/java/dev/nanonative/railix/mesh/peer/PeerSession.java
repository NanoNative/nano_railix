package dev.nanonative.railix.mesh.peer;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Runs authenticated peer connections for one mesh replica. */
public final class PeerSession {
    private static final long STALE_NANOS = Duration.ofSeconds(8).toNanos();
    private static final Duration HEARTBEAT = Duration.ofSeconds(2);

    private final PeerTls tls;
    private final UUID meshId;
    private final String build;
    private final UUID incarnation = UUID.randomUUID();
    private final byte[] localStateBody;

    private PeerSession(final PeerTls tls, final UUID meshId, final String build,
                        final byte[] localStateBody) {
        this.tls = tls;
        this.meshId = meshId;
        this.build = build;
        this.localStateBody = localStateBody;
    }

    /**
     * Loads the local identity and trust material and prepares the local state advertisement.
     * @param meshId mesh UUID required by certificates and hello messages
     * @param build logical build ID required in the peer hello
     * @param advertised endpoint announced to the peer
     * @param flowCapacity configured positive local flow capacity
     * @param identity local PKCS12 key store
     * @param identityPassword file containing the identity store password
     * @param trust PKCS12 store of trusted issuers
     * @param trustPassword file containing the trust store password
     * @return a session handler ready for incoming and outgoing connections
     * @throws IOException if a credential file cannot be read
     * @throws GeneralSecurityException if the credentials or trust material are invalid
     */
    public static PeerSession open(final UUID meshId, final String build, final PeerEndpoint advertised,
                                   final int flowCapacity, final Path identity, final Path identityPassword,
                                   final Path trust, final Path trustPassword)
            throws IOException, GeneralSecurityException {
        final PeerTls tls = PeerTls.load(meshId, identity, identityPassword, trust, trustPassword);
        return new PeerSession(tls, meshId, build, PeerProtocol.state(advertised, flowCapacity));
    }

    /** @return the local replica UUID from the loaded certificate */
    public UUID replicaId() {
        return tls.replicaId();
    }

    /**
     * Owns an accepted TCP socket until authentication fails or the peer disconnects.
     * @param transport accepted TCP socket
     * @param deadline absolute join deadline on the {@link System#nanoTime()} clock
     * @param onState accepts or declines the connection on join and on observed state changes
     * @param onRejected receives the rejection reason if the peer cannot join
     * @return whether the peer sent a follow-up heartbeat after the join
     * @throws InterruptedException if the caller is interrupted during the message exchange
     */
    public boolean accept(final Socket transport, final long deadline,
                          final BiPredicate<PeerConnection, RailixValue.ObjectValue> onState,
                          final Consumer<String> onRejected)
            throws InterruptedException {
        try {
            return join(tls.accept(transport, deadline), false, deadline, onState, onRejected);
        } catch (final IOException failure) {
            onRejected.accept(transportFailure(failure));
            return false;
        }
    }

    /**
     * Connects to one resolved peer address and runs the session until it disconnects.
     * @param address resolved peer address
     * @param deadline absolute join deadline on the {@link System#nanoTime()} clock
     * @param onState accepts or declines the connection on join and on observed state changes
     * @param onRejected receives the rejection reason if the peer cannot join
     * @return whether the peer sent a follow-up heartbeat after the join
     * @throws InterruptedException if the caller is interrupted during the message exchange
     */
    public boolean dial(final InetSocketAddress address, final long deadline,
                        final BiPredicate<PeerConnection, RailixValue.ObjectValue> onState,
                        final Consumer<String> onRejected)
            throws InterruptedException {
        try {
            return join(tls.dial(address, deadline), true, deadline, onState, onRejected);
        } catch (final IOException failure) {
            onRejected.accept(transportFailure(failure));
            return false;
        }
    }

    private boolean join(final PeerTlsConnection connection, final boolean outbound, final long deadline,
                         final BiPredicate<PeerConnection, RailixValue.ObjectValue> onState,
                         final Consumer<String> onRejected)
            throws InterruptedException {
        final AtomicLong expires = new AtomicLong(deadline);
        final Thread deadlineGuard = Thread.ofVirtual().name("railix-mesh-peer-deadline")
                .start(() -> expire(connection, expires));
        boolean joined = false;
        boolean heartbeatReceived = false;
        try (connection) {
            final PeerChannel channel = connection.channel();
            final Optional<UUID> connectionId = outbound ? Optional.of(UUID.randomUUID()) : Optional.empty();
            final byte[] localHello = PeerProtocol.hello(build, meshId, tls.replicaId(), incarnation, connectionId);
            PeerProtocol.write(channel, localHello);

            final Optional<RailixValue.ObjectValue> opening = PeerProtocol.read(channel);
            if (opening.isEmpty()) {
                onRejected.accept(MeshErrors.INVALID_HELLO);
                return false;
            }
            final RailixValue.ObjectValue peerHello = opening.get();
            final Optional<String> rejected = PeerProtocol.rejectHello(peerHello, build, meshId, connection.replicaId(),
                    tls.replicaId(), !outbound);
            if (rejected.isPresent()) {
                onRejected.accept(rejected.get());
                return false;
            }
            if (deadline - System.nanoTime() <= 0) {
                onRejected.accept(MeshErrors.JOIN_TIMEOUT);
                return false;
            }

            PeerProtocol.write(channel, localStateBody);
            final Optional<RailixValue.ObjectValue> peerState = PeerProtocol.read(channel)
                    .filter(PeerProtocol::validState);
            if (peerState.isEmpty()) {
                onRejected.accept(MeshErrors.INVALID_STATE);
                return false;
            }
            if (!refresh(expires)) {
                onRejected.accept(MeshErrors.JOIN_TIMEOUT);
                return false;
            }

            final UUID replica = connection.replicaId();
            final UUID peerIncarnation = UUID.fromString(PeerProtocol.text(peerHello.values().get("incarnationId")));
            final UUID initiator = outbound ? tls.replicaId() : replica;
            final UUID id = connectionId.orElseGet(() ->
                    UUID.fromString(PeerProtocol.text(peerHello.values().get("connectionId"))));
            final PeerConnection peer = new PeerConnection(replica, peerIncarnation, initiator, id,
                    () -> abort(connection));
            final RailixValue.ObjectValue initialState = peerState.orElseThrow();
            joined = onState.test(peer, observedPeer(peer, initialState));
            if (!joined) {
                return false;
            }
            heartbeatReceived = exchangeHeartbeats(connection, peer, initialState, expires, onState);
        } catch (final IOException failure) {
            if (!joined) {
                onRejected.accept(expires.get() == 0 ? MeshErrors.JOIN_TIMEOUT : MeshErrors.PEER_UNAVAILABLE);
            }
        } finally {
            deadlineGuard.interrupt();
        }
        // Initial state can arrive before the remote side has admitted the connection.
        return heartbeatReceived;
    }

    private boolean exchangeHeartbeats(final PeerTlsConnection connection, final PeerConnection peer,
                                       final RailixValue.ObjectValue initialState, final AtomicLong expires,
                                       final BiPredicate<PeerConnection, RailixValue.ObjectValue> onState)
            throws InterruptedException {
        final Thread heartbeat = Thread.ofVirtual().name("railix-mesh-peer-heartbeat")
                .start(() -> heartbeat(connection, localStateBody));
        boolean heartbeatReceived = false;
        try {
            RailixValue.ObjectValue publishedState = initialState;
            while (!Thread.currentThread().isInterrupted()) {
                final Optional<RailixValue.ObjectValue> next = PeerProtocol.read(connection.channel());
                if (next.isEmpty()) {
                    break;
                }
                final RailixValue.ObjectValue state = next.orElseThrow();
                final boolean changed = !state.equals(publishedState);
                if (changed && !PeerProtocol.validState(state)) {
                    break;
                }
                if (!refresh(expires)) {
                    break;
                }
                heartbeatReceived = true;
                if (changed) {
                    if (!onState.test(peer, observedPeer(peer, state))) {
                        break;
                    }
                    publishedState = state;
                }
            }
        } catch (final IOException disconnected) {
            // The session ended. An earlier heartbeat still counts for retry backoff.
        } finally {
            heartbeat.interrupt();
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
                "availableFlowSlots", fields.get("availableFlowSlots")
        ));
    }

    private static void heartbeat(final PeerTlsConnection connection, final byte[] stateBody) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(HEARTBEAT);
                PeerProtocol.write(connection.channel(), stateBody);
            }
        } catch (final IOException failure) {
            abort(connection);
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
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
