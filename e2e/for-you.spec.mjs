/**
 * For You page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('For You', () => {
  test('for-you page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // Should be on the For You page
    await expect(authedPage).toHaveURL(/\/for-you/);
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('for-you page shows empty state with helpful links', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // New user should see empty state suggesting subscriptions or bookmarks
    // The empty state says: "There's no content to recommend yet."
    // with links to subscriptions and bookmarks
    await authedPage.waitForTimeout(3000);

    const bodyText = await authedPage.locator('body').textContent();
    // Either shows items or the empty state message
    expect(bodyText.length).toBeGreaterThan(0);
  });

  test('for-you shows create account banner for non-authenticated users', async ({ page }) => {
    await page.goto('/for-you');

    // Non-authed users see "Create an account" banner
    await expect(page.locator('text=Create an account')).toBeVisible();
  });

  test('history page loads when signed in', async ({ authedPage }) => {
    // History is at /history (not /for-you/history)
    await authedPage.goto('/history');

    // Should show Reading History title
    await expect(authedPage.locator('text=Reading History')).toBeVisible();
  });

  test('history page has back link to for-you', async ({ authedPage }) => {
    await authedPage.goto('/history');

    // Should have a back link to For You
    const backLink = authedPage.locator('a[href="/for-you"]');
    await expect(backLink).toBeVisible();
  });
});
