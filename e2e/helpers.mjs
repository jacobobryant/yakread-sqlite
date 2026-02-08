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

export { expect, signIn, getVerificationCode, CONTENT_SERVER_URL, SEED_EMAIL };
