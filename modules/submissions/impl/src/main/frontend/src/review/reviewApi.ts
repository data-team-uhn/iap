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

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

// PRETOTYPE. Writing on a submission under review is one workflow event, reviewComment: an issue, a message to
// the other reviewers or a note to self, or a reply, an edit or a removal.
export type CommentKind = "issue" | "discussion" | "note";

export function writeComment(doFetch: AuthenticatedFetch, submission: string,
  fields: Record<string, string | string[]>): Promise<string | undefined> {
  const body = new FormData();
  for (const [ name, value ] of Object.entries(fields)) {
    for (const one of Array.isArray(value) ? value : [ value ]) {
      body.append(name, one);
    }
  }
  return sendEvent(doFetch, submission, "reviewComment", body);
}
