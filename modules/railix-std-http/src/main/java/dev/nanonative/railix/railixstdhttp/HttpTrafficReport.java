package dev.nanonative.railix.railixstdhttp;

import dev.nanonative.railix.kernel.model.KernelContractCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HttpTrafficReport {

    private final HttpTrafficPanelQuery query;

    public HttpTrafficReport() {
        this(new HttpTrafficPanelQuery());
    }

    HttpTrafficReport(final HttpTrafficPanelQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public Report read(final java.nio.file.Path runsRoot, final int limit) {
        final List<HttpTrafficPanelQuery.HttpTrafficRow> rows = query.read(runsRoot, limit);
        long handled = 0L;
        long rejected = 0L;
        long failed = 0L;
        final Map<Integer, Long> statusCounts = new LinkedHashMap<>();
        for (final HttpTrafficPanelQuery.HttpTrafficRow row : rows) {
            statusCounts.merge(row.status(), 1L, Long::sum);
            switch (row.captureKind()) {
                case HttpCaptureArtifact.CAPTURE_KIND_HANDLED_RUN -> handled++;
                case HttpCaptureArtifact.CAPTURE_KIND_BOUNDARY_REJECTION -> rejected++;
                case HttpCaptureArtifact.CAPTURE_KIND_BOUNDARY_FAILURE -> failed++;
                default -> throw new IllegalArgumentException("Unknown HTTP capture kind: " + row.captureKind());
            }
        }
        return new Report(rows.size(), handled, rejected, failed, Map.copyOf(statusCounts), rows);
    }

    public String toStableJson(final java.nio.file.Path runsRoot, final int limit) {
        final Report report = read(runsRoot, limit);
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("totalRows", report.totalRows());
        raw.put("handledRows", report.handledRows());
        raw.put("rejectedRows", report.rejectedRows());
        raw.put("failedRows", report.failedRows());
        raw.put("statusCounts", report.statusCounts());
        raw.put("rows", report.rows().stream().map(HttpTrafficReport::rawRow).toList());
        return KernelContractCodec.toStableJson(raw);
    }

    private static Map<String, Object> rawRow(final HttpTrafficPanelQuery.HttpTrafficRow row) {
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("timestamp", row.timestamp().toString());
        raw.put("appId", row.appId());
        raw.put("flowId", row.flowId());
        raw.put("runId", row.runId());
        raw.put("method", row.method());
        raw.put("path", row.path());
        raw.put("status", row.status());
        raw.put("durationMs", row.durationMs());
        raw.put("outcome", row.outcome());
        raw.put("captureKind", row.captureKind());
        return raw;
    }

    public record Report(
            int totalRows,
            long handledRows,
            long rejectedRows,
            long failedRows,
            Map<Integer, Long> statusCounts,
            List<HttpTrafficPanelQuery.HttpTrafficRow> rows
    ) {
        public Report {
            if (totalRows < 0) {
                throw new IllegalArgumentException("totalRows must be >= 0");
            }
            if (handledRows < 0) {
                throw new IllegalArgumentException("handledRows must be >= 0");
            }
            if (rejectedRows < 0) {
                throw new IllegalArgumentException("rejectedRows must be >= 0");
            }
            if (failedRows < 0) {
                throw new IllegalArgumentException("failedRows must be >= 0");
            }
            statusCounts = Map.copyOf(statusCounts);
            rows = List.copyOf(rows);
        }
    }
}
