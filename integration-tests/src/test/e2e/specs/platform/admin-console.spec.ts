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

import { expect, test, type Page } from '@playwright/test';

import { LoginPage } from '../../pages/login.page';
import { ADMIN, adminAuth } from '../../support/auth';

/**
 * The administration console: who may reach it, and whether the tools registered on it actually
 * arrive there.
 */
test.describe('the administration console', () => {
  const signIn = async (page: Page): Promise<void> => {
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs(ADMIN.username, ADMIN.password);
    // The sign-in form going away is what says the session exists; navigating before that races it.
    await expect(login.signIn).toHaveCount(0);
  };

  test('serves its tools to an administrator and to nobody else', async ({ request }) => {
    // `maxRedirects: 0` because otherwise it redirects to /login, a 200 sign-in page.
    const anonymous = await request.get('/Extensions/Admin.2.json', { maxRedirects: 0 });
    expect(anonymous.ok()).toBeFalsy();
    expect(await anonymous.text()).not.toContain('Submission categories');

    const administrator = await request.get('/Extensions/Admin.2.json', { headers: adminAuth });
    expect(administrator.ok()).toBeTruthy();
    expect(await administrator.text()).toContain('Submission categories');
  });

  test('offers the category manager as one of its tools', async ({ page }) => {
    await signIn(page);
    await page.goto('/admin');

    await expect(page.getByRole('heading', { name: 'Submission categories' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Manage categories' })).toBeVisible();
  });

  test('summarises the current categories on the console itself', async ({ page }) => {
    await signIn(page);
    await page.goto('/admin');

    // Top-level categories start collapsed, so only these two labels are on screen.
    await expect(page.getByText('Retrospective studies')).toBeVisible();
    await expect(page.getByText('Prospective studies')).toBeVisible();
  });

  test('opens the category manager on the seed tree', async ({ page }) => {
    await signIn(page);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Manage categories' }).click();

    await expect(page).toHaveURL(/\/admin\/categories$/);
    await expect(page.getByRole('button', { name: 'New category' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit Retrospective studies' })).toBeVisible();
  });
});
