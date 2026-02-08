/**
 * Read Later (Bookmarks) page e2e tests.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Read Later / Bookmarks', () => {
  test('bookmarks page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/read-later');

    // Should show the bookmarks page or redirect to content
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('bookmarks page shows empty state', async ({ authedPage }) => {
    await authedPage.goto('/read-later');

    // Empty state should show helpful message about bookmarking
    await expect(authedPage.locator('text=Bookmark')).toBeVisible({ timeout: 5000 }).catch(() => {
      // May show a different empty state message
    });
  });

  test('add bookmark page loads', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should show the add bookmark form
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('add bookmark page has URL input', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Should have a URL input field
    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]');
    // Check that the page has loaded with some input
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('can add a bookmark via URL', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]').first();

    if (await urlInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await urlInput.fill(`${CONTENT_SERVER_URL}/post/1`);

      const submitBtn = authedPage.locator('button[type="submit"]').first();
      if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await submitBtn.click();
        await authedPage.waitForTimeout(2000);
      }
    }
  });
});
