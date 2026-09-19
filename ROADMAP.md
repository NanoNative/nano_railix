# Railix II Roadmap

Updated: 2026-09-14

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
- Current UI preparation: **Complete scene connections and scoped application observations;
  3/3 gates verified (100%; 0% left), locally accepted 2026-09-08. No renderer replacement is included.**
  Streamed project/source
  processing remains a separate million-Step compilation requirement. Feature work remains
  **5.6 Merge/fan-in, then Split and bounded Loop**.
- Core/base certification checkpoint: **Complete, 6/6 gates (100%; 0% left)**
- Feature roadmap: **resumed after the 2026-08-13 core/base clean gate passed**
- Retained incomplete item: **3. Primitive Contract And Standard Library, 4/6 complete
  (67%); 33% left**
- Retained incomplete item: **6. Live Runtime, Operations, Bounded Work, And Permissions,
  1/6 complete (17%); 83% left**
- Previous renderer verification: **PixiJS factory renderer (2026-09-09), scoped gates verified.**
- Current UI work: **CSS Factory World, `feature/css-factory-world`; visual acceptance pending.**
  Accepted checkpoint counts are unchanged. Publication verification completed on **2026-09-14**;
  the user authorized commit and push of this UI branch.
  **Publication checkpoint:** 2,512 Java tests passed; the desktop browser suite was verified in
  batches with corrected-case reruns (608 cases, including two expected opt-in skips). The final
  mobile gate passed all 28 cases at 320 pixels; package and runtime-soak verification passed all
  43 cases. The million-call runtime fixture completed with a 64 MiB heap. This is not million-Step
  or million-request-per-second certification. Native packaging passed. Review fixed cross-Creator
  sound-edit conflicts, stale sound-editor revisions, mismatched App/JVM/System comparison rows,
  and missing branch labels; desktop-only assumptions in responsive tests were corrected.
  No dependencies or generated-application changes were added. The native complex-example review
  confirmed the metrics comparison, Home/footer/HUD, local 50% group entry, zoom-out retaining the
  group and visible moving parcels. It reported no browser errors; project, Creator metadata and
  application JAR hashes were unchanged. Subjective music quality and sustained thermal behavior
  remain unverified. The initial `clean verify` was interrupted in the browser phase; later batches
  and corrections establish this checkpoint, not an uninterrupted full-CI or aggregate coverage result.
  Evidence: `/private/tmp/railix-ui-publication-final-verify.log`,
  `/private/tmp/railix-ui-publication-remaining-verify.log`, `/private/tmp/railix-publication-corrections.log`,
  `/private/tmp/railix-publication-step-labels.log`, `/private/tmp/railix-publication-final-package.log`,
  `/private/tmp/railix-publication-package-mobile.log` (package/soak gate), and
  `/private/tmp/railix-publication-responsive-verified.log` (final mobile gate).
  **2026-09-12 theme variants:** embedded Foundry Standard/High detail and Classic, optional local
  descriptors and relative CSS/PNG/SVG assets, non-overwriting installation, shared building frame,
  and the Running HUD row are implemented. The generated application is unchanged. The initial
  shell-only PNG experiment did not meet smooth-frame targets; the production Canvas delivery below
  supersedes it. See ADR 0020 for measurements and limits.
  **Verification completed 2026-09-13:** 65 scene cases, 18 theme HTTP cases, 12 targeted World cases,
  three Settings browser cases at both 1280 and 390 pixels, and the 6,003-node/60-navigation regression
  pass. Theme switching preserves the running application's PID and project; native packaging passes.
  A leaf-focus/overscan defect found during review is fixed, as is literal `+` handling in asset paths.
  Evidence: `/private/tmp/railix-theme-final-verification.log`, `/private/tmp/railix-theme-mobile-final.log`,
  `/private/tmp/railix-focus-viewport.log`; the earlier broad renderer run is not a new full-CI certificate.
  The native complex-example preview was restarted; project, Creator metadata and application JAR hashes
  were unchanged. Its then-pending native visual check is superseded by the modular-renderer review below.
  **2026-09-13 Overview:** type/name and shared-renderer building portrait, removal of the icon chooser
  while retaining stored icons, and concise coverage/connection status pass 11 targeted desktop cases
  and three cases at 390 pixels. Native packaging passes; the preview's three content hashes remain
  unchanged. Evidence: `/private/tmp/railix-overview-final.log`, `/private/tmp/railix-overview-mobile.log`.
  GPU utilization and automatic Example-input proposals are not implemented.
  **2026-09-13 modular renderer: Implemented; visual acceptance pending.** Foundry exposes
  Renderer Canvas (default) and Renderer CSS through the same Settings selector, each at high quality.
  The earlier Standard variants have been removed; existing `canvas` and `hq` selections retain their IDs.
  The two-choice selection, high-resolution Canvas default and saved CSS preference pass desktop/narrow
  regressions. Nineteen scoped settings, extension, group-navigation and motion cases plus a packaged
  extension check pass in `/private/tmp/railix-renderer-names-package.log` and
  `/private/tmp/railix-renderer-names-smoke.log`. The running complex preview shows both exact labels;
  project, metadata and generated application hashes remain unchanged. Full CI was not rerun.
  Embedded `assets/themes/<theme>/`, `assets/sounds/` and `assets/music/<group>/` mirror Railix-home
  extension paths; per-file overrides retain embedded fallbacks. Separate MML roots are canonical;
  old mixed music folders and JSON scores remain read-only fallback sources. There is no project-local
  asset-root precedence, new graphics dependency or generated-application change.
  The production-renderer comparison uses 128 visible processors with active transport, 1920x1080,
  DPR 2 and Chrome 152/ANGLE Metal on M1 Max. Canvas p95 frame intervals are 16.7-16.8 ms versus
  100 ms for CSS, with substantially lower renderer/graphics-process CPU. High resolution uses four
  times the drawing-surface memory of the former Standard tier for Retina sharpness. This does not
  certify sustained thermals, other browsers, million-Step projects or application throughput.
  **Verification:** 229 HTTP/workspace cases passed; Settings switching and PNG/SVG load/fallback
  browser cases passed, including two variant cases at 390 pixels. Three freshly packaged-Creator
  cases verify extensions without rebuilding/system Java and owned-process cleanup. The broad run's
  remaining audio-test tab-selection failure was corrected and passed its targeted rerun. Group
  outlines and empty-view animation-loop regressions also passed after their fixes. Zoom, motion,
  hidden-page suspension and visible raised parcels have browser checks; full CI was not rerun.
  Evidence: `/private/tmp/railix-modular-themes-package.log`, `/private/tmp/railix-native-package-final.log`,
  `/private/tmp/railix-native-mobile-final.log`, `/private/tmp/railix-modular-native-package-smoke.log`;
  measurements: `/private/tmp/railix-renderer-retina.jsonl`. ADR 0020 records reproduction and limits.
  Final gesture review fixed the Inspector remaining open on group entry and intercepting the second
  click on narrow screens. Four desktop/narrow gesture cases, including normal Inspector interaction,
  pass in `/private/tmp/railix-final-gesture-package.log`; packaging succeeds. Eight other final
  variant/zoom/motion cases passed in `/private/tmp/railix-modular-final-package.log`. Its four gesture
  failures were a test selector for an unavailable Appearance tab on automatic sections, corrected
  to the existing Groups tab before the passing rerun.
  Two final packaged extension/cleanup checks pass in `/private/tmp/railix-modular-packaged-final.log`.
  The earlier native complex-example check used Canvas Standard: 128-Step double-click entry at 50%, Home,
  wheel zoom and visible replay parcels were inspected. No page errors or leftover test processes
  were found. Functional project, Creator metadata and application JAR hashes remain unchanged.
  **2026-09-13 scenery atlas: Implemented.** Renderer Canvas reuses three embedded environment images
  through the existing atlas, resource overrides and static ground cache; CSS variants and atlases
  without scenery entries keep their CSS environment. Coverage is plain percentage text without a bar.
  Scoped verification covers 21 functional cases across targeted runs, Retina pixel/zoom/reload checks,
  editable-theme installation and packaged extension loading. Two stale test assumptions were corrected:
  selecting empty corners of isometric bounding boxes, and clicking through an open Inspector.
  Evidence: `/private/tmp/railix-scenery-final-package.log` (17 initial passes),
  `/private/tmp/railix-scenery-reviewed-package.log` (four corrected cases and packaging),
  `/private/tmp/railix-scenery-retina-verified.log` (two Retina cases). The 128-processor comparison,
  now with six image-backed environment plots, retains 16.7-16.8 ms Canvas p95 versus 83-100 ms CSS;
  raw evidence is `/private/tmp/railix-scenery-retina.jsonl`. The packaged extension check passes in
  `/private/tmp/railix-scenery-retina-package-smoke.log`; its two stricter scenery-test fixture failures
  are superseded by the Retina rerun above. Browser screenshots were inspected; direct inspection of
  the refreshed complex preview was blocked by the locked Mac. Its three content hashes are unchanged.
  No full-CI, sustained-thermal or million-Step certification is claimed.
  **Resumed on 2026-09-11 with permission to continue beyond the 10% usage remainder.**
  That checkpoint was uncommitted. Inspector navigation/footer/Groups tab, radar selection and rhythmic
  editable music have scoped tests. The remaining dense-navigation work is **Partial**.
  **Latest user review:** straight chains replace serpentine packing. Group entry frames the first
  Step at local 50%; leaving restores the enclosing camera/reference. A tall branching-group
  regression caught entry on the topmost branch instead of the origin and now passes.
  Focus keeps the Trigger Example chooser open across native-popup hover loss.
  Creator-only MML tone controls add optional decay, sustain, cutoff, detuning, saturation,
  stereo placement and finite echoes. After approval of Circuit Drive's sound, eight roughly
  3.5-minute instrumental arrangements replace the short previews, with developer-humour titles
  and no speech samples. MML playback now consumes compact repeats through a rolling audio-clock
  window instead of expanding/capping the total notes. Full-track musical acceptance remains open.
  The approved building direction is now **Railix Foundry**,
  the embedded default; **Railix Classic** remains selectable. Both can be installed as editable
  CSS copies. One renderer supplies HQ, portal, routing switch, processor and bounded group-canopy
  solids. The shared HUD drops duplicate bevels and uses underlined Inspector tabs.
  Scoped Foundry verification covers 65 Creator cases across world, editor and theme-protocol
  checks. The broad run caught a narrow HUD overflow and an obsolete tab-style expectation;
  their fixes passed the subsequent 15-case packaging run (1m54s). Six final interaction/rendering
  checks passed in 38s. Logs: `/private/tmp/railix-foundry-package.log`,
  `/private/tmp/railix-foundry-final.log`, `/private/tmp/railix-foundry-boundaries.log`.
  The 128-Step navigation probe renders nine visible machines/174 plane descendants, with
  18.1 ms frame p95 and 0.1 ms command p95; this does not close the dense 128-visible-machine issue.
  Native review with the existing 143-node project confirmed both themes, group entry at 50%
  and restoration of the enclosing view. Project/metadata/generated-JAR hashes are unchanged.
  Full clean verification was not rerun; visual acceptance remains with the user.
  **Foundry transport/radar correction:** the user review rejected the gap between the target image
  and the retained conveyors, cargo and radar. Foundry now has finite-path three-faced cargo, conveyor
  return walls, smaller direction marks and a round radar with directional endpoints. A browser visual
  probe exposed flattening caused by the old clipping ancestor; finite cargo paths replace that clip
  without allowing packages outside the run. Distant transport remains a tiled material. The 25-case
  scoped package run passed in 2m41s (`/private/tmp/railix-foundry-transport-package.log`). This is not
  acceptance of target-image fidelity or completion of the dense 128-visible-machine performance work.
  Native review also exposed ignored nested keyframes: a running animation name alone did not prove
  parcel movement. The new position-change regression failed, then passed with top-level keyframes.
  Final package: 10 cases passed in 1m28s (`/private/tmp/railix-foundry-motion-final.log`). The active
  renderer probe at rates 1, 100 and 1,000,000 retained 22 cubes/192 plane elements and 16.7ms frame p95;
  these are renderer inputs, not application-throughput or dense-world certification.
  Native verification of the 143-node project confirms changing cube positions on the selected
  `express-batch` path and the round radar. Functional project, presentation metadata and generated-JAR
  hashes remain unchanged. Rounded routes and full target-image detailing are not implemented here.
  **Subsequent user review supersedes the performance impression above:** packets looped at every
  run and the laptop's renderer/GPU-process CPU remained excessive. Whole-connection paths replace
  those loops; a camera-projected CSS surface replaces three moving faces per packet. Portal bodies
  now fill their foundations and the default Choice has a T footprint and raised moving tongue.
  Old Trigger/Choice shape overrides were removed only from the review example with user permission.
  Large animated-view CPU is still unresolved. Do not use frame p95 alone as an efficiency gate;
  the subsequent layer-separation implementation is described below.
  Local correction verification: 13 Creator cases and native packaging passed in 2m28s
  (`/private/tmp/railix-paths-buildings-review.log`); the functional project and generated application
  JAR hashes are unchanged. Temporary profiling code was removed. Reduced motion is used only as
  a review-preview mitigation pending the renderer decision, not as a performance fix.
  **Renderer correction (2026-09-12):** after the allowance pause, Portals and Groups now
  share the existing body-size rule. Portal heights are proportional, not independent 84-unit uprights.
  Ground transport, projected parcels and raised buildings use three compositing contexts without
  changing the project, authored shape overrides or generated binary. Four containers are added per
  renderer, not per Step; no graphics dependency or per-frame JavaScript loop is introduced.
  The comparable 120-frame Chromium trace reduced accumulated `DirectRenderer::DrawRenderPass`
  duration from 270,094 to 146,121 microseconds (`/private/tmp/railix-renderer-trace.log` and
  `/private/tmp/railix-renderer-layers.log`). These nested event durations are not whole-process CPU
  percentages. Per-building isolation and mask-free parcel triangulation were measured and removed;
  temporary profiling code is not retained. The 22-case camera/transport/group regression run passes
  (`/private/tmp/railix-renderer-regressions.log`); the 128-Step navigation sample has nine visible
  machines and 18 ms frame p95. This does not certify 128 simultaneously detailed animated machines.
  Five focused World cases and one theme-switch case also pass (`/private/tmp/railix-renderer-alignment.log`),
  including a geometric check of parcel feet against actual conveyor projections after zoom/pan.
  Initial scoped package verification passed 27 Creator cases plus the 8 core / 72 standard-library
  baseline cases in 4m28s (`/private/tmp/railix-renderer-final-package.log`). Review also fixed retained
  detached scene containers: the weak-reference GC test fails with the old references and passes
  after disposal releases them and the browser processes detachment. An existing selection test now
  waits for the asynchronously loaded Inspector, not just an already-selected ID. No product delay
  was added. Full clean verification was not repeated.
  Native review subsequently resumed with motion enabled. It exposed a second size cause: the
  fixed 960-unit flow width and capped height compressed ordinary stations while leaving App and
  Trigger full-sized. The natural compacted extent replaces that compression, with no endpoint or
  persistence changes. Its API regression fails at roughly 0.63 station scale before the fix and
  passes at 1.0 after it; all 64 scene cases pass. Two full-branch tests now query the full scene
  rather than assuming every branch fits their former 1000-unit viewport.
  A strong wheel zoom also exposed ground-only culling of still-visible elevated roofs. Viewport
  queries now include the elevation and foundation overhang in the current group's local unit.
  Four focused wheel/camera/parcel/128-Step cases pass (`/private/tmp/railix-roof-viewport.log`).
  The narrow 390px parcel geometry check excludes offscreen overruns beneath culled machines;
  every zoom must still test exposed onscreen parcels within the unchanged 1px tolerance.
  Final native packaging passes 91 Creator cases (64 scene, 26 renderer, one theme editor) plus the
  8 core / 72 standard-library baseline cases in 4m43s
  (`/private/tmp/railix-renderer-complete-package.log`). Four additional 390px browser cases pass
  (`/private/tmp/railix-renderer-mobile-complete.log`); wheel entry uses successive real gestures
  because a narrow viewport starts farther from the detail boundary. The same wheel test also passes
  again on desktop (`/private/tmp/railix-renderer-desktop-wheel-final.log`). Native review confirms entry
  at the first of 128 Steps with local 50% zoom. The temporary viewport override was removed.
  A nine-second native sample at 1280x900, 70% zoom, with Example replay and 27 parcels active records
  35.5-42.1% CPU for the shared graphics process, 15.0-30.0% for the page renderer, 1.5-2.1% for Creator
  and 0.3-0.7% for its generated application (`/private/tmp/railix-native-active-cpu.log`). This is not
  GPU utilization or a matched comparison to the earlier 128% / 40% large-view readings. Sustained
  thermal behavior and dense-scene capacity remain unproven. The inspection worker itself developed
  high CPU/memory use and was reset before measurements and after UI checks. Reduced motion stays off;
  the project, visual metadata and generated application JAR are unchanged.
  At that checkpoint, branch `feature/css-factory-world` remained unpublished. The September 14
  publication request subsequently authorized commit and push after verification.
  Uniform three-axis scaling now keeps casing depth and core elevation proportional to zoom;
  the regression failed before the correction. Belt materials retain fixed dimensions, and the
  fixed CSS camera no longer allocates hidden casing faces. No visible Steps were removed.
  A separate Choice defect was reproduced in the native complex example: a lateral L outlet was
  incorrectly normalized as a central bus, with projected-coordinate rounding toggling the mistake.
  Bus classification now uses the source route's inlet/outlet orientation. The Standard branch
  was checked at the same camera position before and after the fix, including animated example
  packages and the selected T-junction. The strengthened multi-outlet regression checks the
  destination span rather than just its corner; it fails on the old classifier at outlet `two`.
  Dense navigation p95 was 312 ms at the initial baseline, 175 ms at the previous pause, 142 ms
  after depth/material-state correction and approximately 83 ms with normalized belt materials.
  Hidden-face removal reduced plane descendants from 3122 to 2354; all 128 Steps remain visible.
  These are headless measurements on a shared host, not a smooth-rendering or hardware FPS guarantee.
  **Camera batching implemented with user approval:** one preserve-3D wrapper moves the bounded
  visible factory. A camera-only regression went from 56 material writes to zero. Identical scene
  responses reuse geometry; viewport overscan and scale/detail rebasing bound cached surfaces.
  Shapes, picking, terrain, labels and insertion previews retain their coordinate contracts.
  No renderer dependency, runtime change or hidden fallback was added. Custom themes must retain
  the wrapper's 3D hierarchy and use descendant selectors (see ADR 0020).
  The fresh pre-batch 128-Step run measured 91 ms frame p95 and 547 ms total JavaScript for 60
  navigation frames; the broad batched run measured 98 ms p95 and 194 ms JavaScript, with 2355
  descendants and all 128 Steps still rendered. Other batched runs ranged from 81 to 102 ms p95.
  Script/style work is lower, but smooth dense navigation is **not demonstrated**; frame-pipeline
  profiling remains open. Evidence: `/private/tmp/railix-camera-batch-red.log` and
  `/private/tmp/railix-camera-batch-review.log` (41 rendering cases passed, packaging passed).
  Final camera/theme/insertion verification passed 8 cases and packaging in
  `/private/tmp/railix-camera-batch-final-green.log`. The camera mutation probe now uses controlled
  renderer observations so unrelated asynchronous metrics cannot invalidate the measurement.
  The 6003-Step navigation case also passed: 20 measured requests after 12 warmup requests,
  308 maximum world/label DOM elements and 1.96 ms scene-request p95; this is not a frame-rate
  or long-duration leak proof (`/private/tmp/railix-camera-batch-final.log`).
  Evidence: `/private/tmp/railix-depth-belts-red.log`, `/private/tmp/railix-depth-dense.log`,
  `/private/tmp/railix-belt-material-red.log`, `/private/tmp/railix-belt-material-dense.log`,
  `/private/tmp/railix-depth-review-package.log`, `/private/tmp/railix-lateral-branch-fixed.log`,
  `/private/tmp/railix-lateral-destination-red.log`. The broader scoped run passed 26 world and
  8 editor/audio browser cases: `/private/tmp/railix-stable-junctions-review.log`.
  The strengthened destination regression and three related render checks then passed with
  packaging in `/private/tmp/railix-lateral-destination-green.log`; `git diff --check` passed.
  Full `clean verify` and cross-browser performance qualification were not run.
  Native review now runs the corrected package at `http://127.0.0.1:65121/` with the existing
  `/private/tmp/railix-review-20260907/railix.project.json`, not the old preview.
  The batched package was checked there at whole-group and close zoom, then on the selected
  Standard path through its T/L outlets. Project, Creator metadata and generated JAR hashes
  stayed unchanged; test browsers and test JVMs were gone after verification.
  Eight embedded music scores were rendered into 12-second review excerpts under Creator's
  `target/audio-review/`; all are audible signal-wise and unclipped. Native start/next/stop and
  preserved mute were checked. Musical quality has not received a human listening acceptance.
  No new generated-application endpoint, dependency or runtime path was added.
  Creator supports recursive local CSS themes, portable appearance metadata, seeded world scenery,
  power conduits, optional Example selection/replay, and known never-executed Step styling.
  Appearance changes do not rebuild the application. Groups remain visual: their timing is the sum
  of sampled Step means, not measured passage latency. App/JVM/System metrics remain separate.
  One right-hand radar/HUD replaces the bottom dock.
  Clicking opens the selected Step/group Inspector. Focus and group navigation sit beside Close;
  Delete is a labeled-for-accessibility trash control at the bottom. Overview owns construction and observations;
  Inputs, Appearance, Examples and Groups remain separate. Selection uses a shared accent except
  for issues; expanded boundaries retain group colors. Automatic expansion waits for readable
  children and retains the deepest automatic-region boundaries. Field/comparison/value stays primary in
  conditions; optional calculations and comparison extensions use native disclosures.
  Camera-aware radar distinguishes transport/machines, selection and known health/coverage.
  Healthy sampled-time shading is blue, not warning yellow. Cargo and belt sections move for
  observed traffic or the selected Example; terminal intakes are recessed floor openings.
  Keyboard navigation preserves native text editing, IME and modified OS shortcuts.
  Effects and quiet music default on after browser interaction and respect explicit mute.
  Hidden tabs pause music in place and resume it on return unless manually paused or muted;
  effects and previews stop. Runtime execution is independent of Creator audio and visualization.
  Stable world-coordinate conveyor identities avoid destroying/restarting belts on camera moves.
  Machine and conveyor materials use fixed geometry with compositor scaling; reveal transitions
  no longer change station size. Dense navigation remains incomplete as described above.
  Eight music arrangements now use bass figures and melodic phrases with locally synthesized
  kick/snare/hi-hat instruments. Public MML parsing, actual audio envelopes, voice reuse and
  playlist traversal have scoped tests; musical acceptance remains with the user.
  Selected terminal replay is shown only for an unambiguous successful exit; ambiguous terminal
  branches remain unmarked, and no terminal throughput is invented.
  World-space scenery clearance and close-up continuity are independent of label placement.
  Step appearance edits leave siblings, functional JSON and application PID unchanged.
  No new dependency, generated-application endpoint, runtime code or persisted layout was added.
  Full `clean verify` and fresh overall coverage were not run for this correction.
  **Belt/zoom correction:** the bounded scene derives a stable station scale separately from group
  content bounds. Viewport-wide collision rescaling is removed; shape changes adjust socket contacts,
  not branch axes. T/L junctions include through-ports and retain selected rims. Collinear treads share
  a world-space phase; cargo paints above arrows, both move with the plates, and excess transport
  hides beneath casings rather than opaque socket strips. No per-request browser objects were added.
  Navigation checks compare against the actual camera scale and allow empty-space views.
  **Implemented:** Creator-wide theme, motion and audio preferences. **Planned:** language and local
  time display. Preferences belong to Creator, not the compiled functional project.
  **September 11 settings/group correction:** embedded themes/scores coexist with local files and
  are downloadable; preferences use revision-checked global settings rather than project metadata.
  Sound/music HUD shortcuts, Trigger-hover Example selection, clipped raised cargo and explicit
  group exit are implemented. Small entered groups expose all Steps and stay open on zoom-out.
  Long chains retain forward lanes after user review removed serpentine packing and U-turn routing.
  Group entry starts at the first Step at local 50%, not a fit of every Step. Choice outlets are
  symmetric around their immediate destination stations; subtrees do not bias the split.
  Content padding does not accumulate or enlarge machine casings.
  **September 11 correction, implemented; visual acceptance pending:** normal machine/belt scaling,
  eight-child automatic overviews, 180 ms group reveals and Home at App/50% replace capped station
  size and repeated binary partition boxes. The 143-node review project contains 128 real sequential
  numeric transformations, not 128 user groups. Automatic-section Overview identifies Step and named-group counts.
  Settings separates Appearance, Sound and Music; playback buttons do not reopen Settings.
  MML text scores feed the existing synthesizer; old JSON scores remain readable. Default installation
  never overwrites local edits. No executable score code, binary media, runtime dependency or generated-app
  endpoint is added. Review addresses shuffle advancement, preview cancellation, muted reloads,
  bounded voices, Settings clipping/contrast and an unbounded off-screen label collision grid.
  Current geometry verification passed 62 scene, 44 observation and six navigation/transport cases.
  The final 6,003-node/60-navigation probe measured 3.390 ms viewport-request p95, at most 330 world/label
  DOM elements and a 162,324-byte post-GC heap delta; no workers or detached script states.
  The 20,000-node probe measured 107.151 ms indexing and 0.108 ms query p95. These remain advisory,
  not dense-viewport, million-Step or leak-free certification. Full `clean verify` and overall coverage
  were not rerun. Settings/audio verification passed 119 protocol cases and six browser journeys,
  followed by a native-package run covering two protocol and four browser cases after simplifying
  legacy conversion and HUD state reflection. These are overlapping verification runs, not additive
  coverage totals. Regressions first confirmed stale audio starts, read/delete directory side effects,
  overlapping region machines, exterior U-turn culling and HUD-obscured group contents.
  The subsequent native package passed five HUD-fit/navigation/large-scene browser cases. Camera
  fitting excludes controls without changing persisted geometry; sound and music volume changes
  no longer redraw the diagram. New Music drafts inherit the selected music group.
  Final package verification passed the new grouped Music save/preview cleanup, persisted mute and
  legacy-score editing browser journeys, plus 80 upstream cases. No commit or push was performed.
  In the native 823-pixel-wide review tab, stepped CSS cadence reduced an idle overview sample from
  23.4% renderer / 74.5% GPU-process CPU to 11.4% / 16.5%; the generated app sample was 0.3%.
  Selected-path close-up samples were 13.9% / 21.1%. These are process CPU readings, not GPU utilization
  or isolated benchmarks; the shared browser GPU process measured about 2.8% with the preview blank.
  **Implemented:** the metrics table aligns matching catalog identifiers and units
  across App/JVM/System rather than zipping unrelated lists; unavailable cells remain empty.
  Desktop and 320px regressions cover CPU alignment and unfamiliar catalog measurements with differing
  units. JVM heap limits are not host memory, and no App/JVM CPU split or scheduler-thread count is
  inferred from a shared process counter. Group-count default symbols remain a suggestion, not shipped.
