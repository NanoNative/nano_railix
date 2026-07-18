# ADR 0006: Standard packs outside kernel

## Status

Accepted for blueprint.

## Context

Railix II must remain UI-first, local-first, step-first, and packageable as a standalone Java app. The system should avoid hidden infrastructure layers and avoid architectural throwaway decisions.

## Decision

HTTP, data transforms, store operations, metrics, file operations, and protocol adapters are packs, not kernel features.

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
