# Railix II Roadmap

Updated: 2026-09-10

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

- Accepted complete roadmap items: **2/8 (25%)**; partial checkpoints are reported per item,
  not combined into an unmeasured estimate of engineering effort
- Railix production certification: **Not certified**; one core gate does not certify the full product
- Current item: **5. Flow Control, Groups, And Flat Compilation**
- Current item progress: **4/7 checkpoints accepted (57%); 3/7 checkpoints (43%) left**
- Latest delivery checkpoint: **5.3 Semantic Zoom & Group Management; locally verified;
  6/6 correction gates complete (100%); 0% left for this checkpoint**
- Publication vehicle: **[PR #4](https://github.com/NanoNative/nano_railix/pull/4)** combines
  Production Observability Foundation with checkpoint 5.3 and its Inspector/diagram follow-up
- Current delivery: **5.3 Inspector and diagram follow-up; 4/4 acceptance gates complete
  (100%); 0% left, locally accepted 2026-09-07.** The earlier six-gate checkpoint remains verified separately.
- Work resumed on **2026-09-07**, with the requested weekly-usage stop threshold changed to
  **10% remaining**. Full clean verification and native-app visual review are complete for that
  September 7 checkpoint, not a new certification of the scaling work below.
- Current technical delivery: **Compiler and observation scaling; 3/3 scoped gates verified
  (100%; 0% left), locally accepted 2026-09-08. No renderer replacement is included.**
- Scoped observation ingestion is complete. Streamed project/source processing remains open.
  Feature work remains **5.6 Merge/fan-in, then Split and bounded Loop**.
- Core/base certification checkpoint: **Complete, 6/6 gates (100%; 0% left)**
- Feature roadmap: **resumed after the 2026-08-13 core/base clean gate passed**
- Retained incomplete item: **3. Primitive Contract And Standard Library, 4/6 complete
  (67%); 33% left**
- Retained incomplete item: **6. Live Runtime, Operations, Bounded Work, And Permissions,
  1/6 complete (17%); 83% left**
- Latest technical verification: **Generic Metric Contract (2026-09-10)**.

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

Status: **In progress (4/7 checkpoints, 57% complete; 43% left)**

Goal: compose large applications without hidden graph behavior or compiler-only visual models.

Current contract:

- `railix.project.json` contains only `format`, `id`, fully materialized `nodes`, and `links`;
- `railix.creator.json` optionally contains `format`, visual `groups`, and Step presentation;
- compiler and application never read Creator metadata;
- stable opaque UUID-backed node and group IDs encode no order;
- each ordinary Step has at most one optional group assignment; groups may remain empty;
- Group Manager owns group name, color, embedded icon, and boundary style while Step Appearance
  owns assignment;
- deleting a group only unassigns its Steps and never changes the functional project;
- one region is derived for each connected component assigned to a group, so one group may render
  as multiple disconnected regions without persisted occurrences or geometry;
- continuous pan, zoom, Fit, group focus, scale-based detail, and deterministic automatic branch
  regions are ephemeral Creator state;
- creating or inserting a Step never inherits a group, and edits never propagate through groups;
- strict valid format-1 metadata migrates to canonical format 2; invalid legacy source is preserved
  while the flat functional graph remains available;
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
3. **Done (2026-09-06): Semantic Zoom & Group Management.** User review
   accepted continuous zoom; all six requested correction gates are now locally verified:
   (1) one diagram combining build state, Example paths and measured traffic/heat, without view modes
   or discovery prompts; (2) Example selection beside Trigger definitions, retained during inspection;
   (3) normal-sized collapsed Group and automatic-region summaries; (4) clickable visible node bodies
   without prior focus; (5) Examples use ordinary execution metrics with no measurement exclusion;
   (6) regression, performance/ownership review, clean verification and a refreshed review application.
   Focused regression evidence includes 18 Creator editor browser cases,
   six renderer gesture/size cases, 10 generated-runtime scenarios and 15 generated-variant tests.
   Public RED evidence preceded the corrections. Clean acceptance also proves fail-closed metric
   documents, omission of unprovable capped-route counts, visible Trigger Example counts alongside
   normal execution counts, and retained selection while inspecting App build facts.
   The native Creator review project has 143 nodes and five Examples; all five execute in the
   built application and count in normal metrics. One deliberately unvisited route leaves 141/142
   executable Steps reached. No view mode, discovery prompt, or extra execution policy remains.
   Examples remain application-owned input cases, not assertions;
   they begin at Trigger output and use the same compiled flow. Creator only observes them.

   **Accepted: Inspector and diagram follow-up (2026-09-07), 4/4 gates, 100%; 0% left.**
   Four acceptance gates:
   (1) closable Inspector, selected-Example values beside source/target and field choices,
   no duplicate Built example/output sections, preserved intermediate results and local failures,
   and clearer "Measure this Step" wording;
   (2) aligned chain centers and canonical orthogonal connectors with shared branch trunks;
   (3) common Step/Group shape presets, corner rounding and proportions, stored only in Creator
   metadata, with matching connection outlines and hit areas;
   (4) bounded throughput animation from existing measured counter deltas, independent static
   Example highlighting and heat, no particles per request or extra runtime execution path.
   Each gate includes public regression proof and visual review. Motion must expire on stale
   observations and pause for zero/unavailable traffic, hidden pages, reduced motion, context
   loss and disposal. Animation-only frames must reuse uploaded geometry without label churn.
   **Implementation checkpoint (2026-09-07).** Inspector values, close/reopen behavior, corrected
   branch alignment, common shape controls and bounded animation passed clean verification and
   updated native-app visual review. The earlier pause at
   49% weekly allowance was superseded by permission to continue until 10% remains.

   Public regressions established the grouped 4/8-branch row mismatch before correcting invisible
   index transforms. All 92 scene/editor HTTP cases then passed, including 3/4/8-way shared-trunk
   checks, shape persistence, metadata-only application identity, and invalid shape inputs.
   Browser review caught and fixed missing labels on small unselected shapes. The 11 focused
   correction cases passed, including stale observations, graphics restoration, reduced motion,
   and rates of 1/100/1,000,000 with zero animation-only geometry uploads or label replacements.
   Four separate outline-hit cases also passed. These rate cases exercise the renderer's public
   input contract; they do not claim a million-request application benchmark.

   The prior Inspector regressions cover missing versus null/false/zero/empty, real writable
   before/after values, selected-case field choices, unvisited Steps, Escape order, and invalid JSON
   drafts across close/reopen. Trigger examples begin at output, so Trigger targets show that known
   output once without inventing a before value. A static close button survives blur-triggered
   rerenders. The full suite caught a historical helper-name collision; the read-only projection
   selector is now named observedExampleContext, distinct from removed browser execution logic.
   Clean verification also exposed outdated browser assertions for the renamed metrics heading
   and intentionally collapsed long values. The tests now use the new heading and open the real
   value summary before inspecting it. CreatorEditorBrowserIT is included in Maven's responsive
   execution so its two tagged cases no longer depend on a separate manual invocation.

   Native review reproduced a shared-trunk paint-order defect and fixed-width status hatches
   escaping narrow shapes. Public renderer regressions established both failures before the fix.
   Link painting now retains active traffic and paints selected Example paths last. Pending marks
   fit the shape's content bounds; triangle content uses an inscribed rectangle. All 31 focused
   follow-up cases passed, including these ten renderer cases and the existing long-value cases.

   Existing files own all behavior: CreatorDocument validates optional shape metadata; CreatorScene
   preserves aligned lanes; app.js/app.css/index.html own Inspector editing; world.js owns bounded
   geometry, shared contour hit/port logic and shader animation. No module, dependency, Step kind,
   compiled runtime path, or persisted camera/layout model was added. Motion expires after three
   seconds; animation-only redraws run at most 30 times per second. Example highlighting stays independent.

   Final clean verification: ./scripts/coverage.sh passed in 33:05 with 2,760 tests, zero failures,
   errors or skips: core 815, standard library 72, Creator/generated application 1,423, desktop
   browser 403, package/soak 42, responsive browser 5. Authored Java coverage is 95.44% lines
   (10,299/10,791) and 90.47% branches (5,414/5,984); coverage remains advisory, not a build gate.
   The 6,003-node navigation check recorded desktop/mobile viewport-request p95 of 1.500/1.664 ms
   and CPU draw-submission p95 of 0.300/0.300 ms. These are machine-specific observations, not GPU
   completion timings or million-Step certification. Animation rate tests are renderer contract
   tests, not fabricated application load. Native review used 143 nodes and five real Examples,
   reaching 141/142 executable Steps; straight terminal lanes, continuous selected branch trunks,
   outline hit areas, group shapes and Inspector controls were checked on the packaged app.
   [Accepted Inspector screenshot](screenshots/roadmap-item-5-3-inspector-diagram.png).

   Final clean acceptance log: /tmp/railix-followup-acceptance-20260907-accepted.log. Focused evidence:
   /tmp/railix-shape-scene.log, /tmp/railix-ui-corrections.log, /tmp/railix-shape-hits.log.
   /tmp/railix-visual-regressions-red.log and /tmp/railix-visual-regressions-green.log record the
   final rendering findings and their corrections. The earlier full run in
   /tmp/railix-followup-acceptance-20260907-final.log had one stale assertion and two collapsed-value
   test timeouts; it is not successful acceptance evidence.
   Final command remains ./scripts/coverage.sh; never overlap Maven runs. Integration-test by itself
   can print BUILD SUCCESS with failures: inspect reports or run failsafe verify as well.
   The earlier review project remains at /private/tmp/railix-review-20260906; its old process was
   no longer running when checked on September 7. The refreshed 143-node, five-Example project is
   /private/tmp/railix-review-20260907/railix.project.json. The verified native review runs on port
   65121 with Creator PID 21200 and generated-app PID 21202 at this checkpoint. Post-verification
   process inspection found no remaining Playwright workers or test JVMs; only this intentional
   Creator/application pair remains. The existing compiler size limits and later roadmap scope
   are unchanged.

   Publication review (2026-09-08) corrected the manifest-test artifact layout: rewritten JARs
   now retain a project-local build directory and lock instead of escaping the temporary workspace
   and failing on Linux at /build.lock. A failing containment regression preceded the correction.
   Verification with ./mvnw -pl modules/railix-creator -am -Djava.io.tmpdir=/tmp
   -Dtest=RailixValueNullContractTest,PrimitiveStepsCreatorProjectE2eTest,DevelopmentRuntimeGeneratedApplicationE2eTest
   test passed all 256 selected cases in 4:29, with zero failures, errors or skips. This follow-up
   changes only test setup and documentation, not production behavior. No test workers remained.

   Previous local baseline: the earlier
   implementation scaled all DOM cards and drew region boxes. That did not satisfy recursive
   zoom or bounded rendering. Its screenshot and 6,001-card test are not acceptance evidence for
   those requirements. The baseline closed seven gates: metadata-only Group Manager with reusable
   disconnected identities and non-destructive deletion; recursive bounded viewport rendering;
   state-derived learning guidance; server-owned scenes and bounded editor loading with stable-ID
   edits; Build/Example/Live observations with activation identity and bounded response lifecycles.
   Comprehensive desktop/mobile lifecycle proof and measured supported-scale acceptance complete
   the gate. Acceptance is local and scoped to the documented compiler and rendering limits.

   One functional project file remains canonical. Only authored group/presentation choices may
   persist separately. Hierarchy, coordinates, scene data, camera, and automatic regions are
   derived. Native WebGL2 renders visible stations and rails with bounded accessible DOM labels.
   Application observations never change geometry. Example execution stays in the built app.
   Live heat must use actual sampled timing, not invented p95 or an unproven bottleneck label.

   Stable-ID saves use compact revision-checked receipts rather than whole-project round trips.
   Functional and presentation acknowledgements remain independent; stale edits preserve the draft
   without overwriting accepted work. No edit history or additional persistent format is introduced.
   The browser loads a bounded editing neighborhood; canonical indexes, memberships and global
   usage counts stay on the server. Regressions cover delayed reads across newer saves, invalid
   drafts across navigation, unloaded Group members/icons, same-batch flow deletion/insertion,
   repeated deletion IDs, and unnamed Group search. The review removed repeated unchanged-preview
   redraws, duplicate response readers and an unnecessary edit utility type. Shared runtime tests no
   longer launch unrelated automatic process-tree Examples; dedicated real-process ownership tests
   retain exit assertions and failure cleanup.
   Further review fixes delayed navigation overriding Fit/selection, deferred picker refresh,
   collision-prone route captions, and legal Group IDs colliding with scene focus aliases.
   Runtime metrics remain behind a
   disclosure; the metrics build setting stays visible. Browser suites reuse one harness and split
   by behavior for bounded worker scheduling, without deleting scenarios.
   Rejected/superseded node selections cannot replace a newer Inspector selection.
   Group search replaces results and page
   controls together without losing the focused search field. Deletion assertions wait for the
   visible result rather than assuming build completion also completes the canvas refresh.

   Focused September 5 evidence: 94 editor/edit HTTP cases, 45 scene HTTP cases, 35 observation
   cases, real desktop/mobile authoring and lifecycle regressions, and 12 response-boundary cases.
   A 6,003-node project completes 1,800 measured desktop navigations with at most 21 visible glyphs,
   60 rail segments and 8 labels; viewport request p95 is 1.274 ms and WebGL CPU submission p95 is
   0.200 ms on the recorded host. The final five post-GC JS samples are identical; DOM, listener,
   scene-map and vertex-buffer counts remain bounded. Native/embedder growth also reproduces in
   a script-free 1,800-fetch Chrome control. Its exact contribution to world growth remains
   unresolved; these measurements do not establish browser-wide leak freedom or GPU frame time.
   Mobile at 320 pixels proves recursive aggregate-to-region-to-Step reveal and compact overview
   labels. A separate 6,002-node real Example/metric run returns bounded viewport observations.
   The final clean run passes all 2,690 cases. Its 60-navigation desktop/mobile probes report
   viewport request p95 of 1.796/1.998 ms and CPU draw submission p95 of 0.300/0.300 ms, respectively.
   These short probes include warmup growth; the sustained retention evidence above is separate.
   [Accepted group/Example view](screenshots/roadmap-item-5-3-semantic-zoom-group-management.png),
   [6,003-node overview](screenshots/semantic-zoom-overview.png), and
   [real Step detail](screenshots/semantic-zoom-detail.png) are captured from the working Creator.

   The September 8 scaling work below removes the earlier total-node, Trigger-count and local
   source-size ceilings. No million-Step readiness claim follows from a bounded renderer.
   Rendering budgets are 2,048 glyphs, 4,096 rail segments,
   256 labels and 2 MiB per response. Latency and retained-memory measurements are advisory, never
   build byte or timing gates.
4. **Partial (2026-08-28, 3/6 control Steps accepted):** Filter, Choice, and Switch are accepted.
   Filter and Choice are ordinary Steps with generic inputs, explicit
   `match` / `otherwise` links, rolling-built execution and previews, and iterative route
   authoring. Choice additionally proves ordered
   short-circuiting, exact missing/present semantics, predicate failure propagation, restored UI,
   and position-independent editor state. Switch adds generic authored candidate outcomes,
   metadata-only labels, generated per-node route plans, and whole-flow execution. Nested authored
   routes fail explicitly.
   Choice hardening on 2026-08-11 adds generic Step search aliases, contract-derived
   Matcher/Transform labels, buildable predicate defaults, `value.not-equals`, three CLI example
   templates, canonical shared Transforms plus independent AND Matcher programs, legacy program
   normalization, measured trunk/bus/drop connectors, and targeted automatic-result rendering that
   cannot replace an open Step picker. No matcher kind or alternate execution path was added.
5. **Done (2026-09-01): Public rejection and recovery proof.** Reject malformed format-2 groups,
   unknown assignments, unsupported App/Trigger assignments, invalid boundaries and icons, and
   unsupported fields. Validate format 1 strictly before migration, prefer its deepest overlapping
   occurrence, preserve invalid source, and prove flat startup fallback without restarting the
   application.
6. **Planned:** Merge plus fan-in cardinality, Split plus fan-out cardinality, and bounded Loop.
7. **Planned:** per-Trigger and per-Step test input, fixtures, assertions, reports,
   stop-before-side-effect behavior, and proof that every terminal path satisfies Trigger results.

### Compiler And Observation Scaling

Status: **Locally accepted (2026-09-08); 3/3 scoped gates verified (100%; 0% left).**
This technical checkpoint does not change the feature roadmap's 2/8 accepted items or item 5's
4/7 accepted checkpoints.

1. **Done:** automatic class/method partitioning replaces the 16,384-node and 512-Trigger ceilings.
   Production and development JARs execute 16,385 ordinal Steps exactly once and in order. A
   513-Trigger build passes; six 128-Step flow cases with 16, 17 and 258 outcomes per Step prove
   the first and last outcomes through inline and extracted routing. The Step contract, dependency
   set and one-JAR runtime boundary are unchanged.
2. **Done:** empty runtime inputs allocate their value map only when populated. The controlled
   Java 25 interpreter probe measured 320 to 256 bytes per no-input call; this is not a throughput
   claim or a build gate. Compiler metric indexes use primitive arrays only for development;
   assembly avoids a second project-sized output buffer. Viewport aggregation no longer allocates
   per-project counter prefix tables. Local files and incremental edits can exceed 1 MiB, while
   HTTP request, UTF-8, graph and runtime input validation remain enforced.
3. **Done:** final package, generated-JAR, observation, concurrency and memory verification.
   Review corrected constructor diagnostic mapping after extraction and a wide-routing bytecode
   overflow. A real child-process regression also reproduced shutdown failure after workspace
   deletion; cleanup now accepts an already-removed workspace without hiding other I/O failures.
   Timing and allocation measurements are advisory; execution, isolation and retention checks
   remain assertions.

The production-code delta is 48 net lines across six existing files, with no new maintained
production types, modules, dependencies or Step-definition options. Generated helper classes are
an internal compilation detail, not separately deployed services. Small tables remain inline.

Verification: the Maven app image was rebuilt after review. The complete non-browser acceptance
command `./mvnw -pl modules/railix-creator -am test '-Dtest=*,!*BrowserIT' -Drailix.test.forks=2`
passed **2,379 tests, zero failures/errors/skips, in 11:02**: 835 core, 72 standard-library and 1,472
Creator/runtime/package cases. This includes 35 packaged-launcher and seven production-soak cases.
The separate controlled probe
`env JDK_JAVA_OPTIONS=-Xint ./mvnw -pl modules/railix-core -Dtest=WorkflowRuntimeInputsTest -Djacoco.skip=true -Drailix.test.forks=1 test`
passed all 20 cases and measured 256 bytes per no-input call. Process inspection found no remaining
test/browser workers. Expected permission-denial tests still report cleanup failures and prove
successful retry. Browser suites and full coverage certification were not rerun for this batch.

Remaining before million-Step acceptance: streamed functional JSON and generated compilation
units; incremental structural indexing; chunked Example manifests and trace access; scoped or
incremental metric ingestion; and hierarchical route aggregation instead of traversal cutoffs.
The current 32,768-character per-Step lowering and 4 MiB Example-manifest bounds remain explicit.
Hardware-limited project capacity is the target, not a claim established by the 16,385-Step proof.
PixiJS or another renderer is not part of this delivery.

### UI Data-Path Readiness

Status: **Complete, 3/3 scoped gates verified (100%; 0% left), locally accepted 2026-09-08.**

Acceptance gates:

1. Complete hierarchical connections, including dense chain and wide-branch views. View budgets
   coarsen detail instead of truncating connectivity; no project node ceiling is introduced.
2. Scoped application-owned metric and Example membership queries, with bounded batching,
   independent capability availability, artifact/PID checks, deadlines, and no production hooks.
3. Real HTTP, browser, generated-artifact and resource verification; advisory large-scene evidence;
   review and documentation of remaining capacity limits before renderer work.

The 1.1-million-Step chain scene measurement indexed in 5.04 seconds and prepared overview plus
queries at 0.57 ms p95, with 325 bytes across its query bodies. This exercises derived scene
preparation, not million-Step compilation, browser rendering, or request throughput. Real 6,002-node
application observation polls measured 39.44 ms p95 without a selected Example and 35.07 ms with one.
Timings are advisory, not build gates. The 6,003-node desktop browser probe measured 1.26 ms p95
viewport responses and 0.30 ms p95 CPU WebGL submission, with at most six visible glyphs and
24 label DOM elements. This is not a GPU-completion or leak-freedom claim.

Verification and review:

- `./mvnw clean verify -Drailix.test.forks=2` ran 2,881 cases in 56:53: 2,431 non-browser,
  403 desktop, 42 package/soak, and five mobile cases. It found two browser failures, so that
  invocation is recorded as failed, not relabeled green after corrections.
- A controlled real-response browser regression reproduced an older camera focus hiding a newly
  added Step. Directly requesting the new focus replaces the refresh-then-focus sequence and
  removes its unused camera-version accessor. The other failing test clicked a separate text label
  instead of empty space outside a shape; its corrected probe checks that distinction and waits
  for selection handling to settle.
- The final source was verified in an isolated build directory, checked against the workspace before
  cleanup. Complete Inspector, World and Data Workbench suites passed all 137 desktop cases;
  the added many-flow compilation/scene case also passed (138 Creator cases, 13:04). All five
  responsive cases plus the focus regression passed at 320 pixels (six mobile cases, 1:38).
  These reruns used `./mvnw -pl modules/railix-creator -am test -Dtest=...` with the upstream
  `StepContractJsonE2eTest` and `PrimitiveStepsCreatorProjectE2eTest` smoke suites and
  `-Djacoco.skip=true`; mobile set `-Drailix.browser.viewport.width=320`.
- Final workspace `package` plus `jacoco:report-aggregate@coverage-aggregate` passed with the two
  upstream smoke suites and `GeneratedApplicationE2eTest#manyFlowSceneKeepsItsAppAndFocusedTriggerAccessible`.
  The rebuilt native launcher then passed all 35 `RailixPackageIT` cases. Full-suite Java execution
  data was preserved before focused reruns: aggregate coverage is **95.53% lines / 90.82% branches**
  (10,598/11,094 lines; 5,590/6,155 branches). These are Java, not JavaScript, coverage figures.
- Final process inspection found no test JVMs or Playwright workers. The existing user review
  Creator and child were preserved. The temporary verification directory was removed.

The production delta is **407 net lines in seven existing files**, with no new production files,
modules, dependencies, Step-definition options, or production-runtime hooks. Static scene membership
and completed Example reach intervals replace repeated whole-observation ingestion and trace replay.
The runtime query changes received a separate runtime-agent review; the complete diff was reviewed
again after the browser corrections.

This closes the pre-renderer data-path checkpoint, not million-Step application certification.
Streamed compilation, incremental structural indexing, chunked Example manifests and indexed trace
detail remain listed above. Explicit application metric totals still scan flow counters; Example
queries snapshot the union bitmap. Full CI duration was measured, not reduced by this checkpoint.
No renderer migration, commit or push is included in this delivery.

### Generic Metric Contract

Status: **Implemented; shared HTTP/browser contract verified in both branch variants.**

The application describes metrics once through `/v1/metrics/catalog`. Bounded queries select
metric IDs and application/flow/Step/process scopes. Creator relays the definitions, aggregates
by their declared operation, and keeps metric values separate from scene/Example fields.
The Inspector renders available descriptors; rates and averages remain frontend calculations.
New presentation of existing measurements needs neither a Creator Java change nor an app rebuild.
New instrumentation, custom metrics and historical windows are not claimed by this delivery.

Review removed per-metric full-counter reads in grouped queries, retained direct atomic slot
reads, and corrected invalid-catalog handling to HTTP 502 without caching the invalid response.
No Step-definition change, new dependency, production module or recording hook was added.
Existing JSON/Prometheus/Influx fields and units remain stable. Explicit application totals
still scan flow counters; observations are not transactionally atomic across Steps or batches.

Verification on September 10 used Maven with at most two concurrent test forks:
- Shared integration: 379 Creator/runtime cases plus 8 core and 72 standard-library cases,
  all passed in 4:53. Includes the full 280-case generated-runtime suite, 44 scene-observation
  cases, 30 metric-contract cases, four metric browser cases and 21 upstream failure/deadline cases.
- Isolated base branch: 194 Creator cases plus the same 80 upstream cases, all passed in 6:24.
  Includes full Workspace and World browser suites, scene and metric-contract suites.
- Final catalog-boundary regression: 55 Creator cases plus the same 80 upstream cases passed
  separately in each variant (base 1:37; factory UI including native packaging 1:49).
  Includes seven rejected/recoverable catalog cases, 44 scene-observation cases and four
  metric Inspector/cache browser cases. Descriptor validation runs once before caching,
  not again on every scene poll.
- The 20,000-Step derived-scene check measured 0.173 ms p95 viewport/query preparation;
  real 6,002-node observation polls measured 39.571/37.621 ms p95 without/with an Example.
  These advisory measurements do not certify million-Step compilation, request throughput or leaks.

The factory renderer, vendor assets, HUD and machine appearance remain a separate dependent
`feature/factory-ui` branch. The shared contract belongs to `feature/production-observability`.
No fresh full-suite coverage claim replaces the previously dated coverage evidence. Item 6
and the overall accepted roadmap count remain unchanged; this is not platform completion.

### Current Evidence

- The generated production application contains direct static Step calls, lowered input resolvers,
  constants, and integer routes. It contains no `StepDefinition`, binding hierarchy, alternate
  executor, or runtime Step scan; runtime behavior is proven through generated child JVMs. The
  generator compiles each node fragment once for both application variants and reuses the canonical
  immutable Step definition during compilation. The Example metric correction leaves production
  execution unchanged and removes the development dispatch's measurement-exclusion flag and branches.
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
- A real 129-Step production flow executes 322,500 verified Steps with zero traces. The September 8
  advisory reports 86,552 allocated bytes and 36,996 median nanoseconds per call: 670 bytes and
  286 nanoseconds per Step, with 1,616 retained bytes. A one-million-call soak stays inside a
  64 MiB heap with 137,624 retained bytes. The single-Step boundary and escaped result each report
  3,392 allocated bytes per call; median single-call latency is 700 nanoseconds, and the array-read
  calibration reports 103,017 bytes per call. Allocation and latency are advisory and never fail
  the build; constrained-heap, retained-growth, trace, execution, and correctness assertions remain
  acceptance gates.
- The earlier 16,384-node and 512-Trigger acceptance boundaries are superseded by the scaling
  checkpoint above; neither count is a compiler ceiling now.
  A depth-seven exhaustive branch tree resolves all 128 leaves uniquely, while 2,000 concurrent
  contexts remain isolated and 2,000 external effects occur exactly once.
- Cross-process application publication uses one project-local OS file lock outside the artifact
  directory. Sixteen simultaneous packaged cold starts share the atomic cache without deleting
  active staging or exposing partial output; abandoned staging and failed child startup remain
  cleanup-proven.
- Every Creator API read and mutation requires the per-process random token and exact loopback
  `Host`; mutations additionally require same-origin browser requests and exact JSON content type.
  Stored Creator presentation values are HTML-encoded, including group names and region labels.
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
- Clean `./scripts/coverage.sh` (`./mvnw clean verify` plus the advisory report) passes
  **2,690/2,690 tests** with no failure, error, or skip: core 815, standard library 72, Creator
  Surefire 1,403, desktop browser 355, package and soak 42, and mobile browser 3. The final local
  gate finishes on 2026-09-06 in **29:03**, compared with the pre-cleanup 56:22 run. Shared fixture
  reuse and six coherent browser partitions across four workers reduce the elapsed time without
  removing verification stages. Focused contributor commands avoid native packaging for ordinary
  code/UI feedback. Independent review confirms desktop/mobile discovery and coverage aggregation.
  The host has 64 GiB RAM and 10 logical CPUs, with unrelated workloads present. Hosted CI timing
  and memory remain unverified; the 30-minute job budget was not raised to mask a failure.
- Clean aggregate JaCoCo is **95.45% lines (10,287/10,777)** and **90.30% branches
  (5,378/5,956)**. The visible non-failing 95%/90% target is met without changing the build into a
  coverage gate.
- The generated development dispatch avoids the JDK 25 conditional-method-reference compiler
  crash through one explicit observation branch. A source-shape regression guards that contract,
  and clean desktop/mobile Failsafe reports contain no compiler exception, NPE, `LambdaToMethod`,
  or conditional-tree crash.
- Current production inventory is three modules, 37 Java files (21,564 lines), and four web files
  (7,313 lines), totaling **28,877 Java/JavaScript/CSS/HTML lines**, with no third-party Maven runtime
  dependency. Against this branch's pre-checkpoint HEAD, 5.3 adds two Java owners, one renderer, and
  a net 2,268 production lines. The September 6 correction removes 101 production lines relative
  to the previous local checkpoint. Removing the unbounded DOM renderer and duplicate readers reduces
  duplication, not the whole checkpoint's total line count. No module or dependency was added. The executable Creator
  JAR is 664,829 bytes and its host application image is 139 MiB. The image includes every stable build-JDK module
  so Creator can compile and run arbitrary locked Step bundles without ambient Java; unsupported
  incubator modules are excluded, and per-project minimal images remain Item 8. Packaging rejects
  empty output paths and JDKs without `java.base.jmod` before cleanup or linking begins.
- Clean concurrency proof completes 2,000 isolated calls and 2,000 external effects exactly once,
  with all 64 workers overlapping and no production traces. Post-gate inspection found no owned
  test-owned Railix application or browser process. The updated native Creator and its 143-node
  review application were then intentionally reopened for user review.

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
6. **Done:** close public-boundary coverage deficits without padding, rerun clean certification,
   and keep the separate advisory coverage report at or above 95% lines and 90% branches;
7. **Done:** implement and accept Switch as an ordinary control Step through the generic authored-
   outcome candidate contract, generated child applications, metadata-only groups, and browser
   E2Es;
8. **Queued:** implement and accept Merge/fan-in, then continue with Split and bounded Loop.

PR-readiness hardening is tracked separately from feature completion: one root build, one hosted
verification workflow, a checksum-verified Maven wrapper, minimal cross-editor settings, pinned
lifecycle plugins, host/browser requirements, compiler bounds, cross-process build ownership,
quiet deterministic packaging, and contributor rules are integrated.
The public target is `NanoNative/nano_railix` and its Apache-2.0 license is preserved. Publication
status does not change Item 5 completion.

Global reusable groups, automatic group suggestions, and parameter suggestions remain unsupported.

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
   Influx output. The original baseline excluded Examples; the 2026-09-06 correction removes that
   exclusion so every admitted execution uses the same metrics, including Examples.
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
   package, lifecycle, and soak E2E after the ownership change: 2,438/2,438 tests pass with no
   failure, error, or skip.
5. **Closed:** selected-node metrics use fixed open-addressed indexes and exports distinguish exact
   primitive counter, lookup, and total bytes without changing the production fast path. Committed
   generated-application E2Es prove bounded streaming for 4,096 Step series; the direct fixed-store
   contract proves exact accounting for 16,384 series. The September 8 compiler scaling checkpoint
   above extends the former node ceiling; this does not certify million-series observation.
6. **Closed:** clean `./mvnw clean verify` passes in 47:49 with advisory aggregate coverage of
   95.0335% lines and 90.2590% branches. The one-million-call 64 MiB soak retains 127,512 bytes;
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
