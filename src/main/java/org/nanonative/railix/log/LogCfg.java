package org.nanonative.railix.log;

import java.util.function.Consumer;

/**
 * Immutable logging configuration.
 *
 * If a logConsumer is provided, all log entries will be sent to it instead of using
 * the internal RailixLogger implementation. This allows integration with any logging framework
 * (SLF4J, Log4J, Logback, java.util.logging, etc.).
 */
public record LogCfg(String name, LogLevel level, LogFormat format, Consumer<LogEntry> logConsumer) {
  public LogCfg {
    if (name == null || name.isBlank()) {
      name = "railix";
    }
    if (level == null) {
      level = LogLevel.INFO;
    }
    if (format == null) {
      format = LogFormat.CONSOLE;
    }
  }

  /**
   * Creates a LogCfg without a custom log consumer (uses internal logging).
   */
  public LogCfg(String name, LogLevel level, LogFormat format) {
    this(name, level, format, null);
  }

  public static LogCfg defaults() {
    return new LogCfg("railix", LogLevel.INFO, LogFormat.CONSOLE, null);
  }

  /**
   * Creates a new LogCfg with a custom log consumer.
   * When provided, the internal logger is bypassed and all log entries are sent to the consumer.
   *
   * @param logConsumer the consumer to receive log entries
   * @return new LogCfg with the consumer
   */
  public LogCfg withConsumer(Consumer<LogEntry> logConsumer) {
    return new LogCfg(name, level, format, logConsumer);
  }
}

