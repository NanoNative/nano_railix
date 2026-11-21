# Railix Mesh

## Human Review

Status: **Fixed-peer design accepted, implementation in progress**, updated on 2026-11-21.

Owns: discovery of already-running replicas, authenticated peer membership, remote Flow
placement, distributed admission and invocation lifetime, peer transport, and the boundary that
later supports remote Step placement. The [system model](system-model.md) retains ownership of
local Flow and Step semantics. [Environments and security](environments-and-security.md) retains
ownership of secret delivery, immutable grants, and credential rotation. The
[roadmap](../roadmap.md#7-decentralised-execution-state-sharding-and-replication) owns delivery
sequence and status.

The first mesh coordinates replicas of one Railix application. The initial distributed work unit is one complete Flow
invocation. Individual eligible Step placement is a later delivery over the same peer-channel and
invocation-lifecycle architecture.

The accepted architecture is recorded by
[ADR 0024](../adr/0024-optional-mesh-module-and-peer-channel.md). The decisions below were made
by Abhiroop Basu.

### Accepted first-delivery decisions

- `railix-core` exposes only the canonical Railix invocation and result boundaries required by
  distributed execution. It does not own discovery, TLS, routing, or peer lifecycle.
- A new `railix-mesh` production module depends only on `railix-core`. It owns peer coordination,
  transport, discovery, routing, distributed admission, and distributed invocation lifetime.
- Mesh capability is selected when building a production application and activated by startup
  configuration. Without that configuration, the application opens no mesh listener and behaves
  as a single instance.
- Peers use a persistent, multiplexed, versioned, bounded Railix protocol over mutual TLS and TCP.
- Deployment supplies certificates, private keys, and trust material at startup. A local
  development launcher may generate temporary credentials and a shared temporary trust root.
  No credential is derived from the application name or another predictable identifier.
- Local development may bind and scan a configured bounded localhost port range. Cross-machine
  bootstrap uses explicit seed addresses. Arbitrary subnet scanning is excluded.
- Routing is local-first. The receiver makes the final admission decision when it handles
  `invoke`.
- Known non-admission may permit another bounded placement attempt. Once admission may have
  occurred, loss of the connection or result is uncertain completion and MUST NOT cause automatic
  replay.
- Mesh membership, discovery, peer status and Flow placement are scoped to one logical build.
  Equivalent builds can exchange work across supported operating systems and CPU architectures.

### First-delivery journey

When a request reaches replica A:

1. A owns the original socket and Trigger lifecycle and normalises the source input.
2. A executes the Flow locally when it can admit it.
3. When local Flow admission is saturated, A selects a known, authenticated, compatible peer B
   and sends `invoke`.
4. While handling `invoke`, B checks peer authority, logical build, target eligibility, remaining
   time and current capacity. If B cannot admit the Flow, it immediately sends `refused`.
5. If B admits the Flow, it executes the same compiled continuation that the local path would
   call and returns its terminal `outcome` to A.
6. A applies the result and completes the original Trigger response. B never writes to A's client
   socket or repeats the source's processing effects.

The first remote Flow does not forward the same assignment again.

## Module And Execution Boundaries

| Owner | Responsibility |
| --- | --- |
| `railix-core` | Canonical values, local execution semantics, transferable invocation/result contracts, and generated entrypoints. |
| `railix-mesh` | Peer discovery, authenticated channels, protocol framing, placement, remote admission, capacity knowledge, correlation, and distributed lifetime. |
| Generated application | Includes the mesh module only when selected at build time and supplies the compiled entrypoints that peers may invoke. |
| Creator/deployment | Builds the selected capability and supplies startup endpoints and credentials; it does not remain running to coordinate peers. |

The mesh boundary carries canonical data and approved execution metadata. It MUST NOT carry an
authored graph, Java class, closure, executable configuration, resource registry, source socket,
or arbitrary serialized Java object. The existing internal `WorkflowRuntime.FlowRunner` is live,
mutable local state and is not a wire contract.

The ordinary local path continues to call compiled Java directly. Enabling mesh capability MUST
NOT force local Flow or Step calls through serialization, framing, or a loopback network request.

## Startup Configuration

For a mesh-capable build, `RAILIX_MESH_CONFIG` points to a startup JSON file. An unset variable
selects standalone startup. An explicitly supplied path must point to readable, valid configuration
with usable credentials, or startup fails before the mesh listener opens. The generated app keeps
passing its ordinary command-line arguments to its sources.

The fixed-endpoint configuration supplies `meshId` (the expected mesh authority ID),
`listenEndpoint`, `advertisedEndpoint`, `bootstrapEndpoints`, `flowCapacity`, `identityStore` and
`trustStore`. Each endpoint has `host` and `port`. `bootstrapEndpoints` is a list and `flowCapacity`
is a positive integer. Each PKCS12 store has a `path` and a `passwordFile`. Relative credential paths
resolve from the configuration file's directory. Deployment supplies separate password files
readable only by the application account. Each contains one UTF-8 password line. Store password
values, private keys and usable credentials outside the JSON, command line, Git and build artifact.
The stable replica ID comes from the local certificate, whose mesh ID must match `meshId`.

## Peer Transport And Trust

Version 1 uses a persistent, bidirectional JDK TLS connection for each connected replica pair.
Each frame starts with a four-byte unsigned big-endian length, followed by that many bytes of
canonical UTF-8 JSON encoded with `RailixJson`. A session uses the fixed v1 wire format. Its opening
`hello` has `type` set to `hello.v1`, which identifies the format once during join. An unsupported
opening message ends the join. Version 1 excludes compression. Invocation messages share an
invocation ID and may complete out of order on the same connection.

| Message | Purpose and data |
| --- | --- |
| `hello` | Carries `type` (`hello.v1`), `logicalBuildId`, `meshId`, `replicaId` and `incarnationId`. The dialer supplies `connectionId` for duplicate connection resolution. |
| `state` | Carries `type` (`state`), `status` (`starting`, `ready` or `draining`), `advertisedEndpoint`, `availableFlowSlots` and `peerHints`. Periodic state is also the heartbeat. The fixed-peer proof uses an empty hints list. |
| `invoke` | Selects the compiled Flow continuation and carries its canonical input, invocation ID, remaining duration, attempt and trace identity. |
| `refused` | Ends a placement attempt before admission, with a structured reason such as build mismatch, saturation, drain, ineligibility or expiry. |
| `outcome` | Ends an admitted invocation with its terminal result, preserving applicable Flow outcome and branch semantics. |
| `cancel` | Requests cancellation of an in-flight invocation. |

For the first intranet prototype, each side applies one internal 5-second join deadline from
the start of an outbound connection attempt or the acceptance of an inbound socket. The deadline
covers TCP setup, mutual TLS, `hello` and receipt of the peer's first valid `state`. On expiry,
close the connection and follow the discovery retry policy.

The first intranet prototype sends `state` heartbeats every 2 seconds. The receiver marks a peer
stale after 8 seconds without a valid `state` from that peer. Both timings are internal defaults.

Each deployed replica receives its own private key and X.509 certificate chain in a PKCS12
identity store and trusted issuers in a separate PKCS12 trust store. Both peers prove possession
of their private keys and validate the other's certificate for its TLS role. A URI Subject
Alternative Name binds the signed certificate to the mesh authority ID and stable replica ID using
`https://github.com/NanoNative/nano_railix/mesh/v1/<mesh-id>/replicas/<replica-id>`, with both IDs in
lowercase UUID form. Railix matches the URI locally. After mutual TLS, both replicas send `hello`
and verify the other peer's identity claims against its certificate and configured mesh authority.
Each replica also compares `logicalBuildId` with its own. A mismatch closes the connection before
either side exchanges `state`. Each process generates a separate random incarnation ID at startup.
The matching build ID is recorded for the lifetime of the session. The first valid `state`
completes join and reports the advertised endpoint, readiness and capacity.

For local development, the launcher generates a random temporary mesh ID and uses JDK `keytool`
to provision a shared temporary trust root, a distinct key and certificate per replica, and temporary
password files. Enrollment and rotation remain owned by
[Environments and security](environments-and-security.md).

## Discovery And Peer Knowledge

The first secure-peer proof runs two local processes with fixed loopback endpoints. Each process
allows one peer session and one concurrent handshake. The follow-on discovery milestone adds
selection and probing within a configured bounded loopback port range.

Cross-host startup configuration supplies one fixed listen endpoint, a reachable advertised
endpoint, and explicit bootstrap endpoints as hostname/IP and port pairs. The listen endpoint binds
the local socket. Peers dial the advertised endpoint. A peer that passes `hello` may share a bounded
set of addresses in `state`. Each hint requires direct authentication and a matching logical build
before the discovered replica becomes a known peer.

When both replicas dial each other, the connection initiated by the lexicographically smaller
certificate-bound replica ID wins. Shared connection IDs break ties between duplicate connections
initiated in the same direction. A replacement must be authenticated and ready before retiring
a ready connection. A restarted process can join with a new incarnation after the old connection
ends. A peer that sees overlapping incarnations claiming one replica ID reports the conflict and
retains only one connection for that replica.

A joined peer becomes an offload candidate when its `state` is fresh and reports readiness.
Each replica measures peer freshness against its monotonic clock. A disconnect removes the peer
from routing immediately. A stale connection closes and leaves the routing pool.
Configured bootstrap addresses remain retry targets while the application runs. In the intranet
prototype the first attempt has no retry delay. After a failed join, choose a random delay
between half the current ceiling and the full ceiling. The first failure uses a 1-second ceiling.
Each subsequent consecutive failure doubles it up to 30 seconds. A successful join resets it.

For a learned address the prototype tries once immediately and, after a failed join, tries once
more after a random delay between 0.5 and 1 second. Repeated hints with the same replica
incarnation and endpoint share that retry budget. A new incarnation or a changed endpoint can
start a fresh attempt. A returning replica can also join by contacting a configured seed. A replica
with no reachable peers continues local execution.

Railix does not enumerate an arbitrary LAN, cloud subnet, or IPv6 range. A Mac and Raspberry Pi
on the same reachable network can instead use each other's address or a stable local hostname as
a seed. Across networks, routing or a VPN remains an operator responsibility; discovery does not
create network reachability.

## Logical Build And Host Compatibility

The build embeds a versioned SHA-256 logical identity over these deterministic inputs.

- the canonical functional project, including compiled configuration;
- deterministic generated production entrypoints;
- used Step implementation and contract identities, including locked provider artifact digests;
- the Railix execution-contract version.

Core owns the logical-build calculation and can share its functional identity inputs with Creator.
Artifact caching retains its separate package build key. Examples, Creator presentation, platform
packaging, listener addresses, TLS credentials, startup secrets, capacity and live measurements
are excluded from the logical identity. Equivalent macOS and Linux/ARM packages can therefore
share one identity.

Only portable Java Flows are eligible for remote execution in the first delivery. A Flow that
depends on JNI, a host device, a machine-local file or another host-specific resource stays local.
Ordinary Railix applications use JDK APIs and require no application-owned JNI. General host
capability declarations belong to later Step placement.

## Flow Admission And Transport Limits

The local development launcher supplies Flow capacity for its replicas. Running local Flows and
admitted remote Flows share one execution pool. The receiver tries to take an execution permit
while handling `invoke` after checking the authenticated session's build ID against its own. A
build mismatch returns `refused` before the receiver takes a permit. The receiver retains no
admission queue. The sole application-level reply to `invoke` is its terminal `refused` or
`outcome`. The ingress replica uses a separate pending-invocation pool of the same configured size
while placing or waiting for a remote Flow. A pending remote invocation holds no local execution
permit.

The first delivery allows at most two sequential remote `invoke` attempts per Flow. Another
peer is eligible only after known non-admission and while the original deadline allows it. The
local attempt is outside that count. The receiver never forwards the invocation to another peer.

| Resource | Version 1 limit |
| --- | --- |
| Aggregate canonical Flow data per invocation or outcome | 1 MiB, measured as encoded UTF-8 JSON including syntax |
| Protocol envelope around the Flow data, including its JSON syntax | 64 KiB |
| Complete encoded JSON data-frame body | 1 MiB + 64 KiB |
| Complete encoded JSON control-frame body | 64 KiB |
| JSON nodes per frame, including object field names, containers and primitives | 65,536 |
| Container depth | 64 for a business value and 72 for a complete JSON document |

Frame body limits exclude the four-byte length prefix. The Flow-data limit uses Railix's 1 MiB
default as its starting point and counts all transferred business data together. Protocol fields
use their own allowance. An otherwise valid input outside the transfer limits is eligible only
for local execution. An admitted Flow whose outcome cannot fit returns a small terminal failure
through the mesh. That failure never permits replay.

Reject zero-length, oversized and truncated frames. Validate length and reserve global inbound
capacity before allocating the frame body. Read it within a finite deadline, reject malformed
UTF-8, BOMs and duplicate fields, and enforce node and depth budgets during core JSON parsing.
Encoded size alone leaves room for many small values to expand substantially on the heap. Bound
global outbound bytes before encoding or queueing and bound retained decoded values separately
from encoded buffers.

## Deadline, Drain And Failure

Under MESH-009, a ready replica with available local Flow capacity MUST continue to run
locally eligible requests while all peers are unreachable or stalled. Peer discovery and
reconnection run independently of those requests.

The ingress replica owns one authoritative monotonic deadline. At dispatch it sends the remaining
duration. The receiver clamps that duration to its allowed limit and applies it using its own
monotonic clock. Ingress expires the operation at its original deadline and discards a late result.
After dispatch, deadline expiry or caller disconnection requests best-effort cancellation.

On drain, a replica advertises that it is draining and refuses new remote admissions. Already
admitted work can finish within the earlier of its local operation deadline or the remaining
application drain budget (30 seconds by default). Drain expiry requests cancellation. Cleanup
preserves resources still in use until execution exits, and the peer channel remains available
to return outcomes while admitted work drains. Step-descendant drain and permit rules belong to
later Step placement.

| Event | First-delivery behavior |
| --- | --- |
| Failure before any `invoke` could have been delivered, or explicit `refused` | Known non-admission. Another eligible peer can be tried within the original limits. |
| Disconnect or receiver crash after possible delivery | Uncertain completion. The ingress replica never replays the invocation. |
| Deadline or caller disconnect after dispatch | Request cancellation. The remote Flow may still complete. |
| Ingress process exits before receiving an outcome | The result loses its owner. Reconnection begins a fresh peer session with no takeover or result recovery. |
| Network partition | Affected peers leave routing when disconnected or stale. New work uses local execution or other fresh peers. |
| Peer authentication or `hello` validation fails, including a logical build mismatch | Close the connection before exchanging `state`. |
| The receiver detects a logical build mismatch while handling `invoke` | Return `refused` before admission. |

Use the existing runtime status and metrics boundary for peer readiness and rejection states,
replica/incarnation IDs, connection and reconnection reasons, active/pending counts, and counts of
admissions, outcomes and rejections. Structured diagnostics correlate invocation ID, peer, attempt
and classification.
Report ignored late or duplicate outcomes and frame or payload rejections. Keep payloads, private
keys, passwords and secrets out of diagnostics and metrics.

## Behavior

- **MESH-001 — Optional capability:** A build without mesh capability MUST preserve standalone
  execution without a mesh listener, peer threads, remote routing, or mandatory serialization.
  A mesh-capable build MUST open its peer listener only when valid startup mesh configuration is
  supplied.
- **MESH-002 — Module isolation:** Mesh transport, discovery, routing, and distributed lifetime
  MUST remain in `railix-mesh`; `railix-core` MUST remain usable without that module and expose
  only the canonical execution boundaries required by local and remote callers.
- **MESH-003 — Authenticated membership:** Every peer connection MUST use mutual TLS and MUST bind
  the authenticated principal to the permitted application operation before business execution.
  Unknown, expired, invalid, or unauthorized credentials MUST reject without invoking a Flow.
- **MESH-004 — Bounded discovery:** A replica MAY scan only its configured bounded loopback port
  range. Cross-host bootstrap MUST use explicit seeds in the first delivery. Every learned
  endpoint MUST authenticate independently. Retained peer knowledge and concurrent connection
  attempts MUST be bounded. The retry schedule MUST follow the endpoint's configured or learned
  origin.
- **MESH-005 — Compatible replicas:** Every session MUST use the fixed v1 wire format.
  Replicas MUST match logical build IDs in `hello` before exchanging discovery and peer status in
  `state`. The receiver MUST verify the build again while handling `invoke` before taking an
  execution permit. OS or CPU differences alone MUST NOT reject equivalent builds. In the first
  delivery, remote placement MUST be limited to portable Java Flows.
- **MESH-006 — Bounded protocol:** Frame sizes, decode depth and node count, active requests,
  pending writes, retained results, waiting, and read/write time MUST have explicit finite limits.
  Malformed, truncated, oversized, unsupported, or stale messages MUST reject without unbounded
  retention or business execution where detectable.
- **MESH-007 — Canonical transfer:** Remote Flow input and output MUST use approved canonical
  values and compiled target identity. Transport handles, Java object graphs, mutable execution
  frames, and executable code MUST NOT cross the peer boundary.
- **MESH-008 — Flow ownership:** The ingress replica MUST retain the source transport and one
  completion obligation. A remote execution owner MUST execute only the admitted compiled target
  and return its result; it MUST NOT independently acknowledge or respond to the original source.
- **MESH-009 — Local-first admission:** Eligible work MUST prefer ordinary local execution while
  local Flow admission is available. Sender capacity knowledge is advisory. The receiver MUST
  decide admission while handling `invoke` based on authority, compatibility, target eligibility,
  remaining time and actual capacity. The receiver MUST immediately refuse an invocation it
  cannot admit. An admitted invocation reports its terminal `outcome`.
- **MESH-010 — Failure distinction:** Explicit non-admission, admitted execution, successful
  completion, cancellation request, and uncertain completion MUST remain distinct. Another peer
  MAY be tried only after known non-admission and within the original bounded attempt/deadline budget.
  Failure after possible admission MUST NOT trigger automatic replay.
- **MESH-011 — One operation budget:** Admission, transport, waiting, execution, and result return
  MUST consume one original operation budget. A hop MUST NOT grant a new full timeout, and clock
  values from unrelated host-monotonic clocks MUST NOT be compared directly.
- **MESH-012 — Distributed backpressure:** Active execution, orchestration waits, retained payload
  bytes, write queues, and placement attempts MUST be bounded separately. Advertised capacity
  MUST NOT reserve a slot; actual admission remains authoritative.
- **MESH-013 — Later Step placement:** The peer channel, correlation, admission, and lifetime
  architecture MUST allow a later distinct Step-invocation payload and handler without replacing
  the Flow transport. The first delivery MUST keep individual Steps local and MUST NOT expose a
  partial Step-dispatch feature.

## First-Delivery Exclusions

The first mesh does not include:

- remote execution of individual Steps;
- peer membership and Flow placement across different logical builds;
- durable recovery or takeover of an invocation owner;
- automatic replay after uncertain completion;
- host provisioning, process supervision, or replacement of an external supervisor;
- a broker, central registry, central scheduler, HTTP fallback, MQTT, or BGP implementation;
- arbitrary subnet scanning or Kubernetes-specific discovery;
- distributed storage ownership, replication, or globally coordinated limits;
- CPU, memory, or I/O telemetry as authoritative admission;
- production credential enrollment or rotation implementation.

These exclusions do not remove the long-term goals. They
keep the first proof bounded while preserving their required architectural boundaries.

## Acceptance And Planned Evidence

For the MESH-004 bootstrap reconnection check, start A while its configured bootstrap peer B is
down. Verify that A retries within the delay bounds, keeps serving local requests and reconnects
when B returns.

For MESH-009, keep a generated application below its local Flow capacity and stall its only
peer. Send real Trigger requests and verify that the application keeps returning their normal
results locally while the peer is stalled, disconnected and reconnecting. This is a planned
integration check.

| Requirements | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| MESH-001/002 | Build and run applications with mesh excluded, included but inactive, and active. Inspect listeners, packaged dependencies, local behavior and retained processes. Start an included build with invalid requested configuration and verify startup fails before binding a mesh listener. | Planned packaged-application proof. |
| MESH-003/005 | Connect two trusted fixed-endpoint replicas and exchange `hello` and `state`. Repeat with unknown or expired credentials, a wrong mesh or replica identity, a wrong key and an unsupported opening message. Verify rejected peers never become ready. | Planned two-process TLS proof. |
| MESH-004 | Start replicas across a bounded occupied loopback range. On two hosts, bootstrap B against A and hold back B's first ready `state`. Verify through runtime peer status that B enters routing only after that state arrives and leaves on disconnect. Withhold the first `state` past the join deadline and verify A closes the socket while serving local requests. Also verify duplicate connection resolution, bootstrap reconnection and two dial attempts for an unreachable learned peer repeatedly advertised by B. | Planned local and cross-host proof. |
| MESH-005 | Connect two replicas with trusted certificates and different build IDs. Verify `hello` validation closes the connection before either replica sends `state` or records the other as a joined peer. Separately exercise the admission check with a mismatched build and verify `refused` before any permit or Flow execution. | Planned join and invocation rejection proof. |
| MESH-005/007 | Run matching builds in macOS and Linux/ARM packages and exercise an eligible Flow and a platform-ineligible target. | Planned Mac/Raspberry Pi proof. |
| MESH-006/007/012 | Exercise UTF-8, byte, node and depth boundaries, aggregate outcomes and simultaneous slow frames. Verify early rejection, bounded memory and recovery. | `PeerFramesTest` covers length encoding, byte limits and partial reads. `PeerChannelTest` covers malformed frames, whole-frame writes and socket closure after a failure. Mesh JSON and concurrent resource checks are planned. |
| MESH-008/009 | Saturate A with real source requests. Verify its `invoke` gets only a terminal `outcome` from B and the original source gets one response from A. Saturate B with local and remote work and verify `invoke` gets an immediate `refused`. | Planned generated-application integration test. |
| MESH-010/011 | Refuse an invocation, disconnect after possible admission, return a late result and drain with one ongoing Flow. Verify bounded attempts, one ingress deadline and no duplicate execution. | Planned controlled failure and shutdown tests. |
| MESH-013 | Complete concurrent Flow invocations out of order over one peer channel. | Flow evidence planned first. Step evidence belongs to the later milestone. |

## Delivery Gates

- **Fixed-peer membership:** implement the startup and authentication contract above and exercise
  the two-process TLS cases in the acceptance table.
- **Broader discovery:** measure join, heartbeat, stale and bootstrap retry timings under load on the
  Raspberry Pi. Use that evidence to set finite peer and concurrent-handshake caps and a learned-hint
  lifetime before enabling learned peers.
- **Remote Flow execution:** establish build-time eligibility for portable providers. Measure and
  set the source operation budget, receiver clamp, frame deadlines and aggregate inbound, outbound
  and retained-value budgets before completing the remote-execution milestone.
- **Packaged evidence:** verify the build/module integration from ADR 0024 for the three activation
  cases in the acceptance table. Packaging and canonical invocation work can proceed independently
  of the fixed-peer test.
