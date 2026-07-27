# Railix II

Railix builds Java applications from explicit JSON flow definitions and trusted Java
Step dependencies. A flow connects named Step outcomes for control and named ports for
data. The compiler rejects incomplete or ambiguous graphs before an executable plan can
run.

The current implementation is deliberately small: one compiler/runtime, one compact
standard catalog, one external trusted-Step monolith, one packaged command, and one Creator
surface backed by the real compiler. See [ROADMAP.md](ROADMAP.md) for supported and planned
behavior.

Accepted architecture decisions:

- [ADR 0013: Small Java Product Boundary](adr/0013-small-java-product-boundary.md)
- [ADR 0014: Canonical Primitive Data](adr/0014-canonical-primitive-data.md)
- [ADR 0015: Explicit Step Contract And Lock](adr/0015-explicit-step-contract-and-lock.md)
- [ADR 0016: Canonical Flow Compilation And Authoring](adr/0016-canonical-flow-compilation-and-authoring.md)
- [ADR 0017: Compiled Execution And Explicit Results](adr/0017-compiled-execution-and-results.md)
- [ADR 0018: Whole-Flow Application And Explicit Ingress](adr/0018-whole-flow-application-and-ingress.md)

## Run It

Requirements: Java 25, Maven 3.9 or newer, and installed Google Chrome for the
browser E2E suite. Set `RAILIX_BROWSER_CHANNEL` to use another installed Playwright
browser channel. Every browser scenario runs at `1280x720` and `320x700`; verification
never downloads browsers implicitly.
Railix runtime does not use Node, Homebrew, or `llhttp`; Java Playwright is test-only.

```sh
mvn -q verify
./railix run examples/lowercase-app
./railix creator
```

The example prints:

```json
{"text":"hello railix"}
```

`./railix` never starts a Maven build implicitly. It runs the verified shaded JAR at
`modules/railix-creator/target/railix.jar` or returns a deterministic package error.
Creator listens on loopback only and prints its URL as canonical JSON.

Creator starts with the central Application, its default CLI Trigger, and one runnable
lowercase flow. Layout is derived from the flow and cannot drift into saved coordinates.

1. Select **New** for a generated short application name and an attached CLI Trigger.
2. Select **Add next** on a Trigger or Step outcome to add only the explicit control edge.
3. Select **Add data consumer** on a free output, or **Choose source** on an input, to edit only data.
4. Select the Application to edit its name, ports, and Triggers; select a Step to edit mappings and configuration.
5. Open **Map fields** only for nested field/index paths, defaults, or explicit conversions.
6. Use **Run** for the current sample event and **Save** for canonical `railix.flow.json`.

Ordinary ports allow one connection and stop offering an occupied endpoint. Imported
advanced multi-mappings remain visible and removable without being flattened. Dedicated
Merge, Split, Choice, and Loop semantics remain roadmap work; Creator does not disguise
ordinary edges as those operators.

## Build A Custom Monolith

The standalone Welcome App remains outside the default three-module product reactor. Its
opt-in profile builds Railix and the real external consumer together, with no prior install.
It registers one standard Step and one external Step, embeds the complete Creator-authored
flow, and shades that application into one JAR.

```sh
mvn -q -Ptrusted-step-monolith clean verify
mvn -q -Ptrusted-step-monolith,refresh-step-lock clean verify
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar \
  cli command < examples/trusted-step-monolith/input.json
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar
java -jar examples/trusted-step-monolith/target/trusted-step-monolith.jar creator
```

The first command rejects any missing, malformed, or stale dependency lock before shading and
emits the same JAR bytes from independent clean source copies. The second explicitly
regenerates the canonical lock and then verifies the complete build; normal builds never edit
source files. The CLI trigger prints `{"message":"Welcome, railix"}`. With no arguments the same
committed JAR listens on `http://127.0.0.1:18080/` and exposes `/greet`, the explicit flow-event
route, and the explicit `prefix` Step-event route. The Creator command serves the exact explicitly
registered catalog; open
`examples/trusted-step-monolith/src/main/resources/railix.flow.json` to edit the same flow
that the JAR embeds. The external build has a readable flow E2E, a 14-scenario dependency
safety matrix, copied-project lifecycle proof, and package and real-browser E2Es. See its
[template README](examples/trusted-step-monolith/README.md) for the small copy/edit surface.

## Develop A Step

A Step is one named, immutable contract plus one handler. It does not decide flow error
handling or inspect the graph.

