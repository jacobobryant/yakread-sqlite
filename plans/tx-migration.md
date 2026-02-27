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

## Migration Steps Per Namespace

### 1. Remove Dual-Write Wrappers
Replace `(biffs/dual-write ctx :update ...)` calls with direct SQLite-native TX operations:
- `:update` → `[:patch-docs :table {:xt/id id ...}]` or equivalent
- `:delete-from` → `[:delete-docs :table id]`

### 2. Remove XTDB-Only TX Conditionals
Remove `{:xt ... :sqlite nil}` patterns (e.g., `biffx/assert-unique` calls). SQLite has its own unique constraints in the schema.

### 3. Verify Field Names
Ensure all TX documents use new SQLite field names (see query-migration.md for the full mapping). Most should already be updated from the query migration pass.

### 4. Update `biffs/submit-tx`
Once all namespaces are migrated, simplify `biffs/submit-tx` to only target SQLite, removing the XTDB dual-write logic.

## Namespace Checklist

### App Layer
- [ ] `com.yakread.app.settings` — uses `biffs/dual-write` for user settings updates
- [ ] `com.yakread.app.advertise` — uses `biffs/dual-write` for ad CRUD, `:biff/upsert` for ad-credits
- [ ] `com.yakread.app.subscriptions` — uses `biffs/dual-write` for unsubscribe (`:delete-from`)
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
- [ ] `com.yakread.lib.item` — uses `:put-docs` for content ingestion
- [ ] `com.yakread.lib.fx` — TX handler registration (`:biff.fx/tx` → `biffs/submit-tx`)
- [ ] `com.yakread.util.biff-staging` — Core dual-write translation logic; simplify to SQLite-only

### Migration Utilities
- [ ] `com.yakread.lib.migrate.xtdb2` — uses `:put-docs`, `:delete-docs` for data migration

## Key Considerations

### Dual-Write Removal
The `biffs/dual-write` function in `biff_staging.clj` currently translates between XTDB and SQLite field names and formats. Once the migration is complete:
1. Remove the XTDB code path from `submit-tx`
2. Remove the `xt->sqlite-key` mapping
3. Remove the `:xt`/`:sqlite` conditional TX pattern
4. Simplify `submit-tx` to call SQLite directly

### Unique Constraints
`biffx/assert-unique` is used in SMTP for ensuring sub uniqueness. SQLite handles this via schema-level UNIQUE constraints, so these assertions can be removed.

### ID Generation
`biffs/gen-uuid` is used throughout for generating deterministic UUIDs. This will continue to work with SQLite — no changes needed.

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
