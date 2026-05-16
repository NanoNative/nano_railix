# ROADMAP-SPEC (ARCHIVED DRAFT): Railix “Tiny Nano” (Config + Services + Connectors + Live Ops)

> **Status:** 🚧 DRAFT (needs decisions)
>
> This file turns “Nano can do X” gaps into **EPICs + tickets** for Railix.
> Goal: keep Railix a **pipeline library** (no event bus), but add the minimum runtime pieces to build microservice-ish
> apps (config, services/connectors, shutdown, live config, issues, rate limiting).

## 0) What Railix Has Today (and What It Doesn’t)

### ✅ Railix today
- `Rail` is a deterministic deque-based pipeline with `meta()` (`TypeMapI<?>`) and terminal `Result`.
- `RailConfig` is immutable runtime config (executor/logging/metrics hooks).
- `Cfg.load()` exists, but it is **not** a compiled “app config” attached to a rail runtime.
- `Metrics` exports **Prometheus** only.

### ❌ Missing vs Nano (high level)
- Compiled configuration map like Nano’s `readConfigs()` + `readConfigFiles()` pipeline.
- A runtime container: services/connectors lifecycle, shutdown orchestration, keepalive, drain semantics.
- Built-in connectors (HTTP receiver/sender, file watcher, CLI receiver/executor).
- Live config updates + propagation to services.
- Global issues/health registry + “pause until resolved”.
- Rate limiting primitives.
- Multiple metric formats.

## 1) Nano Config Baseline (What We’re Copying)

Nano’s config “compilation” is basically:
1. Load defaults + profile cascade from `application.properties` and `application-<profile>.properties` across a list of
   directories.
2. Overlay ENV + `-D` + CLI args (and a config map passed at runtime).
3. Re-run profile cascade in case overlays enabled new profiles.
4. Resolve `${key}` / `${key:fallback}` placeholders.

**Reference implementation in Nano:**
- `/Users/yuna/projects/nano/src/main/java/org/nanonative/nano/core/NanoBase.java` → `readConfigs(...)`
- `/Users/yuna/projects/nano/src/main/java/org/nanonative/nano/helper/NanoUtils.java` → `readConfigFiles(...)`,
  `addConfig(...)`, `resolvePlaceHolders(...)`

**Railix today:** `org.nanonative.railix.cfg.Cfg#load()` is a much smaller subset:
- classpath `railix.properties`
- optional external `.properties` via `RAILIX_CONFIG_FILE` / `-Drailix_config_file`
- `RAILIX_*` ENV + `railix.*`/`railix_*` system props
- key normalization via `Names.sanitize(...)`
- **no** profile cascade, directory scanning, CLI decoding, placeholder resolution, or live update hooks.

## 2) Big Architectural Decision (Needed Upfront)

Railix currently has:
- `Rail.meta()` → `TypeMapI<?>`
- `Rail.config()` → `RailConfig`

You requested:
- “Rail should have a `config()` (TypeMap) with compiled configurations.”

That’s an API name collision. We need to pick one:
- **Option A (non-breaking):** keep `Rail.config()` as `RailConfig`, add `Rail.cfg()` (or `Rail.configMap()`).
- **Option B (breaking):** rename `Rail.config()` → `Rail.railConfig()` and use `Rail.config()` for the TypeMap.

This decision impacts all other tickets.

## 3) Proposed Model (Draft)

### 3.1 `RailixRuntime` (new)
Concrete class (not an interface) that owns global state:
- `ConcurrentTypeMap config` — the compiled config map (and live updates).
- `ServiceRegistry services` — ordered services/connectors, plus typed lookup.
- `IssueRegistry issues` — global issue/health map with listeners + metrics integration.
- `Metrics metrics` — one registry; multiple export formats.
- `RailixLogger logger` — configured from `config` and/or `LogCfg`.
- `AtomicInteger activeRails` + “accepting” flag for drain-on-shutdown.
- Shutdown hook + `await()`/`shutdown(...)` entry points.

### 3.2 `Rail` (extended)
Each `Rail` should expose:
- `meta()` — already exists.
- compiled config TypeMap — name TBD (see Section 2).
- services access — name TBD (`services()`?), ideally type-safe.
- issues access — `issues()` or similar.
- pause / rate limit helpers (implemented as steps).

### 3.3 Services (aka Connectors/Actors)
Services are long-lived infrastructure components (Mongo, HTTP server, file watcher, etc.). They must support:
- startup ordering
- shutdown ordering (reverse), plus **phased shutdown**:
  - stop accepting new work (HTTP receiver)
  - drain rails
  - stop late (Mongo)
- optional live-config change callback
- issue reporting (report/resolved)

## 4) EPICs + Tickets

### ⏳ TODO — EPIC-01: Compiled Config Map (Nano-like `readConfigs` for Railix)

**Outcome:** Railix has a compiled, normalized config map accessible from every rail, and updateable at runtime.

