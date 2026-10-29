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

import { AppShell } from '../../pages/appShell.page';
import { ADMIN, signInAs } from '../../support/auth';

test.describe('the personas a dashboard is arranged for', () => {
  // Each widget is recognised by the accessible name of its search box, which is unmistakably that widget
  // whatever it currently holds. Its empty state would not do: the instance is shared, and other specs
  // file submissions as this account.
  const MINE = 'Search my submissions';

  const QUEUE = 'Search my review queue';

  test('gives a submitter the table of their own submissions', async ({ page }) => {
    await signInAs(page, ADMIN);

    // Submitter is where everyone lands: the catalogue is ordered least-permissive-first and the first
    // entry is the default
    expect(await new AppShell(page).actingAs()).toBe('Submitter');
    await expect(page.getByLabel(MINE)).toBeVisible();
  });

  test('takes it away from a reviewer, whose dashboard is not about their own submissions', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);
    await expect(page.getByLabel(MINE)).toBeVisible();

    await shell.actAs('Reviewer');

    // The review queue stays, which is what makes this the widget going away rather than the page
    await expect(page.getByLabel(MINE)).toHaveCount(0);
    await expect(page.getByLabel(QUEUE)).toBeVisible();
  });

  test('brings it back when the submitter hat goes on again', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);

    await shell.actAs('Administrator');
    await expect(page.getByLabel(MINE)).toHaveCount(0);

    await shell.actAs('Submitter');
    await expect(page.getByLabel(MINE)).toBeVisible();
  });
});
