# ⏳ TODO — TICKET-096: Mermaid diagram export

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Let the Railix build DSL export Mermaid diagrams for the defined app and rail structure.

## Expected Scope

- source actors as ingress nodes
- named fragments/steps/operators as graph nodes
- terminal outcomes as end nodes
- app-level topology where available

## Constraint

The export can only be as rich as the named structure in the definition.
Anonymous lambdas may need to fall back to generic labels such as `step`, `map`, or `verify`.

## Open Questions

- Should Mermaid export be a default generated artifact or opt-in?
- Do we generate one diagram per rail, one app overview, or both?
- How do we represent branching, optional paths, loops, and actor calls without making the graph unreadable?
