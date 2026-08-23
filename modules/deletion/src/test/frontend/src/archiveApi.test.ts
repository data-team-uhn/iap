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

import {
  ARCHIVE_ROUTE,
  entryResourcePath,
  entryRoute,
  fetchArchiveEntries,
  fetchArchiveEntry,
  fetchArchiveSummary,
  purgeEntry,
  restoreEntry,
} from "@iap/deletion/archiveApi";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

const summary = { last24Hours: 1, lastWeek: 2, total: 3, approximate: false };

const emptyPage = {
  rows: [], offset: 0, limit: 25, returnedrows: 0, totalrows: 0,
  totalIsApproximate: false, sortBy: "jcr:created", descending: true,
};

describe("fetchArchiveSummary", () => {
  it("reads the three counts", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, summary));
    await expect(fetchArchiveSummary(doFetch)).resolves.toEqual(summary);
  });

  it("asks the archive root for its summary", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, summary));
    await fetchArchiveSummary(doFetch);
    expect(doFetch.mock.calls[0][0]).toBe("/Archive.summary.json");
  });

  it("rejects when the archive is not readable, rather than reporting zeroes", async () => {
    // Zeroes would be a claim that nothing has ever been deleted
    const doFetch = vi.fn().mockResolvedValue(new Response("", { status: 404 }));
    await expect(fetchArchiveSummary(doFetch)).rejects.toThrow("404");
  });

  it("rejects when the body is empty", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, null));
    await expect(fetchArchiveSummary(doFetch)).rejects.toThrow();
  });
});

describe("fetchArchiveEntries", () => {
  it("asks for the default page when nothing is specified", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, emptyPage));
    await fetchArchiveEntries(doFetch);
    expect(doFetch.mock.calls[0][0]).toBe("/Archive.entries.json");
  });

  it("passes the paging, filter and sort along", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, emptyPage));
    await fetchArchiveEntries(doFetch,
      { offset: 25, limit: 10, filter: "alice", sortBy: "deletedBy", descending: false });
    const url = doFetch.mock.calls[0][0] as string;
    expect(url).toContain("offset=25");
    expect(url).toContain("limit=10");
    expect(url).toContain("filter=alice");
    expect(url).toContain("sortBy=deletedBy");
    expect(url).toContain("descending=false");
  });

  it("leaves an empty filter out of the request entirely", async () => {
    // Otherwise the server would be asked to match everything against the empty string
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, emptyPage));
    await fetchArchiveEntries(doFetch, { filter: "", offset: 0 });
    expect(doFetch.mock.calls[0][0] as string).not.toContain("filter");
  });

  it("returns the page as the server described it", async () => {
    const page = { ...emptyPage, totalrows: 7, rows: [ {
      path: "/Archive/ab/cd/ef/one", shortPath: "/Archive/one", requestedPath: "/content/one", deletedBy: "alice",
      created: "2026-08-14T00:00:00.000+00:00", originalPaths: [ "/content/one" ], itemCount: 1,
    } ] };
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, page));
    await expect(fetchArchiveEntries(doFetch)).resolves.toEqual(page);
  });

  it("rejects when the listing cannot be read", async () => {
    const doFetch = vi.fn().mockResolvedValue(new Response("", { status: 500 }));
    await expect(fetchArchiveEntries(doFetch)).rejects.toThrow("500");
  });

  it("rejects when the body is empty", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, null));
    await expect(fetchArchiveEntries(doFetch)).rejects.toThrow();
  });
});

describe("restoreEntry", () => {
  it("posts to the entry's restore endpoint", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, { status: "restored", restored: [ "/a" ] }));
    const answer = await restoreEntry(doFetch, "/Archive/ab/one");
    expect(doFetch.mock.calls[0][0]).toBe("/Archive/ab/one.restore.json");
    expect(doFetch.mock.calls[0][1]).toMatchObject({ method: "POST" });
    expect(answer.status).toBe("restored");
  });

  it("resolves a refusal as an outcome rather than throwing", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(409, {
      status: "conflict", conflicts: [ { originalPath: "/a", reason: "OCCUPIED" } ],
    }));
    const answer = await restoreEntry(doFetch, "/Archive/ab/one");
    expect(answer.status).toBe("conflict");
    expect(answer.conflicts).toHaveLength(1);
  });
});

