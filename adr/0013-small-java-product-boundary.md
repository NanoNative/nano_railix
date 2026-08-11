# ADR 0013: Small Java Product Boundary

## Status

Accepted on 2026-07-27; release distribution clarified on 2026-08-11.

## Context

Railix needs one reusable core without pulling transport, UI, capability, or future
distributed concerns into it. The previous blueprint split speculative behavior across many
modules before a complete public flow worked.

## Decision

Production uses Java 25 and JDK APIs. The default reactor contains exactly
`railix-core`, `railix-stdlib`, and `railix-creator`, with dependencies pointing outward:
core defines canonical semantics, stdlib supplies trusted Steps, and Creator owns ingress,
application lifecycle, packaging, CLI, HTTP APIs, and UI.

There are no JPMS descriptors, reflection, runtime scanning, framework runtime, or
third-party runtime dependencies. Maven, JUnit, AssertJ, JaCoCo, Shade, and Playwright are
build/test tools only. Distribution is currently one shaded JAR; the permanent tracked root
launcher never starts a build implicitly and Maven never creates, changes, or removes it.

That JAR and launcher are the development distribution. Roadmap Item 8 owns the production
distribution: `jdeps` determines the required JDK modules, `jlink` creates one minimal
runtime image, and `jpackage` combines that image with Creator into each supported
platform's application and installer. The resulting application must not require Maven or
a separately installed JDK. GraalVM native-image is a later target after the Java contracts
and application are stable; it is not a development-time constraint.

## Invariants

- Core growth must pass the ownership tests in `ROADMAP.md`.
- Transport and Step implementation details stay outside core.
- Registration and dependency wiring remain explicit.
- The production runtime remains independent of Node and native libraries.
- The development launcher is tracked source and remains present across every Maven lifecycle.
- The final Creator distribution includes its Java runtime and launches without Maven or an
  ambient JDK.

## Consequences

The runtime is small and inspectable, but conveniences normally supplied by frameworks must
be implemented only when a current public behavior requires them.

## Rejected Alternatives

The former multi-pack/module layout, speculative interfaces, service loading, framework
containers, runtime frontend tooling, and running release packaging during normal
development builds are rejected.

## Evidence

- [`pom.xml`](../pom.xml)
- [`modules/railix-core/pom.xml`](../modules/railix-core/pom.xml)
- [`modules/railix-stdlib/pom.xml`](../modules/railix-stdlib/pom.xml)
- [`modules/railix-creator/pom.xml`](../modules/railix-creator/pom.xml)
- [`development launcher`](../railix)

`jdeps --print-module-deps modules/railix-creator/target/railix.jar` reports only
`java.base,java.desktop,java.net.http,jdk.httpserver`.

## Deferred Decisions

Exact installer formats, signing, cross-host packaging, and clean-machine release proof
remain Roadmap Item 8. GraalVM native-image compatibility follows the stable Java distribution.

## Historical Disposition

This supersedes the implemented fragments of deleted ADRs 0001, 0006, and 0009. Their
unimplemented packaging and module claims are not retained.
