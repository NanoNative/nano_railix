# Historical Verification Evidence

These are earlier reports migrated from the roadmap and ADR 0020 on 2026-09-19.
They are not current CI results, current process state, or product certification.
Statements such as "current", "next" and "accepted" below refer to their original
checkpoint, not a new instruction or approval. Old renderer experiments are historical.
Active behavior lives in [specifications](specs/); delivery status lives in
[roadmap.md](roadmap.md). No runtime tests were rerun for this documentation move.

Machine-local temporary log paths are recorded as basenames only. Those logs were
ephemeral and are not shipped evidence; retain the conditions and reproducible test
entrypoints, not an assumption that another checkout can access those files.

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
  Evidence: `railix-ui-publication-final-verify.log`,
  `railix-ui-publication-remaining-verify.log`, `railix-publication-corrections.log`,
  `railix-publication-step-labels.log`, `railix-publication-final-package.log`,
  `railix-publication-package-mobile.log` (package/soak gate), and
  `railix-publication-responsive-verified.log` (final mobile gate).
  **2026-09-12 theme variants:** embedded Foundry Standard/High detail and Classic, optional local
  descriptors and relative CSS/PNG/SVG assets, non-overwriting installation, shared building frame,
  and the Running HUD row are implemented. The generated application is unchanged. The initial
  shell-only PNG experiment did not meet smooth-frame targets; the production Canvas delivery below
  supersedes it. See ADR 0020 for measurements and limits.
  **Verification completed 2026-09-13:** 65 scene cases, 18 theme HTTP cases, 12 targeted World cases,
  three Settings browser cases at both 1280 and 390 pixels, and the 6,003-node/60-navigation regression
  pass. Theme switching preserves the running application's PID and project; native packaging passes.
  A leaf-focus/overscan defect found during review is fixed, as is literal `+` handling in asset paths.
  Evidence: `railix-theme-final-verification.log`, `railix-theme-mobile-final.log`,
  `railix-focus-viewport.log`; the earlier broad renderer run is not a new full-CI certificate.
  The native complex-example preview was restarted; project, Creator metadata and application JAR hashes
  were unchanged. Its then-pending native visual check is superseded by the modular-renderer review below.
  **2026-09-13 Overview:** type/name and shared-renderer building portrait, removal of the icon chooser
  while retaining stored icons, and concise coverage/connection status pass 11 targeted desktop cases
  and three cases at 390 pixels. Native packaging passes; the preview's three content hashes remain
  unchanged. Evidence: `railix-overview-final.log`, `railix-overview-mobile.log`.
  GPU utilization and automatic Example-input proposals are not implemented.
  **2026-09-13 modular renderer: Implemented; visual acceptance pending.** Foundry exposes
  Renderer Canvas (default) and Renderer CSS through the same Settings selector, each at high quality.
  The earlier Standard variants have been removed; existing `canvas` and `hq` selections retain their IDs.
  The two-choice selection, high-resolution Canvas default and saved CSS preference pass desktop/narrow
  regressions. Nineteen scoped settings, extension, group-navigation and motion cases plus a packaged
  extension check pass in `railix-renderer-names-package.log` and
  `railix-renderer-names-smoke.log`. The running complex preview shows both exact labels;
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
  Evidence: `railix-modular-themes-package.log`, `railix-native-package-final.log`,
  `railix-native-mobile-final.log`, `railix-modular-native-package-smoke.log`;
  measurements: `railix-renderer-retina.jsonl`. ADR 0020 records reproduction and limits.
  Final gesture review fixed the Inspector remaining open on group entry and intercepting the second
  click on narrow screens. Four desktop/narrow gesture cases, including normal Inspector interaction,
  pass in `railix-final-gesture-package.log`; packaging succeeds. Eight other final
  variant/zoom/motion cases passed in `railix-modular-final-package.log`. Its four gesture
  failures were a test selector for an unavailable Appearance tab on automatic sections, corrected
  to the existing Groups tab before the passing rerun.
  Two final packaged extension/cleanup checks pass in `railix-modular-packaged-final.log`.
  The earlier native complex-example check used Canvas Standard: 128-Step double-click entry at 50%, Home,
  wheel zoom and visible replay parcels were inspected. No page errors or leftover test processes
  were found. Functional project, Creator metadata and application JAR hashes remain unchanged.
  **2026-09-13 scenery atlas: Implemented.** Renderer Canvas reuses three embedded environment images
  through the existing atlas, resource overrides and static ground cache; CSS variants and atlases
  without scenery entries keep their CSS environment. Coverage is plain percentage text without a bar.
  Scoped verification covers 21 functional cases across targeted runs, Retina pixel/zoom/reload checks,
  editable-theme installation and packaged extension loading. Two stale test assumptions were corrected:
  selecting empty corners of isometric bounding boxes, and clicking through an open Inspector.
  Evidence: `railix-scenery-final-package.log` (17 initial passes),
  `railix-scenery-reviewed-package.log` (four corrected cases and packaging),
  `railix-scenery-retina-verified.log` (two Retina cases). The 128-processor comparison,
  now with six image-backed environment plots, retains 16.7-16.8 ms Canvas p95 versus 83-100 ms CSS;
  raw evidence is `railix-scenery-retina.jsonl`. The packaged extension check passes in
  `railix-scenery-retina-package-smoke.log`; its two stricter scenery-test fixture failures
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
  checks passed in 38s. Logs: `railix-foundry-package.log`,
  `railix-foundry-final.log`, `railix-foundry-boundaries.log`.
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
  scoped package run passed in 2m41s (`railix-foundry-transport-package.log`). This is not
  acceptance of target-image fidelity or completion of the dense 128-visible-machine performance work.
  Native review also exposed ignored nested keyframes: a running animation name alone did not prove
  parcel movement. The new position-change regression failed, then passed with top-level keyframes.
  Final package: 10 cases passed in 1m28s (`railix-foundry-motion-final.log`). The active
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
  (`railix-paths-buildings-review.log`); the functional project and generated application
  JAR hashes are unchanged. Temporary profiling code was removed. Reduced motion is used only as
  a review-preview mitigation pending the renderer decision, not as a performance fix.
  **Renderer correction (2026-09-12):** after the allowance pause, Portals and Groups now
  share the existing body-size rule. Portal heights are proportional, not independent 84-unit uprights.
  Ground transport, projected parcels and raised buildings use three compositing contexts without
  changing the project, authored shape overrides or generated binary. Four containers are added per
  renderer, not per Step; no graphics dependency or per-frame JavaScript loop is introduced.
  The comparable 120-frame Chromium trace reduced accumulated `DirectRenderer::DrawRenderPass`
  duration from 270,094 to 146,121 microseconds (`railix-renderer-trace.log` and
  `railix-renderer-layers.log`). These nested event durations are not whole-process CPU
  percentages. Per-building isolation and mask-free parcel triangulation were measured and removed;
  temporary profiling code is not retained. The 22-case camera/transport/group regression run passes
  (`railix-renderer-regressions.log`); the 128-Step navigation sample has nine visible
  machines and 18 ms frame p95. This does not certify 128 simultaneously detailed animated machines.
  Five focused World cases and one theme-switch case also pass (`railix-renderer-alignment.log`),
  including a geometric check of parcel feet against actual conveyor projections after zoom/pan.
  Initial scoped package verification passed 27 Creator cases plus the 8 core / 72 standard-library
  baseline cases in 4m28s (`railix-renderer-final-package.log`). Review also fixed retained
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
  Four focused wheel/camera/parcel/128-Step cases pass (`railix-roof-viewport.log`).
  The narrow 390px parcel geometry check excludes offscreen overruns beneath culled machines;
  every zoom must still test exposed onscreen parcels within the unchanged 1px tolerance.
  Final native packaging passes 91 Creator cases (64 scene, 26 renderer, one theme editor) plus the
  8 core / 72 standard-library baseline cases in 4m43s
  (`railix-renderer-complete-package.log`). Four additional 390px browser cases pass
  (`railix-renderer-mobile-complete.log`); wheel entry uses successive real gestures
  because a narrow viewport starts farther from the detail boundary. The same wheel test also passes
  again on desktop (`railix-renderer-desktop-wheel-final.log`). Native review confirms entry
  at the first of 128 Steps with local 50% zoom. The temporary viewport override was removed.
  A nine-second native sample at 1280x900, 70% zoom, with Example replay and 27 parcels active records
  35.5-42.1% CPU for the shared graphics process, 15.0-30.0% for the page renderer, 1.5-2.1% for Creator
  and 0.3-0.7% for its generated application (`railix-native-active-cpu.log`). This is not
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
  profiling remains open. Evidence: `railix-camera-batch-red.log` and
  `railix-camera-batch-review.log` (41 rendering cases passed, packaging passed).
  Final camera/theme/insertion verification passed 8 cases and packaging in
  `railix-camera-batch-final-green.log`. The camera mutation probe now uses controlled
  renderer observations so unrelated asynchronous metrics cannot invalidate the measurement.
  The 6003-Step navigation case also passed: 20 measured requests after 12 warmup requests,
  308 maximum world/label DOM elements and 1.96 ms scene-request p95; this is not a frame-rate
  or long-duration leak proof (`railix-camera-batch-final.log`).
  Evidence: `railix-depth-belts-red.log`, `railix-depth-dense.log`,
  `railix-belt-material-red.log`, `railix-belt-material-dense.log`,
  `railix-depth-review-package.log`, `railix-lateral-branch-fixed.log`,
  `railix-lateral-destination-red.log`. The broader scoped run passed 26 world and
  8 editor/audio browser cases: `railix-stable-junctions-review.log`.
  The strengthened destination regression and three related render checks then passed with
  packaging in `railix-lateral-destination-green.log`; `git diff --check` passed.
  Full `clean verify` and cross-browser performance qualification were not run.
  Native review now runs the corrected package at `http://127.0.0.1:65121/` with the existing
  `railix-review-20260907/railix.project.json`, not the old preview.
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

