package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persists and queries bounded remote-execution request history for the Creator shell.
 */
public final class CreatorRemoteExecutionRequestStore {

    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final Comparator<RequestRecord> NEWEST_FIRST = Comparator
            .comparing(RequestRecord::updatedAt, Comparator.reverseOrder())
            .thenComparing(RequestRecord::executionId)
            .thenComparing(RequestRecord::requestStatus);

    private CreatorRemoteExecutionRequestStore() {}

    public static void record(
            final Path runsRoot,
            final String requestBody,
            final int responseStatus,
            final Map<String, Object> responseBody,
            final Instant receivedAt,
            final Instant completedAt
    ) {
        final Path normalizedRunsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        final String nonNullRequestBody = Objects.requireNonNull(requestBody, "requestBody");
        final Map<String, Object> nonNullResponseBody = Map.copyOf(Objects.requireNonNull(responseBody, "responseBody"));
        final Instant nonNullReceivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        final Instant nonNullCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        final Path targetFile = newRecordPath(normalizedRunsRoot, nonNullRequestBody, nonNullResponseBody, nonNullCompletedAt);
        writeRecord(targetFile, persistedModel(
                nonNullRequestBody,
                responseStatus,
                nonNullResponseBody,
                nonNullReceivedAt,
                nonNullCompletedAt,
                nonNullCompletedAt,
                ""
        ));
    }

    public static Path begin(
            final Path runsRoot,
            final String requestBody,
            final int responseStatus,
            final Map<String, Object> responseBody,
            final Instant receivedAt
    ) {
        final Path normalizedRunsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        final String nonNullRequestBody = Objects.requireNonNull(requestBody, "requestBody");
        final Map<String, Object> nonNullResponseBody = Map.copyOf(Objects.requireNonNull(responseBody, "responseBody"));
        final Instant nonNullReceivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        final Path targetFile = newRecordPath(normalizedRunsRoot, nonNullRequestBody, nonNullResponseBody, nonNullReceivedAt);
        writeRecord(targetFile, persistedModel(
                nonNullRequestBody,
                responseStatus,
                nonNullResponseBody,
                nonNullReceivedAt,
                nonNullReceivedAt,
                null,
                ""
        ));
        return targetFile;
    }

    public static void complete(
            final Path recordPath,
            final String requestBody,
            final int responseStatus,
            final Map<String, Object> responseBody,
            final Instant receivedAt,
            final Instant completedAt
    ) {
        writeRecord(
                Objects.requireNonNull(recordPath, "recordPath"),
                persistedModel(
                        Objects.requireNonNull(requestBody, "requestBody"),
                        responseStatus,
                        Map.copyOf(Objects.requireNonNull(responseBody, "responseBody")),
                        Objects.requireNonNull(receivedAt, "receivedAt"),
                        Objects.requireNonNull(completedAt, "completedAt"),
                        completedAt,
                        ""
                )
        );
    }

    public static Map<String, Object> capture(final Path runsRoot, final int limit) {
        final Path normalizedRunsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        final int normalizedLimit = requireLimit(limit);
        try {
            final List<RequestRecord> records = readRecords(normalizedRunsRoot);
            final List<RequestRecord> page = records.stream().limit(normalizedLimit).toList();
            final long acceptedCount = records.stream().filter(record -> "accepted".equals(record.decision())).count();
            final long rejectedCount = records.stream().filter(record -> "rejected".equals(record.decision())).count();
            final long unsupportedCount = records.stream().filter(record -> "unsupported".equals(record.decision())).count();
            final long queuedCount = records.stream().filter(record -> "queued".equals(record.requestStatus())).count();
            final long executedCount = records.stream().filter(record -> "executed".equals(record.requestStatus())).count();
            final long failedCount = records.stream().filter(record -> "failed".equals(record.requestStatus())).count();
            final long interruptedCount = records.stream().filter(record -> "interrupted".equals(record.requestStatus())).count();
            return orderedMap(
                    "title", "Remote Execution Requests",
                    "source", "artifact-backed-remote-execution-requests",
                    "error", "",
                    "limit", normalizedLimit,
                    "totalCount", records.size(),
                    "acceptedCount", acceptedCount,
                    "rejectedCount", rejectedCount,
                    "unsupportedCount", unsupportedCount,
                    "queuedCount", queuedCount,
                    "executedCount", executedCount,
                    "failedCount", failedCount,
                    "interruptedCount", interruptedCount,
                    "requests", page.stream().map(RequestRecord::toUiModel).toList()
            );
        } catch (final RuntimeException exception) {
            return orderedMap(
                    "title", "Remote Execution Requests",
                    "source", "artifact-backed-remote-execution-requests",
                    "error", summarize(exception),
                    "limit", normalizedLimit,
                    "totalCount", 0,
                    "acceptedCount", 0,
                    "rejectedCount", 0,
                    "unsupportedCount", 0,
                    "queuedCount", 0,
                    "executedCount", 0,
                    "failedCount", 0,
                    "interruptedCount", 0,
                    "requests", List.of()
            );
        }
    }

