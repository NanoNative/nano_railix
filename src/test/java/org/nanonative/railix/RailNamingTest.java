package org.nanonative.railix;

import org.junit.jupiter.api.Test;
import org.nanonative.railix.name.Names;

import static org.assertj.core.api.Assertions.assertThat;

final class RailNamingTest {

  @Test
  void sanitize_withCommonSeparators_shouldNormalizeDeterministically() {
    assertThat(Names.sanitize("HTTP-Server")).isEqualTo("http_server");
    assertThat(Names.sanitize("http server")).isEqualTo("http_server");
    assertThat(Names.sanitize("http_server")).isEqualTo("http_server");
  }

  @Test
  void methodKey_withCamelCaseAndAcronyms_shouldConvertToSnakeCase() {
    assertThat(Names.methodKey("traceId")).isEqualTo("trace_id");
    assertThat(Names.methodKey("userId")).isEqualTo("user_id");
    assertThat(Names.methodKey("HTTPServer")).isEqualTo("http_server");
  }

  @Test
  void methodKey_withGreekAndLatinMixedInput_shouldKeepUnicodeLetters() {
    assertThat(Names.methodKey("ΜέθοδοςΔοκιμή")).isEqualTo("μέθοδος_δοκιμή");
    assertThat(Names.methodKey("GreekTest")).isEqualTo("greek_test");
    assertThat(Names.methodKey("TestGreek")).isEqualTo("test_greek");
  }

  @Test
  void methodKey_withSingleGreekWord_shouldLowercaseWithoutExtraUnderscores() {
    assertThat(Names.methodKey("Απλό")).isEqualTo("απλό");
  }

  @Test
  void sanitize_withNullBlankAndUnderscoreOnlyInput_shouldFallback() {
    assertThat(Names.sanitize(null, "fallback")).isEqualTo("fallback");
    assertThat(Names.sanitize("   ", "fallback")).isEqualTo("fallback");
    assertThat(Names.sanitize("___", "fallback")).isEqualTo("fallback");
    assertThat(Names.sanitize("___", null)).isNull();
  }

  @Test
  void methodKey_withBlankAndDigitBoundaries_shouldNormalizeDeterministically() {
    assertThat(Names.methodKey("")).isEqualTo("service");
    assertThat(Names.methodKey("HTTP2Server")).isEqualTo("http_2_server");
    assertThat(Names.methodKey("Version42Value")).isEqualTo("version_42_value");
  }
}
