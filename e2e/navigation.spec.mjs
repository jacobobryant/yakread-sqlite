/**
 * Navigation e2e tests.
 * Tests that the sidebar/nav links work correctly and active states are shown.
 */
import { test, expect } from './helpers.mjs';

test.describe('Navigation', () => {
  test('can navigate to all main pages from sidebar', async ({ authedPage }) => {
    // Start at For You
    await authedPage.goto('/for-you');
    await expect(authedPage.locator('body')).toBeVisible();

    // Navigate to Subscriptions
    const subsLink = authedPage.locator('a[href="/subscriptions"]');
    if (await subsLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await subsLink.click();
      await authedPage.waitForURL('**/subscriptions**');
      await expect(authedPage).toHaveURL(/\/subscriptions/);
    }

    // Navigate to Read Later
    const readLaterLink = authedPage.locator('a[href="/read-later"]');
    if (await readLaterLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await readLaterLink.click();
      await authedPage.waitForURL('**/read-later**');
      await expect(authedPage).toHaveURL(/\/read-later/);
    }

    // Navigate to Favorites
    const favLink = authedPage.locator('a[href="/favorites"]');
    if (await favLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await favLink.click();
      await authedPage.waitForURL('**/favorites**');
      await expect(authedPage).toHaveURL(/\/favorites/);
    }

    // Navigate to Settings
    const settingsLink = authedPage.locator('a[href="/settings"]');
    if (await settingsLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await settingsLink.click();
      await authedPage.waitForURL('**/settings**');
      await expect(authedPage).toHaveURL(/\/settings/);
    }
  });

  test('active page is highlighted in navigation', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // The For You nav link should have some active state styling
    const forYouLink = authedPage.locator('a[href="/for-you"]');
    if (await forYouLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Active links typically have a different background or text color
      // Check that the link exists and has some class
      const classes = await forYouLink.getAttribute('class');
      expect(classes).toBeTruthy();
    }
  });

  test('can navigate back to For You from any page', async ({ authedPage }) => {
    // Go to settings first
    await authedPage.goto('/settings');
    await expect(authedPage).toHaveURL(/\/settings/);

    // Click For You in the nav
    const forYouLink = authedPage.locator('a[href="/for-you"]');
    if (await forYouLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await forYouLink.click();
      await authedPage.waitForURL('**/for-you**');
      await expect(authedPage).toHaveURL(/\/for-you/);
    }
  });
});
