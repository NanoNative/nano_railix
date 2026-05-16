# ⏳ TODO — EPIC-09: Build DSL / Packaging

> **Priority:** P1

## Outcome

Railix can describe and build a Rail app through its own DSL.
The same definition should be able to drive local builds, bootstrap a fresh machine, generate Maven metadata for
IDE/tooling, package the app for multiple targets, and export diagrams of the app structure.

Target capabilities:
- declare Java version (`latest` or explicit)
- declare output folder or use sensible defaults
- declare license, author, owners/developers, dependencies, repositories
- provide a wrapper-style Railix Build entrypoint (`sh` first, native launcher later) similar to Maven Wrapper
- generate Maven metadata so IDEs can import the project cleanly
- detect installed JDKs or download the requested version if missing
- locate the named build definition class or fall back to the first discoverable build definition
- build JAR, `jlink`/`jpackage`, GraalVM native executable
- when Docker/buildx is available, build `amd64` and `arm64` scratch images
- when Docker/buildx is available, cross-build GraalVM executables for supported target OS/arch combinations
- export Mermaid diagrams for app/rail structure when the definition provides enough named structure

## Why

The app definition should be boring and declarative. Packaging should not require the user to hand-wire Maven,
container files, Graal toolchains, JDK discovery, wrapper bootstrapping, and visualization glue every time.

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-090: Build DSL shape](./TODO-TICKET-090-build-dsl-shape.md) | ⏳ TODO | define the declarative model |
| [TICKET-097: Railix Build wrapper bootstrap](./TODO-TICKET-097-wrapper-bootstrap.md) | ⏳ TODO | wrapper-style launcher for fresh machines |
| [TICKET-091: Toolchain detection + acquisition](./TODO-TICKET-091-toolchain-detection.md) | ⏳ TODO | detect installed JDKs/GraalVM or fetch missing ones |
| [TICKET-092: Maven model generation](./TODO-TICKET-092-maven-generation.md) | ⏳ TODO | generate `pom.xml` and related metadata for IDEs |
| [TICKET-093: Packaging targets](./TODO-TICKET-093-packaging-targets.md) | ⏳ TODO | jar, jlink/jpackage, native executable |
| [TICKET-094: Docker/buildx cross-builds](./TODO-TICKET-094-docker-cross-build.md) | ⏳ TODO | scratch images and containerized native builds |
| [TICKET-095: Private repository credentials](./TODO-TICKET-095-private-repos.md) | ⏳ TODO | support private Nexus/artifact registries without forcing global `settings.xml` |
| [TICKET-096: Mermaid diagram export](./TODO-TICKET-096-mermaid-export.md) | ⏳ TODO | export app/rail structure as Mermaid when names are available |

## Open Questions

- Is Maven the canonical generated output, or only one backend behind the DSL?
- Should the wrapper execute the named build definition class only, or auto-pick the first valid one when omitted?
- Where should downloaded toolchains live and how are they cached?
- How do private repository credentials stay project-local and safe without relying on a global `settings.xml`?
- Which parts are mandatory in core and which parts belong in optional build actors/plugins?
- How much Mermaid detail is realistic for anonymous lambdas versus named steps/fragments/actors?
