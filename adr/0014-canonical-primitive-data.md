# ADR 0014: Canonical Primitive Data

## Status

Accepted on 2026-07-27.

## Context

Steps, flow compilation, transports, and Creator need one deterministic value boundary.
Format guessing or parallel document models would make mappings and diagnostics ambiguous.

## Decision

`RailixValue` is the only runtime data model: explicit null, boolean, number, string, array,
and object values. Java `null` is never a Railix value. Values are immutable and preserve
deterministic object order.

`RailixData.normalize` accepts an explicit format and bounded UTF-8 bytes. Strict JSON,
constrained block YAML, and typed XML normalize to the same value model and canonical compact
JSON. Unsupported syntax, malformed input, depth, size, number, and encoding failures return
stable diagnostics. Railix never guesses a format or silently drops document semantics.

`NUMBER` is an exact `BigDecimal` whose unsigned canonical plain magnitude is at most 1,024
characters. An optional leading minus is syntax rather than magnitude, so negating a valid
number cannot leave the domain. Serialized transport limits still count every byte, including
that minus. JSON, YAML, XML, canonical writing, and successful Step output use this one rule.

## Invariants

- Every Step input/output and flow event uses `RailixValue`.
- Canonical JSON is the stable serialized representation.
- Normalization is bounded before untrusted input can become runtime state.
- A successful numeric Step output must fit the canonical number domain before preview
  capture or workflow-context mutation.
- Unsupported YAML/XML syntax rejects instead of approximating another structure.

## Consequences

Cross-format data and numeric closure are predictable, while full YAML and arbitrary
document-shaped XML remain outside the supported grammar.

## Rejected Alternatives

Java object graphs, reflection-based binding, blobs/references in the primitive model,
format guessing, separate format-specific trees, and implicit lossy conversion are rejected.

## Evidence

- [`RailixValue.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/RailixValue.java)
- [`RailixData.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/RailixData.java)
- [`RailixJson.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/RailixJson.java)
- [`RailixYaml.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/RailixYaml.java)
- [`RailixXml.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/RailixXml.java)

## Deferred Decisions

Any grammar expansion requires explicit normalization and rejection fixtures; it is not a
compatibility promise for currently unsupported syntax.

## Historical Disposition

This supersedes the value fragment of deleted ADR 0005. Its `Patch`, `Selector`, reference,
and parallel shape architecture is not retained.
