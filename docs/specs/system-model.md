# System Model And Flow Control

## Human Review

Status: **Existing baseline with accepted target boundaries**, updated on 2026-09-19.
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

- **State and resource lifecycle:** define who owns listeners, connection pools, storage handles
  and transaction scopes while Step handlers remain stateless. Decide admission, cancellation,
  deadlines and drain/release behavior across normal completion and rolling replacement.
- **Control and side effects:** Merge/Split/Loop need ordering, context ownership, merge-conflict
  and bounded-work rules; [Merge modes](#planned-merge-and-join) now have accepted direction but
  incomplete semantics. I/O needs explicit outcomes for failures with unknown completion;
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

- Let the application own shared listeners, pools and storage handles; give each invocation its
  own context and explicit resource leases/transaction scope. Stateless handlers use resources
  without creating a new pool or client for every tiny Step.
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

Every admitted item owns one isolated mutable JSON-compatible context. Projects may create any
ordinary keys. These names are conventions rather than runtime containers:

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
result and does not interpret that flag. There is no event entity, event bus, event store, or
retained execution object after the bounded trace completes.

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

## Candidate Conditions

`CANDIDATES` and every matcher inside `MATCHER_GROUPS` use one condition structure. An empty
`when` array means presence-only acceptance. A conditional candidate stores shared preparation and
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

Legacy flat `when` arrays remain an ingress form and are normalized deterministically to the same
structure. Creator authors only the structured form when a condition exists. Step discovery and
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
branching, fan-in, fan-out, and loops.

## Filter

Filter is one ordinary standard-library `STEP`, not a kind or compiler intrinsic. Its generic
ordered `conditions` candidates select a workflow-context field or literal and optionally run a
condition. A present source with no condition matches; otherwise every authored `all` program must
finish BOOLEAN and pass. The first accepted candidate selects `match`; no accepted candidate selects
`otherwise`.

Both outcomes always have explicit links in `railix.project.json`. Creator materializes both links
when Filter is added, renders declared outcomes in deterministic depth-first order with an explicit
stack, and inserts later Steps into the route selected in the Inspector. Missing, duplicate,
unknown, or repeated links remain visible on their owning outcome and are never presented as End.
Functional compilation rejects those malformed links before a project enters a Creator workspace;
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

Status: **Accepted mode direction; not implementation-ready.** The user requests configurable
non-waiting convergence and waiting for all inputs, with synchronous/asynchronous behavior.
Logical/electrical analogies are an exploration request, not a chosen operator catalog or engine.
Current compilation still requires one incoming link per non-App node and rejects cycles.

- **SYS-008:** Flow convergence MUST support a configured mode that continues without waiting
  for other inputs, and a configured mode that waits for its defined required inputs before
  continuing. The required-input set, correlated execution scope and resulting context must be
  agreed before implementation; a mode name alone is not sufficient execution semantics.

Recommendation: distinguish three responsibilities rather than encode all in a sync/async flag:

| Responsibility | Proposed behavior | Open boundary |
| --- | --- | --- |
| Non-waiting convergence | Continue the common downstream path for each arrival; do not wait for absent alternatives. | It is not a race winner that discards later arrivals, and does not automatically combine payloads. Confirm cross-Trigger ownership/response routing before lowering. |
| Waiting join | Continue once the required branches for one correlated activation are complete. | Decide same-request versus independent-event correlation, branch/activation IDs, skipped inputs, duplicates and later iterations. |
| Execution scheduling | Decide whether work runs inline or suspends/resumes under the application's bounded lifecycle. | Waiting does not imply blocking a thread; asynchronous execution does not imply a mesh or an unbounded executor. |

Established workflow semantics separate [pass-through convergence](https://docs.camunda.io/docs/components/modeler/bpmn/exclusive-gateways/),
[waiting for all branches](https://docs.camunda.io/docs/components/modeler/bpmn/parallel-gateways/) and
[waiting for activated branches](https://docs.camunda.io/docs/components/modeler/bpmn/inclusive-gateways/).
These are references, not dependencies or a BPMN-compatibility promise.

AND/OR/XOR/NAND over Boolean values are not interchangeable with arrival rules. A delayed branch
is not Boolean false. Negated or exclusive arrival conditions need an explicit completion boundary
to establish that another input will not arrive. Recommendation: use reusable predicate logic
for values and explicit join policies for control; circuit symbols can help Creator explain both,
but must not hide timing, buffering or duplicate-execution behavior.

Decision timing:

- **Resolve now for the next flow-control slice:** whether waiting joins initially correlate
  only branches of one original request. Combining independent events needs an explicit key and
  completion/window rule; a queue or future mesh must not decide this implicitly.
- **Before implementing either mode:** define context ownership/output mapping, ordering, duplicate
  arrivals and emitted execution count. Cover shared continuation across Triggers if in scope.
- **Before waiting/suspension:** decide activated versus all-wired branches, deadlines, branch
  failure/cancellation, buffer bounds, cleanup and retained-context proof. Waiting on all wired
  inputs after an exclusive Choice otherwise risks waiting forever.
- **Before later operators:** decide inclusive/first/N-of-M/negated policies and repeated visits
  only when selected; no full gate family, physics simulation or distributed join is implied now.

Acceptance for SYS-008 must exercise the selected mode through a real generated Flow and Creator:
each non-waiting arrival, required arrivals in either order, a skipped/failed branch, duplicates,
cancellation and two simultaneous requests that must not be combined accidentally. These are
planned scenarios; exact output and failure expectations are still blocked on the decisions above.

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

Compilation is pure structural work. It parses, validates, applies Step defaults, builds the flat
executable, and returns deterministic diagnostics. It never invokes a Trigger or Step handler.

Functional edits trigger a rolling replacement only after a valid application starts. Invalid
edits keep the previous application running. Presentation-only group edits persist without
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
emits one `Flow_<trigger>` class, partitioned route/plan helpers and reusable handler instances.
The production executor loops over a numeric current-Step position, dispatches a compiled
`switch` to `execution.call(plan, handlerCall, received, inputResolver)`, then selects the next
position from the declared outcome. `handlerCall` is a statically bound `HANDLER_n::run`.
Routing and input resolvers are generated Java, not runtime interpretation of the project JSON.

The artifact still uses `WorkflowRuntime.Execution`, Step plans, `StepInput`/`StepResult` and
canonical context values. It is not yet a fully flattened chain of minimal specialized methods,
and one Flow is not one Java Stream, OS thread or independently scheduled lane. Handler reuse
does not remove these call, value or allocation costs. Groups and their visual identity are absent.

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
.run(ChangeField.class);
```

Its implementation uses only the declared input names:

```java
public final class ChangeField implements StepHandler {
    public ChangeField() {
    }

    @Override
    public StepResult run(final StepInput input) throws InterruptedException {
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
