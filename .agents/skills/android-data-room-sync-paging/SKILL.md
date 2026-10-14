---
name: android-data-room-sync-paging
description: "Use for Room, DataStore, repositories, cache, sync, or Paging: entities, DAOs, migrations, transactions, source of truth, freshness, outbox, account isolation, local-remote consistency."
---

# Data, Room, DataStore, Sync, Cache, and Paging

## Repository contract first

Before implementation, write or infer:

```text
Authoritative truth:
Observable read path:
One-shot read path:
Write path:
Freshness/stale definition:
Refresh trigger and de-duplication:
Offline behavior:
Conflict resolution:
Retry/idempotency:
Cache key, scope, TTL/validator, invalidation:
Account/logout behavior:
Partial failure behavior:
```

A repository coordinates data sources and owns data strategy. It is not automatically the truth itself: the truth may be Room, server, file, DataStore, or a bounded session owner.

## Select the data strategy

Use the simplest strategy matching product requirements and existing architecture:

- **local-only:** Room/DataStore/file is truth;
- **online-only:** API result is ephemeral; no fake cache;
- **network-first:** request remote, optionally fall back to valid cache;
- **cache-first:** serve valid cache and refresh by policy;
- **stale-while-revalidate:** observe cached truth, refresh in background, expose stale/refresh state;
- **offline-first:** UI observes local DB; remote refresh/mutations reconcile into DB;
- **write-through:** mutation updates server/local according to a transactional policy;
- **outbox:** local mutation records pending operation for durable sync.

Do not declare offline-first if the implementation reads API responses directly into UI, drops local edits on refresh, or lacks conflict/retry metadata.

## Model boundaries

Separate DTO/Entity/Domain/UI models when contracts differ.

**DTO:** wire names, optional fields, compatibility, unknown enums.

**Entity:** schema, indexes, foreign keys, local IDs, tombstones/sync metadata.

**Domain:** business meaning/invariants.

**UI:** display formatting and interaction state.

Mapping guidance:

- do not persist raw response models merely for convenience when schema must evolve independently;
- do not expose Room entities or transport exceptions above the repository boundary;
- preserve unknown server enum values safely instead of crashing or silently mapping to an unrelated value;
- distinguish absent, null, default, and malformed fields;
- centralize timestamp/time-zone conversion;
- never include credentials/tokens in data-class logging or UI models.

For a very small local-only feature, one model may be reasonable. Document why contracts are identical.

## Room

### Entities and schema

- stable table/column names;
- explicit primary keys and conflict policy;
- indexes for measured/hot query predicates and join keys;
- foreign keys and cascade behavior chosen intentionally;
- converters deterministic and migration-aware;
- no large blobs when a file/URI is more appropriate;
- account/user scope represented so data cannot cross accounts;
- sync metadata only when the workflow requires it.

### DAO API

- `Flow` for observable query results;
- suspend/one-shot query for snapshots/checks/mutations;
- no DAO returning UI/domain formatting;
- parameterized queries only;
- avoid N+1 by joins, relations, batched IDs, or query redesign;
- avoid unbounded table reads and in-memory filtering for large data;
- define ordering explicitly;
- avoid emitting mutable lists/entities that callers can change.

Room already dispatches generated suspend and observable query work appropriately. Do not wrap every DAO call in IO. CPU-heavy mapping can be moved separately if profiling/tests justify it.

### Transactions

Use a DAO `@Transaction` method or `RoomDatabase.withTransaction` for changes that must be atomic:

- parent plus children;
- remote data plus remote keys/sync metadata;
- replace/merge that must not expose a half-state;
- outbox record plus local mutation;
- conflict resolution updates.

Do not call a fictional `dao.withTransaction` unless the project defines that extension.

Avoid delete-all/insert-all when it can erase local pending edits, break references, reset metadata, or cause UI flicker. Merge by stable key and preserve local-only fields.

### Migrations

- never use destructive migration for user data without explicit approval;
- bump schema/version intentionally;
- handle rename, default, nullability, index, foreign key, and converter changes;
- use auto migrations only when they express the real change safely;
- export schemas if the project does;
- add migration tests from every supported upgrade path or at least the oldest supported schema chain;
- verify downgrade policy explicitly;
- preserve encrypted/database-key strategy.

