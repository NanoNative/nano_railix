package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads bounded Creator-visible run artifact details from persisted summary and signal files.
 */
final class CreatorRunArtifactSnapshot {

    private static final int MAX_SIGNAL_PREVIEW_ROWS = 12;
    private static final int MAX_TIMELINE_ROWS = 12;
    private static final int MAX_CHANGED_PATHS = 16;
    private static final int MAX_METRIC_SERIES = 8;
    private static final int MAX_AUDIT_EVENTS = 8;
    private static final int MAX_VALUE_PREVIEW_CHARS = 160;
    private static final int MAX_LABEL_KEYS = 6;
    private static final int MAX_AUDIT_KEYS = 6;
    private static final int MAX_ARTIFACT_ERROR_CHARS = 200;

    private final int signalCount;
    private final List<Map<String, Object>> latestSignals;
    private final String lastSignalType;
    private final List<Map<String, Object>> timeline;
    private final Map<String, Object> graphActivity;
    private final Map<String, Object> contextDiff;
    private final Map<String, Object> metrics;
    private final Map<String, Object> audits;
    private final Map<String, Object> summary;
    private final String summaryReadStatus;
    private final String summaryReadError;
    private final String signalStreamStatus;
    private final String signalReadError;

    private CreatorRunArtifactSnapshot(
            final int signalCount,
            final List<Map<String, Object>> latestSignals,
            final String lastSignalType,
            final List<Map<String, Object>> timeline,
            final Map<String, Object> graphActivity,
            final Map<String, Object> contextDiff,
            final Map<String, Object> metrics,
            final Map<String, Object> audits,
            final Map<String, Object> summary,
            final String summaryReadStatus,
            final String summaryReadError,
            final String signalStreamStatus,
            final String signalReadError
    ) {
        this.signalCount = signalCount;
        this.latestSignals = List.copyOf(latestSignals);
        this.lastSignalType = lastSignalType;
        this.timeline = List.copyOf(timeline);
        this.graphActivity = Map.copyOf(graphActivity);
        this.contextDiff = Map.copyOf(contextDiff);
        this.metrics = Map.copyOf(metrics);
        this.audits = Map.copyOf(audits);
        this.summary = Map.copyOf(summary);
        this.summaryReadStatus = summaryReadStatus;
        this.summaryReadError = summaryReadError;
        this.signalStreamStatus = signalStreamStatus;
        this.signalReadError = signalReadError;
    }

