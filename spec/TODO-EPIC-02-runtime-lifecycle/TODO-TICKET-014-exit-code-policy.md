# ⏳ TODO — TICKET-014: Exit code policy

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Define exit-code semantics for graceful shutdown.

## Options

- Default: **never** call `System.exit`; return an exit code from `await()` / `shutdown(...)`.
- Opt-in: `runtime.awaitAndExit()` or config `railix_exit_on_shutdown=true`.

