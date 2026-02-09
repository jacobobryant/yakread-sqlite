# SQLite Dual-Write Migration Plan

## Files with `:biff.fx/tx` call sites that need updating

These files use HoneySQL maps (`:update`, `:delete-from`, `:assert`, `:delete`) in their `:biff.fx/tx` values.
Each HoneySQL map needs to be wrapped in `{:xt <xt-honeysql> :sqlite <sqlite-honeysql>}`.

Note: Files that use only XTQL operations (`:put-docs`, `:patch-docs`, `:delete-docs`, `:erase-docs`)
do NOT need updating — the `submit-tx` function handles translating those to sqlite automatically.

Custom tx-ops (`:biff/upsert`, `:biff/assert-query`) expand into both XTQL ops and HoneySQL maps
during `resolve-tx-ops`, so they also don't need wrapping at call sites. However, the expansion
code in `biff_staging.clj` (`biff-tx-op` methods) does produce HoneySQL maps that will need to
be wrapped — so those methods need to be updated.

### Call-site files needing updates (HoneySQL maps in `:biff.fx/tx`)

- [ ] `src/com/yakread/ui_components/item/read.clj` — 3 `{:update ...}` maps
- [ ] `src/com/yakread/app/for_you.clj` — `[:delete :skip ...]` HoneySQL 
- [ ] `src/com/yakread/app/advertise.clj` — 2 `{:update :ad ...}` maps
- [ ] `src/com/yakread/app/settings.clj` — `{:update :user ...}` maps
- [ ] `src/com/yakread/app/subscriptions.clj` — `{:delete-from ...}`, `{:update ...}` maps
- [ ] `src/com/yakread/app/subscriptions/add.clj` — `{:assert ...}` maps
- [ ] `src/com/yakread/app/subscriptions/view.clj` — `{:update :sub ...}` map
- [ ] `src/com/yakread/app/admin/advertise.clj` — `{:update :ad ...}` maps

### Internal expansion code needing updates

- [ ] `src/com/yakread/util/biff_staging.clj` — `biff-tx-op :biff/upsert` produces `{:update ...}` maps and `[:biff/assert-query ...]` which itself produces `{:assert ...}` maps. The `upsert` function also produces `{:update ...}`.

### Files with only XTQL operations (no changes needed at call sites)

- `src/com/yakread/smtp.clj` — `:put-docs` + `biffx/assert-unique` (already formatted)
- `src/com/yakread/work/subscription.clj` — `:patch-docs`, `:put-docs`
- `src/com/yakread/work/digest.clj` — `:patch-docs`, `:put-docs`
- `src/com/yakread/work/materialized_views.clj` — `:biff/upsert` (custom op)
- `src/com/yakread/work/account.clj` — `:erase-docs`, `:delete-docs`, `:put-docs`
- `src/com/yakread/work/train.clj` — `:put-docs`
- `src/com/yakread/app/admin/discover.clj` — `:patch-docs`
- `src/com/yakread/lib/item.clj` — `:put-docs` (via `biffs/upsert` helper)

### Infrastructure changes

- [ ] Add `:biff/malli-opts*` to `initial-system` (com.yakread) using sqlite schema
- [ ] Update `lib.sqlite/use-sqlite` to use `:biff/malli-opts*` and put pool under `:biff/conn*`
- [ ] Add `lib.sqlite/use-sqlite` to components after `use-xtdb2`
- [ ] Update `submit-tx` in `biff_staging.clj` for dual-write
- [ ] Build xtdb→sqlite key mapping
- [ ] Implement xtql→honeysql translation for sqlite side
