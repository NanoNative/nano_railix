# ⏳ TODO — TICKET-023: Config-change notifications

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

When `updateConfig(...)` is called:
- compute a diff
- notify actors in deterministic order (define which order)
- deliver the diff to actors via a `Rail` (payload/meta/ctx TBD)

## Diff shape (locked)

- Diff is **keys with values** (changed keys → new values), not keys-only.
- Removals are represented as tombstones (`key → null`) in the diff.
- Delivery mechanism: via the `Rail` passed to the hook (likely a dedicated meta key like `_cfgDiff` or a dedicated
  diff record exposed by `rail.runtime().configDiff()`).
