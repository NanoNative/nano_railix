# Railix II

## What Railix Is

Railix is being built as a product-building system for cross-functional teams. People author an
application as visual **Flows** of **Steps** in Creator; a successful Railix build will compile
that static model into one immutable monolithic application. Java is Railix's current
implementation language, not its product boundary.

Railix is neither diagram-to-code nor a runtime-interpreted workflow engine. Creator groups,
Blueprints, and Templates help people author and reuse patterns, but the compiler sees only
materialized Flows and Steps. It builds one fixed application with no reflection, runtime Step
scanning, hidden coercion, or production graph editing.

Railix calls its end-to-end model **Railway-Oriented Programming**: the application path from an
external trigger through policy, processing, and storage is made from Flows and Steps. Examples are
functional project inputs: the project owns their definitions, and the generated development
application owns their compiled copy, execution, and results. They show the real path and context
changes now; assertions and a later production-build gate remain roadmap work. Test-only behavior
uses real test services or explicit test-client Steps that may be omitted from a production artifact.

The product direction is to use that same executable model for local verification, build,
operations, and eventually distributed operation. External protocols and security still exist;
Railix aims to express and wire them through Flows and Steps, reducing hand-managed infrastructure
configuration rather than pretending it is unnecessary. Platform user authorization and encrypted
environment-secret delivery are a protected boundary: application Flows may enforce product
policy, but cannot grant themselves project, build, deployment, or production-observation access.
The exact planned operating model and its status live in [ROADMAP.md](ROADMAP.md).

## First Accepted Journey

The current accepted journey is deliberately smaller than that direction:

```text
Application -> CLI Trigger -> Lowercase -> End
```

Two files have deliberately different owners:

- `railix.project.json` is the complete flat functional graph used by the compiler and application.
- `railix.creator.json` is optional Creator-only grouping and presentation metadata.

Losing Creator metadata can remove visual groups, names, colors, and icons. It cannot change the
compiled application. Invalid functional edits show diagnostics while the last valid development
application keeps running. Railix uses no reflection, runtime Step scanning, hidden coercion,
fallback graph, Node runtime, or Homebrew package.

## Ownership Boundaries

Railix has one functional model and five deliberately non-overlapping ownership boundaries:

- `StepDefinition` is the reusable declaration a Step developer publishes: identity, graph role,
  generic inputs, context paths, outcomes, result defaults, implementation class, and optional
  catalog presentation or starter Example values. Starter values are copied into a project when a
  Step is added; the definition owns no project Example afterward and no process, HTTP server,
  scheduler, trace, metric state, or Creator behavior.
- `railix-core` owns canonical values, project validation, lowering, generated Java source, and the
  runtime contracts copied into an application artifact. Compilation materializes project Examples
  once into a bounded development manifest. Core does not start an application or execute an
  Example while compiling.
- The generated application artifact owns execution. Its development build reads its embedded
  Example manifest, runs all Examples through the same generated Flow entrypoints, retains bounded
  traces, records metrics, and serves authenticated management endpoints. Its production build
  physically omits the manifest, Example runner, trace/metric code, and management endpoints.
- `railix.project.json` owns authored Flows, Steps, links, inputs, and Examples. It contains no
  running state, trace, metric, PID, build path, or Creator presentation metadata.
- Creator is the project editor and build/lifecycle controller: it persists project/metadata edits,
  displays the catalog, asks core to compile, publishes the
  JAR, replaces the child process, and shows PID/build status. It only relays and displays
  management data exposed by the running application. Creator never invokes a Flow to run an
  Example, schedules Examples, stores Example traces, or supplies a fallback result. No reachable
  management endpoint means no Example or metric display. Contract-declared default paths remain
  available for authoring, but only application responses may provide observed values or results.

`railix.project.json` remains the functional source of truth, including authored Examples.
`railix.creator.json` remains presentation-only. The generated development application embeds the
compiled Example inputs; the running application, not Creator, owns their execution and results.
Creator saves changed Steps, connections, and presentation entries by stable ID. Revision checks
reject stale saves, and acknowledgements do not resend the project. The browser loads only the
selected Step's editing neighborhood, paged Group definitions, and the visible scene. Canonical
graph indexes stay on the Creator server; unloaded Steps do not become browser configuration objects.

See [ROADMAP.md](ROADMAP.md) for exact progress and unsupported scope. [ADR
0020](adr/0020-creator-first-application-graph.md) owns the active graph, metadata, and workflow
context contract.
[ADR 0022](adr/0022-http-ingress-and-step-owned-jdk-modules.md) owns the initial HTTP ingress/client boundary.

## Build And Start

Requirements:

- Java 25
- Google Chrome for browser E2Es
- macOS or Linux for the current host-native Creator image

