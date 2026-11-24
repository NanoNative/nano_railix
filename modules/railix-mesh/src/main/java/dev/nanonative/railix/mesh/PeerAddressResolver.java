package dev.nanonative.railix.mesh;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds the wait for the JDK hostname resolver during startup and peer dialing. */
final class PeerAddressResolver {
    private final Semaphore lookupSlot = new Semaphore(1, true);

    List<InetSocketAddress> resolve(final PeerEndpoint endpoint, final long deadline)
            throws IOException, InterruptedException {
        try {
            return List.of(new InetSocketAddress(InetAddress.ofLiteral(endpoint.host()), endpoint.port()));
        } catch (final IllegalArgumentException hostname) {
            // Numeric addresses can still be tried while a previous hostname lookup is blocked.
        }
        final FutureTask<List<InetSocketAddress>> result = startLookup(endpoint, deadline);
        try {
            return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException expired) {
            throw new SocketTimeoutException(MeshErrors.HOST_LOOKUP_TIMED_OUT);
        } catch (final ExecutionException failed) {
            final Throwable cause = failed.getCause();
            if (cause instanceof IOException lookup) {
                throw lookup;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IOException(MeshErrors.HOST_LOOKUP_FAILED, cause);
        } finally {
            result.cancel(true);
        }
    }

    private FutureTask<List<InetSocketAddress>> startLookup(final PeerEndpoint endpoint, final long deadline)
            throws SocketTimeoutException, InterruptedException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !lookupSlot.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
            throw new SocketTimeoutException(MeshErrors.HOST_LOOKUP_TIMED_OUT);
        }
        boolean started = false;
        try {
            if (deadline - System.nanoTime() <= 0) {
                throw new SocketTimeoutException(MeshErrors.HOST_LOOKUP_TIMED_OUT);
            }
            final FutureTask<List<InetSocketAddress>> result = new FutureTask<>(() -> lookup(endpoint));
            Thread.ofPlatform().daemon().name("railix-mesh-resolver").start(() -> {
                try {
                    result.run();
                } finally {
                    // A native lookup can outlive cancellation. Release the slot when the worker finishes.
                    lookupSlot.release();
                }
            });
            started = true;
            return result;
        } finally {
            if (!started) {
                lookupSlot.release();
            }
        }
    }

    private static List<InetSocketAddress> lookup(final PeerEndpoint endpoint) throws UnknownHostException {
        final InetAddress[] addresses = InetAddress.getAllByName(endpoint.host());
        return Arrays.stream(addresses)
                .map(address -> new InetSocketAddress(address, endpoint.port()))
                .toList();
    }
}
