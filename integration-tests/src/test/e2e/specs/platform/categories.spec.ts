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

/**
 * The submission categories a deployment starts with, and the three endpoints that read them.
 */
test.describe('submission categories', () => {
  test('installs the seed taxonomy under /Categories', async ({ request }) => {
    const response = await request.get('/Categories.deep.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    // Spelled out rather than typed loosely: what the test reads is exactly what it declares, and a
    // subcategory is serialized as a child object named after its node.
    const tree = (await response.json()) as {
      'jcr:primaryType'?: string;
      Retrospective?: { label?: string };
      Prospective?: { label?: string; Observational?: { SurveysEducation?: { label?: string } } };
    };
    expect(tree['jcr:primaryType']).toBe('cat:CategoriesHomepage');
    expect(tree.Retrospective?.label).toBe('Retrospective studies');
    expect(tree.Prospective?.label).toBe('Prospective studies');
    // The seed is a hierarchy, not a flat list: this one sits three levels down, so it also pins that
    // `deep` really does descend the whole subtree rather than stopping at the first generation.
    expect(tree.Prospective?.Observational?.SurveysEducation?.label).toBe('Surveys Education');
  });

  test('paginates without tripping over its irregular plural', async ({ request }) => {
    // Regression test. The pagination endpoint every entity homepage carries derives the child node type
    // from the homepage's resource type by stripping "sHomepage", which turns cat/CategoriesHomepage
    // into `cat:Categorie`, so the query is rejected. The CND pins `childNodeType` explicitly to prevent it.
    const response = await request.get('/Categories.paginate.json', { headers: adminAuth });
    expect(response.status()).toBe(200);

    const page = (await response.json()) as { totalrows: number; rows: { label?: string }[] };
    expect(page.totalrows).toBeGreaterThan(0);
    const labels = page.rows.map(row => row.label);
    expect(labels).toContain('Retrospective studies');
    expect(labels).toContain('Prospective studies');
  });

  test('publishes a catalogue of itself', async ({ request }) => {
    const response = await request.get('/Categories.doc.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const catalogue = (await response.json()) as unknown;
    expect(JSON.stringify(catalogue)).toContain('Submission categories');
  });

  test('renders the same catalogue as Markdown', async ({ request }) => {
    const response = await request.get('/Categories.doc.md', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    expect(await response.text()).toContain('Submission categories');
  });
});
