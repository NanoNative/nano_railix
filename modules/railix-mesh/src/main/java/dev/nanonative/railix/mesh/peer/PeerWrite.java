package dev.nanonative.railix.mesh.peer;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One submitted frame and the notification that returns its reservation. */
record PeerWrite(Supplier<Optional<byte[]>> prepare, int limit, long deadline, Consumer<Status> complete) {
    enum Status {
        SKIPPED, WRITTEN, FAILED
    }
}
