# Railix II

Railix Creator turns one visual project into one Java monolith. The current real journey is:

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

See [ROADMAP.md](ROADMAP.md) for exact progress and unsupported scope. [ADR
0020](adr/0020-creator-first-application-graph.md) owns the active graph, metadata, and workflow
context contract.

## Build And Start

Requirements:

- Java 25
- Maven 3.9 or newer
- Google Chrome for browser E2Es

Build everything:

```sh
mvn clean verify
```

The repository provides the development launcher, and the build creates the executable Creator
JAR:

```text
modules/railix-creator/target/railix.jar
./railix
```

`railix.jar` is Creator itself, not the final environment-specific monolith. Self-contained
`jlink`/`jpackage` application output remains roadmap work.

Start Creator with its default project file and an automatically selected loopback port:

```sh
./railix creator
```

Or select both:

```sh
./railix creator path/to/railix.project.json 7310
```

Creator prints the URL to open. `Ctrl-C` stops Creator and its owned development-application JVM.
`mvn clean` removes the generated JAR but never the tracked root launcher. Railix has only Java
runtime dependencies. Playwright is test-only.

## Create The First Flow

1. Select the permanent **Application** Step and choose **Add Trigger** in the Inspector.
2. Search the installed Trigger catalog for **CLI**.
3. Set its Target to `context.payload.arguments` and its Example payload to `["Hello RAILIX"]`.
4. Choose **Add next Step**, search for **Lowercase**, and add it as an ordinary graph Step.
5. Keep Source at `context.payload.arguments[0]` and set Target to `context.result`.
6. Wait for **Built** and select the CLI Trigger. Creator shows the Example result automatically.

Each ordinary Step remains one graph node until the user explicitly creates a visual group.
Project persistence, structural compilation, development-application replacement, and example
execution are automatic. Creator sends examples to the built application; the compiler never
executes Step handlers.

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

Every invocation owns one mutable workflow context. `payload`, `header`, `metadata`, `result`, and
`exit_code` are ordinary keys. Projects may add any JSON-compatible key. Only `context.runtime` is
reserved and supplied read-only by Railix.

The CLI Trigger defaults `context.result` to JSON `null` and `context.exit_code` to `0`. Its named
examples are independent cases. Creator unions their paths and shapes for field suggestions
without merging their values. Run a built CLI project as a one-shot application with:

```sh
(cd examples/lowercase-app && ../../railix run "Hello RAILIX")
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
.run(input -> input.run("steps").writeWhenPresent("field"));
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

## Develop A Unary Step

"Primitive" is a compact Creator presentation and product role, not a `StepDefinition.Kind`.
A small value operation is an ordinary `STEP` with one receive, one return, and an explicitly named
primary outcome when `next` is not suitable:

```java
public final class LowercaseStep {
    private LowercaseStep() {
    }

    public static StepDefinition definition() {
        return StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .run(input -> StepResult.outcome("ok").output(
                        "value",
                        RailixValue.string(input.string("value").toLowerCase(Locale.ROOT))
                ));
    }
}
```

Register definitions explicitly in a `StepCatalog`; Railix never discovers classes through
reflection. The default outcomes are App `start`, Trigger `next`, and ordinary Step `next`.
`.primaryOutcome("ok")` deliberately replaces `next` for the unary operation above. Additional
`.outcome(...)` calls declare explicit non-primary results such as `invalid` or `empty`.
Optional `.displayName(...)` and `.searchTerms(...)` values affect only Creator presentation and
search; compiler and runtime behavior remains entirely in the functional contract.

[ADR 0021](adr/0021-total-and-fallible-primitives.md) owns the finite built-in unary catalog and
its exact semantics. External dependency loading and the reusable Step template remain roadmap
Item 4.

## Modules

- `railix-core`: canonical primitive data, flat project compiler, and stateless stream execution.
- `railix-stdlib`: App, CLI Trigger, Field Manipulation, Filter, Choice, and built-in total or
  explicitly fallible unary Steps.
- `railix-creator`: Creator HTTP/UI, optional presentation metadata, rolling child JVM, launcher,
  and executable shaded JAR.

The reactor has exactly these three production modules and no third-party runtime dependency.

## Unsupported Scope

The current implementation does not claim additional Trigger/I/O catalogs, external dependency
resolution, branch-aware grouping, Switch/Merge/Split/Loop Steps, global reusable groups, test
fixtures/assertions, live metrics, remote attachment, queues, permissions, sharding, or final
`jlink`/`jpackage` application output. These remain explicit roadmap checkpoints.

## Verification

`mvn clean verify` runs compiler, runtime, HTTP, packaged-JAR, desktop Chrome, and mobile Chrome
public-entrypoint tests. JaCoCo reports the 95% line and 90% branch targets without failing a
developer build; test, compilation, and packaging failures still fail verification. Exact accepted
counts and aggregate coverage are recorded once in [ROADMAP.md](ROADMAP.md).
