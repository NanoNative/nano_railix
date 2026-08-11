# ADR 0015: Explicit Step Contract And Lock

## Status

Superseded on 2026-07-29 by [ADR 0020](0020-creator-first-application-graph.md).

## Context

Step development must stay small while the compiler, Creator, and package build share one
exact contract. Runtime discovery would hide dependencies and make whole-flow builds
non-reproducible.

## Decision

A Step is one `StepDefinition` containing its id, implementation version, authoring kind,
ports, fixed configuration, outcomes, and trusted in-process Java handler. Applications
register definitions explicitly in one `StepCatalog`; there is no reflection, classpath
scanning, annotation processing, or service loading.

`StepDefinition.Kind` changes authoring presentation only. Every kind executes through the
same handler contract. A definition owns one handler instance shared by concurrent runs, so
the handler must be stateless or thread-safe. The compiler derives a canonical dependency
lock from the authored flow and only its referenced Step contracts. Packaged execution
verifies that lock before running. Handler bytecode is not inspected, so a behavior change
requires an explicit Step version change.

## Invariants

- A flow references Steps by exact id and pins id, version, and contract fingerprint.
- Packaging derives dependencies from the complete flow; users do not select Steps twice.
- Trusted handlers run in-process and receive no implied sandbox guarantee.
- Shared handlers must not keep unsynchronized per-run mutable state.
- Test-only registration paths cannot satisfy package acceptance.

## Consequences

Step authors get a small Java API and deterministic dependency failures, but they own version
discipline for behavior changes that do not alter the visible contract.

## Rejected Alternatives

Reflection, service providers, annotations, generated registries, runtime downloads,
selected-Step packaging, and a second lightweight-operator runtime are rejected.

## Evidence

- [`StepDefinition.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepDefinition.java)
- [`StepCatalog.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepCatalog.java)
- [`StepLock.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/flow/StepLock.java)
- [`TrustedGreetingMain.java`](../examples/trusted-step-monolith/src/main/java/example/railix/welcome/TrustedGreetingMain.java)

## Deferred Decisions

Permissions, metering, remote eligibility, and durable Step state remain Items 7-9.

## Historical Disposition

This supersedes the implemented Step-first and standard-pack fragments of deleted ADRs 0002,
0006, and 0008. Their provider, pack, operator-AST, and future-runtime layers are not retained.
