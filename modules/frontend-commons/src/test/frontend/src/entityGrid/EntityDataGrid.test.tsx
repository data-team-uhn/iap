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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

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

function mockPage(rows: Record<string, unknown>[]) {
  const page = {
    rows,
    offset: 0,
    limit: 5,
    returnedrows: rows.length,
    totalrows: rows.length,
    totalIsApproximate: false,
  };
  const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
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

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
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
    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.searchParams.getAll("fieldName")).toEqual(["jcr:createdBy"]);
    expect(url.searchParams.getAll("fieldValue")).toEqual(["@me"]);
    expect(url.searchParams.get("childType")).toBe("sub:Review");
    expect(url.searchParams.getAll("childFieldName")).toEqual(["reviewer"]);
  });

  it("shows an error when the server rejects the request", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: RequestInfo | URL) => Promise<Response>>(
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
    const user = userEvent.setup();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    await user.click(screen.getAllByRole("button", { name: /search/i })[0]);
    // Set the value atomically: typing keystroke by keystroke races the debounced re-render
    fireEvent.change(await screen.findByPlaceholderText("Search…"), { target: { value: "cardiac" } });

    await waitFor(() => {
      const lastUrl = new URL(String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]), "http://localhost");
      // Terms are turned into prefix matches: the JCR full text search only matches whole words
      expect(lastUrl.searchParams.get("filter")).toBe("cardiac*");
      // A new search always starts back on the first page
      expect(lastUrl.searchParams.get("offset")).toBe("0");
    });

    // With a search active, an empty result reads as "nothing matched", not "nothing exists"
    expect(await screen.findByText("No results found")).toBeInTheDocument();
    expect(screen.queryByText("Nothing to show")).toBeNull();

    // Clearing the search restores the plain empty message
    fireEvent.change(screen.getByPlaceholderText("Search…"), { target: { value: "" } });
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
      const lastUrl = new URL(String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]), "http://localhost");
      expect(lastUrl.searchParams.getAll("fieldName")).toEqual(["title"]);
      expect(lastUrl.searchParams.getAll("fieldComparator")).toEqual(["ILIKE"]);
      expect(lastUrl.searchParams.getAll("fieldValue")).toEqual(["%card%"]);
    });
    // An active column filter also counts as "searching" for the empty-state message
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
      const lastUrl = new URL(String(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0]), "http://localhost");
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
});
