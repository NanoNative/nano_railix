# ⏳ TODO — TICKET-012: Shutdown hook

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Install a JVM shutdown hook that triggers graceful shutdown.

## Open question

Should shutdown hook call `System.exit` (no, unless opted in) or just stop actors and return?
