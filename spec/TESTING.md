# Railix Testing Checklist

Temporary working checklist while the broader ADR testing policy is being implemented in code.
This file is intentionally more specific and more volatile than the ADRs.

Railix tests must validate behavior only through the public DSL and runtime entrypoints.
No private helper tests. No line-chasing tests. Coverage must be an effect of public behavior.

## Rules

- Prefer `Rail.of(...)` + DSL + `fire(payload[, ctx])`
- Treat tests as library-boundary E2E tests
- Every public operator needs success and failure-path coverage
- Every bug fix needs a regression test
- Runtime input should be seeded through `fire(...)` whenever the behavior is about payload/ctx ingress
- Unsupported behavior must fail predictably and be asserted explicitly
- Performance tests/benchmarks are required for core runtime paths

## Coverage Gate

- Line coverage: `>= 95%`
- Branch coverage: `>= 90%`
- Enforced in Maven `verify`

## Required Case Matrix

### `fire(...)`

- success with `null` payload
- success with scalar payload
- success with `Map` payload
- success with `List` payload
- payload + ctx separation
- repeated `fire(...)` on sealed rail must not leak state
- child/include reuse must not mutate the blueprint

### `step(...)`

- success path
- named step tracking
- terminal stop behavior
- exception -> `UNEXPECTED`
- null step ignored

### `verify(...)`

- passing predicate
- failing predicate
- null predicate
- predicate throws
- custom message/code

### `choose(...)`

- true branch
- false branch
- missing else
- null predicate
- predicate throws

### `map(...)`

- same-path transform success
- missing path
- explicit `null` value
- nested path
- mapper returns `null`
- mapper throws

### `reduce(...)`

- iterable success
- empty iterable
- missing path
- scalar input behavior
- reducer throws
- identity-only result

### `each(...)`

- scalar success
- iterable success
- array success
- map success with `index + key + value`
- empty iterable
- missing path
- invalid path
- null logic
- nested path
- explicit `null` items
- step/verify/fail inside the loop

### `parallel(...)`

- independent payload merge
- independent ctx merge
- failing child must fail the parent
- `UNEXPECTED` outranks `ERROR`
- successful sibling writes stay merged even when the parent fails
- conflicting write behavior

### `parallelEach(...)`

- scalar success
- iterable success
- array success
- map success with `index + key + value`
- empty iterable
- missing path
- null logic
- child failure must fail the parent
- successful sibling writes stay merged even when the parent fails
- conflicting write behavior
- disjoint write behavior
- ctx merge behavior

## Performance Checklist

- baseline rail execution cost
- iteration cost for `each(...)`
- parallel iteration cost for `parallelEach(...)`
- config load/update cost
- logger formatting cost for console/json hot paths
- metrics recording/export hot paths
