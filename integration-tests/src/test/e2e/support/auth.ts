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

import { readFile } from 'node:fs/promises';

import { expect, test, type Cookie, type Page } from '@playwright/test';

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
 * Where the current project keeps the administrator session its sign-in setup project saved, e.g.
 * `target/e2e-auth/platform-chromium.json`.
 */
export function adminSessionFile(): string {
  const file: unknown = test.info().project.metadata.adminSession;
  if (typeof file !== 'string') {
    throw new Error(`Project ${test.info().project.name} has no saved administrator session`);
  }
  return file;
}

/**
 * Signs the browser in, so that whatever the test is really about starts from an authenticated session.
 *
 * The browser counterpart of {@link adminAuth}, for tests that drive the interface rather than call the
 * repository. It leaves the browser on no page a test may rely on, so the test goes next to where it starts.
 * The administrator loads no page at all: it reuses the session its project's sign-in setup saved rather than
 * filling the form at every one of some fifty sign-ins. Signing out elsewhere does not end that session: Sling
 * checks its cookie, it stores none.
 */
export async function signInAs(page: Page, user: Credentials): Promise<void> {
  if (user !== ADMIN) {
    await signInThroughForm(page, user);
    return;
  }
  const session = JSON.parse(await readFile(adminSessionFile(), 'utf8')) as { cookies: Cookie[] };
  await page.context().addCookies(session.cookies);
}

/** Signs the browser in through the sign-in form, leaving it on the page a sign-in lands on. */
export async function signInThroughForm(page: Page, user: Credentials): Promise<void> {
  const login = new LoginPage(page);
  await login.open();
  await login.signInAs(user.username, user.password);
  // The sign-in form going away is what says the session exists; navigating before that races it.
  await expect(login.signIn).toHaveCount(0);
}
