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
 * A schema version's page, and the comparison of two versions, which outlines one the same way: each part an item
 * of the outline, found by what it says.
 */
export class SchemaVersionPage {
  constructor(private readonly page: Page) {}

  /** The innermost part of the outline whose heading says the given text, after the icon naming its type. */
  part(text: string): Locator {
    const heading = this.page.getByText(new RegExp(`${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
    return this.page.getByRole('listitem').filter({ has: heading }).last();
  }

  /** Adds what the given scope offers to add, of the given type, saying what it says. */
  async add(scope: Locator, type: string, field: RegExp, text: string): Promise<void> {
    await scope.getByRole('button', { name: 'Add', exact: true }).last().click();
    await this.page.getByRole('menuitem', { name: type, exact: true }).click();
    const dialog = this.page.getByRole('dialog', { name: `Add ${type.toLowerCase()}` });
    await dialog.getByLabel(field).fill(text);
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(dialog).toBeHidden();
  }

  /** Chooses an action from the menu of what can be done to the part saying the given text. */
  async act(text: string, action: string): Promise<void> {
    await this.page.getByRole('button', { name: `Actions for “${text}”` }).click();
    await this.page.getByRole('menu').getByRole('menuitem', { name: action, exact: true }).click();
  }
}
