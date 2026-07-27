# Railix II Roadmap

This is the single source of truth for product status. An item is `Done` only when its
supported behavior passes public-entrypoint tests, line coverage is at least 95%, branch
coverage is at least 90% where branches exist, and unsupported behavior fails explicitly.
Each distinct behavior, rejection, edge case, and user-visible assertion requires its own
named test at the highest practical public entrypoint. Fake or test-only runtime paths cannot
satisfy acceptance. Resource and lifecycle claims require repeatable cleanup, cancellation,
and leak evidence. UI items also require real browser proof and a screenshot.

## Product Contract

Railix is a Java-only system for composing trusted Java Steps into explicit flows over
JSON-compatible primitive data, then compiling the complete authored flow into one small
application. Creator must make control, data, defaults, and triggers understandable before
build; the compiler must reject incomplete or ambiguous flows before execution.

The core owns only the reusable semantics every Railix application needs:

- immutable primitive values and bounded canonical JSON/YAML/XML normalization;
- explicit Step contracts and the catalog presented to the compiler;
- one flow model, compiler, executable plan, executor, and deterministic diagnostics.

Standard Steps and trusted local capabilities belong in `railix-stdlib`. Trigger transports,
application lifecycle, packaging, CLI, HTTP APIs, and Creator belong in `railix-creator`.
Observability, permissions, remote execution, and durable state remain outside the core until
their own roadmap item proves that the canonical execution contract itself must change.

Core growth must satisfy all three tests: current supported behavior requires it, more than one
public entrypoint consumes it, and the behavior cannot remain in its owning outer module without
duplicating canonical semantics. Railix keeps one value/flow/execution path, no reflection, no
third-party runtime dependency, no hidden mapping/default/fallback, and no speculative
abstraction. Invalid construction is rejected at compiler or ingress boundaries.

## Progress

- Roadmap implementation: **4/10 items Done (40%)**
- Current item: **Local Application Capabilities, In Progress (4/6 checkpoints, 67% integrated, 33% left)**
- Current checkpoint: **Trusted local capabilities (3/5 capability slices, 60% integrated, 40% left)**
- Production release certification: **0%**

Current verified baseline:

- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,612 executions with zero failures,
  errors, or skips.
- The gate comprises 706 core, 181 standard-library, 310 Creator component, 187 desktop
  browser/package, 174 mobile-browser, and 54 external-application executions.
- Coverage is 99.23% line / 97.49% branch for core, 96.03% / 90.24% for the standard
  library, 95.04% / 90.63% for Creator, and 100% line for the external application.
- Compiler and Creator both permit mutually exclusive outcomes from one predecessor to share
  a successor and reject fan-in from different predecessor Steps; dedicated cases prove each
  additional diagnostic and prevent collateral diagnostics.
- The product has three modules, 34 production Java files, 9,023 Java lines, three Creator
  web resources with 3,430 lines, one value/flow/execution model, and no third-party runtime
  dependency.
- The 273,072-byte product JAR has SHA-256
  `1cb39b456cd97e8fcd916cb7c4160896435b28b3a4c31324c612dd23c1077150`; the
  278,877-byte external JAR has SHA-256
  `f938cdc23ee0856b6462f28f16a78aaa8584387d7bdb188147814907cf55e106`.

Evidence inside completed checkpoints is a frozen snapshot from that checkpoint's closure.
The current baseline above supersedes older counts, sizes, hashes, and support boundaries
without rewriting their historical evidence.

Production certification stays at zero until every feature claimed for the release has
repeatable package, compatibility, resource, leak, and release evidence. A green local
milestone is not a production-readiness claim.

## Remaining Work

The ordered leftovers are:

1. Finish Item 5 checkpoint 5 slice 4: explicit runtime configuration and secrets with
   redaction and non-serialization proof.
2. Finish Item 5 checkpoint 5 slice 5: trusted resource lifecycle/cancellation closure and
   the complete Creator/package/capability audit.
3. Finish Item 5 checkpoint 6: build, start, control, and stop the complete flow-defined
   monolith with public package, concurrency, resource, and lifecycle E2Es.
4. Implement Item 6 observability, bounded work, explicit Merge/Split/Choice/Loop nodes, and
   authorable test events, boundaries, assertions, and environments.
5. Implement Items 7-9 in order: trusted permissions/metering, multi-instance Step execution,
   then Step-owned sharded and replicated state.
6. Complete Item 10 production certification and release DX only after every claimed feature
   has repeatable compatibility, soak, leak, package, and release evidence.

The numbered item sections below own detailed acceptance and evidence. This list records
execution order only; planned behavior is not silently pulled into an earlier item.

## 1. Comprehensible Local Railix

Status: **Done**

Goal: establish the smallest honest end-to-end Railix application surface.

Checkpoints:

1. Three active modules only: core, standard library, and Creator.
2. Named, non-reflective Java Step definitions with explicit inputs, outputs, outcomes, and handler.
3. Compiler-owned opaque executable plans with graph, mapping, type, reachability, and definite-data validation.
4. Stable canonical JSON parsing and writing with deterministic diagnostics.
5. One shaded package, one root launcher, and one readable lowercase application.
6. Real Creator HTTP/browser validation and execution, package subprocess E2E, lifecycle proof, and enforced 95/90 coverage gates.

Evidence:

- `mvn -q verify`
- `./railix run examples/lowercase-app`
- Core coverage: 97.87% line / 92.22% branch
- Standard library coverage: 100% line
- Creator coverage: 95.58% line / 93.59% branch
- Packaged launcher, package rejection, Creator health, compiler, runtime, and browser scenarios

At Item 1 closure, explicitly unsupported behavior was: control cycles, automatic mappings,
automatic workspace persistence, dependency resolution, YAML/XML interchange, sandboxing,
metering, remote execution, and durable state. Later item evidence supersedes this historical
support boundary.

## 2. Visual Flow Authoring

Status: **Done**

Goal: make Creator edit the same flow model that the compiler executes.

Checkpoints:

1. **Done:** Add and remove Step nodes from the real catalog without invented mappings or routes.
2. **Done:** Connect and disconnect explicit outcome transitions.
3. **Done:** Connect and disconnect explicit data ports.
4. **Done:** Show compatible sources and stable compiler diagnostics while editing.
5. **Done:** Edit flow inputs, outputs, Step defaults/configuration, and sample input without hidden values.
6. **Done:** Load, save, and reopen canonical flow JSON without semantic drift.
7. **Done:** Prove each authoring and rejection scenario through browser E2E at desktop and mobile widths.
8. **Done:** Replace raw graph assembly with one deterministic Application-centered composition path, default CLI
   ingress, contextual control/data connection actions, human endpoint labels, and explicit removal.

Checkpoint 1 evidence:

- Seven packaged-CLI browser scenarios cover run, add, compiler rejection, both removal shapes, repeated IDs, and 320 px behavior.
- Removal reports every deleted data connection, outcome transition, and entry binding exactly once.
- Browser verification uses an installed channel and cannot download browsers implicitly.
- `mvn clean verify` passes 164 scenarios and the enforced 95% line / 90% branch gates.

Checkpoint 2 evidence:

- Node-local controls connect each declared outcome to `end` or another existing Step and
  disconnect by deleting only that canonical `steps[].on` entry.
- Outcome route controls invent no data mapping, entry, default, or fallback. Creator blocks ordinary
  cycles and implicit fan-in from different predecessor Steps before mutation; compiler diagnostics
  remain the rejection authority for imported invalid graphs, missing routes, reachability, and
  required data.
- Ten dedicated packaged-browser scenarios cover disconnect, reconnect, reroute, visibility,
  connected removal, compiler diagnostics, cycle rejection, and 320 px / 720 px layouts.
- The compiler rejects the reserved invocation id `end` before graph analysis can confuse it
  with the terminal marker.
- `mvn clean verify` passes 177 scenarios: 110 core, 1 standard-library flow E2E, 43 Creator
  component cases, 17 real browser cases, and 6 package smokes.
- Coverage is 97.87% line / 92.22% branch for core, 100% line for the standard library, and
  95.58% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-outcome-routes.png` and
  `output/playwright/railix-creator-outcome-routes-mobile.png`.

Checkpoint 3 evidence:

- Target-local controls edit the canonical `connections[]` array directly: disconnect deletes
  one target binding, reconnect adds one, and remap replaces in place without duplicates.
- Every canonical connection renders as a wire; multi-Step input and flow-output mappings no
  longer depend on the first node or a parallel UI-only model.
- Ten dedicated packaged-browser scenarios cover Step-input and flow-output disconnects,
  reconnect/run, replacement, unavailable future data, connected removal, compiler-owned
  diagnostics, and 320 px / 720 px layouts.
- `mvn clean verify` passes 187 scenarios: 110 core, 1 standard-library flow E2E, 43 Creator
  component cases, 27 real browser cases, and 6 package smokes.
- Coverage is 97.87% line / 92.22% branch for core, 100% line for the standard library, and
  95.58% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-data-ports.png` and
  `output/playwright/railix-creator-data-ports-mobile.png`.

Checkpoint 4 evidence:

- Data controls show endpoint shapes and apply the compiler's structural rule: `any` targets
  accept every source shape; concrete targets accept only the same shape.
- Control-flow availability remains compiler-owned. Every flow mutation automatically renders
  the canonical diagnostic code, path, and message without replacing its authoring event.
