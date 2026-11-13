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

import { createTheme, ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

import PageLayout from "@iap/homepage/PageLayout";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// How long after a page opens with a region collapsed its hint appears, in milliseconds
const HINT_APPEAR_DELAY = 1500;

const SHELL_POINTS = ["FrameTop", "FrameBottom", "FrameStart", "FrameEnd", "PageTop", "PageBottom"];

// An extension as loadExtensions gives it, whose component shows "<name> content"
const ext = (name: string, props: Record<string, unknown> = {}) => ({
  "ext:name": name,
  "ext:render": () => <div>{`${name} content`}</div>,
  ...props,
});

// Answers each extension point with the given extensions, and any other point with none
const mockPoints = (points: Record<string, Record<string, unknown>[]> = {}) =>
  mockedLoadExtensions.mockImplementation(point => Promise.resolve(points[point] ?? []));

// A screen's answer to a media query, which never reports a change
const mediaQueryList = (matches: boolean) => ({
  matches,
  media: "",
  onchange: null,
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
});

// A matchMedia for a small screen, with every region collapsed: it matches "all" and nothing else
const stubCollapsedScreen = () =>
  vi.stubGlobal("matchMedia", vi.fn((query: string) => mediaQueryList(query === "all")));

// A matchMedia that matches "all" and nothing else, and records every query it is asked
const recordMediaQueries = () => {
  const queries: string[] = [];
  vi.stubGlobal("matchMedia", vi.fn((query: string) => {
    queries.push(query);
    return mediaQueryList(query === "all");
  }));
  return queries;
};

// The layout as a page that has just opened finds it, before any region has claimed the once-ever hint
const freshPageLayout = async () => {
  vi.resetModules();
  return (await import("@iap/homepage/PageLayout")).default;
};

// Finds a region's pull tab once it has rendered. A tab shows only below its region's breakpoint, and
// jsdom matches media queries against a 1024 by 768 viewport that a test cannot resize, so a tab keeps
// `display: none`, which hides it from the accessibility tree. It is found by its label instead.
const findPullTab = (container: HTMLElement, label: string) =>
  waitFor(() => {
    const tab = container.querySelector<HTMLElement>(`[aria-label="${label}"]`);
    expect(tab).not.toBeNull();
    return tab!;
  });

// Asserts that `first` comes before `second` in document order.
const expectBefore = (first: Element, second: Element) =>
  expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

describe("PageLayout", () => {
  it("renders the main content even when every extension point is empty", async () => {
    mockPoints();

    render(<PageLayout><div>Main content</div></PageLayout>);

    expect(await screen.findByText("Main content")).toBeInTheDocument();
    for (const point of SHELL_POINTS) {
      expect(mockedLoadExtensions).toHaveBeenCalledWith(point);
    }
  });

  it("renders the shell regions around the main content, in page order", async () => {
    mockPoints({
      FrameTop: [ext("Top bar")],
      FrameBottom: [ext("Bottom bar")],
      FrameStart: [ext("Start rail")],
      FrameEnd: [ext("End rail")],
      PageTop: [ext("Page header")],
      PageBottom: [ext("Page footer")],
    });

    render(<PageLayout><div>Main content</div></PageLayout>);

    const topBar = await screen.findByText("Top bar content");
    const bottomBar = await screen.findByText("Bottom bar content");
    const startRail = screen.getByText("Start rail content");
    const endRail = screen.getByText("End rail content");
    const pageHeader = screen.getByText("Page header content");
    const pageFooter = screen.getByText("Page footer content");
    const main = screen.getByText("Main content");
    expectBefore(topBar, startRail);
    expectBefore(startRail, pageHeader);
    expectBefore(pageHeader, main);
    expectBefore(main, pageFooter);
    expectBefore(pageFooter, endRail);
    expectBefore(endRail, bottomBar);
  });

  it("pins the frame bars while the page regions scroll with the content", async () => {
    mockPoints({
      FrameTop: [ext("Top bar")],
      FrameBottom: [ext("Bottom bar")],
      PageTop: [ext("Page header")],
    });

    render(<PageLayout>main</PageLayout>);

    expect((await screen.findByText("Top bar content")).parentElement).toHaveStyle({ position: "sticky" });
    expect((await screen.findByText("Bottom bar content")).parentElement).toHaveStyle({ position: "sticky" });
    expect(screen.getByText("Page header content").parentElement).not.toHaveStyle({ position: "sticky" });
  });

  it("renders no rail at all for an empty side point", async () => {
    mockPoints({ FrameEnd: [ext("End rail")] });

    const { container } = render(<PageLayout>main</PageLayout>);

    await screen.findByText("End rail content");
    expect(container.querySelectorAll("aside").length).toBe(1);
  });

  it("pulls a rail over the content as a drawer from its narrow-screen pull tab", async () => {
    mockPoints({ FrameStart: [ext("Rail")] });

    const { container } = render(<PageLayout>main</PageLayout>);

    // Once in the rail, which CSS hides on a narrow screen
    expect((await screen.findAllByText("Rail content")).length).toBe(1);
    fireEvent.click(await findPullTab(container, "Open the side panel"));
    // And once more in the drawer
    await waitFor(() => expect(screen.getAllByText("Rail content").length).toBe(2));
  });

  it("pulls a frame bar over the content as a drawer from its short-screen pull tab", async () => {
    mockPoints({ FrameTop: [ext("Top bar")] });

    const { container } = render(<PageLayout>main</PageLayout>);

    expect((await screen.findAllByText("Top bar content")).length).toBe(1);
    fireEvent.click(await findPullTab(container, "Open the top panel"));
    await waitFor(() => expect(screen.getAllByText("Top bar content").length).toBe(2));
  });

  it("publishes the measured frame bar heights as CSS variables for the side rails", async () => {
    // jsdom has no layout. Every element is 48px high, and the observer never reports a change.
    vi.stubGlobal("ResizeObserver", class {
      observe() {}
      unobserve() {}
      disconnect() {}
    });
    const originalOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");
    Object.defineProperty(HTMLElement.prototype, "offsetHeight", { configurable: true, value: 48 });
    try {
      mockPoints({
        FrameTop: [ext("Top bar")],
        FrameStart: [ext("Rail")],
      });

      const { container } = render(<PageLayout>main</PageLayout>);

      await screen.findByText("Rail content");
      const shell = container.firstChild as HTMLElement;
      await waitFor(() => expect(shell.style.getPropertyValue("--iap-frame-top")).toBe("48px"));
    } finally {
      vi.unstubAllGlobals();
      if (originalOffsetHeight) {
        Object.defineProperty(HTMLElement.prototype, "offsetHeight", originalOffsetHeight);
      }
    }
  });

  it("flashes a once-ever hint on the pull tab when the page opens with a region collapsed", async () => {
    // jsdom has no matchMedia, and no hint shows without one
    stubCollapsedScreen();
    // A storage of the test's own, so that no hint has been seen yet, and what is remembered can be
    // read
    const stored = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => stored.get(key) ?? null,
      setItem: (key: string, value: string) => stored.set(key, value),
      removeItem: (key: string) => stored.delete(key),
      clear: () => stored.clear(),
    });
    try {
      mockPoints({ FrameStart: [ext("Rail")] });

      render(<PageLayout>main</PageLayout>);

      await screen.findByText("Rail content");
      // The hint appears after a delay, and is remembered
      expect(screen.queryByText("The side panel is available here")).toBeNull();
      expect(await screen.findByText("The side panel is available here", {}, { timeout: 3000 }))
        .toBeInTheDocument();
      expect(stored.get("iap.pullTabHintSeen")).toBe("true");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  // A matchMedia that matches "all" and none of the queries that expand a region. The function it
  // returns fires its "change" listeners, as when the viewport crosses a region's breakpoint.
  const stubResizableMedia = () => {
    const listeners: ((event: MediaQueryListEvent) => void)[] = [];
    vi.stubGlobal("matchMedia", vi.fn((query: string) => ({
      matches: query === "all",
      media: query,
      onchange: null,
      addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.push(listener),
      removeEventListener: () => { /* the component's cleanup, not observed here */ },
      dispatchEvent: () => false,
    })));
    return (matches: boolean) => {
      act(() => { listeners.forEach(listener => { listener({ matches } as MediaQueryListEvent); }); });
    };
  };

  // A storage saying the hint was already shown when a page opened, which leaves only the hint on
  // a collapse
  const stubSeenHint = () => vi.stubGlobal("localStorage", {
    getItem: () => "true",
    setItem: () => { /* nothing to remember */ },
    removeItem: () => { /* unused */ },
    clear: () => { /* unused */ },
  });

  it("flashes the hint when a region collapses live, and takes it down again after a while", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const crossBreakpoint = stubResizableMedia();
    stubSeenHint();
    try {
      mockPoints({ FrameStart: [ext("Rail")] });
      render(<PageLayout>main</PageLayout>);
      await screen.findByText("Rail content");
      expect(screen.queryByText("The side panel is available here")).toBeNull();

      // The region collapses, and the hint appears after its delay
      crossBreakpoint(false);
      await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
      expect(screen.getByText("The side panel is available here")).toBeInTheDocument();

      // It goes away after its duration
      await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
      await waitFor(() => { expect(screen.queryByText("The side panel is available here")).toBeNull(); });
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
    }
  });

  it("takes the hint down as soon as the region expands again", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const crossBreakpoint = stubResizableMedia();
    stubSeenHint();
    try {
      mockPoints({ FrameStart: [ext("Rail")] });
      render(<PageLayout>main</PageLayout>);
      await screen.findByText("Rail content");
      crossBreakpoint(false);
      await act(async () => { await vi.advanceTimersByTimeAsync(1500); });
      expect(screen.getByText("The side panel is available here")).toBeInTheDocument();

      crossBreakpoint(true);

      await waitFor(() => { expect(screen.queryByText("The side panel is available here")).toBeNull(); });
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
    }
  });

  it("names the pull tab in a tooltip while it is hovered", async () => {
    mockPoints({ FrameStart: [ext("Rail")] });
    const { container } = render(<PageLayout>main</PageLayout>);
    const tab = await findPullTab(container, "Open the side panel");

    fireEvent.mouseOver(tab);

    expect(await screen.findByRole("tooltip")).toHaveTextContent("Open the side panel");

    fireEvent.mouseLeave(tab);

    await waitFor(() => { expect(screen.queryByRole("tooltip")).not.toBeInTheDocument(); });
  });

  it("closes the rail drawer again", async () => {
    mockPoints({ FrameStart: [ext("Rail")] });
    const { container } = render(<PageLayout>main</PageLayout>);
    fireEvent.click(await findPullTab(container, "Open the side panel"));
    await waitFor(() => { expect(screen.getAllByText("Rail content")).toHaveLength(2); });

    fireEvent.keyDown(screen.getAllByText("Rail content")[1], { key: "Escape", code: "Escape" });

    await waitFor(() => { expect(screen.getAllByText("Rail content")).toHaveLength(1); });
  });

  it("closes the frame bar drawer again", async () => {
    mockPoints({ FrameTop: [ext("Top bar")] });
    const { container } = render(<PageLayout>main</PageLayout>);
    fireEvent.click(await findPullTab(container, "Open the top panel"));
    await waitFor(() => { expect(screen.getAllByText("Top bar content")).toHaveLength(2); });

    fireEvent.keyDown(screen.getAllByText("Top bar content")[1], { key: "Escape", code: "Escape" });

    await waitFor(() => { expect(screen.getAllByText("Top bar content")).toHaveLength(1); });
  });

  it("takes the region dimensions the theme configures, rather than its own defaults", async () => {
    // Sizes the shell would never pick for itself
    const theme = createTheme({
      iapShell: { frameStart: { width: 321, collapseWidth: 777 }, frameTop: { collapseHeight: 654 } },
    });
    const queries = recordMediaQueries();
    try {
      mockPoints({ FrameStart: [ext("Rail")], FrameTop: [ext("Top bar")] });

      render(
        <ThemeProvider theme={theme}>
          <PageLayout>main</PageLayout>
        </ThemeProvider>
      );

      expect((await screen.findByText("Rail content")).closest("aside")).toHaveStyle({ inlineSize: "321px" });
      // Each region asks the screen whether it is expanded, at the size the theme gave it
      expect(queries).toContain("(min-width:777px)");
      expect(queries).toContain("(min-height: 654px)");
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("stays quiet about the hint when there is nowhere to remember it", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubCollapsedScreen();
    // Storage that throws on every access, as a browser blocking it would
    vi.stubGlobal("localStorage", {
      getItem: () => { throw new Error("storage blocked"); },
      setItem: () => { throw new Error("storage blocked"); },
      removeItem: () => { /* unused */ },
      clear: () => { /* unused */ },
    });
    try {
      mockPoints({ FrameStart: [ext("Rail")] });
      const FreshPageLayout = await freshPageLayout();

      render(<FreshPageLayout>main</FreshPageLayout>);
      await screen.findByText("Rail content");
      // Well past the delay after which a page opening with the region collapsed shows the hint
      await act(async () => { await vi.advanceTimersByTimeAsync(HINT_APPEAR_DELAY * 2); });

      expect(screen.queryByText("The side panel is available here")).toBeNull();
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
    }
  });

  it("still renders the other regions when one extension point fails to load", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    mockedLoadExtensions.mockImplementation(point =>
      point === "FrameEnd" ? Promise.reject("uixp error") : Promise.resolve([ext(point)]));

    render(<PageLayout><div>Main content</div></PageLayout>);

    expect(await screen.findByText("FrameTop content")).toBeInTheDocument();
    expect(await screen.findByText("PageBottom content")).toBeInTheDocument();
    expect(screen.getByText("Main content")).toBeInTheDocument();
    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
