package org.nanonative.railix.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class MetricsTest {

    @Test
    void toPrometheus_withEmptyRegistry_shouldReturnEmptyString() {
        assertThat(new Metrics().toPrometheus()).isEmpty();
    }

    @Test
    void toPrometheus_withCounterHistogramAndGauge_shouldExportAllTypes() {
        final Metrics metrics = new Metrics();

        metrics.incrementCounter("rail_requests_total", Map.of("route", "/users", "method", "GET"));
        metrics.incrementCounter("rail_requests_total", Map.of("method", "GET", "route", "/users"));
        metrics.incrementCounter("rail_failures_total", Map.of());
        metrics.recordHistogram("rail_duration_ms", 10, Map.of("route", "/users"));
        metrics.recordHistogram("rail_duration_ms", 30, Map.of("route", "/users"));
        metrics.setGauge("rail_queue_depth", 7.5d, Map.of("lane", "default"));

        final String prometheus = metrics.toPrometheus();

        assertThat(prometheus).contains(
            "# TYPE rail_requests_total counter",
            "rail_requests_total{",
            "method=\"GET\"",
            "route=\"/users\"",
            "} 2",
            "rail_failures_total 1",
            "# TYPE rail_duration_ms histogram",
            "rail_duration_ms_sum{route=\"/users\"} 40",
            "rail_duration_ms_count{route=\"/users\"} 2",
            "# TYPE rail_queue_depth gauge",
            "rail_queue_depth{lane=\"default\"} 7.5"
        );
    }

    @Test
    void incrementCounter_withNullTags_shouldThrowPredictably() {
        final Metrics metrics = new Metrics();

        assertThatThrownBy(() -> metrics.incrementCounter("x", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.recordHistogram("x", 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.setGauge("x", 1.0d, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toPrometheus_withMetricNamesContainingColon_shouldHandleEmptyAndMalformedTagSectionsPredictably() {
        final Metrics metrics = new Metrics();

        metrics.incrementCounter("rail_metric:", Map.of());
        metrics.incrementCounter("rail_metric:broken", Map.of());

        final String prometheus = metrics.toPrometheus();

        assertThat(prometheus).contains("# TYPE rail_metric counter");
        assertThat(prometheus.lines().filter(line -> line.equals("rail_metric 1")).count()).isEqualTo(2);
    }

    @Test
    void toPrometheus_withHistogramOnlyAndGaugeOnly_shouldStillEmitSectionsWithoutLeadingCounterBlock() {
        final Metrics histogramOnly = new Metrics();
        histogramOnly.recordHistogram("rail_duration_ms", 12, Map.of("route", "/users"));

        final Metrics gaugeOnly = new Metrics();
        gaugeOnly.setGauge("rail_queue_depth", 3.0d, Map.of("lane", "fast"));

        assertThat(histogramOnly.toPrometheus()).contains(
            "# TYPE rail_duration_ms histogram",
            "rail_duration_ms_sum{route=\"/users\"} 12",
            "rail_duration_ms_count{route=\"/users\"} 1");
        assertThat(gaugeOnly.toPrometheus()).contains(
            "# TYPE rail_queue_depth gauge",
            "rail_queue_depth{lane=\"fast\"} 3.0");
    }
}