## Flow And Group Checkpoint History

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
   [Accepted Inspector screenshot](../screenshots/roadmap-item-5-3-inspector-diagram.png).

   Final clean acceptance log: /tmp/railix-followup-acceptance-20260907-accepted.log. Focused evidence:
   /tmp/railix-shape-scene.log, /tmp/railix-ui-corrections.log, /tmp/railix-shape-hits.log.
   /tmp/railix-visual-regressions-red.log and /tmp/railix-visual-regressions-green.log record the
   final rendering findings and their corrections. The earlier full run in
   /tmp/railix-followup-acceptance-20260907-final.log had one stale assertion and two collapsed-value
   test timeouts; it is not successful acceptance evidence.
   Final command remains ./scripts/coverage.sh; never overlap Maven runs. Integration-test by itself
   can print BUILD SUCCESS with failures: inspect reports or run failsafe verify as well.
   The earlier review project remains at railix-review-20260906; its old process was
   no longer running when checked on September 7. The refreshed 143-node, five-Example project is
   railix-review-20260907/railix.project.json. The verified native review runs on port
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
   [Accepted group/Example view](../screenshots/roadmap-item-5-3-semantic-zoom-group-management.png),
   [6,003-node overview](../screenshots/semantic-zoom-overview.png), and
   [real Step detail](../screenshots/semantic-zoom-detail.png) are captured from the working Creator.

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
[overview](../screenshots/factory-workshop.png), [construction tray](../screenshots/factory-workshop-construction.png)
and [nested detail](../screenshots/factory-workshop-detail.png).

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

### Earlier Runtime And Delivery Evidence

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

## Observability Foundation Acceptance

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
7. **Closed:** the [accepted production-observability screenshot](../screenshots/roadmap-item-6-1-production-observability.png)
   shows the real Maven-built Creator, selected Example path, application-owned result, and
   connected Step/Flow metrics.

Protocol-specific Trigger test clients and Example assertions remain later checkpoints; current
Examples begin at the generic Trigger output after protocol conversion.


## Renderer Comparison

The earlier layered-renderer probe used a 6,003-node public browser navigation regression:
60 measured requests had p95 1.795 ms and at most 155 world/label DOM elements in visited views.
Before correction, top-level overhang queried offscreen Steps at deep zoom and could obscure
the focused leaf; ground-only bounds instead lost visible elevated groups. The retained design
uses station-scaled overhang. These are historical probe results, not latency guarantees.

Explicit promotion, per-building isolation wrappers and mask-free gradient triangulation did
not improve the probes enough to retain. Layer separation improved traced compositing costs.
A short native replay sample measured 36-42% CPU for the shared graphics process and 15-30%
for the page renderer, not GPU utilization or a matched thermal test. The shell-only PNG probe
reduced composition cost but missed smooth-frame targets and was superseded by Canvas.
Reduced motion was not accepted as a performance fix.

The three scenery motifs added 234,681 encoded bytes and 1,555,605 decoded pixels
(5.93 MiB RGBA, excluding masks and browser copies), shared per motif rather than per building.

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

Latest scenery-atlas evidence: `railix-scenery-retina-package-smoke.log` and
`railix-scenery-retina.jsonl`. The initial building-only run remains in
`railix-renderer-retina.jsonl`. Reproduce with
`-Drailix.renderer.comparison=true -Drailix.browser.dpr=2` and
`CreatorWorldBrowserIT#compareProductionVariantsWith128VisibleStations`. The comparison now discovers
the current descriptor choices; it no longer runs the removed Standard variants.
The original captured sprite strips can be reproduced separately with `railix.sprite.export=true`;
no screenshot converter runs in the shipped Creator.

## Unified Branching, 2026-10-31

The local reactor `./mvnw test` passed 871 core, 77 standard-library and 1,787 Creator tests.
The final lazy-allocation adjustment was rechecked with `StepContractJsonE2eTest`,
`PrimitiveStepsCreatorProjectE2eTest` and `BranchingFlowE2eTest`: 134 tests passed.
The branching regressions exercise generated production JARs, installed implementations and
validators, independent failures, atomic writes and rollback, split snapshots, nested-program
entry snapshots, retained-handle rejection, per-path traces and physical worker drain.

Thirteen selected desktop/narrow browser scenarios passed through the existing JUnit/Playwright
stack. They cover Branch settings, route/condition edits, save/reopen, Undo/Redo, previews,
connected-route deletion protection, the 167-node authoring fixture, the 155-node grouped fixture
and the 1,237-node system with live ingress, Examples and reuse. Narrow navigation was checked at
430 and 320 pixels. Screenshot captures are local ignored build evidence, not source assets.
`./mvnw -DskipTests package` built the Creator app image. Its CLI ran a real parallel Branch
application and printed `"BRANCH_OK"` with exit status zero, without Node.js.
The complete `clean verify` browser/package/soak matrix and remote CI were not run for this checkpoint.

A production-JAR probe compared baseline `b22fc6c09bca5c97fce669c60cfb2f50be02caf6` with this
increment through `RuntimeApplication.runSource`. The workload is CLI arguments -> Lowercase ->
result, with an optional literal-true first-match Choice/Branch before Lowercase. Every response
is checked. Same local Java 25.0.1 JVM, `-Xms128m -Xmx128m`, one caller, 100,000 warmup calls and
seven 100,000-call rounds. Values below are medians of the final five rounds. Allocation uses
the calling thread's JDK allocation counter. Timing is advisory, not a throughput guarantee.

| Workload | Time per call | Allocated bytes per call |
| --- | --- | --- |
| Baseline ordinary flow | 0.989 us | 4,272 |
| New ordinary flow | 1.008 us | 4,304 |
| Baseline first-match Choice | 1.157 us | 4,968 |
| Unified first-match Branch | 1.480 us | 6,872 |

The ordinary path adds 32 allocated bytes, not a scheduler or worker. Unified Branch still costs
more than the old single-purpose Choice. Removing unused snapshot preparation and stream-based
route copying reduced its probe from 1.743 us / 8,000 bytes to 1.480 us / 6,872 bytes. Parallel,
large-payload, sustained-load and multi-host costs are not established by this small probe.

### Branch Performance Follow-Up

The first-match path no longer prepares worker/result lists before a continuation is actually
started. Route matching shares the ordinary matcher-group evaluator without a temporary Boolean
result map. Route-map copying and the first-match handler avoid unused intermediate collections.
The public continuation API, atomic commits, copy snapshots and worker ownership are unchanged.

