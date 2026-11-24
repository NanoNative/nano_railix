package dev.nanonative.railix.mesh.peer;

import java.util.UUID;

/**
 * Identifies an authenticated connection and the remote process using it.
 * @param replicaId certificate-bound remote replica
 * @param incarnationId remote process lifetime
 * @param initiator replica that dialed the connection
 * @param connectionId identifier shared by both ends of the connection
 * @param disconnect action that closes the transport and interrupts pending I/O
 */
public record PeerConnection(
        UUID replicaId, UUID incarnationId, UUID initiator, UUID connectionId, Runnable disconnect
) implements AutoCloseable {

    /** Closes the underlying transport. */
    @Override
    public void close() {
        disconnect.run();
    }
}
