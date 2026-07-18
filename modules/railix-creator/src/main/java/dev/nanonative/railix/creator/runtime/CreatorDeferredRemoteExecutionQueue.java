package dev.nanonative.railix.creator.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns deferred same-instance remote-execution requests and persists their lifecycle.
 */
public final class CreatorDeferredRemoteExecutionQueue implements AutoCloseable {

    static final int MAX_ACTIVE_REQUESTS = 4;
    private static final long QUEUED_DWELL_NANOS = Duration.ofMillis(125).toNanos();

    private final ExecutorService executor;
    private final Path runsRoot;
    private final LinkedHashMap<String, TrackedExecution> executions;
    private Runnable stateChangeListener;
    private boolean closed;

    public CreatorDeferredRemoteExecutionQueue(final Path runsRoot) {
        this(Executors.newVirtualThreadPerTaskExecutor(), runsRoot);
    }

    CreatorDeferredRemoteExecutionQueue(final ExecutorService executor, final Path runsRoot) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runsRoot = Objects.requireNonNull(runsRoot, "runsRoot").toAbsolutePath().normalize();
        this.executions = new LinkedHashMap<>();
        this.stateChangeListener = () -> {};
    }

    public synchronized void setStateChangeListener(final Runnable stateChangeListener) {
        this.stateChangeListener = Objects.requireNonNull(stateChangeListener, "stateChangeListener");
    }

    public synchronized Map<String, Object> snapshot() {
        return orderedMap(
                "ready", true,
                "activeCount", activeCount(),
                "maxActiveRequests", MAX_ACTIVE_REQUESTS
        );
    }

    public synchronized boolean canAccept() {
        return !closed && activeCount() < MAX_ACTIVE_REQUESTS;
    }

    public Map<String, Object> submit(
            final String requestBody,
            final CreatorRemoteExecutionBoundary.BoundaryResponse response,
            final Instant receivedAt
    ) {
        Objects.requireNonNull(requestBody, "requestBody");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (response.deferredExecution() == null) {
            throw new IllegalArgumentException("Deferred execution response is required");
        }
        final TrackedExecution tracked;
        synchronized (this) {
            ensureOpen();
            ensureCapacity();
            final String executionId = response.deferredExecution().executionId();
            final Path recordPath = CreatorRemoteExecutionRequestStore.begin(
                    runsRoot,
                    requestBody,
                    response.status(),
                    response.body(),
                    receivedAt
            );
            tracked = new TrackedExecution(
                    executionId,
                    recordPath,
                    requestBody,
                    response.status(),
                    receivedAt,
                    response.deferredExecution()
            );
            executions.put(executionId, tracked);
            tracked.future = executor.submit(() -> runTrackedExecution(tracked));
        }
        notifyStateChanged();
        return response.body();
    }

    @Override
    public void close() {
        final Map<String, TrackedExecution> active;
        synchronized (this) {
            closed = true;
            active = Map.copyOf(executions);
        }
        for (final TrackedExecution tracked : active.values()) {
            if (!tracked.completed && tracked.future != null) {
                CreatorRemoteExecutionRequestStore.complete(
                        tracked.recordPath,
                        tracked.requestBody,
                        tracked.responseStatus,
                        tracked.deferredExecution.interrupted().apply(
                                "Creator shell shut down before deferred remote execution completed."
                        ),
                        tracked.receivedAt,
                        Instant.now()
                );
                tracked.future.cancel(true);
            }
        }
        executor.close();
    }

    private void runTrackedExecution(final TrackedExecution tracked) {
        try {
            LockSupport.parkNanos(QUEUED_DWELL_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            final Map<String, Object> completedBody = tracked.deferredExecution.complete().get();
            CreatorRemoteExecutionRequestStore.complete(
                    tracked.recordPath,
                    tracked.requestBody,
                    tracked.responseStatus,
                    completedBody,
                    tracked.receivedAt,
                    Instant.now()
            );
            synchronized (this) {
                tracked.completed = true;
                executions.remove(tracked.executionId);
            }
            notifyStateChanged();
        } catch (final RuntimeException exception) {
            CreatorRemoteExecutionRequestStore.complete(
                    tracked.recordPath,
                    tracked.requestBody,
                    tracked.responseStatus,
                    tracked.deferredExecution.interrupted().apply(
                            "Deferred remote execution failed before completion: " + summarize(exception)
                    ),
                    tracked.receivedAt,
                    Instant.now()
            );
            synchronized (this) {
                tracked.completed = true;
                executions.remove(tracked.executionId);
            }
            notifyStateChanged();
        }
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Deferred remote execution queue is no longer available after shutdown");
        }
    }

    private synchronized void ensureCapacity() {
        if (activeCount() >= MAX_ACTIVE_REQUESTS) {
            throw new IllegalStateException("Deferred remote execution queue is currently at capacity");
        }
    }

    private synchronized int activeCount() {
        return executions.size();
    }

    private void notifyStateChanged() {
        final Runnable listener;
        synchronized (this) {
            listener = stateChangeListener;
        }
        listener.run();
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

    private static final class TrackedExecution {
        private final String executionId;
        private final Path recordPath;
        private final String requestBody;
        private final int responseStatus;
        private final Instant receivedAt;
        private final CreatorRemoteExecutionBoundary.DeferredExecution deferredExecution;
        private Future<?> future;
        private boolean completed;

        private TrackedExecution(
                final String executionId,
                final Path recordPath,
                final String requestBody,
                final int responseStatus,
                final Instant receivedAt,
                final CreatorRemoteExecutionBoundary.DeferredExecution deferredExecution
        ) {
            this.executionId = Objects.requireNonNull(executionId, "executionId");
            this.recordPath = Objects.requireNonNull(recordPath, "recordPath");
            this.requestBody = Objects.requireNonNull(requestBody, "requestBody");
            this.responseStatus = responseStatus;
            this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
            this.deferredExecution = Objects.requireNonNull(deferredExecution, "deferredExecution");
            this.completed = false;
        }
    }
}
