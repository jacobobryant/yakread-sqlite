# XTDB → SQLite Query Migration Plan

> **Note:** Update this file based on any feedback received or things learned during the migration process.

## Overview

This app is being migrated from XTDB 2 to SQLite. The schema and base infrastructure are set up in `com.yakread.model.schema` (new SQLite schema) and `com.yakread.model.old-schema` (old XTDB schema). The migration is done namespace by namespace.

## Key Schema Differences

### ID Fields
- Old: `:xt/id` (universal for all tables)
- New: `:table/id` (e.g., `:user/id`, `:feed/id`, `:sub/id`, `:item/id`, `:ad/id`, `:user-item/id`)

### Reference Fields (foreign keys)
- Old: Used dot-namespaced or short names (e.g., `:sub/user`, `:sub.feed/feed`, `:ad/user`, `:user-item/user`, `:user-item/item`)
- New: Suffixed with `-id` (e.g., `:sub/user-id`, `:sub/feed-id`, `:ad/user-id`, `:user-item/user-id`, `:user-item/item-id`)

### Timestamps
- Old: `ZonedDateTime` (via `tick/zoned-date-time?`, aka `::zdt`)
- New: `Instant` (via `inst?`)
- In tests: `(t/zdt 2000)` → `(t/instant 2000)`, results show `#time/instant` instead of `#time/zoned-date-time`

### Enum Values
- Old: Bare keywords (e.g., `:pending`, `:approved`, `:rejected`, `:quarter`, `:annual`)
- New: Namespaced keywords matching `:table.column-name/value` (e.g., `:ad.approve-state/pending`, `:ad.approve-state/approved`, `:user.plan/quarter`, `:feed.moderation/approved`, `:sub.record-type/feed`)

### Nested Map Fields
- `:ad/card-details` keys changed: `:exp-year`/`:exp-month` (kebab-case) → `:exp_year`/`:exp_month` (snake_case, matching Stripe API)

### Other Renames
- `:user/timezone*` → `:user/timezone`
- `:sub.email/from` → `:sub/email-from`
- `:sub.email/unsubscribed-at` → `:sub/email-unsubscribed-at`
- `:item.feed/feed` → `:item/feed-id`
- `:item.feed/guid` → `:item/feed-guid`
- `:item.email/sub` → `:item/email-sub-id`
- `:item.email/*` → `:item/email-*`
- `:item/doc-type [:= :item/direct]` → `:item/record-type [:enum ...]`
- `:item.direct/candidate-status` → `:item/direct-candidate-status`
- `:digest/user` → `:digest/user-id`, `:digest/subject` → `:digest/subject-id`, `:digest/ad` → `:digest/ad-id`, `:digest/bulk-send` → `:digest/bulk-send-id`
- `:ad.click/*` → `:ad-click/*` (table name normalized)
- `:ad.credit/*` → `:ad-credit/*` (table name normalized)
- `:mv.sub/*` → `:mv-sub/*`, `:mv.user/*` → `:mv-user/*`

## Migration Steps Per Namespace

### 1. Identify Query Calls
Look for anywhere `:biff/conn` is used, specifically `biffx/q conn` calls. These need to be converted to use `:biff/query`.

### 2. Convert Queries
- **Old pattern:** `(biffx/q conn {:select ... :from ... :where ...})`
- **New pattern:** `(query {:select ... :from ... :where ...})` where `query` is destructured from `{:keys [biff/query]}` or `{:biff/keys [query]}`
- Update `:select :xt/id` → `:select :table/id` (or `:select 1` if only checking existence)
- Update column names in `:where` clauses to new schema
- Update destructuring of query results (e.g., `{ad-id :xt/id}` → `{ad-id :ad/id}`)

### 3. Update Field Names Throughout
Even in non-query code (TX operations, data access, etc.), update field names to match the new schema:
- Reference fields: add `-id` suffix
- Enum values: use namespaced keywords
- ID fields: `:xt/id` → `:table/id` in TX docs
- **Exception:** Pathom query specs and pathom-resolved data may still use `:xt/id` — leave these unchanged

### 4. Update Imports
- Remove `[com.biffweb.experimental :as biffx]` if no longer needed
- Remove other unused imports (e.g., `clojure.set` if rename-keys no longer needed)

### 5. Update Test Files
- Change `:db` to `:db*` for test data seeding (switches from XTDB to SQLite)
- Update seeded data keys to new schema (e.g., `:xt/id` → `:table/id`, reference fields with `-id` suffix)
- Change `(t/zdt year)` → `(t/instant year)` for timestamp fields
- Run the test runner to regenerate expected results: `clojure -X:run com.yakread.lib.test/run-examples! :ext '"filename_test.edn"'`
- Review regenerated results and commit

### 6. Don't Worry About (for now)
- TX operation formats (dual-write, upsert syntax, etc.) — these will be addressed in a separate pass
- Pathom resolver internals — they have their own migration path
- The `biffs/dual-write` helper calls — leave as-is

## Completed Migrations
- [x] `com.yakread.app.subscriptions.add` (and `add_test.edn`)
- [x] `com.yakread.app.subscriptions.view` (and `view_test.edn`)
- [x] `com.yakread.app.advertise` (and `advertise_test.edn`)

## Reference Commands

```bash
# Run all tests
clojure -X:run com.yakread.lib.test/run-examples!

# Run tests for a specific file
clojure -X:run com.yakread.lib.test/run-examples! :ext '"filename_test.edn"'

# Check tests haven't changed (CI check)
scripts/check-tests.sh
```

## Reference Files
- New schema: `src/com/yakread/model/schema.clj`
- Old schema: `src/com/yakread/model/old_schema.clj`
- Example migrated namespace: `src/com/yakread/app/subscriptions/add.clj`
- Example migrated tests: `test/com/yakread/app/subscriptions/add_test.edn`
