# ADR 0020: Creator-First Application Graph

## Status

Accepted on 2026-07-29. Refined through 2026-09-01 to separate the flat functional graph from
optional Creator metadata, remove compiler-expanded reusable flows, and add generic explicit
flow-control inputs, continuous semantic zoom, and explicit Creator/compiler/generated-application
ownership.

Amended on 2026-09-19 following the user's specification-workshop clarification: independent
copying and linked sharing are planned Creator features, not rejected product directions.
The compiler remains group-unaware. Artifact capability changes require rebuilding; the first
mesh is later than single-instance readiness and coordinates already-running, compatible
instances of the same project. These decisions do not mark their implementation complete.

Amended on 2026-09-27 from the user's reuse and flow-control specification: linked editing also
applies to individual Steps and includes field mappings. One occurrence can be detached without
changing its behavior. Ordinary multi-input convergence needs no Merger. Coordination remains
explicit. Join/Limiter use public Step contracts and may have compact connector presentation,
without privileged built-in IDs. Detailed semantics and unresolved decisions stay in the owners
below. This amendment does not implement them or select a scheduler or persistence schema.

Amended on 2026-10-17: direct editing uses visible connection/placement previews, selection-based
grouping and local reuse at near or distant destinations. Deterministic wiring is automatic.
Unresolved destinations stay visible for a user decision. Spatial proximity never creates linked
editing. The [Creator interaction contract](../specs/creator.md#planned-direct-editing) owns the
journeys and acceptance. Compiler ownership and the flat application model remain as decided below.

## Context

Amended on 2026-10-27 following the accepted whole-Creator usability review: the primary surface
becomes a compact flow workbench with shallow-depth modules, a docked Inspector and meaningful
location navigation. The existing scene and native Canvas pipeline remain shared. The temporary
Map comparison is not a second authoring product. Visual scope and zoom remain Creator-only and
do not change functional connections, Example selection or compiler inputs.

Railix Creator is the product entrypoint. One project must describe exactly the application that
the compiler executes and the release build will package. Visual grouping must help people manage
large graphs without becoming a second execution model or making functional builds depend on one
Creator version.

The previous implementation mixed reusable-flow definitions, compiler expansion, presentation,
global blueprints, and functional graph data in one file. That added an alternate model before
basic graph editing was stable. It has been removed.

## Decision

Keep one materialized functional graph and one generated application, with declarative
Step contracts and no hidden group expansion. Compilation is structural; the generated
application executes. Creator owns editing, building and child-process lifecycle.
Groups, geometry and themes remain presentation only. Development observations belong to
the generated application and are physically omitted from current production artifacts.

Creator may additionally copy and share a Step or group's functional contents. It resolves those edits
into ordinary Steps and links before compilation; a deployed application never follows a live
group reference. Visual grouping itself still has no execution semantics. This preserves the
single compiler model without prohibiting useful authoring reuse.

Linked editing, a combined building with separate lanes, and shared runtime state are different
relationships. Detaching an occurrence changes the editing relationship, not its execution.
Functional routing, correlation and budget identities cannot depend on optional graphics data.
Simply connecting more incoming belts processes each arrival separately. Waiting and context
combination belong to an explicitly configured Join. This keeps convenience in Creator without
making a visual change secretly schedule work or combine requests.

Interaction references support this direction. [Node-RED wiring](https://nodered.org/docs/user-guide/editor/workspace/wires)
demonstrates insertion feedback and direct connection operations. [Factorio remote view](https://www.factorio.com/blog/post/fff-380)
motivates consistent local and distant interaction. Its [Undo/Redo work](https://www.factorio.com/blog/post/fff-412)
shows the importance of complete changes and previews. These are design evidence, not adopted
execution models or proof of Railix usability.

The detailed contracts formerly accumulated here now have explicit owners:

- [System model](../specs/system-model.md): graph, context, declarations and routing.
- [Observation](../specs/observation.md): execution, management and metric boundaries.
- [Creator](../specs/creator.md): metadata, interaction, camera and renderer contracts.
- [Creator assets](../specs/creator-assets.md): settings, themes, audio and safe extensions.
- [Environments and security](../specs/environments-and-security.md): accepted usability goals
  and open identity, secrets and ingress decisions; SOPS is not prescribed.

This separation was documented on 2026-09-19 without changing implementation. The later
[Example decision](0022-example-validation-and-release.md) changes the target storage
ownership and adds Creator validation; it does not move execution out of the application.

## Consequences

Compiler and runtime have one canonical model and one execution path. Visual groups can evolve or
be lost independently. A newer Creator can still open the flat project even if presentation
metadata is incompatible. Shared authoring requires a persistence/update contract that does not
make presentation metadata a runtime dependency. Cross-project sharing remains undecided.

## Rejected Alternatives

Triggers outside the Step catalog, automatic heaviness or kind detection, a dedicated kind or
execution engine for unary Steps, hidden coercion, multiple deployable
applications per project, compiler-expanded reusable-flow definitions, runtime live blueprint
links, persisted scene geometry, collapse as a
second graph state, timestamps or counters as identity, running handlers during compilation,
rebuilding with `jpackage` after every edit, and TypeMap's failed-conversion-to-null semantics are
rejected.

The earlier blanket rejection of authoring-side shared-edit propagation is superseded by the
2026-09-19 clarification. Reuse identity and persistence will be specified in Creator; the old
format-2 metadata restrictions describe the current implementation, not a permanent prohibition.

On 2026-10-17 the direct-editing baseline keeps processing materialized in the functional graph
and adds optional identity correspondence to Creator metadata. It does not introduce a second
compiler graph or a runtime group reference. Complete editor actions replace the browser's two
independent document writes, so one shared edit and its Undo cover processing, connections and
membership together. A temporary backup restores the previous graph/metadata pair after a process
interruption. Session history and renderer state are not application dependencies. Advanced
multi-entrance placement remains open rather than inventing routes or enabling parallel execution.

## Acceptance And Evidence

The first accepted public journey remains Application -> CLI Trigger -> mapped Lowercase
-> End. Contract-specific acceptance belongs to the linked specs. Earlier test, renderer
and performance reports live in [verification](../verification.md); they are not new
certification. The [roadmap](../roadmap.md) owns remaining feature status.

## Supersedes

This ADR supersedes ADR 0015, ADR 0016, ADR 0017, ADR 0018, and the reusable-flow/global-blueprint
parts of its earlier revision. ADR 0013 retains the three-module product boundary and ADR 0014
retains canonical primitive data. Runtime settings and secrets remain unsupported as recorded by
superseded ADR 0019. ADR 0021 records the unary-contract decision; the
[standard-library spec](../specs/standard-library.md) owns its exact semantics.
