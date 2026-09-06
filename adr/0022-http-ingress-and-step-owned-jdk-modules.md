# ADR 0022: HTTP Ingress And Step-Owned JDK Modules

## Status

Proposed on 2026-09-06.

## Context

HTTP Server & Client support is initiated.

## Decision

The standard library supplies two HTTP capabilities:

- `railix.trigger.http`, a singleton Trigger for source `application.http`.
- `railix.http.client`, an ordinary Step that calls one configured HTTP endpoint.

The HTTP Trigger receives one request object containing method, path, query, headers and body. Its
Trigger maps ordinary workflow responses from `body`, `headers` and `status`. The Trigger
handler's normal `run(...)` method mirrors the CLI Trigger: it writes the received payload to
the configured target path. Serving is an explicitly generated application entrypoint implemented by
`HttpStepHandlers.Trigger.serve(...)`, called only from generated source when the compiled project
contains an HTTP Trigger.

Step implementations declare required JDK modules at compile time:

```java
.run(HttpStepHandlers.Trigger.class, "jdk.httpserver")
.run(HttpStepHandlers.Client.class, "java.net.http")
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
mapping, invalid JSON/UTF-8 rejection, and a bounded body size.

## Rejected Alternatives

- Hardcoding `HttpStepHandlers` class names in `ApplicationBuilder` to infer JDK modules.
- Adding `java.net.http` or `jdk.httpserver` to every generated application unconditionally.

## Deferred Decisions

Route-level metadata, TLS, auth, streaming, multipart forms, request size configuration, raw response bodies 
and JDK module metadata  for third-party Step bundles remain separate decisions.
