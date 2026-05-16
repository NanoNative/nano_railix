# Railix

> Work in progress: Railix is under active design and its public API may change before a stable release.

Railix is a lightweight Java library for building **source-bound, stream-like processing pipelines**.
A rail should read top to bottom like an advanced `Stream`, but it is driven by an external source actor such as
HTTP, CLI, socket, MQTT, or a scheduler.

## Canonical Model

- `Rail.of(sourceActor)` is the normal ingress for runtime-driven rails.
- `Rail.of().fire(payload[, ctx])` is a first-class manual/custom ingress for tests and custom runtimes.
- The public DSL should read in declaration order: validation, mapping, branching, side effects, terminal outcome.
- Execution is actor-driven internally. `fire(...)` exists for tests, manual triggering, and reusable blueprints.
- `TypeMap` stays central for payload, ctx, config, and conversion helpers.
- Rail metadata describes the rail itself. Runtime/user data is payload, not fake metadata.
- Reuse stays on `Rail` itself: build a reusable rail, `seal()` it into a `RailDef`, and include it with
  `step(otherRailDef)`.
- Railix does not invent path logic. Payload and ctx mutation follow `TypeMap` semantics unchanged.
- Public path-taking APIs stay `Object...`; same-path `map/reduce` stay in the core DSL.
- `each(...)` and `parallelEach(...)` expose `rail, index, key, value`.
  Lists, iterables, and arrays use `key=null`; maps expose the real key; scalars are treated as a single item.

## DSL Shape

```java
final RailDef validate = Rail.of()
    .verify("email_required", rail -> rail.payload().asString("email") != null, "email_required", 400)
    .seal();

final RailDef signup = Rail.of(myHttpActor())
    .step("validate_email", validate)
    .map("normalize_email", value -> value == null ? null : String.valueOf(value).trim().toLowerCase(), "email")
    .step("save_user", rail -> rail.payload().putR("saved", true))
    .ok("created")
    .seal();
```

## Data Model

- **Source actor**: owns ingress and feeds payload into the rail.
- **Payload**: request/message/job data moving through the rail.
- **Rail ctx**: per-execution derived values and transient state.
- **App context**: shared actors, config, metrics, executors.
- **Rail metadata**: descriptive information about the rail itself, such as name, version, tags, transport.

## Reuse

```java
final RailDef normalize = Rail.of()
    .map("normalize_email", value -> value == null ? null : String.valueOf(value).trim().toLowerCase(), "email")
    .seal();

final RailDef signup = Rail.of(myHttpActor())
    .step("normalize_email", normalize)
    .seal();
```

Manual/test triggering stays explicit:

```java
final Result result = signup.fire(
    Map.of("email", "USER@EXAMPLE.COM"),
    Map.of("trace_id", "trace-1")
);
```

This is a real supported mode, not only a fallback.
It is the intended shape for tests and for users who already own ingress outside Railix.

## TypeMap

Railix builds around `berlin.yuna:type-map`.
Use `TypeMap` / `ConcurrentTypeMap` plus `asString`, `asInt`, `asBoolean`, `asOpt`, and related helpers.
Use `Object...` paths exactly as `TypeMap` expects.
The goal stays plain Java data with easy JSON/XML conversion, not reflection-heavy magic.

## References

- Roadmap: `ROADMAP.md`
- Decisions: `spec/ADR.md`
- Testing: `spec/TESTING.md`
- Style: `spec/CODE_STYLE.md`

## License

Railix is licensed under the Apache License, Version 2.0.
