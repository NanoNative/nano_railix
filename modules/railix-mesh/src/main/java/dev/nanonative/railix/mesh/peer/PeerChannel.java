package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.mesh.MeshErrors;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;

/**
 * Owns one connected peer socket with exclusive access to each direction.
 * Callers bound pending calls and provide socket I/O deadlines.
 */
final class PeerChannel implements Closeable {
    private static final Set<Socket> CLAIMED_SOCKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private final Socket socket;
    private final InputStream input;
    private final DataOutputStream output;
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Ownership of the connected socket transfers on successful construction. Close it through this channel.
     * @throws IOException if the socket is closed or its streams cannot be opened
     * @throws IllegalStateException if another channel has already claimed this socket
     */
    PeerChannel(final Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        if (!CLAIMED_SOCKETS.add(socket)) {
            throw new IllegalStateException(MeshErrors.SOCKET_ALREADY_CLAIMED);
        }
        boolean opened = false;
        try {
            if (socket.isClosed()) {
                throw new SocketException(MeshErrors.SOCKET_CLOSED);
            }
            input = socket.getInputStream();
            output = new DataOutputStream(socket.getOutputStream());
            opened = true;
        } finally {
            if (!opened) {
                CLAIMED_SOCKETS.remove(socket);
            }
        }
    }

    /** Reads one frame using a caller-reserved buffer. An incomplete frame closes the channel. */
    FrameRead read(final int bodyLimit, final IntFunction<Optional<byte[]>> reserveBody)
            throws IOException, InterruptedException {
        final int limit = PeerFrames.checkedBodyLimit(bodyLimit);
        Objects.requireNonNull(reserveBody, "reserveBody");
        readLock.lockInterruptibly();
        try {
            return receiveFrame(limit, reserveBody);
        } finally {
            readLock.unlock();
        }
    }

    /** Writes one frame. The caller keeps the body stable until this call returns. */
    boolean write(final byte[] body, final int bodyLimit) throws IOException, InterruptedException {
        writeLock.lockInterruptibly();
        try {
            final boolean written = PeerFrames.write(output, body, bodyLimit);
            if (written) {
                output.flush();
            }
            return written;
        } catch (final IOException failure) {
            throw closeAfterFailure(failure);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            socket.close();
        } finally {
            if (socket.isClosed()) {
                CLAIMED_SOCKETS.remove(socket);
            }
        }
    }

    private FrameRead receiveFrame(final int bodyLimit, final IntFunction<Optional<byte[]>> reserveBody)
            throws IOException {
        final FrameRead result;
        try {
            result = PeerFrames.read(input, bodyLimit, reserveBody);
        } catch (final IOException | RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }
        if (result instanceof FrameReadStatus) {
            close();
        }
        return result;
    }

    private <E extends Exception> E closeAfterFailure(final E failure) {
        try {
            close();
        } catch (final IOException closing) {
            if (failure != closing) {
                failure.addSuppressed(closing);
            }
        }
        return failure;
    }
}