The same production-JAR probe was repeated with five fresh JVMs per version and workload,
interleaving the versions. Each JVM used the warmup and seven measured rounds described above.
The table reports the median of the five per-JVM medians of the final five rounds.

| Workload | Before | After | Allocated bytes before / after |
| --- | --- | --- | --- |
| First-match Branch | 1.471 us | 1.385 us | 6,872 / 6,144 |
| Installed Step with eight value inputs | 1.260 us | 1.282 us | 4,816 / 4,816 |

Branch saves 728 bytes per call, about 11%, with about 6% lower measured latency. The multi-input
control has overlapping timing ranges and unchanged allocation. These are local probes, not a
general throughput claim. Branch remains more expensive than the earlier single-purpose Choice.
An attempted generic singleton-map optimization was rejected after a five-JVM control showed
about 5% higher latency for the multi-input Step without saving allocations. Ordinary input-map
construction is unchanged. The retained singleton-map optimization applies only to route inputs.

The full browser run exposed narrow-layout and feedback defects missed by the earlier selected
scenarios. The condition source is now visible without scrolling on 320/390-pixel screens.
Recorded outcomes mark the actual routes in Configure and Data, including multiple outcomes.
Type conflicts remain readable when recorded values cannot be loaded. Declared JSON defaults
are directly editable, scalar JSON uses one row, and explicit Reset restores omission without
treating empty strings as missing. Reset also clears an invalid unsaved draft consistently with
other edit actions. Public browser regressions reproduced the feedback/reset defects before fixes.

Local ignored probes and review evidence are under `.railix/branch-performance-evidence/`.

The final `./mvnw clean verify` passed on 2026-10-31 in 25 minutes 38 seconds, without command-line
test exclusions or fork overrides. It ran in an identical source snapshot under
`.railix/branch-clean-verify/` so cleaning did not remove pre-existing active previews. All source
files matched the working tree at that checkpoint. The later Inspector/HUD changes are verified
separately below.

| Verification layer | Discovered | Failures / errors | Skipped |
| --- | --- | --- | --- |
| Core Java | 871 | 0 / 0 | 0 |
| Standard-library Java | 77 | 0 / 0 | 0 |
| Creator Java | 1,790 | 0 / 0 | 0 |
| Desktop browser | 792 | 0 / 0 | 1 |
| Package and runtime soak | 43 | 0 / 0 | 0 |
| Narrow browser | 81 | 0 / 0 | 0 |

The single skip is the explicitly opt-in renderer comparison, gated by
`railix.renderer.comparison`. The normal scene, animation, navigation and browser performance
scenarios ran. A separate final app-image CLI check executed the parallel Branch fixture and
printed `"BRANCH_OK"` with exit status zero. Review findings were fixed and rechecked. Owned test
and helper processes finished. Remote CI and publication have not run for this working tree.

### Inspector And HUD Review

