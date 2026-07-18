# Concepts

## App

A packaged executable containing flows, step dependencies, settings schema, UI assets, runtime, and lockfile.

## Flow

A graph of step invocations and edges.

The flow describes what comes next. The flow is not the execution unit.

## Step

The main architectural unit.

A step has:

- contract
- inputs
- outputs
- outcomes
- settings
- permissions
- timeout
- retry policy
- cache policy
- resource requirements
- metric definitions
- audit definitions
- optional UI editor metadata

A step may be tiny or large. A step is the future unit that can be outsourced to another Railix instance.

## Group

A UI/policy grouping overlay. It may define default settings, permissions, timeout, retry policy, resource limits, and metric panel presets, but it does not hide individual steps from the scheduler.

## Trigger / SourceStep

A step that creates an `Envelope` from an entry point such as manual UI, CLI, HTTP, MQTT, cron, queue, file watch, socket, webhook, or a custom protocol pack.

## Envelope

Generic input wrapper created by a trigger.

Contains:

- source id
- protocol id
- payload document
- metadata document
- optional reply channel
- resource refs

## Context

A per-run structured document that carries remembered values through the flow.

Steps receive a context snapshot and return patches. The runtime applies patches and records diffs.

## Settings

One inherited tree for config and secrets.

Secret values are protected settings, not a separate system.

## Reply

Generic output model consumed by the protocol adapter that created the envelope.

A reply may be immediate, file, stream, deferred, session, broadcast, or none.

## RunSignal

Observation record emitted during execution.

Examples:

- step started
- step finished
- context patched
- metric emitted
- audit event
- permission decision
- reply produced
- resource created

Run signals are not execution transport.
