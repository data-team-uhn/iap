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

import AppBar from "@iap/homepage/AppBar";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// Builds an app bar entry as returned by loadExtensions: the parsed ext:Extension JSON with the
// render asset already resolved to a component that displays "<name> content".
const entry = (name: string, props: Record<string, unknown> = {}) => ({
  "ext:name": name,
  "ext:render": () => <span>{`${name} content`}</span>,
  ...props,
});

// Asserts that `first` comes before `second` in document order.
const expectBefore = (first: Element, second: Element) =>
  expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

describe("AppBar", () => {
  it("renders the entries grouped into their sections, in row order", async () => {
    mockedLoadExtensions.mockResolvedValue([
      entry("Account", { "ext:appBarSection": "end", "defaultOrder": 1 }),
      entry("Search", { "ext:appBarSection": "middle" }),
      entry("Theme", { "ext:appBarSection": "end", "defaultOrder": 2 }),
      entry("Brand", { "ext:appBarSection": "start" }),
    ]);

    render(<AppBar />);

    const brand = await screen.findByText("Brand content");
    const search = screen.getByText("Search content");
    const theme = screen.getByText("Theme content");
    const account = screen.getByText("Account content");
    expectBefore(brand, search);
    expectBefore(search, theme);
    // Within a section, defaultOrder decides
    expectBefore(account, theme);
    expect(mockedLoadExtensions).toHaveBeenCalledWith("AppBarEntry");
  });

  it("defaults an entry without a declared section to the start section", async () => {
    mockedLoadExtensions.mockResolvedValue([
      entry("End control", { "ext:appBarSection": "end" }),
      entry("Unplaced"),
    ]);

    render(<AppBar />);

    expectBefore(await screen.findByText("Unplaced content"), screen.getByText("End control content"));
  });

  it("renders an empty bar when nothing is registered", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    const { container } = render(<AppBar />);

    // The toolbar frame itself still renders (an empty strip), just with no content
    expect(container.querySelector(".MuiToolbar-root")).not.toBeNull();
  });

  it("reports a failure to load the entries, and still renders the bar", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    const failure = new Error("network error");
    mockedLoadExtensions.mockRejectedValue(failure);

    const { container } = render(<AppBar />);

    await vi.waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith("Something went wrong loading the app bar entries", failure);
    });
    expect(container.querySelector(".MuiToolbar-root")).not.toBeNull();
    errorSpy.mockRestore();
  });
});
