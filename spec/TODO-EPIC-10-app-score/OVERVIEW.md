# ⏳ TODO — EPIC-10: App Score / Gamification

> **Priority:** P2

## Outcome

A Railix app can produce a visible quality score or rating that motivates improvement and highlights weak areas.
The score should be informative first, but useful enough to gate releases later if desired.

Possible inputs:
- test coverage
- failing tests
- dependency/security findings
- binary/image size
- complexity
- startup footprint
- memory usage
- documentation quality

## Why

A simple score makes quality visible without forcing users to inspect ten different reports manually.
It also gives Railix a strong developer-experience story around continuous improvement.

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-100: Score model](./TODO-TICKET-100-score-model.md) | ⏳ TODO | define score inputs and weights |
| [TICKET-101: Background scanners](./TODO-TICKET-101-background-scanners.md) | ⏳ TODO | coverage, security, size, complexity collectors |
| [TICKET-102: Score export + badges](./TODO-TICKET-102-score-export.md) | ⏳ TODO | expose score in CLI/logs/UI/build output |

## Open Questions

- Is the score purely informative at first, or can it fail builds from day one?
- Which metrics are core, and which are optional plugins?
- How do we keep the score deterministic and not noisy across machines?