- ⏳ TODO — CFG-001: Define config key rules + normalization
  - Decide canonical key format: (a) Nano-style `standardiseKey`, (b) Railix `Names.sanitize`, or a merged rule-set.
  - Decide whether to keep the current “must start with `railix`” restriction from `Cfg.load()`.

- ⏳ TODO — CFG-002: Implement “compiled config” loader (Nano parity)
  - Directories scan (defaults like Nano’s `CONFIG_FILE_LOCATIONS`).
  - `application.properties` + `application-<profile>.properties` cascade.
  - Profile discovery keys (Nano supports Spring/Micronaut/etc aliases).
  - Overlay precedence (ENV < `-D` < CLI < programmatic overrides).
  - Placeholder resolution `${key}` / `${key:fallback}` with cycle prevention + depth limit.

- ⏳ TODO — CFG-003: Attach compiled config to runtime and to `Rail`
  - Every rail sees the same compiled config (snapshot vs live view decision).
  - Provide API that does not return `null`.

- ⏳ TODO — CFG-004: CLI args decoding (optional but Nano-like)
  - Accept `key=value` args string(s) and merge into config overlays.

- ⏳ TODO — CFG-005: `RailixRuntime.updateConfig(...)` (live changes)
  - Update config atomically and compute a **diff** (changed keys).
  - Emit a structured “config changed” callback into services (no event bus; direct callbacks).

**Acceptance baseline (EPIC):**
- A sample app can reproduce Nano-like behavior: profiles + placeholders + overlays.
- Config can be updated at runtime and services are notified with a diff.

---

### ⏳ TODO — EPIC-02: Runtime Container + Service Lifecycle (start/stop/drain)

**Outcome:** Railix can run as a long-lived runtime when services exist, and shutdown gracefully.

- ⏳ TODO — RT-001: Introduce `RailixRuntime` (concrete) + builder
  - Holds config/services/issues/metrics/logger.
  - Can create rails that are “runtime aware”.

- ⏳ TODO — SVC-001: Define `RailixService` contract
  - `start(Rail rail)` and `stop(Rail rail)` (both receive rail for config + error reporting).
  - `order()` / `priority()` for startup ordering.
  - Optional `onConfigChange(Rail rail)` (diff attached via meta/config/ctx).
  - Optional `stopAccepting(Rail rail)` hook for early shutdown (HTTP receiver).

- ⏳ TODO — SVC-002: Service shutdown phases + ordering rules
  - Startup: ascending order.
  - Shutdown: **reverse**, but with phases:
    1. stop-accepting phase (sources)
    2. drain rails
    3. stop phase (remaining services, reverse order)
  - Timeouts + forced shutdown behavior.

- ⏳ TODO — RT-002: Track active rails + drain semantics
  - Increment/decrement counter around rail execution (sync + async).
  - During shutdown: reject new rails from connectors, wait for active rails to finish.

- ⏳ TODO — RT-003: Shutdown hook + exit-code policy
  - Install `Runtime.getRuntime().addShutdownHook(...)`.
  - Decide if Railix ever calls `System.exit(code)` itself (Nano does conditionally).
  - Provide `shutdown(int exitCode, String reason)` API (and a “no System.exit” mode).

- ⏳ TODO — RT-004: Keepalive / await API
  - Default: no blocking thread.
  - Provide `await()` (blocks until shutdown) and/or `keepAlive()` (starts a non-daemon sleeper).

- ⏳ TODO — RT-005: Global lifecycle hooks (Rail-based)
  - Provide `onStart(Consumer<Rail>)` / `onStop(Consumer<Rail>)` at runtime level.
  - Hooks receive a rail so users can read config/services and report issues/errors consistently.

---

### ⏳ TODO — EPIC-03: Service Access From Rails (typed, ergonomic)

**Outcome:** user can register services globally and access them from a rail ergonomically (preferably type-safe).

- ⏳ TODO — SVC-010: Define service lookup API (non-APT)
  - `rail.services().get(MongoService.class)` style API.
  - Must not return `null` (use `Optional` or failure `Result`/issue).

- ⏳ TODO — APT-010: Add optional annotation support for typed service accessors
  - Option: new annotation `@RailixServices` generating a `Services` inner class (similar to `Ctx`).
  - Option: `@RailService` on `@Railix` interface methods to mark them as services (not metadata keys).
  - Goal call site: `myRail.services().mongo()` (or similar), without string keys.

- ⏳ TODO — SVC-011: Service “requirements” declaration
  - Allow users to declare “these services must exist” for a given rail/app.
  - Options:
    - annotation-driven (APT generates a runtime validator)
    - programmatic builder validation

---

### ⏳ TODO — EPIC-04: Built-in Connectors (Default Services)

**Outcome:** Railix ships with a few batteries that mirror Nano’s built-in services, without becoming an event bus.

- ⏳ TODO — CONN-001: HTTP Receiver (JDK `HttpServer`)
  - Accept requests, build a `Rail`, run user handler, return response.
  - Implements `stopAccepting` early during shutdown.

