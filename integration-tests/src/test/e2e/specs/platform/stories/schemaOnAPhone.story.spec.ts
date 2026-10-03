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

import { AppShell } from '../../../pages/appShell.page';
import { SchemaVersionPage } from '../../../pages/schemaVersion.page';
import { ADMIN, signInAs } from '../../../support/auth';
import { fitsTheScreen, pastTheEdge, PHONE } from '../../../support/phone';

/**
 * THE STORY: a schema set up from a phone.
 *
 * Tomás looks after what his team's requests ask for, and today all he has with him is his phone. He starts a
 * schema for equipment requests, gives its draft a form with two questions, and makes the second one asked only
 * when the first is answered a certain way, with a group of conditions as he would build it on a computer.
 * Nothing he reaches runs past the edge of his screen, and every action is there under a name he can read.
 *
 * Each step has its spec. Only this asks whether the whole path exists at a phone's width: the console, a new
 * schema, its draft's page, the menus that stand in for a part's buttons, and a condition with a group in it.
 */
test.describe('stories: a schema set up from a phone', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.use({ viewport: PHONE, isMobile: true, hasTouch: true });
  test.skip(({ browserName }) => browserName === 'firefox', 'Firefox cannot emulate a phone');

  const SCHEMA = 'Equipment request';

  const WHAT = 'What do you need?';

  const WHY = 'Why do you need it?';

  test('Tomás sets up a schema whose second question depends on the first', async ({ page }) => {
    const shell = new AppShell(page);
    const version = new SchemaVersionPage(page);

    await test.step('he signs in on his phone, and opens the schemas', async () => {
      await signInAs(page, ADMIN);
      await page.getByRole('button', { name: 'Administration' }).click();
      await page.getByRole('link', { name: /Schemas/ }).first().click();
      await expect(page.getByRole('heading', { name: 'Schemas' })).toBeVisible();
      await fitsTheScreen(page);
    });

    await test.step('he starts a schema, and opens its first version', async () => {
      await page.getByRole('button', { name: 'New schema' }).click();
      const dialog = page.getByRole('dialog', { name: 'New schema' });
      await dialog.getByLabel('Title').fill(SCHEMA);
      await dialog.getByRole('button', { name: 'Create' }).click();
      await page.getByText('Version 1.0').click();
      await expect(page.getByRole('heading', { name: /Version 1\.0/ })).toBeVisible();
      await fitsTheScreen(page);
    });

    await test.step('he gives the draft a form with two questions', async () => {
      await version.add(page.locator('main'), 'Form', /Label/, 'Equipment');
      // Empty, it starts closed
      await page.getByRole('button', { name: 'Expand Equipment' }).click();
      const form = version.part('Equipment');
      await version.add(form, 'Question', /Question/, WHAT);
      await version.add(form, 'Question', /Question/, WHY);
      await expect(page.getByText(WHY)).toBeVisible();
      await fitsTheScreen(page);
    });

    await test.step('he finds what can be done to the second question under its name', async () => {
      await page.getByRole('button', { name: `Actions for “${WHY}”` }).click();
      const menu = page.getByRole('menu');
      await expect(menu.getByRole('menuitem', { name: 'When it applies' })).toBeVisible();
      await expect(menu.getByRole('menuitem', { name: 'Remove' })).toBeVisible();
      await menu.getByRole('menuitem', { name: 'When it applies' }).click();
    });

    await test.step('he asks it only when the first is answered "Laptop", in a group of conditions', async () => {
      const dialog = page.getByRole('dialog', { name: 'When this question applies' });
      await dialog.getByRole('button', { name: 'Add a group' }).click();
      const group = dialog.getByRole('group', { name: 'Group of conditions' });
      await group.getByRole('combobox', { name: 'Question' }).click();
      await page.getByRole('option', { name: new RegExp(WHAT.replace('?', '\\?')) }).click();
      await group.getByLabel(/Value/).fill('Laptop');
      expect(await pastTheEdge(dialog)).toEqual([]);
      await dialog.getByRole('button', { name: 'Save' }).click();
      await expect(dialog).toBeHidden();
    });

    await test.step('the question says when it is asked, and he signs out', async () => {
      await expect(version.part(WHY).getByText(/Only when/)).toBeVisible();
      await fitsTheScreen(page);
      await shell.signOut();
    });
  });
});
