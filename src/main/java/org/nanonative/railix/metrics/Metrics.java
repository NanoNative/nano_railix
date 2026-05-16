package org.nanonative.railix.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Generic metrics registry with Prometheus export support.
 * Metrics are auto-created on first use via computeIfAbsent.
 * Thread-safe using ConcurrentHashMap and LongAdder.
 */
public class Metrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HistogramData> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GaugeData> gauges = new ConcurrentHashMap<>();

    public void incrementCounter(String name, Map<String, String> tags) {
        String key = metricKey(name, tags);
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public void recordHistogram(String name, long value, Map<String, String> tags) {
        String key = metricKey(name, tags);
        histograms.computeIfAbsent(key, k -> new HistogramData(name, tags)).record(value);
    }

    public void setGauge(String name, double value, Map<String, String> tags) {
        String key = metricKey(name, tags);
        gauges.computeIfAbsent(key, k -> new GaugeData(name, tags)).set(value);
    }

    /**
     * Export metrics in Prometheus text format.
     *
     * @return Prometheus-formatted metrics
     */
    public String toPrometheus() {
        StringBuilder sb = new StringBuilder();

        // Counters
        Map<String, List<Map.Entry<String, LongAdder>>> countersByName =
            counters.entrySet().stream()
                .collect(Collectors.groupingBy(e -> extractName(e.getKey())));

        for (Map.Entry<String, List<Map.Entry<String, LongAdder>>> entry : countersByName.entrySet()) {
            String name = entry.getKey();
            sb.append("# TYPE ").append(name).append(" counter\n");
            for (Map.Entry<String, LongAdder> counter : entry.getValue()) {
                sb.append(name).append(formatTags(extractTags(counter.getKey())))
                    .append(" ").append(counter.getValue().sum()).append("\n");
            }
        }

        // Histograms
        Map<String, List<Map.Entry<String, HistogramData>>> histogramsByName =
            histograms.entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getValue().name));

        for (Map.Entry<String, List<Map.Entry<String, HistogramData>>> entry : histogramsByName.entrySet()) {
            String name = entry.getKey();
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("# TYPE ").append(name).append(" histogram\n");
            for (Map.Entry<String, HistogramData> hist : entry.getValue()) {
                HistogramData data = hist.getValue();
                sb.append(name).append("_sum").append(formatTags(data.tags))
                    .append(" ").append(data.sum.sum()).append("\n");
                sb.append(name).append("_count").append(formatTags(data.tags))
                    .append(" ").append(data.count.sum()).append("\n");
            }
        }

        // Gauges
        Map<String, List<Map.Entry<String, GaugeData>>> gaugesByName =
            gauges.entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getValue().name));

        for (Map.Entry<String, List<Map.Entry<String, GaugeData>>> entry : gaugesByName.entrySet()) {
            String name = entry.getKey();
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("# TYPE ").append(name).append(" gauge\n");
            for (Map.Entry<String, GaugeData> gauge : entry.getValue()) {
                GaugeData data = gauge.getValue();
                sb.append(name).append(formatTags(data.tags))
                    .append(" ").append(data.value).append("\n");
            }
        }

        return sb.toString();
    }

    private String metricKey(String name, Map<String, String> tags) {
        String tagStr = tags.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(","));
        return name + (tagStr.isEmpty() ? "" : ":" + tagStr);
    }

    private String extractName(String key) {
        int idx = key.indexOf(':');
        return idx < 0 ? key : key.substring(0, idx);
    }

    private Map<String, String> extractTags(String key) {
        int idx = key.indexOf(':');
        if (idx < 0) return Map.of();

        String tagStr = key.substring(idx + 1);
        if (tagStr.isEmpty()) return Map.of();

        Map<String, String> tags = new HashMap<>();
        for (String pair : tagStr.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                tags.put(kv[0], kv[1]);
            }
        }
        return tags;
    }

    private String formatTags(Map<String, String> tags) {
        if (tags.isEmpty()) return "";
        return "{" + tags.entrySet().stream()
            .map(e -> e.getKey() + "=\"" + e.getValue() + "\"")
            .collect(Collectors.joining(",")) + "}";
    }

    private static class HistogramData {
        final String name;
        final Map<String, String> tags;
        final LongAdder sum = new LongAdder();
        final LongAdder count = new LongAdder();

        HistogramData(String name, Map<String, String> tags) {
            this.name = name;
            this.tags = tags;
        }

        void record(long value) {
            sum.add(value);
            count.increment();
        }
    }

    private static class GaugeData {
        final String name;
        final Map<String, String> tags;
        volatile double value;

        GaugeData(String name, Map<String, String> tags) {
            this.name = name;
            this.tags = tags;
        }

        void set(double value) {
            this.value = value;
        }
    }
}