- ⏳ TODO — CONN-002: HTTP Sender (JDK `java.net.http.HttpClient`)
  - Optional retries/backoff (Nano has it).
  - Configurable via compiled config map.

- ⏳ TODO — CONN-003: File Watcher (JDK `WatchService`)
  - Grouped watch/unwatch.
  - Use for live config reload and user-triggered rails.

- ⏳ TODO — CONN-004: CLI Receiver
  - Read stdin lines / commands and trigger rails.

- ⏳ TODO — CONN-005: CLI Executor
  - Execute shell commands as rails steps with structured results (stdout/stderr/exit code).

---

### ⏳ TODO — EPIC-05: Observability (multi-format metrics + dynamic logging config)

**Outcome:** Metrics and logging can be configured via live config and exported in multiple formats.

- ⏳ TODO — MET-001: Add metric export formats beyond Prometheus
  - Add `toInflux()`, `toDynatrace()`, `toWavefront()` (Nano-style).
  - Ensure tag formatting + metric name sanitization rules are defined.

- ⏳ TODO — MET-002: Metrics endpoint integration (if HTTP connector is present)
  - Serve `/metrics/*` endpoints depending on enabled formats.

- ⏳ TODO — LOG-001: Dynamic logging config
  - Config keys for level/format/excludes.
  - Live updates via `updateConfig(...)`.
  - Keep `LogCfg.withConsumer(...)` integration path working.

---

### ⏳ TODO — EPIC-06: Live Config Updates (service notifications + reloaders)

**Outcome:** Config can be changed on the fly and services can react.

- ⏳ TODO — CFG-020: Config diff model
  - Define how diffs are represented (keys only vs key+old/new values).
  - Define where diff lives (rail meta? config map? dedicated structure).

- ⏳ TODO — CFG-021: Service callback semantics
  - Is `onConfigChange` called synchronously (blocking) or async on runtime executor?
  - Ordering: same order as startup? reverse? none?

- ⏳ TODO — CFG-022: File-based config reload (optional)
  - Use FileWatcher connector to detect changes and call `updateConfig(...)`.
  - Include debouncing.

---

### ⏳ TODO — EPIC-07: Rate Limiting + Pause Primitives

**Outcome:** Rails can slow down themselves and/or wait for global conditions.

- ⏳ TODO — FLOW-001: `pause(Duration)` / `sleepMs(long)`
  - A step that pauses safely and preserves interruption semantics.

- ⏳ TODO — FLOW-002: Keyed rate limiter
  - Token bucket or leaky bucket implementation (no external deps).
  - Global per runtime (not per rail instance).

- ⏳ TODO — FLOW-003: Rail DSL integration
  - `rail.rateLimit("mongo", permits, perDuration)` and a “current rail id” default key.
  - Metrics: wait time, throttled count.

---

### ⏳ TODO — EPIC-08: Global Issue Registry + “Pause Until Resolved”

**Outcome:** Services can report issues; rails can wait for issues to clear; metrics reflect issues.

- ⏳ TODO — ISSUE-001: Define issue model + registry
  - `report(key, message, details)` / `resolve(key)`.
  - Must be concurrent + observable.
  - Decide whether issue keys are sanitized (`Names.sanitize`).

- ⏳ TODO — ISSUE-002: Service integration
  - Services can report/resolve issues from `start/stop/onConfigChange`.
  - Services may subscribe to issue changes (e.g., HTTP receiver pauses/returns 503).

- ⏳ TODO — ISSUE-003: Rail pause helpers
  - `rail.pauseOnIssue("mongo_down")`
  - `rail.pauseOnIssues(regex/predicate)`
  - Optional timeout + failure result when timeout exceeded.

- ⏳ TODO — ISSUE-004: Metrics integration
  - Gauge: active issues count
  - Per-issue gauges/counters (bounded cardinality rules!)

---

### 💡 IDEA — EPIC-09: “Nano parity” extras (only if we really want Nano 2.0)

- 💡 IDEA — SCHED-001: Built-in scheduler helpers (daily/weekly/timezone/DST)
- 💡 IDEA — RES-001: Retry/backoff DSL for steps
- 💡 IDEA — RES-002: Timeouts per rail or per step

## 5) Open Questions (need answers before code)

1. **API naming:** do we go with Option A or B for `Rail.config()` vs config TypeMap?
2. **Config prefixes:** must keys be `railix_*` only (current `Cfg` behavior) or accept “app_*” style keys too?
3. **Profiles:** do we copy Nano’s full profile-discovery key list, or a smaller set?
4. **System.exit:** should Railix ever call `System.exit` (Nano-style “prod mode”), or only expose exit codes?
5. **Typed services:** is it acceptable to store services in `meta` and reuse today’s `ctx()` accessor generation, or do you
   want a dedicated `services()` accessor concept (APT or not)?
6. **Cardinality limits:** for issues + metrics tags, what’s the acceptable upper bound (to avoid Prometheus explosions)?
