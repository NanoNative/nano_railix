---
name: railix-specification
description: Draft, change, or review Railix specifications and sequence an implementation roadmap with a human before coding. Use for collaborative requirements discovery, scope and architecture decisions, acceptance criteria, requirement drift, and implementation-readiness review. Not a mandatory documentation phase for routine fixes or fully specified small changes.
---

# Railix Specification Workflow

Turn an intended outcome into a reviewable contract and an evidence-driven
roadmap. Humans decide material tradeoffs; the AI researches, recommends,
records, and checks consistency. Do not invent decisions to complete a document.

Scope is the repository/project currently being worked on and the requested
area within it. Keep its specifications, ADRs, and roadmap in that project.

## Discover the project's conventions

- Resolve the target project and relevant module from the request, workspace,
  and repository instructions. Do not assume the current directory is the root.
- Inspect existing specifications, planning documents, decision records, and
  their indexes. Read relevant public code and tests to distinguish current
  behavior from the proposed change; do not scan unrelated modules by default.
- Locate existing documents and their ownership, stable IDs, language, status
  vocabulary, and review gates. Preserve those contracts while using the
  canonical paths below; existing locations do not override them.
- Do not impose a framework, technology, delivery process, or empty documentation
  scaffold when arranging the project's documents.
- When sources conflict, expose the conflict and its owner. Ask for authority
  when evidence cannot resolve it; never silently select the convenient version.

Keep one authoritative home for each fact. Specifications own behavior;
roadmaps reference that behavior and own delivery sequence and progress. Follow
existing decision-record ownership. Indexes link rather than repeat contracts.
Use links relative to the owning document for project files and verify that
targets exist. Do not embed machine-local absolute paths or inherit rules and
document ownership from unrelated projects. Relevant external documentation
may be cited as reference evidence; it does not expand the work to those projects.

### Canonical paths

Use these exact, case-sensitive paths relative to the resolved project root,
not the skill directory. They are required locations, not fallbacks:

| Location | Owns |
| --- | --- |
| `docs/specs/` | Behavioral contracts, requirements, and acceptance criteria: what the system must do. |
| `docs/adr/` | Significant architectural decisions, their context, alternatives, and consequences: why a direction was chosen. |
| `docs/roadmap.md` | Implementation order, dependencies, progress, and links to the owning specifications. |
| `docs/verification.md` | Historical verification reports and measurements when a separate evidence record is needed; not current requirements or CI status. |

For authorized documentation changes, move affected documents from other
locations into these paths, preserving stable IDs, unique content, history and
ownership. Update inbound and relative links, navigation and path references;
do not retain duplicate documents or competing roots such as `doc/`, root-level
`adr/` or `ROADMAP.md`. In a review-only task, report noncanonical locations
without moving files. Root `README.md` and `AGENTS.md` remain entrypoints that
link to the canonical owners rather than duplicating their contracts.

Create only artifacts needed for the requested work, not empty folders or
placeholder indexes. Keep acceptance criteria inside the owning specification;
do not duplicate them in a separate acceptance document or the roadmap.

## Align before specifying

1. Establish the user outcome, actors, scope, exclusions, affected public
   boundaries, and what success would look like. Separate the desired outcome
   from a suggested implementation. Trace a concrete user journey through its
   relevant UI, storage, execution, failure and operational boundaries.
2. Identify existing constraints, state and data ownership, failure scenarios,
   compatibility expectations, and material unknowns. Keep facts, proposals,
   accepted decisions, and reversible assumptions visibly distinct.
3. Research repository evidence first. Consult current primary sources when
   changing standards, security, platforms, protocols, or dependencies affect
   the decision. Link evidence to the claim it supports, not a reading list.
4. Recommend one reasoned path. Compare alternatives only when they materially
   change behavior, complexity, cost, or risk. Do not disguise a technology
   preference as a requirement. Translate references to another product or
   standard into specific desired behaviors, exclusions and evidence; inspiration
   does not imply full compatibility, conformance or certification.
5. Ask the next blocking question in plain language: explain the scenario,
   recommendation, consequence, and dependent work. Continue independent
   inspection or drafting while waiting; stop work that requires the answer.

A decision is material when it changes scope, public behavior, architecture,
dependencies, deployment, trust, security, privacy, stored data, compatibility,
operating cost, or the evidence needed to accept the result. Numeric limits
need an owner and rationale; do not invent performance or retention targets.
Record who accepted each material decision and the actual source of acceptance.
Silence, an AI recommendation, or a completed draft is not approval.

When an answer arrives, resolve or supersede the affected question in its owning
specification and reconcile dependent requirements, ADRs and roadmap entries.
Preserve still-unanswered parts; do not re-ask settled questions or count a broad
agreement as acceptance of every suggested detail.

## Write the specification

Use the repository's format. Where none exists, use these compact sections,
omitting irrelevant detail rather than filling a checklist with speculation:

