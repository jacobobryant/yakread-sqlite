# XTDB → SQLite Transaction Migration Plan

> **Note:** Update this file based on any feedback received or things learned during the migration process.

## Overview

This plan covers migrating transaction (TX) operations from XTDB-compatible formats to native SQLite operations. The query migration (see `plans/query-migration.md`) has been completed — all namespaces now use `biff/query` instead of `biffx/q`. This phase focuses on the remaining TX operations.

Currently, TX operations go through `biffs/submit-tx` (in `com.yakread.util.biff-staging`), which handles dual-writing to both XTDB and SQLite. The goal is to remove the XTDB path entirely and have TX operations target SQLite only.

## Current TX Operation Formats

The following TX operation formats are used:

### Standard Operations
- **`[:put-docs :table {...}]`** — Insert one or more documents
- **`[:patch-docs :table {:xt/id id, ...}]`** — Partial update (merge into existing)
- **`[:delete-docs :table id1 id2 ...]`** — Delete documents by ID
- **`[:erase-docs :table id1 id2 ...]`** — Hard delete (same as delete for SQLite)
- **`[:biff/upsert :table [:unique-key] {...}]`** — Insert or update by unique constraint

### Dual-Write Operations
- **`(biffs/dual-write ctx :update ...)` / `(biffs/dual-write ctx :delete-from ...)`** — Generates both XTDB and SQLite TX operations
- **`{:xt [...] :sqlite nil}`** — Conditional TX operation (XTDB-only, e.g., `assert-unique`)

## Migration Steps

### 1. Create `:biff.fx/sqlite` fx handler
Create a new fx handler `:biff.fx/sqlite` in `com.yakread.lib.fx` that calls `com.biffweb.sqlite/execute` directly. This bypasses `biffs/submit-tx` and the dual-write layer entirely. The handler accepts a vector of HoneySQL maps and executes them against the SQLite connection.

### 2. Migrate each namespace
For each namespace, replace all TX operations with `:biff.fx/sqlite` calls using HoneySQL maps:
- `[:put-docs :table {...}]` → `{:insert-into :table :values [{...}]}`
- `[:patch-docs :table {:xt/id id ...}]` → `{:update :table :set {...} :where [:= :table/id id]}`
- `[:delete-docs :table id]` → `{:delete-from :table :where [:= :table/id id]}`
- `[:erase-docs :table id]` → same as delete
- `[:biff/upsert :table [:unique-key] {...}]` → `{:insert-into :table :values [{...}] :on-conflict {:unique-key ...} :do-update-set {...}}`
- `(biffs/dual-write ctx :update :table {:where ...} {:set ...})` → `{:update :table :set {...} :where [...]}`
- `(biffs/dual-write ctx :delete-from :table {:where ...})` → `{:delete-from :table :where [...]}`
- Remove `{:xt [...] :sqlite nil}` patterns (e.g., `biffx/assert-unique`). SQLite has schema-level UNIQUE constraints.

Also verify field names match the new SQLite schema (see query-migration.md for the full mapping).

### 3. Clean up old infrastructure
Once all namespaces use `:biff.fx/sqlite`:
1. Remove `:biff.fx/tx` handler and `biffs/submit-tx`
2. Remove the dual-write logic from `biff_staging.clj`
3. Remove the `xt->sqlite-key` mapping

## Namespace Checklist

### App Layer
- [x] `com.yakread.app.settings` — uses `biffs/dual-write` for user settings updates
- [x] `com.yakread.app.advertise` — uses `biffs/dual-write` for ad CRUD, `:biff/upsert` for ad-credits
- [x] `com.yakread.app.subscriptions` — uses `biffs/dual-write` for unsubscribe (`:delete-from`)
- [ ] `com.yakread.app.subscriptions.add` — uses `:patch-docs`, `:put-docs` for feed/sub creation
- [ ] `com.yakread.app.subscriptions.view` — uses `:patch-docs`, `:biff/upsert` for sub settings
- [ ] `com.yakread.app.for-you` — uses `:biff/upsert` for skip records and reclist tracking
- [ ] `com.yakread.app.admin.discover` — uses `:patch-docs` for feed moderation
- [ ] `com.yakread.app.admin.advertise` — uses `:patch-docs`, `biffs/dual-write` for ad approval

