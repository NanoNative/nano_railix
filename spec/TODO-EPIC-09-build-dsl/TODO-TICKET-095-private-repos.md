# ⏳ TODO — TICKET-095: Private repository credentials

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Support private Nexus or other artifact repositories in a project-local way.

## Constraint

The happy path should not require users to hand-manage a global Maven `settings.xml`.

## Open Questions

- env vars only?
- encrypted local file?
- delegated OS keychain?
- generated temporary Maven settings during the build?
