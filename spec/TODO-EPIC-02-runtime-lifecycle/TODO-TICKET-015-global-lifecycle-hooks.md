# ⏳ TODO — TICKET-015: Global lifecycle hooks

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Allow users to register global hooks:
- `onStart(Consumer<Rail>)`
- `onStop(Consumer<Rail>)`

Hooks receive a rail so they can read config/actors and report issues/errors consistently.
