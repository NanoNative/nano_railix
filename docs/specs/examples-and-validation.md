# Examples And Creator Validation

## Human Review

Status: **Accepted direction; detailed contract not implementation-ready.** Recorded
on 2026-09-19 from the user's documentation-planning request and explicit answers.
No parser, project file, endpoint or generated application changes in this task.

Outcome: use actual Example execution to build and connect Flows, discover fields and demonstrate
which Steps execute. Optional assertions add checks of expected results; they are not what makes
an Example useful or required for authoring.

Owns: Example input/expectation ownership, validation meaning and acceptance criteria.
Does not own: Step execution, visual groups, production instrumentation, assertion
implementation or a new dependency. See [observation](observation.md) for current
execution boundaries and [ADR 0022](../adr/0022-example-validation-and-release.md)
for the decision and its consequences.

### Accepted Decisions

| Decision | Acceptance source |
| --- | --- |
| Phase 1 succeeds when the Example finishes without an unexpected error. | User's documentation-restructuring request. |
| Phase 2 adds optional visual expectations on each Example's Step outputs. | Same request: AssertJ-inspired authoring, with Creator validating and the binary executing. |
| Versioned `railix.examples.json` owns Example inputs and expectations together, linked to Trigger and Step IDs. | Explicit answer accepting the separate-file recommendation. |
| An expectation on a Step that the Example does not reach fails validation. | Explicit answer: "Fail: the expected Step must be reached". |
| Examples provide execution/coverage evidence and actual fields for mapping and connecting Steps, including fields created earlier in a Flow. | User's subsequent clarification distinguishing Examples from passing tests. |
| Unreachable Steps should be omitted unless explicitly kept. | Same clarification; the meaning of unreachable and retention behavior remain blocked in the system-model decision below. |

These decisions do not approve a particular assertion grammar, a JSON schema, a
migration algorithm or a production-build gate implementation.
The latest clarification supersedes the roadmap's blanket "every Example passes tests before
production compilation" wording. It does not remove optional expectation failures or authorize
silently pruning a branch merely because a demonstration did not traverse it.

### Current Versus Target

| Concern | Current implementation | Accepted target |
| --- | --- | --- |
| Executable behavior | `railix.project.json`: flat nodes, links and Step inputs; Trigger nodes also contain Examples. | Project file owns executable behavior; Example cases move out. |
| Example authoring | Trigger `examples` entries contain `name`, `payload`, optional `context`; no expectations. | Inputs and optional expectations together in `railix.examples.json`. |
| Presentation | Optional `railix.creator.json`, ignored by compiler/runtime execution. | Still presentation only; no expectations or validation policy. |
| Execution | Generated development app runs compiled Examples once through normal routes and owns their results. | Same owner and execution path. |
| Validation | Execution results and coverage are visible; no output-assertion evaluator or assertion-based release gate. | Creator validates current application-returned evidence. |
| Production | Example manifest, development traces/metrics and management endpoints are physically omitted. | No expectation evaluator or test-only dependency added to the production application. |

Separating the authoring file does not move Example execution into Creator or make
the development app load mutable authoring files at request time. Changes to Example
inputs must reach the matching development artifact; the build/invalidation details
remain to be specified. Starter Example values in Step definitions remain templates,
not an alternative owner of authored project cases.

## Behavior

`MUST` below describes the accepted target, not completed implementation.

