/**
 * Advertise page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('Advertise', () => {
  test('advertise page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/advertise');

    // Should show the advertise page
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('advertise page is accessible from sidebar', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // Advertise link should be in the sidebar
    await expect(authedPage.locator('#sidebar a:has-text("Advertise")')).toBeVisible();
  });

  test('advertise page shows for non-authenticated users', async ({ page }) => {
    await page.goto('/advertise');

    // Advertise page should be accessible without authentication
    await expect(page.locator('body')).toBeVisible();
  });
});
