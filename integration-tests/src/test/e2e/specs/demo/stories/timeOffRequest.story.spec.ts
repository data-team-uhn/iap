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
import { DashboardPage } from '../../../pages/dashboard.page';
import { LoginPage } from '../../../pages/login.page';
import { SubmissionPage } from '../../../pages/submission.page';
import { basicAuth } from '../../../support/auth';
import { childNodes } from '../../../support/nodes';
import { APPROVER, REQUESTER } from '../../../support/timeOffDemo';
import { uniqueTitle } from '../../../support/titles';

/** What the dashboard's dialog offers the demo's schema version as. */
const TIME_OFF_REQUEST = /Time off request 1\.0/;

const LAST_DAY_AWAY = /Which is your last day away/;

const FIRST_DAY_AWAY = /Which day does your time off start/;

/**
 * THE STORIES: two ways a request goes through, told end to end.
 *
 * The first is the ordinary one: asked for, decided, and seen to have been decided. The second asks
 * for something no answer can supply. The request wants a document, and it is ready to send only once
 * the document arrives.
 *
 * ---
 *
 * STORY ONE: asking for time off, and getting it.
 *
 * Priya needs the last week of November away. She signs in and raises a time off request. She fills
 * it in: several days, the days she means to be away, and what kind of absence it is. The first last
 * day she gives would take more time off than she has left, and the form says so at once. She corrects
 * it, and the request is complete. She sends it and signs out.
 *
 * Her approver signs in, finds the request waiting for a decision, reads what she asked for, and
 * approves it. He signs out.
 *
 * Priya signs back in and sees that her time off was approved.
 *
 * ---
 *
 * Each part of the story above is a step below. A step that needs something the platform cannot do
 * yet is marked `test.fixme`, and its message names what is missing.
 */
