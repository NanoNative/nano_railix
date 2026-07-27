# ADR 0018: Whole-Flow Application And Explicit Ingress

## Status

Accepted on 2026-07-27.

## Context

Packaging selected Steps or implementing triggers as Steps would split deployment and ingress
from the flow the user authored. Creator must also avoid pretending that local authoring
execution is an attached built application.

## Decision

The current application artifact embeds exactly one complete authored flow, its referenced
Step implementations, and its canonical dependency lock. The plural
`/v1/flows/{flowId}` URI is an ID-addressed namespace for that flow, not a runtime flow
registry; another id is not found.

CLI, startup, HTTP, socket, and scheduled triggers are compiler-validated declarations in the
flow. Each adapter creates the same immutable object event and invokes the same compiled
executor. Adapters own ingress framing, route admission, readiness, bounded input and
concurrency policy, deadlines, schedule cadence where applicable, and `RunResult`
translation. They do not redefine flow execution semantics.

One `ApplicationRuntime` owns startup readiness, HTTP/socket/schedule admission, active-work
cancellation, rollback, and idempotent stop. Network listeners bind to loopback. Creator uses
the real catalog, compiler, and runner against the same flow model, but Creator Run is local
authoring execution; it does not build, start, or attach to the packaged application.

## Invariants

- Packaging includes the whole flow and never asks for a second Step selection.
- Trigger configuration is explicit flow data and validated before application startup.
- No server-side workspace, build-on-launch, or parallel Creator runtime exists.
- Lifecycle cleanup is owned by the application boundary, subject to cooperative handlers.

## Consequences

One artifact is simple to run and audit. Multi-flow packaging and live built-application
attachment require explicit future contracts rather than an implied registry.

## Rejected Alternatives

Selected-Step packages, trigger Steps, generic envelopes, public network binding, ambient
ingress, server-side workspace persistence, build-on-launch, and fake packaged runtimes are
rejected.

## Evidence

- [`RailixMain.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/RailixMain.java)
- [`ApplicationRuntime.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationRuntime.java)
- [`ApplicationHttpServer.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationHttpServer.java)
- [`ApplicationSocketIngress.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationSocketIngress.java)
- [`CreatorServer.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/CreatorServer.java)
- [`TrustedGreetingMain.java`](../examples/trusted-step-monolith/src/main/java/example/railix/welcome/TrustedGreetingMain.java)

## Deferred Decisions

Configuration/secrets, built-app attachment and metrics, multi-flow packaging, permissions,
remote execution, state, and release certification remain Items 5-10.

## Historical Disposition

This supersedes the local-application fragment of deleted ADR 0009. Deleted ADRs 0003, 0010,
and 0011 described absent settings, state, and built-app attachment behavior and stay deleted.