Use the root Maven project in an IDE; it imports the three production modules without extra setup.
The checked-in wrapper downloads the pinned Maven version on first use. The normal code/test loop is:

```sh
./mvnw test
```

For a focused change, select its public-entrypoint suite without rebuilding the native image:

```sh
./mvnw -pl modules/railix-creator -am -Dtest=RailixValueNullContractTest,PrimitiveStepsCreatorProjectE2eTest,CreatorEditorE2eTest -Dsurefire.failIfNoSpecifiedTests=false test
```

For UI work, replace `CreatorEditorE2eTest` with a browser suite such as `CreatorEditorBrowserIT`.
Keep the two upstream smoke suites: each reactor module requires at least one test. Check that
the selected suite was discovered.
Normal verification still requires every configured suite. For asynchronous UI changes, assert
the visible result with Playwright's retrying assertions; a completed build does not mean its
canvas refresh has finished.

Before opening or merging a pull request, build and verify every generated application, package,
and desktop/mobile browser scenario:

```sh
./mvnw clean verify
```

Maven creates both the executable Creator JAR and the host-native Creator launcher during
`package`:

```sh
./mvnw -pl modules/railix-creator -am package
```

```text
modules/railix-creator/target/railix.jar
modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix  # macOS
modules/railix-creator/target/app-image/railix/bin/railix                # Linux
```

`railix.jar` is Creator itself, not the final environment-specific monolith. Self-contained
Creator output already uses `jlink` and `jpackage`; minimal environment-specific images and
installers for applications generated from a project remain Roadmap Item 8.

Choose the generated launcher for the build host.

macOS:

```sh
RAILIX="$PWD/modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix"
```

Linux:

```sh
RAILIX="$PWD/modules/railix-creator/target/app-image/railix/bin/railix"
```

Start Creator with its default project file and an automatically selected loopback port:

```sh
"$RAILIX" creator
```

Or select both:

```sh
"$RAILIX" creator path/to/railix.project.json 7310
```

Creator prints the URL to open. `Ctrl-C` stops Creator and its owned development-application JVM.
`./mvnw clean` removes the generated JAR and application image. Railix has no third-party Java runtime
libraries or graphics framework. Creator renders its factory with native Canvas2D or HTML/CSS;
Playwright is test-only.

The printed URL contains a random Creator token in its fragment. Every Creator API request requires
that token and the exact loopback `Host`; the browser client supplies both without storing the token
in project files. Treat the complete local URL as a credential while Creator is running.

## Create The First Flow

1. Select the Application, then **Add Trigger** in Inspector Overview.
2. Search the installed Trigger catalog for **CLI**.
3. Select the Trigger to edit Target; use **Edit** beside its Example chooser to edit its payload.
   Use `context.payload.arguments` and `["Hello RAILIX"]` respectively for this flow.
4. Choose **Add next Step**, search for **Lowercase**, and add it as an ordinary graph Step.
5. Select the Step, keep Source at `context.payload.arguments[0]`, and set Target to `context.result`.
6. Wait for **Running** and select the CLI Trigger. Creator shows the Example result automatically.

Each ordinary Step remains one graph node whether or not the user assigns it to a visual group.
Project persistence, structural compilation, development-application replacement, and example
execution are automatic. The built development application starts its own compiled Examples; the
compiler and Creator never execute Step handlers.

## Development Observation

The generated development application runs every compiled named Trigger Example automatically.
An Example begins at the generic Trigger output after protocol conversion, carries
`context.runtime.test=true`, and executes the remaining Flow once through the normal generated
entrypoint. Creator only reads the application's management API. The selected Example highlights
its path and shows the real input/output change at each reached Step; union coverage separately
identifies Steps reached by any Example. Example traces do not contain timing data and never
increment operational metrics.

Development traces are bounded to 4 MiB per event, 64 MiB per Example, and 256 MiB per suite.
The application runs at most 16 Examples per chunk, retains one terminal result or error for every
started trace, waits for every admitted worker to physically exit before admitting another chunk,
and serves ready-to-display summary or selected-Step JSON through at most two concurrent readers.
A timed-out worker has five seconds to exit after interruption. If trusted Step code still refuses
to return, the generated development process exits instead of retaining that worker indefinitely;
Creator reports the application as stopped and never fabricates a result.
The full Example inventory and every projection are bounded to 16 MiB. The Creator server drains
each application response into a transient bounded buffer, closes the application read, and then
sends it to the browser. At most four such response payloads are held concurrently; Creator never
parses, persists, or caches them. For the current application process, the browser caches only the
application-returned Trigger/index-to-ID lookup, selected case, and bounded all-Example projection
for the selected deployed node; it never derives an Example ID or result.
The all-Example projection for one Step has one in-flight owner from its frozen case snapshot through
response transport. A concurrent read returns bounded unavailability without allocating a second
case snapshot. The owner rejects the request before replay when its completed source traces exceed
64 MiB. Exact Step context cannot be reconstructed from compact context changes without replaying
from each Example's initial context.
Runtime metrics use fixed-cardinality counters selected at compile time per graph Step. Counts are
exact, Flow timings are exact, and Step timings sample one execution in 1,024. The development
application serves JSON, Prometheus, and Influx metric formats; Creator displays application, Flow,
and selected Step values without owning those values. Metric JSON and text exports distinguish exact
primitive counter bytes, lookup-index bytes, and their sum from JVM-dependent object storage.
Every metric JSON response carries the owning application PID; Creator and its browser discard a
response when rolling replacement changes the current PID or artifact fingerprint.

