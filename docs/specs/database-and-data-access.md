# Database And Data Access

## Human Review

Status: **`railix.database` is implemented; Creator data administration is still planned**, updated on 2026-11-19.
Source: the user asked for a database that stays out of time-series territory, lets Creator view and
edit data within a user's permissions, and follows familiar MongoDB behavior.

The standard library now ships `railix.database` (`1`) as an ordinary portable Step. It stores
documents in a pure-JDK append-only journal and supports `upsert` (one document or a batch), `get`,
`find` with keyset pagination, `delete`, comparison and logical query operators, nested field paths,
and conditional writes. Creator's data-administration screens, the authorized serving endpoint
(`DATA-001` to `DATA-003`), partial update operators (`$set`, `$unset`, `$inc`) and query budgets are
not built yet.

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

1. **Data model and scope:** the query operators and conditional writes below are settled for
   `railix.database` (`1`). Partial updates (`$set`, `$unset`, `$inc`) and whether external database
   connectors join the first Creator data UI are still open.
2. **Durability and transactions:** single-instance durability and recovery are implemented as
   described below. Backup and restore, encryption keys at rest and upgrade migrations are still open.
3. **Concurrent edits:** `condition` on `upsert` and `delete` gives atomic optimistic concurrency
   inside the Step. How Creator surfaces revision conflicts and retries after a timeout must be
   decided before `DATA-001`.
4. **Permissions and trust:** scope permissions by project, environment, database/collection and
   operation; decide whether record/field restrictions are initially needed. Define how the
   running instance verifies identity against its compiled policy and how rebuilt replacements
   remove stale/revoked authority. A local Git checkout cannot assert its own administrator role
   or update a running application's permissions.
5. **Management safety and performance:** bounded page/query sizes, cancellation, query deadlines,
   index/scan behavior and resource budgets so browsing cannot exhaust production execution.
   Decide bulk-change confirmation, audit metadata/retention without secret dumps, export and
   restore rights, environment visibility, and whether schema administration is initially supported.
