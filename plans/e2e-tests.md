# E2E Test Plan for Yakread

## Overview
Comprehensive Playwright e2e tests for the Yakread web application. These tests run against the app with XTDB2 backend and use a local dummy content server for RSS feeds and blog posts.

## Infrastructure Setup
- [x] Restore XTDB2 component (undo SQLite migration changes in `com.yakread`)
- [x] Save SQLite schema to `dev/sqlite_schema.clj`, restore XTDB2 schema
- [x] Create test config (config.env with dev secrets)
- [x] Set up Playwright (package.json, playwright.config.ts)
- [x] Create dummy content server (serves RSS feeds, blog posts at localhost:8888)
- [x] Create test helpers (auth bypass, page utilities)
- [x] Verify app starts and basic page loads work

## Test Categories

### 1. Home / Landing Page
- [x] Landing page loads with correct title and content
- [x] Sign-in link is visible
- [x] Sign-up form is present
- [ ] Footer links work (About, Contact, TOS, Privacy)

### 2. Authentication Flow
- [x] Sign-in page loads
- [x] Can sign in with email verification code
- [x] Redirects to For You page after sign in
- [ ] Invalid code shows error
- [ ] Sign out works

### 3. Settings Page
- [x] Settings page loads when signed in
- [x] Digest day checkboxes are present
- [x] Digest time selector is present
- [x] Timezone selector is present
- [x] "Open links on original website" checkbox is present
- [ ] Can save settings changes
- [ ] Premium section is visible
- [ ] Account section is visible (export data, delete account buttons)

### 4. Subscriptions
- [x] Subscriptions page loads
- [x] Add subscription page loads
- [ ] Can add an RSS feed subscription
- [ ] Added subscription appears in subscription list
- [ ] Can view subscription detail page
- [ ] Can unsubscribe from a feed
- [ ] Email subscription section exists on add page

### 5. Read Later / Bookmarks
- [x] Bookmarks page loads
- [x] Add bookmark page loads
- [ ] Can add a bookmark via URL
- [ ] Bookmarked item appears in list
- [ ] Can remove a bookmark

### 6. Favorites
- [x] Favorites page loads
- [x] Add favorite page loads
- [ ] Can add a favorite via URL
- [ ] Favorited item appears in list

### 7. For You Page
- [x] For You page loads when signed in
- [ ] Shows empty state or items
- [ ] History page loads

### 8. Navigation
- [x] Can navigate between all main pages via sidebar/nav
- [x] Active page is highlighted in navigation
- [ ] Mobile navigation works

### 9. Content Server Integration
- [ ] RSS feed from dummy server can be subscribed to
- [ ] Feed items appear after sync
- [ ] Blog post can be bookmarked from dummy server URL

## Status
- **In Progress**: Infrastructure setup, basic tests
- **Not Started**: Content integration tests, detailed interaction tests
