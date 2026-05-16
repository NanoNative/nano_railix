# 💡 IDEA — Schedulers (Nano-like helpers, without a framework)

> **Status:** 💡 IDEA · **Priority:** P3

## Summary

Add optional scheduling helpers (delay/periodic/daily/weekly, timezone/DST-aware) similar to Nano’s scheduler helpers,
but usable from a Railix runtime (no event bus).

## Why

Connectors like HTTP/file watchers often need periodic tasks (health checks, reloads, housekeeping).

## Non-Goals

- Not a cron replacement.
- Not a “keep the app alive” mechanism by itself (runtime decides).

