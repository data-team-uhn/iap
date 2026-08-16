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

// The client half of the archive viewer's endpoints: reading what is in the archive, and acting on
// one entry of it. Every call takes the fetch to use as its first argument rather than reaching for
// a hook, so that this file stays free of React and can be tested as plain functions; components
// pass `useAuthenticatedFetch()` in.

/** The fetch a caller supplies, normally the session-aware one from `@iap/frontend-commons/reLogin`. */
export type AuthenticatedFetch = (url: string, init?: RequestInit) => Promise<Response>;

/** The archive at a glance, as the administration console widget shows it. */
export interface ArchiveSummary {
  last24Hours: number;
  lastWeek: number;
  total: number;
  /** Whether counting stopped at the server's bound, so the numbers are lower bounds. */
  approximate: boolean;
}

/** One recorded deletion. */
export interface ArchiveEntry {
  /** The entry's own repository path — where it is stored, inside the prefix tree. */
  path: string;
  /** The same entry without the prefix tree, which is what a reader should be shown and linked to. */
  shortPath: string;
  /** The path whose deletion was requested. */
  requestedPath: string;
  /** The user who asked for it. */
  deletedBy: string;
  /** When it was archived, ISO-8601; absent only if the entry somehow carries no creation date. */
  created?: string;
  /** Where each archived subtree came from. */
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
  /** The sort the server actually applied, which is not always the one that was asked for. */
  sortBy: string;
  descending: boolean;
}

/** What a listing asks for. All optional; the server has a default for each. */
export interface ArchiveQuery {
  offset?: number;
  limit?: number;
  /** Keeps only the entries whose requested path or requesting user contains this, ignoring case. */
  filter?: string;
  sortBy?: string;
  descending?: boolean;
}

/** The outcome word an action answers with. */
export type ActionStatus =
  | "restored"  // every archived item went back where it came from
  | "deleted"   // the entry and everything in it are gone for good
  | "conflict"  // refused: something is in the way of the restore, nothing changed
  | "vetoed"    // refused: a guard objected to destroying this
  | "invalid"   // the request did not name something that can be acted on
  | "failed";   // the server could not carry it out

/** One thing standing in the way of a restore. */
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
  /** A sentence fit to show the user, present whenever there is something to explain. */
  "status.message"?: string;
  /** Where everything went back to, on a successful restore. */
  restored?: string[];
  conflicts?: RestoreConflict[];
  vetoes?: ActionVeto[];
}

/** One entry, with what would happen if either action were taken on it now. */
export interface ArchiveEntryDetail extends ArchiveEntry {
  /** Whether a restore requested now would go through. */
  restorable: boolean;
  restoreConflicts: RestoreConflict[];
  /** Whether a purge requested now would go through. */
  purgeable: boolean;
  purgeVetoes: ActionVeto[];
}

/** The archive root. The entries themselves live in buckets under it and are addressed by path. */
const ARCHIVE_PATH = "/Archive";

/**
 * Read the three counts the administration console widget shows.
 *
 * Rejects rather than resolving on failure: the widget has nothing to display without them, so
 * there is no partial answer worth inventing.
 */
export const fetchArchiveSummary = async (doFetch: AuthenticatedFetch): Promise<ArchiveSummary> => {
  const response = await doFetch(`${ARCHIVE_PATH}.summary.json`, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`The archive summary could not be read (${String(response.status)})`);
  }
  const body = await response.json() as ArchiveSummary | null;
  if (!body) {
    throw new Error("The archive summary could not be read");
  }
  return body;
};

/**
 * Read one page of archive entries.
 *
 * Rejects on failure, for the same reason as the summary: a table with no rows and no explanation
 * would claim the archive is empty.
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
  const body = await response.json() as ArchivePage | null;
  if (!body) {
    throw new Error("The archive could not be listed");
  }
  return body;
};

/**
 * Put one entry's contents back where they came from.
 *
 * A refusal is an outcome, not an error: this resolves for every answer the endpoint gives, so
 * callers switch on `status`. Only a network failure rejects.
 */
export const restoreEntry = (doFetch: AuthenticatedFetch, path: string): Promise<ActionResponse> =>
  act(doFetch, `${path}.restore.json`, "POST");

/**
 * Destroy one entry and everything archived in it. Irreversible.
 *
 * Resolves for every answer, like {@link restoreEntry}.
 */
export const purgeEntry = (doFetch: AuthenticatedFetch, path: string): Promise<ActionResponse> =>
  act(doFetch, path, "DELETE");

const act = async (doFetch: AuthenticatedFetch, url: string, method: string): Promise<ActionResponse> => {
  const response = await doFetch(url, { method, headers: { Accept: "application/json" } });
  try {
    // A body of `null` is valid JSON, so the parse can succeed and still yield nothing
    const body = await response.json() as ActionResponse | null;
    if (typeof body?.status === "string") {
      return body;
    }
  } catch {
    // Falls through to the synthesized outcome below
  }
  // The endpoints answer JSON for everything they handle themselves, but the container can cut in
  // ahead of them — an HTML error page, a login redirect. An unreadable body is never taken as
  // success, even on a 2xx: at that point we genuinely do not know what happened.
  return {
    status: "failed",
    "status.message": `The server gave an answer this page could not read (${String(response.status)})`,
  };
};

/**
 * Describe one entry: what is archived in it, and whether restoring or purging it would work.
 *
 * The preflight is a snapshot rather than a promise — another deletion can occupy a path between
 * reading this and acting on it — so the actions still report their own refusals.
 *
 * Rejects when the path does not name an archive entry: buckets share the archive's resource type
 * and answer with something else entirely, and rendering that as an empty entry would be a lie.
 */
export const fetchArchiveEntry = async (
  doFetch: AuthenticatedFetch,
  path: string
): Promise<ArchiveEntryDetail> => {
  const response = await doFetch(`${path}.entry.json`, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`That archive entry could not be read (${String(response.status)})`);
  }
  const body = await response.json() as ArchiveEntryDetail | null;
  if (!body?.path) {
    throw new Error("That is not an archive entry.");
  }
  return body;
};
