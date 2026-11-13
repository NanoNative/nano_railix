# Custom Java Steps In Creator

## Human Review

Status: **Creation and compilation gate accepted** on 2026-11-11.
Added a separate custom-Step creation option with Java written or
pasted in Creator. A Step becomes selectable only after successful compilation.
Shared core compilation API accepted on 2026-11-12: another authoring tool must
be able to compile a Java method without a Creator dependency.
As on 2026-11-12 the implementation accepts Creator as the only editor while a project is
open. The existing project lease excludes a second Creator in that workspace.
The existing build lock serializes publications within the active Creator.
Direct source-file edits belong between Creator sessions. The deletion
increment retains a semantic source fingerprint and rejects intervening outside
changes before publication. Each submission reads the saved document
under the build lock, compiles its addition, and atomically persists the candidate
before publishing the catalog. Catalog revisions remain necessary for refreshing
browser tabs.

Owns: this authoring flow and its project-local source. Existing Step contracts,
generated execution, Examples, dependency ownership and lifecycle remain in their
current owners. This document does not change those specifications or ADRs.

The initial implementation creates named ordinary transformation Steps from one
public static method. It supports String, boolean, BigDecimal and RailixValue
parameters/results. Creator derives receive ports from parameters and supplies
one output named `result`. Advanced resource/control authoring and editing an
already published custom definition are separate work.

**Definition deletion and history policy accepted on 2026-11-13; runtime verification pending.**
The user requires an option to delete a project-owned custom Step only after its
uses have been removed. This covers the current materialized project,
including linked placements, unvisited branches and actual nested Step calls.
Example payloads or literal JSON containing the same `use` text are not uses.
Prebuilt provider definitions remain outside this deletion flow. Existing binary
artifacts remain available to applications that already own them.

The user's latest clarification on 2026-11-13 supersedes the earlier policy of
clearing history after deletion: definition creation and deletion participate in
the same Undo/Redo history as placement edits. Undo restores a deleted definition
before earlier actions can restore its placements. Redo removes those placements
before deleting the definition again. New accepted definition changes clear Redo
as ordinary editor actions do. The history is shared across tabs and lasts only
for the active Creator session. Before submitting deletion, the red Delete button
must ask for confirmation and explain recovery during the current session.
Cancellation and rejected or failed changes preserve history. Recovery of unused saved
methods whose Java no longer compiles remains a separate open increment: normal
Creator startup currently rejects those documents. Runtime verification is
excluded by the user's no-running constraint; regressions must be reported as
unexecuted until execution is authorized.

