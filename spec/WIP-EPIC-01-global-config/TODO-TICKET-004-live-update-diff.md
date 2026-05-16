# ⏳ TODO — TICKET-004: Live config updates + diff

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Provide `updateConfig(...)` at runtime level to:
- merge changes into `globalConfig()`
- compute a diff of changed keys
- notify actors via an optional callback

## Diff model (locked)

- Always deliver **keys with values**: `Map<String, Object>` / TypeMap containing the changed keys and their **new**
  values (no “keys-only”, no old/new tuple).
- A key removal is represented as a tombstone in the diff: `key → null`.
