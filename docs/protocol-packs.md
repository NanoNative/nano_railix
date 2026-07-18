# Protocol Packs

Entry points are generic. HTTP is only one protocol pack.

## Generic trigger model

A trigger produces an `Envelope`:

```yaml
envelope:
  source: http.default
  protocol: http
  payload: {}
  metadata: {}
  reply:
    mode: immediate
```

The flow works with payload, metadata, context, settings, refs, and reply. It should not depend on protocol-specific classes.

## Generic reply model

A flow writes to `reply.*`.

```text
reply.mode
reply.status
reply.metadata
reply.payload
reply.file
reply.stream
reply.session
reply.deferred
```

Modes:

```text
none
immediate
file
stream
deferred
session
broadcast
```

## Protocol adapter responsibility

The protocol adapter translates generic reply into protocol behavior.

Examples:

- HTTP adapter translates `reply.status`, headers, file, stream, deferred response
- CLI adapter writes `reply.payload` to stdout or `reply.file` to path
- MQTT adapter publishes `reply.payload` to a topic
- WebSocket adapter uses `reply.session` or stream refs

## Adding a new protocol

A protocol pack may provide:

```text
SourceStep
ReplyAdapter
protocol metadata shape
resource ref types
sample capture support
traffic panel
settings schema
permissions schema
```

The kernel should not be modified for new protocols unless a genuinely new resource lifecycle primitive is needed.
