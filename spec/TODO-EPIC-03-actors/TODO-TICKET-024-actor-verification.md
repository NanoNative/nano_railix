# ⏳ TODO — TICKET-024: Actor verification

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Provide a standard way to verify required actors (by name and/or class) and define what happens when they’re missing.

## Requirements (from you)

- Verification must work with both:
  - programmatic usage (no annotations)
  - APT-generated typed accessors (`actors().mongo()`)
- The verifier accepts:
  - actor name(s) and/or class(es)
  - a user-provided fallback function (“what to do otherwise”)
- If the user does not provide a fallback:
  - Railix produces an **error `Result`** (instead of throwing by default)
- Direct actor access without prior verification is null-tolerant:
  - `actors().mongo()` returns `null` if missing
  - `actors().mongoOpt()` returns `Optional.empty()`

## Notes

- This ticket defines the *policy* and API surface; the registry storage/type-casting rules live in:
  - [TICKET-021](./TODO-TICKET-021-actor-registry-ordering.md)
  - [TICKET-022](./TODO-TICKET-022-typed-actor-access.md)