```java
public final class LowercaseStep {
    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final Locale AZERI = Locale.forLanguageTag("az");
    private static final Locale LITHUANIAN = Locale.forLanguageTag("lt");
    private static final Locale THAI = Locale.forLanguageTag("th");

    private LowercaseStep() {
    }

    public static StepDefinition definition() {
        return StepDefinition.named("text.lowercase", "1.2.0")
                .kind(StepDefinition.Kind.NORMALIZER)
                .config(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.string("und")
                )
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.string("text").toLowerCase(
                                caseLocale(input.configString("languageTag"))
                        ))
                ));
    }

    private static Locale caseLocale(final String languageTag) {
        if (effectiveLanguage(languageTag, "tr")) {
            return TURKISH;
        }
        if (effectiveLanguage(languageTag, "az")) {
            return AZERI;
        }
        if (effectiveLanguage(languageTag, "lt")) {
            return LITHUANIAN;
        }
        return effectiveLanguage(languageTag, "th") ? THAI : Locale.ROOT;
    }

    private static boolean effectiveLanguage(final String languageTag, final String language) {
        if (!languageTag.regionMatches(true, 0, language, 0, language.length())) {
            return false;
        }
        if (languageTag.length() == language.length()) {
            return true;
        }
        return languageTag.charAt(language.length()) == '-'
                && !hasExtlang(languageTag, language.length() + 1);
    }

    private static boolean hasExtlang(final String languageTag, final int start) {
        final int separator = languageTag.indexOf('-', start);
        final int end = separator < 0 ? languageTag.length() : separator;
        if (end - start != 3) {
            return false;
        }
        return Character.isLetter(languageTag.charAt(start));
    }
}
```

Register every referenced definition explicitly with `StepCatalog.of(...)`. Railix uses no
reflection to discover Steps. `RailixRunner` executes against exactly that catalog, and
`CreatorServer.start(port, catalog)` gives the palette, compiler, and runner the same source
of truth. The compiler validates Step contracts, flow nodes, outcomes, mappings, types,
reachability, and definite data availability before it returns an opaque `CompiledFlow`.
Runtime results are explicit `Succeeded`, `Rejected`, `Failed`, or `Cancelled` values.

The minimal standard reference is
[`LowercaseStep.java`](modules/railix-stdlib/src/main/java/dev/nanonative/railix/stdlib/text/LowercaseStep.java).
The reusable external shape is
[`GreetingStep.java`](examples/trusted-step-monolith/src/main/java/example/railix/welcome/GreetingStep.java),
registered by
[`TrustedGreetingMain.java`](examples/trusted-step-monolith/src/main/java/example/railix/welcome/TrustedGreetingMain.java)
and proven inside its committed flow by
[`GreetingStepFlowIT.java`](examples/trusted-step-monolith/src/test/java/example/railix/welcome/GreetingStepFlowIT.java).
[`StepDependencyBuildIT.java`](examples/trusted-step-monolith/src/test/java/example/railix/welcome/StepDependencyBuildIT.java)
keeps missing, duplicate, and structurally incompatible dependencies out of the build with
stable compiler diagnostics.

Every Step version is an explicit opaque compatibility signal. Compilation derives one
canonical `railix.lock.json` from the complete authored flow and only its referenced Steps.
The lock fingerprints every declared port, configuration/default, outcome, id, version, and
authoring kind; unused catalog entries are excluded. Change the Step version whenever handler
behavior changes, because Railix deliberately does not inspect bytecode or use reflection to
guess semantics.

## Use Lightweight Data Steps

`StepDefinition.Kind` classifies authoring presentation without creating another execution
model. Validators, normalizers, mappers, and translators compile and run as ordinary Steps;
the kind is visible in Creator and protected by the dependency lock, but runtime never branches
on it.

| Step | Kind | Configuration | Outcomes |
| --- | --- | --- | --- |
| `text.nonblank` | validator | `trimBeforeCheck: boolean`, default `true` | `valid`, `invalid` |
| `text.lowercase` | normalizer | `languageTag: language-tag string`, default `"und"` | `ok` |
| `value.default-if-null` | mapper | required `replacement: any` | `kept`, `defaulted` |
| `text.translate-exact` | translator | required `from: string`, `to: string` | `translated`, `unchanged` |