Production artifacts physically omit Example tracing, development endpoints, and metrics. They do
not merely disable dormant code. Protocol-specific test clients, assertions, remote attachment,
and environment-selected development capabilities remain roadmap work.

The current generated development application binds an ephemeral loopback management port and
requires its per-process bearer token on every route:

- `GET /v1/examples` returns the bounded full Example inventory and union coverage on demand.
- `GET /v1/examples/status` returns compact suite progress and its revision.
- `GET /v1/examples/coverage` returns the union coverage bitmap for one suite revision.
- `POST /v1/examples/query` returns coverage and optional selected-Example membership counts
  for requested inclusive ranges of canonical project node ordinals, without replaying traces.
- `GET /v1/examples/{id}` returns one Example's current application-owned status.
- `GET /v1/examples/{id}/view` returns one completed Example summary as ready-to-display JSON.
- `GET /v1/examples/{id}/steps/{node}` returns one selected Step projection as ready-to-display JSON.
- `GET /v1/examples/steps/{node}` returns every real Example case plus its initial context and,
  when reached, that selected Step projection as one bounded ready-to-display response. It returns
  `413 EXAMPLE_STEP_REPLAY_TOO_LARGE` before replay when completed source traces exceed 64 MiB.
- `GET /v1/metrics`, `/v1/metrics/application`, and `/v1/metrics/nodes/{id}` stream JSON metrics.
- `GET /v1/metrics/catalog` describes metric IDs, labels, units, kinds, scopes, aggregation and sampling.
- `GET /v1/metrics/prometheus` and `/v1/metrics/influx` stream standard text formats.
- `POST /v1/metrics/query` aggregates requested inclusive ranges of metrics-enabled Step
  ordinals (the order of the full metric export), named flows, an optional application total,
  and an optional process group. `metrics` selects metric IDs; omitted means all, `[]` means none.
  These are read-only queries, not executions or changes to metric settings.
- `POST /v1/run/{trigger}` admits one explicit untraced development invocation.
- `POST /v1/trace/{trigger}` admits one explicit traced development invocation with a caller-owned
  trace ID; `DELETE /v1/traces/{id}` requests its cancellation.

Run and trace request bodies are limited to 1 MiB, must finish within five seconds, and are read
before the separate 32-run or 16-trace admission budget is acquired. At most 48 incomplete bodies
with a valid token can be held. Missing or invalid tokens have an independent four-body budget, so
they cannot consume authenticated admission. Excess or expired incomplete connections are closed
without executing a Flow.

Scoped queries share the authenticated body deadline and their existing two-reader admission.
Each query allows at most 4,096 groups, 1,048,576 range-member visits, and a 1 MiB request/response.
Creator batches larger selections; these are per-read resource budgets, not project-size limits.
For example, the metric query
`{"steps":{"region":[[0,9]]},"flows":{},"application":"app"}` returns two counter groups.
The Example query `{"groups":{"region":[[1,10]]},"example":"command:0"}` returns the same
named group's coverage and, once available, selected membership. These ordinal domains differ:
Example ordinals include every functional node; metric ordinals exclude the App and disabled Steps.
All queries are tied to the captured artifact and PID. Creator discards Example aggregates if their
suite revision changes between batches, and applies one 30-second deadline to the complete poll.
Metric aggregation reads the requested counters; an explicitly requested application total still
scans all flow counters. Example reads snapshot the union bitmap and use immutable reached intervals
per completed case. These observations add no counters or hooks to production execution.

Metric values and presentation are separate. The application owns the catalog and cumulative
measurements; Creator caches the catalog per child application and relays bounded selections.
The Inspector renders available catalog entries by unit, not a metric-name whitelist. Scene
observations carry values under `metrics`, separate from node IDs and Example coverage. Creator
combines query batches using each definition's `sum` or `max`; nonaggregatable values stay separate.
Absent measurements are omitted, not fabricated as zero. Timing definitions identify their sample
counter, so an unsampled duration is displayed as unavailable.

