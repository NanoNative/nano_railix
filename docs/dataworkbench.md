# DataWorkbench

DataWorkbench is the UI/editor model for building real business logic without custom Java code.

## Purpose

It lets users:

- map nested payloads to context
- remember values
- transform fields
- validate required structure
- route based on conditions
- loop over lists
- aggregate data
- build nested replies
- preview before/after output

## UI layout

```text
+------------------+--------------------------+------------------+
| Source Tree      | Mapping / Operators      | Target Tree      |
| payload          | payload.x -> trim -> ctx | ctx              |
| ctx              |                          | reply            |
| settings         |                          | refs             |
+------------------+--------------------------+------------------+
| Flat paths / selector builder / live preview / diff           |
+---------------------------------------------------------------+
```

## Main user actions

```text
Pick field
Remember as context path
Map to target path
Add transform operator
Add validation rule
Create repeated mapping scope
Create route condition
Create aggregate
Build reply tree
Preview with sample data
```

## Operator pipeline

Operators are structured contracts, not arbitrary scripts.

Example:

```yaml
expression:
  op: lower
  input:
    op: trim
    input:
      path: payload.customer.email
```

Rendered as:

```text
payload.customer.email | trim | lower
```

## Repeated mapping

Example:

```yaml
repeat:
  selector: payload.orders[*].items[*]
  as: item
  parentAliases:
    order: payload.orders[*]
  mappings:
    - from: item.sku
      to: ctx.items[*].sku
    - from: item.qty
      to: ctx.items[*].quantity
    - from: order.id
      to: ctx.items[*].orderId
```

## Standard data steps

Top-level graph should stay compact. Use few powerful data steps:

```text
DataTransform
DataValidate
DataRoute
DataForEach
DataAggregate
DataMerge
DataTemplate
```

Do not create one top-level step for every tiny operation like `trim`, `lower`, `abs`, or `equals`. Those are operators inside data steps.
