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

import { type Page } from '@playwright/test';

/**
 * A submission's own page, which shows the submission or, to its author while it is a draft, edits it.
 */
export class SubmissionPage {
  constructor(private readonly page: Page) {}

  /**
   * Switches the page to editing.
   *
   * Scoped to the submission's own View/Edit switch. The dashboard has an Edit control on each row, and waiting
   * for those to go proves nothing, because they are also absent while the listings load. An unscoped click can
   * then land on one of them.
   */
  async edit(): Promise<void> {
    await this.page.getByRole('group', { name: 'How to show this submission' })
      .getByRole('button', { name: 'Edit' }).click();
  }

  /**
   * Answers a date question. Leaving the field is what saves it.
   *
   * @param question a pattern matching the field's label
   * @param day the day as `YYYY-MM-DD`
   */
  async answerDate(question: RegExp, day: string): Promise<void> {
    const field = this.page.getByLabel(question);
    await field.fill(day);
    await field.blur();
  }
}
