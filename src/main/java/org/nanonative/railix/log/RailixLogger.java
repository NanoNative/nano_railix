package org.nanonative.railix.log;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard logger with console and JSON output support.
 */
public class RailixLogger implements AutoCloseable {
  private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
  private static final Pattern KV = Pattern.compile("(\\w+):?\\s*\\[\\{\\}]");

  private final LogCfg cfg;
  private final PrintStream out;
  private final PrintStream err;

  public RailixLogger(final LogCfg cfg) {
    this.cfg = cfg == null ? LogCfg.defaults() : cfg;
    this.out = System.out;
    this.err = System.err;
  }

  public boolean enabled(final LogLevel level) {
    return level != null && level.enabled(cfg.level());
  }

  public void log(final LogLevel level, final Throwable error, final String template, final Object... args) {
    // Early return if log level is not enabled - avoid message compilation
    if (!enabled(level)) {
      return;
    }

    // Compile message only after log level check
    final String msg = Msg.format(template, args);

    // If custom log consumer is provided, use it instead of internal logging
    if (cfg.logConsumer() != null) {
      cfg.logConsumer().accept(
          new LogEntry(cfg.name(), level, msg, error, System.currentTimeMillis(), Thread.currentThread().getName()));
      return;
    }

    // Internal logging (only used when no consumer is provided)
    final long now = System.currentTimeMillis();
    final String thread = Thread.currentThread().getName();
    final PrintStream sink = (level == LogLevel.ERROR) ? err : out;

    if (cfg.format() == LogFormat.JSON) {
      sink.print(JsonLine.format(cfg.name(), now, thread, level, template, args, error, msg));
    } else {
      sink.println(
          TS.format(Instant.ofEpochMilli(now)) + " [" + thread + "] " + level + " " + cfg.name() + " - " + msg);
      if (error != null) {
        error.printStackTrace(sink);
      }
    }
  }

  public void trace(final String template, final Object... args) {
    log(LogLevel.TRACE, null, template, args);
  }

  public void trace(final Supplier<String> msgSupplier) {
    if (enabled(LogLevel.TRACE)) {
      log(LogLevel.TRACE, null, msgSupplier.get());
    }
  }

  public void debug(final String template, final Object... args) {
    log(LogLevel.DEBUG, null, template, args);
  }

  public void debug(final Supplier<String> msgSupplier) {
    if (enabled(LogLevel.DEBUG)) {
      log(LogLevel.DEBUG, null, msgSupplier.get());
    }
  }

  public void info(final String template, final Object... args) {
    log(LogLevel.INFO, null, template, args);
  }

  public void info(final Supplier<String> msgSupplier) {
    if (enabled(LogLevel.INFO)) {
      log(LogLevel.INFO, null, msgSupplier.get());
    }
  }

  public void warn(final String template, final Object... args) {
    log(LogLevel.WARN, null, template, args);
  }

  public void warn(final String template, final Throwable error, final Object... args) {
    log(LogLevel.WARN, error, template, args);
  }

  public void warn(final Supplier<String> msgSupplier) {
    if (enabled(LogLevel.WARN)) {
      log(LogLevel.WARN, null, msgSupplier.get());
    }
  }

  public void error(final String template, final Object... args) {
    log(LogLevel.ERROR, null, template, args);
  }

  public void error(final String template, final Throwable error, final Object... args) {
    log(LogLevel.ERROR, error, template, args);
  }

  public void error(final Supplier<String> msgSupplier) {
    log(LogLevel.ERROR, null, msgSupplier.get());
  }

  private static final class Msg {
    private Msg() {
    }

    static String format(final String template, final Object... args) {
      final String t = Objects.toString(template, "");
      if (args == null || args.length == 0) {
        return t;
      }
      final String normalized = t.replace("{}", "%s");
      try {
        return String.format(normalized, args);
      } catch (final RuntimeException ex) {
        return t + " " + Arrays.toString(args);
      }
    }
  }

  private static final class JsonLine {
    private JsonLine() {
    }

    static String format(
        final String name,
        final long epochMillis,
        final String thread,
        final LogLevel level,
        final String rawTemplate,
        final Object[] args,
        final Throwable error,
        final String formattedMessage) {
      final TreeMap<String, String> json = new TreeMap<>();

      put(json, "message", formattedMessage);
      put(json, "timestamp", TS.format(Instant.ofEpochMilli(epochMillis)));
      put(json, "level", String.valueOf(level));
      put(json, "logger", name);
      put(json, "thread", thread);

      extractKeyValuesFromMessage(json, rawTemplate, args);
      addMapEntries(json, args);

      if (error != null) {
        put(json, "error", error.toString());
      }

      final StringBuilder sb = new StringBuilder(256);
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, String> e : json.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append('"').append(escape(e.getKey())).append('"')
            .append(':')
            .append('"').append(escape(e.getValue())).append('"');
      }
      sb.append('}').append('\n');
      return sb.toString();
    }

    private static void extractKeyValuesFromMessage(final Map<String, String> json, final String template,
        final Object[] args) {
      if (template == null || args == null || args.length == 0) {
        return;
      }
      final Matcher m = KV.matcher(template);
      int idx = 0;
      while (m.find() && idx < args.length) {
        put(json, m.group(1), String.valueOf(args[idx]));
        idx++;
      }
    }

    private static void addMapEntries(final Map<String, String> json, final Object[] args) {
      if (args == null) {
        return;
      }
      for (Object a : args) {
        if (a instanceof Map<?, ?> map) {
          for (Map.Entry<?, ?> e : map.entrySet()) {
            put(json, String.valueOf(e.getKey()), String.valueOf(e.getValue()));
          }
        }
      }
    }

    private static void put(final Map<String, String> json, final String key, final String value) {
      if (key == null || key.isBlank()) {
        return;
      }
      json.put(key, value == null ? "null" : value);
    }

    private static String escape(final String s) {
      if (s == null) {
        return "null";
      }
      final StringBuilder out = new StringBuilder(s.length() + 16);
      for (int i = 0; i < s.length(); i++) {
        final char c = s.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> out.append(c);
        }
      }
      return out.toString();
    }
  }

  @Override
  public void close() {
    // no-op
  }
}
