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

import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import CategoryManager from "@iap/categories/CategoryManager";

// Rendering the full manager (tree + MUI dialogs) is slow on a loaded machine, e.g. during the
// Maven build where every suite runs in parallel; the default 5s per-test budget is too tight.
vi.setConfig({ testTimeout: 15000 });

// A tree with a branch (whose deletion must be blocked), a bound leaf, and a retired category.
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
      "schemaVersion": {
        "jcr:primaryType": "sch:SchemaVersion",
        "jcr:uuid": "uuid-sv1",
        "version": "1.0",
        "@path": "/Schemas/basic/1.0",
      },
    },
  },
  "Paper": {
    "jcr:primaryType": "cat:Category",
    "label": "Paper submissions",
    "retired": true,
  },
};

const stubFetch = () =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
    ok: true, status: 200, statusText: "OK",
    json: () => Promise.resolve(url.startsWith("/Schemas")
      ? { "jcr:primaryType": "sch:SchemasHomepage" }
      : treeJson),
    headers: { get: () => null },
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

const renderManager = () => render(<MemoryRouter><CategoryManager /></MemoryRouter>);

describe("CategoryManager", () => {
  it("renders the category tree with nested rows", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    expect(screen.getByText("Retrospective Data Studies")).toBeInTheDocument();
    expect(screen.getByText("Paper submissions")).toBeInTheDocument();
    expect(screen.getByText("Existing data or specimens only.")).toBeInTheDocument();
  });

  it("marks retired categories and displays schema bindings", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByText("Retired")).toBeInTheDocument();
    expect(screen.getByText("Schema: basic v1.0")).toBeInTheDocument();
  });

  it("blocks deleting a category that still has subcategories", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByRole("button", { name: "Delete Retrospective studies" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete Paper submissions" })).toBeEnabled();
  });

  it("opens the creation dialog from the main action", async () => {
    stubFetch();
    renderManager();
    await screen.findByText("Retrospective studies");

    fireEvent.click(screen.getByRole("button", { name: "New category" }));

    expect(await screen.findByRole("heading", { name: "New category" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Label/)).toBeInTheDocument();
  });

  it("offers retiring instead when the server refuses a deletion", async () => {
    stubFetch();
    const fetchMock = vi.mocked(fetch);
    renderManager();
    await screen.findByText("Paper submissions");

    fireEvent.click(screen.getByRole("button", { name: "Delete Paper submissions" }));
    // The confirmation dialog appears; the server then answers the delete POST with 409
    fetchMock.mockResolvedValueOnce({
      ok: false, status: 409, statusText: "Conflict",
    } as unknown as Response);
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/cannot be deleted/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retire instead" })).toBeInTheDocument();
  });
});
