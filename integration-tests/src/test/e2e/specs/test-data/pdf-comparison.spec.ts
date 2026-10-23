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

import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';

import { ADMIN, signInAs } from '../../support/auth';

/**
 * Two versions of a PDF compared side by side, in a real browser, since only a browser lays the pages out.
 * The PDFs are printed by Chromium and served by the test itself, so nothing is stored on the instance.
 */

const OLDER = '/e2e/pdf-comparison/older.pdf';
const NEWER = '/e2e/pdf-comparison/newer.pdf';

/** A paragraph of one line, which says its number, the same in both versions. */
const paragraph = (number: number): string => `Paragraph ${number} reads the same in both versions.`;

/** The older version: 150 numbered paragraphs, over several pages. */
const OLDER_TEXT = Array.from({ length: 150 }, (_, index) => paragraph(index + 1));

/**
 * The newer version: 30 paragraphs added after the 10th, and the 60th to the 80th removed. The added
 * paragraphs use words the older version does not have, so they are one change.
 */
const NEWER_TEXT = [
  ...OLDER_TEXT.slice(0, 10),
  ...Array.from({ length: 30 }, () => 'Inserted words appear here alone.'),
  ...OLDER_TEXT.slice(10, 59),
  ...OLDER_TEXT.slice(80),
];

/** A PDF of the paragraphs, printed by Chromium. */
async function printPdf(browser: Browser, paragraphs: string[]): Promise<Buffer> {
  const printer = await browser.newPage();
  await printer.setContent(`<body style="font: 14px/1.6 serif">${paragraphs.map(text => `<p>${text}</p>`).join('')}</body>`);
  const pdf = await printer.pdf({ format: 'A4' });
  await printer.close();
  return pdf;
}

/** The number of the paragraph in the middle of a version's pane, from its text layer, or 0 for none. */
async function getMiddleParagraph(pane: Locator): Promise<number> {
  return pane.evaluate(frame => {
    const middle = frame.getBoundingClientRect().top + frame.clientHeight / 2;
    const line = Array.from(frame.querySelectorAll('.textLayer span'))
      .find(span => /Paragraph \d+/.test(span.textContent) && span.getBoundingClientRect().bottom >= middle);
    return Number(/Paragraph (\d+)/.exec(line?.textContent ?? '')?.[1] ?? 0);
  });
}

async function openComparison(page: Page): Promise<{ older: Locator; newer: Locator }> {
  await signInAs(page, ADMIN);
  await page.goto('/');
  // The innermost element holding the widget's form, as the PDF viewer's widget has a checkbox of the same name
  const widget = page.locator('div').filter({ has: page.getByLabel('Older version URL') })
    .filter({ has: page.getByRole('button', { name: 'Compare' }) }).last();
  await widget.getByLabel('Older version URL').fill(OLDER);
  await widget.getByLabel('Newer version URL').fill(NEWER);
  await widget.getByRole('checkbox', { name: 'Show in place, below the form' }).uncheck();
  await widget.getByRole('button', { name: 'Compare' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('status')).toHaveText(/changes found/);
  const panes = dialog.getByRole('region', { name: 'Document' });
  await expect(panes).toHaveCount(2);
  return { older: panes.first(), newer: panes.last() };
}

test.describe('comparing two versions of a PDF', () => {
  test.skip(({ browserName }) => browserName !== 'chromium', 'Only Chromium prints a page to PDF');

  test.beforeEach(async ({ page, browser }) => {
    const [ older, newer ] = await Promise.all([ printPdf(browser, OLDER_TEXT), printPdf(browser, NEWER_TEXT) ]);
    await page.route(`**${OLDER}`, route => route.fulfill({ contentType: 'application/pdf', body: older }));
    await page.route(`**${NEWER}`, route => route.fulfill({ contentType: 'application/pdf', body: newer }));
  });

  test('scrolls the newer version with the older, matched by their text', async ({ page }) => {
    const { older, newer } = await openComparison(page);

    await older.hover();
    await page.mouse.wheel(0, 3000);

    await expect.poll(() => getMiddleParagraph(older)).toBeGreaterThan(20);
    await expect.poll(async () => Math.abs(await getMiddleParagraph(older) - await getMiddleParagraph(newer)))
      .toBeLessThanOrEqual(1);
  });

  test('scrolls the older version with the newer, past text only one of them has', async ({ page }) => {
    const { older, newer } = await openComparison(page);

    await newer.hover();
    await page.mouse.wheel(0, 3000);

    await expect.poll(() => getMiddleParagraph(newer)).toBeGreaterThan(80);
    await expect.poll(async () => Math.abs(await getMiddleParagraph(older) - await getMiddleParagraph(newer)))
      .toBeLessThanOrEqual(1);
  });

  test('takes both versions to a change stepped to', async ({ page }) => {
    const { older, newer } = await openComparison(page);
    const status = page.getByRole('dialog').getByRole('status');

    await page.getByRole('button', { name: 'Next change' }).click();
    await expect(status).toHaveText(/^Change 1 of \d+\. Added on page/);
    await page.getByRole('button', { name: 'Next change' }).click();

    await expect(status).toHaveText(/^Change 2 of \d+\. Removed on page/);
    await expect(older.locator('.iap-mark-removed.selected').first()).toBeInViewport();
    // The removed paragraphs are the 60th to the 80th: the older version shows them, the newer what is around.
    await expect.poll(() => getMiddleParagraph(older)).toBeGreaterThanOrEqual(58);
    await expect.poll(() => getMiddleParagraph(newer)).toBeGreaterThanOrEqual(55);
    expect(await getMiddleParagraph(newer)).toBeLessThanOrEqual(85);
  });
});
