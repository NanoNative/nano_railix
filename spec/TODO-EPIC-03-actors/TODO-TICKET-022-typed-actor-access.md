# ⏳ TODO — TICKET-022: Typed access via `actors()`

> **Status:** ⏳ TODO · **Priority:** P0

## Goal

Provide a dedicated `myRail.actors()` accessor that can retrieve registered actors by **name** and/or **class**.

Must support defensive access:
- `...Opt()` variants (`Optional<T>`)
- a cast-safe API that returns `null` when the actor exists but cannot be cast (as requested)
- if an actor is missing and the user didn’t verify/require it, typed getters return `null`

## Options

- Programmatic:
  - `myRail.actors().get("mongo")` / `getOpt("mongo")`
  - `myRail.actors().get("mongo", MongoActor.class)` → `MongoActor` or `null` on cast-mismatch
  - `myRail.actors().getOpt("mongo", MongoActor.class)` → `Optional<MongoActor>`
  - `myRail.actors().get(MongoActor.class)` / `getOpt(MongoActor.class)`
- Optional APT: generate typed actor accessors (e.g. `myRail.actors().mongo()` + `myRail.actors().mongoOpt()`).

### APT idea (fits your requirement)

Extend the existing `@Railix` processor to support actor accessors (method-level).

```java
@Railix
public interface App {
  // ctx key (existing behavior)
  String traceId();

  // actor accessors (new)
  @RailActor(MongoActor.class) MongoActor mongo();
  @RailActor(value = HttpReceiverActor.class, name = "http") HttpReceiverActor http();
}
```

Generated API:
- `appRail.ctx().traceId() ...`
- `appRail.actors().mongo() ...`
- `appRail.actors().http() ...`

Generated defensive API (always present):
- `appRail.actors().mongoOpt() ...`
- `appRail.actors().httpOpt() ...`

## `@RailActor` shape (recommended)

- Method-level annotation on `@Railix` interfaces.
- Methods must be `no-arg` and return the actor class (not `Optional`).
- Annotation defines actor identity:
  - required: `value` = actor class (e.g. `@RailActor(MongoActor.class)`)
  - optional: `name` (only if not method name)
- When `name` is absent, the actor key is the method name (sanitized via `Names.sanitize(...)` rules).
- The processor rejects mismatches between `value` and return type.
