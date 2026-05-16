package org.nanonative.railix.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixLoggerTest {

  @Test
  void enabled_withNullLevel_shouldReturnFalse() {
    assertThat(new RailixLogger(LogCfg.defaults()).enabled(null)).isFalse();
  }

  @Test
    void supplier_whenLevelDisabled_shouldNotEvaluate() {
        final AtomicBoolean called = new AtomicBoolean(false);
        final RailixLogger log = new RailixLogger(new LogCfg("t", LogLevel.WARN, LogFormat.CONSOLE));

    log.debug(() -> {
      called.set(true);
      return "debug";
    });

        assertThat(called.get()).isFalse();
    }

    @Test
    void supplierMethods_whenLevelDisabled_shouldNotEvaluateTraceInfoWarnOrErrorSuppliers() {
        final AtomicBoolean traceCalled = new AtomicBoolean(false);
        final AtomicBoolean infoCalled = new AtomicBoolean(false);
        final AtomicBoolean warnCalled = new AtomicBoolean(false);
        final AtomicBoolean errorCalled = new AtomicBoolean(false);
        final RailixLogger log = new RailixLogger(new LogCfg("t", LogLevel.ERROR, LogFormat.CONSOLE));

        log.trace(() -> {
            traceCalled.set(true);
            return "trace";
        });
        log.info(() -> {
            infoCalled.set(true);
            return "info";
        });
        log.warn(() -> {
            warnCalled.set(true);
            return "warn";
        });
        log.error(() -> {
            errorCalled.set(true);
            return "error";
        });

        assertThat(traceCalled.get()).isFalse();
        assertThat(infoCalled.get()).isFalse();
        assertThat(warnCalled.get()).isFalse();
        assertThat(errorCalled.get()).isTrue();
    }

    @Test
    void constructor_withNullConfig_shouldUseDefaultsAndWriteConsoleInfoToStdout() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            final RailixLogger log = new RailixLogger(null);
            log.info("hello {}", "world");

            assertThat(out.toString(StandardCharsets.UTF_8)).contains("INFO railix - hello world");
        } finally {
            System.setOut(prevOut);
        }
    }

  @Test
  void supplierMethods_whenEnabled_shouldEmitEntriesToConsumer() {
    final List<LogEntry> entries = new ArrayList<>();
    final RailixLogger log = new RailixLogger(new LogCfg("t", LogLevel.TRACE, LogFormat.CONSOLE).withConsumer(entries::add));

    log.trace(() -> "trace");
    log.debug(() -> "debug");
    log.info(() -> "info");
    log.warn(() -> "warn");
    log.error(() -> "error");
    log.close();

    assertThat(entries.stream().map(LogEntry::level).toList())
        .isEqualTo(List.of(LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR));
    assertThat(entries.stream().map(LogEntry::message).toList())
        .isEqualTo(List.of("trace", "debug", "info", "warn", "error"));
  }

  @Test
  void jsonLogExtractsFieldsFromTemplateAndMergesMapArgs() {
    final PrintStream prev = System.out;
    final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
    try {
      final RailixLogger log = new RailixLogger(new LogCfg("t", LogLevel.INFO, LogFormat.JSON));
      log.info("user: [{}] action: [{}]", "yuna", "login", Map.of("tenant", "t1"));
      final String line = buf.toString(StandardCharsets.UTF_8);
      assertThat(line).contains(
          "\"user\":\"yuna\"",
          "\"action\":\"login\"",
          "\"tenant\":\"t1\"",
          "\"message\":\"user: [yuna] action: [login]\"");
    } finally {
      System.setOut(prev);
    }
  }

  @Test
  void jsonLog_withEscapedCharactersAndError_shouldEscapeAndIncludeError() {
    final PrintStream prevErr = System.err;
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      final RailixLogger log = new RailixLogger(new LogCfg("t", LogLevel.INFO, LogFormat.JSON));
      log.log(LogLevel.ERROR, new IllegalStateException("boom\nstate"), "msg: [{}]", "a\"b\n\tc", Map.of("k", "v\"x", "", "ignored"));
      final String line = err.toString(StandardCharsets.UTF_8);
      assertThat(line).contains(
          "\"error\":\"java.lang.IllegalStateException: boom\\nstate\"",
          "\"message\":\"msg: [a\\\"b\\n\\tc]\"",
          "\"k\":\"v\\\"x\"",
          "a\\\"b\\n\\tc");
      assertThat(line).doesNotContain("\"\":\"ignored\"");
    } finally {
      System.setErr(prevErr);
    }
  }

  @Test
    void consoleError_withThrowable_shouldWriteToErrAndPrintStackTrace() {
        final PrintStream prevErr = System.err;
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.CONSOLE));
      log.error("failed {}", new IllegalStateException("boom"), "request");
      final String output = err.toString(StandardCharsets.UTF_8);
      assertThat(output).contains("ERROR svc - failed request", "java.lang.IllegalStateException: boom");
    } finally {
      System.setErr(prevErr);
        }
    }

    @Test
    void consoleWarn_withThrowable_shouldWriteToStdoutAndPrintStackTrace() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.CONSOLE));
            log.warn("warn {}", new IllegalStateException("boom"), "request");

            final String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("WARN svc - warn request", "java.lang.IllegalStateException: boom");
        } finally {
            System.setOut(prevOut);
        }
    }

    @Test
    void log_withInvalidFormatString_shouldFallbackToTemplateAndArgs() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.CONSOLE));
      log.trace("trace {}", "x");
      log.info("value=%d", "oops");
      final String output = out.toString(StandardCharsets.UTF_8);
      assertThat(output).contains("value=%d [oops]");
        } finally {
            System.setOut(prevOut);
        }
    }

    @Test
    void log_withNullTemplateAndMapArgs_shouldKeepPredictableJsonFields() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            final HashMap<Object, Object> meta = new HashMap<>();
            meta.put("", "ignored");
            meta.put(null, "null-key");
            meta.put("tenant", null);

            final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.JSON));
            log.info(null, meta);

            final String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains(
                "\"message\":\"\"",
                "\"logger\":\"svc\"",
                "\"null\":\"null-key\"",
                "\"tenant\":\"null\"");
            assertThat(output).doesNotContain("\"\":\"ignored\"");
        } finally {
            System.setOut(prevOut);
        }
    }

    @Test
    void jsonLog_withBackslashAndCarriageReturn_shouldEscapeSupportedCharacters() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.JSON));
            log.info("path: [{}]", "c:\\tmp\\file\rname");

            final String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("\"message\":\"path: [c:\\\\tmp\\\\file\\rname]\"");
        } finally {
            System.setOut(prevOut);
        }
    }

    @Test
    void jsonLog_withoutArgs_shouldKeepBaseFieldsOnly() {
        final PrintStream prevOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.JSON));
            log.info("plain message");

            final String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("\"message\":\"plain message\"", "\"logger\":\"svc\"");
            assertThat(output).doesNotContain("\"tenant\":", "\"user\":");
        } finally {
            System.setOut(prevOut);
        }
    }

    @Test
    void log_withNullTemplateAndNullArgs_shouldFallbackToEmptyMessage() {
        final List<LogEntry> entries = new ArrayList<>();
        final RailixLogger log = new RailixLogger(new LogCfg("svc", LogLevel.INFO, LogFormat.JSON).withConsumer(entries::add));

        log.log(LogLevel.INFO, null, null, (Object[]) null);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.message()).isEmpty();
            assertThat(entry.level()).isEqualTo(LogLevel.INFO);
        });
    }
}
