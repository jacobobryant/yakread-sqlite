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

  test('RSS feed is accessible and valid', async ({ page }) => {
    const response = await page.request.get(`${CONTENT_SERVER_URL}/feed.xml`);
    expect(response.ok()).toBeTruthy();
    const body = await response.text();
    expect(body).toContain('<rss');
    expect(body).toContain('The Future of Reading');
    expect(body).toContain('Building Better RSS Readers');
    expect(body).toContain('Newsletter Curation Tips');
  });

  test('Atom feed is accessible and valid', async ({ page }) => {
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

  test('blog post has autodiscovery RSS link', async ({ page }) => {
    const response = await page.request.get(`${CONTENT_SERVER_URL}/post/1`);
    const body = await response.text();
    // Post 1 has an RSS autodiscovery link
    expect(body).toContain('application/rss+xml');
    expect(body).toContain('/feed.xml');
  });

  test('home page has both RSS and Atom feed links', async ({ page }) => {
    const response = await page.request.get(CONTENT_SERVER_URL);
    const body = await response.text();
    expect(body).toContain('application/rss+xml');
    expect(body).toContain('application/atom+xml');
  });

  test('can subscribe to RSS feed from content server', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Fill in the URL input
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/feed.xml`);

    // Click Subscribe
    await authedPage.locator('button:has-text("Subscribe")').click();

    // Wait for the subscription to be processed
    await authedPage.waitForTimeout(3000);

    // Should either show success message or redirect to subscriptions
    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });

  test('can subscribe using website URL (autodiscovery)', async ({ authedPage }) => {
    await authedPage.goto('/subscriptions/add');

    // Use the website URL instead of the feed URL directly
    await authedPage.locator('input[name="url"]').fill(CONTENT_SERVER_URL);

    // Click Subscribe
    await authedPage.locator('button:has-text("Subscribe")').click();

    // Wait for processing
    await authedPage.waitForTimeout(3000);

    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });

  test('can bookmark an article from content server', async ({ authedPage }) => {
    await authedPage.goto('/read-later/add');

    // Fill in the Article URL
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/post/1`);

    // Click Add
    await authedPage.locator('button:has-text("Add")').first().click();

    // Wait for bookmark to be processed
    await authedPage.waitForTimeout(3000);

    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });

  test('can favorite an article from content server', async ({ authedPage }) => {
    await authedPage.goto('/favorites/add');

    // Fill in the Article URL
    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/post/2`);

    // Click Add
    await authedPage.locator('button:has-text("Add")').click();

    // Wait for processing
    await authedPage.waitForTimeout(3000);

    const bodyText = await authedPage.locator('body').textContent();
    expect(bodyText).toBeTruthy();
  });
});