- UI delivery: **Factory-machine target not yet accepted.** User review
  rejected the initial presentation as a repaint. The machine-and-conveyor replacement below is running
  in the native Creator with the complex example. This does not change the accepted Item 5 or
  overall roadmap count.

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
- no third-party Java runtime or graphics-framework dependency; Creator renders its viewport in CSS.

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
   derived. WebGL2 renders visible stations and rails with bounded accessible DOM labels.
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
units; incremental structural indexing; chunked Example manifests and indexed trace-detail access.
The following UI preparation replaces full metric ingestion and route traversal cutoffs.
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

### Factory Canvas Presentation

Status: **Implemented with scoped verification; UX acceptance pending.**

The initial presentations were rejected as repaints. On 2026-09-08 the user approved the generated
factory-machine target image and its implementation, without committing or pushing. Overall roadmap
acceptance counts remain unchanged; implementation is not a substitute for visual acceptance.

The replacement has four technical gates:

1. Canvas-first construction: contextual bottom dock, searchable placement tray, on-demand Inspector,
   accessible App/build HUD, and compact application status. Construction builds the real application.
2. Application-owned Example selection above its Trigger, with input/output summaries in the dock,
   without re-execution or rebuilding. Unchanged values and focused controls survive counter updates.
3. Layered machines with faceplates, sockets and broad segmented conveyors with rounded orthogonal
   corners. Carrier density uses measured traffic, and sampled-duration heat preserves authored
   colors. Groups and Steps share a collision-limited footprint; authored shapes remain supported.
