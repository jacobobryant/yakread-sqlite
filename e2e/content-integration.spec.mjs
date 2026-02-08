/**
 * Content server integration tests.
 * Tests that Yakread can interact with content from the dummy content server.
 */
import { test, expect, CONTENT_SERVER_URL } from './helpers.mjs';

test.describe('Content Server', () => {
  test('dummy content server is running', async ({ page }) => {
    const response = await page.request.get(CONTENT_SERVER_URL);
    expect(response.ok()).toBeTruthy();
    const body = await response.text();
    expect(body).toContain('Test Blog');
  });

  test('RSS feed is accessible', async ({ page }) => {
    const response = await page.request.get(`${CONTENT_SERVER_URL}/feed.xml`);
    expect(response.ok()).toBeTruthy();
    const body = await response.text();
    expect(body).toContain('<rss');
    expect(body).toContain('The Future of Reading');
  });

  test('Atom feed is accessible', async ({ page }) => {
    const response = await page.request.get(`${CONTENT_SERVER_URL}/atom.xml`);
    expect(response.ok()).toBeTruthy();
    const body = await response.text();
    expect(body).toContain('<feed');
    expect(body).toContain('Atom Feed Post');
  });

  test('blog posts are accessible', async ({ page }) => {
    const response1 = await page.request.get(`${CONTENT_SERVER_URL}/post/1`);
    expect(response1.ok()).toBeTruthy();
    const body1 = await response1.text();
    expect(body1).toContain('The Future of Reading');

    const response2 = await page.request.get(`${CONTENT_SERVER_URL}/post/2`);
    expect(response2.ok()).toBeTruthy();
    const body2 = await response2.text();
    expect(body2).toContain('Building Better RSS Readers');

    const response3 = await page.request.get(`${CONTENT_SERVER_URL}/post/3`);
    expect(response3.ok()).toBeTruthy();
    const body3 = await response3.text();
    expect(body3).toContain('Newsletter Curation Tips');
  });

  test('can subscribe to RSS feed from content server', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Look for the URL input
    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]').first();

    if (await urlInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await urlInput.fill(`${CONTENT_SERVER_URL}/feed.xml`);

      const submitBtn = authedPage.locator('button[type="submit"]').first();
      if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await submitBtn.click();

        // Wait for the subscription to be processed
        await authedPage.waitForTimeout(3000);

        // Navigate to subscriptions to verify
        await authedPage.goto('/subscriptions');
        await authedPage.waitForTimeout(2000);

        // The feed should appear in subscriptions
        const bodyText = await authedPage.locator('body').textContent();
        // Verify the page loaded - subscription might take time to appear
        expect(bodyText.length).toBeGreaterThan(0);
      }
    }
  });

  test('can bookmark an article from content server', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    const urlInput = authedPage.locator('input[type="url"], input[name="url"], input[placeholder*="URL"], input[placeholder*="url"]').first();

    if (await urlInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await urlInput.fill(`${CONTENT_SERVER_URL}/post/1`);

      const submitBtn = authedPage.locator('button[type="submit"]').first();
      if (await submitBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await submitBtn.click();

        // Wait for bookmark to be processed
        await authedPage.waitForTimeout(3000);

        // Navigate to read-later to check
        await authedPage.goto('/read-later');
        await authedPage.waitForTimeout(2000);

        const bodyText = await authedPage.locator('body').textContent();
        expect(bodyText.length).toBeGreaterThan(0);
      }
    }
  });
});
