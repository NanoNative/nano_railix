# Railix II Blueprint

Railix II is a local-first visual app/workflow builder where the main architectural unit is a **Step**.
A Creator app lets users add cached step dependencies, connect steps, map nested payloads, test flows by running the real built app, and package the result into standalone executables with or without UI.

This repository is a **blueprint repo**, not a finished implementation. It is structured so an implementation AI or developer can start building without re-deciding the architecture every ten minutes like a frightened committee.

## Core product statement

Railix II builds standalone flow applications from reusable Java step packs.

A built Railix II app can:

- run headless or with bundled UI
- accept generic triggers such as manual, CLI, HTTP, MQTT, file watch, queue, cron, or custom protocols
- process nested data using visual path/selector/transform tools
- carry context through a flow as patchable structured documents
- expose live run signals, metrics, audit events, traffic, context diffs, and bottlenecks
- be configured by packaged defaults, environment profiles, properties files, environment variables, CLI flags, and encrypted secret values
- later scale across multiple instances without forcing the user to manage containers, microservices, Kubernetes, Prometheus, Grafana, ingress, or network glue by hand

## First principles

1. **Step-first.** The step is the reusable execution unit. Flow only describes which step goes next.
2. **Plan-connected.** Steps are connected by the app plan, not by an event bus.
3. **Observation is separate.** Runs emit a `RunSignalLog` for UI, metrics, audit, and debugging. It does not sit between steps.
4. **Settings are one tree.** Config and secrets use one settings model. Secret values are metadata-protected settings.
5. **Nested data is first-class.** Data is editable as a tree, flat paths, selectors, tables/lists, mappings, and previews.
6. **Core stays clean.** HTTP, MQTT, database, store operations, transforms, metrics panels, and protocol adapters live in standard packs, not in the kernel.
7. **Build real apps.** The Creator run button builds and starts the actual app executable/image, then attaches to its control and metrics endpoint.
8. **jlink/jpackage from day one.** JARs are intermediate artifacts. Native via `javan` is a later target.
9. **No primary DSL in day one.** The canonical source is the app plan. A DSL may generate plans later, but the UI must not depend on reverse-mapping code.
10. **Small feature set, serious contracts.** Day-one scope is intentionally narrow, but contracts must support future protocols, resource refs, distributed execution, UI dashboards, and protocol packs.

## Repository layout

```text
railix-ii-blueprint/
  README.md
  ROADMAP.md
  IMPLEMENTATION_PROMPT.md
  docs/
  adr/
  spec/
  packs/
  examples/
  modules/
  tools/
```

## Minimal implementation target

The first useful slice should support:

- `RailixValue`, `RailixPath`, `Selector`, `Patch`, `Shape`
- `SettingsTree` with secret metadata
- `StepContract` and `OperatorContract`
- `Envelope` and `Reply`
- `ContextDoc` snapshots and patches
- `RunSignalLog`
- local deterministic run folders
- `ManualTrigger`, `CliTrigger`
- `DataTransform`, `DataValidate`, `DataRoute`
- `StoreRead`, `StoreWrite`
- Creator graph editor stub
- DataWorkbench stub with flat path mapping and preview
- `jlink` and `jpackage` packaging scripts
- thin `railix package` wrapper over the current packaging driver, with opt-in machine-readable package reports

## Naming

Working product name: **Railix II**

CLI/package naming candidates:

```text
railix
railix2
railix-ii
nanonative-railix
```

Use `railix2` for internal module/package names until public naming is settled. The creature may yet molt.
