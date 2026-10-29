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

import { basicAuth, type Credentials } from '../../support/auth';
import { ensureUser } from '../../support/users';

/**
 * Who may file a submission: anyone signed in, since filing is what applicants do. Asked as an account holding no
 * groups and no grants, because the administrator would pass whether or not that holds.
 */
test.describe('the submissions bootstrap', () => {
  const BYSTANDER: Credentials = { username: 'bystander', password: 'bystander' };

  test('takes a request from an account that has been given nothing', async ({ request }) => {
    await ensureUser(request, BYSTANDER);
    // The bare platform has no schema to file against, so the request names one that does not exist. Being
    // refused for that by the bootstrap's own handler shows the request reached it
    const response = await request.post('/Submissions', {
      headers: basicAuth(BYSTANDER),
      form: { title: 'My day off', schemaVersion: '/Schemas/nowhere/v1' },
      maxRedirects: 0,
    });

    expect(response.status(), await response.text()).toBe(400);
    expect(await response.json()).toMatchObject({ error: expect.stringContaining('no schema version') });
  });
});
