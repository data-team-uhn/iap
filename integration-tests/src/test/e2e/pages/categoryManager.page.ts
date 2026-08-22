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

/**
 * The category manager, the administration console tool the archive stories delete from.
 *
 * Every category is located by the label it was given, because that is what the tree announces each
 * of its controls with — `Edit Pilot studies`, `Delete Pilot studies`. Node names never appear here:
 * the server derives those from the label and the stories are not told what it decided, which is as
 * it should be, since nobody using this page is told either.
 */
export class CategoryManagerPage {
  constructor(private readonly page: Page) {}

  /** Opens the manager the way an administrator reaches it: from the console, by its own link. */
  async openFromConsole(): Promise<void> {
    await this.page.goto('/admin');
    await this.page.getByRole('link', { name: 'Manage categories' }).click();
    await expect(this.page).toHaveURL(/\/admin\/categories$/);
    await expect(this.page.getByRole('button', { name: 'New category' })).toBeVisible();
  }

  /** The control that opens one category for editing, which is also how the tree says it is there. */
  edit(label: string): Locator {
    return this.page.getByRole('button', { name: `Edit ${label}`, exact: true });
  }

  /** The control that deletes one category, disabled for as long as it has subcategories. */
  deleteControl(label: string): Locator {
    return this.page.getByRole('button', { name: `Delete ${label}`, exact: true });
  }

  /**
   * Creates a category, at the root or under a parent, and leaves it in view.
   *
   * A new subcategory arrives inside a parent that is still collapsed, so the parent is opened
   * afterwards — otherwise the category is there and nothing on screen says so.
   */
  async create(label: string, parent?: string): Promise<void> {
    if (parent === undefined) {
      await this.page.getByRole('button', { name: 'New category' }).click();
    } else {
      await this.page.getByRole('button', { name: `Add subcategory to ${parent}`, exact: true }).click();
    }

    const dialog = this.page.getByRole('dialog');
    // By the name the field announces rather than by its label's text: the label carries the required
    // marker as well, so "Label" and "Label *" are the same field asked for two different ways.
    await dialog.getByRole('textbox', { name: 'Label', exact: true }).fill(label);
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(dialog).toHaveCount(0);

    if (parent !== undefined) {
      await this.expand(parent);
    }
    await expect(this.edit(label)).toBeVisible();
  }

  /** Deletes a category, confirming as a person does. */
  async delete(label: string): Promise<void> {
    await this.deleteControl(label).click();
    await this.confirmDelete(label);
  }

  /**
   * Accepts a deletion already asked for.
   *
   * Separate from {@link delete} so that a story can read what the confirmation says before agreeing
   * to it — which is the point of the dialog, and in one story the promise the rest of it checks.
   */
  async confirmDelete(label: string): Promise<void> {
    const dialog = this.page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: `Delete ${label}?` })).toBeVisible();
    await dialog.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(this.edit(label)).toHaveCount(0);
  }

  /** Opens a category to show its subcategories, if it is not open already. */
  async expand(label: string): Promise<void> {
    const toggle = this.page.getByRole('button', { name: `Expand ${label}`, exact: true });
    // The toggle is always rendered and merely made invisible on a category with no subcategories,
    // so being visible is what says there is anything to open.
    if (await toggle.isVisible()) {
      await toggle.click();
    }
  }
}
