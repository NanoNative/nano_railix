package dev.nanonative.railix.mesh.peer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerChannelTest {
    @Test
    void onlyOneChannelCanClaimTheSameSocket() throws Exception {
        final CyclicBarrier start = new CyclicBarrier(2);
        try (PeerStreamSocket socket = new PeerStreamSocket(
                InputStream.nullInputStream(), OutputStream.nullOutputStream());
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final Callable<Optional<PeerChannel>> claim = () -> {
                start.await(2, SECONDS);
                try {
                    return Optional.of(new PeerChannel(socket));
                } catch (final IllegalStateException duplicate) {
                    return Optional.empty();
                }
            };
            final Future<Optional<PeerChannel>> first = tasks.submit(claim);
            final Future<Optional<PeerChannel>> second = tasks.submit(claim);
            final List<PeerChannel> channels = Stream.of(first.get(2, SECONDS), second.get(2, SECONDS))
                    .flatMap(Optional::stream).toList();
            assertEquals(1, channels.size());
            assertFalse(socket.isClosed());
            channels.getFirst().close();
            assertTrue(socket.isClosed());
            assertThrows(SocketException.class, () -> new PeerChannel(socket));
        }
    }

    @Test
    void failedConstructionLeavesTheSocketWithItsCaller() throws Exception {
        try (Socket socket = new Socket()) {
            assertThrows(IOException.class, () -> new PeerChannel(socket));
            assertThrows(IOException.class, () -> new PeerChannel(socket));
            assertFalse(socket.isClosed());
        }
    }

    @Test
    void overlappingWritesKeepWholeFramesAndAllowAnIndependentRead() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final ByteArrayInputStream input = new ByteArrayInputStream(wire("000000012a"));
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(input, output));
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<Boolean> first = tasks.submit(() -> channel.write(new byte[]{1, 2}, 3));
            try {
                assertTrue(output.headerWritten.await(2, SECONDS));
                final CountDownLatch secondStarted = new CountDownLatch(1);
                final Future<Boolean> second = tasks.submit(() -> {
                    secondStarted.countDown();
                    return channel.write(new byte[]{3, 4, 5}, 3);
                });
                assertTrue(secondStarted.await(2, SECONDS));

                final Future<FrameRead> read = tasks.submit(() -> channel.read(1, size -> Optional.of(new byte[size])));
                assertArrayEquals(new byte[]{42}, assertInstanceOf(FrameBody.class, read.get(2, SECONDS)).bytes());
                assertThrows(TimeoutException.class, () -> second.get(100, MILLISECONDS));

                output.resume.countDown();
                assertTrue(first.get(2, SECONDS));
                assertTrue(second.get(2, SECONDS));
                assertArrayEquals(wire("00000002010200000003030405"), output.bytes.toByteArray());
            } finally {
                output.resume.countDown();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "'', END_OF_STREAM, , 0",
            "0001, TRUNCATED_HEADER, , 0",
            "0000000201, TRUNCATED_BODY, 2, 0",
            "00000000aa, INVALID_LENGTH, , 1",
            "00010001aa, INVALID_LENGTH, , 1",
            "ffffffffaa, INVALID_LENGTH, , 1"
    })
    void closesOnTerminalFrameReads(final String bytes, final FrameReadStatus reason,
                                    final Integer requestedBytes, final int unreadBytes) throws Exception {
        final ByteArrayInputStream input = new ByteArrayInputStream(wire(bytes));
        final PeerStreamSocket socket = new PeerStreamSocket(input, OutputStream.nullOutputStream());
        final ArrayList<Integer> requests = new ArrayList<Integer>();
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertEquals(reason, channel.read(PeerFrames.MAX_CONTROL_BODY_BYTES, size -> {
                requests.add(size);
                return Optional.of(new byte[size]);
            }));
            assertEquals(requestedBytes == null ? List.of() : List.of(requestedBytes), requests);
            assertEquals(unreadBytes, input.available());
            assertTrue(socket.isClosed());
        }
    }

    @Test
    void closesWithoutReadingTheBodyWhenCapacityIsUnavailable() throws Exception {
        final ByteArrayInputStream input = new ByteArrayInputStream(wire("00000002aabb"));
        final PeerStreamSocket socket = new PeerStreamSocket(input, OutputStream.nullOutputStream());
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertEquals(FrameReadStatus.BODY_UNAVAILABLE, channel.read(2, size -> {
                assertEquals(2, size);
                return Optional.empty();
            }));
            assertEquals(2, input.available());
            assertTrue(socket.isClosed());
        }
    }

    @Test
    void closesSocketAfterAPartialWrite() throws Exception {
        final HeaderOnlyOutputStream output = new HeaderOnlyOutputStream();
        final PeerStreamSocket socket = new PeerStreamSocket(InputStream.nullInputStream(), output);
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertSame(output.failure, assertThrows(IOException.class, () -> channel.write(new byte[]{42}, 1)));
            assertArrayEquals(wire("00000001"), output.bytes.toByteArray());
            assertTrue(socket.isClosed());
        }
    }

    private static byte[] wire(final String bytes) {
        return HexFormat.of().parseHex(bytes);
    }
}

final class PeerStreamSocket extends Socket {
    private final InputStream input;
    private final OutputStream output;

    PeerStreamSocket(final InputStream input, final OutputStream output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }
}

final class FrameWriteGate extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final CountDownLatch headerWritten = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);

    @Override
    public void write(final int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(final byte[] source, final int offset, final int length) throws IOException {
        bytes.write(source, offset, length);
        if (bytes.size() == Integer.BYTES) {
            headerWritten.countDown();
            try {
                if (!resume.await(5, SECONDS)) {
                    throw new IOException("Test did not release the frame header.");
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("Test interrupted the frame write.", interruption);
            }
        }
    }
}

final class HeaderOnlyOutputStream extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Integer.BYTES);
    final IOException failure = new IOException("Test stopped the stream after the header.");

    @Override
    public void write(final int value) throws IOException {
        if (bytes.size() == Integer.BYTES) {
            throw failure;
        }
        bytes.write(value);
    }
}
