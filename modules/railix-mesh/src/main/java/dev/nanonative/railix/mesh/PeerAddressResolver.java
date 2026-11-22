package dev.nanonative.railix.mesh;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds the wait for the JDK hostname resolver during startup and peer dialing. */
final class PeerAddressResolver {
    private Thread worker;

    List<InetSocketAddress> resolve(final PeerEndpoint endpoint, final long deadline)
            throws IOException, InterruptedException {
        try {
            return List.of(new InetSocketAddress(InetAddress.ofLiteral(endpoint.host()), endpoint.port()));
        } catch (final IllegalArgumentException hostname) {
            // Numeric addresses can still be tried while a previous hostname lookup is blocked.
        }
        if (worker != null && worker.isAlive()) {
            throw new SocketTimeoutException(MeshErrors.HOST_LOOKUP_IN_PROGRESS);
        }

        final var result = new FutureTask<>(() -> {
            final InetAddress[] addresses = InetAddress.getAllByName(endpoint.host());
            return Arrays.stream(addresses)
                    .map(address -> new InetSocketAddress(address, endpoint.port()))
                    .toList();
        });
        worker = Thread.ofPlatform().daemon().name("railix-mesh-resolver").start(result);
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
            // The native resolver may ignore interruption. Keep its worker until it actually exits.
            result.cancel(true);
        }
    }
}
