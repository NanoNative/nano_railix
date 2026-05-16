# ⏳ TODO — EPIC-08: Self Update / Version Handover

> **Priority:** P1

## Outcome

A deployed Railix app can hand traffic from an older sibling version to a newer sibling version with minimal or zero manual
cutover.

Target shape:
- `rail_app_v1` is running
- `rail_app_v2` is deployed next to it
- `rail_app_v1` detects or is told about `rail_app_v2`
- new ingress is handed over or forwarded to `rail_app_v2`
- `rail_app_v1` drains in-flight rails
- once handover is stable, `rail_app_v1` stops itself

## Why

This is the runtime foundation for boring upgrades without requiring a heavy orchestrator for every case.
It also prepares Railix for multi-version coexistence and future marketplace/runtime management.

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-080: Successor discovery + handover contract](./TODO-TICKET-080-successor-discovery.md) | ⏳ TODO | define how `v1` finds and trusts `v2` |
| [TICKET-081: Source actor handover / forwarding](./TODO-TICKET-081-source-handover.md) | ⏳ TODO | how ingress moves from old to new instance |
| [TICKET-082: Drain, promote, rollback](./TODO-TICKET-082-drain-promote-rollback.md) | ⏳ TODO | drain active rails, confirm health, roll back on failed handover |

## Open Questions

- Should `v1` forward traffic to `v2`, or should the source actor rebind directly to `v2`?
- How do we prevent forwarding loops between sibling versions?
- How is a successor identified: folder naming, manifest metadata, port file, unix socket, registry entry?
- What is the rollback rule if `v2` starts but never becomes healthy?
