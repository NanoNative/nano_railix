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
visible stations and rails. CSS geometry or native Canvas sprites render a fixed orthographic world,
with a bounded, screen-facing DOM overlay for labels, keyboard focus, and selection. The CSS camera
matrix also determines hit-testing and inverse-projected viewport requests. The previous graphics
renderer is not retained. CSS remains available without Canvas or WebGL support.
Automatic corridors occupy compact outer footprints so nearby junctions and terminals remain
readable. Membership edits and deletion retain a surviving selection as the navigation target.
Appearance-only edits do not move the camera. Selected stations and application/trigger labels have
priority; route captions receive space before ordinary station details when possible.
Invisible spatial-index containers preserve one affine layout transform instead of independently
insetting child rows. This keeps a final Step and its terminal on the same horizontal lane.
Step and Group metadata share optional `shape` (rectangle, ellipse, triangle, diamond, hexagon,
event, storage, subsystem), `aspect`
(width / height from 0.5 to 4), and `roundness` (0 to 50 percent of the shorter rectangle side).
Defaults remain implicit: rectangle, 1, and 12. Glyphs fit the same bounded station envelope;
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
The App/JVM/System comparison matches metric identifiers after removing their scope prefix,
together with their catalog unit. Unavailable cells stay empty; differing units and duplicate
identities within one scope retain separate rows. JVM heap limits remain JVM measurements.

