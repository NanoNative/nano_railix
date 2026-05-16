# ⏳ TODO — TICKET-003: Placeholder resolution (`${key:fallback}`)

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Resolve placeholders in the compiled `globalConfig()`:
- `${some_key}` resolves from config
- `${some_key:fallback}` resolves to fallback when missing
- prevent cycles and infinite recursion (depth limit)

## Notes

Nano uses a depth limit and a `seen` set; copy that behavior.

