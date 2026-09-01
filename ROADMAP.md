# Railix II Roadmap

Updated: 2026-09-01

## Product Goal

Railix is a product-building system for cross-functional teams. It turns one visual project into
one immutable monolithic application. Java is Railix's current implementation language, not its
product boundary. The functional project is a complete flat graph beginning at one permanent
Application Step. Optional Creator metadata adds semantic zoom and presentation without entering
compilation or changing behavior.

Railix operates on JSON-compatible primitive values. The core remains plain Java,
non-reflective, small, deterministic, and stateless per admitted item. Small unary operations and
larger I/O or business Steps use the same generic Step contract; only graph roles with genuinely
different lifecycle remain distinct kinds.

[ADR 0020](adr/0020-creator-first-application-graph.md) owns the current graph, metadata, and
Creator contract. [ADR 0021](adr/0021-total-and-fallible-primitives.md) owns the finite built-in
unary-operation catalog.

### Target Operating Model — Planned

The following is product direction, not current shipped behavior:

- **One executable model.** Applications are expressed as Flows and Steps. HTTP, MQTT, storage,
  access policy, rate limits, protocol handling, and other application concerns are modeled from
  that same vocabulary. Creator-only Groups, Blueprints, and Templates are reusable authoring aids
  that compile away; production understands only Flows and Steps.
- **Railway-Oriented Programming.** Railix uses this term for the complete application path from
  an external trigger through policy, processing, and storage—not only for success/failure control
  flow. Every supported concern must be expressible through the same Flow and Step model.
- **Example-gated builds.** Named local Examples are intended to be the build gate: every declared
  example must pass before a production artifact can be built. Test-only behavior uses real test
  services or explicit test-client Steps that may be omitted from the production artifact.
  Examples are authored before build; production traffic is never captured as a new Example.
- **Immutable application artifacts.** A built graph never changes in production. Artifacts will
  carry timestamp versions and allow only the small set of environment settings selected at build.
  Whether customers use Git for project persistence and change management remains their choice.
- **Platform identity and secrets.** Railix will own users, roles, and authorization for project
  visibility, editing, local Example execution, builds, deployment, and production attachment.
  Application Flows may enforce product-level authentication and authorization, but cannot grant
  themselves platform authority. Per-environment secrets will be stored as SOPS-encrypted project
  material for explicitly authorized recipient keys or key groups, never as Flow values or
  observability data.
- **Read-only operational visibility.** When explicitly included at build and authorized, Creator
  will attach read-only to an instance and display the executing Flow, throughput, bottlenecks,
  metrics, queues, and errors. It will not edit or invoke production Flows.
- **A decentralised application mesh.** In the intended multi-instance mode, every authorized
  Railix instance can accept work and participate in balancing, restart recovery, and rolling
  replacement. Users declare reachable compute and storage resources; Railix will own the
  placement and operational plumbing. The unit of distributed work—an eligible Step, a Flow, or
  another compiled boundary—has deliberately not been decided. Creator Groups never define that
  boundary.

Railix reduces hand-wired infrastructure configuration; it does not claim that certificates,
authentication, external networks, storage, or operating-system process supervision cease to
exist. The aim is one coherent model that makes those concerns explicit and buildable instead of
spreading them across unconnected application, CI/CD, observability, and orchestration files.

## Progress

- Accepted roadmap items: **2/8 (25%)**
- Checkpoint-weighted Railix integration: **42% published baseline; checkpoint 6.1 is accepted
  locally and will be folded into the next published baseline**
- Railix production certification: **0%**; the full product is not certified by one core gate
- Current item: **6. Live Runtime, Operations, Bounded Work, And Permissions**
- Current item progress: **1/6 checkpoints accepted (17%); 5/6 checkpoints (83%) left**
- Active delivery checkpoint: **6.1 Production Observability Foundation, 7/7 acceptance gates
  closed (100%); 0% left**
- Publication vehicle: **checkpoint 6.1 publication is pending through its dedicated pull request;
  baseline PR #1 merged at `e98aec7`**
- Next roadmap feature: **5.6 Merge/fan-in control slice, 0% integrated; 100% left**
- Core/base certification checkpoint: **Complete, 6/6 gates (100%; 0% left)**
- Feature roadmap: **resumed after the 2026-08-13 core/base clean gate passed**
- Retained incomplete item: **3. Primitive Contract And Standard Library, 4/6 complete
  (67%); 33% left**
