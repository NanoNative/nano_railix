package org.nanonative.railix.config;

import berlin.yuna.typemap.model.LinkedTypeMap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class ConfigTest {

    @Test
    void load_withClasspathExternalAndSystemProperties_shouldMergeCanonicalRailixKeys() throws IOException {
        final Path file = Files.createTempFile("railix-cfg", ".properties");
        Files.writeString(file, """
            = ignored-empty-key
            railix.external-key = external-value
            railix.log-level = WARN
            railix.blank =
            app.name = ignored
            """);

        final String prevConfigFile = System.getProperty("railix_config_file");
        final String prevMode = System.getProperty("railix.logging mode");
        final String prevLevel = System.getProperty("railix.log-level");
        final String prevIgnored = System.getProperty("app.name");

        try {
            System.setProperty("railix_config_file", file.toString());
            System.setProperty("railix.logging mode", " json ");
            System.setProperty("railix.log-level", "DEBUG");
            System.setProperty("app.name", "ignored");

            final LinkedTypeMap cfg = Config.load();

            assertThat(cfg.asString("railix_test_classpath_value")).isEqualTo("from_classpath");
            assertThat(cfg.asString("railix_external_key")).isEqualTo("external-value");
            assertThat(cfg.asString("railix_logging_mode")).isEqualTo("json");
            assertThat(cfg.asString("railix_log_level")).isEqualTo("DEBUG");
            assertThat(cfg.asString("railix_config_file")).isEqualTo(file.toString());
            assertThat(cfg.asString("app_name")).isNull();
            assertThat(cfg.asString("railix_blank")).isNull();
            assertThat(cfg.asString("")).isNull();
        } finally {
            restoreProperty("railix_config_file", prevConfigFile);
            restoreProperty("railix.logging mode", prevMode);
            restoreProperty("railix.log-level", prevLevel);
            restoreProperty("app.name", prevIgnored);
            Files.deleteIfExists(file);
        }
    }

    @Test
    void load_withMissingExternalFile_shouldIgnoreUnreadableSource() {
        final String prevConfigFile = System.getProperty("railix_config_file");
        final String prevCustom = System.getProperty("railix_custom_flag");

        try {
            System.setProperty("railix_config_file", "/path/that/does/not/exist/railix.properties");
            System.setProperty("railix_custom_flag", "enabled");

            final LinkedTypeMap cfg = Config.load();

            assertThat(cfg.asString("railix_custom_flag")).isEqualTo("enabled");
            assertThat(cfg.asString("railix_config_file"))
                .isEqualTo("/path/that/does/not/exist/railix.properties");
        } finally {
            restoreProperty("railix_config_file", prevConfigFile);
            restoreProperty("railix_custom_flag", prevCustom);
        }
    }

    @Test
    void load_withDirectoryExternalPathAndMixedSystemProperties_shouldIgnoreUnreadableAndFilterBlankValues()
        throws IOException {
        final Path dir = Files.createTempDirectory("railix-config-dir");
        final String prevConfigFile = System.getProperty("railix_config_file");
        final String prevCustom = System.getProperty("railix_custom_flag");
        final String prevMixed = System.getProperty("railix_mixed_key");
        final String prevBlank = System.getProperty("railix_blank_value");
        final String prevIgnored = System.getProperty("app.name");

        try {
            System.setProperty("railix_config_file", dir.toString());
            System.setProperty("railix_custom_flag", "enabled");
            System.setProperty("railix_mixed_key", " spaced ");
            System.setProperty("railix_blank_value", "   ");
            System.setProperty("app.name", "ignored");

            final LinkedTypeMap cfg = Config.load();

            assertThat(cfg.asString("railix_custom_flag")).isEqualTo("enabled");
            assertThat(cfg.asString("railix_mixed_key")).isEqualTo("spaced");
            assertThat(cfg.asString("railix_blank_value")).isNull();
            assertThat(cfg.asString("app_name")).isNull();
            assertThat(cfg.asString("railix_config_file")).isEqualTo(dir.toString());
        } finally {
            restoreProperty("railix_config_file", prevConfigFile);
            restoreProperty("railix_custom_flag", prevCustom);
            restoreProperty("railix_mixed_key", prevMixed);
            restoreProperty("railix_blank_value", prevBlank);
            restoreProperty("app.name", prevIgnored);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void load_withBlankExternalPropertyPointer_shouldSkipExternalLookup() {
        final String prevConfigFile = System.getProperty("railix_config_file");

        try {
            System.setProperty("railix_config_file", "   ");

            final LinkedTypeMap cfg = Config.load();

            assertThat(cfg.asString("railix_test_classpath_value")).isEqualTo("from_classpath");
            assertThat(cfg.asString("railix_config_file")).isNull();
        } finally {
            restoreProperty("railix_config_file", prevConfigFile);
        }
    }

    private static void restoreProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
