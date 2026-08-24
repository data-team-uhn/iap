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
import { uniqueTitle } from '../../../support/titles';

/**
 * THE STORIES: two ways a request goes through, told end to end.
 *
 * The first is the ordinary one — asked for, decided, seen to have been decided. The second is the
 * one that cannot be answered with words: a request that asks for a document, which is a different
 * kind of incompleteness and a different way of becoming ready to send.
 *
 * ---
 *
 * STORY ONE: asking for time off, and getting it.
 *
 * Priya needs the last week of November away. She signs in, raises a time off request, and fills it
 * in: several days rather than a half or a full one, the days she means to be away, and what kind of
 * absence it is. She first picks a month that would take more time than she has left, and is told so
 * on the spot; she corrects it and the request is complete. She sends it and signs out.
 *
 * Her approver signs in, finds the request waiting for a decision, reads what she asked for, and
 * approves it. He signs out.
 *
 * Priya signs back in and sees that her time off was approved.
 *
 * ---
 *
 * Everything above is the story, and all of it is told below now: raised, decided, and seen to have
 * been decided. Where a part of it waits on a capability the platform does not have yet, it stays
 * here as prose and is named where it is missing, so this file doubles as the honest list of what the
 * demo cannot yet do end to end.
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

  test('Priya asks for the last week of November, is told when it is too much, and sends it', async ({ page }) => {
    // The whole story is one test rather than several, so the per-test budget has to stretch over
    // three sign-ins, two sign-outs and an autosaving form. That fits inside the 30s default on a
    // developer machine and does not on a two-core CI runner, where every step still passes and the
    // clock simply runs out.
    test.slow();

    const login = new LoginPage(page);
    const shell = new AppShell(page);

    // The request as Priya leaves it, which her approver and then she herself have to find again by
    // name. Unique per attempt: the instance outlives the test, so on a retry a fixed title would also
    // match the request the failed attempt left behind, and the lookup would fail on two rows rather
    // than on whatever went wrong first.
    const REQUEST = uniqueTitle('The last week of November');

    await test.step('she signs in', async () => {
      await login.open();
      await login.signInAs('demo-requester', 'demo-requester');
      expect(await shell.signedInAs()).toBe('demo-requester');
    });

    await test.step('she raises a time off request', async () => {
      await page.getByRole('button', { name: 'New submission' }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
      await dialog.getByLabel(/Title/).fill(REQUEST);
      await dialog.getByRole('button', { name: 'Create' }).click();
      // The heading, not the text: the dashboard she came from lists the request too, so a bare text
      // match is satisfied without her having left it
      await expect(page.getByRole('heading', { name: REQUEST })).toBeVisible();
    });

    await test.step('she fills it in, and the form asks for more as she does', async () => {
      // Scoped to the request's own View/Edit switch. The dashboard lists requests with an Edit
      // control per row, and waiting for those to go is not a guard: they are equally absent while
      // the listings are still loading, so the wait passes and the click lands among them once they
      // arrive. Naming the switch cannot be raced.
      await page.getByRole('group', { name: 'How to show this submission' })
        .getByRole('button', { name: 'Edit' }).click();

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

    await test.step('she sends it', async () => {
      // The control says what the process calls this step, because it *is* that step: the workflow
      // is parked on a task performed by whoever raised the request, and this completes it
      await page.getByRole('button', { name: /Say when you want to be away/ }).click();

      // Out of her hands, said by the state rather than by a message: the request is submitted, the
      // page is showing it rather than the editor, and there is nothing left for her to send
      await expect(page.getByText('Submitted')).toBeVisible();
      await expect(page.getByRole('button', { name: 'View', pressed: true })).toBeVisible();
      await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);

      // And she is not shown what her approver may do with it. The decision waiting now names his
      // team, not her — so it is absent rather than refused: a control she could see and not press
      // would tell her the same thing about somebody else's part in this
      await expect(page.getByText('Approve the request')).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Approved' })).toHaveCount(0);
    });

    await test.step('and it will not take any more answers', async () => {
      // Not a courtesy: the same rule refuses a save, so what the editor says and what the server
      // does cannot come apart
      await page.getByRole('group', { name: 'How to show this submission' })
        .getByRole('button', { name: 'Edit' }).click();

      await expect(page.getByText(/can no longer be changed/)).toBeVisible();
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });

    await test.step('her approver signs in and finds it waiting for him', async () => {
      await login.signInAs('demo-approver', 'demo-approver');
      expect(await shell.signedInAs()).toBe('demo-approver');

      // Found in the list of what is waiting for him rather than by being handed the address: the
      // request's approval names his team as its performers, and that alone is what puts it there
      await expect(page.getByRole('row', { name: new RegExp(REQUEST) })).toBeVisible();
    });

    await test.step('he reads what she asked for, and approves it', async () => {
      await page.getByRole('row', { name: new RegExp(REQUEST) }).getByText(REQUEST).click();
      await expect(page.getByText(REQUEST)).toBeVisible();

      // What he may decide it with comes from the definition, offered under the task's own name
      await expect(page.getByText('Approve the request')).toBeVisible();
      await page.getByRole('button', { name: 'Approved' }).click();

      // Deciding is not pressing a button: this is where the decision says why
      await page.getByLabel('Note').fill('Cover arranged with the rest of the team');
      await page.getByRole('button', { name: 'Record decision' }).click();

      // The end event the gateway routed to said what approving means to the request, so the page
      // says it too — and there is nothing left for him to decide
      await expect(page.getByText('Approved')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Approved' })).toHaveCount(0);
    });

    await test.step('he signs out', async () => {
      await shell.signOut();
    });

    await test.step('Priya signs back in and sees that her time off was approved', async () => {
      await login.signInAs('demo-requester', 'demo-requester');
      expect(await shell.signedInAs()).toBe('demo-requester');

      // She looks for it the way anyone with a few requests does: by searching her own list. The dashboard
      // shows five rows a page and she has more than that by now, so which page hers is on depends on the order
      // the rest of the suite happened to create theirs in. Searching is server-side and starts back at the
      // first page, which makes finding it hers to do rather than the pagination's to decide.
      //
      // Named rather than found by placeholder: this dashboard carries two grids, each with a search box
      // reading "Search…", so the placeholder alone matches both.
      await page.getByRole('searchbox', { name: 'Search my submissions' }).fill(REQUEST);

      // Scoped to a row, which is also what keeps it off the box she just typed the title into
      await page.getByRole('row', { name: new RegExp(REQUEST) }).getByText(REQUEST).click();

      await expect(page.getByRole('heading', { name: REQUEST })).toBeVisible();
      await expect(page.getByText('Approved')).toBeVisible();
      // And nothing is waiting for her either: the process is over
      await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);
    });
  });
});


/**
 * STORY TWO: being off sick, and having to prove it.
 *
 * Priya was unwell for a day. She raises a request and answers everything the form asks — and saying
 * that it was sick leave makes the request ask for one more thing, which no answer can supply: a note
 * from her doctor. While it is missing the request is not ready, and the control that would send it
 * says so by refusing.
 *
 * She attaches the note. That is the last thing the request was waiting for, so it becomes ready and
 * she sends it.
 *
 * ---
 *
 * Why this is a story and not another spec: each step is only true because of what the one before it
 * left behind. The note is asked for because of an answer; the request is unready because the note is
 * missing; the request becomes ready because the note arrives; and sending becomes possible because
 * the request is ready. Every one of those is decided on the server, and nothing but the whole
 * sequence shows them agreeing.
 */