- A monotonically increasing draft revision prevents an older compile response from replacing
  feedback for a newer edit; repaired drafts clear stale diagnostics deterministically.
- Seven dedicated packaged-browser scenarios cover source presentation, initial validity,
  automatic missing-input and multi-issue feedback, rapid repair, unavailable future data,
  event preservation, and 320 px access.
- At checkpoint 4 closure, the public catalog contained only the `string -> string`
  `text.lowercase` Step, so no incompatible catalog fixture was invented solely to make a
  browser test pass.
- `mvn clean verify` passes 194 scenarios: 110 core, 1 standard-library flow E2E, 43 Creator
  component cases, 34 real browser cases, and 6 package smokes.
- Coverage is 97.87% line / 92.22% branch for core, 100% line for the standard library, and
  95.58% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-live-feedback.png` and
  `output/playwright/railix-creator-live-feedback-mobile.png`.

Checkpoint 5 evidence:

- Fixed Step configuration is separate from event input: a Step declares defaults or required
  values, every invocation has an explicit `steps[].config` object, and the compiler resolves the
  effective immutable configuration into the existing executable plan.
- Creator edits flow inputs and outputs, canonical shapes, Step configuration, and raw event JSON.
  Add operations invent no mappings or sample values; rename and removal update only exact affected
  connections. Sample input is sent only by Run and is never executable-flow data or a fallback.
- The compiler rejects unknown root, Step, connection, and configuration fields, missing required
  configuration, and shape mismatches with deterministic code/path/message diagnostics.
- The shipped `text.lowercase` Step proves a visible `languageTag: "und"` default, immediate `"tr"`
  override, reset, compile, and real execution where `"I"` becomes `"ı"`.
- Twelve dedicated browser scenarios cover defaults, immediate overrides, reset, contract add/rename/
  remove behavior, shape rejection, raw sample execution, malformed samples, and 320 px access.
- `mvn clean verify` passes 226 scenarios: 128 core, 1 standard-library flow E2E, 45 Creator
  component cases, 46 real browser cases, and 6 package smokes.
- Coverage is 98.19% line / 92.70% branch for core, 100% line for the standard library, and
  95.85% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-flow-contract-config.png` and
  `output/playwright/railix-creator-flow-contract-config-mobile.png`.
- Complexity audit: no module, runtime dependency, or top-level production class was added. The
  checkpoint added 267 Java and 338 HTML/CSS/JavaScript lines to deliver the four active behaviors;
  the 605-line review trigger was accepted only after duplicate models and adjacent scope were rejected.

Checkpoint 6 evidence:

- Open sends the selected file bytes to the existing compiler boundary. Save downloads that same
  compiler's canonical UTF-8 JSON plus one final newline as `railix.flow.json`; no browser serializer,
  second flow model, server-side file store, route, module, framework, or runtime dependency was added.
- Reopen restores the exact explicit flow contract, configuration, mappings, routes, and entry in a
  fresh page. Event sample input remains session-only, compiler defaults are not materialized, and a
  reopen/resave cycle is byte-for-byte stable.
- Malformed, structurally invalid, unsupported, invalid UTF-8, lone-surrogate, duplicate-key, and
  oversized files are rejected without changing the current flow. Picker cancellation is a no-op,
  filename extensions are advisory, invalid drafts do not download, and repeated open/save is stable.
- One draft revision rule prevents stale Compile, Validate, Run, Open, and Save responses from
  replacing newer authoring state. Download Blob URLs are revoked after initiation and every HTTP
  exchange, request decoder, browser file input, and page used by verification has an explicit owner.
- Twenty dedicated packaged-browser scenarios cover persistence success, rejection, repetition,
  response reordering, and 320 px operation. Strict UTF-8, Unicode scalar, canonical-source, and 1 MiB
  ingress behavior is also proven directly through the public compiler and run HTTP endpoints.
- `mvn clean verify` passes 259 scenarios: 135 core, 1 standard-library flow E2E, 51 Creator
  component cases, 66 real browser cases, and 6 package smokes.
