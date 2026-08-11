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

import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

import { SchemaVersionPage } from '../../../pages/schemaVersion.page';
import { ADMIN, adminAuth, signInAs } from '../../../support/auth';

/**
 * THE STORY: a schema, from its first draft to a second version, with a submission made against it.
 *
 * Miriam administers this deployment. She sets up what an equipment loan asks for: a form with a section, a question
 * whose answer is picked from options, a number, and a question asked only when a laptop is wanted. Her first attempt
 * to activate it is refused, because she typed a number's bounds the wrong way round. Once it is active, she can
 * still correct a typo, and nothing else. She makes a submission against it, and sees the conditional question
 * appear. She then makes a second version, compares it with the first, and activates it, which retires the first:
 * one version at a time is active, and the submission stays with the version it was made against. Last, she retires
 * the whole schema, and reopens it.
 *
 * Every step is covered by a spec somewhere, and none of that coverage says the path exists on a live instance: that
 * the validity checks refuse an activation through the dialog, that a published version offers only corrections,
 * that a condition set in the editor decides what a submission asks, that the comparison reads two real versions,
 * that activating a version retires the one it replaces, and that retiring and reopening a schema restore each
 * version's state.
 */
test.describe('stories: a schema from its first draft to a second version', () => {
  // One schema, carried through every episode in turn
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  const SCHEMA = 'Equipment loan';

  const FORM = 'Loan request';

  const SECTION = 'The equipment';

  const WHAT = 'What do you need?';

  const HOW_MANY = 'How many do you need?';

  // Typed with a typo, corrected once the version is active
  const WHY_TYPO = 'Why do you need a laptp?';

  const WHY = 'Why do you need a laptop?';

  const WHY_REWORDED = 'Why do you need a laptop, and for how long?';

  const WHEN_BACK = 'When will you bring it back?';

  const OPTIONS = [ 'Laptop', 'Monitor', 'Headset' ];

  const TITLE = 'Laptop for the field trip';

  // The repository paths, read off the pages as the story goes
  let schema = '';

  let submission = '';

  test.afterAll(async ({ playwright, baseURL }) => {
    // Leave the instance as it was found, whichever episode failed. The submission first: it refers to the schema's
    // version, and a schema something still refers to cannot be deleted. Permanently, so nothing waits in the archive.
    const request = await playwright.request.newContext({ baseURL });
    for (const path of [ submission, schema ]) {
      if (path !== '') {
        await request.delete(`${path}?permanent=true&recursive=true`, { headers: adminAuth });
      }
    }
    await request.dispose();
  });

  test('Miriam creates a schema, and its first version is a draft', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    await openSchemas(page);

    await page.getByRole('button', { name: 'New schema' }).click();
    const dialog = page.getByRole('dialog', { name: 'New schema' });
    await dialog.getByRole('textbox', { name: 'Title', exact: true }).fill(SCHEMA);
    await dialog.getByRole('button', { name: 'Create' }).click();

    // The schema's page opens, under a name the server derived from the title
    await expect(page).toHaveURL(/\/admin\/schemas\/[^/]+$/);
    schema = `/Schemas/${new URL(page.url()).pathname.split('/').pop() ?? ''}`;
    await expect(page.getByRole('heading', { name: SCHEMA })).toBeVisible();

    await page.getByRole('link', { name: '1.0', exact: true }).click();
    await expect(page.getByRole('heading', { name: /Version 1\.0/ })).toBeVisible();
    await expect(page.getByText('Everything in this version can change until it is activated.')).toBeVisible();
    expect(await tagsOf(request, `${schema}/v1`)).toEqual([ 'draft' ]);
  });

  test('she builds it: a form, a section, and three questions', async ({ page }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));

    await version.add(page.locator('main'), 'Form', /Label/, FORM);
    await expand(page, FORM);
    await version.add(version.part(FORM), 'Section', /Title/, SECTION);
    await expand(page, SECTION);

    const section = version.part(SECTION);
    await version.addWith(section, 'Question', async dialog => {
      await dialog.getByRole('textbox', { name: 'Question', exact: true }).fill(WHAT);
    });
    await version.addWith(section, 'Question', async dialog => {
      await dialog.getByRole('textbox', { name: 'Question', exact: true }).fill(HOW_MANY);
      await dialog.getByRole('combobox', { name: 'Answer type' }).click();
      await page.getByRole('option', { name: 'Whole number', exact: true }).click();
      // The wrong way round, which nothing refuses until the version is checked
      await dialog.getByRole('textbox', { name: 'Lowest value' }).fill('10');
      await dialog.getByRole('textbox', { name: 'Highest value' }).fill('5');
    });
    await version.addWith(section, 'Question', async dialog => {
      await dialog.getByRole('textbox', { name: 'Question', exact: true }).fill(WHY_TYPO);
    });

    await expect(version.part(HOW_MANY).getByText('Whole number')).toBeVisible();
  });

  test('she gives the first question its options, and moves one up', async ({ page }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));
    await expand(page, FORM, SECTION, WHAT);

    for (const option of OPTIONS) {
      await version.addWith(version.part(WHAT), 'Option', async dialog => {
        await dialog.getByRole('textbox', { name: 'Value', exact: true }).fill(option);
      });
    }
    await expect(version.part(WHAT).getByText('3 options')).toBeVisible();

    // Each step is saved as it is taken
    await version.action('Headset', 'Move up').click();
    await expect.poll(() => optionsOf(version, WHAT)).toEqual([ 'Laptop', 'Headset', 'Monitor' ]);
  });

  test('she asks the last question only when a laptop is wanted', async ({ page }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));
    await expand(page, FORM, SECTION);

    await version.action(WHY_TYPO, 'When it applies').click();
    const dialog = page.getByRole('dialog', { name: 'When this question applies' });
    await dialog.getByRole('button', { name: 'Add a condition' }).click();
    const condition = dialog.getByRole('group', { name: 'Condition' });
    await condition.getByRole('combobox', { name: 'Question' }).click();
    await page.getByRole('option', { name: new RegExp(escaped(WHAT)) }).click();
    // A question with options offers them, and opens the list as soon as it is picked
    await page.getByRole('option', { name: 'Laptop', exact: true }).click();
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(dialog).toBeHidden();

    await expect(version.part(WHY_TYPO).getByText(`Only when the answer to “${WHAT}” is “Laptop”`)).toBeVisible();
  });

  test('activating it is refused for the bounds she typed the wrong way round, until she fixes them', async ({
    page, request,
  }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));

    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: 'Activate version 1.0' });
    await dialog.getByRole('button', { name: 'Activate', exact: true }).click();
    // The check names the question and what is wrong with it, and the version stays a draft
    await expect(dialog.getByRole('alert'))
      .toContainText(`"${HOW_MANY}" has a smallest accepted value above its largest`);
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    expect(await tagsOf(request, `${schema}/v1`)).toEqual([ 'draft' ]);

    await expand(page, FORM, SECTION);
    await version.action(HOW_MANY, 'Edit').click();
    const edit = page.getByRole('dialog', { name: 'Edit question' });
    await edit.getByRole('textbox', { name: 'Lowest value' }).fill('1');
    await edit.getByRole('button', { name: 'Save' }).click();
    await expect(edit).toBeHidden();

    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    dialog = page.getByRole('dialog', { name: 'Activate version 1.0' });
    await expect(dialog).toContainText('Nothing is retired: this schema has no active version at the moment.');
    await dialog.getByRole('button', { name: 'Activate', exact: true }).click();
    await expect(page.getByText('Version 1.0 is active')).toBeVisible();
    expect(await tagsOf(request, `${schema}/v1`)).toEqual([ 'active' ]);
    expect(await activeVersions(request, schema)).toEqual([ '1.0' ]);
  });

  test('once active, she can correct the typo, and change nothing else', async ({ page }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));
    await expand(page, FORM, SECTION);

    await expect(page.getByText('Only the wording of this version can be corrected.', { exact: false })).toBeVisible();
    // Nothing can be added, moved, removed or made conditional any more
    await expect(page.getByRole('main').getByRole('button', { name: /^Add/ })).toHaveCount(0);
    await expect(version.action(WHY_TYPO, 'Move')).toHaveCount(0);
    await expect(version.action(WHY_TYPO, 'Remove')).toHaveCount(0);
    await expect(version.action(WHY_TYPO, 'When it applies')).toHaveCount(0);

    await version.action(WHY_TYPO, 'Edit').click();
    const edit = page.getByRole('dialog', { name: 'Edit question' });
    await edit.getByRole('textbox', { name: 'Question', exact: true }).fill(WHY);
    await edit.getByRole('button', { name: 'Save' }).click();
    await expect(edit).toBeHidden();

    await expect(version.part(WHY)).toBeVisible();
    await expect(page.getByText(WHY_TYPO)).toHaveCount(0);
  });

  test('she makes a submission against it, and the laptop question appears once she wants a laptop', async ({
    page,
  }) => {
    await signInAs(page, ADMIN);
    await page.goto('/');

    await page.getByRole('button', { name: 'New submission' }).click();
    const dialog = page.getByRole('dialog', { name: 'New submission' });
    await dialog.getByRole('radio', { name: `${SCHEMA} 1.0` }).check();
    await dialog.getByRole('textbox', { name: 'Title', exact: true }).fill(TITLE);
    await dialog.getByRole('button', { name: 'Create' }).click();

    // Named by a UUID, and filed under its first three byte pairs
    await expect(page).toHaveURL(/\/Submissions\/([0-9a-f]{2})\/([0-9a-f]{2})\/([0-9a-f]{2})\/\1\2\3[0-9a-f-]+$/);
    submission = new URL(page.url()).pathname;
    await page.getByRole('button', { name: 'Edit', exact: true }).click();

    // Asked only when its condition holds, which the server decides as each answer is saved. The editor does not
    // offer a question's options yet, so the answer is typed.
    const what = page.getByLabel(WHAT, { exact: true });
    await expect(what).toBeVisible();
    await expect(page.getByLabel(WHY, { exact: true })).toHaveCount(0);

    await what.fill('Laptop');
    await what.press('Tab');
    const why = page.getByLabel(WHY, { exact: true });
    await expect(why).toBeVisible();

    await page.getByLabel(HOW_MANY, { exact: true }).fill('1');
    await page.getByLabel(HOW_MANY, { exact: true }).press('Tab');
    await why.fill('Recording interviews on the field trip');
    await why.press('Tab');
    await expect(page.getByText('Saved', { exact: true })).toHaveCount(3);
  });

  test('she makes a second version, changes it, and compares it with the first', async ({ page, request }) => {
    const version = new SchemaVersionPage(page);
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v1'));

    await page.getByRole('button', { name: 'New version from this one' }).click();
    const dialog = page.getByRole('dialog', { name: `New version of ${SCHEMA}` });
    await expect(dialog.getByRole('textbox', { name: 'Label', exact: true })).toHaveValue('2.0');
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(page).toHaveURL(/\/v2$/);
    expect(await tagsOf(request, `${schema}/v2`)).toEqual([ 'draft' ]);

    await expand(page, FORM, SECTION);
    // She drops the number, rewords the laptop question, and asks when it comes back
    await version.action(HOW_MANY, 'Remove').click();
    const remove = page.getByRole('dialog', { name: 'Remove this question' });
    await remove.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(remove).toBeHidden();

    await version.action(WHY, 'Edit').click();
    const edit = page.getByRole('dialog', { name: 'Edit question' });
    await edit.getByRole('textbox', { name: 'Question', exact: true }).fill(WHY_REWORDED);
    await edit.getByRole('button', { name: 'Save' }).click();
    await expect(edit).toBeHidden();

    await version.addWith(version.part(SECTION), 'Question', async add => {
      await add.getByRole('textbox', { name: 'Question', exact: true }).fill(WHEN_BACK);
      await add.getByRole('combobox', { name: 'Answer type' }).click();
      await page.getByRole('option', { name: 'Date', exact: true }).click();
    });

    // The active version is offered first, and the comparison counts what she changed
    await page.getByRole('button', { name: 'Compare with…' }).click();
    const first = page.getByRole('menuitem', { name: /Version 1\.0/ });
    await expect(first).toContainText('The active version');
    await first.click();

    await expect(page.getByRole('heading', { name: /Version 2\.0 compared with 1\.0/ })).toBeVisible();
    await expect(page.getByText('1 part added, 1 removed and 1 changed.')).toBeVisible();
  });

  test('activating the second version retires the first, and the submission keeps its version', async ({
    page, request,
  }) => {
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema, 'v2'));

    // The confirmation names the version this one replaces
    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    const activate = page.getByRole('dialog', { name: 'Activate version 2.0' });
    await expect(activate).toContainText('Version 1.0 is retired in the same step');
    await activate.getByRole('button', { name: 'Activate', exact: true }).click();
    await expect(page.getByText('Version 2.0 is active')).toBeVisible();

    expect(await tagsOf(request, `${schema}/v1`)).toEqual([ 'retired' ]);
    expect(await tagsOf(request, `${schema}/v2`)).toEqual([ 'active' ]);
    expect(await activeVersions(request, schema)).toEqual([ '2.0' ]);

    expect(await madeAgainst(request, submission, `${schema}/v1`)).toBe(true);
    const offered = await offeredForSubmission(page);
    expect(offered).toContain(`${SCHEMA} 2.0`);
    expect(offered).not.toContain(`${SCHEMA} 1.0`);
  });

  test('she retires the schema, and reopening it puts each version back as it was', async ({ page, request }) => {
    await signInAs(page, ADMIN);
    await page.goto(adminPageOf(schema));

    await headerAction(page, 'Retire').click();
    const retire = page.getByRole('dialog', { name: `Retire ${SCHEMA}` });
    await retire.getByRole('button', { name: 'Retire', exact: true }).click();
    await expect(page.getByText(`${SCHEMA} is retired`)).toBeVisible();
    await expect(page.getByText('This schema is retired. None of its versions accepts new submissions.'))
      .toBeVisible();
    expect(await offeredForSubmission(page)).not.toContain(`${SCHEMA} 2.0`);

    await page.goto(adminPageOf(schema));
    await headerAction(page, 'Reopen').click();
    const reopen = page.getByRole('dialog', { name: `Reopen ${SCHEMA}` });
    await reopen.getByRole('button', { name: 'Reopen', exact: true }).click();
    await expect(page.getByText(`${SCHEMA} is open again`)).toBeVisible();

    expect(await tagsOf(request, `${schema}/v1`)).toEqual([ 'retired' ]);
    expect(await tagsOf(request, `${schema}/v2`)).toEqual([ 'active' ]);
    expect(await activeVersions(request, schema)).toEqual([ '2.0' ]);
    expect(await offeredForSubmission(page)).toContain(`${SCHEMA} 2.0`);
  });
});

