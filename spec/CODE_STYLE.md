# Code Style & Rules

This document defines the coding and API style for Railix.
All contributors should follow it so the library keeps one shape instead of drifting per author.

---

## Formatting

| Rule            | Value          |
|-----------------|----------------|
| Java version    | 21             |
| Indentation     | 4 spaces       |
| Line endings    | LF             |
| Max line length | 120 characters |
| Charset         | UTF-8          |
| Final newline   | Yes            |

Formatting is enforced by `.editorconfig`.

---

## Naming

### Packages

- Short, lowercase, singular: `log`, `cfg`, `fn`, `name`, `apt`, `metrics`
- No `util`, `helper`, `common`, `base`, or `impl`

### Classes

- Concrete names, no `Default*`, `Abstract*`, or `*Impl`
- `RailixLogger`, not `DefaultRailLogger`
- `Metrics`, not `MetricsRegistry`

### Methods

- Flow verbs should read in declaration order: `of`, `verify`, `map`, `choose`, `each`, `parallelEach`, `parallel`,
  `step`, `ok`, `fail`
- Accessors stay short: `payload()`, `ctxMap()`, `actors()`, `config()`
- Avoid builder noise in the main DSL

### Keys

- Payload, context, and config keys are normalized via `Names.sanitize()`
- Canonical form: `snake_case`, lowercase, letters/digits/underscore only
- `traceId` -> `trace_id`, `HTTPServer` -> `http_server`

---

## Rail DSL Rules

### One Clear Ingress

Rails start at `Rail.of(sourceActor)`.
Do not design public examples around `Rail.init()` plus later manual execution.

### Payload Is Not Metadata

Do not disguise request or message data as rail metadata.
Use explicit operations for constants or enrichments.

```java
Rail.of(httpActor)
    .put("tenant", "acme")
    .defaults(defaults())
    .enrich(loadStaticContext())
    .verify(hasEmail())
    .ok();
```

### Keep Rails Readable

A rail should be readable top to bottom without jumping through helper indirection.
Prefer small named reusable rails and steps over giant lambdas.

```java
final RailDef validateUser = Rail.of()
    .verify(hasEmail())
    .verify(hasName())
    .seal();
```

### Keep Runtime Mechanics Internal

Definition/composition belongs in the public DSL.
Traversal state, step pointers, retry bookkeeping, and checkpoint machinery stay internal.

---

## Class Design

### No Unnecessary Interfaces

Create an interface only when there is a real second implementation.

### Records for Data Holders

Use records for immutable data objects.

```java
public record LogEntry(String loggerName, LogLevel level, String message, Throwable error, long timestampMillis,
                       String threadName) {}
```

### No Factory Classes for Trivial Construction

Do not hide trivial `new X()` construction behind factory classes.

### Standard Functional Interfaces First

Use `Consumer<T>`, `Function<T, R>`, `Predicate<T>`, and related JDK types before inventing custom SAM types.

---

## Variable & Expression Rules

### Inline Single-Use Variables

Do not create intermediates that are used exactly once unless the name materially improves readability.

### Use `final` on Parameters

Mark method parameters `final` unless there is a concrete reason not to.

---

## Null Handling

### Public API: Null-Safe

Public methods should avoid hostile null handling.
Skip null callbacks/steps when safe, return explicit `Result`s for failures, and do not return `null` from public APIs.

### Internal Code: Be Precise

Private/internal code may assume validated inputs and stay strict.

---

## Configuration Pattern

- Config remains explicit and boring.
- Config does not replace source ingress.
- Shared app config belongs in runtime/app context.
- Per-rail descriptive metadata is not request payload.

---

## Testing Rules

- Framework: JUnit 6.x + AssertJ
- Prefer behavior-first tests around operators and rail outcomes
- Test classes should have narrow scope: `RailChoiceTest`, `RailVerifyTest`, `RailConfigTest`
- Test methods should follow `thing_withCase_shouldResult`
