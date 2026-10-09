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
 * The caught mail console on a bare distribution, where the catcher ships but is switched off.
 *
 * This is the case worth an explicit test rather than the obvious one. The safety argument for
 * shipping the catcher everywhere is that it publishes nothing until somebody asks, so an assertion
 * that this instance is *not* catching is an assertion about that argument. And an empty list means
 * opposite things either way round — "nothing has been sent" while catching, "everything sent was
 * delivered" while not — so the console has to say which, and this is where that is checked.
 */
test.describe('the caught mail console on a bare distribution', () => {
  test('says mail is being delivered rather than caught', async ({ request }) => {
    const response = await request.get('/CaughtMail.adminSummary.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const status = (await response.json()) as { enabled: { value: boolean }; total: { value: number } };
    // The bundle is in every aggregate; only test_tar and demo_tar add the configuration that
    // switches it on. Nothing here should ever have caught anything.
    expect(status.enabled.value).toBe(false);
    expect(status.total.value).toBe(0);
  });

  test('offers the tool, and its summary says nothing is being caught', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    // That the tool is here at all is the assertion: these extension nodes ship in the core
    // aggregate, so a production distribution carries the console page too
    await expect(page.getByRole('heading', { name: 'Caught mail' })).toBeVisible();
    // The catcher's state is what makes an empty mailbox readable
    await expect(page.getByText('Catching mail', { exact: true })
      .locator('xpath=ancestor-or-self::dt/following-sibling::dd[1]')).toHaveText('Off');
  });

  test('opens the list, which explains itself rather than looking broken', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Manage: Caught mail' }).click();

    await expect(page).toHaveURL(/\/admin\/mail$/);
    await expect(page.getByText('Nothing has been caught yet.')).toBeVisible();
    await expect(page.getByText(/Switch the catcher on/)).toBeVisible();
  });
});
