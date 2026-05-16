# ⏳ TODO — TICKET-081: Source actor handover / forwarding

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Let source actors move new ingress from `rail_app_v1` to `rail_app_v2` without losing requests/messages.

## Notes

Possible strategies:
- direct source rebinding to the newer process
- temporary forwarding/proxying from old to new
- handover via shared socket/port/file/queue ownership

This must stay source-actor driven, not a separate lifecycle taxonomy.
