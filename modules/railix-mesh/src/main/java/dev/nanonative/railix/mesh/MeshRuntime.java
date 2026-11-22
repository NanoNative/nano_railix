package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.peer.PeerSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/** Owns the fixed-peer mesh listener and session for one generated application's lifetime. */
public final class MeshRuntime implements AutoCloseable {
    private static final long LISTEN_LOOKUP_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long JOIN_NANOS = Duration.ofSeconds(5).toNanos();
    private static final RailixValue ABSENT = RailixValue.nullValue();

    private final MeshConfig config;
    private final PeerSession peerSession;
    private final String build;
    private final Semaphore sessionSlot = new Semaphore(1);
    private final PeerAddressResolver resolver;
    private final ServerSocket listener;
    private final Thread acceptor;
    private final Thread dialer;
    private volatile boolean closed;
    private volatile RailixValue.ObjectValue status;

    private MeshRuntime(final MeshConfig config, final PeerSession peerSession, final String build,
                        final ServerSocket listener, final PeerAddressResolver resolver) {
        this.config = config;
        this.peerSession = peerSession;
        this.build = build;
        this.listener = listener;
        this.resolver = resolver;
        acceptor = Thread.ofVirtual().name("railix-mesh-listener").unstarted(this::accept);
        dialer = Thread.ofVirtual().name("railix-mesh-bootstrap").unstarted(this::dial);
        publish("starting", ABSENT, ABSENT);
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
            final MeshConfig config = MeshConfig.load(Path.of(configurationPath));
            final PeerSession peerSession = PeerSession.open(config.meshId(), logicalBuildId,
                    config.advertised(), config.flowCapacity(), config.identity().path(), config.identity().passwordFile(),
                    config.trust().path(), config.trust().passwordFile());
            final var resolver = new PeerAddressResolver();
            final var listen = resolver.resolve(config.listen(), System.nanoTime() + LISTEN_LOOKUP_NANOS).getFirst();
            final ServerSocket listener = new ServerSocket();
            try {
                listener.setReuseAddress(true);
                listener.bind(listen, 1);
                return Optional.of(new MeshRuntime(config, peerSession, logicalBuildId, listener, resolver));
            } catch (final IOException | RuntimeException failure) {
                try {
                    listener.close();
                } catch (final IOException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + MeshErrors.LISTEN_LOOKUP_INTERRUPTED, interrupted);
        } catch (final IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException(MeshErrors.STARTUP_FAILED_PREFIX + failure.getMessage(), failure);
        }
    }

    /** Starts peer processing after the application's local sources launch successfully. */
    public synchronized void start() {
        if (closed || acceptor.getState() != Thread.State.NEW) {
            return;
        }
        publish("waiting", ABSENT, ABSENT);
        acceptor.start();
        if (!config.bootstrap().isEmpty()) {
            dialer.start();
        }
    }

    /** @return the immutable current view for authenticated local observation */
    public RailixValue.ObjectValue status() {
        return status;
    }

    /** Releases the listener and interrupts peer I/O and bootstrap waits on application shutdown. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        publish("draining", ABSENT, ABSENT);
        acceptor.interrupt();
        dialer.interrupt();
        try {
            listener.close();
        } catch (final IOException ignored) {
            // The acceptor has already been interrupted.
        }
    }

    private void accept() {
        try {
            while (!closed) {
                try (Socket transport = listener.accept()) {
                    final long deadline = System.nanoTime() + JOIN_NANOS;
                    if (!sessionSlot.tryAcquire()) {
                        continue;
                    }
                    try {
                        if (peerSession.accept(transport, deadline, this::joined, this::reject)) {
                            publish("waiting", ABSENT, ABSENT);
                        }
                    } finally {
                        sessionSlot.release();
                    }
                }
            }
        } catch (final IOException failure) {
            rejectWhileIdle(MeshErrors.LISTENER_UNAVAILABLE);
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void dial() {
        final var endpoints = config.bootstrap();
        int next = 0;
        int ceiling = 1_000;
        try {
            while (!closed) {
                // Wait out an active peer without reserving the slot during hostname resolution.
                sessionSlot.acquire();
                sessionSlot.release();
                if (closed) {
                    return;
                }
                final boolean joined = dial(endpoints.get(next));
                next = (next + 1) % endpoints.size();
                if (joined) {
                    ceiling = 1_000;
                } else {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(ceiling / 2, ceiling + 1));
                    ceiling = Math.min(ceiling * 2, 30_000);
                }
            }
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean dial(final PeerEndpoint seed) throws InterruptedException {
        final long deadline = System.nanoTime() + JOIN_NANOS;
        final List<InetSocketAddress> addresses;
        try {
            addresses = resolver.resolve(seed, deadline);
        } catch (final SocketTimeoutException expired) {
            rejectWhileIdle(MeshErrors.JOIN_TIMEOUT);
            return false;
        } catch (final IOException failure) {
            rejectWhileIdle(MeshErrors.PEER_UNAVAILABLE);
            return false;
        }
        if (!sessionSlot.tryAcquire()) {
            return false;
        }
        try {
            for (int index = 0; index < addresses.size() && !closed; index++) {
                final long now = System.nanoTime();
                final long remaining = deadline - now;
                if (remaining <= 0) {
                    reject(MeshErrors.JOIN_TIMEOUT);
                    return false;
                }
                // Leave time for the other addresses and carry unused time forward.
                final int untriedAddresses = addresses.size() - index;
                final long attemptDeadline = now + Math.max(1, remaining / untriedAddresses);
                if (peerSession.dial(addresses.get(index), attemptDeadline, this::joined, this::reject)) {
                    publish("waiting", ABSENT, ABSENT);
                    return true;
                }
            }
            return false;
        } finally {
            sessionSlot.release();
        }
    }

    private void joined(final RailixValue.ObjectValue peer) {
        publish("joined", peer, ABSENT);
    }

    private void reject(final String reason) {
        publish("rejected", ABSENT, RailixValue.string(reason));
    }

    private void rejectWhileIdle(final String reason) {
        if (!sessionSlot.tryAcquire()) {
            return;
        }
        try {
            reject(reason);
        } finally {
            sessionSlot.release();
        }
    }

    private synchronized void publish(final String phase, final RailixValue peer, final RailixValue rejection) {
        if (closed && !"draining".equals(phase)) {
            return;
        }
        status = RailixValue.object(Map.of(
                "status", RailixValue.string(phase),
                "logicalBuildId", RailixValue.string(build),
                "replicaId", RailixValue.string(peerSession.replicaId().toString()),
                "peer", peer,
                "rejection", rejection
        ));
    }
}
