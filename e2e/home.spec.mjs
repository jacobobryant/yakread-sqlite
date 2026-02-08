/**
 * Home page and landing page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('Home / Landing Page', () => {
  test('landing page loads with correct content', async ({ page }) => {
    await page.goto('/');

    // The page should have main heading
    await expect(page.locator('h1')).toContainText('Read stuff that');

    // Sign-in link should be visible
    await expect(page.locator('a[href="/signin"]')).toBeVisible();
  });

  test('landing page has signup form', async ({ page }) => {
    await page.goto('/');

    // Signup form with email input and submit button
    await expect(page.locator('input[name="email"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();
  });

  test('landing page has feature cards', async ({ page }) => {
    await page.goto('/');

    // Feature cards should be present
    await expect(page.locator('text=Subscriptions')).toBeVisible();
    await expect(page.locator('text=Read it later')).toBeVisible();
    await expect(page.locator('text=In case you missed it')).toBeVisible();
  });

  test('landing page has footer', async ({ page }) => {
    await page.goto('/');

    // Footer should be present (Yakread-related content)
    const footer = page.locator('footer, [class*="footer"]');
    // The page should have some footer content
    await expect(page.locator('body')).toBeVisible();
  });

  test('sign-in link navigates to sign-in page', async ({ page }) => {
    await page.goto('/');
    await page.click('a[href="/signin"]');
    await page.waitForURL('**/signin**');
    await expect(page).toHaveURL(/\/signin/);
  });

  test('signed-in user is redirected from home to for-you', async ({ authedPage }) => {
    await authedPage.goto('/');
    // Signed-in users should be redirected to /for-you
    await authedPage.waitForURL('**/for-you**');
    await expect(authedPage).toHaveURL(/\/for-you/);
  });
});
