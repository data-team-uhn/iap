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

const POINT = '/apps/iap/ExtensionPoints/AnswerComponent';

// The basic components the submissions module ships
const SHIPPED = [ 'Boolean', 'Choice', 'Date', 'File', 'Number', 'Text' ];

interface AnswerComponentExtension {
  'ext:name': string;
  'ext:renderURL': string;
}

/**
 * How a question gets the input that answers it. Each answer component is an
 * `ext:Extension` on this point naming its own asset, and the editor loads every one of them and
 * lets it register itself.
 *
 * Asserted here rather than in a unit test because each link in the chain is deployment, and each
 * fails quietly. The initial content has to install; the point has to find the extensions by their
 * `ext:pointId`; the asset name an extension gives has to resolve through `assets.json` to a file
 * that is really there. An asset resolving to nothing is logged to the browser console and its
 * extension is silently dropped, so a mistake in any of this leaves the editor unable to answer a
 * single question, with the build and every unit test still green.
 */
test.describe('the answer components', () => {
  test('are all declared on the extension point', async ({ request }) => {
    const response = await request.get(POINT, { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const extensions = (await response.json()) as AnswerComponentExtension[];
    expect(extensions.map(extension => extension['ext:name']).sort()).toEqual(SHIPPED);
  });

  test('each name an asset that was really built', async ({ request }) => {
    const extensions = (await (await request.get(POINT, { headers: adminAuth }))
      .json()) as AnswerComponentExtension[];
    const built = (await (await request.get('/libs/iap/resources/assets.json', { headers: adminAuth }))
      .json()) as Record<string, string>;

    // Guards the loop below: an empty extension point would make every assertion in it vacuous
    expect(extensions).toHaveLength(SHIPPED.length);
    for (const extension of extensions) {
      const asset = extension['ext:renderURL'].replace(/^asset:/, '');
      // Asserted against the key list, not with toHaveProperty: that reads a dot as a step into a
      // nested object, and every asset name has two of them, so it looks up
      // built['iap-submissions']['ChoiceAnswer']['js'] and reports the name as missing when it is
      // right there
      expect(Object.keys(built), `${asset} is named by an extension but was not built`)
        .toContain(asset);
      const file = await request.get(`/libs/iap/resources/${built[asset]}`, { headers: adminAuth });
      expect(file.ok(), `${built[asset]} could not be fetched`).toBeTruthy();
    }
  });
});
