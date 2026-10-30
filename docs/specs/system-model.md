# System Model And Flow Control

## Human Review

Status: **Existing baseline with accepted target boundaries**, updated on 2026-09-27.
The platform boundaries below record the user's specification-workshop decisions,
not an implementation or fresh test pass. The roadmap owns delivery status.

Owns: the flat functional graph, context, Step declarations, routing and compilation.
Visual grouping belongs to [Creator](creator.md); observations to
[development observation](observation.md).

Decisions: [ADR 0013](../adr/0013-small-java-product-boundary.md), [ADR 0014](../adr/0014-canonical-primitive-data.md), [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

The current file layout still embeds Example inputs in Trigger nodes. The accepted
[Example-file target](examples-and-validation.md) changes that ownership in a later
migration; no current parser or project file has changed in this documentation task.

## Accepted Platform Boundaries

Source: the user's 2026-09-19 workshop clarification: capability changes require a rebuild,
internal execution is Java, and the first mesh coordinates already-running instances of the
same project. These are target requirements; implementation evidence remains scoped below.
The same workshop also requires result-based handling instead of avoidable exceptions and
accepts measurable performance gates; workload sizes and numeric budgets remain undecided.

- **SYS-001:** An application MUST execute its built Flows without a running Creator.
- **SYS-002:** Changing an artifact's included capability set MUST require rebuilding it.
  An excluded capability MUST NOT remain as dormant capability-specific code, routes or
  dependencies. Shared dependencies required by included capabilities remain necessary.
  Environment-selected settings do not turn absent code into an available capability.
- **SYS-003:** Step-to-Step execution MUST use the canonical Java value model without a
  mandatory JSON serialization/reparse between Steps. JSON is an interchange representation,
  not a second internal execution engine.
- **SYS-004:** The first mesh MUST coordinate already-running instances of the same project.
  It MUST NOT treat unrelated projects as interchangeable execution members. Rolling versions
  require an explicit compatibility decision; matching project identity alone is insufficient.
  Starting/replacing host processes and provisioning machines are outside this first mesh.
- **SYS-005:** Expected rejection, invalid user input, absence and ordinary execution failure
  MUST be represented by explicit results or declared Step outcomes at the owning boundary.
  Railix MUST NOT manufacture an exception to propagate such a result through its own normal
  execution path. Unavoidable library exceptions are translated once at their owning boundary;
  cancellation retains its semantics, and fatal JVM failures are not ordinary business results.
- **SYS-006:** Performance acceptance MUST identify workload, hardware, build/runtime variant
  and measurement conditions, and report compiler/build cost, artifact size, execution latency,
  throughput, allocations/memory and Creator interaction cost where applicable. Numeric budgets
  MUST be agreed before the dependent slice is called performance-ready; authored Step count
  alone is not evidence of concurrent execution capacity.

The product is a system in one deployable application, usable standalone or later in a mesh.
Kubernetes is inspiration for operational behavior, not a required dependency or an equivalence
claim. Single-instance production readiness precedes mesh delivery; the mesh remains planned.
Dispatch boundaries, compatibility negotiation, state ownership and partition behavior are open.
Creator group reuse is owned by [Creator](creator.md#planned-group-copy-and-sharing);
the compiler continues to receive only fully materialized Steps and links.

### Acceptance And Evidence

| Requirement | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| SYS-001 | Start a built application with Creator closed and execute its ingress. | Existing CLI journey below; broader production lifecycle remains planned. |
| SYS-002 | Exclude a capability, inspect/run the artifact, then request that absent capability. | Current whole-development-runtime omission only; independent selection and refusal need packaged tests. |
| SYS-003 | Pass canonical values through a generated chain and verify results and allocations. | Existing generated execution; no new performance certification from this decision. |
| SYS-004 | Join compatible instances of one project; reject another project or incompatible build; stop one member. | Planned integration and failure tests after membership/compatibility contracts are agreed. |
| SYS-005 | Supply invalid data to parsing and a nested Step, exercise cancellation and an I/O failure; verify explicit outcomes and preserved diagnostics. | Existing public result types; internal exception removal and success/rejection allocation measurements remain planned. |
| SYS-006 | Compare the same representative project through compilation, packaged execution and Creator navigation, including sustained load and resource exhaustion. | Measurement gate accepted; workload, target hardware and thresholds remain open. |

### Current Failure-Handling Gap

The public APIs already expose `StepResult`, `RunResult` and `CompileResult`. Expected Step
branches such as invalid numeric input use declared outcomes. However, `WorkflowRuntime`
uses `ProgramAbort` internally to carry a `RunResult` out of nested execution, and `RailixJson`
uses `JsonFailure` before returning its public invalid result. Both disable stack traces;
they are still exception-based control flow, not proof that SYS-005 is fully implemented.

The migration should extend existing result ownership, not introduce a wrapper per method.
Constructor/builder programmer-contract violations are distinct from invalid external data;
changing their public exception contracts requires a separate compatibility decision. No Java
API is changed by this specification edit, and no performance gain has been measured.

## Open Runtime And Delivery Contracts

The 2026-09-19 review identified the following implementation blockers beyond the current unary
and branching baseline. These are unanswered questions, not newly selected architectures:

- **State and resource lifecycle:** [PRE/PROCESS/POST](#planned-step-lifecycle) now has accepted
  direction: static Step functions may use app-local resources with explicit occurrence
  associations, explicit keyed sharing and isolation; named profiles are deferred.
  Configuration stays compiled and separate from context. Ordering, graceful drain and
  standalone/embedded escalation are agreed; generic ingress and optional resource registration
  are implemented. Expanded HTTP settings and runtime sharing diagnostics remain separate work.
  The drain deadline is configurable with an accepted 30-second default.
  Host-aware Steps use this same contract, not a built-in-only runtime subsystem.
- **Control and side effects:** [Convergence, branching and Join](#planned-merge-and-join) separate
  per-arrival processing from explicit coordination. Branch execution, Join correlation and
  [Limiter](#planned-limiters) completion/budget rules remain open before their respective slices.
  I/O needs explicit outcomes for failures with unknown completion.
  retries must not silently duplicate a committed write or external action. Define the owner of
  retry/idempotency behavior rather than assuming every small Step can be safely repeated.
- **Evolution and distribution:** specify supported project/Step contract versions, dependency
  locks and trust, toolchain pinning, migration and unsupported-version behavior. Artifact rollback
  is not data rollback; [database compatibility](database-and-data-access.md#decisions-before-implementation)
  must be decided before persistent resources participate in rolling builds.
- **Measurable scale:** separate authored graph size, compiler peak memory/build time, in-flight
  work, payload size, execution throughput/latency and visible UI work. Choose representative
  hardware and workloads plus resource budgets before claiming million-Step readiness.

### Recommendations For The Next Contract Round

These recommendations answer the user's request for lifecycle/retry suggestions; they are not
approved defaults or permission to implement:

- Let the application coordinate the lifecycle of Step-owned listeners, pools and storage handles;
  keep invocation data separate from those resources. The accepted static-function direction
  supersedes the earlier stateless-only recommendation without requiring one handler object per
  placed Step. The optional resource API below implements this ownership direction.
- Bound admission, queued work, payloads and fan-out. Carry an overall deadline through the Flow;
  cancellation stops further admission and releases owned resources, but does not promise to undo
  an external side effect. Drain admitted work during replacement before releasing resources.
- Do not retry arbitrary Steps automatically. Retry only an explicitly safe operation, with a
  bounded attempt/time budget and delay. A timed-out write can have committed: report unknown
  completion rather than "not written"; safe retries need operation identity/deduplication at
  the side-effect owner, not just a new wrapper around the same request.
- Keep admission denial, cancellation, known rejection and unknown completion distinguishable
  through existing result/outcome contracts. Exact API changes await the owning I/O/control slice.

"Compatibility" covers project/Step formats read by a newer Creator, generated artifacts using
persisted data, and later peers exchanging work. For example, an older artifact may not understand
a field type or storage format written after an upgrade. Restoring its executable does not undo
those writes. Decide migration, mixed-version access and rollback refusal before storage release;
the network protocol details can wait until the mesh milestone.

Decide foundational identity, value, side-effect and resource-ownership boundaries before I/O.
Defer mesh membership, placement, replication, partition and protocol algorithms until their
roadmap slice, with explicit blockers and required failure tests. Do not add dormant mesh code
or speculative interfaces to the single-instance runtime merely to reserve those possibilities.

## One Flat Functional Project

One `railix.project.json` produces one deployable monolith. Its root contains only:

```text
format  contract version
id      authored project name
nodes   every fully materialized functional Step
links   every functional continuation
```

There is no functional group, reusable-flow invocation, blueprint reference, hidden expansion, or
second selected-Step list. Link topology owns execution. JSON object key order does not.
This describes the implemented baseline. The planned compilation-selection policy below changes
artifact inclusion, not ownership of the saved graph or the rule that Creator metadata is optional.

The platform supplies one `railix.app` Step at canonical node ID `app`. It is mandatory, unique,
persisted, and non-deletable. Trigger definitions are catalog Steps connected from App. Each
Trigger owns a non-empty list of named Examples. An Example has a required payload and an optional
whole context object; compilation writes the payload to the Trigger's current example-target path
inside the development artifact's Example manifest.
Every Example is an independent case. The generated application returns the selected case's real
contexts and Step projections; Creator never unions Example values into synthetic runtime data.

Creator-generated mutable nodes use opaque UUID-backed IDs generated once. Reordering, insertion,
and deletion never renumber existing IDs. IDs have no timestamp or ordering meaning. Hand-authored
safe IDs remain valid project identifiers.

### Planned Compilation Selection

Source: the user's 2026-09-19 clarification: unreachable Steps are omitted unless explicitly kept.
This is an accepted outcome, not yet an implementation-ready selection algorithm. Current
`ProjectCompiler` rejects disconnected/unreachable graphs; it does not prune by Example coverage.
See the existing [compiler rejection test](../../modules/railix-core/src/test/java/dev/nanonative/railix/core/CreatorFirstCompilerRejectionE2eTest.java).

- **SYS-007:** Under the agreed reachability rule, the compiled artifact MUST omit unreachable
  Steps unless the user explicitly keeps them. Omission MUST NOT delete authored Steps, and the
  build result MUST identify the omitted and explicitly retained set. The classifier and keep
  override are unresolved below, so this requirement does not yet authorize pruning logic.

Resolve now, before selecting the emitted graph: does unreachable mean no connection path from a
Trigger, or also a connected Step never visited by the current Example set? The latter can remove
an error-handling path that real input would need. A finite suite does not prove semantic
unreachability. Recommendation awaiting approval: retain structurally reachable branches and show
missing Example coverage; omit only graph-unreachable Steps unless explicitly kept.

Before implementation, define the persisted keep selector, build report, validation of excluded
drafts, dependencies of retained Steps, and behavior of every edge crossing into an omitted region.
Keeping a disconnected Step cannot make it executable without a declared entry path. Do not invent
a new ingress, auto-connect its neighbors or silently turn an omitted branch into successful End.
Production inclusion decisions belong to executable/build configuration, not presentation metadata.

If Example observations drive inclusion, specify development-candidate compilation, evidence
identity/completeness and final-build validation without a compiler executing Steps or a build/run
cycle. An Example edit must not silently change a stale artifact's reachability decision. Preserve
the authored graph and show omitted, retained and unobserved sets separately. Packaged proof must
cover an unvisited error route, explicit retention, incomplete evidence and invalid selected code.

## One Workflow Context

Every admitted item starts with its own isolated mutable JSON-compatible context. Planned
explicit Joins may combine related contexts under SYS-024. Ordinary convergence and linked
editing do not. Projects may create any ordinary keys. These names are conventions rather than
runtime containers:

```text
payload    business input
header     protocol fields when supplied
metadata   mutable invocation metadata
result     conventional Trigger result
exit_code  CLI process status
runtime    reserved read-only Railix values
```

Paths include the root, for example `context.payload.customer.name`. The runtime request body is
the context itself. The generated development application adds `context.runtime.test=true` when it
executes a compiled Example, allowing project Steps to react explicitly. Creator only displays the
result and does not interpret that flag. The current synchronous baseline has no event entity,
event bus, event store, or retained execution object after the bounded trace completes. Planned
waiting and independent work require explicit bounded ownership under SYS-027, not an event bus.

The planned lifecycle also supplies a context to PRE and POST, without an invocation payload.
This does not authorize retaining a PROCESS context in long-lived storage, inserting a Java
client/socket into the JSON-compatible value tree, or making request data global. The lifecycle
contract below keeps compiled Step configuration separate from context and owns resource access.

## Minimal Step Kinds

Kinds exist only for graph roles with genuinely different placement or lifecycle:

- `APP` is the structural non-deletable application root.
- `TRIGGER` owns ingress, examples, and result/default contracts.
- `STEP` is every other definition, including Field Manipulation, unary value operations, I/O,
  control behavior, databases, and future distributable work.

"Primitive" remains a product and Creator presentation term for a small stateless unary `STEP`.
It is not a `StepDefinition.Kind`. A unary Step declares one receive and return and may replace the
ordinary default `next` outcome with an explicit primary outcome such as `ok`. It can be an
ordinary graph node whose `receives` and `returns` bind context paths, or be composed inside a
generic ordered `STEPS` input. Graph instances have the same stable identity and explicit outcome
links as every other `STEP`; no primitive-only execution model exists.

Ordinary Step behavior is declared through one recursive input grammar shared by built-in and
third-party definitions:

- canonical `JSON` values;
- readable, writable, or read/write workflow `PATH` values;
- tagged `OPTIONS` with owned inputs;
- ordered `CANDIDATES` with explicit value sources, conditions, and optional authored outcomes;
- Boolean `MATCHER_GROUPS` containing ordered OR groups of ordered AND matchers;
- ordered nested `STEPS` bound to one earlier value source.

Compiler, runtime, and Creator consume this grammar from definitions. They do not branch on a
standard-library Step ID or input name.

### Configuration Validation

Accepted on 2026-09-21: an optional fluent validation function on a JSON input, returning
`Optional<String>`. Empty means valid; a present nonblank string explains the rejection. The API
validates authored configuration, not invocation payloads or runtime resources.

```java
.input("port", Input.json(ValueShape.NUMBER)
        .defaultValue(RailixValue.number(8080))
        .validation(Integer.class, port ->
                port >= 0 && port <= 65535
                        ? Optional.empty()
                        : Optional.of("Port must be between 0 and 65535.")))
```

- **SYS-019:** JSON input declarations MUST support an optional validation function receiving a
  declared Java value type and returning an optional error message. Existing shape, presence and
  range checks remain available without a custom function. `NUMBER` itself continues to represent
  exact decimal values; an `Integer` validator MUST reject fractional or out-of-range values before
  calling the function, without rounding or truncation.
- **SYS-020:** Supplied and defaulted configuration values MUST satisfy the same validation.
  An absent optional value without a default MUST NOT invoke the function. A validator returning
  Java null or failing unexpectedly MUST produce a validation-failure diagnostic, not acceptance.
- **SYS-021:** Creator MUST show the custom rejection at the owning input, and the compiler MUST
  reject the same configuration with the same message. Built-in and installed third-party Steps
  MUST use the same contract, including owned inputs and nested Steps.
- **SYS-022:** Configuration validators MUST be deterministic, side-effect-free functions. Railix
  MUST NOT invoke PRE, PROCESS or POST to validate configuration or revalidate fixed configuration
  on each runtime invocation. Ordinary Steps without validators MUST NOT require a validator process.

The accepted export is `StepContractJson.writeManifest(MySteps.class, MySteps.definitions())`,
where `MySteps` declares `public static List<StepDefinition> definitions()`. The manifest names
that class and its owned JAR entry; input metadata contains only the supported Java type, not a
serialized callback. Signature inspection and catalog discovery do not initialize the provider.
The worker calls it directly, without reflection, and requires its definitions to match the installed
contracts. Definition construction must also be side-effect-free. Existing bundles without validators
retain the list-only export.

Supported callback types are `Integer`, `Long`, `BigDecimal`, `String`, `Boolean`, `RailixValue`,
`RailixValue.ArrayValue` and `RailixValue.ObjectValue`. Integral conversion checks precision and range
before invoking user code. Compilation checks defaults when used and skips absent optional inputs.
`.between(...)` remains available and supplies Creator's numeric bounds.

Registered providers use one short-lived JVM per provider per compilation, with a ten-second batch
deadline, a 128 MiB heap cap and a 1 MiB response limit. Identical checks within the batch share their
result; no cross-build callback cache can retain stale code or configuration. A failed, interrupted,
malformed or timed-out response rejects the build. Workers, observed descendants and temporary files
are cleaned up. This is crash/hang containment, not a filesystem or network sandbox; installed
validator code must be trusted. Direct programmatic `StepCatalog.of(definitions)` remains an explicit
in-process API for caller-owned functions; `StepCatalog.of(MySteps.class, definitions)` selects the
isolated path, which the built-in catalog also uses. No worker starts when there are no resolved
validated inputs. Generated applications contain neither the worker nor per-invocation validation.

Acceptance coverage is in `ConfigurationValidationE2eTest`, `StepContractJsonE2eTest`,
`ConfigurationValidationBundleE2eTest`, the HTTP invalid-port regression and Creator's field-validation
browser scenario. These cover typed/default/absent values, owned and nested inputs, callback failures,
provider mismatch, cleanup, generated-JAR execution and unchanged saved state after an invalid edit.

### Planned Host-Aware Step Integration

Source: the user's 2026-09-20 clarification. Platform-dependent behavior belongs to a Step that
may be supplied by Railix or a third party, not a mandatory Railix host-management service.
This is an accepted extension direction, not a selected API or implemented lifecycle.

- **SYS-009:** Any Step contract added for host-dependent execution or resource lifecycle MUST
  be available equally to built-in and third-party Step definitions, without privileged
  standard-library Step IDs or compiler/Creator special cases for individual implementations.

A Step may need platform/capability detection, initialization or ongoing checks that its required
configuration remains effective. PRE/PROCESS/POST scope and ordering are recorded below; resource
access, cancellation and bounded monitoring still need agreement.
Resolve these before implementing the first dependent Step, preserving thread-safe invocation
behavior and explicit registration rather than runtime scanning. Ordinary Steps must not acquire
mandatory timers or platform dependencies merely because another Step needs them.

[Layer 4 integration](environments-and-security.md#planned-layer-4-step-integration) is one use
case, not the definition of this generic contract. Host commands, native interfaces, privileges
and automatic repair are not approved by recording the extension direction.

Acceptance for SYS-009: install an independently authored Step bundle using the agreed lifecycle,
build and run it without Step-specific core/Creator edits, and exercise supported, unsupported,
denied, initialization-failure and cleanup paths. Evidence is planned; the lifecycle contract
and platform support matrix must be defined first.

## Candidate Conditions

`CANDIDATES` and every matcher inside `MATCHER_GROUPS` use one condition structure. An omitted
`when` or `{"transforms":[],"all":[]}` means presence-only acceptance. A conditional candidate stores shared preparation and
independent AND requirements:

```json
"when": {
  "transforms": [{"use":"list.size","inputs":{}}],
  "all": [
    [{"use":"number.greater-than","inputs":{"than":1}}],
    [{"use":"number.less-than","inputs":{"than":5}}]
  ]
}
```

The source resolves once. `transforms` executes once in order. Every non-empty `all` program starts
independently with that prepared value, executes in order, and must finish BOOLEAN. Evaluation
stops at the first false program. A candidate that supplies a value still supplies its original
source, not the condition's prepared value. Missing sources skip all condition Steps. Failures,
rejections, cancellation, and interruption propagate unchanged.

Flat `when` arrays are rejected, not silently migrated. Creator authors the same structured
form consumed by the compiler. Step discovery and
compatibility use ordinary receive, return, input, and outcome contracts; there is no matcher kind
or Step-ID allowlist.

## Field Manipulation

Field Manipulation is one ordinary standard-library `STEP`. It selects a destination `field`.
Ordered `value` candidates resolve the current field, another field, or a literal. Each candidate
may carry a condition. The first present accepted candidate supplies the value;
ordered unary transformation Steps run before the write.

Canonical JSON `null`, empty containers, false, and zero remain present unless an authored
predicate rejects them. Later candidates express fallback or copied alias behavior without a
special fallback contract or live JSON alias. If every candidate is unresolved or a nested Step
returns a non-primary outcome, standard Field Manipulation leaves the destination unchanged and
continues through `next`.

Creator has no generic missing-value route or termination editor. Explicit control Steps own
branch selection, coordinated waiting and loops. Planned ordinary multi-input convergence needs
no separate control Step. It follows SYS-008 below.

## Filter

Filter is one ordinary standard-library `STEP`, not a kind or compiler intrinsic. Its generic
ordered `conditions` candidates select a workflow-context field or literal and optionally run a
condition. A present source with no condition matches; otherwise every authored `all` program must
finish BOOLEAN and pass. The first accepted candidate selects `match`; no accepted candidate selects
`otherwise`.

Both outcomes always have explicit links in `railix.project.json`. Creator materializes both links
when Filter is added, renders declared outcomes in deterministic depth-first order with an explicit
stack, and inserts later Steps into the route selected in the Inspector. Missing, duplicate source-port,
or unknown links remain visible on their owning outcome and are never presented as End. Distinct
outcomes may target the same Step and each arrival executes independently. Functional compilation rejects malformed links before a project enters a Creator workspace;
insertion is also disabled for any malformed transient route. Runtime executes exactly one
successor. Control Steps can be grouped and inserted inside groups without changing the flat graph.

## Choice

Choice is one ordinary standard-library `STEP`, not a kind or compiler intrinsic. Its generic
`conditions` input is an ordered outer list of OR groups. Every non-empty group is an ordered list
of AND matchers. Each matcher reuses the field/literal source and optional condition grammar from
candidates.

A missing source is false and skips its condition. Canonical JSON `null`, false, zero,
empty strings, and empty containers remain present. Groups and matchers short-circuit in authored
order. An empty outer list resolves false; an empty inner group is rejected at compilation.
Failures, rejections, cancellation, and interruption from matcher conditions propagate unchanged.
True selects `match`; false selects `otherwise`, and both successors are explicit flat links.

Creator renders `MATCHER_GROUPS` from the catalog schema without inspecting the Choice Step ID. It
adds, reorders, and removes OR groups and AND matchers, never authors an empty inner group, and
persists the ordinary project JSON. Source suggestions come from real preceding Examples. Final
Boolean and per-predicate stage previews come only from the rolling-built application.

Creator presents shared preparation separately from independent AND matcher programs. Compatible
ordinary Steps returning BOOLEAN start matcher programs; compatible non-Boolean Steps prepare the
shared value. A matcher program may continue with compatible ordinary Steps, for example
`value.equals` followed by `boolean.not`. Catalog `search_terms` are Step-developer metadata and
apply to every generic Step search. Built-in equality, exact-number, and literal text predicates
declare total defaults so inserting one never creates a missing-input diagnostic. Classification,
compatibility, and defaulting use only Step contracts, never Step IDs.

Choice itself owns only the fixed `match` and `otherwise` outcomes. It does not claim Merge, Split,
or bounded Loop behavior; Switch owns authored outcomes through the generic contract below.

## Planned Merge And Join

Status: **Accepted direction. Implementation and remaining decisions are separate.** Source:
the user's 2026-09-27 specification. This refines SYS-008: ordinary convergence no longer requires
a Merger or a mode selection. Waiting belongs to an explicit Join, not to an extra incoming belt.
The section heading is retained for existing links, not as a requirement for a Merge-Step family.

An arrival is one visit to a Step. A job is an independently admitted invocation. Related branches
may later belong to that job. Linked authoring occurrences are separate uses of editable contents,
not those jobs or branches. A lane identifies a use/route for selection or coordination, not a
thread. Its precise persisted identity is still open in the [Creator contract](creator.md#planned-group-copy-and-sharing).

- **SYS-008 (amended):** An ordinary Step MUST accept multiple incoming connections without a
  mandatory Merger. Each arrival MUST execute independently, without implicit waiting, winner
  selection, deduplication or combination of jobs/contexts. A Step MUST NOT belong exclusively to
  one Trigger. Arrivals from different sources retain their own invocation and response contract.
- **SYS-023:** Branch selection MUST distinguish one route, multiple matching routes and all
  routes from the separate choice to execute selected work sequentially or independently.
  Adding connections MUST NOT silently enable independent execution or waiting. A proposal in
  Creator becomes functional behavior only through an explicit authored choice.
- **SYS-024:** A Join MUST support related branches and independently received related messages,
  wait for its defined required results and merge their contexts before continuation. For conflicting
  values, the value from the latest contributing arrival at that Join MUST win, as selected by the
  user on 2026-09-27. Participating input flows alone do not define which arrivals to combine when
  several jobs overlap. Independent-message scope was requested. Its pairing rule is awaiting
  clarification in FC-4, together with merge granularity and completion rules.
- **SYS-025:** Join and Limiter behavior MUST be implementable by installed third-party Steps
  through public Step contracts, without privileged built-in IDs in compiler, runtime or Creator.
  Extend existing declarations, inputs and results before proposing an additional API. Any new
  execution capability must have a current use and installed-bundle proof.
- **SYS-026:** Unused coordination capabilities MUST NOT add capability-specific queues, polling,
  timers or per-invocation tracking to ordinary synchronous flows. No speculative global listener
  or interception system is introduced. Measure the ordinary path under SYS-006 rather than
  promising zero overhead without evidence.
- **SYS-027:** Waiting and independently running work MUST remain owned by application lifecycle
  accounting until completion or confirmed cancellation. Timeout, failure and shutdown MUST NOT
  leave retained contexts, orphan continuations or a successful completion reported too early.

Simple convergence preserves the arriving context. It does not create a context merge policy.
Independent jobs remain isolated until an explicit Join combines their related contexts. Using
the same building or Step does not do so. Context merging at Join is accepted. Whether related
branches can observe live shared mutations before that point remains open below.
Two arrivals mean two executions, including side effects. Convergence is not an exactly-once
delivery mechanism. A delayed or unselected branch is not a Boolean false result.

### Current Contract And Missing Capabilities

Repository inspection on 2026-10-17 establishes the following baseline:

| Boundary | Present implementation | Gap for this direction |
| --- | --- | --- |
| [ProjectCompiler](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ProjectCompiler.java), [ApplicationPlan](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ApplicationPlan.java) | Ordinary Steps accept multiple arrivals. Triggers still require one App connection. Every ordinary outcome has one destination, and the graph is acyclic. There is no exclusive Trigger owner on a Step plan. | Multiple selected outputs, waiting and cycles remain separate contracts. |
| [StepDefinition](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepDefinition.java), [StepResult](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepResult.java) | Generic options, authored outcomes, validation and one returned outcome with outputs/writes. | No declared multi-route result, branch activation/correlation or deferred continuation result. No exact replacement Java API is selected here. |
| [StepInput](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepInput.java) | PRE/POST and private/keyed-shared resources can own counters or queues. Nested programs are synchronous, valid only on the owning thread before PROCESS returns. | State storage alone does not expose downstream completion, a transferable continuation, or Join membership. A Step can already do local admission and return overload. It cannot thereby limit arbitrary downstream work. |
| [WorkflowRuntime](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/WorkflowRuntime.java), [ApplicationGenerator](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ApplicationGenerator.java) | One selected integer-routed continuation. Shared destinations have one generated route body and one placement lifecycle. Each invocation retains its own context and original source response. | Independent branches and suspended work need completion/cancellation ownership beyond the current calling thread. No existing context or nested program may simply be retained and resumed elsewhere. |
| [CreatorEditor](../../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/CreatorEditor.java), [browser editor](../../modules/railix-creator/src/main/resources/dev/nanonative/railix/creator/web/app.js) | The bounded neighborhood lists all reaching Example sources. Connection edits address one source port. Step deletion bypasses that Step for all arrivals, unless another connected output prevents deletion. | Compound placement of a reused group with several external entrances remains unimplemented. The current compiler rejects disconnected intermediate drafts. |

Existing compiler rejection and generated Step-contract tests verify the remaining restrictions,
including rejection of nested programs used after return or on another thread. They do not verify
Join, fan-out or Limiter support. Standard-library and third-party Steps share this same gap.
Cycles/Loop and distributed coordination are not silently added here. Independent-message joins
are in the accepted target, but do not exist in the current execution path.

### Decisions Before Flow-Control Implementation

These questions block only their dependent slices, not ordinary non-waiting convergence:

| ID / timing | Plain-language question | Decision still needed |
| --- | --- | --- |
| FC-1, before multi-route selection | Two shipping rules match. Run the first, both, or every route? What if none matches? | Selection order, empty selection/fallback, persisted configuration and migration. Link count is not a default. |
| FC-2, before sequential multi-route execution | Price calculation runs before invoice creation. Does invoice creation see the new price, and does a failed first route prevent the second? | Branch boundaries, order, context visibility, failure propagation and response/completion rules. |
| FC-3, before independent branches | Price and stock checks run at once. Both write `status`. Which changes can each see before reaching a Join? | Shared-context versus branch-result ownership and write visibility remain open. The accepted last-arrival-wins rule applies when Join combines contexts. It does not select live concurrent mutation rules. Repeated writes can also be deliberate and valid. |
| FC-4, before Join | Flow A delivers orders 1 then 2. Flow B delivers 2 then 1. Pair by order identity or simply take the next arrival from each input? | Context merging and last-arrival-wins for conflicting values are accepted. Independent-message scope was requested, but the user expects identification through joined flows. Clarify keyed pairing, next-arrival pairing or only branches of one original job. Then define required/activated inputs, repeated pairings, duplicates/late results and completion. Merge details still include arrival ordering for concurrent deliveries, nested fields, arrays, absent versus null and reserved runtime data. |
| FC-5, before waiting or independent execution | A stock check fails or never returns. Does the price check stop, and what happens to the waiting order? | Explicit failure/cancellation/timeout outcomes, sibling policy, finite waiting limits, cleanup and lifecycle accounting. Numeric budgets and any retry policy remain unselected. |
| FC-6, before Join and whole-job limits interact | A one-place Limiter admits invoice A, which waits for payment B at a Join. B needs the same occupied place. What can complete? | Detect/report provable circular waits. Define admission topology, ownership after joining jobs, response delivery to original callers and transfer/release of every held permit. Never silently release A early, admit B beyond the limit, or treat Join arrival as job completion. |

### Flow-Control Acceptance

`ConvergingFlowE2eTest` covers ordinary convergence through generated production and development
artifacts, independent contexts and shared placement resources. The branching, Join and Limiter
checks below remain planned unless explicitly listed as implemented. Existing synchronous
behavior must remain unchanged.

| Requirements | Public-boundary check |
| --- | --- |
| SYS-008, SYS-013 | Two alternative routes, then two different Triggers, reach one ordinary Step without a Merger. Each arrival produces its own result/response, preserves its own data, and executes the Step once. The shared placement initializes once. A failed arrival does not corrupt another. |
| SYS-008, CR-011/013 | Select each incoming route, inspect its Example, insert on one connection and remove one connection without changing the others. Save/reopen and run the generated artifact with presentation metadata absent. |
| SYS-023 | Exercise one, multiple matching and all routes with zero/one/multiple matches. Adding a wire does not change scheduling. Sequential execution follows FC-2. Independent execution follows FC-3/5 after approval. |
| SYS-024, SYS-027 | Interleave two jobs in opposite input orders and verify the pairing agreed in FC-4, not an assumed message key. For paired arrivals with different `status` values, reverse arrival order and verify that the later value wins. Check nested/missing values and duplicate/skipped/failed/late/cancelled arrivals under FC-4/5, bounded retention, original response ownership and no unintended continuation. |
| SYS-025/026 | Install a third-party coordinating Step without compiler/Creator edits. Compare a plain flow with the capability absent for artifact contents, allocations and execution cost. Preserve the direct synchronous path. |

## Planned Limiters

Status: **Accepted direction. Whole-job concurrency scope selected, completion details open.**
The user selected release at the end of the entire job on 2026-09-27, not after the next Step
or a selected section. A Limiter is an
ordinary functional Step, optionally drawn as a small connection component. It is not a database,
Trigger, observation service or implicit property of a visual Group.

- **SYS-028:** Limiters MUST be usable at suitable declared execution boundaries throughout a
  Flow. Rate admission (starts per time interval) and concurrency (unfinished work occupying
  places) MUST be distinct behaviors with explicit configuration. A concurrency place MUST stay
  occupied from admission at the Limiter until the entire admitted job completes, including
  still-owned branch work. Returning from the next Step does not release it. Any placement restriction
  must follow the public capability contract, not a built-in Step ID or transport type.
- **SYS-029:** Budget sharing MUST be explicit. Equal limit values, linked editing, a common
  building or group membership MUST NOT alone combine independent budgets. Identity, membership
  and compatibility of a shared budget must be agreed before persistence or implementation.
- **SYS-030:** Admission MUST be fair between eligible waiting lanes, use bounded in-memory
  waiting and expose an explicit overload outcome when waiting cannot be admitted. The fairness
  policy, queue capacities and time limits remain to be selected. File/database persistence is
  excluded from this first delivery.
- **SYS-031:** Limiter and Join control MUST function with Creator disconnected and optional
  observations/metrics disabled. Their execution state MUST NOT be inferred from sampled timing,
  counters or animation. Optional displays report state. They do not own it.

Returning from Limiter PROCESS is not proof that the work being limited has finished. Likewise,
requesting cancellation is not proof that a database write or independent branch has stopped.
Permit release must use the agreed completion boundary and avoid leaks or double release.
waiting work and admitted work both need the lifecycle contract in SYS-027. Existing resource
sharing can store a budget, but its compatibility key alone does not define lane membership or
which downstream work occupies it. The Join/whole-job interaction in FC-6 is a required design
case, not permission to change the accepted whole-job lifetime to avoid a deadlock.

### Decisions Before Limiter Implementation

| ID / timing | Plain-language question | Decision still needed |
| --- | --- | --- |
| LM-1, before concurrency limiting | Limiter -> database -> email: the user chose release only when the entire job ends. What if an email branch is still running or the caller disconnects? | Whole-job scope accepted. Define completion across response delivery, independent branches, failure/cancellation and repeated visits to the same budget. A cancellation request alone must not free a place while owned work still runs. Visual Groups do not delimit the job. |
| LM-2, before shared budgets | Two lanes each say "3 at once". Is that three each or three together? | Explicit budget identity/membership, scope and compatibility. Identical settings alone remain separate. Lane identity follows the functional contract, not screen position. |
| LM-3, before queued admission | Lane A has 100 orders and lane B has one. When does B get a turn, and what happens when the waiting room is full? | Fairness algorithm and lane ordering, per-lane/total bounds, payload-retention budget, timeout/cancellation outcomes and whether zero waiting is selectable. No numeric defaults are invented. |
| LM-4, before rate limiting | Can ten orders start together and then wait, or must their starts be spaced out? | Rate/time units, burst behavior, clock semantics and configuration validation, separately from concurrent-work limits. |

Acceptance: use a controlled clock and held work through a generated application. Verify rate
and concurrency independently, private versus explicitly shared budgets, progress for a quiet
lane under sustained load, full/expired waiting, cancellation/shutdown, and no leaked or double
released places. Repeat with an installed third-party Limiter and without Creator/metrics.
Verify no persistence files and bounded retained memory. Exact expected ordering/counts await
LM-1 to LM-4. This is not an already usable Step or an approved scheduling implementation.

## Switch

Switch is one ordinary standard-library `STEP`, not a kind or compiler intrinsic. Its top-level
`cases` input is a generic `CandidatesInput` enabled through `.withAuthoredOutcomes()`. Every
configured candidate owns one non-blank safe lowercase route ID that is unique within the node and
does not collide with a fixed Step outcome. Cases resolve in authored order. The first present
candidate accepted by its condition selects that candidate's route; no accepted candidate selects
the primary `otherwise` route. Every route has one explicit flat project link.

Authored outcomes are a third-party Step capability. One ordinary Step may declare one such
top-level candidates input; it cannot also declare a default candidate. Nested programs reject
Steps with authored outcomes because a nested invocation has no graph destination for those
routes. Creator derives availability and editing from the contract and excludes such Steps from
nested Transform and Matcher searches; neither compiler nor Creator branches on `railix.switch`.

The functional project owns stable route IDs and links. Optional human labels live only in the
owning Step's Creator presentation and merely reference those IDs. Visual grouping neither aligns
nor copies routes. Compilation adds the configured routes to that node's immutable outcome plan.
Generated applications initialize the node-specific candidate routes once, while each invocation
holds only its own selected outcome.

## CLI Trigger

The CLI Trigger declares `maximum_instances = 1`, owns the source `application.arguments`, and
defaults its writable target to `context.payload.arguments`. A process has one command-line
argument vector, so a second Trigger claiming that unique source is rejected instead of receiving
an invented loop or broadcast.

`railix run [arguments...]` supplies ordered strings once. Non-null `context.result` is printed
and numeric `context.exit_code` controls process status. Defaults are JSON `null` and `0`, producing
silent success when the flow writes neither. Interactive terminal sessions are not inferred.
CLI completion does not request application-wide shutdown or terminate other active Triggers.
The status applies when the standalone application exits; Trigger-owned lifetime is defined in LC-9.

## Planned Step Lifecycle

Source: the user's 2026-09-21 HTTP review and lifecycle clarification. Status: **In progress**.
The compiler now emits static `process(StepInput configuration, StepInput context)` calls for
built-in and third-party Steps and rejects missing or invalid PROCESS signatures before Java
generation. Implementation classes must be public. Registered class bytes are inspected at build
time without initializing the class. Java compiler failures at direct PROCESS/PRE/POST references
identify the offending implementation instead of reporting an internal generation failure.
The builder uses `.implementation(StepClass.class)`; `StepHandler` and `.run(StepClass.class)`
are removed, with no legacy execution path. Existing bundles need rebuilding.
Declared JDK module requirements travel in the canonical Step contract and its digest, so
installed bundles retain the same compilation requirements as directly declared Steps. An absent
module list means no declared requirements. This does not implement the planned jlink/native packaging.
Previously exported bundles whose providers declare modules must be re-exported so their
locked contracts agree with those definitions; no missing metadata is inferred at runtime.

Configuration is cached per compiled occurrence and contains authored literals, field paths and
selected options. Invocation inputs resolve those paths and expose executable nested programs;
configuration itself cannot execute programs or select request-dependent candidates. Literal
objects are reused between configuration and invocation. Optional PRE/POST methods now use the
same static signature, are validated from class bytes and run in compiled dependency order.
Nested sequential programs initialize downstream-first before their containing Step; independent
programs use deterministic declaration order. Declared input-source dependencies, including
option aliases, use the same downstream-first order. Candidate predicates prepare before shared
transforms, which prepare before their source's nested programs. This only orders PRE/POST;
PROCESS input resolution and short-circuit behavior do not change. Lifecycle contexts carry no invocation payload.
Hooks return the primary outcome without payload writes or outputs; invalid results and ordinary
hook failures are explicit startup/cleanup failures. Cleanup includes the occurrence whose PRE
failed and continues after a POST failure. Fatal errors propagate after attempted cleanup.

The embedded application boundary now closes admission and drains admitted source, Example and
trace calls using one adjustable budget, defaulting to 30 seconds. On expiry it requests
cancellation and reports incomplete shutdown without running POST while work remains. A later
stop call can finish cleanup after that work exits, but cannot reset or extend the initial deadline.
Third-party startup/protocol fixtures now use PRE rather than constructor/class-initializer side
effects, and their regression suite passes. Development startup rejection and fatal PRE/POST
failures reclaim owned child processes before exit, verified with generated application JARs.
Generic live ingress now uses a Trigger lifecycle context's `source()` capability. PRE registers
`onStart` and acquired-resource `onClose` actions; activation waits until every PRE succeeds.
`source.run(values, response)` dispatches the declared native inputs and includes the response
consumer in admission/drain accounting. Finite activity calls `complete(status)` without stopping
other sources. Standalone launch waits on its existing main thread without polling or allocating
an idle platform thread per Trigger. Nonzero source statuses are selected in deterministic
lifecycle order only after all sources finish. Cleanup actions run after that occurrence's POST,
in reverse registration order, including partial startup failure; one failure does not skip others.

`RuntimeApplication.start()` initializes Steps for embedding; `launch(arguments)` also activates
external sources once. They share the same lifecycle, configuration and resources, not separate
Example and production applications. Creator activates sources with an empty business argument
list before starting its Example suite. Native Trigger PROCESS remains separate from output
injection by Examples. Compiler and Creator no longer select CLI or HTTP launch modes: CLI
argument/output handling lives in its Step, and HTTP binding lives in its Step.

Generated-JAR tests cover mixed CLI/third-party source lifetime, activation-failure cleanup and
response draining. The `railix run` launcher forwards termination and allows the same default
30-second grace period before forcing its child to exit. A development-artifact test verifies
live ingress alongside Example injection. Ordinary Step inputs retain their previous storage;
source capabilities are allocated only for Trigger lifecycle contexts. With the optional resource
API, allocation remains at 3,432 bytes per short invocation and 671 bytes per Step in the 129-Step
fixture. One million production calls fit a 64 MiB heap, retaining 147,496 additional bytes in the
measured soak. These workload-specific checks do not establish a general scale or throughput promise.
Development management and business ingress now drain concurrently inside the same 30-second
budget; the Creator's process owner waits for that grace period rather than killing the child
after a few seconds. A held-response regression covers Creator closure before resource cleanup.
Optional resource registration and explicit sharing now use the same lifecycle. The HTTP Client
uses this public API rather than creating a client per request. Expanded HTTP configuration and
Creator sharing diagnostics remain separate work; this does not certify the entire HTTP migration.

- **SYS-010:** Steps MUST be able to declare optional PRE/INIT and POST behavior alongside
  PROCESS. All three phases MUST receive configuration and context through the same contract,
  with configuration separate from context. PRE and POST MUST have no invocation payload.
  PRE and INIT denote one phase, not two competing hooks.
  Conventional `pre`, `process` and `post` methods MUST NOT require redundant method-name
  registration. Missing optional PRE/POST methods MUST generate no call or no-op allocation.
  Method resolution and signature validation belong at build time, not on each invocation.
  An executable Step without PROCESS MUST be rejected at build time; absence MUST NOT become
  implicit pass-through. This does not change the existing structural App definition.
- **SYS-011:** A Step MUST be able to own and reuse Java resources across PROCESS invocations.
  Railix-managed resources MUST have an explicit association within their running application.
  Ordinary Java-owned instances, static singletons and registries remain valid; their owner,
  not Railix, is responsible for sharing, concurrency and cleanup. Compatible
  occurrences MAY share a managed resource under a Step-defined policy; configuration and request data
  MUST remain isolated. PROCESS code MUST be callable as static generated function composition
  without requiring a handler object per occurrence. Concurrent resource access MUST be safe;
  completed request data MUST NOT be retained. Pure value operations MUST NOT acquire unused
  resource storage or hooks.
  An existing client or pool object MAY itself be the resource state; Step authors MUST NOT need
  an additional State class merely to wrap it. Lifecycle bookkeeping MUST NOT recreate reusable
  resources or allocate an otherwise unnecessary resource wrapper for each PROCESS invocation.
- **SYS-012:** Compiler, runtime and Creator MUST consume registered lifecycle/ingress declarations
  identically for built-in and third-party Steps. No HTTP class, source name or Step ID may select
  a privileged implementation path. Removing the current HTTP generator exception is **P1**.
- **SYS-013:** PRE MUST run once per included occurrence at application startup, downstream before
  upstream. POST MUST run in the reverse lifecycle order during shutdown. Shared downstream Steps
  MUST initialize only once, not once for every path or Trigger that reaches them. Independent
  branches MUST have deterministic ordering; visual Groups MUST NOT affect that order.
- **SYS-014:** Shutdown MUST stop new admissions from all Triggers, allow admitted work to finish,
  and only then release resources that work may still use. POST ordering alone MUST NOT be treated
  as proof that in-flight work has drained. Deadline expiry and incomplete cleanup MUST be explicit.
- **SYS-015:** User-authored Step configuration MUST remain immutable compiled data for that
  occurrence, separate from invocation context. PRE, PROCESS and POST MUST see the same settings.
  Context mappings may select request values but MUST NOT mutate the compiled configuration.
- **SYS-016:** Managed resource registration MUST be optional and MUST NOT require a second
  resource declaration in StepDefinition. The simple API MUST create a private per-placement
  resource. Explicit sharing MUST use a Step-supplied immutable compatibility key containing all
  relevant effective settings and session/isolation identity. The Step owns that key and its
  ordinary configuration controls; Railix MUST NOT infer compatibility by inspecting factories,
  visual groups or resource display names. Sharing requires the same application, Step definition,
  resource name, declared Java type and equal key. Resolution belongs to PRE, not request-time
  comparison. A shared resource MUST remain usable until every owner's POST completes.
- **SYS-017 (deferred):** Named resource profiles MUST own their shared configuration in functional project
  data, independently of optional Creator metadata. Selecting a profile MUST reference that
  configuration rather than keep a second editable copy in each Step. Compiled applications MUST
  use the resolved settings without requiring Creator or its profile editor at runtime.
- **SYS-018:** Each Trigger MUST own the lifetime of its source activity. Normal completion of one
  Trigger MUST NOT stop another active Trigger or terminate its admitted work. A standalone
  application with only completed finite sources and no remaining work MUST shut down and run
  lifecycle cleanup, without an artificial permanent keepalive. Compiler and runtime MUST NOT
  select application lifetime by built-in Trigger type or a CLI-versus-server launch mode.

Static functions do not eliminate the HTTP client, connection pool or listener objects themselves.
Libraries may own internal objects and state without registering each object with Railix. The Step
owns integration and cleanup through the library's public API; Railix does not inspect its internals.
Only the resources actually needed by the compiled application survive. Resource storage is not
serializable business data. Steps remain independent of their upstream Trigger and downstream
Steps; the application coordinates ordering. SYS-005 still owns result-based failures. No
reflection, runtime graph interpretation or mandatory Java Stream pipeline is required: direct
generated calls may preserve the functional composition without per-request stream allocation.

### Accepted Lifecycle Decisions

- **LC-1, phase and scope:** PRE once at application startup, PROCESS per arrival, POST on
  shutdown, with configuration and resource associations scoped per placed occurrence. The user's
  sharing clarification permits compatible occurrences to refer to the same resource; it does
  not require a separate client or pool for each placement. Static Step functions are the target,
  not newly allocated Step instances. The compiler derives lifecycle dependencies from executable
  connections, including shared paths; optional hooks do not execute business PROCESS code.
- **LC-2, configuration:** compiled Step settings are not context fields. The earlier proposal
  to move startup settings into a protected context region is superseded. Literal settings are
  currently plain; HTTPS does not wait for the future secret manager. Certificate paths/passwords
  and trust policy belong to Step configuration; method/URL/headers/body may come from mapped
  context values. A compiled field path stays fixed even when the value it selects changes.
- **LC-3, resource ownership and DX:** the latest clarification replaces mandatory resource
  declarations and automatic sharing with optional `context.resource(...)` registration in PRE.
  Normal Java objects/singletons remain possible. The simple registration is private; an explicit
  key opts into sharing. Ordinary Step options may choose the overload, without a special Creator
  resource model. The Step owns creation, compatibility and cleanup; Railix coordinates registered
  ownership and lifetime without inspecting library internals. No `.state(...)` declaration or
  wrapper class is required. Visual Groups remain presentation-only. Named profiles are deferred
  under SYS-017 and are not required for the current API.
- **LC-4, drain deadline:** the user accepted an adjustable 30-second default. This is the
  application-wide drain budget, not a fresh 30 seconds per Step. Expiry requests cancellation
  and reports incomplete shutdown if work does not stop. After cancellation fails, a standalone
  generated application must terminate unsuccessfully rather than remain alive indefinitely.
  An embedded runtime must report failure without terminating its host process. A Java interruption
  alone is not proof of completed cleanup; termination cannot guarantee graceful resource release.
- **LC-5, required PROCESS:** the user confirmed that a missing PROCESS implementation is a
  build error. Conventional method names need no explicit registration; PRE/POST remain optional.
- **LC-6, migration:** the user approved replacing the Step author API and migrating built-ins
  through the same contract as third-party Steps. No permanent handler-object compatibility path
  is retained. Project documents are not silently rewritten; schema changes need explicit migration.
- **LC-7, Examples and live ingress:** the user confirmed that Examples supply Trigger output,
  not external input to its PROCESS method. Example execution starts at the compiled continuation
  after the Trigger and neither opens nor closes listeners. Starting the local development app
  must still start its configured external Triggers independently of Example execution. Examples
  and real incoming requests may use the same running app, with separate invocation contexts;
  downstream Steps and resources execute normally in both cases. Per-Step output fakes remain
  separately planned in [EX-010](examples-and-validation.md#behavior).
- **LC-8, local replacement:** the user accepted a short interruption during Creator rebuilds.
  Compile first while the current app keeps running. After a successful build, stop admission,
  drain and stop the old app before starting the replacement. Retain its artifact until the
  replacement succeeds. Persist the candidate before activation so an unsaved candidate cannot run
  Examples. Publish its accepted in-memory project and artifact identity only after activation succeeds.
  If activation fails, restore the previous file unless another writer changed it, in which case preserve
  that edit and report the conflict. A failed file restoration is explicit. Restart the last working artifact and
  report both the replacement failure and recovery outcome. This applies to every Trigger and
  Step-owned exclusive resource, not just network ports. Recovery does not undo side effects
  and can itself fail if resources are no longer available. A failed recovery must not block
  a later valid build in the same Creator session. No forwarding proxy is implied.
- **LC-9, Trigger-owned lifetime:** the user clarified that there is no CLI-versus-persistent
  application policy to select. CLI processes its arguments once and contributes no ongoing
  keepalive. An HTTP listener keeps serving until stopped; a future scheduler's lifetime follows
  its finite or repeating activity, not its name. Finishing CLI must neither stop a listener nor
  force process exit while another Trigger remains active. Once all finite activity and admitted
  work have completed, a standalone application shuts down normally, including POST cleanup.
  Trigger implementations must account for JVM liveness: daemon threads, including virtual
  threads, do not by themselves prevent process exit. This does not require a dedicated platform
  thread for every Trigger. Embedded applications do not own their host process; Creator's
  development management lifetime is separate from business Trigger lifetime. Creator control
  arguments must never become CLI payload. Scheduler implementation is not added to this slice.

For `HTTP -> transform -> persistence`, PRE prepares persistence before HTTP accepts work.
During shutdown HTTP stops admitting work, admitted requests finish using the still-open
persistence resource, then that resource closes. With multiple Triggers, the admission/drain
barrier covers all of them, including different paths to a shared Step. Startup failure must
clean up acquired resources without declaring the application ready; cleanup failure must not
skip remaining cleanup. Forced termination cannot guarantee POST or graceful completion.

### Resource API

```java
// PRE: private resource for this placement, reused across invocations.
context.resource("client", HttpClient.class,
        HttpClient::newHttpClient, HttpClient::close);

// PROCESS or POST: retrieve the registered instance.
final HttpClient client = context.resource("client", HttpClient.class);

// Alternative PRE: opt into sharing using immutable effective settings.
context.resource("client", HttpClient.class, settings,
        () -> createClient(settings), HttpClient::close);
```

The private and shared registrations are alternatives, not consecutive registrations of one name.
Each name may be registered once per placement, only in PRE. Retrieval uses the exact declared
type. Missing resources, duplicate names, invalid/null registration arguments, null factory
results and late registration are programmer-contract failures translated at the Step boundary.
Factories execute only for a new private resource or a new shared key. Shared registrations must
describe equivalent creation/cleanup behavior; the first registration supplies both functions.
The key must have stable equality/hashCode and must never be request data or a mutable cookie jar.

Resources are Java capabilities, not JSON values. Plain Steps keep ordinary StepInput objects;
registered Steps receive resource access in their existing invocation context, not a second
per-request resource wrapper. Nested Steps, Examples and live calls use the same prepared scopes.
Registered cleanup runs after POST in reverse acquisition order; reference counts track owners,
not requests. A closer is attempted exactly once even when it fails, and failure does not skip
other cleanup. The Step must not manually close a registered resource. Partial PRE failure rolls
back acquired resources. A failed drain retains resources while admitted work still uses them.
Closing new admission alone is not proof that all in-flight work has finished.

### Lifecycle Acceptance

| Requirements | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| SYS-010/011, LC-5 | Build conventional static Steps without explicit hook names, including a pure PROCESS-only Step and a client Step without a custom State class. Verify direct calls, omitted absent hooks, and build-time rejection of absent PROCESS or invalid signatures. | Static hook/signature tests and ResourceLifecycleE2eTest exercise the optional API through an installed third-party bundle and produced JAR. |
| SYS-010/011/015 | Build an application with differently configured occurrences of one resourceful Step; run concurrent requests and stop it. Check reuse and isolation without handler objects. | ResourceLifecycleE2eTest runs 128 concurrent flows through seven resource-owning placements, including a nested Step. Five resources are created and closed once each. |
| SYS-011/014/016 | Share compatible resources while isolating private occurrences and incompatible keys; fail PRE or POST and repeat shutdown. | ResourceLifecycleE2eTest covers private/shared/key/definition isolation, rollback, invalid registration, ordinary/fatal failures and continued cleanup. Separate-JVM applications cannot share this app-local registry; simultaneous embedded application instances are not a current generated entrypoint. |
| SYS-016 | Select private/shared through ordinary inputs and prove compatibility is resolved once in PRE. | Generic resource tests use ordinary option inputs without resource declaration metadata. The HTTP connection test proves ten requests use two private connections or one shared connection, with cookie storage disabled. Visual Groups do not participate in generated resource identity. |
| SYS-017 | Use a shared profile from several Steps and build without Creator; verify canonical configuration resolution. | Deferred; profile persistence and edit/delete rules must be agreed before that later slice. |
| SYS-013/014 | Two Triggers share a downstream resource; hold one request in flight while stopping the app. Verify one downstream PRE, all admission closed, request completion before resource POST, and reverse cleanup ordering. | ConvergingFlowE2eTest proves a shared placement lifecycle across concurrent sources. Drain and transport-response ownership are covered separately by ProductionRuntimeConcurrencyE2eTest and GenericTriggerLifecycleE2eTest; the combined shared-resource drain scenario remains unverified. |
| SYS-014, LC-4 | Configure a short controlled drain budget and hang work across several Steps. Verify one application-wide deadline, cancellation at expiry and no successful-shutdown report while work remains. | ProductionRuntimeConcurrencyE2eTest covers drain, rejected admission, ignored cancellation and repeated cleanup. GenericTriggerLifecycleE2eTest holds a real transport response during standalone JAR termination, railix run termination and Creator closure. Development management and business drain share one deadline instead of sequential grace periods. |
| SYS-010/013/014 | Fail partial initialization, interrupt in-flight work, then attempt repeated shutdown. Verify cleanup continues after a hook failure without admitting work to an unready Step or reporting false completion. | Generated-app checks cover PRE rollback, POST failure, graph and nested-program order. ResourceLifecycleE2eTest also checks registered-resource cleanup after ordinary/fatal failures and repeated shutdown. |
| SYS-012 | Install a third-party Trigger/resource Step; build and run it without changing compiler or Creator. Inspect generated calls, module closure and absence of stdlib-only branches. | GenericTriggerLifecycleE2eTest installs a real socket-source bundle and executes production/development artifacts. ResourceLifecycleE2eTest proves optional resource ownership. GeneratedApplicationVariantsE2eTest rejects built-in launcher behavior. |
| SYS-018, LC-9 | Run a CLI-only artifact and verify one invocation followed by POST and process exit. Combine CLI with a third-party persistent Trigger and verify CLI completion leaves that source serving. Complete a finite asynchronous source and verify its work finishes before normal cleanup and exit. | GeneratedApplicationE2eTest covers CLI process status/output and HTTP launch. GenericTriggerLifecycleE2eTest proves a virtual-thread source outlives CLI and its explicit completion permits process exit with the CLI status preserved. |
| LC-7 | Start the development app with a live Trigger, run an Example at its output and send a real request. Verify independent contexts, no Example call to Trigger PROCESS, no listener restart per Example and normal downstream execution. | GenericTriggerLifecycleE2eTest verifies an active listener, Example execution without Trigger PROCESS, subsequent real-input execution and source cleanup. |
| LC-8 | Rebuild through Creator, verify the previous process exits before the replacement executes, fail candidate startup and observe successful or failed recovery. | Process ordering and recovery implemented; generated-app regressions cover these outcomes. Generic admission/drain hooks now apply to live sources; the Creator respects the shared grace period before process escalation. |
| SYS-011, SYS-006 | Compare a pure-value application and repeated HTTP requests; measure artifact size, initialization, allocations, connections and throughput. | ProductionRuntimeSoakE2eTest passes with the resource API: one million calls inside 64 MiB and unchanged measured pure-flow allocation. GeneratedApplicationE2eTest verifies socket reuse for private/shared clients. Comparative HTTP throughput and a general scale budget remain unclaimed. |

## HTTP Trigger And Client

The owning Step specifications are [HTTP Trigger](http-trigger.md) and
[HTTP Client](http-client.md). They distinguish the working minimal baseline from the accepted
2026-09-21 target, unresolved decisions and required public-boundary checks.
[ADR 0023](../adr/0023-http-ingress-and-step-owned-jdk-modules.md) records the earlier implementation
and its superseded built-in-only launch direction. HTTP is the first consumer of SYS-010 to
SYS-012, not a separate lifecycle API. HTTPS belongs in the next HTTP delivery; binary bodies,
multipart and streaming remain deferred. Real Example I/O remains permitted under the
[Example contract](examples-and-validation.md#real-io-and-output-fakes).

## Planned Environment Builds

Final environment applications will derive direct Step calls, dependencies, JDK modules, `jlink`,
and `jpackage` output from the complete flat project. Current production JARs physically omit the
complete development runtime, Example manifest, traces, metrics, and management routes. Splitting
project/build metadata, Example projection, live error, queue, and metrics into independently
selectable environment capabilities remains planned. Every omitted capability must contribute no
capability-specific route, class, dependency, or JDK module. Shared dependencies still required
by included capabilities are retained. SYS-002 owns the rebuild boundary.

Remote attachment, time-window/custom metrics, queue control, permissions, sharding, and production
debugging remain roadmap work. Current examples never sample production traffic.

## Compilation And Lifecycle

Compilation parses the project, checks its structure, applies Step defaults and runs declared
configuration validators before building the flat executable. It returns deterministic diagnostics
and never invokes Trigger or Step PRE, PROCESS or POST. The isolated callback boundary is defined
by [SYS-019 to SYS-022](#configuration-validation).

Functional edits compile before stopping the previous application. Invalid source or a failed
build keeps it running. A successful build then replaces the child process with a short interruption;
failed startup attempts to restart the retained previous artifact and reports recovery separately.
Creator closes admission and drains active work within one shared deadline before escalating
child-process cleanup under LC-8. Presentation-only group edits persist without
restarting the application. Planned copy/shared-content edits change functional Steps and links
and therefore follow the functional rebuild path, not the presentation-only path.
Creator shows the project path, exact child launch path/classpath,
child PID, graph counts, and last successful build time.

The compiler splits plans, routing, Trigger entrypoints, handlers and development tables into
bounded Java methods and classes inside one JAR. There is no fixed total-node or Trigger-count
ceiling. E2Es execute 16,385 ordinary Steps in production and development builds and build 513
Triggers. Small tables stay inline; wide outcome tables are extracted automatically.

Compilation still materializes the complete project and generated source strings.
A lowered Step plan is bounded to 32,768 characters and the development Example manifest
to 4 MiB. These are distinct from a total-node ceiling. Million-Step compilation is
not certified; removing a fixed limit does not eliminate memory or bytecode budgets.

### Current Generated Flow Shape

[ApplicationGenerator](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ApplicationGenerator.java)
emits one `Flow_<trigger>` class, partitioned route/plan helpers and static method references.
The production executor loops over a numeric current-Step position, dispatches a compiled
`switch` to `execution.call(plan, handlerCall, received, inputResolver)`, then selects the next
position from the declared outcome. `handlerCall` binds directly to `StepClass::process` and receives
the cached configuration separately from the invocation input. No Step object is constructed.
Routing and input resolvers are generated Java, not runtime interpretation of the project JSON.

The artifact still uses `WorkflowRuntime.Execution`, Step plans, `StepInput`/`StepResult` and
canonical context values. It is not yet a fully flattened chain of minimal specialized methods,
and one Flow is not one Java Stream, OS thread or independently scheduled lane. Static calls
do not remove these call, value or allocation costs. Groups and their visual identity are absent.

The [variant tests](../../modules/railix-core/src/test/java/dev/nanonative/railix/core/GeneratedApplicationVariantsE2eTest.java)
check compiled inputs, primitive routing codes and production omission of development machinery;
they are existing evidence, not a fresh run or a throughput guarantee. Straight-line specialization
is a possible measured optimization, not a selected compiler rewrite. The current generated Java
is compiled to JVM bytecode; jlink/jpackage packaging does not make it a native machine-code Flow.

## Runnable Project

The [lowercase project](../../examples/lowercase-app/railix.project.json) is the complete
Application -> CLI Trigger -> Lowercase -> End journey. Its receive reads
`context.payload.arguments[0]`; its return writes `context.result`. After building the
[launcher](../../README.md#build-and-start), run it from the repository root:

```sh
(cd examples/lowercase-app && "$RAILIX" run "Hello RAILIX")
```

## Generic Input Declaration Example

The definition uses only the generic recursive input grammar available to third parties:

```java
.input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
.input("value", Input.candidates(
        Input.option("current").fromParent("field"),
        Input.option("literal")
                .input("literal", Input.json(ValueShape.ANY)
                        .defaultValue(RailixValue.nullValue()))
                .fromOwned("literal"),
        Input.option("field")
                .input("source", Input.path(READ)
                        .defaultPath("context", "payload"))
                .fromOwned("source")
).defaultCandidate("current"))
.input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
.implementation(ChangeField.class);
```

Its implementation uses only the declared input names:

```java
public final class ChangeField {
    private ChangeField() {
    }

    public static StepResult process(final StepInput configuration, final StepInput input)
            throws InterruptedException {
        return input.run("steps").writeWhenPresent("field");
    }
}
```

`JSON`, `PATH`, `OPTIONS`, `CANDIDATES`, Boolean `MATCHER_GROUPS`, and ordered `STEPS` are generic
inputs. A parent or owned option source is explicit. Input names and unary Step port names are not
reserved.

## Creator Matcher Discovery

Creator displays shared transforms separately from independent AND matcher programs. It derives
both lists from ordinary unary Step receive and return contracts; there is no matcher kind or Step-ID
allowlist. Step developers can add catalog-only search aliases with `.searchTerms(...)`; `eq`,
`neq`/`ne`, `gt`, `gte`/`ge`, `lt`, and `lte`/`le` resolve the built-in equality and exact-number
matchers. Built-in matchers start with total defaults: JSON `null` for equality, zero for number
comparisons, and an empty string for literal text boundaries. Regex is not in the standard catalog
until a bounded engine is accepted.

## Acceptance Boundaries

| Behavior | Existing public proof / remaining gap |
| --- | --- |
| Flat graph and explicit outcomes | Compile a project and run the generated CLI/HTTP application; reject missing, ambiguous and invalid links before handlers run. |
| Context and nested primitives | Generated application tests cover mapped values, no-write outcomes, faults and cancellation; catalog rules live in [standard library](standard-library.md). |
| Edit recovery | Creator tests retain the previous child on invalid edits; presentation-only edits do not restart it. |
| Large graphs | Earlier compiler and scene probes are recorded in [verification](../verification.md#compiler-and-observation-scaling); they do not certify million-Step compilation. |