    private static List<RequestRecord> readRecords(final Path runsRoot) {
        final Path storeRoot = storeRoot(runsRoot);
        if (!Files.isDirectory(storeRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(storeRoot)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .map(CreatorRemoteExecutionRequestStore::readRecord)
                    .sorted(NEWEST_FIRST)
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RequestRecord readRecord(final Path path) {
        try {
            final Map<String, Object> model = KernelContractCodec.parseStableJsonObject(Files.readString(path));
            return new RequestRecord(
                    stringValue(model, "executionId"),
                    stringValue(model, "requestId"),
                    stringValue(model, "requestStatus"),
                    stringValue(model, "decision"),
                    booleanValue(model, "accepted"),
                    numberValue(model, "responseStatus"),
                    stringValue(model, "rejectionCode"),
                    stringValue(model, "message"),
                    stringValue(model, "stepUse"),
                    stringValue(model, "replyMode"),
                    numberValue(model, "resourceRefCount"),
                    stringValue(model, "requesterInstanceId"),
                    stringValue(model, "targetInstanceId"),
                    stringValue(model, "appId"),
                    stringValue(model, "flowId"),
                    stringValue(model, "runId"),
                    stringValue(model, "runOutcome"),
                    stringValue(model, "runFolder"),
                    numberValue(model, "signalCount"),
                    Instant.parse(stringValue(model, "receivedAt")),
                    Instant.parse(stringValue(model, "updatedAt")),
                    stringValue(model, "completedAt"),
                    numberValue(model, "durationMs")
            );
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String requestStatus(final Map<String, Object> responseBody, final Map<String, Object> resultModel) {
        final String decision = stringValue(responseBody, "decision");
        if ("accepted".equals(decision)) {
            final String resultStatus = stringValue(resultModel, "status");
            return resultStatus.isBlank() ? "accepted" : resultStatus.toLowerCase(Locale.ROOT);
        }
        if ("rejected".equals(decision) || "unsupported".equals(decision)) {
            return decision;
        }
        return "failed";
    }

    private static Path newRecordPath(
            final Path runsRoot,
            final String requestBody,
            final Map<String, Object> responseBody,
            final Instant timestamp
    ) {
        final Map<String, Object> requestModel = parseRequestModel(requestBody);
        final String requestId = firstNonBlank(
                stringValue(requestModel, "requestId"),
                stringValue(responseBody, "requestId"),
                "anonymous-request"
        );
        final Path storeRoot = storeRoot(runsRoot);
        try {
            Files.createDirectories(storeRoot);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
        final String fileName = timestamp.toEpochMilli()
                + "-" + sanitizeFileSegment(requestId)
                + "-" + UUID.randomUUID()
                + ".json";
        return storeRoot.resolve(fileName);
    }

    private static Map<String, Object> persistedModel(
            final String requestBody,
            final int responseStatus,
            final Map<String, Object> responseBody,
            final Instant receivedAt,
            final Instant updatedAt,
            final Instant completedAt,
            final String startedAt
    ) {
        final Map<String, Object> requestModel = parseRequestModel(requestBody);
        final Map<String, Object> resultModel = mapValue(responseBody, "result");
        final String requestId = firstNonBlank(
                stringValue(requestModel, "requestId"),
                stringValue(responseBody, "requestId"),
                "anonymous-request"
        );
        final String executionId = firstNonBlank(
                stringValue(responseBody, "executionId"),
                stringValue(requestModel, "executionId"),
                "remote-execution-" + UUID.randomUUID()
        );
        return orderedMap(
                "executionId", executionId,
                "requestId", requestId,
                "requestStatus", requestStatus(responseBody, resultModel),
                "decision", stringValue(responseBody, "decision"),
                "accepted", booleanValue(responseBody, "accepted"),
                "responseStatus", responseStatus,
                "rejectionCode", stringValue(responseBody, "rejectionCode"),
                "message", truncate(stringValue(responseBody, "message"), MAX_MESSAGE_CHARS),
                "stepUse", stringValue(requestModel, "stepUse"),
                "replyMode", stringValue(requestModel, "replyMode"),
                "resourceRefCount", listValue(requestModel, "resourceRefs").size(),
                "requesterInstanceId", firstNonBlank(
                        stringValue(requestModel, "requesterInstanceId"),
                        stringValue(responseBody, "requesterInstanceId"),
                        ""
                ),
                "targetInstanceId", firstNonBlank(
                        stringValue(requestModel, "targetInstanceId"),
                        stringValue(responseBody, "targetInstanceId"),
                        ""
                ),
                "appId", stringValue(resultModel, "appId"),
                "flowId", stringValue(resultModel, "flowId"),
                "runId", stringValue(resultModel, "runId"),
                "runOutcome", stringValue(resultModel, "outcome"),
                "runFolder", stringValue(resultModel, "runFolder"),
                "signalCount", numberValue(resultModel, "signalCount"),
                "receivedAt", receivedAt.toString(),
                "updatedAt", updatedAt.toString(),
                "startedAt", startedAt,
                "completedAt", completedAt == null ? "" : completedAt.toString(),
                "durationMs", Duration.between(receivedAt, updatedAt).toMillis()
        );
    }

    private static void writeRecord(final Path targetFile, final Map<String, Object> persisted) {
        try {
            final String fileName = targetFile.getFileName().toString();
            final Path tempFile = targetFile.resolveSibling(fileName + ".tmp");
            Files.writeString(tempFile, KernelContractCodec.toStableJson(persisted));
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> parseRequestModel(final String requestBody) {
        try {
            return KernelContractCodec.parseStableJsonObject(requestBody);
        } catch (final RuntimeException exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listValue(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        if (value instanceof List<?> listValue) {
            return (List<Object>) listValue;
        }
        return List.of();
    }

    private static int numberValue(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        return value instanceof Number numberValue ? numberValue.intValue() : 0;
    }

    private static boolean booleanValue(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private static String stringValue(final Map<String, Object> source, final String key) {
        final Object value = source.get(key);
        return value instanceof String stringValue ? stringValue : "";
    }

    private static String truncate(final String value, final int maxChars) {
        final String nonNullValue = Objects.requireNonNullElse(value, "");
        if (nonNullValue.length() <= maxChars) {
            return nonNullValue;
        }
        return nonNullValue.substring(0, maxChars) + "...";
    }

    private static String firstNonBlank(final String first, final String second, final String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return Objects.requireNonNull(fallback, "fallback");
    }

    private static int requireLimit(final int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be an integer between 1 and 50");
        }
        return limit;
    }

    private static Path storeRoot(final Path runsRoot) {
        return runsRoot.resolve(".creator").resolve("remote-execution-requests");
    }

    private static String sanitizeFileSegment(final String value) {
        final String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return normalized.isBlank() ? "request" : normalized;
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private static LinkedHashMap<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private record RequestRecord(
            String executionId,
            String requestId,
            String requestStatus,
            String decision,
            boolean accepted,
            int responseStatus,
            String rejectionCode,
            String message,
            String stepUse,
            String replyMode,
            int resourceRefCount,
            String requesterInstanceId,
            String targetInstanceId,
            String appId,
            String flowId,
            String runId,
            String runOutcome,
            String runFolder,
            int signalCount,
            Instant receivedAt,
            Instant updatedAt,
            String completedAt,
            int durationMs
    ) {
        private RequestRecord {
            executionId = Objects.requireNonNull(executionId, "executionId");
            requestId = Objects.requireNonNull(requestId, "requestId");
            requestStatus = Objects.requireNonNull(requestStatus, "requestStatus");
            decision = Objects.requireNonNull(decision, "decision");
            rejectionCode = Objects.requireNonNull(rejectionCode, "rejectionCode");
            message = Objects.requireNonNull(message, "message");
            stepUse = Objects.requireNonNull(stepUse, "stepUse");
            replyMode = Objects.requireNonNull(replyMode, "replyMode");
            requesterInstanceId = Objects.requireNonNull(requesterInstanceId, "requesterInstanceId");
            targetInstanceId = Objects.requireNonNull(targetInstanceId, "targetInstanceId");
            appId = Objects.requireNonNull(appId, "appId");
            flowId = Objects.requireNonNull(flowId, "flowId");
            runId = Objects.requireNonNull(runId, "runId");
            runOutcome = Objects.requireNonNull(runOutcome, "runOutcome");
            runFolder = Objects.requireNonNull(runFolder, "runFolder");
            receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }

        private Map<String, Object> toUiModel() {
            return orderedMap(
                    "executionId", executionId,
                    "requestId", requestId,
                    "requestStatus", requestStatus,
                    "decision", decision,
                    "accepted", accepted,
                    "responseStatus", responseStatus,
                    "rejectionCode", rejectionCode,
                    "message", message,
                    "stepUse", stepUse,
                    "replyMode", replyMode,
                    "resourceRefCount", resourceRefCount,
                    "requesterInstanceId", requesterInstanceId,
                    "targetInstanceId", targetInstanceId,
                    "appId", appId,
                    "flowId", flowId,
                    "runId", runId,
                    "runOutcome", runOutcome,
                    "runFolder", runFolder,
                    "signalCount", signalCount,
                    "receivedAt", receivedAt.toString(),
                    "updatedAt", updatedAt.toString(),
                    "completedAt", completedAt,
                    "durationMs", durationMs
            );
        }
    }
}
