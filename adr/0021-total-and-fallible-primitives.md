# ADR 0021: Total And Fallible Primitive Contract

## Status

Accepted on 2026-07-30 and refined on 2026-08-06. The Primitive contracts below are stable;
only rows marked `Done` are implemented. Standard Field Manipulation version 2 applies the
total host policy defined here.

## Context

Primitive Steps must stay as small as ordinary Java functions while still giving Creator,
the compiler, and the generated application enough information to author and execute them
without hidden conversion or exception behavior. The initial five Primitives are total and
always return `ok`; `text.to-number` is the first fallible Primitive. Collection and percentile
operations add expected `empty` and `invalid` results without exceptions. The containing
ordinary host Step, not a special kind, decides whether those results become graph routes or
ordinary no-write data handling. Every Primitive-style operation is an ordinary `STEP` definition.

The standard catalog must be finite. "Expose Java" is not a contract: it would produce an
unsearchable, unstable mirror of thousands of classes and methods.

## Decision

One Primitive-style operation remains stateless and receives exactly one named `value`. It is an
ordinary mapped graph `STEP` and may also be composed inside a generic nested `STEPS` input.
`NUMBER` means the existing exact JSON number backed by `BigDecimal`; there is no second decimal
type. A date remains an integral UTC epoch millisecond `NUMBER`.

The canonical number domain counts at most 1,024 characters in the unsigned plain magnitude.
The optional minus is not part of that magnitude, so `number.negate` is total. Transport byte
limits still count the serialized minus. Every successful numeric Primitive output is checked
before preview capture or workflow-context mutation.

Primitive classes are:

- `T` (total): declares exactly `ok`; every value matching its input shape returns exactly one
  compatible `value`;
- `F` (fallible): declares `ok, invalid`; `ok` returns exactly one compatible `value` and
  `invalid` returns no output;
- `FE` (fallible with empty): declares `ok, empty, invalid`; non-`ok` outcomes return no
  output.

Expected data never throws. Unknown outcomes, missing or extra outputs, Java `null`, and
runtime exceptions are implementation faults. `InterruptedException` remains cancellation.
Wrong runtime input shape remains a contract rejection, not a business outcome.

Configuration is immutable and resolved by the compiler. Shape, default, and semantic
constraints must be machine-readable in the Step contract and serialized by the catalog so
Creator and the compiler use the same rule. No configured catalog row may become `Done`
before that declaration path exists. Invalid configuration is a deterministic project
diagnostic and never reaches a handler.

### Primitive Host Composition

As a graph node, a Primitive reads and writes only its declared `receives` and `returns` paths.
Its declared outcome follows an ordinary project link. No host or special compiler behavior is
involved.

A `CANDIDATES` input declares ordered tagged value sources. Each candidate may run an ordered
program of total predicate Primitives; a present value is accepted only when that program's final
value is `true`. JSON `null` is present, and projects define their own empty or mismatch predicates
instead of inheriting truthiness rules. A `STEPS` input binds one declared earlier input as its
`ValueSource`; an ordinary host may additionally declare explicit absence or outcome propagation
when that host genuinely owns control behavior. Runtime resolves the generic declaration before
invoking host code, so a handler cannot inject an unrelated source value.

- Every Primitive primary result continues the ordered stack.
- A non-primary result stops the stack, records the Primitive outcome, and returns no composed
  value. Expected data never throws.
- Standard Field Manipulation writes only when the composed value is present. Missing input,
  `invalid`, or `empty` therefore preserves the target and the host follows its ordinary `next`.
- Creator does not synthesize `missing`, `invalid`, or `empty` graph routes for standard Field
  Manipulation. Termination, Choice, fan-in, fan-out, and loops remain explicit control Steps.
- A third-party ordinary Step may explicitly propagate nested outcomes through the same generic
  core contract, but it must declare and connect those outcomes itself; no Step id receives magic
  handling.
- There is no nested `on` object, exception route, implicit fallback, or second execution engine.

Fallible Primitives still expose `ok, invalid`, and collection Primitives may expose
`ok, empty, invalid`; those outcomes remain visible in preview stages and execution traces even
when the standard host continues through `next`.

