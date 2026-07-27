package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical JSON responses shared by the CLI and Creator HTTP boundary. */
final class RailixProtocol {
    private RailixProtocol() {
    }

    static RailixValue.ObjectValue diagnostics(
            final String status,
            final List<Diagnostic> diagnostics
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string(status),
                "diagnostics", diagnosticValues(diagnostics)
        ));
    }

    static RailixValue.ObjectValue diagnostics(
            final String status,
            final List<Diagnostic> diagnostics,
            final List<RunResult.StepExecution> steps
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string(status),
                "diagnostics", diagnosticValues(diagnostics),
                "steps", executions(steps)
        ));
    }

    static RailixValue.ObjectValue failure(
            final String status,
            final RunFailure failure,
            final List<RunResult.StepExecution> steps
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string(status),
                "failure", RailixValue.object(Map.of(
                        "code", RailixValue.string(failure.code()),
                        "message", RailixValue.string(failure.message()),
                        "step", RailixValue.string(failure.stepId())
                )),
                "steps", executions(steps)
        ));
    }

    static RailixValue.ObjectValue success(
            final RailixValue.ObjectValue outputs,
            final List<RunResult.StepExecution> steps
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("succeeded"),
                "outputs", outputs,
                "steps", executions(steps)
        ));
    }

    static RailixValue.ObjectValue cancelled(final List<RunResult.StepExecution> steps) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("run-cancelled"),
                "steps", executions(steps)
        ));
    }

    static RailixValue.ObjectValue error(
            final String status,
            final String code,
            final String message
    ) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string(status),
                "error", RailixValue.object(Map.of(
                        "code", RailixValue.string(code),
                        "message", RailixValue.string(message)
                ))
        ));
    }

    static RailixValue.ObjectValue creatorReady(final String creatorUrl) {
        return RailixValue.object(Map.of(
                "status", RailixValue.string("creator-ready"),
                "creatorUrl", RailixValue.string(creatorUrl)
        ));
    }

    private static RailixValue.ObjectValue diagnostic(final Diagnostic diagnostic) {
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        fields.put("code", RailixValue.string(diagnostic.code()));
        fields.put("message", RailixValue.string(diagnostic.message()));
        fields.put("path", RailixValue.string(diagnostic.path()));
        fields.put("line", RailixValue.number(diagnostic.line()));
        fields.put("column", RailixValue.number(diagnostic.column()));
        return RailixValue.object(fields);
    }

    private static RailixValue.ArrayValue diagnosticValues(final List<Diagnostic> diagnostics) {
        return RailixValue.array(diagnostics.stream()
                .map(RailixProtocol::diagnostic)
                .map(RailixValue.class::cast)
                .toList());
    }

    private static RailixValue.ArrayValue executions(final List<RunResult.StepExecution> steps) {
        return RailixValue.array(steps.stream()
                .map(step -> RailixValue.object(Map.of(
                        "step", RailixValue.string(step.stepId()),
                        "outcome", RailixValue.string(step.outcome())
                )))
                .map(RailixValue.class::cast)
                .toList());
    }
}
