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

import { WorkflowConsolePage } from '../../../pages/workflowConsole.page';
import { ADMIN, adminAuth, signInAs } from '../../../support/auth';

/**
 * THE STORY: a workflow, from its first draft to its retirement.
 *
 * Miriam administers this deployment. She authors a workflow for leave requests in the console, tries it,
 * puts it live, replaces it with a second version, and finally withdraws it — and at every step the console
 * offers her exactly the moves the version's lifecycle allows.
 *
 * Every step below is covered by a unit test somewhere, and none of that coverage says the path exists on a
 * live instance: that a lifecycle tag may be placed on a workflow version at all, which only the tag
 * definitions decide; that the guards read the tags the repository really holds; that activating a version
 * retires the one before it; or that a diagram saved from the editor is parsed into the flow nodes the engine
 * runs.
 */
test.describe('stories: a workflow from its first draft to its retirement', () => {
  // One workflow, carried through every episode in turn. Each episode is a dozen steps with a page load and a
  // round trip in most of them, so it gets a budget of its own, as the archive stories do.
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  const TITLE = 'Leave approval';

  // The user task of the starting diagram every new workflow is opened on
  const REVIEW_TASK = 'Activity_0waxs0q';

  let first = '';

  let second = '';

  test.afterAll(async ({ playwright, baseURL }) => {
    // Leave the instance as it was found, whichever episode failed. Over HTTP DELETE, since a POST to a
    // workflow is an event for the engine rather than a Sling operation; and permanently, so that no copy
    // waits in the archive for the next run.
    if (first !== '') {
      const request = await playwright.request.newContext({ baseURL });
      await request.delete(`${workflowOf(first)}?permanent=true`, { headers: adminAuth });
      await request.dispose();
    }
  });

  test('Miriam creates a workflow, and its first version is a draft to edit', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openFromConsole();

    await workflows.create(TITLE, 'Who approves time off');
    first = await workflows.edit('1.0');

    await expect(workflows.editorHeading(TITLE, '1.0')).toBeVisible();
    await expect(page.getByText('Draft', { exact: true })).toBeVisible();
    expect(await tagsOf(request, first)).toEqual([ 'draft' ]);
  });

  test('she names the review step, and the diagram she saves is the one the workflow runs', async ({
    page, request,
  }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openEditor(first);

    await workflows.rename(REVIEW_TASK, 'Review the request');
    await workflows.save();

    // Parsed into the flow nodes the engine runs, in the same commit that stored it
    const task = await request.get(`${first}/${REVIEW_TASK}.json`, { headers: adminAuth });
    expect(task.ok(), 'the review step was not derived from the diagram').toBeTruthy();
    expect(((await task.json()) as { label?: string }).label).toBe('Review the request');
  });

  test('she puts it on trial, and it can no longer be edited', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openWorkflow(workflowOf(first));
    await expect.poll(() => workflows.offered('1.0'))
      .toEqual([ 'Activate', 'Edit', 'New draft from this', 'Start trial' ]);

    await workflows.move('1.0', 'Start trial', 'Put version 1.0 on trial?', 'Trial');

    // Frozen as it stands: changed again only by going back to being a draft, or carried forward by a copy
    await expect.poll(() => workflows.offered('1.0'))
      .toEqual([ 'Activate', 'New draft from this', 'Return to draft' ]);
    await expect(workflows.runs()).toHaveText('Disabled');
    expect(await tagsOf(request, first)).toEqual([ 'trial' ]);
  });

  test('she activates it, and the workflow runs', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openWorkflow(workflowOf(first));

    await workflows.move('1.0', 'Activate', 'Activate version 1.0?', 'Active');

    await expect(workflows.runs()).toHaveText('Enabled');
    await expect.poll(() => workflows.offered('1.0')).toEqual([ 'New draft from this', 'Retire' ]);
    expect(await tagsOf(request, first)).toEqual([ 'active' ]);
  });

  test('she carries it forward as a second version, and activating that one retires the first', async ({
    page, request,
  }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openWorkflow(workflowOf(first));

    second = await workflows.draftFrom('1.0', '2.0');

    // The copy is a draft of its own: it does not take the original's place, nor its tag
    expect(await tagsOf(request, second)).toEqual([ 'draft' ]);
    expect(await tagsOf(request, first)).toEqual([ 'active' ]);

    await workflows.openWorkflow(workflowOf(first));
    await workflows.move('2.0', 'Activate', 'Activate version 2.0?', 'Active');

    // One workflow, one version running: the first stepped down in the same move
    await expect(workflows.version('1.0')).toContainText('Retired');
    await expect(workflows.runs()).toHaveText('Enabled');
    expect(await tagsOf(request, first)).toEqual([ 'retired' ]);
    expect(await tagsOf(request, second)).toEqual([ 'active' ]);
  });

  test('she withdraws it, and the workflow runs nothing until a version is activated again', async ({
    page, request,
  }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openWorkflow(workflowOf(first));

    await workflows.move('2.0', 'Retire', 'Retire version 2.0?', 'Retired');

    await expect(workflows.runs()).toHaveText('Retired');
    // Either version can be brought back, or carried forward
    await expect.poll(() => workflows.offered('2.0')).toEqual([ 'Activate', 'New draft from this' ]);
    await expect.poll(() => workflows.offered('1.0')).toEqual([ 'Activate', 'New draft from this' ]);
    expect(await tagsOf(request, second)).toEqual([ 'retired' ]);
  });
});

