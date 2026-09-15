---
name: railix-java
description: "Implement and review Railix Java contracts, Step behavior, compilation, generated applications, runtime lifecycle, backend APIs, and persistence."
---

# Java Engineering

Deliver the smallest working behavior through a real public entrypoint.

## Implement

1. Trace the entrypoint through its implementation and existing tests. For a bug,
   add a public-boundary regression and confirm its expected failure before fixing it.
2. Keep ownership clear: core owns values, contracts, compilation and generated-runtime
   capabilities; stdlib owns built-in Steps; Creator owns editing, builds and lifecycle.
3. Extend an existing coherent type before introducing another layer or dependency.
   Express variation through data, functions and existing contracts, not Step IDs,
   example names, wrappers that only forward, or a second execution path.
4. Validate each invariant at its owning parser, builder or ingress. Downstream code
   relies on it. Keep domain null, Java null and absence distinct. Translate exceptions
   once; do not swallow failures or catch and rethrow unchanged.
5. Keep Step handlers stateless and resource ownership explicit. Use atomic persistence
   with revision conflicts; close streams, executors and owned child applications.

## Preserve

- Use Java 25, JDK types, final values and pure functions where practical. Prefer
  standard functional interfaces, meaningful fluent results and records for data.
- Public APIs need Javadocs for inputs, absence and failures. Do not return Java
  null or void except signatures imposed by Java/platform entrypoints. Do not invent
  dummy results. Use Optional for returns/locals, not fields.
- Keep StepDefinition an authoring-time contract. Generate direct calls from its
  generic input algebra and explicit catalog, without reflection, runtime discovery,
  parallel streams, coercion or fallback interpretation.
- Split generated methods/classes when bytecode requires it; do not impose an
  arbitrary product Step limit. Keep output deterministic and the monolith small.
- Groups and appearance are presentation-only. The generated development application,
  not Creator, executes Examples and owns observations. Production output physically
  omits that development machinery. UI-only display changes need no runtime API change.

## Prove

Run from repository root: `./mvnw test` during implementation and
`./mvnw clean verify` before publishing code. Check actual discovered tests.
Exercise valid input, rejection, absence, exceptions, reuse and relevant concurrency.
For compiler/runtime work, build a realistic fixture normally and execute the produced
artifact. Inspect production omissions, repeatability and size; never patch generated
or third-party code to hide a gap. Measure affected hot paths before claiming scale.
Report exact checks and gaps, and stop task-owned test/helper processes.
