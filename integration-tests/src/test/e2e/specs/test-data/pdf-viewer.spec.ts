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

import { expect, test, type Locator, type Page } from '@playwright/test';

import { getCharBoxes, PdfViewerWidget, type Box } from '../../pages/pdfViewer.page';
import { writePdf } from '../../support/pdf';
import { PHONE, pastTheEdge } from '../../support/phone';

const URL = '/e2e/pdf-viewer/report.pdf';

/** A passage found only on page 3. */
const GRANT = 'The grant covers travel to the conference in Lisbon.';

/** A passage found on pages 2 and 4. */
const RESULTS = 'Results are reported to the committee each quarter.';

/** Eight pages, each naming itself. "committee" is on pages 1, 2 and 4. */
const PDF = writePdf([
  [ 'The committee met in March to review the budget.', 'Page 1 sets out the questions.' ],
  [ RESULTS, 'Page 2 describes how the data was gathered.' ],
  [ GRANT, 'Page 3 lists what the grant pays for.' ],
  [ RESULTS, 'Page 4 closes the findings.' ],
  ...[ 5, 6, 7, 8 ].map(page => [ `Page ${page} adds nothing new.` ]),
]);

/** Where the elements a locator finds are on the screen, as one box around all of them. */
async function getBox(elements: Locator): Promise<Box> {
  const boxes = await elements.evaluateAll(found => found.map(element => element.getBoundingClientRect()));
  expect(boxes).not.toHaveLength(0);
  return {
    left: Math.min(...boxes.map(box => box.left)),
    right: Math.max(...boxes.map(box => box.right)),
    top: Math.min(...boxes.map(box => box.top)),
    bottom: Math.max(...boxes.map(box => box.bottom)),
  };
}

/** How wide the elements a locator finds are, together. */
async function getWidth(elements: Locator): Promise<number> {
  const { left, right } = await getBox(elements);
  return right - left;
}

/** Where the text layer puts some words of a page's text. */
async function getTextBox(viewer: Locator, page: number, words: string): Promise<Box> {
  const span = viewer.locator(`[data-page-number="${page}"] .textLayer span[role="presentation"]`, { hasText: words }).first();
  const from = (await span.textContent() ?? '').indexOf(words);
  const [ box ] = await getCharBoxes(span, [ { from, to: from + words.length } ]);
  return box;
}

/** Checks that a mark covers the words it marks, to within a pixel at either end. */
async function expectOnWords(mark: Locator, viewer: Locator, page: number, words: string): Promise<void> {
  const [ drawn, text ] = await Promise.all([ getBox(mark), getTextBox(viewer, page, words) ]);
  expect(Math.abs(drawn.left - text.left)).toBeLessThan(1);
  expect(Math.abs(drawn.right - text.right)).toBeLessThan(1);
  expect(drawn.top).toBeLessThan(text.bottom);
  expect(drawn.bottom).toBeGreaterThan(text.top);
}

/**
 * Checks that the document's frame shows an element, at least in part. It says where the viewer took the
 * reader whether or not the page around the viewer has it on the screen.
 */
async function expectShownIn(viewer: Locator, element: Locator): Promise<void> {
  const frame = viewer.getByRole('region', { name: 'Document' });
  await expect.poll(async () => {
    const [ shown, inside ] = await Promise.all([ getBox(frame), getBox(element) ]);
    return inside.bottom > shown.top && inside.top < shown.bottom;
  }).toBe(true);
}

/** Selects words on a page with the mouse, pressing at their start and letting go at their end. */
async function selectWords(page: Page, viewer: Locator, onPage: number, words: string): Promise<void> {
  const box = await getTextBox(viewer, onPage, words);
  const middle = (box.top + box.bottom) / 2;
  await page.mouse.move(box.left + 1, middle);
  await page.mouse.down();
  await page.mouse.move(box.right - 1, middle, { steps: 10 });
  await page.mouse.up();
}

/**
 * The viewer in a real browser, which lays the pages out, draws the text layer and lets text be selected.
 * The unit tests' stand-in for PDF.js can show none of these.
 */
