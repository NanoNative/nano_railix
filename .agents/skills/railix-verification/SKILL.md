---
name: railix-verification
description: "Verify and review Railix changes through regression tests, browser/audio checks, profiling and packaged artifacts. Use static checks for documentation-only work."
---

# Verification And Review

Define the claimed behavior and choose evidence that can disprove it.

## Verify Through Real Boundaries

1. Inspect the affected entrypoint, implementation and tests. Reproduce bugs with a
   failing public-boundary regression before fixing them. Control inputs, time,
   randomness and concurrency; retry observable outcomes instead of arbitrary sleeps.
2. Use compiled artifacts, real HTTP and visible browser controls rather than only
   private helpers. Cover success, absence, rejection, failure and relevant reuse/races.
   Do not hand-edit generated output or fixtures to conceal unsupported behavior.
3. Run from repository root with Java 25. During development use `./mvnw test`;
   before publishing code use `./mvnw --batch-mode --no-transfer-progress clean verify`.
   The latter includes packaged/native and desktop/mobile browser checks.
4. For a focused Creator run, build upstream modules and keep required suites nonempty:

```sh
./mvnw -pl modules/railix-creator -am -Drailix.test.forks=1 \
  -Dtest=RailixValueNullContractTest,PrimitiveStepsCreatorProjectE2eTest,CreatorEditorE2eTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Replace the last class with the affected Creator suite, such as CreatorEditorBrowserIT
or CreatorWorldBrowserIT. Confirm discovery in test reports; a focused run is not the
full gate. Coverage is advisory, not an enforced Maven percentage threshold.

## Inspect The Changed Behavior

- Browser: use existing Java Playwright fixtures and installed Chrome, not a global
  npm CLI. Inspect fresh page state after navigation/menu changes. Test desktop/narrow
  layouts, keyboard typing, and affected renderers/navigation. Keep traces/screenshots
  under the module's target directory; do not substitute private calls for user journeys.
- Performance: compare the same project, viewport/DPR, renderer, motion and warmup.
  Record frame-time distribution, CPU, memory growth, requests and visible object count.
  Separate navigation from steady animation; graphics-process CPU is not GPU utilization.
- Audio: render through playback including master gain; check duration, finite samples,
  peaks/RMS and clipping. Exercise pause/cleanup and listen when possible.
- Packaging: test Creator and generated applications separately, using isolated homes.
  Check production omissions, artifact size and external extensions without a rebuild.
- Documentation: validate metadata, links, portability and Git inclusion. Support status
  claims with evidence, distinguish partial/planned work, and update the owning document
  instead of duplicating counts. Do not start an application build just for Markdown.

## Review And Finish

For review-only work, do not edit or publish. For branch reviews, resolve the requested
base, using its locally available upstream when ahead; run `git merge-base HEAD <base>`
and inspect `git diff <merge-base> HEAD`, not a diff against the base tip. For uncommitted
work inspect staged, unstaged and intended new files, including callers and tests.

Report actionable introduced defects with a demonstrable triggering scenario.
Exclude speculation, intentional changes, unrelated existing issues and cosmetic nits.
Present findings first: severity (P0 critical, P1 urgent, P2 normal, P3 minor), changed
file/line, triggering case and consequence. Say `No findings` when none qualify.
For a broader audit, judge findings against its requested scope, not only new changes.

During implementation, fix in-scope findings and repeat affected checks. Report exact
commands/results and skipped visual, auditory or runtime checks. Close owned browsers,
drivers, servers, audio renders and child JVMs; never broadly kill Node/Java processes.
Commit, push and PR creation each require explicit, single-use permission.
