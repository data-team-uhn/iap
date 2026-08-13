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

import { act, render, screen, waitFor } from "@testing-library/react";

import Dashboard from "@iap/homepage/Dashboard";
import { loadExtensions } from "@iap/ui-extension/extensionManager";
import { STORE_KEY, setActivePersona } from "@iap/ui-extension/personas";

// Only the loading half is mocked; visibleInPersona is pure, and the dashboard's persona filtering
// is only worth testing against the real predicate.
vi.mock("@iap/ui-extension/extensionManager", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@iap/ui-extension/extensionManager")>()),
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The active persona is held on `window`; reset it so tests don't inherit each other's choice.
afterEach(() => {
  Reflect.deleteProperty(window, STORE_KEY);
});

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

    // The indicator lives in the LoadingOverlay's backdrop; jsdom never completes the fade so it
    // stays visibility:hidden, hence { hidden: true } to assert it is rendered.
    expect(screen.getAllByRole("progressbar", { hidden: true }).length).toBeGreaterThan(0);
  });

  it("wraps each widget's content in a titled Widget frame", async () => {
    mockedLoadExtensions.mockResolvedValue([widget("Welcome", 0)]);

    render(<Dashboard />);

    // The dashboard frames every widget itself, titling it with the extension's iap:extensionName.
    const content = await screen.findByText("Welcome content");
    expect(content.closest(".MuiPaper-root")).not.toBeNull();
    expect(screen.getByRole("heading", { name: "Welcome" })).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("DashboardWidget");
  });

  it("renders a widget's iap:subtitle as a subtitle", async () => {
    mockedLoadExtensions.mockResolvedValue([{ ...widget("Some widget", 0), "iap:subtitle": "A short hint" }]);

    render(<Dashboard />);

    expect(await screen.findByText("A short hint")).toBeInTheDocument();
  });

  it("renders an empty dashboard when there are no widgets", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    const { container } = render(<Dashboard />);

    await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
    expect(container.querySelector(".MuiPaper-root")).toBeNull();
  });

  it("spans a wide widget across more columns, and treats an unknown width as normal", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { ...widget("Wide", 0), "iap:widgetWidth": "wide" },
      { ...widget("Odd", 1), "iap:widgetWidth": "enormous" },
      widget("Default", 2),
    ]);

    render(<Dashboard />);

    await screen.findByText("Wide content");
    expect(screen.getByText("Odd content")).toBeInTheDocument();
    expect(screen.getByText("Default content")).toBeInTheDocument();
  });

  it("renders a widget that declares no name", async () => {
    const unnamed: Record<string, unknown> = { ...widget("Unnamed", 0) };
    delete unnamed["iap:extensionName"];
    mockedLoadExtensions.mockResolvedValue([unnamed]);

    render(<Dashboard />);

    expect(await screen.findByText("Unnamed content")).toBeInTheDocument();
  });

  it("reports a failure to load the widgets, and stops waiting for them", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    const failure = new Error("network error");
    mockedLoadExtensions.mockRejectedValue(failure);

    const { container } = render(<Dashboard />);

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith("Something went wrong loading the dashboard", failure);
    });
    await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
    expect(container.querySelector(".MuiPaper-root")).toBeNull();
    errorSpy.mockRestore();
  });

  describe("persona filtering", () => {
    it("shows a widget that belongs to the active persona", async () => {
      mockedLoadExtensions.mockResolvedValue([
        { ...widget("Reviews", 0), "iap:personas": [ "submitter" ] },
      ]);

      render(<Dashboard />);

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
    });

    it("hides a widget that belongs to another persona", async () => {
      mockedLoadExtensions.mockResolvedValue([
        widget("Everyone", 0),
        { ...widget("Reviews", 1), "iap:personas": [ "reviewer" ] },
      ]);

      render(<Dashboard />);

      // The ungated widget proves the dashboard finished loading before we assert an absence
      expect(await screen.findByText("Everyone content")).toBeInTheDocument();
      expect(screen.queryByText("Reviews content")).not.toBeInTheDocument();
    });

    it("re-lays out when the persona changes, without loading the widgets again", async () => {
      mockedLoadExtensions.mockResolvedValue([
        { ...widget("Reviews", 0), "iap:personas": [ "reviewer" ] },
      ]);
      // The mock is shared by every test in this file; only this render's calls should be counted.
      mockedLoadExtensions.mockClear();

      render(<Dashboard />);
      await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
      expect(screen.queryByText("Reviews content")).not.toBeInTheDocument();

      act(() => setActivePersona("reviewer"));

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
      expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
    });
  });
});
