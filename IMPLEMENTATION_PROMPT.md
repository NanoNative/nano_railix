# Implementation Prompt for an AI Coding Agent

You are implementing Railix II from this blueprint repository.

## Non-negotiable rules

- Use Java first.
- Use modern Java records, sealed interfaces, immutable value objects, and explicit APIs.
- Avoid reflection, classpath scanning, dynamic plugin magic, and framework-heavy startup paths.
- Do not introduce Spring, Quarkus, Micronaut, Lombok, Jackson, Guava, or external libraries in the first kernel slice.
- Do not use an event bus between steps.
- The app plan connects steps. The scheduler executes plan edges directly.
- Run signals are observation records only.
- Config and secrets are one `SettingsTree`; secrets are protected setting values.
- Nested data must be represented through `RailixValue`, `RailixPath`, `Selector`, `Patch`, and `Shape`.
- The Creator UI edits the canonical app plan. Do not make a Java DSL the source of truth.
- The first package target is `jlink`/`jpackage`, not GraalVM.
- `javan` is a future native target; keep the code javan-friendly by staying simple and avoiding unsupported runtime features.

## First coding milestone

Create a compilable Java module `railix.kernel` with:

- `RailixValue`
- `RailixPath`
- `Selector`
- `Patch`
- `ContextDoc`
- `SettingsTree`
- `Envelope`
- `Reply`
- `StepContract`
- `OperatorContract`
- `RunSignal`
- `PermissionSet`

Then create a tiny local runner that executes this example:

```text
ManualTrigger -> DataTransform -> LogStep
```

The runner should:

1. create a deterministic local run folder
2. create an initial `Envelope`
3. create a context snapshot
4. execute steps in the plan order
5. apply context patches
6. emit run signals
7. write run signals as newline-delimited JSON-like text

No UI is required in the first commit, but the `RunSignalLog` format must be suitable for UI consumption.

## Implementation style

Prefer code like this:

```java
public record RailixPath(List<Token> tokens) {
    public static RailixPath parse(final String value) {
        // deterministic parser; no regex soup if avoidable
    }
}
```

Avoid code like this:

```java
Map<String, Object> ctx = new HashMap<>();
ctx.put("lol", anything);
```

That way lies sadness, and sadness with stack traces is Java.
