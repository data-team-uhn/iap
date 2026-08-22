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
import { MemoryRouter } from "react-router";

import { ArchiveBrowser } from "@iap/deletion/ArchiveBrowser";
import { appTheme } from "@iap/frontend-commons/appTheme";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

const entry = (name: string, extra: Record<string, unknown> = {}) => ({
  path: `/Archive/ab/cd/ef/${name}`,
  shortPath: `/Archive/${name}`,
  requestedPath: `/content/${name}`,
  deletedBy: "alice",
  created: "2026-08-14T00:00:00.000+00:00",
  originalPaths: [ `/content/${name}` ],
  itemCount: 1,
  ...extra,
});

const page = (rows: unknown[], extra: Record<string, unknown> = {}) => ({
  rows,
  offset: 0,
  limit: 25,
  returnedrows: rows.length,
  totalrows: rows.length,
  totalIsApproximate: false,
  sortBy: "jcr:created",
  descending: true,
  ...extra,
});

// Routes the listing and the actions to different answers, and records every URL asked for.
//
// The session endpoint is answered as a live session because `useAuthenticatedFetch` reads a 500 as a
// possibly-expired session and asks: without that answer, every 500 in these tests would be reported
// as a sign-in problem instead of as the failure being exercised.
const server = (options: {
  listing?: () => Response;
  action?: () => Response;
} = {}) => {
  const calls: string[] = [];
  const mock = vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) => {
    const asked = String(url);
    if (asked.includes("sessionInfo")) {
      return Promise.resolve(jsonResponse(200, { userID: "admin" }));
    }
    calls.push(asked);
    if (asked.includes(".entries.json")) {
      return Promise.resolve(options.listing?.() ?? jsonResponse(200, page([ entry("one") ])));
    }
    return Promise.resolve(
      options.action?.() ?? jsonResponse(200, { status: "restored", restored: [ "/content/one" ] }));
  });
  return { calls, mock };
};

// Rendered by the application shell's router, and its rows link through it to each entry.
const browser = () => render(
  <MemoryRouter>
    <ThemeProvider theme={appTheme} defaultMode="light">
      <ArchiveBrowser />
    </ThemeProvider>
  </MemoryRouter>
);

const listings = (calls: string[]) => calls.filter(url => url.includes(".entries.json"));

