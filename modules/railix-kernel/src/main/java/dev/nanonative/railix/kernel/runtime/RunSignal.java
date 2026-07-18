package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public sealed interface RunSignal permits RunSignal.RunStarted,
        RunSignal.StepStarted,
        RunSignal.StepFinished,
        RunSignal.StepFailed,
        RunSignal.ContextPatched,
        RunSignal.PermissionDecided,
        RunSignal.SettingRead,
        RunSignal.MetricEmitted,
        RunSignal.AuditEmitted,
        RunSignal.ResourceCreated,
        RunSignal.ReplyProduced,
        RunSignal.RunFinished {

    String signalId();
    String appId();
    String flowId();
    String runId();
    Instant timestamp();
    String type();

    default Optional<String> stepId() {
        return Optional.empty();
    }

    default OptionalInt attempt() {
        return OptionalInt.empty();
    }

    record RunStarted(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            RailixValue inputEnvelopeSummary
    ) implements RunSignal {
        public RunStarted {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            inputEnvelopeSummary = Objects.requireNonNull(inputEnvelopeSummary, "inputEnvelopeSummary");
        }

        @Override
        public String type() {
            return "run.started";
        }
    }

    record StepStarted(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber
    ) implements RunSignal {
        public StepStarted {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
        }

        @Override
        public String type() {
            return "step.started";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record StepFinished(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            String outcome,
            long durationMs
    ) implements RunSignal {
        public StepFinished {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            outcome = requireNonBlank(outcome, "outcome");
            validateDuration(durationMs);
        }

        @Override
        public String type() {
            return "step.finished";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record StepFailed(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            RailixValue errorSummary
    ) implements RunSignal {
        public StepFailed {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            errorSummary = Objects.requireNonNull(errorSummary, "errorSummary");
        }

        @Override
        public String type() {
            return "step.failed";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record ContextPatched(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            RailixValue patchSummary,
            List<RailixPath> changedPaths
    ) implements RunSignal {
        public ContextPatched {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            patchSummary = Objects.requireNonNull(patchSummary, "patchSummary");
            changedPaths = List.copyOf(changedPaths);
        }

        @Override
        public String type() {
            return "context.patched";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record PermissionDecided(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            String permission,
            String resource,
            String decision,
            String reason
    ) implements RunSignal {
        public PermissionDecided {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            permission = requireNonBlank(permission, "permission");
            resource = requireNonBlank(resource, "resource");
            decision = requireNonBlank(decision, "decision");
            reason = requireNonBlank(reason, "reason");
        }

        @Override
        public String type() {
            return "permission.decided";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record SettingRead(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            RailixPath path,
            boolean secret,
            boolean materialized
    ) implements RunSignal {
        public SettingRead {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            path = Objects.requireNonNull(path, "path");
        }

        @Override
        public String type() {
            return "setting.read";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record MetricEmitted(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            String name,
            RailixValue value,
            String unit,
            Map<String, String> labels
    ) implements RunSignal {
        public MetricEmitted {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            name = requireNonBlank(name, "name");
            value = Objects.requireNonNull(value, "value");
            unit = requireNonBlank(unit, "unit");
            labels = Map.copyOf(labels);
        }

        @Override
        public String type() {
            return "metric.emitted";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record AuditEmitted(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            String eventName,
            RailixValue.ObjectValue data
    ) implements RunSignal {
        public AuditEmitted {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            eventName = requireNonBlank(eventName, "eventName");
            data = Objects.requireNonNull(data, "data");
        }

        @Override
        public String type() {
            return "audit.emitted";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record ResourceCreated(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String step,
            int attemptNumber,
            String refId,
            String refType,
            RailixValue.ObjectValue metadata
    ) implements RunSignal {
        public ResourceCreated {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            step = requireNonBlank(step, "step");
            validateAttempt(attemptNumber);
            refId = requireNonBlank(refId, "refId");
            refType = requireNonBlank(refType, "refType");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public String type() {
            return "resource.created";
        }

        @Override
        public Optional<String> stepId() {
            return Optional.of(step);
        }

        @Override
        public OptionalInt attempt() {
            return OptionalInt.of(attemptNumber);
        }
    }

    record ReplyProduced(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String mode,
            RailixValue status,
            RailixValue.ObjectValue metadata
    ) implements RunSignal {
        public ReplyProduced {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            mode = requireNonBlank(mode, "mode");
            status = Objects.requireNonNull(status, "status");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public String type() {
            return "reply.produced";
        }
    }

    record RunFinished(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String outcome,
            long durationMs
    ) implements RunSignal {
        public RunFinished {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            outcome = requireNonBlank(outcome, "outcome");
            validateDuration(durationMs);
        }

        @Override
        public String type() {
            return "run.finished";
        }
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static void validateAttempt(final int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }

    private static void validateDuration(final long durationMs) {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
    }
}
