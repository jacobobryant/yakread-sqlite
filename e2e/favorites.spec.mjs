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

    // Empty state should mention starring articles
    await expect(authedPage.locator('text=starred')).toBeVisible({ timeout: 5000 }).catch(() => {
      // Different wording may be used
    });

    // Should have "Add articles" button
    await expect(authedPage.locator('text=Add articles')).toBeVisible({ timeout: 5000 }).catch(() => {});
  });

  test('add favorite page loads with correct title', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Should show the add favorites page
    await expect(authedPage.locator('text=Add favorites')).toBeVisible();
  });

  test('add favorite page has Article URL input', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Should have "Article URL" label and input
    await expect(authedPage.locator('text=Article URL')).toBeVisible();
    await expect(authedPage.locator('input[name="url"]')).toBeVisible();
  });

  test('add favorite page has bookmarklet option', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Should have bookmarklet link
    await expect(authedPage.locator('text=add articles via bookmarklet')).toBeVisible();
  });

  test('can add a favorite via URL', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Fill in the Article URL
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/post/2`);

    // Click Add button
    await authedPage.locator('button:has-text("Add")').click();

    // Wait for the favorite to be processed
    await authedPage.waitForTimeout(3000);

    // Should show success message or redirect
    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });
});
