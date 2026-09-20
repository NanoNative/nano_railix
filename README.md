# Railix II

## What Railix Is

Railix is being built as a product-building system for cross-functional teams. People author an
application as visual **Flows** of **Steps** in Creator; a successful Railix build will compile
that static model into one immutable monolithic application. Java is Railix's current
implementation language, not its product boundary.

Railix is neither diagram-to-code nor a runtime-interpreted workflow engine. Creator groups
help people navigate; cross-project Blueprints and Templates remain planned. The compiler sees
only materialized Flows and Steps. It builds one fixed application with no reflection, runtime Step
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
The planned operating model and its status live in [docs/roadmap.md](docs/roadmap.md).

## Documentation Map

| Location | Owns |
| --- | --- |
| This README | Product introduction, setup and contributor entrypoints. |
| [.agents/skills/](.agents/skills/) | Standalone project workflow skills, including specification, Java and CSS; no global installation required. |
| [System model](docs/specs/system-model.md) | Functional graph, Step/context contracts, control routing and compilation. |
| [Standard library](docs/specs/standard-library.md) | Primitive semantics, catalog/support matrix and acceptance. |
| [Observation](docs/specs/observation.md) | Current development execution, metrics and management APIs. |
| [Creator](docs/specs/creator.md) | Authoring, presentation metadata, factory world and navigation. |
| [Creator assets](docs/specs/creator-assets.md) | Settings, theme folders, renderer variants and editable sounds/music. |
| [Examples and validation](docs/specs/examples-and-validation.md) | Agreed future Example ownership and two-phase validation; not implemented. |
| [Environments and security](docs/specs/environments-and-security.md) | Multi-project secret-management goals and open identity, recovery and ingress design. |
| [Database and data access](docs/specs/database-and-data-access.md) | Planned durable database and permission-controlled Creator data administration; no time-series database. |
| [docs/roadmap.md](docs/roadmap.md) | Delivery sequence, dependencies, progress and unsupported scope. |
| [docs/adr/](docs/adr/) | Architectural decisions, rationale, alternatives and consequences. |
| [Verification history](docs/verification.md) | Earlier checks and performance evidence, not current CI status. |

Specs own behavior and acceptance. ADRs explain why; the roadmap links to those owners.
New requirements go in their owning spec rather than being copied into every document.

## First Accepted Journey

```text
Application -> CLI Trigger -> Lowercase -> End
```

The [canonical lowercase project](examples/lowercase-app/railix.project.json) is a
ready-to-open example of this flow.

Today `railix.project.json` contains the flat graph and Trigger Examples;
`railix.creator.json` is optional presentation only. The accepted future design moves
Example inputs and expectations together into `railix.examples.json`. That migration
has not happened. See the [validation spec](docs/specs/examples-and-validation.md).
Invalid functional edits show diagnostics while the last valid development app keeps running.

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

## Modules

- `railix-core`: canonical values, Step contracts, project validation, Java application generation,
  generated-application runtime contracts, stateless workflow execution, and the development
  capabilities packaged only into development artifacts.
- `railix-stdlib`: App, CLI Trigger, Field Manipulation, Filter, Choice, Switch, and built-in total
  or explicitly fallible unary Steps.
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
runs the same clean verification for every pull request, with two test forks to fit the
hosted runner's resource budget. The acceptance suites are unchanged.

## Support And Verification

Consult the [roadmap](docs/roadmap.md) for supported milestones and remaining scope, and
[standard library](docs/specs/standard-library.md) for individual catalog rows.

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
in [verification history](docs/verification.md), with their original scope and dates.

## License

Railix is licensed under the [Apache License 2.0](LICENSE).