6. **Builds, tests and upgrades:** storage paths resolve predictably across rolling replacement and
   `collection.lock` keeps a single writer. Format migration and old-artifact compatibility are open. Example fixtures and fakes require
   [isolated test contracts](examples-and-validation.md#open-decisions); permission to edit data must
   not silently authorize automatic Examples against production.

Permission enforcement guidance: [OWASP authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
recommends deny-by-default and checks on every request. The exact Railix permission matrix is open.

## Standard Library `railix.database` (`1`) Contract

`railix.database` is a normal portable provider Step. It is registered through `StandardLibrary` and
uses the public `StepDefinition` and `context.resource` contracts, so a Flow that never places it does
not carry `DatabaseStepHandlers` into the built application.

### Collections, Keys And Storage Location

- **DATA-005:** Configuration is validated at compile time: `collection`, `directory`, `key_field`,
  `unique_fields`, `index_fields`, `defaults` and `limit` are all checked before anything runs, with
  no special-case compiler code.
- **DATA-006:** Collection and key behavior must be identical on Linux, macOS and Windows.
  - A collection name is 1 to 64 characters, starts and ends with a lowercase letter or digit, and
    otherwise uses only `a-z`, `0-9`, `.`, `_` and `-`. It cannot contain `..` or start with a
    Windows device name such as `con`, `nul`, `com1` or `lpt1`.
  - A `directory` must name at least one directory segment and cannot contain control characters,
    `<`, `>`, `"`, `|`, `?`, `*`, a `:` outside a drive root (`C:/` or `C:\`), `.` or `..`
    segments, segments ending in a dot or space, or OS-reserved device names.
  - A relative `directory` resolves against `-Drailix.data.dir`, then `RAILIX_DATA_DIR`, then the
    project root (when started from `.railix/build/<entry>/application.jar`), then the working directory.
  - `collection.lock` records the collection that owns the folder (`DATABASE_COLLECTION_COLLISION`).
    Placements that share one collection must agree on `key_field` (`DATABASE_KEY_FIELD_MISMATCH`),
    which must be a valid non-blank top-level field name up to 128 bytes without `.` or leading `$`.
  - The string key `"42"` and the number key `42` are different keys. `42` and `42.00` are the same
    key, and numbers are never converted through `double`. A missing or `null` key either becomes a
    generated UUID (`auto_key: true`) or routes to `invalid`.

### Operations And Queries

- **`upsert`** takes one object or an array of objects in `document` (an empty array returns `ok`
  with `[]`), plus optional `fields` and `condition`. It replaces the stored record for each key:
  when `fields` is empty (`[]`), the whole document is stored; when `fields` lists field names,
  only `key_field` and those top-level fields are kept and stored, and any unlisted fields from the
  input are dropped. A batch is all-or-nothing: an invalid document, a unique-field clash or an
  oversized batch routes to `invalid` and writes nothing.
- **`get`** and **`delete`** need a valid scalar `key`. A missing record routes to `not_found` and an
  unusable key routes to `invalid`. `get` applies `fields` and `defaults` only to the returned view,
  leaving stored records unchanged. `delete` removes the record when the optional `condition` also
  matches; otherwise it routes to `not_found`.
- **`find`** requires `filter` to resolve to a JSON object at run time (a missing path or `null`
  routes to `invalid`; pass `{}` to match all documents). It also takes an optional `after` cursor
  (a key value), `fields` and `defaults` (applied only to the returned view), and a `limit` from 1
  to 1,000 (default 100). Results come back in key order: numbers by value, then strings by UTF-16
  order, and scans check thread interruption cooperatively between records.
- **DATA-004 filters and conditions** follow MongoDB where it fits:
  - Field operators: `$eq`, `$ne`, `$gt`, `$gte`, `$lt`, `$lte`, `$in`, `$nin`, `$exists`.
    Logical operators: `$and` and `$or`, each with a non-empty list of sub-filters.
  - Field paths may be nested (`customer.tier`, `items.0.sku`), up to 16 segments. Array segments
    are non-negative integer indexes.
  - Indexes declared in `index_fields` and `unique_fields`, including nested paths, are ordered, so
    equality, `$in`, range comparisons, combined index lookups and fully indexed `$or` filters avoid
    scanning every record. Key lookups and key ranges use the sorted key set.
  - Railix-specific rules: values are never coerced (`"100"` does not match `$gte: 100`, and
    `$gt`/`$gte`/`$lt`/`$lte` accept only numbers or strings); arrays are compared as whole values;
    unknown `$` operators, a `$` operator mixed with plain keys, malformed paths, more than 1,000
    `$in`/`$nin` values, more than 16 nested levels or more than 128 clauses route to `invalid`.
  - Conditional writes: when a `condition` is authored on `upsert` or `delete`, a missing or `null`
    value at run time routes to `invalid` instead of silently writing unconditionally. An empty
    condition `{}` needs an existing record. To insert only when absent, include
    `{"<key_field>": {"$exists": false}}` (alone or combined with other predicates that also hold
    on an absent document, such as `{"<key_field>": {"$exists": false}, "status": {"$ne": "closed"}}`).
    To create or update by revision, combine that check with an existing-record check using `$or`.

### Durability, Concurrency And Limits

- **DATA-007:** Every committed `upsert` or `delete` is written to `journal.wal` as a CRC32-checked
  frame and flushed before the Step returns `ok`.
  - On startup, a half-written frame at the very end of the file is treated as an interrupted write
    and trimmed. Anything else that looks wrong (bad header, checksum mismatch on a complete frame,
    invalid key, wrong partition, malformed JSON) fails startup with `DATABASE_JOURNAL_CORRUPTED` and
    leaves the damaged file untouched.
  - When dead space reaches 64 KiB and exceeds live data, live records are rewritten to
    `journal.wal.compact` and swapped in atomically.
- Only one process may own a collection folder (exclusive lock on `collection.lock`). Inside one
  application, placements of the same collection share one store, with concurrent reads and
  serialized writes.
- Limits and why: 1 MiB per document keeps per-record memory bounded; 32 names per field list keeps
  index upkeep bounded; 1,000 documents per `find` page keeps responses bounded, and `after` continues
  past it. Replication and transactions across documents or collections are out of scope.

## Acceptance And Evidence

| Requirement | Scenario | Evidence / gap |
| --- | --- | --- |
| DATA-005 | Build and run Flows with and without `railix.database`; verify zero external runtime JARs, omission when unused, and compile-time configuration diagnostics. | [`DatabaseStepHandlers.java`](../../modules/railix-stdlib/src/main/java/dev/nanonative/railix/stdlib/DatabaseStepHandlers.java), [`DatabaseStepStoreTest.java`](../../modules/railix-stdlib/src/test/java/dev/nanonative/railix/stdlib/DatabaseStepStoreTest.java), [`DatabaseStepGeneratedApplicationE2eTest.java`](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/DatabaseStepGeneratedApplicationE2eTest.java). |
| DATA-006 | Exercise single/batch `upsert`, `get`, `find` (`limit` + `after`), `delete`, exact `BigDecimal` keys/values, OS-portable names, `auto_key`, projections, and defaults. | [`DatabaseStepStoreTest.java`](../../modules/railix-stdlib/src/test/java/dev/nanonative/railix/stdlib/DatabaseStepStoreTest.java), [`DatabaseStepGeneratedApplicationE2eTest.java`](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/DatabaseStepGeneratedApplicationE2eTest.java), [`RailixCreatorDatabaseBrowserIT.java`](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/RailixCreatorDatabaseBrowserIT.java). |
| DATA-007 | Restart across separate JVMs, truncate EOF incomplete writes while preserving damaged journals on corruption, compact dead space (with replay/read/write/compaction measurements), enforce locks, and survive rolling replacement. | [`DatabaseStepStoreTest.java`](../../modules/railix-stdlib/src/test/java/dev/nanonative/railix/stdlib/DatabaseStepStoreTest.java), [`DatabaseStepGeneratedApplicationE2eTest.java`](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/DatabaseStepGeneratedApplicationE2eTest.java). |
| DATA-004 | Exercise comparison/range/logical query operators, nested dot-paths & array indexes, ordered secondary indexes, and atomic conditional `upsert`/`delete` across restarts. | [`DatabaseStepStoreTest.java`](../../modules/railix-stdlib/src/test/java/dev/nanonative/railix/stdlib/DatabaseStepStoreTest.java), [`DatabaseStepGeneratedApplicationE2eTest.java`](../../modules/railix-creator/src/test/java/dev/nanonative/railix/creator/DatabaseStepGeneratedApplicationE2eTest.java). Partial updates (`$set`/`$unset`/`$inc`) remain planned. |
| DATA-001 | Create/read/update/delete a record through Creator and read it through a Flow; preserve nested values and exact numbers. | Planned; Creator data-administration UI and authenticated application endpoint remain open. |
| DATA-002 | Try record discovery, read and mutation with insufficient rights, including a direct request bypassing the UI. | Planned permission-matrix tests; granularity and identity contracts are open. |
| DATA-003 | Build without data administration, then with it but observation-only user rights; neither grants data operations. | Planned packaged omission and authorization checks; capability selection is not implemented. |
| Management budgets | Browse/query a large store while normal Flows execute; cancel a costly query and verify released resources. | Planned; representative workload and measurable budgets must be agreed before certification. |
