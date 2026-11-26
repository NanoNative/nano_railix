# ADR 0024: Optional Mesh Module And Peer Channel

## Status

Accepted on 2026-11-18. The 2026-11-21 follow-up clarified build checks at join and admission.
The first Flow offload proof uses direct HTTP ingress. A reverse proxy is a deployment choice.

## Context

Railix generates a standalone Java application. Replicas should share work while preserving the
compiled Flow, the Trigger's response contract, and the ability to run on one machine. The
[mesh specification](../specs/mesh.md) owns protocol fields, limits, and acceptance scenarios.

## Decisions

### Standalone Java and optional mesh

Keep direct compiled execution in `railix-core`. Select mesh at build time and activate it with
startup configuration. The optional `railix-mesh` module depends on core and owns peer transport,
discovery, placement, and distributed lifetime. Creator bundles the mesh JAR for offline
packaging. The generated application manages its own runtime. Startup configuration lets each
host supply its addresses and credentials to the same immutable artifact.

Putting mesh in core would couple every standalone build to peer concerns. Activating an
always-bundled module at startup would still ship its code. Putting mesh in Creator would make
a development tool a production dependency. An installed Java runtime is enough for the first
proof. Self-contained distributions have their own delivery work in Item 8.

### Mutual TLS and replica identity

Use JDK mutual TLS with deployment-provided keys, certificates, and trust anchors. A certificate
URI Subject Alternative Name binds the mesh and replica IDs independently of the network
address. A separate incarnation ID distinguishes a restarted process from an overlapping process
claiming the same replica. The local demo uses temporary credentials.

An address or a mesh ID supplied in configuration identifies where to connect or which group to
expect. Mutual TLS adds proof of identity on both sides. The JDK supplies certificate validation
and encryption. Custom cryptography would duplicate those responsibilities.

### Direct, persistent peer channel

Use a bounded, length-framed protocol over JDK TLS and TCP. Multiplexing lets one authenticated
session carry concurrent invocations and outcomes. The opening exchange identifies
the fixed wire format. Use Railix JSON with exact decimal scale and object field order for Flow
data so Steps see the same values on both replicas. Railix owns framing and maps messages to
compiled targets.

Each joined connection owns one bounded writer. Invocations, replies, and control frames
enter its FIFO. A slow peer uses its own write capacity.

Use authenticated replica ID order to pick one socket during join, then keep it until it
disconnects or expires. Either replica can dial with the address it knows. Ordering the two
initial state messages lets both sides agree before offloading.

A Flow-specific HTTP endpoint would tie dispatch to a source protocol and require a separate
design for later Step dispatch and cancellation. An HTTP/2 or HTTP/3 server framework would expand
the Java runtime dependency boundary. A broker such as MQTT would add queue and session ownership
while the receiving replica would still have to decide admission.

### Bootstrap and peer discovery

Start with fixed endpoints for the membership proof. Bounded discovery uses configured cross-host
seeds, a bounded loopback range for local discovery, and hints learned from matching peers.
Replicas authenticate hinted peers directly. A seed provides an initial address. After peers
connect directly, the seed has no special runtime role.

A full static list makes each joining replica an operator configuration change. Arbitrary subnet
scanning widens the search beyond intended hosts. A central registry or permanent seed would add
a service the deployment has to keep available. Direct discovery lets already-connected replicas
keep coordinating while a seed is down.

### One logical build per mesh

Require an exact logical build match before exchanging discovery and status. Check it again at
admission, where a compiled target is about to execute. The ID represents the execution contract,
including the compiled project, providers, runtime, and peer compatibility.

A shared project name can hide changes in behavior or dependencies. Matching the entire package
would split equivalent macOS and Linux/ARM builds because their launchers and packaging differ.
Matching the execution contract lets equivalent packages cooperate while isolating different builds
during a rollout.

### Compiled Flow continuations first

