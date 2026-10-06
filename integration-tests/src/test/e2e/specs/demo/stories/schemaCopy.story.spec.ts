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
 * A new schema can start as a copy of a version of another, which is how a process already in use is
 * revised without touching it.
 *
 * A story, because creating a schema is a change a spec may not make. And end to end, because the copy
 * exists only as steps of the deployed workflows: creating the schema calls on its `createVersion`,
 * which creates the version, copies the source into it with the engine's `copyContent` task, and marks
 * it a draft. Each step has its own tests; only a running instance shows them joined up.
 */

/** The demo's request schema: active and in use, so a copy of it is the case that matters. */
const SOURCE = '/Schemas/timeOffRequest/v1';

type SerializedNode = Record<string, unknown>;

const isVersion = (child: unknown): child is SerializedNode =>
  typeof child === 'object' && child !== null
  && (child as SerializedNode)['jcr:primaryType'] === 'sch:SchemaVersion';

/** A node with its references as raw identifiers, so that two nodes' references can be compared. */
async function read(request: APIRequestContext, path: string): Promise<SerializedNode> {
  const response = await request.get(`${path}.-dereference.json`, { headers: adminAuth });
  expect(response.status(), `${path} should be readable`).toBe(200);
  return await response.json() as SerializedNode;
}

/** The titles of every schema, drafts and retired included. */
async function schemaTitles(request: APIRequestContext): Promise<unknown[]> {
  const response = await request.get('/Schemas.1.-active.json', { headers: adminAuth });
  expect(response.status()).toBe(200);
  return Object.values(await response.json() as SerializedNode)
    .filter((child): child is SerializedNode => typeof child === 'object' && child !== null)
    .map(child => child.title);
}

test.describe('a new schema can start as a copy of a version of another', () => {
  test('which it copies whole, under its own label and as a draft', async ({ request }) => {
    const created = await request.post('/Schemas.create.json', {
      headers: adminAuth,
      form: { title: `Copy story ${Date.now()}`, version: 'Revised', source: SOURCE },
      maxRedirects: 0,
    });
    expect(created.status(), await created.text()).toBe(302);
    const schema = (await created.json() as { path: string }).path;

    // A draft is listed only to a reader asking for -active
    const listing = await request.get(`${schema}.1.simple.-active.json`, { headers: adminAuth });
    expect(listing.status()).toBe(200);
    const versions = Object.values(await listing.json() as SerializedNode).filter(isVersion);
    expect(versions).toHaveLength(1);
    expect(versions[0].tags, 'marking the copy a draft replaces where the source stood').toEqual(['draft']);
    const copy = String(versions[0]['@path']);

    const [original, copied] = await Promise.all([read(request, SOURCE), read(request, copy)]);
    expect(copied.version, 'the copy keeps its own label').toBe('Revised');
    expect(copied.description).toBe(original.description);
    expect(copied.workflow, 'the workflow is outside the copy, so both point at it').toBe(original.workflow);
    expect(original.tags, 'the source stays where it stood').toContain('active');

    const [question, copiedQuestion, copiedApproval] = await Promise.all([
      read(request, `${SOURCE}/details/day`),
      read(request, `${copy}/details/day`),
      read(request, `${copy}/approval`),
    ]);
    expect(copiedQuestion.text).toBe('Which day are you taking off?');
    expect(copiedQuestion.dataType).toBe('date');
    expect(copiedQuestion.minAnswers).toBe(1);
    expect(copiedQuestion['jcr:uuid'], 'answers will point at the question by its identifier').toBeTruthy();
    expect(copiedQuestion['jcr:uuid'], 'the copy has questions of its own').not.toBe(question['jcr:uuid']);
    expect(copiedApproval.approverGroup).toBe('time-off-approvers');
  });

  test('and is refused, leaving nothing behind, when the source is not a schema version', async ({ request }) => {
    const title = `Refused copy story ${Date.now()}`;
    const refused = await request.post('/Schemas.create.json', {
      headers: adminAuth,
      form: { title, source: '/Workflows/timeOffRequest/v1' },
      maxRedirects: 0,
    });
    expect(refused.status(), await refused.text()).toBe(400);
    expect((await refused.json() as { error?: string }).error).toContain('/Workflows/timeOffRequest/v1');

    // The schema was created before its version failed to copy, and the whole event is undone
    const titles = await schemaTitles(request);
    expect(titles, 'the listing should show the schemas there are').toContain('Time off request');
    expect(titles).not.toContain(title);
  });
});
