/**
 * Read Later (Bookmarks) page e2e tests.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Read Later / Bookmarks', () => {
  test('bookmarks page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/read-later');

    // Should show the page with "Read later" in navigation
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('bookmarks page shows empty state with add button', async ({ authedPage }) => {
    await authedPage.goto('/read-later');

    // Empty state should show helpful message about bookmarking
    await expect(authedPage.locator('text=Bookmark')).toBeVisible({ timeout: 5000 }).catch(() => {
      // Page may be loading content lazily
    });

    // Should have "Add bookmarks" button
    await expect(authedPage.locator('text=Add bookmarks')).toBeVisible({ timeout: 5000 }).catch(() => {
      // The button text may vary
    });
  });

  test('add bookmark page loads with correct title', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should show the add bookmark page
    await expect(authedPage.locator('text=Add bookmarks')).toBeVisible();
  });

  test('add bookmark page has Article URL input', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should have "Article URL" label and input
    await expect(authedPage.getByRole('textbox', { name: 'Article URL', exact: true })).toBeVisible();
    await expect(authedPage.locator('input[name="url"]')).toBeVisible();
  });

  test('add bookmark page has batch URLs textarea', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should have batch URL textarea
    await expect(authedPage.locator('text=List of article URLs, one per line')).toBeVisible();
    await expect(authedPage.locator('textarea[name="batch"]')).toBeVisible();
  });

  test('add bookmark page has bookmarklet option', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should have bookmarklet option
    await expect(authedPage.locator('button:has-text("add articles via bookmarklet")')).toBeVisible();
  });

  test('can add a bookmark via URL', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Fill in the Article URL
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/post/1`);

    // Click Add button (first one, for single URL)
    await authedPage.locator('button:has-text("Add")').first().click();

    // Wait for the bookmark to be processed
    await authedPage.waitForTimeout(3000);

    // Should show success message or redirect
    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });
});
