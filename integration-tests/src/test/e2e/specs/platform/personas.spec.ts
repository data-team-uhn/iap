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

import { AppShell } from '../../pages/appShell.page';
import { ADMIN, signInAs } from '../../support/auth';

// The dashboard widgets' titles, which name their regions
const MY_SUBMISSIONS = 'Submissions';

const REVIEW_QUEUE = 'Review queue';

const widget = (page: Page, title: string) => page.getByRole('region', { name: title, exact: true });

test.describe('the dashboard', () => {
  test('signs everyone in acting as a submitter', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);

    expect(await shell.actingAs()).toBe('Submitter');
  });

  test('shows a submitter their own submissions', async ({ page }) => {
    await signInAs(page, ADMIN);

    await expect(widget(page, MY_SUBMISSIONS)).toBeVisible();
  });

  test('hides their own submissions from a reviewer, and keeps the review queue', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);
    await expect(widget(page, MY_SUBMISSIONS)).toBeVisible();

    await shell.actAs('Reviewer');

    await expect(widget(page, REVIEW_QUEUE)).toBeVisible();
    await expect(widget(page, MY_SUBMISSIONS)).toHaveCount(0);
  });

  test('hides their own submissions from an administrator, and shows them again to a submitter', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);
    await expect(widget(page, MY_SUBMISSIONS)).toBeVisible();

    await shell.actAs('Administrator');
    await expect(widget(page, REVIEW_QUEUE)).toBeVisible();
    await expect(widget(page, MY_SUBMISSIONS)).toHaveCount(0);

    await shell.actAs('Submitter');
    await expect(widget(page, MY_SUBMISSIONS)).toBeVisible();
  });
});
