# Railix Mesh

## Human Review

Status: **Accepted first-delivery decisions; implementation and remaining budgets planned**,
updated on 2026-11-18.

Owns: discovery of already-running replicas, authenticated peer membership, remote Flow
placement, distributed admission and invocation lifetime, peer transport, and the boundary that
later supports remote Step placement. The [system model](system-model.md) retains ownership of
local Flow and Step semantics. [Environments and security](environments-and-security.md) retains
ownership of secret delivery, immutable grants, and credential rotation. The
[roadmap](../roadmap.md#7-decentralised-execution-state-sharding-and-replication) owns delivery
sequence and status.

The first mesh coordinates replicas of one compiled Railix application. It does not turn Steps
into separately deployed services or require a broker, central scheduler, registry, or
user-managed orchestration layer. The initial distributed work unit is one complete Flow
invocation. Individual eligible Step placement is a later delivery over the same peer-channel and
invocation-lifecycle architecture.

The accepted architecture is recorded by
[ADR 0024](../adr/0024-optional-mesh-module-and-peer-channel.md). The decisions below were agreed
with Abhiroop Basu in the first-delivery mesh design discussion and describe planned behavior.

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
- Routing is local-first. A remote replica remains a candidate until it performs the final
  compatibility and capacity checks and admits the invocation.
- Known non-admission may permit another bounded placement attempt. Once admission may have
  occurred, loss of the connection or result is uncertain completion and MUST NOT cause automatic
  replay.
- Compatibility uses the same logical application build, not byte-identical operating-system
  packages. Different supported operating systems or CPU architectures are not blockers when the
  compiled application semantics and required capabilities are equivalent.

### First-delivery journey

When a request reaches replica A:

1. A owns the original socket and Trigger lifecycle and normalises the source input.
2. A executes the Flow locally when it can admit it.
3. When local Flow admission is saturated, A selects a known, authenticated, compatible peer B.
4. B rechecks the peer authority, protocol, logical build, target eligibility, and current
   capacity before admission.
5. If admitted, B executes the same compiled Flow continuation that the local path would call and
   returns a canonical result to A.
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

## Peer Transport And Trust

Version 1 uses a persistent, bidirectional JDK TLS connection for each connected replica pair.
Each frame starts with a four-byte unsigned big-endian length, followed by that many bytes of
canonical UTF-8 JSON encoded with `RailixJson`. Peers require an exact Railix protocol-version
match. Version 1 excludes compression. Invocation messages share an invocation ID and may
complete out of order on the same connection.

| Message | Purpose and data |
| --- | --- |
| `hello` | Establishes the protocol version, mesh authority, logical build, replica and process-incarnation IDs, and advertised endpoint. |
| `state` | Reports readiness or drain, available execution capacity and bounded peer hints. Periodic state is also the heartbeat. |
| `invoke` | Selects the compiled Flow continuation and carries its canonical input, invocation ID, remaining duration, attempt and trace identity. |
| `admitted` | Confirms the receiver accepted execution ownership for the invocation. |
| `refused` | Confirms non-admission with a structured reason, such as saturation, drain, ineligibility or expiry. |
| `outcome` | Returns the terminal result, preserving applicable Flow outcome and branch semantics. |
| `cancel` | Requests cancellation of an in-flight invocation. |

Each deployed replica receives its own private key and X.509 certificate chain in a PKCS12
identity store and trusted issuers in a separate PKCS12 trust store. Both peers prove possession
of their private keys and validate the other's certificate for its TLS role. A URI Subject
Alternative Name binds the signed certificate to the mesh authority ID and stable replica ID.
Peers check the configured mesh authority and verify the corresponding claims in `hello` against
that certificate before accepting work. Each process generates a separate random incarnation ID
at startup. The logical build and protocol version are independent compatibility checks.

When mesh activation is requested, missing or invalid required credentials MUST prevent mesh
readiness rather than silently opening a plaintext listener or downgrading to unauthenticated
local-only operation. For local development the launcher generates a random temporary mesh ID
and uses JDK `keytool` to provision a shared temporary trust root with a distinct key and
certificate per replica. Deployment supplies production credentials at startup. Enrollment and
rotation remain owned by [Environments and security](environments-and-security.md).

## Discovery And Peer Knowledge

A replica may choose the first available listener port from a configured bounded local range.
For local multi-process development it may probe that range on loopback and accept a candidate
only after successful authentication and compatibility checks.

Cross-host startup configuration supplies one fixed listen endpoint, a reachable advertised
endpoint, and explicit bootstrap endpoints as hostname/IP and port pairs. The listen endpoint binds
the local socket. Peers dial the advertised endpoint. An authenticated, compatible peer may share
a bounded set of addresses in `state`. Each hint requires direct authentication and compatibility
checks before it becomes a placement candidate.

When both replicas dial each other, the connection initiated by the lexicographically smaller
certificate-bound replica ID wins. Shared connection IDs break ties between duplicate connections
initiated in the same direction. A replacement must be authenticated and ready before retiring
a ready connection. A restarted process can join with a new incarnation after the old connection
ends. A peer that sees overlapping incarnations claiming one replica ID reports the conflict and
retains only one connection for that replica.

Authenticated `state` messages refresh the direct connection against the receiving replica's
monotonic clock. Stale connections close and leave the routing pool. Configured bootstrap addresses
remain retry targets while the application runs, with capped exponential backoff and jitter.
Learned addresses expire after bounded attempts. A returning replica can rejoin through a seed
or be discovered again through fresh peer exchange. A replica with no reachable peers continues
local execution.

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

Mesh activation in production requires one positive per-replica Flow-capacity value at startup.
The local development launcher supplies that value for its replicas. Running local Flows and
admitted remote Flows share one execution pool. The receiver admits immediately or refuses
saturation and retains no admission queue. The ingress replica uses a separate pending-invocation
pool of the same configured size while placing or waiting for a remote Flow. A pending remote
invocation holds no local execution permit.

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
| Authentication or compatibility fails | The peer cannot receive Flow work. |

Use the existing runtime status and metrics boundary for peer readiness and rejection states,
replica/incarnation IDs, connection and reconnection reasons, active/pending counts and outcome or
rejection counts. Structured diagnostics correlate invocation ID, peer, attempt and classification.
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
- **MESH-005 — Compatible replicas:** A peer MUST match the required logical application build
  and protocol before admission. OS or CPU differences alone MUST NOT reject equivalent builds.
  In the first delivery, remote placement MUST be limited to portable Java Flows.
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
  local Flow admission is available. Sender capacity knowledge is advisory; the receiver MUST
  recheck authority, compatibility, target eligibility, and actual capacity before admission.
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
- mixed-build or rolling-version execution cohorts;
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

| Requirements | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| MESH-001/002 | Build/run an application with mesh excluded and included-but-inactive; inspect listeners, packaged dependencies, local behavior, and retained processes. | Planned packaged-application proof. |
| MESH-003 | Connect trusted, unknown, expired, wrong-application, and wrong-key peers; verify no unauthorized Flow execution or plaintext fallback. | Planned two-process TLS proof; enrollment/rotation remain open. |
| MESH-004 | Start replicas across a bounded occupied loopback range. On two hosts, verify explicit bootstrap, duplicate connection resolution, bootstrap reconnection and expiry of learned peers. | Planned local and cross-host proof. |
| MESH-005/007 | Run the same logical build in macOS and Linux/ARM packages, dispatch an eligible Flow, then attempt a platform-ineligible target and a different logical build. | Planned Mac/Raspberry Pi proof; platform packaging must exist first. |
| MESH-006/007/012 | Exercise UTF-8, byte, node and depth boundaries, aggregate outcomes and simultaneous slow frames. Verify early rejection, bounded memory and recovery. | Planned protocol/resource tests after aggregate byte and time budgets are selected. |
| MESH-008/009 | Saturate A with real source requests, execute an eligible Flow on B, and verify one response from A. Saturate B with both local and accepted remote work and verify immediate refusal. | Planned generated-application integration test. |
| MESH-010/011 | Refuse an invocation, disconnect after possible admission, return a late result and drain with one ongoing Flow. Verify bounded attempts, one ingress deadline and no duplicate execution. | Planned controlled failure and shutdown tests. |
| MESH-013 | Complete concurrent Flow invocations out of order over one peer channel. | Flow evidence planned first. Step evidence belongs to the later milestone. |

## Decisions Before Implementation

The sections above record the accepted behavior. Remaining decisions are scoped to the first
milestone that depends on them.

- **Secure peer membership:** final wire field and startup setting schema, certificate URI syntax,
  peer and concurrent-handshake limits, connection and handshake deadlines, heartbeat/freshness
  intervals, and bootstrap backoff and learned-address retry/expiry values.
- **Remote Flow execution:** how the build establishes eligibility for portable providers, the
  source operation time budget and receiver clamp, frame read/write deadlines, and aggregate
  inbound, outbound and retained-value budgets measured under concurrent load.
- **Packaged evidence:** implement and verify the build/module integration from ADR 0024, including
  excluded, included-but-inactive, and active mesh packages. The acceptance table defines the
  evidence for each slice.

Independent packaging, canonical-boundary and test-harness work can proceed while the remaining
decisions are reviewed. Numeric limits require representative workloads and an explicit owner.
