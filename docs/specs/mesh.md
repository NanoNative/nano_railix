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
  its outcome to ingress. Ingress alone completes the source response.
- **MESH-009 — Local-first admission.** A ready replica MUST execute eligible work locally while a
  local slot is available, including when peers are stalled or unreachable. If local capacity is
  exhausted, only a fresh, ready peer can be considered for offload. The receiver decides
  admission using authority, compatibility, eligibility, time remaining, and actual capacity.
  Refuse immediately if any check fails. An admitted invocation returns a terminal outcome.
- **MESH-010 — Failure distinction.** Distinguish non-admission, admission, success, cancellation
  requested, and uncertain completion. Retry at another eligible peer only after known
  non-admission and within the original attempt and deadline limits. If a failure follows
  possible admission, report uncertain completion and stop placement attempts.
- **MESH-011 — One operation budget.** Admission, transfer, waiting, execution, and result return
  consume one ingress deadline. Send remaining duration. The receiver clamps it and uses its own
  monotonic clock.
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
| `flowCapacity` | Positive integer. |
| `identityStore`, `trustStore` | Objects with PKCS12 `path` and `passwordFile`. Relative paths resolve from the configuration file's directory. |

Configuration, stores, and password files MUST be regular files. Each password file contains one
UTF-8 line and is readable only by the application account. Deploy private keys and passwords
separately from JSON, command lines, Git, and generated artifacts. Deploy each replica with its
own key and X.509 chain plus a separate store of trusted issuers. The demo launcher may use JDK
`keytool` with a random temporary mesh ID, a shared temporary issuer, and distinct replica
credentials and password files. Generate unpredictable keys and passwords.

## Peer Transport And Trust

Retain one persistent bidirectional JDK TLS/TCP session per connected pair. The four-byte unsigned
big-endian length prefixes canonical UTF-8 JSON encoded with `RailixJson`. Version 1 uses
uncompressed messages. Reject an unsupported opening message. Invocation IDs correlate
responses that can complete out of order on the same connection.

| Message | Content |
| --- | --- |
| `hello` | `type` is `hello.v1`. Carries `logicalBuildId`, `meshId`, `replicaId`, and `incarnationId`. The dialer supplies `connectionId`. |
| `state` | `type` is `state`. Carries `status` (`starting`, `ready`, or `draining`), `advertisedEndpoint`, `availableFlowSlots`, and `peerHints`. Also serves as the heartbeat. |
| `invoke` | Compiled continuation, canonical input, invocation ID, remaining duration, attempt, and trace identity. |
| `refused` | Terminal before admission. Structured reason includes build mismatch, saturation, drain, ineligibility, or expiry. |
| `outcome` | Terminal after admission. Preserves applicable Flow outcome and branch semantics. |
| `cancel` | Best-effort cancellation request for an in-flight invocation. |

Both peers verify key possession, certificate trust, and the peer's TLS role. The certificate
URI Subject Alternative Name binds mesh and stable replica identity. Match this URI locally
with lower-case UUIDs:

`https://github.com/NanoNative/nano_railix/mesh/v1/<mesh-id>/replicas/<replica-id>`

Startup requires a currently valid trust anchor. Validate new handshakes against anchors valid
at join. Each process creates a random incarnation ID. Check `hello` claims against the
certificate, mesh authority, and logical build. Close on mismatch before sending `state`.
Keep the authenticated build ID for the session. The first valid `state` completes join.

| Prototype timing | Contract |
| --- | --- |
| Join | One internal 5-second deadline from dial or accept covers DNS, TCP, TLS, `hello`, and first valid `state`. Reserve outbound connection capacity after DNS so inbound joins can proceed. Try resolved addresses in resolver order. Divide remaining time among untried addresses and carry unused time forward. Close on expiry and apply the discovery retry policy. |
| Heartbeat | Send `state` every 2 seconds. Measure freshness locally with a monotonic clock. Close and remove a peer after 8 seconds without valid `state`. Remove it immediately on disconnect. |

## Discovery And Peer Knowledge

The fixed-peer checkpoint uses two processes, one session and one handshake per replica, empty
`peerHints`, and configured Flow capacity. Bounded discovery adds multiple sessions and learned
hints. Remote admission adds live slot accounting.

- Provide a fixed listen endpoint, a reachable advertised endpoint, and seed endpoints on
  cross-host deployments. Authenticate a hinted address directly and match its build before
  treating it as a known peer. Discovery and reconnection run independently of source requests.
- For simultaneous dials, keep the connection initiated by the lexicographically smaller
  certificate-bound replica ID. Use shared connection IDs to break same-direction ties.
  Authenticate a replacement and wait for readiness before retiring a ready connection.
  Retain one connection per replica ID. Report overlapping incarnations. A restarted replica
  can rejoin after the previous connection ends.
- Try configured seeds immediately. After failure, retry with random delay in the range from
  half the ceiling to the ceiling. Start at a 1-second ceiling, double after consecutive
  failures up to 30 seconds, and reset on successful join.
- Try a learned endpoint immediately, then once more after a random delay of 0.5 to 1 second.
  Repeated hints for the same incarnation and endpoint share that budget. A new incarnation
  or endpoint can start a fresh attempt. A returning replica can contact a configured seed.

## Logical Build And Host Compatibility

