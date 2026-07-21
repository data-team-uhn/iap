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

// Builds an app bar entry as returned by loadExtensions: the parsed iap:Extension JSON with the
// render asset already resolved to a component that displays "<name> content".
const entry = (name: string, props: Record<string, unknown> = {}) => ({
  "iap:extensionName": name,
  "iap:extensionRender": () => <span>{`${name} content`}</span>,
  ...props,
});

// Asserts that `first` comes before `second` in document order.
const expectBefore = (first: Element, second: Element) =>
  expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

describe("AppBar", () => {
  it("renders the entries grouped into their sections, in row order", async () => {
    mockedLoadExtensions.mockResolvedValue([
      entry("Account", { "iap:appBarSection": "end", "iap:defaultOrder": 2 }),
      entry("Search", { "iap:appBarSection": "middle" }),
      entry("Theme", { "iap:appBarSection": "end", "iap:defaultOrder": 1 }),
      entry("Brand", { "iap:appBarSection": "start" }),
    ]);

    render(<AppBar />);

    const brand = await screen.findByText("Brand content");
    const search = screen.getByText("Search content");
    const theme = screen.getByText("Theme content");
    const account = screen.getByText("Account content");
    expectBefore(brand, search);
    expectBefore(search, theme);
    // Within a section, iap:defaultOrder decides
    expectBefore(theme, account);
    expect(mockedLoadExtensions).toHaveBeenCalledWith("AppBarEntry");
  });

  it("defaults an entry without a declared section to the start section", async () => {
    mockedLoadExtensions.mockResolvedValue([
      entry("End control", { "iap:appBarSection": "end" }),
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
});
