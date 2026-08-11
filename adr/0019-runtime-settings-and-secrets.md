# ADR 0019: Runtime Settings And Secrets

## Status

Superseded on 2026-07-30 by [ADR 0020](0020-creator-first-application-graph.md)
and the accepted [roadmap](../ROADMAP.md).

The implementation described below belonged to the removed pre-Creator graph. Current Railix
supports fixed Step configuration only. Runtime settings and secrets are unsupported until
their own bounded vertical slice; this ADR is retained only as historical design input.

## Context

Step configuration currently contains only canonical flow values resolved by the compiler.
Useful applications also need deployment settings and secrets without placing their values
in authored flows, dependency locks, packaged resources, events, or execution history.
Ambient environment lookup, system properties, conventional files, and provider chains
would hide precedence and make Creator, packages, and tests disagree.

## Decision

One Step contract distinguishes three explicit fields:

- fixed configuration, declared with `config(...)` or `requiredConfig(...)`;
- runtime settings, declared with `setting(...)` or `requiredSetting(...)`;
- required string secrets, declared with `secret(...)`.

The existing `steps[].config` object remains the only flow authoring syntax. It may set
fixed configuration and provide a visible flow default for a runtime setting. A secret
value in flow configuration is always rejected.

One optional, caller-owned, strict JSON runtime source has this exact shape:

```json
{
  "settings": {
    "step-invocation-id": {
      "setting-name": "deployment value"
    }
  },
  "secrets": {
    "step-invocation-id": {
      "secret-name": "deployment secret"
    }
  }
}
```

The source is bounded to 1 MiB, must be valid UTF-8, and is read once. Keys are exact Step
invocation ids and contract field names. Unknown root fields, Step ids, settings, secrets,
wrong sections, wrong types, empty secrets, and duplicate values reject before execution.

Runtime setting precedence is exact:

1. the external runtime source;
2. a flow-authored value in `steps[].config`;
3. the Step-owned setting default;
4. deterministic `RUNTIME_SETTING_REQUIRED` rejection.

Fixed configuration is never overridden externally. A secret has no default or precedence:
exactly one non-empty external string is required.

Binding occurs once before listeners open or startup Steps execute. The bound application
uses that immutable snapshot until shutdown. Reload, polling, signals, mutation, multiple
sources, and fallback lookup are unsupported. Root development runs accept one explicit
runtime file. Packaged startup and CLI runs accept one leading `--runtime <file>` pair.
Creator sends one transient runtime object only with Run; Save and Compile never include it.
Creator enforces the same 1 MiB bound by counting canonical UTF-8 bytes without materializing
a second secret-bearing JSON string.

`StepInput` exposes fixed configuration, settings, and secrets separately. A secret is an
invocation-local `char[]` valid only while the handler runs. Railix clears its invocation
copy after the handler and its bound master copy at shutdown.

## Invariants

- Railix never writes or reserializes secret values.
- Secret values never enter canonical flow JSON, dependency locks, packaged resources,
  events, outputs assembled by Railix, execution history, diagnostics, readiness, logs,
  metrics, URLs, compile responses, or Creator downloads.
- Diagnostics identify only Step ids, field names, expected contracts, and source paths.
- Runtime values are resolved through the existing compiled flow and executor.
- Creator marks Run responses as non-cacheable and clears transient secret inputs after Run.
- Runtime files are caller-owned inputs. Railix never creates, copies, or discovers them.
- Trusted Step code can deliberately copy or expose a secret. Preventing malicious trusted
  code requires future isolation and is not claimed here.
- Plain Java cannot guarantee erasure of parser-created strings, JVM copies, heap dumps, or
  copies made by trusted code. Railix limits owned retention and makes no false zeroization
  claim beyond owned character arrays.

## Consequences

Step developers get one small explicit contract and no provider API. Flow authors see which
values are fixed, deployable, or secret before build. Runtime startup can reject a complete
invalid deployment before side effects. The runtime JSON input necessarily transports
secret material, but Railix does not persist or echo it.

## Rejected Alternatives

Environment variables, JVM properties, secret command-line values, automatic filenames,
embedded or encrypted flow values, overriding all existing configuration, global mutable
context, runtime reload, provider interfaces, distributed stores, and secrets represented
as ordinary flow values are rejected.

## Evidence

The superseded implementation and its tests were deleted with the pre-Creator execution
model. No current source file, test, profile, or example is evidence for this decision.
