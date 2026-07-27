# ADR 0016: Canonical Flow Compilation And Authoring

## Status

Accepted on 2026-07-27.

## Context

Creator, package builds, and runtime must agree on one flow rather than convert between a UI
graph, schemas, and executable models with different validity rules.

## Decision

Strict JSON is the sole authored flow format. It names the flow, triggers, entry, primitive
inputs/outputs, Step invocations, outcome routes, and data connections. Control and data are
independent: outcome routes choose the next Step, while connections map named values with
explicit field/index paths, defaults, and conversions.

`FlowSourceReader` rejects unknown or malformed fields. `FlowCompiler` is the only constructor
of executable plans and returns either deterministic diagnostics or a resolved `CompiledFlow`.
Ordinary control cycles and fan-in from different predecessor Steps are rejected. Mutually
exclusive outcomes from one Step may share one successor, and `end` may receive transitions
from any Step. Mappings, defaults, routes, and fallbacks are never invented.

Creator edits, validates, opens, and saves this same model. Layout and the central Application
view are deterministic projections and are not stored in flow JSON or a server workspace.

## Invariants

- Canonical saved JSON is compiler-produced and byte-stable for equivalent accepted input.
- Compiler validation is authoritative for imported and UI-authored flows.
- Runtime never reparses authoring JSON or rediscovers graph validity.
- The current pre-1.0 flow grammar is unversioned; no migration compatibility is claimed.

## Consequences

Authored behavior is explicit and auditable. Schema evolution cannot promise compatibility
until a format-version and migration policy is accepted.

## Rejected Alternatives

YAML flow DSLs, UI-only graph models, persisted coordinates, automatic mappings, scripts,
`Selector`/`Patch` layers, hidden routes, and compiler bypasses are rejected.

## Evidence

- [`FlowSourceReader.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/flow/FlowSourceReader.java)
- [`FlowCompiler.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/flow/FlowCompiler.java)
- [`CompiledFlow.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/flow/CompiledFlow.java)
- [`app.js`](../modules/railix-creator/src/main/resources/dev/nanonative/railix/creator/web/app.js)

## Deferred Decisions

Flow format versioning/migrations belong to Item 10. Merge, Split, Choice, Loop, and visual
test authoring remain Item 6.

## Historical Disposition

This supersedes the implemented authoring fragments of deleted ADRs 0005, 0008, and 0012.
Their speculative schemas and operator layers are not retained.