Place one complete eligible Flow after the Trigger has selected its continuation. Send the
compiled target identity and canonical values. The receiver uses its own initialized resources and
returns an outcome in the same value model. Later Step placement can reuse the channel and
invocation lifetime with its own target and payload contract.

Running the Trigger again on the receiver could repeat source effects. Moving live Java objects,
resource handles, or graphs would tie dispatch to process state or require a second execution
model. Step-by-Step dispatch would add network boundaries inside a Flow before the complete-Flow
contract has been proven.

### Local-first placement and receiver admission

Prefer local execution while the replica has a Flow slot. A saturated replica can try a ready,
eligible peer. That peer owns final admission and shares its execution capacity between local and
remote work. Separate limits for pending remote calls preserve local execution slots while
ingress waits for another replica.

Check Flow eligibility before comparing capacity. The local replica and its peers use the
same compiled Flow identity. The first proof makes that Flow eligible on both configured
replicas. The receiver checks its own eligibility and actual capacity before running it.
This keeps placement separate from the compiled continuation and lets deployment add
replica or role constraints later. If a Flow is pinned to one replica, spare capacity
elsewhere cannot override the pin.

Always sending work to the least-loaded peer would add a network hop even when local execution
has room. A capacity advertisement can be stale by the time work arrives. A receiver queue would
defer the admission decision and retain another copy of the work. The receiver checks its actual
resources and refuses immediately when it cannot admit the Flow.

Reserve a terminal reply position on the receiving connection before admitting remote work.
Execution releases its Flow slot after handing the reply to the connection. Drain waits for
both execution and replies within one deadline. This keeps network backpressure on the peer
that caused it and preserves local execution capacity.

### Single-member ingress and future Smart clients

Use single-member mode for the first Flow offload delivery. The caller uses one ingress address.
The first HTTP proof calls a replica's Trigger endpoint directly. Deployments can put a reverse
proxy in front of ready replicas for a stable URL. A dedicated Railix gateway would concentrate
public request handling in one process.

The ingress replica owns the original transport and completes the response. An execution peer
returns the Flow's `RunResult`. Ingress uses the same compiled response slots for local and
remote results. This preserves the Trigger contract and sends the final context once.

A future Smart client can learn reachable Trigger endpoints and select a replica before
sending the event. Both modes use the same Flow placement, receiver admission, and response
path after the event reaches ingress.

### Bounded waiting and receiver-owned execution

Keep one ingress deadline for transfer, admission, and the reply. Send the remaining budget
to peers. Known non-admission allows a bounded attempt on another eligible peer. A failure
after possible admission ends placement with an uncertain result. Ingress discards late replies.

The receiver owns an admitted Flow through execution. Caller timeout, interruption, and peer loss
leave that work running because the receiver has accepted responsibility for its effects. Its
execution slot stays occupied until it exits. The receiver's application lifecycle owns shutdown.
Drain closes admission and keeps the peer channels available for outstanding replies within the
drain and reply deadlines.

Giving each attempt a full timeout can exceed the caller's original budget. Replaying on any lost
reply can repeat an effect that the first receiver already committed.

### Observation and deployment

Use the application's existing read-only status boundary for Creator attachment. The selected
replica owns its status. Deployment starts and supervises replicas while the mesh coordinates
those running processes. The pilot limits each replica's peer list to fit the existing status response.

A separate mesh administration service would add another runtime dependency for reading peer
state. Making mesh membership responsible for provisioning or restarting processes would couple
work placement to host management.

## Relationship To ADR 0013

This ADR updates [ADR 0013](0013-small-java-product-boundary.md) to allow the optional fourth
production module. Its Java and JDK boundary, explicit dependencies, capability-selected
artifacts, and restrictions on runtime scanning and reflection still apply.

[Mesh guide](../mesh.md) ·
[Roadmap Item 7](../roadmap.md#7-decentralised-execution-state-sharding-and-replication)