| ID | Phase | Observable requirement |
| --- | --- | --- |
| EX-001 | 1 | Creator MUST regard a completed Example without an unexpected error as successful without requiring output expectations. This proves execution, not every possible business result. |
| EX-002 | File separation | Example inputs and optional expectations MUST share one versioned `railix.examples.json`, linked to Trigger/Step IDs rather than labels or visual groups. Executable behavior and presentation retain their separate owners. |
| EX-003 | Both | The application MUST execute the Example through its normal generated path. Creator MUST validate returned evidence without re-executing Steps or compiling assertion logic into the application. |
| EX-004 | 2 | Users MUST be able to author optional expectations visually for a particular Example and Step output. A reached output that violates an expectation MUST fail that Example's validation. |
| EX-005 | 2 | When an expected Step is not reached, Creator MUST fail validation and identify that Step, rather than skip the expectation or report success. |
| EX-006 | Both | Creator MUST NOT report validation success using incomplete evidence or evidence from a different application/Example revision. Execution completion and expectation results MUST remain distinguishable. |
| EX-007 | Authoring | Creator MUST expose the selected Example's actual Step inputs/outputs and created context/payload fields for downstream mapping and connection, with Example/Step provenance. Observed values MUST NOT be substituted for the Step's declared contract. |
| EX-008 | Coverage | Creator MUST show which Flows and Steps the current Example set executed, and distinguish uncovered Steps, unsuccessful/incomplete execution and failed optional expectations. Reaching a Step MUST NOT by itself report its successful completion or prove all of its possible outcomes. |

EX-006 follows from checking the selected case, not an older or unobserved execution.
Its concrete pending/unavailable/stale status vocabulary is still open. A missing
output is not a null output, and neither is evidence that a Step was unreached.
Current trace retention/size limits can make output evidence unavailable; do not
silently increase them or turn absence into a passing comparison.

### Examples, Coverage And Inclusion

The authoring goal is to demonstrate every Flow and Step intended for execution, not to demand
assertions on each value. A field created by one Step becomes visible for mapping after that
Example reaches the relevant point. If another Example omits it or gives it another supported
shape, Creator retains that provenance instead of inventing one always-present merged payload.
Examples show concrete behavior; they do not prove a dynamic field exists for every future input.

