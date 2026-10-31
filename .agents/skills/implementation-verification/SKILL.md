---
name: implementation-verification
description: "Verify and review changes through public-boundary regressions, browser/audio checks, profiling and packaged artifacts. Use static checks for documentation-only work."
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
3. Discover the repository's toolchain, commands, coverage policy and publication gate.
   Build prerequisites for focused tests and confirm nonempty discovery in reports;
   a successful command with skipped suites is not evidence. A focused run is not the full gate.
   Direct test-plugin goals can load installed dependencies despite an earlier build.
   Compile in the same reactor invocation and verify the actual runtime classpath.
4. Protect active previews and user outputs from clean builds. Do not run competing
   builds against the same output directory. Use an isolated snapshot when necessary.

## Inspect The Changed Behavior

- Browser: use the project's supported fixtures and browsers. Inspect fresh page
  state after navigation/menu changes. Test desktop/narrow
  layouts, keyboard typing, and affected renderers/navigation. Keep traces/screenshots
  in ignored build output; do not substitute private calls for user journeys.
- Performance: compare the same project, viewport/DPR, renderer, motion and warmup.
  Record frame-time distribution, CPU, memory growth, requests and visible object count.
  Separate navigation from steady animation; graphics-process CPU is not GPU utilization.
- Audio: render through playback including master gain; check duration, finite samples,
  peaks/RMS and clipping. Exercise pause/cleanup and listen when possible.
- Packaging: test authoring tools and generated applications separately, using isolated homes.
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
