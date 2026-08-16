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

/** The three periods the console widget counts deletions over, named as it names them. */
export type ArchivePeriod = 'Archived in the last 24 hours' | 'Archived in the last 7 days' | 'Archived in total';

/**
 * The archive as an administrator meets it: the summary on the console, and the listing behind it.
 *
 * Entries are addressed by the path they were deleted from, which is the only thing about an entry a
 * reader is ever shown — the entry's own name is a UUID the server chose.
 */
export class ArchivePage {
  constructor(private readonly page: Page) {}

  /**
   * One of the three counts the console widget shows.
   *
   * The widget draws a period and its number as two siblings rather than as a labelled value, so the
   * number is read as the label's own following element. An approximate count is rendered as `12+`;
   * that only happens past ten thousand entries, and reading it as a number here would silently turn
   * a bounded scan into an exact answer, so it is refused instead.
   */
  async archived(period: ArchivePeriod): Promise<number> {
    const value = (await this.page.getByText(period, { exact: true })
      .locator('xpath=following-sibling::span').innerText()).trim();
    expect(value, `${period} was reported as an approximate count`).toMatch(/^\d+$/);
    return Number(value);
  }

  /** Opens the archive the way an administrator reaches it: from the console, by its own link. */
  async openFromConsole(): Promise<void> {
    await this.page.goto('/admin');
    await this.page.getByRole('link', { name: 'Manage the archive' }).click();
    await expect(this.page).toHaveURL(/\/admin\/archive$/);
    await expect(this.page.getByRole('heading', { name: 'Archive' })).toBeVisible();
  }

  /** Everything currently listed, one row per entry, without the header row. */
  rows(): Locator {
    return this.page.getByRole('table', { name: 'Archive entries' }).locator('tbody tr');
  }

  /** The row for one deleted path, found by the link the listing offers through to its entry. */
  rowFor(deletedPath: string): Locator {
    return this.rows().filter({ has: this.page.getByRole('link', { name: deletedPath, exact: true }) });
  }

  /** What the listing says when there is nothing in it at all. */
  empty(): Locator {
    return this.page.getByText('Nothing has been archived yet.');
  }

  /** What the listing says after a restore or a purge, which is where a refusal is reported too. */
  notice(): Locator {
    return this.page.getByRole('alert');
  }

  /**
   * The path of the most recently archived entry.
   *
   * The listing arrives newest first, so this is the deletion that has just been made — which is how
   * the stories learn the path of a category they only ever knew by its label.
   */
  async newestEntryPath(): Promise<string> {
    const link = this.rows().first().getByRole('link');
    await expect(link).toBeVisible();
    return (await link.innerText()).trim();
  }

  /** Narrows the listing to entries whose path or deleting user contains the text. */
  async filterBy(text: string): Promise<void> {
    await this.page.getByRole('textbox', { name: 'Filter by path or user' }).fill(text);
  }

  /** Orders the listing by a column, or reverses it if it is already ordered by that one. */
  async sortBy(column: 'Archived' | 'Deleted by' | 'Deleted path'): Promise<void> {
    await this.page.getByRole('button', { name: column }).click();
  }

  /** The paths currently listed, in the order they are shown in. */
  async listedPaths(): Promise<string[]> {
    return (await this.rows().getByRole('link').allInnerTexts()).map(path => path.trim());
  }

  /** Follows one entry through to its own page, where what an action would do can be seen first. */
  async openEntry(deletedPath: string): Promise<void> {
    await this.rowFor(deletedPath).getByRole('link', { name: deletedPath, exact: true }).click();
    // A single segment, and no prefix-tree buckets in it: entries are stored three levels deep and
    // addressed as if they were not.
    await expect(this.page).toHaveURL(/\/admin\/archive\/[^/]+$/);
  }

  /** Restores one entry straight from the listing, which acts without asking what would happen. */
  async restore(deletedPath: string): Promise<void> {
    await this.rowFor(deletedPath).getByRole('button', { name: 'Restore' }).click();
  }

  /** Purges one entry from the listing, confirming as a person does. */
  async purge(deletedPath: string): Promise<void> {
    await this.rowFor(deletedPath).getByRole('button', { name: 'Purge' }).click();
    const dialog = this.page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Purge this entry?' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Purge' }).click();
    await expect(dialog).toHaveCount(0);
  }
}

/**
 * One archive entry: what it holds, and — the reason the page exists — whether restoring or purging
 * it would actually work, asked before anybody commits to either.
 */
export class ArchiveEntryPage {
  constructor(private readonly page: Page) {}

  /** The entry names itself by the path that was deleted. */
  heading(deletedPath: string): Locator {
    return this.page.getByRole('heading', { name: deletedPath, exact: true });
  }

  /** How much the entry holds, which is what a restore would put back and a purge would destroy. */
  itemsHeading(): Locator {
    return this.page.getByRole('heading', { name: /^Archived items/ });
  }

  /** One archived item, saying where it would go back to and why it cannot. */
  item(originalPath: string): Locator {
    return this.page.getByRole('listitem').filter({ hasText: originalPath });
  }

  restoreEverything(): Locator {
    return this.page.getByRole('button', { name: 'Restore everything' });
  }

  purgeControl(): Locator {
    return this.page.getByRole('button', { name: 'Purge', exact: true });
  }

  /** Destroys the entry, confirming as a person does. */
  async purge(): Promise<void> {
    await this.purgeControl().click();
    const dialog = this.page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Purge this entry?' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Purge' }).click();
    await expect(dialog).toHaveCount(0);
  }
}
