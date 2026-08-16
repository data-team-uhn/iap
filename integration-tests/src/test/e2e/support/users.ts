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

import { expect, type APIRequestContext } from '@playwright/test';

import { adminAuth, basicAuth, type Credentials } from './auth';

/**
 * Composum's user manager, the same endpoint its `/bin/users.html` page posts to. An account is the
 * one thing a suite cannot declare in a feature and then rely on: only the demo feature creates any,
 * so a suite that needs somebody other than the administrator has to make them.
 */
const USER_MANAGER = '/bin/cpm/usermanagement.user.json';

/**
 * Makes sure an ordinary account exists and can sign in, creating it if it does not.
 *
 * The account is given no group memberships and no grants of any kind, which is the point of it: it
 * is what an authenticated person who has been given nothing can see. That is a weaker starting
 * position than anonymous in one respect and a stronger one in another, and it is the case the
 * suites had no way to check until now.
 *
 * Creating is not asserted on, but signing in is. A repeat run — a CI retry, an instance that
 * outlived its build — finds the account already there and the creation refused, which is not a
 * failure; the account being unusable is, and that is what the check below would catch either way.
 */
export async function ensureUser(request: APIRequestContext, user: Credentials): Promise<void> {
  await request.post(USER_MANAGER, {
    headers: adminAuth,
    form: { username: user.username, password: user.password, intermediatePath: '' },
  });

  // Asked as the new account rather than as the administrator: what matters is that these
  // credentials work, not that a node appeared. `maxRedirects: 0` because an unauthenticated request
  // is answered with a 302 to a 200 sign-in page, which would otherwise read as success.
  const session = await request.get('/system/sling/info.sessionInfo.json', {
    headers: basicAuth(user),
    maxRedirects: 0,
  });
  expect(session.ok(), `${user.username} could not sign in after being created`).toBeTruthy();
  expect(((await session.json()) as { userID?: string }).userID).toBe(user.username);
}
