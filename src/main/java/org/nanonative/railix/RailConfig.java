package org.nanonative.railix;

import org.nanonative.railix.log.LogFormat;
import org.nanonative.railix.log.LogLevel;
import org.nanonative.railix.name.Names;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Per-rail overrides builder.
 * <p>
 * This is <b>not</b> the compiled global config (env/props/args/files).
 * It only produces an immutable {@link Map} that is applied to {@link Rail#railConfig()}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * Rail rail = Rail.of(RailConfig.builder()
 *     .name("my-rail")
 *     .metrics(true)
 *     .kv("my_custom_key", "value")
 *     .build());
 * }</pre>
 */
public final class RailConfig {

  public static final String KEY_NAME = "railix_rail_name";
  public static final String KEY_LOG_LEVEL = "railix_log_level";
  public static final String KEY_LOG_FORMAT = "railix_log_format";
  public static final String KEY_EXECUTOR = "railix_executor";
  public static final String KEY_METRICS_ENABLED = "railix_metrics_enabled";
  public static final String KEY_ON_COMPLETE = "railix_on_complete";

  private RailConfig() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final HashMap<String, Object> out = new HashMap<>();

    private Builder() {
    }

    public Builder name(final String name) {
      return kv(KEY_NAME, name == null || name.isBlank() ? null : name.trim());
    }

    public Builder logLevel(final LogLevel level) {
      return kv(KEY_LOG_LEVEL, level);
    }

    public Builder logFormat(final LogFormat format) {
      return kv(KEY_LOG_FORMAT, format);
    }

    public Builder executor(final Executor executor) {
      return kv(KEY_EXECUTOR, executor);
    }

    public Builder metrics(final boolean enabled) {
      return kv(KEY_METRICS_ENABLED, enabled);
    }

    public Builder onComplete(final Consumer<Result> callback) {
      return kv(KEY_ON_COMPLETE, callback);
    }

    public Builder kv(final String key, final Object value) {
      final String k = Names.sanitize(key, "");
      if (k.isBlank()) {
        return this;
      }
      if (value == null) {
        out.remove(k);
      } else {
        out.put(k, value);
      }
      return this;
    }

    public Map<String, Object> build() {
      return out.isEmpty()
          ? Map.of()
          : Collections.unmodifiableMap(new HashMap<>(out));
    }
  }
}
