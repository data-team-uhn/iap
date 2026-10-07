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

import { adminAuth } from '../../support/auth';

const POINTS = '/apps/iap/ExtensionPoints';

interface Extension {
  'ext:renderURL'?: string;
}

// An asset is built only when its module's assets.config lists it, and an extension naming one that
// was not is dropped at runtime without a word: its action or view is simply missing
test('every asset an extension names was really built', async ({ request }) => {
  const listing = (await (await request.get(`${POINTS}.1.json`, { headers: adminAuth }))
    .json()) as Record<string, unknown>;
  const points = Object.keys(listing).filter(name => typeof listing[name] === 'object');
  const built = (await (await request.get('/libs/iap/resources/assets.json', { headers: adminAuth }))
    .json()) as Record<string, string>;

  const named: string[] = [];
  for (const point of points) {
    const extensions = (await (await request.get(`${POINTS}/${point}`, { headers: adminAuth }))
      .json()) as Extension[];
    // The asset's name is what follows the prefix, without the options a query may add (`?lazy`), as the
    // asset manager reads it
    named.push(...extensions
      .map(extension => extension['ext:renderURL'] ?? '')
      .filter(url => url.startsWith('asset:'))
      .map(url => url.slice('asset:'.length).split('?')[0]));
  }

  // Guards the loop below: an empty walk would make it vacuous, so two assets known to be named are
  // looked for first
  expect(named).toEqual(expect.arrayContaining([
    'iap-workflows.WorkflowPropertiesAction.js', 'iap-workflows.WorkflowNewVersionAction.js',
  ]));
  for (const asset of named) {
    // Against the key list: toHaveProperty would read each dot in an asset name as a step into a nested
    // object
    expect(Object.keys(built), `${asset} is named by an extension but was not built`).toContain(asset);
  }
});