- Retained incomplete item: **5. Flow Control, Groups, And Flat Compilation, 5/7 complete
  (71%); 29% left**
- Latest locally accepted checkpoint: **6.1 Production Observability Foundation, 2026-09-01**

Completion percentages count accepted checkpoints, not lines written. A roadmap item reaches 100%
only after its public acceptance gate, coverage report, complexity audit, stability checks,
documentation, and screenshot are complete.

## Retained Foundations

- immutable canonical `RailixValue` primitives;
- bounded deterministic JSON, YAML, and XML normalization;
- three explicit production modules: core contracts/compiler/runtime, standard library, and
  Creator/development tooling;
- explicit Step registration without reflection or runtime scanning;
- one flat functional project model and one runtime execution path;
- deterministic diagnostics and canonical JSON writing;
- Creator-owned rolling child-process lifecycle whose generated development application owns
  Example execution, traces, metrics, and management endpoints;
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
- each group occurrence is one connected region with one derived entry; Creator authors a region
  by selecting either end of one ancestor/descendant path and renders every boundary exit;
- shared occurrences require the same logical slots, Step definitions, and internal route topology;
- compilation builds one immutable authored-order node plan with integer destinations from one
  linear graph index; runtime resolves IDs only at public Trigger, source, and preview boundaries;
- `railix.filter` is an ordinary Step using generic ordered candidate predicates and explicit
  `match` / `otherwise` links;
- `railix.choice` is an ordinary Step using generic ordered OR groups whose values pass through one
  shared Transform program and then independent non-empty AND Matcher programs, with missing
  sources false and canonical JSON values present;
- `railix.switch` is an ordinary Step whose generic top-level `CANDIDATES` input authors one stable
  outcome per case; the first accepted case selects its route and no accepted case selects the
  primary `otherwise` route;
- authored route identities and links are owned by the functional project, optional labels live
  only in Creator metadata, and compiler/runtime/Creator derive both fixed and authored routes from
  the contract;
- nested authored-outcome Steps are rejected because nested programs have no graph destinations,
  and Creator excludes those Steps from nested Transform and Matcher searches;
- Creator renders matcher groups from the generic catalog input, while the rolling-built
  application owns short-circuit execution, failures, routing, and previews;
- Creator lays out every declared route in deterministic iterative depth-first order and edits each
  control route from the Inspector;
- malformed links are rejected at the functional boundary and remain diagnostic-only with Step
  insertion disabled if transient browser state is corrupted.

Checkpoints:

1. **Done (2026-08-06): Flat functional graph.** Remove reusable-flow definitions,
   compiler-generated graph expansion, alternate executable source, and global blueprint storage.
   Structural compilation never invokes handlers. Hardened on 2026-08-12 with one O(V+E) graph
   index, one immutable global node plan, and integer hot-path routing without per-Trigger copies.
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
6. **Partial (2026-08-28, 4/7 control slices accepted):** Filter, Choice, branch-aware groups, and
   Switch are accepted. Filter and Choice are ordinary Steps with generic inputs, explicit
   `match` / `otherwise` links, rolling-built execution and previews, and iterative route
   authoring. Choice additionally proves ordered
   short-circuiting, exact missing/present semantics, predicate failure propagation, restored UI,
   and position-independent editor state. Branch-aware semantic zoom accepts control Steps,
   reversible path boundaries, full connected branch regions through metadata, every exact external
   route, non-primary insertion, topology-equivalent shared edits, nested detach, and metadata-only
   deletion. Sibling boundaries on different paths are rejected explicitly. Switch adds generic
   authored candidate outcomes, node-occurrence-local route identities, metadata-only labels,
   generated per-node route plans, whole-flow execution, and shared-group mutation with different
   concrete route IDs. Nested authored routes fail explicitly. Remaining slices are Merge plus fan-in
   cardinality, Split plus fan-out cardinality, and bounded Loop.
   Choice hardening on 2026-08-11 adds generic Step search aliases, contract-derived
   Matcher/Transform labels, buildable predicate defaults, `value.not-equals`, three CLI example
   templates, canonical shared Transforms plus independent AND Matcher programs, legacy program
   normalization, measured trunk/bus/drop connectors, and targeted automatic-result rendering that
   cannot replace an open Step picker. No matcher kind or alternate execution path was added.
7. **Planned:** per-Trigger and per-Step test input, fixtures, assertions, reports,
   stop-before-side-effect behavior, and proof that every terminal path satisfies Trigger results.