The validator preserves its input, the mapper replaces only explicit JSON `null`, and the
translator compares exact case-sensitive strings. Missing mapped data remains a compiler/runtime
mapping rejection; none of these Steps invents data, coercion, routes, retries, queues, or scaling
behavior. Language tags use Java 25's strict BCP 47 parser at compile time; malformed, empty,
whitespace, underscore, and truncated tags are rejected without trimming or rewriting. Execution
uses fixed locale instances only for Java 25's locale-sensitive `tr`, `az`, `lt`, and `th`
lowercase families; every other effective language uses `Locale.ROOT`, so event-supplied tags
cannot grow the JDK's process-global locale cache.

## Use Trusted Local Files

`file.read`, `file.write`, and `file.delete` are full trusted Steps, not lightweight mappers.
Every path is an explicit input: it must be non-empty, at most 4,096 UTF-8 bytes, and name a
regular file. Relative paths resolve from the application process working directory. Parent
symlinks resolve normally; a final symlink, directory, or special file is rejected.

```sh
./railix run examples/file-read-app
./railix run examples/file-persistence-app
```

`file.read` uses `json` by default or exact `yaml`/`xml` configuration and emits one normalized
`value`. Its outcomes are `read`, `missing`, and `rejected`. It reads at most 1 MiB plus one
probe byte and then uses the same strict
`RailixData.normalize(...)` boundary documented below. Empty, malformed, invalid UTF-8,
over-depth, overlong-number, oversized, directory, and otherwise non-regular or unreadable
sources route to `rejected` before a data stream is opened; an absent path routes to `missing`.

`file.write` accepts `value:any`, persists at most 1 MiB of canonical compact UTF-8 JSON, and
returns `written`, `conflict`, or `rejected`. `overwrite` defaults to `false`; existing regular
files therefore conflict without changing. Railix never creates a parent directory. A write uses
one sibling `.railix-*.tmp`, forces its data and metadata, and requires an atomic move; unsupported
atomic move rejects without a copy/delete fallback. `file.delete` returns `deleted`, `missing`, or
`rejected` and removes regular files only.

One interruptible process-local lock serializes writes and deletes. Reads remain lock-free and
observe a complete old value, a complete new value, or absence under Railix-owned mutation.
Normal failure and cooperative cancellation remove the owned temporary file. Portable Java cannot
fsync a directory entry, so rename/delete survival across host power loss is not claimed. Abrupt
process or host loss can leave a recognizable temp file; Railix does not perform ambient
scavenging. External processes racing the same paths are outside the atomicity and file-type
guarantees.

There is no ambient root, format guessing, retry, cache, permission claim, or sandbox wrapper.
The loaded Steps are trusted Java and therefore have the JDK access of their process. Runtime
settings, secrets, and enforceable permissions remain later roadmap work.

## Use Trusted Outbound HTTP

`http.get`, `http.post`, and `http.delete` are ordinary trusted Steps backed by Java 25
`HttpURLConnection`. All accept explicit `url:string` and `headers:object`; POST also accepts
`body:any` and sends at most 1 MiB of canonical compact JSON. There is no shared client, selector
thread, executor, retry, redirect following, response cache, cookie store, or alternate flow path.

All three Steps expose `format` with the exact `json` default or explicit `yaml`/`xml`, plus
`timeoutMillis` with the visible `30000` default and integer range `1..300000`. They return
`status:number`, lowercase array-valued `headers:object`, and normalized `body:any`. An empty body
is explicit JSON `null`. Outcomes are `success` for 2xx, `redirect` for 3xx, `client-error` for
4xx, `server-error` for 5xx, `other` for every other received status, and `rejected` for local,
transport, bound, or normalization rejection.

URLs must be absolute HTTP or HTTPS with a host, no user info or fragment, and at most 8,192
UTF-8 bytes. Redirects are returned, never followed. Explicit request headers allow at most 64
case-insensitively unique names and 16 KiB total UTF-8 name/value bytes. Names use HTTP token
syntax; values reject control characters except horizontal tab and reject malformed Unicode.
`Connection`, `Content-Length`, `Expect`, `Host`, and `Upgrade` are always rejected regardless
of process properties. POST supplies `Content-Type: application/json` only when absent; this
generated header is outside the explicit-input budget.

Responses accept at most 64 unique normalized names, 16 KiB total name/value bytes, and 1 MiB
plus one body probe byte. Repeated fields become one ordered string array and each physical
name/value pair consumes the byte budget. Railix applies the response-header bound after the JDK
transport parser has admitted headers; it is an output/normalization bound, not a raw-wire
sandbox guarantee.