4. Continuous group zoom, bounded viewport work, responsive controls, native packaging, screenshots,
   review and owned-process cleanup. No persisted camera, geometry or insertion preview.

The September 9 renderer replaces direct WebGL resource management with locally bundled PixiJS
8.20.1 and its CSP extension (MIT). Existing scene, catalog, camera and semantic-zoom contracts remain.
Two meshes draw conveyors and overlays; visible Sprites share baked machine textures by shape and
resolution. Offscreen Sprites and unused textures are released; disposal also releases geometry,
shaders and the renderer. Pixi housekeeping runs with explicit frames, not an independent idle ticker.
The two unmodified vendor scripts total 834,616 bytes. No new Java module, production type, npm build,
CDN, alternate renderer, protocol-specific behavior, artificial traffic or reward system was added.
Traffic density is logarithmic, not one particle per request. Traffic-only frames reuse geometry and
labels; idle, stale, hidden and reduced-motion states stop animation. Group counters aggregate
contained Steps and do not claim flow latency. Neighbor checks use repaint-local spatial buckets,
not the traffic animation loop; worst-case buckets can still require pairwise checks.

The Pixi review corrected backward static chevrons, joined conveyor corners without overlapping
segment fans, and retained authored shapes and shape-aware selection. The final pixel regression
fails against the old chevron direction and passes after correction. Pixel probes use Pixi extraction
and reject blank buffers; they do not run in the application. Traffic probes wait for initial viewport
fitting, and mobile shape probes close the Inspector before clicking the actual canvas.

