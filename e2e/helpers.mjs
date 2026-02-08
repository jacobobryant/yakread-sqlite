/**
 * Test helpers and fixtures for Yakread e2e tests.
 *
 * Auth approach: We POST to /auth/send-code to trigger code generation,
 * then read the code from the test-code endpoint (a small server-side
 * addition for testing). If the test-code endpoint isn't available,
 * we fall back to reading from a file that the dev server writes to.
 */
import { test as base, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

const CONTENT_SERVER_URL = 'http://localhost:8888';
const CODE_FILE = path.join(process.cwd(), 'storage', 'test-auth-code.txt');

/**
 * Wait for and retrieve the verification code.
 * In dev mode, Biff logs the code. We have a few strategies:
 * 1. Read from a test endpoint /dev/auth-code
 * 2. Read from a file the server writes to
 * 3. Use a fixed code in test mode
 */
async function getVerificationCode(page, email) {
  // Strategy 1: Try the dev auth-code endpoint
  try {
    const response = await page.request.get('/dev/auth-code?email=' + encodeURIComponent(email));
    if (response.ok()) {
      const code = (await response.text()).trim();
      if (code && code.length === 6) {
        return code;
      }
    }
  } catch (e) {
    // Fall through to next strategy
  }

  // Strategy 2: Read from file
  for (let i = 0; i < 10; i++) {
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

  throw new Error('Could not retrieve verification code');
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
   * Provides a signed-in page.
   * Usage: test('my test', async ({ authedPage }) => { ... })
   */
  authedPage: async ({ page }, use) => {
    await signIn(page);
    await use(page);
  },
});

export { expect, signIn, getVerificationCode, CONTENT_SERVER_URL };
