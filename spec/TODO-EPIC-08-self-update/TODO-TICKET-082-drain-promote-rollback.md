# ⏳ TODO — TICKET-082: Drain, promote, rollback

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

After ingress handover starts:
- old instance stops accepting new work
- old instance drains active rails
- new instance is promoted only after readiness is stable
- failed promotion rolls back cleanly

## Open Questions

- what timeout or health threshold promotes `v2`?
- what state must survive rollback?
- how much of this can be generic runtime logic versus source-actor specific logic?
