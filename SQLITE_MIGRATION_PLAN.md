# SQLite Migration Plan

Plan for migrating Yakread from XTDB v2 to SQLite.

## Overview

The SQLite schema and resolver infrastructure already exist in `com.yakread.lib.sqlite`. The main work involves replacing XTDB-specific code paths throughout the application to use SQLite via next.jdbc/HoneySQL instead of `biffx/q` and XTDB transaction operations.

## Phase 1: Core Infrastructure

### 1.1 Switch Pathom resolvers from xtdb2 to sqlite
- In `com.yakread`, replace `(biffs/xtdb2-resolvers malli-opts)` with `(lib.sqlite/sqlite-resolvers lib.sqlite/malli-opts)` in the `pathom-env` def
- The `sqlite-resolvers` function in `lib/sqlite.clj` is already implemented and generates batch Pathom resolvers from the SQLite malli schema

### 1.2 Rewrite transaction submission (`biff.fx/tx`)
- In `lib/fx.clj`, the `:biff.fx/tx` handler currently maps to `biffs/submit-tx`
- `biffs/submit-tx` calls `biffx/submit-tx` which uses XTDB transaction operations like `:put-docs`, `:erase-docs`, `:update`
- Need to implement a new `submit-sqlite-tx` function that translates these operations to SQL:
  - `:put-docs` / `:biff/upsert` → `INSERT OR REPLACE INTO ... VALUES ...` (with write coercions from `lib.sqlite/build-coercions`)
  - `:erase-docs` → `DELETE FROM ... WHERE id = ...`
  - `:update` → `UPDATE ... SET ... WHERE ...`
  - `:biff/assert-query` → `SELECT` assertion check before proceeding
- Wire the new function into `fx/handlers`

### 1.3 Replace `biffx/q` with next.jdbc queries
- Create a helper function (e.g. `lib.sqlite/q`) that:
  - Takes a datasource and a HoneySQL query map
  - Applies read coercions automatically based on the table being queried
  - Returns results with proper Clojure types (UUIDs, Instants, keywords for enums, etc.)
- This helper should handle the common patterns: `{:select ... :from ... :where ...}`

## Phase 2: Schema and Query Migration

### 2.1 Unify schema definitions
- The XTDB schema lives in `model/schema.clj` (uses `:xt/id`)
- The SQLite schema lives in `lib/sqlite.clj` (uses `:table/id`)
- Decide on one source of truth — likely `lib/sqlite.clj` since that's the target
- Update any code that references `model/schema.clj` XTDB-specific patterns

### 2.2 Migrate model files (9+ files)
Each model file contains resolvers and query functions that use `biffx/q`. These need to be converted to HoneySQL:

- `model/ad.clj` — Ad management queries and mutations
- `model/admin.clj` — Admin dashboard queries
- `model/digest.clj` — Digest data fetching
- `model/item.clj` — Core item CRUD operations
- `model/moderation.clj` — Content moderation queries
- `model/recommend.clj` — Recommendation engine queries (complex joins)
- `model/subscription.clj` — Subscription listing and mutations
- `model/user.clj` — User profile operations
- `model/feed.clj` — Feed data queries

**Approach**: For each file:
1. Identify all `biffx/q` calls and their query patterns
2. Rewrite as HoneySQL maps with the `lib.sqlite/q` helper
3. Verify type coercions are handled (UUID, Instant, enum, boolean)
4. Update any direct XTDB document manipulation to SQL equivalents

### 2.3 Migrate work files (5 files)
Background job machines that do database reads and writes:

- `work/subscription.clj` — Feed sync, item ingestion (heaviest DB usage)
- `work/account.clj` — Account deletion and cleanup
- `work/train.clj` — ML training data preparation
- `work/digest.clj` — Digest generation and bulk sending
- `work/materialized_views.clj` — Materialized view maintenance (complex upserts)

### 2.4 Migrate app/route handlers (15+ files)
HTTP handler files that perform database operations:

- `app/for_you.clj`, `app/settings.clj`, `app/subscriptions/*.clj`
- `app/advertise.clj`, `app/admin/*.clj`
- `smtp.clj` — Email ingestion
- Various UI component files

## Phase 3: Test Infrastructure

