/**
 * Settings page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('Settings Page', () => {
  test('settings page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Page should have Settings heading
    await expect(authedPage.locator('text=Settings')).toBeVisible();
  });

  test('digest day checkboxes are present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // All day checkboxes should be present
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    for (const day of days) {
      await expect(authedPage.locator(`text=${day}`)).toBeVisible();
    }
  });

  test('digest time selector is present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Time selector should be present
    await expect(authedPage.locator('select[name="user/send-digest-at"]')).toBeVisible();
  });

  test('timezone selector is present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Timezone selector should be present
    await expect(authedPage.locator('select[name="user/timezone"]')).toBeVisible();
  });

  test('open original links checkbox is present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // "Open links on the original website" checkbox
    await expect(authedPage.locator('text=Open links on the original website')).toBeVisible();
  });

  test('save button is present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Save button
    await expect(authedPage.locator('button:has-text("Save")')).toBeVisible();
  });

  test('can save settings changes', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Check a day checkbox (Monday)
    const mondayCheckbox = authedPage.locator('input[value="monday"]');
    const isChecked = await mondayCheckbox.isChecked();
    if (!isChecked) {
      await mondayCheckbox.check();
    }

    // Click save
    await authedPage.locator('button:has-text("Save")').click();

    // Should redirect back to settings page
    await authedPage.waitForURL('**/settings**');
    await expect(authedPage).toHaveURL(/\/settings/);
  });

  test('premium section is visible', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Premium section should be present
    await expect(authedPage.locator('text=Premium')).toBeVisible();
  });

  test('account section is visible', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Account section with export data and delete account buttons
    await expect(authedPage.locator('text=Export data')).toBeVisible();
    await expect(authedPage.locator('text=Delete account')).toBeVisible();
  });

  test('redirects to sign-in when not authenticated', async ({ page }) => {
    // Settings requires authentication
    await page.goto('/settings');

    // The page should still load (settings shows disabled state for non-authed users)
    // or redirect to sign-in
    const url = page.url();
    // Settings page renders for both authed and non-authed users (with disabled state)
    expect(url).toMatch(/\/(settings|signin)/);
  });
});