One monotonic deadline bounds connect, headers, and every response-body read, including peers
that trickle data. Java URL connections expose no write timeout, so an operating-system-blocked
POST write is bounded by cooperative interruption rather than `timeoutMillis`. Every invocation
disconnects in `finally`; cancellation is peer-visible, 64 blocked calls allocate no HTTP-client
selector per invocation, and a 1,000-request public-flow soak checks descriptor/thread return.

Run the committed whole-flow example with Java's included development server:

```sh
jwebserver -b 127.0.0.1 -p 18082 -d examples/http-get-app/server
./railix run examples/http-get-app
```

The first command stays active, so run the second in another terminal.

## Define A Flow

[`examples/lowercase-app/railix.flow.json`](examples/lowercase-app/railix.flow.json) is the
canonical example. Its mappings are explicit:

```text
input.text -> lowercase.text
lowercase.text -> output.text
lowercase.ok -> end
```

No mapping, configuration override, validation, or error route is invented at runtime.
Declared Step defaults are resolved by the compiler. Authoring tools must use compiler
diagnostics to give feedback before packaging.

## Trigger, Event, And Reply Contract

Every flow declares one explicit trigger array. Empty means no deployed ingress. CLI, startup,
HTTP, socket, and scheduled declarations are implemented:

```json
"triggers": [
  {
    "id": "command",
    "type": "cli",
    "config": {"stdin": true, "arguments": "arguments"}
  },
  {
    "id": "initialize",
    "type": "startup",
    "config": {}
  },
  {
    "id": "orders",
    "type": "http",
    "config": {"port": 8080, "path": "/orders"}
  },
  {
    "id": "flow-events",
    "type": "http",
    "config": {"port": 8080, "flow": true}
  },
  {
    "id": "validate-events",
    "type": "http",
    "config": {"port": 8080, "step": "validate"}
  },
  {
    "id": "events",
    "type": "socket",
    "config": {"port": 17000, "timeoutMillis": 30000, "maxConnections": 32}
  },
  {
    "id": "refresh",
    "type": "scheduled",
    "config": {"intervalMillis": 60000, "initialDelayMillis": 0, "maxConcurrentRuns": 1}
  }
]
```

Trigger ids are unique non-blank strings. CLI requires `stdin:boolean`; optional `arguments`
names an `array` or `any` flow input. With stdin enabled, the command reads exactly one bounded
UTF-8 JSON object. Flow and event sources are limited to 1 MiB. With stdin disabled, the event
starts empty. Process arguments are inserted only at the declared input, never overwrite stdin
data, and cannot make the final event exceed 1 MiB:

```sh
java -jar application.jar cli command --uppercase fast < event.json
```

Every HTTP declaration requires one shared port and exactly one route selector. `path` exposes a
static business route, `flow:true` exposes `POST /v1/flows/{flowId}/events`, and `step` exposes
`POST /v1/flows/{flowId}/steps/{stepId}/events`. Nothing is exposed implicitly. A Step event starts
at that Step and continues through its normal outcomes; compilation rejects downstream mappings
that would need a skipped Step output or unavailable flow input.

The application listener uses JDK `HttpServer` on IPv4 loopback only. Enabled routes accept POST,
`Content-Type: application/json`, no query, and one UTF-8 JSON object up to 1 MiB. Transport
rejections use deterministic Problem JSON. Successful flow replies use HTTP 200; input/contract
rejection uses 422, Step failure 500, and cancellation 503. Replies are also bounded to 1 MiB.

One socket declaration exposes IPv4 loopback TCP. Each frame is a four-byte signed big-endian
length followed by one strict UTF-8 JSON object from 1 byte through 1 MiB; replies use the same
framing. Frames run serially per connection. Malformed events receive one rejection and leave the
connection reusable; invalid, truncated, or late framing receives one rejection and closes.
`timeoutMillis` is an absolute read/write deadline. `maxConnections` is a hard `1..256` limit:
excess clients receive immediate backpressure and are never queued.

A scheduled trigger requires an inputless flow and sends `{}`. `intervalMillis` is positive,
`initialDelayMillis` is non-negative, and `maxConcurrentRuns` is `1..256`. Its monotonic fixed-rate
clock starts at application readiness. An occupied concurrency slot skips the tick without a queue
or catch-up burst. Restart creates a new clock epoch. Success writes the canonical reply to stdout;
rejection or failure writes to stderr, and later ticks continue.