## Standard Catalog `stdlib-1`

Table rules separated by semicolons are separate public acceptance scenarios. `Done` means
implemented now; `Planned` means contractually accepted but unsupported until its roadmap
checkpoint closes.

### Text

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `text.lowercase` | T | STRING -> STRING | none | `Locale.ROOT`; empty stays empty; Unicode case expansion is retained |
| Done | `text.uppercase` | T | STRING -> STRING | none | `Locale.ROOT`; empty stays empty; Unicode case expansion is retained |
| Done | `text.trim` | T | STRING -> STRING | none | Java `strip`; empty stays empty; internal whitespace is unchanged |
| Done | `text.normalize-space` | T | STRING -> STRING | none | use `Character.isWhitespace`; strip outer whitespace; collapse each internal whitespace run to one ASCII space |
| Done | `text.normalize-nfc` | T | STRING -> STRING | none | Unicode NFC; already-normalized text is unchanged |
| Done | `text.length` | T | STRING -> NUMBER | none | count Unicode code points; empty returns `0`; supplementary characters count once |
| Done | `text.is-empty` | T | STRING -> BOOLEAN | none | only zero code points is true; whitespace is not empty |
| Done | `text.contains` | T | STRING -> BOOLEAN | `needle:STRING`, default `""` | literal case-sensitive containment; empty needle is true |
| Done | `text.starts-with` | T | STRING -> BOOLEAN | `prefix:STRING`, default `""` | literal case-sensitive prefix; empty prefix is true |
| Done | `text.ends-with` | T | STRING -> BOOLEAN | `suffix:STRING`, default `""` | literal case-sensitive suffix; empty suffix is true |

### Number And Boolean

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `number.floor` | T | NUMBER -> NUMBER | none | mathematical floor; integers unchanged; negative fractions move down |
| Done | `number.ceil` | T | NUMBER -> NUMBER | none | mathematical ceiling; integers unchanged; negative fractions move up |
| Done | `number.round` | T | NUMBER -> NUMBER | none | scale `0` with `HALF_UP`; exact halves move away from zero |
| Done | `number.abs` | T | NUMBER -> NUMBER | none | negative becomes positive; zero and positive unchanged |
| Done | `number.negate` | T | NUMBER -> NUMBER | none | reverse sign; canonical zero remains zero; the optional minus does not consume magnitude capacity |
| Done | `number.sign` | T | NUMBER -> NUMBER | none | return exactly `-1`, `0`, or `1` |
| Done | `number.greater-than` | T | NUMBER -> BOOLEAN | `than:NUMBER`, default `0` | exact `BigDecimal.compareTo`; equality is false |
| Done | `number.greater-or-equal` | T | NUMBER -> BOOLEAN | `than:NUMBER`, default `0` | exact comparison; equality ignoring decimal scale is true |
| Done | `number.less-than` | T | NUMBER -> BOOLEAN | `than:NUMBER`, default `0` | exact comparison; equality is false |
| Done | `number.less-or-equal` | T | NUMBER -> BOOLEAN | `than:NUMBER`, default `0` | exact comparison; equality ignoring decimal scale is true |
| Done | `boolean.not` | T | BOOLEAN -> BOOLEAN | none | true becomes false; false becomes true |

### List And Date

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `list.size` | T | ARRAY -> NUMBER | none | empty returns `0`; nested values each count once |
| Done | `list.is-empty` | T | ARRAY -> BOOLEAN | none | only zero elements is true |
| Done | `list.reverse` | T | ARRAY -> ARRAY | none | preserve values exactly; empty and single-element arrays are unchanged |
| Done | `list.sum` | F | ARRAY -> NUMBER | none | numeric values sum exactly; empty returns `0`; any non-number or final sum outside the canonical number domain is `invalid`; intermediate overflow may cancel into a valid final sum |
| Done | `list.min` | FE | ARRAY -> NUMBER | none | return the first existing least numeric value; empty is `empty`; any non-number is `invalid` |
| Done | `list.max` | FE | ARRAY -> NUMBER | none | return the first existing greatest numeric value; empty is `empty`; any non-number is `invalid` |
| Done | `list.percentile` | FE | ARRAY -> NUMBER | `percentile:NUMBER = 95` | require `0..100`; nearest-rank over ascending numbers; `0` selects minimum; empty is `empty`; any non-number is `invalid` |
| Done | `date.is-utc-millis` | T | NUMBER -> BOOLEAN | none | true only for an integral value in signed 64-bit range; fractions and out-of-range values are false |

