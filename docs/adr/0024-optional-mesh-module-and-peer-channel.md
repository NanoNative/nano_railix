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

### Rejected transport alternatives

- **HTTP request/response endpoint.** An endpoint per Flow could serve the first offload use case.
  It would tie peer dispatch to HTTP controllers. Later Step dispatch needs concurrent
  invocations, correlated replies, and lifecycle messages on a channel independent of the
  Trigger's source protocol.
- **HTTP/2 or HTTP/3 server framework.** A framework would supply multiplexing but add a
  third-party server to the JDK-only runtime. The Java 25 boundary favored the JDK's TLS and
  socket APIs for the peer channel.
- **MQTT or another broker.** A broker could route and queue messages. It could accept a message
  while every replica was full. Railix would still need receiver-side admission and would also
  need to operate the broker and define who owns queued work and sessions.

The direct channel supports concurrent calls on one authenticated connection and allows later
Step dispatch to reuse it. The cost is maintaining a bounded peer protocol in Railix, including
framing, correlation, backpressure, and failure handling.

### Execution scope and build identity

Start with complete eligible Flows. Ingress runs the Trigger and sends the compiled continuation
identity and canonical inputs when it needs a peer. Repeating the Trigger on the receiver could
repeat source effects. Moving live Java objects, resource handles, or authored graphs would couple
the request to process state or require another execution model. The receiver uses its own
initialized resources. Ingress keeps the source transport and completes the request. Individual
Step placement was deferred. It would introduce network boundaries inside a Flow before the
complete-Flow execution contract had been proven.

The first placement policy was local-first with immediate receiver admission and a shared execution
pool for local and remote work. Advertised capacity was advisory. Another peer could be tried only
after known non-admission. The early protocol proposed a separate `admitted` acknowledgement and a
`cancel` message. It allowed two remote attempts within the original deadline and sized the separate
pending-invocation pool from the local execution capacity. These choices are amended below.

Logical compatibility was defined by the compiled application's behavior and required capabilities.
Matching by project name could group builds whose behavior or dependencies differed. Hashing
the entire OS package would separate equivalent builds with different launchers or runtime images.
Cross-platform placement was scoped to portable Java Flows with equivalent resources.
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
Each suggested peer would still authenticate directly. Keeping an exhaustive address list on
every replica would require updating that list as replicas come and go. An arbitrary subnet scan
would expand the search beyond intended hosts. A central registry or permanent seed would add a
service the deployment would need to keep available. The direct connections allow already-joined
replicas to keep coordinating when a seed leaves.

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

The first HTTP proof would send the request directly to one replica's Trigger. Any reachable
replica can receive the request. The seed's role is to provide a bootstrap address. Deployment may
put a reverse proxy in front of ready replicas when it needs a stable public URL as replicas come
and go. Building a dedicated Railix gateway would add a component to operate and concentrate
ingress in that process. Letting the execution peer reply directly would require transferring the
original transport or arranging another request from the caller.

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

This revision named the first ingress topology single-member mode. The caller uses one ingress
address. The planned Smart mode would move replica selection to the client while preserving the
same Trigger and execution contract.

The initial multi-peer rule preferred the socket initiated by the lower certificate-bound replica
ID and used the connection ID to break same-direction ties. A preferred, authenticated socket
could replace an existing one, with a readiness check to protect a ready connection. That rule
resolved duplicate dials deterministically. The offload pilot exposed the risk of replacing a socket
that still owned a reply.

