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

import { expect, type Page } from '@playwright/test';

/**
 * The dashboard a signed-in person lands on, and the dialog it opens to raise a submission.
 */
export class DashboardPage {
  constructor(private readonly page: Page) {}

  /**
   * Raises a submission, choosing what to submit against the way a person does, and waits for its page.
   *
   * @param schemaVersion a pattern matching the label the dialog offers the schema version under, e.g.
   *   `/Time off request 1\.0/`
   * @param title the text typed into the dialog's Title field, which the new page's heading then shows
   */
  async raiseSubmission(schemaVersion: RegExp, title: string): Promise<void> {
    await this.page.getByRole('button', { name: 'New submission' }).click();
    const dialog = this.page.getByRole('dialog');
    await dialog.getByRole('radio', { name: schemaVersion }).check();
    await dialog.getByLabel(/Title/).fill(title);
    await dialog.getByRole('button', { name: 'Create' }).click();
    // The heading, not the text: the dashboard lists the submission too, so a bare text match is satisfied
    // without having left it
    await expect(this.page.getByRole('heading', { name: title })).toBeVisible();
  }
}
