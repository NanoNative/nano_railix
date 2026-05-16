# ⏳ TODO — EPIC-02: Runtime Lifecycle (await/keepalive/shutdown/drain)

> **Priority:** P0

## Outcome

Railix can run “framework-like” when actors are present, but remains non-blocking by default:
- no keepalive thread unless requested
- graceful shutdown: stop accepting → drain rails → stop actors
- shutdown hook installs automatically
- exit code policy is explicit (no surprise `System.exit` unless configured)

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-010: Introduce `RailixRuntime`](./TODO-TICKET-010-railix-runtime.md) | ⏳ TODO | global state owner (config/actors/issues/metrics) |
| [TICKET-011: Phased shutdown + drain](./TODO-TICKET-011-phased-shutdown-drain.md) | ⏳ TODO | stop-accepting → drain → stop |
| [TICKET-012: Shutdown hook](./TODO-TICKET-012-shutdown-hook.md) | ⏳ TODO | install hook; controlled behavior |
| [TICKET-013: Keepalive / await API](./TODO-TICKET-013-keepalive-await.md) | ⏳ TODO | block only when requested |
| [TICKET-014: Exit code policy](./TODO-TICKET-014-exit-code-policy.md) | ⏳ TODO | decide `System.exit` vs return code |
| [TICKET-015: Global lifecycle hooks](./TODO-TICKET-015-global-lifecycle-hooks.md) | ⏳ TODO | `onStart`/`onStop` consumers |