Verification uses `./mvnw -pl modules/railix-creator -am test` with explicit `-Dtest` selectors,
`-Drailix.test.forks=2 -Djacoco.skip=true`; mobile adds `-Drailix.browser.viewport.width=320`:

- The full **45-case renderer run** passed 43 cases and exposed two test-probe defects, not a wholly
  green build (`/tmp/railix-pixi-final-world-package.log`, 7:40). After correction, all **10 affected
  arrow, traffic and lifecycle cases plus Maven native packaging passed** in
  `/tmp/railix-pixi-package-green.log` (2:03). Together the runs cover all 45 renderer cases.
- **Eight desktop Editor cases and two generated-artifact boundary cases passed** in
  `/tmp/railix-pixi-editor.log` (1:37). The artifact cases passed again in the full renderer run:
  generated production JARs contain neither Creator/Pixi assets nor development/trace surfaces.
- **Eight mobile Editor and three native-launcher cases passed** in
  `/tmp/railix-pixi-mobile-native.log`; four additional desktop-only shape probes initially failed
  behind the mobile Inspector. After correcting their setup, all **five mobile canvas cases passed**
  in `/tmp/railix-pixi-mobile-shapes-final.log` (57 seconds), and all **four desktop shape cases passed**
  in `/tmp/railix-pixi-desktop-shapes-final.log` (54 seconds). One superseded probe run was stopped;
  it is not counted as evidence. The shape tests now join the responsive CI selection.