test.describe('a story: asking for time off, and getting it', () => {
  test.describe.configure({ mode: 'serial' });

  const AWAY_FROM = '2026-11-23';

  const AWAY_UNTIL = '2026-11-27';

  // More than the twelve days the demo's holiday bank gives this requester, which is the mistake the
  // story turns on
  const TOO_FAR = '2026-12-19';

  test('Priya asks for the last week of November, is told when it is too much, and sends it', async ({ page }) => {
    // One test tells the whole story: three sign-ins, two sign-outs and an autosaving form. That
    // needs more than the ordinary per-test budget.
    test.slow();

    const login = new LoginPage(page);
    const shell = new AppShell(page);
    const dashboard = new DashboardPage(page);
    const submission = new SubmissionPage(page);

    // Her approver and then Priya herself find the request again by this name
    const REQUEST = uniqueTitle('The last week of November');

    await test.step('she signs in', async () => {
      await login.open();
      await login.signInAs(REQUESTER.username, REQUESTER.password);
      expect(await shell.signedInAs()).toBe(REQUESTER.username);
    });

    await test.step('she raises a time off request', async () => {
      await dashboard.raiseSubmission(TIME_OFF_REQUEST, REQUEST);
    });

    await test.step('she fills it in, and the form asks for more as she does', async () => {
      await submission.edit();

      // The last day is not asked for until the answer makes it relevant
      await expect(page.getByLabel(LAST_DAY_AWAY)).toHaveCount(0);
      await page.getByRole('radio', { name: 'Several days' }).check();
      await expect(page.getByLabel(LAST_DAY_AWAY)).toBeVisible();

      await submission.answerDate(FIRST_DAY_AWAY, AWAY_FROM);
      await expect(page.getByText('Saved')).toHaveCount(2);
    });

    await test.step('she picks a month that would take more time than she has left', async () => {
      await submission.answerDate(LAST_DAY_AWAY, TOO_FAR);

      // The reason reaches the field that carries the answer, with both numbers, so she can act on it
      await page.getByText('Not saved').hover();
      await expect(page.getByRole('tooltip'))
        .toContainText('This asks for more time off than you have left. Requested: 27. Remaining: 12.');
    });

    await test.step('she corrects it, and the request is complete', async () => {
      await submission.answerDate(LAST_DAY_AWAY, AWAY_UNTIL);
      await expect(page.getByText('Not saved')).toHaveCount(0);

      // Written down rather than remembered: the answers come back from the repository
      await page.reload();
      await expect(page.getByLabel(LAST_DAY_AWAY)).toHaveValue(AWAY_UNTIL);
      await expect(page.getByLabel(FIRST_DAY_AWAY)).toHaveValue(AWAY_FROM);
    });

    await test.step('she says what kind of absence it is', async () => {
      await page.getByRole('radio', { name: 'Vacation' }).check();
      // One, not four: the reload remounted the editor, so only a field saved since then says so
      await expect(page.getByText('Saved')).toHaveCount(1);
    });

    await test.step('she sends it', async () => {
      // The button carries the name of the workflow's task. That task waits on whoever raised the
      // request, and the button completes it.
      await page.getByRole('button', { name: /Say when you want to be away/ }).click();

      // Out of her hands: submitted, shown rather than edited, and nothing left to send
      await expect(page.getByText('Submitted')).toBeVisible();
      await expect(page.getByRole('button', { name: 'View', pressed: true })).toBeVisible();
      await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);

      // Nor is she shown what her approver may do. The waiting decision names his team, not her, so
      // its controls are absent rather than refused.
      await expect(page.getByText('Approve the request')).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Approved' })).toHaveCount(0);
    });

    await test.step('and it will not take any more answers', async () => {
      // The same rule refuses a save on the server
      await submission.edit();

      await expect(page.getByText(/can no longer be changed/)).toBeVisible();
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });

    await test.step('her approver signs in and finds it waiting for him', async () => {
      test.fixme(true, 'His dashboard lists a request only once an open review names him, and a review is only '
        + 'created when the decision is recorded');
      await login.signInAs(APPROVER.username, APPROVER.password);
      expect(await shell.signedInAs()).toBe(APPROVER.username);

      // Found among what is waiting for him, not by its address. The approval task names his team as
      // its performers.
      await expect(page.getByRole('row', { name: new RegExp(REQUEST) })).toBeVisible();
    });

    await test.step('he reads what she asked for, and approves it', async () => {
      await page.getByRole('row', { name: new RegExp(REQUEST) }).getByText(REQUEST).click();
      await expect(page.getByText(REQUEST)).toBeVisible();

      // The outcomes come from the workflow definition, under the task's own name
      await expect(page.getByText('Approve the request')).toBeVisible();
      await page.getByRole('button', { name: 'Approved' }).click();

      // The decision carries a note saying why
      await page.getByLabel('Note').fill('Cover arranged with the rest of the team');
      await page.getByRole('button', { name: 'Record decision' }).click();

      // The end event placed the approved tag, and the page shows it. The approval the schema asked
      // for now names him and his note, where it said it was waiting on his team.
      await expect(page.getByText('Approved', { exact: true })).toBeVisible();
      await expect(page.getByText(/^Approved by demo-approver on /)).toBeVisible();
      await expect(page.getByText('Cover arranged with the rest of the team')).toBeVisible();
      // And there is nothing left for him to decide
      await expect(page.getByRole('button', { name: 'Approved' })).toHaveCount(0);
    });

    await test.step('he signs out', async () => {
      await shell.signOut();
    });

    await test.step('Priya signs back in and sees that her time off was approved', async () => {
      await login.signInAs(REQUESTER.username, REQUESTER.password);
      expect(await shell.signedInAs()).toBe(REQUESTER.username);

      // She finds it by searching her own list. The dashboard shows five rows a page, and she has more
      // than that by now. The order the rest of the suite created theirs in decides which page hers is
      // on. A search runs on the server and starts again at the first page.
      //
      // Her own submissions: the dashboard has two grids, each with a search box.
      await page.getByRole('region', { name: 'Submissions', exact: true }).getByRole('searchbox').fill(REQUEST);

      // Wait for the search before clicking what it found. Filtering is debounced and runs on the
      // server. A click straight after typing may hit a row of the unfiltered list, or one being
      // replaced. The count waits for the search and proves it worked.
      const row = page.getByRole('row', { name: new RegExp(REQUEST) });
      await expect(row).toHaveCount(1);

      // Scoped to a row, which keeps it off the search box that also holds the title. Retried as a
      // pair: the grid re-renders after a search, and a click that lands mid-render is lost. The cell
      // takes focus and nothing navigates. Clicking again is what a person would do.
      await expect(async () => {
        await row.getByText(REQUEST).click();
        await expect(page.getByRole('heading', { name: REQUEST })).toBeVisible({ timeout: 5_000 });
      }).toPass({ timeout: 30_000 });
      // Two things tell her. The request carries the approved tag. The decision is recorded against
      // the approval the schema asked for, so the page also names who granted it.
      await expect(page.getByText('Approved', { exact: true }).first()).toBeVisible();
      await expect(page.getByText(/^Approved by demo-approver on /)).toBeVisible();
      // And nothing is waiting for her either: the process is over
      await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);
    });
  });
});


/**
 * STORY TWO: being off sick, and having to prove it.
 *
 * Priya was unwell for a day. She raises a request and answers everything the form asks. Saying it
 * was sick leave makes the request ask for one more thing, which no answer can supply: a note from her
 * doctor. While the note is missing, the request is not ready. The control that would send it says so
 * by refusing.
 *
 * She attaches the note. That is the last thing the request was waiting for, so it becomes ready and
 * she sends it.
 *
 * ---
 *
 * It is a story rather than a spec: each step holds only because of the one before it. An answer
 * asks for the note. The missing note keeps the request unready. The note arriving makes it ready,
 * and a ready request can be sent. The server decides each of these, and only the whole sequence shows
 * them agreeing.
 */
