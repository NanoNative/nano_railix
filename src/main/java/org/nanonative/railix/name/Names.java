package org.nanonative.railix.name;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public class Names {
  private static final Pattern NON_ALLOWED = Pattern.compile("[^\\p{L}\\p{N}_]+");
  private static final Pattern UNDERSCORES = Pattern.compile("_+");
  private static final Pattern LEADING_UNDERSCORES = Pattern.compile("^_+");
  private static final Pattern TRAILING_UNDERSCORES = Pattern.compile("_+$");

  private Names() {
  }

  /**
   * Normalizes a name to: lowercase, only letters/digits/underscore, collapsed
   * underscores, trimmed underscores.
   * <p>
   * If the result is blank, returns {@code fallback}.
   */
  public static String sanitize(final String raw, final String fallback) {
    final String result = TRAILING_UNDERSCORES.matcher(
        LEADING_UNDERSCORES.matcher(
            UNDERSCORES.matcher(
                NON_ALLOWED.matcher(
                    ((raw == null || raw.isBlank()) ? fallback : raw).toLowerCase(Locale.ROOT)).replaceAll("_"))
                .replaceAll("_"))
            .replaceAll(""))
        .replaceAll("");
    return result.isBlank() ? fallback : result;
  }

  public static String sanitize(final String raw) {
    return sanitize(raw, "service");
  }

  /**
   * Converts a java method name to a snake_case key, then sanitizes it.
   * Example: {@code traceId -> trace_id}, {@code HTTPServer -> http_server}.
   */
  public static String methodKey(final String methodName) {
    Objects.requireNonNull(methodName, "methodName");
    if (methodName.isBlank()) {
      return "service";
    }
    final String snake = camelToSnake(methodName);
    return sanitize(snake, "service");
  }

  private static String camelToSnake(final String s) {
    // Unicode-aware regex:
    // \p{Ll} = Lowercase letter, \p{Lu} = Uppercase letter, \p{L} = Any letter, \d
    // = Digit
    return s.replaceAll("([\\p{Ll}\\d])(\\p{Lu})", "$1_$2")
        .replaceAll("(\\p{Lu}+)(\\p{Lu}\\p{Ll})", "$1_$2")
        .replaceAll("(\\p{L})(\\d)", "$1_$2")
        .replaceAll("(\\d)(\\p{L})", "$1_$2")
        .toLowerCase(Locale.ROOT);
  }
}
