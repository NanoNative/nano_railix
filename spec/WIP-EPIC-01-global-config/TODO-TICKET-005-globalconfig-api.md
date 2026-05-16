# ⏳ TODO — TICKET-005: `globalConfig()` API surface

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Expose compiled config to users:
- `myRail.config()` returns the concurrent TypeMap with all compiled configs.
- `Rail.config()` is the same TypeMap (singleton runtime).
- `Rail.globalConfig()` is an alias for the same TypeMap (static access; callable via instance as well).

## Notes

This implies a default/singleton runtime. Multiple runtimes (with isolated config) can be a later idea if needed.
