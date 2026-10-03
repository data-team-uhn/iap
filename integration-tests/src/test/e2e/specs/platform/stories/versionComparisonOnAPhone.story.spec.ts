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
import { fitsTheScreen, PHONE } from '../../../support/phone';

/**
 * THE STORY: a new version of a schema, checked from a phone against the one it was copied from.
 *
 * Priya looks after what her team's leave requests ask for, and all she has with her today is her phone. Her
 * schema's first version asks how many days and why. She makes a second version from it, rewords why, drops how
 * many days and asks when the leave starts instead. Before she goes further she wants to see everything she
 * changed: the version it was copied from is offered first to compare it with, and the comparison shows the
 * question she dropped, the one she added, and the words she changed within the other. Nothing runs past the edge
 * of her screen.
 *
 * Each step has its spec. Only this asks whether a version's copy and its comparison fit together at a phone's
 * width: the copy remembering where it came from, and the comparison reachable from the copy's own actions.
 */
test.describe('stories: a new version checked from a phone', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.use({ viewport: PHONE, isMobile: true, hasTouch: true });
  test.skip(({ browserName }) => browserName === 'firefox', 'Firefox cannot emulate a phone');

  const SCHEMA = 'Leave request';

  const DAYS = 'How many days?';

  const WHY = 'Why are you away?';

  const WHY_REWORDED = 'Why are you away from work?';

  const WHEN = 'When does it start?';

  test('Priya sees what her new version changed, compared with the one it was copied from', async ({ page }) => {
    const shell = new AppShell(page);
    const version = new SchemaVersionPage(page);

    await test.step('she signs in on her phone, and starts a schema', async () => {
      await signInAs(page, ADMIN);
      await page.getByRole('button', { name: 'Administration' }).click();
      await page.getByRole('link', { name: /Schemas/ }).first().click();
      await page.getByRole('button', { name: 'New schema' }).click();
      const dialog = page.getByRole('dialog', { name: 'New schema' });
      await dialog.getByLabel('Title').fill(SCHEMA);
      await dialog.getByRole('button', { name: 'Create' }).click();
      await page.getByText('Version 1.0').click();
      await expect(page.getByRole('heading', { name: /Version 1\.0/ })).toBeVisible();
    });

    await test.step('its first version asks how many days and why', async () => {
      await version.add(page.locator('main'), 'Form', /Label/, 'Leave');
      await page.getByRole('button', { name: 'Expand Leave' }).click();
      await version.add(version.part('Leave'), 'Question', /Question/, DAYS);
      await version.add(version.part('Leave'), 'Question', /Question/, WHY);
      await expect(page.getByText(WHY)).toBeVisible();
      await fitsTheScreen(page);
    });

    await test.step('she makes a second version from it', async () => {
      await page.getByRole('button', { name: 'Actions for version 1.0' }).click();
      await page.getByRole('menuitem', { name: 'New version from this one' }).click();
      await page.getByRole('dialog', { name: `New version of ${SCHEMA}` }).getByRole('button', { name: 'Create' })
        .click();
      await expect(page.getByRole('heading', { name: /Version 2\.0/ })).toBeVisible();
    });

    await test.step('she rewords why, drops how many days, and asks when the leave starts', async () => {
      await version.act(WHY, 'Edit');
      const edit = page.getByRole('dialog', { name: 'Edit question' });
      await edit.getByLabel(/Question/).fill(WHY_REWORDED);
      await edit.getByRole('button', { name: 'Save' }).click();
      await expect(edit).toBeHidden();
      await version.act(DAYS, 'Remove');
      await page.getByRole('dialog', { name: 'Remove this question' }).getByRole('button', { name: 'Remove' })
        .click();
      await expect(page.getByText(DAYS)).toBeHidden();
      await version.add(version.part('Leave'), 'Question', /Question/, WHEN);
      await expect(page.getByText(WHEN)).toBeVisible();
      await fitsTheScreen(page);
    });

    await test.step('she is offered the version it was copied from first, to compare it with', async () => {
      await page.getByRole('button', { name: 'Actions for version 2.0' }).click();
      await page.getByRole('menuitem', { name: 'Compare with…' }).click();
      const first = page.getByRole('menu').getByRole('menuitem').first();
      await expect(first).toHaveText(/Version 1\.0.*What it was copied from/);
      await first.click();
      await expect(page.getByRole('heading', { name: /Version 2\.0 compared with 1\.0/ })).toBeVisible();
    });

    await test.step('the comparison shows what she dropped, added and reworded, and she signs out', async () => {
      await expect(page.getByText('1 part added, 1 removed and 1 changed.')).toBeVisible();
      await expect(version.part(DAYS)).toContainText('Removed');
      await expect(version.part(WHEN)).toContainText('Added');
      await expect(version.part(WHY_REWORDED).locator('ins', { hasText: 'from work' })).toBeVisible();
      await fitsTheScreen(page);
      await shell.signOut();
    });
  });
});
