package dev.nanonative.railix.core.runtime;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;

/** Canonical trigger-independent run result without exception-driven user control flow. */
public sealed interface RunResult permits
        RunResult.Succeeded,
        RunResult.Rejected,
        RunResult.Failed,
        RunResult.Cancelled {
    record Succeeded(RailixValue.ObjectValue context, List<StepExecution> steps) implements RunResult {
        public Succeeded {
            steps = List.copyOf(steps);
        }
    }

    record Rejected(List<Diagnostic> diagnostics, List<StepExecution> steps) implements RunResult {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
            steps = List.copyOf(steps);
        }
    }

    record Failed(RunFailure failure, List<StepExecution> steps) implements RunResult {
        public Failed {
            steps = List.copyOf(steps);
        }
    }

    /** Cancellation observed at an execution boundary, after the listed Steps completed. */
    record Cancelled(List<StepExecution> steps) implements RunResult {
        public Cancelled {
            steps = List.copyOf(steps);
        }
    }

    record StepExecution(String stepId, String outcome) {
    }
}
