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

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import Main from "@iap/homepage/homepage";
import { getRoutes } from "@iap/frontend-commons/routes";

vi.mock("@iap/frontend-commons/routes", () => ({
  getRoutes: vi.fn(),
}));

const mockedGetRoutes = vi.mocked(getRoutes);

function renderMain(initialEntries: string[]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Main />
    </MemoryRouter>
  );
}

// Builds a view extension as returned by getRoutes: the parsed iap:Extension JSON with the
// render asset already resolved to a component. Whether that resolution happened eagerly
// or lazily (see extensionManager.tsx) is transparent to Main, so it isn't exercised here.
const view = (name: string, targetURL: string) => ({
  "iap:extensionName": name,
  "iap:targetURL": targetURL,
  "iap:extensionRender": () => <div>{`${name} view`}</div>,
});

describe("Main routing", () => {
  it("renders the view whose targetURL matches the current URL", async () => {
    mockedGetRoutes.mockResolvedValue([
      view("Dashboard", "/"),
      view("Other", "/other"),
    ]);

    renderMain(["/"]);

    expect(await screen.findByText("Dashboard view")).toBeInTheDocument();
    expect(screen.queryByText("Other view")).not.toBeInTheDocument();
    expect(mockedGetRoutes).toHaveBeenCalled();
  });

  it("renders nothing when no view matches the current URL", async () => {
    mockedGetRoutes.mockResolvedValue([view("Dashboard", "/")]);

    renderMain(["/unknown"]);

    // Give the effect a chance to resolve the (non-matching) views
    await vi.waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(screen.queryByText("Dashboard view")).not.toBeInTheDocument();
  });

  it("treats a failure to load the views as an empty list of routes", async () => {
    mockedGetRoutes.mockRejectedValue(new Error("network error"));

    renderMain(["/"]);

    await vi.waitFor(() => expect(mockedGetRoutes).toHaveBeenCalled());
    expect(screen.queryByText("Dashboard view")).not.toBeInTheDocument();
  });
});
