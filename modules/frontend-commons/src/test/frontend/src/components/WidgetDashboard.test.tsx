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

import type { ReactNode } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import WidgetDashboard from "@iap/frontend-commons/components/WidgetDashboard";
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

// Builds a widget extension as returned by loadExtensions: the parsed ext:Extension
// JSON with the render asset already resolved to a component.
const widget = (name: string, order: number) => ({
  "ext:name": name,
  "defaultOrder": order,
  "ext:render": () => <div>{`${name} content`}</div>,
});

// Builds a widget group node as returned by loadExtensions: a data-only extension on the same point,
// identified by its node name and titled by its ext:name.
const group = (name: string, label = name) => ({
  "@name": name,
  "ext:name": label,
  "ext:isWidgetGroup": true,
});

// A widget filed under a group.
const grouped = (name: string, order: number, groupName: string) => ({
  ...widget(name, order),
  "ext:widgetGroup": groupName,
});

describe("WidgetDashboard", () => {
  it("shows a loading indicator until the widgets are retrieved", () => {
    // A promise that never resolves keeps the dashboard in its loading state
    mockedLoadExtensions.mockReturnValue(new Promise(() => undefined));

    render(<WidgetDashboard point="TestWidgets" />);

    // The indicator lives in the LoadingOverlay's backdrop; jsdom never completes the fade so it
    // stays visibility:hidden, hence { hidden: true } to assert it is rendered.
    expect(screen.getAllByRole("progressbar", { hidden: true }).length).toBeGreaterThan(0);
  });

  it("wraps each of the given point's widgets in a titled Widget frame", async () => {
    mockedLoadExtensions.mockResolvedValue([widget("Welcome", 0)]);

    render(<WidgetDashboard point="TestWidgets" />);

    // The dashboard frames every widget itself, titling it with the extension's ext:name.
    const content = await screen.findByText("Welcome content");
    expect(content.closest(".MuiPaper-root")).not.toBeNull();
    expect(screen.getByRole("heading", { name: "Welcome" })).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("TestWidgets");
  });

  it("renders a widget's ext:subtitle as a subtitle", async () => {
    mockedLoadExtensions.mockResolvedValue([{ ...widget("Some widget", 0), "ext:subtitle": "A short hint" }]);

    render(<WidgetDashboard point="TestWidgets" />);

    expect(await screen.findByText("A short hint")).toBeInTheDocument();
  });

  it("renders a header action linking to the widget's target when ext:actionLabel is set", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { ...widget("Categories", 0), "ext:actionLabel": "Configure", "ext:targetURL": "/admin/categories" },
      // Without a label there is no action, even with a target
      { ...widget("Plain", 1), "ext:targetURL": "/somewhere" },
    ]);

    render(<MemoryRouter><WidgetDashboard point="TestWidgets" /></MemoryRouter>);

    // The label is what is shown, while the accessible name also carries the widget's title, so
    // that a verb shared by several widgets still says where it leads.
    const action = await screen.findByRole("link", { name: "Configure: Categories" });
    expect(action).toHaveTextContent("Configure");
    expect(action).toHaveAttribute("href", "/admin/categories");
    expect(screen.getAllByRole("link")).toHaveLength(1);
  });

  it("names a header action by its label alone when the widget has no title", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { ...widget("", 0), "ext:actionLabel": "Configure", "ext:targetURL": "/admin/categories" },
    ]);

    render(<MemoryRouter><WidgetDashboard point="TestWidgets" /></MemoryRouter>);

    expect(await screen.findByRole("link", { name: "Configure" })).toBeInTheDocument();
  });

  it("renders nothing when there are no widgets and no empty state was given", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    const { container } = render(<WidgetDashboard point="TestWidgets" />);

    await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the given empty state when there are no widgets", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    render(<WidgetDashboard point="TestWidgets" empty={<span>Nothing to see</span>} />);

    expect(await screen.findByText("Nothing to see")).toBeInTheDocument();
  });

  it("spans a wide widget further, and treats an unrecognised width as normal", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { ...widget("Wide", 0), "ext:widgetWidth": "wide" },
      { ...widget("Odd", 1), "ext:widgetWidth": "enormous" },
      widget("Plain", 2),
    ]);

    render(<MemoryRouter><WidgetDashboard point="TestWidgets" /></MemoryRouter>);

    expect(await screen.findByText("Wide content")).toBeInTheDocument();
    expect(screen.getByText("Odd content")).toBeInTheDocument();
    expect(screen.getByText("Plain content")).toBeInTheDocument();
  });

  it("renders a widget that declares no name", async () => {
    const unnamed: Record<string, unknown> = { ...widget("Unnamed", 0) };
    delete unnamed["ext:name"];
    mockedLoadExtensions.mockResolvedValue([unnamed]);

    render(<MemoryRouter><WidgetDashboard point="TestWidgets" /></MemoryRouter>);

    expect(await screen.findByText("Unnamed content")).toBeInTheDocument();
  });

  it("keys widgets by their path, so widgets sharing a name are told apart", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    mockedLoadExtensions.mockResolvedValue([
      { ...widget("Summary", 0), "@path": "/Extensions/DashboardWidget/One" },
      { ...widget("Summary", 1), "@path": "/Extensions/DashboardWidget/Two" },
    ]);

    try {
      render(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findAllByText("Summary content")).toHaveLength(2);
      // React reports duplicate keys through console.error
      expect(consoleError).not.toHaveBeenCalledWith(expect.stringContaining("same key"), expect.anything());
    } finally {
      consoleError.mockRestore();
    }
  });

  it("renders the empty state, not a crash, when the extension point fails to load", async () => {
    mockedLoadExtensions.mockRejectedValue(new Error("network down"));

    render(<WidgetDashboard point="TestWidgets" empty={<span>Nothing to see</span>} />);

    expect(await screen.findByText("Nothing to see")).toBeInTheDocument();
  });

  describe("persona filtering", () => {
    it("shows a widget that belongs to the active persona", async () => {
      mockedLoadExtensions.mockResolvedValue([
        { ...widget("Reviews", 0), "ext:personas": [ "submitter" ] },
      ]);

      render(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
    });

    it("hides a widget that belongs to another persona", async () => {
      mockedLoadExtensions.mockResolvedValue([
        widget("Everyone", 0),
        { ...widget("Reviews", 1), "ext:personas": [ "reviewer" ] },
      ]);

      render(<WidgetDashboard point="TestWidgets" />);

      // The ungated widget proves the dashboard finished loading before we assert an absence
      expect(await screen.findByText("Everyone content")).toBeInTheDocument();
      expect(screen.queryByText("Reviews content")).not.toBeInTheDocument();
    });

    it("re-lays out when the persona changes, without loading the widgets again", async () => {
      mockedLoadExtensions.mockResolvedValue([
        { ...widget("Reviews", 0), "ext:personas": [ "reviewer" ] },
      ]);
      // The mock is shared by every test in this file; only this render's calls should be counted.
      mockedLoadExtensions.mockClear();

      render(<WidgetDashboard point="TestWidgets" />);
      await waitFor(() => expect(screen.queryByRole("progressbar")).toBeNull());
      expect(screen.queryByText("Reviews content")).not.toBeInTheDocument();

      act(() => setActivePersona("reviewer"));

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
      expect(mockedLoadExtensions).toHaveBeenCalledTimes(1);
    });
  });

  describe("widget groups", () => {
    // Two groups of two and one widgets, plus one widget in no group.
    const groupedDashboard = () => [
      group("Configuration"),
      group("Operations"),
      grouped("Categories", 10, "Configuration"),
      grouped("Workflows", 20, "Configuration"),
      grouped("Errors", 30, "Operations"),
      widget("Loose", 40),
    ];

    const groupToggle = (name: string) => screen.getByRole("button", { name });

    // A group title's semantic element comes from the app theme's `subheading` variant mapping, so
    // these renders need the real theme around them.
    const Themed = ({ children }: { children: ReactNode }) => (
      <ThemeProvider theme={appTheme}>{children}</ThemeProvider>
    );
    const renderThemed = (ui: ReactNode) => render(ui, { wrapper: Themed });

    // Collapsed groups are remembered in localStorage, which this environment does not reliably
    // provide; each test gets a fresh one of its own.
    let stored: Map<string, string>;
    beforeEach(() => {
      stored = new Map<string, string>();
      vi.stubGlobal("localStorage", {
        getItem: (key: string) => stored.get(key) ?? null,
        setItem: (key: string, value: string) => stored.set(key, value),
      });
    });
    afterEach(() => {
      vi.unstubAllGlobals();
    });
    const remembered = (point: string): unknown =>
      JSON.parse(stored.get(`iap.widgetDashboard.${point}.collapsed`) ?? "null");

    it("titles each group with a level-2 heading, and lists ungrouped widgets under Other", async () => {
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 2 }).map(heading => heading.textContent))
        .toEqual([ "Configuration", "Operations", "Other" ]);
      expect(groupToggle("Configuration")).toHaveAttribute("aria-expanded", "true");
      // Every widget is still framed, but the group nodes are not rendered as widgets
      expect(screen.getAllByText(/ content$/)).toHaveLength(4);
      expect(document.querySelectorAll(".MuiPaper-root")).toHaveLength(4);
    });

    it("does not title the widgets when none of them is in a group", async () => {
      mockedLoadExtensions.mockResolvedValue([ group("Unused"), widget("One", 0), grouped("Two", 1, "Missing") ]);

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("One content")).toBeInTheDocument();
      // A widget naming a group that does not exist is kept, just not under a title
      expect(screen.getByText("Two content")).toBeInTheDocument();
      expect(screen.queryByRole("heading", { level: 2 })).not.toBeInTheDocument();
      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });

    it("collapses a group down to its title and widget count, and expands it again", async () => {
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      renderThemed(<WidgetDashboard point="TestWidgets" />);
      await screen.findByText("Categories content");

      fireEvent.click(groupToggle("Configuration"));

      expect(groupToggle("Configuration (2)")).toHaveAttribute("aria-expanded", "false");
      await waitFor(() => expect(screen.queryByText("Categories content")).not.toBeInTheDocument());
      expect(screen.queryByText("Workflows content")).not.toBeInTheDocument();
      // The other groups are untouched
      expect(screen.getByText("Errors content")).toBeInTheDocument();
      expect(screen.getByText("Loose content")).toBeInTheDocument();

      fireEvent.click(groupToggle("Configuration (2)"));

      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      expect(groupToggle("Configuration")).toHaveAttribute("aria-expanded", "true");
    });

    it("collapses Other like any other group", async () => {
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      renderThemed(<WidgetDashboard point="TestWidgets" />);
      await screen.findByText("Loose content");

      fireEvent.click(groupToggle("Other"));

      expect(groupToggle("Other (1)")).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText("Loose content")).not.toBeInTheDocument());
    });

    it("remembers which groups were collapsed the next time the dashboard is shown", async () => {
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      const { unmount } = renderThemed(<WidgetDashboard point="TestWidgets" />);
      await screen.findByText("Categories content");
      fireEvent.click(groupToggle("Configuration"));
      expect(remembered("TestWidgets")).toEqual([ "Configuration" ]);
      unmount();

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Errors content")).toBeInTheDocument();
      expect(groupToggle("Configuration (2)")).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByText("Categories content")).not.toBeInTheDocument();
    });

    it("remembers collapsed groups per dashboard", async () => {
      stored.set("iap.widgetDashboard.OtherWidgets.collapsed", JSON.stringify([ "Operations" ]));
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      const { rerender } = renderThemed(<WidgetDashboard point="TestWidgets" />);
      await screen.findByText("Errors content");

      rerender(<WidgetDashboard point="OtherWidgets" />);

      expect(await screen.findByRole("button", { name: "Operations (1)" })).toBeInTheDocument();
      // A choice made on this dashboard is remembered for this dashboard alone
      fireEvent.click(groupToggle("Configuration"));
      expect(remembered("OtherWidgets")).toEqual([ "Operations", "Configuration" ]);
      expect(remembered("TestWidgets")).toBeNull();
    });

    it("starts expanded when what is remembered cannot be read", async () => {
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      stored.set("iap.widgetDashboard.TestWidgets.collapsed", "not json");
      const { unmount } = renderThemed(<WidgetDashboard point="TestWidgets" />);
      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      unmount();

      stored.set("iap.widgetDashboard.TestWidgets.collapsed", JSON.stringify({ Configuration: true }));
      renderThemed(<WidgetDashboard point="TestWidgets" />);
      expect(await screen.findByText("Categories content")).toBeInTheDocument();
    });

    it("ignores remembered entries that are not group names", async () => {
      stored.set("iap.widgetDashboard.TestWidgets.collapsed", JSON.stringify([ 3, "Operations" ]));
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      expect(groupToggle("Operations (1)")).toBeInTheDocument();
    });

    it("still collapses groups when storage is unavailable", async () => {
      const blocked = vi.fn(() => {
        throw new Error("blocked");
      });
      vi.stubGlobal("localStorage", { getItem: blocked, setItem: blocked });
      mockedLoadExtensions.mockResolvedValue(groupedDashboard());

      renderThemed(<WidgetDashboard point="TestWidgets" />);
      expect(await screen.findByText("Categories content")).toBeInTheDocument();

      fireEvent.click(groupToggle("Configuration"));

      expect(groupToggle("Configuration (2)")).toBeInTheDocument();
      // Both the read and the write were attempted, and their failures swallowed
      expect(blocked).toHaveBeenCalledWith("iap.widgetDashboard.TestWidgets.collapsed");
      expect(blocked).toHaveBeenCalledWith("iap.widgetDashboard.TestWidgets.collapsed", "[\"Configuration\"]");
    });

    it("drops a group whose widgets all belong to another persona", async () => {
      mockedLoadExtensions.mockResolvedValue([
        group("Configuration"),
        group("Reviewing"),
        grouped("Categories", 10, "Configuration"),
        { ...grouped("Reviews", 20, "Reviewing"), "ext:personas": [ "reviewer" ] },
      ]);

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 2 }).map(heading => heading.textContent))
        .toEqual([ "Configuration" ]);

      act(() => setActivePersona("reviewer"));

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 2 }).map(heading => heading.textContent))
        .toEqual([ "Configuration", "Reviewing" ]);
    });

    it("hides a group limited to another persona, widgets included", async () => {
      mockedLoadExtensions.mockResolvedValue([
        group("Configuration"),
        { ...group("Reviewing"), "ext:personas": [ "reviewer" ] },
        grouped("Categories", 10, "Configuration"),
        grouped("Reviews", 20, "Reviewing"),
      ]);

      renderThemed(<WidgetDashboard point="TestWidgets" />);

      expect(await screen.findByText("Categories content")).toBeInTheDocument();
      // Hidden with its group, not moved to Other
      expect(screen.queryByText("Reviews content")).not.toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 2 }).map(heading => heading.textContent))
        .toEqual([ "Configuration" ]);

      act(() => setActivePersona("reviewer"));

      expect(await screen.findByText("Reviews content")).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 2 }).map(heading => heading.textContent))
        .toEqual([ "Configuration", "Reviewing" ]);
    });

    it("renders the empty state when there are groups but no widgets", async () => {
      mockedLoadExtensions.mockResolvedValue([ group("Configuration") ]);

      renderThemed(<WidgetDashboard point="TestWidgets" empty={<span>Nothing to see</span>} />);

      expect(await screen.findByText("Nothing to see")).toBeInTheDocument();
    });
  });
});
