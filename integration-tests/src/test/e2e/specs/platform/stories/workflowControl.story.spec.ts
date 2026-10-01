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

import { adminAuth } from '../../../support/auth';

/**
 * A resource type comes under workflow control as soon as a system workflow targeting it is installed,
 * and leaves it when that workflow is removed — on a running instance, not only on one that has just
 * started.
 *
 * A story, because installing a system workflow is exactly the kind of change a spec may not make. And
 * end to end, because what decides it is the servlet resolver: it binds the resource types a servlet is
 * registered with and never sees a later change to them, which no unit test's service registry
 * reproduces. A type then stays with the Sling POST servlet, which writes whatever an event carries
 * straight into the content.
 */

/** A type nothing in the platform uses, so that only this story's workflow can put it under control. */
const TYPE = 'story/ControlledType';

/** What the events are aimed at, under a parent of no particular type that can be removed whole. */
const HOLDER = '/workflowControlStory';
const TARGET = `${HOLDER}/target`;

/** A system workflow needs no steps to claim its type: a definition and one inactive version will do. */
const WORKFLOW = '/SystemWorkflows/workflowControlStory';

/**
 * Who answered an event aimed at the target: the engine, refusing it with a 409 since no workflow
 * accepts it, or the Sling POST servlet, with a 200.
 */
async function sendEvent(request: APIRequestContext, form?: Record<string, string>): Promise<number> {
  return (await request.post(`${TARGET}.create.json`, { headers: adminAuth, form })).status();
}

/**
 * Removes the system workflow the way any content is deleted. A definition's own type is under workflow
 * control, so a POST to it is an event for the engine and never a Sling operation; and permanently, so that
 * no copy waits in the archive for the next run.
 */
async function removeWorkflow(request: APIRequestContext): Promise<number> {
  return (await request.delete(`${WORKFLOW}?permanent=true`, { headers: adminAuth })).status();
}

test.describe.configure({ mode: 'serial' });

test.describe('a resource type comes under workflow control while the instance runs', () => {
  test.slow();

  test.afterAll(async ({ playwright, baseURL }) => {
    // Leave the instance as it was found, whichever step failed
    const request = await playwright.request.newContext({ baseURL });
    await removeWorkflow(request);
    await request.post(HOLDER, { headers: adminAuth, form: { ':operation': 'delete' } });
    await request.dispose();
  });

  test('when a system workflow targeting it is installed', async ({ request }) => {
    const created = await request.post(TARGET, {
      headers: adminAuth,
      form: { 'jcr:primaryType': 'nt:unstructured', 'sling:resourceType': TYPE },
    });
    expect(created.status(), 'the target should have been created').toBeLessThan(300);
    expect(await sendEvent(request), 'nothing targets the type yet').toBe(200);

    const installed = await request.post(WORKFLOW, {
      headers: adminAuth,
      form: {
        'jcr:primaryType': 'wf:WorkflowDefinition',
        'title': 'Workflow control story',
        'v1/jcr:primaryType': 'wf:WorkflowVersion',
        'v1/version': '1.0',
        'v1/targetResourceType': TYPE,
      },
    });
    expect(installed.status(), 'the system workflow should have been installed').toBeLessThan(300);

    // Hearing of the new workflow is asynchronous, so the type changes hands shortly after, not at once
    await expect.poll(() => sendEvent(request), {
      message: 'the type never came under workflow control',
      timeout: 30_000,
    }).toBe(409);

    // Refused, and nothing the event carried was written
    expect(await sendEvent(request, { note: 'written' })).toBe(409);
    const target = await request.get(`${TARGET}.json`, { headers: adminAuth });
    expect((await target.json() as { note?: string }).note).toBeUndefined();
  });

  test('and leaves it when that workflow is removed', async ({ request }) => {
    expect(await removeWorkflow(request), 'the system workflow should have been removed').toBeLessThan(300);

    await expect.poll(() => sendEvent(request), {
      message: 'the type never left workflow control',
      timeout: 30_000,
    }).toBe(200);
  });
});