`list.percentile` uses index `max(0, ceil(percentile * size / 100) - 1)` and returns the
existing value at that index. It performs no interpolation and invents no rounding.

### Conversion, Normalization, And Translation

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `text.to-number` | F | STRING -> NUMBER | none | exact JSON number grammar and bounds are `ok`; whitespace, leading plus, leading zero, NaN, infinity, and malformed text are `invalid` |
| Planned | `text.to-boolean` | F | STRING -> BOOLEAN | none | only exact lowercase `true` and `false` are `ok`; all other text is `invalid` |
| Done | `number.to-text` | T | NUMBER -> STRING | none | canonical plain JSON number; no exponent or redundant trailing zero |
| Planned | `number.to-boolean` | F | NUMBER -> BOOLEAN | none | numeric value `0` is false; numeric value `1` is true regardless of decimal scale; every other number is `invalid` |
| Done | `boolean.to-text` | T | BOOLEAN -> STRING | none | return exact lowercase `true` or `false` |
| Done | `boolean.to-number` | T | BOOLEAN -> NUMBER | none | false returns `0`; true returns `1` |
| Done | `value.wrap-list` | T | ANY -> ARRAY | none | return one-element array; an array input becomes a nested one-element array; null is retained |
| Done | `value.to-json` | T | ANY -> STRING | none | bounded canonical JSON using the production writer; strings remain quoted JSON strings |
| Planned | `text.parse-json` | F | STRING -> ANY | none | explicit bounded JSON normalization; nested values and exact decimals are retained; malformed input is `invalid` |
| Planned | `text.parse-yaml` | F | STRING -> ANY | none | explicit bounded YAML normalization; nested values and exact decimals are retained; malformed input is `invalid` |
| Planned | `text.parse-xml` | F | STRING -> ANY | none | explicit bounded XML normalization; nested values and exact decimals are retained; malformed input is `invalid` |

Formats are never guessed. Parsing uses the same UTF-8, size, depth, scalar, and canonical
number limits as `RailixData`.

Ordinary ports retain shallow outer-shape checks. These four rows opt into the same
machine-readable `ValueRefinement` contract consumed by the compiler, runtime, catalog, and
Creator. Canonical validation checks every programmatic descendant, exact number bounds,
Unicode scalar validity, and the global depth limit. Explicit depth and canonical JSON byte
limits reserve output headroom without changing unrefined Steps.

`list.reverse` preserves canonical depth `64`; `number.to-text` accepts only canonical numbers;
`value.wrap-list` accepts depth `63` and produces depth `64`; `value.to-json` accepts at most
`N` canonical JSON bytes and declares the exact worst-case output bound `2N + 2`. Refinement
traversal is iterative, performs no recursive call-stack growth or sorted container copy, and
rejects invalid output before preview capture or workflow-context mutation. Creator applies
the same constraints to every authored Trigger example and every real built preview case.

