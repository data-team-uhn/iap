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

import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import { getRoutes } from "@iap/frontend-commons/routes";
import Breadcrumbs from "@iap/homepage/Breadcrumbs";

vi.mock("@iap/frontend-commons/routes", () => ({
  getRoutes: vi.fn(),
}));

const mockedGetRoutes = vi.mocked(getRoutes);

// The views, with only the properties the trail reads
const views = [
  { "ext:name": "Dashboard", "ext:targetURL": "/" },
  { "ext:name": "Administration", "ext:targetURL": "/admin" },
  { "ext:name": "Submission categories", "ext:targetURL": "/admin/categories" },
  // A splat with no page of its own, and a splat beside the page it details. The splat comes
  // first, so that a trail taking the first view matching an ancestor would name it.
  { "ext:name": "Submission", "ext:targetURL": "/Submissions/*" },
  { "ext:name": "Archive entry", "ext:targetURL": "/admin/archive/*" },
  { "ext:name": "Archive", "ext:targetURL": "/admin/archive" },
];

const renderAt = (url: string) => render(
  <MemoryRouter initialEntries={[url]}>
    <Breadcrumbs />
  </MemoryRouter>
);

describe("Breadcrumbs", () => {
  // A block body, because the shorthand would return the mock. Vitest calls a function returned
  // from a hook after each test, as its teardown.
  beforeEach(() => { mockedGetRoutes.mockResolvedValue(views); });

  it("renders nothing on the home page", async () => {
    const { container } = renderAt("/");

    await waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing on a top-level page, whose only ancestor is home", async () => {
    const { container } = renderAt("/admin");

    await waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("links the ancestor pages of a deeper page", async () => {
    renderAt("/admin/categories");

    const crumb = await screen.findByRole("link", { name: "Administration" });
    expect(crumb).toHaveAttribute("href", "/admin");
    expect(screen.queryByText("Submission categories")).not.toBeInTheDocument();
  });

  it("follows the ancestors with the crumbs the page adds, for as long as it is shown", async () => {
    function VersionPage() {
      usePageCrumbs([ { path: "/admin/schemas/study", label: "Clinical study" } ]);
      return null;
    }
    const { rerender } = render(
      <MemoryRouter initialEntries={[ "/admin/schemas/study/v1" ]}>
        <Breadcrumbs />
        <VersionPage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("link", { name: "Clinical study" })).toHaveAttribute("href", "/admin/schemas/study");
    expect(screen.getByRole("link", { name: "Administration" })).toBeInTheDocument();
    rerender(
      <MemoryRouter initialEntries={[ "/admin/schemas/study/v1" ]}>
        <Breadcrumbs />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.queryByRole("link", { name: "Clinical study" })).not.toBeInTheDocument());
  });

  // /Submissions/* matches /Submissions and the three folders under it, and none of them is a page
  it("puts no crumb on the buckets a treed submission is filed under", async () => {
    const { container } = renderAt("/Submissions/95/21/a8/6f3c1e2a-0b44-4d21-9a77-51e0c9d3b8fa");

    await waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  // Both /admin/archive and /admin/archive/* match the ancestor /admin/archive
  it("names an ancestor after its own page, not after the view detailing one of its children", async () => {
    renderAt("/admin/archive/6f3c1e2a");

    expect(await screen.findByRole("link", { name: "Archive" })).toHaveAttribute("href", "/admin/archive");
    expect(screen.queryByText("Archive entry")).not.toBeInTheDocument();
  });

  it("skips ancestors that have no registered view, e.g. views the user cannot read", async () => {
    // Served to a user who cannot read the Administration view
    mockedGetRoutes.mockResolvedValue(views.filter(view => view["ext:targetURL"] !== "/admin"));

    const { container } = renderAt("/admin/categories");

    await waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("reports a failure to load the views, and renders nothing", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    const failure = new Error("network down");
    mockedGetRoutes.mockRejectedValue(failure);

    const { container } = renderAt("/admin/categories");

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith("Something went wrong loading the views", failure);
    });
    expect(container).toBeEmptyDOMElement();
    errorSpy.mockRestore();
  });

  it("falls back to the path for a view that registers no name", async () => {
    mockedGetRoutes.mockResolvedValue([
      { "ext:targetURL": "/admin" },
      { "ext:name": "Submission categories", "ext:targetURL": "/admin/categories" },
    ]);

    renderAt("/admin/categories");

    expect(await screen.findByRole("link", { name: "/admin" })).toBeInTheDocument();
  });
});
