package dev.nanonative.railix.mesh.peer;

import java.util.UUID;

/**
 * Identifies an authenticated connection and the remote process using it.
 * @param replicaId certificate-bound remote replica
 * @param incarnationId remote process lifetime
 * @param flows invocation exchange tied to this authenticated connection
 * @param disconnect action that closes the transport and interrupts pending I/O
 */
public record PeerConnection(
        UUID replicaId, UUID incarnationId, PeerFlowCalls flows, Runnable disconnect
) implements AutoCloseable {

    /** Closes the underlying transport. */
    @Override
    public void close() {
        disconnect.run();
    }
}