- Each scoped reactor run also passed **8 core and 72 standard-library cases**. Reruns are not
  additional coverage. Full `clean verify` and coverage were not rerun. Earlier Java coverage was
  95.53% lines / 90.82% branches, not a fresh or JavaScript coverage measurement.

The September 9 real 6,003-node desktop navigation check measured advisory p95 viewport response of
**1.256 ms** and CPU Pixi submission of **0.900 ms**, with at most 21 scene glyphs, 60 route segments
and 44 label DOM elements. Post-GC heap samples were 5,226,556, 5,272,756, 5,386,656 and 5,475,232 bytes
after 0, 20, 40 and 60 measured navigations. These are not GPU-completion timings, leak proof or
million-Step/application-throughput certification. The raw report is `target/world-measurements-1280.txt`
under the Creator module. The preceding 20,000-Step scene check was not rerun for this renderer-only change.
The 143-node review project is unchanged and is open
in the rebuilt native Creator. Real application screenshots show the
[overview](screenshots/factory-workshop.png), [construction tray](screenshots/factory-workshop-construction.png)
and [nested detail](screenshots/factory-workshop-detail.png).

The Pixi iteration adds **64 net owned lines to the existing world renderer**, separately from its
vendor assets and the preceding uncommitted UI work. Review covered resource ownership and browser
probes. The native review uses
the unchanged 143-node project with five application-owned Examples. All three screenshots above
were refreshed from that actual application and visually checked, not generated mockups.

