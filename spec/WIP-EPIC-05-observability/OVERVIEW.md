# 🚧 WIP — EPIC-05: Observability (metrics + logging)

> **Priority:** P0

## Outcome

Railix observability is:
- configurable via `globalConfig()`
- updateable on the fly
- supports multiple metric formats

## What exists already

- ✅ `Metrics.toPrometheus()`
- ✅ `LogCfg.withConsumer(...)` bridge + `RailixLogger` formats

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-040: Prometheus export baseline](./DONE-TICKET-040-prometheus-export.md) | ✅ DONE | existing metrics registry + export |
| [TICKET-041: Multi-format metrics](./TODO-TICKET-041-multi-format-metrics.md) | ⏳ TODO | Influx/Dynatrace/Wavefront |
| [TICKET-042: Log consumer baseline](./DONE-TICKET-042-log-consumer.md) | ✅ DONE | existing consumer integration |
| [TICKET-043: Dynamic logging config](./TODO-TICKET-043-dynamic-logging-config.md) | ⏳ TODO | live log level/format updates |
| [TICKET-044: Metrics endpoints](./TODO-TICKET-044-metrics-endpoints.md) | ⏳ TODO | expose via HTTP receiver actor if present |
| [TICKET-045: Metric cardinality limits](./TODO-TICKET-045-metric-cardinality-limits.md) | ⏳ TODO | allowlists + hard limits to prevent unbounded series |
