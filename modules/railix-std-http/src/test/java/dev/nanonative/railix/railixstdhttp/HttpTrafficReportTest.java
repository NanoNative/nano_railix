package dev.nanonative.railix.railixstdhttp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTrafficReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSummarizeHandledRejectedAndBoundaryFailureRows() throws Exception {
        writeCapture(
                tempDir.resolve("railix-app/http-flow/http-run-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"2026-06-28T10:12:00Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-1","method":"POST","path":"/ok","responseStatus":201,"durationMs":3,"runOutcome":"ok","captureKind":"handled-run"}
                """
        );
        writeCapture(
                tempDir.resolve("railix-app/http-flow/_http-rejections/http-rejected-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"2026-06-28T10:12:01Z","appId":"railix-app","flowId":"http-flow","runId":"http-rejected-1","method":"POST","path":"/bad","responseStatus":400,"durationMs":1,"runOutcome":"rejected","captureKind":"boundary-rejection"}
                """
        );
        writeCapture(
                tempDir.resolve("railix-app/http-flow/_http-failures/http-failed-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"2026-06-28T10:12:02Z","appId":"railix-app","flowId":"http-flow","runId":"http-failed-1","method":"POST","path":"/boom","responseStatus":500,"durationMs":2,"runOutcome":"failed","captureKind":"boundary-failure"}
                """
        );

        final HttpTrafficReport.Report report = new HttpTrafficReport().read(tempDir, 10);
        final String json = new HttpTrafficReport().toStableJson(tempDir, 10);

        assertThat(report.totalRows()).isEqualTo(3);
        assertThat(report.handledRows()).isEqualTo(1);
        assertThat(report.rejectedRows()).isEqualTo(1);
        assertThat(report.failedRows()).isEqualTo(1);
        assertThat(report.statusCounts()).containsEntry(201, 1L).containsEntry(400, 1L).containsEntry(500, 1L);
        assertThat(json)
                .contains("\"handledRows\":1")
                .contains("\"rejectedRows\":1")
                .contains("\"failedRows\":1")
                .contains("\"captureKind\":\"boundary-failure\"");
    }

    @Test
    void shouldFailClearlyOnUnknownCaptureKind() throws Exception {
        writeCapture(
                tempDir.resolve("railix-app/http-flow/http-run-1/" + HttpCaptureArtifact.FILE_NAME),
                """
                {"timestamp":"2026-06-28T10:12:00Z","appId":"railix-app","flowId":"http-flow","runId":"http-run-1","method":"POST","path":"/odd","responseStatus":200,"durationMs":1,"runOutcome":"ok","captureKind":"mystery-kind"}
                """
        );

        assertThatThrownBy(() -> new HttpTrafficReport().read(tempDir, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown HTTP capture kind: mystery-kind");
    }

    private static void writeCapture(final Path path, final String json) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }
}
