package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.runtime.RunResult;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A reservation for one receiver execution through its terminal reply handoff.
 * @param execute returns empty if the application declines before executing the continuation
 * @param release returns the shared execution reservation
 */
public record AdmittedFlow(Supplier<Optional<RunResult>> execute, Runnable release) implements AutoCloseable {
    @Override
    public void close() {
        release.run();
    }
}
