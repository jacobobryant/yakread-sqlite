/**
 * S3 (MinIO) integration tests.
 * Tests that bookmarking a long article stores content in S3 (MinIO)
 * and that the article can be read back from the read-later page.
 */
import { test, expect, CONTENT_SERVER_URL, gotoWithContent } from './helpers.mjs';

test.describe('S3 Bookmark Storage', () => {
  test('bookmark a long article and read it from the read-later page', async ({ authedPage }) => {
    // Step 1: Bookmark a long article (content >1000 chars triggers S3 storage)
    await authedPage.goto('/read-later/add');

    await authedPage.locator('input[name="url"]').fill(`${CONTENT_SERVER_URL}/post/long`);
    await authedPage.locator('button:has-text("Add")').first().click();

    // Wait for the bookmark to be processed by polling the read-later page
    let bookmarkVisible = false;
    for (let i = 0; i < 15; i++) {
      await authedPage.waitForTimeout(2000);
      await gotoWithContent(authedPage, '/read-later', '/read-later/content');
      const title = authedPage.locator('text=The Complete Guide to Digital Reading in 2025');
      if (await title.isVisible().catch(() => false)) {
        bookmarkVisible = true;
        break;
      }
    }
    expect(bookmarkVisible).toBeTruthy();

    // Step 2: Click on the article card to open it
    await authedPage.locator('a:has-text("The Complete Guide to Digital Reading in 2025")').click();

    // Step 3: Verify the article content is displayed (fetched from S3/MinIO)
    await expect(
      authedPage.locator('text=The Evolution of E-Readers')
    ).toBeVisible({ timeout: 15000 });

    await expect(
      authedPage.locator('text=Read-It-Later Applications')
    ).toBeVisible({ timeout: 5000 });
  });
});
