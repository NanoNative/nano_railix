# ⏳ TODO — TICKET-020: Actor contract

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Allow **any user-defined object** to be registered as an actor.

- If the object implements lifecycle/config hook interfaces, Railix calls them.
- If it implements nothing, it’s still a valid actor: it’s just globally reachable.

No reflection. Hook detection is `instanceof`-based.

## Minimal structured hooks (draft)

- `start(Rail rail)` (startup phase)
- `stopAccepting(Rail rail)` (shutdown phase 1: stop taking new work)
- `stop(Rail rail)` (shutdown phase 2: final stop; called after drain)
- `onConfigChange(Rail rail)` (receives a config diff payload TBD)

## Key requirement

Actors must be able to read `globalConfig()` and report errors/issues via the rail/runtime.
