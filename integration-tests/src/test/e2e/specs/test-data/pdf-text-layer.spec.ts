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

import { expect, test, type Locator } from '@playwright/test';

import { getCharBoxes, PdfViewerWidget } from '../../pages/pdfViewer.page';
import { writePdf } from '../../support/pdf';

const URL = '/e2e/pdf-text-layer/narrow-and-wide.pdf';

/** How many narrow letters come before the `x`, and how many wide ones after it. */
const RUN = 60;

/** Helvetica's advance widths, in thousandths of the font size, which PDF.js draws the font at. */
const ADVANCE = { i: 222, x: 500, m: 833 };

/**
 * A tagged page with a line of narrow letters, then the `x`, then wide letters, between two others. Tagged,
 * PDF.js ends lines with empty pieces of text it draws nothing for, as on most real documents.
 */
const PDF = writePdf(
  [ [ 'Before the line.', `${'i'.repeat(RUN)}x${'m'.repeat(RUN)}`, 'After the line.' ] ],
  { width: 842, height: 240, tagged: true },
);

/** Where the line's ends are, and where its `x` starts. */
interface Line {
  start: number;
  end: number;
  x: number;
}

/** Measures the line of narrow and wide letters: its first and last letters, and its `x`. */
async function measureLine(line: Locator): Promise<Line> {
  const text = await line.textContent() ?? '';
  const at = text.indexOf('x');
  const [ first, last, x ] = await getCharBoxes(line, [
    { from: 0, to: 1 },
    { from: text.length - 1, to: text.length },
    { from: at, to: at + 1 },
  ]);
  return { start: first.left, end: last.right, x: x.left };
}

/** Checks that the `x` is as far along the line as its glyphs put it, to within a pixel. */
function expectOnGlyph({ start, end, x }: Line): void {
  const total = RUN * ADVANCE.i + ADVANCE.x + RUN * ADVANCE.m;
  const drawnAt = start + (end - start) * RUN * ADVANCE.i / total;
  expect(Math.abs(x - drawnAt)).toBeLessThan(1);
}

/**
 * The text layer in a real browser, which lays its text out. Selecting, searching and marking all read where
 * the text layer puts a character, so it has to be where the page draws it. The viewer sets the text in the
 * fonts PDF.js loads to draw the page. That relies on PDF.js internals, so this fails if an upgrade changes
 * them.
 */
test.describe('the text of a PDF', () => {
  test.beforeEach(async ({ page }) => {
    await new PdfViewerWidget(page).serve(URL, PDF);
  });

  test('puts each character where the page draws it, on a line of narrow and wide letters', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL });
    const line = viewer.locator('.textLayer span[role="presentation"]', { hasText: 'ix' });

    // Set in the font PDF.js loaded for the page, named after the document and the font
    await expect(line).toHaveCSS('font-family', /^"?g_d\d+_f\d+"?, /);
    expectOnGlyph(await measureLine(line));
  });

  test('keeps each character where the page draws it after a zoom', async ({ page }) => {
    const viewer = await new PdfViewerWidget(page).open({ url: URL });
    const line = viewer.locator('.textLayer span[role="presentation"]', { hasText: 'ix' });
    await expect(line).toHaveCSS('font-family', /^"?g_d\d+_f\d+"?, /);
    const before = await measureLine(line);

    await viewer.getByRole('button', { name: 'Zoom in' }).click();

    // PDF.js lays the line out again at the new scale
    await expect.poll(async () => {
      const { start, end } = await measureLine(line);
      return end - start;
    }).toBeGreaterThan(before.end - before.start + 10);
    expectOnGlyph(await measureLine(line));
  });
});
