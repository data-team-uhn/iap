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

// The client half of the deletion endpoint documented in docs/deletion.md: the shape of what it
// answers, and one call that always resolves to that shape.

/**
 * A `fetch` that survives the session expiring underneath it, as returned by `useAuthenticatedFetch`
 * (`@iap/frontend-commons/reLogin`).
 *
 * Taken as an argument rather than reached for here, because that hook can only be called from a
 * component — and keeping this module free of React is what lets it be tested as a plain function.
 */
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

/** The machine-readable outcome word every deletion response carries. */
export type DeletionStatus =
  | "archived"    // moved to the archive, restorable
  | "deleted"     // permanently removed
  | "dryRun"      // nothing was changed, this is only what would happen
  | "referenced"  // refused: other resources point at this one
  | "vetoed"      // refused: a guard objected, and no option makes it proceed
  | "denied"      // refused: the user may not delete everything this would touch
  | "missing"     // there is nothing at that path
  | "invalid"     // the path cannot be deleted at all, e.g. the archive itself
  | "failed";     // the server could not carry it out

/** Resources pointing at the target, grouped by their type so they can be named in a sentence. */
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
  /** A sentence fit to show the user, present whenever there is something to explain. */
  "status.message"?: string;
  /** Dry runs only: whether the deletion would go through exactly as asked. */
  executable?: boolean;
  /** Where the resources were moved, on an archiving deletion. */
  archiveEntry?: string;
  /** Every subtree the deletion would remove, the requested one included. */
  items?: string[];
  removedLinks?: string[];
  referrers?: ReferrerGroup[];
  /** Referring resources the user may not see, counted but never named. */
  inaccessibleReferrers?: number;
  vetoes?: Veto[];
}

export interface DeletionRequest {
  /** Delete the resources referring to this one as well, instead of refusing. */
  recursive?: boolean;
  /** Skip the archive and destroy the resources. */
  permanent?: boolean;
  /** Report what would happen and change nothing. */
  dryRun?: boolean;
}

/**
 * Ask the deletion endpoint to delete a resource, or — with `dryRun` — what deleting it would do.
 *
 * A refusal is an outcome, not an error: this resolves for every answer the endpoint gives, so
 * callers switch on `status` rather than catching. Only a network failure, or an expired session the
 * user declined to sign back in for, rejects.
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
 * The endpoint answers JSON for everything it handles itself, but the container can still cut in
 * ahead of it — an HTML error page, a login redirect — so an unreadable body is reported as an
 * outcome of its own rather than crashing the caller. A body we cannot read is never taken as
 * success, even on a 2xx: at that point we genuinely do not know what happened.
 */
const readResponse = async (response: Response): Promise<DeletionResponse> => {
  try {
    // A body of `null` is valid JSON, so the parse can succeed and still yield nothing
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
