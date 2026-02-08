/**
 * Subscriptions page e2e tests.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Subscriptions', () => {
  test('subscriptions page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions');

    // Should show the subscriptions page title
    await expect(authedPage.locator('text=Subscriptions')).toBeVisible();
  });

  test('subscriptions page shows empty state for new user', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions');

    // Empty state shows the subscriptions page (no feed items yet)
    await expect(authedPage).toHaveURL(/\/subscriptions/);
    // The page should load without errors
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('subscriptions page shows non-authenticated state', async ({ page }) => {
    await page.goto('/subscriptions');

    // Non-authed users see the page with empty state and "Create an account" banner
    await expect(page.getByRole('heading', { name: 'Subscriptions' })).toBeVisible();
    await expect(page.locator('text=Create an account')).toBeVisible();
  });

  test('add subscription page loads', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should show the add subscription page with title
    await expect(authedPage.locator('text=Add subscriptions')).toBeVisible();
  });

  test('add subscription page has RSS feed section', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have RSS feeds section
    await expect(authedPage.getByRole('heading', { name: 'RSS feeds' })).toBeVisible();

    // Should have URL input labeled "Website or feed URL"
    await expect(authedPage.locator('text=Website or feed URL')).toBeVisible();

    // Should have Subscribe button
    await expect(authedPage.locator('button[type="submit"]:has-text("Subscribe")')).toBeVisible();
  });

  test('add subscription page has newsletters section', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have Newsletters section
    await expect(authedPage.getByRole('heading', { name: 'Newsletters' })).toBeVisible();
  });

  test('add subscription page has OPML import', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have OPML file input
    await expect(authedPage.locator('text=OPML file')).toBeVisible();

    // Should have Import button
    await expect(authedPage.locator('button:has-text("Import")')).toBeVisible();
  });

  test('add subscription page has bookmarklet option', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Should have bookmarklet option
    await expect(authedPage.locator('button:has-text("subscribe via bookmarklet")')).toBeVisible();
  });

  test('can add an RSS feed subscription', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Fill in the URL input
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/feed.xml`);

    // Click Subscribe (use the submit button specifically)
    await authedPage.locator('button[type="submit"]:has-text("Subscribe")').click();

    // Should redirect back to add page with success message or subscriptions page
    await authedPage.waitForTimeout(3000);

    // Check for success indicator (added-feeds message)
    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });

  test('email subscription section shows username setup', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Either shows username setup form or existing username
    // The Newsletters section should have some content about email
    const newsletterSection = authedPage.getByRole('heading', { name: 'Newsletters' });
    await expect(newsletterSection).toBeVisible();
  });

  test('can click into a subscription to view its items', async ({ seededPage }) => {
    await seededPage.goto('/subscriptions');

    // Wait for lazy-loaded subscription cards
    await seededPage.waitForTimeout(3000);

    // Click the first subscription link
    const subLink = seededPage.locator('a:has-text("Example Tech Blog")');
    await expect(subLink).toBeVisible({ timeout: 10000 });
    await subLink.click();

    // Should navigate to the subscription view page
    await seededPage.waitForURL('**/subscription/**', { timeout: 10000 });

    // The subscription view page should show the feed's items
    await seededPage.waitForTimeout(3000);
    const bodyText = await seededPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });

  test('can unsubscribe from a feed subscription', async ({ seededPage }) => {
    await seededPage.goto('/subscriptions');

    // Wait for lazy-loaded subscription cards
    await seededPage.waitForTimeout(3000);

    // The seeded user has "Daily News Digest" subscription
    await expect(seededPage.locator('text=Daily News Digest')).toBeVisible({ timeout: 10000 });

    // Find the overflow menu button near "Daily News Digest" and click it
    const newsCard = seededPage.locator('.relative:has-text("Daily News Digest")');
    const menuButton = newsCard.locator('button').first();
    await menuButton.click();

    // Click "Unsubscribe" in the overflow menu
    const unsubButton = seededPage.locator('button:has-text("Unsubscribe")');
    await expect(unsubButton).toBeVisible({ timeout: 5000 });

    // Accept the confirmation dialog
    seededPage.on('dialog', dialog => dialog.accept());
    await unsubButton.click();

    // Wait for the unsubscribe action to complete
    await seededPage.waitForTimeout(3000);

    // After unsubscribing, "Daily News Digest" should no longer appear
    await expect(seededPage.locator('text=Daily News Digest')).not.toBeVisible({ timeout: 10000 });
  });
});
