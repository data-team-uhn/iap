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
 * Who may read the system workflows: anyone signed in, since they are what a user interface offers
 * actions from. What someone may *do* stays the engine's decision; this is only about reading.
 *
 * Asked as an account holding no groups and no grants. The administrator reads everything anyway, and
 * would pass whether or not the grant exists — which is also why no unit test can cover it.
 */
test.describe('the system workflows', () => {
  const BYSTANDER: Credentials = { username: 'bystander', password: 'bystander' };

  test('can be read by an account that has been given nothing', async ({ request }) => {
    await ensureUser(request, BYSTANDER);
    // Without the grant the account is served a 404. `maxRedirects: 0` because refused credentials are
    // answered with a redirect to the sign-in page, which would otherwise read as the definition served
    const asBystander = { headers: basicAuth(BYSTANDER), maxRedirects: 0 };

    const definition = await request.get('/SystemWorkflows/createWorkflow.json', asBystander);
    expect(definition.status(), 'the definition was not served').toBe(200);
    expect((await definition.json() as { title?: string }).title).toBe('Create a workflow');

    // A step deep in the tree, which is what an interface reads to learn what an event would do
    const step = await request.get('/SystemWorkflows/createWorkflow/v1/create.json', asBystander);
    expect(step.status(), 'the step was not served').toBe(200);
    expect((await step.json() as { handler?: string }).handler).toBe('createEntity');
  });
});
