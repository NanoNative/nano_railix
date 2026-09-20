# Environments And Security

## Human Review

Status: **Planned; design discussion**, updated on 2026-09-20. The user accepted the
local Git-backed default, optional external managers, usability, early rejection, immutable
deployed identities/permissions, credential rotation and compliance-support goals, not a
cryptographic architecture or implementation. The latest clarification rejects live user
management; it supersedes the earlier proposal for a mutable account/role service.
Runtime settings and secrets remain unsupported as recorded in [ADR 0019](../adr/0019-runtime-settings-and-secrets.md).

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
