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

const asAdmin = { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` };

/**
 * The submissions bootstrap on the bare platform. There are no schemas here, so what this suite can prove is
 * the refusal side — the payload layer doing its job — and that the bootstrap definition itself is installed;
 * the happy path lives in the demo suite, where a real, active schema exists to submit against.
 */
test.describe('creating a submission through the bootstrap system workflow', () => {
  test('a POST without a title is refused', async ({ request }) => {
    const response = await request.post('/Submissions', {
      headers: asAdmin,
      form: { schemaVersion: '/Schemas/somewhere/v1' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    expect(((await response.json()) as { error?: string }).error).toContain('title');
  });

  test('a POST without a schema version is refused', async ({ request }) => {
    const response = await request.post('/Submissions', {
      headers: asAdmin,
      form: { title: 'My day off' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    expect(((await response.json()) as { error?: string }).error).toContain('schemaVersion');
  });

  test('a POST against a schema version that does not exist is refused', async ({ request }) => {
    const response = await request.post('/Submissions', {
      headers: asAdmin,
      form: { title: 'My day off', schemaVersion: '/Schemas/nowhere/v1' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    expect(((await response.json()) as { error?: string }).error).toContain('no schema version');
  });

  test('ships the bootstrap definition under /SystemWorkflows', async ({ request }) => {
    // Contributed by the submissions module, not the workflows one: each module ships the system workflows
    // for its own homepages
    const response = await request.get('/SystemWorkflows/createSubmission/v1.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { active?: boolean; targetResourceType?: string };
    expect(version.active).toBe(true);
    expect(version.targetResourceType).toBe('sub/SubmissionsHomepage');
  });
});