/** The schemas, reached the way an administrator reaches them. */
async function openSchemas(page: Page): Promise<void> {
  await page.goto('/admin');
  await page.getByRole('link', { name: 'Manage: Schemas' }).click();
  await expect(page.getByRole('heading', { name: 'Schemas', level: 1 })).toBeVisible();
}

/** The page of a schema, or of one of its versions, from the schema's repository path. */
function adminPageOf(schema: string, version?: string): string {
  const name = schema.split('/').pop() ?? '';
  return version ? `/admin/schemas/${name}/${version}` : `/admin/schemas/${name}`;
}

/** Expands each of the parts named, in turn, unless it already is. */
async function expand(page: Page, ...parts: string[]): Promise<void> {
  for (const part of parts) {
    const button = page.getByRole('button', { name: `Expand ${part}`, exact: true });
    if (await button.count() > 0) {
      await button.click();
    }
    await expect(page.getByRole('button', { name: `Collapse ${part}`, exact: true })).toBeVisible();
  }
}

/** The options of a question, in the order the outline shows them. */
async function optionsOf(version: SchemaVersionPage, question: string): Promise<string[]> {
  const items = await version.part(question).getByRole('listitem').allInnerTexts();
  return items.map(text => text.split('\n')[0].trim());
}

/** An action of the page's own header, not one of a version row's. */
function headerAction(page: Page, name: string) {
  return page.getByRole('button', { name, exact: true }).and(page.locator(':not([role="row"] *)'));
}

