# ADR 0017: Compiled Execution And Explicit Results

## Status

Superseded on 2026-07-29 by [ADR 0020](0020-creator-first-application-graph.md).

## Disposition

This ADR described the deleted `FlowExecutor` and `CompiledFlow` runtime. Current execution is
generated Java with one synchronous integer-routed production path; ADR 0020 and
[ROADMAP.md](../ROADMAP.md) own its contract and evidence. No alternate executor is retained.
