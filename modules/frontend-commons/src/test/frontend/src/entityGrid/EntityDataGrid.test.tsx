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

import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";

import EntityDataGrid from "@iap/frontend-commons/entityGrid/EntityDataGrid";
import { registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

// Flushes the React work that promises resolved outside act() left pending, since the mocked fetch
// resolves in a microtask. Otherwise a render between two awaited queries can wait until a findBy*
// gives up.
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

// A status rendered as a component, such as a tag chip or an action button
const ELEMENT_TYPE = "test/ElementEntity";
registerEntityType(ELEMENT_TYPE, {
  homepage: "/ElementEntities",
  columns: [
    { field: "title", headerName: "Title" },
    { field: "status", headerName: "Status", renderCell: params => <em>{String((params.row as Record<string, unknown>).status)}</em> },
  ],
});

// A type with every kind of column content the composed card handles. The "nested" column cannot
// be sorted.
const CARD_TYPE = "test/CardEntity";
registerEntityType(CARD_TYPE, {
  homepage: "/CardEntities",
  columns: [
    { field: "title", headerName: "Title" },
    { field: "status", headerName: "Status", renderCell: params => <em>{String((params.row as Record<string, unknown>).status)}</em> },
    { field: "count", headerName: "Count" },
    { field: "flag", headerName: "Flag" },
    { field: "when", headerName: "When", valueGetter: (value: unknown) => new Date(String(value)) },
    { field: "nested", headerName: "Nested", sortable: false },
    { field: "extra" },
  ],
});

// A type with its own card, which leaves out the status when that column is hidden
const CUSTOM_CARD_TYPE = "test/CustomCardEntity";
registerEntityType(CUSTOM_CARD_TYPE, {
  homepage: "/CustomCards",
  columns: [{ field: "title", headerName: "Title" }, { field: "status", headerName: "Status" }],
  listItem: (row, visibleFields) => (
    <div>Custom card: {String(row.title)}{visibleFields.has("status") && ` (${String(row.status)})`}</div>
  ),
});

// A type composing its narrow-screen card declaratively, through the columns' card slots
const SLOTTED_TYPE = "test/SlottedEntity";
registerEntityType(SLOTTED_TYPE, {
  homepage: "/SlottedEntities",
  columns: [
    {
      field: "title",
      headerName: "Title",
      cardSlot: "title",
      cardValue: row => String(row.title ?? row["@name"]),
    },
    {
      field: "status",
      headerName: "Status",
      cardSlot: "badge",
      renderCell: params => <em>{String((params.row as Record<string, unknown>).status)}</em>,
    },
    { field: "kind", headerName: "Kind", cardSlot: "caption" },
    {
      field: "when",
      headerName: "When",
      cardSlot: "caption",
      cardValue: row => new Date(String(row.when)).toLocaleDateString(),
    },
    { field: "internal", headerName: "Internal", cardSlot: "omit" },
    { field: "note", headerName: "Note" },
  ],
});

// A type with a choice column whose options carry colors
const CHOICE_TYPE = "test/ChoiceEntity";
registerEntityType(CHOICE_TYPE, {
  homepage: "/ChoiceEntities",
  columns: [
    { field: "title", headerName: "Title" },
    {
      field: "state",
      headerName: "State",
      type: "singleSelect",
      valueOptions: [
        // Filled, since jsdom cannot compute the soft style's color-mix() and light-dark()
        { value: "open", label: "Open", color: "#1d6a3a", variant: "filled" },
        { value: "closed", label: "Closed" },
      ],
    },
  ],
});

// A choice column whose options are plain strings, with no label, color or variant
const STRING_CHOICE_TYPE = "test/StringChoiceEntity";
registerEntityType(STRING_CHOICE_TYPE, {
  homepage: "/StringChoiceEntities",
  columns: [
    { field: "title", headerName: "Title" },
    { field: "state", headerName: "State", type: "singleSelect", valueOptions: ["open", "closed"] },
  ],
});

// A type listing entities with nested rows: each entity arrives with its versions inline
const versionsOf = (row: Record<string, unknown>) => Object.values(row)
  .filter((value): value is Record<string, unknown> =>
    typeof value === "object" && value !== null && (value as Record<string, unknown>).kind === "version");
const TREE_TYPE = "test/TreeEntity";
registerEntityType(TREE_TYPE, {
  homepage: "/TreeEntities",
  columns: [
    { field: "title", headerName: "Name", flex: 2 },
    { field: "status", headerName: "Status" },
  ],
  defaultSort: { field: "title", sort: "asc" },
  children: { selectors: "1", rows: versionsOf, treeField: "title", countLabel: count => `${count} versions` },
});
const EXPANDED_TYPE = "test/ExpandedTreeEntity";
registerEntityType(EXPANDED_TYPE, {
  homepage: "/ExpandedTreeEntities",
  columns: [ { field: "status", headerName: "Status" } ],
  children: { selectors: "1", rows: versionsOf, treeField: "label", expanded: true },
});
// The same, with its card composed from card slots, and a page for each row, children included
const CARD_TREE_TYPE = "test/CardTreeEntity";
registerEntityType(CARD_TREE_TYPE, {
  homepage: "/TreeEntities",
  columns: [
    { field: "title", headerName: "Name", cardSlot: "title" },
    { field: "status", headerName: "Status", cardSlot: "badge" },
  ],
  children: { selectors: "1", rows: versionsOf, treeField: "title" },
  rowLink: row => row["@path"] as string | undefined,
});
const TREE_ROWS = [
  {
    "@path": "/TreeEntities/study",
    "title": "Clinical study",
    "status": "open",
    "v1": { "@path": "/TreeEntities/study/v1", "kind": "version", "title": "Version one", "status": "retired" },
    "v2": { "@name": "v2", "kind": "version", "status": "active" },
  },
];

// Makes MUI's useMediaQuery see a narrow viewport, switching the grid to its list mode
function fakeNarrowScreen() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("max-width"),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }));
}

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
    { ok: true, url: "", json: () => Promise.resolve(page) } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("EntityDataGrid", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.clear();
  });

  it("lists entities with their children nested under them", async () => {
    const fetchMock = mockPage(TREE_ROWS);

    render(<EntityDataGrid entityType={TREE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // The number of children, worded by countLabel, in the regular weight
    const count = await screen.findByText("(2 versions)", { exact: false });
    expect(count.closest(".MuiDataGrid-cell")).toHaveTextContent("Clinical study (2 versions)");
    expect(count).toHaveStyle({ fontWeight: 400 });
    const url = new URL(fetchMock.mock.calls[0][0], "http://localhost");
    expect(url.searchParams.get("resourceSelectors")).toBe("1");
    expect(url.searchParams.get("sortBy")).toBe("title");
    // The named column became the tree column, rather than showing twice
    expect(screen.getAllByRole("columnheader").map(header => header.textContent)).toEqual([ "Name", "Status" ]);
    // Collapsed until asked
    expect(screen.queryByText("Version one")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /see children/i }));

    expect(await screen.findByText("Version one")).toBeInTheDocument();
    // A child without a title of its own goes by its name
    expect(screen.getByText("v2")).toBeInTheDocument();
    expect(screen.getByText("active")).toBeInTheDocument();
    // The entity stands out from what is nested under it
    const cellOf = (text: string) => screen.getByText(text).closest(".MuiDataGrid-cell");
    expect(cellOf("Clinical study")).toHaveClass("entity-grid-entity");
    expect(cellOf("Version one")).not.toHaveClass("entity-grid-entity");
  });

  it("sorts by the column the tree column stands for", async () => {
    const fetchMock = mockPage(TREE_ROWS);

    render(<EntityDataGrid entityType={TREE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText(/Clinical study/);
    fireEvent.click(screen.getByRole("columnheader", { name: /Name/ }));

    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls.at(-1)?.[0] ?? "", "http://localhost");
      expect(url.searchParams.get("sortBy")).toBe("title");
      expect(url.searchParams.get("descending")).toBe("true");
    });
  });

  it("can start with every entity expanded", async () => {
    mockPage(TREE_ROWS);

    render(<EntityDataGrid entityType={EXPANDED_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("retired")).toBeInTheDocument();
    expect(screen.getByText("active")).toBeInTheDocument();
    // Without a countLabel, the bare number of children
    expect(screen.getByText("(2)", { exact: false }).closest(".MuiDataGrid-cell"))
      .toHaveTextContent("/TreeEntities/study (2)");
  });

  it("keeps one card per entity on narrow screens", async () => {
    fakeNarrowScreen();
    mockPage(TREE_ROWS);

    render(<EntityDataGrid entityType={TREE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // Its children are on its card, not cards of their own
    const card = (await screen.findByText("Clinical study")).closest("[role='row']");
    expect(screen.getByText("Version one").closest("[role='row']")).toBe(card);
    expect(screen.getAllByRole("row")).toHaveLength(1);
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

  it("lays out a cell rendering a component for an element, not for a text baseline", async () => {
    mockPage([{ "@path": "/ElementEntities/e1", title: "First entity", status: "draft" }]);

    render(<EntityDataGrid entityType={ELEMENT_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    await screen.findByText("First entity");
    expect(screen.getByText("draft").closest(".MuiDataGrid-cell")).toHaveClass("MuiDataGrid-cell--flex");
    // A column rendering plain text keeps the text layout
    expect(screen.getByText("First entity").closest(".MuiDataGrid-cell"))
      .not.toHaveClass("MuiDataGrid-cell--flex");
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

  it("lists rows it is given in the browser, without fetching them", async () => {
    const fetchMock = mockPage([]);
    const rows = [
      { "@path": "/GridEntities/a", "title": "Alpha", "status": "draft" },
      { "@path": "/GridEntities/b", "title": "Beta", "status": "active" },
    ];

    render(<EntityDataGrid entityType={TEST_TYPE} rows={rows} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Alpha")).toBeInTheDocument();
    expect(screen.getByText("Beta")).toBeInTheDocument();
    // Searched in the browser too
    fireEvent.change(screen.getByPlaceholderText("Search…"), { target: { value: "beta" } });
    await waitFor(() => expect(screen.queryByText("Alpha")).not.toBeInTheDocument());
    expect(screen.getByText("Beta")).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([ url ]) => url.includes(".paginate.json"))).toEqual([]);
  });

  it("lists from the homepage it is given rather than the registered one", async () => {
    const fetchMock = mockPage([{ "@path": "/Elsewhere/e1", title: "Borrowed entity", status: "draft" }]);

    render(
      <EntityDataGrid entityType={TEST_TYPE} homepage="/Elsewhere" disableVirtualization />,
      { wrapper: MemoryRouter }
    );

    expect(await screen.findByText("Borrowed entity")).toBeInTheDocument();
    expect(new URL(fetchMock.mock.calls[0][0], "http://localhost").pathname).toBe("/Elsewhere.paginate.json");
  });

  it("shows an error when the server rejects the request", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.resolve({ ok: false, url: "", status: 503 } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // Worded by describeRequestFailure
    expect(await screen.findByText(/The server ran into a problem.*\(HTTP 503\)/)).toBeInTheDocument();
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

    // The search box is always open.
    // The value is set in one change, since typing races the grid's debounced render
    fireEvent.change(await screen.findByPlaceholderText("Search…"), { target: { value: "cardiac" } });

    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      // Each term becomes a prefix
      expect(lastUrl.searchParams.get("filter")).toBe("cardiac*");
      // A new search starts back on the first page
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

    // The filter panel starts with one condition, on the first column (Title) with its first
    // operator (contains). The button's badge is hidden while nothing filters.
    const filterTrigger = screen.getAllByRole("button", { name: /filter/i })[0];
    expect(within(filterTrigger).getByText("0")).toHaveClass("MuiBadge-invisible");
    await user.click(filterTrigger);
    fireEvent.change(await screen.findByPlaceholderText("Filter value"), { target: { value: "card" } });

    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.getAll("fieldName")).toEqual(["title"]);
      expect(lastUrl.searchParams.getAll("fieldComparator")).toEqual(["ILIKE"]);
      expect(lastUrl.searchParams.getAll("fieldValue")).toEqual(["%card%"]);
    });
    // A column filter, as well as a search, shows the message for no results
    await flushRender();
    expect(await screen.findByText("No results found")).toBeInTheDocument();
    // The button's badge counts the condition
    expect(within(filterTrigger).getByText("1")).toBeInTheDocument();
  });

  it("expands a day picked in the filter panel into boundary conditions", async () => {
    const user = userEvent.setup();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // The condition moves to the date column, whose first operator is "is". The grid's date input
    // turns the picked day into a Date.
    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("combobox", { name: "Column" }));
    await user.click(await screen.findByRole("option", { name: "Modified" }));
    // jsdom does not type reliably into a date input, so the value is set directly
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
    // The badge counts the one condition, not the two filters it became
    expect(within(screen.getAllByRole("button", { name: /filter/i })[0]).getByText("1")).toBeInTheDocument();
  });

  it("restores the column selection remembered for the entity type", async () => {
    window.localStorage.setItem(`iap.entityGrid.${TEST_TYPE}.columns`, JSON.stringify({ status: false }));
    mockPage([{ "@path": "/GridEntities/e1", title: "First entity", status: "draft" }]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("First entity")).toBeInTheDocument();
    // MUI X header cells have no accessible role in jsdom, so the headers are found by their text
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
    // Text that is not JSON, and JSON that is not an object, both show every column
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

    // Without a default sort, the first request names none
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
    // The later rows have no path, and the last two no name either, so that their ids come from the
    // name and from their position
    mockPage([
      { "@path": "/PlainEntities/e1", title: "Plain entity" },
      { "@name": "e2", title: "Nameless entity" },
      { title: "Pathless entity" },
      { title: "Another pathless entity" },
    ]);

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<EntityDataGrid entityType={PLAIN_TYPE} disableVirtualization />} />
        </Routes>
      </MemoryRouter>
    );

    // The grid is still on screen after the click
    await user.click(await screen.findByText("Plain entity"));
    expect(screen.getByText("Plain entity")).toBeInTheDocument();
    // Both rows without a path or a name are shown, with no duplicate id to fail the grid
    expect(screen.getByText("Pathless entity")).toBeInTheDocument();
    expect(screen.getByText("Another pathless entity")).toBeInTheDocument();
  });

  it("stays put when a row's link resolves to nothing", async () => {
    const user = userEvent.setup();
    const LINKLESS_TYPE = "test/LinklessEntity";
    registerEntityType(LINKLESS_TYPE, {
      homepage: "/GridEntities",
      columns: [{ field: "title", headerName: "Title" }],
      rowLink: () => undefined,
    });
    mockPage([{ "@path": "/GridEntities/e1", title: "Unlinked entity" }]);

    render(<EntityDataGrid entityType={LINKLESS_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    await user.click(await screen.findByText("Unlinked entity"));
    expect(screen.getByText("Unlinked entity")).toBeInTheDocument();
  });

  it("stringifies non-Error fetch rejections into the error message", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.reject("catastrophe")));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText(/catastrophe/)).toBeInTheDocument();
  });

  it("switches to a generic card list on narrow screens, keeping rows tappable", async () => {
    const user = userEvent.setup();
    fakeNarrowScreen();
    mockPage([{
      "@path": "/GridEntities/e1",
      "title": "First entity",
      "status": "draft",
      "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
    }]);

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />} />
          <Route path="/GridEntities/e1" element={<div>Entity page</div>} />
        </Routes>
      </MemoryRouter>
    );

    // The composed card: the first column as the title, the others as labeled rows
    expect(await screen.findByText("First entity")).toBeInTheDocument();
    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.getByText("draft")).toBeInTheDocument();

    await user.click(screen.getByText("First entity"));
    expect(await screen.findByText("Entity page")).toBeInTheDocument();
  });

  it("renders every kind of column content in the generic card", async () => {
    fakeNarrowScreen();
    mockPage([{
      "@path": "/CardEntities/e1",
      "title": "Rich entity",
      "status": "special",
      "count": 42,
      "flag": true,
      "when": "2026-07-02T10:00:00.000Z",
      "nested": { inner: "value" },
      "extra": "loose end",
    }]);

    render(<EntityDataGrid entityType={CARD_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Rich entity")).toBeInTheDocument();
    expect(screen.getByText("special").tagName).toBe("EM");
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("true")).toBeInTheDocument();
    expect(screen.getByText(new Date("2026-07-02T10:00:00.000Z").toLocaleString(undefined, {
      year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric",
    }))).toBeInTheDocument();
    // An object cannot be shown as text, so its labeled row is left out
    expect(screen.queryByText("Nested")).toBeNull();
    // A column without a header is labeled with its field
    expect(screen.getByText("extra")).toBeInTheDocument();
    expect(screen.getByText("loose end")).toBeInTheDocument();
  });

  it("prefers a type's own card renderer in list mode", async () => {
    fakeNarrowScreen();
    mockPage([{ "@path": "/CustomCards/e1", "title": "Bespoke", "status": "open" }]);

    render(<EntityDataGrid entityType={CUSTOM_CARD_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText(/Custom card: Bespoke \(open\)/)).toBeInTheDocument();
  });

  it("hides columns the user hid from the generic card too", async () => {
    fakeNarrowScreen();
    window.localStorage.setItem(`iap.entityGrid.${TEST_TYPE}.columns`, JSON.stringify({ status: false }));
    mockPage([{ "@path": "/GridEntities/e1", "title": "First entity", "status": "draft" }]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("First entity")).toBeInTheDocument();
    expect(screen.queryByText("Status")).toBeNull();
    expect(screen.queryByText("draft")).toBeNull();
  });

  it("hands the column selection to a type's own card renderer", async () => {
    fakeNarrowScreen();
    window.localStorage.setItem(`iap.entityGrid.${CUSTOM_CARD_TYPE}.columns`, JSON.stringify({ status: false }));
    mockPage([{ "@path": "/CustomCards/e1", "title": "Bespoke", "status": "open" }]);

    render(<EntityDataGrid entityType={CUSTOM_CARD_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText(/Custom card: Bespoke/)).toBeInTheDocument();
    expect(screen.queryByText(/open/)).toBeNull();
  });

  it("puts its own leading columns before a type's own card, unless hidden or kept off cards", async () => {
    fakeNarrowScreen();
    window.localStorage.setItem(`iap.entityGrid.${CUSTOM_CARD_TYPE}.columns`, JSON.stringify({ hidden: false }));
    mockPage([{ "@path": "/CustomCards/e1", "title": "Bespoke", "status": "open" }]);
    const leading = [ "shown", "hidden", "omitted" ].map(field => ({ field,
      ...field === "omitted" ? { cardSlot: "omit" as const } : {}, renderCell: () => <span>{`Leading ${field}`}</span> }));

    render(<EntityDataGrid entityType={CUSTOM_CARD_TYPE} leadingColumns={leading} disableVirtualization />,
      { wrapper: MemoryRouter });

    expect(await screen.findByText(/Custom card: Bespoke/)).toBeInTheDocument();
    expect(screen.getByText("Leading shown")).toBeInTheDocument();
    expect(screen.queryByText("Leading hidden")).toBeNull();
    expect(screen.queryByText("Leading omitted")).toBeNull();
  });

  it("ends a card's title line with what can be done, which does not open the entity", async () => {
    fakeNarrowScreen();
    mockPage([{ "@path": "/GridEntities/e1", "title": "First entity", "status": "draft" }]);
    const opened = vi.fn();
    const actions = [ { field: "__actions__", cardSlot: "actions" as const,
      renderCell: () => <button type="button" onClick={opened}>Act</button> } ];

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/"
            element={<EntityDataGrid entityType={TEST_TYPE} extraColumns={actions} disableVirtualization />} />
          <Route path="/GridEntities/e1" element={<div>Entity page</div>} />
        </Routes>
      </MemoryRouter>
    );
    fireEvent.click(await screen.findByRole("button", { name: "Act" }));

    expect(opened).toHaveBeenCalled();
    expect(screen.queryByText("Entity page")).toBeNull();
    expect(screen.getByText("First entity")).toBeInTheDocument();
  });

  it("ends a type's own card with what can be done, unless hidden", async () => {
    fakeNarrowScreen();
    window.localStorage.setItem(`iap.entityGrid.${CUSTOM_CARD_TYPE}.columns`, JSON.stringify({ hidden: false }));
    mockPage([{ "@path": "/CustomCards/e1", "title": "Bespoke", "status": "open" }]);
    const actions = [ "shown", "hidden" ].map(field => ({ field, cardSlot: "actions" as const,
      renderCell: () => <span>{`Actions ${field}`}</span> }));

    render(<EntityDataGrid entityType={CUSTOM_CARD_TYPE} extraColumns={actions} disableVirtualization />,
      { wrapper: MemoryRouter });

    expect(await screen.findByText(/Custom card: Bespoke/)).toBeInTheDocument();
    expect(screen.getByText("Actions shown")).toBeInTheDocument();
    expect(screen.queryByText("Actions hidden")).toBeNull();
  });

  it("closes an entity's card with its children, each linked to its page where it has one", async () => {
    fakeNarrowScreen();
    mockPage(TREE_ROWS);

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<EntityDataGrid entityType={CARD_TREE_TYPE} disableVirtualization />} />
          <Route path="/TreeEntities/study/v1" element={<div>Version page</div>} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText("Clinical study")).toBeInTheDocument();
    // Each child shows its badges. One without a title shows its name, and one without a path is not linked.
    expect(screen.getByText("retired")).toBeInTheDocument();
    expect(screen.getByText("active")).toBeInTheDocument();
    expect(screen.getByText("v2").closest("a")).toBeNull();
    fireEvent.click(screen.getByRole("link", { name: "Version one" }));

    expect(await screen.findByText("Version page")).toBeInTheDocument();
  });

  it("composes the card from the columns' card slots", async () => {
    fakeNarrowScreen();
    mockPage([{
      "@path": "/SlottedEntities/e1",
      "title": "Slotted entity",
      "status": "open",
      "kind": "demo",
      "when": "2026-07-02T10:00:00.000Z",
      "internal": "secret",
      "note": "a labeled row",
    }]);

    render(<EntityDataGrid entityType={SLOTTED_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Slotted entity")).toBeInTheDocument();
    expect(screen.getByText("open").tagName).toBe("EM");
    const day = new Date("2026-07-02T10:00:00.000Z").toLocaleDateString();
    expect(screen.getByText((_, element) => element?.textContent === `demo • ${day}`)).toBeInTheDocument();
    // A column without a slot is a labeled row, and an omitted one is not shown
    expect(screen.getByText("Note")).toBeInTheDocument();
    expect(screen.getByText("a labeled row")).toBeInTheDocument();
    expect(screen.queryByText("secret")).toBeNull();
  });

  it("keeps the slotted card in step with the column selection", async () => {
    fakeNarrowScreen();
    window.localStorage.setItem(`iap.entityGrid.${SLOTTED_TYPE}.columns`,
      JSON.stringify({ status: false, kind: false }));
    mockPage([{
      "@path": "/SlottedEntities/e1",
      "title": "Slotted entity",
      "status": "open",
      "kind": "demo",
      "when": "2026-07-02T10:00:00.000Z",
    }]);

    render(<EntityDataGrid entityType={SLOTTED_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // The hidden badge and caption leave the card, and the other caption keeps its line
    expect(await screen.findByText("Slotted entity")).toBeInTheDocument();
    expect(screen.queryByText("open")).toBeNull();
    expect(screen.queryByText(/demo/)).toBeNull();
    const day = new Date("2026-07-02T10:00:00.000Z").toLocaleDateString();
    expect(screen.getByText((_, element) => element?.textContent === day)).toBeInTheDocument();
  });

  it("offers toolbar sorting in list mode, since cards have no headers to click", async () => {
    const user = userEvent.setup();
    fakeNarrowScreen();
    const fetchMock = mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    const toolbar = screen.getByRole("toolbar");
    await user.click(within(toolbar).getByRole("button", { name: "Sort" }));
    await user.click(await screen.findByRole("menuitem", { name: /Modified/ }));
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("sortBy")).toBe("jcr:lastModified");
      expect(lastUrl.searchParams.get("descending")).toBeNull();
    });

    // Picking the sorted column again reverses the order
    await user.click(within(toolbar).getByRole("button", { name: "Sort" }));
    await user.click(await screen.findByRole("menuitem", { name: /Modified/ }));
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("descending")).toBe("true");
    });

    await user.click(within(toolbar).getByRole("button", { name: "Sort" }));
    await user.keyboard("{Escape}");
    await waitFor(() => {
      expect(screen.queryByRole("menuitem", { name: /Modified/ })).toBeNull();
    });
  });

  it("keeps the sort menu out of the regular column view", async () => {
    mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    expect(within(screen.getByRole("toolbar")).queryByRole("button", { name: "Sort" })).toBeNull();
  });

  it("keeps the next page reachable when the server's total is approximate", async () => {
    const rows = Array.from({ length: 5 }, (unused, index) => (
      { "@path": `/GridEntities/e${index}`, "title": `Entity ${index}` }));
    const page = {
      rows, offset: 0, limit: 5, returnedrows: 5,
      // The server counted 8 matches and stopped there
      totalrows: 8, totalIsApproximate: true,
    };
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, url: "", json: () => Promise.resolve(page) } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Entity 0");

    expect(screen.getByRole("button", { name: /next page/i })).toBeEnabled();
    // The grid words the lower bound as an estimate
    expect(screen.getByText(/1–5 of around 8/)).toBeInTheDocument();
  });

  // Left to itself, the grid also ends on the first page, after reading and showing page 2 in the
  // new order. The test therefore counts the requests.
  it("returns to the first page when the sort order changes, in one request", async () => {
    const rows = Array.from({ length: 5 }, (unused, index) => (
      { "@path": `/GridEntities/e${index}`, "title": `Entity ${index}` }));
    const fetchMock = vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve({
      ok: true,
      url: "",
      json: () => Promise.resolve({ rows, offset: 0, limit: 5, returnedrows: 5, totalrows: 20,
        totalIsApproximate: false }),
    } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Entity 0");

    await user.click(screen.getByRole("button", { name: /next page/i }));
    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(url.searchParams.get("offset")).toBe("5");
    });

    const before = fetchMock.mock.calls.length;
    await user.click(screen.getByText("Status"));

    await waitFor(() => {
      const url = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(url.searchParams.get("sortBy")).toBe("status");
    });
    const sorted = fetchMock.mock.calls.slice(before).map(call => {
      const url = new URL(call[0], "http://localhost");
      return `${url.searchParams.get("offset")}/${url.searchParams.get("sortBy")}`;
    });
    expect(sorted).toEqual(["0/status"]);
  });

  it("reads the page once when a caller adds a column of its own", async () => {
    const fetchMock = mockPage([{ "@path": "/GridEntities/e1", "title": "First entity" }]);
    const actions = [ { field: "__actions__", renderCell: () => <button type="button">Act</button> } ];

    const { rerender } = render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />,
      { wrapper: MemoryRouter });
    await screen.findByText("First entity");
    rerender(<EntityDataGrid entityType={TEST_TYPE} extraColumns={actions} disableVirtualization />);
    await screen.findByRole("button", { name: "Act" });
    await flushRender();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("names its search box, so a reader is told what it searches", async () => {
    render(
      <EntityDataGrid entityType={TEST_TYPE} searchLabel="Search my submissions" disableVirtualization />,
      { wrapper: MemoryRouter },
    );

    expect(await screen.findByRole("searchbox", { name: "Search my submissions" })).toBeInTheDocument();
  });

  it("falls back on a plain name when the caller does not say", async () => {
    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    expect(await screen.findByRole("searchbox", { name: "Search" })).toBeInTheDocument();
  });

  it("recovers from a fetch error through the retry button, keeping its controls", async () => {
    let failing = true;
    const page = { rows: [], offset: 0, limit: 5, returnedrows: 0, totalrows: 0, totalIsApproximate: false };
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => failing
      ? Promise.reject(new Error("Failed to list /GridEntities: 500 — Query parse error"))
      : Promise.resolve({ ok: true, url: "", json: () => Promise.resolve(page) } as unknown as Response)));
    const user = userEvent.setup();

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // The grid and its controls stay on screen around the error
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

    // React warns about a state update on the unmounted grid
    settlers[0](new Error("too late"));
    await flushRender();
  });

  it("shows the newest request's result when an older response arrives late", async () => {
    const settlers: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => {
      settlers.push(resolve);
    })));
    const pageWith = (title: string) => ({
      ok: true, url: "",
      json: () => Promise.resolve({
        rows: [{ "@path": `/GridEntities/${title}`, title }],
        offset: 0, limit: 5, returnedrows: 1, totalrows: 1, totalIsApproximate: false,
      }),
    } as unknown as Response);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    // A search sends a second request while the first is unanswered
    fireEvent.change(await screen.findByPlaceholderText("Search…"), { target: { value: "new" } });
    await waitFor(() => {
      expect(settlers.length).toBeGreaterThan(1);
    });

    // The newer response arrives first, and the older one must not replace it
    settlers[settlers.length - 1](pageWith("Newest entity"));
    await flushRender();
    settlers[0](pageWith("Stale entity"));
    await flushRender();

    expect(screen.getByText("Newest entity")).toBeInTheDocument();
    expect(screen.queryByText("Stale entity")).toBeNull();
  });

  // The slowest test of the file, which a run with coverage can take far past the default timeout
  it("shows values picked in \"is any of\" as chips in their options' colors", { timeout: 60_000 }, async () => {
    const user = userEvent.setup();
    mockPage([]);

    render(<EntityDataGrid entityType={CHOICE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("combobox", { name: "Column" }));
    await user.click(await screen.findByRole("option", { name: "State" }));
    await user.click(screen.getByRole("combobox", { name: "Operator" }));
    await user.click(await screen.findByRole("option", { name: "is any of" }));

    // The list closes after each pick, so it is opened again for the second
    await user.click(screen.getByRole("combobox", { name: "Value" }));
    await user.click(await screen.findByRole("option", { name: "Open" }));
    await user.click(screen.getByRole("combobox", { name: "Value" }));
    await user.click(await screen.findByRole("option", { name: "Closed" }));

    // An option without a color keeps the stock outlined chip
    const open = screen.getByText("Open").closest(".MuiChip-root");
    expect(open).toHaveClass("MuiChip-filled");
    expect(open).toHaveStyle({ backgroundColor: "#1d6a3a", color: "#fff" });
    const closed = screen.getByText("Closed").closest(".MuiChip-root");
    expect(closed).toHaveClass("MuiChip-outlined");

    // The input keeps the size the filter form gives it, the size of the selects beside it
    expect(screen.getByRole("combobox", { name: "Value" }).closest(".MuiInputBase-root"))
      .toHaveClass("MuiInputBase-sizeSmall");
  });

  // The grid passes an option as it was declared, so a plain string has no label to read
  it("labels the chips of a column whose options are plain strings", { timeout: 60_000 }, async () => {
    const user = userEvent.setup();
    mockPage([]);

    render(<EntityDataGrid entityType={STRING_CHOICE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("combobox", { name: "Column" }));
    await user.click(await screen.findByRole("option", { name: "State" }));
    await user.click(screen.getByRole("combobox", { name: "Operator" }));
    await user.click(await screen.findByRole("option", { name: "is any of" }));

    await user.click(screen.getByRole("combobox", { name: "Value" }));
    await user.click(await screen.findByRole("option", { name: "open" }));

    const chip = screen.getByText("open").closest(".MuiChip-root");
    expect(chip).toBeInTheDocument();
    expect(chip).toHaveTextContent("open");
  });

  it("docks the filter panel to the bottom of narrow screens, one card per condition", async () => {
    const user = userEvent.setup();
    fakeNarrowScreen();
    mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    // Flushes the panel's render after the click, which a run with coverage can delay too long
    await flushRender();

    const panel = document.querySelector(".MuiDataGrid-panel");
    expect(panel).not.toBeNull();
    expect(panel).toHaveStyle({ position: "fixed" });
    // A header, and a Remove button instead of the delete icon
    expect(await screen.findByText("Filters", {}, { timeout: 10_000 })).toBeInTheDocument();
    expect(await screen.findByRole("combobox", { name: "Column" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => {
      expect(screen.queryByRole("combobox", { name: "Column" })).toBeNull();
    });

    // Removing the only condition closes the sheet too
    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("button", { name: "Remove" }));
    await waitFor(() => {
      expect(screen.queryByRole("combobox", { name: "Column" })).toBeNull();
    });
  });

  it("gives the columns panel the same bottom-sheet header on narrow screens", async () => {
    const user = userEvent.setup();
    fakeNarrowScreen();
    mockPage([]);

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    await user.click(screen.getByRole("button", { name: /columns/i }));
    await flushRender();

    expect(await screen.findByText("Columns", {}, { timeout: 10_000 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => {
      expect(screen.queryByText("Columns")).toBeNull();
    });
  });
});