September 9 navigation follow-up: **scoped verification passed; subsequent HUD decision accepted below.**
Regression tests reproduce viewport observation flashing, a twofold grid-density jump, and inverted
inner conveyor faces. The fixes retain one bounded observation snapshot across unchanged scene
identities, blend adjacent grid scales, reconnect elbows to authored shapes, reserve belt clearance,
and use shallow S-bends without degenerate join normals. The renderer changes add 22 net owned lines.
Polling also duplicated an already-pending selected-Step read; identical in-flight reads are now reused.

The HTTP 503 investigation found small Example status/metadata reads sharing expensive projection
admission. Runtime snapshots now have a separate four-response budget; inventory, coverage, queries,
views and Step projections keep the existing heavy budgets. Creator retains global request bounds
and the four-response heavy-output budget through slow-client writes. A relative `steps/<node>`
classification error found during review is covered too. Overload still returns 503; no retry loop,
unlimited admission or fabricated success was introduced. Historical browser logs cannot identify
which layer rejected each request. Regression failures were reproduced before their fixes, including
two selected-Step requests where one was expected and snapshot reads returning 503 under real
slow-client backpressure. Scoped Maven packaging passed all **51 renderer cases and 8 App/API cases**
in nine minutes (`/tmp/railix-navigation-final-package.log`). A second run passed **5 mobile canvas
cases and 3 native-launcher cases** in one minute (`/tmp/railix-navigation-mobile-native.log`). Each
reactor run also passed the same 8 core and 72 standard-library baseline cases; repeated runs are
not additional coverage. Native jlink/jpackage output was rebuilt and started with the unchanged
143-node review project and its five application-owned Examples.

