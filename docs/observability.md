# Observability

Railix II has built-in observability through run signals.

## RunSignalLog

Run signals are append-only records used by UI, metrics, audit, and debugging.

They do not connect steps.

Examples:

```text
run.started
step.started
step.finished
context.patched
permission.decided
setting.read
metric.emitted
audit.emitted
resource.created
reply.produced
run.finished
```

## Metrics

Metrics are step-first and aggregate upward:

```text
step -> group -> flow -> app
```

Automatic metrics:

```text
step.duration
step.queueTime
step.attempt
step.retryCount
step.success
step.failure
step.cacheStatus
step.inputSize
step.outputSize
resource.createdCount
```

Custom metrics are declared in step contracts and emitted by steps.

Current proven custom-metric slice is intentionally narrow:

```text
railix.std.metrics.MetricEmit emits one contract-declared descriptor: railix.metric.value
descriptor unit is fixed by the contract, not caller input
optional labels are currently limited to source and result
signals are persisted locally in signals.ndjson and queried from run history
```

## Audit

Secret materialization is tracked with `setting.read`. Custom business audit remains separate and step-owned:

```text
railix.std.audit.AuditCustom emits one audit.emitted record
eventName is caller-provided but must be a non-blank string
data is object-only and defaults to {} when omitted
setting.read is not duplicated as audit.emitted
signals are persisted locally in signals.ndjson and queried from run history
```

## Panels

Metric visibility should be customizable with Grafana-like panels:

```text
panel = query + transformation + visualization + options
```

Railix II panels should initially query local run signals. Later, they can query distributed instances or exported telemetry.

## Debugging UX

Every step inspector should answer:

```text
What came in?
What changed?
What went out?
Why did this branch run?
Why did this step fail?
Which settings/secrets/resources were used?
Which metric changed?
Where is the bottleneck?
```