With HTTP, socket, or scheduled ingress declared, no packaged arguments start all ingress gated,
execute startup triggers, publish readiness, then serve until caller interruption. Without
long-running ingress, no arguments execute every startup trigger once and return. Startup and
scheduled triggers use `{}` and therefore require an inputless flow.
A CLI invocation preflights its trigger and event, executes all startup triggers first, then
executes the selected CLI trigger. The first non-success stops the sequence; previous successful
output remains visible. CLI, `creator`, and `lock` never start long-running ingress; `creator` and
`lock` never execute startup behavior. The old packaged
`run <input.json>` command is rejected, not retained as a fallback. Root
`./railix run <app-directory>` and Creator Run remain explicit authoring events rather than
deployed ingress. Arbitrary trigger types fail with `FLOW_TRIGGER_TYPE_UNSUPPORTED`.

An event is exactly one immutable JSON object matching the declared flow inputs. Railix adds
no envelope, timestamp, reply channel, metadata, or implicit value. The compiled flow returns
one trigger-independent `RunResult`: `Succeeded` carries outputs and completed Steps,
`Rejected` carries deterministic diagnostics and completed Steps, `Failed` carries a Step
contract or implementation fault and completed Steps, and `Cancelled` carries only completed
Steps.

Cancellation uses Java interruption owned by the caller that runs the compiled flow. Railix
observes it before admission, between Steps, and before assembling the reply; an interruptible
Step may propagate `InterruptedException`. Railix restores the interrupted flag and reports
`Cancelled` only after the Step has terminated. Trusted Steps must return promptly when
interrupted. Railix stops ingress immediately and requests cancellation; it cannot safely
terminate Java code that ignores interruption. A non-cooperative Step is unsupported and can
keep its execution state alive until it returns or the process exits. A Step exception remains
`Failed` rather than being disguised as cancellation. CLI stdin remains caller-owned and finishes
only at EOF or when its owner closes the stream; Railix does not claim that interruption can
terminate an arbitrary blocked stream.

## Map Nested Data

Connections may select and assemble nested primitive data without a second schema or runtime
mapper. `sourcePath` and `targetPath` are non-empty arrays when present: strings are exact object
field names and non-negative integers are array indexes. Omitting either path means the complete
port value. Paths are limited to 64 elements.

```json
[
  {
    "from": "input.request",
    "sourcePath": ["profile", "name"],
    "default": "UNKNOWN",
    "to": "lowercase.text"
  },
  {
    "from": "lowercase.text",
    "to": "output.response",
    "targetPath": ["profile", "name"]
  }
]
```

`default` is used only when a selected field or array index is missing. Explicit JSON `null`, a
wrong container type, and conversion failures remain visible rejections. Defaults already have the
target type and do not pass through `convert`. The explicit conversions are `string-to-number`,
`number-to-string`, `string-to-boolean`, and `boolean-to-string`; Railix performs no other coercion.

The compiler rejects incompatible roots, duplicate or overlapping targets, mixed object/array
assembly, and target-array holes before execution. Nested values selected from `any` or a container
are checked when reached and return stable connection diagnostics. Completed Step history remains
present when a later mapping rejects.

## Normalize Primitive Data

`RailixData.normalize(...)` is the one explicit-format boundary for strict UTF-8 JSON,
constrained block YAML, and typed XML. It returns either a normalized immutable value or a
stable diagnostic; callers do not receive parser exceptions.

```java
final byte[] source = """
        name: "Railix"
        ports:
          - 80
          - 443
        """.getBytes(StandardCharsets.UTF_8);

switch (RailixData.normalize(RailixData.Format.YAML, source)) {
    case RailixData.Normalized normalized ->
            System.out.println(normalized.canonicalJson());
    case RailixData.Invalid invalid ->
            System.err.printf("%s at %d:%d: %s%n",
                    invalid.code(), invalid.line(), invalid.column(), invalid.message());
}
```

This prints `{"name":"Railix","ports":[80,443]}`. YAML deliberately supports the
lossless block subset: two-space maps/sequences, JSON double-quoted strings and numbers,
lowercase booleans/null, and empty collections. XML uses explicit `object`, `field`, `array`,
`item`, `string`, `number`, `boolean`, and `null` elements instead of guessing how arbitrary
XML maps to primitives. Direct flow and event documents default to 1 MiB and 64 nested
containers. The explicit overload accepts byte limits through 8 MiB for bounded enclosing
transport documents; direct ingress keeps the 1 MiB contract. JSON number source and canonical
forms are capped at 1,024 characters.

### Format Contract

