# Cache and Store

## Store is internal, operations are steps

The runtime needs an internal store for dependencies, built app artifacts, step modules, compiled steps, resources, run history, and signals.

But users interact with generic store operations as steps:

```text
StoreRead
StoreWrite
StoreDelete
StoreExists
StoreList
StoreExpire
```

## Generic value store

The store can persist Railix values and resource references:

```text
primitive values
nested documents
lists
files
binary blobs
stream references
session references
compiled step artifacts
run outputs
```

Each stored entry has metadata:

```yaml
key: customer/u-123
type: document/json
digest: sha256:...
size: 1234
scope: app|flow|run|step|global
ttl: 24h
createdAt: 2026-06-26T10:00:00Z
permissions: {}
```

## Current proven first slice

Current proven store-pack behavior is intentionally narrow:

```text
filesystem-backed store root from settings.store.rootDir
slash-delimited hierarchical keys such as customer/u-123
StoreWrite reads ctx.store.value with payload.store.value fallback and accepts explicit null as stored data
StoreRead, StoreDelete, StoreExists read ctx.store.key with payload.store.key fallback
StoreList reads ctx.store.prefix with payload.store.prefix fallback, then falls back to key
entry.json replacement uses temp-file move semantics instead of in-place overwrite
corrupt stored JSON fails explicitly at read time
results are patched back into ctx.store.result, ctx.store.deleted, ctx.store.exists, or ctx.store.keys
in-process, named-module, and jlink-packaged launcher proof exists for the first store flow
```

Not proven yet:

```text
metadata sidecars for digest/size/ttl/scope
wildcard or prefix store permission enforcement in the kernel
expiry policies
cross-process locking or concurrent writer safety
non-filesystem store backends
```

## Step cache policies

Caching is step-level.

Default for runtime/event apps: `none`.

Policies:

```text
none
by-input
by-key
ttl
idempotency-key
manual
artifact-only
```

Examples:

```yaml
cache:
  mode: none
```

```yaml
cache:
  mode: ttl
  key: customer-lookup:${ctx.customer.id}
  ttl: PT10M
```

## Dependency cache

Dependency cache is used before and during app build:

- pack manifests
- step modules
- operator definitions
- UI editor metadata
- protocol adapters
- package artifacts

The built app includes only reachable dependencies from the lockfile.