Revealing a newly added Step uses the existing focus request directly, superseding an unfinished
older focus. It does not wait for an unrelated scene refresh or add a second camera-state mechanism.
The route index aggregates connections between visible representatives and skips internal edges.
If the connection budget would be exceeded, the frontier coarsens and is queried again: connections
are never truncated at a traversal count. Multiple hidden outcomes sharing one visible connection
are unlabeled until zoom reveals them. This changes presentation only, not routing or execution.
Transport uses fresh measured counter deltas and logarithmically bounded carrier density in CSS
transform animations; no per-request object or execution path is added. A three-second freshness
window prevents stalled polling from implying continuing traffic. Hidden pages and disposal stop
animation. The September 10 decision keeps operational motion independent of browser preferences;
explicit Creator preferences for motion, locale and time display remain planned. Selected Example paths have separate blue replay motion, independent of
traffic counters; `None` clears replay. Replay never invokes the application. Idle machines glow,
known never-executed machines are dim, and unavailable or disabled metrics remain unknown rather
than implying zero executions. App-to-Trigger connections represent power, not transport.
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
destination metrics establish it; terminal throughput is not inferred. A successful selected Example
can replay through a terminal when its reached source has exactly one outgoing connection. Multiple
unknown terminal choices remain unknown; this presentation inference adds no runtime measurement.
Transport speed responds logarithmically to exact counter differences between two current-PID,
current-viewport snapshots, never counting a retained Example path as continuing traffic.
Portable icons are deduplicated in each visible scene, never repeated per node.

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
provides continuous pan, cursor-anchored zoom, Home at the App at 50%, group focus, and scale-based detail. The canvas
fills the workspace. Selecting a Step or group opens its Inspector. Overview shows the selection
type, name, a building portrait, construction and available observations. The portrait reuses the
machine geometry/material path at a fixed size, without a second scene, camera or ticker. Inputs,
Appearance and Groups have separate tabs; Focus and Enter Group are header actions, Delete a footer
action. Removing the icon chooser leaves stored icons readable and removes the boot-time icon catalog
request. The footer abbreviates Example coverage to coverage and omits the normal observations
connection message, retaining unavailable-state diagnostics. The searchable construction
tray previews a connection without editing the project. Nodes contain no action buttons. Group
selection is separate from Group Manager; camera focus and wheel zoom reveal the same nested graph.
Collapsed stations use a stable layout-derived `station_scale`, calculated in the bounded Creator
scene response separately from a group's content bounds. Shapes fit this footprint without a
viewport-wide collision rescale or screen-size cap: culling a neighbor cannot resize an unchanged
machine, and increasing camera scale visibly increases both machines and belts between detail levels.
Top-level flows retain their natural compacted layout extent. The former 960-unit width and capped
flow height shrank the flow independently of App and Trigger, producing inconsistent building sizes.
Only nested group contents shrink within their parent footprint; spatial querying and recursive
aggregation still bound the response when the outer world grows. No coordinates are persisted.
The foundation fits a 136-unit maximum dimension before local station/camera scaling. Changing its
contour does not change the building body's size; explicit aspect changes stay inside that frame.
Foundry elevations derive from the shared body size, rather than independent fixed-height meshes.
Focus frames the actual machine body with surrounding space, not only its compact layout bounds.
Its one-shot scene response supplies the minimum scale required by every enclosing unentered region.
Considering only the focused Part allowed a leaf to collapse back into its group when the following
viewport request omitted `focus`. Entered regions still use the existing local 50% entry scale.
CSS top and side surfaces are reused by visible identity and released when culled.
Native controls own input; no independent rendering ticker or graphics framework is included.
Neither surfaces nor camera state are persisted, and the generated application includes none of
these Creator resources. Connection sockets and belt direction derive from the
graph. Example selection is anchored beside its Trigger when an unobstructed position is available;
values and counters update independently in Overview. Optional timing labels never take
priority over selectable station names.
Region expansion is driven by the readable size of its children, not the parent's longest edge.
Automatic hierarchy partitions have up to eight children, rather than repeated binary partitions.
Only the deepest expanded automatic boundaries are shown; named boundaries remain authored context.
Counts of Steps and contained named-group occurrences come from the existing scene index, not runtime
instrumentation or new persisted metadata. Radar follows the camera using a
fixed cell budget and existing topology; it does not acquire global metrics. Keyboard commands are
scoped away from text editing, composition and OS modifiers. Search options use arrows and native
button activation. This applies the contextual controls and removal of duplicate entrypoints from
[Factorio's GUI review](https://www.factorio.com/blog/post/fff-363), and the attribute/comparison/value
structure of [Finder criteria](https://support.apple.com/en-gb/guide/mac-help/mh15155/mac), without
introducing another saved model or a Choice-specific execution path.
The world and label surfaces use non-scrollable clipping; browser focus scrolling must not move
the diagram independently of the camera or displace its controls.
Diagnostics and malformed route states appear on their owning node, outcome, or group.
Containing-region IDs derive from existing scene parent links, not a second membership model.
Pending edits and diagnostics propagate to the containing occurrence, not every use of a group name.
The Inspector scrolls its fields independently of its fixed navigation, tabs and Close control.
Selection may be empty. Floor clicks cancel pending selection loads and remove
both Step and group highlights; pan gestures do not. Escape dismisses the topmost open control before
the Inspector and selection. Example choice remains independent, and the existing metrics poll reads
application counters when no individual Step is selected. Navigation retains the same application's
latest process snapshot until its next read; failures or a different application PID invalidate it.
HUD controls use a shared CSS material and one non-semantic symbol element with pseudo-elements;
native buttons retain labels, focus, disabled and pressed behavior. State-specific styling wins over
the base material. This adds fixed HUD geometry, not per-Step objects, timers, or graphic dependencies.
Settings and Locate use symbols with accessible names/tooltips. A single Settings entrypoint opens
Appearance, Sound and Music tabs, not the selected Step. Sound/music HUD controls toggle playback.
One CSS cursor leaf follows mouse
input over the world only; it adds no animation loop and is released with the renderer. Material
depth uses a lit edge and dark return, not stacked outlines. Replay changes chevron color/motion,
not its directional geometry. Tread seams and chevrons use the track pseudo-elements; one additional
strip per visible run supplies raised cargo roofs and their visible return face. Hidden faces are
omitted for the fixed camera. A world-coordinate phase keeps collinear spans continuous, and all
layers advance together. Long physical runs are clipped to the camera vicinity without changing
their topology or world phase. Idle/working illumination animates opacity on a decorative leaf,
not border colors on machine materials. CSS stepped easing limits transport updates to approximately
24 per second, power pulses to 10 and idle glow to 5; transport layers share the same cadence and phase.
This reduces repeated composition without a JavaScript ticker or disabling operational motion.
Label collision indexing also clips its cell ranges to the viewport: an enlarged off-screen machine
must not allocate cells for its entire projected body. These are visible-work bounds, not Step limits.
Group reveals use a 180 ms translation-only transition; camera input cancels only the tracked
active reveals, without querying animations for every machine. Station size does not change during
these transitions. Machine meshes and conveyor materials retain fixed geometry and scale uniformly
in X, Y and Z with the camera; camera changes do not rebuild side faces or resize tread textures.
Camera-only frames now transform one `.world-factory` inside `#world-plane`, retaining casing,
socket and conveyor materials. Themes must use descendant selectors rather than assuming these
objects are direct plane children, and preserve this wrapper's 3D transform hierarchy.
The cache contains only the bounded scene and a 25% viewport margin on each side. New geometry,
appearance updates, detail changes, leaving the margin or crossing a scale octave reconcile it;
identical scene responses do not. Screen-space labels, picking, terrain and the camera-dependent
insertion preview stay current without moving every factory material. There is no idle render loop,
new dependency, persisted layout or generated-application cost.
Only casing faces facing the fixed CSS camera are allocated. Conveyor and socket identities use world coordinates,
not screen coordinates, so a pan/zoom does not destroy and recreate their animations.
Shared-bus normalization applies only to parallel inlet/outlet legs, classified from source route
coordinates. Lateral L outlets keep their own destination legs; projected floating-point equality
must not decide topology. The native multi-outcome example exposed this rounding-dependent defect
even when the smaller connection fixtures passed.
Joined intervals include through-ports in their T/L masks and
retain selected rim colors. Belt overruns hide beneath casings and junctions; sockets do not paint
opaque strips over the transport. Shape changes adjust socket contacts, not the layout's branch axis.
None of this adds per-request objects, generated-application instrumentation or persisted geometry.
The visual vocabulary follows the consistent-control and in-context-action principles in
[Factorio's GUI design](https://www.factorio.com/blog/post/fff-238) and
[GUI tileset](https://www.factorio.com/blog/post/fff-243), without importing game assets.

Creator owns recursive local theme discovery under its home `themes` directory and the Settings
selector. Theme, reduced motion and audio preferences live in `creator.settings.json` under Creator
home, shared across projects. The embedded theme and scores remain selectable/downloadable even if
local files use the same names. Catalog keys distinguish embedded and local scores; event mappings
and playlists use those keys rather than frontend file lists. `GET/POST /api/settings` uses content
revisions, bounded reads, atomic replacement and a nonblocking file lock for competing Creator writers.
Conflicts are explicit, not silent last-writer wins. Settings are read at startup/menu opening, not
polled. There is no lock, asset, endpoint or settings logic in generated applications. Historical
project theme metadata is preserved but not used for global selection. `created_at` remains only in
Creator metadata. New projects receive the date; existing unknown dates are not invented. Scene decoration
is deterministic from this seed, viewport-bound and never persisted. CSS is loaded as text under
a same-origin page policy; no remote imports/assets, script evaluation, or symlink traversal are allowed.
Groups remain visual summaries of underlying Step metrics, never runtime spans or execution units.
Group timing is the sum of individual sampled means, not the pooled total/sample ratio and not a
measured passage. Creator reads at most 512 member series per page through the application's existing
metric query. A counter-index cursor binary-searches interval membership, avoiding rescanning all
members for every page. Only visible groups retain one partial scalar sum and sample count; pages
are sequential with a 100 ms yield, separate from traffic observations, and complete sums refresh
after ten seconds. Scene revision and application PID invalidate them. Missing samples stay explicit.

The minimap reuses one coarse scene per revision, bins it into at most 32 by 20 CSS cells, and moves
the existing camera. Its read has a five-second deadline and bounded retry, independent of traffic.
Machine centers and routed belt segments have distinct cell geometry. Current viewport observations
and known diagnostics provide state overlays; unobserved areas remain neutral. Selection retains
one world position/identity and colors its cell, without a second selection box or navigation history.
Red is reserved for real errors; timing uses blue
logarithmic shading, not a utilization or bottleneck claim. No extra metrics poll or generated-app
change is required. It never materializes the full functional graph or persists a second layout.
Scenery uses seeded multi-cell districts (ridges, groves, basins) and the machine projection;
distance thins a fixed lattice without reseeding positions. The existing coarse overview supplies
world-space factory plots and transport clearance once per scene revision. Expanding a group or
changing visible labels cannot evict a structure, and close zoom no longer deletes all scenery.
Relief is culled by its projected bounds, including structures whose origin is just outside the view.
Continuous ground regions fade at detail boundaries; their
density follows viewport area and their individual CSS surface stays below 2048 pixels, not a
project-size limit. Only the visible triangular faces of the terrain peaks are
rendered for the fixed camera. Districts and exclusion are presentation, never functional obstacles.
This adopts coherent terrain and exclusion passes from [Factorio terrain generation](https://www.factorio.com/blog/post/fff-401)
and [Age of Empires tree placement](https://support.ageofempires.com/hc/en-us/articles/5026862103188-Placing-Trees).
The [Factorio map-color work](https://www.factorio.com/blog/post/fff-332) supports distinct entity/transport
marks; no game assets or simulation mechanics are imported.

Audio is owned solely by Creator. Effects default on; quiet music waits for a user gesture and
respects a persisted mute. Editable MML text and existing JSON inputs parse into one validated score
model for selection effects, ambient loops and music; no code evaluation, binary media or second
synthesizer is supported. Instruments reuse their voices throughout their notes. Hidden pages suspend
the music context and its scheduler, preserving the track, position and shuffle queue without changing
the playback preference. Returning resumes only enabled, non-manually-paused music. Effects and
previews release their contexts on hide; Stop and page disposal also release music. Explicit Pause
suspends music without advancing the playlist. Settings edits bounded score files; the first music subfolder names its group.
`GET /api/sounds` lists scores, diagnostics and a revision; authenticated `POST /api/sounds` saves,
deletes or installs defaults. Revisions hash the current local sources; mutations use a shared
filesystem lock and reject stale revisions across Creator instances and external edits observed
before saving. An unsaved browser draft retains the revision it loaded, even when the catalog
refreshes. Reselecting a file explicitly loads its latest content before editing again.
Pinned directory handles, no-follow reads and atomic replacement avoid path traversal and partial
updates. Default installation uses CREATE_NEW, never replacing a user's file. Missing music-group
directories are created by moving an owned temporary directory into the pinned parent; unsupported
cross-filesystem moves fail explicitly instead of falling back to symlink-following path writes.
Read and delete requests never create directories. Legacy score-to-text conversion is owned once by
the server catalog boundary, shared by installation and editing; the browser only plays validated scores.
Malformed local scores do not hide healthy entries. Score size, track count, repeat depth/count,
envelopes and note duration are bounded at ingress. MML repeats remain a compact tree: playback
walks them lazily instead of expanding or capping the total played notes. Legacy version-1 JSON
keeps its 256-note bound. These are audio resource bounds, not project-size limits.
One scheduler uses the audio clock for music, previews and ambience, queues onsets only two seconds
ahead and refills every 250 ms. A started note retains its full envelope. Late callbacks skip expired
notes rather than producing a burst and resume the remainder of a still-active sustained note without
moving its original end; missed percussion attacks are discarded. A stall beyond the lookahead can
still interrupt audio before the callback recovers. The future event queue is bounded by this window;
catching up after a foreground stall still traverses the elapsed notes.
Pause suspends the clock and refill timer, and Resume restarts
the same cursors. Looping tracks restart on a shared score boundary. Stop cancels the timer.
Optional named MML controls `decay`, `sustain` and `cutoff` shape tonal envelopes and filtered
timbres; `detune`, `drive`, `pan` and `echo` add paired pitched oscillators, saturation, stereo
placement and two finite beat-synced delay taps without feedback. Ingress rejects duplicate,
unknown, out-of-range and nonfinite controls. Filters and oscillators are retained per voice;
panning and delays are shared per track, never created per note. Echo routing reserves output
headroom and completion includes its tail. Stop disconnects all voices and routing nodes.
Scores without these controls keep their prior sound. No dependency is added.
Listening checks render the same validated scores and scheduler using controlled offline-clock
advancement, not a separate audio asset or alternate playback path. Eight full-length embedded
instrumentals replace the short previews; their existing paths remain stable for saved selections.
No per-Step audio source, media endpoint or audio code is added to the generated application.
Volume controls display percentages while retaining the existing 0-1 preference values. Music uses
that value directly as its master gain; score mixing already supplies headroom. No second attenuation
cap is applied. Full-track render checks include the music master at 100%, not only the raw synth.

Long sequential regions retain forward lanes; visual row folding and its special U-turn routing
were removed after user review. Actual branches provide additional lanes. Partitioning retains
consecutive Steps; spatial sorting must not invent cross-region traffic. Internal padding is applied
once, not accumulated at each partition. Content extents and collapsed station footprints are distinct.
Choice sockets face opposite lateral lanes, centred on the immediate destination stations rather
than weighted by downstream leaf counts.

Explicitly entered regions are transient camera scopes. The existing scene query accepts `inside`;
the same interval index bounds membership and routing without a second graph. Small scopes expand
their automatic partitions, while large scopes retain the existing visible-work budgets. The scene
provides one entry station, independent of viewport culling. Its station scale defines the group's
local zoom reference: entry frames that station at 50%, not all contents at an absolute world scale.
Zoom-out preserves the scope; leaving restores the parent camera and reference. Framing excludes
the HUD/Inspector, reuses the Trigger popover's control bounds and examines only the fixed UI
obstacles, never every Step. No scope is persisted.
Trigger hover reads only its editor neighborhood after a short dwell, aborts stale reads, and never
changes selection or executes an Example. A focused chooser retains ownership across native popup
hover loss; blur returns ownership to the normal hover lifecycle. Cargo uses `overflow: clip` on the belt run: raised faces
remain 3D, but animated overlength cannot spill outside the transport surface.

The Foundry and Classic themes share one scene, transport and camera renderer. Foundry replaces
icon plates with bounded CSS solids selected by existing node kinds and routing outcomes; a group
contains three symbolic decorations regardless of its Step count. No theme changes the functional
graph, metrics or generated artifact. Built-in CSS is copied through the existing local-theme boundary;
local files with matching IDs replace catalog entries, not duplicate them. An embedded catalog supplies
variant names and defaults. Optional sibling JSON descriptors provide the same metadata for local CSS;
variant stylesheets are not also listed as standalone themes. Base and variant links are staged together
before replacing the active pair. Relative PNG/SVG resources use native stylesheet URL resolution.
Authenticated catalog discovery grants a separate read-only asset cookie, scoped to the asset route;
the existing header token remains required for other APIs and all writes. No functional artifact changes.
Installation uses create-new writes and never replaces local work. Discovery occurs on Settings refresh,
not on rendering frames or each asset request; assets use bounded, symlink-free reads. Theme changes refresh the
camera/material projection without restarting the application. Higher decorative faces use browser
surface picking even after pointer capture returns the event to the canvas. The theme declares a
conservative height envelope for culling; no per-machine layout reads are added to camera frames.
Foundry transport follows whole connection paths through elbows, clipped only beyond the viewport
overscan. Per-run loops visibly teleported parcels at intermediate junctions and were removed.
The three visible cube faces are projected once from the fixed camera into one CSS gradient surface;
generated CSS transform keyframes retain the path and speed without per-frame JS work. Parcel count
depends on visible path length (64 material units per parcel), not request rate. Loop endpoints extend
under stations, rather than ending in the exposed belt span. CSS owns motion and pauses through the
existing observation/replay/visibility controls. No runtime metric or endpoint is introduced.
Native review found high renderer/GPU-process CPU with motion enabled, despite good frame times.
The renderer now separates ground transport, screen-space cargo and raised stations into independent
compositing contexts. The outer world is flat; the ground and building layers each retain real CSS
extrusions under the same `--world-camera` projection. Cargo is projected once when preparing its
path, instead of sharing 3D depth sorting with every building face. Buildings cover arrivals at their
casings, and expanded group boundaries remain on the ground. This ordered-layer contract does not
model elevated belts crossing over buildings. No per-frame JavaScript, per-Step wrappers, persistent
coordinates or generated-application code are added. Four additional containers serve the whole
visible scene; stable parts move between layers on region expansion and all layers release on disposal.
Disposal also clears container references: removing the root from the document alone retained detached
parcels and machines while the old renderer remained reachable. A weak-reference/forced-GC regression
checks release after the browser has processed detachment, with the disposed renderer still alive.
Camera batches transform all three layers together; the parcel translation includes the camera's
projection origin so zoom and pan cannot detach cargo from its conveyors.
Viewport queries include the ground beneath elevated roofs and the foundation overhang, scaled to
visible station units (the nearest known station when crossing empty ground), not the top-level unit.
Only the bounded scene is examined, without DOM layout reads. Top-level overhang at deep zoom queried
thousands of offscreen Steps and could hide the focused leaf behind constrained aggregates. The
6,003-node public browser navigation regression now retains focus after the camera settles; 60 measured
navigation requests had p95 1.795 ms and at most 155 world/label DOM elements in its visited views.
Ground-only queries lost a still-visible group during a large wheel zoom, preventing entry; roof
overhang remains necessary and is covered by the existing real-wheel regression.
Explicit promotion, per-building isolation wrappers and mask-free gradient triangulation did not
produce sufficient overall improvement in the probes and were not retained. Traced compositing costs
improved with layer separation. A short native sample with Example replay enabled measured 36-42%
CPU for the shared graphics process and 15-30% for the page renderer; these are not GPU utilization
figures or a matched before/after thermal test. Dense-scene and sustained fan behavior remain unproven;
reduced motion is not a performance fix. Foundry Portals and Groups share the existing body-size
rule; Portal upright and field heights are proportional to that size instead of a separate oversized mesh.
The initial shell-only PNG probe reduced CSS compositing cost but did not meet smooth-frame targets.
It is superseded by production-selectable Renderer Canvas (default) and Renderer CSS, each at high
quality without additional quality tiers. Their existing descriptor IDs are `canvas` and `hq`.
One scene, camera, routing, observation and interaction owner remains in RailixWorld;
RailixCanvas only draws the bounded visible jobs. No PixiJS, custom executable renderer, runtime
endpoint or generated-application asset is added.

Canvas decodes PNG/SVG once per theme load, builds shared bitmaps and alpha picking masks, and caches
ground and building layers. One requestAnimationFrame loop paints treads, continuous route parcels,
switch frames and powered lights. It stops on hidden pages, empty views or disposal. Group borders use a constant
screen-space stroke, not the aggregate's station scale. The Inspector uses the loaded sprite without
another animation loop. Static caches and all bitmap references are released on theme replacement.
Each drawing surface has an 8-megapixel budget; Renderer Canvas uses up to two pixels per CSS point.
Arbitrary CSS cannot alter pixels inside a sprite; materials, shared foundations and HUD stay CSS-themed.
Sprite images must be authored for the fixed theme camera. PNG/SVG are asset formats, not separate engines.
Foundry scenery also uses the atlas: three static plot images replace repeated CSS faces in Canvas
variants and are painted into the existing ground cache before transport. World-seeded placement,
clearance and viewport thinning remain owned by RailixWorld; no scene model, extra drawing surface,
animation loop or persisted geometry is introduced. Missing scenery sprites keep the CSS fallback.
The images add 234,681 encoded bytes and 1,555,605 decoded pixels (5.93 MiB RGBA, excluding masks and
browser copies). These are shared per motif, not allocated per environment building.

The historical four-variant public-renderer comparison included terrain, labels, HUD and real camera calls, with
128 visible processors, six environment plots and identical transport input. Headless Chrome 152 on this Apple M1 Max used
ANGLE Metal at 1920x1080 and device pixel ratio 2. Two forward/reverse rounds per variant and phase
recorded 100 frames (first 20 omitted from interval statistics), page/process CPU deltas and surfaces:

| Variant | Active p95 frame interval | Renderer CPU | Graphics-process CPU | Idle p95 |
| --- | --- | --- | --- | --- |
| CSS Standard | 83.4-100.0 ms | 73.5-74.1% | 123.4-124.8% | 50.1-66.7 ms |
| CSS High detail | 83.4-100.0 ms | 73.9-74.9% | 121.4-123.7% | 50.0-50.1 ms |
| Canvas Standard | 16.7 ms | 17.4-22.0% | 15.2-16.8% | 16.7-16.8 ms |
| Canvas High resolution | 16.8 ms | 15.8-17.3% | 14.9-16.6% | 16.8 ms |

CPU percentages are normalized CPU seconds per wall second (100% is one core), not GPU utilization.
Canvas uses 2.02/8.08 million pixels per surface here, about 23.1/92.5 MiB RGBA across three surfaces,
excluding browser copies, bitmap storage and other page elements. Both retain operational animation.
The same short navigation probe measured p95 200-450 ms for CSS, 16.7 ms for Canvas Standard and 16.8 ms for
Canvas HQ; these single navigation rounds include reconciliation cost and do not establish an HQ
speed advantage. Standard used less surface memory; those lower-quality options were subsequently
removed at the user's request. Renderer Canvas corresponds to Canvas High resolution, Renderer CSS
to CSS High detail. The remaining Canvas choice prioritizes Retina sharpness, not minimum memory.
No sustained thermal, Safari/Firefox, million-Step or application-throughput certification follows.

Latest scenery-atlas evidence: `/private/tmp/railix-scenery-retina-package-smoke.log` and
`/private/tmp/railix-scenery-retina.jsonl`. The initial building-only run remains in
`/private/tmp/railix-renderer-retina.jsonl`. Reproduce with
`-Drailix.renderer.comparison=true -Drailix.browser.dpr=2` and
`CreatorWorldBrowserIT#compareProductionVariantsWith128VisibleStations`. The comparison now discovers
the current descriptor choices; it no longer runs the removed Standard variants.
The original captured sprite strips can be reproduced separately with `railix.sprite.export=true`;
no screenshot converter runs in the shipped Creator.
Group entry closes Inspector consistently for wheel, double-click and explicit entry. A responsive
Inspector may appear under the pointer after the first click; the browser's second-click count and
original selection point preserve that one gesture without delaying ordinary selection. Desktop and
narrow-screen regressions also verify that subsequent Inspector tab clicks retain their own behavior.

Embedded `assets/themes/<theme>/`, `assets/sounds/` and `assets/music/<group>/` mirror the same
relative paths under Railix home. The minimal theme catalog references each theme's own descriptor;
the sound catalog references canonical score paths. Matching user files override individual resources,
missing files retain embedded defaults, and installs never overwrite edits. Old mixed music folders
and JSON scores remain read-only sources; new writes use the separate MML roots. Preferences and
derived caches do not enter the functional project.
The packaged-Creator extension regression starts without system Java, then adds nested CSS/PNG themes
and sound/music MML files, edits them and rereads them through public HTTP routes. The application PID,
functional project and Creator JAR remain unchanged. Project-local asset-root precedence is not added.
The bounded minimap retains four
direction bits per occupied cell so terminal marks do not imply extra branches.

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
