# ⏳ TODO — TICKET-011: Phased shutdown + drain

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Implement shutdown phases:
1. **Stop accepting** new work (HTTP receiver, CLI receiver, etc.)
2. **Drain** active rails (wait until they finish or timeout)
3. **Stop actors** in reverse order

## Key requirement

Some actors must stop early (sources), others must stop late (Mongo) after rails drained.
