# Railix II Roadmap

Updated: 2026-08-11

## Product Goal

Railix Creator turns one visual project into one deployable Java monolith. The functional project
is a complete flat graph beginning at one permanent Application Step. Optional Creator metadata
adds semantic zoom and presentation without entering compilation or changing behavior.

Railix operates on JSON-compatible primitive values. The core remains plain Java,
non-reflective, small, deterministic, and stateless per admitted item. Small unary operations and
larger I/O or business Steps use the same generic Step contract; only graph roles with genuinely
different lifecycle remain distinct kinds.

[ADR 0020](adr/0020-creator-first-application-graph.md) owns the current graph, metadata, and
Creator contract. [ADR 0021](adr/0021-total-and-fallible-primitives.md) owns the finite built-in
unary-operation catalog.

## Progress

- Accepted roadmap items: **2/8 (25%)**
- Railix production certification: **0%**
- Current item: **5. Flow Control, Groups, And Flat Compilation**
- Current item progress: **5/7 checkpoints complete (71%); 29% left**
- Active checkpoint: **5.6 explicit flow-control Steps, 2/7 slices accepted (29%); 71% left**
- Retained incomplete item: **3. Primitive Contract And Standard Library, 4/6 complete
  (67%); 33% left**
- Latest completed milestone: **ordinary Choice Step plus ordered OR/AND matcher groups,
  2026-08-10**

Completion percentages count accepted checkpoints, not lines written. A roadmap item reaches 100%
only after its public acceptance gate, coverage report, complexity audit, stability checks,
documentation, and screenshot are complete.

## Retained Foundations

- immutable canonical `RailixValue` primitives;
- bounded deterministic JSON, YAML, and XML normalization;
- exactly three production modules: core, standard library, and Creator;
- explicit Step registration without reflection or runtime scanning;
- one flat functional project model and one runtime execution path;
- deterministic diagnostics and canonical JSON writing;
- Creator-owned rolling development application with real HTTP/browser E2E proof;
- no third-party runtime dependency.

Anything that cannot serve the accepted Creator-first graph is removed rather than wrapped.

## 1. Creator-First Application Graph

Status: **Complete; re-accepted 2026-07-30**

Goal: prove one understandable real journey before adding breadth.

Accepted journey:

```text
Application -> searched CLI Trigger -> mapped Lowercase Step -> Trigger result -> End
```

Accepted behavior:

1. One mutable workflow context with reserved read-only `context.runtime`.
2. One permanent persisted Application root and catalog-driven searchable Trigger insertion.
3. Trigger-owned named payload/context Examples, explicit/defaulted results, and unique
   ingress-source claims.
4. Generic recursive Step inputs consumed by Creator, compiler, and runtime.
5. Automatic persistence, structural compilation, rolling child replacement, and example runs.
6. Node-owned diagnostics, deterministic layout, stable selection, deletion, and keyboard/mobile
   authoring.
7. Truthful project path, application launch path, PID, graph count, and build time.

Evidence:

- compiler, runtime, Creator HTTP, desktop/mobile Chrome, packaged JAR, and launcher E2Es;
- no manual Validate/Run/Start lifecycle and no global Step palette;
- [accepted Creator screenshot](screenshots/roadmap-item-1-creator-first-v2.jpg).

Deferred: additional Trigger/I/O catalogs, control Steps, external dependencies, live metrics,
permissions, distribution, remote execution, state, and sharding.

## 2. Primitive Manipulation And Data Workbench

Status: **Complete (2026-07-30)**

Goal: make nested data work understandable without path syntax.

Accepted behavior:

1. Guided sample-backed paths rooted at `context`, with object fields, array indexes, editable
   breadcrumbs, and a non-mutating Cancel action.
2. Ordered, searchable, type-compatible unary Steps with insertion, movement, removal, and real
   per-stage preview from the built application.
3. Exact JSON numbers, UTC epoch-millisecond dates, sparse arrays, and shape propagation.
4. Invalid literal drafts remain visible and unpersisted rather than deleting user input.
5. JSON, YAML, and XML normalization through public project/runtime boundaries.
6. Desktop/mobile keyboard behavior and the runnable lowercase example.

Evidence:

- one public scenario per path, shape, draft, ordering, preview, normalization, and rejection;
- [accepted data-workbench screenshot](screenshots/roadmap-item-2-data-workbench.jpg).

The complete unary catalog is not implied by this item; Item 3 owns it.

## 3. Primitive Contract And Standard Library

Status: **In progress (4/6 checkpoints, 67% complete; 33% left)**

Goal: finish a finite versioned catalog for total and explicitly fallible unary operations.

Invariants:

