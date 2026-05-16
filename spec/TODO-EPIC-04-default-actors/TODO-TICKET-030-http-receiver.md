# ⏳ TODO — TICKET-030: HTTP receiver actor

> **Status:** ⏳ TODO · **Priority:** P1

## Goal

Provide an actor that:
- accepts HTTP requests
- constructs a rail per request
- runs user handler steps
- writes response

## Shutdown requirement

Implements “stop accepting” so shutdown prevents new rails from being created.
