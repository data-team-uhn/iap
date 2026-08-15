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

import { render, type RenderOptions, type RenderResult } from "@testing-library/react";

import { MessagesProvider, seedMessages } from "@iap/frontend-commons/messages";

// The English wording the page shell uses, as the shipped catalog defines it.
//
// Repeated here rather than read from the catalog: a test asserting on whatever the catalog happened to
// say would still pass if the catalog said the wrong thing. Keeping the expected wording in the test is
// what makes it an assertion; that the catalog agrees is the integration suite's job.
export const SHELL_MESSAGES: Record<string, string> = {
  "iap.shell.colourScheme.switchToDark": "Switch to dark mode",
  "iap.shell.colourScheme.switchToLight": "Switch to light mode",
  "iap.shell.notifications.label": "Notifications",
  "iap.shell.notifications.empty": "You have no new notifications",
  "iap.shell.userMenu.account.label": "Your account",
  "iap.shell.userMenu.signOut": "Sign out",
  "iap.shell.sidePanel.open": "Open the side panel",
  "iap.shell.sidePanel.hint": "The side panel is available here",
  "iap.shell.welcome.text": "Welcome to the Institutional Authorization Platform.",
  // The shared footer renders inside the shell, so its wording belongs here too
  "iap.footer.landmark.label": "Footer",
  "iap.footer.credit.builtBy": "Built by",
};

// Renders something with the shell's messages already in place.
//
// Seeded rather than fetched, so the provider has a catalog on its first pass and nothing has to wait
// for a request that is not what these tests are about.
export function withMessages(children: ReactNode) {
  seedMessages(SHELL_MESSAGES);
  return <MessagesProvider>{children}</MessagesProvider>;
}

// Testing Library's render with the provider already around it, imported as `render` by the shell's
// tests. Every component in the shell reads its words from a catalog now, so every one of them needs a
// provider above it — wrapping here rather than at each of the twenty-odd call sites keeps the tests
// about what they were about.
export function renderWithMessages(ui: ReactNode, options?: RenderOptions): RenderResult {
  return render(withMessages(ui), options);
}