Current evidence:

- The generated production application contains direct static Step calls, lowered input resolvers,
  constants, and integer routes. It contains no `StepDefinition`, binding hierarchy, alternate
  executor, or runtime Step scan; runtime behavior is proven through generated child JVMs. The
  generator compiles each node fragment once for both application variants, reuses the canonical
  immutable Step definition during compilation, and emits byte-identical production, development,
  and launcher sources after the cleanup.
- Step definitions retain one immutable class-literal-derived implementation address. Locked
  third-party implementations must be owned by their root bundle and compile failures return stable
  diagnostics without exposing `javac` output.
- Production requests allocate no Step trace; application-owned Example traces and explicit
  development trace requests alone capture Steps, resolved inputs, candidate selections, and
  nested stages.
- Generated constants are initialized once in bounded 16-node partitions; routing methods remain
  bounded at 128 nodes. Each admitted item owns one shallow mutable
  context root, lazily thaws only written containers, freezes only at observation/response
  boundaries, and uses detached copy-on-write only for atomic multi-write Steps. Isolated owner
  tokens prevent borrowed containers from retaining prior event frames.
- Generated synchronous calls return primitive outcome indexes; one stored terminal `RunResult`
  represents rejection, failure, or cancellation. No success-path routing object, exception,
  future, or scheduler remains between a Step and its authored integer destination.
- A real 129-Step production flow executes 322,500 verified Steps with zero traces. The clean
  advisory reports 86,552 allocated bytes and 34,543 median nanoseconds per call: 670 bytes and
  267 nanoseconds per Step, with 2,632 retained bytes. A one-million-call soak stays inside a
  64 MiB heap with 127,512 retained bytes. The single-Step boundary and escaped result each report
  3,392 allocated bytes per call; median single-call latency is 622 nanoseconds, and the array-read
  calibration reports 103,097 bytes per call. Allocation and latency are advisory and never fail
  the build; constrained-heap, retained-growth, trace, execution, and correctness assertions remain
  acceptance gates.
- The maximum 16,384-node generated monolith executes all 16,381 ordinary Steps exactly once in
  authored order. Generated applications currently admit at most 512 Triggers; exact-boundary
  production and development builds pass, while Trigger 513 is rejected before source allocation.
  A depth-seven exhaustive branch tree resolves all 128 leaves uniquely, while 2,000 concurrent
  contexts remain isolated and 2,000 external effects occur exactly once.
- Cross-process application publication uses one project-local OS file lock outside the artifact
  directory. Sixteen simultaneous packaged cold starts share the atomic cache without deleting
  active staging or exposing partial output; abandoned staging and failed child startup remain
  cleanup-proven.
- Every Creator API read and mutation requires the per-process random token and exact loopback
  `Host`; mutations additionally require same-origin browser requests and exact JSON content type.
  Stored Creator presentation values are HTML-encoded, including nested group breadcrumbs.
- Real-process lifecycle scenarios prove bounded Creator forwarding, graceful accepted-work drain,
  synchronous and repeated close, interruption preservation, process-tree termination, port release,
  bounded diagnostic retention, and object retention. The authenticated loopback startup protocol
  rejects wrong-token, partial, oversized, and stalled callbacks before accepting the real generated
  application callback. These cases use a locked third-party Step bundle and real child JVMs.
- A complete test-boundary audit found zero fake or direct in-process runtime implementations:
  compiler/model and generated-source checks remain structural, while runtime semantics use real
  generated artifacts, child JVMs, HTTP, CLI, or browser boundaries. Five unique HTTP cases were
  consolidated into the canonical generated-child suite; one duplicate 33-case integration suite,
  two obsolete handler files, and 26 unreferenced nested test handlers were removed.
- Clean `./mvnw clean verify` passes **2,439/2,439 tests** with no failure, error, or skip: core 815,
  standard library 72, Creator Surefire 1,187, desktop browser 322, package and soak 42, and mobile
  browser 1. The complete local gate takes 48:34; the two real desktop suites pass 128 and 194 cases
  in 2,259 and 2,353 seconds and remain a measured contribution-speed target, not a reason to
  replace public-boundary proof with mocks.
- Clean aggregate JaCoCo is **95.0777% lines (9,117/9,589)** and **90.4042% branches
  (4,607/5,096)**. The visible non-failing 95%/90% target is met without changing the build into a
  coverage gate.
