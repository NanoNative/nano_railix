# ⏳ TODO — EPIC-04: Default Actors (HTTP/CLI/File)

> **Priority:** P1

## Outcome

Provide default actors similar to Nano services, but pipeline-first:
- HTTP receiver
- HTTP sender
- File watcher
- CLI receiver
- CLI executor

All actors must integrate with:
- global config
- graceful shutdown phases
- issues registry + metrics

## Tickets

| Ticket | Status | Description |
|--------|--------|-------------|
| [TICKET-030: HTTP receiver](./TODO-TICKET-030-http-receiver.md) | ⏳ TODO | build rails from requests, stop-accepting on shutdown |
| [TICKET-031: HTTP sender](./TODO-TICKET-031-http-sender.md) | ⏳ TODO | JDK HttpClient wrapper + retries/backoff |
| [TICKET-032: File watcher](./TODO-TICKET-032-file-watcher.md) | ⏳ TODO | WatchService-based, grouped watch/unwatch |
| [TICKET-033: CLI receiver](./TODO-TICKET-033-cli-receiver.md) | ⏳ TODO | stdin → rails |
| [TICKET-034: CLI executor](./TODO-TICKET-034-cli-executor.md) | ⏳ TODO | exec commands, structured results |
