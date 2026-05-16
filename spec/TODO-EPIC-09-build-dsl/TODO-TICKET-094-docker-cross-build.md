# ⏳ TODO — TICKET-094: Docker/buildx cross-builds

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

When Docker and buildx are available, Railix should be able to:
- build `amd64` and `arm64` scratch images
- build target-specific native executables inside containers when the local machine can support it

## Notes

This should stay optional and capability-detected.
