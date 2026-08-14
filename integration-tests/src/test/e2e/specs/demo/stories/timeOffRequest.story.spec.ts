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

import { AppShell } from '../../../pages/appShell.page';
import { LoginPage } from '../../../pages/login.page';

/**
 * THE STORY: asking for time off, and getting it.
 *
 * Priya needs the last week of November away. She signs in, raises a time off request, and fills it
 * in: several days rather than a half or a full one, the days she means to be away, and what kind of
 * absence it is. She first picks a month that would take more time than she has left, and is told so
 * on the spot; she corrects it and the request is complete. She submits it and signs out.
 *
 * Her approver signs in, finds the request waiting for a decision, reads what she asked for, and
 * approves it. He signs out.
 *
 * Priya signs back in and sees that her time off was approved.
 *
 * ---
 *
 * Everything above is the story. The steps below are it, as far as the platform can currently tell
 * it; the rest stays here as prose, and each part turns into steps as the capability it waits on
 * arrives. What is missing is named where it is missing, so this file is also the honest list of
 * what the demo still cannot do end to end.
 */
test.describe('a story: asking for time off, and getting it', () => {
  // One story, told in order: each step depends on what the last one left behind, so they share a
  // page and run in sequence rather than in parallel like ordinary specs.
  test.describe.configure({ mode: 'serial' });

  const AWAY_FROM = '2026-11-23';

  const AWAY_UNTIL = '2026-11-27';

  // More than the twelve days the demo's holiday bank gives this requester, which is the mistake the
  // story turns on
  const TOO_FAR = '2026-12-19';

  test('Priya asks for the last week of November, and is told when she asks for too much', async ({ page }) => {
    const login = new LoginPage(page);
    const shell = new AppShell(page);

    await test.step('she signs in', async () => {
      await login.open();
      await login.signInAs('demo-requester', 'demo-requester');
      expect(await shell.signedInAs()).toBe('demo-requester');
    });

    await test.step('she raises a time off request', async () => {
      await page.getByRole('button', { name: 'New submission' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
      await dialog.getByLabel(/Title/).fill('The last week of November');
      await dialog.getByRole('button', { name: 'Create' }).click();
      await expect(page.getByText('The last week of November')).toBeVisible();
    });

    await test.step('she fills it in, and the form asks for more as she does', async () => {
      await page.getByRole('button', { name: 'Edit' }).click();

      // The return date is not asked for until the answer makes it relevant
      await expect(page.getByLabel(/Which day are you back/)).toHaveCount(0);
      await page.getByRole('radio', { name: 'Several days' }).check();
      await expect(page.getByLabel(/Which day are you back/)).toBeVisible();

      const start = page.getByLabel(/Which day does your time off start/);
      await start.fill(AWAY_FROM);
      await start.blur();
      await expect(page.getByText('Saved')).toHaveCount(2);
    });

    await test.step('she picks a month that would take more time than she has left', async () => {
      const back = page.getByLabel(/Which day are you back/);
      await back.fill(TOO_FAR);
      await back.blur();

      await page.getByLabel('Not saved').hover();
      await expect(page.getByRole('tooltip')).toContainText('more time off than you have left');
    });

    await test.step('she corrects it, and the request is complete', async () => {
      const back = page.getByLabel(/Which day are you back/);
      await back.fill(AWAY_UNTIL);
      await back.blur();
      await expect(page.getByLabel('Not saved')).toHaveCount(0);

      // Written down rather than remembered: the answers come back from the repository
      await page.reload();
      await expect(page.getByLabel(/Which day are you back/)).toHaveValue(AWAY_UNTIL);
      await expect(page.getByLabel(/Which day does your time off start/)).toHaveValue(AWAY_FROM);
    });

    await test.step('she says what kind of absence it is', async () => {
      await page.getByRole('radio', { name: 'Vacation' }).check();
      // One, not four: the reload above remounted the editor, so what each field reports is what has
      // been saved since — the answers themselves are still there, which the reload just proved
      await expect(page.getByText('Saved')).toHaveCount(1);
    });

    // ---------------------------------------------------------------------------------------------
    // THE REST OF THE STORY, still prose because the platform cannot yet tell it:
    //
    //   She submits the request.
    //
    // Waiting on: a submit action. The editor saves answers and nothing more, so a request stays a
    // draft for ever — there is no control that says "this is finished", and no event that moves the
    // lifecycle tag off `draft`. Until then the story cannot leave her hands, and everything below
    // waits on this one step rather than on itself.
    //
    //   She signs out.
    //
    // Implementable today (AppShell.signOut), and deliberately not written yet: signing out here
    // would be a step that advances nothing, since the request it would be leaving behind is not in a
    // state anybody else can act on.
    //
    //   Her approver signs in, finds the request waiting for a decision, reads what she asked for,
    //   and approves it.
    //
    // Waiting on: anywhere for an approver to *see* a decision they owe. The engine already routes
    // the task and authorizes the decision — `carries the request through to approval when the
    // approver decides`, in the suite beside this one, drives exactly that over HTTP — so what is
    // missing is the screen, not the machinery: a list of tasks assigned to whoever is signed in, a
    // view of the request behind one, and controls that complete it.
    //
    //   He signs out. Priya signs back in and sees that her time off was approved.
    //
    // Waiting on: the two above. The last step is the cheapest of them — the submission page already
    // draws the lifecycle tag, so once something can set `approved`, seeing it is already built.
    // ---------------------------------------------------------------------------------------------
  });
});
