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

import { ADMIN, adminAuth, basicAuth, signInAs, type Credentials } from '../../../support/auth';
import { tagsOf } from '../../../support/nodes';
import { ensureUser } from '../../../support/users';

/** An account with no part in administering anything. */
const READER: Credentials = { username: 'catalogue-reader', password: 'catalogue-reader' };

/**
 * Sends a lifecycle event to a catalogue or one of its versions.
 *
 * @param path the repository path of the `datareq:Catalogue` or `datareq:CatalogueVersion`
 * @param event the event, such as `activate`
 * @param headers who sends it; the administrator if absent
 * @returns the HTTP status the engine answered with
 */
async function send(request: APIRequestContext, path: string, event: string, headers = adminAuth): Promise<number> {
  return (await request.post(`${path}.${event}.json`, { headers })).status();
}

/**
 * A story: the lifecycle of a data catalogue, as an administrator runs it. Each test has a sample catalogue of its
 * own, since the tests run side by side.
 */
test.describe('a story: publishing and retiring data catalogues', () => {
  test('activating a draft retires the version in use, and a retired version can be brought back', async ({
    request,
  }) => {
    const catalogue = '/Catalogues/lifecycleActivation';

    expect(await send(request, `${catalogue}/v2`, 'activate')).toBe(200);
    expect(await tagsOf(request, `${catalogue}/v2`)).toEqual([ 'active' ]);
    expect(await tagsOf(request, `${catalogue}/v1`)).toEqual([ 'retired' ]);

    expect(await send(request, `${catalogue}/v1`, 'activate')).toBe(200);
    expect(await tagsOf(request, `${catalogue}/v1`)).toEqual([ 'active' ]);
    expect(await tagsOf(request, `${catalogue}/v2`)).toEqual([ 'retired' ]);

    expect(await send(request, `${catalogue}/v1`, 'retire')).toBe(200);
    expect(await tagsOf(request, `${catalogue}/v1`)).toEqual([ 'retired' ]);
    // A retired version cannot be retired again
    expect(await send(request, `${catalogue}/v1`, 'retire')).toBe(409);
  });

  test('retiring a catalogue closes every version until it is reopened', async ({ request }) => {
    const catalogue = '/Catalogues/lifecycleRetirement';

    expect(await send(request, catalogue, 'retire')).toBe(200);
    expect(await tagsOf(request, catalogue)).toEqual([ 'retired' ]);
    // Neither the draft nor a retired version can be activated while the catalogue is retired
    expect(await send(request, `${catalogue}/v3`, 'activate')).toBe(409);
    expect(await send(request, `${catalogue}/v1`, 'activate')).toBe(409);

    expect(await send(request, catalogue, 'activate')).toBe(200);
    expect(await tagsOf(request, catalogue)).toEqual([]);
    // Each version is back where it stood
    expect(await tagsOf(request, `${catalogue}/v2`)).toEqual([ 'active' ]);
    expect(await send(request, `${catalogue}/v3`, 'activate')).toBe(200);
    expect(await tagsOf(request, `${catalogue}/v3`)).toEqual([ 'active' ]);
    expect(await tagsOf(request, `${catalogue}/v2`)).toEqual([ 'retired' ]);
  });

  test('only an administrator may change where a catalogue stands', async ({ request }) => {
    await ensureUser(request, READER);
    const version = '/Catalogues/lifecycleUnchanged/v2';

    expect(await send(request, version, 'retire', basicAuth(READER))).toBe(403);
    expect(await send(request, '/Catalogues/lifecycleUnchanged', 'retire', basicAuth(READER))).toBe(403);
    expect(await tagsOf(request, version)).toEqual([ 'active' ]);
  });

  test('a schema version cannot be published offering a catalogue version that is not open', async ({ request }) => {
    const response = await request.post('/Schemas/cataloguePinning/v1.activate.json', { headers: adminAuth });

    expect(response.status()).toBe(409);
    expect(((await response.json()) as { error?: string }).error)
      .toContain('offers version 2026-01 of "Unchanged registry", which is not open to new schema versions');
    expect(await tagsOf(request, '/Schemas/cataloguePinning/v1')).toEqual([ 'draft' ]);
  });

  test('an administrator activates a draft from the console', async ({ page, request }) => {
    const catalogue = '/Catalogues/lifecycleInterface';
    await signInAs(page, ADMIN);
    await page.goto('/admin/catalogues/lifecycleInterface');

    // Only the draft can be activated
    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Activate version 2026-02' });
    // The confirmation names the version this one replaces
    await expect(dialog).toContainText('Version 2026-01 is retired in the same step');
    await dialog.getByRole('button', { name: 'Activate', exact: true }).click();

    await expect(page.getByText('Version 2026-02 is active')).toBeVisible();
    await expect(page.getByText('Retired')).toBeVisible();
    expect(await tagsOf(request, `${catalogue}/v2`)).toEqual([ 'active' ]);
    expect(await tagsOf(request, `${catalogue}/v1`)).toEqual([ 'retired' ]);
  });
});
