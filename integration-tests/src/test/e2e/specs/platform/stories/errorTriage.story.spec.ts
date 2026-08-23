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

import { expect, test, type APIRequestContext } from '@playwright/test';

import { ADMIN, adminAuth, signInAs } from '../../../support/auth';

/**
 * The whole of error triage, told as one story: something goes wrong on the instance, an
 * administrator is told about it on the console, works out what happened, records what she decided,
 * and sees that it no longer needs attention.
 *
 * It is a story rather than a set of specs because every step depends on the one before, and because
 * the point is the loop rather than any single screen. Several things here cannot be tested any other
 * way: whether the recorder actually writes a node for a fault raised in a real request, whether the
 * triage markers really are derived when the decision commits, and whether the acknowledge endpoint's
 * URL reaches its servlet at all — the last of which no unit test can see, since it is Sling's
 * parsing of the URL that decides it.
 */

/** Where the throwaway script goes: the homepage's own resource type, so `/` can be asked for it. */
const SCRIPT_PARENT = '/libs/app/Homepage';

/** Requesting `/.error` selects this script, the extension naming it exactly as `html` does. */
const SCRIPT_NAME = 'error.esp';

/**
 * What the recorded fault will be identified by. All three are part of the fingerprint, so together
 * they pick this story's error out of anything else the instance may have recorded.
 */
const COMPONENT = 'ErrorTriageStory';
const OPERATION = 'tellTheStory';
const MESSAGE = 'the administrator story asked for this to go wrong';

/**
 * A script that raises a genuine fault and hands it to the error tracker.
 *
 * Nothing in the platform calls ErrorLogger yet — wiring the callers is deliberately a separate
 * change — so a story about triaging errors has to cause one. Doing it from a real request, in server
 * script rather than by POSTing a node, is what makes this end to end: the fingerprint, the stack
 * trace, the recording thread and the derived triage marker are all the production ones.
 */
const SCRIPT = `<%
var boom = new Packages.java.lang.IllegalStateException("${MESSAGE}");
boom.fillInStackTrace();
Packages.io.uhndata.iap.errortracking.api.ErrorLogger.logError(
    boom,
    Packages.io.uhndata.iap.errortracking.api.ErrorContext.of("${COMPONENT}", "${OPERATION}"));
out.println("recorded");
%>`;

/** One row of the recorded errors, as the pagination servlet serializes it. */
interface ErrorRow {
  '@name': string;
  '@path': string;
  component?: string;
  operation?: string;
  occurrences?: number;
  computedTags?: string | string[];
}

/** The recorded errors this story's script is responsible for, newest first. */
async function storyErrors(request: APIRequestContext): Promise<ErrorRow[]> {
  const response = await request.get('/LoggedErrors.paginate.json?limit=100', { headers: adminAuth });
  expect(response.ok()).toBeTruthy();
  const page = (await response.json()) as { rows?: ErrorRow[] };
  return (page.rows ?? []).filter(row => row.operation === OPERATION);
}

/**
 * The fingerprint of the error this story raised, shared between its steps.
 *
 * Not known in advance: the node is named after a digest of the fault, which is the whole point of
 * the identity, so the story has to read it back the way any client would.
 */
let fingerprint = '';

test.describe.configure({ mode: 'serial' });

