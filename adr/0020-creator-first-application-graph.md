# ADR 0020: Creator-First Application Graph

## Status

Accepted on 2026-07-29. Refined through 2026-08-28 to separate the flat functional graph from
optional Creator metadata, remove compiler-expanded reusable flows, and add generic explicit
flow-control inputs.

## Context

Railix Creator is the product entrypoint. One project must describe exactly the application that
the compiler executes and the release build will package. Visual grouping must help people manage
large graphs without becoming a second execution model or making functional builds depend on one
Creator version.

The previous implementation mixed reusable-flow definitions, compiler expansion, presentation,
global blueprints, and functional graph data in one file. That added an alternate model before
basic graph editing was stable. It has been removed.

## Decision

### One Flat Functional Project

One `railix.project.json` produces one deployable monolith. Its root contains only:

```text
format  contract version
id      authored project name
nodes   every fully materialized functional Step
links   every functional continuation
```

There is no functional group, reusable-flow invocation, blueprint reference, hidden expansion, or
second selected-Step list. Link topology owns execution. JSON object key order does not.

The platform supplies one `railix.app` Step at canonical node ID `app`. It is mandatory, unique,
persisted, and non-deletable. Trigger definitions are catalog Steps connected from App. Each
Trigger owns a non-empty list of named Examples. An Example has a required payload and an optional
whole context object; Creator writes the payload to the Trigger's current example-target path.
Every Example is an independent case. Creator unions paths and shapes for suggestions but never
merges values into synthetic data.

Creator-generated mutable nodes use opaque UUID-backed IDs generated once. Reordering, insertion,
and deletion never renumber existing IDs. IDs have no timestamp or ordering meaning. Hand-authored
safe IDs remain valid project identifiers.

### One Workflow Context

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
the context itself. Creator examples carry `context.runtime.test=true` when sent to the built
development application, allowing project Steps to react explicitly. Creator does not otherwise
interpret that flag. There is no event entity, event bus, event store, or retained execution
object.

### Minimal Step Kinds

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

### Candidate Conditions

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

### Field Manipulation

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

### Filter

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

### Choice

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

### Switch

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
owning Step's Creator presentation and merely reference those IDs. Shared group occurrences align
authored routes by ordered candidate slot while preserving each occurrence's concrete route IDs and
labels. Compilation adds the configured routes to that node's immutable outcome plan. Generated
applications initialize the node-specific candidate routes once, while each invocation holds only
its own selected outcome.

### Optional Creator Metadata

`railix.creator.json` is optional and contains only:

```text
format  metadata contract version
groups  visual semantic-zoom groups
steps   per-Step name/color/icon and outcome-label presentation
```

The compiler and application never read this file. Removing it cannot change compilation,
dependencies, execution, or results. A missing or invalid file opens the functional graph flat.
Invalid source is preserved for recovery and shown as a targeted Creator diagnostic.

A group contains optional `name`, `color`, and embedded Base64 `icon`, plus one or more
occurrences. An occurrence contains:

```text
id      stable opaque occurrence ID
flow    owning Trigger ID
parent  enclosing occurrence ID or null
steps   logical slot ID -> concrete functional Step ID
```

There is no redundant `members` array, `instances` map, `global` flag, live link, or compiler
group representation. Logical slot IDs and occurrence IDs are UUID-backed opaque values generated
once. Shared occurrences use exactly the same slot set; each occurrence maps those slots to its
own concrete Step IDs.

Each occurrence is a non-empty connected region in one Trigger flow with one entry derived from the
flat links. Occurrences remain inside their parent region, cannot overlap siblings, and cannot form
parent cycles. Creator selects either end of one ancestor/descendant path, so reverse selection and
secondary routes work while sibling boundaries on different paths are rejected explicitly. Valid
metadata may represent a complete divergent region. A collapsed group renders every exact edge that
leaves its region; opening it renders every member and boundary exit.

Shared occurrences have equal logical slots and equivalent Step/route topology. Structural edits on
primary or secondary outcomes preserve those slots. Detaching or deleting a parent occurrence
reparents nested groups, and group deletion remains metadata-only.

Opening a group adds one semantic-zoom level. Groups can nest without a depth-specific execution
model because every contained Step already exists flat in `railix.project.json`. Deleting a group
preserves all functional Steps and reparents nested groups. Editing a Step shared by multiple
occurrences requires one explicit action:

```text
Update all
Detach this
Create variant
Cancel
```

Structural insertion or deletion under `Update all` creates or removes the corresponding concrete
flat Step in every occurrence while preserving logical slots and ordinary project links.

### Creator And Compiler Responsibilities

Compilation is pure structural work. It parses, validates, applies Step defaults, builds the flat
executable, and returns deterministic diagnostics. It never invokes a Trigger or Step handler.