Metric queries include `steps` and `flows` objects, empty when unused. For example,
`{"steps":{"region":[[0,9]]},"flows":{},"metrics":["executions","duration_nanos_max"]}`
returns only those two measurements.
`{"steps":{},"flows":{},"process":"runtime","metrics":["heap_used_bytes"]}`
requests process memory without scanning Steps. Responses identify the application PID and include
UTC `observed_at` plus monotonic `elapsed_nanos`; the frontend derives rates from counter/time deltas.
Concurrent counters are observations, not an atomic transaction across every Step or query batch.
Existing JSON, Prometheus and Influx names and units remain unchanged.

New charts, rates or averages over existing measurements require only frontend changes. A genuinely
new measurement still needs application instrumentation. Custom instrumentation, historical time
windows and environment-specific capability selection remain roadmap work; no formula engine,
metric database, additional dependency or per-request allocation is introduced here.

Automatic Examples do not call the HTTP run or trace routes. The application invokes its generated
Flow entrypoint directly and stores the bounded trace itself. Creator exposes read-only
`/api/examples` descendants plus application and selected-node `/api/metrics` proxies for its
browser; it exposes no `/api/run` or `/api/trace` route.

The [accepted production-observability screenshot](screenshots/roadmap-item-6-1-production-observability.png)
shows the real generated application completing its compiled CLI Example, highlighting the reached
path, returning the transformed context, and serving connected Step and Flow metrics to Creator.

## Project Contract

`railix.project.json` has exactly four root fields: `format`, `id`, `nodes`, and `links`. The
complete runnable contract for the journey above is
[`examples/lowercase-app/railix.project.json`](examples/lowercase-app/railix.project.json). Its
Trigger Example stores a required payload and optional whole context. The Trigger target places
that payload at `context.payload.arguments`; the Lowercase Step reads element `0` through
`receives.value` and writes `context.result` through `returns.value`.

The compiler validates and canonicalizes this flat graph. Link order owns execution; JSON object
key order does not. Creator-generated graph IDs are opaque UUID-backed values created once and
never encode visual order. The mandatory Application keeps the reserved node ID `app`.

The compiler splits plans, routing, Trigger entrypoints, handlers and development tables into
bounded Java methods and classes inside one JAR. There is no fixed total-node or Trigger-count
ceiling. E2Es execute 16,385 ordinary Steps in production and development builds and build 513
Triggers. Small tables stay inline; wide outcome tables are extracted automatically.

Every invocation owns one mutable workflow context. `payload`, `header`, `metadata`, `result`, and
`exit_code` are ordinary keys. Projects may add any JSON-compatible key. Only `context.runtime` is
reserved and supplied read-only by Railix.

The CLI Trigger defaults `context.result` to JSON `null` and `context.exit_code` to `0`. Its named
examples are independent cases. Creator unions their paths and shapes for field suggestions
without merging their values. Run a built CLI project as a one-shot application with:

```sh
(cd examples/lowercase-app && "$RAILIX" run "Hello RAILIX")
```

The CLI Trigger writes the ordered arguments to its declared target path, by default
`context.payload.arguments`. A non-null result is printed as canonical JSON; the numeric exit code
controls process status.

Serve a built HTTP project as a loopback application with:

```sh
(cd examples/auth-http-app && "$RAILIX" serve 8080)
```

`railix serve [port]` builds the project and starts the generated application JAR with Railix's  internal `--railix-http` launcher flag.

```json
{
  "method": "POST",
  "path": "/login",
  "query": "",
  "headers": {
    "content-type": [
      "application/json"
    ]
  },
  "body": {
    "email": "alice@example.com",
    "password": "xxxxxx"
  }
}
```

Flow responses come from ordinary context values mapped through the Trigger response slots:
`body`, `headers` and `status`. JSON request bodies are parsed when the `Content-Type` is JSON;
non-JSON UTF-8 bodies are exposed as strings; empty bodies are JSON `null`. Invalid JSON, invalid
UTF-8 and bodies over the current one-megabyte limit are rejected before workflow execution.

The HTTP Client is an ordinary Step. It takes authored `url`, `method` and `headers`, receives an
optional dynamic `body`, and returns a `response` object containing `status`, `headers` and `body`.
The proxy example in [`examples/auth-proxy-http-app`](examples/auth-proxy-http-app) forwards the
incoming request body to [`examples/auth-http-app`](examples/auth-http-app), demonstrating one
Railix app calling another over HTTP.

## Creator Metadata

`railix.creator.json` is optional and has only `format`, `groups`, and `steps`. A visual group over
the functional Lowercase Step can be represented as:

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