| Concern | JSON | YAML | XML |
| --- | --- | --- | --- |
| Primitive model | Objects, arrays, strings, JSON decimals, booleans, and null | Block maps/sequences plus the same scalars; empty `{}` and `[]` are accepted | Typed `object`, `array`, `string`, `number`, `boolean`, and `null` elements |
| Field names | Any JSON string; duplicate fields are invalid | `[A-Za-z_][A-Za-z0-9_-]*`; duplicate fields are invalid | Exactly one `name` attribute on `field`; its decoded string may be empty; duplicate fields are invalid |
| Repeated values | Arrays preserve order and repeated values | Sequences preserve order and repeated values | Repeated `item` elements preserve order and values |
| Numbers | Strict JSON decimal grammar | The same JSON decimal grammar | The same grammar as exact element text; formatting whitespace is invalid |
| Strings and escapes | JSON double quotes, escapes, Unicode escapes, and valid surrogate pairs | JSON double-quoted strings and escapes only | `string` preserves text; predefined and decimal/hex character references are decoded |
| Whitespace | Space, tab, CR, and LF outside values | Blank lines and trailing spaces are ignored; nesting uses two spaces; tabs are unsupported | Structural XML whitespace is ignored; `string` whitespace is preserved |
| Comments | Invalid JSON | `DATA_YAML_UNSUPPORTED` | `DATA_XML_UNSUPPORTED` |
| Alternate YAML forms | Not applicable | Non-empty flow collections, plain/single-quoted strings, block scalars, directives, and document markers are unsupported | Not applicable |
| References | Not applicable | Anchors, aliases, tags, and merge keys are unsupported | Predefined/numeric character references are accepted; undefined references are invalid |
| Attributes and namespaces | Not applicable | Not applicable | Only `field name` is supported; every namespace declaration or prefix is unsupported |
| Typed structure | Not applicable | Not applicable | `boolean` is exactly `true`/`false`; `null` is empty; each `field`/`item` has one value; objects/arrays reject non-whitespace text |
| Document markup | One root value; trailing content is invalid | One root value | Optional UTF-8 declaration; DTD/custom entities, comments, CDATA, and other processing instructions are unsupported |
| Diagnostic family | `DATA_JSON_INVALID` | `DATA_YAML_INVALID`, `DATA_YAML_UNSUPPORTED`, or `DATA_FIELD_DUPLICATE` | `DATA_XML_INVALID`, `DATA_XML_UNSUPPORTED`, or `DATA_FIELD_DUPLICATE` |

All accepted forms normalize into the same canonical value model. Unsupported means the
format is recognized but intentionally outside this lossless contract; invalid means the
document violates the selected contract. Railix never guesses another format or silently
drops data. Ingress and resource diagnostics are shared: `DATA_FORMAT_REQUIRED`,
`DATA_SOURCE_REQUIRED`, `DATA_LIMIT_INVALID`, `DATA_SOURCE_TOO_LARGE`,
`DATA_BOM_UNSUPPORTED`, `DATA_SOURCE_UTF8_INVALID`, `DATA_DEPTH_EXCEEDED`, and
`DATA_NUMBER_LIMIT_EXCEEDED`.

## Active Tree

```text
modules/railix-core      JSON values, Step contract, compiler, executable flow
modules/railix-stdlib    Trusted standard Step definitions
modules/railix-creator   CLI, shaded package, loopback API, Creator UI
examples/lowercase-app  Runnable public example
examples/file-read-app  Bounded trusted file-read example
examples/file-persistence-app  Atomic write/read/delete example
examples/http-get-app  Bounded trusted outbound HTTP example
examples/trusted-step-monolith  External Step and whole-flow custom JAR
railix                  Root launcher
```

## Operational Limits

[ROADMAP.md](ROADMAP.md) is the only implemented/planned capability and progress source.
The constraints below are operational details for currently runnable behavior:

- Creator has no autosave, implicit restore, workspace history, cloud storage, or server-path file
  access. Raw event input is intentionally authoring-only and is never persisted into the executable
  flow or used as a runtime fallback.
- Packaged flow/event reads stop after the 1 MiB limit plus one probe byte; committed dependency
  lock reads stop after the 8 MiB limit plus one probe byte. Caller-owned stdin is never closed
  by Railix.
- Control cycles are rejected because unbounded execution history is not yet safe.
- Canonical JSON plus constrained block YAML and typed XML normalization are implemented.
  Full YAML and arbitrary document-shaped XML are explicitly unsupported rather than silently
  approximated; the exact accepted/rejected contract is documented above.
- Reproducible package evidence currently covers the exercised Java/Maven toolchain. Cross-OS and
  cross-toolchain equivalence is not claimed.
