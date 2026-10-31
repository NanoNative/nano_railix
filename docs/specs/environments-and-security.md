# Environments And Security

## Human Review

Status: **Typed configuration implemented; security design planned**, updated on 2026-10-31. The user accepted the
local Git-backed default, optional external managers, usability, early rejection, immutable
deployed identities/permissions, credential rotation and compliance-support goals, not a
cryptographic architecture or implementation. The latest clarification rejects live user
management; it supersedes the earlier proposal for a mutable account/role service.
Runtime settings and secrets remain unsupported as recorded in [ADR 0019](../adr/0019-runtime-settings-and-secrets.md).

On 2026-10-30 the user requested a small Settings tab for typed project key/value entries,
followed by Step input references usable in transformations. Ordinary configuration is fixed
for a build; Steps read it without mutating it. The user explicitly reaffirmed SEC-009 as
essential: necessary credential rotation remains distinct from build-fixed configuration.
After reviewing storage, reference syntax, build lifetime and concurrent editing, the user
authorized proceeding with the [first slice](#typed-project-configuration-first-slice).
Configuration uses immutable canonical maps; edits construct replacement snapshots and use
revision-checked project publication. The first slice is implemented and locally verified;
the full publication gate remains separate from the focused evidence below.

Owns: environment configuration, secret access and lifecycle, platform identities and ingress
policy boundaries. [System model](system-model.md#accepted-platform-boundaries) owns artifact
capability inclusion; [observation](observation.md) owns management/observation behavior.
[Database access](database-and-data-access.md) owns the new data-administration requirements;
its serving boundary must enforce permissions even when a caller bypasses Creator's UI.

## Accepted Goals

- **SEC-001:** Adding projects MUST NOT require a user to create, track and unlock a separate
  cryptographic identity for each project. The user's example is one person working across
  500 projects. Railix MUST manage any necessary internal encryption keys rather than making
  per-project key administration the ordinary workflow. SOPS is inspiration, not a mandatory
  format, dependency or key-management design.
- **SEC-002:** Ingress policies SHOULD reject disallowed traffic at the earliest boundary where
  their required information is trustworthy and the supported transport exposes enforcement.
  The supported rejection stage and already-incurred work MUST be explicit; an HTTP request
  filter MUST NOT be described as preventing TCP acceptance or TLS negotiation.
- **SEC-003:** The default secret-management workflow MUST work locally with versioned,
  text-based encrypted files suitable for Git. Collaborators MUST be able to exchange revisions
  through a repository and review/restore file versions without a mandatory vault service.
  Git synchronization is not instantaneous revocation or automatic deployment.
- **SEC-004:** External secret managers MAY be configured, but MUST NOT be required for the
  default local/Git workflow. Provider selection and supported integrations remain open.
- **SEC-005:** Repository-managed configuration, encrypted secret values and required sharing
  metadata MUST use text formats, not a proprietary binary vault/database or built executable.
  Plaintext secrets, usable private identity keys and unlock credentials MUST stay outside Git.
  Text encoding of ciphertext does not make it plaintext; its authenticated encryption and
  versioned representation still need a defined contract.
- **SEC-006:** A running application's Railix access permissions MUST be fixed by its build.
  Changing those permissions MUST require rebuilding and deploying a replacement artifact;
  neither a management request nor reloading a mutable policy file may change them in place.
  Creator editing of the next policy revision does not change an already running instance.
- **SEC-007:** Security and compliance support MUST be specified as scoped controls with
  requirement-to-evidence mappings and explicit operator responsibilities. Railix MUST NOT
  present a framework name, selected setting or passing Example suite as proof that an
  application or organization is compliant or certified.
- **SEC-008:** Railix identities and access grants MUST be declared statically for compilation,
  not maintained through a running user-registration, account-management or role-editing service.
  Changing compiled identity membership or grants requires a rebuilt replacement under SEC-006.
  This does not remove authentication or the checks on each protected operation.
- **SEC-009:** Included security capabilities MUST support necessary credential/key rotation
  without changing compiled identity membership or permissions. Rotation MUST NOT introduce new
  authority through externally mutable roles or claims. Trust, renewal and failure details remain
  to be specified; private keys and secret values are not compiled into the artifact.

### Immutable Permissions And Revocation

SEC-006 records the user's explicit 2026-09-19 decision. It removes live permission mutation,
not authentication or per-operation authorization. Old instances retain their compiled policy
until replaced or stopped; deployment must not report revocation complete while those instances
still serve the affected access. Emergency containment, rollout deadlines and rollback admission
therefore need a contract before production. Immutability alone does not eliminate attack vectors.

The user resolved the earlier scope/rotation questions on 2026-09-19: no live user-management
model; static declarations with necessary dynamic security rotation. Do not retain the earlier
suggestion of a Railix-supplied mutable customer-account subsystem as accepted scope. Editing
ordinary application records is not an implicit grant to modify compiled security authority.
Static identity declarations and ciphertext/key-grant files need an agreed authoring format;
this decision does not approve an account-editor UI or a new credential protocol.

### Security And Compliance Support

The user requested GDPR, ISO, DORA and OWASP support. The following is a proposed mapping,
not a selected certification scope or legal determination for every deployment:

| Reference | Railix design work to specify | Boundary |
| --- | --- | --- |
| [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng), especially Articles 5, 25 and 32 | Data minimization, restricted defaults, retention/deletion, access/export, redaction and recovery across records, Examples, traces, logs and backups. | Legal basis, controller/processor duties and applicable rights depend on the operator and processing; encryption alone is insufficient. |
| [ISO/IEC 27001](https://www.iso.org/standard/27001) | Risk/control evidence, access review, change/dependency inventory, incident and recovery procedures. | This is a proposed interpretation of "ISO"; it concerns an organizational ISMS, not a binary's automatic certification. |
| [DORA](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32022R2554), Articles 1 and 2 | Operational-resilience evidence, incident information, recovery tests and dependency/third-party visibility. | Financial-sector and ICT-provider applicability requires assessment; Railix cannot fulfill organizational reporting or contractual duties merely by compiling an app. |
| [OWASP ASVS](https://owasp.org/projects/asvs) | Versioned, applicable security requirements and verification evidence, backed by threat modeling and boundary tests. | Proposed verification baseline, not an OWASP certification claim; version, level and exclusions need agreement. |

The next security slice must identify assets, trust boundaries and abuse cases before selecting
controls. Audit retention, payload redaction, export/erasure and backup restoration need explicit
failure and permission rules. Git history is not an erasable personal-data store. Dependency
provenance, vulnerability handling and reproducible artifact identity belong in release evidence.
Keep compliance reports and authoring tools outside production unless a selected capability needs
runtime enforcement; essential enforcement cannot be removed merely to meet a size target.

## Typed Project Configuration: First Slice

This slice establishes reusable, non-secret values in the functional project. It does not
implement secret storage or claim SEC-009 coverage. Named environments, OS environment imports,
configuration expressions, live updates and mutable global variables are outside this slice.
The following CFG requirements and representation define the initial implementation contract.

### Behavior

- **CFG-001 — Project ownership:** Settings MUST expose a Project configuration tab with
  Key / Type / Value entries for the current project. Values MUST persist with the functional
  project, not global Creator preferences or presentation metadata. A project with no entries
  MUST retain its existing behavior. The tab MUST identify values as non-secret and build-fixed.
- **CFG-002 — Canonical types:** The first slice MUST support String, Number and Boolean using
  Railix's existing canonical values. Type selection MUST be explicit in the editor; no type
  inference from string contents or implicit conversion is permitted. Numeric values MUST
  retain their exact value through browser editing, API transport, saving and compilation,
  using the existing exact-number path and numeric limits. Missing entries, empty strings,
  zero and false MUST remain distinct. Configuration String values MUST be empty or contain
  only printable ASCII characters from `!` through `~` (U+0021–U+007E). Whitespace, control
  characters and non-ASCII characters MUST be rejected without trimming or substitution.
  Array, Object and Null entries are deferred; existing Step literal inputs and their String
  contents remain unchanged.
- **CFG-003 — Input binding:** A compatible canonical JSON input MUST accept a project
  configuration reference, including inputs owned by selected options, candidates, matchers
  and nested Steps. Built-in and third-party definitions MUST use the same binding rule.
  References MUST NOT target writable paths, receive/return ports, option selectors, outcome
  names or whole nested programs. Steps MUST receive ordinary resolved typed inputs through
  their existing contract, without configuration lookup APIs or access to the entire map.
- **CFG-004 — Validation:** Compilation MUST reject unknown keys, unsupported entry types,
  invalid configuration names or String characters, references to undeclared or ineligible
  inputs, and values that fail the consuming input's existing shape/range constraints.
  A reference and a literal for the same input MUST be
  rejected as ambiguous. An explicit broken reference MUST NOT fall back to an input default,
  even for an optional input. Diagnostics MUST identify the configuration key and owning input
  path when applicable. Unused entries MUST still be validated as supported canonical values.
- **CFG-005 — Build lifetime:** References MUST resolve during compilation into immutable
  values. Development and production builds MUST use the same resolution rules. Editing a
  value changes a subsequent build; it MUST NOT mutate an existing application or its active
  Flows. Transforming a configured value produces Flow data, leaving the configuration intact.
  Configuration snapshots MUST be immutable; concurrent readers retain their snapshot while
  edits validate a replacement through the revision-checked project boundary.
  SEC-009 credential rotation MUST NOT be routed through this compile-time literal mechanism.
- **CFG-006 — Editing:** Configuration edits MUST use the existing revision-checked project
  edit/save/build path. Creator MUST retain an invalid edit as a visible draft while preserving
  the last accepted project and application. Removing a referenced key or changing its type
  incompatibly MUST identify affected inputs and reject the invalid save. The initial UI MUST
  require rebinding consumers before removing or renaming a referenced key; no automatic
  rename propagation is included. Valid edits retain the existing distinction between saved
  source and successful activation: a failed build/activation MUST NOT appear as a live update.
  Changing a comparison MUST preserve configuration references under the same compatibility
  rules as literal operands. Changing a candidate option MUST reset that option's owned
  literals and references while preserving its condition and outcome identity.

### Representation And Compiler Boundary

Add an optional `configuration` object to `railix.project.json`. Its keys are unique,
case-sensitive names containing one or more ASCII letters (`A-Z`, `a-z`), digits (`0-9`),
underscores (`_`) or hyphens (`-`), matched exactly. No leading-letter rule is imposed.
Other punctuation, whitespace and control characters are not permitted in names. Values
use native canonical JSON types; there is no second stored type tag that could disagree with
the value. The explicit Type control selects the value representation when authoring.
String values may contain punctuation such as `@`, `/`, `\` and quotes. Use ordinary JSON
escaping: `/` needs no escape; a backslash is stored as `\\` and a quote as `\"`.
Validate the decoded characters: JSON escapes for a newline, carriage return, tab or space
do not make those characters valid. Do not pre-escape values before serializing JSON.

Illustrative project-root member:

```json
"configuration": {
  "tax_rate": 0.20,
  "account_code": "00123",
  "feature_enabled": false
}
```

Add an optional `input_sources` object beside `inputs` wherever the recursive grammar owns
named inputs: a graph node, nested Step invocation, or selected option/candidate/matcher.
Each entry names a declared canonical JSON input and contains exactly one `configuration`
key naming the project entry. An illustrative fragment for an input named `rate` is:

```json
"inputs": {},
"input_sources": {
  "rate": { "configuration": "tax_rate" }
}
```

Keep references separate from literal data. An ordinary literal object containing a field
named `configuration` or `input_sources` MUST keep its original meaning. No reserved string
interpolation or object-marker detection is introduced inside literal values. Missing
`input_sources` preserves current default/required-input behavior. Other binding sources are
not introduced by this proposal.

The compiler resolves references through the same validation used for literal JSON inputs
and lowers them to the existing literal binding. Preserve the authored key reference in the
saved project; do not replace it with a copied literal there. This needs no new runtime
binding, Step handler signature, global configuration service or expression evaluator.

Creator's input control offers Literal / Project configuration for eligible fields and shows
the selected key, type and value. Suggestions use the input's declared compatibility rules;
server compilation remains authoritative. Existing context-field bindings remain separate.
Configuration references can supply transformation parameters or a candidate's owned value
input; the enclosing Step continues to determine execution and data flow.

Extend the existing project edit payload with per-key configuration updates; an edit-payload
JSON null deletes an entry, while saved configuration Null values remain unsupported. Reuse
the existing revision conflict and authenticated mutation boundary, not `/api/settings`, which
owns global Creator preferences. Derive references/usage from the current authored project;
do not persist another dependency index.

These fields are additive to the current project format. Updated tooling MUST read
existing projects without rewriting their literals or requiring configuration entries. Older
tooling may reject projects using the new fields; forward compatibility is not promised.
Interpret these fields only at the declared structural boundaries; previously legal literal
data is not reserved as new syntax.

### Acceptance And Delivery

Deliver one vertical slice: author an entry, bind existing Step inputs, save/reopen, compile
and execute. Separate UI-only work is not completion. Arithmetic in
`subtotal * tax_rate + subtotal` remains Step behavior: this slice supplies `tax_rate`; it does not add a formula
language or certify that a particular arithmetic Step is already available.

| Requirement | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| CFG-001, CFG-003 | Save one configuration key, bind two Steps, reopen the project and run without Creator; verify global preferences do not affect the result. | Creator HTTP/browser save and reopen checks; standalone generated applications exercise a shared key in two Steps. |
| CFG-002 | Round-trip `00123` as String, empty String, URL/path punctuation and escaped quotes/backslashes, false, zero, and a supported Number beyond JavaScript's exact integer range through the actual UI and build. Reject invalid names and whitespace/control characters in configuration Strings. | Compiler, HTTP and browser configuration checks; the large Number is consumed by a real Example and a generated production application. |
| CFG-003, CFG-004 | Bind a value to a third-party input and a nested transformation parameter; exercise an incompatible shape and an existing numeric range restriction. | Compiler checks cover nested candidates, transforms and matchers; a locked third-party option executes with the exact configured Number. |
| CFG-004 | Compile a missing reference on an optional/defaulted input, a literal/reference conflict, unsupported entry type and unknown target input; identify the owning key/input. | Compiler rejection tests cover these cases, plus blank/duplicate/exact keys and ineligible path inputs. |
| CFG-003, CFG-004 | Compile existing object literals containing the field names; retain their literal values and preserve existing projects. | Literal-name compatibility check and the existing compiler/editor regression suites. |
| CFG-005 | Build A, edit a referenced value and build B; A retains the old result while B uses the new value. A transformation result does not change the configured value. | Generated A/B applications retain their values; transforming one shared-key use leaves the second unchanged. Configured/literal generated sources are equal in both build modes. |
| CFG-006 | Reject an in-use deletion/type change, retain its visible draft, and reject a stale concurrent edit without overwriting accepted source. Exercise saved-source/activation-failure reporting. | HTTP/browser rejection and draft recovery; concurrent edits retain every accepted key; existing saved-source recovery uses the same project save path. |
| CFG-006 | Replace a comparison without losing a compatible configuration operand; replace a candidate option without retaining its old owned references or losing condition-owned references. Save/reopen and execute the resulting project. | Browser regressions cover both comparison directions, an incompatible operand, Current/Field candidate options, condition-reference retention and returning to Literal defaults. |
| CFG-001, CFG-006 | Edit a displayed key after a saved view reorders entries; import a different project after an invalid configuration draft. Keep inactive configuration rows out of unrelated editor renders. | Browser regressions cover value/type/removal identity, import draft/message reset, and lazy row construction with 1,000 entries. |

Exact local commands, coverage boundaries and publication-gate gaps are recorded in the
[dated verification checkpoint](../verification.md#typed-project-configuration-2026-10-31).

Implementation of this described configuration slice was authorized on 2026-10-30 after the
design review. The user explicitly confirmed immutable maps and coordinated edits. This
authorization does not extend to runtime configuration, environment profiles or secret delivery.
On 2026-10-31 the user narrowed configuration names to letters, digits, underscores and
hyphens, and confirmed that configuration String values allow punctuation but reject
whitespace and control characters. The character rules above define this restriction.

## Proposed Direction, Not Accepted Architecture

One user-facing identity can authorize many projects while automatically managed encryption
keys isolate secret scopes. Project/environment permissions are distinct from decryption-key
administration. Granting access need not copy another user's private key. Applications need
their own scoped access rather than a human password or the user's entire secret collection.

The default is now local encrypted text files shared through Git, not the previously proposed
always-available team authority. A possible implementation uses locally protected user/device
identity keys and automatically managed secret-scope keys, with encrypted key grants stored
beside versioned ciphertext. This is a proposal, not an approved encryption format or protocol.
Repository read/write access alone must not be assumed to confer secret-decryption authority.

Recovery is conditional on the chosen security design: an appropriately authorized holder may
be able to update source declarations/key grants, or a lost identity may require fresh onboarding.
Replacing a compiled identity still requires new artifacts, not live account recovery. The
user has not required administrator password reset, private-key recovery or guaranteed recovery
when every decryption/recovery key has been lost. No private-key escrow is approved.

Before the secrets/remote-access milestone, decide encryption format, static identity declaration,
trust bootstrap, password/OTP handling and authorized source/key-grant changes; recovery, Git
conflicts, retained history and rollback validation; private-key backup; runtime secret delivery;
rotation overlap/expiry, failed renewal and unavailable-provider behavior. The permission/identity
mutation question is resolved by SEC-006/SEC-008 and rotation is permitted by SEC-009; do not keep
asking those as unselected directions. No bespoke cryptography is approved.

### Git History And Rollback

Git restores file versions; it cannot undo a credential rotation performed in an external
database or erase previously cloned ciphertext. An old revision may retain grants for a removed
member, and offline clones cannot discover revocation immediately. Re-encrypting the latest
revision does not remove old ciphertext or plaintext already obtained by a recipient.

Recommendation awaiting approval: Creator-assisted rollback should restore selected values or
configuration under current authorized access, not silently resurrect old recipients or revoked
credentials. Exact freshness/authorization evidence, recovery and conflict rules must be agreed;
commit signatures alone do not prove that an old revision is still authorized. A user can still
check out historical files outside Creator, so the UI cannot promise to prevent all rollback.

## Research Boundaries

- Envelope encryption separates data encryption from authorized key access. Revocation cannot
  retract previously obtained plaintext; exposed external credentials may need rotation.
  See [OWASP secrets management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html).
- Updating recipients and rotating data-encryption keys are separate from rotating actual
  passwords/API keys. See [SOPS key management](https://getsops.io/docs/usage/key-management/)
  as a design reference, not a mandated implementation.
- JDK HTTP filters run around the request handler. They can reject before Flow execution and
  application body decoding, but are not a pre-accept socket hook. See [JDK Filter](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/Filter.html).
- A transport with an accept-stage hook can check the peer before application protocol work;
  kernel-level rejection is earlier still but requires platform-specific authority. It cannot
  be promised by an ordinary unprivileged portable binary. See [JDK ServerSocket](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ServerSocket.html)
  and [Netfilter hooks](https://wiki.nftables.org/wiki-nftables/index.php/Netfilter_hooks).

Ingress design still needs trusted-proxy rules, admission/handshake/header/body budgets, and
protocol implementation selection. Body-dependent decisions cannot precede body inspection.
These are design questions, not approval to replace proven HTTP/TLS implementations.

## Planned Layer 4 Step Integration

Source: the user's 2026-09-20 clarification. Layer 4 enforcement is a potential Step capability,
including Steps authored by third parties under [SYS-009](system-model.md#planned-host-aware-step-integration),
not a built-in-only firewall controller or mandatory Creator service. SEC-002 owns the observable
rejection boundary. **Layer 4 tools, APIs and supported platforms are not specified yet.**

Before implementing a Layer 4 Step, define how it checks host capabilities and permissions,
whether it invokes host commands or another interface, and which dependencies its artifact needs.
Specify rule/resource ownership across other applications and rolling instances, initialization,
monitoring/drift detection, cleanup and the limits of ongoing enforcement. A periodic check alone
is not evidence of uninterrupted protection. Automatic repair, fail-closed behavior and weaker
fallback enforcement remain decisions, not accepted defaults from the preceding discussion.

Keep these decisions with the dependent Step/security slice; they do not block unrelated Flow
work. Any eventual maintenance must preserve the compiled-policy boundary in SEC-006 and the
capability inclusion boundary in SYS-002. No host-modifying implementation is approved here.

## Acceptance And Evidence

| Requirement | Public-boundary scenario | Evidence / gap |
| --- | --- | --- |
| SEC-001 | Authorize one user across 500 projects, onboard a second device and revoke project access without manual per-project key files. | Planned; identity, storage, recovery and revocation contracts are open. |
| SEC-002 | Exercise a rejected peer/request and measure which accept, handshake, decode and Flow stages ran; repeat through a trusted and untrusted proxy. | Planned per transport; earliest-stage guarantee and budgets need an agreed contract. |
| SEC-003, SEC-005 | Two local Creators exchange an encrypted change through Git, detect a conflicting edit and restore a revision; inspect tracked files for plaintext/private keys and binary artifacts. | Planned; file, conflict, trust and rollback contracts are open. |
| SEC-004 | Use the default with no vault service, then explicitly select an external manager. | Planned; provider contract and integrations are undecided. |
| SEC-006 | Change the repository policy and try live API/file updates; the old instance retains its policy, while only a rebuilt replacement applies the new grants. Check mixed-version deployment and rollback. | Planned; policy representation, identity boundary and emergency-revocation process remain open. |
| SEC-007 | Review a release's applicable-control mapping, tests, gaps and operator duties; verify no unsupported compliance badge or claim is produced. | Planned; regulatory applicability, ISO scope and ASVS target must be agreed before claims. |
| SEC-008 | Attempt live registration or role/membership edits; only a rebuilt replacement changes the declared identities and authority. | Planned; source format and authentication boundary are not implemented. |
| SEC-009 | Rotate a credential for an existing identity; preserve permissions and reject attempts to add authority. Exercise expired credentials and failed renewal. | Planned; trust, overlap and failure policy must be agreed before implementation. |