**Native Image authoring policy accepted on 2026-11-12; implemented on 2026-11-13.**
The user selected rejection of known dynamic APIs for custom Steps created through
Creator, retaining general Java access. This is a targeted denylist, not an API
allowlist. The shared compiler supplies an explicit policy selected by Creator;
its existing general-purpose API remains available to other authoring tools.
Creator applies the same policy when rebuilding saved custom source. Prebuilt
[portable providers](system-model.md#portable-step-providers) retain their existing
SYS-036/037 dynamic-class contract. Native Image deployment remains future work;
this adds a custom-source authoring constraint to the earlier
[distribution decision](../adr/0013-small-java-product-boundary.md).

The accepted scope covers resolved references in submitted source, including
local/anonymous classes. It does not inspect the internals of called library/JDK
implementations and does not certify their Native Image compatibility. Neither
this denylist nor successful javac compilation proves whole-application support.

## Behavior

- **CUSTOM-001:** Creator MUST expose custom-Step creation separately from
  placing an existing Step. Typing or failed compilation MUST NOT add a catalog entry.
- **CUSTOM-002:** Successful publication MUST compile the method and its normal
  Step adapter, verify the resulting bundle through the ordinary catalog boundary,
  and persist its source before exposing the entry. Compilation MUST NOT invoke
  the user method to infer its behavior or ports.
- **CUSTOM-003:** Source errors MUST identify the user's source location. The
  editor MUST retain the submitted draft after failure and make changed source
  require another compile before publication.
- **CUSTOM-004:** Published source MUST belong to the project. Reopening, ordinary
  production builds and source-inclusive application restoration MUST reconstruct
  the same Step contract/code through normal build ownership. Generated artifacts
  remain in `.railix`; compilation state is not trusted merely from a UI flag.
- **CUSTOM-005:** A published custom Step MUST use ordinary placement, reuse,
  Examples and generated static execution. A duplicate identity MUST be rejected;
  creating an unused definition MUST NOT restart the running application.
- **CUSTOM-006:** An editor snapshot MUST identify its catalog revision. An open
  Creator tab MUST refresh stale definitions before using that snapshot for
  placement or inspection. If the picker cannot refresh definitions, it MUST show
  a failure with an explicit retry and preserve the current search query.
- **CUSTOM-007:** Core MUST expose stateless Java-method compilation with explicit
  Step identity, display name, source, runtime API classpath and output directory.
  It MUST return an ordinary Step bundle and contract or located diagnostics,
  without modifying a project document or registering a catalog entry. Creator
  MUST use that API and retain ownership of source persistence and publication.
- **CUSTOM-008:** Creator MUST reject an addition whose complete serialized custom
  source document exceeds the existing project-document byte limit, before saving
  the document or publishing the candidate catalog. Rejection MUST preserve the
  accepted source and catalog revision.

The accumulated source document uses the existing 64 MiB project-document limit.
Each compile request still uses Creator's existing 1 MiB HTTP body limit, including
the name, method source and JSON encoding. Neither limit is a Step execution limit.

### Custom-Step deletion and history

- **CUSTOM-011:** Creator MUST offer deletion of a project-owned custom-Step
  definition only when no placement or nested invocation in the current project
  references it. Example execution counts MUST NOT determine eligibility.
- **CUSTOM-012:** The server MUST serialize the usage check and deletion with
  placement/edit/publication under the existing build ownership. Rejected or
  failed deletion MUST preserve accepted source, catalog and running application.
  Accepted deletion MUST persist the source change before publishing a revised
  catalog and MUST NOT restart the unchanged application. Other open tabs MUST
  refresh removed definitions without resurrecting an older catalog response.

- **CUSTOM-013:** Creating and deleting custom definitions MUST each create one
  entry in the existing shared editor Undo/Redo history. Undo/Redo MUST restore or
  remove the saved definition source and catalog entry together, preserving its
  source order and other definitions. Removing a definition through history MUST
  reject any remaining placement or nested invocation. Definition-only history
  actions MUST NOT restart the unchanged application. New accepted definition
  changes MUST clear Redo while retaining earlier Undo entries within the existing
  history budget. Rejected or failed changes MUST preserve both stacks. Conflicting
  outside source edits MUST be preserved.
- **CUSTOM-014:** Clicking a custom definition's red Delete button MUST open a
  Railix-themed confirmation dialog before submitting deletion. The dialog MUST name the definition
  and explain that deletion can be undone during the current Creator session,
  whose history is shared across tabs and lost when Creator closes. Cancelling MUST
  leave source, catalog and history unchanged and MUST NOT submit a deletion request. Cancel MUST
  receive initial focus; Escape MUST cancel and return to the definition list.

History remains in the active Creator server's memory and does not survive its
restart. Deletion removes authoring source/catalog entries; it does not remove
immutable binaries owned by existing builds. Broken-source (manually erroring the custom step json file)
startup recovery is not supported.

### Creator's dynamic-API restriction

- **CUSTOM-009:** Creator's Java-method compilation MUST reject references to the
  known dynamic APIs below, including reflective class/member lookup and
  invocation. Rejection MUST identify the offending submitted-source location
  through the existing diagnostics boundary and preserve accepted source/catalog
  state under CUSTOM-001/003/005. Code MUST NOT be executed to inspect its behavior.
- **CUSTOM-010:** Creator MUST select the same restriction for custom-source
  creation, reopening, builds and restored source. Other authoring tools and
  prebuilt provider installation MUST retain their existing policy. Ordinary
  filesystem access MUST remain available. Compiler-generated lambdas and string concatenation
  MUST NOT be rejected merely because their bytecode uses `invokedynamic`.

| Rejected API surface | Scope |
| --- | --- |
| `java.lang.reflect`, `java.lang.invoke`, `java.lang.foreign`, `java.lang.instrument`, `javax.tools`, `javax.script` | References to types/members in these packages and subpackages. |
| `Class` | Reflective member/annotation/generic/enclosing/nest lookup, `forName`, `newInstance`, `arrayType`, class-loader access and classpath-resource lookup. Class literals and ordinary type-name access remain available. |
| `ClassLoader` and subclasses | Type/member references, including constructor calls and inherited methods. |
| `ResourceBundle`, `ServiceLoader` and subclasses | Resource-bundle lookup and runtime provider discovery. |
| `ObjectInputStream`, `ObjectOutputStream`, `ObjectStreamClass`, `ObjectStreamField` and subclasses | Java object serialization. Ordinary byte/text streams remain available. |
| `Module.getResourceAsStream`, `System.load/loadLibrary`, `Runtime.load/loadLibrary`, `sun.misc.Unsafe`, `jdk.internal.misc.Unsafe` | Classpath resources and explicit native/unsafe access. Native method declarations in local classes are also rejected. |

Checks use resolved API identities: fully qualified names, variable receivers,
method references and code inside local/anonymous classes must not bypass the
same policy. Matching words in source or scanning only `java.lang.reflect` is
insufficient. Signature/port inspection remains separate from these body checks.

## Acceptance

| Requirement | Public-boundary check | Evidence |
| --- | --- | --- |
| CUSTOM-001/003 | Enter invalid Java in the creation UI; see a source diagnostic while the Step remains absent from the picker. Edit after a successful compile and verify the old success cannot publish the changed text. | Browser journey below; the API test also rejects placing a definition before compilation. |
| CUSTOM-002/005 | Compile a typed method, inspect its catalog ports, place/reuse it, then execute a saved Example through the generated application. | API tests cover all supported types, exact decimals and structured values. Browser checks assert successful completion, exact context values, visits and actual returned values for both custom-Step occurrences after reuse. |
| CUSTOM-004 | Reopen the project and build/run a production artifact using its saved custom source. Restore source-inclusive project files into a fresh workspace. | One artifact journey covers two saved definitions, multiple/mixed arguments, reopening, captured JAR-digest equality, standalone execution and fresh-workspace restoration. A separate check inspects an embedded observed build. |
| CUSTOM-005 | Reject a duplicate name/identity and verify creating an unused Step leaves the application generation unchanged. | API test checks duplicate rejection, unchanged saved source and unchanged application PID. |
| CUSTOM-002/003 | Reject references to authoring-only classes, keep method names separate from lifecycle hooks, and locate adapter errors at the submitted method declaration. | API regressions reject `StepDefinition`, execute methods named `pre` and `post` in production JARs, and check checked-exception diagnostics after a leading blank line. |
| CUSTOM-002/005 | Fail source publication and preserve the accepted catalog. | API regression exercises a real directory-write failure. It requires enforceable POSIX permissions and reports a skip otherwise. |
| CUSTOM-006 | Compile in one tab; use the Step from another tab, both with its picker already open and after the first tab has placed it. | Two-tab browser regressions cover independent picker and Inspector refresh paths. |
| CUSTOM-006 | Return an older catalog after another editor request has installed a newer one. | A desktop browser regression buffers a real catalog response, obtains the newer revision through Group navigation, then checks the picker after releasing the older response. |
| CUSTOM-006 | Fail a second tab's catalog refresh, then retry without changing its search. | Desktop/narrow browser regression checks the visible failure, preserved query, explicit retry and placement of the newly available Step. |
| CUSTOM-007 | Compile through a standalone Java authoring client whose classpath excludes Creator and stdlib; install the resulting bundle and invoke its ordinary adapter. | Core E2E covers all supported parameter types, exact decimals, deterministic JAR bytes, source diagnostics and caller-workspace preservation. Creator artifact checks also exclude the build-time compiler from production applications. |
| CUSTOM-008 | Add a method to a multibyte source document exactly at the byte limit, reject the next addition, and reopen the accepted document. | API regression checks exact bytes, unchanged source/catalog after rejection and restoration of the accepted definitions. |
| CUSTOM-002/005 | Submit concurrent distinct and duplicate definitions, then reopen the project. | Custom-Step API regression checks serialized publication, one duplicate rejection and catalog revision. Project lease exclusion stays covered by the existing `GeneratedApplicationE2eTest.secondCreatorCannotOwnTheSameProject` lifecycle regression. |
| CUSTOM-001/003 | Submit malformed UTF-8/JSON, missing or incorrectly typed fields, unknown fields, a blank name and an oversized request. | API characterization preserves rejection bodies/statuses, the 1 MiB request cap, name normalization and absence of publication after rejection. |
| CUSTOM-007 | Use fully qualified spellings of the supported Java types. | Core regression compiles and installs the ordinary bundle and verifies its port shapes. |
| CUSTOM-002/005 | Map two String parameters to distinct fields and a boolean parameter through Configure; reload and execute both branch Examples. | Desktop/narrow browser journey verifies independent saved mappings, compatible Boolean choices, exact execution results and persistence after reload. |
| CUSTOM-009 | Submit denied APIs through the core policy and Creator, including fully qualified calls, method references and nested/local code. | Core/API regressions verify located rejection, no method execution, and unchanged accepted source/catalog/artifacts. The browser compilation/reuse journey checks the visible diagnostic and retained draft. |
| CUSTOM-010 | Reopen/build custom source containing a denied call. Compile ordinary transformations, filesystem reads, lambdas, string concatenation and misleading names/comments. | Core/API regressions verify repeated rejection on Creator's source paths, successful ordinary execution, identical bundles under either policy and an unrestricted standalone authoring call. Called-library internals remain outside this increment. |
| CUSTOM-009/010 | Build and run a representative produced application on the selected GraalVM target without adding custom-step reachability metadata. | Future deployment evidence; no Native Image compatibility claim is established by JVM policy tests. |
| CUSTOM-011/012 | Delete an unused custom definition, reopen the project and recreate its name; reject deletion while a placement, linked use or nested invocation remains. Ignore matching text in literal/example data. | API regressions authored; not executed under the no-running constraint. |
| CUSTOM-012 | Race placement with deletion, deliver an older catalog after deletion, and fail source persistence. | Concurrent placement/deletion, delayed-catalog browser and source-publication failure regressions authored; not executed under the no-running constraint. |
| CUSTOM-006/012 | Keep a placement selected in one tab, remove it and its definition in another, then open and close Manage custom Steps. Repeat with an unsaved Example draft. | Desktop/narrow browser regression authored: matching project/catalog refresh returns a removed selection to the App; conflicting drafts retain their view and definitions with a reload diagnostic. Not executed under the no-running constraint. |
| CUSTOM-013 | Undo/redo definition creation, placement, removal and definition deletion in order. Preserve unrelated edits and definitions, restore original source/order, recreate the same name with different source, and branch history after Undo. Reject stale, in-use, externally changed and failed-persistence changes without consuming history. Verify definition-only actions retain the application PID and restart drops session history. | API and desktop/narrow browser regressions authored; not executed under the no-running constraint. |
| CUSTOM-014 | Click Delete, inspect the themed session-history warning and initial Cancel focus; cancel by button and Escape, verifying no DELETE request, unchanged definition/history and a usable button. Confirm deletion, then restore the definition and its placement through Undo and repeat with Redo. | Desktop/narrow browser regression and warning screenshots authored; not executed under the no-running constraint. |

## Implementation Flow

The `custom-steps.js` editor owns dialog events, draft status, diagnostics and modal
keyboard handling. Application callbacks own HTTP requests, exact JSON decoding,
workspace checks and catalog publication. The ordinary picker owns search state,
loading errors and retries for both custom and existing Steps.

1. Choose **Create custom Step** from the Project menu or Add Step picker. Enter a
   name and one Java method. This is a draft until **Compile Step** succeeds.
2. Creator supplies the stable ID, name, method source, its production runtime API
   and the project artifact directory to core's `JavaStepCompiler.compile`.
   The shared compiler derives typed inputs and `result`, compiles the method and
   ordinary static Step entrypoint, and returns a content-addressed JAR and normal
   dependency lock. The method's class is separate from the entrypoint so its name
   cannot declare lifecycle hooks. Source diagnostics refer to submitted Java;
   adapter errors point to the method declaration. Compilation never invokes it.
3. Creator verifies the compiled bundle through the normal Step catalog, then
   atomically saves the name/source in `railix.custom-steps.json` beside the project
   file. Only then does it expose the definition to the picker. Existing input
   compatibility rules still determine where the Step can be placed.
   The Creator session advances its catalog revision after publication. Editor
   snapshots carry that revision so other tabs fetch new definitions when needed.
   Reopening installs saved definitions without rewriting their source documents.
4. Placement, Examples and linked reuse use the existing editor/build path.
   Production packaging includes the referenced compiled implementation; it does
   not need Creator or a Java source interpreter during execution.
5. Reopening or building reconstructs the catalog from saved source. Generated
   bundle JARs live under `.railix/custom-steps/artifacts/`. Source-inclusive
   artifacts also carry the custom source document so a fresh workspace can
   reconstruct the project.

The Project menu's **Manage custom Steps** opens project-owned definitions and
their usage status. `GET /api/custom-steps` supplies this list and the catalog
revision; `DELETE /api/custom-steps` accepts `id` and `catalog_revision`. Providers
cannot be deleted through this boundary. Usage comes from accepted compiler
dependencies, including actual nested calls, rather than a search through JSON
data. The red Delete button opens a Railix-themed confirmation dialog naming the definition and
explaining that deletion can be undone during this Creator session; history is
shared across tabs and lost when Creator closes. Cancellation sends no deletion request.
Publication removes the saved record at the atomic single-file persistence
boundary, publishes the revised catalog, then records the definition change in
the existing bounded history. History retains the affected definition's source
and position rather than copies of the entire custom source document. Undo/Redo
insertion uses the same restricted compiler and source publication as creation;
removal retains the usage check. Failed changes preserve history. Retained build
artifacts are never deleted.

Other authoring tools call the same core API with their own identity, classpath
and output locations. The runtime API may be a directory or JAR; its supplied
contents determine which APIs submitted methods can reference. Creator supplies
its restricted production API rather than its full process classpath. The shared
compiler knows no project filename, UI state or publication revision and removes
only its own staging directory.

Verification uses [JavaStepCompilerE2eTest](../../modules/railix-core/src/test/java/dev/nanonative/railix/core/JavaStepCompilerE2eTest.java),
[CustomStepsE2eTest](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/CustomStepsE2eTest.java),
[RailixCreatorCustomStepsBrowserIT](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/RailixCreatorCustomStepsBrowserIT.java)
and the existing `ProjectSourceArtifactsE2eTest`. Focused core and custom-Step
API/artifact checks and browser journeys at 1280×800 and 320×720 passed on
2026-11-12 in a fresh source copy. This evidence does not claim the full repository
verification gate.

Compilation does not establish purity, safe retries, remote eligibility or a
sandbox for executing arbitrary Java. Those remain the ordinary Step boundaries.