    static CreatorRunArtifactSnapshot capture(final Path runFolder, final Path signalsFile) {
        final SummaryReadResult summaryReadResult = readSummary(runFolder);
        if (!Files.isRegularFile(signalsFile)) {
            return new CreatorRunArtifactSnapshot(
                    0,
                    List.of(),
                    "",
                    List.of(),
                    emptyGraphActivity(),
                    emptyContextDiff(),
                    emptyMetrics(),
                    emptyAudits(),
                    summaryReadResult.summary(),
                    summaryReadResult.status(),
                    summaryReadResult.error(),
                    "empty",
                    ""
            );
        }
        final ArrayDeque<Map<String, Object>> latestSignals = new ArrayDeque<>(MAX_SIGNAL_PREVIEW_ROWS);
        final ArrayDeque<Map<String, Object>> timeline = new ArrayDeque<>(MAX_TIMELINE_ROWS);
        Map<String, Object> latestContextDiff = emptyContextDiff();
        final LinkedHashMap<String, MetricAggregate> metricAggregates = new LinkedHashMap<>();
        final LinkedHashMap<String, AuditAggregate> auditAggregates = new LinkedHashMap<>();
        final GraphActivityAggregate graphActivityAggregate = new GraphActivityAggregate();
        int metricCount = 0;
        int auditCount = 0;
        int signalCount = 0;
        String signalStreamStatus = "complete";
        String signalReadError = "";
        try (java.util.stream.Stream<String> lines = Files.lines(signalsFile)) {
            int lineNumber = 0;
            for (final String line : (Iterable<String>) lines.filter(value -> !value.isBlank())::iterator) {
                lineNumber++;
                final Map<String, Object> decoded;
                try {
                    decoded = KernelContractCodec.parseStableJsonObject(line);
                } catch (final RuntimeException exception) {
                    signalStreamStatus = "partial";
                    signalReadError = summarize(lineNumber, exception);
                    continue;
                }
                signalCount++;
                graphActivityAggregate.accept(decoded);
                offer(latestSignals, decoded, MAX_SIGNAL_PREVIEW_ROWS);
                final Map<String, Object> event = summarizeTimelineEvent(decoded);
                if (!event.isEmpty()) {
                    offer(timeline, event, MAX_TIMELINE_ROWS);
                }
                if ("context.patched".equals(stringValue(decoded, "type"))) {
                    latestContextDiff = summarizeContextDiff(decoded);
                }
                if ("metric.emitted".equals(stringValue(decoded, "type"))) {
                    metricCount++;
                    updateMetricAggregates(metricAggregates, decoded);
                }
                if ("audit.emitted".equals(stringValue(decoded, "type"))) {
                    auditCount++;
                    updateAuditAggregates(auditAggregates, decoded);
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        final List<Map<String, Object>> latestSignalList = List.copyOf(latestSignals);
        return new CreatorRunArtifactSnapshot(
                signalCount,
                latestSignalList,
                latestSignalList.isEmpty() ? "" : stringValue(latestSignalList.getLast(), "type"),
                List.copyOf(timeline),
                graphActivityAggregate.toUiModel(signalCount),
                latestContextDiff,
                summarizeMetrics(metricCount, metricAggregates),
                summarizeAudits(auditCount, auditAggregates),
                summaryReadResult.summary(),
                summaryReadResult.status(),
                summaryReadResult.error(),
                signalStreamStatus,
                signalReadError
        );
    }

    int signalCount() {
        return signalCount;
    }

    List<Map<String, Object>> latestSignals() {
        return latestSignals;
    }

    String lastSignalType() {
        return lastSignalType;
    }

    List<Map<String, Object>> timeline() {
        return timeline;
    }

    Map<String, Object> graphActivity() {
        return graphActivity;
    }

    Map<String, Object> contextDiff() {
        return contextDiff;
    }

    Map<String, Object> metrics() {
        return metrics;
    }

    Map<String, Object> audits() {
        return audits;
    }

    Map<String, Object> summary() {
        return summary;
    }

    String summaryReadStatus() {
        return summaryReadStatus;
    }

    String summaryReadError() {
        return summaryReadError;
    }

    String signalStreamStatus() {
        return signalStreamStatus;
    }

    String signalReadError() {
        return signalReadError;
    }

    String failureSummary() {
        final Map<String, Object> terminalFailure = objectValue(summary, "terminalFailure");
        return terminalFailure.isEmpty() ? "" : KernelContractCodec.toStableJson(terminalFailure);
    }

    private static void offer(
            final ArrayDeque<Map<String, Object>> deque,
            final Map<String, Object> value,
            final int maxSize
    ) {
        if (deque.size() == maxSize) {
            deque.removeFirst();
        }
        deque.addLast(value);
    }

    private static SummaryReadResult readSummary(final Path runFolder) {
        if (runFolder == null) {
            return new SummaryReadResult(Map.of(), "missing", "");
        }
        final Path summaryFile = runFolder.resolve("summary.json");
        if (!Files.isRegularFile(summaryFile)) {
            return new SummaryReadResult(Map.of(), "missing", "");
        }
        try {
            return new SummaryReadResult(
                    KernelContractCodec.parseStableJsonObject(Files.readString(summaryFile)),
                    "complete",
                    ""
            );
        } catch (final RuntimeException exception) {
            return new SummaryReadResult(
                    Map.of(),
                    "invalid",
                    summarizeSummary(summaryFile, exception)
            );
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> summarizeTimelineEvent(final Map<String, Object> decoded) {
        final String type = stringValue(decoded, "type");
        if (type.isBlank()) {
            return Map.of();
        }
        final LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", stringValue(decoded, "timestamp"));
        event.put("type", type);
        event.put("stepId", stringValue(decoded, "step"));
        event.put("attempt", numberValue(decoded, "attemptNumber"));
        switch (type) {
            case "run.started" -> event.put("summary", "Run started");
            case "step.started" -> event.put("summary", "Step started");
            case "context.patched" -> {
                final List<String> changedPaths = stringList(decoded.get("changedPaths"), MAX_CHANGED_PATHS);
                event.put("summary", "Context patched");
                event.put("changedPaths", changedPaths);
                event.put("changedPathCount", changedPaths.size());
                final Map<String, Object> patchSummary = objectValue(decoded, "patchSummary");
                event.put("patchCount", numberValue(patchSummary, "count"));
                event.put("patchOps", stringList(patchSummary.get("ops"), MAX_CHANGED_PATHS));
            }
            case "step.finished" -> {
                event.put("summary", "Step finished");
                event.put("outcome", stringValue(decoded, "outcome"));
                event.put("durationMs", numberValue(decoded, "durationMs"));
            }
            case "step.failed" -> {
                event.put("summary", "Step failed");
                event.put("error", stringValue(objectValue(decoded, "errorSummary"), "message"));
            }
            case "permission.decided" -> {
                event.put("summary", "Permission decided");
                event.put("decision", stringValue(decoded, "decision"));
                event.put("resource", stringValue(decoded, "resource"));
            }
            case "setting.read" -> {
                event.put("summary", "Setting read");
                event.put("path", stringValue(decoded, "path"));
            }
            case "metric.emitted" -> {
                event.put("summary", "Metric emitted");
                event.put("name", stringValue(decoded, "name"));
            }
            case "audit.emitted" -> {
                event.put("summary", "Audit emitted");
                event.put("eventName", stringValue(decoded, "eventName"));
            }
            case "resource.created" -> {
                event.put("summary", "Resource created");
                event.put("refType", stringValue(decoded, "refType"));
            }
            case "reply.produced" -> {
                event.put("summary", "Reply produced");
                event.put("replyMode", stringValue(decoded, "mode"));
                event.put("status", primitiveValue(decoded.get("status")));
            }
            case "run.finished" -> {
                event.put("summary", "Run finished");
                event.put("outcome", stringValue(decoded, "outcome"));
                event.put("durationMs", numberValue(decoded, "durationMs"));
            }
            default -> event.put("summary", type);
        }
        return Map.copyOf(event);
    }

    private static Map<String, Object> summarizeContextDiff(final Map<String, Object> decoded) {
        final Map<String, Object> patchSummary = objectValue(decoded, "patchSummary");
        final List<String> changedPaths = stringList(decoded.get("changedPaths"), MAX_CHANGED_PATHS);
        return orderedMap(
                "available", true,
                "timestamp", stringValue(decoded, "timestamp"),
                "stepId", stringValue(decoded, "step"),
                "attempt", numberValue(decoded, "attemptNumber"),
                "changedPathCount", changedPaths.size(),
                "changedPaths", changedPaths,
                "patchCount", numberValue(patchSummary, "count"),
                "patchOps", stringList(patchSummary.get("ops"), MAX_CHANGED_PATHS)
        );
    }

    private static Map<String, Object> emptyContextDiff() {
        return orderedMap(
                "available", false,
                "timestamp", "",
                "stepId", "",
                "attempt", 0,
                "changedPathCount", 0,
                "changedPaths", List.of(),
                "patchCount", 0,
                "patchOps", List.of()
        );
    }

    private static Map<String, Object> emptyGraphActivity() {
        return orderedMap(
                "available", false,
                "statusSummary", "No graph activity evidence discovered yet.",
                "nodes", List.of(
                        graphNode("trigger", "Run started", "pending", "Waiting for a real run.started signal."),
                        graphNode("step", "Step activity", "pending", "Waiting for a real step.started signal."),
                        graphNode("patch", "Context patched", "pending", "Waiting for a real context.patched signal."),
                        graphNode("reply", "Reply produced", "pending", "Waiting for a real reply.produced signal."),
                        graphNode("finish", "Run finished", "pending", "Waiting for a real run.finished signal.")
                )
        );
    }

    private static Map<String, Object> emptyMetrics() {
        return orderedMap(
                "count", 0,
                "series", List.of()
        );
    }

    private static Map<String, Object> emptyAudits() {
        return orderedMap(
                "count", 0,
                "events", List.of()
        );
    }

    private static void updateMetricAggregates(
            final LinkedHashMap<String, MetricAggregate> metricAggregates,
            final Map<String, Object> decoded
    ) {
        final String name = stringValue(decoded, "name");
        if (name.isBlank()) {
            return;
        }
        metricAggregates.compute(name, (ignored, aggregate) -> aggregate == null
                ? MetricAggregate.create(decoded)
                : aggregate.update(decoded));
    }

    private static void updateAuditAggregates(
            final LinkedHashMap<String, AuditAggregate> auditAggregates,
            final Map<String, Object> decoded
    ) {
        final String eventName = stringValue(decoded, "eventName");
        if (eventName.isBlank()) {
            return;
        }
        auditAggregates.compute(eventName, (ignored, aggregate) -> aggregate == null
                ? AuditAggregate.create(decoded)
                : aggregate.update(decoded));
    }

    private static Map<String, Object> summarizeMetrics(
            final int count,
            final LinkedHashMap<String, MetricAggregate> metricAggregates
    ) {
        if (count == 0) {
            return emptyMetrics();
        }
        return orderedMap(
                "count", count,
                "series", metricAggregates.values().stream()
                        .limit(MAX_METRIC_SERIES)
                        .map(MetricAggregate::toUiModel)
                        .toList()
        );
    }

    private static Map<String, Object> summarizeAudits(
            final int count,
            final LinkedHashMap<String, AuditAggregate> auditAggregates
    ) {
        if (count == 0) {
            return emptyAudits();
        }
        return orderedMap(
                "count", count,
                "events", auditAggregates.values().stream()
                        .limit(MAX_AUDIT_EVENTS)
                        .map(AuditAggregate::toUiModel)
                        .toList()
        );
    }

    private static Map<String, Object> graphNode(
            final String id,
            final String label,
            final String state,
            final String detail
    ) {
        return orderedMap(
                "id", id,
                "label", label,
                "state", state,
                "detail", detail
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return primitiveValue(value);
    }

    private static String primitiveValue(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "";
    }

    private static String previewValue(final Object value) {
        final String rendered = switch (valueKind(value)) {
            case "string", "number", "boolean", "null" -> primitivePreview(value);
            case "list" -> "list[size=" + listSize(value) + "]";
            case "object" -> "object[keys=" + objectSize(value) + "]";
            default -> valueKind(value);
        };
        if (rendered.length() <= MAX_VALUE_PREVIEW_CHARS) {
            return rendered;
        }
        return rendered.substring(0, MAX_VALUE_PREVIEW_CHARS) + "...";
    }

    private static String primitivePreview(final Object value) {
        if (value instanceof Map<?, ?> mapValue && mapValue.get("type") instanceof String) {
            final String encodedType = primitiveValue(mapValue.get("type"));
            if ("null".equals(encodedType)) {
                return "null";
            }
            return primitiveValue(mapValue.get("value"));
        }
        return primitiveValue(value);
    }

    private static String valueKind(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List<?>) {
            return "list";
        }
        if (value instanceof Map<?, ?> mapValue) {
            final String encodedType = primitiveValue(mapValue.get("type"));
            return encodedType.isBlank() ? "object" : encodedType;
        }
        return "unknown";
    }

    private static int objectSize(final Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            final Map<String, Object> encodedValues = objectValue((Map<String, Object>) mapValue, "values");
            return encodedValues.isEmpty() ? mapValue.size() : encodedValues.size();
        }
        return 0;
    }

    private static int listSize(final Object value) {
        if (value instanceof List<?> listValue) {
            return listValue.size();
        }
        if (value instanceof Map<?, ?> mapValue) {
            final Object encodedValues = mapValue.get("values");
            if (encodedValues instanceof List<?> listValue) {
                return listValue.size();
            }
        }
        return 0;
    }

    private static int numberValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(final Object value, final int limit) {
        if (!(value instanceof List<?> listValue) || limit <= 0) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (final Object entry : (List<Object>) listValue) {
            final String rendered = primitiveValue(entry);
            if (!rendered.isBlank()) {
                values.add(rendered);
            }
            if (values.size() == limit) {
                break;
            }
        }
        return List.copyOf(values);
    }

    private static String summarize(final int lineNumber, final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "signals.ndjson line " + lineNumber + " could not be decoded";
        }
        return "signals.ndjson line " + lineNumber + " could not be decoded: " + message;
    }

    private static String summarizeSummary(final Path summaryFile, final RuntimeException exception) {
        final String message = exception.getMessage();
        final String fileName = summaryFile.getFileName().toString();
        if (message == null || message.isBlank()) {
            return boundedError(fileName + " could not be decoded");
        }
        return boundedError(fileName + " could not be decoded: " + message);
    }

    private static String boundedError(final String value) {
        if (value.length() <= MAX_ARTIFACT_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ARTIFACT_ERROR_CHARS) + "...";
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private static final class GraphActivityAggregate {
        private boolean runStarted;
        private boolean stepStarted;
        private boolean stepFinished;
        private boolean stepFailed;
        private boolean contextPatched;
        private boolean replyProduced;
        private boolean runFinished;
        private String stepId = "";
        private String stepOutcome = "";
        private String replyMode = "";
        private String runOutcome = "";
        private int changedPathCount;

        private void accept(final Map<String, Object> decoded) {
            final String type = stringValue(decoded, "type");
            switch (type) {
                case "run.started" -> runStarted = true;
                case "step.started" -> {
                    stepStarted = true;
                    stepId = stringValue(decoded, "step");
                }
                case "context.patched" -> {
                    contextPatched = true;
                    stepId = stringValue(decoded, "step");
                    changedPathCount = stringList(decoded.get("changedPaths"), MAX_CHANGED_PATHS).size();
                }
                case "step.finished" -> {
                    stepFinished = true;
                    stepId = stringValue(decoded, "step");
                    stepOutcome = stringValue(decoded, "outcome");
                }
                case "step.failed" -> {
                    stepFailed = true;
                    stepId = stringValue(decoded, "step");
                }
                case "reply.produced" -> {
                    replyProduced = true;
                    replyMode = stringValue(decoded, "mode");
                }
                case "run.finished" -> {
                    runFinished = true;
                    runOutcome = stringValue(decoded, "outcome");
                }
                default -> {
                    // Ignore unrelated signals for graph activity.
                }
            }
        }

        private Map<String, Object> toUiModel(final int signalCount) {
            if (signalCount == 0) {
                return emptyGraphActivity();
            }
            final String finishState = runFinished
                    ? ("ok".equals(runOutcome) || runOutcome.isBlank() ? "complete" : "failed")
                    : (replyProduced || stepFinished || stepFailed ? "active" : "pending");
            final String stepState = stepFailed
                    ? "failed"
                    : (stepFinished ? "complete" : (stepStarted ? "active" : "pending"));
            final String patchState = contextPatched
                    ? "complete"
                    : (stepStarted ? "active" : "pending");
            final String replyState = replyProduced
                    ? "complete"
                    : ((stepFinished || stepFailed || contextPatched) ? "active" : "pending");
            return orderedMap(
                    "available", true,
                    "statusSummary", statusSummary(finishState, stepState, patchState, replyState),
                    "nodes", List.of(
                            graphNode("trigger", "Run started", runStarted ? "complete" : "pending",
                                    runStarted ? "Real run.started signal observed." : "Waiting for a real run.started signal."),
                            graphNode("step", "Step activity", stepState,
                                    stepId.isBlank()
                                            ? "Waiting for a real step.started signal."
                                            : (stepFailed ? "Step failed at " + stepId + "." : stepFinished ? "Step completed at " + stepId + outcomeSuffix() : "Step active at " + stepId + ".")),
                            graphNode("patch", "Context patched", patchState,
                                    contextPatched
                                            ? changedPathCount + " changed paths observed."
                                            : "Waiting for a real context.patched signal."),
                            graphNode("reply", "Reply produced", replyState,
                                    replyProduced
                                            ? "Reply mode " + (replyMode.isBlank() ? "unknown" : replyMode) + "."
                                            : "Waiting for a real reply.produced signal."),
                            graphNode("finish", "Run finished", finishState,
                                    runFinished
                                            ? "Run outcome " + (runOutcome.isBlank() ? "unknown" : runOutcome) + "."
                                            : "Waiting for a real run.finished signal.")
                    )
            );
        }

        private String statusSummary(final String finishState, final String stepState, final String patchState, final String replyState) {
            if ("failed".equals(finishState)) {
                return "Run finished with a non-ok outcome.";
            }
            if ("complete".equals(finishState)) {
                return "Run finished with real graph activity evidence.";
            }
            if ("complete".equals(replyState)) {
                return "Reply produced; waiting for terminal run completion.";
            }
            if ("complete".equals(patchState)) {
                return "Context patch evidence is active in the graph.";
            }
            if ("failed".equals(stepState)) {
                return "Step failure evidence is active in the graph.";
            }
            if ("active".equals(stepState)) {
                return "Live step activity is active in the graph.";
            }
            if (runStarted) {
                return "Run started; waiting for deeper graph activity.";
            }
            return "No graph activity evidence discovered yet.";
        }

        private String outcomeSuffix() {
            return stepOutcome.isBlank() ? "." : " with outcome " + stepOutcome + ".";
        }
    }

    private record SummaryReadResult(
            Map<String, Object> summary,
            String status,
            String error
    ) {}

    private record MetricAggregate(
            String name,
            int count,
            String unit,
            String valueKind,
            String latestValuePreview,
            int labelCount,
            List<String> labelKeys,
            String latestStepId,
            int latestAttempt,
            String latestTimestamp
    ) {
        private static MetricAggregate create(final Map<String, Object> decoded) {
            return new MetricAggregate(
                    stringValue(decoded, "name"),
                    1,
                    stringValue(decoded, "unit"),
                    CreatorRunArtifactSnapshot.valueKind(decoded.get("value")),
                    CreatorRunArtifactSnapshot.previewValue(decoded.get("value")),
                    objectValue(decoded, "labels").size(),
                    sortedKeys(objectValue(decoded, "labels"), MAX_LABEL_KEYS),
                    stringValue(decoded, "step"),
                    numberValue(decoded, "attemptNumber"),
                    stringValue(decoded, "timestamp")
            );
        }

        private MetricAggregate update(final Map<String, Object> decoded) {
            return new MetricAggregate(
                    name,
                    count + 1,
                    stringValue(decoded, "unit"),
                    CreatorRunArtifactSnapshot.valueKind(decoded.get("value")),
                    CreatorRunArtifactSnapshot.previewValue(decoded.get("value")),
                    objectValue(decoded, "labels").size(),
                    sortedKeys(objectValue(decoded, "labels"), MAX_LABEL_KEYS),
                    stringValue(decoded, "step"),
                    numberValue(decoded, "attemptNumber"),
                    stringValue(decoded, "timestamp")
            );
        }

        private Map<String, Object> toUiModel() {
            return orderedMap(
                "name", name,
                "count", count,
                "unit", unit,
                "valueKind", valueKind,
                "latestValuePreview", latestValuePreview,
                "labelCount", labelCount,
                "labelKeys", labelKeys,
                "latestStepId", latestStepId,
                "latestAttempt", latestAttempt,
                "latestTimestamp", latestTimestamp
            );
        }
    }

    private record AuditAggregate(
            String eventName,
            int count,
            String latestDataPreview,
            int topLevelKeyCount,
            List<String> dataKeys,
            String latestStepId,
            int latestAttempt,
            String latestTimestamp
    ) {
        private static AuditAggregate create(final Map<String, Object> decoded) {
            final Map<String, Object> data = objectValue(decoded, "data");
            return new AuditAggregate(
                    stringValue(decoded, "eventName"),
                    1,
                    previewValue(data),
                    CreatorRunArtifactSnapshot.topLevelKeyCount(data),
                    topLevelKeys(data),
                    stringValue(decoded, "step"),
                    numberValue(decoded, "attemptNumber"),
                    stringValue(decoded, "timestamp")
            );
        }

        private AuditAggregate update(final Map<String, Object> decoded) {
            final Map<String, Object> data = objectValue(decoded, "data");
            return new AuditAggregate(
                    eventName,
                    count + 1,
                    previewValue(data),
                    CreatorRunArtifactSnapshot.topLevelKeyCount(data),
                    topLevelKeys(data),
                    stringValue(decoded, "step"),
                    numberValue(decoded, "attemptNumber"),
                    stringValue(decoded, "timestamp")
            );
        }

        private Map<String, Object> toUiModel() {
            return orderedMap(
                "eventName", eventName,
                "count", count,
                "latestDataPreview", latestDataPreview,
                "topLevelKeyCount", topLevelKeyCount,
                "dataKeys", dataKeys,
                "latestStepId", latestStepId,
                "latestAttempt", latestAttempt,
                "latestTimestamp", latestTimestamp
            );
        }
    }

    private static List<String> sortedKeys(final Map<String, Object> values, final int limit) {
        return values.keySet().stream().sorted().limit(limit).toList();
    }

    private static int topLevelKeyCount(final Map<String, Object> encodedValue) {
        final Map<String, Object> nestedValues = objectValue(encodedValue, "values");
        return nestedValues.isEmpty() ? encodedValue.size() : nestedValues.size();
    }

    private static List<String> topLevelKeys(final Map<String, Object> encodedValue) {
        final Map<String, Object> nestedValues = objectValue(encodedValue, "values");
        return sortedKeys(nestedValues.isEmpty() ? encodedValue : nestedValues, MAX_AUDIT_KEYS);
    }
}
