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
import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import PdfViewer, { type PdfQuotePassage } from "@iap/frontend-commons/components/PdfViewer";

import { fakePdf, QUOTE, stubCanvasContext, stubFetch } from "../pdfjs.fixture";

vi.mock("pdfjs-dist", async () => (await import("../pdfjs.fixture")).createPdfjsModule());

const PDF = "/Submissions/demo-1/proposal/1/file/file.pdf";
const TEXT_UNREADABLE = "The text of this page could not be read, so nothing is marked.";
const MARKED = "The passage is marked on this page.";
// A 600-point page is fitted to 860 pixels: this width minus the gutter beside the page.
const FRAME_WIDTH = 876;

// jsdom has no layout, so every element reports this width.
let frameWidth = FRAME_WIDTH;

function show(overrides: Partial<PdfQuotePassage> = {}) {
  const onClose = vi.fn();
  const passage = { quote: QUOTE, cite: "p. 2 · Study treatment", source: `${PDF}#page=2`, ...overrides };
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <PdfViewer passage={passage} onClose={onClose} />
    </ThemeProvider>,
  );
  return onClose;
}

function getFrame() {
  return screen.getByRole("region", { name: "Document" });
}

function getNextPage() {
  return screen.getByRole("button", { name: "Next page" });
}

function getPreviousPage() {
  return screen.getByRole("button", { name: "Previous page" });
}

async function releaseHeldPage() {
  await waitFor(() => expect(fakePdf.releaseHeldPage).toBeDefined());
  act(() => {
    fakePdf.releaseHeldPage?.();
  });
}

