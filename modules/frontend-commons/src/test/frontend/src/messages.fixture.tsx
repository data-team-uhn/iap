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

// The English wording the footer uses, as the shipped catalog defines it.
//
// Repeated here rather than read from the catalog, for the same reason the sign-in page's fixture repeats
// its own: a test asserting on whatever the catalog happened to say would still pass if the catalog said
// the wrong thing. Keeping the expected wording in the test is what makes it an assertion. That the
// catalog agrees with it is the integration suite's job.
export const FOOTER_MESSAGES: Record<string, string> = {
  "iap.footer.landmark.label": "Footer",
  "iap.footer.credit.builtBy": "Built by",
};

// Renders something with the footer's messages already in place.
//
// Seeded rather than fetched, so the provider has a catalog on its first pass and nothing has to wait for
// a request that is not what these tests are about.
export function withMessages(children: ReactNode) {
  seedMessages(FOOTER_MESSAGES);
  return <MessagesProvider>{children}</MessagesProvider>;
}
