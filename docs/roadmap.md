# Railix II Roadmap

Documentation reorganized: 2026-09-19. Direct-editing/reuse review updated 2026-10-17. Feature status is
retained from the previous roadmap, not recertified by this edit. Historical checkpoints and test counts live in
[verification](verification.md). Railix is **not production-certified**.

The [documentation map](../README.md#documentation-map) identifies each behavioral owner.
ADRs record rationale; this file owns sequence and status, not a second contract.

## Target Operating Model - Planned

The following is product direction, not current shipped behavior:

- **One executable model.** Applications are expressed as Flows and Steps. HTTP, MQTT, storage,
  access policy, rate limits, protocol handling, and other application concerns are modeled from
  that same vocabulary. Creator-only Groups, Blueprints, and Templates are reusable authoring aids
  that compile away; production understands only Flows and Steps.
- **Railway-Oriented Programming.** Railix uses this term for the complete application path from
  an external trigger through policy, processing, and storage, not only for success/failure control
  flow. Every supported concern must be expressible through the same Flow and Step model.
- **Example-driven authoring.** Examples demonstrate executed Flows/Steps and expose actual
  fields for mapping and connection. Execution coverage is distinct from successful completion
  and optional assertion tests; [EX-001 to EX-008](specs/examples-and-validation.md#behavior)
  own those meanings. [SYS-007](specs/system-model.md#planned-compilation-selection) records
  default omission of unreachable Steps with explicit retention, pending a precise reachability
  rule. No blanket test-pass build gate is selected. Production traffic is not captured as Examples.
- **Immutable application artifacts.** A built graph never changes in production. Artifacts will
  carry timestamp versions and allow only the small set of environment settings selected at build.
  Runtime access permissions are build-fixed under [SEC-006](specs/environments-and-security.md#immutable-permissions-and-revocation),
  not part of a live-editable settings surface.
  Whether customers use Git for project persistence and change management remains their choice.
- **Static identity and rotating credentials.** Railix uses declared identities and access policy,
  not a running user-management or mutable role service. Membership/permission changes require a
  rebuilt replacement; necessary credential rotation preserves that authority. The accepted
  default is local encrypted text files synchronized through Git, with automatic key management
  across projects and optional external secret managers; no vault service or binary vault file
  is required. SOPS is inspiration only.
  [Environments and security](specs/environments-and-security.md) owns the accepted goals and
  open storage, authorization, recovery and delivery decisions.
- **Read-only operational visibility.** When explicitly included at build and authorized, Creator
  will attach read-only to an instance and display the executing Flow, throughput, bottlenecks,
  metrics, queues, and errors. This observation capability will not edit or invoke production
  Flows. Separately authorized [data administration](specs/database-and-data-access.md) may read
  and mutate stored records; observation permission is not a data-access grant. A dedicated
  built-in time-series database is out of scope, without removing existing metric exports.
- **A decentralised application mesh.** After single-instance readiness, the first mesh
  coordinates already-running instances of the same project, subject to build compatibility.
  It balances eligible work; it does not provision hosts or start/replace their processes.
  [SYS-004](specs/system-model.md#accepted-platform-boundaries) owns that boundary. The unit of
  distributed work remains open; Creator Groups do not become runtime placement units.

Railix reduces hand-wired infrastructure configuration; it does not claim that certificates,
authentication, external networks, storage, or operating-system process supervision cease to
exist. The aim is one coherent model that makes those concerns explicit and buildable instead of
spreading them across unconnected application, CI/CD, observability, and orchestration files.

## Current Sequence

Two of eight feature milestones are accepted. Checkpoint counts describe accepted
scope, not estimated effort. Only accepted, implementation-ready slices proceed to code. Ordinary
multi-input convergence is the recommended first control slice. It does not wait for a full Join
or Limiter design. Reuse, branching and coordination are sequenced below, not approved for coding
by this documentation task. The 2026-09-19 decisions put single-instance production readiness before Item 7.
the required single-instance packaging work from Item 8 therefore does not wait for the mesh.

The 2026-09-21 HTTP review adds a P1 prerequisite to further HTTP work: remove privileged
compiler handling through the common Step lifecycle. Delivery order for that slice is lifecycle
and third-party proof, then context-driven client/server behavior including HTTPS. Static Step
functions, compiled configuration separate from context, downstream-first PRE, upstream-first
POST and graceful draining are accepted, with an adjustable 30-second drain deadline and explicit
standalone/embedded escalation. Local replacement with a short interruption and attempted recovery
is accepted under LC-8; optional private/keyed-shared resource registration now implements LC-3.
Normal Java-owned resources remain possible. Certificate and hostname checks are separate
client options, enabled by default; CORS is independent. Secret-manager work
does not block plain certificate configuration. Configurable primitive families are separate work.

| Specification slice | Outcome / requirements | Dependency and exit evidence | Status |
| --- | --- | --- | --- |
| Application and first-mesh boundary | [SYS-001 to SYS-004](specs/system-model.md#accepted-platform-boundaries) | Single-instance execution and minimal packaged capabilities first; mesh compatibility, membership and failure contracts before multi-node tests. | Accepted direction; no new implementation certification. |
| Result-based failures | [SYS-005](specs/system-model.md#current-failure-handling-gap) | Reuse current results; regress nested rejection/cancellation and invalid parsing before replacing internal exception control flow; measure success and rejection paths. | Planned; no runtime/API change in this workshop. |
| Step configuration validators | [SYS-019 to SYS-022](specs/system-model.md#configuration-validation) | Prove typed optional functions, defaults, Creator field errors and installed-bundle parity without lifecycle calls or per-request validation. | Implemented; verification gate passed 2026-09-26. Typed callbacks, explicit bundle definition providers, bounded validation workers and field diagnostics use the same contract for built-in and installed Steps. |
| Common Step lifecycle; remove HTTP privilege (P1) | [SYS-010 to SYS-016, SYS-018](specs/system-model.md#planned-step-lifecycle), [CR-006/007](specs/creator.md#planned-step-resource-settings) | Prove optional hooks, lifecycle ordering, compatible sharing/isolation through per-Step configuration, partial-init cleanup, graceful drain and third-party ingress with Trigger-owned lifetime (LC-9). | Implemented; verification gate passed 2026-09-26: static hooks, cached configuration, admission/drain, Creator replacement/recovery and generic source activation. Optional context.resource registration supports private ownership and explicit keyed sharing without new StepDefinition metadata. Installed-bundle JAR tests cover concurrency, nested Steps and failure cleanup. HTTP Client now reuses connections, with an ordinary private/shared option. Runtime sharing diagnostics and expanded HTTP behavior are not included. |
| Named resource profiles | [SYS-017](specs/system-model.md#planned-step-lifecycle), [CR-008](specs/creator.md#planned-step-resource-settings) | Agree persistence, references, migration and edit/delete rules before profile authoring; prove canonical settings and affected-Step feedback. | Planned, deferred by the user as a later convenience; not a prerequisite for lifecycle or optional resource sharing. |
| Context-driven HTTP and HTTPS | [HTTP Trigger](specs/http-trigger.md), [HTTP Client](specs/http-client.md) | Common lifecycle first; resolve field shapes, limits and migration; real HTTPS tests for independent certificate/hostname options, CORS checks and resource measurements. | Planned; plain compiled settings now, secret manager later; binary/multipart/streaming deferred. |
| Configurable Step families | [Consolidation candidates](specs/standard-library.md#planned-configurable-families) | Agree the first family, outcomes/output shape and version migration; prove existing modes and the generic third-party declaration path without numeric precision loss. | Direction accepted; no IDs merged or catalog rows changed. |
| Compilation selection | [SYS-007](specs/system-model.md#planned-compilation-selection) | Resolve graph-unreachable versus Example-unvisited now; agree retention, validation and edge handling before pruning; inspect artifacts and authored-graph preservation. | Accepted omission/keep direction; classifier is blocking, current compiler rejects unreachable graphs. |
| Convergence, branching and Join | [SYS-008, SYS-023 to SYS-027](specs/system-model.md#planned-merge-and-join) | Resolve FC-1 to FC-6 only before their dependent branch or Join slice. | Ordinary convergence implemented. Branch scheduling and Join remain planned. Independent-message pairing, merge granularity and completion remain open. |
| Step/group reuse and lanes | [CR-001 to CR-003, CR-009 to CR-013](specs/creator.md#planned-group-copy-and-sharing) | Prove independent copies, atomic shared edits, detachment and selectable lanes in the materialized graph. | Single-entrance copy/reuse, detach and complete-action history implemented. Advanced bindings and optional combined buildings remain planned. No implicit runtime sharing. |
| General-purpose Limiters | [SYS-028 to SYS-031](specs/system-model.md#planned-limiters) | Resolve LM-1 to LM-4 and FC-6. Installed-bundle proof, bounded waiting and fairness with metrics/Creator absent. | Planned. Whole-job concurrency scope accepted. Rate limiting is separate. No persisted queues or implicit shared budgets. |
| Environments, secrets and early rejection | [SEC-001 to SEC-006, SEC-008/009](specs/environments-and-security.md#accepted-goals), [Layer 4 Step scope](specs/environments-and-security.md#planned-layer-4-step-integration) | Static identities/access and credential rotation accepted; decide source format, trust, recovery/rollback, renewal failure and transport enforcement before the security milestone. | Planned; no live user-management subsystem; cryptographic architecture and Layer 4 tools/platforms remain unspecified. |
| Security and compliance evidence | [SEC-007](specs/environments-and-security.md#security-and-compliance-support) | Threat model and applicable controls before security-sensitive implementation; select ISO/ASVS scope, operator duties and release evidence. | Support goal accepted; no certification or legal-compliance claim. |
| Database and Creator data administration | [DATA-001 to DATA-004](specs/database-and-data-access.md#accepted-behavior) | Agree MongoDB-inspired behavior profile, durability, permission scope and concurrent edits; prove Creator/Flow CRUD, direct API denial, restart and packaged omission. | Planned; no engine or full MongoDB compatibility selected; dedicated time-series database excluded. |
| Runtime lifecycle, evolution and scale | [Open runtime contracts](specs/system-model.md#open-runtime-and-delivery-contracts), [SYS-006](specs/system-model.md#accepted-platform-boundaries), [SYS-009](specs/system-model.md#planned-host-aware-step-integration) | Agree control/retry semantics, compatibility and numeric workload budgets before dependent I/O/host/distributed implementation. | Common lifecycle and optional resource ownership implemented; monitoring/repair policy, distribution and thresholds remain open. |
| Creator capability/resource guidance | [CR-004](specs/creator.md#planned-java-module-visibility), [CR-005](specs/creator.md#planned-step-capability-symbols), [proposed UI journeys](specs/creator.md#planned-capability-and-resource-guidance) | Define per-Step module attribution, capability evidence and unresolved states before this UI slice; agree observation/resource interactions and test comprehension, accessibility and bounded UI work. | Module visibility and capability symbols are planned requirements; detection contracts and UI remain unspecified. Other guidance remains proposed. |
| Example authoring, ownership and coverage | [EX-001 to EX-003, EX-007/008](specs/examples-and-validation.md#behavior) | Actual field-mapping and coverage journeys; schema/migration before file separation; selected-build evidence before an inclusion or release gate. | Accepted direction; not equivalent to passing assertion tests. |
| Per-Example Step output fakes | [EX-009/010](specs/examples-and-validation.md#real-io-and-output-fakes) | Agree fake replacement/output/outcome selection and persistence; prove case isolation, no accidental side effects, truthful coverage and production omission. | Planned; real-I/O Examples and explicit test-mode bypasses remain permitted today. |
| Phase 1 execution validation | EX-001, EX-003, EX-006, EX-008 | Public Creator proof of reached versus completed/failed Steps, cancellation and stale evidence; release consequences await the selection contract. | Partial: persistent Example selection, observed Step/group field changes and failure navigation explain actual results after edits. Large closed branch areas now have a derived overview with existing observations, separate corridors and elevated crossings. See [editing behavior](specs/creator.md#editing-an-existing-flow), [rendering scope](specs/creator.md#camera-and-rendering) and [observed values](specs/creator.md#world-state-and-selection). Predictive change-impact preview, domain-aware semantic grouping and release eligibility remain open. No blanket build gate approved. |
| Phase 2 visual expectations | EX-004 to EX-006 | Phase 1 plus agreed operators/selection semantics; matching, failing and unreached-Step examples through Creator. | Planned. |

These are dependencies, not approval to implement or an invented release date. Inputs
and expectations migrate together once the file contract is agreed. Fixtures, expected
error assertions and protocol-specific test clients remain separately scoped work.

## 1. Creator-First Application Graph

Status: **Complete; re-accepted 2026-07-30**. The first CLI/Lowercase journey is accepted.
Behavior: [system model](specs/system-model.md), [Creator](specs/creator.md).
Evidence: [accepted journey](../screenshots/roadmap-item-1-creator-first-v2.jpg).

## 2. Primitive Manipulation And Data Workbench

Status: **Complete (2026-07-30)**. Guided paths, exact values, ordered unary programs,
real application previews, invalid-draft preservation and normalization are accepted.
Behavior: [system model](specs/system-model.md), [standard library](specs/standard-library.md).
Evidence: [accepted workbench](../screenshots/roadmap-item-2-data-workbench.jpg).
The full unary catalog is not implied by this milestone.

## 3. Primitive Contract And Standard Library

Status: **In progress (4/6 checkpoints, 67% complete; 33% left)**

Goal: finish a finite versioned catalog for total and explicitly fallible unary operations.

Checkpoints:

1. **Done:** accept the finite catalog matrix (decision: ADR 0021; owner: standard-library spec).
2. **Done:** implement and prove fallible `text.to-number` with `invalid` no-write continuation.
3. **Done:** implement collection aggregation and percentile with explicit `empty`/`invalid`.
4. **Done:** implement and prove all accepted total conversion, text, boolean, number, list,
   date, matching, and normalization rows.
5. **In progress:** implement every remaining accepted fallible parse, validation, translation,
   and collection row. [The standard-library spec](specs/standard-library.md) is the only support matrix.
6. **Planned:** full catalog acceptance gate, docs, coverage/complexity audit, stability proof, and
   milestone screenshot.

Regex remains excluded until a bounded implementation exists. External dependency loading belongs
to Item 4.
The [configured-family review](specs/standard-library.md#planned-configurable-families) does not
change these checkpoint counts or authorize silently replacing existing versioned Step IDs.

## 4. Triggers, I/O Steps, And Dependencies

Status: **Partial: initial HTTP slice; remaining scope planned**

The [HTTP Trigger](specs/http-trigger.md) and [HTTP Client](specs/http-client.md) specifications
separate today's minimal implementation from the requested context-driven HTTPS contract.
[ADR 0023](adr/0023-http-ingress-and-step-owned-jdk-modules.md) records the initial implementation;
its HTTP-specific launcher exception has been replaced under SYS-012. New lifecycle code
must use static function composition, compiled settings and ordered startup/draining shutdown.
The baseline does not certify that contract, production ingress hardening or the protocols below.

Goal: provide practical application boundaries while preserving one flat graph.

Scope:

- startup, HTTP, socket, scheduler, MQTT, and gRPC Trigger Steps in addition to CLI;
- Trigger-owned examples, results, defaults, resource claims, and generated API contracts;
- HTTP client/server, file, database, and other trusted ordinary Steps;
- explicit Maven Central, Gradle-compatible coordinates, and repository dependencies;
- reproducible dependency locks derived from the complete project;
- one small reusable Step developer template and one-command external-flow E2E proof.

No Trigger or I/O Step is accepted without a real built-application test.
Database Steps also require the [database/data-access contract](specs/database-and-data-access.md),
including restart behavior and the connected management experience; they are not covered by
listing "database" alongside other I/O Steps.

## 5. Flow Control, Groups, And Flat Compilation

Status: **In progress; 4/7 checkpoints accepted**, with checkpoint 4 partial.
Behavior: [system model](specs/system-model.md), [Creator](specs/creator.md),
[Example validation](specs/examples-and-validation.md).

1. **Done:** one flat graph and structural compilation; no group expansion or handler execution in the compiler.
2. **Done:** metadata isolation and flat recovery without functional changes.
3. **Done:** semantic zoom and group management; historical scoped UI follow-ups do not imply product certification.
4. **Partial:** Filter, Choice, Switch and ordinary convergence are implemented. Multi-route execution, Join and Loop remain unsupported.
5. **Done:** malformed metadata rejection, migration and recovery.
6. **Partial:** [Ordinary convergence](specs/system-model.md#planned-merge-and-join) has a working baseline.
   Branch execution, Join and bounded Loop still need their contracts and proof. Waiting and
   independent work need explicit lifecycle ownership.
7. **Planned:** Examples, visual expectations and release eligibility according to the two-phase validation spec; fixtures and protocol clients require their own decisions.

The accepted [Step/group reuse slice](specs/creator.md#planned-group-copy-and-sharing) is additional
work, not part of the previously accepted checkpoint counts. Compiler group expansion
remains excluded. The following small vertical slices are a recommended implementation order,
not new completion counts. Their acceptance criteria stay in the owning specifications.

| Slice | Observable outcome / requirements | Dependency and exit evidence | Status / blocker |
| --- | --- | --- | --- |
| F1: ordinary convergence | Multiple routes reach one ordinary Step without a Merger, SYS-008/013 and CR-013/015/016. | Verify same-Trigger and cross-Trigger arrivals, isolated contexts/responses, one placement lifecycle, preview/cancel and connection edits in Creator plus built JAR. | Baseline implemented. Generated routes have no exclusive Trigger owner. No Join or scheduler was added. Broader lane and large-graph interaction acceptance remains. |
| F2: independent copy | Copy one Step, then group contents, CR-001/003. | Run original/copy, edit one, reopen and compile without graphics metadata. | Single-entrance placement implemented. Several group entrances still require a compound binding interaction under RE-1. |
| F3: link and detach | Edit shared contents/mappings atomically and detach one use, CR-002/003/009/010/018. | Prove near/distant uses, related highlights, independent copies, rejected/stale edits and unchanged behavior after detachment. | Basic shared processing, internal insertion/deletion, detach, session history and startup rollback implemented. Advanced boundary changes and the full large-graph/browser matrix remain. |
| F4: combined building and components | Select separate lanes in one linked building. Inspect a compact functional connector, CR-011/012/013. | Functional identities from F1/F3 and an actual declared connector capability, not placeholder Join/Limiter behavior. Pointer/keyboard/narrow-layout proof and screenshots. No runtime dependence on geometry. | Planned. Combined buildings are optional, not a prerequisite for reuse. |
| F5: multiple selected routes, sequential execution | Explicitly choose one, several matching or all routes without implicit concurrency, SYS-023/025. | FC-1/2 and public result/declaration contract. Built-in/installed-bundle parity, ordered execution, no-match/error cases and unchanged existing Choice/Switch behavior. | Planned. Do not merge catalog IDs before mode and migration decisions. |
| F6: independent branch execution | Owned concurrent work with bounded lifecycle and context rules, SYS-023/026/027. | FC-3/5 and current public-contract gap analysis. Real branches, failure/cancel/drain and retained-memory checks with the plain synchronous path unchanged. | Planned. No callback-only or global observer architecture selected. |
| F7: explicit Join | Pair related arrivals, then merge contexts, SYS-024/025/027 and CR-014. | FC-4/5/6. Use F6 ownership capabilities where required. Prove overlapping jobs, last-arrival-wins and non-blocking Creator warnings, late/duplicate/failing inputs, original caller responses and bounded waiting via an installed bundle. | Planned. Independent-message pairing, merge granularity and completion open. |
| F8: rate admission | Limit starts per interval with bounded fair waiting and overload, SYS-028 to SYS-031. | LM-2/3/4 and owned waiting from the common contract. Controlled-clock burst/fairness/overflow tests, private/shared budgets, no Creator/metrics dependency. | Planned. Independent of Join context-merge policy. Waiting semantics still need agreement. |
| F9: whole-job concurrency limit | Hold each admitted place until its job is finished, SYS-027 to SYS-031. | LM-1/2/3 and FC-6. Hold downstream work, exercise branch/Join completion, cancellation, re-entry and starvation/deadlock cases. Prove no permit leaks or early release. | Planned. Whole-job scope accepted, exact accounting and interaction rules open. |

This list adds no module, runtime dependency or alternate interpreter. Reuse does not require
runtime parallelism. Rate admission need not wait for the Join feature. Coordination capabilities
must be added only when a selected slice proves they are needed. Bounded Loop remains a separate
later contract, not implied by these graph edits.

The accepted [direct-editing journeys](specs/creator.md#planned-direct-editing), CR-015 to CR-020,
add selection-based grouping and stable near/distant navigation. Presentation-only grouping can
be delivered against the current graph independently of F1. Reuse needs F2/F3 bindings and atomic
edits. Complete Undo/Redo uses the bounded-history and revision boundary recorded under RE-2.
The baseline now includes connection handles/previews, separate Step/Group insertion search, selection and
range grouping, source selection and complete-action Undo/Redo. This does not complete the wider
acceptance matrix or add newly accepted checkpoint counts. Runtime F5 to F9 remain planned.

Usability on complex saved flows is the current priority. Further graphic redesign and new renderer
work are deferred. Concept art alone does not establish implementation readiness.

Compiler/observation scaling and viewport-readiness scoped gates were locally accepted
on 2026-09-08. [Earlier probes](verification.md#compiler-and-observation-scaling)
are not proof of million-Step compilation, million concurrent requests or sustained
thermal behavior. Streamed project/source processing remains unproven at that scale.
The current default renderer is native Canvas. CSS styles the HUD and palette, not world geometry.

## 6. Live Runtime, Operations, Bounded Work, And Permissions

Status: **In progress; 1/6 checkpoints accepted (17%)**

Goal: make development observable and production behavior bounded.

Scope:

- coordinated waiting/independent work begins only with public Step capabilities and complete
  ownership under [SYS-025 to SYS-027](specs/system-model.md#planned-merge-and-join): bounded
  admission, completion, deadline/cancellation, shutdown and retained-memory proof. The exact
  continuation API and scheduling mechanism are not selected in this roadmap.
- independently includable project/build metadata, preview, live-error and queue capabilities;
  the [accepted observation direction](specs/observation.md#accepted-production-observation-direction)
  instead includes bounded baseline Step/Flow telemetry in every production build;
- local and remote authenticated, read-only Creator attachment with explicit compatibility
  metadata and build-selected observation endpoints; observation never edits or invokes a Flow;
- separately authorized Creator database administration through build-selected data capabilities,
  with permissions enforced at the serving boundary, not only in the browser;
- static identity and per-environment access declarations, compiled enforcement and necessary
  credential rotation; no running account-registration or mutable role-management API;
- encrypted environment secrets and automatic multi-project key management according to
  [environments and security](specs/environments-and-security.md); concrete storage/key protocols
  are open, and raw secrets must not leak through ordinary Flow JSON, diagnostics, traces, metrics
  or Creator exports;
- bounded throughput, timing, resources, uptime, bottleneck, queue, and custom metrics per flow
  and Step without workflow values;
- deterministic configuration precedence, named environments, explicit placeholders, and restart
  behavior without hidden live mutation;
- bounded issue and diagnostic registries with explicit overflow behavior. General-purpose
  [Limiters](specs/system-model.md#planned-limiters) separately own rate/concurrency admission,
  explicit budgets, fair lanes, bounded in-memory waiting and overload paths.
- bounded metric names, labels, and cardinality with an explicit overflow series instead of
  unbounded production memory;
- development Example traces remain outside production; production tracing requires explicit
  enablement, bounded capture and no workflow values by default;
- bounded queues, concurrency, deadlines, cancellation, backpressure, and shutdown;
- explicit filesystem, network, process, environment, and other permission requests;
- optional resource measurement and enforceable execution policy.

The ordinary synchronous route must remain free of an unused scheduler. Coordination extends the
same compiled execution model, not a second interpreter. A callback alone is insufficient:
abandoned work must not retain its context without completion, cancellation and cleanup ownership.

Checkpoints:

1. **Done:** development observability foundation; [observation spec](specs/observation.md) owns behavior and budgets, [earlier evidence](verification.md#observability-foundation-acceptance) records acceptance.
2. **Planned:** lifecycle-complete suspending Steps with bounded admission, deadline, cancellation,
   shutdown, and retained-context proof while preserving the synchronous fast path.
3. **Planned:** independently includable development capabilities, named environments, deterministic
   configuration precedence, and build-time physical omission of development-only capabilities,
   while retaining bounded baseline production telemetry.
4. **Planned:** authenticated local/remote read-only attachment, static identities/access policy,
   credential rotation and encrypted environment-secret delivery.
5. **Planned:** bounded queues, backpressure, diagnostics, live errors, rate limits, metric
   cardinality, and explicit overflow behavior.
6. **Planned:** declared filesystem/network/process/environment permissions, resource measurement,
   and enforceable execution policy.

The new database administration scope is planned separately in the specification table above;
the existing observation checkpoint does not certify static access enforcement or data read/write access.

## 7. Decentralised Execution, State, Sharding, And Replication

Status: **Planned**

Goal: scale one application through a decentralised Railix mesh without turning it into
microservices or adding a user-managed orchestration layer.

Delivery follows single-instance production readiness, including its packaging work in Item 8.
The first mesh scope is SYS-004; cross-project execution, host provisioning and process
supervision are not requirements for that slice. Other projects use explicit application APIs.

Scope:

- define member discovery, authenticated mesh membership, resource declarations, work ownership,
  and work takeover as instances stop, restart or are replaced by their external supervisor;
- require a successor to prove identity, compatibility, readiness, ownership transfer, drain, and
  rollback before an existing application yields work;
- every authorized mesh instance can accept work and participate in balancing and recovery;
- decide and prove the compiled unit of distribution: eligible Step, Flow, or another boundary.
  Creator-only Groups, Blueprints, and Templates must never become a production placement model;
- explicit eligibility and dependency-tree compatibility, never automatic migration from timing
  alone;
- authenticated remote dispatch with bounded retries, deadlines, backpressure, and observability;
- stable invocation identity, attempt, origin, and trace information;
- explicit local-resource and placement constraints;
- Step-owned state, partitioning, replication, repair, backup, and recovery;
- partition, restart, migration, recovery, and soak proof without retained run state or leaks.

## 8. Direct Generation, Distribution, And Certification

Status: **Planned**

Goal: ship the complete project as a small self-contained application.

Scope:

- direct Java calls for reachable Steps and control paths;
- only referenced dependencies and JDK modules;
- dependency-aware `jdeps` module closure after all project bundles are known, cached `jlink`
  runtime images, and `jpackage` applications/installers;
- fresh-machine JDK/toolchain acquisition with verified checksums, plus authenticated private
  repositories whose credentials never enter project JSON or generated artifacts;
- environment-selected development capabilities omitted as code rather than disabled at runtime;
- deterministic Mermaid audit export from the functional graph and a reproducible application
  quality score whose inputs and deductions are inspectable rather than heuristic fog;
- immutable timestamp-versioned artifacts, reproducible outputs, signing-ready release smoke,
  upgrades, supported operating-system supervision integration, and clean-machine proof;
- GraalVM native-image only after Java contracts stabilize;
- approximately 95% line and at least 90% branch coverage as non-failing quality reports;
- one public E2E per reachable success, rejection, cancellation, repeat, concurrency, lifecycle,
  packaging, and recovery scenario;
- leak, process-tree, descriptor, queue, memory, and long-running stability evidence.

## Working Rules

- Build the active acceptance checkpoint only.
- Prefer deletion and reuse over another abstraction.
- Add no module, runtime dependency, parallel model, compatibility layer, or fake runtime.
- Reject invalid construction at compiler, builder, parser, or ingress boundaries with deterministic
  diagnostics.
- Test through the highest practical public entrypoint; one scenario owns one test.
- Keep line coverage near 95% and branch coverage at least 90%, reported without failing a
  developer build.
- Every completed roadmap item ends with a real Creator screenshot and exact clean verification.
- Roadmap status changes only when public acceptance evidence exists.
