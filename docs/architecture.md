# Architecture

## High-level architecture

```text
Creator App
  edits app plan
  manages local dependency cache
  starts real built app in dev mode
  attaches to app control endpoint
  displays live run signals, metrics, traffic, context diffs, and bottlenecks

Built Railix App
  contains embedded runtime
  contains selected reachable flows
  contains selected reachable step packs
  contains bundled UI assets when UI mode is enabled
  reads settings from packaged defaults, profiles, files, env, CLI
  runs headless or with UI

Kernel
  validates app plan
  schedules steps directly from plan edges
  resolves settings
  manages context snapshots and patches
  enforces permissions
  manages resource refs
  writes run signal log

Standard Packs
  provide triggers, data steps, store steps, metrics steps, protocol adapters
  are dependencies, not kernel features
```

## Execution path

```text
Envelope -> Trigger Step -> Flow Scheduler -> StepRunner -> next StepRunner -> Reply
```

No event bus sits between steps.

## Observation path

```text
StepRunner -> RunSignalLog -> UI / metrics panel / audit viewer / run history
```

Run signals are append-only observations.

## Kernel responsibilities

The kernel owns:

- plan loading and validation
- settings resolution
- permission decisions
- context snapshot creation
- patch application
- run folder lifecycle
- step scheduling
- resource ref lifecycle
- run signal writing

The kernel does not own:

- HTTP semantics
- MQTT semantics
- database clients
- business transforms
- dashboards
- protocol-specific response behavior
- Kubernetes-like orchestration

Those live in packs.

## App plan

The app plan is the canonical source of truth.

The Creator edits the plan.
The CLI validates/runs/packages the plan.
A future Java/Kotlin DSL may generate the plan.
The UI does not reverse-engineer arbitrary code.

## Build process

```text
Creator/CLI
  loads app plan
  resolves dependencies from local/global cache
  computes reachable flows/steps/operators/protocol adapters
  writes lockfile
  builds modular Java runtime image with jlink
  packages app image with jpackage
```

Unused steps are not packaged.

## Runtime process

```text
Built App
  loads embedded plan and lockfile
  resolves runtime settings
  starts selected trigger adapters
  accepts envelopes
  executes flows
  writes run signals
  exposes control/metrics endpoint for Creator/UI
```