test.describe('a story: being off sick, and having to prove it', () => {
  test.describe.configure({ mode: 'serial' });

  const SICK_DAY = '2026-09-14';

  test('Priya cannot send her sick day until the doctor\'s note is attached, and then she can',
    async ({ page, request }) => {
      // Each answer is a workflow event followed by a re-read of the form, and so is the upload. That
      // needs more than the ordinary per-test budget.
      test.slow();

      const login = new LoginPage(page);
      const shell = new AppShell(page);
      const dashboard = new DashboardPage(page);
      const submission = new SubmissionPage(page);

      // Declared inside the test, so that every attempt makes a new one
      const REQUEST = uniqueTitle('A day off sick');

      await test.step('she signs in', async () => {
        await login.open();
        await login.signInAs(REQUESTER.username, REQUESTER.password);
        expect(await shell.signedInAs()).toBe(REQUESTER.username);
      });

      await test.step('she raises a request for the day she was unwell', async () => {
        await dashboard.raiseSubmission(TIME_OFF_REQUEST, REQUEST);
      });

      await test.step('she answers everything the form asks', async () => {
        await submission.edit();

        // A full day, so the last day is never asked for
        await page.getByRole('radio', { name: 'Full day' }).check();
        await submission.answerDate(FIRST_DAY_AWAY, SICK_DAY);
        await expect(page.getByText('Saved')).toHaveCount(2);
      });

      await test.step('saying it was sick leave asks for something no answer can supply', async () => {
        // Absent, not hidden. The requirement applies only to a sick absence. The server decides that,
        // and the requirement arrives with the form it sends back.
        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toHaveCount(0);

        await page.getByRole('radio', { name: 'Sick leave' }).check();

        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toBeVisible();
        await expect(page.getByText('Attach a file for "Doctor\'s note"')).toBeVisible();
      });

      await test.step('and the request will not be sent while it is missing', async () => {
        // Every question is answered, so only the document keeps the request unready. The step refuses
        // rather than disappearing, because it is her step and it still needs something.
        await page.getByRole('button', { name: /Say when you want to be away/ }).click();
        await expect(page.getByText('Say when you want to be away did not go through')).toBeVisible();
        await expect(page.getByText('Answer everything this request asks for first.').first()).toBeVisible();
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
        // The upload re-ran the completeness check a save runs. The step reads the result afresh when
        // she takes it.
        await page.getByRole('button', { name: /Say when you want to be away/ }).click();

        await expect(page.getByText('Submitted')).toBeVisible();
        await expect(page.getByRole('button', { name: /Say when you want to be away/ })).toHaveCount(0);
      });

      await test.step('and the note is on the request for whoever decides it', async () => {
        // On the reading page, which lists each attachment under the requirement it answers
        await expect(page.getByRole('heading', { name: 'Doctor\'s note' })).toBeVisible();
        await expect(page.getByRole('link', { name: 'sick-note.pdf' })).toBeVisible();
      });

      await test.step('stored as a document of its own, which gives back what she uploaded', async () => {
        // She could have written none of it. A handler decided what may be attached and stored it as a
        // document naming the requirement it fulfils. The document's first version holds the file.
        const raised = new URL(page.url()).pathname;
        const response = await request.get(`${raised}.deep.-dereference.infinity.json`,
          { headers: basicAuth(REQUESTER) });
        expect(response.ok()).toBeTruthy();
        const documents = childNodes(await response.json())
          .filter(child => child['sling:resourceType'] === 'sub/Document');
        expect(documents).toHaveLength(1);
        expect(documents[0].title).toBe('sick-note.pdf');
        expect(documents[0].fulfills).toBeTruthy();
        const versions = childNodes(documents[0]).filter(child => child['jcr:primaryType'] === 'sub:DocumentVersion');
        expect(versions).toHaveLength(1);
        // The `files` processor flattens an nt:file into a download descriptor: the type and size move up
        // onto the file node, and the path streams the bytes
        const file = (versions[0].file as Record<string, unknown> | undefined)?.uploadedFile as
          Record<string, unknown> | undefined;
        expect(file?.['jcr:primaryType']).toBe('nt:file');
        expect(file?.['jcr:mimeType']).toBe('application/pdf');

        const download = await request.get(file?.['@path'] as string, { headers: basicAuth(REQUESTER) });
        expect(download.ok()).toBeTruthy();
        expect(await download.text()).toBe('%PDF-1.4 unfit for work on the 14th');
      });
    });
});