test.describe('an administrator triages an error the instance could not deal with', () => {
  // A story is many steps against a real instance, where a spec is one. Each step below still gets
  // its own budget, which is what keeps a failure pointing at the step that broke rather than at
  // the story as a whole.
  test.slow();

  test.afterAll(async ({ playwright, baseURL }) => {
    // Leave the instance as it was found: the platform suite's other specs assert on empty states,
    // and a script left under /libs would be a live endpoint for every later test.
    const request = await playwright.request.newContext({ baseURL });
    await request.post(`${SCRIPT_PARENT}/${SCRIPT_NAME}`, {
      headers: adminAuth,
      form: { ':operation': 'delete' },
    });
    if (fingerprint !== '') {
      await request.post(`/LoggedErrors/${fingerprint}`, {
        headers: adminAuth,
        form: { ':operation': 'delete' },
      });
    }
    await request.dispose();
  });

  test('something goes wrong, and the instance records it', async ({ request }) => {
    // Two requests, not one: Sling creates the node a file part's path implies before it applies a
    // primary type, so a script has to be uploaded rather than described
    const upload = await request.post(SCRIPT_PARENT, {
      headers: adminAuth,
      multipart: {
        [`./${SCRIPT_NAME}`]: {
          name: SCRIPT_NAME,
          mimeType: 'application/x-ecmascript',
          buffer: Buffer.from(SCRIPT, 'utf-8'),
        },
        [`./${SCRIPT_NAME}@TypeHint`]: 'nt:file',
      },
    });
    expect(upload.status(), 'the script should have been installed').toBeLessThan(300);

    const ran = await request.get('/.error', { headers: adminAuth });
    expect(ran.ok(), 'requesting /.error should run the script').toBeTruthy();
    expect(await ran.text()).toContain('recorded');

    // The recorder tallies on the request thread and writes on its own, so the node appears very
    // shortly afterwards rather than immediately. A first-seen fault is written at once — the
    // 60-second interval only withholds faults already written inside the window — so this is a
    // short wait rather than a minute.
    await expect.poll(async () => (await storyErrors(request)).length, {
      message: 'the fault should have been recorded under /LoggedErrors',
    }).toBe(1);

    const [ recorded ] = await storyErrors(request);
    fingerprint = recorded['@name'];
    expect(fingerprint).not.toBe('');
    expect(recorded.component).toBe(COMPONENT);
    // Derived by the tag processor in the recorder's own commit, which is what puts it on the
    // console's "needing attention" count without anybody placing a tag
    expect(recorded.computedTags).toContain('unacknowledged');
  });

  test('the console tells her something needs attention', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');

    const widget = page.getByRole('heading', { name: 'Recorded errors' });
    await expect(widget).toBeVisible();
    await expect(page.getByText('Needing attention')).toBeVisible();
    // The count is the label's sibling rather than a labelled value
    await expect(page.getByText('Recorded in total')).toBeVisible();
    await expect(page.getByText('Everything recorded has been dealt with.')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Triage errors' })).toBeVisible();
  });

  test('she follows it through to the listing and finds the fault', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Triage errors' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Recorded errors' })).toBeVisible();
    // The fault is shown by the throwable's simple name; the package would repeat down the column
    await expect(page.getByRole('gridcell', { name: 'IllegalStateException' })).toBeVisible();
    await expect(page.getByRole('gridcell', { name: OPERATION })).toBeVisible();
    await expect(page.getByRole('gridcell', { name: 'Needs attention' })).toBeVisible();
  });

  test('she opens it and sees what actually happened', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto(`/admin/errors/${fingerprint}`);

    await expect(page.getByRole('heading', { level: 1, name: 'IllegalStateException' })).toBeVisible();
    await expect(page.getByText(COMPONENT)).toBeVisible();
    await expect(page.getByText(OPERATION)).toBeVisible();
    await expect(page.getByText('Something was thrown')).toBeVisible();

    // The qualified name and the message each appear twice on this page — once as a fact, and again
    // inside the stack trace, which opens with "java.lang.IllegalStateException: <message>". Both
    // are counted rather than located loosely: asserting on one of two matches would pass just as
    // well if the fact were missing and only the trace remained.
    await expect(page.getByText('java.lang.IllegalStateException')).toHaveCount(2);
    await expect(page.getByText(MESSAGE)).toHaveCount(2);

    // A real stack trace, from the request that raised it
    await expect(page.getByText('Stack trace')).toBeVisible();
    await expect(page.getByText('Nobody has recorded a decision about this yet.')).toBeVisible();
  });

  test('she records what she decided, and the error says so', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto(`/admin/errors/${fingerprint}`);
    await expect(page.getByRole('heading', { level: 1, name: 'IllegalStateException' })).toBeVisible();

    await page.getByRole('combobox', { name: 'Decision' }).click();
    await page.getByRole('option', { name: /Known issue/ }).click();
    await page.getByRole('textbox', { name: /Why/ }).fill('Raised on purpose by the triage story.');
    await page.getByRole('button', { name: 'Record decision' }).click();

    await expect(page.getByRole('alert')).toContainText('Decision recorded');
    // Re-read from the server rather than patched in the browser: the markers are derived from the
    // decision when the write commits. Both of them are shown, since the processor derives the
    // umbrella `acknowledged` alongside the decision itself and neither is the whole story.
    await expect(page.getByText('Acknowledged')).toHaveCount(1);
    await expect(page.getByText('Decisions (1)')).toBeVisible();
    // Three times, and all three are wanted: the marker derived from the decision, the decision
    // named in the list, and the select still showing the choice that was just made. Counted
    // rather than taken `.first()` so that losing any of them is a failure — the marker is what
    // the rest of the platform reads, the list entry is the record, and the select keeping its
    // value is what tells the reader what they submitted.
    await expect(page.getByText('Known issue')).toHaveCount(3);
    await expect(page.getByText('Raised on purpose by the triage story.')).toBeVisible();
    await expect(page.getByText(`${ADMIN.username} ·`)).toBeVisible();
  });

  test('and it no longer needs attention', async ({ page, request }) => {
    // The end of the loop, and the reason the marker is derived rather than placed: the error is
    // dealt with because a decision was recorded about it, not because anything tagged it so
    const [ triaged ] = await storyErrors(request);
    expect(triaged.computedTags).toContain('known-issue');
    expect(triaged.computedTags).not.toContain('unacknowledged');

    await signInAs(page, ADMIN);
    await page.goto('/admin/errors');
    // Both markers in the one Triage cell: that the error was dealt with, and what was decided.
    // Two substring matches on the same cell rather than one match on its whole accessible name,
    // which would pin down how the chips happen to be joined together; what matters is that
    // neither marker is missing, since either alone leaves the reader asking the other question.
    await expect(page.getByRole('gridcell', { name: 'Acknowledged' })).toBeVisible();
    await expect(page.getByRole('gridcell', { name: 'Known issue' })).toBeVisible();
    await expect(page.getByRole('gridcell', { name: 'Needs attention' })).toHaveCount(0);

    // Narrowed to what is outstanding, this error is gone
    await page.getByRole('switch', { name: 'Only what needs attention' }).click();
    await expect(page.getByText('Nothing needs attention.')).toBeVisible();

    await page.goto('/admin');
    await expect(page.getByText('Everything recorded has been dealt with.')).toBeVisible();
  });
});
