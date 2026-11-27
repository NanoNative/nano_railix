package dev.nanonative.railix.mesh.peer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/** Owns the bounded FIFO for all frames after a peer has joined. */
final class PeerWriter implements AutoCloseable {
    private final PeerChannel channel;
    private final ScheduledExecutorService timers;
    private final Runnable abort;
    private final int capacity;
    private final ArrayDeque<PeerWrite> pending = new ArrayDeque<>();
    private boolean closed;

    PeerWriter(final PeerChannel channel, final ScheduledExecutorService timers, final Runnable abort, final int capacity) {
        this.channel = channel;
        this.timers = timers;
        this.abort = abort;
        this.capacity = capacity;
        Thread.ofVirtual().name("railix-mesh-peer-writer").start(this::run);
    }

    synchronized boolean offer(final PeerWrite write) {
        if (closed || pending.size() == capacity) {
            return false;
        }
        pending.addLast(write);
        notifyAll();
        return true;
    }

    void remove(final PeerWrite write) {
        final boolean removed;
        synchronized (this) {
            removed = pending.remove(write);
        }
        if (removed) {
            write.complete().accept(PeerWrite.Status.SKIPPED);
        }
    }

    private synchronized PeerWrite take() throws InterruptedException {
        while (pending.isEmpty() && !closed) {
            wait();
        }
        return pending.pollFirst();
    }

    private void run() {
        try {
            while (true) {
                final PeerWrite write = take();
                if (write == null || !send(write)) {
                    return;
                }
            }
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    private boolean send(final PeerWrite write) {
        PeerWrite.Status status = PeerWrite.Status.FAILED;
        try {
            final boolean written = channel.writeBefore(
                    write.prepare(), write.limit(), write.deadline(), timers, abort);
            status = written ? PeerWrite.Status.WRITTEN : PeerWrite.Status.SKIPPED;
            return true;
        } catch (final IOException | RuntimeException failure) {
            abort.run();
            return false;
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
            abort.run();
            return false;
        } finally {
            write.complete().accept(status);
        }
    }

    @Override
    public void close() {
        final List<PeerWrite> dropped;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            dropped = List.copyOf(pending);
            pending.clear();
            notifyAll();
        }
        dropped.forEach(write -> write.complete().accept(PeerWrite.Status.SKIPPED));
    }
}
