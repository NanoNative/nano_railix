# Standard Library And Primitive Contracts

## Human Review

Status: **Existing baseline with unified Branch**, updated on 2026-10-31.
The user accepted reviewing related operations as configurable Step families; exact
replacement contracts remain open for the other families. Branching is implemented and locally
checked through the [Branch contract](system-model.md#branch). The ordinary `value.is-type` predicate now supports
type guards without changing those planned families.

Owns: the finite versioned unary catalog, supported rows, composition and acceptance rules.
Other routing contracts live in [system model](system-model.md).

The standard catalog is the default distribution of ordinary portable providers, not a privileged
host implementation. [SYS-032 to SYS-035](system-model.md#portable-step-providers) own identical
integration and later repository extraction. The common export and embedded-bundle path use the
same automatic runtime selection under SYS-037. Definition factories need not be shipped in the
produced application or placed in a separate class. The full local clean gate passed on 2026-11-10.
See [packaging evidence and its coverage limitation](../verification.md#portable-provider-packaging).

Decisions: [ADR 0021](../adr/0021-total-and-fallible-primitives.md). Historical checks: [verification](../verification.md).

## Primitive Contract

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
  Manipulation. Branch selection, Join and loops require explicit functional behavior. Ordinary
  multi-input convergence requires no Merger under [SYS-008](system-model.md#planned-merge-and-join).
- A third-party ordinary Step may explicitly propagate fixed declared nested outcomes through the
  same generic core contract, but it must declare and connect those outcomes itself; no Step id
  receives magic handling.
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

[TypeMap](https://github.com/YunaBraska/type-map) was evaluated against the JSON codec gap. It is
reflection-free and has no external runtime dependency, but its conversion and serialization
APIs do not provide Railix's canonical key order, number domain, maximum depth, output-byte
limit, or explicit invalid outcome. It may remain useful at a future ingress boundary, but an
adapter would add another value path without replacing Railix's contract, so no dependency is
added to the core JSON codec. The separately accepted Transform Step below uses TypeMap only
inside its ordinary provider implementation.

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

### Transform

Accepted, implemented and verified on 2026-11-13.

`railix.transform` is an ordinary provider `STEP`, not a compiler intrinsic. It declares a
readable `source` path, a `convert_to` option and a writable `target` path. Both paths default
to `context.payload`. The default conversion is `number`. A missing source is distinguishable
from JSON null. Both produce `invalid` unless an explicit fallback is configured. No value
is written on `invalid`. The declared `ok` and `invalid` outcomes use normal graph connections.

Each conversion option owns an optional `fallback` configuration value of its result shape.
Fallbacks must already be valid destination values, not values converted again at runtime.
Zero, false, empty text, empty arrays and empty objects are usable fallbacks. A valid conversion
never uses the fallback. Invalid configuration is rejected before execution through the public
configuration-validation contract. Existing source data remains unchanged unless source and
target deliberately select the same field. Writes remain ordinary atomic Step writes.

| Option | Result | Accepted conversion |
| --- | --- | --- |
| `text` | STRING | Existing text, Boolean text or a canonical plain number without exponent notation. Collections are not silently flattened or serialized. |
| `number` | NUMBER | Existing exact number, numeric text parsed as `BigDecimal`, or Boolean as 0/1. No `double` intermediate. |
| `integer` | NUMBER | Number conversion with no fractional component. No silent rounding or Java 32-bit restriction. |
| `boolean` | BOOLEAN | Existing Boolean, exact numeric 0/1, or exact text `true`, `false`, `0`, `1`. Other values are invalid, not false. |
| `list` | ARRAY | Preserve an array, otherwise wrap one non-null JSON value as a single element. Text containing JSON remains text. |
| `object` | OBJECT | Preserve an object. Text is not implicitly parsed. |
| `timestamp_utc_millis` | NUMBER | Integral signed 64-bit epoch milliseconds, numeric text in that same unit, or an ISO offset date-time. No seconds/milliseconds guessing, machine-local timezone or current-date defaults. |
| `json_text` | STRING | Explicit bounded canonical JSON serialization through the existing Railix codec. |
| `json_value` | ANY | Explicit strict JSON parsing through the existing Railix codec. Retain exact numbers and nested JSON types. Root JSON null is invalid. |

TypeMap is a pinned dependency of the provider, not of `railix-core`. The provider bundles its
dependency and license through ordinary provider packaging. An application which does not use
Transform must not retain TypeMap runtime classes. No global conversion registration is changed.
Only JSON-native values enter or leave the Step. Java dates, files, arbitrary objects, non-finite
numbers and cycles cannot become context data. Integer and timestamp are number constraints,
not new context types. Canonical value limits also apply after conversion and before a write.
Container depth includes the target path's containing objects/arrays. A converted value that
does not fit there uses the explicit fallback only if that value also fits, otherwise `invalid`
leaves the target unchanged. It must not first mutate the context and fail during serialization.

This increment does not globally forbid JSON null or replace null with empty text. The user raised
that broader policy on 2026-11-13, but its consequences for external data, optional numeric fields
and existing Trigger defaults remain undecided. Java null remains forbidden as a public value.

Acceptance must cover real generated requests, all conversion options, absent/null/invalid sources,
fallback presence and absence, exact large numbers, invalid fallback configuration, repeated use,
explicit JSON parsing versus list wrapping, ordinary provider import and standalone packaging.
Creator evidence must include insertion, mixed-type Examples, source/target selection, target changes,
fallback editing/removal, save/reopen and desktop/narrow layouts. These checks pass locally through
generated applications, an independently compiled provider and real Creator browser journeys at
1280 and 320 pixels. `./mvnw clean verify` passes, including packaged-launcher and production tests.
A separate smoke test built the Transform application with the packaged Creator and executed both
conversion and fallback using its bundled Java runtime. Final review has no open findings.

### Matching And Validation

| Status | Step | Class | Shape | Config | Rules |
| --- | --- | --- | --- | --- | --- |
| Done | `value.equals` | T | ANY -> BOOLEAN | `expected:ANY`, default JSON `null` | recursive JSON equality; array order matters; object order does not; numbers compare by value rather than decimal scale |
| Done | `value.not-equals` | T | ANY -> BOOLEAN | `expected:ANY`, default JSON `null` | exact logical inverse of `value.equals` |
| Done | `value.is-type` | T | ANY -> BOOLEAN | `type:OPTIONS` selecting `null`, `boolean`, `number`, `string`, `array` or `object`; default `string` | exact JSON shape, no coercion; JSON null is a value, not an absent field; number includes integral and fractional values |
| Planned | `value.require-not-null` | F | ANY -> ANY | none | non-null returns unchanged `ok`; JSON null is `invalid` |
| Planned | `text.require-not-blank` | F | STRING -> STRING | none | non-blank returns unchanged `ok`; empty or Unicode-blank text is `invalid` |
| Planned | `number.require-between` | F | NUMBER -> NUMBER | required `min:NUMBER`, `max:NUMBER` | inclusive exact bounds; outside is `invalid`; `min > max` is a project diagnostic |

To guard a type-specific transformation in a Choice, put `value.is-type` in an earlier AND
condition than that transformation. Existing short-circuit evaluation skips later conditions
on mismatch or an absent field. The guard is an ordinary unary Step, also usable as a graph
node or in a nested predicate program; it does not change compiler inference or convert data.

## Planned Configurable Families

The goal is fewer concepts for users, not fewer useful capabilities. Related operations should
share a configurable definition when their input, output and failure contracts fit. A shared
Creator presentation alone does not justify changing stored Step IDs or runtime behavior.

| Family | Existing behavior to preserve | Proposed direction / remaining decision |
| --- | --- | --- |
| Numeric comparison | Greater/less than, inclusive variants, exact decimal comparison. | One operator selection; decide IDs, defaults and migration before replacing rows. |
| Numeric collection statistics | Sum/min/max, with different empty and invalid outcomes. Average is not implemented. | Configurable aggregate or summary outputs such as count/sum/min/max/average; agree output shape, empty input and average precision/rounding. |
| Rounding | Floor, ceiling and half-up rounding. | One rounding operation with declared mode; preserve existing negative-number and tie behavior. |
| Text matching | Contains, starts-with, ends-with. | One match operation with mode and ordinary operands; do not silently introduce regex or change case rules. |
| Text case | Locale-independent upper/lower conversion. | One case mode if the declaration remains simpler than separate operations. |
| Branching | Filter, Choice, Switch, Split behavior. | `railix.branch` replaces the three separate catalog entries, without aliases or migration. First/matching/all selection, sequential/parallel execution, shared context by default and per-output snapshot copies use public Step contracts. Implemented and locally checked. See the [Branch contract](system-model.md#branch). |

Except for the Branch increment, these are consolidation candidates, not newly supported Steps. A summary object is not a
drop-in replacement for a scalar sum. Do not adopt Java double-based statistics and lose the
existing exact number domain. A non-terminating average needs an explicit precision/rounding
rule, not accidental `BigDecimal.divide` exceptions. Empty arrays need a declared result for
each selected output, rather than invented minimum/maximum values. `list.size` still accepts
nonnumeric elements and must not acquire a numeric scan just to share a statistics family.

Options and their valid shapes belong to the ordinary Step definition, consumed by Creator,
compiler and third-party Steps alike. No special family names or stdlib IDs in those consumers.
For branching, execution settings and extension-supplied synchronous/asynchronous logic must
also follow the public contract in [SYS-025](system-model.md#planned-merge-and-join), not a
standard-library-only mode selector or runtime escape hatch.
Before implementation, agree the first family, compatibility policy, scalar versus multiple-output
behavior, and how options affect outcomes and available connections. Branching explicitly retires
the old definitions without migration. Other families have no selected compatibility policy yet.

Acceptance is planned through the public catalog, Creator and generated application: preserve
each existing operation's relevant scenarios, exercise every supported mode and invalid setting,
prove the selected compatibility policy, and verify a third-party configured Step uses the same
declaration path. Measure any changed collection hot path against the current
operation; consolidation must not compute unused aggregates or introduce new runtime machinery
without a demonstrated need.

Planned Join and Limiter definitions must use the same public contracts as installed third-party
Steps. Their execution requirements and open decisions belong to
[SYS-024 to SYS-031](system-model.md#planned-merge-and-join). Compact connector artwork is not a
new Step kind. There is no supported Join/Limiter catalog row or chosen ID/schema yet. Plain
convergence needs no library Step, and library names must not select privileged runtime behavior.

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

## Develop A Unary Step

"Primitive" is a compact Creator presentation and product role, not a `StepDefinition.Kind`.
A small value operation is an ordinary `STEP` with one receive, one return, and an explicitly named
primary outcome when `next` is not suitable:

```java
public final class Lowercase {
    private Lowercase() {
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        return StepResult.outcome("ok").output(
                "value",
                RailixValue.string(input.string("value").toLowerCase(Locale.ROOT))
        );
    }
}

StepDefinition.named("text.lowercase", "1")
        .primaryOutcome("ok")
        .receive("value", ValueShape.STRING)
        .returns("value", ValueShape.STRING)
        .implementation(Lowercase.class);
```

Register definitions explicitly in a `StepCatalog`; Railix never discovers classes through
reflection. The default outcomes are App `start`, Trigger `next`, and ordinary Step `next`.
Built-in and bundle Steps use this same static PROCESS signature. Old bundles using
`StepHandler.run` must be rebuilt; there is no second legacy execution path. Configuration
contains compiled literals, paths and selected options, while `input` supplies invocation values
and compiled nested programs. Constructors are never a Step startup hook.
`.primaryOutcome("ok")` deliberately replaces `next` for the unary operation above. Additional
`.outcome(...)` calls declare explicit non-primary results such as `invalid` or `empty`.
Optional `.displayName(...)` and `.searchTerms(...)` values affect only Creator presentation and
search; compiler and runtime behavior remains entirely in the functional contract.

The catalog above owns supported unary behavior; ADR 0021 records the rationale. Immutable locked Step bundles are implemented; repository acquisition and
the reusable Step template remain roadmap Item 4.

## Evidence

- [`StepDefinition.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepDefinition.java)
- [`StepResult.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/step/StepResult.java)
- [`ValueRefinement.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/value/ValueRefinement.java)
- [`ProjectCompiler.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ProjectCompiler.java)
- [`ApplicationGenerator.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/ApplicationGenerator.java)
- [`WorkflowRuntime.java`](../../modules/railix-core/src/main/java/dev/nanonative/railix/core/project/WorkflowRuntime.java)
- [`PrimitiveSteps.java`](../../modules/railix-stdlib/src/main/java/dev/nanonative/railix/stdlib/PrimitiveSteps.java)
- [Roadmap Item 3](../roadmap.md)

Thirty-seven `Done` rows are proven: five total rows by Item 2, `text.to-number` no-write
continuation by Item 3 checkpoint 2, four collection rows with explicit `empty`/`invalid`
Primitive outcomes by Item 3 checkpoint 3, and all 27 total-catalog rows in checkpoint 4.
The remaining eight `Planned` fallible rows stay unsupported until Item 3 checkpoint 5 is
accepted.
