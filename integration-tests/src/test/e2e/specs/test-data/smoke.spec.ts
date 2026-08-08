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

import { LoginPage } from '../../pages/login.page';

/**
 * The platform with the sample content installed, where every feature has something to show. This is
 * where broad functional coverage belongs; the tests below only establish that the instance really is
 * carrying the test data, which is what distinguishes it from the bare platform.
 */
test.describe('the instance with test data', () => {
  test('shows the participating institutions the sample registry ships', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();

    await expect(login.participatingInstitutions).toBeVisible();
    // Located as links rather than by image or text, for two reasons. It is unambiguous — the page footer
    // shows the same logo, so the alt text alone matches twice — and it asserts more: that each
    // institution's `url` is wired through to somewhere you can actually click.
    //
    // The two samples deliberately cover both rendering paths: one ships logo images and so is named by
    // its alt text, the other ships none and so is named by its own text.
    await expect(page.getByRole('link', { name: 'University Health Network' }))
      .toHaveAttribute('href', 'https://www.uhn.ca');
    await expect(page.getByRole('link', { name: 'Centre for Addiction and Mental Health' }))
      .toHaveAttribute('href', 'https://www.camh.ca');
  });

  test('offers the institutional sign-in method ahead of the local one', async ({ page }) => {
    // The sample content registers an external provider at order 10, which outranks the login module's own
    // credentials form at order 100. So this instance is where the "primary method plus collapsed
    // alternatives" arrangement is actually exercised; the bare platform only ever shows one method.
    const login = new LoginPage(page);
    await login.open();

    // The primary method renders in place with no heading of its own — what identifies it is its action,
    // built from the extension's `iap:actionLabel` and `iap:hint`. Only the collapsed alternatives get a
    // heading, which is what the toggle below is.
    await expect(page.getByRole('button', { name: 'Continue to sign-in' })).toBeVisible();
    await expect(page.getByText("You will be redirected to your institution's sign-in page.")).toBeVisible();
    await expect(login.localAccountToggle).toBeVisible();
    // Collapsed, so the credentials form is not on the page until it is asked for
    await expect(login.username).toHaveCount(0);
  });

  test('signs in with the sample credentials and leaves the sign-in page', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();
    // Reveals the collapsed credentials form first, since the institutional method is primary here
    await login.signInAs('admin', 'admin');

    await expect(page).not.toHaveURL(/\/login/);
  });

  test('ships the participating institutions registry', async ({ request }) => {
    // The API-level counterpart of the first test, which keeps working where no browser can run, and
    // which is what would catch both suites being pointed at the same instance.
    const response = await request.get('/libs/iap/ParticipatingInstitutions.1.json');

    expect(response.ok()).toBeTruthy();
    const registry = (await response.json()) as Record<string, { name?: string }>;
    const names = Object.values(registry)
      .map(entry => entry.name)
      .filter(Boolean);
    expect(names).toContain('University Health Network');
    expect(names).toContain('Centre for Addiction and Mental Health');
  });

  test('carries the sample entities the test-data module installs', async ({ request }) => {
    const response = await request.get('/TestEntities.json', {
      headers: { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` },
    });

    expect(response.ok()).toBeTruthy();
  });
});
