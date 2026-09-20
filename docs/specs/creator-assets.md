# Creator Themes, Settings And Audio

## Human Review

Status: **Existing baseline**, reorganized on 2026-09-19. This move does not
approve new behavior or establish a fresh test pass. Explicitly planned behavior
remains unsupported. The roadmap owns delivery status.

Owns: embedded/default assets, safe user extensions, Settings, sound editing and playback.
These capabilities belong to Creator, not generated applications. Scene behavior belongs
to [Creator](creator.md).

Decisions: [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

## Themes And Renderer Descriptors

The CSS material variables live in `app.css`; individual visible machines expose stable
`data-station-id` and generic `data-kind` attributes. Add local `.css` files under `~/.railix/themes/`,
including subfolders, then select **Settings > Appearance > Theme**. The panel shows the actual folder
when Railix home is overridden. Themes are reread when opening Settings; no rebuild is needed.
The embedded **Railix Foundry** default renders an App headquarters, Trigger portal, routing switch,
processing buildings, recessed Ends and glass-covered groups with three symbolic inner machines.
There are no per-contained-Step decorative objects. **Railix Classic** retains the former icon-plate
machines on the same scene/camera path. Both are selectable and installable as editable theme folders;
`assets/themes/foundry/theme.css` holds Foundry materials, while `web/app.css` remains the shared baseline.
Foundry offers exactly **Renderer Canvas** (default) and **Renderer CSS** under **Settings > Appearance > Renderer**,
each at high quality, without additional quality tiers. CSS keeps individually styleable building faces.
Canvas reuses embedded PNG machines and scenery and draws moving transport natively, at up to two pixels
per screen point on high-density displays. Both retain motion, selection, observations and shared group navigation.
The choices and their names come from the theme descriptor, not a fixed global enum.
**Install editable copy** installs the theme, variants and descriptor,
and preserves every existing file. No local folder contents are required for the embedded defaults.

A custom theme can use this optional layout. Embedded resources under `assets/` mirror the same
relative roots as the user directory; each theme owns its own folder:

```text
~/.railix/themes/studio/theme.css
~/.railix/themes/studio/theme.json
~/.railix/themes/studio/variants/line.css
~/.railix/themes/studio/assets/portal.svg
~/.railix/themes/studio/assets/processor.png
~/.railix/sounds/working.mml
~/.railix/music/quiet/track.mml
```

The optional sibling descriptor names variants and their files, relative to its directory:

```json
{"name":"Studio","defaultVariant":"line","variants":[{"id":"line","name":"Line art","stylesheet":"variants/line.css"}],"files":["assets/portal.svg","assets/processor.png"]}
```

CSS URLs resolve relative to the stylesheet, so `variants/line.css` can use
`url('../assets/portal.svg')`. Local files at embedded paths override only those assets; missing files
fall back to their embedded equivalents. A Canvas variant adds `"renderer":"canvas"` and an `"atlas"`
path to its descriptor. PNG and SVG are source formats, not different renderers: both are decoded and
rasterized once, then cached. No PixiJS or other graphics dependency is required. Packaged-Creator tests add
and edit nested themes, PNG assets and MML sounds/music while the executable is running without system
Java; the running application and functional project are unaffected. User extensions remain external
data, not code that must be compiled into Creator. Open Settings again to refresh the catalogs.
An atlas is version 1 with a `sprites` object. Each entry names a relative `file`, horizontal-strip
`frames`, pixel `scale`, ground anchor `anchorX`/`anchorY`, and animation `period` in milliseconds.
The installed Foundry atlas is the editable example. `step` is the required fallback; existing node
kinds and output symbols select optional specialized sprites. Source images match the theme's fixed
camera; arbitrary CSS cannot restyle their internal pixels. The Inspector uses the same loaded image.
Optional `scenery-ridge`, `scenery-grove` and `scenery-basin` entries replace the corresponding CSS
environment plots. They use the same loader, resource overrides and bitmap budget. Their ground anchor
is the plot's top-left world coordinate (a 307.2 by 230.4 world-unit plot); a static first frame is cached
beneath transport and machines. Missing scenery entries retain CSS, so existing custom atlases still work.
Foundry includes all three scenery images in Renderer Canvas and in its editable installation.
Native foundation colors, selection and cargo use CSS tokens such as `--canvas-wall`,
`--canvas-selection`, `--canvas-error`, `--canvas-tread`, `--canvas-cargo-top`,
`--canvas-cargo-light` and `--canvas-cargo-shade`.
Shape/aspect/roundness overrides still control the common foundation geometry. Atlases are limited to
64 shared sprites, 16 megapixels decoded in total and 32 frames per sprite, not 64 project Steps.
Each of the three viewport drawing surfaces is capped at 8 megapixels; high-resolution drawing adapts
to that surface budget. Broken atlases show a warning and restore CSS instead of leaving an empty world.
Theme assets use a separate read-only, HttpOnly cookie; it cannot authorize project reads or mutations.
Local asset reads retain the existing 1 MiB per-file boundary; external resources are blocked.
Themes can select building geometry with `--world-buildings: 1` (Classic uses `0`); taller custom
buildings must increase the conservative `--world-building-height` culling envelope (112 by default).
The fixed orthographic camera omits invisible solid faces. Preferences are saved globally in
`~/.railix/creator.settings.json`, not in the functional project or per-Step appearance.
Older project `theme` metadata is left untouched but no longer selects the Creator theme. Missing files
show an explicit warning and built-in styling; external CSS resources and symbolic links are rejected.
Custom files must be installed on each machine. Local theme loading requires filesystem support
for secure directory access (the supported macOS/Linux runtime); other providers return an explicit
diagnostic rather than use an unsafe read path. No per-Step CSS text editor is provided.

## Settings And Audio Authoring

**Settings** has Appearance, Sound and Music tabs. The separate sound/music HUD buttons toggle
playback rather than open more settings panels. Effects default on; quiet background music starts
after a browser interaction. Explicit mute preferences remain respected. Close-up working machines
have quiet ambience; selecting a machine gives a state-dependent cue.
Volume controls show 0-100%; music at 100% uses the full mixed signal without an extra attenuation cap.
The default music setting remains 25%, and existing saved levels are retained.
Editable sound files use declarative MML text under `~/.railix/sounds/`, never executable code or binary
audio. Embedded defaults remain available independently of local files.
Music scores under `~/.railix/music/` form a shuffled playlist. The first subfolder is the selectable
music group; root music is Ungrouped. Embedded and local scores coexist, including identical filenames.
Choose all tracks, a group or one track; **Event sounds** assigns effects to individual events.
Choose a local version there to replace an embedded event sound. Eight embedded arrangements combine
percussion, bass figures and melodic phrases across industrial, electro, trance, swing and quieter
styles. Each lasts roughly 3.5 minutes, with sections and developer-humour titles rather than speech
or borrowed samples. The eight text scores total about 26 KiB; musical preference remains subjective.
Settings can create, edit and delete scores. **Install defaults** writes
editable copies without replacing existing files. Reopening Settings refreshes the catalog without
discarding an unfinished editor draft. Content revisions and a shared filesystem lock reject stale
saves from another Creator or external edits observed before saving. A conflict keeps the draft;
refresh the library and reselect the file to load its latest content before editing again.
External editors do not share Creator's lock, so edits racing the filesystem replacement itself
cannot be coordinated. Malformed files remain selectable for repair. Embedded scores
are read-only originals; edit and save a local MML copy. Existing version-1 JSON scores remain readable
and are not rewritten merely by opening the editor. Old `sounds/music/` files remain readable;
new writes and installs use the separate `music/` root. MML overrides win over legacy JSON sources;
deleting an override can reveal the preserved legacy source again.
Play/Pause retains the same composition. Hidden tabs suspend the music clock and scheduler,
then resume the same track and position when visible; manual pause and mute remain respected.
Effects and editor previews release their resources when hidden. Stop or closing the page releases music too.
Next and automatic completion advance the shuffled playlist. Instrument voices are reused across
notes, and no per-Step sound source is retained. Audio is solely a Creator capability.
Creating a missing music group uses a secure directory move. Filesystems that cannot move the
temporary directory atomically report an error; create the group folder locally in that case.

Each score has a name, tempo (40-240 BPM) and 1-8 parallel instrument lines. Before `|` are instrument,
volume, attack and release; after it are notes `a`-`g`, rests `r`, octave `o`, default length `l`,
chords in brackets and repeats `/: ... :/N`. An explicit `@` duration is measured in beats.
MML files are limited to 64 KiB; repeats use 1-16 passes and at most three nesting levels.
Repeated notes are not expanded into an array or limited to 256 events. Playback schedules note
onsets within a rolling two-second window, sharing the same clock across instruments. Pause freezes
that clock; Stop cancels scheduling and releases voices. Legacy version-1 JSON retains its 256-note bound.
Instruments are `sine`, `square`, `sawtooth`, `triangle`, `kick`, `snare` and `hat`.
Optional `decay=.18 sustain=.2 cutoff=1800` before `|` controls tonal decay in seconds,
the held amplitude fraction and filter frequency in Hz. Optional `detune=7 drive=2 pan=-.3 echo=.2`
adds a paired oscillator spread in cents (0-30, pitched instruments only), saturation (0-8),
stereo position (-1 left to 1 right) and two beat-synced echo taps (0-.5).
These effects share reusable voices and per-track routing, not new sources per note.
Scores without these controls keep their previous sound.
Instrument presets are derived from the catalog, including these optional controls.
Percussion decays within the note's duration; longer durations leave silence between hits.
Kick pitch follows the note. Snare and hi-hat use fixed filtered noise, synthesized locally and
shared within each audio context, not loaded from audio files.
For full-length rendered listening checks, run `CreatorEditorBrowserIT#embeddedMusicRendersAudibleUnclippedReviewExcerpts`
with `-Drailix.audio.review.full=true`; otherwise it renders short excerpts. Review WAV/MML files go
under `modules/railix-creator/target/audio-review/`, not into the distributed Creator assets.

```text
name: Arrival
tempo: 120
sine .3 .01 .1 | o4 l8 /: c e g e :/4
triangle .15 .02 .2 | o3 [ceg]@8
kick .4 .002 .15 | o2 /: c@2 :/4
snare .15 .001 .09 | r@1 c@2 c@2 c@2 r@1
```

## Persistence And Loading

Catalog keys distinguish embedded and local scores; event mappings and playlists use those keys
rather than frontend file lists. `GET/POST /api/settings` uses content
revisions, bounded reads, atomic replacement and a nonblocking file lock for competing Creator writers.
Conflicts are explicit, not silent last-writer wins. Settings are read at startup/menu opening, not
polled. Theme, reduced-motion and audio preferences are shared across projects. CSS is loaded as text under
a same-origin page policy; no remote imports/assets, script evaluation, or symlink traversal are allowed.

Local themes with matching IDs replace theme catalog entries, not duplicate them;
this is distinct from embedded/local score keys. An embedded catalog supplies variant names and defaults.
Variant stylesheets are not also listed as standalone themes. Base and variant links are staged together
before replacing the active pair.
Authenticated catalog discovery grants a separate read-only asset cookie, scoped to the asset route;
the existing header token remains required for other APIs and all writes. Discovery occurs on Settings refresh,
not on rendering frames or each asset request; assets use bounded, symlink-free reads. Theme changes refresh the
camera/material projection without restarting the application. Higher decorative faces use browser
surface picking even after pointer capture returns the event to the canvas. The theme declares a
conservative height envelope for culling; no per-machine layout reads are added to camera frames.

`GET /api/sounds` lists scores, diagnostics and a revision; authenticated `POST /api/sounds` saves,
deletes or installs defaults. Revisions hash current local sources. An unsaved browser draft retains
the revision it loaded even when the catalog refreshes.
Pinned directory handles, no-follow reads and atomic replacement avoid path traversal and partial
updates. Default installation uses CREATE_NEW. Directory creation uses pinned parents and has no
symlink-following fallback when an atomic move is unsupported.
Read and delete requests never create directories. Legacy score-to-text conversion is owned once by
the server catalog boundary, shared by installation and editing; the browser only plays validated scores.

## Playback Resource Ownership

MML and legacy JSON parse into one validated score model, not separate synthesizers. Score size,
tracks, repeat depth/count, envelopes and note durations are bounded at ingress. MML repeats
remain a compact tree walked lazily; these audio budgets are not project-size limits.
One scheduler uses the audio clock for music, previews and ambience, queues onsets only two seconds
ahead and refills every 250 ms. A started note retains its full envelope. Late callbacks skip expired
notes rather than producing a burst and resume the remainder of a still-active sustained note without
moving its original end; missed percussion attacks are discarded. A stall beyond the lookahead can
still interrupt audio before the callback recovers. The future event queue is bounded by this window;
catching up after a foreground stall still traverses the elapsed notes.
Pause suspends the clock and refill timer, and Resume restarts
the same cursors. Looping tracks restart on a shared score boundary. Stop cancels the timer.
Echo uses two finite beat-synced delay taps without feedback. Ingress rejects duplicate,
unknown, out-of-range and nonfinite controls. Filters and oscillators are retained per voice;
panning and delays are shared per track, never created per note. Echo routing reserves output
headroom and completion includes its tail. Stop disconnects all voices and routing nodes.
Listening checks render the same validated scores and scheduler using controlled offline-clock
advancement, not a separate audio asset or alternate playback path. Volume preferences remain
0-1 values; full-track render checks include the music master at 100%, not only the raw synth.

## Packaged Extension Boundary

The minimal embedded theme catalog references each theme's descriptor; the sound catalog references
canonical score paths. Preferences and derived caches do not enter the functional project.
The packaged-Creator extension regression starts without system Java, then adds nested CSS/PNG themes
and sound/music MML files, edits them and rereads them through public HTTP routes. The application PID,
functional project and Creator JAR remain unchanged. Project-local asset-root precedence is not added.

## Acceptance Boundaries

Packaged-Creator HTTP tests use isolated homes without ambient Java to discover, install,
edit and reload assets; they prove that project contents and the running application do
not change. Conflict, malformed-input and unsafe-path cases are required alongside success.
Browser/audio checks exercise both renderers, pause/resume/cleanup and the same synthesis
path at full master volume. A finite, unclipped signal is not subjective music acceptance.