Each ordinary Step belongs to zero or one visual group. A group may be empty and carries optional
Creator-only `name`, `color`, embedded Base64 `icon`, and boundary style. Connected components are
derived from the flat functional links, so the same group can produce multiple disconnected visual
regions without persisting occurrences or geometry. Group Manager edits definitions; Step
Appearance assigns or unassigns membership. Deleting a group only unassigns its Steps.

Creator is a factory-first workshop. Clicking a station or group selects it and opens its Inspector.
Inspector Overview shows the selection type, name and a small building portrait using the same
theme and geometry as the diagram, followed by available observations and construction actions.
Focus and Enter Group sit beside Close; Delete is in the footer. Inputs, Appearance and Groups have
separate tabs. There is no icon chooser; existing embedded icons remain readable.
Closing the Inspector retains selection and drafts.
Click empty floor to deselect a Step or group. Escape dismisses open controls,
then the Inspector, then selection. Dragging keeps selection; the chosen Example and global
application status are independent of it. HUD symbols are CSS geometry on native labeled buttons,
with raised controls, pressed feedback and recessed active tabs/readouts; no icon library is loaded.
The searchable bottom construction tray previews an insertion on hover or keyboard focus without
saving a node or running a handler. Enter Group lives in Inspector Overview, not the global HUD.
Double-click or scroll to zoom into the same diagram, drag to pan, and use Home to return to the App at 50%.
Minus, zoom percentage and Plus stay right-aligned in the status footer; status values scroll separately
on narrow screens. The round Home button sits beside Music in the HUD.
World shortcuts are E (Inspector), F (Focus), Enter (enter group), arrows (pan), +/- (zoom), and
Home (App at 50%). Text fields, composition input, native controls and OS-modified keys retain their normal
behavior. Search results support Up/Down, Enter to choose and Escape to return to search.
Choice comparisons use adjacent field, comparison and operand controls; changing the comparison keeps
compatible operands. Optional calculations and longer comparison chains expand on demand.

