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
 * The sign-in page.
 *
 * Everything is located the way a person finds it — by label, by role, by the text on the button — rather
 * than by CSS class or test id. That keeps the tests honest about accessibility, and stops a restyle from
 * breaking them.
 */
export class LoginPage {
  readonly username: Locator;

  readonly password: Locator;

  readonly signIn: Locator;

  /**
   * The link that reveals the local credentials form where it is not the primary sign-in method. Present
   * only when something else — an institutional identity provider, say — is registered ahead of it.
   */
  readonly localAccountToggle: Locator;

  /** The strip of participating institutions, which only appears where a registry has been installed. */
  readonly participatingInstitutions: Locator;

  constructor(private readonly page: Page) {
    this.username = page.getByLabel('Username');
    this.password = page.getByLabel('Password');
    this.signIn = page.getByRole('button', { name: 'Sign in' });
    this.localAccountToggle = page.getByRole('button', { name: 'Use a local account instead' });
    this.participatingInstitutions = page.getByRole('heading', { name: 'Participating institutions' });
  }

  async open(): Promise<void> {
    await this.page.goto('/login');
  }

  /**
   * Makes the local credentials form usable, whichever way the deployment has arranged its sign-in
   * methods.
   *
   * Sign-in methods are contributed through an extension point and ordered; the first renders in place and
   * the rest collapse behind their labels. So the credentials form is right there on a deployment that
   * registers nothing else, and one click away on a deployment whose primary method is an external
   * provider. Waiting for either shape to appear before deciding avoids racing the extension load.
   */
  async revealLocalAccountForm(): Promise<void> {
    await expect(this.username.or(this.localAccountToggle).first()).toBeVisible();
    if (!(await this.username.isVisible())) {
      await this.localAccountToggle.click();
    }
    await expect(this.username).toBeVisible();
  }

  async signInAs(username: string, password: string): Promise<void> {
    await this.revealLocalAccountForm();
    await this.username.fill(username);
    await this.password.fill(password);
    await this.signIn.click();
  }
}
