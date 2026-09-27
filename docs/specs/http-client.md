# HTTP Client

## Human Review

Status: **Lifecycle reuse implemented; expanded HTTP contract pending**, updated 2026-09-21 from the
user's HTTP review and lifecycle clarification. Owns outbound HTTP/HTTPS, connection reuse, request options and
response/failure projection. [HTTP data rules](http-trigger.md#shared-http-data-rules) own protocol
values; [Step lifecycle](system-model.md#planned-step-lifecycle) owns resource/configuration scope.
This is an ordinary Step, independent of HTTP/CLI/other Triggers and of Merge or routing Steps.

The user rejects a new client per request and fixed URL/method/header-only configuration.
Request values belong in the context. Steps may own Java resources; PRE/PROCESS/POST share one
contract with compiled configuration separate from context. HTTPS and an explicit trust-all
certificate option are in scope now; binary bodies, multipart and streaming are deferred.

## Current Baseline

`railix.http.client` has fixed authored URL/method/headers, a bound body and a response object
containing status/headers/body. PRE registers a reusable JDK client through the optional common
resource API; PROCESS retrieves it and application cleanup closes it after draining. The ordinary
`connection` option defaults to `private`; `shared` reuses the same cookie-free default transport
across this Step's placements in one application. No HTTP-specific registry or compiler branch is required.
Its defaults use
normal JDK certificate trust, HTTP/2 preference and no automatic redirects; outgoing HTTPS
therefore already works with trusted servers. A fixed 30-second request timeout and 1 MiB
response limit apply. Non-null outgoing bodies are JSON-encoded. Response text is tried as JSON
without consulting its content type. I/O failures and invalid headers use normal `next` with
status `0` and an error string in the body. These are current limitations, not the target API.

## Behavior

| ID | Required behavior |
| --- | --- |
| HC-001 | The Step MUST reuse its owned HTTP client across compatible PROCESS invocations and close it through the common lifecycle. It MUST NOT create a connection pool per request or mix incompatible credential/trust settings through an unscoped static field. |
| HC-002 | URL, method, query values, headers and body MUST be bindable from ordinary context fields. Literal data may be prepared by normal Steps. No HTTP-specific object or compiler branch may be required to build a request. |
| HC-003 | Response status, headers, body, effective URI/protocol and available parsed convenience fields MUST be readable through normal context mappings. The shared HTTP data rules apply to both directions. |
| HC-004 | A received HTTP response, including 4xx/5xx, MUST remain distinguishable from failure to obtain a response. Transport/timeout/TLS/encoding failures MUST use declared result/outcome data with stable codes; neither Java exceptions nor invented HTTP status `0` are the target control contract. |
| HC-005 | Connection and request/body time budgets, size bounds, redirect policy and supported protocol preference MUST be configurable. The contract MUST distinguish settings fixed for a reusable client from request-local values; names such as "read timeout" MUST match the enforced behavior. |
| HC-006 | HTTPS MUST verify certificates by default and offer an explicit compiled option to allow unverified certificates. Custom CA/certificate configuration MUST also be supported. Trust-all MUST be scoped to this Step's client, visibly marked insecure and never selected as fallback for invalid trust material. No JVM-wide verification override is permitted. |
| HC-007 | Redirect handling MUST have explicit hop/destination/credential rules, including cross-origin requests and HTTPS downgrade. Dynamic destinations MUST not bypass the configured network trust boundary. No request may weaken trust simply by supplying a context payload. |
| HC-008 | Retry policy MUST be explicit and bounded. No blanket retry of side-effecting requests is permitted; a timeout can leave the remote action's completion unknown. Cancellation MUST not be reported as a successful response or silently retried. |
| HC-009 | Ordinary context construction MUST support developer-supplied test responses and explicit test routing without teaching the HTTP Step about Example names. Real I/O remains permitted; future output fakes follow the shared Example contract. |
| HC-010 | Certificate verification and hostname verification MUST be independently selectable compiled Step options, both enabled by default. Disabling either MUST be explicit, scoped to that client and visibly identified as insecure. Unsupported combinations MUST be rejected, not silently weaken another setting or use a JVM-global switch. |
| HC-011 | Cookie storage MUST default to disabled. A private cookie session MUST remain isolated; sharing stored cookies MUST require an explicit common session selection. Session identity, not changing cookie contents, MUST participate in resource compatibility. Equal transport settings alone MUST NOT merge login sessions. |

## Option Boundaries

The following separates lifetimes, not different value classes or a final field schema:

| Lifetime | Values to specify |
| --- | --- |
| Compiled Step configuration | Trust roots, independent certificate/hostname verification options and optional client identity, connection timeout, protocol preference, redirects, proxy selection and fixed context bindings. The same immutable settings reach PRE/PROCESS/POST. |
| Request context | URI/method, encoded query inputs, headers including explicit credentials, body mode/value, request deadline and permitted per-request bounds. |
| Response context | Actual HTTP status, effective URI/protocol, multi-value headers, normalized content/language/encoding fields, body mode/value or structured failure evidence. |

Connection-bound changes must not lead to unbounded caches keyed by request data. Reusing the
transport must not create an implicit shared cookie jar, cross-user authorization state or retained payloads.
If configurable request-local redirects/proxies/TLS profiles are later required, define a bounded
ownership model first. A single mutable global client reconfigured by concurrent requests is not
a valid implementation of context-driven requests. Connection settings are already known from
configuration before PRE; there is no need to move them into the invocation context.

Compatible placements may reuse the same client within one running application. The HTTP Step,
not the compiler, defines compatibility from effective transport/security settings and ownership
of mutable authentication or cookie state. Different request URLs alone do not require separate
clients. Equal configuration is insufficient when callers require separate session state. The
current default is private ownership, with explicit compatible sharing under the updated LC-3.
Named profiles are deferred and do not block client lifecycle. HC-011 requires separate
cookie-session ownership; its exact API remains open. Sharing must not let one placement close
a client still needed by another.

### Certificate Trust

"Allow all" means accepting unverified certificates, not CORS. This is an explicit user option,
not restricted to Examples or inferred from `runtime.test`. Certificate verification remains the
default; enabling trust-all loses certificate-authenticated peer trust and permits interception.
It does not turn HTTPS into plaintext HTTP or weaken another Step's client.

The user selected independent options for certificate trust and hostname matching. Both stay
enabled by default. Disabling certificate verification does not implicitly disable hostname
matching, and disabling hostname matching does not implicitly trust an untrusted certificate.
Neither weakened combination establishes the same peer authentication as both checks enabled.
Public JDK [trust-manager](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/X509ExtendedTrustManager.html)
and [client TLS configuration](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html)
boundaries support implementing client-scoped policy; the chosen JDK behavior must be proved
through real HTTPS tests for every combination before claiming support.
No secret manager is required: certificate file locations and passwords use ordinary plain
configuration until the later secret-management work. Compiled plaintext is not secret storage.

## Decisions Before Implementation

1. **HTTP-OPEN-1 resolved:** "allow all" means unverified certificates. CORS belongs to the server
   and is independently configurable. HC-010 records the accepted independently configurable
   hostname check; UI labels and persisted option names remain to be selected.
2. **Lifecycle:** LC-3's generic resource API is implemented. Before introducing configurable
   sessions/TLS, specify their effective settings key and session isolation. The current key
   describes only the fixed JDK defaults without cookies; it must change with transport settings.
   Certificate formats, redaction and any mTLS/rotation scope remain
   explicit decisions; future encrypted secret delivery is not a prerequisite for this slice.
3. **Public shape and migration:** select normal path bindings, outcome names/error fields,
   timeout/size defaults, redirect/retry rules and compatibility for existing client definitions.
   Stable codes must distinguish invalid request, timeout, connection/TLS failure and response
   decoding/size rejection; do not present unknown remote completion as proof of no side effect.

## Acceptance

Existing size/header and private/shared connection-reuse checks are in
[GeneratedApplicationE2eTest](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/GeneratedApplicationE2eTest.java).
The generic resource suite additionally proves nested/concurrent ownership and cleanup failures.
Expanded HTTP target checks below remain planned, not claimed passes:

| Requirements | Public-boundary evidence |
| --- | --- |
| HC-001, SYS-010/011 | Execute repeated/concurrent calls from a built application; observe connection reuse, compatible sharing, isolation of incompatible settings/session state and separate applications, shutdown without premature close, and no request-context retention. |
| HC-002/003, HTTP-001 to HTTP-008 | Build requests entirely from upstream context data; verify URI encoding, duplicate headers, tokens, exact JSON/text/no-body round trips and ordinary downstream access. |
| HC-004/005 | Use controlled HTTP/HTTPS servers for real 4xx/5xx, refused connections, delayed headers/body, invalid encoding, oversize responses and cancellation. Verify declared outcomes and no ambiguous status-zero success. |
| HC-006/007 | Exercise default rejection and explicit trust-all acceptance of untrusted certificates, wrong-host/expiry behavior, custom CA, redirects and attempts to override configuration with request data; verify one insecure client cannot weaken a separately configured client. |
| HC-010 | Test all four certificate/hostname settings against trusted/untrusted and matching/wrong-host HTTPS peers, including two differently configured clients in the same process. Verify secure defaults and no global policy changes. |
| HC-011, SYS-016/017 | Return cookies from an isolated test server; prove no storage by default, isolation of private sessions, deliberate reuse of a named shared session and no cross-application leakage. Changes to cookie contents must not create new registry entries. |
| HC-008 | Interrupt a remote write after acceptance; verify unknown completion and absence of automatic duplicate writes; prove any approved retry budget. |
| HC-009 | Route `runtime.test` around the client, write an ordinary replacement response, rejoin the Flow and prove that no request reached the isolated test server. Separately run a real-I/O Example. |
| SYS-006/012 | Inspect generated artifact/module closure and exercise an independently authored resource Step through the same lifecycle; report throughput, allocation, socket reuse and size. |

## Platform References

The JDK already supports reusable clients and these transport settings; no additional HTTP
dependency is needed for this direction. See [HttpClient](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html)
and [Builder](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.Builder.html).
Transport settings alone do not establish safe retry, CORS, secret delivery or SSRF policy.
