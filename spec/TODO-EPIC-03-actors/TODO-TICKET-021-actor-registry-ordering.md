# ⏳ TODO — TICKET-021: Actor registry + ordering

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Provide a registry to:
- register actors
- start them in ascending order (priority/order)
- shutdown in reverse order
- support phased shutdown (stop-accepting before drain)