- every operation is an ordinary stateless `STEP`, not a separate kind;
- one named `value` receive and one named `value` return for standard unary operations;
- total operations use one primary `ok` result;
- expected invalid or empty data uses declared outcomes and never exceptions;
- implementation faults remain failures and interruption remains cancellation;
- dates remain UTC epoch milliseconds represented as exact JSON numbers;
- Creator suggestions and configuration derive only from the Step contract.

Checkpoints:

1. **Done:** accept the finite catalog matrix in ADR 0021.
2. **Done:** implement and prove fallible `text.to-number` with `invalid` no-write continuation.
3. **Done:** implement collection aggregation and percentile with explicit `empty`/`invalid`.
4. **Done:** implement and prove all accepted total conversion, text, boolean, number, list,
   date, matching, and normalization rows.
5. **In progress:** implement every remaining accepted fallible parse, validation, translation,
   and collection row. ADR 0021 is the only support matrix.
6. **Planned:** full catalog acceptance gate, docs, coverage/complexity audit, stability proof, and
   milestone screenshot.

Regex remains excluded until a bounded implementation exists. External dependency loading belongs
to Item 4.

## 4. Triggers, I/O Steps, And Dependencies

Status: **Planned**

Goal: provide practical application boundaries while preserving one flat graph.

Scope:

- startup, HTTP, socket, scheduler, MQTT, and gRPC Trigger Steps in addition to CLI;
- Trigger-owned examples, results, defaults, resource claims, and generated API contracts;
- HTTP client/server, file, database, and other trusted ordinary Steps;
- explicit Maven Central, Gradle-compatible coordinates, and repository dependencies;
- reproducible dependency locks derived from the complete project;
- one small reusable Step developer template and one-command external-flow E2E proof.

No Trigger or I/O Step is accepted without a real built-application test.

## 5. Flow Control, Groups, And Flat Compilation

Status: **In progress (5/7 checkpoints, 71% complete; 29% left)**

Goal: compose large applications without hidden graph behavior or compiler-only visual models.

Current contract:

- `railix.project.json` contains only `format`, `id`, fully materialized `nodes`, and `links`;
- `railix.creator.json` optionally contains `format`, visual `groups`, and Step presentation;
- compiler and application never read Creator metadata;
- stable opaque UUID-backed node, group, occurrence, and logical-slot IDs encode no order;
- one occurrence maps logical slots to concrete flat Step IDs;
- nested groups provide semantic zoom and deleting a group preserves every Step;
- shared edits require **Update all**, **Detach this**, **Create variant**, or **Cancel**;
- current grouping accepts only contiguous ranges on one deterministic primary route;
- `railix.filter` is an ordinary Step using generic ordered candidate predicates and explicit
  `match` / `otherwise` links;
- `railix.choice` is an ordinary Step using generic ordered OR groups whose values pass through one
  shared Transform program and then independent non-empty AND Matcher programs, with missing
  sources false and canonical JSON values present;
- Creator renders matcher groups from the generic catalog input, while the rolling-built
  application owns short-circuit execution, failures, routing, and previews;
- Creator lays out every declared route in deterministic iterative depth-first order and edits each
  Filter route from the Inspector;
- malformed links are rejected at the functional boundary and remain diagnostic-only with Step
  insertion disabled if transient browser state is corrupted.

Checkpoints:

1. **Done (2026-08-06): Flat functional graph.** Remove reusable-flow definitions,
   compiler-generated graph expansion, alternate executable source, and global blueprint storage.
   Structural compilation never invokes handlers.
2. **Done (2026-08-06): Metadata isolation.** Persist grouping and appearance separately;
   missing/corrupt metadata opens the same functional graph flat and preserves invalid source.
3. **Done (2026-08-06): Semantic zoom.** Create, open, close, nest, rename, recolor, re-icon, and
   delete Creator groups without restarting or modifying the built application.
4. **Done (2026-08-06): Shared occurrences.** Map stable logical slots to concrete Step IDs;
   explicitly update all, detach, create a variant, or cancel; propagate structural insert/delete
   through ordinary flat nodes and links.
5. **Done (2026-08-06): Public rejection and recovery proof.** Reject empty, unknown,
   non-contiguous, overlapping, cyclic, mismatched, and out-of-parent metadata; prove flat startup
   fallback, desktop/mobile UX, deterministic layout, reload, and functional-Step preservation.
6. **Partial (2026-08-10, 2/7 control slices accepted):** Filter plus deterministic branch layout
   and Choice plus ordered OR/AND matcher groups are accepted. Both are ordinary Steps with generic
   inputs, explicit `match` / `otherwise` links, rolling-built execution and previews, iterative
   route authoring, and preserved existing groups. Choice additionally proves ordered
   short-circuiting, exact missing/present semantics, predicate failure propagation, restored UI,
   and position-independent editor state. Remaining slices are Switch, Merge plus fan-in
   cardinality, Split plus fan-out cardinality, bounded Loop, and branch-aware group editing.
   Choice hardening on 2026-08-11 adds generic Step search aliases, contract-derived
   Matcher/Transform labels, buildable predicate defaults, `value.not-equals`, three CLI example
   templates, canonical shared Transforms plus independent AND Matcher programs, legacy program
   normalization, measured trunk/bus/drop connectors, and targeted automatic-result rendering that
   cannot replace an open Step picker. No matcher kind or alternate execution path was added.
