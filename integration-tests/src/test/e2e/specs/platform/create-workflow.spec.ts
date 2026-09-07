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
 * The first system workflow, exercised over HTTP on the bare platform: a POST to /Workflows is translated into a
 * create event, the bootstrap workflow under /SystemWorkflows runs it, and the answer is a redirect to the
 * created definition. This is the workflow-first promise made concrete — nothing here talks to a CRUD endpoint,
 * and what a POST does is decided by an editable workflow definition, not by platform code.
 */
test.describe('creating a workflow through the bootstrap system workflow', () => {
  test('a POST to /Workflows creates a definition and redirects to it', async ({ request }) => {
    const response = await request.post('/Workflows', {
      headers: asAdmin,
      form: { title: 'Leave request approval' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(302);
    const location = response.headers().location;
    expect(location).toBe('/Workflows/leaveRequestApproval');

    const created = await request.get(`${location}.json`, { headers: asAdmin });
    expect(created.ok()).toBeTruthy();
    const definition = (await created.json()) as {
      'jcr:primaryType'?: string; title?: string; active?: boolean;
    };
    expect(definition['jcr:primaryType']).toBe('wf:WorkflowDefinition');
    expect(definition.title).toBe('Leave request approval');
    // Freshly created definitions are inactive until someone authors and enables them
    expect(definition.active ?? false).toBe(false);
  });

  test('identical titles get distinct names', async ({ request }) => {
    const first = await request.post('/Workflows', {
      headers: asAdmin, form: { title: 'Duplicated' }, maxRedirects: 0,
    });
    const second = await request.post('/Workflows', {
      headers: asAdmin, form: { title: 'Duplicated' }, maxRedirects: 0,
    });

    expect(first.status()).toBe(302);
    expect(second.status()).toBe(302);
    expect(first.headers().location).toBe('/Workflows/duplicated');
    expect(second.headers().location).toBe('/Workflows/duplicated2');
  });

  test('a POST without a title is refused as bad request', async ({ request }) => {
    const response = await request.post('/Workflows', {
      headers: asAdmin,
      form: { unrelated: 'value' },
      maxRedirects: 0,
    });

    expect(response.status()).toBe(400);
    const body = (await response.json()) as { error?: string };
    expect(body.error).toContain('title');
  });

  test('ships the bootstrap definition under /SystemWorkflows', async ({ request }) => {
    // The definition driving the behavior above is itself content, installed on the bare platform — which is
    // also what makes it customizable per deployment
    const response = await request.get('/SystemWorkflows/createWorkflow/v1.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const version = (await response.json()) as { active?: boolean; targetResourceType?: string };
    expect(version.active).toBe(true);
    expect(version.targetResourceType).toBe('wf/WorkflowsHomepage');
  });
});
