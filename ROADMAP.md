# Railix II Roadmap

This roadmap is ordered to avoid throwaway architecture. Each slice is small enough to implement, but each contract should be designed as if the system will later replace heavier distributed infrastructure.

## Slice 1: Kernel contracts

Goal: define the stable language of the runtime.

Deliverables:

- `RailixValue`: null, bool, number, string, list, object, blob/file/stream/session/deferred refs, secret refs
- `RailixPath`: canonical flat path syntax such as `ctx.user.email`, `payload.orders[0].items[2].sku`
- `Selector`: wildcard path selection such as `payload.orders[*].items[*]`
- `Patch`: set, remove, append, merge, move, copy
- `Shape`: inferred or declared data structure model
- `SettingsTree`: one tree for config and secrets
- `Envelope`: generic trigger input
- `Reply`: generic protocol output
- `StepContract`: inputs, outputs, outcomes, permissions, settings, resources, cache policy, metrics
- `OperatorContract`: transform operator schema for DataWorkbench
- `RunSignal`: events used only for observation
- `PermissionSet`: declared and granted powers

Acceptance:

- Contracts can be serialized to stable JSON/YAML.
- Contracts are round-trippable through the Creator UI model.
- Contracts have no dependency on HTTP, Docker, Kubernetes, or a specific database.

## Slice 2: Local execution kernel

Goal: run simple plan-connected steps locally.

Deliverables:

- `AppPlan`
- local scheduler
- step runner API
- `ContextDoc` snapshot and patch application
- deterministic run folder
- `RunSignalLog`
- simple run history
- simple settings resolver
- simple permission decision model

Acceptance:

- A flow with manual trigger -> data transform -> log step can run.
- UI can read run signals while the app executes.
- Step execution does not depend on a middleware event bus.

## Slice 3: Creator minimum

Goal: edit, run, and inspect a flow.

Deliverables:

- graph editor shell
- step palette
- dependency/pack browser stub
- step inspector
- settings tree editor
- run button
- attach-to-running-app mechanism
- live run timeline
- context diff viewer
- run signal viewer

Acceptance:

- Pressing Run builds/starts the real app in dev mode.
- Creator attaches to the app control endpoint and displays live step status.

## Slice 4: DataWorkbench

Goal: make nested payload handling first-class.

Deliverables:

- source tree view
- flat path view
- selector builder
- target tree view
- operator pipeline editor
- live preview using sample payloads
- before/after diff
- `DataTransform` step
- `DataValidate` step
- `DataRoute` step
- core operator catalog

Acceptance:

- User can map nested input to nested context and nested reply without Java code.
- User can loop/select wildcard paths such as `payload.orders[*].items[*]`.
- User can define remembered context variables from mapped paths.

## Slice 5: Packaging

Goal: app image from day one.

Deliverables:

- modular Java layout
- `jlink` runtime image script
- `jpackage` app image script
- headless app mode
- UI bundled static assets mode
- environment build profiles
- runtime settings overrides by properties/env/CLI

Acceptance:

- App can be packaged and launched without an external JDK.
- JAR remains an intermediate artifact only.

## Slice 6: Standard packs

Goal: useful steps outside the kernel.

Deliverables:

- `railix.std.trigger`: ManualTrigger, CliTrigger
- `railix.std.data`: DataTransform, DataValidate, DataRoute, DataForEach, DataAggregate
- `railix.std.store`: StoreRead, StoreWrite, StoreDelete, StoreExists, StoreList
- `railix.std.metrics`: MetricEmit, standard metric descriptors
- `railix.std.audit`: AuditCustom
- `railix.std.file`: file resource refs and file operations

Acceptance:

- Packs are dependency modules.
- Unused steps are not packaged into the built app.

## Slice 7: First protocol pack

Goal: prove generic trigger/reply model with a real protocol.

Deliverables:

- `railix.std.http`
- `HttpTrigger`
- HTTP request envelope mapper
- HTTP reply adapter
- traffic panel
- sample request capture
- response mapping via `reply.*`

Acceptance:

- Flow can handle HTTP-like request data as generic payload.
- Flow returns generic `Reply`, not HTTP-specific response objects.

## Slice 8: Build app as internal tool

Goal: show Railix II can replace lightweight internal tools.

Deliverables:

- manual trigger UI forms
- run history UI
- per-flow dashboard
- custom metric panels
- settings editor for deployed app
- permissions/audit viewer

Acceptance:

- A non-developer can run a packaged flow app with UI, trigger a workflow, and inspect results.

## Slice 9: Multi-instance foundation

Goal: prepare scale without forcing DevOps concepts into the UI.

Deliverables:

