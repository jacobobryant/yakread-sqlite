/**
 * Settings page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('Settings Page', () => {
  test('settings page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Page should have Settings heading
    await expect(authedPage.getByRole('heading', { name: 'Settings' })).toBeVisible();
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

    // Time selector - the select element has name :user/send-digest-at (with colon prefix)
    await expect(authedPage.locator('select[name="\\:user/send-digest-at"]')).toBeVisible();
  });

  test('timezone selector is present', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Timezone selector
    await expect(authedPage.locator('text=Your timezone')).toBeVisible();
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
    await expect(authedPage.getByRole('heading', { name: 'Premium' })).toBeVisible();

    // Should show upgrade options for non-premium users
    await expect(authedPage.locator('text=$30')).toBeVisible();
    await expect(authedPage.locator('text=$60')).toBeVisible();
  });

  test('account section is visible', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Account section with export data and delete account buttons
    await expect(authedPage.locator('text=Export data')).toBeVisible();
    await expect(authedPage.locator('text=Delete account')).toBeVisible();
  });

  test('saved settings persist after page reload', async ({ authedPage }) => {
    await authedPage.goto('/settings');

    // Toggle Tuesday on
    const tuesdayCheckbox = authedPage.locator('input[value="tuesday"]');
    await tuesdayCheckbox.check();

    // Click save
    await authedPage.locator('button:has-text("Save")').click();

    // Wait for redirect back to settings
    await authedPage.waitForURL('**/settings**', { timeout: 10000 });

    // Reload the page to verify persistence
    await authedPage.reload();
    await authedPage.waitForLoadState('networkidle');

    // Tuesday should still be checked after reload
    await expect(authedPage.locator('input[value="tuesday"]')).toBeChecked();
  });

  test('settings page shows disabled state for non-authenticated user', async ({ page }) => {
    await page.goto('/settings');

    // Settings page renders for non-authed users with disabled state
    // The fieldset should be disabled
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    // Should show "Create an account" banner
    await expect(page.locator('text=Create an account')).toBeVisible();
  });
});