The 2026-11-02 review applied Apple's guidance on
[toolbars](https://developer.apple.com/design/human-interface-guidelines/toolbars),
[sheets](https://developer.apple.com/design/human-interface-guidelines/sheets) and
[accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility)
to the existing web interface, not a new renderer or native UI stack.

Phone editing now occupies the screen. The same Example, history and Settings controls move
with it. Covered canvas motion pauses, while the application and observations continue.
Grouping owns assignment and has immediate loading feedback, explicit retry and no late tab,
focus or reopen side effects. Empty condition catalogs stay collapsed. Scalar JSON controls,
source/operand alignment, symbol disclosure, import error ownership and narrow footer labels
were corrected without changing Step execution or adding dependencies or saved preferences.

Ten new public browser journeys cover the changes. The complete responsive selection passed
all 90 cases, including touch, short landscape layouts, keyboard, examples, errors, connections,
insertion, groups, settings, import and undo/redo. The 320-pixel condition form keeps its complete
Expected field above the footer. A real DOM measurement found a 51-pixel spacing defect caused
by stacked padding/margins and baseline alignment; the corrected gap is 22 pixels.

Manual in-app-browser checks used a saved 1,238-node application with successful and failing
Examples. The automatic viewport/heap scenario also exercised 6,003 nodes. Independent visual
and source review findings were corrected and rechecked. Evidence is in the ignored
`.railix/ui-ux-audit/` directory, with screenshots under `target/screenshots/inspector-*`.

The final `./mvnw verify` passed on 2026-11-02 in 24 minutes 16 seconds, without test
exclusions or fork overrides. Earlier runs exposed obsolete split-panel/group-entry journeys
and a zoom test that continued on a rounded percentage before the Home flight finished.
The journeys now follow the visible controls and wait for the settled camera, retaining their
execution, data, focus and exact zoom assertions.

| Verification layer | Discovered | Failures / errors | Skipped |
| --- | --- | --- | --- |
| Core Java | 871 | 0 / 0 | 0 |
| Standard-library Java | 77 | 0 / 0 | 0 |
| Creator Java | 1,790 | 0 / 0 | 0 |
| Desktop browser | 802 | 0 / 0 | 1 |
| Package and runtime soak | 43 | 0 / 0 | 0 |
| Narrow browser | 90 | 0 / 0 | 0 |

The skip is the same opt-in renderer comparison described above. The full log is
`.railix/ui-ux-audit/verify-confirmed.log`. Current reactor screenshots are in
`modules/railix-creator/target/screenshots/inspector-*`. Relative to the preceding Branch
checkpoint, the production browser changes add 167 lines and remove 55, a net addition of 112
across four existing files. Ten new browser journeys and updated existing journeys cover the
new behavior. There is no new dependency, persisted mode or duplicate control owner.

This is not Apple certification or proof of every possible interface state. Real-device Safari,
iOS and VoiceOver were not tested. No new graphics family or audio composition is part of this
follow-up. The split phone layout and duplicated membership entry point were removed rather
than retained as alternative modes.

### Radar And Navigation Follow-Up

The follow-up reproduced caption clicks moving the camera into empty space, ignored radar
input during a camera flight, focus loss after tab visibility changes, overlapping nested
breadcrumbs and a failing Example name compressed to 91 pixels. The fixes retain the same
select and action controls, keep every ancestor reachable through one native popover, cancel
camera flights on radar input and reuse a current overview after returning to the tab.

Further review found detached popovers, overflow with a valid 61-character project id and
overlapping radar/timing controls in 320/390 by 360-pixel viewports. Native CSS positioning
anchors the parent popover without a JavaScript positioning loop, following the
[WebKit anchor-positioning guidance](https://webkit.org/blog/17240/a-gentle-introduction-to-anchor-positioning/).
The short layout wraps the timing scale beside the radar. Return placement is refreshed after
the scale changes. Timing membership uses the canvas bounds rather than HUD occlusion, removing
the legend's show/hide feedback dependency. No extra redraw loop is retained.

Seven new browser scenarios add thirteen parameterized cases per viewport. Their regressions
include controlled camera time, tab visibility changes, keyboard return, repeated settled
refreshes, combined Example/read failures and Retry recovery. The selected responsive run at
390 pixels passed all 103 cases, also exercising explicitly selected 320, 823 and 1280-pixel
layouts. Manual checks used the saved 1,238-node application with nested groups, failing Examples,
all four Inspector tabs and the radar. Screenshots are ignored build evidence, not theme assets.
The packaged Creator JAR was checked separately with that saved application and an isolated
home directory. Desktop and phone navigation, the failing Example and the parent popover
worked without browser errors. Task-owned preview servers and browser tabs were then closed.

The final targeted Inspector/HUD run passed all 23 cases. Full-run testing also exposed
regression-harness defects: a text assertion compared layout newlines rather than the accessible
measurement label, and a one-millisecond virtual-clock pause point could be overtaken under
parallel browser load. The assertions retain the visible scale and stable measurement checks.
The pause point now leaves virtual time for the protocol round-trip, without a real-time sleep
or a production behavior change. The short arrival-picker test no longer requires station labels
behind its open panel to be visible. It still checks insertion, undo, route selection, button
bounds and hit targets. Two existing navigation journeys now open the parent menu before
choosing an ancestor, retaining the camera-restoration, selected-Example and delayed-focus checks.

The final plain `./mvnw verify` passed on 2026-11-02 in 24 minutes 28 seconds, with no test
exclusions or fork overrides. Of 3,699 discovered cases, 3,698 passed and the opt-in renderer
comparison was skipped. Core Java ran 871 cases, the standard library 77, Creator Java 1,790,
desktop browsers 815, package/runtime checks 43 and narrow browsers 103. There were no failures
or errors. The complete log is `.railix/ui-ux-audit/radar-verify-final-state.log`.

The combined Inspector/HUD production diff against the preceding Branch checkpoint now adds
221 lines and removes 82, a net addition of 139 across four existing browser files. No runtime
dependency, persisted mode, duplicate control owner or second graph model was introduced.
Evidence and review logs are under `.railix/ui-ux-audit/radar-*`. Real Safari/iOS and VoiceOver
remain unverified. Browser checks do not establish Apple certification or exhaustive state coverage.

### Shared Runtime Observation

The 2026-11-03 follow-up removes environment-dependent ordinary dispatch, runtime context and
metric instrumentation. Both generated outputs use direct handler references and one
`RuntimeMetrics` implementation. Example execution and recorded values remain optional.
Normal ingress returns the same complete context, including `runtime.test=false`.

`ApplicationMetricsParityE2eTest` builds both real JARs, calls their ordinary ingress and hosts
the same authenticated metric HTTP handler. It checks exact counters, rejected unauthenticated
reads, invalid queries and closed admission. The no-Example JAR omits Example execution and
tracing, but retains the shared metric classes. Existing installed-bundle, lifecycle, failure,
cancellation, trace and concurrency tests exercise the common implementation.

Review reproduced slow query bodies consuming metric-reader admission: two unfinished requests
made a normal metric read return 503. `MetricsEndpointE2eTest` first failed through real HTTP.
Separate body and reader admission restores 200 responses without removing either budget or
the body deadline. Ordinary convergence remains independent per arrival, without waiting or
context merging, as clarified by the user on this date.

The plain `./mvnw clean verify` passed in 24 minutes 33 seconds in an isolated source snapshot,
protecting existing previews. There were no fork overrides, test exclusions, failures or errors.
The final whitespace-only cleanup was followed by another successful 872-case core run.

| Verification layer | Discovered | Passed | Skipped |
| --- | --- | --- | --- |
| Core Java | 872 | 872 | 0 |
| Standard-library Java | 77 | 77 | 0 |
| Creator Java | 1,792 | 1,792 | 0 |
| Desktop browser | 815 | 814 | 1 |
| Package and runtime soak | 43 | 43 | 0 |
| Narrow browser | 103 | 103 | 0 |

The skip is the existing opt-in renderer comparison. The million-call run completed with a
64 MiB heap. The 129-Step baseline measured 68,199 ns per invocation and 87,784 allocated bytes
per invocation. Browser tests exercised the saved 6,003-node fixture at 1280 and 320 pixels.
Its overview is collapsed, not 6,003 simultaneously rendered stations. No UI redesign was made.

An additional serial comparison built the same two-Step CLI project from the preceding commit
`765f6baa1836c89755fcb34447124316510e1ce8` and from this change. Each artifact ran three separate
JVMs with a 64 MiB heap, 100,000 warmup calls and 15 batches of 20,000 measured real-ingress calls.
The table reports the median of each JVM's batch median. No browser tests ran concurrently.

| Artifact | Wall ns/call | Calling-thread CPU ns/call | Calling-thread allocated bytes/call |
| --- | --- | --- | --- |
| Previous ordinary artifact, without metrics | 995.9 | 970.2 | 4,304 |
| Shared runtime, without Examples | 1,058.6 | 1,035.7 | 4,440 |
| Shared runtime, with Example capability, ordinary ingress | 1,005.3 | 982.6 | 4,440 |

The no-Example artifact costs about 63 ns and 136 bytes more than the previous unobserved
artifact in this small scenario. This includes all changed execution/context behavior, not an
isolated measurement of metric recording. The two new artifacts allocate identically here.
The short timing comparison is not proof of equal latency for every project or a throughput
certificate. Capturing Example values and external I/O were not timed by this comparison.

The Java production diff adds 1,517 lines and removes 1,347, net +170. Java tests add 254 and
remove 75, net +179. Most metric and HTTP code was moved, not eliminated. The removed maintenance
obligations are separate ordinary call tables/trace wrappers and environment-specific metric
generation. No runtime dependency, second interpreter or legacy metric alias was added.

Evidence is under ignored `.railix/runtime-parity/`: `clean-final.log`, `core-final.log`,
`slow-body-before.log`, `slow-body-after.log`, benchmark sources and `benchmark-results.jsonl`.
The snapshot retains JUnit reports and screenshots. Task-owned helper processes were stopped.
Automatic standalone listener/discovery, final explicit build options, Creator assertions and
real-traffic tracing remain open work. The HTTP probe does not prove automatic Creator attachment.

### Stateless Creator Assertions

The subsequent 2026-11-03 increment adds authenticated Creator checks over a selected ordinary
Step's recorded context. Equality is structural and numerically exact. Absence is not JSON null.
Checking retained evidence does not execute a Step, write project files or change Step counters.
Installed third-party Steps use the same boundary. A later failing Step does not invalidate a
completed earlier snapshot or make the application itself stopped.

Review reproduced truncated recording being mistaken for an unvisited Step. Example summaries
now identify complete recording, and Step projections expose their visit count. Incomplete evidence
is unavailable, and repeated visits are rejected as ambiguous until their selection policy is
agreed. Checks share the existing response admission, a single 30-second deadline and bounded
request/result sizes. Source revision, artifact and application PID guard evidence freshness.

`./mvnw test` passed all 2,774 Java cases: 872 core, 77 standard-library and 1,825 Creator cases.
The Creator count includes 33 new public-HTTP assertion cases. A separate reactor invocation
compiled the current sources and ran Data Workbench, Branch and Inspector browser journeys with
both Failsafe executions and their verification goals. All 90 desktop and 28 narrow cases passed.
No exclusions or fork overrides were added. These runs followed, and do not replace, the preceding
full clean gate. The full clean gate has not been repeated after this assertion increment.

Relative to that checkpoint, Java production code adds 272 lines and removes six, net +266.
The regression suite adds 292 lines. This is additional validation behavior, not a code-reduction
claim. It reuses existing evidence, authentication, admission and lifecycle owners rather than
introducing an assertion runtime in generated applications.

Evidence is under ignored `.railix/assertions-local/`: `assertions-before.log`,
`incomplete-before.log`, `all-java.log` and `browser-regression.log`. There is no new dependency,
background evaluator, retained assertion-result cache or second Step execution path. Test-owned
JVMs and browser helpers have exited. Existing preview processes were left alone.

No assertion UI or persistence migration is delivered by this increment. Saved expectations,
stable Example identity, suite validation, standalone listener/discovery and the final build-option
surface remain open. The user selected local observation enabled by default with a build-time
opt-out. That decision is recorded, not yet an implemented connection.

### Separate Example Storage

The next 2026-11-03 increment moves authored cases and optional expectations together into
`railix.examples.json`. Creator imports embedded cases once, preserves their IDs and allocates
opaque IDs for new cases. The Flow file no longer stores them. The existing compiler consumes a
transient combined input without expectations. Installed and built-in Triggers still use the same
definition contracts. Saved-check edits do not rebuild the application or enter its JAR resources.

Independent review and public tests exposed lost expectations during Undo/rollback, an orphan
Example file being overwritten by a new empty project, ambiguous recovery and stale positional
IDs in the selector. The existing journal now records before/planned-after documents and owns
the three-file transaction. History restores affected checks. Recovery and pre-write checks
preserve observed outside edits. A successful real source activation followed by an outside
metadata edit now rejects the conflicting editor action and restores the accepted Flow/artifact.
One-file expectation edits retain atomic replacement and content-hash conflicts without creating
another transaction manager. A no-op preserves file bytes, revision and Undo state.

The plain `./mvnw test` passed all 2,788 cases: 872 core, 77 standard-library and 1,839 Creator.
After the final journal guard/refinement, another same-reactor run passed 14 storage cases,
18 complete-editor cases, 13 installed-source lifecycle cases and the core/standard-library
prerequisites. The new concurrent-write case verifies exactly one accepted save and one conflict.
The final saved-case/selection browser journey passed again at 1280 and 320 pixels.

The broader Data Workbench, Branch and Inspector run passed all 90 desktop and 29 narrow cases.
The saved 6,003-node world then passed both viewport runs with 60 measured navigation requests
each. Request p95 was 1.905 ms at 1280 pixels and 1.838 ms at 320 pixels, excluding input debounce.
The world uses collapsed and culled representations, not 6,003 simultaneous glyphs. The short
post-GC series grew by about 244/263 KiB and does not establish leak freedom or GPU utilization.
No new graphics family was introduced. Screenshots include `example-document-1280.png`,
`example-document-320.png` and the two `world-6003-*` series in the snapshot's ignored screenshots.

Relative to the stateless-assertion checkpoint, Java production adds 632 lines and removes 119,
net +513. Java tests add 417 and remove 36, net +381. Browser production adds four lines and
removes three. This is additional storage and recovery behavior, not a code-reduction claim.
It replaces duplicated raw project read/write helpers and extends the existing journal/history
rather than retaining a second writable Example source or another execution engine.

Evidence is under ignored `.railix/assertions-local/`: `example-files-complete-java.log`,
`example-owner-final.log`, `example-browser-final-state.log`, `example-large-world-final.log`
and the independent review. All test-owned JVMs, browser drivers and review helpers exited.
Existing previews were left alone. The sources match the tested isolated snapshot.

The earlier full clean gate predates this storage increment and has not been repeated for it.
No publication or merge readiness is claimed. Saved-check API/storage is complete for this slice;
the JSON-field authoring UI, whole-suite validation/release policy, standalone observation
connection and final build-option surface remain separate open work.

### Project Documents In Application JARs

The 2026-11-03 source-portability increment adds `build --include-project` and
`creator --application`. Both embedded and local documents use the existing project formats,
compiler and installed bundle contracts. No reduced graph description or persisted scene is added.
The source revision belongs in the standard JAR manifest. Source inclusion does not enable
Example execution or application-side assertions in the standalone JAR.

Public CLI and artifact tests verify original document bytes, deterministic separate build keys,
byte-identical executable classes with/without source, real JAR execution, and Creator opening.
They cover missing/mismatched projects, reserved dependency entries, malformed archives, UTF-8,
tampered inflated sizes, actual JAR signature failures and the agreed 64 MiB document limit.
Installed bundles work in both variants. Missing artifacts reject restoration without publishing
a workspace. Captured lock bytes remain paired with compiled code after a later disk replacement.
A controlled installed validator proves that a source change during compilation rejects publication.
Expected compilation and stale-input rejections retain `CompileResult.Rejected` rather than
throwing exceptions only to print their messages.

The broad `./mvnw package` checkpoint passed 2,817 Java cases: 872 core, 77 standard-library and
1,868 Creator, and built the native Creator image. After the final capture/result refinements,
a same-reactor targeted package run passed all 139 selected/prerequisite cases. Five native
launcher cases passed, including both source variants with system Java removed from PATH and
CLI argument/missing-project behavior. Four Chrome journeys passed at 1280 and 320 pixels with the
saved 155-Step project, three Examples and four linked audit uses. Original data, group selection
and recorded group field changes remained available in both source variants.

The sample's three documents total 51,375 bytes. Its JAR is 332,845 bytes without source and
337,270 bytes with source, a 4,425-byte compressed increase. The 64 MiB cap is a safety bound,
not a target size. No source capture, metric history, rendered topology or default expansion is
saved as another project document. One existing snapshot test no longer requires a request to
stay unfinished until a later status poll. Its saturation, frozen old status and fresh completed
projection checks remain intact.

Relative to the separate-storage checkpoint, Java production adds 335 lines and removes 50,
net +285. Tests add 547 and remove 14, net +533. This is feature growth, not a code-reduction claim.
Captured originals replace the competing lock reread as package input, and both catalog entry
forms share the same parser. No new runtime dependency or Step execution path is introduced.

Evidence is under ignored `.railix/project-packaging/`: `package-verified.log`,
`capture-race-red.log`, `source-final-verified.log`, `native-desktop-complete.log`,
`mobile-complete.log` and review outputs. Screenshots are the snapshot's `project-source-*` series.
The complete `clean verify` gate has not been repeated for this increment. Disk-full and cleanup
faults were not injected, and Grype was unavailable. Independent application attachment and the
simultaneous deployed/local comparison remain open, not delivered by this source-loading feature.
The current source files match the tested snapshot. Task-owned test and review processes exited;
the pre-existing previews were left untouched. No commit, push or pull-request update was made.

### Readonly Live Connection And Draft Comparison

The 2026-11-03 attachment increment connects an independently started application through the
same `MetricsEndpoint` used by Creator-owned execution. Ordinary builds enable an authenticated
loopback listener. `build --no-observation` disables only that connection. Normal `build` and
`creator` need no new arguments. Project actions select the running application in the UI.
Project sources remain either the original embedded documents or matching local files. No reduced
graph format, saved comparison, process-control endpoint or new runtime dependency was added.

Public tests launch real application JARs, authenticate observation, read real HTTP ingress metrics,
reject control routes, and verify that closing Creator leaves the observed application alive.
They cover both source variants, stale process registrations, unsafe credential permissions,
CLI exit with observation disabled, staged/cancelled selection, instance-pinned reads and restores,
independent local edits, connected-section restoration and ordinary Undo. Restoration uses the
declared primary outcome, including a route with a secondary terminal exit, not built-in Step IDs.

The saved 156-node branch-heavy application opens through the real connection menu with linked
groups and local Examples preserved in Draft. Live has no Example execution or mutation controls.
Browser tests inspect a removed Step with its old connections and restore it in Draft. Separate
regressions forward a live catalog request, fail its response after server selection, and verify
Draft recovery plus a subsequent selection of the correct live instance. Header-triggered failures
must be visible too. Simultaneous app startup exposed and removed a directory-creation race.

Five earlier clean gates were not accepted as green. Corrections retain
durable Example IDs in authoring checks, remove an old test fixture's competing Example document,
and let the existing poll retry transient scope reads while permanent rejections still require
an explicit retry. Converging routes no longer each claim the common destination's full count.
The third run exposed a desktop-only test action against the intentionally hidden mobile header.
The test now follows the reachable narrow-screen path. A real-HTTP regression also found stale
selected-group metrics. Groups now use the same live metric refresh container as individual Steps.
The existing observation cycle notifies the Inspector after a read and when discarding its
snapshot. Real HTTP requests update both selected groups and Steps. Unchanged facts retain their
DOM. A completed-read shutdown regression verifies that stopped processes leave no stale Live
metric rows while the selected Step and its read-only configuration remain available.
The fourth run found one Authoring test assuming Example values could never be temporarily busy.
It now follows the existing explicit Retry control before checking that a Step cannot offer its
own returned field as an input. The projection remains read-only and does not rerun the Example.
The user subsequently approved one automatic retry for transient recorded-value failures. Summary,
Step and section reads now use the existing visible-page poll for that one retry. A second failure
waits for Retry. Permanent failures do not retry automatically and no new timer was added.
The fifth run found a Settings ordering test polling fresh reads while waiting for its write.
It now observes the actual successful POST and its edited value before releasing newer held
snapshots. Final saved preferences and the untouched volume are still checked through the public
API. All four response-order cases pass in the focused follow-up. Product Settings behavior did
not change.

The sixth exact `./mvnw clean verify` passed at 21:57 on 2026-11-03, before the subsequently approved
explicit-local-start and one-retry changes. It executed 3,802 passing tests and skipped only the
optional renderer comparison benchmark. That checkpoint does not verify the subsequent changes.

The user also approved pausing only the owned local application when a Live connection is confirmed.
A compiled build holds a separate lease without running a JVM. Draft edits still compile and persist.
`Start locally` uses the existing start/activate lifecycle and leaves revisions and history unchanged.
Public tests cover an occupied trigger port, failed start retaining the ready build, successful
explicit start, completed local Examples and idempotent reconnection. The independently running app
remains available throughout. Normal Creator startup without a Live connection still auto-starts.
The follow-up review found an unused-build leak after a failed paused edit. A real 512-Step build
with an outside source change reproduces the retained folder. The prepared candidate now takes
ownership before the publication lease is released, so ordinary discard removes the failed build.
Recovery preserves the outside file and the prior ready build. All ten Live API scenarios and the
focused six desktop/three narrow browser cases pass. The independent read-only follow-up reported
no further actionable findings. These focused checks do not replace the final clean gate.
The subsequent gate exposed an obsolete test that rejected all application POSTs and a discovery
test that could read the temporary registration file before atomic publication. The method check
now uses PUT, while invalid start POSTs remain rejected. Discovery waits through the public
connection reader and inspects only its published record. No production discovery policy changed.
The complete browser pass then found one more old zero-retry assertion. Its actual two reads match
the approved initial read plus one retry. The updated assertion passes through the real browser.
An additional public `CreatorServer.start` probe with a real occupied loopback application port
reproduces an initial-start limitation: Creator closes before offering Live selection. Whether to
open a ready draft after an initial activation failure is awaiting the user's decision. It is not
silently folded into the confirmed-connection pause policy.

Final verification of the approved additions passed on 2026-11-03 at 23:33 CET. The exact
`./mvnw clean verify` finished in 26:02 with 3,805 passing tests and one skipped optional renderer
comparison benchmark. Counts are 872 core, 77 standard-library, 1,880 Creator unit/API, 823 desktop
browser (including that skip), 45 package/soak and 109 narrow browser cases. The earlier zero-retry
assertion was corrected, not suppressed. The final source tree matched the isolated snapshot.
The aggregate report has 92.70% line and 84.26% branch coverage, below the non-gating 95%/90% goals.
Remaining observation gaps include malformed discovery variants, filesystem/lease cleanup failures
and unsupported protection paths. Ready-owner read fallbacks are also unexercised because public
routes reject non-running owners before dispatch. Grype was unavailable and raw-header saturation
was not fuzzed. These checks do not establish full release or security certification.
Task-owned build, browser, probe and review processes have ended. Existing unrelated previews were
preserved. Evidence is retained under ignored `.railix/live-connection/`, including failure
reproductions, review outputs, screenshots and the final `clean-verify-bounded-retry.log`.
Screenshots are the snapshot's `live-workspace-*`, `live-comparison-*` and `live-draft-ready-*` series.
The initial-start fallback decision remains open as described above. Remote attachment, deployment,
real-traffic payload recording and a full release-readiness claim are outside this increment.

## Step-Defined Application Metrics

The 2026-11-04 increment extends the existing [application metric contract](specs/observation.md#development-observation).
Step definitions declare fixed counters and gauges. The generated application owns their storage
and publishes them through its existing endpoint. Static Step functions obtain prebound handles
in PRE, PROCESS and POST. No Step-owned server, listener, timer or request-derived registration
was added. Unused measurements share no-op handles. A gauge uses one atomic slot, including its
unobserved state, rather than a value plus a separately coordinated flag.

Focused checks pass through authenticated HTTP and actual generated JARs with and without Examples.
`StepMetricsApplicationE2eTest` installs a real external bundle and drives concurrent real ingress.
It verifies independent placements, repeated nested functions, lifecycle counts and disabled
measurements. `StepMetricsE2eTest` verifies 160,000 concurrent increments, overflow saturation,
validation, absent gauges, group aggregation, preserved placement details and exact text exports.
The review found and resolved lost nonaggregatable nested gauges, ambiguous Prometheus family
names and missing placement details in Live. The final read-only review found no actionable issue
in this slice.

Browser checks use the existing Inspector in a saved branch-heavy project with Examples and
linked groups, plus an independently running HTTP app. Both desktop and 320-pixel layouts show
custom measurements. Live values update after real requests and disappear when that instance
exits. Repeated nested gauges remain separate. Screenshots and review logs are retained in ignored
`.railix/live-connection/` evidence, not committed generated artifacts.

Local public-API measurements observed 7.20 ns per enabled counter update and 3.33 ns
for a disabled update, including the context/name lookup. Both measured 0 additional allocated
bytes per update. These are warmed, uncontended measurements, not a concurrency-throughput claim.
The final-source probe used GraalVM CE 25.0.1 without a coverage agent, five warmup blocks and nine
measured blocks of two million updates. It also checks the resulting counter to prevent measuring
discarded work. Its source and output are retained with the ignored evidence.
Three fresh-JVM comparisons of the unchanged CLI-to-lowercase generated app measured median
1.035 microseconds before and 1.036 microseconds after the extension, with 4,440 allocated bytes
per complete invocation in both. Each JVM used five warmup and nine measured 100,000-call blocks.
The observed spread was larger than the timing difference, so this does not establish a speedup.

The additional scale review reproduced two generator-boundary failures. Metric declarations could
bypass the existing per-Step source budget, and count-only array partitioning could emit an
oversized Java initializer. Both regressions fail before the correction. The existing source
budget now includes metric metadata, and constant arrays also partition by content size. A real
installed-bundle JAR with 96 Steps and 100 declarations each now builds and executes successfully.
The first full `./mvnw clean verify` completed in 26:44 with one existing desktop source-selection
case blocked on a disabled Example picker. It did not include the final generator correction.
A deterministic browser regression holds the old application-status GET while selecting another
Step. It fails before the fix. Follow-up cases reproduce the same delay when opening a group or
its Inspector. All selection paths now cancel the old read and request an immediate refresh through
the existing owner. The three cases pass on desktop and narrow layouts, as do both shared-source
selection cases. No new reader, timer or retry policy was added. A second clean gate was stopped
when the group cases exposed that incomplete correction, rather than reported as successful.
Another browser regression reproduces incorrect thousands grouping inside decimal gauge values.
The common number formatter now groups only the integer part and preserves the fraction.
The final focused run passes all four generated-bundle cases, seven desktop browser cases and five
narrow cases, along with the affected core tests. The final read-only follow-up found no actionable
issue in these corrections.

The final exact `./mvnw clean verify` passed on 2026-11-04 at 21:01 CET in 26:46. XML reports
confirm 3,829 passing tests, zero failures/errors and one skipped optional renderer comparison.
Counts are 884 core, 77 standard-library, 1,884 Creator unit/API, 827 desktop browser (including
the skip), 45 package/soak and 113 narrow browser cases. Application code and tests match the
isolated snapshot. Aggregate coverage is 92.73% line and 84.17% branch, below the non-gating
95%/90% goals. Some metric-declaration rejection combinations, observation protection/cleanup and
other existing failure branches remain uncovered. This is not complete release or security certification.

Fresh desktop and narrow screenshots were inspected for both Draft and Live. Task-owned test,
browser, probe and review processes have ended. Existing previews were left alone. The redundant
scale-only snapshot was removed after checking ownership and references. The final build, failing
regressions, review logs, benchmark sources/results and screenshots remain under ignored
`.railix/live-connection/`, including `step-metrics-clean-verify-complete.log` and
`evidence/step-metrics-*`.
HTTP next-free-port selection and the preferred/active-port display remain separate, unimplemented
work under HT-002/009.

## HTTP Port Selection

The following 2026-11-04 increment closes the port-selection gap named above. HTTP definition
version 2 adds a default-enabled `next_free_port` input and two fixed gauges through the existing
Step contract. One server tries actual binds in order on the resolved address. Unknown errors,
invalid addresses, strict mode, interruption and range exhaustion do not continue searching.
No availability-probe race, wrap-around, extra metric server or HTTP-specific compiler/Creator
branch is introduced. The runtime now attributes activation failure to the source's graph Step.
Only the HTTP Step reports its own native bind reason; arbitrary third-party exception messages
remain excluded from generic lifecycle results.

The new real-JAR port cases first failed on missing fallback/measurements. Another regression
reproduced the empty Trigger identity on startup failure. Cases cover both artifact variants,
free and occupied ports, strict mode, OS-selected port zero, an unavailable address, exhaustion
at 65535 and two simultaneously active applications. Real requests and listener release are checked.
An old Live test assumed its preferred port remained free while another test bound it. It now
uses the authenticated active-port measurement rather than guessing. Native unavailable-address
wording differs between Linux and macOS; the failure-output check accepts either JDK wording.
The final focused API/generated-JAR run passes 170 Creator cases plus 11 core and 77 library cases.
Responsive saved-project and readonly Live journeys exercise the generic Inspector and the real
installed socket source's own port gauge on desktop and at 320 pixels (four passing browser cases).
The generic startup report also retains the source's graph Step identity in the Example-enabled
launcher. An additional public regression reproduces and fixes a late completion callback racing
with shutdown cleanup. A completed source cannot replace its final status or throw solely because
cleanup already ended. Ordinary conflicting completion reports are still rejected while active.

Creator activation failures reuse the existing 16 KiB process-output buffer and its existing
bounded drainer join, rather than introducing a log reader or status API. Only authenticated,
origin-checked local mutation responses receive that owned-process diagnostic. Observed remote
output is not read. The first-buffer retention policy can omit a late diagnostic after verbose PRE
output. It is bounded diagnostic evidence, not complete logging or automatic secret redaction.
The final read-only follow-up found no introduced actionable findings. Grype and Gitleaks are
unavailable on the verification host. A local changed-file scan found no credential-pattern matches,
and all 28 added relative documentation links resolve.

The exact final `./mvnw clean verify` passed on 2026-11-04 at 22:16 CET in 26:44. XML reports
confirm 3,850 passing cases, zero failures/errors and one skipped optional renderer comparison.
Counts are 884 core, 77 standard-library, 1,903 Creator unit/API, 828 desktop browser (including
the skip), 45 package/soak and 114 narrow browser cases. The isolated snapshot matches the
application source and tests. Fresh desktop/narrow Draft and Live port screenshots were inspected
and retained under ignored `evidence/http-active-port-*`. Task-owned test/browser/review processes
ended without touching existing previews. Final-source public-API counter measurements observed
7.81 ns/update enabled and 4.55 ns/update disabled, both with zero additional measured allocated
bytes, under the same warmed uncontended protocol as above. These are observations, not a speedup.
Aggregate coverage is 93.01% line and 84.25% branch, below the non-gating 95%/90% goals. Uncovered
rejection/protection and cleanup-failure combinations remain a verification gap. The full log is
retained as `publication-clean-verify.log`; GitHub checks remain separate publication evidence.

Unknown native occupancy message languages fail closed and are not verified. The existing broader
HTTPS/CORS and real-traffic payload-recording work remains outside this increment.

The first GitHub run for `fb087d1` failed one desktop timing-label assertion after the local gate
passed. It measured a fractional relative-position change while the font/camera refresh could
still be settling. No runtime defect was demonstrated by that assertion. The test-only correction
waits for fonts, the public scene refresh and its rendered frames, then reads both rectangles in
one browser evaluation. Every arrow direction must visibly move the station and apply a fresh
scene before comparison. The original 0.1-pixel tolerance is unchanged. Failure diagnostics are
now retained for this case. The four affected desktop cases and the generated-JAR metric/port
checks pass, and the final read-only review found no actionable issue in this correction.
The focused log is retained as `publication-layout-final-focused.log`. The complete local
follow-up is recorded below. A new GitHub check remains required before merging.

The follow-up clean gate completed on 2026-11-05 at 00:05 CET with three real-time audio timeouts,
not a successful result. All three reproduce in isolation. Failure snapshots show visible pages
with running audio contexts still at 0.00533 seconds after the 30-second waits. Offline synthesis
passes. Headless browser tests now use Chromium's `--disable-audio-output` switch to isolate the
physical output device while preserving real Web Audio rendering, scheduling and autoplay rules.
Chromium selects an [output-only substitute](https://github.com/chromium/chromium/blob/main/media/audio/audio_manager_base.cc)
whose [worker requests real audio buffers](https://github.com/chromium/chromium/blob/main/media/audio/fake_audio_output_stream.cc).
No player code, score, timeout or expected value was weakened. The volume case additionally
requires the audio clock to advance before checking its controls. All three cases then pass.
Audio clock/state/gain details and traces are retained on failure. These automated checks do not
verify physical speakers or audible device recovery. Reproduction and corrected logs are
`publication-audio-reproduction.log` and `publication-audio-isolated.log`.

The corrected exact `./mvnw clean verify` passed on 2026-11-05 at 00:37 CET in 25:55. XML reports
confirm 3,850 passed, zero failures/errors and one skipped optional renderer comparison, with the
same suite counts as above. Source and tests match the isolated snapshot. Aggregate coverage is
93.04% line and 84.25% branch, still below the non-gating goals. The final follow-up review found
no actionable issue. Task-owned test, browser and review processes have ended. Fresh desktop and
narrow port screenshots are retained in ignored `evidence/publication-followup/`; the full log is
`publication-followup-final-clean-verify.log`. No product code changed in this follow-up.
The initial published `fb087d1` GitHub check failed. The local gate does not replace the required
green GitHub check on the corrected commit before merging.

### Portable Provider Packaging

Scope: SYS-032 to SYS-037. Distribution defaults and local providers now use the same
manifest, immutable artifact store, lock resolution and application packager. App definitions
are provider-owned structural metadata. No provider checkout or Creator is required to run the
produced application. Provider acquisition UI, Git fetching and Native Image builds are not
certified by this increment.

The original default application failed `jdeps --print-module-deps` because complete bundles
copied definition factories that referenced intentionally omitted build APIs. Public-JAR
regressions reproduced the leak, unrelated nest-member retention, mixed-enum retention and
missing active service providers before their corrections. Packaging now selects runtime classes
and static methods from the declared lifecycle, called helpers and explicit dynamic roots.
It preserves state and initialization rather than silently discarding their side effects.
Remaining authoring dependencies are explicit build rejections. Class hierarchy analysis reads
verified bytecode and actual JDK modules, never Creator's application class loader.

`ProviderPortabilityE2eTest` covers standalone standard-provider compilation against the Core SDK,
execution after removing that checkout, mixed factory/PROCESS classes, nested private access,
pattern switches, inherited helpers, implicit enum entrypoints, serializable lambdas, declared
reflection, generic/annotation metadata, resources and real service loading. It checks deterministic
rebuilt JARs, matching development/production provider sets and ordinary jdeps without missing-dependency
suppression. The SDK proof supports both a classes-directory CodeSource during `test` and the
packaged Core JAR during `verify`.

The 1,024-helper mixed-provider regression verifies the static call-graph closure without a full
class transform per helper. Its build-time samples are advisory, not a fixed performance gate.
Optional service descriptors are retained without forcing integrations whose service interface
is absent, following the JDK's [on-demand service discovery](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html).
The same rule applies to provider and library JARs. A malformed runtime helper or missing active
provider rejects the build; the Creator's 422 regression retains the prior application's PID.
Further regressions reproduced HTTP 500 for a valid header with a broken class identity and for
an invalid PROCESS opcode. Lazy class-model reads now share the typed rejection boundary.
All three malformed-class cases return `STEP_RUNTIME_CLASS_INVALID` with the class name and
preserve the prior application's PID. No broad runtime-exception or fatal-error catch was added.

Five fresh JVMs per version were interleaved on the same host and JDK 25.0.1. Both versions built
and executed the same lowercase CLI project with observation enabled. The warmed flow probe used
five warmup and nine measured rounds of 100,000 calls per JVM. Values below are medians of the
five per-JVM medians. CLI process duration includes JVM startup, one real invocation and shutdown.
The memory column is the maximum RSS reported by the operating system for that process, not heap.
Cold CLI runs use `-Xms32m -Xmx128m`, warmed flows use `-Xms64m -Xmx256m`, and both use Serial GC.
The CLI build comparison reuses each variant's existing artifact cache. It measures startup,
dependency analysis and cache validation, not a fresh Java compilation. These final measurements
were repeated after the completed clean gate with no test or browser workers running.

| Measure | Full-bundle baseline | Selected runtime |
| --- | --- | --- |
| Application JAR | 304,385 bytes, 166 classes | 234,411 bytes, 122 classes |
| CLI process duration | 169.92 ms | 161.12 ms |
| Process maximum RSS | 118,685,696 bytes | 119,406,592 bytes |
| Warmed flow latency | 1,010.6 ns | 1,010.3 ns |
| Allocated bytes per flow | 4,440 | 4,440 |
| CLI build/cache check, five interleaved fresh JVMs | 890.82 ms | 930.25 ms |

The artifact is about 23 percent smaller. This does not establish a runtime speedup or memory
reduction. Runtime timings overlap, measured allocation is unchanged, and the build pays about
39 ms for dependency analysis in this small workload. It is not a framework comparison, large-library
build budget or sustained-load certification. No runtime dependency or second executor was added.

The exact `./mvnw clean verify` passed on 2026-11-10 at 22:32 CET in 34:54, using an identical
source snapshot under ignored `.railix/provider-portability/release-check/` to preserve existing
previews. All 247 non-Markdown source files match that snapshot. The normal project defaults
were used, without test exclusions or fork overrides. XML reports record 3,900 passed, zero
failures/errors and one optional renderer-comparison skip:

| Suite | Discovered | Failed / errored | Skipped |
| --- | --- | --- | --- |
| Core | 907 | 0 | 0 |
| Standard library | 77 | 0 | 0 |
| Creator Java | 1,927 | 0 | 0 |
| Desktop browser | 829 | 0 | 1 |
| Packaged artifact and soak | 45 | 0 | 0 |
| 320-pixel browser | 116 | 0 | 0 |

The optional `compareProductionVariantsWith128VisibleStations` case requires the explicit
`railix.renderer.comparison` property. It is not an omitted functional gate. Fresh desktop and
narrow screenshots are retained in the snapshot's `target/screenshots/` directory. The package
tests exercise both `railix.jar` and the macOS app image, and the soak includes one million calls.

The review/fix loop also corrected test synchronization problems without changing product
behavior, timeouts or tolerances: wait for the held import response, split the long Branch
journey while preserving earlier-edit Undo checks, and settle the Inspector resize before
comparing camera queries. The persistent Example chooser is now captured through a waiting
locator rather than a nullable one-shot query. All original identity, readability and touch-size
assertions remain. Independent reviews found no remaining actionable functional findings.
An earlier host-sleep interruption was stopped and is not counted as a successful run. Temporary
idle prevention was process-scoped; thermal and closed-lid protection were not disabled.

**Coverage measurement limitation:** raw aggregate coverage is 91.06% line and 82.80% branch,
below the non-gating 95%/90% goals. Runtime pruning changes provider class bytes and therefore
their [JaCoCo class identifiers](https://www.jacoco.org/jacoco/trunk/doc/classids.html).
The existing report analyzes original provider classes, so execution of transformed versions
cannot be matched and produces warnings and false zeroes. In particular, the standard-library
31.18%/2.36% figures are not complete runtime coverage measurements. They must not be compared
with the pre-pruning aggregate or used as evidence that those functions were not exercised.
The packaged functional regressions remain valid, but coverage reporting for matching emitted
variants is a remaining tooling task. Production packaging is not bypassed or changed for tests.
Creator/Core coverage is still partial. Unexercised packaging paths include filesystem races or
storage failures, unavailable SHA-256 handling, and defensive class-file transformation catches.
No full-coverage claim is made.

Raw regressions, review outputs, performance probes and results are retained under ignored
`.railix/provider-portability/`. Task-owned Maven, browser, benchmark and review processes have
ended. Grype is unavailable on this host. Local checks do not establish public CI or release
evidence. Public CI must validate the published commit before merging. No production,
cross-platform or Native Image certification follows from these local checks.

## Fixed-Peer Mesh Membership

Recorded on 2026-11-21 for two independent JVMs on fixed loopback endpoints.

- `RailixMeshPackageIT` checked mesh class inclusion, inactive startup, invalid configuration
  rejection, and two authenticated replicas. It checked that Creator reads the selected replica's
  peer and that different logical builds reject the join without publishing peer state.
- `PeerTlsTest` exercised mutual TLS, credential rejection, the join timeout, and build rejection
  before the first state exchange. `PeerFramesTest` covered frame lengths, byte bounds, and partial
  reads. `PeerChannelTest` covered malformed frames, whole-frame writes, and socket closure after
  failure.
- The packaged CLI built and ran a mesh-capable command-line project with network access restricted.
  The Creator browser check showed peer readiness and build rejection.

The [mesh acceptance scenarios](specs/mesh.md#acceptance) cover the remaining discovery, remote
Flow, and cross-platform proofs.

## Configured Flow Offload Pilot

Recorded on 2026-11-24 with a packaged CLI and two independent JVMs on loopback. Both replicas
selected the same HTTP Flow and had one execution slot each. The existing HTTP Client Step
called a shared test service so the test could hold each Flow in progress.

- The first request used the local slot. A second request to the same HTTP endpoint reached
  the service through the peer. While both requests were held, a third received HTTP 503.
  Both admitted requests returned their original payloads.
- Stopping the peer after its call reached the service returned `MESH_FLOW_UNCERTAIN` to the
  original HTTP caller. The locally admitted request could still finish.
- Different logical builds continued to reject membership before publishing peer state.

All three focused `RailixMeshPackageIT` cases passed. The proof covers configured loopback
replicas and one eligible Flow. See the remaining [acceptance scenarios](specs/mesh.md#acceptance).

Follow-up checks used two membership-only peers ahead of the eligible peer in selection order.
The same eligible Flow still offloaded. The standalone JAR omitted the offload types and
receiver entrypoint. Peer channel tests exercised retained-input refusal, occupied result
buffers, and an expired queued write while keeping the channel open.

The deadline follow-up exercised an active control write timeout and checked the budget on a
queued invocation after the writer became available. Seven packaged mesh cases passed,
including rejection at `hello` when only the packaged mesh classes differed.

The admission follow-up verified that a queued refusal leaves the reader available, a
cancelled worker sends its outcome, and a worker writes an application refusal before
releasing its slot. Five packaged mesh cases passed. The graceful drain case sent SIGTERM
to a replica with an offloaded HTTP Flow in progress and verified that the original caller
received the response and the replica exited.

The cancellation follow-up verified that an active Flow still receives an interrupt and
returns a cancelled outcome with all data buffers occupied. A loopback TLS probe sent a
cancel during a blocked reply, then received the complete reply and finished a second call on
the same connection. Three packaged offload cases passed, including abrupt peer loss and
graceful drain.

The sender follow-up verified that interrupting a queued invocation returns `Cancelled`
and releases the data buffer without writing a frame. In a loopback TLS probe, interrupting
the source during an invocation write left the complete invocation on the stream, followed
by its cancellation frame. An unrelated call on the same connection also completed.

The deadline isolation checks held a Step's interruptible resource close open. A peer read
and another connection's write watchdog both completed during the hold. A channel test
delivered two replies in reverse order and verified the result for each caller. The packaged
retry case used an authenticated peer that declined its admission and verified that a second
eligible replica supplied the original HTTP response.

The per-connection writer checks held one peer's write open while another peer returned a
Flow result. A finished Flow returned its execution slot while drain still waited for its
reply. A connection with both reply reservations occupied refused the next invocation
before execution. The focused unit run passed 111 cases across `PeerChannelTest`,
`PeerFramesTest`, and `RailixJsonComponentTest`. Five packaged `RailixMeshPackageIT`
cases passed over loopback, covering join and observation, eligible offload, retry after
refusal, uncertain completion after peer loss, and graceful drain.

The reply format check sent the same 320 KiB echo through local and offloaded execution.
Both returned HTTP 200 with the original payload. A separate offloaded result that exceeded
the 1 MiB limit returned `MESH_FLOW_RESULT_INVALID`. The generated application checks also
passed for standalone response mapping and missing required Trigger results.

After removing mesh cancellation, channel tests held admitted work past the reply deadline and
across session closure. In each case the work finished without interruption and released its
reply reservation. Five packaged cases passed for eligible offload, retry after explicit refusal,
uncertain completion after execution peer loss, graceful drain, and mesh build mismatch.