## Destination-specific Room schema evolution gate

Run this gate for **every edit**, even when the expected answer is "no persistence impact." A schema-affecting edit includes changes to `@Entity`, `@Database`, embedded/related persisted fields, table or column names, primary/foreign keys, indices, nullability/defaults, type converters, database views/FTS, raw SQL schema, triggers, or a prepackaged database. DAO query-only changes do not automatically change the schema, but still require compatibility review.

If the edit does **not** change the persisted Room schema:

- leave `FOCUS_DATABASE_VERSION` unchanged;
- do not create an empty migration or regenerate/rewrite schema history;
- state `Room schema: unchanged` in the final audit.

If the edit changes the persisted Room schema, all of the following are mandatory in the same patch:

1. Treat the checked-in previous schema JSON as the immutable old contract. Never edit, replace, or delete a schema that may represent a shipped build.
2. Increment `FOCUS_DATABASE_VERSION` exactly once from `N` to `N + 1`; do not skip a version to conceal missing migration work.
3. Add an explicit, deterministic `FocusMigrationsV{N}To{N+1}` migration (using the repository's numeric filename convention), and register it chronologically in `FocusMigrations.ALL`. Confirm production still reaches it through `FocusDatabaseProvider.create()` and `.addMigrations(*FocusMigrations.ALL)`.
4. Prefer a manual migration when data must be renamed, transformed, normalized, deduplicated, split, merged, or backfilled. Use Room auto-migration only when its generated operations exactly preserve the intended contract; use a spec for supported rename/delete cases and test the generated path.
5. Define the old-to-new mapping before SQL: defaults/backfill, nullability, units, enum/converter compatibility, ID preservation, duplicate policy, indices, foreign keys/cascades, triggers, views, and large-table cost. Migration code is local, synchronous, deterministic, bounded, and independent of network, UI, DI services, asynchronous work, or current account/session state.
6. Preserve every user-owned row and policy unless the user explicitly approves a documented destructive transformation. Never add `fallbackToDestructiveMigration`, catch an open failure and recreate, drop/recreate a populated table without a proven copy/verification sequence, or convert corrupt/unknown data to fake success.
7. Export and commit the new `app/schemas/com.ankit.destination.data.FocusDatabase/N+1.json`. Review the generated schema diff against the intended change. Keep all supported origin schemas and the entire contiguous production migration chain.
8. Update `RoomMigrationMatrixInstrumentedTest` and focused migration tests. Test the direct `N -> N + 1` step and every supported retained/shipped origin -> current chain. Seed representative non-default rows before migration, including null/legacy/boundary values relevant to the change.
9. Assert more than successful open: schema identity/version, row counts, field values/backfills, primary/foreign keys, indices, uniqueness, `PRAGMA foreign_key_check`, trigger behavior, converter/enum compatibility, and a post-migration DAO read/write on the affected path.
10. Test both fresh database creation and upgrade of an existing database. Run the schema-export guard plus connected migration instrumentation on an emulator/device. If no target is available, report migration runtime proof as unverified; compilation or JVM tests alone cannot establish crashless upgrade behavior.
11. Define downgrade/rollback behavior. Assume an older binary may be unable to open a newer schema; use staged rollout and forward-fix planning where rollback is not schema-safe. Never advertise a rollback as safe without testing it.
12. Update `project.md` with the current database version, retained schema artifacts, migration test command/support floor, and any rollout constraint. Update `architecture.md` if database authority, storage location, ownership, transactions, or source-of-truth behavior changed.

Before completion, search production builders for destructive fallback, search every migration registration/call site, and verify there is no second builder or variant that omits the new migration. A migration is not "seamless" or "crashless" merely because source compiles; claim only the evidence actually run.

## DataStore and preferences

Use DataStore for small key/value configuration or typed preferences, not relational lists, caches, or large documents.

- one owner/instance per file;
- expose Flow and suspend edits through repository/settings owner;
- define defaults centrally;
- migrate legacy SharedPreferences with a tested migration and failure policy;
- avoid reads directly in Composables;
- avoid duplicated string keys across features;
- use Proto/typed schema when evolution and type safety justify it;
- corruption handler must not silently erase important user state;
- DataStore is not secure secret storage; use approved encrypted/keystore-backed solution for credentials.

## Cache design

Every cache needs:

```text
Key dimensions:
Value/model:
Owner and lifetime:
Maximum size:
Freshness (TTL/ETag/version):
Invalidation events:
Account/session scope:
Concurrent load policy:
Failure/stale behavior:
```

Hazards:

- key omits locale, account, filters, permissions, or version;
- mutable singleton with no invalidation;
- stale data survives logout/account switch;
- cache failure is treated as fresh success;
- memory cache duplicates Room without a measured need;
- unbounded LRU/map or bitmap cache;
- each collector starts a refresh;
- failed refresh deletes usable stale data.

Use HTTP validators/ETags/version tokens when the API supports them. Keep cache metadata transactional with cached content when consistency requires it.

## Offline-first reads

A common shape:

1. UI observes Room.
2. ViewModel/repository triggers guarded refresh by product policy.
3. Repository fetches remote.
4. Repository maps and writes atomically.
5. Room re-emits.
6. Refresh failure preserves stale content and exposes refresh error/stale metadata.

Do not emit a second competing remote list as screen truth.

Freshness may depend on time, app/session event, user action, server validator, push event, or data version. Inject/test clock only when freshness logic needs determinism.

## Offline writes and outbox

For a mutation that must work offline:

- apply a local optimistic/pending state transactionally;
- create a stable operation/idempotency ID;
- record payload/version needed for sync;
- schedule unique durable work;
- reconcile server response by operation/entity ID;
- retain actionable failure instead of silently dropping;
- support cancellation/undo only if server/local semantics permit;
- define tombstones for offline delete when needed.

Outbox states may include pending, in-flight, succeeded, retryable failure, permanent failure, and conflict. Avoid an unbounded dead-letter table without UX/cleanup policy.

## Conflict resolution

Choose per entity/workflow, not globally:

- server wins;
- client wins;
- last-write-wins using trusted comparable version/time;
- field-level merge;
- version-vector/revision check;
- manual resolution;
- reject and reload.

Never silently overwrite local pending edits. Server/client clocks may not be comparable; prefer server revision/version where available. Test concurrent edit, delete/update conflict, retry after partial sync, and account switch.

## Retry and sync

- classify transient versus permanent failures;
- bounded exponential backoff with jitter;
- respect Retry-After/server policy;
- retry only idempotent/protected operations;
- surface partial counts and permanent failures;
- de-duplicate unique work and concurrent manual refresh;
- do not assume connectivity callback means internet/API success;
- avoid auto-sync on metered/roaming/low battery when product or payload requires user control;
- cancel/invalidate account-scoped work on logout;
- avoid leaked repository scopes.

## Paging

Preserve the project’s Paging version/API pattern; do not introduce version-specific experimental presenter APIs unless already used/requested.

Boundary rules:

- `Pager`/`PagingSource`/`RemoteMediator` live in data layer;
- ViewModel owns screen query/filter and caches/shares paging stream in its scope when appropriate;
- UI collects paging data and renders load states/retry/empty;
- stable item keys/content types;
- no manual append requests that race Paging unless the chosen API explicitly requires it;
- search/query changes cancel old read pipeline, not in-flight writes;
- invalidation source is correct and not refreshed on every collector.

`RemoteMediator`:

- local DB is displayed truth;
- remote keys and entity writes are one transaction;
- refresh/append/prepend keys are deterministic;
- end-of-pagination is correct;
- refresh does not erase pending local data;
- error maps to retryable/permanent policy;
- query/account/cache namespace is part of keying.

## Repository errors

Map storage/network implementation failures into stable data/domain failures. Preserve useful internal cause/code for diagnostics while exposing UI-safe categories. Cancellation remains cancellation.

Do not catch everything and return empty list; empty is valid data, not a failure substitute.

## Testing

Test:

- DAO observation and ordering;
- transaction atomicity;
- migration paths;
- refresh de-duplication and stale preservation;
- cache key/TTL/account invalidation;
- offline mutation/outbox retry/idempotency;
- conflict policy;
- partial failure;
- DataStore migration/corruption/defaults;
- Paging keys, load states, invalidation, RemoteMediator transactions;
- logout clearing/cancelling account-scoped data.

## Anti-patterns

- UI/ViewModel imports DAO, Retrofit response, `HttpException`, or Entity;
- repository called the “single source of truth” while holding a second mutable cache;
- refresh on every collector;
- `fallbackToDestructiveMigration` in production;
- delete-all/insert-all erases pending edits;
- relational data in preferences;
- secrets in plain DataStore;
- account-blind cache;
- unbounded retry/outbox/cache;
- network status used as certainty;
- `RemoteMediator` writes keys and data outside one transaction;
- Paging collected/owned in the wrong layer;
- empty list returned on exception;
- DAO transaction API that does not exist.


## Deep implementation protocol

### Repository behavior contract

Before implementation, complete the relevant cells:

| Concern | Decision |
|---|---|
| authoritative read truth | Room / server / DataStore / file / memory session |
| first render | local immediately / blocking remote / empty until remote |
| freshness | TTL/version/ETag/manual/no cache |
| refresh trigger and owner | explicit action/start policy/worker/push |
| offline read | cached/stale/unsupported |
| offline mutation | reject/optimistic/outbox |
| conflict policy | server wins/client wins/version/merge/user choice |
| account/tenant partition | keying and purge rule |
| transaction boundary | exact writes that must commit together |
| deletion/tombstone rule | hard/soft/server-confirmed |
| retry/idempotency | key, attempts, backoff, terminal state |

The UI must be able to distinguish loading, empty, stale/offline content, refresh failure with retained content, blocking failure, pending mutation, and conflict when the product supports them.

### Schema and migration discipline

A Room schema change requires an explicit old-to-new mapping, defaults/backfill policy, index/foreign-key impact, downgrade expectation, and migration test from every supported upgrade origin required by the app. Verify actual rows and constraints after migration, not only that the database opens.

### Cache and sync discipline

Every cache needs scope, key, capacity, freshness, invalidation, account boundary, and source precedence. An outbox needs a stable operation ID, payload version, attempt metadata, idempotency strategy, and reconciliation rule. Sync metadata and entity updates that define one visible state must be transactional.

## AI-generated code hazards

- repository returns network response while Room is separately treated as truth, producing flicker and divergence.
- DAO and API called directly from ViewModel/UI to “simplify” one screen.
- `fallbackToDestructiveMigration` or schema version bump without migration.
- `REPLACE` used without considering foreign keys, row IDs, triggers, or partial field loss.
- multi-table refresh outside a database transaction.
- delete-all then insert, exposing empty intermediate state or data loss on failure.
- `runInTransaction`/`withTransaction` called on the wrong type or around suspending/network work incorrectly.
- DataStore used as a relational/query store or for large mutable collections.
- mutable process cache with no account key, invalidation, TTL, or memory limit.
- every collector triggers refresh, causing request storms.
- retries duplicate non-idempotent writes or reorder outbox operations.
- conflict silently resolved by last callback arrival rather than domain version/policy.
- Paging source transformed or cached at the wrong scope, or RemoteMediator keys/entities updated non-transactionally.
- tests create a fresh schema only and never exercise migration or corrupt/partial data.

## Post-change audit

### Data integrity checks

- Trace all reads and writes for the entity/key and confirm one authoritative path.
- Inspect generated Room schema diff where available.
- Run migration tests with representative nulls, old enum values, duplicates, foreign keys, and large data where relevant.
- Query the post-migration database directly for row counts, values, indices, and constraints.
- Inject refresh failure between remote success and local commit; visible truth must remain coherent.
- Test account switch/logout so old-tenant rows, cache, paging keys, and pending work cannot leak.
- Test offline launch, offline refresh, reconnect, duplicate sync, retry after partial success, and conflict.
- Confirm DataStore edits are atomic and default/corrupt/unknown values are handled.

### Paging checks

Verify initial load, refresh, append, end-of-pagination, retry, invalidation, reorder, deletion, remote-key reset, and retained cached content. Confirm one Pager/cache owner and no new Pager per recomposition or collector.

### Evidence record

```text
Truth and source strategy:
Schema/migration versions tested:
Transaction boundary proven:
Offline launch/refresh result:
Duplicate retry/idempotency result:
Account isolation result:
Paging edge cases:
Data checks not run:
```
