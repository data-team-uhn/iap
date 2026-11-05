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

import NoticeBanner from "@iap/frontend-commons/components/NoticeBanner";
import { type Extension } from "@iap/ui-extension/ExtensionList";

import useCatching from "./useCatching";

// The notice is in code, not on the extension node, so that it changes only with the code that decides
// when it shows.
const NOTICE: Extension = {
  "ext:data": "**No email is being sent from this instance.** Everything it would have emailed is"
    + " being kept here instead, including password resets and invitations.",
  "ext:severity": "error",
};

// Warns, on every page and before sign-in too, that mail is being caught and not sent.
export default function CatchingBanner() {
  const catching = useCatching();

  return catching ? <NoticeBanner extension={NOTICE} /> : null;
}
