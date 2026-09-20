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

## Context

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

Creator may additionally copy and share a group's functional contents. It resolves those edits
into ordinary Steps and links before compilation; a deployed application never follows a live
group reference. Visual grouping itself still has no execution semantics. This preserves the
single compiler model without prohibiting useful authoring reuse.

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
