# ⏳ TODO — EPIC-03: Actors / Registry

> **Priority:** P0

## Outcome

Users can define global actors (Mongo, HTTP server, file watcher, etc.) with:
- ordered startup
- reversed shutdown
- phased stop (stop-accepting vs stop-late)
- config-change callback
- typed actor access via `myRail.actors()`

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-020: Actor contract](./TODO-TICKET-020-actor-contract.md) | ⏳ TODO | plain objects + optional lifecycle hooks (no reflection) |
| [TICKET-021: Actor registry + ordering](./TODO-TICKET-021-actor-registry-ordering.md) | ⏳ TODO | priorities + reversed shutdown |
| [TICKET-022: Typed access via `actors()`](./TODO-TICKET-022-typed-actor-access.md) | ⏳ TODO | `myRail.actors().mongo()` style |
| [TICKET-023: Config-change notifications](./TODO-TICKET-023-config-change-notifications.md) | ⏳ TODO | diff delivery rules |
| [TICKET-024: Actor verification](./TODO-TICKET-024-actor-verification.md) | ⏳ TODO | required actors + fallback behavior |
