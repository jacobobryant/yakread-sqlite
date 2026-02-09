/**
 * For You page e2e tests.
 *
 * The for-you page lazy-loads content from /for-you/content via htmx.
 * That endpoint runs through the Spark-trained recommendation model
 * (use-spark), so these tests implicitly exercise the model code.
 *
 * Because the recommendation logic includes randomness (shuffling,
 * random sampling), assertions focus on structural properties rather
 * than specific item titles or ordering.
 */
import { test, expect } from './helpers.mjs';

test.describe('For You', () => {
  test('for-you page loads when signed in', async ({ authedPage }) => {
    await authedPage.goto('/for-you');

    // Should be on the For You page
    await expect(authedPage).toHaveURL(/\/for-you/);
    await expect(authedPage.locator('body')).toBeVisible();
  });

  test('for-you content endpoint loads for fresh user', async ({ authedPage }) => {
    // Directly hit the lazy-loaded content endpoint.
    // This exercises the Spark model (use-spark) code path.
    const response = await authedPage.goto('/for-you/content');
    expect(response.status()).toBe(200);

    // A fresh user should see either recommendation cards or
    // the empty-state message, depending on available content.
    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText.length).toBeGreaterThan(0);
  });

  test('for-you shows create account banner for non-authenticated users', async ({ page }) => {
    await page.goto('/for-you');

    // Non-authed users see "Create an account" banner
    await expect(page.locator('text=Create an account')).toBeVisible();
  });

  test('for-you content endpoint returns HTML for seeded user', async ({ seededPage }) => {
    // Directly hit the content endpoint that the for-you page lazy-loads.
    // This runs the Pathom query through the Spark model.
    const response = await seededPage.goto('/for-you/content');
    expect(response.status()).toBe(200);

    const contentType = response.headers()['content-type'];
    expect(contentType).toContain('text/html');

    // The response should contain substantive HTML content
    const bodyText = await seededPage.locator('body').textContent();
    expect(bodyText.length).toBeGreaterThan(0);
  });

  test('for-you content has recommendation cards or empty state for seeded user', async ({ seededPage }) => {
    // Navigate directly to the content endpoint to exercise the model.
    await seededPage.goto('/for-you/content');

    // The seeded user has subscriptions and items. The model should produce
    // either recommendation cards (with links) or the empty-state message.
    // Both are valid since the model depends on interaction data.
    const body = await seededPage.locator('body').textContent();
    const hasRecs = (await seededPage.locator('a').count()) > 0;
    const hasEmptyState = body.includes("There's no content to recommend yet.");
    expect(hasRecs || hasEmptyState).toBe(true);
  });

  test('for-you page shell loads for seeded user', async ({ seededPage }) => {
    // Verify the for-you page renders the app shell with sidebar
    await seededPage.goto('/for-you');
    await expect(seededPage).toHaveURL(/\/for-you/);

    // The sidebar should be present with navigation links
    await expect(seededPage.locator('#sidebar')).toBeVisible();
    await expect(seededPage.locator('#sidebar a:has-text("For you")')).toBeVisible();

    // The content area should exist (lazy-load target)
    await expect(seededPage.locator('#content')).toBeVisible();
  });

  test('history page loads when signed in', async ({ authedPage }) => {
    // History is at /history (not /for-you/history)
    await authedPage.goto('/history');

    // Should show Reading History title
    await expect(authedPage.locator('text=Reading History')).toBeVisible();
  });

  test('history page has back link to for-you', async ({ authedPage }) => {
    await authedPage.goto('/history');

    // Should have a back link to For You (the "Back" link)
    const backLink = authedPage.locator('a:has-text("Back")').first();
    await expect(backLink).toBeVisible();
  });
});
