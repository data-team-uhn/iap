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

import { ADMIN, signInAs } from '../../support/auth';

/**
 * The workflow editor, as an administrative tool. This only tests that the workflows widget is
 * present and working in the admin console, not the actual functionality of the workflow editor.
 */
test.describe('the workflow editor as an administrative tool', () => {
  test('is offered on the administration console', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Manage workflows' })).toBeVisible();
  });

  test('is listed after the category manager, as its declared order asks', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();

    const titles = await page.getByRole('heading', { level: 6 }).allTextContents();
    expect(titles.indexOf('Submission categories')).toBeGreaterThanOrEqual(0);
    expect(titles.indexOf('Submission categories')).toBeLessThan(titles.indexOf('Workflows'));
  });

  test('says so on the console when no workflows are defined', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    // The bare platform ships the /Workflows tree but no definitions, so this is the empty state.
    // Ensure that there are indeed no workflows in the base platform, and that the widget says so.
    await expect(page.getByText('No workflows are defined yet.')).toBeVisible();
  });

  test('opens the BPMN editor at its own address', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Manage workflows' }).click();

    await expect(page).toHaveURL(/\/admin\/workflows$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Workflows' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Load' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New' })).toBeVisible();
  });
});
