/**
 * Test helpers and fixtures for Yakread e2e tests.
 *
 * Auth approach: We POST to /auth/send-code to trigger code generation.
 * In dev mode (console email), the server writes the code to
 * storage/test-auth-code.txt which the tests read.
 */
import { test as base, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

const CONTENT_SERVER_URL = 'http://localhost:8888';
const CODE_FILE = path.join(process.cwd(), 'storage', 'test-auth-code.txt');
const SEED_EMAIL = 'seed@example.com';

/**
 * Wait for and retrieve the verification code.
 * The Yakread dev server writes the code to storage/test-auth-code.txt
 * when sending via console (i.e. when MailerSend keys are not configured).
 */
async function getVerificationCode(page, email) {
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500);
    try {
      if (fs.existsSync(CODE_FILE)) {
        const code = fs.readFileSync(CODE_FILE, 'utf-8').trim();
        if (code && code.length === 6) {
          // Clear the file for next use
          fs.writeFileSync(CODE_FILE, '');
          return code;
        }
      }
    } catch (e) {
      // Continue waiting
    }
  }

  throw new Error('Could not retrieve verification code from ' + CODE_FILE);
}

/**
 * Sign in to Yakread with the given email address.
 */
async function signIn(page, email = 'test@example.com') {
  await page.goto('/signin');

  // Fill in email
  await page.fill('input[name="email"]', email);

  // Submit the form (clicking the sign-in button)
  await page.click('button[type="submit"]');

  // Wait for the verify-code page
  await page.waitForURL('**/verify-code**', { timeout: 10000 });

  // Get the verification code
  const code = await getVerificationCode(page, email);

  // Enter the code
  await page.fill('input[name="code"]', code);

  // Submit verification
  await page.click('button[type="submit"]');

  // Wait for redirect to the app
  await page.waitForURL('**/for-you**', { timeout: 10000 });
}

/**
 * Extended test fixture with auth helpers.
 */
export const test = base.extend({
  /**
   * Provides a signed-in page (fresh user, not in seed data).
   * Usage: test('my test', async ({ authedPage }) => { ... })
   */
  authedPage: async ({ page }, use) => {
    await signIn(page);
    await use(page);
  },
  /**
   * Provides a signed-in page as the seed data user (seed@example.com).
   * This user has subscriptions, bookmarks, favorites, and items.
   * Usage: test('my test', async ({ seededPage }) => { ... })
   */
  seededPage: async ({ page }, use) => {
    await signIn(page, SEED_EMAIL);
    await use(page);
  },
});

/**
 * Navigate to a page and wait for its lazy-loaded content (via HTMX
 * hx-trigger="intersect once") to be fetched and swapped in.
 *
 * If HTMX fires normally the helper just waits for the content response.
 * If the response doesn't arrive within a short window, the helper
 * manually triggers the hx-get request so that tests don't depend on
 * IntersectionObserver timing in headless CI environments.
 */
async function gotoWithContent(page, pagePath, contentPath) {
  // Start listening for the content response BEFORE navigating
  const contentPromise = page.waitForResponse(
    resp => resp.url().includes(contentPath) && resp.status() === 200,
    { timeout: 15000 }
  ).catch(() => null);

  await page.goto(pagePath);

  // Give HTMX a moment to fire the intersect trigger naturally
  const resp = await Promise.race([
    contentPromise,
    page.waitForTimeout(3000).then(() => null),
  ]);

  if (!resp) {
    // HTMX didn't fire – manually trigger the lazy-load request
    await page.evaluate(async (cp) => {
      const el = document.querySelector(`[hx-get="${cp}"]`) ||
                 document.querySelector(`[hx-get*="${cp}"]`);
      if (el && window.htmx) {
        htmx.trigger(el, 'intersect');
      } else {
        // Fallback: fetch the content and swap it in ourselves
        const res = await fetch(cp);
        const html = await res.text();
        const content = document.querySelector('#content');
        if (content) {
          content.innerHTML = html;
          // Re-initialize HTMX on the new content if available
          if (window.htmx) htmx.process(content);
        }
      }
    }, contentPath);

    // Wait for the content to actually appear in the DOM
    await page.waitForTimeout(1000);
  }
}

export { expect, signIn, getVerificationCode, gotoWithContent, CONTENT_SERVER_URL, SEED_EMAIL };
