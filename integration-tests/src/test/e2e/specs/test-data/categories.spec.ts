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

import { expect, test, type Page } from '@playwright/test';

import { LoginPage } from '../../pages/login.page';
import { ADMIN, adminAuth } from '../../support/auth';

/**
 * The sample taxonomy of study types, which only this instance carries, and the two things that can
 * only be asserted against real categories: that the tree endpoints return them, and that the
 * administration console shows them. The endpoints' behaviour on an empty tree is the platform suite's.
 */
test.describe('the sample category taxonomy', () => {
  const signIn = async (page: Page): Promise<void> => {
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs(ADMIN.username, ADMIN.password);
    // The sign-in form going away is what says the session exists; navigating before that races it.
    await expect(login.signIn).toHaveCount(0);
  };

  test('installs the sample taxonomy under /Categories', async ({ request }) => {
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
    // The sample is a hierarchy, not a flat list: this one sits three levels down, so it also pins that
    // `deep` really does descend the whole subtree rather than stopping at the first generation.
    expect(tree.Prospective?.Observational?.SurveysEducation?.label).toBe('Surveys Education');
  });

  test('checks the loaded categories in', async ({ request }) => {
    // Categories are versionable, and content loaded into the repository by a bundle is checked out
    // unless the descriptor asks otherwise — leaving every sample category permanently without a base
    // version. The `checkin:=true` directive on the initial content is what puts them in a committed
    // state; edits from the administration UI check them out and back in again through the Sling POST
    // servlet, which is configured to do so automatically.
    const response = await request.get('/Categories/Retrospective.json', { headers: adminAuth });
    expect(response.ok()).toBeTruthy();

    const category = (await response.json()) as { 'jcr:isCheckedOut'?: boolean };
    expect(category['jcr:isCheckedOut']).toBe(false);
  });

  test('retires everything under a retired category', async ({ request }) => {
    // The one thing only a real repository can show: `retired` is an inheritable tag, so placing it on
    // a category makes Oak materialize it onto the whole subtree at commit time, into a separate
    // property. A mock enforces no node types and runs no commit editors, so this behaviour - the whole
    // reason retirement is a tag rather than a boolean - is invisible to the unit tests.
    const branch = '/Categories/Prospective/Observational';
    const leaf = `${branch}/SurveysEducation`;
    const patch = (value: string) => request.post(branch, {
      headers: adminAuth,
      form: { 'tags@TypeHint': 'String[]', 'tags@Patch': 'true', tags: value },
    });

    expect((await patch('+retired')).ok()).toBeTruthy();
    try {
      const response = await request.get(`${leaf}.json`, { headers: adminAuth });
      expect(response.ok()).toBeTruthy();

      const category = (await response.json()) as { tags?: string[]; inheritedTags?: string[] };
      // Inherited, not placed: the leaf was never written to, and only the branch can be unretired
      expect(category.inheritedTags).toContain('retired');
      expect(category.tags ?? []).not.toContain('retired');
    } finally {
      await patch('-retired');
    }

    const restored = await request.get(`${leaf}.json`, { headers: adminAuth });
    expect(((await restored.json()) as { inheritedTags?: string[] }).inheritedTags ?? [])
      .not.toContain('retired');
  });

  test('lists the sample categories through the pagination endpoint', async ({ request }) => {
    const response = await request.get('/Categories.paginate.json', { headers: adminAuth });
    expect(response.status()).toBe(200);

    const page = (await response.json()) as { totalrows: number; rows: { label?: string }[] };
    expect(page.totalrows).toBeGreaterThan(0);
    const labels = page.rows.map(row => row.label);
    expect(labels).toContain('Retrospective studies');
    expect(labels).toContain('Prospective studies');
  });

  test('summarises the sample categories on the administration console', async ({ page }) => {
    await signIn(page);
    await page.goto('/admin');

    // Top-level categories start collapsed, so only these two labels are on screen.
    await expect(page.getByText('Retrospective studies')).toBeVisible();
    await expect(page.getByText('Prospective studies')).toBeVisible();
  });

  test('opens the category manager on the sample tree', async ({ page }) => {
    await signIn(page);
    await page.goto('/admin');
    await page.getByRole('link', { name: 'Manage categories' }).click();

    await expect(page).toHaveURL(/\/admin\/categories$/);
    await expect(page.getByRole('button', { name: 'New category' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit Retrospective studies' })).toBeVisible();
  });
});
