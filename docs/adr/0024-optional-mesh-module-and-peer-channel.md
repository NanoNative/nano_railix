# ADR 0024: Optional Mesh Module And Peer Channel

## Status

Accepted on 2026-11-18. The 2026-11-21 follow-up clarifies the build checks at join and admission.
The [mesh specification](../specs/mesh.md) owns the behavior and acceptance evidence.

## Context

The first planned delivery lets already-running replicas of one logical application build
exchange complete Flow invocations. Later Step placement uses the same peer-channel architecture.

Putting TLS, discovery, routing, remote admission, and peer lifecycle into `railix-core` would
make standalone execution depend on distributed concerns and would give core several unrelated
responsibilities. Putting the behavior in Creator would make a development tool part of the
production data plane. A dedicated mesh module keeps both boundaries explicit.

The long-term Step-scaling goal also requires a persistent multiplexed invocation channel rather
than making a Flow-specific HTTP endpoint the architectural boundary. Java 25 supplies TLS/TCP
through JDK APIs, while the current product boundary rejects third-party runtime frameworks and
custom cryptography.

## Decision

Add one cohesive production module when mesh implementation begins:

`modules/railix-mesh`

`railix-mesh` depends only on `railix-core`. Core does not depend on mesh. The mesh module owns:

- authenticated peer transport and versioned framing;
- peer discovery and bounded peer knowledge;
- distributed routing and receiver-side admission;
- capacity exchange, invocation correlation, and distributed lifetime;
- Flow dispatch and the later distinct Step-dispatch boundary.

`railix-core` owns only canonical values, local execution semantics, and the transferable
invocation/result contracts required by both local and remote callers. It does not own listeners,
TLS configuration, seed discovery, peer routing, or distributed lifecycle.

Mesh capability is a build selection. A generated application that excludes it has no mesh code
or listener. A mesh-capable application activates it through valid startup configuration; absence
of activation preserves ordinary single-instance execution.

The initial transport is a persistent, multiplexed, bounded Railix protocol over mutual TLS and
TCP using JDK security and socket APIs. Protocol messages carry canonical data and approved compiled target
identity.

The first implementation dispatches complete Flow invocations. The protocol, correlation,
admission, and lifetime boundary must accommodate a later distinct Step-invocation message and
handler without replacing the peer channel. This does not authorize implementing Step dispatch in
the first delivery.

Mesh membership uses mutual TLS and a fixed v1 opening exchange that checks for a matching logical
build before peers share discovery and status. The receiving replica verifies the build again when
admitting offloaded work. Equivalent macOS and Linux/ARM packages can exchange work across
platforms. The first delivery places only portable Java Flows, as defined in the
[mesh specification](../specs/mesh.md#logical-build-and-host-compatibility).

## Consequences

- Standalone applications retain a small direct local path and do not serialize local calls.
- The source reactor gains a fourth production module once implementation begins, but a generated
  artifact includes it only when mesh capability is selected.
- Mesh behavior can evolve without importing transport concerns into core or requiring Creator at
  runtime.
- Railix owns a small security-sensitive framing and connection implementation, including strict
  bounds, backpressure, correlation, failure classification and peer-identity checks.
- Mutual TLS needs deployment-provided credentials and a separate local-development provisioning
  workflow.
- Build identity must separate execution semantics from OS-specific launchers and runtime images.
  During a rollout, discovery, status and placement stay within each matching build.

## Rejected Alternatives

- **HTTP request/response as the invocation architecture:** rejected because later granular Step
  dispatch needs persistent multiplexing and lifecycle messages independent of HTTP controllers.
- **HTTP/2 or HTTP/3 server framework:** not selected because the current Java 25/JDK-only runtime
  boundary has no suitable built-in server and does not admit a third-party runtime framework.
- **MQTT or another broker:** rejected because broker acceptance is not
  receiver admission and would introduce queue/session ownership outside the selected direct-peer
  model.

## Relationship To ADR 0013

This ADR supersedes only ADR 0013's claim that the production source reactor permanently contains
exactly `railix-core`, `railix-stdlib`, and `railix-creator`. Once implemented, `railix-mesh` is the
accepted optional fourth production module.

ADR 0013 otherwise remains in force, including Java 25 and JDK APIs, explicit dependencies, no
third-party Maven runtime framework, transport outside core, no runtime scanning or reflection,
and capability-selected generated artifacts. The new module is justified by accepted behavior,
not by speculative package decomposition.

## Evidence

- [Mesh specification](../specs/mesh.md)
- [Roadmap Item 7](../roadmap.md#7-decentralised-execution-state-sharding-and-replication)
- Planned packaged single-instance, same-host multi-process, and Mac/Raspberry Pi evidence in the
  mesh specification
