# Trusted Step Application Template

This standalone Maven project is the copyable shape for a custom Railix application:
one external Step, an explicit catalog of every standard and external Step, one committed
whole flow, one readable flow E2E, and one dependency safety matrix.

```text
input.name -> lowercase.text
lowercase.text -> prefix.text
prefix.text -> output.message
lowercase.ok -> prefix
prefix.ok -> end
```

From the repository root:

```sh
mvn -q -Ptrusted-step-monolith clean verify
mvn -q -Ptrusted-step-monolith,refresh-step-lock clean verify
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar \
  cli command < examples/trusted-step-monolith/input.json
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar creator
```

The first build is read-only and fails before Shade when `railix.lock.json` is missing, malformed, or stale. The
second build explicitly regenerates that lock atomically and then runs the same verification.
Independent clean source copies produce the same JAR bytes. Running the finished JAR with an empty
process environment needs only absolute Java, the JAR, and its JSON input; it does not invoke Maven or
require a separate Creator installation.
With no arguments the committed application listens on IPv4 loopback HTTP port `18080` and socket
port `18081`. Its authored flow enables `POST /greet`, `POST /v1/flows/trusted-greeting/events`, and
`POST /v1/flows/trusted-greeting/steps/prefix/events`; the first two accept `{"name":"RAILIX"}`,
while the Step route accepts `{"text":"railix"}`. Socket events use a four-byte signed big-endian
length followed by the same JSON object and receive a reply with identical framing.
The profile adds this external consumer to the reactor without making it a default product
module or requiring a prior local install. To build another application, change these five
small developer-owned surfaces:

- `GreetingStep.java`: Step contract and handler.
- `TrustedGreetingMain.java`: explicit trusted catalog registration.
- `src/main/resources/railix.flow.json`: complete Creator-authored application.
- `GreetingStepFlowIT.java`: public compiler/runtime proof using real input and expected output.
- `StepDependencyBuildIT.java`: one compile/build scenario per dependency rejection.

`src/main/resources/railix.lock.json` is generated evidence, not another Step-selection or
hand-editing surface. `RailixMain.executeApplication(...)` owns startup, `cli <trigger-id>`, HTTP,
socket, scheduled, `creator`, and `lock`. Application mains do not duplicate launch, result,
diagnostic, or Creator lifecycle code. No reflection, runtime discovery, framework, annotation
processor, or generated registration is involved. Package E2Es inspect and run the shaded JAR outside this project,
including all three HTTP routes and the socket listener without rewriting the artifact. A separate
inputless shaded application assembled from the same packaged runtime proves scheduled readiness,
execution, output, and termination. The browser E2E extracts the committed flow from the JAR, opens
it in Creator, runs and saves it, and captures the screenshot.

The dependency matrix compiles the committed flow and requires exact code, path, and message
diagnostics for structural and lock drift. The canonical lock contains only the dependency closure
referenced by the whole flow, sorted by Step id, with exact opaque versions and fingerprints of all
declared ports, configuration/defaults, and outcomes. Repeated invocations are deduplicated and
unused catalog entries are omitted. A handler behavior change requires a Step version bump; this
template does not use reflection or bytecode inspection to pretend otherwise.

The shaded application also exposes the same narrow lock surface directly:

```sh
java -jar target/trusted-step-monolith.jar lock
java -jar target/trusted-step-monolith.jar lock --check
java -jar target/trusted-step-monolith.jar lock --write path/to/railix.lock.json
```
