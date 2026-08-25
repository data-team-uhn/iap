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

// A type exercising every kind of column content the generic list card can face; the "nested"
// column is also excluded from sorting
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

// A type bringing its own card renderer for the list mode; the card applies the column
// selection to its own composition through the visible fields it receives
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

// A type with a choice column whose options carry display colors, like tag definitions do
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
        // Filled, because the default soft styling is color-mix()/light-dark() CSS that
        // jsdom cannot verify — chipStyle's own tests pin the soft recipes down
        { value: "open", label: "Open", color: "#1d6a3a", variant: "filled" },
        { value: "closed", label: "Closed" },
      ],
    },
  ],
});

// A choice column declaring its options the other way MUI allows, as plain strings: there is no
// label, color or variant to read off one, and the grid passes them along as declared
const STRING_CHOICE_TYPE = "test/StringChoiceEntity";
registerEntityType(STRING_CHOICE_TYPE, {
  homepage: "/StringChoiceEntities",
  columns: [
    { field: "title", headerName: "Title" },
    { field: "state", headerName: "State", type: "singleSelect", valueOptions: ["open", "closed"] },
  ],
});

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
      () => Promise.resolve({ ok: false, url: "", status: 503 } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });

    // The shared vocabulary for a status, rather than the developer-facing wording it replaced
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
    // first offered operator (contains). The trigger's badge hides while nothing is filtering.
    const filterTrigger = screen.getAllByRole("button", { name: /filter/i })[0];
    expect(within(filterTrigger).getByText("0")).toHaveClass("MuiBadge-invisible");
    await user.click(filterTrigger);
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
    // The trigger now flags the active condition with a badge
    expect(within(filterTrigger).getByText("1")).toBeInTheDocument();
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
    // The trigger's badge counts the panel's single condition, not the expanded server triples
    expect(within(screen.getAllByRole("button", { name: /filter/i })[0]).getByText("1")).toBeInTheDocument();
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
    // The later rows exercise the row-id fallbacks: to the node name when the projection carries
    // no path, and to the row's position when it carries neither -- two such rows must still be
    // told apart, since the grid throws on duplicate ids and would take the widget down with it
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

    // Clicking a row must not navigate anywhere: the grid is still on screen afterwards
    await user.click(await screen.findByText("Plain entity"));
    expect(screen.getByText("Plain entity")).toBeInTheDocument();
    // Both identity-less rows rendered, rather than one of them colliding the grid into an error
    expect(screen.getByText("Pathless entity")).toBeInTheDocument();
    expect(screen.getByText("Another pathless entity")).toBeInTheDocument();
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

    // The generic card: first column as the title, the others as labeled rows
    expect(await screen.findByText("First entity")).toBeInTheDocument();
    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.getByText("draft")).toBeInTheDocument();

    // Tapping the card navigates like clicking a row does
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
    // renderCell columns render through their own renderer
    expect(screen.getByText("special").tagName).toBe("EM");
    // Plain numbers and booleans are stringified, dates from value getters are formatted
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("true")).toBeInTheDocument();
    // Timestamps display without seconds throughout the grids
    expect(screen.getByText(new Date("2026-07-02T10:00:00.000Z").toLocaleString(undefined, {
      year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric",
    }))).toBeInTheDocument();
    // Nested objects have no generic rendering: the whole labeled row is skipped
    expect(screen.queryByText("Nested")).toBeNull();
    // A column without a header falls back to its field name as the label
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

    // The title leads the card, with the badge beside it through the column's own renderer
    expect(await screen.findByText("Slotted entity")).toBeInTheDocument();
    expect(screen.getByText("open").tagName).toBe("EM");
    // The caption columns join into one " • " line, compacted through their cardValue
    const day = new Date("2026-07-02T10:00:00.000Z").toLocaleDateString();
    expect(screen.getByText((_, element) => element?.textContent === `demo • ${day}`)).toBeInTheDocument();
    // Unhinted columns stay labeled rows, omitted ones don't appear at all
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

    // The hidden badge and caption columns leave the card; the remaining caption keeps its line
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

    // Picking the active column again flips the direction
    await user.click(within(toolbar).getByRole("button", { name: "Sort" }));
    await user.click(await screen.findByRole("menuitem", { name: /Modified/ }));
    await waitFor(() => {
      const lastUrl = new URL(fetchMock.mock.calls[fetchMock.mock.calls.length - 1][0], "http://localhost");
      expect(lastUrl.searchParams.get("descending")).toBe("true");
    });

    // The menu can also be dismissed without picking anything
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

    // The column headers have their own sort buttons; the toolbar must not double up
    expect(within(screen.getByRole("toolbar")).queryByRole("button", { name: "Sort" })).toBeNull();
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
      { ok: true, url: "", json: () => Promise.resolve(page) } as unknown as Response)));

    render(<EntityDataGrid entityType={TEST_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Entity 0");

    // With an exact total this button could be disabled; the approximate flag keeps it live
    expect(screen.getByRole("button", { name: /next page/i })).toBeEnabled();
    // The servlet's counted lower bound shows through the grid's own estimate wording
    expect(screen.getByText(/1–5 of around 8/)).toBeInTheDocument();
  });

  // Sorting reorders the whole collection server-side, so where the reader was in the old order
  // says nothing about where they want to be in the new one.
  //
  // The assertion is on the requests rather than on where the grid ends up: left to itself the grid
  // reaches the first page anyway, but only after fetching page 2 of the new order and showing it,
  // so the end state is the same either way and only the traffic tells the two apart.
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

  it("names its search box, so a reader is told what it searches", async () => {
    // "Search…" is a placeholder, not a name. Without this the control announces itself identically
    // wherever it appears, to a screen reader and to anything else that addresses the page by its
    // accessible names.
    render(
      <EntityDataGrid entityType={TEST_TYPE} searchLabel="Search my submissions" disableVirtualization />,
      { wrapper: MemoryRouter },
    );

    // A searchbox rather than a textbox: the quick filter control says so, and the role is what a reader
    // and a locator both go by
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
      ok: true, url: "",
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

  // The many sequential picker interactions make this the file's slowest test; under a
  // coverage-instrumented full-suite run it can far exceed the default timeout, so it gets
  // generous room
  it("shows values picked in \"is any of\" as chips in their options' colors", { timeout: 60_000 }, async () => {
    const user = userEvent.setup();
    mockPage([]);

    render(<EntityDataGrid entityType={CHOICE_TYPE} disableVirtualization />, { wrapper: MemoryRouter });
    await screen.findByText("Nothing to show");

    // Point the filter panel's condition at the choice column, with "is any of"
    await user.click(screen.getAllByRole("button", { name: /filter/i })[0]);
    await user.click(await screen.findByRole("combobox", { name: "Column" }));
    await user.click(await screen.findByRole("option", { name: "State" }));
    await user.click(screen.getByRole("combobox", { name: "Operator" }));
    await user.click(await screen.findByRole("option", { name: "is any of" }));

    // Pick both options; the popup closes after each pick, so it is reopened in between
    await user.click(screen.getByRole("combobox", { name: "Value" }));
    await user.click(await screen.findByRole("option", { name: "Open" }));
    await user.click(screen.getByRole("combobox", { name: "Value" }));
    await user.click(await screen.findByRole("option", { name: "Closed" }));

    // A colored option styles its chip per its color and variant; an option without a color
    // stays stock outlined
    const open = screen.getByText("Open").closest(".MuiChip-root");
    expect(open).toHaveClass("MuiChip-filled");
    expect(open).toHaveStyle({ backgroundColor: "#1d6a3a", color: "#fff" });
    const closed = screen.getByText("Closed").closest(".MuiChip-root");
    expect(closed).toHaveClass("MuiChip-outlined");

    // The input keeps the size the filter form asked for, so it lines up with the column and
    // operator selects beside it rather than standing a size taller
    expect(screen.getByRole("combobox", { name: "Value" }).closest(".MuiInputBase-root"))
      .toHaveClass("MuiInputBase-sizeSmall");
  });

  // A column may declare its choices as plain strings, and the grid passes an option along as it
  // was declared: there is no label to read off it, and claiming there is leaves the chip blank.
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

    // The chip carries the value as its label, the way MUI labels a plain-string option
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
    // Flush any panel render work left pending by the click, so the assertions below see the
    // sheet even under a heavily loaded, coverage-instrumented run (see flushRender)
    await flushRender();

    // The panel is a fixed bottom sheet rather than a floating popper
    const panel = document.querySelector(".MuiDataGrid-panel");
    expect(panel).not.toBeNull();
    expect(panel).toHaveStyle({ position: "fixed" });
    // The sheet announces itself with a header, and the condition card carries a labeled
    // Remove button instead of the desktop layout's delete X
    expect(await screen.findByText("Filters", {}, { timeout: 10_000 })).toBeInTheDocument();
    expect(await screen.findByRole("combobox", { name: "Column" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove" })).toBeInTheDocument();

    // The header's own close button dismisses the sheet
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => {
      expect(screen.queryByRole("combobox", { name: "Column" })).toBeNull();
    });

    // Removing the only (still empty) condition also dismisses the sheet — stock behavior
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
    // Same flush-and-wide-timeout treatment as the filters sheet test above
    await flushRender();

    expect(await screen.findByText("Columns", {}, { timeout: 10_000 })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await waitFor(() => {
      expect(screen.queryByText("Columns")).toBeNull();
    });
  });
});
