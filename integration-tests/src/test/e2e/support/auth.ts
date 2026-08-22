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

import { LoginPage } from '../pages/login.page';

/** Somebody these suites can act as. */
export interface Credentials {
  readonly username: string;

  readonly password: string;
}

/** The administrator a freshly launched instance always has, which is how these suites sign in. */
export const ADMIN: Credentials = { username: 'admin', password: 'admin' } as const;

/** Request headers that authenticate as somebody. */
export function basicAuth(user: Credentials): Record<string, string> {
  return { Authorization: `Basic ${Buffer.from(`${user.username}:${user.password}`).toString('base64')}` };
}

/**
 * Request headers that authenticate as the administrator.
 *
 * Deliberately passed per request rather than configured as Playwright `httpCredentials`, because
 * several tests exist precisely to check what an *unauthenticated* caller is served — a project-wide
 * credential would silently make those pass against a repository that never refused anyone.
 */
export const adminAuth = basicAuth(ADMIN);

/**
 * Signs the browser in, so that whatever the test is really about starts from an authenticated session.
 *
 * The browser counterpart of {@link adminAuth}: for tests that drive the interface rather than call the
 * repository, and that only pass through the sign-in page on their way somewhere else.
 */
export async function signInAs(page: Page, user: Credentials): Promise<void> {
  const login = new LoginPage(page);
  await login.open();
  await login.signInAs(user.username, user.password);
  // The sign-in form going away is what says the session exists; navigating before that races it.
  await expect(login.signIn).toHaveCount(0);
}
