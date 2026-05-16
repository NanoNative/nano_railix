# ⏳ TODO — TICKET-006: `RailConfig` overrides → `railConfig()` TypeMap

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Make `RailConfig` act as an *optional per-rail overrides builder* that produces an **immutable Java `Map`** of settings.

`Rail` exposes those overrides as a TypeMap via `myRail.railConfig()` (rail-only settings, not compiled env/args/props).

The builder must also support user-defined custom `k/v` so app code can attach per-rail settings without going through
`globalConfig()`.

Global compiled configuration lives in `myRail.config()` (aka `globalConfig()` alias), not in `RailConfig`.

## Rationale

You want:
- global compiled config (env/props/args/files) in `globalConfig()`
- per-rail overrides that are explicit and do not pretend to be “the whole config”

## API expectations (from you)

- `Rail.globalConfig()` and `myRail.globalConfig()` point to the same compiled config.
- `myRail.railConfig()` exists and is a TypeMap for per-rail overrides.

## Open question

How do we represent “unset” vs “explicitly set to default” for fields like log level?
