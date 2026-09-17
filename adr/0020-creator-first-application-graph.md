# ADR 0020: Creator-First Application Graph

## Status

Accepted on 2026-07-29. Refined through 2026-09-01 to separate the flat functional graph from
optional Creator metadata, remove compiler-expanded reusable flows, and add generic explicit
flow-control inputs, continuous semantic zoom, and explicit Creator/compiler/generated-application
ownership.

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
whole context object; compilation writes the payload to the Trigger's current example-target path
inside the development artifact's Example manifest.
Every Example is an independent case. The generated application returns the selected case's real
contexts and Step projections; Creator never unions Example values into synthetic runtime data.

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
the context itself. The generated development application adds `context.runtime.test=true` when it
executes a compiled Example, allowing project Steps to react explicitly. Creator only displays the
result and does not interpret that flag. There is no event entity, event bus, event store, or
retained execution object after the bounded trace completes.

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
owning Step's Creator presentation and merely reference those IDs. Visual grouping neither aligns
nor copies routes. Compilation adds the configured routes to that node's immutable outcome plan.
Generated applications initialize the node-specific candidate routes once, while each invocation
holds only its own selected outcome.

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

A group definition contains:

```text
id        stable opaque group ID
name      optional display name
color     optional #RRGGBB accent
icon      optional embedded SVG or PNG
boundary  optional solid, dashed, or dotted outline
```

The `steps` object stores optional per-Step presentation. An ordinary Step presentation may refer
to one declared group ID; App and Trigger cannot. A group may be empty. Group Manager owns group
identity and appearance, while Step Appearance assigns or unassigns one Step. Creating a group does
not implicitly assign the selected Step. Deleting a group removes only the definition and its Step
assignments; every functional Step and link remains untouched.

Creator derives one region for each connected component of Steps assigned to the same group. One
group may therefore render as multiple disconnected regions without persisted occurrences,
members, parentage, geometry, or camera state. Structural edits never propagate between regions and
new Steps never inherit a group implicitly. Continuous pan and zoom change presentation detail by
scale rather than opening or collapsing a second graph. Large ungrouped branches may receive
deterministic temporary regions; those regions are not metadata.

The continuous-world contract replaces scaling a complete DOM graph: zooming into a region reveals
its recursively indexed children at stable coordinates in the same world. An aggregate is not a
collapse control. The Creator server owns a derived scene index; viewport requests return bounded
visible stations and rails. Native WebGL2 draws geometry, with a bounded DOM overlay for labels,
keyboard focus, and selection. The previous all-card renderer is not retained as a fallback.
Unsupported WebGL2 reports a visible capability error.
Automatic corridors occupy compact outer footprints so nearby junctions and terminals remain
readable. Membership edits and deletion retain a surviving selection as the navigation target.
Appearance-only edits do not move the camera. Station labels take priority over route captions.
Invisible spatial-index containers preserve one affine layout transform instead of independently
insetting child rows. This keeps a final Step and its terminal on the same horizontal lane.
Step and Group metadata share optional `shape` (rectangle, ellipse, triangle, diamond), `aspect`
(width / height from 0.5 to 4), and `roundness` (0 to 50 percent of the shorter rectangle side).
Defaults remain implicit: rectangle, 2.625, and 0. Glyphs fit the same bounded station envelope;
expanded group boundaries still enclose their contents. Fill, outline, port intersections, and
pointer hits use the same bounded contour; small shapes retain external accessible labels.
Status marks fit an inscribed content rectangle, including narrow shapes, rather than using fixed
width decoration that can escape their outline.
The latest user navigation wins over delayed scene reads or queued edit reveals. Group-focus
aliases and visible occurrence identities occupy disjoint namespaces; scene identities are opaque
and never become persisted Group IDs.

