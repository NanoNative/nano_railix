# 🚧 WIP — EPIC-01: Global Config (compiled + live updates)

> **Priority:** P0

## Outcome

Railix provides a compiled, concurrent `globalConfig()` (TypeMap) similar to Nano’s config compilation:
- directory scanning + profile cascade
- overlay precedence (env / `-D` / args / programmatic)
- `${key}` / `${key:fallback}` placeholder resolution
- live `updateConfig(...)` with a diff delivered to actors

## What exists already

- ✅ `org.nanonative.railix.config.Config#load()` (basic env/sysprops/classpath/external file).

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-001: Current `Config.load()` baseline](./DONE-TICKET-001-cfg-load-baseline.md) | ✅ DONE | document what exists + gaps vs Nano |
| [TICKET-002: Config compilation (profiles + directories)](./TODO-TICKET-002-config-compilation-profiles.md) | ⏳ TODO | Nano-like `readConfigFiles` behavior |
| [TICKET-003: Placeholder resolution](./TODO-TICKET-003-placeholders.md) | ⏳ TODO | `${key:fallback}` + cycle protection |
| [TICKET-004: Live config updates + diff](./TODO-TICKET-004-live-update-diff.md) | ⏳ TODO | `updateConfig` + diff model |
| [TICKET-005: `globalConfig()` API surface](./TODO-TICKET-005-globalconfig-api.md) | ⏳ TODO | how rails access compiled config |
| [TICKET-006: RailConfig overrides → `railConfig()`](./TODO-TICKET-006-railconfig-overrides-map.md) | ⏳ TODO | per-rail overrides merge into effective settings |
| [TICKET-007: Config ingestion strictness flag](./TODO-TICKET-007-config-ingest-strictness.md) | ⏳ TODO | default ingest only `railix_*`, optional ingest-all |
