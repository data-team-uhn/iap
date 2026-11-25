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

import { ADMIN, adminAuth, basicAuth, signInAs, type Credentials } from '../../support/auth';
import { ensureUser } from '../../support/users';

/** An account with no part in administering anything. */
const READER: Credentials = { username: 'catalogue-reader', password: 'catalogue-reader' };

/** A sample catalogue nothing changes: one version retired, one active. */
const UNCHANGED = '/Catalogues/lifecycleUnchanged';

/** A catalogue, or one of its versions, as a listing serializes it, with the events the reader may send. */
interface ListedNode {
  title?: string;
  version?: string;
  tags?: string[];
  '@events'?: string[];
}

/**
 * The sample catalogues, which only this instance carries, and what each person is offered to do with them. Changing
 * them is the catalogue lifecycle story's.
 */
test.describe('the sample catalogues', () => {
  test('offers an administrator the events each state allows', async ({ request }) => {
    const response = await request.get(`${UNCHANGED}.1.simple.events.json`, { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const catalogue = (await response.json()) as ListedNode & { v1?: ListedNode; v2?: ListedNode };
    expect(catalogue.title).toBe('Unchanged registry');
    expect(catalogue['@events']).toEqual([ 'retire' ]);
    expect(catalogue.v1?.tags).toEqual([ 'retired' ]);
    expect(catalogue.v1?.['@events']).toEqual([ 'activate' ]);
    expect(catalogue.v2?.tags).toEqual([ 'active' ]);
    expect(catalogue.v2?.['@events']).toEqual([ 'retire' ]);
  });

  test('offers anybody else nothing', async ({ request }) => {
    await ensureUser(request, READER);

    const response = await request.get(`${UNCHANGED}.1.simple.events.json`, { headers: basicAuth(READER) });
    expect(response.ok()).toBeTruthy();

    const catalogue = (await response.json()) as ListedNode & { v2?: ListedNode };
    expect(catalogue['@events'] ?? []).toEqual([]);
    expect(catalogue.v2?.['@events'] ?? []).toEqual([]);
  });

  test('summarizes a version a data requirement names, with its catalogue', async ({ request }) => {
    const response = await request.get('/Schemas/cataloguePinning/v1/data.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    expect(((await response.json()) as { catalogue?: unknown }).catalogue).toEqual({
      '@path': `${UNCHANGED}/v1`,
      version: '2026-01',
      catalogue: { '@path': UNCHANGED, title: 'Unchanged registry' },
    });
  });

  test('lists the catalogues in the administration console, each version with where it stands', async ({ page }) => {
    await signInAs(page, ADMIN);
    await page.goto('/admin/catalogues/lifecycleUnchanged');

    await expect(page.getByRole('heading', { name: 'Unchanged registry' })).toBeVisible();
    await expect(page.getByText('Retired')).toBeVisible();
    await expect(page.getByText('Active', { exact: true })).toBeVisible();

    await page.getByRole('link', { name: '2026-02' }).click();
    await expect(page.getByRole('heading', { name: /Version 2026-02/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Patient records database' })).toBeVisible();
  });
});
