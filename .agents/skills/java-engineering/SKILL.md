---
name: java-engineering
description: "Implement and review Java APIs, compilers, generated applications, resource lifecycles, backend services and persistence."
---

# Java Engineering

Deliver the smallest working behavior through a real public entrypoint.

## Implement

1. Trace the entrypoint through its implementation and existing tests. For a bug,
   add a public-boundary regression and confirm its expected failure before fixing it.
2. Locate the owning module and its public contract. Keep authoring/build-time concerns
   separate from runtime behavior; follow the project's documented boundaries.
3. Extend an existing coherent type before introducing another layer or dependency.
   Express variation through data, functions and existing contracts, not plugin IDs,
   example names, wrappers that only forward, or a second execution path.
4. Validate each invariant at its owning parser, builder or ingress. Downstream code
   relies on it. Keep domain null, Java null and absence distinct. Translate exceptions
   once; do not swallow failures or catch and rethrow unchanged.
5. Keep reusable behavior stateless or thread-safe, with explicit resource ownership
   and no retained request data. Built-in and external extensions use the same contract.
   Use atomic persistence with revision conflicts; close streams, executors and owned child applications.
   Distinguish caller-owned from framework-managed resources. Shared resources need
   stable compatibility keys and one cleanup owner; do not close another user's resource.

## Results Before Exceptions

- Return explicit outcomes/results for expected rejection, invalid user data, absence,
  conflicts and ordinary failure/cancellation paths. Do not construct or throw an
  exception only to catch it and convert it back to a result, including in parsers
  and nested execution. Reuse the owning API's result type rather than adding a
  universal wrapper at every layer.
- Validate untrusted values before invoking constructors that assume valid inputs.
  Existing programmer-contract exceptions are not normal data validation. Do not
  silently break a public exception contract while migrating expected failures.
- Catch unavoidable JDK/library exceptions once at the owning boundary and translate
  recoverable failures with actionable context. Preserve cancellation/interruption;
  do not turn fatal JVM Errors into ordinary business outcomes or blanket-catch Throwable.
- A polled cancellation should return its result, not manufacture an exception.
  Stackless exceptions still perform exceptional control flow; do not claim that
  removing them is faster without measuring the actual rejection and success paths.

## Preserve

- Use the project's supported Java release, JDK types, final values and pure functions where practical. Prefer
  standard functional interfaces, meaningful fluent results and records for data.
- Public APIs need Javadocs for inputs, absence and failures. Do not return Java
  null or void except signatures imposed by Java/platform entrypoints. Do not invent
  dummy results. Use Optional for returns/locals, not fields.
- Keep compile-time and runtime contracts distinct. In code generators, prefer direct
  validated calls when targets are known; do not introduce runtime discovery or a
  second interpreter without a current requirement.
- Split generated methods/classes when bytecode requires it; do not impose an
  arbitrary product limit. Keep output deterministic. Do not ship development-only
  machinery in production artifacts when the contract requires its exclusion.

## Prove

Use the repository's build wrapper, test commands and publication gate. Check actual discovered tests.
Exercise valid input, rejection, absence, exceptions, reuse and relevant concurrency.
For compiler/runtime work, build a realistic fixture normally and execute the produced
artifact. Inspect production omissions, repeatability and size; never patch generated
or third-party code to hide a gap. Measure affected hot paths before claiming scale.
Report exact checks and gaps, and stop task-owned test/helper processes.