/** What the New submission dialog offers, then closes it. */
async function offeredForSubmission(page: Page): Promise<string[]> {
  await page.goto('/');
  await page.getByRole('button', { name: 'New submission' }).click();
  const dialog = page.getByRole('dialog', { name: 'New submission' });
  await expect(dialog).toBeVisible();
  const offered = (await dialog.getByRole('radio').evaluateAll(radios => radios
    .map(radio => radio.closest('label')?.textContent ?? ''))).map(text => text.trim());
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  return offered;
}

/** The tags a node carries itself, as the repository serves them. */
async function tagsOf(request: APIRequestContext, path: string): Promise<string[]> {
  const response = await request.get(`${path}.json`, { headers: adminAuth });
  expect(response.ok(), `${path} could not be read`).toBeTruthy();
  return ((await response.json()) as { tags?: string[] }).tags ?? [];
}

/** The labels of a schema's active versions, read from every version, drafts and retired ones included. */
async function activeVersions(request: APIRequestContext, schema: string): Promise<string[]> {
  const response = await request.get(`${schema}.1.simple.-active.json`, { headers: adminAuth });
  expect(response.ok(), `${schema} could not be read`).toBeTruthy();
  return Object.values((await response.json()) as Record<string, unknown>)
    .filter((child): child is { version: string; tags?: string[] } => typeof child === 'object' && child !== null
      && (child as Record<string, unknown>)['jcr:primaryType'] === 'sch:SchemaVersion')
    .filter(version => version.tags?.includes('active'))
    .map(version => version.version);
}

/** Whether a submission was made against the given schema version. */
async function madeAgainst(request: APIRequestContext, submission: string, version: string): Promise<boolean> {
  const read = async (path: string): Promise<Record<string, unknown>> => {
    const response = await request.get(`${path}.json`, { headers: adminAuth });
    expect(response.ok(), `${path} could not be read`).toBeTruthy();
    return (await response.json()) as Record<string, unknown>;
  };
  const reference = (await read(submission)).schemaVersion;
  // Served as the node it points at, or as its identifier when nothing dereferences it
  if (typeof reference === 'object' && reference !== null) {
    return (reference as { '@path'?: string })['@path'] === version;
  }
  return reference === (await read(version))['jcr:uuid'];
}

/** A text as a regular expression matching it literally. */
function escaped(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