One functional JSON remains the source of truth. Spatial indexes, recursive regions, layout,
camera, and scene caches are rebuilt, not persisted. Only user-authored presentation warrants the
optional second JSON. Compiler and built application remain unaware of both scene and metadata.
`GET /api/editor?node=<id>` returns full configuration only for App, the selected Step, its
predecessor and its owning Trigger, plus shallow connection targets and canonical node indexes.
Group definitions use `q` and `offset` pagination (64 results plus an explicitly selected Group).
The browser retains this neighborhood and unsaved drafts, not every visited Step. The server owns
flow membership, global usage counts and Group occurrence counts. Deleting a flow expands its
Trigger ID into canonical stable-ID removals on the server; deleting a Group unassigns all members,
including unloaded Steps. The editor saves changed entries through revision-checked
`PATCH /api/project` and `PATCH /api/creator`. Nodes and groups are keyed by stable ID; links are
keyed by source port with all of that port's targets retained. Removing an entry uses JSON null.
An edit contains `revision` and `changes`; successful responses contain revision, workspace and
application facts, not the documents. The original compiler and metadata validators still own
acceptance. Individual HTTP requests retain their size budget; trusted local files and assembled
documents do not inherit that transport limit. Explicit whole-document import
uses POST; it is not a fallback for failed edits. Stale edits fail without overwriting accepted work.
Queued edits are compared with separately acknowledged functional and presentation snapshots, so
an older save cannot swallow a newer reversal or discard a rejected draft. There is no edit log or
new persistent format. Reads wait for the current write acknowledgement and reject an intervening
write before replacing the editing neighborhood. Invalid JSON drafts survive unrelated navigation
and saves. Whole-document GET remains an explicit interchange boundary, not browser startup.

One diagram combines pending build changes, Example paths and execution metrics without view modes
or discovery prompts. Example selection lives beside its Trigger definition and survives navigation
between the App and downstream Steps. Trigger summaries retain their Example count beside a compact
run count; full sampled timing and error counters remain available in the Inspector and hover text.
Collapsed Groups and automatic regions have the same station size
as ordinary Steps; expanded contents keep their derived world geometry. Example coverage comes
from the built application's completed suite and selected trace, never simulated traffic.
`GET /api/scene/observations?revision=...` accepts the viewport and an optional `example` ID and
combines the captured running application's scoped metric and Example-membership queries. Static
membership is a derived interval index; neither view state nor groups enter the artifact. Queries
aggregate in the application and return only requested groups. Creator batches fragmented or large
memberships instead of downloading full metric series or replaying the selected trace. Scene
revision, activated artifact and PID must agree; activation failure or replacement cannot attach
old observations to a new graph. Responses contain bounded counts and route reach information,
never production payloads or Example contexts. Two admitted response drains bound concurrent
aggregation; the browser cancels obsolete reads and polls once per completed read plus one second.
The complete upstream poll has a 30-second deadline. Example batches must share one suite revision;
if that revision changes, coverage and selection are omitted for that poll rather than mixed.
Pending or unavailable capabilities omit only their fields; malformed successful responses fail
closed rather than inventing zero measurements. Hidden pages stop scene observation reads.
Unchanged observations do not redraw the world or rebuild the selected Example preview.
Picker updates deferred while the user interacts are retried by the existing poll after interaction,
not by another timer or a full Inspector redraw. Runtime metrics are disclosed on demand; the
per-Step metrics build setting remains immediately available.

The built application owns one metric catalog (`GET /v1/metrics/catalog`). Definitions declare ID,
label, unit, counter/gauge kind, supported scopes, sum/max/none aggregation and sampling dependencies.
Creator caches that catalog per child and exposes it through `GET /api/metrics/catalog`; no
metric-name whitelist belongs in the relay. `POST /v1/metrics/query` accepts an optional metric-ID
selection and process group. Scene responses keep measurement values in a separate `metrics` object;
batch reduction follows the definition, and unsupported or unavailable measurements stay absent.
UTC observation time and monotonic elapsed time identify each read. The frontend owns formatting,
counter deltas and derived rates/averages, not the producer or Creator Java backend. A new display
of existing measurements requires no generated-artifact change. New instrumentation still does.
Read-side descriptors do not change counter storage, generated Step calls or per-request recording.

