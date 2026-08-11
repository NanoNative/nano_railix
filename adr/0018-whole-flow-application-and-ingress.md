# ADR 0018: Whole-Flow Application And Explicit Ingress

## Status

Superseded on 2026-07-29 by [ADR 0020](0020-creator-first-application-graph.md).

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
authoring execution. Creator Build and Creator Start are separate explicit actions. Start owns
one child process built from the complete current flow; it does not attach to arbitrary external
processes.

`railix build <flow-file> <output.jar>` and the same command on a custom application use the
currently running executable JAR as the trusted dependency envelope. Build compiles the whole
flow against that application's explicit catalog, derives its canonical lock, preserves the
manifest and every non-flow entry, replaces only `railix.flow.json` and `railix.lock.json`, and
publishes through a forced sibling temporary file plus required atomic move. The root product
therefore builds standard-catalog applications; a custom shaded application builds with its
custom main and catalog. No archive scan discovers or registers Steps.

Executable templates must be one local self-contained JAR. Exploded or multi-entry classpaths,
manifest `Class-Path` dependencies, signed archives, unreadable or duplicate entries, and
template/input/output identity conflicts are rejected. Entry timestamps use one fixed local
ZIP time so equivalent builds are independent of the JVM timezone.

`POST /api/build` applies that same compiler, catalog, and executable-template contract to the
current Creator flow and returns the JAR as a download. The server owns one temporary archive
for the request, opens it with delete-on-close before publishing headers, streams it with an
exact content length, and removes it after success, rejection, client disconnect, or Creator
shutdown. The attachment name is a bounded portable slug; the browser revokes its transient
object URL after dispatch. Sample events, runtime settings, and secrets are excluded. There is
no Creator workspace or caller-selectable server path.

Build also returns the package's `sha256:` fingerprint. `POST /api/application` accepts exactly
`{build,flow,runtime}`, recompiles and re-materializes the package through the same contract,
verifies the fingerprint, and starts at most one child JVM. `GET /api/application` exposes its
explicit lifecycle snapshot. `DELETE /api/application?instance=<id>` controls only the current
matching instance, so stale clients cannot stop a replacement.

Creator sends runtime data once over the child's framed standard input and then uses a
per-instance management token for readiness and cooperative stop. The token and authenticated
management records never enter published output. Creator retains at most 65,536 UTF-8 bytes per
stdout/stderr stream, owns pipe drains and discovered descendants, and applies bounded graceful,
terminate, and force phases. The verified temporary package remains owned until process,
descendant, pipe, and file cleanup has completed.

## Invariants

- Packaging includes the whole flow and never asks for a second Step selection.
- Embedded flow and lock are compiler-owned canonical resources; runtime settings and secrets
  are never packaged.
- Existing regular output is preserved on rejection and replaced atomically on success; no
  copy/delete publication fallback exists.
- Creator Build returns a portable bounded-name attachment and retains no package after the request.
- Creator Start requires a prior exact Build fingerprint, owns one managed child, and retains no
  runtime source or temporary package after lifecycle cleanup.
- A built application can build another generation only through the same explicit catalog and
  executable dependency envelope.
- Trigger configuration is explicit flow data and validated before application startup.
- No server-side workspace, implicit launch, arbitrary process attachment, or parallel Creator
  runtime exists.
- Lifecycle cleanup is owned by the application boundary, subject to cooperative handlers.

## Consequences

One artifact and one managed child are simple to run and audit. Multi-flow packaging, arbitrary
external-process attachment, and live metrics require explicit future contracts rather than an
implied registry.

## Rejected Alternatives

Selected-Step packages, trigger Steps, generic envelopes, public network binding, ambient
ingress, server-side workspace persistence, implicit build-on-launch, arbitrary process
attachment, and fake packaged runtimes are rejected.

## Evidence

- [`RailixMain.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/RailixMain.java)
- [`ApplicationPackage.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationPackage.java)
- [`ApplicationRuntime.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationRuntime.java)
- [`ApplicationHttpServer.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationHttpServer.java)
- [`ApplicationSocketIngress.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/ApplicationSocketIngress.java)
- [`CreatorApplication.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/CreatorApplication.java)
- [`CreatorServer.java`](../modules/railix-creator/src/main/java/dev/nanonative/railix/creator/CreatorServer.java)
- [`TrustedGreetingMain.java`](../examples/trusted-step-monolith/src/main/java/example/railix/welcome/TrustedGreetingMain.java)
- [`RailixPackageIT.java`](../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/RailixPackageIT.java)
- [`RailixCreatorBrowserIT.java`](../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/RailixCreatorBrowserIT.java)
- [`CreatorApplicationIT.java`](../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/CreatorApplicationIT.java)
- [`CreatorApplicationResourceIT.java`](../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/CreatorApplicationResourceIT.java)
- [`ApplicationRuntimeLauncherE2eTest.java`](../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/ApplicationRuntimeLauncherE2eTest.java)
- [`TrustedGreetingPackageIT.java`](../examples/trusted-step-monolith/src/test/java/example/railix/welcome/TrustedGreetingPackageIT.java)
- [`TrustedGreetingCreatorBrowserIT.java`](../examples/trusted-step-monolith/src/test/java/example/railix/welcome/TrustedGreetingCreatorBrowserIT.java)

## Deferred Decisions

Metrics, arbitrary external-process attachment, multi-flow packaging, permissions, remote
execution, state, and release certification remain Items 6-10. Item 5 closed mixed-trigger
and capability lifecycle ownership through the packaged Creator-managed application path.

## Historical Disposition

This supersedes the local-application fragment of deleted ADR 0009. Deleted ADRs 0003, 0010,
and 0011 described absent settings, state, and built-app attachment behavior and stay deleted.
