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

import { PdfViewerWidget } from '../../pages/pdfViewer.page';
import { writePdf } from '../../support/pdf';

const URL = '/e2e/pdf-text-layer/narrow-and-wide.pdf';

/** How many narrow letters come before the `x`, and how many wide ones after it. */
const RUN = 60;

/** Helvetica's advance widths, in thousandths of the font size, which PDF.js draws the font at. */
const ADVANCE = { i: 222, x: 500, m: 833 };

/** A line of narrow letters, then the `x`, then wide letters, on a page wide enough for it. */
const PDF = writePdf([ [ `${'i'.repeat(RUN)}x${'m'.repeat(RUN)}` ] ], { width: 842, height: 200 });

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
    const line = viewer.locator('.textLayer span', { hasText: 'ix' });

    // Set in the font PDF.js loaded for the page, named after the document and the font
    await expect(line).toHaveCSS('font-family', /^"?g_d\d+_f\d+"?, /);
    const { start, end, x } = await line.evaluate(span => {
      const text = span.firstChild as Text;
      const measure = (from: number, to: number) => {
        const range = document.createRange();
        range.setStart(text, from);
        range.setEnd(text, to);
        return range.getBoundingClientRect();
      };
      const at = text.data.indexOf('x');
      const last = text.data.length;
      return { start: measure(0, 1).left, end: measure(last - 1, last).right, x: measure(at, at + 1).left };
    });

    // The line's two ends are where PDF.js stretched it to, and the x is as far along it as its glyphs are
    const total = RUN * ADVANCE.i + ADVANCE.x + RUN * ADVANCE.m;
    const drawnAt = start + (end - start) * RUN * ADVANCE.i / total;
    expect(Math.abs(x - drawnAt)).toBeLessThan(1);
  });
});
