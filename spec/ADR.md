# Architectural Decision Records

This document captures the active architectural decisions for Railix.
When older code or notes disagree, these decisions win.

---

## ADR-001: No Unnecessary Interfaces

**Status**: Accepted

Use interfaces only when there is a real second implementation.
Concrete classes stay the default for logging, metrics, runtime helpers, and similar infrastructure.

---

## ADR-002: Records for Immutable Data

**Status**: Accepted

Use Java records for immutable data holders such as `Result`, `LogEntry`, and config snapshots.

---

## ADR-003: No Trivial Factory Classes

**Status**: Accepted

Do not wrap `new X()` behind factory classes unless the factory adds real behavior.

---

## ADR-004: Standard Functional Interfaces First

**Status**: Accepted

Prefer `Consumer`, `UnaryOperator`, `Function`, `Predicate`, and friends over one-off custom callback interfaces.

---

## ADR-005: Rail Is the Public Pipeline DSL

**Status**: Accepted

`Rail` remains the public abstraction. Railix does not replace the public DSL with a separate `Flow` type.
If internal engine objects are needed, they stay internal.

---

## ADR-006: `Rail.of(sourceActor)` Is the Normal External Ingress

**Status**: Accepted

In runtime-driven apps, external data normally enters through `Rail.of(sourceActor)`.
HTTP, CLI, socket, MQTT, scheduler, and similar entrypoints are source actors.

Consequence:

- the source actor owns ingress
- runtime data is not injected as fake metadata
- the rail declaration makes the source obvious

---

## ADR-007: No Mandatory Public `run()`

**Status**: Accepted

The intended public DSL is actor-driven and stream-like.
A mandatory public `.run()` is not the target API shape.
Execution mechanics belong behind the source actor and runtime.

Consequence:

- source actors drive normal app execution
- `fire(...)` exists for tests, manual execution, and sealed reusable rails

---

## ADR-008: TypeMap Is a Core Data Carrier

**Status**: Accepted

Railix keeps `berlin.yuna:type-map` central.
Use `TypeMap` / `ConcurrentTypeMap` plus `asString`, `asInt`, `asBoolean`, `asOpt`, and related helpers.

Rationale:

- strong typed access without reflection
- easy JSON/XML conversion
- reusable shared model with adjacent projects
- still compatible with plain-data persistence expectations

---

## ADR-009: Payload Is Not Rail Metadata

**Status**: Accepted

Rail metadata describes the rail itself: name, version, tags, transport kind.
Runtime payload is the data moving through the rail.
Constant enrichment belongs in explicit operators such as `put`, `defaults`, or `enrich`.

---

## ADR-010: Internal Traversal State Stays Separate

**Status**: Accepted

Even if the public API exposes only `Rail`, implementation must keep the reusable rail structure separate from the
per-event traversal state.

Consequence:

- the DSL stays clean
- reuse/composition stays possible
- checkpointing, queueing, and resume-on-another-instance remain feasible later

---

## ADR-011: No Public `RailFragment` Type

**Status**: Accepted

Reuse stays on `Rail` itself.
Build a reusable rail with `Rail.of()...seal()` and include it with `step(otherRailDef)` or
`step(name, otherRailDef)`.
Railix does not add a second public fragment type.

---

## ADR-012: Actors Are Global and Self-Managed

**Status**: Accepted

Actors are globally available background services.
They may manage pools, connections, and lifecycle themselves.
Railix does not introduce a generic pooled-actor abstraction.

---

## ADR-013: Telemetry Is Default, `stepHistory()` Is Not

**Status**: Accepted

Per-step timing/history belongs in metrics/tracing by default.
It should not become a noisy public DSL method unless there is a strong reason.

---

## ADR-014: Null-Safe Public API

**Status**: Accepted

Public Railix APIs should not punish users with avoidable `NullPointerException`s.
Skip null inputs when that behavior is unambiguous, return explicit `Result`s for failures, and avoid `null` returns
from public methods.

---

## ADR-015: Consumer-Based Log Integration

**Status**: Accepted

Logging stays dependency-light.
`LogCfg.withConsumer(Consumer<LogEntry>)` remains the bridge for integrating with external loggers.

---

## ADR-016: TypeMap Owns Path and Mutation Semantics

**Status**: Accepted

Railix reuses `TypeMap` path and mutation behavior as-is.

Consequence:

- path syntax stays `Object...`
- Railix does not add dotted-path parsing
- `set`, `put`, `add`, `defaults`, `remove`, and ctx variants follow `TypeMap` semantics

---

## ADR-017: First-Pass Rail DSL Stays Small

**Status**: Accepted

The current public DSL centers on:

- `of`
- `set`, `put`, `add`, `defaults`, `remove`
- `ctxSet`, `ctxPut`, `ctxAdd`, `ctxDefaults`, `ctxRemove`
- `step`
- `verify`
- `map`
- `choose`
- `each`
- `parallelEach`
- `reduce`
- `parallel`
- `ok`
- `fail`

Not part of the first-pass public DSL:

- `then`
- `onCtx`
- public `RailFragment`
- `flatMap` until its meaning is sharp
- cross-path `map/reduce` overloads that would leak `Object[]` into the public API

---

## ADR-018: Manual `fire(...)` Is First-Class

**Status**: Accepted

Manual execution is not a hidden fallback.
It is a supported ingress mode for:

- tests
- custom runtimes
- users who already own transport, queue, or scheduling concerns

Supported shape:

```java
Result fire()
Result fire(Object payload)
Result fire(Object payload, Object ctx)
```

Consequence:

- `Rail.of(sourceActor)` stays the normal runtime-facing ingress
- `Rail.of().fire(...)` stays the explicit manual/custom ingress

