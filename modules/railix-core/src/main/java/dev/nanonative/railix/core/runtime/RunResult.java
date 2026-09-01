package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixValue;

/** Canonical trigger-independent run result without exception-driven user control flow. */
public sealed interface RunResult permits
        RunResult.Succeeded,
        RunResult.Rejected,
        RunResult.Failed,
        RunResult.Cancelled {
    record Succeeded(RailixValue.ObjectValue context) implements RunResult {
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