test.describe('stories: carrying a platform workflow forward', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  // A workflow the platform ships with its graph written by hand rather than derived from its diagram, which
  // is what makes copying it more than copying a diagram
  const PLATFORM = '/SystemWorkflows/createWorkflow';

  let copy = '';

  test.afterAll(async ({ playwright, baseURL }) => {
    if (copy !== '') {
      const request = await playwright.request.newContext({ baseURL });
      await request.delete(`${copy}?permanent=true`, { headers: adminAuth });
      await request.dispose();
    }
  });

  test('Miriam drafts a copy of a platform workflow, and the copy keeps the steps it was written with', async ({
    page, request,
  }) => {
    await signInAs(page, ADMIN);
    const workflows = new WorkflowConsolePage(page);
    await workflows.openWorkflow(PLATFORM);

    copy = await workflows.draftFrom('1.0', '2.0');

    expect(await tagsOf(request, copy)).toEqual([ 'draft' ]);
    expect(await tagsOf(request, `${PLATFORM}/v1`)).toEqual([ 'active' ]);
    // Step for step what the original runs, since no diagram is there to derive them from
    expect(await flowNodesOf(request, copy)).toEqual(await flowNodesOf(request, `${PLATFORM}/v1`));
  });
});

/** The workflow a version belongs to: its parent, since versions are stored inside their workflow. */
function workflowOf(version: string): string {
  return version.slice(0, version.lastIndexOf('/'));
}

/** The tags a node carries itself, as the repository serves them. */
async function tagsOf(request: APIRequestContext, path: string): Promise<string[]> {
  const response = await request.get(`${path}.json`, { headers: adminAuth });
  expect(response.ok(), `${path} could not be read`).toBeTruthy();
  return ((await response.json()) as { tags?: string[] }).tags ?? [];
}

/** A version's flow nodes as the repository serves them: each one's name, type and handler. */
async function flowNodesOf(request: APIRequestContext, version: string): Promise<string[]> {
  const response = await request.get(`${version}.1.json`, { headers: adminAuth });
  expect(response.ok(), `${version} could not be read`).toBeTruthy();
  return Object.entries((await response.json()) as Record<string, unknown>)
    .filter(([ , value ]) => typeof value === 'object' && value !== null
      && String((value as { 'jcr:primaryType'?: string })['jcr:primaryType']).startsWith('wf:'))
    .map(([ name, value ]) => {
      const node = value as { 'jcr:primaryType': string; handler?: string };
      return `${name} ${node['jcr:primaryType']} ${node.handler ?? ''}`.trim();
    })
    .sort();
}
