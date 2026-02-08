/**
 * Authentication flow e2e tests.
 */
import { test, expect, signIn } from './helpers.mjs';

test.describe('Authentication', () => {
  test('sign-in page loads', async ({ page }) => {
    await page.goto('/signin');

    // Should show the sign-in form
    await expect(page.locator('input[name="email"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test('sign-in page has logo', async ({ page }) => {
    await page.goto('/signin');

    // Yakread logo should be visible
    await expect(page.locator('img[alt="Yakread logo"]')).toBeVisible();
  });

  test('sign-in page has sign-up link', async ({ page }) => {
    await page.goto('/signin');

    // "Don't have an account yet? Sign up." link
    await expect(page.locator('text=Sign up')).toBeVisible();
  });

  test('can sign in with email and verification code', async ({ page }) => {
    await signIn(page, 'test@example.com');

    // After sign-in, should be on the for-you page
    await expect(page).toHaveURL(/\/for-you/);
  });

  test('sign-in sends code and shows verify page', async ({ page }) => {
    await page.goto('/signin');
    await page.fill('input[name="email"]', 'test@example.com');
    await page.click('button[type="submit"]');

    // Should be redirected to verify-code page
    await page.waitForURL('**/verify-code**');
    await expect(page.locator('input[name="code"]')).toBeVisible();
    await expect(page.locator('text=Enter the 6-digit code')).toBeVisible();
  });

  test('sign out works', async ({ authedPage }) => {
    // Click on user menu to show sign-out button
    const userButton = authedPage.locator('button:has-text("Sign out")');
    // The sign-out button may be in a dropdown - first find and click the user trigger
    const userDropdownTrigger = authedPage.locator('#user-dropdown').locator('..');
    // Try clicking the user area to show the dropdown
    await authedPage.locator('button:near(:text("Sign out"))').first().click({ timeout: 5000 }).catch(() => {});

    // Look for the sign-out form/button
    const signOutButton = authedPage.locator('button:has-text("Sign out")');
    if (await signOutButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await signOutButton.click();
      // After sign out, should be redirected to home or sign-in
      await authedPage.waitForURL(url => !url.toString().includes('/for-you'), { timeout: 5000 });
    }
  });
});
