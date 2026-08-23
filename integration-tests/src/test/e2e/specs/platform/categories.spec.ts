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
 * The submission categories a bare deployment starts with — none — and the three endpoints that read
 * them, all of which have to answer on an empty tree. The sample taxonomy, and everything that can only
 * be asserted against actual categories, lives in the test-data suite.
 */
test.describe('submission categories', () => {
  test('starts with an empty tree', async ({ request }) => {
    const response = await request.get('/Categories.deep.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const tree = (await response.json()) as Record<string, unknown>;
    expect(tree['jcr:primaryType']).toBe('cat:CategoriesHomepage');
    // A taxonomy is a deployment's own content: the module contributes the node types and the
    // administration UI, and nothing else. Anything the platform did ship here would be a category
    // every deployment has to notice and delete.
    const categories = Object.values(tree)
      .filter((value): value is Record<string, unknown> => typeof value === 'object' && value !== null)
      .filter(child => child['jcr:primaryType'] === 'cat:Category');
    expect(categories).toHaveLength(0);
  });

  test('paginates without tripping over its irregular plural', async ({ request }) => {
    // Regression test. The pagination endpoint every entity homepage carries derives the child node type
    // from the homepage's resource type by stripping "sHomepage", which turns cat/CategoriesHomepage
    // into `cat:Categorie`, so the query is rejected. The CND pins `childNodeType` explicitly to prevent
    // it. Oak rejects the unknown node type while parsing the query, before matching anything, so an
    // empty tree exercises this just as well as a populated one — the failure was a 500, not a bad
    // result set.
    const response = await request.get('/Categories.paginate.json', { headers: adminAuth });
    expect(response.status()).toBe(200);

    const page = (await response.json()) as { totalrows: number; rows: unknown[] };
    expect(page.totalrows).toBe(0);
    expect(page.rows).toHaveLength(0);
  });

  test('defines the retired tag it closes categories with', async ({ request }) => {
    // The categories module contributes this definition into /Tags, a node the tags module owns. It has
    // to be inheritable: that is what makes a category under a retired one retired as well, which is
    // what "this category or its subcategories" has always meant and what a boolean property could
    // only have promised.
    const response = await request.get('/Tags/retired.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const definition = (await response.json()) as {
      'jcr:primaryType'?: string;
      inheritable?: boolean;
      targetResourceTypes?: string[];
    };
    expect(definition['jcr:primaryType']).toBe('tag:Definition');
    expect(definition.inheritable).toBe(true);
    expect(definition.targetResourceTypes).toContain('cat/Category');
  });

  test('publishes a catalogue of itself', async ({ request }) => {
    const response = await request.get('/Categories.doc.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    // The heading and introduction come from the iap:Documented mixin on /Categories, so the catalogue
    // describes itself even while there is nothing in it to list.
    const catalogue = (await response.json()) as unknown;
    expect(JSON.stringify(catalogue)).toContain('Submission categories');
  });

  test('renders the same catalogue as Markdown', async ({ request }) => {
    const response = await request.get('/Categories.doc.md', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();
    expect(await response.text()).toContain('Submission categories');
  });
});
