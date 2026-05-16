package org.nanonative.railix.config;

import berlin.yuna.typemap.model.LinkedTypeMap;
import org.nanonative.railix.name.Names;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Loads configuration and normalizes keys to a canonical underscore form using
 * {@link Names#sanitize(String)}.
 * <p>
 * Example: {@code railix.logging.mode}, {@code RAILIX_LOGGING_MODE},
 * {@code railix-logging mode}
 * all normalize to {@code railix_logging_mode}.
 */
public final class Config {
  private Config() {
  }

  public static LinkedTypeMap load() {
    final LinkedTypeMap out = new LinkedTypeMap();
    readPropsClasspath("railix.properties").ifPresent(p -> putAll(out, p));
    readPropsExternal().ifPresent(p -> putAll(out, p));
    putAll(out, envProps());
    putAll(out, systemProps());
    return out;
  }

  private static void putAll(final LinkedTypeMap out, final Properties p) {
    for (String k : p.stringPropertyNames()) {
      final String v = p.getProperty(k);
      put(out, k, v);
    }
  }

  private static void putAll(final LinkedTypeMap out, final Map<String, String> env) {
    for (Map.Entry<String, String> e : env.entrySet()) {
      put(out, e.getKey(), e.getValue());
    }
  }

  private static void put(final LinkedTypeMap out, final String rawKey, final String rawValue) {
    if (rawKey == null || rawKey.isBlank()) {
      return;
    }
    final String key = Names.sanitize(rawKey, "railix");
    if (!key.startsWith("railix")) {
      return;
    }
    final String value = rawValue == null ? "" : rawValue.trim();
    if (!value.isEmpty()) {
      out.put(key, value);
    }
  }

  private static Optional<Properties> readPropsClasspath(final String resourceName) {
    try (InputStream is = Config.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (is == null) {
        return Optional.empty();
      }
      final Properties p = new Properties();
      p.load(is);
      return Optional.of(p);
    } catch (final Exception ignored) {
      return Optional.empty();
    }
  }

  private static Optional<Properties> readPropsExternal() {
    final String prop = System.getProperty("railix_config_file");
    final String raw = (prop != null && !prop.isBlank()) ? prop : System.getenv("RAILIX_CONFIG_FILE");
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    final Path path = Path.of(raw);
    if (!Files.isReadable(path)) {
      return Optional.empty();
    }
    try (InputStream is = Files.newInputStream(path)) {
      Properties p = new Properties();
      p.load(is);
      return Optional.of(p);
    } catch (final Exception ignored) {
      return Optional.empty();
    }
  }

  private static Map<String, String> envProps() {
    final java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> e : System.getenv().entrySet()) {
      if (e.getKey().startsWith("RAILIX_")) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  private static Map<String, String> systemProps() {
    final Properties p = System.getProperties();
    final java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
    for (String name : p.stringPropertyNames()) {
      if (name.startsWith("railix.") || name.startsWith("railix_")) {
        out.put(name, p.getProperty(name));
      }
    }
    return out;
  }
}
