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

import { expect, type Locator, type Page } from '@playwright/test';

import { ADMIN, signInAs } from '../support/auth';

/** Where something is on the screen, in pixels. */
export interface Box {
  left: number;
  right: number;
  top: number;
  bottom: number;
}

/** A run of characters of a piece of text, from `from` up to `to`. */
export interface CharRange {
  from: number;
  to: number;
}

/**
 * Where the text layer puts runs of characters of one of its pieces of text, the span PDF.js draws it in.
 * Selecting, searching and marking all read the text layer this way, through a DOM Range.
 */
export function getCharBoxes(span: Locator, ranges: CharRange[]): Promise<Box[]> {
  return span.evaluate((piece, runs) => runs.map(({ from, to }) => {
    const range = document.createRange();
    range.setStart(piece.firstChild as Text, from);
    range.setEnd(piece.firstChild as Text, to);
    const { left, right, top, bottom } = range.getBoundingClientRect();
    return { left, right, top, bottom };
  }), ranges);
}

/** What the test-data widget is told to open. */
export interface Opening {
  url: string;
  olderUrl?: string;
  page?: number;
  // The passages to find, one per line of the widget's field
  quotes?: string[];
  // The viewer's title, which also names it on the page. "The document" when absent.
  title?: string;
  // Below the form rather than in a dialog
  inPlace?: boolean;
}

/**
 * The PDF viewer as the test-data dashboard's widget opens it. The PDFs are served by the test itself, so
 * nothing is stored on the instance.
 */
export class PdfViewerWidget {
  constructor(private readonly page: Page) {}

  /** Serves a PDF at a path on the instance, for this page only. */
  async serve(path: string, pdf: Buffer): Promise<void> {
    await this.page.route(`**${path}`, route => route.fulfill({ contentType: 'application/pdf', body: pdf }));
  }

  /**
   * Signs in as the administrator, fills the widget's form and opens the viewer.
   *
   * @returns the viewer, from its title to its documents, in its dialog or below the form
   */
  async open(opening: Opening): Promise<Locator> {
    const title = opening.title ?? 'The document';
    await signInAs(this.page, ADMIN);
    await this.page.goto('/');
    const form = this.page.locator('div').filter({ has: this.page.getByLabel('Older version URL (optional)') })
      .filter({ has: this.page.getByRole('button', { name: 'Open' }) }).last();
    await form.getByLabel('PDF URL').fill(opening.url);
    await form.getByLabel('Older version URL (optional)').fill(opening.olderUrl ?? '');
    await form.getByLabel('Page (optional)').fill(opening.page === undefined ? '' : String(opening.page));
    await form.getByLabel('Text to find (optional)').fill((opening.quotes ?? []).join('\n'));
    await form.getByLabel('Title (optional)').fill(title);
    await form.getByRole('checkbox', { name: 'Show in place, below the form' }).setChecked(opening.inPlace ?? false);
    await form.getByRole('button', { name: 'Open' }).click();
    const viewer = this.page.locator('div')
      .filter({ has: this.page.getByRole('heading', { name: title, exact: true }) })
      .filter({ has: this.page.getByRole('region', { name: 'Document' }) })
      .last();
    await expect(viewer).toBeVisible();
    return viewer;
  }
}
