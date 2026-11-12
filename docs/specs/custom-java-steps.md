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
Direct source-file edits belong between Creator sessions and arbitrary concurrent
file editors are outside this ownership contract as of now. No retained custom-source
snapshot and external-edit conflict handling is coded at the moment.Each submission 
reads the saved document under the build lock, compiles its addition, and atomically 
persists the candidate before publishing the catalog. Catalog revisions remain 
necessary for refreshing browser tabs.

Owns: this authoring flow and its project-local source. Existing Step contracts,
generated execution, Examples, dependency ownership and lifecycle remain in their
current owners. This document does not change those specifications or ADRs.

The initial implementation creates named ordinary transformation Steps from one
public static method. It supports String, boolean, BigDecimal and RailixValue
parameters/results. Creator derives receive ports from parameters and supplies
one output named `result`. Advanced resource/control authoring and editing an
already published custom definition are separate work.

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
   Reopening installs saved definitions without rebuilding unused source documents.
4. Placement, Examples and linked reuse use the existing editor/build path.
   Production packaging includes the referenced compiled implementation; it does
   not need Creator or a Java source interpreter during execution.
5. Reopening or building reconstructs the catalog from saved source. Generated
   bundle JARs live under `.railix/custom-steps/artifacts/`. Source-inclusive
   artifacts also carry the custom source document so a fresh workspace can
   reconstruct the project.

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