Creator edits, persists, compiles, starts one real development-application JVM, and observes that
application through authenticated local development endpoints. Explicit named examples are sent
to the built application and run as normal whole-flow executions. Creator does not execute a flow
or individual Step in its own process. A Step preview returns the explicit Example context captured
immediately before that Step plus the normal post-Step execution result; it never samples production
traffic or changes application execution semantics.

Functional edits trigger a rolling replacement only after a valid application starts. Invalid
edits keep the previous application running. Creator-only presentation/group edits persist without
restarting the application. Creator shows the project path, exact child launch path/classpath,
child PID, graph counts, and last successful build time.

The current Creator authors every declared route of fixed- or authored-outcome ordinary control
Steps. Unscoped layout follows deterministic declared-outcome depth-first order through an explicit
traversal stack, so nesting depth does not consume the JavaScript call stack. All add, edit, delete,
grouping, and appearance controls live in the Inspector rather than graph nodes. Diagnostics and
malformed route states appear on their owning node, outcome, or group.

### CLI Trigger

The CLI Trigger declares `maximum_instances = 1`, owns the source `application.arguments`, and
defaults its writable target to `context.payload.arguments`. A process has one command-line
argument vector, so a second Trigger claiming that unique source is rejected instead of receiving
an invented loop or broadcast.

`railix run [arguments...]` supplies ordered strings once. Non-null `context.result` is printed
and numeric `context.exit_code` controls process status. Defaults are JSON `null` and `0`, producing
silent success when the flow writes neither. Interactive terminal sessions are not inferred.

### Planned Environment Builds

Final environment applications will derive direct Step calls, dependencies, JDK modules, `jlink`,
and `jpackage` output from the complete flat project. Development project/build metadata, preview,
trace, live error, queue, and metrics endpoints will be independently includable build modules.
Omitted capabilities must contribute no route, class, dependency, or JDK module.

Remote attachment, aggregate metrics, queue control, permissions, sharding, and production
debugging remain roadmap work. Current examples never sample production traffic.

## Invariants

- One functional project produces one monolith.
- Exactly one persisted App exists at node ID `app`.
- Every Trigger connects from App and has at least one named example.
- Every admitted item owns a fresh context; handlers retain no item state.
- Only `context.runtime` is reserved and read-only.
- `APP`, `TRIGGER`, and `STEP` are the complete current kind set.
- Compiler validation never invokes handlers.
- Every functional Step and link is materialized in `railix.project.json`.
- Creator metadata never enters executable JSON or dependency selection.
- Missing or corrupt Creator metadata never changes functional behavior.
- Group deletion never deletes functional Steps.
- IDs are stable and have no ordering semantics.
- Every declared outcome selects at most one successor.
- Filter selects exactly one explicit `match` or `otherwise` successor.
- Choice evaluates ordered OR/AND matcher groups and selects exactly one explicit `match` or
  `otherwise` successor.
- Switch selects the first accepted authored candidate route or its explicit `otherwise`
  successor.
- Merge, Split, and Loop behavior requires explicit Step contracts.
- Creator never replaces a valid running application with an invalid project.
- Runtime and release dependencies derive from the complete project, not UI selection.

## Consequences

Compiler and runtime have one canonical model and one execution path. Visual groups can evolve or
be lost independently. A newer Creator can still open the flat project even if presentation
metadata is incompatible. The cost is that reusable/global groups do not yet cross projects.

## Rejected Alternatives

Triggers outside the Step catalog, automatic heaviness or kind detection, a dedicated kind or
execution engine for unary Steps, hidden coercion, multiple deployable
applications per project, compiler-expanded reusable-flow definitions, global live blueprint
links, timestamps or counters as identity, running handlers during compilation, rebuilding with
`jpackage` after every edit, and TypeMap's failed-conversion-to-null semantics are rejected.

## Acceptance Checkpoint

The first accepted public journey remains:

```text
Application -> CLI Trigger -> Lowercase[arguments[0] -> result] -> End
```

The mapped Lowercase invocation is an ordinary graph node. Its receive reads
`context.payload.arguments[0]`, its return writes `context.result`, and its `ok` outcome links to
End. The active control checkpoint additionally proves ordinary Filter, Choice, and Switch
definitions, generic ordered OR/AND matcher groups, generic authored candidate outcomes, explicit
flat outcome links, deterministic branch layout, route-specific insertion and deletion,
branch-aware semantic zoom with exact boundary exits, rolling-built example execution and previews,
preserved shared groups with occurrence-local route IDs, and separate desktop/mobile proof.

## Supersedes

This ADR supersedes ADR 0015, ADR 0016, ADR 0017, ADR 0018, and the reusable-flow/global-blueprint
parts of its earlier revision. ADR 0013 retains the three-module product boundary and ADR 0014
retains canonical primitive data. Runtime settings and secrets remain unsupported as recorded by
superseded ADR 0019. ADR 0021 defines exact unary operation semantics.