### UI Components
- [ ] `com.yakread.ui-components.item.read` — uses `biffs/dual-write` for read tracking (user-item updates)

### Work Layer (Background Jobs)
- [ ] `com.yakread.work.materialized-views` — uses `:biff/upsert` for mv-sub and mv-user
- [ ] `com.yakread.work.train` — uses `:put-docs` for candidate items
- [ ] `com.yakread.work.account` — uses `:erase-docs`, `:delete-docs`, `:put-docs` for account deletion
- [ ] `com.yakread.work.digest` — uses `:put-docs`, `:patch-docs` for digest records and user updates
- [ ] `com.yakread.work.subscription` — uses `:put-docs`, `:patch-docs` for feed sync and item ingestion

### SMTP
- [ ] `com.yakread.smtp` — uses `:put-docs` for email items and subs, plus `biffx/assert-unique`

### Library/Infrastructure
- [x] `com.yakread.lib.fx` — Create `:biff.fx/sqlite` handler calling `com.biffweb.sqlite/execute`
- [ ] `com.yakread.lib.item` — uses `:put-docs` for content ingestion
- [ ] `com.yakread.util.biff-staging` — Remove dual-write logic once all namespaces migrated

### Migration Utilities
- [ ] `com.yakread.lib.migrate.xtdb2` — uses `:put-docs`, `:delete-docs` for data migration

## Key Considerations

### Infrastructure Cleanup
Once all namespaces use `:biff.fx/sqlite`, remove the old infrastructure:
1. Remove `biffs/submit-tx` and the `:biff.fx/tx` handler
2. Remove the `xt->sqlite-key` mapping from `biff_staging.clj`
3. Remove the `:xt`/`:sqlite` conditional TX pattern
4. Remove `biffs/dual-write` — no longer needed

### Unique Constraints
`biffx/assert-unique` is used in SMTP for ensuring sub uniqueness. SQLite handles this via schema-level UNIQUE constraints, so these assertions can be removed.

When using `ON CONFLICT` / upsert patterns in `:biff.fx/sqlite`, the target column(s) must have a UNIQUE constraint in the schema. Add `:biff/unique` to the table definition in `model/schema.clj`:
```clojure
:my-table [:map {:closed true
                 :biff/unique [[:my-table/some-col]]}
           ...]
```
Then regenerate `resources/schema.sql`. For composite uniqueness constraints, use multiple keys in the inner vector: `:biff/unique [[:col-a :col-b]]`.

### ID Generation
`biffs/gen-uuid` was needed for XTDB 2 which required IDs to be prefixed in a certain way to ensure good locality. For SQLite, this is not needed — use regular random UUIDs (e.g., `(random-uuid)`) instead. All usages of `biffs/gen-uuid` should be replaced with `(random-uuid)`.

### Timestamps in TX Documents
TX documents already use the correct timestamp format (ZonedDateTime or Instant) which `submit-tx` coerces for SQLite. After migration, ensure all timestamps are Instant-compatible.

## Reference Commands

```bash
# Run all tests
clojure -X:run com.yakread.lib.test/run-examples!

# Run tests for a specific file
clojure -X:run com.yakread.lib.test/run-examples! :ext '"filename_test.edn"'
```

## Reference Files
- TX handler: `src/com/yakread/lib/fx.clj` (`:biff.fx/tx` registration)
- Dual-write logic: `src/com/yakread/util/biff_staging.clj` (`submit-tx`, `dual-write`)
- New schema: `src/com/yakread/model/schema.clj`
- Query migration plan: `plans/query-migration.md`