- The generated development dispatch avoids the JDK 25 conditional-method-reference compiler
  crash through one explicit observation branch. A source-shape regression guards that contract,
  and clean desktop/mobile Failsafe reports contain no compiler exception, NPE, `LambdaToMethod`,
  or conditional-tree crash.
- Current production inventory is three modules, 35 Java files, 19,729 Java lines, and 26,567
  Java/JavaScript/CSS lines with no third-party Maven runtime dependency. Checkpoint 6.1 adds one
  cohesive Example-suite owner and no module or dependency. The executable Creator JAR is 602,390
  bytes and its host application image is 139 MiB. The image includes every stable build-JDK module
  so Creator can compile and run arbitrary locked Step bundles without ambient Java; unsupported
  incubator modules are excluded, and per-project minimal images remain Item 8. Packaging rejects
  empty output paths and JDKs without `java.base.jmod` before cleanup or linking begins.
- Clean concurrency proof completes 2,000 isolated calls and 2,000 external effects exactly once,
  with all 64 workers overlapping and no production traces. Post-gate inspection found no owned
  Railix application or browser process.

Next delivery sequence:

1. **Done:** lower generated synchronous routing to direct integer ROP basic blocks and remove the
   remaining per-Step routing result objects without changing the public Step contract;
2. **Done:** prove every runtime assertion uses locked bundles, generated artifacts, child JVMs,
   real HTTP, CLI, or browser boundaries rather than an alternate executor or application double;
3. **Done:** remove duplicate runtime suites, obsolete support handlers, and stale test seams, then
   run negative searches for alternate execution paths and fake applications;
4. **Done:** run the clean reactor, generated-runtime benchmarks, rolling-build/lifecycle proof,
   process inspection, and production complexity audit;
5. **Done:** publish exact current evidence without presenting the advisory coverage target as a
   build failure;
6. **Done:** close the public-boundary coverage deficit without padding, rerun clean certification,
   and record 95.2033% lines and 90.0104% branches in the separate advisory coverage report;
7. **Done:** implement and accept Switch as an ordinary control Step through the generic authored-
   outcome candidate contract, generated child applications, shared groups, and browser E2Es;
8. **Queued:** implement and accept Merge/fan-in, then continue with Split and bounded Loop.

PR-readiness hardening is tracked separately from feature completion: one root build, one hosted
verification workflow, a checksum-verified Maven wrapper, minimal cross-editor settings, pinned
lifecycle plugins, host/browser requirements, compiler bounds, cross-process build ownership,
quiet deterministic packaging, and contributor rules are integrated.
The public target is `NanoNative/nano_railix` and its Apache-2.0 license is preserved. Publication
status does not change Item 5 completion.

Global reusable groups, automatic group suggestions, and parameter suggestions remain unsupported.
No Item 5 screenshot is current because the item is not 100%.

## 6. Live Runtime, Operations, Bounded Work, And Permissions

Status: **In progress; 1/6 checkpoints accepted (17%)**

Goal: make development observable and production behavior bounded.

Scope:

- explicit suspending Steps begin only as one lifecycle-complete slice: top-level suspension,
  bounded admission, ingress-owned deadline/cancellation, idempotent application shutdown,
  one-shot inline resume by integer node, and constrained-heap/classloader-retention proof;
- independently includable project/build metadata, preview/trace, live-error, queue, and metrics
  build capabilities;
- local and remote authenticated, read-only Creator attachment with explicit compatibility
  metadata and build-selected endpoints; production attachment never edits or invokes a Flow;
- platform user identity, role, and per-environment capability authorization for project editing,
  Example execution, build, deployment, and production attachment;
- SOPS-encrypted, recipient-key or key-group-controlled environment secrets that never enter Flow
  JSON, diagnostics, traces, metrics, or Creator exports;
- bounded throughput, timing, resources, uptime, bottleneck, queue, and custom metrics per flow
  and Step without workflow values;
- deterministic configuration precedence, named environments, explicit placeholders, and restart
  behavior without hidden live mutation;
- bounded issue and diagnostic registries with explicit overflow behavior, plus keyed rate limits
  whose key, capacity, queue, and rejection paths are visible in the Flow;
- bounded metric names, labels, and cardinality with an explicit overflow series instead of
  unbounded production memory;
- traces only for explicit examples or user-supplied test contexts, never sampled production data;
- bounded queues, concurrency, deadlines, cancellation, backpressure, and shutdown;
- explicit filesystem, network, process, environment, and other permission requests;
- optional resource measurement and enforceable execution policy.

