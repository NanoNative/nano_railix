# Railix Mesh

Coordinates already-running replicas of one logical build. See the [mesh guide](../mesh.md)
for setup, [ADR 0024](../adr/0024-optional-mesh-module-and-peer-channel.md) for rationale, and
[roadmap Item 7](../roadmap.md#7-decentralised-execution-state-sharding-and-replication)
for delivery status. The [system model](system-model.md) owns local execution.
[Environments and security](environments-and-security.md) owns secret delivery, grants,
credential enrollment, and rotation.

## Behavior

- **MESH-001 — Optional capability.** Standalone builds MUST omit mesh code, listeners, peer
  threads, and remote routing. Mesh builds MUST activate the peer listener only with valid startup
  configuration. Both paths MUST execute local Flows and Steps through direct compiled calls.
- **MESH-002 — Module isolation.** `railix-mesh` owns peer transport, discovery, routing, admission,
  and distributed lifetime. It depends only on `railix-core`. Core owns canonical values,
  execution semantics, and transferable invocation and result contracts. Generated code supplies
  compiled targets. Peer lifetime is independent of Creator.
- **MESH-003 — Authenticated membership.** Both peers MUST authenticate with TLS. The receiver
  MUST bind the authenticated principal to the permitted operation before business execution.
  Unknown, expired, invalid, or unauthorized credentials MUST fail before invoking a Flow.
- **MESH-004 — Bounded discovery.** Scan only a configured loopback port range. Cross-host
  bootstrap MUST use explicit endpoints. Authenticate every learned endpoint independently.
  Bound retained peer knowledge and concurrent connection attempts. Retry according to the
  address's origin.
- **MESH-005 — Compatible replicas.** Use the fixed v1 wire format. Peers MUST match logical build
  IDs in `hello` before exchanging discovery or status. The receiver MUST verify the build again
  on `invoke` before taking a permit. Equivalent builds MUST be compatible across supported
  operating systems and CPU architectures. First delivery permits only portable Java Flows.
- **MESH-006 — Bounded protocol.** Bound frames, decode depth and nodes, active requests, pending
  writes, retained results, wait times, and read and write deadlines. Reject malformed, truncated,
  oversized, unsupported, or stale messages before execution where detectable. Bound cleanup
  and retention on rejection.
- **MESH-007 — Canonical transfer.** Carry approved canonical values, execution metadata, and
  compiled target identity. Exclude source sockets, transport handles, authored graphs, Java
  classes, object graphs, closures, executable configuration, resource registries, and live
  execution frames such as `WorkflowRuntime.FlowRunner`.
- **MESH-008 — Flow ownership.** Ingress owns source processing, the original transport, and one
  completion obligation. The receiver executes the admitted compiled continuation and returns
  its `RunResult` to ingress. Ingress applies the compiled response slots and completes the
  original request.
- **MESH-009 — Local-first admission.** A ready replica MUST execute eligible work locally while a
  local slot is available, including when peers are stalled or unreachable. If local capacity is
  exhausted, only a fresh, ready peer can be considered for offload. The receiver decides
  admission using authority, compatibility, eligibility, time remaining, and actual capacity.
  Refuse immediately if any check fails. Deliver the terminal outcome within the reply budget if
  the connection and ingress are still available.
- **MESH-010 — Failure distinction.** Distinguish known non-admission, a terminal Flow outcome,
  and uncertain completion. Retry at another eligible peer only after known
  non-admission and within the original attempt and deadline limits. If a failure follows
  possible admission, report uncertain completion and stop placement attempts.
- **MESH-011 — One operation budget.** Transfer, admission, and the reply share one ingress
  deadline. Stamp the remaining duration after encoding and the writer wait. The receiver
  validates the bound and starts its local clock budget at the frame header. Admission transfers
  ownership of execution to the receiver. Expiry or connection loss ends the reply path while
  the accepted Flow continues under the receiver's application lifecycle.
- **MESH-012 — Distributed backpressure.** Bound execution, pending invocations, payload
  retention, write queues, and placement attempts independently. Advertised capacity is
  advisory. Only actual admission reserves a slot.
- **MESH-013 — Later Step placement.** Steps run within their Flow on its selected replica in the
  first delivery. Reuse the channel, correlation, admission, and lifetime boundaries for a
  later distinct Step invocation and handler.

## Startup Configuration

`railix build --mesh` packages the matching bundled mesh classes and launcher offline. Mesh
selection is independent of project-source inclusion and observation. With `RAILIX_MESH_CONFIG`
unset, a mesh-enabled build runs standalone. A supplied configuration must be readable and valid
with usable credentials. Otherwise startup fails before the mesh listener opens. Listen hostname
resolution has a five-second limit. The application passes its arguments to ordinary sources.

| JSON field | Contract |
| --- | --- |
| `meshId` | Mesh authority ID. It matches the ID in the local certificate. |
| `listenEndpoint`, `advertisedEndpoint` | Objects with hostname or IP `host` and integer `port`. The first binds the local socket. Peers dial the second. |
| `bootstrapEndpoints` | List of endpoints in the same form. |
| `flowCapacity` | Positive integer. Execution slots on this replica, shared by local requests and work accepted from peers. |
| `offloadFlow` | Optional compiled Trigger ID. For the pilot, configure the same ID on participating replicas. Omission leaves the replica in membership-only mode. |
| `identityStore`, `trustStore` | Objects with PKCS12 `path` and `passwordFile`. Relative paths resolve from the configuration file's directory. |

Configuration, stores, and password files MUST be regular files. Each password file contains one
UTF-8 line and is readable only by the application account. Deploy private keys and passwords
separately from JSON, command lines, Git, and generated artifacts. Deploy each replica with its
own key and X.509 chain plus a separate store of trusted issuers. The demo launcher may use JDK
`keytool` with a random temporary mesh ID, a shared temporary issuer, and distinct replica
credentials and password files. Generate unpredictable keys and passwords.

## Peer Transport And Trust

Retain one persistent bidirectional JDK TLS/TCP session per connected pair. The four-byte unsigned
big-endian length prefixes UTF-8 JSON encoded with `RailixJson`. Flow data preserves decimal
scale and object field order. Version 1 uses uncompressed messages. Reject an unsupported opening
message. Invocation IDs correlate responses that can complete out of order on the same connection.

| Message | Content |
| --- | --- |
| `hello` | `type` is `hello.v1`. Both replicas send `logicalBuildId`, `meshId`, `replicaId`, and `incarnationId`. |
| `state` | `type` is `state`. Carries `status` (`starting`, `ready`, or `draining`), `advertisedEndpoint`, `availableFlowSlots`, `offloadFlow` (a Trigger ID or `null`), and `peerHints`. Also serves as the heartbeat. |
| `invoke` | Compiled continuation, canonical input, and invocation ID. The invocation ID correlates all messages for the call. `admissionNanos` is a ten-digit decimal string (up to five seconds) stamped when the writer is ready. |
| `refused` | Terminal before admission. Structured reason includes build mismatch, saturation, drain, ineligibility, or expiry. |
| `outcome` | Terminal after admission. `payload` contains the `RunResult` with its context and branch outcomes where applicable. |

Both peers verify key possession, certificate trust, and the peer's TLS role. The certificate
URI Subject Alternative Name binds mesh and stable replica identity. Match this URI locally
with lower-case UUIDs:

`https://github.com/NanoNative/nano_railix/mesh/v1/<mesh-id>/replicas/<replica-id>`

Startup requires a currently valid trust anchor. Validate new handshakes against anchors valid
at join. Each process creates a random incarnation ID. Check `hello` claims against the
certificate, mesh authority, and logical build. Close on mismatch before sending `state`.
Keep the authenticated build ID for the session. The replica with the lexicographically smaller
certificate-bound ID reserves a socket and sends `state` first. Its peer validates that state,
reserves the same socket locally and replies with `state`. Each side publishes the peer after
sending and validating initial state.

| Prototype timing | Contract |
| --- | --- |
| Join | One internal 5-second deadline from dial or accept covers DNS, TCP, TLS, `hello`, and the initial `state` exchange. Reserve outbound connection capacity after DNS so inbound joins can proceed. Try resolved addresses in resolver order. Divide remaining time among untried addresses and carry unused time forward. Close on expiry and apply the discovery retry policy. |
| Heartbeat | Send `state` every 2 seconds with a 2-second send deadline. Skip on queue expiry. Abort on active write expiry. Close and remove a peer after 8 seconds without valid `state` or immediately on disconnect. |

## Discovery And Peer Knowledge

### Configured Membership

- Use fixed listen endpoints and reachable advertised endpoints. Cross-host joins use explicit
  seed endpoints. Run joins and retries independently of source requests.
- Retain one connection per replica ID, up to 12 peers. Allow one inbound and one outbound
  handshake concurrently per replica.
- Publish all connected peers in `peers`. Creator uses the first entry for its summary when one
  exists. Advertise configured Flow capacity and empty `peerHints` in `state`.
- Either replica can dial. Count reservations toward the peer limit and release them when join
  fails. Reject duplicate sockets and overlapping incarnations while a reservation or session
  owns the replica ID. Allow a new join after the prior connection ends.
- Hostname lookups wait for one resolver slot within the join deadline.
- Try configured seeds immediately. Seeds that resolve to the same socket address share one
  attempt and retry history. After failure, retry after a random delay from half the ceiling to
  the ceiling. Start at a 1-second ceiling and double it after each consecutive failure, up to
  30 seconds. Reset after a follow-up heartbeat. A disconnect before that heartbeat counts as a
  failed join. If another connection to the replica is retained, wait for it to end before redialing.

### Automatic Discovery (Planned)

- Probe only the configured loopback range. Authenticate each hinted endpoint and match its
  logical build before adding it as a peer.
- Try a learned endpoint immediately, then once more after a random delay of 0.5 to 1 second.
  Repeated hints for the same incarnation and endpoint share that budget. A new incarnation
  or endpoint can start a fresh attempt. A returning replica can contact a configured seed.

## Logical Build And Host Compatibility

The build embeds a versioned SHA-256 logical ID. Inputs are the canonical functional project
with compiled configuration, deterministic production entrypoints, used Step implementations
and contracts with locked artifact digests, and the Railix execution contract, including the
packaged core and mesh classes. Fingerprint sorted class paths and contents. Core owns this
calculation. Artifact caching uses a separate package key.
Artifacts with incompatible peer message contracts must have distinct logical build IDs.

Examples, Creator presentation, platform packaging, listener addresses, credentials, startup
secrets, and runtime capacity or measurements are excluded. Equivalent macOS and Linux/ARM
packages can share an ID. Flows that depend on JNI, local devices, machine-local files, or other
host-specific resources stay local. General host-capability declarations belong to Step placement.

## Flow Admission And Transport Limits

Share one configured execution pool between local and remote work. Check admission before
consuming a permit and refuse without queuing. Each `invoke` receives only its terminal
`refused` or `outcome`. Ingress holds no execution permit while placing or waiting for remote
work. Permit at most three sequential remote attempts, excluding the local attempt. Only ingress
can place an invocation.

The configured pilot uses a five-second admission and reply budget and a two-second write bound.
Ingress caps pending offloads at 12 per replica independently of `flowCapacity` to bound retained
data. The deployment selects one Flow with equivalent resources across its replicas.
Ingress filters for the advertised Flow before counting attempts. Rank ready peers by advertised
free slots, including peers that reported zero. The receiver checks its current capacity on arrival.
Each session reads one frame at a time. The peer and handshake limits bound the number of readers.
The receiver refuses a new invocation when its separate retained-input budget is full.

Each joined connection has one FIFO writer. Bound it to two invocation writes, two terminal
replies, 12 early refusals, and one heartbeat, including frames in progress.
Reserve a terminal reply before taking a remote execution slot. Refuse before execution if
either reservation is unavailable. Return the execution slot when execution exits and any timely
reply has been handed to the writer. The writer returns a queued reply reservation when it sends,
expires, or drops the frame.

Prepare input after reserving a writer buffer. Keep at most one prepared frame per pending
offload. Reuse it across attempts and stamp the remaining duration before each write.

The reader queues early refusals and keeps reading. A worker that the application declines
hands its refusal to the writer before releasing its slot.
Terminal writes use the remaining call budget and the two-second write limit. Undelivered
replies retain the uncertainty rules.

| Limit | v1 |
| --- | --- |
| Aggregate canonical Flow data per invocation or outcome | 1 MiB of encoded UTF-8 JSON including syntax |
| Protocol envelope | 64 KiB including JSON syntax |
| Complete data-frame body | 1 MiB + 64 KiB |
| Complete control-frame body | 64 KiB |
| JSON nodes per frame, including field names, containers, and primitives | 65,536 |
| Container depth | 64 for business values and 72 for a complete document |

Body limits exclude the length prefix. Otherwise valid input over the transfer limit stays
local. An oversized outcome returns a small terminal failure and ends replay eligibility.
Reject zero-length, oversized, or truncated frames. Validate length before allocation. The
frame and reader limits bound receive buffers. Read within a finite deadline. Reject malformed
UTF-8, BOMs, and duplicate fields. Enforce JSON node and depth bounds during parsing. Reserve
a connection's write capacity before encoding or queuing. Bound retained decoded data separately
at Flow admission.

## Deadline, Drain And Failure

| Condition | Required behavior |
| --- | --- |
| Delivery was impossible or the peer explicitly refused | A different eligible peer can be tried within the original bounds. |
| The source thread is interrupted before the writer claims the invocation | Return `Cancelled`. Drop the invocation. |
| The connection or peer is lost after possible delivery | End with uncertain completion. The receiver owns any accepted execution. |
| The original deadline expires or the source thread is interrupted after possible delivery | End with uncertain completion and discard late outcomes. Accepted execution continues. |
| Ingress exits before receiving an outcome | The outcome loses its owner. Reconnection creates a new session. Invocation takeover and recovery are outside this delivery. |
| Replica drains | Publish `draining` and refuse new remote admissions. Wait for execution and outstanding replies within the application drain budget (30 seconds by default). Replies retain their earlier deadlines. At drain expiry the local application lifecycle can interrupt its own work. Preserve resources in use until execution exits. |
| Network partitions | Remove disconnected or stale peers. Continue locally or use other eligible peers. |

The current HTTP Trigger can wait until the offload deadline after the client disconnects.
Once the writer claims an invocation, it owns the write until completion or its write deadline.

Expose join state and the latest rejection through [local standalone observation](observation.md#local-standalone-connection).
Retain the latest rejection across membership changes.
With Flow offload enabled, report Flow execution metrics on the replica that runs the Flow.
Ingress records Trigger Step metrics on the replica that received the request.
Publish offload counters only when `offloadFlow` is configured. Increment `offload_ack` at
ingress for a terminal peer outcome validated before the reply deadline. Increment
`offload_no_ack` for uncertain completion after possible delivery. Count each Flow once.
Explicit refusals and unsent attempts leave both counts unchanged.
Creator reads status owned by the selected process. Extend existing runtime status and metrics
with readiness, replica and incarnation IDs, connection and reconnection reasons, active and
pending counts, admissions, outcomes, and rejections. Correlate diagnostics by invocation, peer,
attempt, and failure classification. Report ignored late or duplicate outcomes and frame or
payload rejections. Omit business payloads, private keys, passwords, and secrets from telemetry.

## First-Delivery Scope

Peers coordinate already-running replicas. Host provisioning, process supervision, distributed
state ownership, replication, globally coordinated limits, and credential enrollment and rotation
belong to separate work. Use direct authenticated peer sessions with no central registry,
scheduler, broker, or alternate HTTP, MQTT, or BGP peer path. Discovery is limited to configured
seeds, the local port range, and authenticated hints. Flow slots govern admission. Public HTTP can
reach replicas directly or through a deployment-provided reverse proxy.

## Acceptance

| Requirements | Public-boundary proof |
| --- | --- |
| MESH-001/002 | Build offline. Run executable JARs with mesh omitted, inactive, active, and invalid configuration. Inspect the JAR, listeners, normal source execution, and process cleanup. Invalid requested configuration fails before binding. |
| MESH-003/005 | Run two fixed-peer JVMs with separate credentials. Verify mutual TLS, matching `hello`, initial `state` exchange, and selected-process status in Creator. Exercise unknown or expired credentials, wrong mesh, replica, or key, and unsupported opening. Different logical builds close before either side publishes `state`. At dispatch, a mismatched invocation is refused before execution or permit acquisition. |
| MESH-004 | Send a valid `starting` state. Verify the peer joins and stays ineligible for offload until it reports `ready`. In a separate run, complete TLS and `hello` and withhold the first `state` past the join deadline. Verify the session times out while the replica keeps serving local requests. |
| MESH-004 | Join four configured replicas. Stop the original seed. Verify the survivors stay connected and a restarted replica joins through a survivor. Exercise a two-host bootstrap. |
| MESH-004 | Start with the seed down. Verify bounded retries, local responses, and reconnection. Verify that aliases share backoff, DNS contention respects the join deadline, and stale or disconnected peers are removed. |
| MESH-004 | Exercise occupied ports during loopback probing. Verify two attempts for a repeatedly hinted unreachable address. |
| MESH-005 | Package the same project with the fixed-peer contract that requires empty hints and the incompatible discovery contract that sends nonempty hints. Verify distinct logical build IDs. Connect with trusted credentials and verify both sides reject at `hello` before either sends `state`. |
| MESH-005/007 | Join equivalent macOS and Linux/ARM packages. Execute an eligible Flow and exercise an ineligible host-specific target. |
| MESH-006/007/012 | Exercise UTF-8, byte, node, and depth limits, aggregate outcomes, and simultaneous slow frames. Verify early rejection, bounded memory, and recovery. |
| MESH-008/009 | While A is saturated, send an HTTP request directly to A. B executes the eligible Flow and returns one terminal outcome. A completes one original response with the same result as local execution. Saturate B with local and remote work and verify immediate refusal. With A below capacity, verify local source responses through peer stall, disconnect, and reconnect. |
| MESH-010/011 | Verify a third eligible peer can return the outcome after two explicit refusals within the original deadline. Exercise disconnect after possible admission, late results, and drain. Verify continued receiver execution after reply expiry or ingress loss and no replay. |
| MESH-013 | Complete concurrent Flow invocations out of order on one channel. Exercise Step invocation only at its later milestone. |

Run the fixed-peer proof on a compatible installed Java runtime. Use
[Item 8 distributions](../roadmap.md#8-direct-generation-distribution-and-certification) for the
later packaged native proof. See [fixed-peer evidence](../verification.md#fixed-peer-mesh-membership).

## Delivery Gates

- **Discovery.** Measure join, heartbeat, staleness, and retry timings under load on Raspberry Pi
  to set finite peer and handshake caps and learned-hint lifetime before enabling learned peers.
- **Flow execution.** Establish build-time eligibility for portable providers. Measure and set the
  admission and reply budget, frame deadlines, and aggregate inbound, outbound, and
  retained-value limits.
- **Native distribution.** Repeat the MESH-001/002 activation cases and MESH-003/005 peer checks
  on the self-contained distributions from Item 8.