test.describe('a story: being off sick, and having to prove it', () => {
  test.describe.configure({ mode: 'serial' });

  const SICK_DAY = '2026-09-14';

  test('Priya cannot send her sick day until the doctor\'s note is attached, and then she can',
    async ({ page }) => {
      // One sign-in and an autosaving form, but each answer is a workflow event followed by a re-read
      // and the upload is another: comfortably inside the default on a developer machine and not on a
      // two-core runner
      test.slow();

      const login = new LoginPage(page);
      const shell = new AppShell(page);

      // Unique per attempt, for the reason the other story gives: a retry runs against an instance that
      // still holds what the failed attempt created. Declared inside the test rather than beside the
      // describe so it is re-made for every attempt, whichever worker runs it.
      const REQUEST = uniqueTitle('A day off sick');

      await test.step('she signs in', async () => {
        await login.open();
        await login.signInAs('demo-requester', 'demo-requester');
        expect(await shell.signedInAs()).toBe('demo-requester');
      });

      await test.step('she raises a request for the day she was unwell', async () => {
        await page.getByRole('button', { name: 'New submission' }).click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('radio', { name: /Time off request 1\.0/ }).check();
        await dialog.getByLabel(/Title/).fill(REQUEST);
        await dialog.getByRole('button', { name: 'Create' }).click();
        await expect(page.getByRole('heading', { name: REQUEST })).toBeVisible();
      });

      await test.step('she answers everything the form asks', async () => {
        await page.getByRole('group', { name: 'How to show this submission' })
          .getByRole('button', { name: 'Edit' }).click();

        // A full day, so the return date never becomes relevant and the only thing left outstanding
        // is the one this story is about
        await page.getByRole('radio', { name: 'Full day' }).check();
        const start = page.getByLabel(/Which day does your time off start/);
        await start.fill(SICK_DAY);
        await start.blur();
        await expect(page.getByText('Saved')).toHaveCount(2);
      });

      await test.step('saying it was sick leave asks for something no answer can supply', async () => {
        // Not hidden until now — absent. The requirement applies only to a sick absence, and that is
        // decided where the condition lives, so it arrives with the form that comes back
        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toHaveCount(0);

        await page.getByRole('radio', { name: 'Sick leave' }).check();

        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toBeVisible();
        await expect(page.getByText('Nothing attached yet')).toBeVisible();
      });

      await test.step('and the request will not be sent while it is missing', async () => {
        // Every question is answered, so what makes the request unready is the document alone. The
        // control refuses rather than disappearing: this *is* her step, and she is being told what it
        // still needs, not that it belongs to somebody else
        await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toBeDisabled();
      });

      await test.step('she attaches the note', async () => {
        await page.getByLabel(/Attach a file for "Doctor's note"/).setInputFiles({
          name: 'sick-note.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.4 unfit for work on the 14th'),
        });

        // What the server says is there, not what the browser just sent
        await expect(page.getByText('Attached: sick-note.pdf')).toBeVisible();
      });

      await test.step('which was the last thing it was waiting for, so now she can send it', async () => {
        // The attach re-ran the same completeness check a save does, so the request stopped being
        // incomplete — and the control reads that rather than working it out again
        await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toBeEnabled();

        await page.getByRole('button', { name: /Say when you want to be away/ }).click();

        await expect(page.getByText('Submitted')).toBeVisible();
        await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);
      });

      await test.step('and the note is on the request for whoever decides it', async () => {
        // Where a reader looks rather than where she put it: the reading page groups what is attached
        // under the requirement it answers, and offers it back
        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toBeVisible();
        await expect(page.getByRole('link', { name: 'sick-note.pdf' })).toBeVisible();
      });
    });
});