With other review views open, 20 real zoom/pan cycles and an Example selection produced **172 API
responses, all HTTP 200, and no JavaScript errors** in the observed browser. Actual screenshots in
`output/playwright/navigation-belt-detail.png` and `navigation-branch-detail.png` show the corrected
S-bend and wide-choice docking. Route labels still need more contrast and separation from belts.
Task-owned test processes and the disconnected browser daemon were stopped; the native Creator
and its owned application remain running. Project and presentation-file hashes are unchanged.

The refreshed 6,003-node navigation report measured advisory p95 viewport response **1.202 ms**
and CPU Pixi submission **1.100 ms**, with at most 21 glyphs, 60 route segments and 32 label DOM
children. Post-GC heap samples after 0/20/40/60 measured navigations were 5,232,924, 5,329,948,
5,405,460 and 5,498,780 bytes. This is not GPU completion, leak proof, million-Step certification
or a fresh coverage measurement. Full `clean verify` was not rerun. The renderer gained 22 net
owned lines in this follow-up; no dependency or production-flow execution path was added.

September 9 connection-grid follow-up: **design accepted; scoped verification complete.**
Custom exterior shapes now share square symbol areas. Linear trigger connections follow the actual
entry axis; shared ports form continuous T/L conveyor networks rather than overlapping individual
casings. Shared-port geometry comes from the graph, never proximity alone. Band markings remain
perpendicular on long junction runs. These are view-only connections, not new Split/Merge semantics.

