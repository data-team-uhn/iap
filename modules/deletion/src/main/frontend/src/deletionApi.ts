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

// Calls the deletion endpoint documented in docs/deletion.md, and describes what it answers.
// The call takes the fetch to use as its first argument, and components pass `useAuthenticatedFetch()`.

/** The machine-readable outcome word every deletion response carries. */
export type DeletionStatus =
  | "archived"    // moved to the archive, restorable
  | "deleted"     // permanently removed
  | "dryRun"      // nothing was changed, this is only what would happen
  | "referenced"  // refused: other resources point at this one
  | "vetoed"      // refused: a guard objected, and no option makes it proceed
  | "denied"      // refused: the user may not delete everything this would remove
  | "missing"     // there is nothing at that path
  | "invalid"     // the path cannot be deleted at all, such as one in the archive
  | "failed";     // the server could not carry it out

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
  /** After a dry run: whether nothing blocks the deletion. The user's permissions are not part of it. */
  executable?: boolean;
  /** The path of the new archive entry, after an archiving deletion. */
  archiveEntry?: string;
  /** Every subtree the deletion removes, the requested one included. */
  items?: string[];
  removedLinks?: string[];
  referrers?: ReferrerGroup[];
  /** Blocking resources the user cannot read, counted but never named. */
  inaccessibleReferrers?: number;
  vetoes?: Veto[];
}

export interface DeletionRequest {
  /** Delete the resources that reference this one too, instead of refusing. */
  recursive?: boolean;
  /** Skip the archive and remove the resources for good. */
  permanent?: boolean;
  /** Report what would happen and change nothing. */
  dryRun?: boolean;
}

/**
 * Ask the deletion endpoint to delete a resource, or, with `dryRun`, what deleting it would do.
 *
 * Resolves for every answer, refusals included, and the caller switches on `status`. Rejects only when
 * the request gets no answer, or when the user declines to sign back in after their session expired.
 */
export const requestDeletion = async (
  authenticatedFetch: AuthenticatedFetch,
  path: string,
  options: DeletionRequest = {}
): Promise<DeletionResponse> => {
  const url = new URL(path, window.location.origin);
  Object.entries(options)
    .filter(([, enabled]) => enabled)
    .forEach(([option]) => url.searchParams.set(option, "true"));

  const response = await authenticatedFetch(url.toString(), {
    method: "DELETE",
    headers: { Accept: "application/json" }
  });
  return await readResponse(response);
};

/**
 * The endpoint's answer. Something else can answer instead, such as an HTML error page or a login
 * redirect. The outcome is then `missing` for a 404, and `failed` otherwise, even for a 2xx.
 */
const readResponse = async (response: Response): Promise<DeletionResponse> => {
  try {
    // `null` is valid JSON, so the parse can succeed and still yield nothing
    const body = await response.json() as DeletionResponse | null;
    if (typeof body?.status === "string") {
      return body;
    }
  } catch {
    // Falls through to the synthesized outcome below
  }
  return {
    "status.code": response.status,
    status: response.status === 404 ? "missing" : "failed",
    "status.message": `The server answered ${response.status} without an explanation.`
  };
};
