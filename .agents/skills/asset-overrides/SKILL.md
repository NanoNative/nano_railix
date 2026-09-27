---
name: asset-overrides
description: "Implement embedded assets, user extension discovery, theme/sprite catalogs, settings and safe file editors. Not for composition or synthesis."
---

# Embedded Assets And User Overrides

Keep packaged applications self-contained while users can add and edit supported assets without rebuilding them.

## Resolve And Edit

1. Resolve the project's asset roots, formats, precedence and trust boundary from its
   contracts. Extend the existing validated catalog/resolver, not a parallel loader.
   Mirror relative embedded/user paths when the overlay contract calls for it.
2. Keep embedded defaults usable without user files. Follow the declared replacement,
   coexistence and fallback rules; identical filenames do not imply identical identity.
   Do not invent migration or legacy compatibility paths.
3. Derive selectors from descriptors/catalogs, not duplicate frontend lists.
   Keep visual design, renderer capability and quality settings distinct when supported.
4. Install defaults without replacing user edits. Make saves atomic and reject stale
   revisions, including external-editor changes. Preserve containment, no-follow
   file access, content/size validation and authentication/Host/Origin checks.
   Treat user assets as data, not executable code. Enforce the declared remote-loading policy.
5. Stage complete theme changes before replacing working assets. On failure, retain
   working settings and show diagnostics. Release replaced bitmaps, CSS resources
   and preview/audio contexts. Honor which application/artifact owns each asset;
   editor-only resources must not leak into unrelated deployed outputs.

## Prove

Use real storage/API boundaries and packaged-application tests with an isolated user directory.
Cover embedded defaults, nested additions, missing/invalid assets, stale saves,
external edits and non-destructive installation for the changed resource type.
Verify extensions work without recompilation or undeclared development tools, and
that unaffected artifacts stay unchanged. Run the project's relevant publication gate.
Report untested behavior and stop
task-owned previews, audio renders and helper processes.
