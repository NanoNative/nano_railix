package dev.nanonative.railix.railixstdhttp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTrafficPanelQueryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnEmptyListWhenRunsRootDoesNotExist() {
        final HttpTrafficPanelQuery query = new HttpTrafficPanelQuery();

        assertThat(query.read(tempDir.resolve("missing"), 10)).isEmpty();
    }

    @Test
    void shouldRejectNegativeLimit() {
        final HttpTrafficPanelQuery query = new HttpTrafficPanelQuery();

        assertThatThrownBy(() -> query.read(tempDir, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be >= 0");
    }

    @Test
    void shouldFailClearlyWhenCaptureFileIsMalformed() throws Exception {
        final Path captureFile = tempDir
                .resolve("railix-app")
                .resolve("http-flow")
                .resolve("http-run-1")
                .resolve(HttpCaptureArtifact.FILE_NAME);
        Files.createDirectories(captureFile.getParent());
        Files.writeString(captureFile, "{\"timestamp\":\"not-an-instant\"}");

        final HttpTrafficPanelQuery query = new HttpTrafficPanelQuery();

        assertThatThrownBy(() -> query.read(tempDir, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void shouldDefaultCaptureKindToHandledRunWhenFieldIsMissing() throws Exception {
        final Path captureFile = tempDir
                .resolve("railix-app")
                .resolve("http-flow")
                .resolve("http-run-1")
                .resolve(HttpCaptureArtifact.FILE_NAME);
        Files.createDirectories(captureFile.getParent());
        Files.writeString(captureFile, """
                {"timestamp":"2026-06-28T10:10:00Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-1","method":"POST","path":"/ok","responseStatus":200,"durationMs":1,"runOutcome":"ok"}
                """);

        final HttpTrafficPanelQuery.HttpTrafficRow row = new HttpTrafficPanelQuery().read(tempDir, 10).getFirst();

        assertThat(row.captureKind()).isEqualTo("handled-run");
    }

    @Test
    void shouldReturnNewestRowsWithinLimit() throws Exception {
        writeCapture(
                "http-run-1",
                """
                {"timestamp":"2026-06-28T10:10:00Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-1","method":"POST","path":"/older","responseStatus":200,"durationMs":1,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );
        writeCapture(
                "http-run-2",
                """
                {"timestamp":"2026-06-28T10:10:02Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-2","method":"POST","path":"/newest","responseStatus":200,"durationMs":1,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );
        writeCapture(
                "http-run-3",
                """
                {"timestamp":"2026-06-28T10:10:01Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-3","method":"POST","path":"/middle","responseStatus":200,"durationMs":1,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );

        assertThat(new HttpTrafficPanelQuery().read(tempDir, 2))
                .extracting(HttpTrafficPanelQuery.HttpTrafficRow::runId)
                .containsExactly("http-run-2", "http-run-3");
    }

    private void writeCapture(final String runId, final String json) throws Exception {
        final Path captureFile = tempDir
                .resolve("railix-app")
                .resolve("http-flow")
                .resolve(runId)
                .resolve(HttpCaptureArtifact.FILE_NAME);
        Files.createDirectories(captureFile.getParent());
        Files.writeString(captureFile, json);
    }
}
