# ADR 0017: Compiled Execution And Explicit Results

## Status

Superseded on 2026-07-29 by [ADR 0020](0020-creator-first-application-graph.md).

## Disposition

This ADR described the deleted `FlowExecutor` and `CompiledFlow` runtime. Current execution is
generated Java with one synchronous integer-routed production path. The
[system model](../specs/system-model.md) and [observation spec](../specs/observation.md)
own its contract; [verification history](../verification.md) records earlier proof.
ADR 0020 records the decision. No alternate executor is retained.
