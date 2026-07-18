package dev.nanonative.railix.creator.runtime;

import dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorRunLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldBoundCapturedLauncherOutputWhilePreservingTotalSizes() {
        final String oversizedStdout = "x".repeat(CreatorRunLauncher.MAX_CAPTURE_BYTES + 1024);
        final String oversizedStderr = "y".repeat(CreatorRunLauncher.MAX_CAPTURE_BYTES + 2048);
        final CreatorRunLauncher.RunLaunchRequest request = new CreatorRunLauncher.RunLaunchRequest(
                tempDir.resolve("plan.json").toString(),
                tempDir.resolve("envelope.json").toString(),
                "",
                "",
                "run-bounded-output-1"
        );

        final Map<String, Object> launch = CreatorRunLauncher.launch(
                request,
                tempDir.resolve("runs"),
                (args, stdout, stderr) -> writeOversizedOutput(args, stdout, stderr, oversizedStdout, oversizedStderr)
        );

        assertThat(launch)
                .containsEntry("exitCode", 0)
                .containsEntry("succeeded", true)
                .containsEntry("stdoutTruncated", true)
                .containsEntry("stderrTruncated", true);
        assertThat(((String) launch.get("stdout"))).endsWith("\n...[truncated]");
        assertThat(((String) launch.get("stderr"))).endsWith("\n...[truncated]");
        assertThat(numberValue(launch, "stdoutSize")).isEqualTo(oversizedStdout.length());
        assertThat(numberValue(launch, "stderrSize")).isEqualTo(oversizedStderr.length());
        assertThat(((String) launch.get("stdout")).length()).isEqualTo(CreatorRunLauncher.MAX_CAPTURE_BYTES);
        assertThat(((String) launch.get("stderr")).length()).isEqualTo(CreatorRunLauncher.MAX_CAPTURE_BYTES);
    }

    private static BuiltRailixAppMain.LaunchResult writeOversizedOutput(
            final List<String> args,
            final PrintStream stdout,
            final PrintStream stderr,
            final String oversizedStdout,
            final String oversizedStderr
    ) {
        assertThat(args).contains("--plan", "--envelope", "--runs-root", "--run-id");
        stdout.print(oversizedStdout);
        stderr.print(oversizedStderr);
        return new BuiltRailixAppMain.LaunchResult(0, "ok", "run-bounded-output-1", "ok", "/tmp/run-bounded-output-1");
    }

    private static int numberValue(final Map<String, Object> source, final String key) {
        return ((Number) source.get(key)).intValue();
    }
}
