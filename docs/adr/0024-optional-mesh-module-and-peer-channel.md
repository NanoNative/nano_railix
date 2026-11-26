# ADR 0024: Optional Mesh Module And Peer Channel

## Status

Accepted on 2026-11-18. The entries record the original decision and its amendments. Dates use
the first available decision, commit, or review record.

Current behavior and usage are in the [mesh guide](../mesh.md). Protocol, limits, and acceptance
scenarios are in the [mesh specification](../specs/mesh.md). See
[roadmap Item 7](../roadmap.md#7-decentralised-execution-state-sharding-and-replication) for delivery
progress and the [verification record](../verification.md#fixed-peer-mesh-membership) for evidence.

## 2026-11-18 — Optional mesh module and direct peer channel

ADR 0013 established a small three-module Java product while distributed execution was speculative.
The first mesh decision scoped the work to already-running replicas of one logical application build.
A new `railix-mesh` module would depend only on `railix-core` and own peer transport, discovery,
routing, distributed admission, and invocation lifetime. Core would keep direct local execution,
canonical values, and the transferable invocation and result contracts. Putting peer coordination
in core would couple every local application to distributed concerns. Putting it in Creator would
make a development tool part of the production data plane.

Select mesh when building the application and activate it with valid startup configuration. Always
bundling the module would ship peer code in ordinary applications. The chosen split lets replicas
use the same built artifact with different addresses and credentials while keeping the local path
as compiled Java calls. A mesh-capable build runs locally when mesh configuration is unset.

Use a persistent, bounded, multiplexed protocol over JDK mutual TLS and TCP. The JDK owns
certificate validation and encryption. Railix owns framing, correlation, backpressure, and failure
classification. Deployment supplies private keys, certificates, and trust anchors. A local launcher
can issue temporary credentials under a temporary trust root. Both peers prove their identity.
An address or a configured mesh ID on its own establishes neither identity nor key possession.
A Railix-specific cryptography layer would duplicate the JDK's responsibilities.

A Flow-specific HTTP endpoint would tie remote dispatch to the Trigger's source protocol. An
HTTP/2 or HTTP/3 server framework would expand the JDK-only runtime boundary. MQTT or another
broker would introduce queue and session ownership while leaving admission with the execution
replica. The direct channel was chosen to support multiple invocations on one connection and leave
room for later Step dispatch.

Start with complete eligible Flows. Ingress runs the Trigger and sends the compiled continuation
identity and canonical inputs when it needs a peer. Repeating the Trigger on the receiver could
repeat source effects. Moving live Java objects, resource handles, or authored graphs would couple
the request to process state or require another execution model. The receiver uses its own
initialized resources. Ingress keeps the source transport and completes the request. Individual
Step placement was deferred until the complete-Flow boundary had been proven.

The first placement policy was local-first with immediate receiver admission and a shared execution
pool for local and remote work. Advertised capacity was advisory. Another peer could be tried only
after known non-admission. The early protocol proposed a separate `admitted` acknowledgement and a
`cancel` message. It allowed two remote attempts within the original deadline and sized the separate
pending-invocation pool from the local execution capacity. These choices are amended below.

Logical compatibility was defined by the compiled application's behavior and required capabilities.
Hashing the entire OS package would separate equivalent builds with different launchers or runtime
images. Cross-platform placement was scoped to portable Java Flows with equivalent resources.
[Original ADR and specification](https://github.com/NanoNative/nano_railix/commit/aba7c832cd0e7e40389c5991faf88b7b97e77f58)

## 2026-11-21 — Compatibility at join and admission

Different logical builds would have separate membership, discovery, and status, even during a
rollout of the same project. Match the build in `hello` before exchanging state, then check it
again at `invoke` before reserving execution capacity. The fixed `hello.v1` opening would identify
the wire format once per session. This replaced the earlier plan to check the protocol again at
admission. The build check stayed because it protects the compiled target about to execute.
The advertised endpoint moved from `hello` to `state` so it is shared after the build check.

Replace the separate `admitted` frame with a single terminal reply to each invocation. A `refused`
response establishes non-admission, and an `outcome` carries the result of accepted work. A separate
acknowledgement would add a response while leaving the same ambiguity when the connection fails
before a reply. If delivery may have occurred and the connection is lost, ingress treats completion
as uncertain. Replaying could repeat an effect already committed by the first receiver.

The fixed-peer proof was scoped to two loopback processes with explicit addresses. The broader
discovery plan used configured cross-host seeds, a bounded loopback range, and hints from matching peers.
Each suggested peer would still authenticate directly. An arbitrary subnet scan would expand the
search beyond intended hosts. A central registry or permanent seed would add a service the
deployment would need to keep available.

The certificate's URI Subject Alternative Name binds the mesh and stable replica identity
independently of the network address. A process incarnation ID distinguishes a restart from two
live processes claiming the same identity. The URI serves as a local identifier. The fixed-peer
proof uses an empty hints list. [Compatibility and fixed-peer decision](https://github.com/NanoNative/nano_railix/commit/defc8dc238efa0ea37a155fb4c7abd778cf9c66e)

## 2026-11-22 — Offline packaging and read-only observation

Creator carries the matching mesh JAR as an offline build resource. The build option selects the
mesh classes and generates the startup hook. The generated application owns the running mesh.
An executable JAR and an installed Java runtime were sufficient for the first public-boundary
proof. Runtime images, installers, and self-contained distribution certification remained separate
work under [roadmap Item 8](../roadmap.md#8-direct-generation-distribution-and-certification).

The selected replica publishes its own join state through the existing authenticated, read-only
observation boundary. Creator can attach to that replica. Independent status admission keeps a
busy mesh status read from consuming the slots reserved for identity or metric reads. A separate
administration service would add another runtime dependency, and process supervision would couple
placement to host management. Deployment retains responsibility for starting and supervising replicas.
[Fixed-peer implementation](https://github.com/NanoNative/nano_railix/commit/2635773e9443fe31df920b83d9576b6d35126e20)

## 2026-11-23 — Direct ingress and optional proxy

The first HTTP proof would send the request directly to one replica's Trigger. The seed remains
a bootstrap address. Deployment may put a reverse proxy in front of ready replicas when it needs
a stable public URL. A dedicated Railix gateway would concentrate ingress in one process. Letting
the execution peer reply directly would require transferring the original transport or arranging
another request from the caller.

A future Smart client may learn reachable Trigger endpoints and select a replica before sending
an event. Either client mode can follow the same placement, admission, and response path after
an event reaches ingress.

The offload design still used one ingress deadline across dispatch, execution, and reply. On
expiry or caller disconnect, ingress would request best-effort cancellation. A lost response
could still leave execution uncertain. This preserved the original time budget while acknowledging
that a cancellation request could reach the receiver after it had already committed an effect.
[Ingress and original cancellation design](https://github.com/NanoNative/nano_railix/commit/9517af705c1de9def4ae6afc842c6a6b83472ffa)

## 2026-11-24 — Configured multi-peer membership

The fixed pair gave way to multiple configured peers as a separate checkpoint before automatic
address discovery. Either side could dial, and surviving replicas could keep their direct
connections when the original seed left. Bootstrap aliases resolving to the same endpoint shared
retry state. A follow-up heartbeat established a healthy session for resetting backoff. The peer
count was bounded to fit the existing observation response.

This revision named the first ingress topology single-member mode.

The initial multi-peer rule preferred the socket initiated by the lower certificate-bound replica
ID and used the connection ID to break same-direction ties. A preferred, authenticated socket
could replace an existing one, with a readiness check to protect a ready connection. That rule
resolved duplicate dials deterministically. The offload pilot exposed the risk of replacing a socket
that still owned a reply.

The logical build fingerprint was extended to cover the packaged core runtime. A change in local
execution could otherwise leave two different artifacts claiming the same execution contract.
The version-specific artifact cache key stayed separate from this platform-independent identity.
[Configured multi-peer decisions](https://github.com/NanoNative/nano_railix/commit/41d979e51e1b3a38ae51ba3746b8706e53b2d1aa)

### Eligibility, capacity, and retries

Deployment selects one compiled Trigger continuation whose resources are equivalent across the
participating replicas. Filter by eligibility before ranking peers. A zero-capacity advertisement
can be stale, so the last reported capacity ranks candidates and the receiving replica decides
admission from its current resources. This leaves room for future replica or role restrictions on
where individual Flows can run.

The ingress pending-offload limit became independent of its local execution capacity. With both
limits tied to one slot, a replica could run one local request and wait for one peer while a third
replica sat idle. Separate limits let ingress use spare capacity on more than one peer while bounding
how much input it holds. The remote attempt limit changed from two to three sequential candidates
within one original deadline. Only known non-admission permits trying another peer. Giving every
candidate a fresh timeout would extend that deadline. A central work registry for work stealing
remained a separate proposal with its own claim, result, and recovery responsibilities.

### Replies, deadlines, and execution ownership

Use one bounded FIFO writer for each authenticated connection. Reserve space for a terminal reply
before admitting remote work. Release the execution slot after the worker hands its reply to the
writer. Keep the reply reservation until the writer sends or drops it. This separates a slow peer's
write pressure from the capacity to run Flows. Drain waits for admitted execution and pending
replies within their applicable budgets.

The pilot removed the remote `cancel` frame and the rule that the ingress deadline ends accepted
execution. The ingress deadline now covers transfer, admission, and the return of the outcome.
Once the receiver accepts work, its application lifecycle owns execution and shutdown. Timeout,
interruption, or disconnect at ingress may end its wait while the receiver continues to run. The
receiver may still interrupt its own work when its shutdown budget expires. The HTTP Trigger can
wait until the deadline when its HTTP server provides no disconnect notification during the Flow.

### Wire fidelity and compatibility

The early design chose canonical Railix JSON to reuse the existing value model. The pilot kept
that model and preserved field order and decimal scale for transferred values so a Step sees the
same input locally and remotely. Ingress derives the Trigger response from the returned final
context using its existing compiled response slots. Encoding both the context and the response
map duplicated large values and could push an otherwise valid outcome over the frame limit.

The early invocation envelope listed an attempt number and a separate trace identity. The pilot
kept retry counting at ingress and used the invocation ID on the session to correlate replies.
The writer stamps the remaining admission time when it can send the frame, so encoding and
queueing consume the same budget. The logical build fingerprint also gained the packaged mesh
classes so applications with different peer contracts have different identities.

### Connection selection

An offloaded Flow can finish after a competing dial completes. Replacing the original socket
could lose that reply while both replicas were healthy. The lower authenticated replica ID now
chooses which socket to reserve by sending initial state first, regardless of who opened the
socket. Its peer confirms on that socket. Retain the agreed connection until it disconnects or
expires. The connection ID and replacement rule became unnecessary once the pair agreed before
exposing the socket to offloads.

### Execution and offload metrics

Counting the ingress wait as execution and counting the work again at the receiver inflated both
execution and in-flight metrics. Count a Flow execution on the replica that ran it. Ingress counts
`offload_ack` when it receives a valid terminal outcome by the deadline and `offload_no_ack` when
delivery was possible but the outcome is unavailable. The terminal outcome can carry success or
failure. The transport still uses one terminal reply per attempt, and an explicit refusal leaves
both counters unchanged.

## Relationship To ADR 0013

This ADR amends [ADR 0013](0013-small-java-product-boundary.md) on the size of the production
source reactor. `railix-mesh` is the optional fourth module. The Java 25 and JDK boundary,
explicit dependencies, build-time capability selection, and restrictions on runtime frameworks,
scanning, and reflection still apply.
