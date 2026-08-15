package dev.nanonative.railix.creator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixLauncherLifecycleE2eTest {
    private static final Path LAUNCHER = Path.of("..", "..", "railix").toAbsolutePath().normalize();

    @Test
    void cleanTestLifecycleKeepsTheTrackedRootLauncher() {
        assertThat(LAUNCHER).isRegularFile().isExecutable();
    }
}
