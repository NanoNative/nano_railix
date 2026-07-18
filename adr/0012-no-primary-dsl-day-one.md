# ADR 0012: No primary DSL day one

## Status

Accepted for blueprint.

## Context

Railix II must remain UI-first, local-first, step-first, and packageable as a standalone Java app. The system should avoid hidden infrastructure layers and avoid architectural throwaway decisions.

## Decision

The canonical app plan is edited by UI and CLI. Language DSLs may generate plans later but are not the source of truth.

## Consequences

Positive:

- Keeps the kernel small and understandable.
- Supports visual editing and AI-assisted implementation.
- Avoids framework-heavy startup paths.
- Preserves future distributed execution options.

Negative:

- More contracts must be designed early.
- Some capabilities require standard packs before they become useful.
- The first implementation must resist the temptation to add convenient shortcuts that poison the long-term model.