7. **Planned:** per-Trigger and per-Step test input, fixtures, assertions, reports,
   stop-before-side-effect behavior, and proof that every terminal path satisfies Trigger results.

Current evidence:

- clean `mvn clean verify`: **1,890/1,890 green**: core 760, standard library 379,
  Creator HTTP and launcher lifecycle 150, packaged development runtime 36, desktop Chrome 270,
  executable JAR 25, and mobile Chrome 270;
- the permanent tracked `./railix` launcher survives clean/test lifecycles, rejects a missing JAR
  deterministically, and starts the real packaged Creator without Maven writing outside `target`;
- clean aggregate JaCoCo: **95.5689% lines** and **89.8162% branches**. Core is
  95.7235%/90.2633%, standard library 99.7041%/97.1698%, and Creator 94.0050%/86.4097%
  (line/branch). The line target is met; aggregate branches remain 0.1838 percentage points below
  the visible non-blocking 90% target, with Creator owning the gap;
- desktop and mobile Chrome prove nested groups, portable appearance, shared edits, deletion
  preservation, stable IDs, deterministic relative layout, explicit branch authoring, valid and
  malformed route presentation, a 6,000-Step branch without JavaScript call-stack growth, separate
  Inspector/Appearance/Examples/Groups tabs, always-visible resettable target paths, payload plus
  optional-context Examples, Example counts, unary operations as ordinary graph Steps, and generic
  Choice group/matcher add, independent reorder/remove, reload, aliases, defaults,
  contract-derived Matcher/Transform roles, shared Size plus `> 1` and `< 5` programs authored in
  the UI and executed after reload at four boundary values, built-preview flows, three default CLI
  examples, measured nested branch connectors, and an open picker surviving a delayed real Example
  result without losing focus or input;
- independent Gatekeeper reviews found recursive branch traversal, false End presentation,
  duplicate connectors, hidden group diagnostics, missing Filter scenarios, and ambiguous malformed
  route insertion, same-Step output leakage into its source picker, partial-Example required paths,
  stale automatic Example results, incomplete matcher semantics/diagnostics, position-bound matcher
  search, storage-only Choice reload proof, dead matcher binding state, missing complete not-equals
  Creator proof, matcher UI/runtime separation, unmeasured branch drops, and automatic Example
  completion replacing the active picker. Each reachable defect or proof gap was reproduced or
  made explicit and closed;
- the production audit remains three modules and 26 Java files at 10,198 lines. Choice hardening
  added 277 production Java lines over the previous accepted gate. No
  production file, interface, kind, compatibility layer, alternate engine, global store, or
  third-party runtime dependency was added;
- the final clean gate completed in 16 minutes 42 seconds. A settled three-second post-gate process
  inspection found no Playwright, remote-debugging Chrome, Surefire, Failsafe, or test-created
  Railix process; the same user-started Creator and development-child PIDs observed before the gate
  remained running.

Next control-flow slice: branch-aware groups must form a single-entry region and expose every edge
that leaves the region, so collapsing a Choice never hides a route or changes the flat project.

Global reusable groups, automatic group suggestions, branch-aware grouping, and parameter
suggestions remain unsupported. No Item 5 screenshot is current because the item is not 100%.

## 6. Live Runtime, Bounded Work, And Permissions

Status: **Planned**

Goal: make development observable and production behavior bounded.

Scope:

- independently includable project/build metadata, preview/trace, live-error, queue, and metrics
  build capabilities;
- local and remote authenticated Creator attachment with explicit compatibility metadata;
- bounded throughput, timing, resources, uptime, bottleneck, queue, and custom metrics per flow
  and Step without workflow values;
- traces only for explicit examples or user-supplied test contexts, never sampled production data;
- bounded queues, concurrency, deadlines, cancellation, backpressure, and shutdown;
- explicit filesystem, network, process, environment, and other permission requests;
- optional resource measurement and enforceable execution policy.

## 7. Remote Execution, State, Sharding, And Replication

Status: **Planned**

Goal: scale eligible ordinary Steps without turning the application into microservices.

Scope:

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
- `jdeps` module closure, cached `jlink` runtime images, and `jpackage` applications/installers;
- environment-selected development capabilities omitted as code rather than disabled at runtime;
- reproducible artifacts, signing-ready outputs, release smoke, upgrades, and clean-machine proof;
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
