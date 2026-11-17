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

import { ADMIN, signInAs } from '../../support/auth';

const URL = '/e2e/pdf-text-layer/narrow-and-wide.pdf';

/** How many narrow letters come before the `x`, and how many wide ones after it. */
const RUN = 60;

/** The line: narrow letters, then the `x`, then wide letters. */
const LINE = `${'i'.repeat(RUN)}x${'m'.repeat(RUN)}`;

/** Helvetica's advance widths, in thousandths of the font size, which PDF.js draws the font at. */
const ADVANCE = { i: 222, x: 500, m: 833 };

/**
 * A one-page PDF of the line in Helvetica, named but not embedded, so its glyphs have known widths. Written
 * by hand, since no printer leaves a standard font unembedded.
 */
function writePdf(): Buffer {
  const content = `BT\n/F1 12 Tf\n1 0 0 1 30 100 Tm\n(${LINE}) Tj\nET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets = objects.map((body, index) => {
    const offset = pdf.length;
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
    return offset;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  pdf += offsets.map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

/** Signs in and opens the PDF in a dialog. Gives back the frame showing it. */
async function openPdf(page: Page): Promise<Locator> {
  await signInAs(page, ADMIN);
  await page.goto('/');
  const widget = page.locator('div').filter({ has: page.getByLabel('Older version URL (optional)') })
    .filter({ has: page.getByRole('button', { name: 'Open' }) }).last();
  await widget.getByLabel('PDF URL').fill(URL);
  await widget.getByRole('checkbox', { name: 'Show in place, below the form' }).uncheck();
  await widget.getByRole('button', { name: 'Open' }).click();
  return page.getByRole('dialog').getByRole('region', { name: 'Document' });
}

/**
 * The text layer in a real browser, which lays its text out. Selecting, searching and marking all read where
 * the text layer puts a character, so it has to be where the page draws it. The viewer sets the text in the
 * fonts PDF.js loads to draw the page. That relies on PDF.js internals, so this fails if an upgrade changes
 * them.
 */
test.describe('the text of a PDF', () => {
  test.beforeEach(async ({ page }) => {
    const pdf = writePdf();
    await page.route(`**${URL}`, route => route.fulfill({ contentType: 'application/pdf', body: pdf }));
  });

  test('puts each character where the page draws it, on a line of narrow and wide letters', async ({ page }) => {
    const frame = await openPdf(page);
    const line = frame.locator('.textLayer span', { hasText: 'ix' });

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
