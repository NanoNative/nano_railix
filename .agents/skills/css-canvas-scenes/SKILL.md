---
name: css-canvas-scenes
description: "Build and review CSS 3D geometry, Canvas scenes, game HUDs, animated transport, camera navigation and rendering performance."
---

# Factory UI Engineering

Build a coherent game-like world with usable controls, not a generic dashboard.

## Build

1. Confirm the visible behavior before a redesign; agree on a target image for a new
   visual family. Preserve the theme's materials, palette, typography and spacing.
   Extend the chosen rendering stack rather than introducing a graphics dependency for decoration.
2. When supporting multiple renderers, share one scene, camera, geometry and picking model.
   PNG/SVG are assets, not separate layout engines. Derive footprints, elevation,
   ports and hit regions from common dimensions; do not add per-example scale hacks.
3. Prove one primitive with unequal width, depth and height. Name faces explicitly
   and reset nested dimensions. Allocate only faces visible to the supported camera.
   A clipped plane with a shadow is not an extruded solid.
4. Separate placement, camera and moving-part transforms. Keep HUD and labels
   screen-facing. Apply preserve-3d on each wrapper that needs depth; it is not
   inherited. Put flattening filters, opacity and clipping on decorative leaves.
5. Establish silhouette, top/side shading, contact shadow and restrained glow before
   detail. Reuse CSS properties, gradients and pseudo-elements for decoration;
   meaningful text and controls remain semantic HTML.
6. Derive belt plates, arrows, parcels and junction highlights from one continuous
   path and phase. Paint markings per run, not per tile. Hide endpoints under
   machines; fix geometry rather than hiding seams with arbitrary z-index values.
7. Keep selection, category color, operational state and preview/simulation distinct.
   Apply overrides only to the selected entity. Preview is not live traffic.

## Make The HUD Usable

Remove duplicate actions, repeated metrics, ornamental copy and empty panel wrappers.
Distinguish editable fields from read-only values and status. Show loading, empty,
failed and selected states when relevant, never fake data. Reuse meaningful CSS
symbols with accessible names, visible focus and usable hit areas. Shortcuts must
not consume typing. Popovers must stay usable during interaction. Narrow layouts
must keep controls reachable, not merely stack panels. Game materials and purposeful
animation are intentional; do not replace them with blanket minimalist style rules.

## Keep It Fast And Prove It

Render only the viewport and a bounded margin. Reuse stable identities and static
caches, cancel stale requests, and release replaced images, listeners and surfaces.
Avoid per-frame layout reads, full-scene rebuilding, huge blurred layers and duplicate
animation clocks. Fewer elements alone do not establish lower rendering cost.

Check supported renderers with unequal shapes, continuous motion, junction highlights,
pan/zoom, detail transitions, navigation reset, theme changes, keyboard input and narrow layouts.
Zoom must visibly scale geometry between detail levels. Respect the explicit motion
setting, but do not disable motion to conceal a performance problem.

Use the repository's browser fixtures and supported browsers; run its relevant publication gate.
Compare identical scenes, viewport/DPR and motion state before/after performance work.
Measure frame times, paint/compositing, CPU and memory; graphics-process CPU is not
GPU utilization. A still screenshot cannot prove animation. Report untested cases
and stop task-owned browser/helper processes.
