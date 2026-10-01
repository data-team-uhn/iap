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
import { act, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { forgetWorkflowHomepages } from "@iap/workflows/workflowModel";
import WorkflowsWidget from "@iap/workflows/WorkflowsWidget";

// The homepage discovery is kept for the life of the session, so each test starts from an unasked one
beforeEach(forgetWorkflowHomepages);

type FetchStub = (url: string) => Promise<Response>;

// A count, as the pagination servlet answers a page of no rows at all.
const count = (total: number, approximate = false) => ({
  rows: [],
  offset: 0,
  limit: 0,
  returnedrows: 0,
  totalrows: total,
  totalIsApproximate: approximate,
});

// A server answering the discovery endpoint, and one count per homepage keyed by its path.
const stubFetch = (homepages: unknown[], counts: Record<string, unknown>) => {
  const fetchMock = vi.fn<FetchStub>(url => {
    const path = new URL(url, "http://localhost").pathname;
    const body = path === "/Workflows.homepages.json"
      ? { homepages }
      : counts[path.replace(".paginate.json", "")];
    return Promise.resolve({ ok: true, status: 200, url, json: () => Promise.resolve(body) } as unknown as Response);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// A server that discovers one homepage straight away but holds its count back, so that a test can
// unmount the widget and only then decide how the request it left behind turns out.
const deferredCount = () => {
  let settle: (ok: boolean) => void = () => undefined;
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => {
    const path = new URL(url, "http://localhost").pathname;
    if (path === "/Workflows.homepages.json") {
      return Promise.resolve({ ok: true, status: 200, url,
        json: () => Promise.resolve({ homepages: [ { path: "/Workflows", title: "Workflows" } ] }) } as
        unknown as Response);
    }
    return new Promise<Response>((resolve, reject) => {
      settle = (ok: boolean) => ok
        ? resolve({ ok: true, status: 200, url, json: () => Promise.resolve(count(3)) } as unknown as Response)
        : reject(new TypeError("Failed to fetch"));
    });
  }));
  // Letting every promise queued so far run out. Needed twice over: once so that discovery settles
  // and the count is actually asked for — a widget unmounted before that has nothing outstanding to
  // ignore — and again after settling, so the handler it left behind has had its chance to report.
  const flush = () => act(async () => { await new Promise(resolve => { setTimeout(resolve, 0); }); });
  return {
    asked: flush,
    settle: async (ok: boolean) => {
      settle(ok);
      await flush();
    },
  };
};

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter><WorkflowsWidget /></MemoryRouter>
  </ThemeProvider>
);

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowsWidget", () => {
  it("counts the workflows of every homepage it discovered", async () => {
    const fetchMock = stubFetch(
      [ { path: "/Workflows", title: "Workflows" }, { path: "/SystemWorkflows", title: "System workflows" } ],
      { "/Workflows": count(3), "/SystemWorkflows": count(12) }
    );

    renderWidget();

    expect(await screen.findByText("System workflows")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    // Each homepage's name is the way to the listing being counted, beside the frame's own action
    expect(screen.getByRole("link", { name: "Workflows" }))
      .toHaveAttribute("href", "/admin/workflows/Workflows");
    expect(screen.getByRole("link", { name: "System workflows" }))
      .toHaveAttribute("href", "/admin/workflows/SystemWorkflows");
    // Counted rather than listed: the widget asks for no rows at all, which is what makes it fit
    const counted = fetchMock.mock.calls.map(call => new URL(call[0], "http://localhost"))
      .filter(url => url.pathname.endsWith(".paginate.json"));
    expect(counted.map(url => url.searchParams.get("limit"))).toEqual([ "0", "0" ]);
  });

  it("shows a count the server stopped short of finishing as the lower bound it is", async () => {
    stubFetch([ { path: "/Workflows", title: "Workflows" } ], { "/Workflows": count(100, true) });

    renderWidget();

    expect(await screen.findByText("100+")).toBeInTheDocument();
  });

  it("says so when there is nowhere workflows are stored", async () => {
    stubFetch([], {});

    renderWidget();

    expect(await screen.findByText("No workflows are defined yet.")).toBeInTheDocument();
  });

  it("still names a homepage whose count was refused, and keeps the others' numbers", async () => {
    // A dashboard is worth less for hiding a tree than for admitting it could not measure one, and
    // the homepages are counted separately precisely so one refusal is not all of them
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal("fetch", vi.fn<FetchStub>(url => {
      const path = new URL(url, "http://localhost").pathname;
      if (path === "/Workflows.homepages.json") {
        return Promise.resolve({ ok: true, status: 200, url, json: () => Promise.resolve({ homepages: [
          { path: "/Workflows", title: "Workflows" },
          { path: "/SystemWorkflows", title: "System workflows" },
        ] }) } as unknown as Response);
      }
      return path.startsWith("/SystemWorkflows")
        ? Promise.resolve({ ok: false, status: 503, url } as unknown as Response)
        : Promise.resolve({ ok: true, status: 200, url, json: () => Promise.resolve(count(3)) } as unknown as
          Response);
    }));

    renderWidget();

    expect(await screen.findByText("System workflows")).toBeInTheDocument();
    // The one that could be counted keeps its number; the one that could not says so instead
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByTitle("The workflows here could not be counted")).toHaveTextContent("?");

    vi.mocked(console.error).mockRestore();
  });

  it("stops caring about the counts it asked for once it is gone", async () => {
    const { asked, settle } = deferredCount();
    const { unmount } = renderWidget();
    await asked();

    unmount();
    await settle(true);

    expect(screen.queryByText("Workflows")).not.toBeInTheDocument();
    expect(screen.queryByText("3")).not.toBeInTheDocument();
  });

  it("stops caring about a count that was refused once it is gone", async () => {
    // The same race on the other answer: a refused count still resolves the load, into a widget that
    // is no longer there to be told anything
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { asked, settle } = deferredCount();
    const { unmount } = renderWidget();
    await asked();

    unmount();
    await settle(false);

    expect(screen.queryByText("Workflows")).not.toBeInTheDocument();
    expect(screen.queryByText("?")).not.toBeInTheDocument();

    vi.mocked(console.error).mockRestore();
  });
});
