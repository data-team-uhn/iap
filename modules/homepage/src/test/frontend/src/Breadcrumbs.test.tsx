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

import { getRoutes } from "@iap/frontend-commons/routes";
import Breadcrumbs from "@iap/homepage/Breadcrumbs";

vi.mock("@iap/frontend-commons/routes", () => ({
  getRoutes: vi.fn(),
}));

const mockedGetRoutes = vi.mocked(getRoutes);

// The registered views as the extension mechanism returns them; only the routing-relevant
// properties matter to the breadcrumb trail.
const views = [
  { "iap:extensionName": "Dashboard", "iap:targetURL": "/" },
  { "iap:extensionName": "Administration", "iap:targetURL": "/admin" },
  { "iap:extensionName": "Submission categories", "iap:targetURL": "/admin/categories" },
];

const renderAt = (url: string) => render(
  <MemoryRouter initialEntries={[url]}>
    <Breadcrumbs />
  </MemoryRouter>
);

describe("Breadcrumbs", () => {
  // A block body on purpose: the arrow shorthand returns the mock, and Vitest takes a function
  // returned from a hook to be a teardown callback -- so it would call getRoutes() again after
  // every test, which goes unnoticed until an implementation rejects and nothing is listening.
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
    // The current page itself is not part of the trail - its title is the page heading
    expect(screen.queryByText("Submission categories")).not.toBeInTheDocument();
  });

  it("skips ancestors that have no registered view, e.g. views the user cannot read", async () => {
    // A user who cannot read the Administration view only gets the other views served
    mockedGetRoutes.mockResolvedValue(views.filter(view => view["iap:targetURL"] !== "/admin"));

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
      { "iap:targetURL": "/admin" },
      { "iap:extensionName": "Submission categories", "iap:targetURL": "/admin/categories" },
    ]);

    renderAt("/admin/categories");

    expect(await screen.findByRole("link", { name: "/admin" })).toBeInTheDocument();
  });

  it("renders nothing when the views cannot be loaded at all", async () => {
    mockedGetRoutes.mockResolvedValue(undefined);

    const { container } = renderAt("/admin/categories");

    await waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
