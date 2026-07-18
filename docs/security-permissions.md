# Security and Permissions

Railix II should make requested permissions clear before execution.

## Permission model

A step contract declares requested permissions:

```yaml
permissions:
  requested:
    settings.read:
      - settings.database.user
    settings.secret:
      - settings.database.password
    store.read:
      - customer/*
    store.write:
      - invoice/*
    net.outbound:
      - api.example.com:443
```

The app/flow/group/step grants permissions:

```yaml
permissions:
  grants:
    settings.read:
      - settings.database.user
    settings.secret:
      - settings.database.password
```

Runtime records decisions:

```yaml
signal: permission.decided
step: write-invoice
permission: settings.secret
resource: settings.database.password
decision: granted
```

## Secrets

Secrets are protected settings. Materialization is automatic-audited.

## Local process isolation

Default local execution is deterministic workspace isolation, not a perfect security sandbox.

Harder isolation can be added by packs/runners:

```text
container runner
WASI runner
OS-specific sandbox runner
remote trusted runner
```

The permission contract should be stable across runner types.
