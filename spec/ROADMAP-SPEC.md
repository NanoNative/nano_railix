# Railix Roadmap Spec (Tiny Nano, no events)

> **Status:** 🚧 WIP · **Updated:** 2026-03-31

Railix is a **source-bound pipeline** library.
This roadmap adds Nano-like runtime capabilities (config + actors + live ops) without introducing an event bus and
without changing the core model: `Rail.of(sourceActor)` plus a readable stream-like DSL.

## Goals

- Provide a compiled, live-updateable `globalConfig()` (`TypeMap`) similar to Nano's `readConfigs()` + profile cascade.
- Provide a runtime with ordered actors and graceful shutdown (stop-accepting -> drain -> stop).
- Provide default source actors (HTTP/CLI/file/socket/queue) that feed rails instead of introducing lifecycle modes.
- Provide issues + pause/rate-limit primitives for resilience and live ops.
- Provide zero/low-downtime sibling-version handover for deployed Railix apps.
- Provide a Railix-native build DSL that can package apps without making users hand-assemble build toolchains.
- Provide a wrapper-style Railix Build entrypoint that can bootstrap builds on fresh machines.
- Provide Mermaid diagram export from the same app/rail definition where structure is nameable.
- Provide an app score/rating system that turns quality signals into an obvious feedback loop.

## Non-Goals

- No global pub/sub event bus.
- No reflection, no parallel streams, no DI container.
- No `script()/server()/worker()` taxonomy in core.

## Current State

- ✅ Core rail DSL exists and is being reshaped toward `Rail.of(sourceActor)`.
- ✅ Logging exists via `RailixLogger` + `LogCfg.withConsumer(...)`.
- ✅ Metrics exist via `Metrics.toPrometheus()` and execution counters/timers.
- ✅ GraalVM sample app exists (`src/test/resources/sample-app`, Maven profile `native`).
- 🚧 Config loading exists via `Config.load()`, but profile cascade, directory scanning, CLI decoding, and placeholder
  resolution still need work.
- ⏳ Self-update/handover is not designed yet.
- ⏳ Build DSL / packaging/bootstrap pipeline is not designed yet.
- ⏳ App scoring exists only as an idea.

## EPICs

| Epic | Status | Priority | Notes |
|------|--------|----------|-------|
| [EPIC-01: Global Config](./WIP-EPIC-01-global-config/) | 🚧 WIP | P0 | `Config.load()` exists; needs Nano-like compilation + live updates |
| [EPIC-02: Runtime Lifecycle](./TODO-EPIC-02-runtime-lifecycle/) | ⏳ TODO | P0 | shutdown, drain, await/keepalive, exit-code policy |
| [EPIC-03: Actors / Registry](./TODO-EPIC-03-actors/) | ⏳ TODO | P0 | ordered start/stop, phased shutdown, typed access |
| [EPIC-04: Default Actors](./TODO-EPIC-04-default-actors/) | ⏳ TODO | P1 | HTTP/CLI/file watcher/source actors |
| [EPIC-05: Observability](./WIP-EPIC-05-observability/) | 🚧 WIP | P0 | Prometheus + logging bridge exist; needs richer telemetry |
| [EPIC-06: Issues + Pause](./TODO-EPIC-06-issues/) | ⏳ TODO | P1 | issue registry, pause-on-issues, issue metrics |
| [EPIC-07: Flow Control](./TODO-EPIC-07-flow-control/) | ⏳ TODO | P2 | sleep/pause, keyed rate limiting |
| [EPIC-08: Self Update / Version Handover](./TODO-EPIC-08-self-update/) | ⏳ TODO | P1 | `v1` -> `v2` handover, drain, promote, rollback |
| [EPIC-09: Build DSL / Packaging](./TODO-EPIC-09-build-dsl/) | ⏳ TODO | P1 | declarative app build, wrapper bootstrap, toolchain acquisition, packaging, Mermaid export |
| [EPIC-10: App Score / Gamification](./TODO-EPIC-10-app-score/) | ⏳ TODO | P2 | quality scoring, scanners, score export |

## Critical Path

`EPIC-01 (config)` -> `EPIC-03 (actors)` -> `EPIC-02 (runtime shutdown/drain)` -> `EPIC-04 (default actors)` -> `EPIC-08 (self-update)`

`EPIC-09 (build DSL)` can start once runtime/config decisions stop moving every week.
`EPIC-10 (app score)` depends mostly on build outputs and telemetry, so it can follow later.

## Locked Decisions

- `Rail.of(sourceActor)` is the one public ingress for external runtime data.
- `fire(...)` stays supported for tests, manual execution, custom ingress, and sealed reusable rails.
- Rail metadata is descriptive; payload data is not stored as fake metadata.
- `TypeMap` stays central for config, payload/context access, and persistence-friendly conversion.
- `TypeMap` owns path and mutation semantics; Railix does not add custom dotted-path parsing.
- Actors are global and accessed through runtime/app context, not persisted inside rail state.
- Actors may be plain user classes or structured actors with lifecycle hooks.
- Actors manage their own pools/resources.
- Reuse stays on `Rail` via `seal()` and `step(otherRailDef)`; no separate public fragment type.
- Per-step timing/history belongs to telemetry by default, not to a noisy public `stepHistory()` API.
- Config keys stay canonical and sanitized.
- Entry adapters decide whether the app is HTTP, CLI, socket, MQTT, scheduler, or something else.
- Self-update must stay boring: hand over new ingress, drain old rails, then stop the old version.
- The build DSL should be able to emit Maven-compatible project metadata for IDE/tooling.
- The build DSL should support a wrapper-style bootstrap entrypoint for fresh machines.
- The build DSL should be able to export Mermaid diagrams when the app definition contains enough named structure.
- Toolchain acquisition should prefer local detection first and fetch only when needed.
- Private dependency repositories need a project-local solution; a mandatory global `settings.xml` is not the target UX.
- App scoring should combine multiple quality signals instead of only coverage.

## Open Questions

- For self-update, does the old version forward traffic to the new one, or do source actors rebind directly?
- How are sibling versions discovered and trusted locally?
- Is Maven the canonical backend for the build DSL, or just one generated target?
- Does the wrapper generate `pom.xml` first and delegate to Maven-compatible backends, or can it build directly?
- Where should downloaded JDK/GraalVM toolchains live and how are they cached safely?
- How rich can Mermaid export be for anonymous versus named pipeline parts?
- Which score dimensions are core and which are optional plugins later on?