[Configured multi-peer decisions](https://github.com/NanoNative/nano_railix/commit/41d979e51e1b3a38ae51ba3746b8706e53b2d1aa)

### Eligibility, capacity, and retries

Deployment selects one compiled Trigger continuation whose resources are equivalent across the
participating replicas. The compiler identifies the continuation. Deployment supplies the
knowledge of which replicas have equivalent resources. Filter by eligibility before ranking
peers so spare capacity on a replica cannot override future Flow or role restrictions.

Sending every request to the least-loaded replica would add a network hop even when a local
slot was free. A capacity advertisement can be stale in either direction. Use it to rank candidates
and let the receiver check available slots when the invocation arrives. A receiver queue could
absorb a burst, but would retain pending work and make ingress wait for an admission decision.
An immediate refusal lets ingress try another eligible replica.

The ingress pending-offload limit became independent of its local execution capacity. With both
limits tied to one slot, a replica could run one local request and wait for one peer while a third
replica sat idle. Separate limits let ingress use spare capacity on more than one peer while bounding
how much input it holds. The remote attempt limit changed from two to three sequential candidates
within one original deadline. Only known non-admission permits trying another peer. Giving every
candidate a fresh timeout would extend that deadline. A central work registry for work stealing
would let idle replicas pull eligible work. It would also need to track exclusive claims and
result delivery and decide how to recover after a claimant fails. The pilot kept placement and
the reply path with the ingress replica.

### Replies, deadlines, and execution ownership

Use one bounded FIFO writer for each authenticated connection. Reserve space for a terminal reply
before admitting remote work. Reserving after execution could leave a completed Flow with no
space for its result. Requiring the worker to finish writing would hold an execution slot while
the network stalled. Release the execution slot after the worker hands its reply to the writer.
Keep the reply reservation until the writer sends or drops it. Drain waits for admitted execution
and pending replies within their applicable budgets.

The pilot removed the remote `cancel` frame and the rule that the ingress deadline ends accepted
execution. The ingress deadline now covers transfer, admission, and the return of the outcome.
Once the receiver accepts work, its application lifecycle owns execution and shutdown. Timeout,
interruption, or disconnect at ingress may end its wait while the receiver continues to run. The
receiver may still interrupt its own work when its shutdown budget expires. The HTTP Trigger can
wait until the deadline when its HTTP server provides no disconnect notification during the Flow.
Retaining remote cancellation would give ingress a way to request interruption, but the receiver
could already have committed a side effect. The chosen ownership rule lets accepted work finish
even when the original caller can no longer receive the result. A disconnect-aware HTTP transport
was deferred.

### Wire fidelity and compatibility

The early design chose canonical Railix JSON to reuse the existing value model. Its canonical
writer sorts object keys and normalizes decimal scale. Using that output for transfer could
change values a Step can observe. The pilot kept the value model and chose one transfer encoding
that preserves field order and decimal scale. Ingress derives the Trigger response from the
returned final context using its existing compiled response slots. Encoding both the context and
the response map duplicated large values and could push an otherwise valid outcome over the
frame limit.

The early invocation envelope listed an attempt number and a separate trace identity. The pilot
kept retry counting at ingress and used the invocation ID on the session to correlate replies.
The writer stamps the remaining admission time when it can send the frame, so encoding and
queueing consume the same budget. The logical build fingerprint gained the packaged core
runtime and mesh classes. Otherwise an execution or peer contract change could leave two
different artifacts claiming compatibility. The artifact cache key still serves a separate
purpose and includes toolchain and packaging inputs.

### Connection selection

An offloaded Flow can finish after a competing dial completes. Replacing the original socket
could lose that reply while both replicas were healthy. Letting each side keep its first socket
independently could leave each side choosing a different one when the dials cross. The lower
authenticated replica ID now chooses which socket to reserve by sending initial state first,
regardless of who opened the socket. Its peer confirms on that socket. This lets a joining
replica dial the seed even when only the joining replica knows the address. Retain the agreed
connection until it disconnects or expires. The connection ID and replacement rule became
unnecessary once the pair agreed before exposing the socket to offloads.

### Execution and offload metrics

Counting the ingress wait as execution and counting the work again at the receiver inflated both
execution and in-flight metrics. Count a Flow execution on the replica that ran it. Ingress counts
`offload_ack` when it receives a valid terminal outcome by the deadline and `offload_no_ack` when
delivery was possible but the outcome is unavailable. The terminal outcome can carry success or
failure. The transport still uses one terminal reply per attempt, and an explicit refusal leaves
both counters unchanged.

The mesh launcher registers these counters for the configured Flow at startup. Flow snapshots
own the counters and the application snapshot sums them. Dedicated fields in Core would make
mesh metric names part of the shared runtime. Keeping only application-wide counters would lose
which Flow was offloaded. The existing metric interface supports both scopes and lets the
launcher bind the recorder once, outside dispatch.
[Metrics follow-up](https://github.com/NanoNative/nano_railix/commit/4bfd1027b20058bd2222476f22d14d31e1019a40)

## 2026-11-27 — Observation boundaries

Mesh observation originally included a peer list and a copy of one peer for Creator's summary.
Creator now selects the summary from the list. Publishing both fields repeated the same peer
data and made the runtime carry a presentation choice.

## Relationship To ADR 0013

This ADR amends [ADR 0013](0013-small-java-product-boundary.md) on the size of the production
source reactor. `railix-mesh` is the optional fourth module. The Java 25 and JDK boundary,
explicit dependencies, build-time capability selection, and restrictions on runtime frameworks,
scanning, and reflection still apply.
