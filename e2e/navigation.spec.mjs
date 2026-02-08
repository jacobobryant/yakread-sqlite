/**
 * Navigation e2e tests.
 * Tests that the sidebar/nav links work correctly and active states are shown.
 */
import { test, expect } from './helpers.mjs';

test.describe('Navigation', () => {
  test('sidebar shows all main navigation items', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // All main nav items should be visible in the sidebar
    await expect(authedPage.locator('a:has-text("For you")')).toBeVisible();
    await expect(authedPage.locator('a:has-text("Subscriptions")')).toBeVisible();
    await expect(authedPage.locator('a:has-text("Read later")')).toBeVisible();
    await expect(authedPage.locator('a:has-text("Favorites")')).toBeVisible();
    await expect(authedPage.locator('a:has-text("Settings")')).toBeVisible();
    await expect(authedPage.locator('a:has-text("Advertise")')).toBeVisible();
  });

  test('can navigate to subscriptions page', async ({ authedPage }) => {
    await authedPage.goto('/for-you');
    await authedPage.locator('#sidebar a:has-text("Subscriptions")').click();
    await authedPage.waitForURL('**/subscriptions**');
    await expect(authedPage).toHaveURL(/\/subscriptions/);
  });

  test('can navigate to read later page', async ({ authedPage }) => {
    await authedPage.goto('/for-you');
    await authedPage.locator('#sidebar a:has-text("Read later")').click();
    await authedPage.waitForURL('**/read-later**');
    await expect(authedPage).toHaveURL(/\/read-later/);
  });

  test('can navigate to favorites page', async ({ authedPage }) => {
    await authedPage.goto('/for-you');
    await authedPage.locator('#sidebar a:has-text("Favorites")').click();
    await authedPage.waitForURL('**/favorites**');
    await expect(authedPage).toHaveURL(/\/favorites/);
  });

  test('can navigate to settings page', async ({ authedPage }) => {
    await authedPage.goto('/for-you');
    await authedPage.locator('#sidebar a:has-text("Settings")').click();
    await authedPage.waitForURL('**/settings**');
    await expect(authedPage).toHaveURL(/\/settings/);
  });

  test('can navigate back to for-you from settings', async ({ authedPage }) => {
    await authedPage.goto('/settings');
    await authedPage.locator('#sidebar a:has-text("For you")').click();
    await authedPage.waitForURL('**/for-you**');
    await expect(authedPage).toHaveURL(/\/for-you/);
  });

  test('active page is highlighted in sidebar', async ({ authedPage }) => {
    // Navigate to for-you
    await authedPage.goto('/for-you');

    // The For You link should have active styling (bg-neut-800 without hover: prefix)
    const forYouLink = authedPage.locator('#sidebar a:has-text("For you")');
    const classes = await forYouLink.getAttribute('class');
    // Active link has 'bg-neut-800 text-white', inactive links have 'hover:bg-neut-800'
    expect(classes).toContain('bg-neut-800');
    expect(classes).not.toContain('hover:bg-neut-800');

    // Other links should have hover:bg-neut-800 (inactive styling)
    const subsLink = authedPage.locator('#sidebar a:has-text("Subscriptions")');
    const subsClasses = await subsLink.getAttribute('class');
    expect(subsClasses).toContain('hover:bg-neut-800');
  });

  test('sidebar shows user email when signed in', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // The sidebar should show the user's email
    await expect(authedPage.locator('#sidebar button:has-text("test@example.com")')).toBeVisible();
  });

  test('user dropdown contains sign out option', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // The sign-out button should be visible in the sidebar
    await expect(authedPage.locator('#sidebar button:has-text("Sign out")')).toBeVisible();
  });

  test('sign out redirects to home', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // Click sign out
    await authedPage.locator('#sidebar button:has-text("Sign out")').click();

    // Should redirect away from authenticated pages
    await authedPage.waitForTimeout(2000);
    const url = authedPage.url();
    expect(url).not.toContain('/for-you');
  });
});
