package org.nanonative.railix.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for custom log consumer integration.
 * Demonstrates how to integrate with external logging frameworks.
 */
final class LogConsumerTest {

    @Test
    void customConsumerReceivesLogEntries() {
        List<LogEntry> captured = new ArrayList<>();

        LogCfg cfg = new LogCfg("test-logger", LogLevel.DEBUG, LogFormat.CONSOLE)
                .withConsumer(captured::add);

        RailixLogger log = new RailixLogger(cfg);

        log.info("Hello {}", "world");
        log.debug("Debug message");
        log.warn("Warning message", new RuntimeException("test error"));

        assertThat(captured).hasSize(3);

        // First entry
        LogEntry entry1 = captured.get(0);
        assertThat(entry1.loggerName()).isEqualTo("test-logger");
        assertThat(entry1.level()).isEqualTo(LogLevel.INFO);
        assertThat(entry1.message()).isEqualTo("Hello world");
        assertThat(entry1.error()).isNull();

        // Second entry
        LogEntry entry2 = captured.get(1);
        assertThat(entry2.level()).isEqualTo(LogLevel.DEBUG);
        assertThat(entry2.message()).isEqualTo("Debug message");

        // Third entry
        LogEntry entry3 = captured.get(2);
        assertThat(entry3.level()).isEqualTo(LogLevel.WARN);
        assertThat(entry3.message()).isEqualTo("Warning message");
        assertThat(entry3.error()).isNotNull();
        assertThat(entry3.error().getMessage()).isEqualTo("test error");
    }

    @Test
    void consumerNotCalledWhenLogLevelTooLow() {
        List<LogEntry> captured = new ArrayList<>();

        LogCfg cfg = new LogCfg("test-logger", LogLevel.WARN, LogFormat.CONSOLE)
                .withConsumer(captured::add);

        RailixLogger log = new RailixLogger(cfg);

        log.debug("Should not be logged");
        log.info("Should not be logged");
        log.warn("Should be logged");
        log.error("Should be logged");

        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).level()).isEqualTo(LogLevel.WARN);
        assertThat(captured.get(1).level()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void timestampAndThreadNameAreProvided() {
        List<LogEntry> captured = new ArrayList<>();

        LogCfg cfg = new LogCfg("test-logger", LogLevel.INFO, LogFormat.CONSOLE)
                .withConsumer(captured::add);

        RailixLogger log = new RailixLogger(cfg);
        long before = System.currentTimeMillis();
        log.info("Test message");
        long after = System.currentTimeMillis();

        assertThat(captured).hasSize(1);
        LogEntry entry = captured.getFirst();

        // Timestamp should be between before and after
        assertThat(entry.timestampMillis()).isGreaterThanOrEqualTo(before);
        assertThat(entry.timestampMillis()).isLessThanOrEqualTo(after);

        // Thread name should be set
        assertThat(entry.threadName()).isNotNull().isNotBlank();
    }

    /**
     * Example: Integration with SLF4J-like API
     */
    @Test
    void exampleSlf4jIntegration() {
        // Simulated SLF4J logger
        class Slf4jAdapter {
            final List<String> logs = new ArrayList<>();

            void log(String level, String logger, String message, Throwable error) {
                logs.add(level + " [" + logger + "] " + message);
                if (error != null) {
                    logs.add("  Error: " + error.getMessage());
                }
            }
        }

        Slf4jAdapter slf4j = new Slf4jAdapter();

        LogCfg cfg = new LogCfg("my-service", LogLevel.INFO, LogFormat.CONSOLE)
                .withConsumer(entry -> {
                    // Bridge to SLF4J
                    slf4j.log(
                            entry.level().name(),
                            entry.loggerName(),
                            entry.message(),
                            entry.error());
                });

        RailixLogger log = new RailixLogger(cfg);
        log.info("Processing request {}", "12345");
        log.error("Failed to process", new IllegalStateException("Invalid state"));

        assertThat(slf4j.logs).hasSize(3);
        assertThat(slf4j.logs.get(0)).contains("INFO [my-service] Processing request 12345");
        assertThat(slf4j.logs.get(1)).contains("ERROR [my-service] Failed to process");
        assertThat(slf4j.logs.get(2)).contains("Error: Invalid state");
    }

    /**
     * Example: Integration with java.util.logging
     */
    @Test
    void exampleJavaUtilLoggingIntegration() {
        // Simulated java.util.logging.Logger
        class JulAdapter {
            final List<String> logs = new ArrayList<>();

            void log(java.util.logging.Level level, String message, Throwable thrown) {
                logs.add(level.getName() + ": " + message);
            }
        }

        JulAdapter jul = new JulAdapter();

        LogCfg cfg = new LogCfg("my-app", LogLevel.DEBUG, LogFormat.CONSOLE)
                .withConsumer(entry -> {
                    // Map LogLevel to java.util.logging.Level
                    java.util.logging.Level julLevel = switch (entry.level()) {
                        case TRACE -> java.util.logging.Level.FINEST;
                        case DEBUG -> java.util.logging.Level.FINE;
                        case INFO -> java.util.logging.Level.INFO;
                        case WARN -> java.util.logging.Level.WARNING;
                        case ERROR -> java.util.logging.Level.SEVERE;
                    };
                    jul.log(julLevel, entry.message(), entry.error());
                });

        RailixLogger log = new RailixLogger(cfg);
        log.debug("Debug info");
        log.warn("Warning!");

        assertThat(jul.logs).hasSize(2);
        assertThat(jul.logs.get(0)).contains("FINE: Debug info");
        assertThat(jul.logs.get(1)).contains("WARNING: Warning!");
    }
}
