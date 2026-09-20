# Development Execution And Observation

## Human Review

Status: **Existing baseline with scope clarification**, updated on 2026-09-19.
Time-series database exclusion and planned data-administration separation do not change
current endpoints or establish a fresh test pass. The roadmap owns delivery status.

Owns: application-owned Example execution, management APIs, metrics and bounded Creator reads.
It does not define the planned [validation contract](examples-and-validation.md).
Planned [database administration](database-and-data-access.md) is a separate permission and
capability boundary. Read-only observation never implies authority to inspect or change records.

Decisions: [ADR 0020](../adr/0020-creator-first-application-graph.md). Historical checks: [verification](../verification.md).

## Development Observation

The generated development application runs every compiled named Trigger Example automatically.
An Example begins at the generic Trigger output after protocol conversion, carries
`context.runtime.test=true`, and executes the remaining Flow once through the normal generated
entrypoint. Creator only reads the application's management API. The selected Example highlights
its path and shows the real input/output change at each reached Step; union coverage separately
identifies Steps reached by any Example. Trace payloads do not contain timing data. Example execution increments normal operational
metrics; visual replay of a retained trace does not execute Steps or increment counters.

Development traces are bounded to 4 MiB per event, 64 MiB per Example, and 256 MiB per suite.
The application runs at most 16 Examples per chunk, retains one terminal result or error for every
started trace, waits for every admitted worker to physically exit before admitting another chunk,
and serves ready-to-display summary or selected-Step JSON through at most two concurrent readers.
A worker has a 30-second execution deadline and five seconds to exit after interruption. If trusted Step code still refuses
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
recording, never the Flow. Unlike transparent Example relays, bounded scene-observation reads
may parse coverage, route summaries and metrics, but never Example contexts or production payloads.
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

The App/JVM/System comparison matches metric identifiers after removing their scope prefix,
together with their catalog unit. Unavailable cells stay empty; differing units and duplicate
identities within one scope retain separate rows. JVM heap limits remain JVM measurements.

## Visual Group Timing

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
