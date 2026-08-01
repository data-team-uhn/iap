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

import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

// Flushes React work left pending by promises that resolved outside an act() boundary (the
// mocked fetch resolves in a microtask). Without this, a re-render triggered between two
// awaited queries — like the empty-overlay label swap after a filtered refetch — stays
// unflushed for as long as a findBy* polls, only to appear right as it gives up.
const flushRender = () => act(() => Promise.resolve());

const TEST_TYPE = "test/GridEntity";

registerEntityType(TEST_TYPE, {
  homepage: "/GridEntities",
  columns: [
    { field: "title", headerName: "Title", flex: 1 },
    { field: "status", headerName: "Status" },
    { field: "jcr:lastModified", headerName: "Modified", type: "dateTime" },
  ],
  defaultSort: { field: "title", sort: "desc" },
  rowLink: row => String(row["@path"]),
});

// A minimal type: no default sort, no row links, and a column sorted server-side through a
// different property than its field
const PLAIN_TYPE = "test/PlainEntity";
registerEntityType(PLAIN_TYPE, {
  homepage: "/PlainEntities",
  columns: [
    { field: "title", headerName: "Title" },
    { field: "modified", headerName: "Modified", sortProperty: "jcr:lastModified" },
  ],
});

function mockPage(rows: Record<string, unknown>[]) {
  const page = {
    rows,
    offset: 0,
    limit: 5,
    returnedrows: rows.length,
    totalrows: rows.length,
    totalIsApproximate: false,
  };
  const fetchMock = vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
    { ok: true, json: () => Promise.resolve(page) } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("EntityDataGrid", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.clear();
  });

  it("lists the fetched entities using the registered columns and sorting", async () => {
    const fetchMock = mockPage([
      { "@path": "/GridEntities/e1", title: "First entity", status: "draft" },
      { "@path": "/GridEntities/e2", title: "Second entity", status: "approved" },
    ]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("First entity")).toBeInTheDocument();
    expect(screen.getByText("Second entity")).toBeInTheDocument();
    expect(screen.getByText("approved")).toBeInTheDocument();

    const url = new URL(fetchMock.mock.calls[0][0], "http://localhost");
    expect(url.pathname).toBe("/GridEntities.paginate.json");
    expect(url.searchParams.get("offset")).toBe("0");
    expect(url.searchParams.get("limit")).toBe("5");
    expect(url.searchParams.get("sortBy")).toBe("title");
    expect(url.searchParams.get("descending")).toBe("true");
  });

  it("forwards the fixed filters to the pagination servlet", async () => {
    const fetchMock = mockPage([]);

    render(
      <EntityDataGrid
        entityType={TEST_TYPE}
        filters={[{ name: "jcr:createdBy", value: "@me" }]}
        childFilter={{ type: "sub:Review", filters: [{ name: "reviewer", value: "@me" }] }}
        emptyMessage="No entities yet"
        disableVirtualization
      />, { wrapper: MemoryRouter }
    );

    expect(await screen.findByText("No entities yet")).toBeInTheDocument();
    const url = new URL(fetchMock.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.getAll("fieldName")).toEqual(["jcr:createdBy"]);
    expect(url.searchParams.getAll("fieldValue")).toEqual(["@me"]);
    expect(url.searchParams.get("childType")).toBe("sub:Review");
    expect(url.searchParams.getAll("childFieldName")).toEqual(["reviewer"]);
  });

  it("shows an error when the server rejects the request", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.resolve({ ok: false, status: 500 } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Failed to list /GridEntities: 500")).toBeInTheDocument();
  });

  it("shows an error for an unregistered entity type", () => {
    mockPage([]);

    render(<EntityDataGrid entityType="test/Unregistered" disableVirtualization />, { wrapper: MemoryRouter });

    expect(screen.getByText(/Unknown entity type/)).toBeInTheDocument();
  });

  it("navigates to the row's page when a row is clicked", async () => {
    const user = userEvent.setup();
    mockPage([{ "@path": "/GridEntities/e1", title: "First entity", status: "draft" }]);

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />} />
          <Route path="/GridEntities/e1" element={<div>Entity page</div>} />
        </Routes>
      </MemoryRouter>
    );

    await user.click(await screen.findByText("First entity"));

    expect(await screen.findByText("Entity page")).toBeInTheDocument();
  });

  it("routes the toolbar quick filter to the server-side full text search", async () => {
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // The search box is always visible, no trigger to expand it first.
    // Set the value atomically: typing keystroke by keystroke races the debounced re-render
    fireEvent.change(await screen.findByPlaceholderText("Search…"), { target: { value: "cardiac" } });

    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      // Terms are turned into prefix matches: the JCR full text search only matches whole words
      expect(lastUrl.searchParams.get("filter")).toBe("cardiac*");
      // A new search always starts back on the first page
      expect(lastUrl.searchParams.get("offset")).toBe("0");
    });

    // With a search active, an empty result reads as "nothing matched", not "nothing exists"
    await flushRender();
    expect(await screen.findByText("No results found")).toBeInTheDocument();
    expect(screen.queryByText("Nothing to show")).toBeNull();

    // A term the user already ended with a wildcard is passed through unchanged
    fireEvent.change(screen.getByPlaceholderText("Search…"), { target: { value: "exact* card" } });
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("filter")).toBe("exact* card*");
    });

    // Clearing the search restores the plain empty message
    fireEvent.change(screen.getByPlaceholderText("Search…"), { target: { value: "" } });
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("filter")).toBeNull();
    });
    await flushRender();
    expect(await screen.findByText("Nothing to show")).toBeInTheDocument();
  });

  it("routes column filters from the filter panel to server-side property filters", async () => {
    const user = userEvent.setup();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // Open the filter panel; it starts with one condition on the first column (Title), using the
    // first offered operator (contains)
    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    // Set the value atomically: typing keystroke by keystroke races the debounced re-render
    fireEvent.change(await screen.findByPlaceholderText("Filter value"), { target: { value: "card" } });

    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.getAll("fieldName")).toEqual(["title"]);
      expect(lastUrl.searchParams.getAll("fieldComparator")).toEqual(["ILIKE"]);
      expect(lastUrl.searchParams.getAll("fieldValue")).toEqual(["%card%"]);
    });
    // An active column filter also counts as "searching" for the empty-state message
    await flushRender();
    expect(await screen.findByText("No results found")).toBeInTheDocument();
  });

  it("expands a day picked in the filter panel into boundary conditions", async () => {
    const user = userEvent.setup();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // Open the filter panel and switch the condition to the date column ("is" is its default
    // operator); the grid's date input turns the picked day into a Date object
    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("combobox", { name: "Column" }));
    await user.click(await screen.findByRole("option", { name: "Modified" }));
    // Native date inputs are set programmatically; typing into them is unreliable in jsdom
    fireEvent.change(screen.getByLabelText("Value"), { target: { value: "2026-07-25" } });

    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.getAll("fieldName")).toEqual(["jcr:lastModified", "jcr:lastModified"]);
      expect(lastUrl.searchParams.getAll("fieldComparator")).toEqual([">=", "<"]);
      // The picked day's boundaries, in the user's own timezone
      expect(lastUrl.searchParams.getAll("fieldValue")).toEqual([
        new Date("2026-07-25T00:00:00").toISOString(),
        new Date("2026-07-26T00:00:00").toISOString(),
      ]);
    });
  });

  it("restores the column selection remembered for the entity type", async () => {
    window.localStorage.setItem(`iap.entityGrid.${TEST_TYPE}.columns`, JSON.stringify({ status: false }));
    mockPage([{ "@path": "/GridEntities/e1", title: "First entity", status: "draft" }]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("First entity")).toBeInTheDocument();
    // MUI X v9 header cells carry no accessible role in jsdom, so headers are checked by text
    expect(screen.getByText("Title")).toBeInTheDocument();
    expect(screen.queryByText("Status")).toBeNull();
    expect(screen.queryByText("draft")).toBeNull();
  });

  it("remembers column selection changes, per entity type", async () => {
    const user = userEvent.setup();
    mockPage([{ "@path": "/GridEntities/e1", title: "First entity", status: "draft" }]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("First entity");

    await user.click(screen.getAllByRole("button", { name: /columns/i })[0]);
    await user.click(await screen.findByRole("checkbox", { name: "Status" }));

    await waitFor(() => {
      expect(JSON.parse(window.localStorage.getItem(`iap.entityGrid.${TEST_TYPE}.columns`) ?? "{}"))
        .toEqual({ status: false });
    });
    expect(screen.queryByText("draft")).toBeNull();
  });

  it("shows every column when the remembered selection is unreadable", async () => {
    // Corrupted storage and valid-JSON-but-not-an-object both fall back to showing everything
    window.localStorage.setItem(`iap.entityGrid.${TEST_TYPE}.columns`, "{corrupted");
    mockPage([{ "@path": "/GridEntities/e1", title: "First entity", status: "draft" }]);
    const { unmount } = render(
      <EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    expect(await screen.findByText("draft")).toBeInTheDocument();
    unmount();

    window.localStorage.setItem(`iap.entityGrid.${TEST_TYPE}.columns`, "42");
    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    expect(await screen.findByText("draft")).toBeInTheDocument();
  });

  it("sorts server-side by a column's sortProperty, and only sorts when asked", async () => {
    const user = userEvent.setup();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={PLAIN_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // No default sort: the first request is unsorted
    const firstUrl = new URL(fetchMock.mock.calls[0][0], "http://localhost");
    expect(firstUrl.searchParams.get("sortBy")).toBeNull();
    expect(firstUrl.searchParams.get("descending")).toBeNull();

    // Sorting on the computed column asks the server to order by its sortProperty
    await user.click(screen.getByText("Modified"));
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("sortBy")).toBe("jcr:lastModified");
    });
  });

  it("leaves rows unclickable for types that declare no row links", async () => {
    const user = userEvent.setup();
    // The second row also exercises the row-id fallback for rows without a path
    mockPage([
      { "@path": "/PlainEntities/e1", title: "Plain entity" },
      { "@name": "e2", title: "Nameless entity" },
    ]);

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<EntityDataGrid entityType={PLAIN_TYPE} disableVirtualization />} />
        </Routes>
      </MemoryRouter>
    );

    // Clicking a row must not navigate anywhere: the grid is still on screen afterwards
    await user.click(await screen.findByText("Plain entity"));
    expect(screen.getByText("Plain entity")).toBeInTheDocument();
  });

  it("stays put when a row's link resolves to nothing", async () => {
    const user = userEvent.setup();
    const LINKLESS_TYPE = "test/LinklessEntity";
    registerEntityType(LINKLESS_TYPE, {
      homepage: "/GridEntities",
      columns: [{ field: "title", headerName: "Title" }],
      // A link callback that can decline individual rows
      rowLink: () => undefined,
    });
    mockPage([{ "@path": "/GridEntities/e1", title: "Unlinked entity" }]);

    render(<EntityDataGrid entityType={LINKLESS_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    await user.click(await screen.findByText("Unlinked entity"));
    expect(screen.getByText("Unlinked entity")).toBeInTheDocument();
  });

  it("stringifies non-Error fetch rejections into the error message", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors
      () => Promise.reject("catastrophe")));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("catastrophe")).toBeInTheDocument();
  });

  it("keeps the next page reachable when the server's total is approximate", async () => {
    const rows = Array.from({ length: 5 }, (unused, index) => (
      { "@path": `/GridEntities/e${index}`, "title": `Entity ${index}` }));
    const page = {
      rows, offset: 0, limit: 5, returnedrows: 5,
      // A lower bound: the server counted 8 matches and stopped there
      totalrows: 8, totalIsApproximate: true,
    };
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(page) } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Entity 0");

    // With an exact total this button could be disabled; the approximate flag keeps it live
    expect(screen.getByRole("button", { name: /next page/i })).toBeEnabled();
    // The servlet's counted lower bound shows through the grid's own estimate wording
    expect(screen.getByText(/1–5 of around 8/)).toBeInTheDocument();
  });

  it("recovers from a fetch error through the retry button, keeping its controls", async () => {
    let failing = true;
    const page = { rows: [], offset: 0, limit: 5, returnedrows: 0, totalrows: 0, totalIsApproximate: false };
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => failing
      ? Promise.reject(new Error("Failed to list /GridEntities: 500 — Query parse error"))
      : Promise.resolve({ ok: true, json: () => Promise.resolve(page) } as unknown as Response)));
    const user = userEvent.setup();

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // The error is shown with its detail, but the grid and its controls stay on screen
    expect(await screen.findByText(/Query parse error/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search…")).toBeInTheDocument();

    failing = false;
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Nothing to show")).toBeInTheDocument();
    expect(screen.queryByText(/Query parse error/)).toBeNull();
  });

  it("ignores failures arriving after the grid is gone", async () => {
    const settlers: ((reason: unknown) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((unused, reject) => {
      settlers.push(reject);
    })));

    const { unmount } = render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />,
      { wrapper: MemoryRouter });
    await waitFor(() => {
      expect(settlers.length).toBe(1);
    });
    unmount();

    // Rejecting now must not touch the unmounted grid's state (React would warn loudly)
    settlers[0](new Error("too late"));
    await flushRender();
  });

  it("shows the newest request's result when an older response arrives late", async () => {
    const settlers: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => {
      settlers.push(resolve);
    })));
    const pageWith = (title: string) => ({
      ok: true,
      json: () => Promise.resolve({
        rows: [{ "@path": `/GridEntities/${title}`, title }],
        offset: 0, limit: 5, returnedrows: 1, totalrows: 1, totalIsApproximate: false,
      }),
    } as unknown as Response);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    // A second request while the first is still in flight: searching re-fetches
    fireEvent.change(await screen.findByPlaceholderText("Search…"), { target: { value: "new" } });
    await waitFor(() => {
      expect(settlers.length).toBeGreaterThan(1);
    });

    // The newer response lands first; the stale one afterwards must not clobber it
    settlers[settlers.length - 1](pageWith("Newest entity"));
    await flushRender();
    settlers[0](pageWith("Stale entity"));
    await flushRender();

    expect(screen.getByText("Newest entity")).toBeInTheDocument();
    expect(screen.queryByText("Stale entity")).toBeNull();
  });
});