[TypeMap](https://github.com/YunaBraska/type-map) was evaluated against this gap. It is
reflection-free and has no external runtime dependency, but its conversion and serialization
APIs do not provide Railix's canonical key order, number domain, maximum depth, output-byte
limit, or explicit invalid outcome. It may remain useful at a future ingress boundary, but an
adapter would add another value path without replacing Railix's contract, so no dependency is
added here.

A useful TypeMap contribution would be a separate opt-in strict JSON API, leaving its current
lenient APIs unchanged. That API would expose explicit success/failure and JSON-null values,
exact `BigDecimal` numbers, duplicate-key and malformed-Unicode rejection, caller-supplied
source/depth/number/output limits, deterministic key ordering, and bounded output measurement.
Railix would still own `RailixValue`, its number domain, `DATA_*` diagnostics, YAML/XML rules,
and Step outcomes.

Railix adopts such an API only when it passes every existing JSON conformance case unchanged,
constructs `RailixValue` directly without an intermediate `Map`/`List` tree, replaces the
single current codec path with less production code, does not increase peak memory, regresses
public-boundary throughput by no more than five percent, and passes `jlink`/`jpackage` smoke
tests. Until then, one independent Railix codec remains safer than an adapter or fallback.

### Matching And Validation

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `value.equals` | T | ANY -> BOOLEAN | `expected:ANY`, default JSON `null` | recursive JSON equality; array order matters; object order does not; numbers compare by value rather than decimal scale |
| Done | `value.not-equals` | T | ANY -> BOOLEAN | `expected:ANY`, default JSON `null` | exact logical inverse of `value.equals` |
| Planned | `value.require-not-null` | F | ANY -> ANY | none | non-null returns unchanged `ok`; JSON null is `invalid` |
| Planned | `text.require-not-blank` | F | STRING -> STRING | none | non-blank returns unchanged `ok`; empty or Unicode-blank text is `invalid` |
| Planned | `number.require-between` | F | NUMBER -> NUMBER | required `min:NUMBER`, `max:NUMBER` | inclusive exact bounds; outside is `invalid`; `min > max` is a project diagnostic |

## Acceptance Derivation

Each accepted Step receives separate public-entrypoint E2Es for every rule in its row plus:

- exact contract metadata and Creator shape filtering;
- successful execution as a mapped graph Step and in a compiled nested `STEPS` program;
- repeated invocation without retained state;
- ordered composition with compatible predecessor and successor shapes;
- wrong authored or runtime shape rejection;
- required, defaulted, wrong-shape, unknown, and semantically invalid configuration where
  applicable.

Each `F` Step additionally receives separate E2Es for `ok` write, `invalid` no-write, unchanged
target, host continuation, actual preview outcome, and compact desktop/mobile authoring. Each
`FE` Step adds separate `empty` no-write and continuation E2Es. Explicit propagation is tested
once through an honestly named generic control host rather than copied into every standard Step.
Common implementation-fault and cancellation cases are tested once at the Primitive contract
boundary.

## Consequences

The catalog is deliberately smaller than the JDK but covers the accepted JSON business-data
families. New standard Primitives require a roadmap change and a new matrix row. Existing
semantics never change silently; incompatible behavior requires a Step version increment.
Projects may explicitly register trusted custom Primitives without waiting for the standard
catalog.

JDK regular expressions are excluded from `stdlib-1`: catastrophic backtracking cannot be
reliably interrupted or deadline-bounded in plain Java. Regex matching requires a separately
accepted bounded implementation before it can enter the standard catalog.

The collection checkpoint may add only the minimum element refinement proven necessary by
aggregation and percentile. It may not introduce a parallel value model or general-purpose
type language.

## Rejected Alternatives

Exceptions, Java `null`, failed-conversion-to-null behavior, automatic trim or case folding,
format guessing, a dedicated Primitive kind or execution engine, nested outcome maps, outcome aliases, runtime
type inference from handler code, one wrapper for every JDK method, and a second lightweight
execution engine are rejected.

## Evidence

- [`StepDefinition.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepDefinition.java)
- [`StepResult.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepResult.java)
- [`ValueRefinement.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/ValueRefinement.java)
- [`ProjectCompiler.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ProjectCompiler.java)
- [`ApplicationGenerator.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ApplicationGenerator.java)
- [`WorkflowRuntime.java`](../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/WorkflowRuntime.java)
- [`PrimitiveSteps.java`](../modules/railix-stdlib/src/main/java/dev/nanonative/railix/stdlib/PrimitiveSteps.java)
- [Roadmap Item 3](../ROADMAP.md)

Thirty-seven `Done` rows are proven: five total rows by Item 2, `text.to-number` no-write
continuation by Item 3 checkpoint 2, four collection rows with explicit `empty`/`invalid`
Primitive outcomes by Item 3 checkpoint 3, and all 27 total-catalog rows in checkpoint 4.
The remaining eight `Planned` fallible rows stay unsupported until Item 3 checkpoint 5 is
accepted.
