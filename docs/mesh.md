# Railix Mesh

A replica is a running copy of a built Railix application. Today you can connect several replicas
of the same build using configured seed addresses and inspect their status in Creator. Each
replica runs the requests it receives. Automatic peer discovery and Flow offload are planned.

## How It Works

### Join And Track Peers

A **seed** is a running replica that another replica contacts to join. Give the joining replica
the seed's mesh address. In this example, B contacts A.

```mermaid
sequenceDiagram
    participant A as Replica A (seed)
    participant B as Replica B (joining)
    B->>A: Connect to seed address
    Note over A,B: Authenticate each other with mutual TLS
    B->>A: hello.v1
    A->>B: hello.v1
    Note over A,B: Check replica identity, mesh, and logical build ID
    B->>A: Initial state
    A->>B: Initial state
    Note over A,B: First valid state completes join
    loop While connected
        B->>A: State heartbeat
        A->>B: State heartbeat
    end
```

State carries readiness and the advertised mesh address. An identity or build mismatch closes
the connection before state is exchanged. A disconnect or heartbeat timeout removes the peer.
Failed attempts to a configured seed are retried with backoff.

If a third replica, C, has both addresses, it connects directly to A and B. B and C can stay
connected if A leaves, and new replicas can join through either survivor. With automatic
discovery, replicas will be able to learn addresses from peers.

### Run A Flow (Planned)

The replica whose Trigger receives the event owns the original request and sends the response.
It runs the Flow locally when it has a free slot. When full, it can ask a ready, eligible peer.

```mermaid
sequenceDiagram
    participant Client as HTTP caller
    participant A as Replica A
    participant B as Replica B
    Client->>A: HTTP request reaches Trigger
    A->>A: Prepare Flow input and keep the HTTP request
    alt A has a free Flow slot
        A->>A: Run the Flow locally
    else A is full and B is eligible
        A->>B: Invoke Flow with input and time remaining
        B->>B: Check build, eligibility, deadline, and capacity
        alt B admits the Flow
            B->>B: Reserve a slot and run the Flow
            B-->>A: Flow outcome
        else B refuses admission
            B-->>A: Refusal
        end
    else A is full and no eligible peer is available
        A->>A: End with an admission failure
    end
    A-->>Client: Complete the original HTTP request
```

After a refusal, A can try another eligible peer within the original deadline and attempt limit.
If B may already have admitted the Flow and the connection drops, A reports uncertain completion
and stops retrying.

The first offload test will send an HTTP request directly to a replica. For a stable public URL
across replica changes, deployment can put an HTTP reverse proxy in front of ready replicas.
The proxy routes HTTP requests. Railix tracks its peers and places offloaded Flows.

Later, a mesh-aware client could discover reachable application endpoints and choose a replica
directly. The event would still enter through the same Trigger and follow the same Flow rules.

## Run A Local Mesh

From the repository root on macOS or Linux, run the demo with Bash, JDK 25, and `uuidgen`.

```sh
./run-mesh.sh auth-http-app
```

The script builds the application once and starts two loopback replicas with temporary credentials.
`auth-http-app` is the default. You can also pass another directory under `examples/` with a
long-running Trigger, such as `text-normalization-http-app`. Set `RAILIX_CLI_JAR` to reuse an
existing packaged CLI.

While the launcher is running, paste the printed `join` command into another terminal. The script
reuses the same JAR, issues a certificate for the new replica, and gives it the addresses of the
other demo replicas. Use a different free mesh port as the last argument each time. The signing
key stays in the private demo directory until the launcher exits.

Run the printed Creator command in another terminal. Choose **Project actions > Connect live app**
and select a replica. In the runtime details, **Mesh** shows the connection state and **Peer**
summarizes one authenticated connection. A mismatched build shows **Build mismatch** in the
rejection details. Stop added replicas with `Ctrl-C` in their own terminals. Then stop the initial
launcher with `Ctrl-C` to stop its two replicas and remove the demo credentials.

## Deploy An Application

Build once with the Railix CLI and JDK 25. Copy the printed JAR and [run-mesh.sh](../run-mesh.sh)
to each host.
Use Bash, Java 25, and `unzip` to start each replica with its own mesh configuration.

```sh
railix build path/to/railix.project.json --mesh

APPLICATION_JAR=/path/to/application.jar RAILIX_MESH_CONFIG=/path/to/mesh.json \
  ./run-mesh.sh deploy
```

Add `--include-project` to embed the project documents for Creator attachment. Use the same JAR
for each replica. Set `APP` to the path printed by the build or to the copied JAR on the host
where you start it.

```sh
APP=/path/to/application.jar
RAILIX_MESH_CONFIG=/path/to/mesh.json java -jar "$APP"
```

### Startup Configuration

Use the same `meshId` and trust issuer across replicas. Give each replica its own certificate and
private key. The certificate identifies the replica and its mesh. The configuration also sets the
listen and advertised mesh endpoints, bootstrap addresses, Flow capacity, and the paths to the
identity store, trust store, and password files. See the [configuration reference](specs/mesh.md#startup-configuration)
and [certificate requirements](specs/mesh.md#peer-transport-and-trust).

On one host, give the mesh listeners distinct ports, such as `7401` for A and `7402` for B. Give A
an empty `bootstrapEndpoints` list and give B `[{"host":"127.0.0.1","port":7401}]`. Configure
the application's Trigger listeners independently, for example with an ephemeral HTTP port.
On separate hosts, advertise addresses that the other replica can reach through the deployment's
network or VPN.

The [mesh specification](specs/mesh.md) defines the runtime contract. [ADR 0024](adr/0024-optional-mesh-module-and-peer-channel.md)
explains the architecture. The [roadmap](roadmap.md#7-decentralised-execution-state-sharding-and-replication)
tracks the delivery sequence.