- instance identity
- local node registry
- capability advertisement
- step execution eligibility model
- shared run signal export/import
- remote cache abstraction
- queue abstraction

Acceptance:

- Architecture supports remote execution, but local execution remains the semantic baseline.

## Slice 10: Remote execution boundary

Goal: execute eligible Steps across trusted Railix instances without changing local Step semantics.

Deliverables:

- deterministic execution request and preflight contracts
- provider and contract identity negotiation
- target capability and resource eligibility checks
- cross-instance request transport
- explicit timeout, cancellation, reply, and failure propagation
- bounded request lifecycle history

Acceptance:

- A locally valid Step invocation produces equivalent results on an eligible remote instance.
- Unknown, stale, incompatible, overloaded, or unreachable targets fail explicitly and deterministically.
- Remote execution never weakens the Step contract, permission, or artifact boundary.

## Slice 11: Production hardening and DX gates

Goal: make supported Railix behavior reproducible, diagnosable, and safe to operate.

Deliverables:

- line coverage gate of at least 95% and branch coverage gate of at least 90%
- public-entrypoint regression suites for every supported behavior and rejection path
- long-running memory, resource, cancellation, and concurrency soak suites
- deterministic diagnostics and reproducible build evidence
- compatibility policy, migration fixtures, support matrix, and release runbook
- cross-host launcher and installer verification

Acceptance:

- Full reactor, API documentation, compatibility, soak, and release gates pass from a clean checkout.
- No supported path relies on fake runtime behavior, unbounded ownership, or undocumented fallback semantics.
- No-leak claims are made only with repeatable instrumentation evidence.

## Slice 12: Step sandboxing and resource metering

Goal: let trusted Java Steps request explicit powers and optionally enforce measurable execution limits.

Deliverables:

- filesystem, network, process, environment, settings, and resource permission contracts
- kernel-owned capability objects instead of ambient access where enforceable
- CPU, memory, IO, network, and duration measurement boundaries
- configurable quotas and deterministic denial signals
- packaged-app policy configuration and audit evidence

Acceptance:

- A Step can declare required powers and receives only granted capabilities through supported boundaries.
- Denials and quota breaches are deterministic, observable, and covered at the real execution entrypoint.
- Unsupported isolation guarantees are rejected explicitly rather than implied.

## Slice 13: Distributed Step orchestration

Goal: place, queue, retry, and observe Step workloads across Railix instances without exposing platform plumbing to flow authors.

Deliverables:

- workload placement and eligibility scheduler
- bounded durable queues with backpressure
- cancellation, retry, lease, and duplicate-delivery semantics
- remote cache identity and invalidation rules
- workload and resource heat maps
- failure recovery and partition behavior

Acceptance:

- Multi-instance execution remains deterministic under overload, retries, instance loss, and network partitions.
- Queue and placement behavior is observable through the same run contract as local execution.

## Slice 14: Step-owned state, storage, sharding, and replication

Goal: provide durable Step state and distributed data ownership without forcing an external database into every app.

Deliverables:

- explicit Step state contract and lifecycle
- durable local storage engine boundary
- partitioning and shard placement
- replication, checksums, repair, backup, and restore
- consistency and conflict semantics
- migration and versioning protocol

Acceptance:

- State survives restart and node loss according to a declared consistency policy.
- Rebalance, repair, backup, restore, and schema migration have deterministic end-to-end proof.
- The runtime never markets this slice as a database replacement before durability evidence exists.

## Slice 15: Canonical JSON, XML, and YAML interchange

Goal: normalize supported document formats into one stable primitive tree without losing declared semantics.

Deliverables:

- canonical primitive data model and ordering rules
- stable JSON parser and serializer
- stable YAML parser and serializer
- stable XML parser and serializer with explicit attribute, namespace, text, and list rules
- format-to-format normalization and translation operators
- limits for depth, size, aliases, entities, and malformed input

Acceptance:

- Canonical fixtures round-trip deterministically for every supported format and cross-format translation.
- Ambiguous or lossy mappings require explicit policy and never silently invent data.
- Parser security and resource limits are proven at public entrypoints.

## Slice 16: Compiler, dependency graph, and release supply chain

Goal: compile a flow definition plus trusted Java Step dependencies into a reproducible standalone application.

Deliverables:

- contract-aware flow validation and compilation
- dependency graph resolution and lockfile
- unused Step and module elimination
- reproducible modular runtime and native installer assembly
- compatibility and migration diagnostics
- signing, notarization, provenance, and vulnerability evidence

Acceptance:

- Equivalent locked inputs produce byte-for-byte reproducible supported artifacts.
- Missing, incompatible, ambiguous, or untrusted dependencies fail before runtime.
- The packaged result contains only required modules and passes the release certification matrix.