Revealing a newly added Step uses the existing focus request directly, superseding an unfinished
older focus. It does not wait for an unrelated scene refresh or add a second camera-state mechanism.
The route index aggregates connections between visible representatives and skips internal edges.
If the connection budget would be exceeded, the frontier coarsens and is queried again: connections
are never truncated at a traversal count. Multiple hidden outcomes sharing one visible connection
are unlabeled until zoom reveals them. This changes presentation only, not routing or execution.
Motion uses fresh measured counter deltas, a logarithmically bounded rail density, and at most
30 animated draws per second. Animation-only draws reuse the vertex buffer and labels, updating
one shader uniform; no per-request object or execution path is added. A three-second freshness
window prevents stalled polling from implying continuing traffic. Zero/unavailable rates, hidden
pages, reduced motion, graphics-context loss and disposal stop motion. Selected Example paths
remain independent of animation and sampled timing heat.
On shared trunks, inactive rails are painted before active rails and the selected Example is
painted last, so another branch cannot erase its path. This is paint priority, not an aggregate
trunk counter or a per-request particle model.
The selected Example's values appear beside source and target fields and chooser rows, never as
a merged Example value. Writable fields distinguish before and after; missing is not null, false,
zero, empty or unreached. Intermediate program results and errors remain available without duplicate
Built example/output blocks. Inspector visibility is ephemeral; closing it preserves selection,
camera and drafts, and Escape dismisses an open field chooser before the Inspector.
Selected membership and union coverage are captured together at one application-owned suite revision;
reached Steps cannot precede the corresponding coverage bits. Scene and observation responses
are capped at 2 MiB. Application response readers enforce a 30-second deadline over headers and
the complete bounded body, cancel timed-out reads, and release forwarding admission on every exit.
Region counters sum contained Step series without adding flow totals again. Disabled metrics,
zero executions and absent duration samples are different states. Heat compares sampled mean
duration within the visible scene; sampled averages are not percentiles and latency alone does not
prove a bottleneck. Connection ingress is available only where the one-parent graph and enabled
destination metrics establish it; terminal outcomes are not inferred. Connection width uses exact
counter differences between two current-PID, current-viewport snapshots, never a retained Example path
as continuing traffic. Portable icons are deduplicated in each visible scene, never repeated per node.

Examples enter at Trigger output and use ordinary compiled routing, handlers and metrics. The
generator does not skip measurement based on the test flag. Optional tracing adds observation,
not a second Example engine. The runtime test flag remains available for user-authored conditions.

Format 2 is canonical. Valid format-1 occurrence metadata is accepted only at ingress and rewritten
to format 2. When legacy groups overlap, the deepest valid occurrence supplies a Step's group.
Invalid legacy source remains unchanged and the same functional graph opens flat. There is no
persisted occurrence, logical slot, `members`, `instances`, `global` flag, live link, shared-edit
propagation, or compiler group representation.

### Ownership Boundaries

Compilation is pure structural work. It parses, validates, applies Step defaults, builds the flat
executable, and returns deterministic diagnostics. It never invokes a Trigger or Step handler.

Creator edits, persists, invokes compilation, publishes one development artifact, starts its JVM,
and observes it through authenticated local management endpoints. The compiler lowers explicit
named Examples into a bounded manifest packaged in that artifact. The generated application reads
the manifest and starts its own Examples as normal whole-flow executions. Creator has no Example
execution endpoint, scheduler, trace store, or fallback result. A Step projection returns the
explicit Example context captured immediately before that Step plus the normal post-Step execution
result; it never samples production traffic or changes application execution semantics. Observation
backpressure or storage failure stops trace recording, never the Flow. The Creator server relays
application-owned Example inventory and projections through at most four transient 16 MiB buffers;
these transparent relays finish each application read before sending the response and never parse,
persist, or cache it. Separately, two bounded scene-observation requests may parse the application's
coverage, selected route summary, or metric snapshot to aggregate visible regions. They never read
Example contexts or production payloads. The application also aggregates all real cases for one selected deployed node so the browser
can derive union field choices and compatibility without replaying Examples or decoding trace
events. Its browser parses and temporarily caches only the current deployment's responses needed
for display.
Contract-declared default paths form an authoring floor when management data is unavailable;
application projections may refine or add observed paths, but cannot erase declarations or survive
a PID, fingerprint, or process-state change. Only application projections provide observed values.
Metric JSON identifies its owning application PID. Creator rechecks the child identity after each
read, and the browser accepts a response only when its PID and captured deployment identity still
match. Aggregate per-Step Example projection has one owner from its frozen case snapshot through
response transport. Concurrent reads allocate no second case snapshot. The owner preflights all
immutable completed trace sizes and rejects source replay above 64 MiB instead of allocating an
index or duplicating full contexts into every trace event.

