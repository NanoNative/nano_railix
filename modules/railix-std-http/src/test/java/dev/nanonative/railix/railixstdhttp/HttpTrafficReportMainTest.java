package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTrafficReportMainTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPrintStableJsonReportToStdout() throws Exception {
        writeCapture(
                tempDir.resolve("railix-app/http-flow/http-run-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"2026-06-28T10:12:00Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-1","method":"POST","path":"/ok","responseStatus":201,"durationMs":3,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final HttpTrafficReportMain.LaunchResult result = HttpTrafficReportMain.launch(
                List.of("--runs-root", tempDir.toString(), "--limit", "10"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(stderr.toString()).isEmpty();
        assertThat(KernelContractCodec.parseStableJsonObject(stdout.toString().trim()))
                .containsEntry("totalRows", 1)
                .containsEntry("handledRows", 1)
                .containsEntry("rejectedRows", 0)
                .containsEntry("failedRows", 0);
    }

    @Test
    void shouldRejectMissingRunsRoot() {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final HttpTrafficReportMain.LaunchResult result = HttpTrafficReportMain.launch(
                List.of("--limit", "10"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("--runs-root");
    }

    @Test
    void shouldRejectNonNumericLimit() {
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final HttpTrafficReportMain.LaunchResult result = HttpTrafficReportMain.launch(
                List.of("--runs-root", tempDir.toString(), "--limit", "ten"),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(stderr.toString()).contains("--limit must be an integer");
    }

    @Test
    void shouldFailClearlyWhenCaptureArtifactIsInvalid() throws Exception {
        writeCapture(
                tempDir.resolve("railix-app/http-flow/http-run-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"not-an-instant"}
                """
        );
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        final HttpTrafficReportMain.LaunchResult result = HttpTrafficReportMain.launch(
                List.of("--runs-root", tempDir.toString()),
                new PrintStream(stdout),
                new PrintStream(stderr)
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(stdout.toString()).isEmpty();
        assertThat(stderr.toString()).contains("Failed to read HTTP traffic report").contains("timestamp");
    }

    private static void writeCapture(final Path path, final String json) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }
}
