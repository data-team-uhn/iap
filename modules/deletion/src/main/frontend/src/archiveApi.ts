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

// Calls the archive endpoints: listing the archive, describing one entry, and restoring or purging it.
// Every call takes the fetch to use as its first argument, and components pass `useAuthenticatedFetch()`.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readJson } from "@iap/frontend-commons/requestFailure";

/** One archive entry, as a listing row. */
export interface ArchiveEntry {
  /** Where the entry is stored, inside the prefix tree. */
  path: string;
  /** The entry's address without the prefix tree, `/Archive/by-id/<name>`, to show and link to. */
  shortPath: string;
  /** The path whose deletion was requested. */
  requestedPath: string;
  /** The user id of the user who requested the deletion. */
  deletedBy: string;
  /** When it was archived, as an ISO-8601 timestamp; absent if the entry has no `jcr:created`. */
  created?: string;
  /** Where each archived subtree was deleted from. */
  originalPaths: string[];
  itemCount: number;
}

/** One page of entries. */
export interface ArchivePage {
  rows: ArchiveEntry[];
  offset: number;
  limit: number;
  returnedrows: number;
  totalrows: number;
  totalIsApproximate: boolean;
  /** The sort the server used, which differs from the one asked for if that one is unknown. */
  sortBy: string;
  descending: boolean;
}

/** What to ask a listing for. The server has a default for each. */
export interface ArchiveQuery {
  offset?: number;
  limit?: number;
  /** Keeps the entries whose requested path or requesting user contains this, ignoring case. */
  filter?: string;
  sortBy?: string;
  descending?: boolean;
}

/** The outcome word a restore or purge answers with. */
export type ActionStatus =
  | "restored"  // every archived item went back where it came from
  | "deleted"   // the entry and everything in it are gone for good
  | "conflict"  // refused: something blocks the restore, and nothing changed
  | "vetoed"    // refused: a guard objected to the purge
  | "invalid"   // the request did not name an archive entry
  | "failed";   // the server could not carry it out

/** One archived item that cannot go back, and why. */
export interface RestoreConflict {
  originalPath: string;
  reason: string;
}

/** One guard's objection to a purge. */
export interface ActionVeto {
  vetoer: string;
  path: string;
  reason: string;
}

export interface ActionResponse {
  status: ActionStatus;
  /** A sentence for the user; present when there is something to explain. */
  "status.message"?: string;
  /** Where every item went back to, after a restore. */
  restored?: string[];
  conflicts?: RestoreConflict[];
  vetoes?: ActionVeto[];
}

/** One entry, and whether a restore and a purge would succeed now. */
export interface ArchiveEntryDetail extends ArchiveEntry {
  /** Whether a restore requested now would go through. */
  restorable: boolean;
  restoreConflicts: RestoreConflict[];
  /** Whether a purge requested now would go through. */
  purgeable: boolean;
  purgeVetoes: ActionVeto[];
}

/** The archive root. The entries are filed in buckets under it. */
export const ARCHIVE_PATH = "/Archive";

// The segment under which an entry answers to its name alone. The same as the server's
// PrefixTree.ADDRESS_SEGMENT.
const ADDRESS_SEGMENT = "by-id";

/**
 * The administration console's route for the archive. An entry's page is at
 * `/admin/archive/<name>`, while its endpoints answer at `/Archive/by-id/<name>`. The two
 * conversions below are the only place either is built.
 */
export const ARCHIVE_ROUTE = "/admin/archive";

/** The name of the entry addressed by a path or route ending in it, or null if there is none. */
const entryName = (path: string): string | null => {
  const name = path.replace(/\/+$/, "").split("/").pop();
  return name === undefined || name.length === 0 ? null : name;
};

/** The console route showing the entry stored at this repository path. */
export const entryRoute = (path: string): string => `${ARCHIVE_ROUTE}/${entryName(path) ?? ""}`;

/**
 * The path at which the endpoints answer for the entry a console route shows. Null if the route
 * names no entry, as for the listing's own route, or for one more than one segment deeper.
 */
export const entryResourcePath = (route: string): string | null => {
  const name = entryName(route);
  return name === null || `${ARCHIVE_ROUTE}/${name}` !== route
    ? null
    : `${ARCHIVE_PATH}/${ADDRESS_SEGMENT}/${name}`;
};

/**
 * Read one page of archive entries. Rejects if the listing fails, rather than resolving to an empty page.
 */
export const fetchArchiveEntries = async (
  doFetch: AuthenticatedFetch,
  query: ArchiveQuery = {}
): Promise<ArchivePage> => {
  const url = new URL(`${ARCHIVE_PATH}.entries.json`, window.location.origin);
  Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== "")
    .forEach(([name, value]) => url.searchParams.set(name, String(value)));

  const response = await doFetch(`${url.pathname}${url.search}`, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`The archive could not be listed (${String(response.status)})`);
  }
  const body = await readJson<ArchivePage | null>(response);
  if (!body) {
    throw new Error("The archive could not be listed");
  }
  return body;
};

/**
 * Put one entry's contents back where they came from.
 *
 * Resolves for every answer, refusals included, and the caller switches on `status`. Rejects only when
 * the request gets no answer, or when the fetch rejects for an expired session.
 */
export const restoreEntry = (doFetch: AuthenticatedFetch, path: string): Promise<ActionResponse> =>
  act(doFetch, `${path}.restore.json`, "POST");

/**
 * Remove one entry and everything archived in it, for good.
 *
 * Resolves for every answer, as {@link restoreEntry} does.
 */
export const purgeEntry = (doFetch: AuthenticatedFetch, path: string): Promise<ActionResponse> =>
  act(doFetch, path, "DELETE");

const act = async (doFetch: AuthenticatedFetch, url: string, method: string): Promise<ActionResponse> => {
  const response = await doFetch(url, { method, headers: { Accept: "application/json" } });
  try {
    // `null` is valid JSON, so the parse can succeed and still yield nothing
    const body = await response.json() as ActionResponse | null;
    if (typeof body?.status === "string") {
      return body;
    }
  } catch {
    // Falls through to the synthesized outcome below
  }
  // Something other than the endpoint answered, such as an HTML error page or a login redirect.
  // An unreadable body is a failure, even with a 2xx status.
  return {
    status: "failed",
    "status.message": `The server gave an answer this page could not read (${String(response.status)})`,
  };
};

/**
 * Describe one entry: what is archived in it, and whether a restore and a purge would succeed now.
 *
 * Rejects if the path does not name an archive entry, such as a prefix-tree bucket's path.
 */
export const fetchArchiveEntry = async (
  doFetch: AuthenticatedFetch,
  path: string
): Promise<ArchiveEntryDetail> => {
  const response = await doFetch(`${path}.entry.json`, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`That archive entry could not be read (${String(response.status)})`);
  }
  const body = await readJson<ArchiveEntryDetail | null>(response);
  if (!body?.path) {
    throw new Error("That is not an archive entry.");
  }
  return body;
};
