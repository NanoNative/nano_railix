# ⏳ TODO — TICKET-002: Config compilation (profiles + directories)

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Implement a Nano-like config compilation pipeline for Railix global config:
- scan default directories (config/resources/etc) for `application.properties`
- load profiles via `application-<profile>.properties`
- allow profile discovery via common keys (spring/micronaut/quarkus + railix own)

## Deliverables

- A new config compiler (name TBD) that returns a `ConcurrentTypeMap` (or TypeMapI).
- Deterministic load order.
- Unit tests reproducing Nano-like precedence.

## Open questions

- Should Railix accept non-`railix_*` keys from files/env by default, or keep the current `railix` prefix restriction?