describe("PdfViewer", () => {
  beforeEach(() => {
    frameWidth = FRAME_WIDTH;
    vi.spyOn(Element.prototype, "clientWidth", "get").mockImplementation(() => frameWidth);
    stubFetch();
    stubCanvasContext();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    fakePdf.reset();
  });

  it("opens the cited page and highlights the quote", async () => {
    show();

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(await screen.findByText(MARKED)).toBeInTheDocument();
    expect(screen.queryByText(/not in the PDF text/)).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Document page 2" })).toBeInTheDocument();
  });

  it("looks through the other pages when the cited one does not have the quote", async () => {
    fakePdf.pages.set(2, "A table of contents.");
    fakePdf.pages.set(3, QUOTE);
    show();

    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    expect(await screen.findByText(MARKED)).toBeInTheDocument();
  });

  it("says so when the quote is not in the PDF text", async () => {
    show({ quote: "This sentence is not anywhere in the document at all." });

    expect(await screen.findByText(/not in the PDF text/)).toBeInTheDocument();
    expect(screen.queryByText(MARKED)).not.toBeInTheDocument();
  });

  it("reads no page's text for a quote too short to find", async () => {
    show({ quote: "Yes" });

    expect(await screen.findByText(/too short to find/)).toBeInTheDocument();
    await waitFor(() => expect(fakePdf.renders).toHaveLength(1));
    expect(fakePdf.textReads.size).toBe(0);
  });

  it("moves between pages and says when the quote is not on the one shown", async () => {
    show();
    await screen.findByText(MARKED);

    await userEvent.click(getNextPage());

    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    expect(await screen.findByText(/not on this page/)).toBeInTheDocument();
    expect(getNextPage()).toBeDisabled();

    await userEvent.click(getPreviousPage());

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(await screen.findByText(MARKED)).toBeInTheDocument();
  });

  it("blanks the last page's picture while the next one loads", async () => {
    fakePdf.heldPage = 3;
    show();
    await screen.findByText(MARKED);
    const canvas = screen.getByRole<HTMLCanvasElement>("img", { name: "Document page 2" });
    expect(canvas.width).toBeGreaterThan(0);

    await userEvent.click(getNextPage());
    await screen.findByText("Page 3 of 3");
    expect(canvas.width).toBe(0);

    await releaseHeldPage();
    await waitFor(() => expect(canvas.width).toBeGreaterThan(0));
    expect(screen.getByRole("img", { name: "Document page 3" })).toBe(canvas);
  });

  it("reads each page's text only once", async () => {
    show();
    await screen.findByText(MARKED);

    await userEvent.click(getNextPage());
    await screen.findByText("Page 3 of 3");
    await userEvent.click(getPreviousPage());
    await screen.findByText(MARKED);

    expect(fakePdf.textReads.get(2)).toBe(1);
    expect(fakePdf.textReads.get(3)).toBe(1);
  });

  it("draws the page at the screen's pixel density", async () => {
    vi.stubGlobal("devicePixelRatio", 2);
    show();

    await waitFor(() => expect(fakePdf.renders).not.toHaveLength(0));
    expect(fakePdf.renders[0]?.transform).toEqual([ 2, 0, 0, 2, 0, 0 ]);
  });

  it("redraws the page when the frame is resized, keeping the same part of it in view", async () => {
    let resized: (() => void) | undefined;
    vi.stubGlobal("ResizeObserver", class FakeResizeObserver {
      constructor(callback: () => void) {
        resized = callback;
      }

      observe() {
        // Fires only when the test asks
      }

      disconnect() {
        resized = undefined;
      }
    });
    show();
    await screen.findByText(MARKED);
    await waitFor(() => expect(fakePdf.renders).toHaveLength(1));
    const frame = getFrame();
    frame.scrollTop = 300;

    frameWidth = 900;
    act(() => {
      resized?.();
    });

    await waitFor(() => expect(fakePdf.renders).toHaveLength(2));
    // 900 wide, minus the gutter, for a 600-point page
    expect(fakePdf.renders[1]?.viewport.width).toBeCloseTo(884);
    // The page grew from 860 to 884 wide, so 300 pixels down it is now about 308
    await waitFor(() => expect(frame.scrollTop).toBeCloseTo(300 * 884 / 860));
  });

  it("says when the document cannot be opened", async () => {
    fakePdf.failOpen = true;
    show();

    expect(await screen.findByText("The document could not be opened")).toBeInTheDocument();
    expect(screen.getByText(/not a pdf/)).toBeInTheDocument();
  });

  it("says why the file could not be fetched, and opens it on retry", async () => {
    stubFetch(404);
    show();
    expect(await screen.findByText(/could not be found on the server/)).toBeInTheDocument();

    stubFetch();
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(screen.queryByText("The document could not be opened")).not.toBeInTheDocument();
  });

  it("asks the person to sign in again when the session has ended", async () => {
    stubFetch(401);
    show();

    expect(await screen.findByText("You are no longer signed in. Sign in and try again.")).toBeInTheDocument();
  });

  it("hands every document the same worker", async () => {
    fakePdf.failOpen = true;
    show();
    await screen.findByText("The document could not be opened");

    fakePdf.failOpen = false;
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByText("Page 2 of 3");

    expect(fakePdf.workers).toHaveLength(2);
    expect(fakePdf.workers[0]).toBeDefined();
    expect(fakePdf.workers[1]).toBe(fakePdf.workers[0]);
  });

  it("says when a page's text cannot be read, and still shows the page", async () => {
    fakePdf.failPage = 2;
    show();

    expect(await screen.findByText(TEXT_UNREADABLE)).toBeInTheDocument();
    await waitFor(() => expect(fakePdf.renders).toHaveLength(1));
  });

  it("says when a page cannot be drawn, and logs why", async () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    fakePdf.failRender = true;
    show();

    expect(await screen.findByText("This page could not be drawn.")).toBeInTheDocument();
    expect(screen.queryByText(MARKED)).not.toBeInTheDocument();
    expect(logged).toHaveBeenCalledWith("A PDF page could not be drawn", expect.any(Error));
  });

  it("says a page cannot be drawn when the browser gives no canvas to draw on", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(null);
    show();

    expect(await screen.findByText("This page could not be drawn.")).toBeInTheDocument();
    expect(fakePdf.renders).toHaveLength(0);
  });

  it("waits for the frame to have a width before drawing", async () => {
    frameWidth = 0;
    show();

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(fakePdf.renders).toHaveLength(0);
  });

  it("keeps searching past a page that cannot be read", async () => {
    fakePdf.failPage = 2;
    fakePdf.pages.set(3, QUOTE);
    show();

    expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
    expect(await screen.findByText(MARKED)).toBeInTheDocument();
    expect(screen.queryByText(/could not be read/)).not.toBeInTheDocument();
  });

  it("says the search was incomplete when the quote is missing and a page could not be read", async () => {
    fakePdf.failPage = 1;
    show({ quote: "This sentence is not anywhere in the document at all." });

    expect(await screen.findByText(/not found, but some pages could not be read/)).toBeInTheDocument();
  });

  it("tries a page again after its text could not be read", async () => {
    fakePdf.failPage = 3;
    show();
    await screen.findByText(MARKED);
    await userEvent.click(getNextPage());
    expect(await screen.findByText(/could not be read/)).toBeInTheDocument();

    fakePdf.failPage = undefined;
    await userEvent.click(getPreviousPage());
    await screen.findByText("Page 2 of 3");
    await userEvent.click(getNextPage());

    expect(await screen.findByText(/not on this page/)).toBeInTheDocument();
  });

  it("opens another page at its top", async () => {
    show();
    await screen.findByText(MARKED);
    const frame = getFrame();
    frame.scrollTop = 300;

    await userEvent.click(getNextPage());
    await screen.findByText("Page 3 of 3");

    await waitFor(() => expect(frame.scrollTop).toBe(0));
  });

  it("centres the highlight again when coming back to its page", async () => {
    show();
    await screen.findByText(MARKED);
    const frame = getFrame();
    await waitFor(() => expect(frame.scrollTop).toBeGreaterThan(0));
    const centred = frame.scrollTop;

    await userEvent.click(getNextPage());
    await screen.findByText(/not on this page/);
    await userEvent.click(getPreviousPage());
    await screen.findByText(MARKED);

    await waitFor(() => expect(frame.scrollTop).toBeCloseTo(centred));
  });

  it("frees a page once the person leaves it", async () => {
    show();
    await screen.findByText(MARKED);

    await userEvent.click(getNextPage());

    await waitFor(() => expect(fakePdf.cleanedPages).toContain(2));
  });

  it("says there is nothing to open when the passage has no document", () => {
    show({ source: undefined });

    expect(screen.getByText("There is no document to open.")).toBeInTheDocument();
    expect(screen.queryByText(/Opening the document/)).not.toBeInTheDocument();
  });

  it("keeps searching after a page change, and says where the quote is without moving", async () => {
    fakePdf.pages.set(2, "A table of contents.");
    fakePdf.pages.set(3, QUOTE);
    fakePdf.heldPage = 3;
    show();
    expect(await screen.findByText("Looking for the passage…")).toBeInTheDocument();

    await userEvent.click(getPreviousPage());
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    await releaseHeldPage();

    expect(await screen.findByText(/It is on page 3/)).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    expect(screen.queryByText(/not in the PDF text/)).not.toBeInTheDocument();
  });

  it("closes when asked", async () => {
    const onClose = show({ cite: undefined, source: PDF });

    await userEvent.click(screen.getByRole("button", { name: "close" }));

    expect(onClose).toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Document" })).toBeInTheDocument();
  });
});
