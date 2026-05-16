package org.nanonative.railix.log;

/**
 * Immutable log entry containing all log information.
 * Used for custom log consumers to integrate with external logging frameworks.
 */
public record LogEntry(
    String loggerName,
    LogLevel level,
    String message,
    Throwable error,
    long timestampMillis,
    String threadName
) {
}
