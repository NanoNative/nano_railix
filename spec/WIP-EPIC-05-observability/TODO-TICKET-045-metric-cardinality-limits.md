# ⏳ TODO — TICKET-045: Metric cardinality limits

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Prevent unbounded metric time-series growth (memory/time leak) by introducing explicit controls:
- allowlists for which tags/keys may be emitted
- hard caps for max unique series per metric (and/or global cap)
- clear behavior on overflow (drop, coalesce, or replace with `_overflow`)

## Why

Every unique `(metric name + tag set)` creates a new entry in the in-memory registry and (in Prometheus) a new time
series. If issue keys, URLs, user ids, etc. become tags, cardinality explodes.

## Scope ideas

- `railix_metrics_cardinality_max_series` (global)
- `railix_metrics_cardinality_max_series_per_metric`
- `railix_metrics_tags_allowlist` (comma-separated)
- issue metrics knobs:
  - `railix_metrics_issues_emit_per_key` (default false)
  - `railix_metrics_issues_max_keys`

