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

/**
 * What the persona a person is wearing changes about their dashboard.
 *
 * Presentation only, and that is the whole point of testing it here rather than only in the unit
 * suites: the widget's own declaration, the extension manager's predicate and the dashboard's layout
 * have to agree, and each of the three is written in a different place. Nothing about *rights* is
 * being asserted — the same account sees a different dashboard depending only on the hat it wears.
 */
test.describe('the personas a dashboard is arranged for', () => {
  // Each widget is recognised by the accessible name of its own search box, which is the one thing on
  // the page that is unmistakably it and does not depend on what it currently holds. Its empty state
  // would be shorter to write and was what this suite used to do - but the platform instance is shared,
  // and `create-submission.spec.ts` files submissions as this very account, so "No submissions" stops
  // being true partway through the project depending on which spec ran first.
  const MINE = 'Search my submissions';

  const WAITING = 'Search what is waiting for you';

  test('gives a submitter the table of their own requests', async ({ page }) => {
    await signInAs(page, ADMIN);

    // Submitter is where everyone lands: the catalogue is ordered least-permissive-first and login
    // takes the first entry
    await expect(page.getByRole('button', { name: /^Acting as Submitter/ })).toBeVisible();
    await expect(page.getByLabel(MINE)).toBeVisible();
  });

  test('takes it away from a reviewer, whose dashboard is not about their own requests', async ({ page }) => {
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);
    await expect(page.getByLabel(MINE)).toBeVisible();

    await shell.actAs('Reviewer');

    // The same account, one hat later: "my submissions" is a submitter's view of the world. The list
    // of what is waiting stays, which is what makes this the widget going away rather than the page
    await expect(page.getByLabel(MINE)).toHaveCount(0);
    await expect(page.getByLabel(WAITING)).toBeVisible();
  });

  test('brings it back when the submitter hat goes on again', async ({ page }) => {
    // Nothing is remembered across a switch, which is what makes the persona a view rather than a
    // setting: the widget is re-laid out from the same extensions, not re-fetched from the server
    const shell = new AppShell(page);
    await signInAs(page, ADMIN);

    await shell.actAs('Administrator');
    await expect(page.getByLabel(MINE)).toHaveCount(0);

    await shell.actAs('Submitter');
    await expect(page.getByLabel(MINE)).toBeVisible();
  });
});
