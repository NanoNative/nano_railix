# Vision

Railix II is a step-based app/workflow platform designed to make heavy infrastructure optional.

The user should not have to think in containers, services, ingress, Prometheus, Grafana, pipelines, queue workers, sidecars, or YAML forests.

The user should think:

```text
I have steps.
I connect them.
I map data between them.
I run the app.
I see what happens.
I package it.
It scales when needed.
```

## What Railix II replaces first

Railix II should first replace:

- local automation scripts
- CI/CD workflows
- internal tools
- data transformations
- webhook handlers
- small integration services
- scheduled jobs
- manual operational workflows
- simple event processors

## What Railix II is designed to grow into

Railix II is designed so multiple instances can later cooperate:

- shared step execution
- remote cache
- distributed run queues
- instance discovery
- capability advertisement
- embedded dashboards
- local and remote metrics aggregation
- resilient store strategies

The first implementation does not need all of this, but the contracts must not block it.

## Product identity

Railix II is not merely a CI tool. CI is one standard pack and one use case.

Railix II is closer to:

```text
visual workflow app builder
+ local-first runtime
+ deterministic package builder
+ internal tool shell
+ metrics/audit dashboard
+ future distributed step executor
```

The center remains the same:

```text
Step = reusable standalone execution unit
Flow = connection description
App = packaged executable containing flows, settings, step packs, UI, and runtime
```
