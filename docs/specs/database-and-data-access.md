# Database And Data Access

## Human Review

Status: **Planned; contract discovery**, recorded on 2026-09-19. Source: the user's review
request excludes a time-series database, requires Creator to view/manipulate database data
subject to user permissions, and selects MongoDB-inspired behavior. No database engine,
administration endpoint or UI is implemented
by this document. The current standard catalog has no database Step.

Owns: built-in database behavior and connected Creator data administration. The canonical value
model belongs to [system model](system-model.md); identities, environments and secret delivery
belong to [security](environments-and-security.md). [Observation](observation.md) remains a
separate capability, not an implicit grant to read stored application records.

## Scope

The earlier product direction includes encrypted durable storage that survives application
restarts. This slice adds the data-management experience needed alongside those Steps, rather
than treating the database as an opaque implementation detail. Exact durability, query and
transaction guarantees still need agreement.

Implementing or bundling a dedicated time-series database is **out of scope**. Ordinary records
may contain timestamps; metric exporters do not imply a bundled historical-metrics database.
This exclusion does not choose an engine or prohibit a later separately approved integration.
Sharding and replication remain the later mesh slice, not the first local database prerequisite.

Project/configuration history in Git is distinct from live database contents. Restoring a
project revision does not restore stored data. The text-file requirement for Git-managed secrets
does not choose the database's internal storage format or put production records into Git.

## Accepted Behavior

- **DATA-001:** When connected to an application that exposes the selected data-management
  capability, Creator MUST let authorized users inspect stored data and create, update or
  delete records within their permissions. Canonical values and data types MUST be preserved;
  the UI must not silently coerce values while editing. Query/filter features remain to be scoped.
- **DATA-002:** The serving data-management boundary MUST authorize each operation on its
  requested target. A denied operation MUST neither disclose protected records nor mutate them.
  Hiding a button is not authorization; a direct API request must receive the same enforcement.
- **DATA-003:** Observation access alone MUST NOT grant database access. Data administration
  MUST respect the artifact's included capabilities and application-side access configuration;
  absent capabilities follow [SYS-002](system-model.md#accepted-platform-boundaries).
  Editing stored records does not edit the compiled Flow or enable arbitrary Flow execution.
- **DATA-004:** Database behavior SHOULD follow familiar MongoDB document/collection semantics
  where compatible with Railix's canonical values and accepted scope. Any intentional departure
  MUST state its reason and observable behavior; an "improvement" must not silently change familiar
  semantics. This direction does not select the MongoDB engine, wire protocol or complete API.

This makes the earlier "read-only attachment" direction precise: observation stays read-only,
while separately authorized data administration may mutate records. The endpoint shape and
permission granularity remain open; runtime policy changes require a rebuilt replacement under
[SEC-006](environments-and-security.md#immutable-permissions-and-revocation).

### MongoDB-Inspired Contract Boundary

Before engine selection, agree a small, versioned behavior profile rather than claim general
MongoDB compatibility. Recommended starting points, not yet an approved operation set:

- Document collections, stable `_id` identity, explicit insert/update/replace/delete behavior and
  duplicate-key results. Creator's stale-edit detection can use an expected revision/value filter.
- Atomic single-document changes. MongoDB checks update filters during concurrent writes; a
  multi-document operation is not automatically one atomic transaction. See [atomicity](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/).
- Explicit acknowledgement/durability and timeout semantics. MongoDB distinguishes replication
  acknowledgement, journaling and waiting limits through [write concern](https://www.mongodb.com/docs/manual/reference/write-concern/);
  these must not become fictional replication settings in Railix's first single-instance store.

MongoDB's [BSON types](https://www.mongodb.com/docs/manual/reference/bson-types/) include ObjectId,
binary and date values beyond JSON. Railix's identity representation, exact numbers, null/missing,
query operators and supported indexes therefore require a written profile and compatibility tests.
Using familiar semantics does not solve engine size, licensing, recovery or embedded deployment.
No full MongoDB driver compatibility, relational layer or time-series database is implied.

## Decisions Before Implementation

1. **Data model and scope:** finalize the MongoDB-inspired JSON document profile, identity,
   duplicate-key behavior, null/missing semantics, schema validation and the first query/index
   operations. Decide whether external database connectors join the first Creator data UI.
2. **Durability and transactions:** what a successful write guarantees after process crash or
   power loss; atomicity/isolation scope; disk-full/corruption results; recovery, encryption-key
   availability, backup and restore. Engine/dependency choice follows these guarantees.
3. **Concurrent edits:** a record can change through a Flow or another Creator while an editor
   is open. Recommendation, not yet approved: compare revisions and return an explicit conflict
   rather than silently overwrite. Timeout after a write also needs an unambiguous retry policy.
4. **Permissions and trust:** scope permissions by project, environment, database/collection and
   operation; decide whether record/field restrictions are initially needed. Define how the
   running instance verifies identity against its compiled policy and how rebuilt replacements
   remove stale/revoked authority. A local Git checkout cannot assert its own administrator role
   or update a running application's permissions.
5. **Management safety and performance:** bounded page/query sizes, cancellation, query deadlines,
   index/scan behavior and resource budgets so browsing cannot exhaust production execution.
   Decide bulk-change confirmation, audit metadata/retention without secret dumps, export and
   restore rights, environment visibility, and whether schema administration is initially supported.
6. **Builds, tests and upgrades:** stable storage identity across rolling application replacement,
   single-writer/multiple-process ownership, schema/format migration and old-artifact compatibility.
   Example fixtures and fakes require [isolated test contracts](examples-and-validation.md#open-decisions);
   permission to edit data must not silently authorize automatic Examples against production.

Permission enforcement guidance: [OWASP authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
recommends deny-by-default and checks on every request. The exact Railix permission matrix is open.

## Acceptance And Evidence

All scenarios are planned and require the generated application plus public Creator/API boundaries.

| Requirement | Scenario | Evidence / gap |
| --- | --- | --- |
| DATA-001 | Create/read/update/delete a record through Creator and read it through a Flow; preserve nested values and exact numbers. | Planned; model, operations and engine are undecided. |
| DATA-002 | Try record discovery, read and mutation with insufficient rights, including a direct request bypassing the UI. | Planned permission-matrix tests; granularity and identity contracts are open. |
| DATA-003 | Build without data administration, then with it but observation-only user rights; neither grants data operations. | Planned packaged omission and authorization checks; capability selection is not implemented. |
| DATA-004 | Exercise every selected operation against the agreed MongoDB reference behavior and documented Railix deviations. | Planned; versioned profile, supported operators and value mapping are not yet selected. |
| Concurrent/durable writes | Race two edits, lose a response, restart during a write, restore a backup and run a compatible upgrade. | Required scenarios for the next contract round; expected outcomes and durability boundaries are not yet agreed. |
| Management budgets | Browse/query a large store while normal Flows execute; cancel a costly query and verify released resources. | Planned; representative workload and measurable budgets must be agreed before certification. |
