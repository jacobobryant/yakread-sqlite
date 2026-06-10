# com.biffweb.fx migration plan

## Goal

Move Yakread from the old `com.yakread.lib.fx` machine runner to `com.biffweb.fx`.

The old interface returned effects as top-level keys:

```clojure
{:biff.fx/pathom query
 :biff.fx/next :render}
```

The new interface returns effect descriptors as vector values:

```clojure
{:biff.fx/result [:biff.fx/pathom query]
 :biff.fx/next :render}
```

`com.yakread.lib.fx` should keep Yakread-specific effect handlers and route macros, but it must not provide a local
`machine` implementation.

## Route/macro status

- [x] `deps.edn` uses published `com.biffweb/{core,config,fx}` `2.0.0-rc5`.
- [x] `com.yakread.lib.fx/machine` removed.
- [x] `com.yakread.lib.fx/route*` uses `com.biffweb.fx/machine`.
- [x] `com.yakread.lib.fx/defroute-pathom` stores initial Pathom output in `:biff.fx/result`.
- [x] `com.yakread.lib.fx/defroute-pathom` passes `:biff.fx/result` to route methods.
- [x] `com.yakread.lib.fx/defmachine` delegates to `com.biffweb.fx/machine`.

## Namespace inventory

| Namespace | Uses | Read migration | Write migration | Manual verification |
|---|---|---|---|---|
| `com.yakread.app.favorites` | read pages | complete | n/a | Playwright signed in as `hello@obryant.dev`; loaded `/favorites` and `/favorites/content` on systemd app `localhost:8080`, both 200 |
| `com.yakread.app.read-later` | read pages | complete | n/a | Playwright signed in as `hello@obryant.dev`; loaded `/read-later` and `/read-later/content` on systemd app `localhost:8080`, both 200 |
| `com.yakread.app.for-you.history` | read pages | complete | n/a | Playwright signed in as `hello@obryant.dev`; loaded `/history` and `/history/next` on systemd app `localhost:8080`, both 200 |
| `com.yakread.app.admin.monitor` | read admin page | complete | n/a | REPL against systemd app; called `page-route` GET with realistic route match/session and actual pstats, returned monitor body |
| `com.yakread.app.admin.dashboard` | read admin pages, one test-error write-ish route | complete | complete | REPL against systemd app; called read routes earlier, then POSTed `test-error-alert-route` through the route machine |
| `com.yakread.app.admin.impersonate` | read search/page, write session impersonation | complete | complete | REPL against systemd app; called read routes earlier, then POSTed `impersonate-route` through the route machine |
| `com.yakread.app.subscriptions.view` | read pages plus mark-read writes | complete | complete | Playwright read checks; REPL with mocked effects POSTed `mark-read` and `mark-all-read` through `biff.fx/machine` |
| `com.yakread.app.subscriptions` | read pages plus unsubscribe/pin/resubscribe writes | complete | complete | Playwright read checks; REPL with mocked effects POSTed unsubscribe feed/email branches, toggle-pin, and resubscribe |
| `com.yakread.app.for-you` | read pages plus click-recording writes | complete | complete | Playwright read checks; REPL with mocked effects POSTed item/ad click recorders |
| `com.yakread.app.subscriptions.add` | read add page plus subscription writes | complete | complete | Playwright loaded `/subscriptions/add`; REPL with mocked effects POSTed set-username, add-rss, and add-opml |
| `com.yakread.app.favorites.add` | read add page plus add-item writes | complete | complete | Playwright loaded `/favorites/add`; REPL with mocked HTTP/JS/SQLite effects POSTed add-item |
| `com.yakread.app.read-later.add` | read add page plus add-item writes and async machine | complete | complete | Playwright loaded `/read-later/add`; REPL with mocked HTTP/JS/SQLite/queue effects POSTed add-item and add-batch |
| `com.yakread.app.settings` | read settings page plus account/billing/settings writes | complete | complete | Playwright loaded `/settings` and submitted the settings form; REPL with mocked effects POSTed billing/account/webhook/export/delete/unsubscribe routes |
| `com.yakread.app.advertise` | read pages/autocomplete plus ad/payment/image writes | complete | complete | Playwright loaded `/advertise`; REPL with mocked Stripe/S3/Pathom effects POSTed ad payment, save, and upload routes |
| `com.yakread.app.admin.discover` | read admin page plus moderation writes | complete | complete | REPL against systemd app; read routes earlier, then POSTed `save-moderation` with mocked SQLite |
| `com.yakread.app.admin.digest-trigger` | read admin page plus queue/preview/send routes | complete | complete | REPL against systemd app; read routes earlier, then POSTed preview, queue, and send-single routes with mocked job submission |
| `com.yakread.app.admin.email-test` | read admin page plus HTTP/JS/email test machine | complete | complete | REPL against systemd app; read routes earlier, then ran `send-test-email` and POSTed `send-email-route` with mocked HTTP/JS/local SMTP |
| `com.yakread.app.admin.rss-test` | read admin pages/feed plus test RSS create/delete writes | complete | complete | REPL against systemd app; read routes earlier, then POSTed create/delete routes with mocked SQLite |
| `com.yakread.app.admin.advertise` | read admin page plus ad approval/charging writes | complete | complete | REPL against systemd app; read routes earlier, then POSTed update-ad, create-pending-charges, and handle-pending-charges with mocked Stripe/SQLite |
| `com.yakread.ui-components.item.read` | item action write routes | n/a | complete | REPL against systemd app; POSTed mark-unread, toggle-favorite, and not-interested with mocked Pathom/SQLite |
| `com.yakread.model.ad` | Stripe status read machine with HTTP effect | complete | n/a | REPL against systemd app with mocked `:biff.fx/http`; `get-stripe-status` returned pending charge status |
| `com.yakread.smtp` | inbound email write machine | n/a | complete | REPL against systemd app; ran `deliver*` with parsed-message-shaped input and mocked JS/S3/SQLite |
| `com.yakread.work.train` | queue and ingest write machines | n/a | complete | REPL against systemd app; ran add-candidate and queue-add-candidate with mocked HTTP/JS/SQLite/queue |
| `com.yakread.work.account` | export/delete account write machines | n/a | complete | REPL against systemd app; ran export-user-data and delete-account with mocked Pathom/files/S3/Stripe/SQLite/email |
| `com.yakread.work.digest` | digest queue/send write machines | n/a | complete | REPL against systemd app; ran queue-prepare-digest, prepare-digest, and send-digest with mocked Pathom/queue/drain/http/SQLite/sleep |
| `com.yakread.work.subscription` | feed sync queue/write machines | n/a | complete | REPL against systemd app; ran sync-all-feeds and sync-feed with mocked query/http/S3/SQLite |

