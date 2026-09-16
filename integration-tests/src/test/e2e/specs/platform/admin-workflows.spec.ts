/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { expect, test } from '@playwright/test';

import { ADMIN, adminAuth, signInAs } from '../../support/auth';

/**
 * The workflow editor, as an administrative tool. This only tests that the workflows widget is
 * present and working in the admin console, not the actual functionality of the workflow editor.
 */
test.describe('the workflow editor as an administrative tool', () => {
  test('is offered on the administration console', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Manage: Workflows' })).toBeVisible();
  });

  test('is listed after the category manager, as its declared order asks', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();

    const titles = await page.getByRole('heading', { level: 6 }).allTextContents();
    expect(titles.indexOf('Submission categories')).toBeGreaterThanOrEqual(0);
    expect(titles.indexOf('Submission categories')).toBeLessThan(titles.indexOf('Workflows'));
  });

  test('counts the workflows in each place they are stored', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    // The widget answers what a dashboard is for - is there anything here, and how much - one line
    // per homepage. A count rather than a particular count: this project shares one instance across
    // its specs, and creating workflows is what several of them are about.
    const stored = page.getByRole('listitem')
      .filter({ has: page.getByRole('link', { name: 'Workflows', exact: true }) });
    await expect(stored).toContainText(/\d/);
  });

  test('opens the console at the homepage the dashboard points at', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Manage: Workflows' }).click();

    // The console's root is deliberately not a page: one segment below it names a homepage, and the
    // dashboard's action leads to the one every deployment has
    await expect(page).toHaveURL(/\/admin\/workflows\/Workflows$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New workflow' })).toBeVisible();
  });

  test('sends the console\'s own root to the default homepage', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin/workflows');

    // The root is a way in rather than a page: it names no homepage, so it opens the one every
    // deployment has instead of the blank screen an unrouted URL leaves behind
    await expect(page).toHaveURL(/\/admin\/workflows\/Workflows$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible();
  });

  // No real node is created behind /admin/workflows/*, check that the URL still resolves correctly
  test('serves a homepage to a request that starts there', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin/workflows/Workflows');

    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New workflow' })).toBeVisible();
  });

  test("serves the application to a request for a version's editor", async ({ request }) => {
    // The editor is asked for by extension - /admin/workflows/Workflows/review/1-0.edit - and `edit`
    // is an extension Sling knows nothing about: without a script for it, a URL the console hands out
    // 404s the moment it is opened, bookmarked or reloaded, however well the client router reads it.
    // No workflow need exist for this: what is being checked is that the shell is served at all.
    const served = await request.get('/admin/workflows/Workflows/review/1-0.edit', { headers: adminAuth });

    expect(served.status()).toBe(200);
    expect(await served.text()).toContain('main-container');
    // Nothing maps `edit` to a media type, so the shell's script has to state one, and has to state
    // it before a single byte is written - a charset can no longer be changed once the response has
    // handed out its writer, and the page would go out as ISO-8859-1 while declaring UTF-8 itself
    expect(served.headers()['content-type']).toContain('utf-8');
  });

  test('serves it to nobody who cannot reach the console', async ({ request }) => {
    // `maxRedirects: 0` because otherwise it redirects to /login, a 200 sign-in page.
    const anonymous = await request.get('/admin/workflows', { maxRedirects: 0 });
    expect(anonymous.status()).not.toBe(200);
  });
});
