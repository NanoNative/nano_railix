# ADR 0023: HTTP Ingress And Step-Owned JDK Modules

## Status

Proposed on 2026-09-06; moved from the unmerged branch's ADR 0022 on 2026-09-20
to avoid collision with the accepted Example-validation decision.

Amended 2026-09-21 after the user's HTTP review: the generic JDK-module declaration direction
remains, but the privileged built-in HTTP launcher described below is **rejected as a P1 gap**.
[SYS-010 to SYS-017](../specs/system-model.md#planned-step-lifecycle) require the same lifecycle
and ingress contract for built-in and third-party Steps. The original baseline below is retained
as history, not permission to extend that exception. Generic source activation now replaces that
launcher in generated applications and Creator. Remaining HTTP and resource-guidance gaps
are tracked in the owning specifications; removing the launcher does not complete them.

Behavior now belongs to [HTTP Trigger](../specs/http-trigger.md) and
[HTTP Client](../specs/http-client.md): ordinary context data, reusable Step-owned resources and
incoming HTTPS are in the accepted direction. Binary bodies, multipart and streaming remain
deferred. The follow-up clarification selects static Step functions, downstream-first PRE and
upstream-first POST with graceful draining. Configuration remains compiled and separate from
context; plain certificate settings do not wait for a secret manager. Client trust-all and
server CORS are independent options; certificate and hostname checks are individually configurable.
The drain deadline is adjustable with a 30-second default. The later resource-DX clarification
(LC-3) supersedes the proposal for mandatory resource declarations and automatic sharing:
ordinary Java ownership remains allowed, and optional context registration defaults to private
ownership. Explicit immutable keys opt into app-local sharing without new StepDefinition metadata.
Visual Groups remain non-functional. Named profiles are deferred; runtime resource guidance and
expanded HTTP details remain separate work. The current resource API and shutdown escalation
contract belong to the linked system specification.

## Context

HTTP Server & Client support is initiated.

## Decision

The standard library supplies two HTTP capabilities:

- `railix.trigger.http`, a singleton Trigger for source `application.http`.
- `railix.http.client`, an ordinary Step that calls one configured HTTP endpoint.

The HTTP Trigger receives one request object containing method, path, query, headers and body. Its
Trigger maps ordinary workflow responses from `body`, `headers` and `status`. The Trigger
implementation's normal `process(...)` method mirrors the CLI Trigger: it writes the received payload to
the configured target path. Serving is an explicitly generated application entrypoint implemented by
`HttpStepHandlers.Trigger.serve(...)`, called only from generated source when the compiled project
contains an HTTP Trigger.

Step implementations declare required JDK modules at compile time:

```java
.implementation(HttpStepHandlers.Trigger.class, "jdk.httpserver")
.implementation(HttpStepHandlers.Client.class, "java.net.http")
```

`StepDefinition` carries this metadata through the catalog as `StepCatalog.Implementation.jdkModules()`.
`ApplicationBuilder` aggregates that metadata and passes `--add-modules` to `javac` only when the
compiled application's reachable Step implementations require it. The packager does not branch on
stdlib class names.


## Consequences

Railix gains minimal HTTP request/response path while preserving the generated application model.
The implementation uses only JDK APIs and keeps HTTP transport code isolated in stdlib.

The current HTTP layer is intentionally smaller than a framework. It supports JSON-first
request and response bodies, string fallback for non-JSON UTF-8 bodies, multi-value headers, status
mapping, invalid JSON/UTF-8 rejection, and a 1 MiB limit for request bodies and client response
bodies. Oversized client responses use the existing transport-failure result (status `0`).
Header values are strings or arrays of strings; invalid values are rejected rather than silently
dropped. Request validation returns explicit HTTP errors without constructing validation exceptions.
Interrupting the server closes its listener and cancels owned request tasks.

## Rejected Alternatives

- Hardcoding `HttpStepHandlers` class names in `ApplicationBuilder` to infer JDK modules.
- Adding `java.net.http` or `jdk.httpserver` to every generated application unconditionally.

## Deferred Decisions

Originally deferred: route-level metadata, TLS, auth, streaming, multipart forms, request size
configuration, raw response bodies and JDK module metadata for third-party Step bundles.
The 2026-09-21 amendment moves HTTPS, configurable limits and JSON/text/no-body handling into
the next HTTP contract. Generic third-party capability declarations are required; their exact
bundle schema is still to be specified. Other open details live in the linked specifications.
