/**
 * Home page and landing page e2e tests.
 */
import { test, expect } from './helpers.mjs';

test.describe('Home / Landing Page', () => {
  test('landing page loads with correct heading', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // The page should have main heading
    await expect(page.locator('h1')).toContainText('Read stuff that');
    await expect(page.locator('h1')).toContainText('matters');
  });

  test('landing page has sign-in link in navbar', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Sign-in link should be visible in the navbar
    await expect(page.locator('a[href="/signin"]')).toBeVisible();
    await expect(page.locator('a:has-text("Sign in")')).toBeVisible();
  });

  test('landing page has signup form', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Signup form with email input and "Join the herd" submit button
    await expect(page.locator('input[name="email"]')).toBeVisible();
    await expect(page.locator('button:has-text("Join the herd")')).toBeVisible();
  });

  test('landing page has feature cards', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Feature cards should be present
    await expect(page.locator('text=Subscriptions')).toBeVisible();
    await expect(page.locator('text=Read it later')).toBeVisible();
    await expect(page.locator('text=In case you missed it')).toBeVisible();
  });

  test('landing page has feature descriptions', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Feature descriptions
    await expect(page.locator('text=Subscribe to your favorite newsletters and RSS feeds')).toBeVisible();
    await expect(page.locator('text=Save articles from around the web')).toBeVisible();
  });

  test('landing page has "Take a look around" link', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Link to explore the app
    await expect(page.locator('text=Take a look around')).toBeVisible();
  });

  test('landing page has testimonial', async ({ page }) => {
    await page.goto('/?noredirect=true');

    // Testimonial section
    await expect(page.locator('text=Jacob O\'Bryant')).toBeVisible();
  });

  test('sign-in link navigates to sign-in page', async ({ page }) => {
    await page.goto('/?noredirect=true');
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

  test('noredirect param prevents redirect for signed-in users', async ({ authedPage }) => {
    await authedPage.goto('/?noredirect=true');
    // With noredirect, should stay on home page even when signed in
    await expect(authedPage).toHaveURL(/\//);
    await expect(authedPage.locator('h1')).toContainText('Read stuff that');
  });
});
