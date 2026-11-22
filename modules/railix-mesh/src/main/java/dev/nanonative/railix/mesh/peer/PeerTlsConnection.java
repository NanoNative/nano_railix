package dev.nanonative.railix.mesh.peer;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/** Holds the verified remote identity and owns the framed socket until close. */
record PeerTlsConnection(UUID replicaId, PeerChannel channel, Socket transport) implements Closeable {
    /** Interrupts pending I/O without waiting for the peer's TLS close notification. */
    void abort() throws IOException {
        transport.close();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
