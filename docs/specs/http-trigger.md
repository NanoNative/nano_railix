# HTTP Trigger

## Human Review

Status: **In progress; generic live ingress implemented**, updated 2026-09-21 from the
user's HTTP review and lifecycle clarification. Owns incoming HTTP/HTTPS, request normalization, response encoding
and the shared HTTP data rules below. [Step lifecycle](system-model.md#planned-step-lifecycle)
owns initialization, resource scope and generic ingress; [security](environments-and-security.md)
owns planned secret management and authorization.

The user requires ordinary context values, useful parsed protocol fields, configurable ports and
certificates, HTTPS in this delivery, and equal third-party support. Binary bodies, multipart and
streaming are explicitly deferred. Configuration is compiled and separate from context; a secret
manager is not a prerequisite. CORS is an independent server option, not the client's TLS
"allow all" option. Optional client/resource sharing is provided by the common lifecycle.

## Current Baseline

`railix.trigger.http` is a singleton source `application.http`. `railix run` and standalone JAR
launch activate all configured sources through their common lifecycle. Host and port are ordinary
Step inputs, defaulting to `127.0.0.1` and `8080`; explicit port `0` selects a free port. The old
`railix serve` and `--railix-http` mode selection are removed. Projects need no automatic rewrite:
omitted settings take the declared defaults; previous command-line bind overrides must move into
the HTTP Step's `host`/`port` inputs. Requests expose `method`, decoded
`path`, raw `query`, lower-case multi-value `headers` and `body` at the configured target path.
Only `application/json` is parsed as JSON; other bodies are UTF-8 strings; empty bodies are JSON null.
Malformed JSON/UTF-8 returns 400; requests over the fixed 1 MiB limit return 413 before Flow execution.
An unfinished body has a fixed 30-second read deadline. Expiry returns 408 with `HTTP_BODY_TIMEOUT`,
interrupts the reader and closes the connection without running the Flow.
Responses read context body/headers/status; non-null bodies are always JSON-encoded. Invalid header
value shapes return 500. Admission closes before draining flow execution and its response write;
subsequent requests receive 503 while the listener drains. Listener/executor cleanup uses the
same registered source lifecycle as third-party Triggers. Normal development activation starts
the business listener independently of Trigger Examples. CLI-only applications omit the HTTP
implementation; CLI output/status handling belongs to its own Step. Step-owned JDK module metadata currently reaches
`javac`; third-party bundle metadata and final minimal-runtime closure remain separate gaps.

Incoming HTTPS, parsed query/language/auth fields and configurable admission/time budgets are
not implemented. Generic optional resource sharing is available. The built-in HTTP launcher exception is removed; registered
static Step calls remain, as for any other implementation class.

## Shared HTTP Data Rules

These are target rules shared with the client, not the current file/API schema. Field paths are
authorable through normal Step bindings. A request/response may be grouped in an ordinary context
object, but no HTTP-specific Java object or transport handle becomes a Flow value.

| ID | Required behavior |
| --- | --- |
| HTTP-001 | HTTP Steps MUST read/write protocol data using canonical context values and the generic Step contract. The compiler and Creator MUST NOT know HTTP field names or special value classes. |
| HTTP-002 | Headers MUST preserve multiple values with case-insensitive names. Parsed convenience values MUST come from the same boundary input, not become a second independently writable authority. Invalid names, line breaks and incompatible value shapes MUST be rejected before sending. |
| HTTP-003 | Query decoding MUST preserve repeated keys and empty values and retain raw query/path information when normalization could lose meaning. Invalid escaping MUST yield a defined result, not partial silent conversion. |
| HTTP-004 | Content type/parameters, charset, accepted media types, encodings and languages MUST be available as ordinary values. Preference parsing MUST preserve quality weights, wildcards and exclusions such as `q=0`; a missing language MUST NOT invent English. |
| HTTP-005 | Authorization parsing MUST expose the scheme and available credentials without treating them as authenticated identity. Malformed Basic input MUST produce a defined parse result. Tokens/passwords MUST NOT leak into default traces, diagnostics or logs; the shared security boundary owns redaction. |
| HTTP-006 | Body handling MUST distinguish no body, JSON and text explicitly. Text MUST NOT gain JSON quotes; JSON null MUST remain distinguishable from no body. Parsing MUST follow declared media type or an explicit selected mode, not guess from text content. Unsupported charset/encoding MUST be reported. |
| HTTP-007 | The transport MUST own framing headers and enforce no-body rules for HEAD and applicable statuses. Invalid status/framing combinations MUST not cause a second response after headers have been sent. |
| HTTP-008 | Readable convenience fields MUST NOT require eager parsing/copying of every possible HTTP header or body format. Any caching MUST remain request-local and bounded; raw and normalized values MUST not drift after boundary processing. |

The first field profile includes method, URI/path/raw query, decoded query values, multi-value
headers, content type/charset, accept preferences/languages/encodings, authorization scheme/token,
body mode/value, protocol version, and available local/peer address information. Token extraction
is not token validation. Forwarded headers are untrusted unless an explicit trusted-proxy policy
permits them. Cookie and path-template helper representations remain to be specified separately.

## Trigger Behavior

| ID | Required behavior |
| --- | --- |
| HT-001 | The Trigger MUST acquire and release its listener through the common Step lifecycle. Submitting a request and receiving its result MUST use the same generic ingress capability available to third-party Triggers. |
| HT-002 | Bind address, port and certificate settings MUST be authored as ordinary immutable Step configuration, available during PRE without payload/context lookup. A configured occupied port MUST fail clearly; only explicit automatic-port selection may choose a different port. |
| HT-003 | Incoming HTTPS MUST be supported in the next HTTP delivery. Invalid/missing selected TLS material MUST prevent readiness, not silently fall back to HTTP or another certificate. Certificate/key loading MUST not require shelling out to OpenSSL. |
| HT-004 | Each admitted request MUST have its own PROCESS payload/context. Routing and product authorization MUST remain ordinary Steps; the HTTP Trigger MUST not grow a second Flow language. |
| HT-005 | The Flow MUST be able to supply response status, headers and JSON/text/no-body data through ordinary context fields. Client disconnects and cancelled execution MUST preserve lifecycle cleanup and explicit failure semantics. |
| HT-006 | Request byte/time limits, admission capacity and overload behavior MUST be declared and configurable at the appropriate boundary. Numeric defaults and shutdown drain budgets MUST be agreed and measured, not inferred from a million-Step graph target. |
| HT-007 | The HTTP server Step MUST offer independent, configurable automatic CORS handling. When enabled, it MUST answer valid preflight requests without executing business PROCESS code and apply the selected CORS response policy to normal and error responses. It MUST not alter TLS trust settings. |
| HT-008 | Normal development-app startup MUST start the configured HTTP listener. Trigger Examples MUST supply output directly to the compiled continuation, without sending an HTTP request, invoking Trigger PROCESS or changing listener lifetime. This uses the generic [LC-7 contract](system-model.md#accepted-lifecycle-decisions), not an HTTP-specific Example mode. |

### Certificates And CORS

Certificate/key file locations and any required password are plain Step configuration for the
first delivery. The configured files are read during PRE. No secret manager, encrypted settings
store or new environment subsystem is required to use HTTPS. Plain compiled passwords are
recoverable from project/generated artifacts; a concealed UI field is not encryption. Do not
copy private key files into generated source or log their contents. Formats and password handling
remain part of the HTTP implementation contract, not a reason to postpone HTTPS indefinitely.

CORS configuration declares allowed origins, methods, request headers, credentials, exposed
response headers and preflight cache duration. Disabled CORS leaves normal HTTP/OPTIONS handling
alone. Enabled CORS recognizes actual preflights, checks the configured policy, and supplies the
protocol headers automatically. A denied preflight does not run the Flow or grant access. Ordinary
OPTIONS without preflight headers is not silently swallowed.

Credentialed responses require an explicit allowed origin, not `*`; reflected origins must first
pass the configured allow policy. Preserve existing `Vary` values and add the dimensions used
to select a response, including `Origin` where applicable. Invalid combinations are rejected at
configuration validation. CORS controls browser response access, not caller identity or general
API authorization. These rules follow the [Fetch CORS protocol](https://fetch.spec.whatwg.org/#http-cors-protocol).

### Decisions Before Implementation

- Use the accepted LC-3 resource API, common source activation and LC-4 shutdown contract.
  Decide initial certificate/key formats; recommended
  starting formats are PKCS12 or X.509 PEM plus supported PKCS8 keys. mTLS and certificate rotation
  need explicit scope and renewal/failure behavior; secret-manager integration stays later work.
- Specify normalized field paths/shapes, encoding/absence precedence, numeric budgets, response
  status mapping for cancellation/rejection, and migration from the current versioned definition.
  Preserve original metadata where needed; do not overwrite a caller's context fields implicitly.
- Select CORS option names/defaults and response precedence using HT-007. Compression,
  cookie helpers and route-template matching are candidates, not approved default middleware.
  Advertise only supported encodings; bound decoded as well as wire bytes if compression is added.

## Acceptance

Existing built-JAR baseline tests live in
[GeneratedApplicationE2eTest](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/GeneratedApplicationE2eTest.java).
The following are planned checks, not claimed passes:

| Requirements | Public-boundary evidence |
| --- | --- |
| HT-001, SYS-012 | Build a third-party source using the same lifecycle; serve concurrent requests, stop it and inspect generated source/dependency closure for privileged HTTP branches. |
| HT-002/003 | Bind requested/free/occupied ports; serve with trusted and untrusted certificates; reject broken keys, expired certificates and invalid startup configuration without HTTP fallback. |
| HTTP-001 to HTTP-005 | Send duplicate query/header values, encoded paths, malformed escapes, weighted language/media preferences and malformed/valid authorization; inspect ordinary Flow fields and redacted evidence. |
| HTTP-006/007, HT-005 | Return exact text, JSON strings, JSON null and no body; exercise HEAD/204/304, invalid status/header values and a disconnected caller. |
| HT-004/006, SYS-006 | Prove request isolation, defined overload behavior, byte/time limits and draining cleanup under sustained concurrent load. |
| HT-007 | Use a real browser and HTTP fixture for allowed/denied origins, credentialed requests, OPTIONS preflights, ordinary OPTIONS, error responses and caching headers; invalid wildcard/credential configuration is rejected and preflights never execute the Flow. |
| HTTP-008 | Compare allocations/latency for minimal requests and requests using parsed convenience fields; report parser/cache cost and artifact size. |

## Protocol References

HTTP semantics follow [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html).
The JDK [HttpsServer](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpsServer.html)
supports TLS ingress. Its bounded [stop behavior](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html#stop(int))
does not by itself prove that all downstream application work has drained.
