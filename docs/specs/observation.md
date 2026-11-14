# Application Execution And Observation

## Human Review

Status: **Shared runtime implementation in progress**, updated on 2026-11-03.
Time-series database exclusion, planned data-administration separation and the production
direction below do not change current endpoints or establish a fresh test pass. The roadmap owns
delivery status.

Owns: application-owned Example execution, management APIs, metrics and bounded Creator reads.
It does not define the planned [validation contract](examples-and-validation.md).
Planned [database administration](database-and-data-access.md) is a separate permission and
capability boundary. Read-only observation never implies authority to inspect or change records.

Decisions: [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

## Development Observation

The 2026-11-03 correction makes the operational contract environment-independent: the same
compiled Step calls, counters, sampling and metric exports apply to real ingress and Examples.
Build-time capability selection, not a development/production mode, determines optional code.
Example injection and recorded per-Step values remain the exception. Assertion evaluation belongs
to Creator and MUST NOT execute application Steps or enter the generated application.
The generated ordinary executor now uses the same direct handler references with and without
Examples. `RuntimeMetrics` owns the common bounded store. The standalone listener and first local
attachment/comparison implementation are described below. Their checks are recorded separately.

The user accepted a first authenticated, loopback-only standalone connection on 2026-11-03.
The user also selected local observation enabled by default, with a build-time opt-out. Disabling
the connection MUST NOT change Step execution or metric calculation. Ordinary builds enable it.
`build --no-observation` disables the listener without changing generated Step calls or counters.
Remote access and its authorization remain out of scope for that increment. Implementation must
separate observation from process ownership: detaching or closing Creator must not stop an
independently started application. The accepted
[same-workspace authoring direction](creator.md#live-application-and-local-changes) separates the
read-only observed version from editable local work. It does not authorize deployment through
the observation connection or extend the first attachment increment beyond loopback.

The generated development application runs every compiled named Trigger Example automatically.
An Example begins at the generic Trigger output after protocol conversion, carries
`context.runtime.test=true`, and executes the remaining Flow once through the normal generated
entrypoint. Creator only reads the application's management API. The selected Example highlights
its path and shows the real input/output change at each reached Step; union coverage separately
identifies Steps reached by any Example. Trace payloads do not contain timing data. Example execution increments normal operational
metrics; visual replay of a retained trace does not execute Steps or increment counters.

Explicit continuations have separate path IDs, call stacks and terminal records. Shared paths
record the exact Step-entry snapshot and its own atomic commit changes separately. Sibling writes
may be visible in the full context but must not be highlighted as this Step's edits. Entry deltas
keep per-path replay aligned with the values actually read. A Branch that only starts paths has
no own field changes. Failed path diagnostics remain locatable without changing successful Step
states or marking the running application as broken. Trace-discard state and shared counters are
synchronized across paths. These records and buffers remain outside production artifacts.

Unexpected Step runtime exceptions retain their Java type and stack in the protected development
trace and selected-Step projection. Details contain at most eight causes and 32 frames per cause,
handle cyclic causes and omit exception messages. Creator discloses them on demand in the failing
Step's evidence. Ordinary failure results and public HTTP responses retain their existing contract.
Production artifacts do not gain exception-tracing machinery.

Development traces are bounded to 4 MiB per event, 64 MiB per Example, and 256 MiB per suite.
The application runs at most 16 Examples per chunk, retains one terminal result or error for every
started trace, waits for every admitted worker to physically exit before admitting another chunk,
and serves ready-to-display summary or selected-Step JSON through at most two concurrent readers.
A worker has a 30-second execution deadline and five seconds to exit after interruption. If trusted Step code still refuses
to return, the generated development process exits instead of retaining that worker indefinitely;
Creator reports the application as stopped and never fabricates a result.
The full Example inventory and every projection are bounded to 16 MiB. The Creator server drains
each application response into a transient bounded buffer, closes the application read, and then
sends it to the browser. At most four such response payloads are held concurrently. On-demand
Example summaries are parsed transiently to attach current scene identities to diagnostics and
resolve the selected visual section's visits. Creator never persists or caches these payloads.
For the current application process, the browser caches only the
application-returned Trigger/index-to-ID lookup, selected case, and bounded all-Example projection
for the selected deployed node; it never derives an Example ID or result.
The all-Example projection for one Step has one in-flight owner from its frozen case snapshot through
response transport. A concurrent read returns bounded unavailability without allocating a second
case snapshot. The owner rejects the request before replay when its completed source traces exceed
64 MiB. Exact Step context cannot be reconstructed from compact context changes without replaying
from each Example's initial context.
Runtime metrics use fixed-cardinality counters selected at compile time per graph Step. Counts are
exact, Flow timings are exact, and Step timings sample one execution in 1,024. The shared application
endpoint serves JSON, Prometheus, and Influx metric formats; Creator displays application, Flow,
and selected Step values without owning those values. Metric JSON and text exports distinguish exact
primitive counter bytes, lookup-index bytes, and their sum from JVM-dependent object storage.
Every metric JSON response carries the owning application PID; Creator and its browser discard a
response when rolling replacement changes the current PID or artifact fingerprint.

Artifacts without Examples omit their manifest, value tracing and Example execution endpoints.
They retain the same operational metric store and generated instrumentation as artifacts with
Examples. The independent read-only observation endpoint must not require Example execution code.
Protocol-specific test clients, Creator assertions and remote attachment remain separate work.

`MetricsEndpoint` is the shared authenticated HTTP handler. The Example-enabled launcher registers
it on its existing management server. Normal JAR startup uses `ObservationServer` on an ephemeral
IPv4 loopback port. Observation never keeps a CLI-only application alive beyond its source lifetime.
Shutdown closes its listener, reader executor and credential and removes its own registration.
Unavailable optional observation does not prevent application execution.

### Local Standalone Connection

The listener publishes one transient connection record under `~/.railix/observations/`. The
directory must be owned by the current user with mode `0700`, each record with mode `0600`.
Unsupported protection fails closed. Discovery ignores dead processes, reused PIDs, unsafe links,
unsafe permissions and invalid records. Old crash records do not consume the live-candidate budget.
It reads at most 16 KiB per record and supports at most 512 live candidates per selection read.
These files are connection credentials and identity metadata, not project documents or topology.
They are never included in builds, browser responses, URLs or application contexts.

Creator selects a discovered instance, authenticates `GET /v1/application` and matches its listener
nonce, PID, process start time, source revision and actual JAR digest. It does not follow redirects
or accept caller-supplied network hosts. The original JAR must still be available and match the
digest captured at startup. Project data then use the same bounded local/embedded reader and installed
Step catalog. Matching local documents are preferred. Embedded originals can supply an older
observed version while the local draft is newer. Otherwise the user selects the matching local file.

The connection exposes only identity and the common metric reads/queries. It has no Example, run,
activation, shutdown or deployment routes. Creator uses the same bounded/deadline-controlled HTTP
body reader for owned and independent applications. A detached reader only closes its own HTTP
client. Closing Creator never stops an independently started process. This first connection is
same-user, POSIX-filesystem and loopback only, not a remote access or deployment contract.

Live reads carry `X-Railix-Live-Instance` with the selected listener nonce. An outdated selection
receives a conflict, never a different instance's data. One candidate connection may be prepared
alongside the active connection. Only an authenticated same-origin confirmation POST activates it.
Confirmation pauses Creator's owned local process, never the observed process. The draft remains a
compiled build with an artifact lease, without a dormant JVM. Editing does not execute its lifecycle
or Examples until `Start locally` uses the normal start/activate path. Reconfirming the active
instance is idempotent and does not stop an explicitly started local process. Read-only GET requests
cannot confirm a connection. Closing or cancelling before confirmation leaves the active version
and owned process unchanged. The next selection
or Creator shutdown closes an unused candidate. Local comparison payloads also identify their source
instance so another browser's selection cannot relabel cached changes.

## Application Source And Build Identity

Clarified on 2026-11-03: a complete flow view needs the exact deployed topology, not only its
measurements. The current metric export includes project/Flow/Step IDs and a PID, but no links,
editable project configuration or deployed build identity. Optional project inclusion and
artifact-referenced local authoring are implemented below. The existing observation path still
uses separate captured sources and instance-bound readers for its owned local application and
independently observed application. They never share counters or Example evidence.

Selected by the user on 2026-11-03: a build option may include the existing project documents in
the artifact. Without embedded project data, the matching documents MUST be available locally to
Creator for the Flow view. Both sources MUST use the same project formats and reading contracts.
There MUST NOT be an intermediate minimal description, separate topology manifest or alternative
graph format. Compiler inputs remain the materialized functional graph; groups and Creator
graphics remain optional presentation data.

Creator MUST verify that the project data describe the observed build rather than a newer local
draft. Source/build identification metadata may establish that correspondence, but MUST NOT
duplicate the graph. Neither a project name nor matching Step IDs prove equivalent behavior.
A PID identifies a process locally, not a deployed build or a globally unique instance. Editable
data must also resolve the relevant third-party Step definitions through the existing contracts.

If neither embedded nor matching local project data are available, Creator MUST request the
matching project instead of drawing a guessed Flow from metrics or presenting a different local
revision as the deployed version. Available measurements do not imply an available Flow view.
Including project documents MUST NOT implicitly enable Example execution or application-side
assertions; their capability ownership remains unchanged.

### Artifact Project Loading

Implementation added on 2026-11-03, with verification tracked in the roadmap:

- `railix build [project-file] [--include-project]` builds the ordinary standalone JAR and prints
  its path. Source inclusion is off unless selected. When enabled, `META-INF/railix/project/`
  contains the original `railix.project.json` and any existing `railix.examples.json`,
  `railix.creator.json` and `railix.dependencies.lock.json`. No generated scenes, runtime values,
  derived grouping/layout or alternative graph descriptions are added.
- The standard JAR manifest contains `Railix-Project-Id`, a SHA-256 source revision over the
  canonical functional graph and resolved implementation/contract identities and locked artifact digests.
  Examples, presentation and generated Java text are excluded. The hash is correspondence
  metadata, not authentication or a substitute for trusting a Step bundle.
- `railix creator [project-file] [port] --application application.jar` verifies the local project
  revision before normal startup. Existing local project files take precedence. Without them,
  embedded documents are restored into a new workspace directory, validated with the existing
  reader/compiler/catalog and only then published. Its parent must already exist. Extraction
  never replaces existing project files or directories. Unknown, duplicate or traversing source
  entries, malformed UTF-8, incomplete documents and missing locked bundles fail explicitly.
- Both variants use `ProjectFiles.read`, the same installed Step catalog and `CreatorServer`
  startup. The resulting execution is local and owned by Creator. This command neither attaches
  to nor acquires control over an existing application process. Source inclusion does not package
  dependency-store copies, enable Example execution in the standalone JAR or move assertions there.
- A build captures the original documents once and compiles and packages that same input. The
  dependency resolver accepts the captured lock through its existing parser. A final read compares
  current files without using them as new inputs. A journal or observed source change rejects the
  build rather than publishing a mixture of editor revisions. No capture manifest is persisted.
- The user accepted a 64 MiB limit per project document for both disk and embedded sources.
  Compressed entries are size-checked and streamed to a temporary workspace, not retained together
  in memory. Oversized disk reads also stop at that limit. This is a rejection safeguard, not a
  target document size. Derived UI state, observations and generated topology remain unpersisted.

The source-loading commands do not themselves attach to a running app. Protected local attachment
and simultaneous Live/Draft comparison use the connection journey below. Remote access remains planned.

Project configuration may contain credentials. Packaging source and starting a local copy are
therefore separate decisions from reading metrics. The access distinction follows
[OWASP's least-privilege guidance for secrets](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html#23-access-control),
not an assumption that authenticated read-only access can safely disclose every project value.
No automatic secret removal or safe production-I/O isolation is implemented by this direction.

## Accepted Production Observation Direction

Accepted on 2026-10-18 and clarified on 2026-11-03: generated applications include low-cost, bounded Step
and Flow telemetry so a connected Creator can show live activity without a separate observability
stack. Baseline measurements are available by default. Detailed production tracing is separately
enabled and bounded; it must not collect workflow values by default. Examples and their output
fakes are optional build capabilities. Their execution and recorded values are omitted when that
capability is disabled. Expectation evaluation belongs to Creator, not to either application artifact.

Development and production must retain one generated Step execution path and compatible metric
meanings. An Example supplies Trigger output to that path; selecting or replaying its evidence does
not run it again. Optional development-only fake behavior must not add a test branch to every Step
implementation. Production telemetry observes execution; it does not depend on Creator being
connected and cannot be inferred from Pod or host metrics alone. Showing traffic at a Step has a
measurable cost, so the production baseline requires a resource budget and packaged-artifact proof.

Before implementation, agree the baseline measurements and overhead bound, tracing admission and
retention, payload redaction, authentication and remote attachment, and how unavailable or stale
production data appears in Creator. No production trace endpoint, storage format, sampling rate or
test harness is selected by this direction. The shared metric store, direct ordinary dispatch and
protected metric HTTP handler, standalone local attachment and build opt-out are implemented.
Bounded real-traffic value tracing remains planned. Example capture does not prove that feature.

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
  When a Step is visited more than once, its projection also includes `invocations`, containing
  each recorded visit's identity, outcome, input context and output context in trace completion
  order. The existing projection fields retain the last visit and the total `visits` count.
  Invocation collection shares the response budget and stops replay when that budget is exceeded;
  it returns `413 EXAMPLE_STEP_PROJECTION_TOO_LARGE` rather than a truncated list. Single-visit
  responses omit `invocations`. The individual selected-Step endpoint retains its existing shape.
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

The inventory and individual case status retain an optional `result` error header at completion.
It contains the first stopping diagnostic, or the implementation failure, without replaying trace
files. Only `code`, `message`, `path` and `step` strings are retained, bounded to 128, 512, 1,024 and
128 characters respectively. Payloads, contexts and exception stacks remain outside this header.
Successful cases omit it. Creator resolves the header's canonical path or Step identity to its
display node and containing groups, using the captured project revision. This supports error-list
navigation even when recorded-value reads fail. It does not change production artifacts or infer
that an aggregate error count identifies the failing Step.

Run and trace request bodies are limited to 1 MiB, must finish within five seconds, and are read
before the separate 32-run or 16-trace admission budget is acquired. At most 48 incomplete bodies
with a valid token can be held. Missing or invalid tokens have an independent four-body budget, so
they cannot consume authenticated admission. Excess or expired incomplete connections are closed
without executing a Flow.

Scoped queries share the authenticated body deadline and their existing two-reader admission.
Each query allows at most 4,096 groups, 1,048,576 range-member visits, and a 1 MiB request/response.
An optional boolean `cases:true` requests reaching `case_ids` for exactly one group, using the
already recorded interval membership. It does not load trace events or add production hooks.
Pending cases without a published path do not appear. Ordinary queries omit this field and its work.
Creator resolves a selected Step/group/work area through the same bounded membership batches using
`GET /api/scene/observations?revision=<scene revision>&cases=<scene ID>`. Responses include the
application PID, suite revision, scope and case IDs. Scene replacement rejects stale requests.
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

Example summaries report `complete:true` only after a complete terminal record. A recording error
reports `complete:false`, independently of whether application work continued. Selected Step
projections report the exact `visits` observed while replaying the trace, rather than asking
Creator to infer repeated arrivals from the last occurrence. These are read-side Example facts,
not new production instrumentation or assertion logic in the application.

Metric values and presentation are separate. The application owns the catalog and cumulative
measurements; Creator caches the catalog per child application and relays bounded selections.
The Inspector renders available catalog entries by unit, not a metric-name whitelist. Scene
observations carry values under `metrics`, separate from node IDs and Example coverage. Creator
combines query batches using each definition's `sum` or `max`; nonaggregatable values stay separate.
Absent measurements are omitted, not fabricated as zero. Timing definitions identify their sample
counter, so an unsampled duration is displayed as unavailable.

### Step-Defined Metrics

Clarified on 2026-11-04: a Step may supply its own metric definitions and measurements through
the public Step contract. Railix registers them in the existing application metric catalog and
publishes them through its existing metric endpoint and exports. Steps remain functions, not
metric servers. Registering a metric MUST NOT create a Step-owned listener, port or separate
reporting channel. Built-in and third-party Steps use the same contract.

The implementation verified on 2026-11-04 declares fixed `MetricDefinition` values through
`StepDefinition.Builder.metric(...)`. A counter defaults to sum aggregation. A gauge defaults to
no aggregation. PROCESS and optional PRE/POST functions use `context.counter(name).increment()` /
`.add(amount)` and `context.gauge(name).set(value)`. No lifecycle hook is required solely to register
metrics. Railix binds handles once while constructing the generated application's metric store.
Updates perform atomic memory operations, not I/O, polling, descriptor creation or per-call handle
allocation. Names cannot be introduced from invocation data. Counters reject negative increments
and saturate instead of overflowing. Gauges accept finite numbers and remain absent until set.

The catalog qualifies each name as `<Step definition ID>:<local name>`. Duplicate local names or
conflicting declarations fail before execution. Measurements are separate for each placement,
including nested Steps. Nested measurements appear under their owning graph Step and use the
declared aggregation. Group summaries omit multiple nonaggregatable observations rather than
choosing one. Single-Step JSON also retains each custom placement under `placements`, and text
exports identify its fixed placement separately. Draft and Live Inspectors disclose those values
individually, including repeated nested gauges.
The ordinary per-node metrics switch also disables custom measurements, including nested ones,
through shared no-op handles. No custom counter arrays are allocated for disabled placements.
The existing catalog, JSON reads/queries, Prometheus and Influx exports carry these measurements.
Creator uses their labels and units through its existing generic Inspector, without Step-ID checks.
The nominal `port` unit displays the exact number, without grouping or an added suffix, for built-in
and installed Steps alike. Source-activation failures identify their owning graph Step. The runtime
still omits arbitrary exception messages; a Step owns its safe transport-boundary diagnostics.
Metric metadata counts toward the existing generated-code budget for one Step. Generated constant
arrays split by content size as well as entry count so many valid declarations across an application
do not create an oversized Java initializer.

```java
StepDefinition.named("example.work", "1")
        .metric(MetricDefinition.counter("processed", "Processed", "count"))
        .metric(MetricDefinition.gauge("queue_depth", "Queue depth", "items"))
        .implementation(Work.class);

// Inside a static Step function. No registration or transport setup here.
context.counter("processed").increment();
context.gauge("queue_depth").set(12);
```

Prometheus exports keep separate counter/gauge families and an injectively escaped definition
namespace, with the original identifier, label and unit in HELP. Fixed placement labels distinguish
repeated uses. Unlike a single catch-all family, each family retains one kind and unit, following
the [Prometheus exporter guidance](https://prometheus.io/docs/instrumenting/writing_exporters/).
Reading any export does not call Step functions or collect new measurements.

### Scoped Metric Reads

A reached destination does not by itself establish that every incoming connection was reached.
An incoming connection from a known unvisited source remains unreached. Missing source evidence
remains unknown, rather than inventing an arrival through a shared destination.
Likewise, destination counters are not route counters when several connections arrive there.
Such connections omit their metric projection rather than repeating the destination total on
each incoming belt. The destination Step keeps its own ordinary counters.

Metric queries include `steps` and `flows` objects, empty when unused. For example,
`{"steps":{"region":[[0,9]]},"flows":{},"metrics":["executions","duration_nanos_max"]}`
returns only those two measurements.
`{"steps":{},"flows":{},"process":"runtime","metrics":["heap_used_bytes"]}`
requests process memory without scanning Steps. Responses identify the application PID and include
UTC `observed_at` plus monotonic `elapsed_nanos`; the frontend derives rates from counter/time deltas.
Concurrent counters are observations, not an atomic transaction across every Step or query batch.
Existing JSON, Prometheus and Influx names and units remain unchanged.

New charts, rates or averages over existing measurements require only frontend changes. A genuinely
new measurement still needs application instrumentation through the fixed declarations above.
Historical time windows and further capability selection remain roadmap work. No formula engine,
metric database, additional dependency or per-update allocation is introduced here.
The 2026-09-19 scope clarification explicitly excludes a dedicated built-in time-series database.
Existing metric exports remain in scope; any historical window needs a separate bounded-retention
contract, not an assumed embedded database.

Automatic Examples do not call the HTTP run or trace routes. The application invokes its generated
Flow entrypoint directly and stores the bounded trace itself. Creator exposes read-only
`/api/examples` descendants plus application and selected-node `/api/metrics` proxies for its
browser; it exposes no `/api/run` or `/api/trace` route.

The [accepted production-observability screenshot](../../screenshots/roadmap-item-6-1-production-observability.png)
shows the real generated application completing its compiled CLI Example, highlighting the reached
path, returning the transformed context, and serving connected Step and Flow metrics to Creator.

## Execution Ownership

Creator edits, persists, invokes compilation, publishes one development artifact and starts its
JVM. A Step projection returns the explicit Example context captured immediately before that
Step plus its normal post-Step result. Observation backpressure or storage failure stops trace
recording, never the Flow. Coarse scene-observation reads may parse coverage, route summaries
and metrics, but never Example contexts or production payloads.
The application's all-case Step projection lets the browser derive union field choices and
compatibility without decoding trace events or invoking Steps.
Contract-declared default paths form an authoring floor when management data is unavailable;
application projections may refine or add observed paths, but cannot erase declarations or survive
a PID, fingerprint, or process-state change. Only application projections provide observed values.

`StepDefinition` owns only reusable declarative capability: identity, graph role, generic inputs,
paths, outcomes, defaults, implementation address, and optional catalog presentation or starter
Example values. Starter values are copied into the functional project when a Step is added. The
project owns authored Flows, Steps, and Examples. Core owns canonical values, project validation,
lowering, generated source, and runtime contracts. The generated application owns execution and
development telemetry. Creator provides the project editor, build, child-process lifecycle, and
read-only display; it does not own Example execution or results. These boundaries do not provide
alternate execution paths for one another.

## Scene Observation

`GET /api/scene/observations?revision=...` accepts the viewport and an optional `example` ID and
combines the captured running application's scoped metric and Example-membership queries. Static
membership is a derived interval index; neither view state nor groups enter the artifact. Queries
aggregate in the application and return only requested groups. Creator batches fragmented or large
memberships instead of downloading full metric series or replaying the selected trace. Scene
revision, activated artifact and PID must agree; activation failure or replacement cannot attach
old observations to a new graph. Responses contain bounded counts and route reach information,
never production payloads or Example contexts. Two admitted response drains bound concurrent
aggregation; the browser cancels obsolete reads and polls once per completed read plus one second.
Scene and scene-observation responses are capped at 2 MiB. Application readers enforce the
30-second deadline over headers and the complete bounded body, cancelling timed-out reads and
releasing admission on every exit. Example batches must share one suite revision;
if that revision changes, coverage and selection are omitted for that poll rather than mixed.
Pending or unavailable capabilities omit only their fields; malformed successful responses fail
closed rather than inventing zero measurements. Hidden pages stop scene observation reads.
Unchanged observations do not redraw the world or rebuild the selected Example preview.
Picker updates deferred while the user interacts are retried by the existing poll after interaction,
not by another timer or a full Inspector redraw. Runtime metrics are disclosed on demand; the
per-Step metrics build setting remains immediately available.
Changing Step, group or navigation selection cancels an older application-observation read and
requests an immediate refresh through that same poll owner. Its eligible Example list must not
wait for a slow read started for the previous selection.

The App/JVM/System comparison matches metric identifiers after removing their scope prefix,
together with their catalog unit. Unavailable cells stay empty; differing units and duplicate
identities within one scope retain separate rows. JVM heap limits remain JVM measurements.

## Visual Group Timing

Selected-Example group inspection is a separate on-demand read of recorded data. Creator's existing
Example-view endpoint accepts `region=<region>&revision=<scene revision>`. It intersects that
occurrence's membership with the application-returned path and resolves actual entry/exit identities
for that Example. The browser reads those Steps' existing projections to compare values. This covers
the current acyclic, single-outcome execution contract. Multiple visits in one Example require an
ordered invocation projection before introducing multi-route execution. The summary and section share
one application read under the Example payload budget, separate from coarse scene observations.
PID/revision checks reject stale evidence. No group instrumentation, production hook, per-group
polling loop or new Example execution is introduced.

Groups remain visual summaries of underlying Step metrics, never runtime spans or execution units.
Group timing is the sum of individual sampled means, not the pooled total/sample ratio and not a
measured passage. Creator reads at most 512 member series per page through the application's existing
metric query. A counter-index cursor binary-searches interval membership, avoiding rescanning all
members for every page. Only visible groups retain one partial scalar sum and sample count; pages
are sequential with a 100 ms yield, separate from traffic observations, and complete sums refresh
after ten seconds. Scene revision and application PID invalidate them. Missing samples stay explicit.

## Acceptance Boundaries

Existing generated-application suites prove normal Example metrics, bounded suite/projection
ownership and production omission. Creator tests exercise authenticated forwarding, stale
PID/revision rejection, absent measurements and group aggregation through HTTP/browser
boundaries. Historical counts are evidence, not a new test run. New assertions and release
eligibility require the separate planned validation evidence.
