package dev.nanonative.railix.mesh;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Shares one dial and retry history across the current resolutions of configured seeds. */
final class BootstrapRetries {
    private final Map<String, Target> targets = new HashMap<>();

    synchronized List<Target> replace(final List<Target> previous, final List<InetSocketAddress> addresses) {
        // Add the new references first so unchanged DNS results retain their retry history.
        final LinkedHashSet<Target> resolved = new LinkedHashSet<>();
        for (final InetSocketAddress address : addresses) {
            final Target target = targets.computeIfAbsent(key(address), key -> new Target(key, address));
            if (resolved.add(target)) {
                target.references++;
            }
        }
        for (final Target target : previous) {
            target.references--;
            if (target.references == 0) {
                targets.remove(target.key);
            }
        }
        return List.copyOf(resolved);
    }

    synchronized boolean tryStart(final Target target) {
        if (target.dialing || target.retryAt - System.nanoTime() > 0) {
            return false;
        }
        target.dialing = true;
        return true;
    }

    synchronized void finish(final Target target, final boolean heartbeatReceived) {
        target.retryAt = System.nanoTime();
        if (heartbeatReceived) {
            target.backoff.reset();
        } else {
            target.retryAt += TimeUnit.MILLISECONDS.toNanos(target.backoff.nextDelayMillis());
        }
        target.dialing = false;
        notifyAll();
    }

    synchronized void awaitRetry(final List<Target> candidates) throws InterruptedException {
        while (true) {
            final long now = System.nanoTime();
            long remaining = Long.MAX_VALUE;
            for (final Target target : candidates) {
                if (!target.dialing) {
                    remaining = Math.min(remaining, target.retryAt - now);
                }
            }
            if (remaining <= 0) {
                return;
            }
            if (remaining == Long.MAX_VALUE) {
                wait();
            } else {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }

    private static String key(final InetSocketAddress endpoint) {
        final InetAddress address = endpoint.getAddress();
        final String host = address.getHostAddress();
        if (address instanceof Inet6Address ipv6) {
            final int separator = host.indexOf('%');
            final String literal = separator < 0 ? host : host.substring(0, separator);
            return "[" + literal + "%" + ipv6.getScopeId() + "]:" + endpoint.getPort();
        }
        return host + ":" + endpoint.getPort();
    }

    static final class Target {
        private final String key;
        private final InetSocketAddress address;
        private final Backoff backoff = new Backoff();
        private long retryAt = System.nanoTime();
        private int references;
        private boolean dialing;

        private Target(final String key, final InetSocketAddress address) {
            this.key = key;
            this.address = address;
        }

        InetSocketAddress address() {
            return address;
        }
    }

    static final class Backoff {
        private int ceiling = 1_000;

        int nextDelayMillis() {
            final int delay = ThreadLocalRandom.current().nextInt(ceiling / 2, ceiling + 1);
            ceiling = Math.min(ceiling * 2, 30_000);
            return delay;
        }

        void reset() {
            ceiling = 1_000;
        }
    }
}