Keep four facts separate: connection/reachability in the graph, observed Example coverage,
execution completion and optional assertion results. Coverage is measured against the authored
graph/build revision, not made green by hiding omitted Steps from its denominator. Explicitly kept
but unobserved behavior stays identifiable. The [compilation-selection contract](system-model.md#planned-compilation-selection)
owns what is emitted; coverage transport stays in observation. The development build must exist
before it can produce that evidence, so compilation must not depend circularly on its own run.

### Visual Authoring Proposal

Recommended, not yet an accepted operator catalog:

```text
Example: valid customer
Step: Normalize customer
Output: value -> customer.name
Expectation: equals "Ada"
```

Offer compatible comparisons from the selected output shape, with actual versus
expected results shown next to a failure. AssertJ is an interaction inspiration,
not a Java dependency, scripting language or second execution engine. A small
declarative operator set is preferable to arbitrary executable assertion code.
Do not create operator registries or UI abstractions until the first agreed cases
need them. Outputs can be structured; field selection semantics need agreement.

## Acceptance

All checks below are **planned**, not passed by this documentation change. They
must use a real generated application and the public Creator boundary.

| Requirement | Given / When / Then | Planned evidence / gap |
| --- | --- | --- |
| EX-001, EX-003 | Given an Example with no expectations, when its generated Flow completes without an unexpected error, then Creator reports execution success without invoking any Step itself. | Creator integration/browser case; agree error classification first. |
| EX-001, EX-006 | Given a failing, cancelled or interrupted Example, when Creator receives its result, then it cannot present that execution as successfully completed. | Generated-child fault, cancellation and deadline scenarios; exact labels remain open. |
| EX-002 | Given a saved case with expectations, when the project is reopened, then the same case and referenced IDs are restored independently of presentation metadata. | Persistence/migration tests after schema and stable Example identity are agreed. |
| EX-003, EX-004 | Given a reached Step output and a matching expectation, when Creator checks returned evidence, then validation passes without a second Step invocation. | Public test with an invocation-counting side effect in an isolated fixture. |
| EX-004 | Given a reached Step output that differs, when its expectation is checked, then the case fails and shows the affected Step and actual/expected values. | Visual comparison scenarios after operators and output selectors are agreed. |
| EX-005 | Given an expectation on branch A, when the Example follows branch B, then the case fails because the expected Step was not reached. | Generated branching Flow with an expectation on the untaken branch. |
| EX-006 | Given truncated/unavailable output or a replaced child/artifact, when old evidence arrives, then no passing assertion is inferred from it. | Public response/rolling-replacement test; bounded evidence policy still needs agreement. |
| EX-003 | Given a production build, when its artifact is inspected and run, then no test expectation evaluator, assertion library or Example file is required at runtime. | Extend existing production-omission artifact tests when implementation begins. |
| EX-007 | Execute an Example that creates a new nested field, map it downstream, then select a case where it is absent or different. | Planned public Creator journey; preserve per-case values and absence without type coercion. |
| EX-008 | Execute one branch successfully, reach another Step that fails, and leave a branch unvisited. | Planned; show coverage, completion and expectations separately without treating omitted or retained Steps as demonstrated. |

Existing tests such as `GeneratedApplicationExampleSuiteE2eTest` and
`GeneratedApplicationVariantsE2eTest` establish execution ownership and production
omission. They do not prove these new expectation or storage behaviors.

## Open Decisions

These block only their dependent implementation slices, not this documentation work:

1. **Resolve now, before compilation-selection work:** agree the meaning of unreachable and
   the explicit keep override in the [owning contract](system-model.md#planned-compilation-selection).
   Demonstrated execution, optional assertions and emitted code are not one test-pass flag.
   Before a release gate is implemented, decide how failed/incomplete execution, coverage gaps,
   failed expectations and explicit retention affect release eligibility; no blanket gate is selected.
2. **File schema and migration:** choose stable Example IDs, reference validation,
   rename/delete behavior, atomic migration from embedded cases, and behavior when
   the new file is missing, invalid or stale. Do not maintain two writable sources.
3. **First assertion slice:** agree output selectors, the first comparisons, exact
   number/null/missing rules and how multiple expectations combine. Loops and repeated
   Step visits are not supported today; do not invent their assertion policy now.
4. **Before validation/headless delivery:** choose a public Creator validation boundary usable
   outside the browser, bounded access to required outputs, result invalidation and
   diagnostics. This is a recommendation, not an implemented endpoint.

Expected-error assertions, fixtures/test services, control of real I/O side effects
and protocol-specific clients require explicit scope. Current automatic Examples
are real execution, not a sandbox. A release-green claim must never imply that all
possible inputs or all external failures have been proven safe.

Before database/I/O Examples are enabled, specify isolated fixture ownership, concurrent-case
separation, cleanup failure and prevention of accidental production writes. Decide which tests
use real isolated services and which explicitly fake responses; a fake is not proof that an
external integration works. [Data administration](database-and-data-access.md) permissions do
not themselves authorize automatic test execution against that data.

### Proposed I/O Isolation

The user suggested sequential execution and batching Examples with the same fake data to keep
artifact overhead low. Recommendation awaiting approval: reuse expensive fixture startup, not
mutable case state. Each Example starts from its declared baseline; reset or isolate writes,
fake-response cursors, clocks and resources between cases. Failed cleanup invalidates dependent
cases rather than allowing a misleading pass. Sequential execution alone is not isolation.
Sequential Example success also does not prove concurrent production safety; real resource and
concurrency checks remain separate acceptance evidence for the affected capability.

Keep normal handlers, routing and value processing identical to production. Bind an explicitly
selected fake at the external I/O boundary for development, instead of adding a different test
implementation of the Flow or a per-Step production test branch. Production omits fixture data,
fake dispatch and assertion evaluation. The required third-party I/O binding contract is not yet
selected; generated-source/artifact inspection and measured overhead must prove this separation.

Faked runs must be distinguishable from real isolated integration runs. A fake does not prove
protocol, driver, storage or failure behavior. Decide fixture identity, reset guarantees and
safe environment admission before automatic I/O Examples are implemented; grouping by matching
fake payloads alone is insufficient evidence that cases can share a fixture.

The [roadmap](../roadmap.md#current-sequence) owns sequencing. Approval of this
design does not authorize runtime implementation, file migration or publication.
