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

import PageLayout from "@iap/homepage/PageLayout";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const SHELL_POINTS = ["FrameTop", "FrameBottom", "FrameStart", "FrameEnd", "PageTop", "PageBottom"];

// Builds an extension as returned by loadExtensions: the parsed iap:Extension JSON with the
// render asset already resolved to a component that displays "<name> content".
const ext = (name: string, props: Record<string, unknown> = {}) => ({
  "iap:extensionName": name,
  "iap:extensionRender": () => <div>{`${name} content`}</div>,
  ...props,
});

// Configures the loadExtensions mock to answer each extension point with the given extensions
// (and any point not listed with an empty list).
const mockPoints = (points: Record<string, Record<string, unknown>[]> = {}) =>
  mockedLoadExtensions.mockImplementation(point => Promise.resolve(points[point] ?? []));

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

  it("orders a region's extensions by their default order", async () => {
    mockPoints({
      FrameStart: [ext("Second", { "iap:defaultOrder": 2 }), ext("First", { "iap:defaultOrder": 1 })],
    });

    render(<PageLayout>main</PageLayout>);

    const first = await screen.findByText("First content");
    expectBefore(first, screen.getByText("Second content"));
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
    // A page region's extensions render straight into the scrolling middle, with no pinning.
    expect(screen.getByText("Page header content").parentElement).not.toHaveStyle({ position: "sticky" });
  });

  it("renders no rail at all for an empty side point", async () => {
    mockPoints({ FrameEnd: [ext("End rail")] });

    const { container } = render(<PageLayout>main</PageLayout>);

    await screen.findByText("End rail content");
    // Only the end rail rendered its <aside>; the empty start side contributed nothing.
    expect(container.querySelectorAll("aside").length).toBe(1);
  });

  it("pulls a rail over the content as a drawer from its narrow-screen pull tab", async () => {
    mockPoints({ FrameStart: [ext("Rail")] });

    render(<PageLayout>main</PageLayout>);

    // Before pulling, the rail content exists once (the regular rail, CSS-hidden when narrow).
    expect((await screen.findAllByText("Rail content")).length).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "Open the side panel" }));
    // The drawer renders the same extensions a second time, in an overlay.
    await waitFor(() => expect(screen.getAllByText("Rail content").length).toBe(2));
  });

  it("pulls a frame bar over the content as a drawer from its short-screen pull tab", async () => {
    mockPoints({ FrameTop: [ext("Top bar")] });

    const { container } = render(<PageLayout>main</PageLayout>);

    expect((await screen.findAllByText("Top bar content")).length).toBe(1);
    // The bar tabs only become visible below the collapse height; jsdom doesn't evaluate media
    // queries, so the tab keeps its display:none base style, which hides it from the
    // accessibility tree (and blanks its accessible name) — query it by attribute instead.
    const tab = container.querySelector('[aria-label="Open the top panel"]');
    expect(tab).not.toBeNull();
    fireEvent.click(tab as HTMLElement);
    await waitFor(() => expect(screen.getAllByText("Top bar content").length).toBe(2));
  });

  it("publishes the measured frame bar heights as CSS variables for the side rails", async () => {
    // jsdom has no ResizeObserver (the shell then just leaves the rails full-height) and no
    // layout, so stub the observer in and give every element a fixed measured height.
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
    // jsdom's own matchMedia never matches anything (so the hint machinery normally disables
    // itself, see useCollapseHint); stub one where "all" matches but the region-expanding
    // queries don't — i.e. a real, small screen with every region collapsed.
    const mediaQueryList = (matches: boolean) => ({
      matches,
      media: "",
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    });
    vi.stubGlobal("matchMedia", vi.fn((query: string) => mediaQueryList(query === "all")));
    // This environment has no localStorage either (the hint machinery treats that as
    // "hint already seen" and stays quiet), so stub one in
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
      // The hint appears after a short delay, and is remembered so it never flashes again
      expect(screen.queryByText("The side panel is available here")).toBeNull();
      expect(await screen.findByText("The side panel is available here", {}, { timeout: 3000 }))
        .toBeInTheDocument();
      expect(stored.get("iap.pullTabHintSeen")).toBe("true");
    } finally {
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
