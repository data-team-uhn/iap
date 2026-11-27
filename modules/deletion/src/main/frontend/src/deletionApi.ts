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
import { readOutcome, UNREADABLE_RESPONSE } from "@iap/frontend-commons/requestFailure";


// Asks the deletion endpoint documented in docs/deletion.md what deleting a resource would do, and
// describes what it answers. The deletion itself is a workflow's `delete` event. The call takes the
// fetch to use as its first argument, and components pass `useAuthenticatedFetch()`.

/** The machine-readable outcome word every answer carries. */
export type DeletionStatus =
  | "dryRun"      // what the deletion would do; nothing was changed
  | "missing"     // there is nothing at that path
  | "invalid"     // the path cannot be deleted at all, such as one in the archive
  | "failed";     // the server could not work it out

/** The resources of one node type that block the deletion by referencing the target. */
export interface ReferrerGroup {
  type: string;
  label: string;
  count: number;
  names: string[];
}

/** One guard's objection. */
export interface Veto {
  vetoer: string;
  path: string;
  reason: string;
}

export interface DeletionResponse {
  "status.code": number;
  status: DeletionStatus;
  /** A sentence for the user; present when there is something to explain. */
  "status.message"?: string;
  /** Whether nothing blocks the deletion. The user's permissions are not part of it. */
  executable?: boolean;
  /** Every subtree the deletion removes, the requested one included. */
  items?: string[];
  removedLinks?: string[];
  referrers?: ReferrerGroup[];
  /** Blocking resources the user cannot read, counted but never named. */
  inaccessibleReferrers?: number;
  vetoes?: Veto[];
}

/**
 * Ask the deletion endpoint what deleting a resource would do.
 *
 * Resolves for every answer, and the caller switches on `status`. Rejects only when the request gets
 * no answer, or when the user declines to sign back in after their session expired.
 */
export const examineDeletion = async (
  authenticatedFetch: AuthenticatedFetch,
  path: string,
): Promise<DeletionResponse> =>
  await readResponse(await authenticatedFetch(`${path}.deletion.json`));

/**
 * The endpoint's answer. Something else can answer instead, such as an HTML error page or a login
 * redirect. The outcome is then `missing` for a 404, and `failed` otherwise, even for a 2xx.
 */
const readResponse = async (response: Response): Promise<DeletionResponse> =>
  await readOutcome<DeletionResponse>(response) ?? {
    "status.code": response.status,
    status: response.status === 404 ? "missing" : "failed",
    "status.message": UNREADABLE_RESPONSE,
  };
