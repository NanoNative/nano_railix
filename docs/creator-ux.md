# Creator UX

The Creator is an IDE-like app for building Railix II apps.

## Main screens

```text
Project dashboard
Flow graph
Step palette
Step inspector
DataWorkbench
Settings tree
Run/debug timeline
Metrics panels
Audit viewer
Packaging screen
Dependency cache browser
```

## Run button behavior

The Run button should:

1. validate the app plan
2. resolve reachable dependencies from local cache
3. build the current app image/dev executable
4. start the real app process in dev mode
5. attach to its control endpoint
6. trigger selected manual/CLI/test input
7. stream run signals into the Creator UI

Creator should not simulate the runtime unless explicitly in preview mode.

## Step inspector

A step inspector should show:

- contract
- inputs
- outputs
- outcomes
- settings used
- secret access requests
- permissions
- timeout
- retry policy
- cache policy
- resource limits
- metric definitions
- audit events
- UI-specific editor metadata

## DataWorkbench

See `docs/dataworkbench.md`.

## Feedback loop

The user edits a flow, presses Run, sees:

- live graph activity
- timings
- traffic/envelopes
- context diffs
- metrics
- audit events
- bottlenecks
- reply output

This must feel immediate. Slow feedback loops are how tools become enterprise rituals.
