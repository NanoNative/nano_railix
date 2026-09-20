# ADR 0021: Total And Fallible Primitive Contract

## Status

Accepted on 2026-07-30 and refined on 2026-08-06. The [Primitive contracts](../specs/standard-library.md) are stable;
only catalog rows marked `Done` are implemented. Standard Field Manipulation version 2 applies the
total host policy defined here.

## Context

Primitive Steps must stay as small as ordinary Java functions while still giving Creator,
the compiler, and the generated application enough information to author and execute them
without hidden conversion or exception behavior. The initial five Primitives are total and
always return `ok`; `text.to-number` is the first fallible Primitive. Collection and percentile
operations add expected `empty` and `invalid` results without exceptions. The containing
ordinary host Step, not a special kind, decides whether those results become graph routes or
ordinary no-write data handling. Every Primitive-style operation is an ordinary `STEP` definition.

The standard catalog must be finite. "Expose Java" is not a contract: it would produce an
unsearchable, unstable mirror of thousands of classes and methods.

## Decision

Use ordinary stateless unary Steps, not a separate kind or execution engine. Total,
fallible and fallible-with-empty declarations make expected data outcomes explicit;
implementation faults and cancellation remain different from business outcomes.
Generic host composition owns continuation/no-write policy. The standard catalog is
finite, versioned and machine-readable rather than a mirror of Java classes.

The [standard-library specification](../specs/standard-library.md) now owns the
exact contract, catalog/support matrix and acceptance scenarios. Moved on 2026-09-19;
no catalog row was implemented or promoted by that documentation change.

## Consequences

The catalog is deliberately smaller than the JDK but covers the accepted JSON business-data
families. New standard Primitives require a roadmap change and a new matrix row. Existing
semantics never change silently; incompatible behavior requires a Step version increment.
Projects may explicitly register trusted custom Primitives without waiting for the standard
catalog.

JDK regular expressions are excluded from `stdlib-1`: catastrophic backtracking cannot be
reliably interrupted or deadline-bounded in plain Java. Regex matching requires a separately
accepted bounded implementation before it can enter the standard catalog.

The collection checkpoint may add only the minimum element refinement proven necessary by
aggregation and percentile. It may not introduce a parallel value model or general-purpose
type language.

## Rejected Alternatives

Exceptions, Java `null`, failed-conversion-to-null behavior, automatic trim or case folding,
format guessing, a dedicated Primitive kind or execution engine, nested outcome maps, outcome aliases, runtime
type inference from handler code, one wrapper for every JDK method, and a second lightweight
execution engine are rejected.

## Evidence

See [catalog evidence](../specs/standard-library.md#evidence) and [roadmap Item 3](../roadmap.md#3-primitive-contract-and-standard-library).
