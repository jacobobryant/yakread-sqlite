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

  test('sign-in page shows Sign in button', async ({ page }) => {
    await page.goto('/signin');

    // Sign in button text
    await expect(page.locator('button:has-text("Sign in")')).toBeVisible();
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

  test('verify-code page has resend option', async ({ page }) => {
    await page.goto('/signin');
    await page.fill('input[name="email"]', 'test@example.com');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/verify-code**');

    // Should have "Send another code" link
    await expect(page.locator('text=Send another code')).toBeVisible();
  });

  test('verify-code page has home link', async ({ page }) => {
    await page.goto('/signin');
    await page.fill('input[name="email"]', 'test@example.com');
    await page.click('button[type="submit"]');
    await page.waitForURL('**/verify-code**');

    // Should have a link to home
    await expect(page.locator('a:has-text("Home")')).toBeVisible();
  });

  test('protected pages redirect to sign-in', async ({ page }) => {
    // History page requires authentication
    await page.goto('/history');
    await page.waitForURL('**/signin**');
    await expect(page).toHaveURL(/\/signin/);
    // Should show "not-signed-in" error
    await expect(page.locator('text=You must be signed in')).toBeVisible();
  });
});
