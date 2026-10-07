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

import { useEffect } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { forgetWorkflowHomepages } from "@iap/workflows/useWorkflowHomepages";
import WorkflowConsole from "@iap/workflows/WorkflowConsole";

// The three pages the console dispatches to, stood in for by markers: what is under test is which
// one a URL opens and what it is told, not what any of them then draws.
vi.mock("@iap/workflows/WorkflowsView", () => ({
  default: ({ homepage }: { homepage?: string }) => <div>{`list of ${homepage ?? "everything"}`}</div>,
}));
vi.mock("@iap/workflows/WorkflowManager", () => ({
  default: ({ path, homepage }: { path: string; homepage: { title: string } }) =>
    <div>{`workflow ${path}`}<span>{`in ${homepage.title}`}</span></div>,
}));
// Each mount of the version page, by what it was opened on: a page carried over to another version keeps
// whatever state it held, so which URLs start a fresh one is under test too
const { editorMounts } = vi.hoisted(() => ({ editorMounts: [] as string[] }));
vi.mock("@iap/workflows/WorkflowEditor", () => ({
  default: function EditorStandIn(
    { path, homepage, editing }: { path: string; homepage: { title: string }; editing: boolean }) {
    const opened = `${path} ${editing ? "editing" : "read-only"}`;
    useEffect(() => {
      editorMounts.push(opened);
      // Mounted once per page: the URL it was mounted for is all that is recorded
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return <div>{`version ${opened}`}<span>{`in ${homepage.title}`}</span></div>;
  },
}));

type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const HOMEPAGES = {
  homepages: [
    { path: "/Workflows", title: "Workflows" },
    { path: "/SystemWorkflows", title: "System workflows" },
  ],
};

beforeEach(() => {
  forgetWorkflowHomepages();
  editorMounts.length = 0;
  // The url matters: the session guard reads it to tell an answer from a login page served in its place
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve({
    ok: true, status: 200, url, json: () => Promise.resolve(HOMEPAGES),
  } as unknown as Response)));
});

afterEach(() => vi.unstubAllGlobals());

const renderAt = (url: string) => {
  const router = createMemoryRouter([ { path: "*", element: <WorkflowConsole /> } ], { initialEntries: [ url ] });
  return {
    router,
    ...render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <RouterProvider router={router} />
      </ThemeProvider>,
    ),
  };
};

describe("WorkflowConsole", () => {
  it("opens the listing on the homepage a URL names", async () => {
    renderAt("/admin/workflows/SystemWorkflows");

    expect(await screen.findByText("list of /SystemWorkflows")).toBeInTheDocument();
  });

  it("opens one workflow", async () => {
    renderAt("/admin/workflows/Workflows/review");

    expect(await screen.findByText("workflow /Workflows/review")).toBeInTheDocument();
  });

  it("tells a workflow's page and a version's which homepage they are in, by its title", async () => {
    // What the breadcrumb trail leads back to: the pages name the steps above them themselves
    const { unmount } = renderAt("/admin/workflows/SystemWorkflows/review");
    expect(await screen.findByText("in System workflows")).toBeInTheDocument();
    unmount();

    renderAt("/admin/workflows/SystemWorkflows/review/2-0");
    expect(await screen.findByText("in System workflows")).toBeInTheDocument();
  });

  it("opens one version, read-only", async () => {
    renderAt("/admin/workflows/Workflows/review/2-0");

    expect(await screen.findByText("version /Workflows/review/2-0 read-only")).toBeInTheDocument();
  });

  it("opens the editor when the suffix asks for it", async () => {
    renderAt("/admin/workflows/Workflows/review/2-0.edit");

    expect(await screen.findByText("version /Workflows/review/2-0 editing")).toBeInTheDocument();
  });

  it("reads a version named after a page as itself", async () => {
    // Nothing in a path is taken for a page, so no name below a homepage is reserved
    renderAt("/admin/workflows/Workflows/review/edit");

    expect(await screen.findByText("version /Workflows/review/edit read-only")).toBeInTheDocument();
  });

  it("says so rather than guessing when a page is named in the path", async () => {
    // What the editor's URL used to be: a version is the deepest thing below a homepage, and a
    // fourth segment is one more than anything the console can place
    renderAt("/admin/workflows/Workflows/review/2-0/edit");

    expect(await screen.findByText(/does not point to a workflow/)).toBeInTheDocument();
  });

  it("says so rather than guessing when the URL names nothing it can show", async () => {
    renderAt("/admin/workflows/Elsewhere/review");

    expect(await screen.findByText(/does not point to a workflow/)).toBeInTheDocument();
  });

  it("reads the repository path out of a URL the browser encoded", async () => {
    // Node names keep accented letters, and the router hands them over percent-encoded
    renderAt("/admin/workflows/Workflows/%C3%A9valuationDesCong%C3%A9s");

    expect(await screen.findByText("workflow /Workflows/\u00e9valuationDesCong\u00e9s")).toBeInTheDocument();
  });

  it("opens each version, and each way of opening it, as a page of its own", async () => {
    const { router } = renderAt("/admin/workflows/Workflows/review/2-0.edit");
    expect(await screen.findByText("version /Workflows/review/2-0 editing")).toBeInTheDocument();

    await act(async () => {
      await router.navigate("/admin/workflows/Workflows/review/2-0");
    });

    expect(await screen.findByText("version /Workflows/review/2-0 read-only")).toBeInTheDocument();
    expect(editorMounts).toEqual([ "/Workflows/review/2-0 editing", "/Workflows/review/2-0 read-only" ]);
  });

  it("offers to ask again when the homepages could not be listed, rather than saying a URL names nothing", async () => {
    // Whether a URL names a workflow depends on the homepages, so without them it cannot be told
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve({
      ok: true, status: 200, url, json: () => Promise.resolve(HOMEPAGES),
    } as unknown as Response)).mockResolvedValueOnce({
      ok: false, status: 503, url: "/Workflows.homepages.json", json: () => Promise.resolve({}),
    } as unknown as Response));
    renderAt("/admin/workflows/SystemWorkflows/review");

    expect(await screen.findByText("The workflow homepages could not be listed")).toBeInTheDocument();
    expect(screen.queryByText(/does not point to a workflow/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("workflow /SystemWorkflows/review")).toBeInTheDocument();
  });

  it("opens the default homepage's listing when the URL is the console's own root", async () => {
    // The root names no page of its own; landing there used to render nothing at all
    renderAt("/admin/workflows");

    expect(await screen.findByText("list of /Workflows")).toBeInTheDocument();
  });

  it("waits for the discovery before deciding what a URL is about", () => {
    // Which of the three a path is depends on the homepages, so nothing is shown until they land.
    renderAt("/admin/workflows/Workflows/review");

    expect(screen.getByLabelText("Loading the workflows")).toBeInTheDocument();
    expect(screen.queryByText("workflow /Workflows/review")).not.toBeInTheDocument();
  });

  it("asks for the homepages once, however many pages are opened", async () => {
    const { unmount } = renderAt("/admin/workflows/Workflows/review");
    expect(await screen.findByText("workflow /Workflows/review")).toBeInTheDocument();
    unmount();

    renderAt("/admin/workflows/Workflows/review/2-0");
    expect(await screen.findByText("version /Workflows/review/2-0 read-only")).toBeInTheDocument();

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });
});
