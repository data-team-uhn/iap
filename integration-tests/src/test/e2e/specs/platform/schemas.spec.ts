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

import { adminAuth } from '../../support/auth';

interface SerializedNode {
  'jcr:primaryType'?: string;
  '@path'?: string;
  tags?: string[];
}

// The schema versions serialized under a node, whatever their names
const versionsUnder = (node: Record<string, unknown>): SerializedNode[] =>
  Object.values(node).filter((child): child is SerializedNode =>
    typeof child === 'object' && child !== null
    && (child as SerializedNode)['jcr:primaryType'] === 'sch:SchemaVersion');

async function versionsOf(request: APIRequestContext, path: string, selectors: string): Promise<SerializedNode[]> {
  const response = await request.get(`${path}.1.${selectors}.json`, { headers: adminAuth });
  expect(response.status()).toBe(200);
  return versionsUnder(await response.json() as Record<string, unknown>);
}

/**
 * A schema created through its workflow starts with a draft version. The schema serialization leaves drafts
 * and the retired out unless asked for `-active`, which is what the admin console asks for, in its own reads
 * and in the selectors it hands the paginated listing.
 */
test.describe('the schemas', () => {
  test('list their drafts to a reader asking for -active', async ({ request }) => {
    const created = await request.post('/Schemas.create.json', {
      headers: adminAuth, form: { title: `Listing check ${Date.now()}` }, maxRedirects: 0,
    });
    expect(created.status(), await created.text()).toBe(302);
    const path = (await created.json() as { path: string }).path;

    expect(await versionsOf(request, path, 'simple'), 'a draft is not listed by default').toHaveLength(0);
    const versions = await versionsOf(request, path, 'simple.-active');
    expect(versions).toHaveLength(1);
    expect(versions[0].tags).toContain('draft');

    const page = await request.get(
      `/Schemas.paginate.json?limit=1000&resourceSelectors=${encodeURIComponent('1.simple.-active')}`,
      { headers: adminAuth });
    expect(page.status()).toBe(200);
    const row = (await page.json() as { rows: Record<string, unknown>[] }).rows.find(each => each['@path'] === path);
    expect(row, 'the new schema is not listed').toBeDefined();
    expect(versionsUnder(row ?? {}).map(version => version.tags)).toEqual([['draft']]);
  });
});
