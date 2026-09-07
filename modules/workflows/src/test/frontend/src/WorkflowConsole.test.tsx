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
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import WorkflowConsole from "@iap/workflows/WorkflowConsole";
import { forgetWorkflowHomepages } from "@iap/workflows/workflowModel";

// The three pages the console dispatches to, stood in for by markers: what is under test is which
// one a URL opens and what it is told, not what any of them then draws.
vi.mock("@iap/workflows/WorkflowsView", () => ({
  default: ({ homepage }: { homepage?: string }) => <div>{`list of ${homepage ?? "everything"}`}</div>,
}));
vi.mock("@iap/workflows/WorkflowManager", () => ({
  default: ({ path }: { path: string }) => <div>{`workflow ${path}`}</div>,
}));
vi.mock("@iap/workflows/WorkflowEditor", () => ({
  default: ({ path, editing }: { path: string; editing: boolean }) =>
    <div>{`version ${path} ${editing ? "editing" : "read-only"}`}</div>,
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
  // The url matters: the session guard reads it to tell an answer from a login page served in its place
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve({
    ok: true, status: 200, url, json: () => Promise.resolve(HOMEPAGES),
  } as unknown as Response)));
});

afterEach(() => vi.unstubAllGlobals());

const renderAt = (url: string) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={[url]}>
      <WorkflowConsole />
    </MemoryRouter>
  </ThemeProvider>
);

describe("WorkflowConsole", () => {
  it("opens the listing on the homepage a URL names", async () => {
    renderAt("/admin/workflows/SystemWorkflows");

    expect(await screen.findByText("list of /SystemWorkflows")).toBeInTheDocument();
  });

  it("opens one workflow", async () => {
    renderAt("/admin/workflows/Workflows/review");

    expect(await screen.findByText("workflow /Workflows/review")).toBeInTheDocument();
  });

  it("opens one version, read-only", async () => {
    renderAt("/admin/workflows/Workflows/review/2-0");

    expect(await screen.findByText("version /Workflows/review/2-0 read-only")).toBeInTheDocument();
  });

  it("opens the editor when the query asks for it", async () => {
    renderAt("/admin/workflows/Workflows/review/2-0?page=edit");

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

    expect(await screen.findByText(/does not name a workflow/)).toBeInTheDocument();
  });

  it("says so rather than guessing when the URL names nothing it can show", async () => {
    renderAt("/admin/workflows/Elsewhere/review");

    expect(await screen.findByText(/does not name a workflow/)).toBeInTheDocument();
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