Viewport-only recursive semantic zoom avoids a DOM element for every project Step. A fixed,
orthographic camera renders visible machines using CSS geometry or cached images on native Canvas2D.
Neither path needs WebGL, a graphics dependency, a CDN, npm or a Node runtime. These Creator assets
are not included in generated applications. JavaScript still owns camera interaction, bounded scene
requests and real application observations; "CSS rendering" does not mean a non-functional CSS demo.
Collapsed groups and ordinary Steps share compact machine footprints derived from their layout scale,
not their currently visible neighbors. Top-level flows keep their natural extent rather than shrinking
into a fixed canvas beside full-size App and Trigger buildings. Panning cannot resize unchanged machines. A region expands only when
its children are readable. Machine and belt dimensions scale with the camera between detail levels.
Automatic sections group at most eight child sections per level; their repeated outer partition
boxes disappear when deeper sections are entered. These are derived overviews, not saved groups.
Inspector Overview identifies them as automatic and shows their Step count. Selection
uses one cool accent (except warnings/errors); group colors remain on the expanded boundary.
Layered chassis, faceplates
and sockets connect to wide segmented conveyors. Shared ports form continuous T/L junctions;
linear entries share one connection axis, which does not move when a shape changes. Belt patterns
stay continuous across split straight runs; socket overruns lie beneath the casings. Icons use square areas independent of exterior shape.
Names and sampled average times share one screen-facing label outside the body. Visible surfaces are
reused by stable identity and removed when they leave the scene. Transport uses CSS transform animations
while fresh, nonzero traffic is observed. Tread sections, direction chevrons and visible cargo move
together during traffic and replay. Foundry parcels follow whole connections across elbows instead
of restarting on each straight run. Their three visible faces share one camera-projected CSS paint
surface. Ground transport, screen-space parcels and raised buildings now have independent
compositing contexts, using the same theme camera (`--world-camera`). Camera batches move these
cached layers together without rebuilding their geometry. Reused parcel count depends on visible path length, never request count; loops end under
stations or outside the viewport. Classic retains its tiled transport. Long belt surfaces and
parcel paths are limited to the camera vicinity. Compositing traces show lower rendering cost;
a short native CPU sample was also taken with Example replay enabled. Neither provides a thermal
or million-Step capacity guarantee. See ADR 0020 and the roadmap evidence.
A selected Example animates its recorded path in cyan,
without executing it again or changing counters; select **None** at its Trigger to clear replay.
Idle machines have a gentle powered glow; known never-executed machines are dimmed. Missing or
disabled metrics are not treated as zero executions. Hidden pages stop animation.
Application metrics compare matching measurements in App, JVM and System columns. Rows are derived
from catalog identifiers and units, with unavailable values left blank; JVM heap limits are not
reported as system memory. This presentation requires no changes to the generated application.
CSS uses browser animations; Canvas uses one visible-world animation loop and cached static layers.
There is no additional pause/resume control. App-to-Trigger links carry power,
not traffic.
Focus and Home are interruptible camera
movements. **Settings > Reduced motion** disables camera transitions and operational animations;
the browser preference does not override this explicit Creator setting. Camera, station geometry and insertion previews are not persisted.
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
New projects record `created_at` in Creator metadata; existing projects keep an unknown creation date.
Scenery derives from this date (or the existing project ID), with no persisted geometry.
Seeded districts contain service-building rows, power structures and cooling basins in Foundry,
or ridges, spire groves and basins in Classic, on fixed world coordinates; distance removes
detail rather than moving anchors. World-space factory plots and transport clearance keep scenery
placement independent of zoomed group contents and changing labels. Close zoom retains visible relief.
The outlined CSS cursor is confined to the factory; HUD and form controls retain native pointers.
A camera-following minimap uses the existing coarse scene plus visible detail and at most 640 display cells.
Foundry presents it as a round radar with larger machine marks and directional line ends; theme tokens
`--map-aspect` and `--map-range` control its projection and surrounding context. It distinguishes
machine marks from transport, retains selection while travelling, and overlays available errors,
unreached paths, never-executed Steps and logarithmic sampled-time shading. It does not request global
runtime observations: unmeasured areas stay neutral. Blue timing intensity is not proof of a bottleneck;
amber is reserved for pending changes and warnings.
The map stores no layout or unbounded observation history. Enter a group by zooming over it,
double-clicking it or using Inspector **Enter**. Entry starts at the first Step at local **50%** and remains open while zooming
out; the group header's close button restores the enclosing camera and zoom reference. Entry uses the free area outside
the HUD and Inspector. Home leaves all entered groups and returns to the App at 50%.
Small entered sections expose all their Steps; very large sections retain viewport-bounded detail.
Long sequential sections remain forward lanes; only actual branches create additional lanes.
Automatic subregions retain execution order; their content bounds do not enlarge the collapsed machine.
Hovering a Trigger offers its Example selector without changing the current selection. The chooser
stays open while it or its native dropdown owns keyboard focus.

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
Reproducible CSS screenshots are written to `modules/railix-creator/target/screenshots/`
by the browser tests; the older screenshots under `screenshots/` show the preceding renderer.
One diagram shows pending changes, application-owned Example coverage, the selected route and
actual execution metrics. Choose an Example beside the selected Trigger or in its Examples tab;
the adjacent chooser is shown only where it fits without covering machines, labels or the HUD.
The choice remains highlighted while inspecting downstream Steps or application facts.
Moving carriers and belt sections reflect measured counter changes per second. Belt speed
grows logarithmically from sparse to busy traffic; it is not one particle per request or
a measurement of transit time. Motion stops when readings expire, metrics disappear, the page is
hidden. Lamps show current observed traffic or errors, not health or utilization. Regions show the
sum of individually sampled Step averages, not passage latency. Missing samples are not invented;
the tooltip states how many Steps contributed. Background pages are independent of the traffic poll.
Disabled metrics, idle Steps, and absent timing samples remain distinct. Clicking a station opens
its Inspector. Build status opens project/build paths, PID and application facts separately,
without changing selection. Runtime metrics separate App, JVM and System behind a disclosure; the bottom status rail
shows compact application facts. Examples count in the same execution metrics as ordinary inputs;
there is no excluded test counter or view-mode switch. Example replay is a visual trace, not live traffic.
Source/target selectors show the selected Example's actual values beside each field, including
before/after writes. Missing fields and unreached Steps are distinct. Counter updates preserve
unchanged Example values and focused controls in Overview. Flow ends are recessed intakes.
Replay reaches an intake only when a successful Example and a reached source's single exit establish
the route. Ambiguous terminal routes remain unmarked; terminal throughput is not invented.
Step and Group Appearance share rectangle, ellipse, triangle, and diamond shapes, width/height
proportions, and rectangle corner rounding. Defaults are a ratio of 1 and 12% rounding; Reset
removes the override. A ratio of 1 produces a square or circle. These settings
are metadata only and never change the compiled application or layout positions.
Local project files and assembled edits are not limited by the 1 MiB HTTP request budget.
Compilation still materializes the complete project and generated sources. Individual lowered
Step plans retain a 32,768-character bound, and the development Example manifest retains a 4 MiB
bound. Viewport observations use compact application-side queries, not full metric snapshots or
selected-trace replay. Scene detail coarsens when necessary to keep complete grouped connections
within the drawing budget; no route traversal cutoff removes connections. Removing the old
node ceiling is not a claim that million-Step application compilation is supported. See
[checkpoint 5.3](ROADMAP.md#5-flow-control-groups-and-flat-compilation) for verification evidence.
Camera position, zoom level, automatic regions, and group bounds are never persisted.
Legacy format-1 metadata is validated and
rewritten to canonical format 2; invalid legacy source remains untouched for recovery.

The compiler and built application never read this file. Missing or invalid metadata opens the
functional graph flat, preserves the invalid file for recovery, and reports a targeted Creator
diagnostic. There is no persisted `members`, `instances`, occurrence, logical slot, parent,
geometry, camera, `global`, live blueprint link, or compiler group expansion.

## Field Manipulation

Field Manipulation is an ordinary `STEP`, not a special kind. It selects one destination `field`.
Its ordered `value` candidates may use the current field, another field, or a literal. Candidate
conditions define project-specific missing, empty, and mismatch rules; Railix does not
reserve universal truthiness for `null`, empty containers, `false`, or zero.

The first present candidate accepted by its condition supplies the value. Compatible unary
Steps then run in listed order before the value is written. If no candidate resolves or a unary
Step returns a non-primary outcome, the destination stays unchanged and Field Manipulation
continues through `next`.

The definition uses only the generic recursive input grammar available to third parties:

```java
.input("field", Input.path(READ_WRITE).defaultPath("context", "payload"))
.input("value", Input.candidates(
        Input.option("current").fromParent("field"),
        Input.option("literal")
                .input("literal", Input.json(ValueShape.ANY)
                        .defaultValue(RailixValue.nullValue()))
                .fromOwned("literal"),
        Input.option("field")
                .input("source", Input.path(READ)
                        .defaultPath("context", "payload"))
                .fromOwned("source")
).defaultCandidate("current"))
.input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
.run(ChangeField.class);
```

Its implementation uses only the declared input names:

```java
public final class ChangeField implements StepHandler {
    public ChangeField() {
    }

    @Override
    public StepResult run(final StepInput input) throws InterruptedException {
        return input.run("steps").writeWhenPresent("field");
    }
}
```

`JSON`, `PATH`, `OPTIONS`, `CANDIDATES`, Boolean `MATCHER_GROUPS`, and ordered `STEPS` are generic
inputs. A parent or owned option source is explicit. Input names and unary Step port names are not
reserved.

## Filter

Filter is an ordinary `STEP` built from the same `CANDIDATES` contract. Ordered field or literal
conditions may transform their source once and require independent BOOLEAN matcher programs. The
first accepted condition follows `match`; no accepted condition follows `otherwise`. Creator
persists and displays both links, and the rolling-built application executes the selected route.
There is no Filter-specific compiler or UI input implementation.

## Choice

Choice is another ordinary `STEP`. Its `MATCHER_GROUPS` input is an ordered list of OR groups; each
non-empty group is an ordered list of AND matchers. A matcher selects a field or literal and may run
shared unary `transforms` once, then require every independent unary program in `all` to finish
BOOLEAN and pass. Missing fields are false without running the condition; JSON `null`, false, zero,
empty strings, and empty containers remain present.

Groups and matchers short-circuit in authored order. An empty outer list follows `otherwise`; an
empty inner group is rejected during compilation. Runtime failures, rejections, cancellation, and
interruption are propagated rather than converted to false. Creator renders the generic input,
persists explicit `match` / `otherwise` links, and obtains matcher-stage and final Boolean previews
only from the rolling-built application. Neither compiler nor Creator branches on `railix.choice`.

Creator displays shared transforms separately from independent AND matcher programs. It derives
both lists from ordinary unary Step receive and return contracts; there is no matcher kind or Step-ID
allowlist. Step developers can add catalog-only search aliases with `.searchTerms(...)`; `eq`,
`neq`/`ne`, `gt`, `gte`/`ge`, `lt`, and `lte`/`le` resolve the built-in equality and exact-number
matchers. Built-in matchers start with total defaults: JSON `null` for equality, zero for number
comparisons, and an empty string for literal text boundaries. Regex is not in the standard catalog
until a bounded engine is accepted.

## Switch

Switch is an ordinary `STEP`. Its generic `CANDIDATES` input is marked for authored outcomes, so
each configured case owns one stable safe route ID in `railix.project.json`. Cases resolve and
short-circuit in order: the first present candidate accepted by its condition selects its route;
no accepted candidate selects the primary `otherwise` route.

Compiler, runtime, and Creator derive this capability from the Step contract rather than the
`railix.switch` ID. Optional human labels live under the Step's `outcomes` presentation in
`railix.creator.json` and never enter compilation. Generated applications use the concrete route
table of each node and keep the selected outcome invocation-local. Authored outcomes are currently
top-level graph routes; nested programs reject such Steps explicitly because they have no graph
destinations.

## Develop A Unary Step

"Primitive" is a compact Creator presentation and product role, not a `StepDefinition.Kind`.
A small value operation is an ordinary `STEP` with one receive, one return, and an explicitly named
primary outcome when `next` is not suitable:

```java
public final class Lowercase implements StepHandler {
    public Lowercase() {
    }

    @Override
    public StepResult run(final StepInput input) {
        return StepResult.outcome("ok").output(
                "value",
                RailixValue.string(input.string("value").toLowerCase(Locale.ROOT))
        );
    }
}

StepDefinition.named("text.lowercase", "1")
        .primaryOutcome("ok")
        .receive("value", ValueShape.STRING)
        .returns("value", ValueShape.STRING)
        .run(Lowercase.class);
```

Register definitions explicitly in a `StepCatalog`; Railix never discovers classes through
reflection. The default outcomes are App `start`, Trigger `next`, and ordinary Step `next`.
`.primaryOutcome("ok")` deliberately replaces `next` for the unary operation above. Additional
`.outcome(...)` calls declare explicit non-primary results such as `invalid` or `empty`.
Optional `.displayName(...)` and `.searchTerms(...)` values affect only Creator presentation and
search; compiler and runtime behavior remains entirely in the functional contract.

[ADR 0021](adr/0021-total-and-fallible-primitives.md) owns the finite built-in unary catalog and
its exact semantics. Immutable locked Step bundles are implemented; repository acquisition and
the reusable Step template remain roadmap Item 4.

## Modules

- `railix-core`: canonical values, Step contracts, project validation, Java application generation,
  generated-application runtime contracts, stateless workflow execution, and the development
  capabilities packaged only into development artifacts.
- `railix-stdlib`: App, CLI Trigger, HTTP Trigger, HTTP Client, Field Manipulation, Filter, Choice,
  Switch, and built-in total or explicitly fallible unary Steps.
- `railix-creator`: Creator HTTP/UI, project build and rolling child-JVM lifecycle, read-only
  management proxies, launcher, and executable shaded JAR.

The reactor has three production modules and no third-party Java runtime library.

## Pull Requests

Start with [AGENTS.md](AGENTS.md) for the shared development workflow and project-local skills
in [`.agents/skills/`](.agents/skills/). These cover Java/compiler work, CSS and Canvas UI,
asset extensions, music/sound design, and verification. These ordinary repository files need no
global skill installation and can also be read directly by tools without skill discovery.

Import and build the root `pom.xml`; there is no second build system or module-specific setup.
Change the smallest owning module: keep contracts and generated-application runtime capabilities in
core, built-in implementations in stdlib, and project editing, build orchestration, lifecycle, and
display in Creator. Do not add a module, runtime dependency, execution path, compatibility layer,
or abstraction without current public behavior that requires it.
Use Java 25, JDK types first, final values by default, stateless Step handlers, and explicit
boundary results. Public methods do not return Java null. Reflection, parallel streams, hidden
fallback execution, and interfaces without a current second implementation are not accepted.

Each behavior or rejection belongs in its own highest-practical public-entrypoint test. During work,
run `./mvnw test`; before requesting review, run `./mvnw clean verify`. Run
`scripts/coverage.sh` only when updating the advisory coverage report. Do not commit `target`,
`.railix`, IDE state, local project files, or `brainstorming`; all are ignored. GitHub Actions
runs the same clean verification for every pull request, so the local and hosted acceptance
commands are identical.

## Unsupported Scope

The current implementation does not claim additional Trigger/I/O catalogs, dependency acquisition
from remote repositories, Merge/Split/Loop Steps, global reusable groups, assertions, remote
attachment, time-window/custom production metrics, queues, permissions, sharding, or final per-project minimal
`jlink`/`jpackage` application output. These remain explicit roadmap checkpoints.

## Verification

`./mvnw clean verify` runs compiler, runtime, HTTP, packaged-JAR, desktop Chrome, and mobile Chrome
public-entrypoint tests. Required suites reject zero discovered tests. The same lifecycle writes the
aggregate coverage report but has no coverage check, so an advisory percentage cannot turn a
successful developer build into a failure.

Browser E2Es use the system `chrome` channel by default. Set `RAILIX_BROWSER_CHANNEL` to another
installed Playwright Chromium channel when required by the contributor host. The single GitHub
Actions workflow runs the same clean verification on every pull request.

`scripts/coverage.sh` runs that clean verification and prints the advisory aggregate stored at
`modules/railix-creator/target/site/jacoco-aggregate/index.html`. Its 95% line and 90% branch targets
cover authored Java production files in the three modules. Generated per-project application
classes and the Creator browser client are proven through generated-artifact and browser E2Es but
are not part of the JaCoCo denominator. Exact accepted counts and aggregate coverage are recorded
in [ROADMAP.md](ROADMAP.md).

## License

Railix is licensed under the [Apache License 2.0](LICENSE).
