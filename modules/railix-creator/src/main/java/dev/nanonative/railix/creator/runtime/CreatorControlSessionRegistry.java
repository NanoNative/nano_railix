package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.runtime.AppPlan;
import dev.nanonative.railix.kernel.runtime.BuiltRailixAppLoader;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns bounded Creator-managed live run sessions over the public built-app launcher boundary.
 */
public final class CreatorControlSessionRegistry implements AutoCloseable {

    static final int MAX_ACTIVE_SESSIONS = 4;
    static final int MAX_TRACKED_SESSIONS = 12;
    private static final long ACCEPTED_DWELL_NANOS = java.time.Duration.ofMillis(75).toNanos();
    private final ExecutorService executor;
    private final java.nio.file.Path runsRoot;
    private final LinkedHashMap<String, TrackedSession> sessions;
    private Runnable stateChangeListener;
    private boolean closed;

    public CreatorControlSessionRegistry(final java.nio.file.Path runsRoot) {
        this(Executors.newVirtualThreadPerTaskExecutor(), runsRoot);
    }

    CreatorControlSessionRegistry(final ExecutorService executor, final java.nio.file.Path runsRoot) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        this.sessions = new LinkedHashMap<>();
        this.stateChangeListener = () -> {};
    }

    public synchronized Map<String, Object> snapshot() {
        return controlSnapshot(new ArrayList<>(sessions.values()));
    }

    public synchronized void setStateChangeListener(final Runnable stateChangeListener) {
        this.stateChangeListener = Objects.requireNonNull(stateChangeListener, "stateChangeListener");
    }

    public Map<String, Object> start(final String requestBody) {
        Objects.requireNonNull(requestBody, "requestBody");
        final CreatorRunLauncher.RunLaunchRequest decoded = CreatorRunLauncher.decodeRequest(requestBody);
        final CreatorRunLauncher.RunLaunchRequest request = decoded.runId().isEmpty()
                ? decoded.withRunId("run-creator-session-" + UUID.randomUUID())
                : decoded;
        final TrackedSession tracked = createTrackedSession(request, loadPlanIdentity(request.planLocation(), request.runId()));
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final Future<?> future;
        final Map<String, Object> uiModel;
        synchronized (this) {
            ensureOpen();
            if (activeSessionCount(new ArrayList<>(sessions.values())) >= MAX_ACTIVE_SESSIONS) {
                throw new IllegalStateException("Too many active Creator control sessions. Wait for a running session to finish.");
            }
            sessions.put(tracked.sessionId, tracked);
            future = executor.submit(() -> runSession(tracked.sessionId, request, contextClassLoader));
            tracked.future = future;
            trimCompletedSessions();
            uiModel = tracked.toUiModel();
        }
        notifyStateChanged();
        return uiModel;
    }

    @Override
    public void close() {
        final List<Future<?>> activeFutures = new ArrayList<>();
        synchronized (this) {
            closed = true;
            for (final TrackedSession session : sessions.values()) {
                if (session.future != null) {
                    activeFutures.add(session.future);
                }
            }
        }
        for (final Future<?> future : activeFutures) {
            future.cancel(true);
        }
        executor.close();
    }

    private void runSession(
            final String sessionId,
            final CreatorRunLauncher.RunLaunchRequest request,
            final ClassLoader contextClassLoader
    ) {
        final Thread thread = Thread.currentThread();
        final ClassLoader previousClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(contextClassLoader);
        try {
            awaitAcceptedPhase();
            markRunning(sessionId);
            final Map<String, Object> launch = CreatorRunLauncher.launch(request, runsRoot);
            markCompleted(sessionId, launch);
        } finally {
            thread.setContextClassLoader(previousClassLoader);
        }
    }

    private static void awaitAcceptedPhase() {
        LockSupport.parkNanos(ACCEPTED_DWELL_NANOS);
    }

    private void markRunning(final String sessionId) {
        synchronized (this) {
            final TrackedSession tracked = requireTrackedSession(sessionId);
            tracked.state = "running";
            tracked.startedAt = Instant.now();
        }
        notifyStateChanged();
    }

    private void markCompleted(final String sessionId, final Map<String, Object> launch) {
        synchronized (this) {
            final TrackedSession tracked = requireTrackedSession(sessionId);
            tracked.state = booleanValue(launch, "succeeded") ? "succeeded" : "failed";
            tracked.completedAt = Instant.now();
            tracked.exitCode = integerValue(launch, "exitCode");
            tracked.succeeded = booleanValue(launch, "succeeded");
            tracked.message = stringValue(launch, "message");
            tracked.outcome = stringValue(launch, "outcome");
            tracked.runFolder = stringValue(launch, "runFolder");
            tracked.stdoutPreview = stringValue(launch, "stdout");
            tracked.stderrPreview = stringValue(launch, "stderr");
            tracked.stdoutSize = integerValue(launch, "stdoutSize");
            tracked.stderrSize = integerValue(launch, "stderrSize");
            tracked.stdoutTruncated = booleanValue(launch, "stdoutTruncated");
            tracked.stderrTruncated = booleanValue(launch, "stderrTruncated");
            trimCompletedSessions();
        }
        notifyStateChanged();
    }

    private TrackedSession createTrackedSession(
            final CreatorRunLauncher.RunLaunchRequest request,
            final PlanIdentity planIdentity
    ) {
        final Instant now = Instant.now();
        final String predictedRunFolder = planIdentity.runFolder().isBlank()
                ? ""
                : runsRoot.resolve(planIdentity.runFolder()).toString();
        final Path predictedSignalsFile = predictedRunFolder.isBlank()
                ? Path.of("")
                : Path.of(predictedRunFolder).resolve("signals.ndjson");
        return new TrackedSession(
                "creator-session-" + UUID.randomUUID(),
                planIdentity.appId(),
                planIdentity.flowId(),
                request.planLocation(),
                request.envelopeLocation(),
                request.settingsLocation(),
                request.profileName(),
                request.runId(),
                predictedRunFolder,
                predictedSignalsFile,
                now
        );
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Creator control sessions are no longer available after shutdown");
        }
    }

    private void notifyStateChanged() {
        final Runnable listener;
        synchronized (this) {
            listener = stateChangeListener;
        }
        listener.run();
    }

    private TrackedSession requireTrackedSession(final String sessionId) {
        final TrackedSession tracked = sessions.get(sessionId);
        if (tracked == null) {
            throw new IllegalStateException("Unknown Creator control session: " + sessionId);
        }
        return tracked;
    }

    private void trimCompletedSessions() {
        while (sessions.size() > MAX_TRACKED_SESSIONS) {
            final String evictableKey = sessions.entrySet().stream()
                    .filter(entry -> !entry.getValue().isActive())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (evictableKey == null) {
                break;
            }
            sessions.remove(evictableKey);
        }
    }

    private static int activeSessionCount(final List<TrackedSession> trackedSessions) {
        return Math.toIntExact(trackedSessions.stream().filter(TrackedSession::isActive).count());
    }

    private static Map<String, Object> controlSnapshot(final List<TrackedSession> trackedSessions) {
        trackedSessions.sort(Comparator.comparing(TrackedSession::submittedAt).reversed());
        return orderedMap(
                "title", "Creator Control Sessions",
                "ready", true,
                "status", "creator-owned-live-run-sessions",
                "cancelSupported", false,
                "maxActiveSessions", MAX_ACTIVE_SESSIONS,
                "maxTrackedSessions", MAX_TRACKED_SESSIONS,
                "activeCount", activeSessionCount(trackedSessions),
                "trackedCount", trackedSessions.size(),
                "sessions", trackedSessions.stream().map(TrackedSession::toUiModel).toList()
        );
    }

    private static PlanIdentity loadPlanIdentity(final String planLocation, final String runId) {
        try {
            final AppPlan plan = BuiltRailixAppLoader.loadPlan(planLocation);
            if (plan.appId().isBlank() || plan.flowId().isBlank()) {
                return new PlanIdentity("", "", "");
            }
            return new PlanIdentity(
                    plan.appId(),
                    plan.flowId(),
                    runsRelativeFolder(plan.appId(), plan.flowId(), runId).toString()
            );
        } catch (final RuntimeException exception) {
            return new PlanIdentity("", "", "");
        }
    }

    private static Path runsRelativeFolder(final String appId, final String flowId, final String runId) {
        return Path.of(appId).resolve(flowId).resolve(runId);
    }

    private static boolean booleanValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private static int integerValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof Number numberValue ? numberValue.intValue() : -1;
    }

    private static String stringValue(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        return value instanceof String stringValue ? stringValue : "";
    }

    private static Map<String, Object> orderedMap(final Object... keyValues) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            values.put((String) keyValues[index], keyValues[index + 1]);
        }
        return values;
    }

    private static final class TrackedSession {
        private final String sessionId;
        private final String appId;
        private final String flowId;
        private final String planLocation;
        private final String envelopeLocation;
        private final String settingsLocation;
        private final String profileName;
        private final String runId;
        private final Path signalsFile;
        private final Instant submittedAt;
        private String state;
        private Instant startedAt;
        private Instant completedAt;
        private int exitCode;
        private boolean succeeded;
        private String message;
        private String outcome;
        private String runFolder;
        private String stdoutPreview;
        private String stderrPreview;
        private int stdoutSize;
        private int stderrSize;
        private boolean stdoutTruncated;
        private boolean stderrTruncated;
        private String failureSummary;
        private Future<?> future;

        private TrackedSession(
                final String sessionId,
                final String appId,
                final String flowId,
                final String planLocation,
                final String envelopeLocation,
                final String settingsLocation,
                final String profileName,
                final String runId,
                final String runFolder,
                final Path signalsFile,
                final Instant submittedAt
        ) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.appId = Objects.requireNonNull(appId, "appId");
            this.flowId = Objects.requireNonNull(flowId, "flowId");
            this.planLocation = Objects.requireNonNull(planLocation, "planLocation");
            this.envelopeLocation = Objects.requireNonNull(envelopeLocation, "envelopeLocation");
            this.settingsLocation = Objects.requireNonNull(settingsLocation, "settingsLocation");
            this.profileName = Objects.requireNonNull(profileName, "profileName");
            this.runId = Objects.requireNonNull(runId, "runId");
            this.signalsFile = Objects.requireNonNull(signalsFile, "signalsFile");
            this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
            this.state = "accepted";
            this.startedAt = null;
            this.completedAt = null;
            this.exitCode = -1;
            this.succeeded = false;
            this.message = "";
            this.outcome = "";
            this.runFolder = Objects.requireNonNull(runFolder, "runFolder");
            this.stdoutPreview = "";
            this.stderrPreview = "";
            this.stdoutSize = 0;
            this.stderrSize = 0;
            this.stdoutTruncated = false;
            this.stderrTruncated = false;
            this.failureSummary = "";
        }

        private Instant submittedAt() {
            return submittedAt;
        }

        private boolean isActive() {
            return "accepted".equals(state) || "running".equals(state);
        }

        private Map<String, Object> toUiModel() {
            final CreatorRunArtifactSnapshot artifactSnapshot = CreatorRunArtifactSnapshot.capture(runFolderPath(), signalsFile);
            final Map<String, Object> summary = artifactSnapshot.summary();
            return orderedMap(
                    "sessionId", sessionId,
                    "appId", appId,
                    "flowId", flowId,
                    "state", state,
                    "active", isActive(),
                    "submittedAt", submittedAt.toString(),
                    "startedAt", startedAt == null ? "" : startedAt.toString(),
                    "completedAt", completedAt == null ? "" : completedAt.toString(),
                    "planLocation", planLocation,
                    "envelopeLocation", envelopeLocation,
                    "settingsLocation", settingsLocation,
                    "profileName", profileName,
                    "runId", runId,
                    "exitCode", exitCode,
                    "succeeded", succeeded,
                    "message", message,
                    "outcome", outcome,
                    "runFolder", runFolder,
                    "stdoutPreview", stdoutPreview,
                    "stderrPreview", stderrPreview,
                    "stdoutSize", stdoutSize,
                    "stderrSize", stderrSize,
                    "stdoutTruncated", stdoutTruncated,
                    "stderrTruncated", stderrTruncated,
                    "failureSummary", artifactSnapshot.failureSummary(),
                    "signalCount", artifactSnapshot.signalCount(),
                    "latestSignals", artifactSnapshot.latestSignals(),
                    "lastSignalType", artifactSnapshot.lastSignalType(),
                    "timeline", artifactSnapshot.timeline(),
                    "graphActivity", artifactSnapshot.graphActivity(),
                    "contextDiff", artifactSnapshot.contextDiff(),
                    "metrics", artifactSnapshot.metrics(),
                    "audits", artifactSnapshot.audits(),
                    "summary", summary,
                    "terminalFailure", summary.getOrDefault("terminalFailure", Map.of()),
                    "summaryReadStatus", artifactSnapshot.summaryReadStatus(),
                    "summaryReadError", artifactSnapshot.summaryReadError(),
                    "signalStreamStatus", artifactSnapshot.signalStreamStatus(),
                    "signalReadError", artifactSnapshot.signalReadError(),
                    "cancelSupported", false
            );
        }

        private Path runFolderPath() {
            if (!runFolder.isBlank()) {
                return Path.of(runFolder);
            }
            return signalsFile.getParent();
        }
    }

    private record PlanIdentity(String appId, String flowId, String runFolder) {
        private PlanIdentity {
            appId = Objects.requireNonNull(appId, "appId");
            flowId = Objects.requireNonNull(flowId, "flowId");
            runFolder = Objects.requireNonNull(runFolder, "runFolder");
        }
    }
}
