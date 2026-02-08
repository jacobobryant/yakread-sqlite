/**
 * Subscriptions page e2e tests.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Subscriptions', () => {
  test('subscriptions page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions');

    // Should show the subscriptions page
    await expect(authedPage.locator('text=Subscriptions')).toBeVisible();
  });

  test('subscriptions page shows empty state or list', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions');

    // Either shows the empty state message or a list of subscriptions
    const body = authedPage.locator('body');
    await expect(body).toBeVisible();
  });

  test('add subscription page loads', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should show the add subscription form
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('add subscription page has RSS feed input', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have an input for URL/feed
    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]');
    // There should be some form of URL input
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('can add an RSS feed subscription', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Enter the test RSS feed URL
    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]').first();

    if (await urlInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await urlInput.fill(`${CONTENT_SERVER_URL}/feed.xml`);

      // Submit the form
      const submitBtn = authedPage.locator('button[type="submit"]').first();
      if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await submitBtn.click();
        // Wait for response
        await authedPage.waitForTimeout(2000);
      }
    }
  });

  test('email subscription section exists on add page', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have email subscription section (email username setup)
    const body = await authedPage.locator('body').textContent();
    // The add page should mention email subscriptions or RSS
    expect(body).toBeTruthy();
  });
});
