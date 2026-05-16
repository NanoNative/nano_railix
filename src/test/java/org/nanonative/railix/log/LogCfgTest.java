package org.nanonative.railix.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class LogCfgTest {

    @Test
    void defaults_shouldUseRailixInfoConsoleWithoutConsumer() {
        final LogCfg cfg = LogCfg.defaults();

        assertThat(cfg.name()).isEqualTo("railix");
        assertThat(cfg.level()).isEqualTo(LogLevel.INFO);
        assertThat(cfg.format()).isEqualTo(LogFormat.CONSOLE);
        assertThat(cfg.logConsumer()).isNull();
    }

    @Test
    void canonicalConstructor_withBlankAndNullValues_shouldNormalizeDefaults() {
        final LogCfg cfg = new LogCfg(" ", null, null, null);

        assertThat(cfg.name()).isEqualTo("railix");
        assertThat(cfg.level()).isEqualTo(LogLevel.INFO);
        assertThat(cfg.format()).isEqualTo(LogFormat.CONSOLE);
        assertThat(cfg.logConsumer()).isNull();
    }

    @Test
    void withConsumer_shouldPreserveNameLevelAndFormat() {
        final List<LogEntry> entries = new ArrayList<>();
        final LogCfg cfg = new LogCfg("svc", LogLevel.DEBUG, LogFormat.JSON).withConsumer(entries::add);

        assertThat(cfg.name()).isEqualTo("svc");
        assertThat(cfg.level()).isEqualTo(LogLevel.DEBUG);
        assertThat(cfg.format()).isEqualTo(LogFormat.JSON);
        assertThat(cfg.logConsumer()).isNotNull();
    }

    @Test
    void canonicalConstructor_withNullLevelOnly_shouldKeepProvidedNameAndFormat() {
        final LogCfg cfg = new LogCfg("svc", null, LogFormat.JSON, null);

        assertThat(cfg.name()).isEqualTo("svc");
        assertThat(cfg.level()).isEqualTo(LogLevel.INFO);
        assertThat(cfg.format()).isEqualTo(LogFormat.JSON);
    }

    @Test
    void canonicalConstructor_withNullFormatOnly_shouldKeepProvidedNameAndLevel() {
        final LogCfg cfg = new LogCfg("svc", LogLevel.DEBUG, null, null);

        assertThat(cfg.name()).isEqualTo("svc");
        assertThat(cfg.level()).isEqualTo(LogLevel.DEBUG);
        assertThat(cfg.format()).isEqualTo(LogFormat.CONSOLE);
    }
}