The build embeds a versioned SHA-256 logical ID. Inputs are the canonical functional project
with compiled configuration, deterministic production entrypoints, used Step implementations
and contracts with locked artifact digests, and the Railix execution contract, including the
packaged core runtime. Core owns this calculation. Artifact caching uses a separate package key.
Artifacts with incompatible peer message contracts must have distinct logical build IDs.

Examples, Creator presentation, platform packaging, listener addresses, credentials, startup
secrets, and runtime capacity or measurements are excluded. Equivalent macOS and Linux/ARM
packages can share an ID. Flows that depend on JNI, local devices, machine-local files, or other
host-specific resources stay local. General host-capability declarations belong to Step placement.

## Flow Admission And Transport Limits

Share one configured execution pool between local and remote work. Check admission before
consuming a permit and refuse without queuing. Each `invoke` receives only its terminal
`refused` or `outcome`. Ingress uses a separate pending-invocation pool of the same configured
size and holds no execution permit while placing or waiting for remote work. Permit at most two
sequential remote attempts, excluding the local attempt. Only ingress can place an invocation.

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
Reject zero-length, oversized, or truncated frames. Validate length and reserve global inbound
bytes before allocation. Read within a finite deadline. Reject malformed UTF-8, BOMs, and
duplicate fields. Enforce JSON node and depth bounds during parsing. Reserve global outbound
bytes before encoding or queuing. Bound retained decoded data separately from buffers.

## Deadline, Drain And Failure

| Condition | Required behavior |
| --- | --- |
| Delivery was impossible or the peer explicitly refused | A different eligible peer can be tried within the original bounds. |
| The connection or peer is lost after possible delivery | End with uncertain completion. |
| The original deadline expires or the caller disconnects after dispatch | Request best-effort cancellation. Discard late results. The receiver may already have completed work. |
| Ingress exits before receiving an outcome | The outcome loses its owner. Reconnection creates a new session. Invocation takeover and recovery are outside this delivery. |
| Replica drains | Publish `draining` and refuse new remote admissions. Keep the channel open for outcomes. Finish admitted work within the earlier of the operation deadline and remaining app drain budget (30 seconds by default). Request cancellation at expiry. Preserve resources in use until execution exits. |
| Network partitions | Remove disconnected or stale peers. Continue locally or use other eligible peers. |

Expose join state and the latest rejection through [local standalone observation](observation.md#local-standalone-connection).
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
| MESH-003/005 | Run two fixed-peer JVMs with separate credentials. Verify mutual TLS, matching `hello`, first valid `state`, and selected-process status in Creator. Exercise unknown or expired credentials, wrong mesh, replica, or key, and unsupported opening. Different logical builds close before either side publishes `state`. At dispatch, a mismatched invocation is refused before execution or permit acquisition. |
| MESH-004 | Send a valid `starting` state. Verify the peer joins and stays ineligible for offload until it reports `ready`. In a separate run, complete TLS and `hello` and withhold the first `state` past the join deadline. Verify the session times out while the replica keeps serving local requests. |
| MESH-004 | Exercise occupied loopback ports and a two-host bootstrap. Start with the seed down and verify bounded retries, normal local responses, and reconnection. Prove duplicate resolution, immediate disconnect removal, stale expiry, and two attempts for a repeatedly hinted unreachable address. Join four replicas. Remove the original seed and verify survivors stay connected and a replica can join through a survivor. |
| MESH-005 | Package the same project with the fixed-peer contract that requires empty hints and the incompatible discovery contract that sends nonempty hints. Verify distinct logical build IDs. Connect with trusted credentials and verify both sides reject at `hello` before either sends `state`. |
| MESH-005/007 | Join equivalent macOS and Linux/ARM packages. Execute an eligible Flow and exercise an ineligible host-specific target. |
| MESH-006/007/012 | Exercise UTF-8, byte, node, and depth limits, aggregate outcomes, and simultaneous slow frames. Verify early rejection, bounded memory, and recovery. |
| MESH-008/009 | While A is saturated, send an HTTP request directly to A. B executes the eligible Flow and returns one terminal outcome. A completes one original response with the same result as local execution. Saturate B with local and remote work and verify immediate refusal. With A below capacity, verify local source responses through peer stall, disconnect, and reconnect. |
| MESH-010/011 | Exercise explicit refusal, disconnect after possible admission, late results, and draining with admitted work. Verify bounded attempts, the ingress deadline, and no replay. |
| MESH-013 | Complete concurrent Flow invocations out of order on one channel. Exercise Step invocation only at its later milestone. |

Run the fixed-peer proof on a compatible installed Java runtime. Use
[Item 8 distributions](../roadmap.md#8-direct-generation-distribution-and-certification) for the
later packaged native proof. See [fixed-peer evidence](../verification.md#fixed-peer-mesh-membership).

## Delivery Gates

- **Discovery.** Measure join, heartbeat, staleness, and retry timings under load on Raspberry Pi
  to set finite peer and handshake caps and learned-hint lifetime before enabling learned peers.
- **Flow execution.** Establish build-time eligibility for portable providers. Measure and set the
  source operation budget, receiver clamp, frame deadlines, and aggregate inbound, outbound, and
  retained-value limits.
- **Native distribution.** Repeat the MESH-001/002 activation cases and MESH-003/005 peer checks
  on the self-contained distributions from Item 8.
