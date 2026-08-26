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

import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";

import { ArchiveEntryView } from "@iap/deletion/ArchiveEntryView";
import { appTheme } from "@iap/frontend-commons/appTheme";

// The view is opened the way a reader reaches it: at its page of the administration console.
const ROUTE = "/admin/archive/one";

/** The entry's short repository address, which is what the page asks the endpoints about. */
const ENTRY = "/Archive/by-id/one";

/** Where the entry is really stored, which is what the endpoint reports back as its path. */
const STORED = "/Archive/ab/cd/ef/one";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

const detail = (extra: Record<string, unknown> = {}) => ({
  path: STORED,
  shortPath: ENTRY,
  requestedPath: "/content/one",
  deletedBy: "alice",
  created: "2026-08-14T00:00:00.000+00:00",
  originalPaths: [ "/content/one" ],
  itemCount: 1,
  restorable: true,
  restoreConflicts: [],
  purgeable: true,
  purgeVetoes: [],
  ...extra,
});

// The session endpoint is answered as live for the reason recorded in ArchiveBrowser.test.tsx: a 500
// is otherwise read as an expired session rather than as the failure under test.
const server = (options: { entry?: () => Response; action?: () => Response } = {}) => {
  const calls: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) => {
    const asked = String(url);
    if (asked.includes("sessionInfo")) {
      return Promise.resolve(jsonResponse(200, { userID: "admin" }));
    }
    calls.push(asked);
    if (asked.endsWith(".entry.json")) {
      return Promise.resolve(options.entry?.() ?? jsonResponse(200, detail()));
    }
    return Promise.resolve(options.action?.() ?? jsonResponse(200, { status: "restored", restored: [ "/content/one" ] }));
  });
  return calls;
};

// Rendered the way the shell renders it: at the console route for one entry, with the listing as a
// sibling route so that navigating away after the entry stops existing is observable.
const view = (route: string = ROUTE) => render(
  <MemoryRouter initialEntries={[ route ]}>
    <ThemeProvider theme={appTheme} defaultMode="light">
      <Routes>
        <Route path="/admin/archive" element={<div>the archive listing</div>} />
        <Route path="/admin/archive/*" element={<ArchiveEntryView />} />
      </Routes>
    </ThemeProvider>
  </MemoryRouter>
);

