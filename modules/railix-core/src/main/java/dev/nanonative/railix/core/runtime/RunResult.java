package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;

import java.util.ArrayDeque;
import java.util.Optional;

/** Canonical trigger-independent run result without exception-driven user control flow. */
public sealed interface RunResult permits
        RunResult.Succeeded,
        RunResult.Completed,
        RunResult.Rejected,
        RunResult.Failed,
        RunResult.Cancelled {

    /**
     * Validates the contexts that a complete result would expose at an observation boundary.
     * This explicit check does not run on individual Step calls or change execution outcomes.
     * @return the first canonical-data rejection, or empty when all exposed contexts are valid
     */
    default Optional<String> contextRejection() {
        final var pending = new ArrayDeque<RunResult>();
        pending.add(this);
        final ValueRefinement canonical = ValueRefinement.canonical();
        while (!pending.isEmpty()) {
            final RunResult current = pending.removeFirst();
            final RailixValue.ObjectValue context = switch (current) {
                case Succeeded succeeded -> succeeded.context();
                case Completed completed -> {
                    completed.paths().forEach(path -> pending.addLast(path.result()));
                    yield completed.context();
                }
                default -> null;
            };
            if (context == null && current instanceof Succeeded) return Optional.of("Run context cannot be Java null.");
            if (context != null) {
                final Optional<String> rejection = canonical.rejection(context);
                if (rejection.isPresent()) return rejection;
            }
        }
        return Optional.empty();
    }

    record Succeeded(RailixValue.ObjectValue context) implements RunResult {
    }

    /**
     * Completed orchestration with independently reported paths, not one synthetic success/failure.
     * The context is the shared parent context. Isolated path changes remain in their own results.
     *
     * @param context non-null final parent context
     * @param paths immutable results of the explicitly started paths
     */
    record Completed(RailixValue.ObjectValue context, java.util.List<PathResult> paths) implements RunResult {
        public Completed {
            java.util.Objects.requireNonNull(context, "Completed context cannot be Java null.");
            paths = java.util.List.copyOf(paths);
        }
    }

    /**
     * One explicitly executed outcome and its independent result.
     *
     * @param stepId Step placement that started the path
     * @param outcome declared outgoing route of that placement
     * @param result non-null terminal path result
     * @throws IllegalArgumentException for a blank identity or outcome
     * @throws NullPointerException for a Java-null result
     */
    record PathResult(String stepId, String outcome, RunResult result) {
        public PathResult {
            if (stepId == null || stepId.isBlank() || outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("Path identity and outcome must be non-blank.");
            }
            java.util.Objects.requireNonNull(result, "Path result cannot be Java null.");
        }
    }

    record Rejected(java.util.List<Diagnostic> diagnostics) implements RunResult {
        public Rejected {
            diagnostics = java.util.List.copyOf(diagnostics);
        }
    }

    record Failed(RunFailure failure) implements RunResult {
    }

    /** Cancellation observed at an execution boundary. */
    record Cancelled() implements RunResult {
    }
}
