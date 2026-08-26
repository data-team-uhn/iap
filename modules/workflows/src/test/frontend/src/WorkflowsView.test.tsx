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
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { forgetWorkflowHomepages } from "@iap/workflows/workflowModel";
import WorkflowsView from "@iap/workflows/WorkflowsView";

// The homepage discovery is kept for the life of the session, so each test starts from an unasked one
beforeEach(forgetWorkflowHomepages);

// A stubbed fetch: the URL, and the request options a write carries.
type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

// One workflow, as the pagination servlet serializes it.
const workflowRow = {
  "@path": "/Workflows/review",
  "@name": "review",
  "title": "Standard review",
  "jcr:created": "2026-07-01T10:00:00.000-04:00",
  "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
};

const page = (rows: unknown[]) => ({
  rows,
  offset: 0,
  limit: 10,
  returnedrows: rows.length,
  totalrows: rows.length,
  totalIsApproximate: false,
});

// A server answering the discovery endpoint and the listings, and nothing else.
const stubFetch = (homepages: unknown[], rows: unknown[] = [workflowRow]) => {
  const fetchMock = vi.fn<FetchStub>((url, options) => {
    const path = new URL(url, "http://localhost").pathname;
    const body = path === "/Workflows.homepages.json" ? { homepages } : page(rows);
    return Promise.resolve({
      ok: true,
      status: 200,
      // An event that created something answers with a redirect to it, which fetch follows on its
      // own, so the final URL is where a creation reads the path to open the editor on
      redirected: options?.method === "POST",
      url: `http://localhost${path.split(".")[0]}/created`,
      headers: new Headers(),
      json: () => Promise.resolve(body),
    } as unknown as Response);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// The listing requests the view made, as URLs, in the order they were made.
const listings = (fetchMock: { mock: { calls: [string, ...unknown[]][] } }) => fetchMock.mock.calls
  .map(call => new URL(call[0], "http://localhost"))
  .filter(url => url.pathname.endsWith(".paginate.json"));

// Wherever the router ended up, as text, so a navigation reads as the destination the user arrives at
function Destination() {
  // Query and all: which page a version is opened on is asked for there, so a destination without it
  // would not say which one the user arrived at
  const { pathname, search } = useLocation();
  return <div>{`went to ${pathname}${search}`}</div>;
}

// This page with the homepage its URL names, which is the reading the console does in the
// application: one segment below the console's root is a homepage, and it is the tab that is open.
function ViewOfHomepage() {
  return <WorkflowsView homepage={useLocation().pathname.slice("/admin/workflows".length)} />;
}

const renderView = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={["/admin/workflows"]}>
      <Routes>
        <Route path="/admin/workflows" element={<WorkflowsView />} />
        {/* Picking a tab navigates here, so the tab that is open survives the round trip */}
        <Route path="/admin/workflows/:homepage" element={<ViewOfHomepage />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>
);

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowsView", () => {
  it("offers a tab per homepage it discovered, and lists the first of them", async () => {
    const fetchMock = stubFetch([
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ]);

    renderView();

    expect(await screen.findByRole("tab", { name: "System workflows" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Workflows" })).toHaveAttribute("aria-selected", "true");
    // One listing, over the tree being looked at: paging, sorting and the total belong to it
    await waitFor(() => expect(listings(fetchMock)).toHaveLength(1));
    expect(listings(fetchMock)[0].pathname).toBe("/Workflows.paginate.json");
    expect(listings(fetchMock)[0].searchParams.get("sortBy")).toBe("title");
  });

  it("lists the homepage whose tab is picked, and nothing until then", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch([
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ]);
    renderView();
    await waitFor(() => expect(listings(fetchMock)).toHaveLength(1));

    await user.click(screen.getByRole("tab", { name: "System workflows" }));

    await waitFor(() => expect(listings(fetchMock)).toHaveLength(2));
    expect(listings(fetchMock)[1].pathname).toBe("/SystemWorkflows.paginate.json");
    expect(screen.getByRole("tab", { name: "System workflows" })).toHaveAttribute("aria-selected", "true");
  });

  it("offers no tab for a lone homepage, which is the page's own subject", async () => {
    stubFetch([ { path: "/Workflows", title: "Workflows" } ]);

    renderView();

    expect(await screen.findByText("Standard review")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    // The page's own heading, and nothing repeating it
    expect(screen.getAllByRole("heading", { name: "Workflows" })).toHaveLength(1);
  });

  it("asks for nothing until it knows where to look", async () => {
    // A listing that guessed at /Workflows would show one tree's workflows and rearrange them a
    // moment later, once the real set of homepages arrived
    const fetchMock = vi.fn<FetchStub>(() => new Promise<Response>(() => undefined));
    vi.stubGlobal("fetch", fetchMock);

    renderView();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(listings(fetchMock)).toHaveLength(0);
  });

  it("stops caring about the homepages it asked for once it is gone", async () => {
    let answer: (response: Response) => void = () => undefined;
    vi.stubGlobal("fetch", vi.fn<FetchStub>(() => new Promise<Response>(resolve => {
      answer = resolve;
    })));
    const { unmount } = renderView();

    unmount();
    answer({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ homepages: [ { path: "/Workflows", title: "Workflows" } ] }),
    } as unknown as Response);

    await waitFor(() => expect(screen.queryByText("Standard review")).not.toBeInTheDocument());
  });

  it("says so when there are no workflows yet", async () => {
    stubFetch([ { path: "/Workflows", title: "Workflows" } ], []);

    renderView();

    expect(await screen.findByText("No workflows are defined yet")).toBeInTheDocument();
  });

  it("says so, and offers nothing to do, when there is nowhere workflows can be read from", async () => {
    stubFetch([]);

    renderView();

    expect(await screen.findByText("There is nowhere you can read workflows from.")).toBeInTheDocument();
    // Creating needs somewhere to create in
    expect(screen.getByRole("button", { name: "New workflow" })).toBeDisabled();
  });

  it("offers to create a workflow, once it knows where one could be created", async () => {
    const user = userEvent.setup();
    stubFetch([ { path: "/Workflows", title: "Workflows" } ]);

    renderView();

    const create = await screen.findByRole("button", { name: "New workflow" });
    await waitFor(() => expect(create).toBeEnabled());
    await user.click(create);

    expect(await screen.findByRole("dialog", { name: "New workflow" })).toBeInTheDocument();
  });

  it("opens the editor on the workflow it just created", async () => {
    // A new workflow has one draft version with nothing drawn in it, so drawing is where its author
    // is going next
    const user = userEvent.setup();
    stubFetch([ { path: "/Workflows", title: "Workflows" } ]);
    renderView();
    const create = await screen.findByRole("button", { name: "New workflow" });
    await waitFor(() => expect(create).toBeEnabled());
    await user.click(create);
    const dialog = await screen.findByRole("dialog", { name: "New workflow" });

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByText("went to /admin/workflows/Workflows/created/created?page=edit")).toBeInTheDocument();
  });
});
