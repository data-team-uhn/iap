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

import CategoriesWidget from "@iap/categories/CategoriesWidget";

const treeJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "description": "Existing data or specimens only.",
    "retired": false,
    "RetrospectiveData": {
      "jcr:primaryType": "cat:Category",
      "label": "Retrospective Data Studies",
      "retired": false,
    },
  },
  "Paper": {
    "jcr:primaryType": "cat:Category",
    "label": "Paper submissions",
    "retired": true,
  },
};

const stubFetch = () =>
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
    ok: true, status: 200, statusText: "OK",
    json: () => Promise.resolve(treeJson),
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("CategoriesWidget", () => {
  it("lists the top-level categories collapsed, condensed to labels only", async () => {
    stubFetch();
    render(<CategoriesWidget />);

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    expect(screen.getByText("Paper submissions")).toBeInTheDocument();
    expect(screen.getByText("Retired")).toBeInTheDocument();
    // Collapsed by default, so subcategories are not visible yet
    expect(screen.queryByText("Retrospective Data Studies")).not.toBeInTheDocument();
    // Condensed: labels only, no descriptions
    expect(screen.queryByText("Existing data or specimens only.")).not.toBeInTheDocument();
  });

  it("expands and re-collapses a branch through its chevron", async () => {
    stubFetch();
    render(<CategoriesWidget />);
    await screen.findByText("Retrospective studies");

    fireEvent.click(screen.getByRole("button", { name: "Expand Retrospective studies" }));
    expect(await screen.findByText("Retrospective Data Studies")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Collapse Retrospective studies" }));
    await waitFor(() => expect(screen.queryByText("Retrospective Data Studies")).not.toBeInTheDocument());
    // Leaves offer no working chevron
    expect(screen.queryByRole("button", { name: /Paper submissions/ })).not.toBeInTheDocument();
  });

  it("reports a loading failure without crashing the console", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false, status: 500, statusText: "Server Error",
    } as unknown as Response)));
    render(<CategoriesWidget />);

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("The categories could not be loaded");
    expect(report).toHaveTextContent("500");
  });

  it("reloads the tree when the load failure's Retry is used", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false, status: 500, statusText: "Server Error",
    } as unknown as Response)));
    render(<CategoriesWidget />);
    await screen.findByRole("alert");

    // The next attempt succeeds
    stubFetch();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("reports an empty tree", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: true, status: 200, statusText: "OK",
      json: () => Promise.resolve({ "jcr:primaryType": "cat:CategoriesHomepage" }),
    } as unknown as Response)));
    render(<CategoriesWidget />);

    expect(await screen.findByText("No categories are defined yet.")).toBeInTheDocument();
  });
});