---

## ADR-019: `seal()` Produces `RailDef`

**Status**: Accepted

`seal()` does not just flip a boolean.
It produces an immutable reusable definition surface: `RailDef`.

`RailDef` exists to restrict mutation while keeping runtime access:

- `fire(...)`
- `source()`
- `config()`
- `railConfig()`
- `executor()`
- `metrics()`
- `actors()`

Reuse inside another rail happens through `step(otherRailDef)`, not nested `.fire()` calls inside a step.

---

## ADR-020: Step Interceptors Stay Name-Based and Test-Oriented

**Status**: Proposed

Railix should allow per-step interception for tests and controlled overrides.
This is primarily a testing aid, not a recommended permanent production pattern.

Preferred shape:

```java
Rail beforeStep(String stepName, Step<Rail> hook)
Rail replaceStep(String stepName, Step<Rail> hook)
Rail afterStep(String stepName, Step<Rail> hook)
```

Rules:

- interception is by explicit step name
- unnamed steps may still get generated names, but stable tests should use explicit names
- included rails will need predictable prefixed names later to avoid collisions
- `replaceStep(...)` is the mocking hook
- `beforeStep(...)` / `afterStep(...)` are the enrichment/trigger hooks

---

## ADR-021: `each` and `parallelEach` Use One Loop Contract

**Status**: Accepted

`each` stays serial and deterministic.
`parallelEach` stays explicit.
Both use the same loop callback shape so scalar, iterable, array, and map traversal share one contract.

Preferred shape:

```java
Rail each(LoopStep<Rail> logic, Object... path)
Rail each(String name, LoopStep<Rail> logic, Object... path)

Rail parallelEach(LoopStep<Rail> logic, Object... path)
Rail parallelEach(String name, LoopStep<Rail> logic, Object... path)
```

Where `LoopStep<Rail>` carries `rail + index + key + value`.

Rules:

- `Map<?, ?>` input exposes `index + key + value`
- `Iterable<?>` and array input expose `index + null + value`
- scalar non-null input is treated as one item with `index=0` and `key=null`
- missing/null input is skipped
- `each(...)` runs serially in source order
- `parallelEach(...)` runs child rails on the runtime executor
- virtual threads stay the default executor strategy
- if any parallel child fails, the parent fails too
- `UNEXPECTED` outranks `ERROR`; ties are resolved by source order
- successful sibling writes stay merged into payload/ctx even when the parent fails
- conflicting writes in parallel item processing should be treated as unsafe unless users write to item-specific paths

---

## ADR-022: Public Paths Stay `Object...`

**Status**: Accepted

Public path-taking APIs should use `Object...` instead of `Object[]`.

Consequence:

- `set`, `put`, `add`, `defaults`, `remove`, `each`, and `parallelEach` all keep `Object...` paths
- `map` and `reduce` stay same-path operations in the public DSL
- cross-path transforms can still be built with `step(...)` until a better public shape exists

---

## ADR-023: Public-API E2E Tests Only

**Status**: Accepted

Railix correctness is proven through public behavior only.
Tests should use the public DSL and `fire(...)`, not private helpers or direct internal state probing.

Rules:

- test through `Rail.of(...)`, DSL composition, and `fire(payload[, ctx])`
- do not write line-chasing tests for private helpers
- line coverage must be an implicit effect of public behavior coverage
- runtime payload and ctx should be supplied through `fire(...)` whenever the behavior under test is ingress/runtime data

---

## ADR-024: Coverage Gate Is Mandatory

**Status**: Accepted

Railix keeps a hard coverage gate in Maven `verify`.

Minimums:

- line coverage: `95%`
- branch coverage: `90%`

The gate is a minimum, not the testing goal.
The real goal is exhaustive reachable behavior coverage.

Every regression fix must add a test that fails before the fix and passes after it.

If reachable logic remains uncovered, that gap needs an explicit reason and a tracked follow-up.

---

## ADR-025: Every Public Feature Must Cover All Reachable Behavioral Paths

**Status**: Accepted

For every public feature, the test suite must cover all reachable behavioral paths through the public API.
This includes every line and branch that can be reached by supported usage.

At minimum, the suite must cover:

- success path
- absent/missing input
- null input where accepted by the API
- invalid/wrong-type input
- exception path
- termination or merge behavior where relevant

Coverage is not complete until the tests show how the system behaves for:

- valid input
- invalid input
- empty input
- nested/complex input
- conflicting input
- repeated execution
- state reuse and state isolation
- concurrency and merge behavior where concurrency exists

Unsupported behavior must also be tested to fail predictably, explicitly, and repeatably.
An unsupported path must never degrade into accidental or unknown behavior.

---

## ADR-026: No Unknown Exception Paths

**Status**: Accepted

Railix should not have "mystery failures".
Every exception path must be either:

- prevented by design
- converted into an explicit, documented outcome
- or covered by a regression test that documents the observed behavior

Testing must deliberately try to break each public feature.
If a public operation can throw or degrade unexpectedly under a reachable input or runtime path, that is a design or coverage gap until made explicit.

---

## ADR-027: Performance Regression Tests Are Mandatory

**Status**: Accepted

Railix is a runtime library. Correctness alone is not enough.
Changes must also be checked for performance regressions on core execution paths.

Rules:

- keep public-API performance tests or benchmarks for the main runtime paths
- measure at the library boundary, not private helpers
- performance runs must be repeatable enough to compare before/after changes
- core paths include at least rail execution, iteration, parallel iteration, config loading, and logging/metrics hot paths
- improvements and refactors should be checked for both regressions and gains

The goal is not a single fixed number forever.
The goal is to detect whether the system got slower or faster with each meaningful change.
