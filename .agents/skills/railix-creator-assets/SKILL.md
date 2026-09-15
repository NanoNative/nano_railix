---
name: railix-creator-assets
description: "Implement Railix theme/sprite packaging, asset discovery, Settings and file editors, installation, and safe theme/sound/music overrides. Not for composition or synthesis."
---

# Creator Asset Engineering

Keep Creator self-contained while users can add and edit assets without rebuilding it.

## Resolve And Edit

1. Extend the existing catalog/resolver and use its validated asset model, not a
   parallel loader or format. Embedded assets and Railix-home extensions share these paths:

```text
themes/<theme>/theme.json
themes/<theme>/theme.css
themes/<theme>/variants/<variant>/...
sounds/<effect>.mml
music/<group>/<track>.mml
```

2. Keep defaults embedded and usable with an empty Railix home. Matching theme files
   overlay individual resources; missing files retain defaults. Embedded and local
   scores coexist, including identical filenames; event assignments choose replacements.
   Keep sound/music roots separate; the first music subfolder names a playlist group.
3. Derive selectors from descriptors/catalogs. A theme is visual design; a variant
   supplies a supported renderer, with one high-resolution choice per method.
   Do not duplicate theme/track lists in frontend code.
4. Install defaults without replacing user edits. Make saves atomic and reject stale
   revisions, including external-editor changes. Preserve containment, no-follow
   file access, content/size validation and authentication/Host/Origin checks.
   Do not enable executable assets or remote imports.
5. Stage complete theme changes before replacing working assets. On failure, retain
   working settings and show diagnostics. Release replaced bitmaps, CSS resources
   and preview/audio contexts. Keep these resources out of the functional graph
   and generated application artifact.

## Prove

Use real HTTP, browser and packaged-Creator tests with an isolated Railix home.
Cover embedded defaults, nested additions, missing/invalid assets, stale saves,
external edits and non-destructive installation for the changed resource type.
Verify extensions work without recompilation or system Node and leave the generated
application unchanged. With Java 25 and Chrome available, run `./mvnw clean verify`
from repository root before publishing code. Report untested behavior and stop
task-owned previews, audio renders and helper processes.
