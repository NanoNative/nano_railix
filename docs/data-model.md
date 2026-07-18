# Data Model

Nested data is the hardest part of Railix II. It must be first-class.

## Core types

`RailixValue` supports:

```text
null
boolean
number
string
list
object
blob ref
file ref
stream ref
session ref
deferred ref
secret ref
```

## Main namespaces

```text
payload   trigger input
metadata  trigger metadata
ctx       remembered flow context
settings  resolved settings
reply     outgoing protocol reply
refs      resource references
metrics   emitted metric values
audit     custom business audit values
store     explicit value store references
```

## Document view

Nested document:

```json
{
  "customer": {
    "email": "USER@EXAMPLE.COM"
  },
  "orders": [
    {
      "id": "o1",
      "items": [
        { "sku": "A", "qty": 2 },
        { "sku": "B", "qty": 5 }
      ]
    }
  ]
}
```

## Flat path view

Same data as paths:

```text
payload.customer.email = "USER@EXAMPLE.COM"
payload.orders[0].id = "o1"
payload.orders[0].items[0].sku = "A"
payload.orders[0].items[0].qty = 2
payload.orders[0].items[1].sku = "B"
payload.orders[0].items[1].qty = 5
```

## Selector view

Wildcard paths:

```text
payload.orders[*]
payload.orders[*].items[*]
payload.orders[*].items[*].sku
```

## Table/list projection

For repeated structures:

```text
payload.orders[*].items[*]
  sku
  qty
```

The UI should allow switching between document tree, flat paths, selector view, and table/list projection.

## Patch model

A step does not mutate context directly. It returns patches:

```yaml
patches:
  - op: set
    path: ctx.customer.email
    value:
      expr:
        op: lower
        input:
          op: trim
          input:
            path: payload.customer.email
```

Patch operations:

```text
set
remove
append
merge
copy
move
clear
```

## Shape model

Shapes can be inferred from:

- sample JSON
- captured trigger envelopes
- JSON Schema
- OpenAPI
- previous step output contracts
- database result metadata
- manual user definition

Shape inference should record confidence:

```text
required in 12/12 samples
optional, seen in 3/12 samples
conflicting types: string in 9 samples, number in 1 sample
```