The synchronous generated route remains a separate zero-scheduler fast path. Suspension is not
accepted as a callback-only API because an abandoned callback would retain its complete event
context without a lifecycle owner.

Checkpoints:

1. **Accepted (7/7 gates closed; 100%, 0% left): Production observability foundation.**
   Development applications
   expose build-selected trace and metric surfaces that are physically absent from production
   artifacts. The generated development application reads its compiled Example manifest and owns
   automatic chunked execution, bounded trace storage, projections, and metrics. Each Example makes
   one ordinary Flow pass with `context.runtime.test=true`; Creator only builds, starts, stops,
   restarts, and reads authenticated management data. The application streams inputs, nested stages,
   compact context changes, terminal results, and deterministic trace errors without replaying a
   Step or capturing production traffic. It retains at most 64 MiB per Example and 256 MiB per suite
   and serves two-reader bounded, ready-to-display summary and selected-Step JSON projections. The
   all-Example projection for one Step has one owner from frozen snapshot through response transport;
   concurrent reads allocate no second case snapshot. It rejects total source replay above 64 MiB
   before parsing. Timed-out workers receive five seconds for cooperative exit; a worker that still
   retains execution terminates its generated development process rather than leaking indefinitely.
   Bounded request-body reads complete before run or trace admission.
   Compact suite status and one-case status avoid transferring the full inventory during polling. Metrics use
   fixed-cardinality atomic arrays: execution/error/cancellation counts are exact, Flow timing is
   exact, graph-Step timing samples one in 1,024 executions, and reads stream JSON, Prometheus, or
   Influx output. Example executions never increment operational metrics.
2. **Planned:** lifecycle-complete suspending Steps with bounded admission, deadline, cancellation,
   shutdown, and retained-context proof while preserving the synchronous fast path.
3. **Planned:** independently includable development capabilities, named environments, deterministic
   configuration precedence, and build-time physical omission per capability.
4. **Planned:** authenticated local/remote read-only attachment plus platform identity, roles,
   authorization, and encrypted environment-secret delivery.
5. **Planned:** bounded queues, backpressure, diagnostics, live errors, rate limits, metric
   cardinality, and explicit overflow behavior.
6. **Planned:** declared filesystem/network/process/environment permissions, resource measurement,
   and enforceable execution policy.

Checkpoint 6.1 acceptance gates:

1. **Closed:** compile project Examples once into the development application; no Creator-owned
   Example scheduler, execution, trace storage, or result fallback remains.
2. **Closed:** expose application-owned authenticated Example inventory, trace projection, and
   metrics APIs through bounded reads; Creator has read-only proxies and no execution endpoint.
3. **Closed:** production generation and packaging physically exclude Example manifests,
   development runtime classes, traces, metrics, and management endpoints.
4. **Closed:** clean verification reruns every converted Creator, generated-application, browser,
   package, lifecycle, and soak E2E after the ownership change: 2,439/2,439 tests pass with no
   failure, error, or skip.
5. **Closed:** selected-node metrics use fixed open-addressed indexes and exports distinguish exact
   primitive counter, lookup, and total bytes without changing the production fast path. Committed
   generated-application E2Es prove bounded streaming for 4,096 Step series; the direct fixed-store
   contract proves exact accounting for 16,384 series, which is also the current compiler node
   limit. Compiler scaling beyond 16,384 nodes remains unaccepted.
6. **Closed:** clean `./mvnw clean verify` passes in 48:34 with advisory aggregate coverage of
   95.0777% lines and 90.4042% branches. The one-million-call 64 MiB soak retains 127,512 bytes;
   process inspection finds no owned Railix, Playwright, or project Maven process after shutdown.
7. **Closed:** the [accepted production-observability screenshot](screenshots/roadmap-item-6-1-production-observability.png)
   shows the real Maven-built Creator, selected Example path, application-owned result, and
   connected Step/Flow metrics.

Protocol-specific Trigger test clients and Example assertions remain later checkpoints; current
Examples begin at the generic Trigger output after protocol conversion.

## 7. Decentralised Execution, State, Sharding, And Replication

Status: **Planned**

Goal: scale one application through a decentralised Railix mesh without turning it into
microservices or adding a user-managed orchestration layer.

Scope:

- define member discovery, authenticated mesh membership, resource declarations, work ownership,
  crash/restart takeover, and decentralised rolling replacement;
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
