# Distributed Model

Railix II is local-first but designed for multi-instance scaling.

## Goal

Users should not need to manage containers, services, ingress, Prometheus, Grafana, network policies, and deployment glue for many workflow-shaped systems.

## Future instance model

Each instance can advertise:

```text
instance id
runtime provider identity
installed pack identity when a deterministic runtime artifact contract exists
supported resource refs
available protocol adapters
runtime version
OS/arch
current load
local cache digests
trust level
settings profile
```

## Remote execution principle

Remote execution is an optimization, not a semantic difference.

A flow must behave the same locally and remotely, except for performance and available permissions/capabilities.

## Step outsourcing

Because Step is the architectural unit, a future scheduler can outsource single steps:

```text
local step
remote step
queued step
retry elsewhere
cache hit elsewhere
```

## Store and metrics

Future store modes:

```text
local only
shared file/database
replicated
sharded
remote cache
```

Future metrics modes:

```text
local run signal log
remote node aggregation
export to external backend
```
