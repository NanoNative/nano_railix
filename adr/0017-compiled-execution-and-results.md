# ADR 0017: Compiled Execution And Explicit Results

## Status

Accepted on 2026-07-27.

## Context

Steps must express business outcomes without owning graph validation, retries, transport
errors, or hidden exception routing.

## Decision

`FlowExecutor` accepts only a compiled flow and immutable object input. Normal flow execution
starts at the compiled entry. A compiler-declared Step event may instead enter directly at
its selected invocation after event inputs pass admission. From either entry mode, the
executor follows one synchronous outcome path to `end`. Each handler receives explicit
admitted or compiled-mapped inputs and resolved configuration, then returns one declared
`StepResult`. Runtime validates handler outputs and follows the compiled route.

The public result is exactly one `RunResult`: succeeded, rejected, failed, or cancelled, with
completed-Step history. Expected business behavior uses declared outcomes. Contract faults and
unexpected implementation exceptions become explicit failures at the execution boundary;
Steps do not receive an implicit error channel. Cancellation is cooperative Java interruption
observed at bounded execution boundaries.

## Invariants

- Invocation data and execution history are local to one run.
- Runtime does not invent routes, retries, queues, or fallback values.
- A non-cooperative trusted handler can delay shutdown and is not reported as terminated.
- Transport adapters consume `RunResult`; they do not create another execution model.

## Consequences

Execution remains small and deterministic. Queueing, parallel branches, retries, and hard
isolation require later explicit semantics rather than hidden runtime policy.

## Rejected Alternatives

Event buses, exception-driven user control flow, global mutable context documents, implicit
retry/error routes, parallel streams, and a separate executor per Step kind are rejected.

## Evidence

- [`FlowExecutor.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/flow/FlowExecutor.java)
- [`RunResult.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/runtime/RunResult.java)
- [`StepHandler.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepHandler.java)
- [`StepResult.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepResult.java)

## Deferred Decisions

Metrics, bounded queues, backpressure, explicit branching primitives, and long-running soak
certification remain Items 6 and 10.

## Historical Disposition

This supersedes the local execution fragment of deleted ADR 0002. Deleted ADRs 0004 and 0007
described absent signal-log and envelope/reply architectures and remain rejected.
