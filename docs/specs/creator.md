# Creator Authoring And Factory World

## Human Review

Status: **Existing baseline with accepted group-reuse direction**, updated on 2026-09-19.
Copy/shared editing is planned; capability/resource presentation below is a design proposal.
Neither is implemented or newly verified by this workshop. Other sections retain their existing
baseline. The roadmap owns delivery status.

Owns: presentation metadata, editor interactions, scene/camera, transport and visual resource bounds.
Asset packaging, settings and audio belong to [Creator assets](creator-assets.md);
metrics and group timing belong to [observation](observation.md).

Decisions: [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

## Planned Capability And Resource Guidance

This specification owns user journeys, interaction states, feedback and accessibility, not just
theme geometry. Define those before each feature's implementation; prototypes illustrate the
contract but do not replace it. Backend facts belong to the owning capability specification.
An ADR is needed for a significant architectural choice, not for each icon or panel.

The user asked how to expose required Java modules, bottlenecks and limits. Proposed interactions,
not yet accepted UI layouts or new runtime metric/API contracts:

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

### Example Guidance And Circuit Metaphors

Examples support field mapping and connection through actual execution, not an assertion-only
test panel. [EX-007/EX-008](examples-and-validation.md#behavior) own this requirement. Show newly
created fields with their source Step and selected Example, and distinguish an unvisited Step
from a failed execution or failed expectation. Build omission/explicit retention follows the
[selection contract](system-model.md#planned-compilation-selection), not a visual hiding toggle.

The user suggested an electrical/physics analogy for logical controls. Proposed visual vocabulary:
ports and signals for connections, a junction for non-waiting convergence, and a barrier with
visible required-input indicators for a waiting join. A small gate symbol can explain a Boolean
predicate, but a late signal is not a false value. Detailed shapes and labels remain unapproved.
Keep familiar descriptions such as "Continue per arrival" and "Wait for required inputs" alongside
accessible explanations; do not make knowledge of circuit notation a prerequisite for authoring.

Before the Merge UI slice, define a user journey that configures its mode, maps branch outputs,
runs Examples and diagnoses waiting/failed/skipped input states. The [control contract](system-model.md#planned-merge-and-join)
owns those states. No animation speed, gate icon or physics analogy may invent runtime semantics.

## Planned Group Copy And Sharing

Source: the user confirmed on 2026-09-19 that both independent copying and linked sharing
belong in Creator and that the compiler does not know groups. This supersedes the earlier
product-level rejection of shared editing; it does not reintroduce runtime group expansion.

- **CR-001:** Creator MUST let the user copy a group's functional contents into another
  position in a Flow or into another Flow. The copied Steps MUST have independent identities;
  later functional edits to the source MUST NOT change that copy.
- **CR-002:** Creator MUST also support explicitly linked occurrences of shared group contents.
  A shared functional edit MUST update the linked occurrences while leaving independent copies
  unchanged. One Flow MAY contain multiple occurrences of the same shared contents.
- **CR-003:** Copying and sharing MUST resolve to ordinary materialized Steps and links before
  compilation. Neither compiler nor application may require group identity, a group interpreter
  or Creator availability to execute the result. Functional edits require a new application build;
  sharing MUST NOT mutate an already deployed artifact.

Sharing concerns authored contents, not merely reusing a group's name, color or visual region.
The compiler-facing graph remains canonical and independently executable. How Creator persists
and updates the authoring relationship is not yet decided; the current presentation file must
not silently become a prerequisite for executing a saved graph.

Open before implementation: entry/exit binding rules, storage/schema and migration, atomic update
and conflict behavior, per-occurrence configuration, detach/delete semantics, and whether sharing
extends beyond one project. No remote dependency or cross-project live-update behavior is implied.

### Acceptance And Evidence

| Requirement | Public Creator scenario | Evidence / gap |
| --- | --- | --- |
| CR-001, CR-002 | Copy to Flow A, share to Flow B twice, edit shared contents; A stays unchanged and both B occurrences update. | Planned; edit transaction and occurrence contracts are open. |
| CR-003 | Compile the resulting flat project without Creator metadata; execute each route and compare results. | Planned regression extending current flat-metadata recovery; no linked-edit implementation exists. |

## Optional Creator Metadata

`railix.creator.json` is optional. Its canonical fields are:

```text
format  metadata contract version
groups  visual semantic-zoom groups
steps   per-Step name/color/icon and outcome-label presentation
created_at  optional UTC epoch milliseconds, used as a scenery seed
theme   retained legacy metadata, not the current global theme preference
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
members, parentage, geometry, or camera state. In the current implementation, structural edits
never propagate between regions; the planned explicit sharing feature is separate. Currently,
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
camera, and scene caches are rebuilt, not persisted. Only user-authored presentation is stored in
the current optional second JSON. The storage of planned sharing relationships is still open.
Compiler and built application remain unaware of both scene and metadata.
This describes the current layout; the accepted separate Example-file target is documented in
[Examples and validation](examples-and-validation.md), not implemented here.
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
Transport uses fresh measured counter deltas and logarithmically bounded carrier density in CSS
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
Selected membership and union coverage are captured together at one application-owned suite revision;
reached Steps cannot precede the corresponding coverage bits. Read budgets and lifecycle are owned by
[observation](observation.md#scene-observation).
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

Format 2 is canonical. Valid format-1 occurrence metadata is accepted only at ingress and rewritten
to format 2. When legacy groups overlap, the deepest valid occurrence supplies a Step's group.
Invalid legacy source remains unchanged and the same functional graph opens flat. Currently there is no
persisted occurrence, logical slot, `members`, `instances`, `global` flag, live link, shared-edit
propagation, or compiler group representation. Planned authoring reuse changes the shared-edit
restriction, not the compiler's flat-graph boundary.

## Camera And Rendering

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
The foundation fits a 136-unit maximum dimension before local station/camera scaling. Changing its
contour does not change the building body's size; explicit aspect changes stay inside that frame.
Foundry elevations derive from the shared body size, rather than independent fixed-height meshes.
Focus frames the actual machine body with surrounding space, not only its compact layout bounds.
Its one-shot scene response supplies the minimum scale required by every enclosing unentered region.
Considering only the focused Part allowed a leaf to collapse back into its group when the following
viewport request omitted `focus`. Entered regions still use the existing local 50% entry scale.
CSS top and side surfaces are reused by visible identity and released when culled.
Native controls own input; CSS needs no rendering ticker or graphics framework.
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
Choice comparisons place field, comparison and operand controls beside one another. Changing
the comparison preserves compatible operands; optional calculations and longer chains expand
on demand.
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
insertion preview stay current without moving every factory material. CSS has no idle render loop,
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

## Radar And Scenery

The minimap reuses one coarse scene per revision, bins it into at most 32 by 20 CSS cells, and moves
the existing camera. Its read has a five-second deadline and bounded retry, independent of traffic.
Machine centers and routed belt segments have distinct cell geometry. Current viewport observations
and known diagnostics provide state overlays; unobserved areas remain neutral. Selection retains
one world position/identity and colors its cell, without a second selection box or navigation history.
Red is reserved for real errors; timing uses blue
logarithmic shading, not a utilization or bottleneck claim. No extra metrics poll or generated-app
change is required. It never materializes the full functional graph or persists a second layout.
Scenery uses seeded multi-cell districts (ridges, groves, basins) and the machine projection;
new projects record `created_at` in Creator metadata; unknown existing dates are not invented.
The seed uses that date or the project ID. Foundry interprets districts as service buildings,
power structures and cooling basins; Classic uses ridges, spire groves and basins.
Distance thins a fixed lattice without reseeding positions. The existing coarse overview supplies
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
local zoom reference: entry frames that station at 50%, not all contents at an absolute world scale.
Zoom-out preserves the scope; leaving restores the parent camera and reference. Framing excludes
the HUD/Inspector, reuses the Trigger popover's control bounds and examines only the fixed UI
obstacles, never every Step. No scope is persisted.
Trigger hover reads only its editor neighborhood after a short dwell, aborts stale reads, and never
changes selection or executes an Example. A focused chooser retains ownership across native popup
hover loss; blur returns ownership to the normal hover lifecycle. Cargo uses `overflow: clip` on the belt run: raised faces
remain 3D, but animated overlength cannot spill outside the transport surface.

## CSS Transport And Drawing Layers

Foundry transport follows whole connection paths through elbows, clipped only beyond the viewport
overscan. Per-run loops visibly teleported parcels at intermediate junctions and were removed.
The three visible cube faces are projected once from the fixed camera into one CSS gradient surface;
generated CSS transform keyframes retain the path and speed without per-frame JS work. Parcel count
depends on visible path length (64 material units per parcel), not request rate. Loop endpoints extend
under stations, rather than ending in the exposed belt span. CSS owns motion and pauses through the
existing observation/replay/visibility controls. No runtime metric or endpoint is introduced.
The renderer separates ground transport, screen-space cargo and raised stations into independent
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
Only the bounded scene is examined, without DOM layout reads. Roof overhang remains necessary
to include still-visible elevated buildings and is covered by the real-wheel regression.
Foundry Portals and Groups share the existing body-size
rule; Portal upright and field heights are proportional to that size instead of a separate oversized mesh.

## Canvas Drawing

Renderer descriptors and shared asset budgets live in [Creator assets](creator-assets.md).
One scene, camera, routing, observation and interaction owner remains in RailixWorld;
RailixCanvas only draws the bounded visible jobs. No PixiJS, custom executable renderer, runtime
endpoint or generated-application asset is added.

Canvas decodes PNG/SVG once per theme load, builds shared bitmaps and alpha picking masks, and caches
ground and building layers. One requestAnimationFrame loop paints treads, continuous route parcels,
switch frames and powered lights. It stops on hidden pages, empty views or disposal. Group borders use a constant
screen-space stroke, not the aggregate's station scale. The Inspector uses the loaded sprite without
another animation loop. Static caches and all bitmap references are released on theme replacement.
Materials, shared foundations and HUD stay CSS-themed.
Foundry scenery also uses the atlas: three static plot images replace repeated CSS faces in Canvas
variants and are painted into the existing ground cache before transport. World-seeded placement,
clearance and viewport thinning remain owned by RailixWorld; no scene model, extra drawing surface,
animation loop or persisted geometry is introduced. Missing scenery sprites keep the CSS fallback.
Images are shared per motif, not allocated per environment building.

Group entry closes Inspector consistently for wheel, double-click and explicit entry. A responsive
Inspector may appear under the pointer after the first click; the browser's second-click count and
original selection point preserve that one gesture without delaying ordinary selection. Desktop and
narrow-screen regressions also verify that subsequent Inspector tab clicks retain their own behavior.

The bounded minimap retains four direction bits per occupied cell so terminal marks
do not imply extra branches. Foundry uses a round radar; `--map-aspect` and `--map-range`
control its projection and context. The cursor is confined to the world, not form controls.

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
selection, group entry/exit, local 50% zoom, route picking, viewport culling and both
renderers. Test captures belong under the module target directory; repository screenshots
may depict older renderers. See [historical comparison](../verification.md#renderer-comparison)
for measured conditions and limitations; sustained thermal behavior remains unproven.