describe("ArchiveEntryView", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks the entry at its own path what would happen to it", async () => {
    const calls = server();
    view();
    await screen.findByRole("heading", { name: "/content/one" });
    // The console route is not the entry's path, so the page converts before asking
    expect(calls[0]).toBe(`${ENTRY}.entry.json`);
  });

  it("reports a route that names no entry rather than asking about one", async () => {
    const calls = server();
    view("/admin/archive/one/deeper");

    expect(await screen.findByText("That is not an archive entry.")).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it("says who deleted it and when", async () => {
    server();
    view();
    expect(await screen.findByText(/Deleted by alice/)).toBeInTheDocument();
  });

  it("shows each archived item as restorable when nothing is in the way", async () => {
    server();
    view();
    expect(await screen.findByText("Would be restored here")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore everything" })).toBeEnabled();
  });

  it("explains, per item, why a restore is impossible", async () => {
    server({
      entry: () => jsonResponse(200, detail({
        restorable: false,
        restoreConflicts: [ { originalPath: "/content/one", reason: "OCCUPIED" } ],
      })),
    });
    view();

    // The reason is put in words rather than left as the wire constant
    expect(await screen.findByText(/something else is at that path now/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore everything" })).toBeDisabled();
  });

  it("falls back to the bare reason if the server invents a new one", async () => {
    server({
      entry: () => jsonResponse(200, detail({
        restorable: false,
        restoreConflicts: [ { originalPath: "/content/one", reason: "SOMETHING_NEW" } ],
      })),
    });
    view();

    // Shown as-is rather than swallowed, so a reason this page has not learned yet still reaches the reader
    expect(await screen.findByText("Cannot be restored: SOMETHING_NEW")).toBeInTheDocument();
  });

  it("says which guard refuses a purge, and does not offer it", async () => {
    server({
      entry: () => jsonResponse(200, detail({
        purgeable: false,
        purgeVetoes: [ { vetoer: "RetentionVeto", path: ENTRY, reason: "Archived less than 30 days ago" } ],
      })),
    });
    view();

    expect(await screen.findByText("Archived less than 30 days ago")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Purge" })).toBeDisabled();
  });

  it("leaves the archive listing once the entry has been restored", async () => {
    // The entry no longer exists, so staying on its page would show a 404 of our own making
    server();
    view();
    await screen.findByRole("heading", { name: "/content/one" });

    await userEvent.click(screen.getByRole("button", { name: "Restore everything" }));

    expect(await screen.findByText("the archive listing")).toBeInTheDocument();
  });

  it("asks before purging, and sends nothing if called off", async () => {
    const calls = server();
    view();
    await screen.findByRole("heading", { name: "/content/one" });

    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/cannot be undone/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
    expect(calls.filter(url => !url.endsWith(".entry.json"))).toHaveLength(0);
  });

  it("leaves the listing once the entry has been purged", async () => {
    const calls = server({ action: () => jsonResponse(200, { status: "deleted" }) });
    view();
    await screen.findByRole("heading", { name: "/content/one" });

    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Purge" }));

    expect(await screen.findByText("the archive listing")).toBeInTheDocument();
    // Acted on where the entry actually is, which the endpoint just reported, rather than on the
    // short address the route was built from: one fewer thing to be wrong about at the moment of
    // destroying something. The read above still goes through the short address.
    expect(calls).toContain(STORED);
  });

  it("re-reads the preflight when an action is refused after all", async () => {
    // The preflight said this would work and it did not, so what is on screen is now a claim that
    // has just been disproved
    const calls = server({
      action: () => jsonResponse(409, {
        status: "conflict", conflicts: [ { originalPath: "/content/one", reason: "OCCUPIED" } ],
      }),
    });
    view();
    await screen.findByRole("heading", { name: "/content/one" });
    const before = calls.filter(url => url.endsWith(".entry.json")).length;

    await userEvent.click(screen.getByRole("button", { name: "Restore everything" }));

    expect(await screen.findByText(/OCCUPIED/)).toBeInTheDocument();
    await waitFor(() => {
      expect(calls.filter(url => url.endsWith(".entry.json")).length).toBeGreaterThan(before);
    });
  });

  it("reports a request that could not be sent", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) =>
      String(url).endsWith(".entry.json")
        ? Promise.resolve(jsonResponse(200, detail()))
        : Promise.reject(new Error("offline")));
    view();
    await screen.findByRole("heading", { name: "/content/one" });

    await userEvent.click(screen.getByRole("button", { name: "Restore everything" }));

    expect(await screen.findByText("The request could not be sent.")).toBeInTheDocument();
  });

  it("offers another go at a request that never arrived, and takes it", async () => {
    // Nothing reached the server, so nothing was decided and the same action can simply be sent
    // again — this time it lands, and the entry stops existing, so the page leaves for the listing
    let attempts = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) => {
      if (String(url).endsWith(".entry.json")) {
        return Promise.resolve(jsonResponse(200, detail()));
      }
      attempts += 1;
      return attempts === 1
        ? Promise.reject(new Error("offline"))
        : Promise.resolve(jsonResponse(200, { status: "restored", restored: [ "/content/one" ] }));
    });
    view();
    await screen.findByRole("heading", { name: "/content/one" });
    await userEvent.click(screen.getByRole("button", { name: "Restore everything" }));
    await screen.findByText("The request could not be sent.");

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("the archive listing")).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it("says so when the path is not an archive entry", async () => {
    server({ entry: () => jsonResponse(200, { "jcr:primaryType": "del:Archive" }) });
    view();

    expect(await screen.findByText("That is not an archive entry.")).toBeInTheDocument();
  });

  it("lets a message be dismissed", async () => {
    server({
      action: () => jsonResponse(409, { status: "vetoed", "status.message": "Too recent" }),
    });
    view();
    await screen.findByRole("heading", { name: "/content/one" });
    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Purge" }));
    await screen.findByText("Too recent");

    await userEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    await waitFor(() => { expect(screen.queryByText("Too recent")).not.toBeInTheDocument(); });
  });

  it("does nothing with an answer that arrives after the page has gone", async () => {
    let answer!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(resolve => { answer = resolve; }));
    const { unmount } = view();

    unmount();
    await act(async () => {
      answer(jsonResponse(200, detail()));
      await Promise.resolve();
    });

    expect(screen.queryByRole("heading", { name: "/content/one" })).not.toBeInTheDocument();
  });
});
