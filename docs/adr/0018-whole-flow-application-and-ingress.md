# ADR 0018: Whole-Flow Application And Explicit Ingress

## Status

Superseded on 2026-07-29 by [ADR 0020](0020-creator-first-application-graph.md).

## Disposition

This ADR described deleted archive mutation, `railix.flow.json`, manual Build/Start APIs, and a
different application lifecycle. Current Creator behavior automatically builds and replaces one
real generated development application. The [system model](../specs/system-model.md)
owns ingress and Creator/compiler boundaries, with rationale in ADR 0020;
ADR 0013 owns Creator packaging and planned per-project distribution.
