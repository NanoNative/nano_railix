# ⏳ TODO — TICKET-007: Config ingestion strictness flag

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Add a config switch controlling whether the compiler ingests only Railix-owned keys (`railix_*`) or all keys.

## Default behavior (locked)

- Default: ingest only keys that normalize to `railix_*`.
- Optional: ingest all keys (useful when adopting existing apps/libraries without renaming config keys).

## Proposed config key

- `railix_config_ingest_all=false` (default false)
  - `false` → only `railix_*` keys are ingested
  - `true` → ingest all normalized keys

## Notes

Even in strict mode, the compiler may need to *read* non-railix keys internally for compatibility (e.g. profile discovery),
but should not persist them into `globalConfig()` unless ingest-all is enabled.