test.describe('the PDF viewer', () => {
  test.beforeEach(async ({ page }) => {
    await new PdfViewerWidget(page).serve(URL, PDF);
  });

  test('takes the reader to a passage on a later page, and marks its words', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, quotes: [ GRANT ] });

    await expect(viewer.getByRole('status')).toHaveText('The passage is marked on page 3.');
    const mark = viewer.locator('[data-page-number="3"] .iap-mark-assistant');
    await expect(mark.first()).toBeInViewport();
    await expectOnWords(mark, viewer, 3, GRANT);
  });

  test('keeps a mark on its words after a zoom', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, quotes: [ GRANT ] });
    await expect(viewer.getByRole('status')).toHaveText('The passage is marked on page 3.');
    const mark = viewer.locator('[data-page-number="3"] .iap-mark-assistant');
    const before = await getWidth(mark);

    await viewer.getByRole('button', { name: 'Zoom in' }).click();

    // Drawn again, wider, once the zoomed page's text is there
    await expect.poll(() => getWidth(mark)).toBeGreaterThan(before + 10);
    await expectOnWords(mark, viewer, 3, GRANT);
  });

  test('looks for a passage from the page it is said to be on', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, quotes: [ RESULTS ], page: 4 });

    await expect(viewer.getByRole('status')).toHaveText('The passage is marked on page 4.');
    await expect(viewer.locator('[data-page-number="4"] .iap-mark-assistant').first()).toBeInViewport();
  });

  test('leaves the reader where they scrolled by hand, once at the passage', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, quotes: [ GRANT ] });
    const mark = viewer.locator('[data-page-number="3"] .iap-mark-assistant').first();
    await expect(mark).toBeInViewport();

    await viewer.getByRole('region', { name: 'Document' }).hover();
    await page.mouse.wheel(0, 6000);

    // Each page drawn on the way is a chance to pull the reader back to the passage. A page this far on is
    // drawn only once the reader reaches it.
    await expect(viewer.locator('[data-page-number="7"] .textLayer span[role="presentation"]', { hasText: 'Page 7' })).toBeInViewport();
    await expect(mark).not.toBeInViewport();
  });

  test('comments on words selected with the mouse', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL });
    await expect(viewer.getByText('Page 1 of 8', { exact: true })).toBeVisible();

    await selectWords(page, viewer, 1, 'review the budget');
    await page.getByRole('button', { name: 'Comment', exact: true }).click();
    await page.getByRole('textbox', { name: 'Comment' }).fill('Check the figures with finance.');
    await page.getByRole('button', { name: 'Save' }).click();

    await expect(viewer.getByText('Check the figures with finance.')).toBeVisible();
    await expectOnWords(viewer.locator('.iap-mark-annotation'), viewer, 1, 'review the budget');
  });

  test('searches the document, and steps through the matches', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL });
    const field = viewer.getByRole('textbox', { name: 'Search the document' });

    await field.fill('committee');

    await expect(viewer.getByText('1 of 3', { exact: true })).toBeVisible();
    await expect(viewer.locator('.textLayer .highlight.selected')).toBeInViewport();
    await field.press('Enter');
    await expect(viewer.getByText('2 of 3', { exact: true })).toBeVisible();
    await field.press('Shift+Enter');
    await expect(viewer.getByText('1 of 3', { exact: true })).toBeVisible();
  });

  test('pops out into a dialog and back, keeping the page', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, inPlace: true });
    await expect(viewer.getByText('Page 1 of 8', { exact: true })).toBeVisible();
    await viewer.getByRole('button', { name: 'Next page' }).click();
    await viewer.getByRole('button', { name: 'Next page' }).click();
    await expect(viewer.getByText('Page 3 of 8', { exact: true })).toBeVisible();

    await viewer.getByRole('button', { name: 'Pop out' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Page 3 of 8', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Pop back in' }).click();
    await expect(dialog).toBeHidden();
    await expect(viewer.getByText('Page 3 of 8', { exact: true })).toBeVisible();
  });
});

test.describe('the PDF viewer on a phone', () => {
  test.use({ viewport: PHONE });

  test.beforeEach(async ({ page }) => {
    await new PdfViewerWidget(page).serve(URL, PDF);
  });

  test('fits the screen, shows one passage at a time and keeps the search behind a button', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL, quotes: [ GRANT, RESULTS ], inPlace: true });

    await expect(viewer.getByRole('status')).toHaveText('Passage 1 of 2 is marked on page 3.');
    expect(await pastTheEdge(viewer)).toEqual([]);
    await expect(viewer.getByRole('button', { name: 'Search the document' })).toBeVisible();
    await expect(viewer.getByRole('textbox', { name: 'Search the document' })).toBeHidden();

    await viewer.getByRole('button', { name: 'Next passage' }).click();

    await expect(viewer.getByRole('status')).toHaveText('Passage 2 of 2 is marked on page 2.');
    // Below the form, the frame may reach past the bottom of a phone's screen
    await expectShownIn(viewer, viewer.locator('[data-page-number="2"] .iap-mark-assistant.selected'));
  });
});
