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
import { jsonResponse, stubFailingFetch, stubFetch } from "@iap/frontend-commons/fetch.fixture";

const treeJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "description": "Existing data or specimens only.",
    "RetrospectiveData": {
      "jcr:primaryType": "cat:Category",
      "label": "Retrospective Data Studies",
    },
  },
  "Paper": {
    "jcr:primaryType": "cat:Category",
    "label": "Paper submissions",
    "tags": ["retired"],
  },
};

const stubTree = () => stubFetch(() => jsonResponse(treeJson));

afterEach(() => vi.unstubAllGlobals());

describe("CategoriesWidget", () => {
  it("lists the top-level categories collapsed, condensed to labels only", async () => {
    stubTree();
    render(<CategoriesWidget />);

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    expect(screen.getByText("Paper submissions")).toBeInTheDocument();
    expect(screen.getByText("Retired")).toBeInTheDocument();
    // Top-level categories start collapsed
    expect(screen.queryByText("Retrospective Data Studies")).not.toBeInTheDocument();
    // Labels only
    expect(screen.queryByText("Existing data or specimens only.")).not.toBeInTheDocument();
  });

  it("expands and re-collapses a branch through its chevron", async () => {
    stubTree();
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
    stubFailingFetch(500);
    render(<CategoriesWidget />);

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("The categories could not be loaded");
    expect(report).toHaveTextContent("500");
  });

  it("reloads the tree when the load failure's Retry is used", async () => {
    stubFailingFetch(500);
    render(<CategoriesWidget />);
    await screen.findByRole("alert");

    // The next attempt succeeds
    stubTree();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("reports an empty tree", async () => {
    stubFetch(() => jsonResponse({ "jcr:primaryType": "cat:CategoriesHomepage" }));
    render(<CategoriesWidget />);

    expect(await screen.findByText("No categories are defined yet.")).toBeInTheDocument();
  });
});
