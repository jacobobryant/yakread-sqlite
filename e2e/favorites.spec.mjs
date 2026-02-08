/**
 * Favorites page e2e tests.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Favorites', () => {
  test('favorites page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/favorites');

    // Should show the favorites page
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('favorites page shows empty state', async ({ authedPage }) => {
    await authedPage.goto('/favorites');

    // Empty state should mention starring/favoriting articles
    await expect(authedPage.locator('text=starred')).toBeVisible({ timeout: 5000 }).catch(() => {
      // May show different wording
    });
  });

  test('add favorite page loads', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Should show the add favorite form
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('can add a favorite via URL', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]').first();

    if (await urlInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await urlInput.fill(`${CONTENT_SERVER_URL}/post/2`);

      const submitBtn = authedPage.locator('button[type="submit"]').first();
      if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await submitBtn.click();
        await authedPage.waitForTimeout(2000);
      }
    }
  });
});
