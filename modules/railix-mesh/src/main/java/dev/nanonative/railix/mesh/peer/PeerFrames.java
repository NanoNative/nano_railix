package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.mesh.MeshErrors;

import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/** Length framing for encoded mesh messages on a caller-owned stream. */
final class PeerFrames {
    static final int MAX_CONTROL_BODY_BYTES = 64 * 1024;
    static final int MAX_DATA_BODY_BYTES = 1024 * 1024 + MAX_CONTROL_BODY_BYTES;
    static final byte[] NO_BODY = new byte[0];

    private PeerFrames() {
    }

    /**
     * Requests an exact-size body buffer only after validating the length prefix.
     * A zero-length buffer declines the reservation and leaves the body unread.
     * The caller owns the reservation's lifetime.
     * The connection owner supplies a checked body limit, handles terminal outcomes and enforces the read deadline.
     */
    static FrameRead read(final InputStream input, final int bodyLimit,
                          final IntFunction<byte[]> reserveBody) throws IOException {
        final byte[] header = input.readNBytes(Integer.BYTES);
        if (header.length == 0) {
            return FrameReadStatus.END_OF_STREAM;
        }
        if (header.length != Integer.BYTES) {
            return FrameReadStatus.TRUNCATED_HEADER;
        }
        final long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (!fitsBody(length, bodyLimit)) {
            return FrameReadStatus.INVALID_LENGTH;
        }
        final int size = (int) length;
        final long headerReceivedNanos = System.nanoTime();
        final byte[] body = reserveBody.apply(size);
        if (body.length == 0) {
            return FrameReadStatus.BODY_UNAVAILABLE;
        }
        if (body.length != size) {
            throw new IllegalArgumentException(MeshErrors.INVALID_RESERVED_BUFFER);
        }
        return input.readNBytes(body, 0, size) == size
                ? new FrameBody(body, headerReceivedNanos) : FrameReadStatus.TRUNCATED_BODY;
    }

    /**
     * Writes one encoded frame and returns false without writing when the body exceeds its limit
     * or is empty. The caller owns the stream and serializes writes across whole frames.
     */
    static boolean write(final DataOutput output, final byte[] body, final int bodyLimit) throws IOException {
        if (!fitsBody(body.length, checkedBodyLimit(bodyLimit))) {
            return false;
        }
        output.writeInt(body.length);
        output.write(body);
        return true;
    }

    private static boolean fitsBody(final long bytes, final int limit) {
        return bytes > 0 && bytes <= limit;
    }

    static int checkedBodyLimit(final int bytes) {
        if (bytes < 1 || bytes > MAX_DATA_BODY_BYTES) {
            throw new IllegalArgumentException(MeshErrors.INVALID_FRAME_LIMIT.formatted(MAX_DATA_BODY_BYTES));
        }
        return bytes;
    }
}
