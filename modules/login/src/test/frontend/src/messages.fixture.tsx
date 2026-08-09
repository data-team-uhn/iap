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

import { type ReactNode } from "react";

import { MessagesProvider, seedMessages } from "@iap/frontend-commons/messages";

// The English messages the sign-in page uses, as the shipped catalog defines them.
//
// Repeated here rather than read from the catalog because these tests are about the interface, not about
// how a catalog reaches the browser: a test that asserted on whatever the catalog happened to say would
// still pass if the catalog said the wrong thing. Keeping the expected wording in the test is what makes
// it an assertion. That the catalog agrees is the integration suite's job.
export const SIGN_IN_MESSAGES: Record<string, string> = {
  "iap.login.credentialsForm.username.label": "Username",
  "iap.login.credentialsForm.password.label": "Password",
  "iap.login.credentialsForm.submit.label": "Sign in",
  "iap.login.credentialsForm.error.invalidCredentials": "Invalid username or password",
  "iap.login.signInMethods.moreOptions.label": "More sign-in options",
  "iap.login.page.aboutPlatform.label": "About the platform",
};

// Renders something with the sign-in page's messages already in place.
//
// Seeded rather than fetched, so the provider has a catalog on its first pass and nothing has to wait for
// a request that is not what the test is about.
export function withMessages(children: ReactNode) {
  seedMessages(SIGN_IN_MESSAGES);
  return <MessagesProvider>{children}</MessagesProvider>;
}
