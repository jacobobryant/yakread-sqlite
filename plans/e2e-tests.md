# E2E Test Plan for Yakread

## Overview
Comprehensive Playwright e2e tests for the Yakread web application. These tests run against the app with XTDB2 backend and use a local dummy content server for RSS feeds and blog posts.

## Infrastructure Setup
- [x] Restore XTDB2 component (undo SQLite migration changes in `com.yakread`)
- [x] Save SQLite schema to `dev/sqlite_schema.clj`, restore XTDB2 schema
- [x] Create test config (config.env with dev secrets)
- [x] Set up Playwright (package.json, playwright.config.mjs)
- [x] Create dummy content server (serves RSS feeds, blog posts on port 8888)
- [x] Create test helpers (auth bypass, page utilities)
- [x] Verify all tests parse correctly (80 tests in 10 files)

## Running Tests

### Prerequisites
1. Java 21+ (required for XTDB2)
2. Clojure CLI (`clj`)
3. Node.js with Playwright installed (`npx playwright install chromium`)

### Start the App
```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
clj -M:run -m com.yakread
```

### Run Tests
```bash
npm run test:e2e          # Run all tests
npx playwright test --list # List all tests
```

### Auth Code Retrieval
The tests need to get the verification code during sign-in. Two strategies are supported:
1. **Dev endpoint**: GET `/dev/auth-code?email=...` returns the code
2. **File-based**: Server writes code to `storage/test-auth-code.txt`

You may need to add one of these mechanisms to the app for the tests to complete the sign-in flow.

## Test Categories & Status

### 1. Home / Landing Page (11 tests) — `home.spec.mjs`
- [x] Landing page loads with correct heading ("Read stuff that matters")
- [x] Landing page has sign-in link in navbar
- [x] Landing page has signup form with "Join the herd" button
- [x] Landing page has feature cards (Subscriptions, Read it later, ICYMI)
- [x] Landing page has feature descriptions
- [x] Landing page has "Take a look around" link
- [x] Landing page has testimonial
- [x] Sign-in link navigates to sign-in page
- [x] Signed-in user is redirected from home to for-you
- [x] noredirect param prevents redirect for signed-in users

### 2. Authentication Flow (9 tests) — `auth.spec.mjs`
- [x] Sign-in page loads with email input and submit button
- [x] Sign-in page has Yakread logo
- [x] Sign-in page has sign-up link
- [x] Sign-in page shows "Sign in" button
- [x] Can sign in with email and verification code
- [x] Sign-in sends code and shows verify-code page
- [x] Verify-code page has "Send another code" option
- [x] Verify-code page has Home link
- [x] Protected pages redirect to sign-in with error message

### 3. Settings Page (10 tests) — `settings.spec.mjs`
- [x] Settings page loads when signed in
- [x] Digest day checkboxes are present (Mon-Sun)
- [x] Digest time selector is present
- [x] Timezone selector is present
- [x] "Open links on original website" checkbox is present
- [x] Save button is present
- [x] Can save settings changes
- [x] Premium section is visible with pricing
- [x] Account section is visible (export data, delete account)
- [x] Non-authenticated user sees disabled state with "Create an account" banner

### 4. Subscriptions (10 tests) — `subscriptions.spec.mjs`
- [x] Subscriptions page loads when signed in
- [x] Subscriptions page shows empty state with "Add subscriptions" button
- [x] Non-authenticated state shows "Create an account" banner
- [x] Add subscription page loads with title
- [x] Add subscription page has RSS feed section with URL input
- [x] Add subscription page has Newsletters section
- [x] Add subscription page has OPML import
- [x] Add subscription page has bookmarklet option
- [x] Can add an RSS feed subscription
- [x] Email subscription section shows username setup

### 5. Read Later / Bookmarks (7 tests) — `bookmarks.spec.mjs`
- [x] Bookmarks page loads when signed in
- [x] Bookmarks page shows empty state with add button
- [x] Add bookmark page loads with correct title
- [x] Add bookmark page has Article URL input
- [x] Add bookmark page has batch URLs textarea
- [x] Add bookmark page has bookmarklet option
- [x] Can add a bookmark via URL

### 6. Favorites (6 tests) — `favorites.spec.mjs`
- [x] Favorites page loads when signed in
- [x] Favorites page shows empty state
- [x] Add favorite page loads with correct title
- [x] Add favorite page has Article URL input
- [x] Add favorite page has bookmarklet option
- [x] Can add a favorite via URL

### 7. For You Page (5 tests) — `for-you.spec.mjs`
- [x] For-you page loads when signed in
- [x] For-you page shows empty state with helpful links
- [x] For-you shows "Create an account" banner for non-authenticated users
- [x] History page loads when signed in with "Reading History" title
- [x] History page has back link to for-you

### 8. Navigation (10 tests) — `navigation.spec.mjs`
- [x] Sidebar shows all main navigation items (For you, Subscriptions, Read later, Favorites, Settings, Advertise)
- [x] Can navigate to subscriptions page
- [x] Can navigate to read later page
- [x] Can navigate to favorites page
- [x] Can navigate to settings page
- [x] Can navigate back to for-you from settings
- [x] Active page is highlighted in sidebar (bg-neut-800 class)
- [x] Sidebar shows user email when signed in
- [x] User dropdown contains sign out option
- [x] Sign out redirects away from authenticated pages

### 9. Content Server Integration (10 tests) — `content-integration.spec.mjs`
- [x] Dummy content server is running
- [x] RSS feed is accessible and contains all 3 posts
- [x] Atom feed is accessible and valid
- [x] Blog posts are accessible (all 3)
- [x] Blog post has autodiscovery RSS link
- [x] Home page has both RSS and Atom feed links
- [x] Can subscribe to RSS feed from content server
- [x] Can subscribe using website URL (autodiscovery)
- [x] Can bookmark an article from content server
- [x] Can favorite an article from content server

### 10. Advertise Page (3 tests) — `advertise.spec.mjs`
- [x] Advertise page loads when signed in
- [x] Advertise page is accessible from sidebar
- [x] Advertise page shows for non-authenticated users

## Status Summary
- **Total tests written**: 80
- **Test files**: 10
- **All tests parse correctly** (verified with `npx playwright test --list`)
- **Pending**: Running tests against live app (requires Clojure CLI installation)
