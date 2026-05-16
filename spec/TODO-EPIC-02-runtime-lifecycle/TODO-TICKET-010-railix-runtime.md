# ⏳ TODO — TICKET-010: Introduce `RailixRuntime`

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Add a concrete runtime container that owns:
- compiled `globalConfig()` (concurrent TypeMap)
- actors registry (ordered)
- issues registry
- metrics + logging configuration
- active-rails tracking (drain)

## API sketch (draft)

- `RailixRuntime.builder()...build()`
- `runtime.newRail()` → returns `Rail` bound to this runtime
