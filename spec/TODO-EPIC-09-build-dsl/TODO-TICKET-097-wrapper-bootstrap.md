# ⏳ TODO — TICKET-097: Railix Build wrapper bootstrap

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Provide a wrapper-style Railix Build entrypoint for fresh machines, similar in spirit to Maven Wrapper.

## Expected Behavior

- runnable via POSIX `sh` first
- native launcher or platform executable later if justified
- detects installed JDKs and downloads the requested one when missing
- finds the named build definition class or falls back to the first valid definition
- generates `pom.xml` and delegates to Maven-compatible build flow when needed
- produces at least a jar from the declared build definition

## Open Questions

- should the wrapper always generate `pom.xml`, or only when IDE/build-tool integration is requested?
- how do we resolve the first valid build definition deterministically?
- what is the minimum offline behavior on a fresh machine with no JDK installed?