## Read migration batches

### Batch R1: simple public/signed-in pages

- [x] `com.yakread.app.favorites`
- [x] `com.yakread.app.read-later`
- [x] `com.yakread.app.for-you.history`

### Batch R2: admin read pages

- [x] `com.yakread.app.admin.monitor`
- [x] `com.yakread.app.admin.dashboard` read routes only: `page-route`, `page-content-route`, `logs-page`
- [x] `com.yakread.app.admin.impersonate` read routes only: `page-route`, `search-route`

### Batch R3: pages with nested read Pathom effects

- [x] `com.yakread.app.subscriptions.view` read routes only: `read-content-route`, `read-page-route`, `page-content-route`, `page-route`
- [x] `com.yakread.app.for-you` read routes only: `page-content-route`, `page-route`, `read-page-route`

### Batch R4: mixed namespaces, read page routes only

- [x] `com.yakread.app.subscriptions`
- [x] `com.yakread.app.subscriptions.add`
- [x] `com.yakread.app.favorites.add`
- [x] `com.yakread.app.read-later.add`
- [x] `com.yakread.app.settings`
- [x] `com.yakread.app.advertise`
- [x] `com.yakread.app.admin.discover`
- [x] `com.yakread.app.admin.digest-trigger`
- [x] `com.yakread.app.admin.email-test`
- [x] `com.yakread.app.admin.rss-test`
- [x] `com.yakread.app.admin.advertise`
- [x] `com.yakread.model.ad`

## Write migration batches

### Batch W1: signed-in browser-facing routes

- [x] `com.yakread.ui-components.item.read`
- [x] `com.yakread.app.subscriptions.view`
- [x] `com.yakread.app.subscriptions`
- [x] `com.yakread.app.subscriptions.add`
- [x] `com.yakread.app.favorites.add`
- [x] `com.yakread.app.read-later.add`
- [x] `com.yakread.app.settings`
- [x] `com.yakread.app.for-you`
- [x] `com.yakread.app.advertise`

### Batch W2: admin routes

- [x] `com.yakread.app.admin.dashboard`
- [x] `com.yakread.app.admin.impersonate`
- [x] `com.yakread.app.admin.discover`
- [x] `com.yakread.app.admin.digest-trigger`
- [x] `com.yakread.app.admin.email-test`
- [x] `com.yakread.app.admin.rss-test`
- [x] `com.yakread.app.admin.advertise`

### Batch W3: background machines

- [x] `com.yakread.smtp`
- [x] `com.yakread.work.train`
- [x] `com.yakread.work.account`
- [x] `com.yakread.work.digest`
- [x] `com.yakread.work.subscription`

## Verification notes

Manual verification should use the running app whenever the route has a browser-visible page. For background/model-only
read machines, use direct REPL evaluation with realistic context and mocked external HTTP where needed.

Each namespace is marked complete only after:

1. Its read-only machines/routes use the new descriptor format.
2. The namespace compiles with `com.biffweb.fx`.
3. A browser or REPL check exercises the migrated read path.

For write migrations, `complete` means the write route or worker machine was run against the live systemd app via nREPL
with mocked external effect handlers, or, for safe browser-visible writes, through Playwright against `localhost:8080`.
