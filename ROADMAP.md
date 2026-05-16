# Railix Roadmap

## Completed Foundations

- [x] Metrics registry with Prometheus export (`Metrics`)
- [x] Structured logging with JSON/console formatting
- [x] External logging integration via `LogCfg.withConsumer(Consumer<LogEntry>)`
- [x] Annotation processor for typed rail accessors
- [x] Config loading baseline via `Config.load()`

## Current Refactor Direction

- [ ] Move public examples and docs to the `Rail.of(sourceActor)` model
- [ ] Remove lingering `init()/run()/withMeta()` thinking from the DSL and docs
- [ ] Keep `TypeMap` central while separating payload from rail metadata
- [ ] Make reusable `RailDef` blueprints and operators the normal way to structure rails
- [ ] Keep actor-driven execution and hide traversal mechanics behind the runtime

## Future Roadmap

### v0.2.x
- [ ] Source actors for HTTP / CLI / socket / queue style ingress
- [ ] Path/value mapping helpers for payload and ctx
- [ ] Validation accumulation and recovery operators

### v0.3.x
- [ ] Richer telemetry around steps and actor ingress
- [ ] Checkpoint hooks for persistence/queue handoff
- [ ] Correlation and tracing propagation

### v0.4.x
- [ ] Self-update / sibling-version handover (`rail_app_v1` -> `rail_app_v2`)
- [ ] Source-actor cutover, drain, promote, rollback semantics
- [ ] Minimal boring upgrade path without requiring heavy external orchestration

### v0.5.x
- [ ] Railix build DSL for Java version, outputs, metadata, dependencies, and repositories
- [ ] Wrapper-style Railix Build launcher (`sh` / executable) for fresh machines
- [ ] Toolchain detection/download for JDK and GraalVM when missing
- [ ] Packaging from one definition: jar, `jlink`/`jpackage`, native executable, Docker `amd64`/`arm64`
- [ ] Mermaid export for app/rail structure from the same definition
- [ ] Project-local support for private artifact repositories without forcing global `settings.xml`

### v0.6.x
- [ ] App score / gamified quality rating
- [ ] Background scanning for coverage, security, size, complexity, startup footprint, and related signals
- [ ] Human-readable and machine-readable score output

### v1.0.0
- [ ] Stable source-bound public DSL
- [ ] Reusable step/fragment packaging model
- [ ] Full Javadoc coverage on the public API