Example interruption is cooperative for five seconds after the 30-second execution deadline. Java
cannot safely reclaim a trusted Step that ignores interruption, so the generated development JVM is
the hard ownership boundary and exits rather than retaining the worker. Management request bodies
finish under a five-second, 48-reader budget before they may consume the independent 32-run or
16-trace execution budgets. Missing or invalid tokens use a separate four-reader budget and cannot
consume authenticated body or execution admission.

`StepDefinition` owns only reusable declarative capability: identity, graph role, generic inputs,
paths, outcomes, defaults, implementation address, and optional catalog presentation or starter
Example values. Starter values are copied into the functional project when a Step is added. The
project owns authored Flows, Steps, and Examples. Core owns canonical values, project validation,
lowering, generated source, and runtime contracts. The generated application owns execution and
development telemetry. Creator provides the project editor, build, child-process lifecycle, and
read-only display; it does not own Example execution or results. These boundaries do not provide
alternate execution paths for one another.

Functional edits trigger a rolling replacement only after a valid application starts. Invalid
edits keep the previous application running. Creator-only presentation/group edits persist without
restarting the application. Creator shows the project path, exact child launch path/classpath,
child PID, graph counts, and last successful build time.

The current Creator authors every declared route of fixed- or authored-outcome ordinary control
Steps. Layout follows deterministic declared-outcome depth-first order through an explicit
traversal stack, so nesting depth does not consume the JavaScript call stack. One ephemeral camera
provides continuous pan, cursor-anchored zoom, Fit, group focus, and scale-based detail. All add,
edit, delete, grouping, and appearance controls live in the Inspector rather than graph nodes.
Diagnostics and malformed route states appear on their owning node, outcome, or group.

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
and `jpackage` output from the complete flat project. Current production JARs physically omit the
complete development runtime, Example manifest, traces, metrics, and management routes. Splitting
project/build metadata, Example projection, live error, queue, and metrics into independently
selectable environment capabilities remains planned. Every omitted capability must contribute no
route, class, dependency, or JDK module.

Remote attachment, time-window/custom metrics, queue control, permissions, sharding, and production
debugging remain roadmap work. Current examples never sample production traffic.

## Invariants

- One functional project produces one monolith.
- Exactly one persisted App exists at node ID `app`.
- Every Trigger connects from App and has at least one named example.
- Every admitted item owns a fresh context; handlers retain no item state.
- Only `context.runtime` is reserved and read-only.
- `APP`, `TRIGGER`, and `STEP` are the complete current kind set.
- Compiler validation never invokes handlers.
- Creator never invokes a Flow to execute an Example.
- The generated development application owns Example execution, traces, metrics, and management
  endpoints; the production artifact contains none of them.
- Every functional Step and link is materialized in `railix.project.json`.
- Creator metadata never enters executable JSON or dependency selection.
- Missing or corrupt Creator metadata never changes functional behavior.
- Group deletion never deletes functional Steps.
- Each ordinary Step has at most one optional visual group assignment.
- Group regions, automatic regions, geometry, camera, and zoom level are derived and never
  persisted.
- Functional edits never propagate through Creator groups or inherit membership implicitly.
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
links, persisted group occurrences or geometry, group-driven shared edit propagation, collapse as a
second graph state, timestamps or counters as identity, running handlers during compilation,
rebuilding with `jpackage` after every edit, and TypeMap's failed-conversion-to-null semantics are
rejected.

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
continuous pan/zoom/Fit with scale-based detail, metadata-only Group Manager, derived disconnected
group regions, deterministic temporary branch regions, rolling-built example execution and
previews, and separate desktop/mobile proof.

## Supersedes

This ADR supersedes ADR 0015, ADR 0016, ADR 0017, ADR 0018, and the reusable-flow/global-blueprint
parts of its earlier revision. ADR 0013 retains the three-module product boundary and ADR 0014
retains canonical primitive data. Runtime settings and secrets remain unsupported as recorded by
superseded ADR 0019. ADR 0021 defines exact unary operation semantics.