describe("purgeEntry", () => {
  it("deletes the entry itself", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, { status: "deleted" }));
    const answer = await purgeEntry(doFetch, "/Archive/ab/one");
    expect(doFetch.mock.calls[0][0]).toBe("/Archive/ab/one");
    expect(doFetch.mock.calls[0][1]).toMatchObject({ method: "DELETE" });
    expect(answer.status).toBe("deleted");
  });

  it("reports a veto as an outcome", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(409, {
      status: "vetoed", "status.message": "Too recent",
    }));
    await expect(purgeEntry(doFetch, "/Archive/ab/one"))
      .resolves.toMatchObject({ status: "vetoed" });
  });
});

describe("an answer this page cannot read", () => {
  it("is never taken as success, even on a 200", async () => {
    // A login redirect or an HTML error page can arrive instead of the endpoint's own answer
    const doFetch = vi.fn().mockResolvedValue(new Response("<html>signed out</html>", { status: 200 }));
    const answer = await purgeEntry(doFetch, "/Archive/ab/one");
    expect(answer.status).toBe("failed");
    expect(answer["status.message"]).toContain("200");
  });

  it("is reported when the body parses but says nothing useful", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, { unexpected: true }));
    await expect(restoreEntry(doFetch, "/Archive/ab/one"))
      .resolves.toMatchObject({ status: "failed" });
  });
});

describe("fetchArchiveEntry", () => {
  const detail = {
    path: "/Archive/ab/cd/ef/one", shortPath: "/Archive/one", requestedPath: "/content/one", deletedBy: "alice",
    created: "2026-08-14T00:00:00.000+00:00", originalPaths: [ "/content/one" ], itemCount: 1,
    restorable: true, restoreConflicts: [], purgeable: true, purgeVetoes: [],
  };

  it("asks the entry itself what would happen to it", async () => {
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, detail));
    await expect(fetchArchiveEntry(doFetch, "/Archive/ab/one")).resolves.toEqual(detail);
    expect(doFetch.mock.calls[0][0]).toBe("/Archive/ab/one.entry.json");
  });

  it("carries the preflight through", async () => {
    const blocked = {
      ...detail,
      restorable: false,
      restoreConflicts: [ { originalPath: "/content/one", reason: "OCCUPIED" } ],
      purgeable: false,
      purgeVetoes: [ { vetoer: "RetentionVeto", path: "/Archive/ab/one", reason: "Too recent" } ],
    };
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, blocked));
    const answer = await fetchArchiveEntry(doFetch, "/Archive/ab/one");
    expect(answer.restorable).toBe(false);
    expect(answer.restoreConflicts[0].reason).toBe("OCCUPIED");
    expect(answer.purgeVetoes[0].reason).toBe("Too recent");
  });

  it("rejects when the entry cannot be read", async () => {
    const doFetch = vi.fn().mockResolvedValue(new Response("", { status: 404 }));
    await expect(fetchArchiveEntry(doFetch, "/Archive/ab/one")).rejects.toThrow("404");
  });

  it("rejects a path that is not an entry at all", async () => {
    // A prefix-tree bucket shares the archive's resource type and answers with something else
    const doFetch = vi.fn().mockResolvedValue(jsonResponse(200, { "jcr:primaryType": "del:Archive" }));
    await expect(fetchArchiveEntry(doFetch, "/Archive/ab")).rejects.toThrow("not an archive entry");
  });
});

describe("the console route and the repository path", () => {
  it("routes to an entry's console page from its repository path", () => {
    expect(entryRoute("/Archive/one")).toBe(`${ARCHIVE_ROUTE}/one`);
    expect(entryRoute("/Archive/ab/cd/ef/one")).toBe(`${ARCHIVE_ROUTE}/one`);
  });

  it("converts a console route back to the path the endpoints answer on", () => {
    expect(entryResourcePath(`${ARCHIVE_ROUTE}/one`)).toBe("/Archive/one");
  });

  it("refuses a route that does not name exactly one entry", () => {
    // The browse page itself, and anything deeper than one entry, address no entry at all
    expect(entryResourcePath(ARCHIVE_ROUTE)).toBeNull();
    expect(entryResourcePath(`${ARCHIVE_ROUTE}/one/deeper`)).toBeNull();
    expect(entryResourcePath("/somewhere/else")).toBeNull();
  });

  it("tolerates a trailing slash on either side", () => {
    expect(entryRoute("/Archive/one/")).toBe(`${ARCHIVE_ROUTE}/one`);
    expect(entryResourcePath(`${ARCHIVE_ROUTE}/one/`)).toBeNull();
  });
});
