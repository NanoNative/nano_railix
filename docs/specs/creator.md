# Creator Authoring And Factory World

## Human Review

Status: **Direct-editing baseline with remaining advanced reuse cases**, updated on 2026-10-17.
Connection previews, selection grouping, single-entrance reuse and session Undo/Redo are implemented.
The acceptance tables below distinguish this baseline from the wider target.
Resource profiles, automatic Step module visibility and capability symbols are also planned.
Their implementation contracts remain open. Other capability/resource interactions below are proposals,
not implemented or newly verified behavior. The roadmap owns delivery status.

Owns: presentation metadata, editor interactions, scene/camera, transport and visual resource bounds.
Asset packaging, settings and audio belong to [Creator assets](creator-assets.md);
metrics and group timing belong to [observation](observation.md).

Visual language reopened on 2026-10-26: buildings and belts are not required metaphors.
The initial flat module studies were rejected. A bounded, connected Canvas specimen now
explores sculpted ceramic modules, sampled group interiors and light channels. It remains
under visual review. No replacement for the default presentation is accepted yet.

Decisions: [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

## Planned Capability And Resource Guidance

This specification owns user journeys, interaction states, feedback and accessibility, not just
theme geometry. Define those before each feature's implementation; prototypes illustrate the
contract but do not replace it. Backend facts belong to the owning capability specification.
An ADR is needed for a significant architectural choice, not for each icon or panel.

The user asked how to expose required Java modules, capability symbols, bottlenecks and limits.
CR-004/CR-005 below own the requested visibility outcomes; the following interactions are proposed
layouts, not new runtime metric/API contracts:

| Journey | Proposed Creator behavior | Required evidence / unresolved dependency |
| --- | --- | --- |
| Add/configure a Step | Show a concise capability summary; expand to required JDK modules, libraries, platform constraints and build impact. Explain missing/incompatible requirements at the affected Step. | Derive from Step contracts plus resolved build dependencies, not hand-maintained Step-name rules. Generic module/capability metadata is not yet selected. |
| Inspect a build | Explain which Steps/configurations require a module and why shared dependencies remain. Show known total/delta sizes only after resolution or measurement. | A dependency closure is not the sum of per-Step sizes; no fabricated size or security-cost estimate. Changes to included capabilities follow SYS-002. |
| Investigate a slow Flow | Link scene indicators to service time, waiting/backlog, throughput and errors when available; separate a suspected bottleneck from a measured limit breach. | Observation owns windows, sampling and supported measurements. A slow Step or sampled mean alone does not prove saturation; unknown/stale is not zero. |
| Inspect resource pressure | Show scope, measured usage, configured limit and enforcement source together; connect the affected resource to relevant Steps. | Pools/admission limits differ from OS/container CPU or memory ceilings; exact per-Step CPU/memory attribution and portable hard quotas are not promised. |
| Resolve a warning | Select an affected Step/resource to inspect the reason and an actionable control; distinguish selection, warning, error and unmeasured state with text/symbols as well as color. | Keyboard/screen-reader access, supported actions and bounded work on the agreed workload need public UI tests. |

Prefer progressive disclosure over module badges on every machine. Read-only operations views
must not imply permission to change settings. Show whether a change needs a rebuild, and link
to the selected environment/build rather than silently altering an attached instance. Performance
acceptance follows [SYS-006](system-model.md#accepted-platform-boundaries); these proposals add no
per-Step polling loop or requirement for a metrics database.

### Planned Java Module Visibility

Source: the user's original module-visibility request, revisited on 2026-09-20. The required
outcome is automatic identification and UI visibility; the detection mechanism is not yet defined.

- **CR-004:** Creator MUST automatically identify and display a selected Step's required Java
  modules for the selected configuration/build target, including third-party Steps. Missing or
  unresolved dependency information MUST be shown as such, not as an empty verified module list.

Here, Java modules mean JDK/JPMS modules such as `java.sql`, not Railix Maven modules. The UI
must distinguish Step-attributed requirements from the final application's shared/transitive
module closure. Exact attribution and presentation are open; no Step-name-to-module lookup table
or per-invocation dependency scan is selected.

[ADR 0013](../adr/0013-small-java-product-boundary.md) already selects `jdeps` for future
per-project packaging. Its [class-dependency analysis](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html)
is a possible input here, not proof of precise per-Step/configuration attribution or complete
runtime requirements. Before this UI slice, decide how analysis, resolved dependencies and any
necessary declarations agree, report gaps and refresh after Step/configuration/toolchain changes.
Do not execute Step handlers to discover their module requirements. The current universal Creator
runtime retains all build-JDK modules; this visibility goal does not change that packaging contract.

Acceptance for CR-004: inspect built-in and independently installed Steps, change a configuration
or bundle, and verify module attribution plus unresolved/missing states through Creator. Compare
the resolved application closure with packaged execution, including shared dependencies. These
checks are planned; the detection contract and UI are not implemented.

### Planned Step Capability Symbols

Source: the user's 2026-09-20 request for recognizable symbols showing Network, Process/commands,
Threads, HTTP server and HTTP client usage. These are requested examples, not a closed taxonomy
or a selected icon library.

- **CR-005:** Creator MUST expose a compact symbol-based capability summary for a selected Step
  and its configuration, with the same behavior for built-in and third-party definitions. Each
  symbol MUST have an accessible name and a plain-language explanation available without relying
  on color or hover alone.

Capabilities describe behavior, separately from the Java modules in CR-004: network access,
external process execution, explicit concurrency/worker management, accepting HTTP requests or
making HTTP requests. Ordinary execution on a JVM thread is not evidence of worker management.
A capability indicator does not imply current activity, granted host permissions or a security
audit. Unknown or unresolved information must not look like a verified absence of capabilities.

Derive the summary from generic Step/configuration evidence, not built-in Step-name rules.
Module presence alone is insufficient to identify these behaviors. Detection/declaration trust,
capability vocabulary and extension metadata, handling overlapping indicators such as HTTP and
Network, and exact symbols/placement remain open before implementation. Prefer a compact summary
with details on demand rather than permanent badges over every building; themes may change the
symbol artwork, not its meaning.

Acceptance for CR-005: inspect built-in and third-party Steps with the requested capabilities,
change their configuration and exercise unresolved information; verify the summary and its
explanations by pointer, keyboard and touch. Evidence is planned, not implemented UI.

### Example Guidance And Circuit Metaphors

Examples support field mapping and connection through actual execution, not an assertion-only
test panel. [EX-007/EX-008](examples-and-validation.md#behavior) own this requirement. Show newly
created fields with their source Step and selected Example, and distinguish an unvisited Step
from a failed execution or failed expectation. Build omission/explicit retention follows the
[selection contract](system-model.md#planned-compilation-selection), not a visual hiding toggle.

The 2026-09-27 direction introduced mainboard-like organization with 3D buildings and belts.
That artwork is now under comparison, not a constraint on the next presentation. A plain connection
junction represents per-arrival convergence. It does not
hide a Merger Step. Join and Limiter may instead be small visible connection components because
they actually change behavior. Their exact geometry remains a design choice, not a new engine.

- **CR-012:** Creator MAY render Join and Limiter as compact connection components instead of
  large buildings. Their function, configuration, selection and replacement MUST remain accessible,
  including for third-party Steps. Appearance or wire count MUST NOT enable execution semantics.
  A non-functional junction MUST remain distinguishable from a functional Join or Limiter.

Use plain explanations such as "Each arrival continues", "Wait for related results" and the
configured limit scope. No knowledge of circuit notation is required. Show configured behavior
separately from observed waiting, overload or failure. Unknown observation is not an empty queue.
The [control contract](system-model.md#planned-merge-and-join) owns execution, while observation
owns any future state transport. No animation, sampled timing or building shape controls admission.

- **CR-014:** When Creator detects conflicting values combined by a Join, it MUST warn without
  blocking that otherwise valid Flow. Show the affected field and contributing inputs, with the
  winning value when observed. Distinguish a possible conflict from a concrete Example observation.
  repeated equal values alone are not a conflict. The runtime winner follows SYS-024, not a UI choice.

Acceptance for CR-012 is planned: select/configure/replace an installed third-party component
by pointer, keyboard and touch. Identify it without color alone. Disconnect Creator and verify
unchanged compiled behavior. Capture screenshots of the real resulting UI, not only target art.
For CR-014, run a Join Example with different values for the same field in both arrival orders:
show a warning and the actual winning value without rejecting save/build/run. Equal values alone
must not cause a conflict warning. No warning is not proof that all future interleavings are safe.
unrelated structural or configuration errors remain blocking under their existing contracts.

## Planned Step Resource Settings

Source: the user's 2026-09-21 resource ownership clarification.
[SYS-016/017 and LC-3](system-model.md#planned-step-lifecycle) own runtime/configuration semantics.
The HTTP Client now exposes an ordinary `connection` option with `private` (default) and `shared`.
It uses the same generic option editor as third-party Steps. Named profiles and resource-sharing
diagnostics remain planned; no separate resource declaration or mandatory panel is introduced.

- **CR-006:** Steps that offer selectable resource ownership MUST expose it through ordinary
  StepDefinition inputs, using the same editor for built-in and third-party Steps. Steps without
  such options MUST NOT acquire an empty resource panel. The Step chooses private or keyed shared
  registration from compiled configuration. No global UI switch may force incompatible resources
  together or infer sharing from visual Groups.
- **CR-007 (planned):** Any sharing explanation MUST distinguish observed allocation from configured
  intent and omit secret values. Creator cannot infer compatibility from arbitrary Java factories
  or keys. Runtime evidence and its UI contract must be specified before presenting actual owners.
  Session-sharing choices MUST be explicit; visual Group changes MUST NOT affect them.
- **CR-008 (deferred):** Named-profile selection MUST use the same metadata-driven controls and
  its canonical settings. Before saving profile edits, Creator MUST show the affected Step
  references and that application changes require a
  rebuild. A Step MUST NOT retain a competing editable copy of the profile configuration.

For HTTP, show a connection control and session behavior in plain language, not Java registry
details. [HC-011](http-client.md#behavior) owns disabled, private and explicitly shared cookies.
The exact layout remains open; profile CRUD/conflict decisions belong to the deferred slice.
Do not add a global Settings preference that silently changes functional project behavior.
A future batch assignment action
may target selected Steps, but visual group membership itself is never resource configuration.

Acceptance: configure built-in and third-party Steps with their ordinary private/shared options.
Sharing/isolation explanations need separate runtime evidence. The later profile slice additionally checks
shared-profile selection and affected Steps before edits. Verify no controls for resource-free
Steps, keyboard access, unresolved compatibility states, secret redaction and unchanged behavior
after moving/deleting visual Groups.

## Planned Group Copy And Sharing

Source: the user's 2026-09-19 decision, extended on 2026-09-27 to individual Steps, detachment
and optional combined buildings. The heading is retained for existing links. This is accepted
authoring direction. The implemented subset is recorded below, and runtime group expansion remains excluded.

- **CR-001 (amended):** Creator MUST let the user independently copy a Step or a group's functional
  contents into another position or Flow. Copies MUST have independent identities. Later edits
  to the source MUST NOT change them.
- **CR-002 (amended):** Creator MUST support explicitly linked occurrences of Step or group
  contents. Shared-content edits MUST apply to all linked uses while leaving independent copies
  unchanged. Field mappings are shared too, as explicitly selected by the user on 2026-09-27.
  they are not per-occurrence overrides. One Flow MAY contain multiple occurrences of the same
  shared contents. Different field mappings require detaching or copying independently.
- **CR-003:** Copying and sharing MUST resolve to ordinary materialized Steps and links before
  compilation. Neither compiler nor application may require group identity, a group interpreter
  or Creator availability to execute the result. Functional edits require a new application build;
  sharing MUST NOT mutate an already deployed artifact.

- **CR-009:** A user MUST be able to detach one linked occurrence and then change or replace it
  independently. Detachment alone MUST preserve its execution behavior and leave other uses
  unchanged, including existing connections, mappings and any explicitly selected resource or
  Limiter budget scope. Detaching MUST NOT silently reroute work or split/combine a runtime budget.
- **CR-010:** A shared-content edit MUST be an atomic authoring change across affected occurrences.
  Creator MUST identify its affected uses. Rejection, revision conflict or interrupted persistence
  MUST NOT leave a partially updated accepted graph. Schema, recovery and build handover details
  must be agreed before implementation. Existing per-document PATCH support is not that proof.
- **CR-011:** Creator MAY show linked occurrences as one building with separate entrance/exit
  lanes. Each occurrence MUST remain individually selectable with its own route and Example
  provenance. This display MUST NOT connect previously independent continuations or combine jobs.
- **CR-013:** At a multi-input Step, editing a connection MUST identify the affected route/use
  rather than silently choosing the first predecessor. Creator MUST distinguish a local connection
  change from a shared-content edit or deletion affecting other routes, and preserve unaffected uses.

Sharing concerns authored contents, not merely reusing a group's name, color or visual region.
Three different relationships must stay distinguishable: linked editing of separate occurrences,
several arrivals at one functional Step, and explicitly shared runtime resources/budgets. None
implies the others. Grouping several already independent Steps does not create linked editing.

The compiler-facing graph stays materialized and independently executable. Group contents are
resolved before compilation. No group interpreter or Creator graphics data enters the application.
When a lane identity affects routing, Join correlation or Limiter fairness, its required identity
must resolve to ordinary functional Step/connection data. A render-only lane, camera position or
optional group file cannot be the source of runtime behavior. No storage schema is chosen here.

### Decisions Before Reuse And Lane Editing

| ID / timing | Plain-language question | Decision and remaining work |
| --- | --- | --- |
| RE-1, advanced copy/link | Two linked normalizations both read `name`. Changing that mapping changes both uses. Where do the separate belts enter and leave? | The baseline places a single-entrance Step/group on one belt. Its chosen exit retains that belt's continuation, other exits end. External destinations remain local. Compound placement with several entrances and ambiguous replacement boundaries still needs a complete binding interaction. |
| RE-2, resolved baseline | One shared change affects several uses, then Undo is requested after another edit. | Optional identity correspondence, complete-action revisions, delta history and interrupted-commit recovery are specified under Optional Creator Metadata. No reusable content is executed from metadata. History is session-local and conflicting outside edits block overwrites. |
| RE-3, baseline and remaining route drafts | Remove one belt into a shared Step. Should its other arrivals survive? | A connection action edits one source port, not the destination Step. Deleting the Step explicitly bypasses all arrivals. A selected Example source controls observation only. Orphan-producing edits are rejected as a whole, and compound disconnected drafts remain open. |
| RE-4, before scope expansion | Should editing a shared item in project A also change project B? | Cross-project live links, distribution and version pinning are unselected. This direction adds no remote dependency. |

Detachment removes the selected editing relationship without renaming functional Steps or
rebuilding unchanged behavior. Other uses retain their identities, processing and external routes.
The storage and history boundary is recorded under RE-2 and Optional Creator Metadata.

### Acceptance And Evidence

| Requirement | Public Creator scenario | Evidence / gap |
| --- | --- | --- |
| CR-001, CR-002 | Copy a Step and group, link uses, edit processing and mappings, retain independent external routes. | Public complete-action tests cover independent copy, shared field edits, internal insertion/deletion and detachment. Wider branching/binding combinations remain under RE-1. |
| CR-003 | Compile the resulting flat project without Creator metadata and execute each route. | The compiler still receives only the materialized graph. Converging generated-application and production-JAR tests cover independent arrivals and one placement lifecycle. |
| CR-009 | Detach one use, run unchanged inputs, then modify/replace it. Its pre-detach results/connections and explicit budget scope are preserved. Other uses change only through their remaining shared relationship. | Planned. Save/reopen and independent jobs must preserve the same behavior. |
| CR-010 | Reject an invalid shared edit, race two revisions and interrupt persistence. Reopen one complete accepted revision. | Public editor tests cover stale/rejected actions, external-file conflicts and startup rollback of both documents. Power-loss durability is not claimed. |
| CR-011/013 | In a combined building, select both occurrences separately, inspect their Examples and edit one connection. Later select a real multi-input Step and distinguish arrival routes from linked authoring. | Planned desktop/narrow, keyboard and pointer journey with screenshots and unchanged unrelated routes. |

## Planned Direct Editing

Accepted on 2026-10-17 after the user's connection and grouping workshop. These are interaction
requirements. The implemented subset is described below. They do not approve unresolved execution APIs.
The user chooses the desired change. Creator handles deterministic placement and connection work.

- **CR-015:** Connecting MUST support both drag and click-source/click-target operation, with
  a visible provisional belt, generous target areas and distinguishable valid attachment points.
  Navigation MUST remain available during click-based connection. Reconnecting MUST retain the
  accepted connection until completion. Cancel or an invalid drop MUST preserve it. Crossing
  belts MUST NOT connect merely through proximity, and another wire MUST NOT enable parallelism.
- **CR-016:** Placing a new Step on a belt MUST preview insertion on that exact route and retain
  its previous continuation under the declared primary outcome. Placement on free space MUST
  reuse the Step/group picker. Group boundary connections follow RE-1. Only unambiguous bindings
  may be automatic. Show unresolved connections at their ports, including Choice/Switch outcomes.
  Rearrangement MUST preserve unrelated working areas and the user's camera location.
- **CR-017:** Group creation MUST operate on the selected Steps without first requiring an empty
  group and individual assignments. Selection between route boundaries MUST preview the actual
  included members and all external connections before acceptance. Membership MUST follow Step
  identities, not a surrounding rectangle. Inserting between two members of the same group MUST
  inherit that membership. At a boundary, the preview MUST identify the proposed membership.
  A pending group preview MUST close when the selection changes. Escape MUST cancel it and return
  keyboard focus to the Group action.
- **CR-018:** The existing picker MUST offer reuse of authored Steps/groups at the destination,
  including distant destinations. Reuse creates linked editing under CR-002. Copy creates
  independence under CR-001. Existing groups MUST NOT become linked through matching names,
  colors, content or proximity. Selecting one linked use MUST highlight other visible linked uses
  without making them additional destructive-action targets. Show the total use count and provide
  direct navigation to other uses. Shared edits MUST identify affected uses and unresolved
  connections together, preserving existing unambiguous boundary bindings.
- **CR-019:** Each accepted editor action MUST be undoable and redoable as one complete change,
  including its affected connections and membership. Cancellation creates no accepted change.
  Undo/Redo MUST respect revisions and MUST NOT overwrite intervening external edits. Shared edits
  also follow CR-010. This covers authored state, not reversal of already executed external I/O.
- **CR-020:** Automatic overview regions MUST remain derived presentation. User-authored group
  boundaries take priority. Distant occurrences and disconnected member regions MUST remain usable
  locally without moving unrelated Steps into a central building or drawing data belts for editing
  relationships. Group entry MUST provide focused detail and an explicit return to the prior view.
  Automatic overview regions MUST act as direct navigation points with distinct, derived location
  labels, not as editable groups. Selecting an authored group MUST still open its Inspector.
  Port and lane details MUST appear when needed for selection or editing, without loading the
  whole graph into the browser or requiring users to manage lanes as separate entities.

Use short action labels, symbols with accessible names, port labels and in-world previews.
Persistent explanatory paragraphs are not the primary guidance. Shared editing, selected use and
execution errors remain distinguishable. Scope changes must preserve the selected connection.
The compiler still receives only materialized functional Steps and links under CR-003.

Implemented controls:

- Select a Step to expose its outputs. Click an output and its destination, or drag between them.
  Escape cancels. More than four outputs use a chooser instead of overlapping handles.
- Click a belt to open insertion on that route. Separate Step and Group searches share one result
  area. Group search is absent when there are no groups. Step results distinguish new definitions
  from authored Steps. Choosing authored content offers linked use or independent copy. Back returns
  to the search; closing returns focus to the originating control. Multiple exits require choosing
  the continuing output.
- Shift-click adds or removes selected Steps. Data's Group action previews those members.
  With two selected boundaries, Include path previews the intervening route before creation.
- Related visible uses have a secondary highlight, not destructive multi-selection. Data
  provides the use count, navigation and detachment. Different Example sources have a selector.
- Footer Undo/Redo also use Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z. Text fields keep their native shortcuts.

Remaining limitations are explicit. Several independent group entrances cannot yet be placed as
one compound action. Disconnected intermediate graphs cannot be accepted. Ambiguous external
replacement boundaries are rejected rather than guessed. Overlapping shared selections are
rejected with Copy or Detach as explicit alternatives, rather than hiding transitive edit targets.
Combined multi-lane buildings, stable
unrelated layout under every structural edit, and the full branching/large-graph interaction
matrix remain planned. Existing group entry/return and camera behavior are preserved.

| Requirements | Planned public-boundary acceptance |
| --- | --- |
| CR-015/016 | Connect by clicks and drag, navigate to a distant target, insert on one route, reconnect and cancel. Verify crossings stay separate, occupied outputs do not acquire implicit fan-out, and unrelated connections/camera remain stable. Repeat by keyboard and on a narrow viewport. |
| CR-017/020 | Group an explicit selection and a previewed route range containing branches, including members separated by unrelated flows. Verify exact membership, preserved external links, insertion inheritance, boundary feedback, entry/return and unchanged generated results with metadata absent. |
| CR-018 | Reuse near the source and beyond unrelated flows, then copy and detach. Verify shared edits and field mappings, independent copies, local continuations, linked-use highlighting/count/navigation and isolated deletion. A new shared outcome exposes unresolved bindings together without inventing destinations. |
| CR-019 | Undo/redo insertion, reconnection, grouping, copy, shared edit and detach. Exercise save/reopen, rejected edits and stale revisions. Verify complete authored changes and preserved intervening work. History bounds, persistence scope and recovery require RE-2 before implementation. |
| CR-015 to CR-020 | Use a branching project with many uses and off-screen members, including Choice/Switch and installed third-party Steps. Capture actual UI screenshots, verify bounded visible work and stable navigation, and keep potential issues separate from observed Example failures. |

## Optional Creator Metadata

`railix.creator.json` is optional. Its canonical fields are:

```text
format  metadata contract version
groups  visual semantic-zoom groups
steps   per-Step name/color/icon and outcome-label presentation
created_at  optional UTC epoch milliseconds, retained creation metadata
theme   retained legacy metadata, not the current global theme preference
reuse   optional shared-edit relationships between materialized Step identities
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
identity and appearance, while Step Appearance assigns or unassigns one Step. Data creates a
group from the selected Steps, with a preview and an optional route range between two selections.
Group Manager can still create an empty group. Deleting a group removes only the definition and its Step
assignments; every functional Step and link remains untouched. Removing a visual group also removes
its optional grouping hint from linked uses, not their shared processing or member identities.
Group assignment uses a native modal dialog. Search receives initial focus, Tab stays inside,
and Cancel or Escape returns to the assignment button without editing.
Shared member deletions remain shared after removing the visual group. Shared insertions are made
through a use that still has a group, then propagated to its uses with or without a visual group.

Creator derives one region for each connected component of Steps assigned to the same group. One
group may therefore render as multiple disconnected regions without persisted scene occurrences,
parentage, geometry, or camera state. Matching group names or membership alone never links edits.
Explicit reuse separately records corresponding Step identities. New Steps inherit membership
when inserted between two members of the same group. Continuous pan and zoom change presentation detail by
scale rather than opening or collapsing a second graph. Large ungrouped branches may receive
deterministic temporary regions; those regions are not metadata.

The continuous-world contract replaces scaling a complete DOM graph: zooming into a region reveals
its recursively indexed children at stable coordinates in the same world. An aggregate is not a
collapse control. The Creator server owns a derived scene index; viewport requests return bounded
visible stations and rails. Native Canvas sprites render a fixed orthographic world,
with a bounded, screen-facing DOM overlay for labels, keyboard focus, and selection. The CSS camera
matrix also determines hit-testing and inverse-projected viewport requests. The previous graphics
renderer is not retained. Canvas2D is required for the default world. The CSS world renderer was removed.
Graphics failures remain visible until a usable renderer and a fresh scene are available. Settings
remain accessible during that failure so a working theme can be selected.
Long linear corridors appear as compact transport runs with a derived Step count and endpoint
names. They are navigation summaries, not authored groups or executable Steps. Choices, branch
labels, junctions, and terminals remain separate in the overview. Internal spatial partitions
remain available to bound visible work but do not replace branching topology with named buildings.
Membership edits and deletion retain a surviving selection as the navigation target.
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
camera, and scene caches are rebuilt, not persisted. Only user-authored presentation is stored in
the optional second JSON. Its `reuse` entries contain a relationship ID and uses, each with a stable
ID, an optional group ID and a role-to-materialized-Step mapping. Processing and internal wiring
remain in the functional project. They must agree across corresponding members. External
continuations and per-use appearance remain local. Removing metadata removes shared editing,
not the materialized processing or its execution behavior.
Compiler and built application remain unaware of both scene and metadata.
This describes the current layout; the accepted separate Example-file target is documented in
[Examples and validation](examples-and-validation.md), not implemented here.
`GET /api/editor?node=<id>` returns full configuration only for App, the selected Step, its
predecessor on the selected Example source, plus shallow connection targets and canonical node indexes.
The read index exposes all reaching Triggers. A Trigger is an observation choice, not a Step owner.
Group definitions use `q` and `offset` pagination (64 results plus an explicitly selected Group).
The browser retains this neighborhood and unsaved drafts, not every visited Step. The server owns
flow membership, global usage counts and Group occurrence counts. Deleting a flow expands its
Trigger ID into canonical stable-ID removals on the server; deleting a Group unassigns all members,
including unloaded Steps. The editor saves complete actions through revision-checked
`POST /api/editor`. Existing document PATCH APIs remain available for interchange. Nodes and groups are keyed by stable ID, links are
keyed by source port with all of that port's targets retained. Removing an entry uses JSON null.
An editor action contains `revision`, `creator_revision` and `command`, with optional `preview`.
Commands cover patches, connections, grouping, copy, reuse, detach, deletion, Undo and Redo.
Preview validates the proposed graph and metadata without building or persisting them.
Successful responses contain revisions, history availability, selection, workspace and
application facts, not the documents. The original compiler and metadata validators still own
acceptance. Individual HTTP requests retain their size budget; trusted local files and assembled
documents do not inherit that transport limit. Explicit whole-document import
uses POST; it is not a fallback for failed edits. Stale edits fail without overwriting accepted work.
Project actions open whole-document import in the existing Inspector, not runtime status.
Choosing a UTF-8 JSON file or pasting JSON prepares a name/count preview without replacing anything.
This preview does not claim compiler acceptance. Source and file selection survive navigation and
reopening until explicitly discarded or successfully replaced. File reads are cancelled when
superseded, and the server's source-byte budget also bounds browser reads and pasted uploads.
The preview identifies the current project file and that replacement cannot be undone.
Only the explicit Replace project action starts replacement. It uses the existing edit-action
exclusion, waits for earlier writes and prevents concurrent edits or a duplicate replacement.
Rejection retains the draft and the accepted application. A lost response or failed editor refresh
requires reading the authoritative editor before editing resumes, never blindly repeating POST.
Unconfirmed import source survives that read, which cannot confirm replacement itself.
A successful replacement response clears the source even if the editor refresh is still pending.
Successful replacement reloads editor/history and returns to the App at its normal scale.
Queued edits are compared with separately acknowledged functional and presentation snapshots, so
an older save cannot swallow a newer reversal or discard a rejected draft.
An unreadable edit response pauses further writes and retains the local draft. Reload project
reopens the authoritative editor instead of blindly repeating a mutation. The existing browser
unload warning lets the user keep that draft or explicitly discard it when reloading. This path
does not infer whether an unanswered request committed or merge concurrent edits automatically.
Reload remains available when a local JSON or Example draft is invalid.
Undo/Redo keeps bounded deltas for the current Creator session. History is not persisted.
Oldest deltas are evicted above the scene-response byte budget, retaining the latest action.
Legacy document writes invalidate history. Revision conflicts preserve accepted work, and
external file changes require reopening rather than silent overwrite. A temporary transaction
backup under `.railix/run/` keeps the original file contents, including invalid optional metadata,
and protects the graph/metadata pair. An interrupted commit is rolled back
on startup before building. A failed live action restores only files still containing its own writes.
Outside edits or deletions are preserved and reported as conflicts, with the completed journal removed
so a later startup cannot overwrite them. Recovery failure blocks further writes. This is process-crash recovery,
not a power-loss durability guarantee. Reads wait for the current write acknowledgement and reject an intervening
write before replacing the editing neighborhood. Invalid JSON drafts survive unrelated navigation
and saves. Whole-document GET remains an explicit interchange boundary, not browser startup.
While a different selection loads, the previous Inspector form and actions are inert and marked
busy. Closing the Inspector or selecting another station remains possible. Completion, failure or
cancellation restores interaction without allowing an older request to unlock a newer one.
Reloading or leaving asks for confirmation while an editor action or save is pending,
an edit remains unsaved, or a Step/Example has an unfinished JSON draft. Unstored sound/music
scores, active library writes and unsaved Settings receive the same protection. Cancelling keeps
the editor and its rendering resources alive. This browser warning is not crash recovery
and cannot guarantee draft retention if the browser or operating system terminates the page.
During pointer or keyboard interaction, live evidence can enable a path and append
new choices without replacing, removing or reordering existing choices. The choice list
scrolls independently so new choices do not move creation or action buttons. Short screens
and wrapped paths keep a reachable choice area inside a scrolling picker. Appending
waits during a press. Destructive updates wait until the interaction ends. Surrounding Inspector
evidence waits until the path dialog or group naming form closes. World traffic stays live and the draft path
does not change automatically.
The Step/Group insertion chooser keeps search fields stationary when its results change. Result
rows retain readable labels and scroll independently. Short landscape windows keep the primary
toolbar accessible while the chooser temporarily covers the lower navigation rows.

### Editing An Existing Flow

Omitted configuration is displayed from the Step catalog's defaults. Viewing does not save those
defaults or create history. Functional editing materializes the configured scopes it changes;
Undo restores the original explicit or omitted configuration.

Inserting a Step into one outgoing connection preserves its previous destination on the new
Step's primary outcome. Additional outcomes initially end the flow. Deleting a linear Step
reconnects its predecessor to its primary successor; deletion is blocked while a secondary
outcome still has a connected branch. Other branches and stable Step IDs remain unchanged.

These graph edits do not rewrite downstream field mappings. Changing or removing an upstream
producer can therefore leave a structurally valid flow with a missing or incompatible value.
The selected Example result reports that failure. The affected building uses the existing error
state, its Data tab names the Example and explains the error, and Configure displays the same
execution diagnostic. These are observed failures, not predictions about untested inputs.
Example explanations belong to the selected Example and matching build; switching Examples or
rebuilding must not reuse an older result. Runtime error counters remain separate evidence.
Repairing the field mapping or producer reruns the Examples;
old failures must not remain attached to the repaired build. "Running" describes the development
application, not a guarantee that every Example succeeded.

The runtime-status control describes the application process only. A separate red count opens a
compact list of current build issues and failed Examples. Each Example row names the Example,
failing Step and concise cause. Selecting a
failed Example opens its failing Step, keeps that Example selected and focuses its
affected setting. Configure and Data show error badges where their contents contain a current error.
Field diagnostics use the declared type and observed value beside the affected field, such as
`null (expected String)`. Detailed codes and paths remain collapsed by default.
The actual failing Step remains red while any current Example fails there, including when a
different Example is selected. A successful Example still follows its own reached path. Earlier
successful Steps and a successfully running App remain healthy.
Downstream Steps are not marked as reached by that Example. Aggregate error counters on the App,
Trigger or group do not by themselves identify a failure there. Data also links other failed Examples
that reach the selected Step or group, even when the
active Example succeeded. Example status comes from the revision-bound inventory, not individual
trace downloads. Queued and running work uses a yellow progress mark, success a green check, and
failure a red cross. Text fallback and accessible names identify status without color alone.
The list displays at most 20 issues per page. A bounded error header from the Example inventory
provides the cause and location independently of recorded-value downloads. Unavailable recorded
values do not erase the failure or prevent navigation. Their explicit retry does not start repeated
background reads. When no Step location was recorded, navigation returns to the Example's Trigger
without claiming that the Trigger failed. Closing the popover or navigating elsewhere cancels its pending
navigation. Rebuilding removes the previous build's failures until current results arrive.

Current proof covers insertion, output-type changes, producer deletion, downstream diagnostics,
repair and reload in one browser journey. Arbitrary reconnection, shared downstream Steps,
and a complete change-impact preview are not provided by this behavior. Undoing a correction
restores the prior flow and its current Example results, not a retained old diagnostic.

## Metadata Example

```json
{
  "format": 2,
  "groups": [
    {
      "id": "group-b5b8ad04-ec95-43c3-a32e-e33874b7a402",
      "name": "Normalize result",
      "color": "#147982",
      "boundary": "solid"
    }
  ],
  "steps": {
    "lowercase-text": {
      "group": "group-b5b8ad04-ec95-43c3-a32e-e33874b7a402"
    }
  }
}
```

## World State And Selection

Revealing a newly added Step uses the existing focus request directly, superseding an unfinished
older focus. It does not wait for an unrelated scene refresh or add a second camera-state mechanism.
The route index aggregates connections between visible representatives and skips internal edges.
If the connection budget would be exceeded, the frontier coarsens and is queried again: connections
are never truncated at a traversal count. Multiple hidden outcomes sharing one visible connection
are unlabeled until zoom reveals them. This changes presentation only, not routing or execution.
Transport uses fresh measured counter deltas and logarithmically bounded carrier density in Canvas
transform animations; no per-request object or execution path is added. A three-second freshness
window prevents stalled polling from implying continuing traffic. Hidden pages and disposal stop
animation. Operational motion follows the explicit Reduced motion setting in Creator, not the browser
preference. Locale and time-display preferences remain planned. Selected Example paths have separate blue replay motion, independent of
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
The active Example has one compact persistent selector in the workbench header. Its identity survives
loading a different Step neighborhood, selecting a group and closing the Inspector. Navigation to
an unrelated Step does not silently choose another Example. Clearing the selection removes replay
while ordinary traffic remains visible. The adjacent Edit Examples action opens the trigger's input
editor. There is no second selector at the trigger or Examples tab in the Inspector.
Locate Example trigger opens its entrance at the normal 50% scale, not at a fitted building-sized zoom.
At a selected trigger, its authored Examples stay selectable during a build or run. A missing
recorded result is shown as updating, not as a successful run or a queued execution. Progress updates
patch existing options without replacing the open selector.
The selector only offers recorded Examples that reach the selected visible Step or collapsed group.
Without a visible selection, an entered group or work area supplies the scope. The system overview
offers all Examples. An incompatible active Example stays visible as a disabled current item, so
inspection does not discard a failing scenario. Only reaching Examples can be newly selected.
No other Example is chosen automatically.
Scope filtering reads cached, revision-bound reachability summaries, never replays traces from render,
hover or paint. Loading, unavailable and empty results are distinct from the global list.
Inspector tabs use Configure, Data, Appearance and Grouping, with consistent symbols and text labels.
Data owns recorded context changes and the observed branch exit. It uses the central Example
selection, with changed JSON fields visible by default and the eye action revealing the full context.
There is no separate Results tab, before/after JSON renderer or local Example selector.
The desktop Inspector can be resized from its left edge with pointer or keyboard. Narrow layouts use
the full available width. Resizing is presentation state, not a project edit.
Appearance offers searchable embedded and user-supplied SVG/PNG symbols through the existing icon
catalog. A chosen symbol is embedded in Creator metadata, never processing configuration, and can be
reset or undone. Grouping starts with the selected Step's
assignment, with other reusable groups behind an All groups disclosure. Foundry uses consistently fitted,
functional module symbols. Connected belt ends have low-contrast, belt-width openings rather than
bright port dots. Moving plates carry direction marks. The selected Example has the strongest
continuous track treatment across straight runs, junctions and bridges. All three share one belt
material. End sinks and unreached markers stay quieter than the selected route.
Form simplification retains those tabs and all editing actions. A single value source needs no
numbered candidate heading or inactive reorder controls. Multiple sources and authored cases retain
their identities and ordering. Duplicate path headings may be visually suppressed only for one
source path with the same label. Distinct and multiple option inputs retain their labels.
Save responses and observation refreshes must preserve an unfinished field value, focus, caret
selection and text scroll position within the same Inspector selection and tab. Unsaved active
text also participates in the reload warning before its change event commits it.
Read, process and destination sections use one level of separation, not nested framed cards.
Path Reset belongs inside the field chooser as a secondary action. The chooser sizes to its content
within the available height, and its action buttons remain reachable without clipped labels.
New evidence must not move an action during a pointer or keyboard interaction. That chooser retains
its height until the next explicit navigation or close, with additional fields in its scrollable list.
An error uses the catalog's operation display name rather than its internal identifier. Its original
message, code and configuration path remain in collapsed technical details. Unchanged refreshes retain
an opened disclosure. A diagnostic targeting a declared input offers Show setting, which focuses
the exact configured field or its nearest rendered parent without changing values or creating history.
The same error is not repeated at the bottom of the form. No automatic repair is inferred.
Selected output actions occupy one aligned Connections row in the Inspector, shared across its tabs.
They do not float over the canvas or obscure other Steps. Connection preview still starts at the
actual belt exit, and a visible Cancel action supplements Escape for touch users. Pointer dragging
and keyboard connection selection use the same actions.
Inspector identity has one persistent type, name and static portrait above its tabs, not repeated
titles in each panel. The default chrome uses a 4/8-pixel spacing scale and related slate surfaces,
without a white-panel/dark-canvas contrast jump. Desktop toolbars occupy 48 pixels, with consistent
36-pixel form controls and 44-pixel narrow-screen targets.
Primary, secondary and quiet actions share consistent typography, corners and focus treatment.
Entered groups and work areas have a separate return control beside the entry station. The actual
entry Step remains functional and selectable. Touch supports single-tap entry/return, canvas pan and
pinch zoom. Narrow layouts provide 44 CSS-pixel control targets and a scrollable Inspector.
Opening or resizing the Inspector keeps the selected Step visible at the current zoom when canvas
space remains. Narrow split views reserve room for the Step, return portal and timing legend.
Short narrow windows use a full-screen Inspector instead of collapsing its form.
Closing it does not recenter the camera or discard the selection.
On narrow screens, choosing an outgoing connection temporarily clears the Inspector from the canvas
so destinations and Cancel remain reachable. The Cancel control restores the same Inspector and output
focus. Once a destination is submitted, destination-picking ends. The pending save cannot be cancelled
through the connection controls.
Undo/Redo use curved action arrows, not navigation arrows. Navigation and Example selection do not
create history. Automated mutation checks use isolated projects, never the user's active preview.
For a Step reachable from several Triggers, its Inspector source follows the selected Example.
There is no redundant source selector while an Example is active. With no Example, the source can
still be chosen for observation. Selecting an ordinary Step never activates an Example implicitly.
Changing that source while another Step is loading preserves the pending Step selection and reloads
its neighborhood for the new source. This changes observation, not graph connections or execution.
Step Data shows added, changed and removed context fields from the recorded before/after values.
Group Data compares the first entered member's input with the last reached member's output for
the selected Example in that particular visual occurrence. These endpoints follow the recorded
execution order, not spatial order or the first/last roof sample. The current graph executes one
selected path per Example. An unvisited occurrence has a neutral visual status, not an error.
Both Data views show a JSON context tree. Initially only added, changed and removed fields are
visible, with changed ancestor branches expanded. An eye button includes unchanged fields without
opening their branches. Removed values remain distinguishable from null, false, zero and empty
containers. Arrays retain their indices and exact numeric spelling is preserved. Values and field
names are escaped as text. Unchanged branches load on expansion. Large branches expose more fields
in bounded batches instead of creating the whole tree on selection. Long scalar values show a
preview and load their complete text only on expansion. Open branches and loaded batches survive
unchanged observation polling and Inspector tab switches. A different Step, occurrence or Example starts
with the changed-only view again. This presentation never merges independent Examples, executes
handlers or substitutes observed values for declared contracts.
Changing the selected Example cancels an obsolete application-status read. Metadata, inventory
and coverage reads share a thirty-second deadline, so one unresponsive read cannot indefinitely
block subsequent selection updates. A response for an obsolete selection is not applied.
Healthy reached Steps do not receive repeated success dots. The selected Example path stays marked
on its belts, while errors, warnings and understated uncovered markers remain visible.
Collapsed Foundry groups show a static, bounded sample of their actual member silhouettes beneath
the existing canopy. Samples do not run, animate, expose controls or substitute for the full group.
They use existing scene data and do not create groups, runtime objects or compiler metadata.
The Creator resolves application diagnostics to stable Step identities and enclosing regions at the
current scene revision. A missing RECEIVE names its configured field when that Step is inspected.
Dirty, rebuilding, unavailable and current observations remain distinct. Pending edits cannot show
old values as current evidence. Selected paths have a steady visual mark as well as replay motion,
and not reached by any Example is distinct from an execution failure or unknown observation.
An arrived-but-failed Step shows its diagnostic and failure mark, not a successful change summary.
Its Data view offers the recorded input through the existing eye control. A stopped group offers its
recorded entrance the same way. Input starts hidden, uses the bounded JSON tree with collapsed
containers, and is labeled as input rather than additions, removals or successful output. Missing or
stale input offers no tree. Changing Step, Example or application clears that input disclosure.
Data uses the same readable diagnostic and Show setting action as Configure, retaining original
diagnostic details on demand. Inspecting recorded input never executes a Step or performs a trace read.
Stopped group boundaries name the stopped Step instead of implying a successful output. Group
diagnostics use the same readable names and lead directly to their owning Step when known.
The Example HUD links directly to that failing Step. Arrival markers do not mean successful completion.
While an Example is selected, cumulative error counters do not paint unrelated buildings as failures
of that Example. Recorded error totals remain available in metrics. Switching selection resets the
Inspector's scroll position so its identity and observed changes are immediately visible.
An unavailable Example read retains its failed selection key. Status polling does not repeat the
trace scan. Retry, a new selection or a changed build may request the recorded values again.
A failure jump loads its destination before requesting recorded values. Summary and Step reads
stay within the application's existing concurrent-read budget.
A failed Step-value read does not discard an independently validated Example result or its reached
path. The read error remains visible and explicitly retryable, separate from an execution failure.
Example observation status appears once per inspected selection as an accessible visual mark.
Hover, keyboard, touch and assistive technology expose its meaning. Configure does not repeat loading, unavailable
or unreached messages under every field, and no Example selection shows no observation status.
Known missing fields and explicit null values remain visible. Actual failures remain actionable
at their owning field and through the failing-Step link. Configure omits a duplicate summary when
the same diagnostic is already shown at its field. Data retains its diagnostic details and uses compact change counts or section
progress alongside the visual mark, without a second paragraph for unchanged context. Existing
interaction sounds supplement feedback when enabled, never replace visible or accessible state.
Selected membership and union coverage are captured together at one application-owned suite revision;
reached Steps cannot precede the corresponding coverage bits. Read budgets and lifecycle are owned by
[observation](observation.md#scene-observation).
Region counters sum contained Step series without adding flow totals again. Disabled metrics,
zero executions and absent duration samples are different states. Measured outgoing belts use
sampled-time coloring, independently of Example selection. Each connection uses its source Step's
sampled mean. A collapsed group uses its slowest measured member mean, not its sum or elapsed
group latency. The group's world label uses that same measure without an `avg sum` suffix.
The sum remains available in Group metrics. Straight runs, corners and bridges retain the same time color. A visible
logarithmic scale compares the measured Steps and collapsed groups currently in view, excluding
stations behind HUD panels. Turquoise marks the shorter sampled mean and blue the longer one.
Panning, zooming or entering a group updates the numeric range after the camera gesture settles,
not continuously during a drag or flight. Equal measurements share one
color and one numeric label. No measurement creates an artificial spread. The legend is read-only
and does not show an interactive cursor. Missing
or disabled measurements are gray, never zero. Numeric Step timings remain available without
relying on color. These are process-accumulated sampled averages, not percentiles, CPU load or
the selected Example's duration. Latency alone does not prove a bottleneck. Group measurements
publish only after every member page is read, retaining the previous complete snapshot during
a refresh. Deployment/revision changes and failed reads invalidate that evidence.
Connection ingress is available only where the one-parent graph and enabled
destination metrics establish it; terminal throughput is not inferred. A successful selected Example
can replay through a terminal when its reached source has exactly one outgoing connection. Multiple
unknown terminal choices remain unknown; this presentation inference adds no runtime measurement.
Transport speed responds logarithmically to exact counter differences between two current-PID,
current-viewport snapshots, never counting a retained Example path as continuing traffic.
Portable icons are deduplicated in each visible scene, never repeated per node.

Format 2 is canonical. Valid format-1 occurrence metadata is accepted only at ingress and rewritten
to format 2. When legacy groups overlap, the deepest valid occurrence supplies a Step's group.
Invalid legacy source remains unchanged and the same functional graph opens flat. Format 2 has no
persisted scene occurrence, geometry, `instances`, `global` flag or compiler group representation.
The optional `reuse` member correspondence is authoring data only. It never becomes a runtime live link.

## Camera And Rendering

### Flow Workbench

Accepted on 2026-10-27 after the whole-product usability review. Replace the large isometric
factory working surface with compact, shallow-depth modules in a readable left-to-right flow.
Keep the existing scene, Canvas renderer, live observations, Example overlay and authoring APIs.
Do not retain a second, incomplete Map working mode. Theme extensions remain supported.

The Inspector occupies its own desktop column rather than covering the flow. Source, process
and destination sections derive from existing input descriptors, not privileged Step IDs.
Optional conditions and addition catalogs open on demand. Internal exception names and paths
belong behind Technical details. Small screens keep readable navigation and a focused editor.

Location and the active Example belong to the fixed workbench toolbar, not separate overlays
covering the canvas. The toolbar identifies the current project and location, uses readable
controls and wraps into explicit rows at narrow widths. Sound, music, Home, Overview and
Settings keep their distinct functions. Starting with a stale or missing access key explains
how to open the current link printed by the running Creator. A transport failure offers an
explicit retry. Neither case removes authentication or guesses credentials.

The workbench uses one material vocabulary for its fixed controls. Depth belongs to the world,
not competing raised panels. At overview scale modules show their names first. Timing and
coverage details appear on selection, in the Inspector and in existing tooltips. An outcome label
that only repeats its destination name is omitted visually without changing the declared output
or its editable port. The footer keeps graph counts, coverage, executions and camera/edit controls.
Process, CPU, memory, uptime and build details remain available through Running rather than
competing with the flow. These are presentation rules, not another saved view or runtime model.
Small module names move outside the miniature when the remaining face cannot hold readable text.
External names avoid visible transport segments. At a narrow viewport edge, area names use the
available space beside the miniature instead of disappearing solely because a full-width label
would leave the screen. Truncated names retain their full accessible name and tooltip.

Every explicit locate operation resolves its meaningful area/group ancestry, including a jump
from an Example failure or a group-library result. Index partitions are not navigation levels.
The displayed zoom is local to the current work area. Returning to a visited ancestor restores
its camera. An unvisited ancestor opens at a readable entry. Group search results identify their
uses by route rather than repeated names alone. Selection never silently changes the Example.

Acceptance: locate a failing Step and an off-screen group in a branch-heavy saved application,
inspect source/process/destination, edit and Undo, insert and connect through visible ports,
enter/leave authored groups and automatic areas, inspect linked uses, and repeat at narrow width.
Verified on 2026-10-27 through 21 focused browser cases and the saved 1,238-Step, 128-group
application at 1440x900 and 430x860. A real typed edit made all 28 Examples pass; Undo restored
the intentional failure. Distant-group location retained the selected Example. A real HTTP
request updated the live execution counter. This is focused workbench evidence, not full release
or cross-browser certification. The independent visual review's readable-label, narrow-location
and duplicate-value findings were incorporated.

The current Creator authors every declared route of fixed- or authored-outcome ordinary control
Steps. Layout follows deterministic declared-outcome depth-first order through an explicit
traversal stack, so nesting depth does not consume the JavaScript call stack. One ephemeral camera
provides continuous pan, cursor-anchored zoom, Home at the App at 50%, group focus, and scale-based detail. The canvas
fills the workspace. A Step click opens its Inspector and a group click enters its contents.
The location breadcrumb opens the current group's Inspector. Its header shows type, name and a
building portrait using the same assets at a fixed size, without a second scene, camera or ticker.
Configure, Data, Appearance and Grouping have separate tabs; Focus and Enter Group are header actions, Delete a footer
action. Removing the icon chooser leaves stored icons readable and removes the boot-time icon catalog
request. The footer abbreviates Example coverage to coverage and omits the normal observations
connection message, retaining unavailable-state diagnostics. The searchable construction
tray previews a connection on hover or keyboard focus without saving a node or running a handler.
Nodes contain no action buttons. Group
selection is separate from Group Manager; camera focus and wheel zoom reveal the same nested graph.
Collapsed stations use a stable layout-derived `station_scale`, calculated in the bounded Creator
scene response separately from a group's content bounds. Shapes fit this footprint without a
viewport-wide collision rescale or screen-size cap: culling a neighbor cannot resize an unchanged
machine, and increasing camera scale visibly increases both machines and belts between detail levels.
Top-level flows retain their natural compacted layout extent. The former 960-unit width and capped
flow height shrank the flow independently of App and Trigger, producing inconsistent building sizes.
Only nested group contents shrink within their parent footprint; spatial querying and recursive
aggregation still bound the response when the outer world grows. No coordinates are persisted.
The workbench foundation fits a 224-unit maximum dimension, with a default aspect ratio of 2.4,
before local station/camera scaling. The static building miniature occupies the left side and the
identity the right side. Sampled timings appear beside readable names at detail scales without
requiring selection. They are accumulated Step averages, not the duration of the selected Example.
Group labels show their slowest sampled Step average, matching the belt color. Their sum remains
in Group metrics, not as an end-to-end latency claim. Missing or disabled
measurements remain absent. An expanded group without a current per-Step timing summary reports
it as unavailable, not zero sampled Steps. At overview scales names move outside the modules and timings disappear.
Changing the contour does not change the miniature's size; explicit aspect changes stay inside that frame.
Parallel station rows reserve space for that square foundation and the raised silhouette, rather
than only the compact scene box. Same-column packing preserves that clearance. Long lateral
branches use the corridor between station columns instead of passing through neighboring buildings.
The scene reserves distinct lanes for independent connections. A clear lateral outlet takes one
corner rather than an unconditional detour. Shared terminal approaches require a common destination
and reuse an identical remaining path. Their visible T-junction does not wait or combine arrivals.
Clicking a shared band first offers its incoming routes. Hover or keyboard focus highlights the
chosen arrival. Only after that choice does the existing Step/Group search open. Insertion changes
that arrival only. Closing either chooser changes no connection. A single clear Step arrival skips this
extra choice. The choice is transient UI state, not a saved lane or runtime instruction.
The route chooser keeps its Close action and scrollable options reachable on short narrow screens,
without covering the header, footer or radar.
If several outcomes of the same Step share one represented connection, the scene includes the
actual outputs carried by that connection. Creator asks which of those outputs to edit. Outputs
leading elsewhere are not offered. A mixed outcome never silently falls back to the primary output.
A collapsed group or area is not silently discarded from this choice. Its explicit Open group
action reveals its contents before an actual Step output is edited. Opening requires this explicit
action even when only one group feeds that band. Editing is not inferred from an aggregate
connection. Duplicate names use visible enclosing areas/groups or
Step types, with a temporary route number only when those still coincide, not generated node IDs.
An offscreen origin is not silently omitted from a shared band. Its Locate action brings the
origin into view without editing. The Locate action itself never inserts a Step or Group.
The scene identifies App activation connections as power links. They remain non-data connections
and are excluded from insertion picking even when the App lies outside the viewport.
The scene reserves socket approaches as well as building footprints. Shared departures require
the same source and outcome. Geometric coincidence never establishes
connectivity. Unavoidable perpendicular crossings carry derived bridge geometry, separate from
junctions. The bridge's raised deck and ramps carry the same Example or live parcels as its route.
Routing reserves ramp clearance from corners and sockets. Adjacent crossings on one straight span
share a continuous deck with one ascent and descent, rather than overlapping independent ramps.
The raised span replaces that route's ground surface, including its tread, rim and shadow.
Bridge scaling includes height. Parcel contact centers and the rendered ramp surfaces coincide
through zoom and retained camera transforms, not only at the unscaled view.
The independent crossing belts remain continuous underneath. Their parcels are occluded by the
deck, not painted over it. Forward and reverse travel retain the same plate direction and phase
as the adjoining ground runs.
Picking inside the painted ramp or deck excludes its hidden underpass. Clicking the visible
underpass beside the deck still selects that route; bridge proximity alone does not occlude it.
This applies the visible-level separation of [Factorio's elevated rails](https://www.factorio.com/blog/post/fff-378)
and the clearance and bend-cost principles of [orthogonal routing](https://docs.yworks.com/yfiles/doc/developers-guide/orthogonal_edge_router.html),
without adopting a rendering or routing dependency.
Routing is deterministic for a complete detail level, not recomputed from only the visible neighbors.
Three derived route projections may be retained. Search work is bounded. An overcrowded projection
uses the existing coarser scene representation instead of silently sharing independent lanes, omitting
connections or failing the entire view. This does not add runtime Steps or persisted layout.
Large branch-heavy applications also derive automatic working areas. The current eligibility budget
is more than 512 represented Steps, with at least 16 Steps and an internal fork per candidate.
An area's entry has one incoming connection from a branching Step. Dominance is evaluated after
named-group contraction, so every path into its contents passes through that entry and named groups
remain intact. Only closed areas with no outgoing connection to an external Step are summarized
currently. Cross-area convergence therefore remains explicit instead of hiding a side entrance or exit.
Simple chains retain their existing run summaries. Maximal eligible areas use the entry's display
name and stable entry identity, not numbered spatial partitions. Selecting one enters its existing
groups and Steps at local 50%. Explicit Focus uses that same local scale. Leaving restores its parent
view. Areas, membership and geometry are derived only. They do not create saved Groups or change
project/compiler/runtime data. Their metrics, coverage and selected-Example path use the existing
region observation contracts. Broader domain-aware grouping and open-ended area boundaries remain
unsupported. Smaller glyphs and search alone are not a whole-system overview.
Overview labels retain area names and a compact evidence count, without optional timing text.
A restrained leader ties each displaced area label to its own building.
Foundry elevations derive from the shared body size, rather than independent fixed-height meshes.
Focus frames the actual machine body with surrounding space, not only its compact layout bounds.
Its one-shot scene response supplies the minimum scale required by every enclosing unentered region.
Considering only the focused Part allowed a leaf to collapse back into its group when the following
viewport request omitted `focus`. Entered regions still use the existing local 50% entry scale.
Static Canvas building and ground layers are cached and released on replacement or disposal.
Native controls own input. No graphics framework is required.
Neither surfaces nor camera state are persisted, and the generated application includes none of
these Creator resources. Connection sockets and belt direction derive from the
graph. Example selection is anchored beside its Trigger when an unobstructed position is available;
values and counters update independently in Data. Optional timing labels never take
priority over selectable station names.
Region expansion is driven by the readable size of its children, not the parent's longest edge.
Automatic hierarchy partitions have up to eight children, rather than repeated binary partitions.
Only the deepest expanded automatic boundaries are shown; named boundaries remain authored context.
An automatic run label derives from its first and last Step names. If the names match, one name
and the Step count suffice. A visible spatial fallback is identified as a flow area, not a group.
One click follows the run or enters the fallback area without selecting an authored group.
Counts of Steps and contained named-group occurrences come from the existing scene index, not runtime
instrumentation or new persisted metadata. Radar maps the current navigation scope using a
fixed cell budget and existing topology; it does not acquire global metrics. Keyboard commands are
scoped away from text editing, composition and OS modifiers. Search options use arrows and native
button activation. This applies the contextual controls and removal of duplicate entrypoints from
[Factorio's GUI review](https://www.factorio.com/blog/post/fff-363), and the attribute/comparison/value
structure of [Finder criteria](https://support.apple.com/en-gb/guide/mac-help/mh15155/mac), without
introducing another saved model or a Choice-specific execution path.
Choice comparisons place field, comparison and operand controls beside one another. Changing
the comparison preserves compatible operands; optional calculations and longer chains expand
on demand.
The common matcher is directly visible, without an additional condition disclosure. Empty comparison
chains say `Value exists`, matching their actual behavior. Additional AND conditions, OR alternatives
and comparisons open on demand, with bounded searchable lists. Optional JSON controls use one
checkbox label rather than a duplicate heading. Path access remains in the field's tooltip.
Configure and Data retain connection controls. Appearance and Grouping omit that unrelated row.
Focus keeps Inspector open, including on narrow screens, and frames the selection beside it.
The world and label surfaces use non-scrollable clipping; browser focus scrolling must not move
the diagram independently of the camera or displace its controls.
Diagnostics and malformed route states appear on their owning node, outcome, or group.
Configuration validation also appears directly at its JSON input with an accessible error
description. Integer/Long validators request whole-number editing; the compiler remains authoritative
and returns the same custom messages for built-in and installed Steps. An invalid draft remains
editable without replacing the saved project or running application. The shared contract and
execution boundary are defined by [SYS-019 to SYS-022](system-model.md#configuration-validation).
Containing-region IDs derive from existing scene parent links, not a second membership model.
Pending edits and diagnostics propagate to the containing occurrence, not every use of a group name.
The Inspector scrolls its fields independently of its fixed navigation, tabs and Close control.
Selection may be empty. Floor clicks cancel pending selection loads and remove
both Step and group highlights; pan gestures do not. Escape dismisses the topmost open control before
the Inspector and selection. Example choice remains independent, and the existing metrics poll reads
application counters when no individual Step is selected. Navigation retains the same application's
latest process snapshot until its next read; failures or a different application PID invalidate it.
HUD controls use a shared CSS material and one non-semantic symbol element with an embedded SVG mask.
The default artwork uses one 24-pixel-grid stroke family with its license embedded in the assets.
All default icons ship offline without a graphics library or icon font. World module icons share
that stroke language, while user theme artwork remains overridable.
Native buttons retain labels, focus, disabled and pressed behavior. State-specific styling wins over
the base material. This adds fixed HUD geometry, not per-Step objects, timers, or graphic dependencies.
Settings and Locate use symbols with accessible names/tooltips. A single Settings entrypoint opens
Appearance, Sound and Music tabs, not the selected Step. Sound/music HUD controls toggle playback.
One CSS cursor leaf follows mouse
input over the world only; it adds no animation loop and is released with the renderer. World
depth uses a lit edge and dark return, not stacked outlines. Replay changes transport color and
motion, not its directional geometry. Continuous route phase keeps parcels, treads and chevrons
aligned through elbows and ramps. Visible work is clipped to the camera vicinity. No CSS building
faces, belt strips, parcel elements or per-parcel keyframes remain.
Label collision indexing also clips its cell ranges to the viewport: an enlarged off-screen machine
must not allocate cells for its entire projected body. These are visible-work bounds, not Step limits.
Group reveals use a bounded 180 ms translation-only transition, without querying DOM animations
for every machine. Camera input remains available. Station size does not change during
these transitions. Machine meshes and conveyor materials retain fixed geometry and scale uniformly
in X, Y and Z with the camera; camera changes do not rebuild side faces or resize tread textures.
Camera-only frames update the drawing surface's camera and retain cached geometry. The cache
contains only the bounded scene and a 25% viewport margin on each side. New geometry, appearance
updates, detail changes, leaving the margin or crossing a scale octave reconcile it. Identical
scene responses do not. Labels, picking, terrain and insertion previews remain current.
The renderers retain the scene's interior corridors. They adjust only terminal socket contacts to
the shared station footprint and authored contour. They do not snap independent elbows onto a bus.
Projected floating-point equality must not decide topology.
Joined intervals include through-ports in their T/L masks and
retain selected rim colors. Belt overruns hide beneath casings and junctions; sockets do not paint
opaque strips over the transport. Shape changes adjust socket contacts, not the layout's branch axis.
None of this adds per-request objects, generated-application instrumentation or persisted geometry.
The visual vocabulary follows the consistent-control and in-context-action principles in
[Factorio's GUI design](https://www.factorio.com/blog/post/fff-238) and
[GUI tileset](https://www.factorio.com/blog/post/fff-243), without importing game assets.

## Radar And Scenery

The minimap reuses one coarse scene per revision, bins it into at most 32 by 20 CSS cells, and moves
the existing camera. Its read has a five-second deadline and bounded retry, independent of traffic.
Machine centers and routed belt segments have distinct cell geometry. Current viewport observations
and known diagnostics provide state overlays; unobserved areas remain neutral. Selection retains
one world position/identity and colors its cell, without a second selection box or navigation history.
Red is reserved for real errors; timing uses blue
logarithmic shading, not a utilization or bottleneck claim. No extra metrics poll or generated-app
change is required. It never materializes the full functional graph or persists a second layout.
The workbench omits decorative scenery so nonfunctional structures cannot be mistaken for Steps.
Existing creation dates and theme scenery assets remain valid metadata/assets, not runtime obstacles.
The [Factorio map-color work](https://www.factorio.com/blog/post/fff-332) supports distinct entity/transport
marks; no game assets or simulation mechanics are imported.

## Entered Groups

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
local zoom reference: entry frames that station at 50%, slightly left of center to show the
following route, not all contents at an absolute world scale.
Opening that region's Inspector during entry preserves the pending camera transition.
Zoom-out preserves the scope; leaving restores the parent camera and reference. Framing excludes
the HUD/Inspector, reuses the Trigger popover's control bounds and examines only the fixed UI
obstacles, never every Step. No scope is persisted.
Trigger hover reads only its editor neighborhood after a short dwell, aborts stale reads, and never
changes selection or executes an Example. A focused chooser retains ownership across native popup
hover loss; blur returns ownership to the normal hover lifecycle.

## Transport And Drawing Layers

Transport follows whole connection paths through elbows and is clipped only beyond viewport
overscan. Loop endpoints extend under stations, not into exposed belt spans. Shared route geometry
owns the belt, ramp, parcel elevation and picking. Independent crossings use short raised decks.
Underlying parcels are occluded by the same deck geometry. Buildings cover arrivals at their casings.
This does not model elevated belts crossing buildings. Disposal releases drawing surfaces, bitmaps,
listeners, scene references and animation callbacks, including when the disposed owner is retained.
Viewport queries include the ground beneath elevated roofs and the foundation overhang, scaled to
visible station units (the nearest known station when crossing empty ground), not the top-level unit.
Only the bounded scene is examined, without DOM layout reads. Roof overhang remains necessary
to include still-visible elevated buildings and is covered by the real-wheel regression.
Foundry Portals and Groups share the existing body-size
rule; Portal upright and field heights are proportional to that size instead of a separate oversized mesh.

## Canvas Drawing

Renderer descriptors and shared asset budgets live in [Creator assets](creator-assets.md).
One scene, camera, routing, observation and interaction owner remains in RailixWorld;
RailixCanvas only draws the bounded visible jobs. No PixiJS, custom executable renderer, runtime
endpoint or generated-application asset is added.

Canvas decodes PNG/WebP/SVG once per theme load, builds shared bitmaps and alpha picking masks, and caches
ground and building layers. One requestAnimationFrame loop paints treads, continuous route parcels,
switch frames and powered lights. It stops on hidden pages, empty views or disposal. Group borders use a constant
screen-space stroke, not the aggregate's station scale. The Inspector uses the loaded sprite without
another animation loop. Static caches and all bitmap references are released on theme replacement.
Junction tread phase is anchored to the world, not the screen. Panning translates the whole corner
without sliding its idle texture or changing its routed world coordinates. Verification covers pixel
translation of an idle corner and camera movement through the saved branch-heavy workload.
After a reveal finishes, single-frame buildings join that cache instead of repainting on every traffic frame.
Materials, shared foundations and HUD stay CSS-themed.
Theme atlases may still contain static scenery images. The workbench does not place them on its
functional canvas. It needs no scenery lattice, clearance cache or extra animation loop.

One click enters every named group, automatic area and run summary, including areas already
expanded by semantic zoom. Entry clears the previous selection and closes Inspector. A double-click
must not enter twice or select a newly revealed child. The current location button, the entered
region's label and the E shortcut open that region's Inspector without leaving it. Ordinary Step
clicks still select the Step and open Inspector. Desktop, keyboard and narrow-screen regressions
verify these routes and subsequent Inspector tab clicks.
An entered area's or group's location bar shows its ancestors. Selecting an ancestor restores
that view's saved camera directly, without changing the selected Example. The existing Leave
action still returns one level. These locations are navigation state, not saved groups or
compiler inputs.

The bounded minimap retains four direction bits per occupied cell so terminal marks
do not imply extra branches. Its topology is fixed to the current system or entered group, rather
than moving with the camera. The viewport rectangle moves over that map. The context name is
visible outside its drawing area, and entering or leaving a group reloads the matching topology.
The map shows state and reachability, not a second timing heat scale. Arrow keys move a bounded
fraction of the current viewport rather than a fraction of a potentially huge system. Foundry uses a compact
landscape map with the same aspect on desktop and narrow screens. Click and keyboard travel
remain available. Camera motion reuses its topology grid instead of rebuilding it. The cursor
is confined to the world, not form controls.

## Keyboard And Motion

World shortcuts are E (Inspector), F (Focus), Enter (enter group), arrows (pan), +/- (zoom),
and Home (App at 50%). Search results support Up/Down, Enter to choose and Escape to return
to search. Native controls, text editing, composition and OS modifiers retain their behavior.
Minus, zoom percentage and Plus are right-aligned in the footer; other status values scroll
separately on narrow screens. The round Home action sits beside Music in the HUD.

Settings > Reduced motion disables camera transitions and operational animations;
there is no separate animation pause button. This is a preference, not a performance fix.

## Acceptance Boundaries

Public Creator/browser checks cover metadata-only edits, recovery, revision conflicts,
selection, group entry/exit, local 50% zoom, route picking, viewport culling and Canvas
rendering. Test captures belong under the module target directory; repository screenshots
may depict older renderers. See [historical comparison](../verification.md#renderer-comparison)
for measured conditions and limitations; sustained thermal behavior remains unproven.
