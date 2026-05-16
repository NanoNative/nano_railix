# ⏳ TODO — TICKET-013: Keepalive / await API

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Railix should not keep a thread alive by default.

Add:
- `runtime.await()` to block until shutdown is triggered
- `runtime.keepAlive()` as an explicit non-daemon blocker (if needed)

