/**
 * Seed data e2e tests.
 * Tests pages and features using the seed data user (seed@example.com)
 * who has pre-configured settings (digest days, timezone, etc.).
 */
import { test, expect, SEED_EMAIL } from './helpers.mjs';

test.describe('Seed Data - Settings', () => {
  test('settings page shows configured digest days', async ({ seededPage }) => {
    await seededPage.goto('/settings');

    // The seeded user has digest days configured (Monday, Wednesday, Friday)
    const mondayCheckbox = seededPage.getByRole('checkbox', { name: 'Monday' });
    await expect(mondayCheckbox).toBeChecked();

    const wednesdayCheckbox = seededPage.getByRole('checkbox', { name: 'Wednesday' });
    await expect(wednesdayCheckbox).toBeChecked();

    const fridayCheckbox = seededPage.getByRole('checkbox', { name: 'Friday' });
    await expect(fridayCheckbox).toBeChecked();

    // Tuesday should not be checked
    const tuesdayCheckbox = seededPage.getByRole('checkbox', { name: 'Tuesday' });
    await expect(tuesdayCheckbox).not.toBeChecked();
  });

  test('settings page shows user email in sidebar', async ({ seededPage }) => {
    await seededPage.goto('/settings');

    // Sidebar should show the seed user's email
    await expect(seededPage.locator(`#sidebar button:has-text("${SEED_EMAIL}")`)).toBeVisible();
  });

  test('settings page shows timezone selector', async ({ seededPage }) => {
    await seededPage.goto('/settings');

    // The timezone selector should be visible and have a value
    const timezoneSelect = seededPage.locator('select[name="\\:user/timezone"]');
    await expect(timezoneSelect).toBeVisible();
  });
});

test.describe('Seed Data - Navigation', () => {
  test('seeded user sees correct email in sidebar across pages', async ({ seededPage }) => {
    // Check email on for-you page
    await seededPage.goto('/for-you');
    await expect(seededPage.locator(`#sidebar button:has-text("${SEED_EMAIL}")`)).toBeVisible();

    // Navigate to subscriptions
    await seededPage.locator('#sidebar a:has-text("Subscriptions")').click();
    await seededPage.waitForURL('**/subscriptions**');
    await expect(seededPage.locator(`#sidebar button:has-text("${SEED_EMAIL}")`)).toBeVisible();

    // Navigate to read later
    await seededPage.locator('#sidebar a:has-text("Read later")').click();
    await seededPage.waitForURL('**/read-later**');
    await expect(seededPage.locator(`#sidebar button:has-text("${SEED_EMAIL}")`)).toBeVisible();
  });

  test('seeded user can sign out', async ({ seededPage }) => {
    await seededPage.goto('/for-you');

    // Click sign out
    await seededPage.locator('#sidebar button:has-text("Sign out")').click();

    // Should redirect away from authenticated pages
    await seededPage.waitForURL(/\/(signin|$)/, { timeout: 10000 });
    const url = seededPage.url();
    expect(url).not.toContain('/for-you');
  });
});

test.describe('Seed Data - Pages Load', () => {
  test('for-you page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/for-you');
    await expect(seededPage).toHaveURL(/\/for-you/);
    await expect(seededPage.locator('body')).toBeVisible();
  });

  test('subscriptions page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/subscriptions');
    await expect(seededPage).toHaveURL(/\/subscriptions/);
    await expect(seededPage.locator('body')).toBeVisible();
  });

  test('read-later page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/read-later');
    await expect(seededPage).toHaveURL(/\/read-later/);
    await expect(seededPage.locator('body')).toBeVisible();
  });

  test('favorites page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/favorites');
    await expect(seededPage).toHaveURL(/\/favorites/);
    await expect(seededPage.locator('body')).toBeVisible();
  });

  test('history page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/history');
    await expect(seededPage.getByText('Reading History')).toBeVisible();
  });

  test('advertise page loads for seeded user', async ({ seededPage }) => {
    await seededPage.goto('/advertise');
    await expect(seededPage).toHaveURL(/\/advertise/);
    await expect(seededPage.locator('body')).toBeVisible();
  });
});

test.describe('Seed Data - Subscriptions Content', () => {
  test('subscriptions content shows seeded feed titles', async ({ seededPage }) => {
    // Navigate directly to the content route (HTMX vendor JS isn't available in CI)
    await seededPage.goto('/subscriptions/content');

    await expect(seededPage.locator('text=Example Tech Blog')).toBeVisible({ timeout: 10000 });
    await expect(seededPage.locator('text=Daily News Digest')).toBeVisible({ timeout: 10000 });
  });

  test('subscriptions content shows unread counts and feed type', async ({ seededPage }) => {
    await seededPage.goto('/subscriptions/content');

    await expect(seededPage.locator('text=unread posts').first()).toBeVisible({ timeout: 10000 });
    await expect(seededPage.locator('text=rss').first()).toBeVisible({ timeout: 10000 });
  });
});

test.describe('Seed Data - Bookmarks Content', () => {
  test('read-later content shows seeded bookmark', async ({ seededPage }) => {
    await seededPage.goto('/read-later/content');

    await expect(seededPage.locator('text=XTDB Deep Dive')).toBeVisible({ timeout: 10000 });
  });
});

test.describe('Seed Data - Favorites Content', () => {
  test('favorites content shows seeded favorite', async ({ seededPage }) => {
    await seededPage.goto('/favorites/content');

    await expect(seededPage.locator('text=Functional Programming Patterns')).toBeVisible({ timeout: 10000 });
  });
});
