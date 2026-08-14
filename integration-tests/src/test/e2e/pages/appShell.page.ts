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
 * The application shell: the bar that is there whatever page a signed-in person is on.
 *
 * Everything here is located the way a person finds it — the account control by the name it announces,
 * the menu item by its text — so these steps break when the shell stops being usable rather than when it
 * is restyled.
 */
export class AppShell {
  constructor(private readonly page: Page) {}

  /**
   * Ends the session through the account menu, the way a person leaves.
   *
   * Waits for the sign-in page rather than for the click: signing out is a navigation to Sling's logout
   * endpoint, so the session is only really gone once that has landed, and a story that carried on
   * immediately would do its next step as whoever was still signed in.
   */
  async signOut(): Promise<void> {
    await this.page.getByRole('button', { name: /^Account:/ }).click();
    await this.page.getByRole('menuitem', { name: 'Sign out' }).click();
    await expect(this.page).toHaveURL(/\/login/);
  }

  /** Who the shell says is signed in, which is the account control's own name for itself. */
  async signedInAs(): Promise<string> {
    const label = await this.page.getByRole('button', { name: /^Account:/ }).getAttribute('aria-label');
    return (label ?? '').replace('Account: ', '');
  }
}
