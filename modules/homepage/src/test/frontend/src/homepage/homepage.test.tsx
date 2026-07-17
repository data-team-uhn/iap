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

import { render, screen, waitForElementToBeRemoved } from "@testing-library/react";

import Dashboard from "./homepage";
import { loadExtensions } from "../uiextension/extensionManager";

vi.mock("../uiextension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// Builds a widget extension as returned by loadExtensions: the parsed iap:Extension
// JSON with the render asset already resolved to a component.
const widget = (name: string, order: number) => ({
  "iap:extensionName": name,
  "iap:defaultOrder": order,
  "iap:extensionRender": () => <div>{`${name} content`}</div>,
});

describe("Dashboard", () => {
  it("shows a loading indicator until the widgets are retrieved", () => {
    // A promise that never resolves keeps the dashboard in its loading state
    mockedLoadExtensions.mockReturnValue(new Promise(() => {}));

    render(<Dashboard />);

    expect(screen.getByRole("progressbar")).toBeInTheDocument();
  });

  it("renders each widget's content wrapped in a Paper element", async () => {
    mockedLoadExtensions.mockResolvedValue([widget("Welcome", 0)]);

    render(<Dashboard />);

    const content = await screen.findByText("Welcome content");
    expect(content.closest(".MuiPaper-root")).not.toBeNull();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("DashboardWidget");
  });

  it("renders the widgets in their default order", async () => {
    mockedLoadExtensions.mockResolvedValue([widget("Second", 2), widget("First", 1)]);

    render(<Dashboard />);

    const first = await screen.findByText("First content");
    const second = screen.getByText("Second content");
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("renders an empty dashboard when there are no widgets", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    const { container } = render(<Dashboard />);

    await waitForElementToBeRemoved(() => screen.queryByRole("progressbar"));
    expect(container.querySelector(".MuiPaper-root")).toBeNull();
  });
});