describe("ArchiveBrowser", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("lists what was deleted, by whom, and how much went with it", async () => {
    server({ listing: () => jsonResponse(200, page([ entry("one", { itemCount: 4 }) ])) });
    browser();

    expect(await screen.findByText("/content/one")).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
  });

  it("says the archive is empty rather than showing a bare table", async () => {
    server({ listing: () => jsonResponse(200, page([])) });
    browser();

    expect(await screen.findByText("Nothing has been archived yet.")).toBeInTheDocument();
  });

  it("says so differently when a filter is what emptied it", async () => {
    server({ listing: () => jsonResponse(200, page([])) });
    browser();
    await screen.findByText("Nothing has been archived yet.");

    await userEvent.type(screen.getByLabelText("Filter by path or user"), "zzz");

    expect(await screen.findByText("No archived deletions match that filter.")).toBeInTheDocument();
  });

  it("reports a listing that could not be read", async () => {
    server({ listing: () => new Response("", { status: 500 }) });
    browser();

    expect(await screen.findByText(/could not be listed/)).toBeInTheDocument();
  });

  it("shows an entry with no recorded date rather than dropping it", async () => {
    server({ listing: () => jsonResponse(200, page([ entry("undated", { created: undefined }) ])) });
    browser();

    expect(await screen.findByText("/content/undated")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("asks the server again once typing stops, and only once", async () => {
    const { calls } = server();
    browser();
    await screen.findByText("/content/one");
    const before = listings(calls).length;

    await userEvent.type(screen.getByLabelText("Filter by path or user"), "alice");

    // One request for the whole word, not one per keystroke
    await waitFor(() => { expect(listings(calls).length).toBe(before + 1); });
    expect(listings(calls).at(-1)).toContain("filter=alice");
  });

  it("orders on the column that was clicked", async () => {
    const { calls } = server();
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("button", { name: "Deleted by" }));

    await waitFor(() => { expect(listings(calls).at(-1)).toContain("sortBy=deletedBy"); });
  });

  it("reverses the order when the same column is clicked again", async () => {
    const { calls } = server();
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("button", { name: "Deleted at" }));

    await waitFor(() => { expect(listings(calls).at(-1)).toContain("descending=false"); });
  });

  it("asks for the next page when the pagination is advanced", async () => {
    const { calls } = server({
      listing: () => jsonResponse(200, page([ entry("one") ], { totalrows: 100 })),
    });
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("button", { name: /next page/i }));

    // That the next page was asked for, rather than that it was asked for last: the filter debounce
    // set going on mount fires 300ms in and returns to the first page, so under a slow enough run
    // (coverage instrumentation is enough) a second listing lands after this one and the reader is
    // back where they started. Harmless in a browser, where nobody pages within 300ms of a load.
    await waitFor(() => {
      expect(listings(calls).some(url => url.includes("offset=25"))).toBe(true);
    });
  });

  it("asks for a different page size when one is chosen", async () => {
    const { calls } = server({
      listing: () => jsonResponse(200, page([ entry("one") ], { totalrows: 100 })),
    });
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("combobox", { name: /rows per page/i }));
    await userEvent.click(await screen.findByRole("option", { name: "10" }));

    // Only the size is asserted, deliberately. Choosing one also returns to the first page, but on
    // the first page that cannot be told from staying put, and getting somewhere else first would
    // mean racing the mount's own debounce — which is what makes the test above timing-bound.
    await waitFor(() => { expect(listings(calls).at(-1)).toContain("limit=10"); });
  });

  it("restores an entry and says what came back", async () => {
    const { calls } = server({
      action: () => jsonResponse(200, { status: "restored", restored: [ "/content/one", "/content/two" ] }),
    });
    browser();
    await screen.findByText("/content/one");
    const before = listings(calls).length;

    await userEvent.click(screen.getByRole("button", { name: "Restore" }));

    expect(await screen.findByText(/Restored 2 items/)).toBeInTheDocument();
    expect(calls).toContain("/Archive/ab/cd/ef/one.restore.json");
    // The row is gone now, so the table is read again
    await waitFor(() => { expect(listings(calls).length).toBeGreaterThan(before); });
  });

  it("leaves the table alone when a restore is refused", async () => {
    const { calls } = server({
      action: () => jsonResponse(409, {
        status: "conflict", conflicts: [ { originalPath: "/content/one", reason: "OCCUPIED" } ],
      }),
    });
    browser();
    await screen.findByText("/content/one");
    const before = listings(calls).length;

    await userEvent.click(screen.getByRole("button", { name: "Restore" }));

    expect(await screen.findByText(/OCCUPIED/)).toBeInTheDocument();
    // Nothing changed, so there is nothing to re-read
    expect(listings(calls).length).toBe(before);
  });

  it("asks before purging, because a purge cannot be undone", async () => {
    const { calls } = server();
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("button", { name: "Purge" }));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/cannot be undone/)).toBeInTheDocument();
    // Nothing has been sent yet
    expect(calls.filter(url => !url.includes(".entries.json"))).toHaveLength(0);
  });

  it("sends nothing when the purge is called off", async () => {
    const { calls } = server();
    browser();
    await screen.findByText("/content/one");
    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    const dialog = await screen.findByRole("dialog");

    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
    expect(calls.filter(url => !url.includes(".entries.json"))).toHaveLength(0);
  });

  it("purges once confirmed", async () => {
    const { calls } = server({ action: () => jsonResponse(200, { status: "deleted" }) });
    browser();
    await screen.findByText("/content/one");
    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    const dialog = await screen.findByRole("dialog");

    await userEvent.click(within(dialog).getByRole("button", { name: "Purge" }));

    expect(await screen.findByText(/permanently removed/)).toBeInTheDocument();
    expect(calls).toContain("/Archive/ab/cd/ef/one");
  });

  it("reports a guard's refusal to purge", async () => {
    server({
      action: () => jsonResponse(409, { status: "vetoed", "status.message": "Archived too recently" }),
    });
    browser();
    await screen.findByText("/content/one");
    await userEvent.click(screen.getByRole("button", { name: "Purge" }));
    await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Purge" }));

    expect(await screen.findByText("Archived too recently")).toBeInTheDocument();
  });

  it("reports a request that could not be sent at all", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) =>
      String(url).includes(".entries.json")
        ? Promise.resolve(jsonResponse(200, page([ entry("one") ])))
        : Promise.reject(new Error("offline")));
    browser();
    await screen.findByText("/content/one");

    await userEvent.click(screen.getByRole("button", { name: "Restore" }));

    expect(await screen.findByText("The request could not be sent.")).toBeInTheDocument();
  });

  it("offers another go at a request that never arrived, and takes it", async () => {
    // Nothing reached the server, so nothing was decided: the same action can simply be sent again
    let attempts = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation((url: RequestInfo | URL) => {
      if (String(url).includes(".entries.json")) {
        return Promise.resolve(jsonResponse(200, page([ entry("one") ])));
      }
      attempts += 1;
      return attempts === 1
        ? Promise.reject(new Error("offline"))
        : Promise.resolve(jsonResponse(200, { status: "restored", restored: [ "/content/one" ] }));
    });
    browser();
    await screen.findByText("/content/one");
    await userEvent.click(screen.getByRole("button", { name: "Restore" }));
    await screen.findByText("The request could not be sent.");

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText(/Restored 1 item/)).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it("lets a message be dismissed", async () => {
    server();
    browser();
    await screen.findByText("/content/one");
    await userEvent.click(screen.getByRole("button", { name: "Restore" }));
    await screen.findByText(/Restored 1 item/);

    await userEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    await waitFor(() => { expect(screen.queryByText(/Restored 1 item/)).not.toBeInTheDocument(); });
  });

  it("does nothing with a listing that arrives after the page has gone", async () => {
    // Superseded and unmounted are the same thing to the response that lands afterwards
    let answer!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(resolve => { answer = resolve; }));
    const { unmount } = browser();

    unmount();
    await act(async () => {
      answer(jsonResponse(200, page([ entry("one") ])));
      await Promise.resolve();
    });

    expect(screen.queryByText("/content/one")).not.toBeInTheDocument();
  });

  it("links each row through to the entry, where the preflight lives", async () => {
    server({ listing: () => jsonResponse(200, page([ entry("one") ])) });
    browser();

    const link = await screen.findByRole("link", { name: "/content/one" });
    // The bucket path is storage; a reader is only ever shown the short address
    expect(link).toHaveAttribute("href", "/admin/archive/one");
  });

});