The relative orange meter is removed: sampled average duration is not utilization. Duration now
shares the station's name label; group values explicitly say Step average, not flow latency. Lamps
indicate current observed traffic or errors rather than treating historical execution as health.
Configure is the general Inspector entry, build status opens separate contextual details without
changing selection, and Example selection remains at the Trigger. This applies the contextual
controls and explicit action labels in [Shapez 2 Devlog 014](https://store.steampowered.com/news/posts/?appgroupname=shapez.io&appids=1318690&enddate=1708614153&feed=steam_community_announcements)
and consistent state displays in [Factorio FFF-363](https://www.factorio.com/blog/post/fff-363).

Regression tests reproduced distorted symbols, absent stable timing labels, the ambiguous orange
meter, wrong HUD destinations, skewed junction markings, and the complex graph's 7.414-unit trigger
offset before fixes. Simpler scene fixtures already passed and were not counted as regression proof.
The broad scoped run covered 55 scene, 59 renderer and 65 editor cases. It exposed an idle repaint
and three outdated or unsynchronized browser-test assumptions, not a clean full-suite pass.
The idle repaint came from repeatedly hiding an already-detached Example anchor; unchanged
status reads now do no drawing. Test probes now scan the full belt, await the applied camera
response, and establish keyboard/pointer ownership before checking placement handoff.
Targeted corrections pass, including four App/build-fact cases. Native Maven packaging with five
320-pixel HUD cases passed in 67 seconds (`/tmp/railix-grid-package-verified.log`); three packaged
launcher cases without external Java plus two desktop HUD cases passed in 38 seconds
(`/tmp/railix-grid-native-smoke.log`). The two idle/placement regressions pass together
(`/tmp/railix-grid-controls-final.log`). Each reactor run also passed the same 8 core and 72
standard-library baseline cases; repeated runs are not additional coverage. Full `clean verify`
and the entire broad suite were not repeated after these corrections.

The 6,003-node review measurement recorded p95 viewport response 1.440 ms and CPU Pixi submission
1.000 ms, with at most 21 glyphs, 60 route segments and 35 label DOM elements. Post-GC heap samples
after 0/20/40/60 navigations were 5,247,980, 5,339,188, 5,437,476 and 5,543,288 bytes. These are
advisory measurements, not GPU-completion timings, leak proof or million-Step certification.
The existing renderer grew by 52 net owned lines in this follow-up; no runtime dependency,
persisted layout, Step kind, or application execution path was added.

The rebuilt native Creator runs the unchanged 143-node review project with five application-owned
Examples. The final screenshot session observed 81 API responses, all HTTP 200, and no JavaScript
errors. Existing overview/construction/detail documentation images were refreshed from the real app.
Project and presentation-file hashes are unchanged. Task-owned test browsers and temporary review
JVMs are closed; only the native review Creator and its owned application remain running.
The Mac was locked, so the user's existing browser tab could not be switched automatically.
Visual acceptance, Item 5.6, million-Step compilation and fresh full coverage remain outstanding.
Publication is now authorized as a separate factory-UI branch above the shared metric contract.

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
- Factory UI integration and native packaging: 323 Creator cases plus the same 80 upstream
  cases passed in 17:37. Includes 59 World, 65 Editor, 33 Workspace, 55 scene, 103 generated
  Example-suite, four production-artifact isolation and four focused composition/workbench cases.
  This is scoped verification, not a fresh full `clean verify` or a UI acceptance decision.
- Three final native-package smoke cases passed: Creator plus owned application starts without
  system Java, application-owned Examples are observable, and stopping the executable terminates
  both processes. The focused Maven run including the same 80 upstream cases took 11.234 seconds.
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