- Coverage is 98.22% line / 92.71% branch for core, 100% line for the standard library, and
  95.97% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-flow-persistence.png` and
  `output/playwright/railix-creator-flow-persistence-mobile.png`.
- Complexity audit: three modules, 24 production Java files, and zero external runtime dependencies
  remain. The checkpoint added 49 Java and 99 HTML/CSS/JavaScript lines across seven existing files;
  no production file, top-level class, interface, abstraction, or dependency was added.

Checkpoint 7 evidence:

- One canonical class keeps 66 named browser scenarios. Failsafe executes every scenario against the
  packaged Creator at `1280x720` and `320x700`, producing 132 viewport-specific browser cases rather
  than a smaller mobile smoke or duplicated test implementation.
- Desktop and mobile executions have explicit immutable viewport settings, separate report and summary
  files, and one aggregate verification gate. The installed Chrome channel remains mandatory and the
  suite never downloads a browser implicitly.
- Browser lifecycle teardown is bounded to 40 seconds, closes the driver-owning Playwright instance,
  stops the packaged Creator, and rejects any surviving child process. This replaced an unbounded
  browser-close path reproduced by the dual-viewport gate.
- `mvn clean verify` passes 325 case executions: 135 core, 1 standard-library flow E2E, 51 Creator
  component cases, 66 desktop browser cases, 66 mobile browser cases, and 6 package smokes.
- Coverage remains 98.22% line / 92.71% branch for core, 100% line for the standard library, and
  95.97% line / 93.59% branch for Creator.
- Desktop and mobile proof: `output/playwright/railix-creator-visual-authoring.png` and
  `output/playwright/railix-creator-visual-authoring-mobile.png`.
- Complexity audit: no production file, line, class, module, dependency, route, or model changed. The
  checkpoint changed only the existing browser specification and Creator test-build configuration.

Checkpoint 8 evidence:

- Creator projects the canonical flow as one centered Application with attached Trigger nodes. A new
  application has one explicit CLI Trigger by default, one generated short URL-safe three-part name,
  and no UI-only application or coordinate model. Session name reuse and invalid edited names reject
  deterministically.
- Trigger and Step **Add next** actions mutate only explicit entry/outcome control. **Add data consumer**
  and target-local **Choose source** actions mutate only canonical data connections. Human labels and
  shapes replace internal endpoint ids in normal authoring; exact ids remain available in diagnostics
  and canonical JSON.
- Ordinary control and data ports are single-use. Used sources/targets disappear from basic choosers,
  ordinary control cycles and implicit fan-in from different predecessor Steps are blocked, and
  unreachable/future data is omitted.
  Imported advanced and multi-source mappings remain preserved and editable under **Map fields** with
  field/index chips, defaults, conversions, explicit disconnect, and no raw path-JSON editor.
- Step and Trigger removal is explicit, layout is derived deterministically after every mutation and
  reload, and the three shipped web resources remain the only Creator frontend.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,605 executions with zero failures, errors,
  or skips: 701 core, 181 standard-library, 310 Creator component, 173 desktop browser, 13 desktop
  package, 173 mobile browser, and 54 external Step/flow/build/package/browser cases.
- Coverage is 99.22% line / 97.47% branch for core, 96.03% / 90.24% for the standard library,
  95.13% / 90.85% for Creator, and 100% line for the external application. The rebuilt root smoke
  `./railix run examples/lowercase-app` prints `{"text":"hello railix"}`.
- Desktop and mobile proof:
  `output/playwright/railix-creator-guided-flow-authoring.png` and
  `output/playwright/railix-creator-guided-flow-authoring-mobile.png`.
- Complexity audit: no production Java file, class, interface, module, route, model, framework, or
  runtime dependency was added. The guided authoring correction adds 1,141 HTML/CSS/JavaScript lines
  across the same three resources, for 3,433 UI lines, after removing duplicate raw-path and mapping
  editor paths. The product remains three modules, 34 production Java files, 9,002 Java lines, one
  flow/value/execution model, and zero third-party runtime dependencies.

Simplicity guardrails:

- Keep the three-module product and require every Creator production class to own current
  public-entrypoint behavior; add no speculative owner.
- Keep Creator on its three plain HTML/CSS/JavaScript resources; no frontend framework or build chain without measured need.
- Keep browser automation test-only. Production remains free of that dependency.
- Every authoring behavior and rejection gets its own public browser scenario rather than a private-helper test.

## 3. Step Developer Kit And Custom Monolith Build

Status: **Done**

Goal: let a small Java team add trusted Steps and package the complete Creator-authored flow
as one custom monolith with low ceremony.

Deliverables include a Step project template, explicit catalog registration, contract
compatibility diagnostics, dependency lock data, automatic dependency closure for every
Step referenced by the authored flow, Step flow E2E fixtures, and reproducible whole-flow
application builds. Developers do not select the same Steps again during packaging.

Checkpoints:

1. **Done:** Build one external trusted Java Step through the public API, register it explicitly, use it
   in a Creator-authored flow, package the complete flow as one custom monolith, and run that artifact.
2. **Done:** Reduce the proven external project to one reusable Step template with one command and one readable
   flow E2E; add no framework, reflection, annotation processor, or generated registration layer.
3. **Done:** Reject missing, duplicate, and incompatible Step dependencies at compile/build ingress with stable
   code/path/message diagnostics.
4. **Done:** Derive deterministic dependency closure and lock data from every Step referenced by the complete
   authored flow. Packaging must never ask developers to select those Steps again.
5. **Done:** Rebuild the same whole-flow artifact byte-for-byte from a clean checkout and run it without invoking
   Maven or requiring a separate Creator installation at runtime.
6. **Done:** Prove every SDK, build, execution, and rejection scenario through its highest public entrypoint;
   retain the 95% line / 90% branch gates and audit package contents, dependencies, and orphaned code.

Checkpoint 1 evidence:

- `mvn -q -Ptrusted-step-monolith clean verify`
- `java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar run examples/trusted-step-monolith/input.json`
- Four caller-catalog HTTP scenarios and ten standalone package/browser scenarios
- The browser extracts the packaged `railix.flow.json`, opens and runs it in Creator, saves canonical JSON,
  and captures `output/playwright/railix-trusted-step-monolith.png`
- Core coverage: 98.22% line / 92.71% branch
- Standard library coverage: 100% line
- Creator coverage: 96.03% line / 93.75% branch
- Three product modules, 24 product production Java files, 2,938 product Java LOC, and no external
  product runtime dependency; checkpoint product delta was 35 LOC in two existing classes

Checkpoint 2 evidence:

- `mvn -q -Ptrusted-step-monolith clean verify` builds Railix and its standalone consumer in one
  reactor without a prior install; the default reactor remains three product modules.
- `RailixMain.executeApplication(...)` owns packaged-flow loading, exact-catalog execution, CLI
  diagnostics, output routing, and Creator lifecycle. `TrustedGreetingMain` is now 30 lines of
  explicit catalog registration and delegation instead of a second 98-line launcher.
- `GreetingStepFlowIT` is a 30-line public compiler/runtime scenario using the committed whole flow,
  real input, exact Step catalog, and expected output. Package and browser E2Es still exercise the
  shaded artifact outside its project and the flow extracted from that artifact.
- The aggregate gate passes 355 executions: 135 core, 1 standard-library flow E2E, 68 Creator
  component cases, 132 Creator browser viewport cases, 6 product package smokes, and 13 external
  flow/package/browser cases.
- Core coverage: 98.22% line / 92.71% branch; standard library: 100% line; Creator: 95.48%
  line / 93.88% branch; external template: 100% line with no production branches.
- External coverage comes from the in-process flow E2E and explicitly instrumented package Java
  subprocesses. Browser runs prove real UI behavior and bounded Creator lifecycle separately; they
  do not inflate the external line result.
- Complexity audit: three product modules, 24 product production Java files, 3,059 product Java
  lines, and zero new runtime dependencies or production classes remain. Shared launcher behavior
  adds 121 product Java lines while deleting 71 duplicated example launcher lines, a net 50
  production-line delta across Railix and the reusable external project.
- Desktop and mobile proof: `output/playwright/railix-trusted-step-monolith.png` and
  `output/playwright/railix-trusted-step-monolith-mobile.png`.

Checkpoint 3 evidence:

- `StepDependencyBuildIT` compiles the committed whole flow through the public compiler boundary;
  Failsafe binds its nine named scenarios to Maven `integration-test` and `verify`.
- Separate scenarios require exact diagnostic code, path, message, and order for a missing Step,
  duplicate Step id, missing handler, removed used input, removed used output, incompatible used input
  shape, incompatible used output shape, removed used outcome, and defaulted configuration becoming
  required.
- Positive package and browser E2Es remain separate: they inspect and run the shaded JAR with its exact
  production catalog, extract its embedded flow, execute it in Creator, and prove bounded lifecycle.
  Negative structural variants do not pretend to be packaged production artifacts.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 364 executions: 135 core, 1 standard-library
  flow E2E, 68 Creator component cases, 132 Creator browser viewport cases, 6 product package smokes,
  and 22 external flow/dependency/package/browser cases.
- Coverage remains 98.22% line / 92.71% branch for core, 100% line for the standard library,
  95.48% line / 93.88% branch for Creator, and 100% line with no production branches for the external
  template.
- Complexity audit: three product modules, 24 product production Java files, 3,059 product Java lines,
  and zero external product runtime dependencies remain. The checkpoint changes no production line,
  class, module, interface, abstraction, or dependency; its behavior proof is one 198-line external
  test file.
- Checkpoint 3 ended with structural compilation only; the checkpoint 4 lock evidence below closes
  identity, version, default, full declared-contract, and referenced-flow closure drift.

Checkpoint 4 evidence:

- Compilation derives one canonical `railix.lock.json` from the complete canonical flow and exactly its
  referenced Step closure. Dependencies are sorted by id, repeated invocations are deduplicated, and unused
  catalog entries are omitted; packaging never asks developers to select Steps again.
- Every locked Step has an explicit opaque version and a domain-separated SHA-256 fingerprint over its id,
  version, every input/output, configuration shape/default/required flag, and outcome. Lock parsing requires
  exact lowercase `sha256:` plus 64 hexadecimal characters. Invalid Unicode is rejected at compiler ingress
  with named diagnostics before canonical JSON or hashing can throw.
- The custom monolith exposes `lock`, `lock --check`, and atomic `lock --write <output>...`. Normal Maven builds
  are read-only and reject stale locks during `prepare-package` before Shade; the explicit `refresh-step-lock`
  profile regenerates source and classes locks and then runs the same verification.
- `TrustedGreetingBuildIT` copies only the real clean build inputs. Its two Maven lifecycle scenarios prove a
  stale lock produces no shaded artifact and explicit refresh writes the exact lock into source, classes, and
  the final JAR. Package subprocesses drain both pipes concurrently and use bounded forced termination.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 446 executions: 174 core, 1 standard-library flow E2E,
  97 Creator component cases, 72 desktop package/browser cases, 66 mobile browser cases, and 36 external
  flow/dependency/build/package/browser cases.
- Coverage is 98.16% line / 92.62% branch for core, 100% line for the standard library, 96.21% line / 95.45%
  branch for Creator, and 100% line with no production branches for the external template.
- The final shaded JAR contains the complete flow, exact dependency lock, product runtime, and external Step;
  source/classes/JAR locks are byte-identical and no JUnit, AssertJ, Playwright, Surefire, Failsafe, or test
  entry is present. No Railix, custom-monolith, Surefire, or Failsafe process survived the gate.
- Bytewright and Gatekeeper independently report the final compiler/lock and lifecycle/resource surfaces clean.
  Sixty-two launcher scenarios include every flow/lock read and close-failure combination with deterministic
  diagnostics; the 39-case compiler lock matrix covers malformed and contract-ingress cases independently.
- Complexity audit: the product remains three modules with zero external runtime dependencies. It has 25
  production Java files and 3,646 lines; the checkpoint adds one owned `StepLock` class and 587 lines over the
  checkpoint-3 baseline. The 500-line review trigger is accepted for four current behaviors: canonical derive,
  strict read/verify, atomic refresh, and resource-safe launcher enforcement. No second model, interface,
  module, reflection path, runtime framework, or compatibility layer was added; the external application
  remains two production files and 53 lines, with one build-only Maven plugin.

Checkpoint 5 evidence:

- `TrustedGreetingBuildIT` first reproduced the defect through two independent clean project copies: both Maven
  builds succeeded but `Files.mismatch` found differing ZIP metadata at byte 10. The same E2E now requires a
  byte-for-byte match after waiting beyond ZIP's two-second timestamp resolution.
- Maven's standard `project.build.outputTimestamp` is the only normalization. The root contract applies it to
  every Railix module and the standalone application declares the same value; no archive rewriter, generated
  metadata model, runtime dependency, or production execution path was added.
- A separate copied-project lifecycle scenario builds the whole flow, moves only its JAR into an isolated runtime
  directory, clears the process environment, invokes it with an absolute `java -jar` command and real JSON input,
  and receives the exact expected output without invoking Maven or requiring a separate Creator installation.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 448 executions: 174 core, 1 standard-library flow E2E,
  97 Creator component cases, 72 desktop package/browser cases, 66 mobile browser cases, and 38 external
  flow/dependency/build/package/browser cases.
- Coverage remains 98.16% line / 92.62% branch for core, 100% line for the standard library, 96.21% line / 95.45%
  branch for Creator, and 100% line with no production branches for the external template.
- Every entry in the clean shaded whole-flow JAR has the exact `2000-01-01T00:00:00` DOS timestamp. The artifact
  contains no JUnit, AssertJ, Playwright, Surefire, Failsafe, or test entry, and no Railix, custom-monolith,
  Surefire, or Failsafe process survives the gate.
- Complexity audit: zero modules, dependencies, production files, production Java lines, interfaces, or runtime
  paths were added. The complete behavior delta is two Maven properties and two copied-project lifecycle tests
  in the existing test class.

Checkpoint 6 evidence:

- The final ownership audit maps all three product modules, 25 product classes, two external classes, runtime and
  build dependencies, test fixtures, example resources, and artifact entry categories to current behavior and a
  public proof. No module, class, dependency, fixture, or executable resource is orphaned.
- Five redundant public conveniences were deleted: unused compile warnings, versionless Step construction, the
  second `withOutcome` construction path, exposed `RailixValue.NULL`, and unused `ValueShape.any()`. Redundant
  control-graph guards already guaranteed by compiler ingress were also removed; no compatibility layer replaced
  them.
- Thirty-seven named core scenarios close every reachable module-local parser, Step input, shape, lock, graph, and
  compiler branch that lacked proof. One real HTTP scenario rejects `GET /api/compile`; six new whole-flow E2Es
  separately prove unknown application/lock arguments, missing/malformed locks before Shade, absence of test/build
  classes, and retained Maven-coordinate provenance.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 492 executions: 211 core, 1 standard-library flow E2E,
  98 Creator component cases, 72 desktop package/browser cases, 66 mobile browser cases, and 44 external
  flow/dependency/build/package/browser cases.
- Coverage is 99.57% line / 99.83% branch for core, 100% line for the standard library, 96.46% line / 96.10%
  branch for Creator, and 100% line with no production branches for the external application. The five remaining
  core module-local lines are two accessors proven through Creator HTTP, one impossible canonical-lock invariant,
  and the Java-25-mandated SHA-256 provider defense.
- The final 108-entry JAR has unique names and one normalized timestamp, retains Maven metadata as provenance,
  and contains no test/build-tool class. `jdeps` reports only `java.base` and `jdk.httpserver`; no Railix,
  custom-monolith, Surefire, or Failsafe process survives the gate.
- Complexity audit: the product remains three modules and 25 production Java files with zero third-party runtime
  dependencies. Product Java falls from 3,646 to 3,607 lines while the external application remains two files and
  53 lines. No module, production file, dependency, interface, reflection path, or alternate execution model was
  added. Pathfinder, Bytewright, and Gatekeeper independently report the final surface clean.

Still unsupported in this item: detecting a handler behavior change without the developer changing its explicit
Step version and cross-OS or cross-toolchain byte equivalence. Handler bytecode/reflection inspection will not be
introduced to manufacture that signal; long-running production leak/soak certification belongs to later roadmap
items and release gates.

## 4. Canonical Data Workbench

Status: **Done**

Goal: make nested primitive data explicit and manageable between Steps.

Deliverables include stable JSON/YAML/XML normalization, explicit lossy-conversion
policies, nested path/selector mapping, validators, normalizers, translators, defaults,
sample previews, size/depth limits, and deterministic malformed-input diagnostics.

Checkpoints:

1. **Done:** Normalize equivalent JSON, YAML, and XML primitive documents into the same canonical `RailixValue`
   and canonical JSON through one public boundary, with deterministic diagnostics and byte/depth limits.
2. **Done:** Complete the cross-format grammar and rejection matrix: every mapping, sequence, scalar, attribute, text,
   repeated element, namespace, comment, escape, alias/tag, entity, and DTD case must either normalize without
   loss or fail with an explicit documented reason.
3. **Done:** Compile nested selectors and data mappings before execution, including arrays/maps, missing paths, shape
   conflicts, defaults, and explicit lossy conversions; runtime must not invent a mapping or fallback.
4. **Done:** Add compact built-in validator, normalizer, mapper, and translator operators with visible defaults and
   deterministic outcomes, without giving lightweight data operators queueing/scaling ceremony.
5. **Done:** Add Creator data-workbench authoring with source/target previews, compact lightweight-operator rendering,
   mapping feedback, and real JSON/YAML/XML examples at every editable point.
6. **Done:** Prove normalization, mapping, operator, UI, package, performance, limit, and rejection behavior through public
   component/package/browser E2Es; retain coverage gates and repeat the dependency/orphan audit.

Checkpoint 1 evidence:

- `RailixData.normalize(...)` is the single explicit-format ingress for strict UTF-8 JSON, constrained block YAML,
  and typed XML. Equivalent primitive documents produce the same immutable `RailixValue` and canonical JSON;
  object keys and decimal representations are deterministic.
- Visible direct-document defaults accept at most 1 MiB and 64 nested containers. The public overload accepts
  explicit byte limits through 8 MiB for bounded enclosing transport documents; direct flow/event ingress stays
  at 1 MiB. Canonical numbers are capped at 1,024 characters, and malformed input returns stable code/message/
  line/column diagnostics rather than leaking parser exceptions.
- YAML currently owns the lossless block subset: two-space maps/sequences, JSON strings and numbers, lowercase
  booleans/null, and empty collections. XML owns an unambiguous typed vocabulary of `object`, `field`, `array`,
  `item`, and scalar elements. Unsupported syntax is rejected explicitly; broader grammar remains checkpoint 2.
- 130 dedicated public-boundary scenarios cover every accepted shape, format equivalence, malformed and
  unsupported input, exact/over limits, encoding and XML hardening, repetition, and virtual-thread concurrency.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 622 executions: 341 core, 1 standard-library flow E2E,
  98 Creator component cases, 72 desktop package/browser cases, 66 mobile browser cases, and 44 external
  flow/build/package/browser cases.
- Coverage is 99.58% line / 99.06% branch for core, 100% line for the standard library, 96.46% line / 96.10%
  branch for Creator, and 100% line for the external template.
- Complexity audit: the product remains three modules and zero external runtime dependencies. One public boundary
  and two format parsers add three production files and 983 lines, with no interface, adapter, alternate value
  model, reflection, or framework. The 28 production Java files total 4,590 lines.
- The 107-entry shaded JAR is timestamp-normalized, contains no test/build classes, depends only on `java.base`,
  `java.xml`, and `jdk.httpserver`, and has SHA-256
  `d66ae9c1dc7d90728f128c071afefadecf2a6ad8595737e2a0b4b91c9e31c22a`.
- Oracle contract selection, Bytewright parser review, and Gatekeeper completion review found no remaining
  checkpoint blocker. No Railix JVM or test driver survives the clean gate; release soak/leak certification
  remains deliberately unclaimed.

Checkpoint 2 evidence:

- The README format contract is the single support matrix for objects/maps, arrays/sequences, every primitive,
  field names/attributes, repetition, whitespace, comments, escapes/references, YAML alternate forms, XML
  namespaces/markup/cardinality, and exact diagnostic families. Unsupported syntax is named rather than guessed,
  discarded, or mislabeled as full YAML/arbitrary XML support.
- 184 `RailixData.normalize(...)` scenarios contain exactly 184 assertions. They prove each accepted/rejected matrix
  cell, strict diagnostic code/message/location values, exact/over depth limits for all three formats, repeated and
  concurrent calls, and JSON/YAML/XML escape, entity, DTD, namespace, declaration, and malformed-input families.
- Failing-first regressions fixed two review findings: typed XML now honors Railix's inclusive 64-container limit
  instead of StAX's lower raw-element default, and adjacent XML elements retain the offending start position.
  YAML anchors, aliases, and tags now use the same explicit unsupported diagnostic in scalar and mapping-key positions.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 676 executions: 395 core, 1 standard-library flow E2E,
  98 Creator component cases, 72 desktop package/browser cases, 66 mobile browser cases, and 44 external
  flow/build/package/browser cases.
- Coverage is 99.58% line / 98.97% branch for core, 100% line for the standard library, 96.46% line / 96.10%
  branch for Creator, and 100% line for the external template.
- Complexity audit: no module, production file, interface, model, framework, reflection, or runtime dependency was
  added. The complete checkpoint changed 14 production lines across the two existing format parsers; the product
  remains 28 production Java files, 4,604 lines, and zero external runtime dependencies.
- The 107-entry shaded JAR is timestamp-normalized, contains no duplicate/test/build entries, depends only on
  `java.base`, `java.xml`, and `jdk.httpserver`, and has SHA-256
  `a2b1cceec3f4ff5936d899ba80a690954997c11ab802b9b4b6c3a46ec2a01a94`.
- Pathfinder's matrix audit and Gatekeeper's two correctness passes were fully addressed. No Railix process survives
  the clean gate. Long-running leak/soak and release compatibility evidence remains outside this checkpoint.

Checkpoint 3 evidence:

- The canonical connection grammar now supports typed object-field and array-index `sourcePath`/`targetPath` values,
  missing-path defaults, and exactly four explicit conversions. The compiler rejects unavailable data, incompatible
  roots/shapes, overlapping targets, mixed containers, array holes, and invalid defaults before execution; runtime
  never invents a mapping, coercion, fallback, or second data model.
- One compiled mapping plan owns selection, conversion, nested assembly, and output projection. Runtime diagnostics
  preserve deterministic connection paths and completed Step history, including rejections after earlier Steps ran.
- Flow and event ingress retain the 1 MiB source, 64-container, 64-path-element, and 1,024-character number limits.
  Public regressions cover JSON/YAML/XML scale overflow, extreme-scale zero, removable fractional zeroes, 20,000
  nested containers, million-digit conversion text, and repeated 512-leaf assembly under a 64 MiB child JVM without
  leaking `OutOfMemoryError`, `StackOverflowError`, `ArithmeticException`, or an unbounded process.
- Creator compiles and runs nested mappings over loopback HTTP, returns exact mapping/number diagnostics and late
  execution history, and byte-stably opens and saves advanced flows. Until checkpoint 5, path/default/conversion and
  multi-source mappings render explicitly as read-only rather than being silently flattened by simple port controls.
- The clean aggregate gate passes 822 executions with zero failures, errors, or skips: 526 core, 1 standard-library,
  105 Creator component, 76 desktop/package, 70 mobile browser, and 44 external flow/build/package/browser cases.
  This is 146 checkpoint-added public-boundary executions over the checkpoint 2 baseline.
- Coverage is 99.67% line / 98.63% branch for core, 100% line for the standard library, 96.59% line / 96.20% branch
  for Creator, and 100% line for the external application.
- Complexity audit: the product remains three modules, 28 production Java files, one execution path, and zero
  third-party runtime dependencies. The checkpoint adds 919 production Java lines to the 4,604-line baseline without
  adding a production file, interface, adapter, framework, reflection path, or parallel value/flow model. The accepted
  review-trigger increase implements the path grammar, compiler/runtime behavior, HTTP exposure, and bounded failure
  handling rather than future architecture.
- The 119-entry shaded JAR has unique names and one normalized timestamp, contains no test/build-tool entry, depends
  only on `java.base`, `java.xml`, and `jdk.httpserver`, and has SHA-256
  `b17d7a1a814ff9f2768a18521f24161e93869e0d05c069ff28a247304833c8a1`. No Railix process survives the gate.
- Oracle selected the explicit contract; Bytewright and Gatekeeper reviews drove failing-first regressions for resource,
  default, HTTP, ordering, history, and metadata-preservation defects. The final independent review found no checkpoint
  blocker. Built-in operators, visual mapping editors/previews, the Item 4 screenshot, and release soak certification
  remain explicitly outside this completed checkpoint.

Checkpoint 4 evidence:

- `StepDefinition.Kind` classifies `STEP`, `VALIDATOR`, `NORMALIZER`, `MAPPER`, and `TRANSLATOR` for authoring only.
  Classification is exposed by Creator and protected by the dependency lock; compiler IR and runtime execution never
  branch on it or create a second lightweight-operator model.
- The standard catalog contains `text.nonblank`, `text.lowercase`, `value.default-if-null`, and
  `text.translate-exact`. Their visible defaults, required configuration, preserved values, exact outcomes, null-only
  replacement, case-sensitive translation, malformed configuration, and every handler branch have separate public
  flow or loopback HTTP scenarios.
- `ConfigFormat.LANGUAGE_TAG` is a closed declarative constraint. Java 25's strict language-tag parser runs only at
  compiler boundaries for Step defaults and flow overrides; empty, whitespace, underscore, truncated, and empty-subtag
  values reject with exact diagnostics. Creator displays `string · language-tag`; no handler or JavaScript fallback,
  trimming, rewriting, arbitrary predicate, hidden route, queue, scaling, or retry behavior exists.
- Contract fingerprints use the `railix-step-contract-v3` domain and cover kind plus optional configuration format.
  Adding/removing either rejects a stale lock. The committed external lock was generated by the explicit refresh
  profile and pins `text.lowercase` 1.2.0; ordinary builds verify it without editing source.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 899 executions with zero failures, errors, or skips:
  555 core, 31 standard-library, 119 Creator component, 78 desktop/package, 72 mobile browser, and 44 external
  flow/build/package/browser cases.
- Coverage is 99.68% line / 98.66% branch for core, 100% line / 100% branch for the standard library,
  96.62% line / 96.20% branch for Creator, and 100% line for the external application.
- Complexity audit: the product remains three modules, one execution path, 29 production Java files, 5,787 lines,
  and zero third-party runtime dependencies. The checkpoint adds one 74-line operator owner and 264 production lines
  over checkpoint 3 for four runnable operators, classification, strict formatted configuration, lock/Creator
  projection, and one compiler diagnostic correction. It adds no interface, adapter, framework, reflection path,
  compatibility layer, or alternate model.
- The 123-entry shaded JAR has unique names and one normalized timestamp, contains no test/build-tool class, depends
  only on `java.base`, `java.xml`, and `jdk.httpserver`, and has SHA-256
  `e731c43db5457c8292e98a4796fb0ed4ec00e63f50984e589e04ff8bd33d17e5`; an immediate second package build emits
  identical bytes, and source/classes/JAR lockfiles are byte-identical.
- Rootkeeper's ownership review drove cleanup-safe Creator tests, one cached immutable catalog, immutable value reuse,
  parser-only language-tag validation, and fixed `tr`/`az`/`lt`/`th` runtime locales so arbitrary valid event tags
  cannot grow the JDK's process-global locale cache. Failing-first flow regressions preserve Java 25's extlang and Thai
  word-boundary semantics; the implementation matches 9,264 installed-locale casing comparisons. One million unique
  tags pass format validation and 100,000 unique tags compile and run through the standard flow under a 64 MiB child
  heap. Gatekeeper's malformed-language-tag fallback finding drove the compiler-owned format contract and regression
  matrix. No Railix process survives the gate. Long-running release soak/leak certification and compact visual
  operator treatment remain later roadmap work.

Checkpoint 5 evidence:

- Creator edits the canonical `connections[]` model directly. Every Step input and flow output renders an owned row
  with explicit source, source path, absent-versus-present default, conversion, target path, multiple-source add/remove,
  expression preview, and exact compiler diagnostic association. Invalid editor JSON stays local and never mutates the
  runnable flow; clearing either path deletes only that field and the saved flow remains runnable.
- JSON, YAML, and XML event tabs retain independent session drafts and send one explicit
  `{flow,event:{format,source}}` request only on Run. Java owns normalization and diagnostics. Flow edits and Open do
  not rewrite event drafts; the browser retains no source above Java's published 1 MiB event limit.
- Lightweight kinds render compact `V`, `N`, `M`, and `T` nodes while ordinary Steps retain the full `Fn` treatment.
  Rendering classification never changes compilation, execution, queueing, scaling, or any other runtime semantics.
- The browser uses the compiler-owned conversion catalog and cancels superseded automatic draft checks. Explicit
  Validate, Save, Open, and Run operations remain independent. Oversized run responses stop in the bounded canonical
  writer, and Creator shutdown reports completion only after its request executor terminates.
- 101 packaged-browser scenarios pass independently at 1280 px and 320 px. Current visual proof is
  `output/playwright/railix-creator-data-workbench.png` and
  `output/playwright/railix-creator-data-workbench-mobile.png`.

Checkpoint 6 evidence:

- `mvn -q verify` passes 932 executions with zero failures, errors, or skips: 724 Java/component cases,
  101 desktop browser cases, 101 mobile browser cases, and 6 packaged-app smokes.
- `mvn -q -Ptrusted-step-monolith clean verify` adds 44 external application flow/build/package/browser executions,
  for 976 clean executions against the trusted-Step monolith.
- Coverage is 99.50% line / 98.15% branch for core, 100% line / 100% branch for the standard library,
  96.85% line / 95.29% branch for Creator, and 100% line for the external application.
- Resource evidence includes the 64 MiB mapping child-JVM matrix, million-character conversion rejection,
  repeated 512-leaf assembly, reusable and concurrent invocation-local mapping state, exact/over event and transport
  limits, bounded response serialization, cancelled stale compiler requests, and interruption-resistant shutdown.
- The product remains three modules, one value/flow/execution path, 29 production Java files and 5,941 Java lines.
  The shipped Creator UI is three files and 1,852 lines. Checkpoints 5-6 added no production file, module, interface,
  framework, reflection path, compatibility layer, or third-party runtime dependency.
- Maven's runtime tree contains only Railix modules. The 192 KiB shaded JAR depends only on `java.base`, `java.xml`,
  and `jdk.httpserver`; two immediate builds produce SHA-256
  `8749b1d9c0341bf6428b9ca0c70eb2eda48be5055a7960758cb9e53d87692a48`.
  The orphan/fake scan is clean and no Railix JVM survives either gate.

Still unsupported in this completed item: application triggers and capabilities (Item 5), live metrics and bounded
queues (Item 6), permission enforcement (Item 7), remote execution (Item 8), sharded state (Item 9), and production
release certification (Item 10).

## 5. Local Application Capabilities

Status: **In Progress (4/6 checkpoints)**

Goal: build useful local monoliths from trusted Steps.

Deliverables include explicit CLI, HTTP, socket, scheduled, and startup triggers; replies;
file and network capabilities; local persistence boundaries; configuration; secrets;
cancellation; and packaged application control without ambient hidden behavior. When enabled,
manual ingress creates events through `POST /v1/flows/{flowId}/events` or at an explicit Step
through `POST /v1/flows/{flowId}/steps/{stepId}/events`.

Checkpoints:

1. **Done:** Define one canonical trigger/event and reply contract, including deterministic build-time diagnostics,
   cancellation ownership, and explicit unsupported-trigger rejection.
2. **Done:** Implement CLI and startup triggers through the same compiled flow entrypoint, with exact stdin/arguments,
   stdout/stderr, exit-code, repetition, and shutdown behavior.
3. **Done:** Implement HTTP triggers and replies, including manual
   `POST /v1/flows/{flowId}/events` and `POST /v1/flows/{flowId}/steps/{stepId}/events` ingress when enabled.
4. **Done:** Implement bounded socket and scheduled triggers with explicit framing/time semantics, backpressure, cancellation,
   overlap, clock, restart, and malformed-input behavior.
5. **In progress (3/5 capability slices):** Add trusted local file/network, persistence, configuration, secret, and
   cancellation capabilities without claiming sandbox enforcement before Item 7.
6. Build, start, control, and stop the complete flow-defined monolith; prove every trigger/capability, rejection,
   package, concurrency, resource, and lifecycle scenario through public-entrypoint E2Es and repeat the dependency audit.

Checkpoint 5 capability slices:

1. **Done:** Bounded normalized trusted local file read.
2. **Done:** Atomic local file persistence with explicit write/read/delete, overwrite, durability, and failure semantics.
3. **Done:** Bounded outbound HTTP/network access.
4. **Next:** Explicit runtime configuration and secrets with redaction and non-serialization proof.
5. Trusted resource lifecycle/cancellation closure and full Creator/package/capability audit.

Slice 4 contract gate, open before implementation:

- select the one canonical external configuration/secret ingress and exact precedence over
  flow-declared non-secret defaults;
- define typed declarations, missing/empty/unknown/conflicting value diagnostics, and whether
  runtime reload is supported or rejected;
- enumerate every redaction surface, including diagnostics, replies, logs, metrics, Creator,
  saved flows, dependency locks, and packaged resources;
- prove secret values are never serialized, retained in execution history, or exposed through
  canonical flow/event JSON;
- define ownership, zeroization limits available in plain Java, cancellation behavior, process
  inheritance, and package/start/stop acceptance.

No slice 4 production change begins until this contract is explicit. Unsupported ingress,
precedence, reload, and secrecy guarantees must reject deterministically rather than fall back.

Checkpoint 1 evidence:

- Every flow requires one canonical plural `triggers` array. Empty means no deployed ingress; each future declaration
  has exactly `id`, `type`, and object `config`. Missing, malformed, duplicate-id, unknown-field, reordered-field,
  singular legacy, and non-empty unsupported declarations produce deterministic compiler diagnostics without a
  compatibility fallback or trigger registry.
- At checkpoint 1 closure, the compiler and Creator exposed an empty supported-trigger list and rejected every
  declaration. Checkpoints 2-3 superseded that baseline with CLI, startup, and HTTP; checkpoint 4 then added socket
  and scheduled while preserving explicit arbitrary-trigger rejection. Creator Run remains an authoring event and
  never mutates the flow's trigger set.
- One direct immutable object event enters the existing compiled-flow path. One trigger-independent `RunResult` owns
  success, rejection, Step failure, and cancellation replies with completed-Step history; no envelope, reply channel,
  timestamp, metadata, trigger Step, adapter hierarchy, or second execution path was added.
- Java interruption owns cancellation before admission, between Steps, and before output assembly.
  `InterruptedException` restores the flag and returns only after the Step terminates; an unexpected Step exception
  remains a failure even when the Step also interrupts. A trusted Step that ignores interruption can still delay
  cancellation and is not falsely reported as terminated.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,025 executions with zero failures, errors, or skips:
  589 core, 31 standard-library, 147 Creator component, 113 desktop browser/package, 101 mobile browser, and
  44 external flow/build/package/browser cases. The trigger/reply contract itself has 30 named core scenarios.
- Coverage is 99.51% line / 98.18% branch for core, 100% / 100% for the standard library,
  96.92% / 95.32% for Creator, and 100% line for the external application; aggregate product and external coverage is
  99.10% line / 97.90% branch.
- The root lowercase application prints `{"text":"hello railix"}` and the trusted custom monolith prints
  `{"message":"Welcome, railix"}` after the clean gate. No Railix JVM survives verification or either smoke.
- Gatekeeper's completed branch-to-test review found no production defect and drove four isolated regressions for
  Runner trigger rejection, completed cancellation history, interruption after prior Steps, and contract-failure
  precedence. All four pass through public compiler/flow or Runner boundaries.
- Complexity audit: the product remains three modules, one value/flow/execution path, 29 production Java files,
  and zero third-party runtime dependencies. The checkpoint adds 131 Java lines across existing owners and no
  production file, interface, module, framework, reflection path, compatibility layer, or executor. The shaded JAR
  depends only on `java.base`, `java.xml`, and `jdk.httpserver` and has SHA-256
  `d46e658818b5061442efc58bcc71616fa9d52df8c50d45303dec20fdf2cd39df`.

Checkpoint 2 evidence:

- At checkpoint 2 closure, the compiler accepted only `cli` and `startup` declarations. CLI requires `stdin:boolean` and optionally maps
  arguments to one declared `array` or `any` flow input; startup accepts only `{}` and requires an inputless flow.
  Unknown fields, absent configuration, unavailable or incompatible argument inputs, startup flow inputs, and every
  then-unsupported trigger type had stable build-time diagnostics. Trigger ids and authored source order survive in the one
  compiled-flow model.
- At checkpoint 2 closure, the packaged grammar was exact: no arguments ran all startup triggers serially in authored order;
  `cli <trigger-id> [argument...]` preflights the selected trigger and event, runs startup first, then only that CLI
  trigger; `creator` and `lock` remain administrative. The removed packaged `run <input.json>` command is rejected
  without a fallback. Success, event/compile rejection, Step failure, and cancellation map to exit codes
  `0`, `2`, `3`, and `130`; launcher/resource failure maps to `4`.
- Flow/event reads stop after 1 MiB plus one probe byte and lock reads after 8 MiB plus one probe byte. All three byte
  ingresses reject malformed UTF-8 before decoding can change content. CLI argument validation stops at the event
  limit before reading stdin, never overwrites stdin data, and rejects null or malformed Unicode deterministically.
  Packaged resources close on success, checked/unchecked read failure, decode failure, and command rejection. Stdin,
  stdout, stderr, and the executing thread remain caller-owned; no trigger executor, shutdown hook, retained event,
  or hidden cancellation path was added.
- Twenty compiler E2Es and 41 launcher E2Es isolate declaration, ordering, stdin, arguments, bounds, repetition,
  caller-thread, resource, failure, and cancellation behavior. The external package suite launches a real CLI
  application and a separate real inputless startup application from JARs; copied-project build/lifecycle tests use
  the same deployed grammar. The first clean gate exposed one stale external `run` smoke, which was migrated rather
  than preserved as compatibility behavior.
- Rootkeeper found no retained-event, executor, thread, or shutdown-hook leak and drove bounded packaged resources,
  CLI events, authoring input, and unconditional child-process cleanup. Gatekeeper then drove strict UTF-8 admission,
  early bounded argument preflight, unchecked stdin projection, and the real startup subprocess proof. Every finding
  has its own public-boundary regression.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,107 executions with zero failures, errors, or skips:
  609 core, 31 standard-library, 205 Creator component, 111 desktop browser/package, 101 mobile browser, and
  50 external flow/build/package/browser cases.
- Coverage is 99.53% line / 98.24% branch for core, 100% / 100% for the standard library,
  97.96% / 96.75% for Creator, and 100% line for the external application; aggregate product and external coverage is
  99.23% line / 98.05% branch.
- Complexity audit: the product remains three modules, one value/flow/execution path, 29 production Java files,
  6,570 production Java lines, and zero third-party runtime dependencies. The checkpoint adds 498 production Java
  lines across existing owners and no production file, interface, module, framework, reflection path, compatibility
  layer, executor, or shutdown hook. The 209,622-byte product JAR depends only on `java.base`, `java.xml`, and
  `jdk.httpserver` and has SHA-256
  `cea0933fe4b4d9f3322b43f75ff715252bccc43c3f1f460c14dd35886fd55f06`.
- The root lowercase smoke prints `{"text":"hello railix"}` and the trusted whole-flow JAR prints
  `{"message":"Welcome, railix"}`. No Node, Homebrew, `llhttp`, Playwright, JNI, native library, fake/fallback marker,
  or orphaned Railix JVM exists in the runtime artifacts or post-gate audit.

Checkpoint 3 evidence:

- The compiler now accepts `http` beside `cli` and `startup`. Every HTTP declaration requires integer port
  `1..65535` and exactly one of static `path`, literal `flow:true`, or existing URL-safe `step`; all declarations
  share one listener port. Unknown fields, conflicting selectors/ports/routes, unsafe ids/paths, null/blank/unknown
  Steps, reserved `/v1` paths, and Step-event graphs that need flow input or skipped upstream output have isolated,
  deterministic compiler E2Es. At checkpoint 3 closure, socket, scheduled, and arbitrary trigger types still failed
  explicitly; checkpoint 4 supersedes that historical support boundary.
- One JDK `HttpServer` listens on IPv4 loopback. Only enabled exact routes accept POST, no query,
  `application/json`, one strict UTF-8 object up to 1 MiB, and a reply up to 1 MiB. Transport failures return
  deterministic Problem JSON; flow success, rejection, Step failure, and cancellation map to HTTP
  `200`, `422`, `500`, and `503`. Custom and flow routes use the existing flow entrypoint; Step routes use one
  alternate-root execution path and then the same outcome/data engine.
- The listener starts gated before startup side effects, returns `APPLICATION_STARTING` until startup succeeds,
  and releases its port on startup/bind failure or caller interruption. Lifecycle has one start transition and one
  idempotent stop. Stop closes admission and requests cancellation without waiting forever for a trusted Step that
  ignores interruption; it never falsely reports that invocation as terminated. Cooperative active requests,
  interrupt-ignoring requests, repetition, 32-way concurrency, and listener rebind have public HTTP/launcher proof.
- Creator edits the canonical `triggers[]` array directly: one shared port, an add/edit/remove list for static business
  routes, one explicit flow-event switch, and one selected-Step switch with exact endpoint previews. Enable, disable,
  last-trigger removal, malformed path/port compiler feedback, reload, custom-route preservation, deterministic ids,
  save, desktop, and mobile scenarios all run against the packaged Creator. Visual proof is
  `output/playwright/railix-creator-http-ingress.png` and
  `output/playwright/railix-creator-http-ingress-mobile.png`.
- The committed trusted-Step monolith itself now contains CLI plus custom, flow-event, and Step-event HTTP
  declarations. Three independent subprocess E2Es run the Maven-built JAR directly at `/greet`,
  `/v1/flows/trusted-greeting/events`, and `/v1/flows/trusted-greeting/steps/prefix/events`; the removed helper can
  no longer inject a test main, flow, lock, or manifest into a rewritten HTTP artifact.
- Rootkeeper found an unbounded executor close and a start/stop race; both drove the single gated lifecycle and
  interrupt-ignoring regressions. Gatekeeper found absent UI authoring, stale support claims, the rewritten package
  proof, and untested null/blank Step selectors; each finding now has public-boundary proof.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,220 executions with zero failures, errors, or skips:
  650 core, 31 standard-library, 245 Creator component, 126 desktop browser/package, 116 mobile browser, and
  52 external flow/build/package/browser cases.
- Coverage is 99.56% line / 98.04% branch for core, 100% / 100% for the standard library,
  95.25% / 94.16% for Creator, and 100% line for the external application. Combined line coverage is 98.59%;
  product branch coverage is 97.42%.
- Complexity audit: the product remains three modules and one flow/value/execution model, with 30 production Java
  files, 7,270 Java lines, 2,070 UI lines, and zero third-party runtime dependencies. This checkpoint adds one
  behavior-owned production file, 700 production Java lines, and 218 UI lines; it adds no module, interface,
  framework, reflection path, compatibility layer, trigger registry, or shutdown hook. The 226,565-byte shaded JAR
  depends only on `java.base`, `java.xml`, and `jdk.httpserver`; repeat packaging emits SHA-256
  `6b660ebb0d8bc6d82a0e01bf94b1f1c70c6ed34e139b2c2125fb216c5556a72f`. The committed external JAR emits
  `88f27407bd42e44b6a2200224b5a12404fb13d4cef05729818d5238eb0de35e1`. No Railix JVM or port-18080 listener
  survives the gate.

Checkpoint 4 evidence:

- The compiler accepts one `socket` declaration per flow with exact integer `port`, `timeoutMillis`, and
  `maxConnections` bounds, and any number of inputless `scheduled` declarations with exact integer
  `intervalMillis`, `initialDelayMillis`, and `maxConcurrentRuns` bounds. Duplicate/conflicting listeners,
  HTTP/socket port collisions, scheduled flows with inputs, unknown fields, malformed values, and arbitrary trigger
  types have isolated deterministic compiler E2Es.
- Socket ingress is JDK-only IPv4 loopback TCP with a four-byte signed big-endian length followed by one strict UTF-8
  JSON object of 1 byte through 1 MiB. Replies use the same framing. Each connection processes frames serially;
  malformed events receive one rejection and remain reusable, while invalid/truncated/late framing receives one
  bounded rejection and closes. Absolute read/write deadlines, a `1..256` hard connection limit, immediate
  backpressure without a queue, slow peers, repetition, concurrency, and port reuse have real-socket E2Es.
- Schedules use monotonic fixed-rate clocks from application readiness, feed `{}` to inputless flows, allow
  `1..256` concurrent runs, and skip overlaps without queueing or catch-up. Restart resets the schedule epoch.
  Success writes the canonical reply to stdout; rejection/failure writes to stderr; later ticks continue.
- One `ApplicationRuntime` owns HTTP, socket, and schedule startup, readiness, stop, cancellation, and rollback.
  Ingress opens before readiness but rejects admission until startup succeeds; the readiness line is published only
  after admission opens. Stop closes ingress immediately, interrupts owned work, releases listeners, and is
  idempotent. Trusted Steps must return promptly when interrupted. A non-cooperative Step is unsupported and can
  retain its execution state until it returns or the process exits.
- Creator edits socket and schedule declarations directly in the canonical flow model without a trigger registry or
  parallel authoring model. Enable/edit/remove/reload/save, exact compiler feedback, collisions, invalid bounds,
  multiple schedules, inputful-flow rejection, and desktop/mobile layout have browser proof. Screenshots are
  `output/playwright/railix-creator-application-ingress.png` and
  `output/playwright/railix-creator-application-ingress-mobile.png`.
- The committed trusted-Step monolith runs its real shaded JAR through length-framed socket ingress. A separate
  inputless shaded application assembled from the same packaged runtime proves scheduled readiness, execution,
  canonical output, and termination. The package harness drains child output before teardown, and no rewritten
  transport runtime or fake ingress is used.
- Rootkeeper selected cooperative interruption as the only honest trusted-Step lifecycle contract. Gatekeeper found
  stale package assertions, missing package proof, a readiness race, exceptional worker-start cleanup, a missing
  write-deadline scenario, and scheduler timing risk. Each reachable finding now has an isolated regression; the
  final independent review found no normal-operation correctness, launcher-lifecycle, or package blocker.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,378 executions with zero failures, errors, or skips:
  701 core, 31 standard-library, 304 Creator component, 149 desktop browser/package, 139 mobile browser, and
  54 external flow/build/package/browser cases.
- Coverage is 99.57% line / 98.10% branch for core, 100% / 100% for the standard library,
  95.13% / 90.85% for Creator, and 100% line for the external application. Aggregate product and external coverage
  is 98.27% line / 96.49% branch.
- Complexity audit: the product remains three modules and one flow/value/execution model, with 32 production Java
  files, 8,220 Java lines, three Creator UI files with 2,292 lines, and zero third-party runtime dependencies.
  The checkpoint adds two behavior-owned production files, 950 production Java lines, and 222 UI lines over
  checkpoint 3; it adds no module, interface, framework, reflection path, compatibility layer, trigger registry, or
  shutdown hook. The 249,136-byte product JAR depends only on `java.base`, `java.xml`, and `jdk.httpserver` and has
  SHA-256 `7070e018457cd84047f277e2273155380a9919b3adfa0905630d6a61eefb25f2`; the 254,941-byte committed external
  JAR has SHA-256 `01e80f48722672bcf8c8c51e556e61d8835843072727f02f96ddbaf2c8bb1f97`. No Railix JVM or
  port-18080/18081 listener survives the clean gate.

Checkpoint 5 slice 1 evidence:

- `file.read` is one ordinary trusted Step with explicit `path:string` input, normalized `value:any` output,
  exact `json`/`yaml`/`xml` configuration with `json` default, and `read`, `missing`, and `rejected` outcomes.
  It admits only regular files, reads at most 1 MiB plus one probe byte, and delegates accepted bytes to the
  canonical normalizer. It adds no root, format guessing, retry, cache, permission wrapper, executor, or alternate
  runtime path.
- Nineteen isolated flow E2Es cover JSON default, YAML, XML, missing, directory, FIFO, invalid path, empty,
  malformed, invalid UTF-8, BOM, over-depth, overlong number, exact-limit, oversized, unsupported/wrong-type
  format, repetition, and 32-way isolated concurrency. Creator catalog/compiler/package scenarios prove the same
  Step through HTTP, desktop/mobile browser, and the shaded launcher.
- `examples/file-read-app` is a committed runnable whole-flow application. Running
  `./railix run examples/file-read-app` reads its real YAML resource and emits
  `{"value":{"active":true,"name":"Railix"}}`.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,405 executions with zero failures, errors, or skips:
  701 core, 50 standard-library, 305 Creator component, 153 desktop browser/package, 142 mobile browser, and
  54 external flow/build/package/browser cases.
- Coverage is 99.45% line / 97.91% branch for core, 97.46% / 97.73% for the standard library,
  95.13% / 91.07% for Creator, and 100% line for the external application. Aggregate product and external
  coverage is 98.13% line / 96.36% branch.
- Complexity audit: the product remains three modules and one flow/value/execution model, with 33 production Java
  files, 8,315 Java lines, three Creator UI files with 2,292 lines, and zero third-party runtime dependencies.
  This slice adds one behavior-owned production file and 95 production Java lines; it adds no module, interface,
  framework, reflection path, compatibility layer, executor, or UI-specific execution code.
- Gatekeeper's only finding was a blocking FIFO admitted as a readable path. Its public-flow regression failed
  before the fix, then passed after non-regular sources were rejected before stream creation.
- The 252,796-byte product JAR depends only on `java.base`, `java.xml`, and `jdk.httpserver` and has SHA-256
  `db00177fa3ab82c824aeafd61e17d925cea4a6aa7c8158852f34be640dcfe9be`; the 258,601-byte committed external
  JAR has SHA-256 `4f2b98266adba148ff6b34b5d087b34fa2d472fffddc6b2669a7a1c04cada51e`. No Railix JVM or
  port-18080/18081 listener survives the clean gate.
- At slice 1 closure, local write/delete and persistence, outbound network Steps, runtime settings and secrets,
  capability-specific resource lifecycle closure, and the complete capability audit remained unsupported.

Checkpoint 5 slice 2 evidence:

- `file.write` accepts explicit `path:string` and `value:any`, writes at most 1 MiB of canonical compact UTF-8 JSON,
  defaults `overwrite` to `false`, and returns `written`, `conflict`, or `rejected`. `file.delete` accepts the same
  explicit path contract and returns `deleted`, `missing`, or `rejected`. `file.read` is behavior-versioned to reject
  final symlinks and paths over 4,096 UTF-8 bytes consistently with both mutation Steps.
- Writes never create parents. One sibling temp file is fully written and `force(true)` is called before a required
  atomic move; unsupported atomic move rejects without a copy/delete fallback. One interruptible process-local lock
  serializes writes/deletes without retaining events, values, or paths. Reads stay lock-free.
- Sixty-seven isolated `FileStepsFlowE2eTest` scenarios cover normalized reads, canonical writes, JSON null,
  exact/oversized data, overwrite/default conflicts, missing/invalid/empty/overlong/root paths, parent/final
  symlinks, directories, FIFOs, permission failures, invalid canonical values, repetition, 32-way write/delete/read
  concurrency, independent paths, and 64 contended cancellations followed by lock reuse and zero temp residue.
- Creator consumes the new definitions generically: two exact HTTP catalog tests and four real browser scenarios
  prove full Step rendering, the visible `overwrite:false` default, compiler-owned wrong-type feedback, and a real
  write-read-delete flow at desktop and mobile widths. No Creator production or UI line changed.
- `examples/file-persistence-app` is a committed runnable whole-flow application. Running
  `./railix run examples/file-persistence-app` emits
  `{"value":{"active":true,"name":"Railix"}}` and leaves neither its target nor a `.railix-*` temp file.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,464 executions with zero failures, errors, or skips:
  701 core, 98 standard-library, 307 Creator component, 158 desktop browser/package, 146 mobile browser, and
  54 external flow/build/package/browser cases.
- Coverage is 99.45% line / 97.91% branch for core, 95.41% / 95.95% for the standard library,
  95.13% / 90.85% for Creator, and 100% line for the external application. Aggregate product and external
  coverage is 98.00% line / 96.27% branch.
- Complexity audit: the product remains three modules, 33 production Java files, and one flow/value/execution
  model. Slice 2 consolidates all three file definitions into the existing owner, adding 190 net production Java
  lines for 8,505 total; the three Creator UI files remain 2,292 lines. It adds no production file, module,
  interface, framework, reflection path, compatibility layer, third-party runtime dependency, or UI-specific path.
- Rootkeeper found no descriptor, buffer, static-root, lock-release, or normal temp-cleanup defect. Stable paths
  under Railix-owned mutation are the supported boundary: external processes can race path checks, and this is not a
  sandbox. Portable Java cannot fsync directory entries, so rename/delete power-loss durability is not claimed.
  Abrupt process/host loss can leave a recognizable temp; Railix performs no unsafe ambient scavenging.
- The 255,478-byte product JAR depends only on `java.base`, `java.xml`, and `jdk.httpserver` and has SHA-256
  `eafa5b3b3b54793c6f7e4eac605d736f1c52e16a4a50650013a00ed7a04dbb21`; the 261,283-byte committed external
  JAR has SHA-256 `f622bdc85433ccb113b8a52beb096c776f7535c7d83f13784e29dafcae6587aa`. No Railix JVM,
  port-18080/18081 listener, persistence target, or temp file survives the clean gate.
- Still unsupported in checkpoint 5: runtime settings and secrets, complete capability-specific
  lifecycle closure, and the full capability audit.

Checkpoint 5 slice 3 evidence:

- `http.get`, `http.post`, and `http.delete` are three ordinary trusted Steps with one implementation
  owner and one execution path. All accept explicit URL and headers; POST sends canonical JSON. They
  return status, lowercase array-valued headers, normalized body, and exact success/redirect/client-error/
  server-error/other/rejected outcomes. `format` defaults to JSON and supports exact YAML/XML;
  `timeoutMillis` defaults to 30,000 and compiles only for integer values `1..300000`.
- URLs are absolute HTTP/HTTPS with a host, no user info/fragment, and an 8,192-byte UTF-8 limit.
  Explicit request headers have 64-name and 16 KiB name/value limits, case-insensitive uniqueness,
  deterministic token/value validation, and a process-property-independent restricted-name set.
  Redirects are returned without following. Request/response bodies stop at 1 MiB plus one probe.
  Response headers normalize repeated fields and reject above 64 unique names or 16 KiB.
- One monotonic deadline covers connection, headers, and every response read, including stalled and
  trickled bodies. Java URL connections expose no request-write timeout; a blocked POST write remains
  cancellation-bound and must cooperate with interruption. The response-header limit is enforced after
  the JDK parser admits headers and is not presented as a raw-wire sandbox guarantee.
- Rootkeeper reproduced the rejected first implementation's linear resource spike: 64 concurrent calls
  created 64 selector threads and raised descriptors from 15 to 335. Its failing public-flow regression
  now passes through synchronous URL connections with explicit `disconnect()` ownership. Gatekeeper
  independently found the old post-header timeout and process-configurable restricted-header defects;
  both have isolated regressions. The requested follow-up worker did not return, so no unavailable
  evidence is claimed.
- Eighty-three isolated `HttpStepsFlowE2eTest` scenarios cover all methods, JSON/YAML/XML, empty and
  malformed bodies, every status boundary, redirects, exact/over body, URL, request-header and
  response-header bounds, multibyte data, malformed URLs/headers, compiler defaults and timeout bounds,
  refusal/TLS/peer-close failures, pre- and post-header timeout, trickle deadline, interruption, same-flow
  repetition, 32-way value/header isolation, 64 blocked calls, and a 1,000-request descriptor/thread soak.
- Creator consumes the definitions generically: three exact catalog component cases, three packaged
  browser scenarios at desktop and mobile widths, and one shaded-launcher package smoke prove contracts,
  visible defaults, compiler diagnostics, and real execution. No Creator production or UI line changed.
  `examples/http-get-app` is a committed whole-flow app; `jwebserver` plus
  `./railix run examples/http-get-app` emits
  `{"body":{"active":true,"name":"Railix"},"status":200}`.
- `mvn -q -Ptrusted-step-monolith clean verify` passes 1,557 executions with zero failures, errors, or
  skips: 701 core, 181 standard-library, 310 Creator component, 162 desktop browser/package, 149 mobile
  browser, and 54 external flow/build/package/browser cases.
- Coverage is 99.22% line / 97.47% branch for core, 96.03% / 90.24% for the standard library,
  95.13% / 90.85% for Creator, and 100% line for the external application. Aggregate product and
  external coverage is 97.77% line / 95.32% branch.
- Complexity audit: the product remains three modules and one flow/value/execution model, with 34
  production Java files and 9,002 Java lines. Slice 3 adds one 481-line behavior owner and 497 net
  production Java lines; the near-500-line review trigger was accepted only after independent review,
  removal of the per-call client path, and proof for three methods and their shared bounds. The Creator
  UI remains three files and 2,292 lines. No interface, module, framework, reflection path,
  compatibility layer, or third-party runtime dependency was added.
- The 265,065-byte product JAR depends only on `java.base`, `java.xml`, and `jdk.httpserver` and has
  SHA-256 `c3f90699d50184bdf67f0235482e549519a5dd1048a139d181838c533214d210`; the 270,870-byte
  external JAR has SHA-256 `2acfd9a7394e4d4ae1c6872570d96d0843d4c61618d7f5ca9afa4e4886d3835b`.
  No Railix JVM, port-18080/18081/18082 listener, persistence target, or temp file survives the gate
  and committed example smoke.
- Still unsupported in checkpoint 5: runtime settings and secrets, the final capability-specific
  lifecycle/cancellation closure, and the complete Creator/package/capability audit.

## 6. Observability And Bounded Work

Status: **Planned**

Goal: make every Step workload measurable without placing telemetry between Steps.

Deliverables include Creator attachment to the built and started application, throughput,
bottlenecks, Step timings, resources, uptime, custom metrics, bounded execution history,
queues with backpressure, cancellation, deterministic overload behavior, heat maps, and
repeatable concurrency/resource soak evidence. This item also owns explicit Merge, Split,
Choice, and bounded Loop composition nodes plus authorable test events, Step output examples,
test context, stop-before-side-effect boundaries, assertions, and generated test environments.

## 7. Trusted Step Permissions And Metering

Status: **Planned**

Goal: let trusted Steps declare filesystem, network, process, environment, and settings
powers, then optionally enforce and measure those boundaries.

Unsupported isolation guarantees must be rejected explicitly. Java sandboxing will not
be claimed where the runtime cannot enforce it.

## 8. Multi-Instance Step Execution

Status: **Planned**

Goal: place eligible Step invocations on compatible Railix instances without changing
local Step semantics.

This includes capability negotiation, bounded request lifecycles, transport, timeout,
cancellation, retries, backpressure, partition behavior, and equivalence proof against
local execution.

## 9. Step-Owned State, Sharding, And Replication

Status: **Planned**

Goal: provide explicit durable Step state with partitioning, replication, repair, backup,
restore, migration, and declared consistency behavior.

Railix will not claim to replace a database or object store before restart, node-loss,
rebalance, corruption, and recovery evidence exists.

## 10. Production Certification And Release DX

Status: **Planned**

Goal: certify the complete supported product from a clean checkout.

Required gates include exhaustive public-entrypoint regressions, minimum 95% line and 90%
branch coverage, long-running memory/resource/cancellation/concurrency soaks, reproducible
artifacts, compatibility and migration fixtures, cross-host installers, signing,
vulnerability evidence, support matrix, and release runbook. No mocks or fake runtime
paths may satisfy release acceptance.
