package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.FlowContinuation;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class FlowWire {
    static final String ADMISSION_NANOS = "admissionNanos";
    static final String LOGICAL_BUILD_ID = "logicalBuildId";
    static final String TRIGGER = "trigger";
    static final String TRIGGER_OUTCOME = "outcome";
    static final String INPUT = "input";
    static final Set<String> INVOCATION_FIELDS = Set.of(
            ADMISSION_NANOS, "type", "id", LOGICAL_BUILD_ID, TRIGGER, TRIGGER_OUTCOME, INPUT);

    private static final long MAX_ADMISSION_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final String EMPTY_BUDGET = "0000000000";
    private static final String BUDGET_PREFIX = "{\"" + ADMISSION_NANOS + "\":\"";
    private static final Pattern BUDGET_DIGITS = Pattern.compile("[0-9]{10}");
    private static final int MAX_DATA = PeerFrames.MAX_DATA_BODY_BYTES - PeerFrames.MAX_CONTROL_BODY_BYTES;
    // The outer object takes the place of the input value when counting envelope nodes.
    private static final int INVOCATION_NODES = PeerProtocol.MAX_NODES - 2 * INVOCATION_FIELDS.size();
    private static final int OUTCOME_DEPTH = RailixJson.MAX_DOCUMENT_DEPTH - 1;
    // The outer object, its three keys, and the id and type values.
    private static final int OUTCOME_NODES = PeerProtocol.MAX_NODES - 6;
    // A path object, three keys, two strings, and at least a three-node cancellation result.
    private static final int MIN_PATH_NODES = 9;
    // A diagnostic object with five scalar fields.
    private static final int DIAGNOSTIC_NODES = 11;
    // A child that exceeds its budget returns this payload for the outer reply.
    private static final RailixValue.ObjectValue OUTCOME_LIMIT_FAILURE = failure(new RunFailure(
            MeshErrors.FLOW_RESULT_INVALID, "Flow outcome exceeds the mesh value limits.", ""));

    private FlowWire() { }

    static boolean fitsInvocationLimits(final RailixValue.ObjectValue value) {
        try {
            return !RailixJson.measureTransfer(value, MAX_DATA, INVOCATION_NODES, RailixData.DEFAULT_MAX_DEPTH).exceeded();
        } catch (final IllegalArgumentException invalidInput) {
            return false;
        }
    }

    static byte[] encodeInvocation(final FlowContinuation continuation, final String id, final String build) {
        final RailixValue.ObjectValue input = continuation.input();
        if (!fitsInvocationLimits(input)) {
            return PeerFrames.NO_BODY;
        }
        final Map<String, RailixValue> frame = new LinkedHashMap<>();
        frame.put(ADMISSION_NANOS, RailixValue.string(EMPTY_BUDGET));
        frame.put("type", RailixValue.string("invoke"));
        frame.put("id", RailixValue.string(id));
        frame.put(LOGICAL_BUILD_ID, RailixValue.string(build));
        frame.put(TRIGGER, RailixValue.string(continuation.trigger()));
        frame.put(TRIGGER_OUTCOME, RailixValue.number(continuation.outcome()));
        frame.put(INPUT, input);
        try {
            final String document = encodeDocument(RailixValue.object(frame));
            if (document.isEmpty()) {
                return PeerFrames.NO_BODY;
            }
            if (!document.startsWith(BUDGET_PREFIX + EMPTY_BUDGET + "\",")) {
                throw new IllegalStateException(MeshErrors.INVALID_INVOCATION_FRAME_LAYOUT);
            }
            return document.getBytes(StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException invalidInput) {
            return PeerFrames.NO_BODY;
        }
    }

    private static String encodeDocument(final RailixValue.ObjectValue frame) {
        return RailixJson.writeTransferDocument(frame, PeerFrames.MAX_DATA_BODY_BYTES, PeerProtocol.MAX_NODES);
    }

    // The invocation envelope puts the budget first. Only the writer changes it.
    static byte[] stampInvocation(final byte[] frame, final long remaining) {
        long nanos = Math.clamp(remaining, 0L, MAX_ADMISSION_NANOS);
        for (int index = BUDGET_PREFIX.length() + EMPTY_BUDGET.length() - 1; index >= BUDGET_PREFIX.length(); index--) {
            frame[index] = (byte) ('0' + nanos % 10);
            nanos /= 10;
        }
        return frame;
    }

    static long admissionNanos(final RailixValue value) {
        final String digits = PeerProtocol.text(value);
        if (!BUDGET_DIGITS.matcher(digits).matches()) {
            return -1;
        }
        final long nanos = Long.parseLong(digits);
        return nanos <= MAX_ADMISSION_NANOS ? nanos : -1;
    }

    static byte[] encodeOutcome(final String id, final RunResult result) {
        try {
            final RailixValue.ObjectValue payload = encodeResult(result, new OutcomeBudget(), OUTCOME_DEPTH);
            if (payload != OUTCOME_LIMIT_FAILURE) {
                final String document = encodeDocument(outcomeMessage(id, payload));
                if (!document.isEmpty()) {
                    return document.getBytes(StandardCharsets.UTF_8);
                }
            }
        } catch (final IllegalArgumentException invalidResult) {
            // Report the terminal error without copying the offending value into a diagnostic.
        }
        final String document = encodeDocument(outcomeMessage(id, OUTCOME_LIMIT_FAILURE));
        if (document.isEmpty()) {
            throw new IllegalStateException(MeshErrors.FLOW_RESULT_INVALID);
        }
        return document.getBytes(StandardCharsets.UTF_8);
    }

    private static RailixValue.ObjectValue outcomeMessage(final String id, final RailixValue.ObjectValue payload) {
        return RailixValue.object(Map.of("type", RailixValue.string("outcome"),
                "id", RailixValue.string(id), "payload", payload));
    }

    static Optional<RunResult> decodeOutcome(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue payload)) {
            return Optional.empty();
        }
        try {
            if (!RailixJson.measureTransfer(payload, MAX_DATA, OUTCOME_NODES, OUTCOME_DEPTH).exceeded()) {
                return readResult(payload);
            }
        } catch (final IllegalArgumentException invalidResult) {
            // The result is outside the wire contract.
        }
        return Optional.empty();
    }

    private static RailixValue.ObjectValue encodeResult(final RunResult result, final OutcomeBudget budget,
                                                        final int depth) {
        if (depth < 1) {
            return OUTCOME_LIMIT_FAILURE;
        }
        return switch (result) {
            case RunResult.Succeeded success ->
                    budget.consumeIfFits(object("succeeded", "context", success.context()), contextDepth(depth));
            case RunResult.Completed completed -> encodeCompleted(completed, budget, depth);
            case RunResult.Rejected rejected -> encodeRejection(rejected, budget, depth);
            case RunResult.Failed failed -> budget.consumeIfFits(failure(failed.failure()), depth);
            case RunResult.Cancelled ignored ->
                    budget.consumeIfFits(RailixValue.object(Map.of("kind", RailixValue.string("cancelled"))), depth);
        };
    }

    private static int contextDepth(final int depth) {
        // These result fields wrap the context in one object.
        return Math.min(depth, RailixData.DEFAULT_MAX_DEPTH + 1);
    }

    private static RailixValue.ObjectValue encodeCompleted(final RunResult.Completed completed,
            final OutcomeBudget budget, final int depth) {
        final RailixValue.StringValue kind = RailixValue.string("completed");
        final RailixValue.ObjectValue header = RailixValue.object(Map.of("kind", kind, "context", completed.context()));
        if (depth < 2 || !budget.tryConsumeValue(header, contextDepth(depth))
                || !budget.tryConsumeArrayOverhead("paths", completed.paths().size(), MIN_PATH_NODES)) {
            return OUTCOME_LIMIT_FAILURE;
        }
        final List<RailixValue> paths = new ArrayList<>();
        for (final RunResult.PathResult path : completed.paths()) {
            final RailixValue.StringValue step = RailixValue.string(path.stepId());
            final RailixValue.StringValue outcome = RailixValue.string(path.outcome());
            if (!budget.tryConsumeValue(RailixValue.object(Map.of("step", step, "outcome", outcome)), 1)
                    || !budget.tryConsumeFieldOverhead("result")) {
                return OUTCOME_LIMIT_FAILURE;
            }
            // This child sits inside the result object, the paths array, and a path object.
            final RailixValue.ObjectValue child = encodeResult(path.result(), budget, depth - 3);
            if (child == OUTCOME_LIMIT_FAILURE) {
                return OUTCOME_LIMIT_FAILURE;
            }
            paths.add(RailixValue.object(Map.of("step", step, "outcome", outcome, "result", child)));
        }
        return RailixValue.object(Map.of("kind", kind, "context", completed.context(), "paths", RailixValue.array(paths)));
    }

    private static RailixValue.ObjectValue encodeRejection(final RunResult.Rejected rejected,
            final OutcomeBudget budget, final int depth) {
        final int requiredDepth = rejected.diagnostics().isEmpty() ? 2 : 3;
        final RailixValue.StringValue kind = RailixValue.string("rejected");
        if (depth < requiredDepth || !budget.tryConsumeValue(RailixValue.object(Map.of("kind", kind)), 1)
                || !budget.tryConsumeArrayOverhead("diagnostics", rejected.diagnostics().size(), DIAGNOSTIC_NODES)) {
            return OUTCOME_LIMIT_FAILURE;
        }
        final List<RailixValue> diagnostics = new ArrayList<>();
        for (final Diagnostic diagnostic : rejected.diagnostics()) {
            final RailixValue.ObjectValue value = diagnostic(diagnostic);
            if (!budget.tryConsumeValue(value, 1)) {
                return OUTCOME_LIMIT_FAILURE;
            }
            diagnostics.add(value);
        }
        return RailixValue.object(Map.of("kind", kind, "diagnostics", RailixValue.array(diagnostics)));
    }

    private static RailixValue.ObjectValue failure(final RunFailure failure) {
        return object("failed", "failure", RailixValue.object(Map.of(
                "code", RailixValue.string(failure.code()), "message", RailixValue.string(failure.message()),
                "step", RailixValue.string(failure.stepId()), "path", RailixValue.string(failure.path()))));
    }

    private static RailixValue.ObjectValue object(final String kind, final String name, final RailixValue value) {
        return RailixValue.object(Map.of("kind", RailixValue.string(kind), name, value));
    }

    private static RailixValue.ObjectValue diagnostic(final Diagnostic value) {
        return RailixValue.object(Map.of("code", RailixValue.string(value.code()),
                "message", RailixValue.string(value.message()), "path", RailixValue.string(value.path()),
                "line", RailixValue.number(value.line()), "column", RailixValue.number(value.column())));
    }

    private static Optional<RunResult> readResult(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue result)) {
            return Optional.empty();
        }
        final Map<String, RailixValue> fields = result.values();
        final RailixValue context = fields.get("context");
        return switch (PeerProtocol.text(fields.get("kind"))) {
            case "succeeded" -> fields.size() == 2 && context instanceof RailixValue.ObjectValue data
                    && fitsContextDepth(data, RailixData.DEFAULT_MAX_DEPTH)
                    ? Optional.of(new RunResult.Succeeded(data)) : Optional.empty();
            case "completed" -> fields.size() == 3 && context instanceof RailixValue.ObjectValue data
                    && fitsContextDepth(data, RailixData.DEFAULT_MAX_DEPTH)
                    ? readPaths(data, fields.get("paths")) : Optional.empty();
            case "rejected" -> fields.size() == 2 ? readDiagnostics(fields.get("diagnostics")) : Optional.empty();
            case "failed" -> fields.size() == 2 ? readFailure(fields.get("failure")) : Optional.empty();
            case "cancelled" -> fields.size() == 1 ? Optional.of(new RunResult.Cancelled()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean fitsContextDepth(final RailixValue value, final int remainingDepth) {
        final Iterable<RailixValue> children;
        switch (value) {
            case RailixValue.ArrayValue array -> children = array.values();
            case RailixValue.ObjectValue object -> children = object.values().values();
            default -> { return true; }
        }
        if (remainingDepth == 0) {
            return false;
        }
        for (final RailixValue child : children) {
            if (!fitsContextDepth(child, remainingDepth - 1)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<RunResult> readPaths(final RailixValue.ObjectValue data, final RailixValue value) {
        if (!(value instanceof RailixValue.ArrayValue array)) {
            return Optional.empty();
        }
        final List<RunResult.PathResult> paths = new ArrayList<>();
        for (final RailixValue item : array.values()) {
            if (!(item instanceof RailixValue.ObjectValue path) || path.values().size() != 3) {
                return Optional.empty();
            }
            final String step = PeerProtocol.text(path.values().get("step"));
            final String outcome = PeerProtocol.text(path.values().get("outcome"));
            final Optional<RunResult> result = readResult(path.values().get("result"));
            if (step.isBlank() || outcome.isBlank() || result.isEmpty()) {
                return Optional.empty();
            }
            paths.add(new RunResult.PathResult(step, outcome, result.orElseThrow()));
        }
        return Optional.of(new RunResult.Completed(data, paths));
    }

    private static Optional<RunResult> readDiagnostics(final RailixValue value) {
        if (!(value instanceof RailixValue.ArrayValue array)) {
            return Optional.empty();
        }
        final List<Diagnostic> diagnostics = new ArrayList<>();
        for (final RailixValue item : array.values()) {
            if (!(item instanceof RailixValue.ObjectValue diagnostic) || diagnostic.values().size() != 5) {
                return Optional.empty();
            }
            final Map<String, RailixValue> fields = diagnostic.values();
            if (!(fields.get("code") instanceof RailixValue.StringValue code)
                    || !(fields.get("message") instanceof RailixValue.StringValue message)
                    || !(fields.get("path") instanceof RailixValue.StringValue path)) {
                return Optional.empty();
            }
            final long line = integer(fields.get("line"), 0, Integer.MAX_VALUE);
            final long column = integer(fields.get("column"), 0, Integer.MAX_VALUE);
            if (line < 0 || column < 0) {
                return Optional.empty();
            }
            diagnostics.add(new Diagnostic(code.value(), message.value(), path.value(), (int) line, (int) column));
        }
        return Optional.of(new RunResult.Rejected(diagnostics));
    }

    private static Optional<RunResult> readFailure(final RailixValue value) {
        if (!(value instanceof RailixValue.ObjectValue failure) || failure.values().size() != 4) {
            return Optional.empty();
        }
        final Map<String, RailixValue> fields = failure.values();
        if (!(fields.get("code") instanceof RailixValue.StringValue code)
                || !(fields.get("message") instanceof RailixValue.StringValue message)
                || !(fields.get("step") instanceof RailixValue.StringValue step)
                || !(fields.get("path") instanceof RailixValue.StringValue path)) {
            return Optional.empty();
        }
        return Optional.of(new RunResult.Failed(new RunFailure(code.value(), message.value(), step.value(), path.value())));
    }

    static long integer(final RailixValue value, final long minimum, final long maximum) {
        if (!(value instanceof RailixValue.NumberValue number)) {
            return -1;
        }
        final java.math.BigDecimal decimal = number.value();
        if (decimal.compareTo(java.math.BigDecimal.valueOf(minimum)) < 0
                || decimal.compareTo(java.math.BigDecimal.valueOf(maximum)) > 0
                || decimal.stripTrailingZeros().scale() > 0) {
            return -1;
        }
        return decimal.longValue();
    }

    /** Charges only the part being added so branches stop before building an oversized result. */
    private static final class OutcomeBudget {
        private int bytes = MAX_DATA;
        private int nodes = OUTCOME_NODES;

        private RailixValue.ObjectValue consumeIfFits(final RailixValue.ObjectValue value, final int depth) {
            return tryConsumeValue(value, depth) ? value : OUTCOME_LIMIT_FAILURE;
        }

        private boolean tryConsumeValue(final RailixValue value, final int depth) {
            if (bytes == 0 || nodes == 0) {
                return false;
            }
            final RailixJson.TransferMeasurement measurement = RailixJson.measureTransfer(value, bytes, nodes, depth);
            if (measurement.exceeded()) {
                return false;
            }
            bytes = measurement.remainingBytes();
            nodes = measurement.remainingNodes();
            return true;
        }

        private boolean tryConsumeArrayOverhead(final String name, final int size, final int nodesPerItem) {
            return size <= (nodes - 2) / nodesPerItem
                    && tryConsumeFieldOverhead(name) && tryConsume(2 + Math.max(0, size - 1), 1);
        }

        private boolean tryConsumeFieldOverhead(final String name) {
            // These field names are fixed ASCII. Count the quotes, colon, and preceding comma.
            return tryConsume(name.length() + 4, 1);
        }

        private boolean tryConsume(final int byteCount, final int nodeCount) {
            if (byteCount > bytes || nodeCount > nodes) {
                return false;
            }
            bytes -= byteCount;
            nodes -= nodeCount;
            return true;
        }
    }
}