- **Human review:** outcome, scope/non-goals, owns/does not own, key decisions
  and consequences, unresolved questions, and implementation blockers.
- **Behavior:** stable requirement IDs, priority using the project's scheme,
  observable responses, and applicable invariants and failure behavior.
- **Acceptance:** representative scenarios and the requirement-to-evidence map.

Human review comes before technical detail. State what accepted decisions force
or exclude. Label open choices and assumptions explicitly; a material open
choice or assumption blocks the dependent implementation slice. Link existing
decisions and contracts instead of copying their normative text.

Write one observable behavior per requirement. If no vocabulary exists, use
`MUST` for required behavior, `SHOULD` for recommendations whose deviations need
rationale, and `MAY` for optional behavior. Useful conditional forms are:

```text
The <system> MUST <observable response>.
When <event>, the <system> MUST <observable response>.
While <state>, the <system> MUST <observable response>.
If <failure>, the <system> MUST <safe observable response>.
```

Avoid vague qualities such as "fast", "secure", or "easy" without observable
criteria. Keep implementation detail out unless it is an accepted constraint;
explain why that constraint is necessary. Preserve IDs when amending or
superseding requirements; do not renumber for appearance or reuse retired IDs.

Cover only applicable concerns:

| Concern | Questions the contract must resolve |
| --- | --- |
| Boundary and access | Who acts, who owns enforcement, what is allowed or denied? |
| State and lifecycle | Who writes, which transitions exist, what happens on repetition, concurrency, restart, migration, or deletion? |
| Side effects and failure | What is observable on interruption, ambiguous outcomes, retry, cancellation, dependency failure, recovery, or cleanup? |
| Data and trust | What crosses a boundary, who can access it, how is it validated, retained, restored, or removed? |
| Compatibility and operation | What changes for existing users, rollout, rollback, status, resource use, and supported environments? Which choices bind at authoring, build, startup or runtime? |

## Map acceptance to evidence

Use representative Given/When/Then scenarios. The result is visible through a
public boundary, not a private helper or storage layout. Include applicable
success, absence, invalid input, denial, repetition, concurrency, interruption,
dependency failure, and recovery; do not manufacture irrelevant test cases.

```text
Requirement ID | public boundary | scenario | evidence or planned check | gap
```

Before implementation, planned tests and missing evidence are normal: label
them as planned, not passed. Existing tests establish current behavior, not
automatic approval of the intended contract. State which external conditions
cannot be proven locally. Prefer checks through real entrypoints and control
time, randomness, and external dependencies where relevant.

## Sequence the roadmap

When planning is requested, update `docs/roadmap.md`. Derive work from
requirement IDs; do not copy the specification into
tasks. Sequence the smallest useful end-to-end slices by dependencies and risk,
not by arbitrary layers or file inventories.

```text
Milestone | outcome / requirement IDs | dependencies | exit evidence | status / blocker
```

Distinguish accepted product direction, an implementation-ready feature contract,
and verified implementation. For material open decisions, record the consequence
and timing in the owning specification: resolve now, resolve before a named
milestone, or intentionally out of scope. The roadmap links these blockers;
do not create a second question registry. Record actual acceptance when available.

Each milestone delivers an observable capability or resolves a named blocking
uncertainty. A research spike needs a question, bounded scope, and decision
output; it is not presumed production code. Stop broad discovery once the active
slice is ready and later questions have explicit decision points; do not demand
the entire product's detailed design before unrelated work can proceed.
Do not invent dates, estimates, owners, or completion claims.
Use the project's status vocabulary and record actual acceptance evidence
before marking a milestone complete. Approval to plan does not authorize
implementation, publication, or deployment.

## Review and handoff

Review the complete intended document diff before calling a slice ready:

- Paths use the canonical layout, links resolve, and IDs, ownership and
  terminology fit this project; no hidden dependency on the source project's
  documents or conventions remains.
- Material decisions and consequences are visible, with genuine acceptance;
  no unresolved choice is silently converted into a default.
- Requirements are observable and acceptance-mapped; relevant failure paths
  and compatibility effects are covered without speculative scope.
- Roadmap order follows real dependencies, each milestone has exit evidence,
  and blocked work is distinguishable from independently implementable work.
- Planned, implemented, and verified behavior are not conflated. A known gap
  is explicit rather than concealed behind an optimistic status.

For changes, trace affected requirements through decisions, scenarios, and
roadmap dependencies. Surface behavior drift; do not rewrite the contract merely
to bless the current implementation. Preserve unique facts when consolidating
documents, respect required historical records, and avoid unrelated cleanup.

Hand off the owning files, accepted scope, unresolved blockers, and next ready
slice (or the exact decision preventing readiness). Stop before coding unless
implementation was also requested and its dependent decisions are accepted.
When implementation reveals a contradiction,
return to the affected decision instead of guessing through it.
