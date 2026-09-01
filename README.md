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

See [ROADMAP.md](ROADMAP.md) for exact progress and unsupported scope. [ADR
0020](adr/0020-creator-first-application-graph.md) owns the active graph, metadata, and workflow
context contract.

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
`./mvnw clean` removes the generated JAR and application image. Railix has no third-party runtime
libraries. Playwright is test-only.

The printed URL contains a random Creator token in its fragment. Every Creator API request requires
that token and the exact loopback `Host`; the browser client supplies both without storing the token
in project files. Treat the complete local URL as a credential while Creator is running.

## Create The First Flow

1. Select the permanent **Application** Step and choose **Add Trigger** in the Inspector.
2. Search the installed Trigger catalog for **CLI**.
3. Set its Target to `context.payload.arguments` and its Example payload to `["Hello RAILIX"]`.
4. Choose **Add next Step**, search for **Lowercase**, and add it as an ordinary graph Step.
5. Keep Source at `context.payload.arguments[0]` and set Target to `context.result`.
6. Wait for **Built** and select the CLI Trigger. Creator shows the Example result automatically.

Each ordinary Step remains one graph node until the user explicitly creates a visual group.
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
- `GET /v1/examples/{id}` returns one Example's current application-owned status.
- `GET /v1/examples/{id}/view` returns one completed Example summary as ready-to-display JSON.
- `GET /v1/examples/{id}/steps/{node}` returns one selected Step projection as ready-to-display JSON.
- `GET /v1/examples/steps/{node}` returns every real Example case plus its initial context and,
  when reached, that selected Step projection as one bounded ready-to-display response. It returns
  `413 EXAMPLE_STEP_REPLAY_TOO_LARGE` before replay when completed source traces exceed 64 MiB.
- `GET /v1/metrics`, `/v1/metrics/application`, and `/v1/metrics/nodes/{id}` stream JSON metrics.
- `GET /v1/metrics/prometheus` and `/v1/metrics/influx` stream standard text formats.
- `POST /v1/run/{trigger}` admits one explicit untraced development invocation.
- `POST /v1/trace/{trigger}` admits one explicit traced development invocation with a caller-owned
  trace ID; `DELETE /v1/traces/{id}` requests its cancellation.

Run and trace request bodies are limited to 1 MiB, must finish within five seconds, and are read
before the separate 32-run or 16-trace admission budget is acquired. At most 48 incomplete bodies
with a valid token can be held. Missing or invalid tokens have an independent four-body budget, so
they cannot consume authenticated admission. Excess or expired incomplete connections are closed
without executing a Flow.

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

The current generated-code boundary accepts at most 16,384 total nodes and 512 Trigger nodes in one
application. Production and development artifacts are built at the exact Trigger boundary in E2E;
larger Trigger sets are rejected by the compiler before source allocation instead of failing later
inside `javac`.

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

## Creator Metadata

`railix.creator.json` is optional and has only `format`, `groups`, and `steps`. One group occurrence
over the functional Lowercase Step can be represented as:

```json
{
  "format": 1,
  "groups": [
    {
      "id": "group-b5b8ad04-ec95-43c3-a32e-e33874b7a402",
      "name": "Normalize result",
      "color": "#147982",
      "occurrences": [
        {
          "id": "occurrence-f2524963-fde3-4bbb-a4f3-c4db188a52db",
          "flow": "command",
          "parent": null,
          "steps": {
            "slot-bbb92270-6a44-4365-afac-ad3e96a14de7": "lowercase-text"
          }
        }
      ]
    }
  ],
  "steps": {}
}
```

Each occurrence maps stable logical slot IDs to concrete functional Step IDs. Shared occurrences
use the same slots, allowing explicit **Update all**, **Detach this**, **Create variant**, or
**Cancel** decisions. Groups may be nested through `parent`, render as semantic zoom, and carry
optional Creator-only `name`, `color`, and embedded Base64 `icon`. Deleting a group preserves all
functional Steps and reparents nested groups.

The compiler and built application never read this file. Missing or invalid metadata opens the
functional graph flat, preserves the invalid file for recovery, and reports a targeted Creator
diagnostic. There is no `members`, `instances`, `global`, live blueprint link, or compiler group
expansion.

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
- `railix-stdlib`: App, CLI Trigger, Field Manipulation, Filter, Choice, Switch, and built-in total
  or explicitly fallible unary Steps.
- `railix-creator`: Creator HTTP/UI, project build and rolling child-JVM lifecycle, read-only
  management proxies, launcher, and executable shaded JAR.

The reactor has three production modules and no third-party runtime library.

## Pull Requests

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