### 3.1 Replace XTDB test node with SQLite
- In `lib/test.clj`, replace `start-test-node` (which creates an in-memory XTDB node) with an in-memory SQLite database
- Use `jdbc:sqlite::memory:` for test databases
- Run the schema DDL (`lib.sqlite/generate-schema-sql`) to create tables in the test DB
- Update `submit-tx` to use SQL INSERT/UPDATE instead of `xt/submit-tx`

### 3.2 Update `with-node` macro
- Currently seeds data using XTDB `put-docs` format: `{:table [{:xt/id ... :field/value ...}]}`
- Need to convert to SQL inserts with proper write coercions
- The seeded data format may need to change from `:xt/id` to `:table/id`

### 3.3 Update test data format
- Test `.edn` files may reference `:xt/id` — need to update to `:table/id`
- UUID comparisons may change since SQLite stores UUIDs as bytes
- Instant comparisons will use epoch milliseconds

## Phase 4: Type Coercion Handling

### 4.1 Write coercions (Clojure → SQLite)
Already implemented in `lib/sqlite.clj`:
- UUID → 16-byte array (`uuid->bytes`)
- Instant → epoch milliseconds (`inst->epoch-ms`)
- Boolean → 0/1 integer (`bool->int`)
- Set/Vector/Map → nippy-frozen bytes (`fast-freeze`)
- Enum keywords → integer codes (`make-enum-writer`)

### 4.2 Read coercions (SQLite → Clojure)
Already implemented in `lib/sqlite.clj`:
- Byte array → UUID (`bytes->uuid`)
- Epoch ms → Instant (`epoch-ms->inst`)
- 0/1 → Boolean (`int->bool`)
- Frozen bytes → Set/Vector/Map (`fast-thaw`)
- Integer codes → enum keywords (`make-enum-reader`)

### 4.3 Integration points
- Ensure all query results go through read coercions
- Ensure all write operations apply write coercions
- The `make-column-reader` function handles read coercions for `next.jdbc` result sets
- Write coercions need to be applied before constructing SQL INSERT/UPDATE statements

## Phase 5: Cleanup

### 5.1 Remove XTDB dependencies
- Remove `com.xtdb/xtdb-api`, `com.xtdb/xtdb-core`, `com.xtdb/xtdb-aws`, `com.xtdb/xtdb-kafka` from `deps.edn`
- Remove XTDB-related config from `resources/config.edn` (`:biff.xtdb2/*` keys)
- Remove `biffx/use-xtdb2` and `biffx/use-xtdb2-listener` references

### 5.2 Remove XTDB utility code
- `util/biff_staging.clj` — Remove `xtdb2-resolvers`, `doc-asts`, and related functions
- `model/schema.clj` — Remove if fully replaced by `lib/sqlite.clj` schema
- `model/old_schema.clj` — Remove legacy schema

### 5.3 Database initialization
- Add SQLite schema migration to the startup sequence
- Use `sqlite3def` or a custom migration approach to apply schema changes
- Ensure `storage/sqlite/` directory is created on startup

## Migration Order (Recommended)

1. **Core infra** (Phase 1) — Get the basic read/write path working with SQLite
2. **Test infra** (Phase 3) — So we can validate changes as we go
3. **Models** (Phase 2.2) — Migrate model files one at a time, running tests after each
4. **Work files** (Phase 2.3) — Migrate background jobs
5. **App handlers** (Phase 2.4) — Migrate HTTP handlers
6. **Schema unification** (Phase 2.1) — Remove duplicate schema definitions
7. **Cleanup** (Phase 5) — Remove XTDB deps and dead code

## Key Risks

- **Data migration**: Moving production data from XTDB to SQLite (the `lib/migrate/sqlite/copilot.clj` importer already handles this)
- **Transaction semantics**: XTDB has different consistency guarantees than SQLite — need to verify business logic isn't affected
- **Query expressiveness**: Some XTDB queries may be difficult to express in SQL — particularly temporal queries if any exist
- **Performance**: SQLite has different performance characteristics — the HikariCP pool helps, but some query patterns may need optimization
- **Materialized views**: The `use-xtdb2-listener` drove MV updates reactively — may need a polling or trigger-based approach with SQLite
