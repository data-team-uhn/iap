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
import { useState, type ReactElement } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { act, cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { OVER_MARK_CLASS } from "@iap/frontend-commons/pdfViewer/markLayer";
import type { Mark, MarkKind } from "@iap/frontend-commons/pdfViewer/marksModel";
import PdfViewer, { type PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";

import {
  createPdfjsError,
  fakePdf,
  FakeWorker,
  getDrawnMarks,
  MATCH_TOP,
  QUOTE,
  stubFetch,
  stubLayout,
} from "./pdfjs.fixture";

vi.mock("pdfjs-dist", async () => (await import("./pdfjs.fixture")).createPdfjsModule());
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", async () => (await import("./pdfjs.fixture")).createViewerModule());
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));
vi.mock("@iap/frontend-commons/assetManager", async () => ({
  getAssetURL: (await import("./pdfjs.fixture")).getAssetURL,
}));

const URL = "/content/pdfs/prc-memo.pdf";
const FOUND = /^The passage is marked on page \d+\.$/;
const ASSISTANT_MARK = "iap-mark iap-mark-assistant selected";

function withTheme(element: ReactElement): ReactElement {
  return <ThemeProvider theme={appTheme} defaultMode="light">{element}</ThemeProvider>;
}

// One passage, as evidence shows it: a quote marked by whoever found it, looked for from the page
// the viewer opens at, unless marks are given.
type ShowProps = Partial<PdfViewerProps> & { quote?: string; markedBy?: MarkKind };

function createViewer({ quote = QUOTE, markedBy = "assistant", ...props }: ShowProps, onClose: () => void) {
  const page = "page" in props ? props.page : 1;
  const marks = [ { id: "passage", quote, page, kind: markedBy } ];
  return withTheme(<PdfViewer url={URL} page={page} marks={marks} onClose={onClose} {...props} />);
}

function show(props: ShowProps = {}) {
  const onClose = vi.fn();
  const { rerender, unmount } = render(createViewer(props, onClose));
  return {
    onClose,
    unmount,
    rerender: (next: ShowProps) => rerender(createViewer({ ...props, ...next }, onClose)),
  };
}

// The passage as shown, rather than the hidden copy that measures it
function getPassage(text: string) {
  return screen.getByText(text, { ignore: "[aria-hidden=true], [aria-hidden=true] *" });
}

function getFrame() {
  return screen.getByRole("region", { name: "Document" });
}

function getStatus() {
  return screen.getByRole("status");
}

// The latest viewer PDF.js was asked to build.
function getViewer() {
  const viewer = fakePdf.viewers.at(-1);
  if (viewer === undefined) {
    throw new Error("No viewer was built");
  }
  return viewer;
}

// Containers a test made for the viewer, removed after it.
const places: HTMLDivElement[] = [];

function createPlace(): HTMLDivElement {
  const place = document.createElement("div");
  document.body.append(place);
  places.push(place);
  return place;
}

describe("PdfViewer", () => {
  beforeEach(() => {
    stubFetch();
    stubLayout();
    vi.stubGlobal("Worker", FakeWorker);
  });

  afterEach(() => {
    // Unmounted before the stand-in is reset, so this test's teardown does not count in the next.
    cleanup();
    places.splice(0).forEach(place => place.remove());
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    fakePdf.reset();
  });

  describe("in a dialog", () => {
    it("opens at the page given and moves to the passage, marked and in view", async () => {
      show();

      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
      expect(getFrame().scrollTop).toBe(MATCH_TOP);
      expect(getDrawnMarks(getFrame())).toEqual({ 2: [ ASSISTANT_MARK ] });
      expect(screen.getByRole("button", { name: "Show the passage on page 2" }))
        .toHaveAccessibleDescription(`“${QUOTE}”`);
      expect(screen.queryByRole("button", { name: "Show the whole passage" })).not.toBeInTheDocument();
    });

    it("cuts a passage running past three lines short, with a button showing all of it", async () => {
      // jsdom lays nothing out, so the passage's text says how many lines it runs over
      vi.spyOn(HTMLSpanElement.prototype, "getClientRects").mockReturnValue({ length: 5 } as DOMRectList);
      show();
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));

      const more = screen.getByRole("button", { name: "Show the whole passage" });
      expect(more).toHaveAttribute("aria-expanded", "false");
      expect(screen.getByRole("button", { name: "Show the passage on page 2" }))
        .toHaveAccessibleDescription(`“${QUOTE}”`);

      await userEvent.click(more);

      expect(screen.getByRole("button", { name: "Show less of the passage" }))
        .toHaveAttribute("aria-expanded", "true");
    });

    it("keeps the note under a passage cut short inside the button that goes to the passage", async () => {
      vi.spyOn(HTMLSpanElement.prototype, "getClientRects").mockReturnValue({ length: 5 } as DOMRectList);
      show();
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      await userEvent.click(screen.getByRole("button", { name: "Next page" }));

      const toPassage = screen.getByRole("button", { name: "Show the passage on page 2" });
      expect(toPassage).toHaveTextContent("found on page 2");
      await userEvent.click(toPassage);

      expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
    });

    it("on a phone, cuts a passage running past one line to one row, with its page and a button", async () => {
      // A phone-width screen, and a passage running over two lines
      vi.stubGlobal("matchMedia", (media: string) => ({
        media,
        matches: media.includes("max-width"),
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      }));
      vi.spyOn(HTMLSpanElement.prototype, "getClientRects").mockReturnValue({ length: 2 } as DOMRectList);
      show();
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));

      expect(screen.getByRole("button", { name: "Show the passage on page 2" })).toHaveTextContent("p. 2");
      await userEvent.click(screen.getByRole("button", { name: "Show the whole passage" }));

      expect(screen.getByRole("button", { name: "Show the passage on page 2" })).toHaveTextContent("found on page 2");
      // The layout changed under the button, which kept its place and the focus
      expect(screen.getByRole("button", { name: "Show less of the passage" })).toHaveFocus();
    });

    it("is named after the file when no title is given", () => {
      show();
      expect(screen.getByRole("dialog", { name: "prc-memo.pdf" })).toBeInTheDocument();
    });

    it("is named after the title given", () => {
      show({ title: "PRC memo" });
      expect(screen.getByRole("dialog", { name: "PRC memo" })).toBeInTheDocument();
    });

    it("shows the passage it looks for", () => {
      show();
      expect(getPassage(`“${QUOTE}”`)).toBeInTheDocument();
    });

    it("shows a passage that came in quotation marks without doubling them", async () => {
      show({ quote: `“${QUOTE}”` });
      expect(getPassage(`“${QUOTE}”`)).toBeInTheDocument();
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
    });

    it("fits the page to the frame, with no borders around it", async () => {
      show();
      await screen.findByText("Page 2 of 3");
      expect(getViewer().currentScaleValue).toBe("page-width");
      expect(getViewer().removePageBorders).toBe(true);
    });

    it("marks the passage in the colour of whoever marked it", async () => {
      show({ markedBy: "annotation" });

      await waitFor(() => expect(getDrawnMarks(getFrame()))
        .toEqual({ 2: [ "iap-mark iap-mark-annotation selected" ] }));
    });

    it("takes the reader to a passage on a page not drawn yet, and marks it once drawn", async () => {
      fakePdf.pages = [ "Cover.", "Contents.", "Foreword.", QUOTE ];
      fakePdf.drawShownPageOnly = true;
      show();

      await waitFor(() => expect(getDrawnMarks(getFrame())).toEqual({ 4: [ ASSISTANT_MARK ] }));
      expect(await screen.findByText("Page 4 of 4")).toBeInTheDocument();
      expect(getFrame().scrollTop).toBe(MATCH_TOP);
    });

    it("finds a passage broken across two pages, and marks both parts", async () => {
      fakePdf.pages = [
        "Cover page of the document.",
        "The memo covers new volunteers. Volunteers who, on a casual basis,\nPage 2 of 3",
        "Volunteer guidelines\nassist from time to time. They may require a check.",
      ];
      show();

      await waitFor(() => expect(getStatus()).toHaveTextContent("The passage is marked on pages 2 and 3."));
      expect(screen.getByRole("button", { name: "Show the passage on pages 2 and 3" }))
        .toHaveTextContent("found on pages 2–3");
      expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
      expect(getDrawnMarks(getFrame())).toEqual({ 2: [ ASSISTANT_MARK ], 3: [ ASSISTANT_MARK ] });
    });

    it("still finds the passage when the text of another page cannot be read", async () => {
      fakePdf.pages = [ "Cover.", "Contents.", QUOTE ];
      fakePdf.unreadable = [ 2 ];
      show();

      await waitFor(() => expect(getStatus()).toHaveTextContent("The passage is marked on page 3."));
    });

    it("looks from the page given onwards, then from the start", async () => {
      fakePdf.pages = [ QUOTE, "Contents.", "The end." ];
      show({ page: 3 });

      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    });

    it("opens at the first page when none is given, or the one given is not in the document", async () => {
      fakePdf.pages = [ "Contents.", "More contents.", "The end." ];
      show({ page: 9 });

      expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    });

    it("says when the passage is not in the document, and stays at the page given", async () => {
      show({ page: 3, quote: "A sentence that is nowhere in the document." });

      await waitFor(() => expect(getStatus())
        .toHaveTextContent("The passage is not in this document."));
      expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
    });

    it("says when the document has no text to search, as with a scan", async () => {
      fakePdf.pages = [ "", " ", "" ];
      show();

      await waitFor(() => expect(getStatus()).toHaveTextContent("This document has no text to search."));
    });

    it("does not look for a passage too short to find", async () => {
      show({ quote: "Yes" });

      expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
      expect(getStatus()).toHaveTextContent("The passage is too short to look for.");
      expect(getDrawnMarks(getFrame())).toEqual({});
    });
  });

  describe("moving around", () => {
    it("goes from page to page, and not past either end", async () => {
      show();
      await screen.findByText("Page 2 of 3");

      await userEvent.click(screen.getByRole("button", { name: "Next page" }));
      expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
      expect(getStatus()).toHaveTextContent("The passage is marked on page 2.");

      await userEvent.click(screen.getByRole("button", { name: "Previous page" }));
      await userEvent.click(screen.getByRole("button", { name: "Previous page" }));
      expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Previous page" })).toBeDisabled();
    });

    it("leaves the reader on the page they went to when the passage is found later", async () => {
      fakePdf.pages = [ "Contents.", "More contents.", QUOTE ];
      fakePdf.holdText = true;
      show();
      await screen.findByText("Page 1 of 3");

      await userEvent.click(screen.getByRole("button", { name: "Next page" }));
      act(() => fakePdf.releaseText?.());

      await waitFor(() => expect(getStatus()).toHaveTextContent("The passage is marked on page 3."));
      expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
      expect(getFrame().scrollTop).toBe(0);
    });

    it("goes back to the passage from the passage's line", async () => {
      show();
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      await userEvent.click(screen.getByRole("button", { name: "Next page" }));
      getFrame().scrollTop = 0;

      await userEvent.click(screen.getByRole("button", { name: "Show the passage on page 2" }));

      expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
      expect(getFrame().scrollTop).toBe(MATCH_TOP);
    });

    it("zooms in and out, and fits the page to the frame again", async () => {
      show();
      await screen.findByText("Page 2 of 3");

      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
      expect(getViewer().currentScaleValue).toBe("2");
      await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
      await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
      expect(getViewer().currentScaleValue).toBe("0.5");
      await userEvent.click(screen.getByRole("button", { name: "Fit to width" }));
      expect(getViewer().currentScaleValue).toBe("page-width");
    });

    it("leaves the reader where they are when zooming, rather than taking them back to the passage", async () => {
      show();
      await waitFor(() => expect(getFrame().scrollTop).toBe(MATCH_TOP));
      getFrame().scrollTop = 100;

      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));

      expect(getFrame().scrollTop).toBe(100);
    });

    describe("when the frame changes width", () => {
      // Size changes are only seen through the observers watching for them, which jsdom does not run.
      const observers: ResizeObserverCallback[] = [];

      beforeEach(() => {
        vi.stubGlobal("ResizeObserver", class {
          constructor(callback: ResizeObserverCallback) {
            observers.push(callback);
          }

          observe(): void {
            // Told by resize() instead
          }

          disconnect(): void {
            // Nothing to stop
          }
        });
      });

      afterEach(() => {
        observers.splice(0);
      });

      async function resize(width: number) {
        Object.defineProperty(getFrame(), "offsetWidth", { value: width, configurable: true });
        act(() => observers.forEach(observer => observer([], {} as ResizeObserver)));
        await act(() => new Promise(resolve => requestAnimationFrame(resolve)));
      }

      it("fits the page to the frame's new width", async () => {
        show();
        await screen.findByText("Page 2 of 3");
        fakePdf.scales = [];

        await resize(900);

        expect(fakePdf.scales).toEqual([ "page-width" ]);
      });

      it("keeps a zoom the reader chose, and fits again once asked to", async () => {
        show();
        await screen.findByText("Page 2 of 3");
        await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
        fakePdf.scales = [];

        await resize(900);
        expect(fakePdf.scales).toEqual([]);

        await userEvent.click(screen.getByRole("button", { name: "Fit to width" }));
        await resize(600);
        expect(fakePdf.scales).toEqual([ "page-width", "page-width" ]);
      });

      it("leaves the page alone when only the frame's height changes", async () => {
        show();
        await screen.findByText("Page 2 of 3");
        await resize(900);
        fakePdf.scales = [];

        await resize(900);

        expect(fakePdf.scales).toEqual([]);
      });
    });

    it("offers no page or zoom controls before the document opens", () => {
      vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));
      show();

      expect(getStatus()).toHaveTextContent("Opening the document…");
      expect(screen.queryByRole("button", { name: /Show the passage/ })).not.toBeInTheDocument();
      for (const name of [ "Previous page", "Next page", "Zoom out", "Fit to width", "Zoom in" ]) {
        expect(screen.getByRole("button", { name })).toBeDisabled();
      }
    });
  });

  describe("when the document cannot be opened", () => {
    it("says why the file could not be fetched, and opens it on retry", async () => {
      stubFetch(404);
      show();

      expect(await screen.findByText("The document could not be opened")).toBeInTheDocument();
      expect(screen.getByText(/could not be found on the server/)).toBeInTheDocument();
      expect(getStatus()).toBeEmptyDOMElement();

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

    it("says when the file is not a PDF it can read", async () => {
      fakePdf.openFailure = createPdfjsError("InvalidPDFException", "Invalid PDF structure.");
      show();

      expect(await screen.findByText("The document could not be opened")).toBeInTheDocument();
      expect(screen.getByText(
        "This file is not a PDF that can be read. It may be damaged, or be another kind of file.",
      )).toBeInTheDocument();
    });

    it("says when the PDF is protected by a password", async () => {
      fakePdf.openFailure = createPdfjsError("PasswordException", "No password given");
      show();

      expect(await screen.findByText("This PDF is protected by a password, so it cannot be shown here."))
        .toBeInTheDocument();
    });

    it("passes on what PDF.js says about any other failure", async () => {
      fakePdf.openFailure = createPdfjsError("UnknownErrorException", "Worker was destroyed");
      show();

      expect(await screen.findByText("Something went wrong: Worker was destroyed")).toBeInTheDocument();
    });

    it("has PDF.js use its own fonts, and tells it where they and its other data are", async () => {
      show();
      await screen.findByText("Page 2 of 3");

      expect(fakePdf.openOptions[0]).toMatchObject({
        useSystemFonts: false,
        standardFontDataUrl: "/libs/iap/resources/pdfjs-dist/standard_fonts.0123456789abcdef.data/",
        wasmUrl: "/libs/iap/resources/pdfjs-dist/wasm.0123456789abcdef.data/",
        cMapUrl: "/libs/iap/resources/pdfjs-dist/cmaps.0123456789abcdef.data/",
        iccUrl: "/libs/iap/resources/pdfjs-dist/iccs.0123456789abcdef.data/",
      });
    });

    it("leaves PDF.js without a folder the build did not publish", async () => {
      fakePdf.unpublished = [ "pdfjs-dist.iccs" ];
      show();
      await screen.findByText("Page 2 of 3");

      expect(fakePdf.openOptions[0]).not.toHaveProperty("iccUrl");
      expect(fakePdf.openOptions[0]).toHaveProperty("wasmUrl");
    });

    it("hands every document the same worker", async () => {
      fakePdf.openFailure = createPdfjsError("InvalidPDFException", "Invalid PDF structure.");
      show();
      await screen.findByText("The document could not be opened");

      fakePdf.openFailure = undefined;
      await userEvent.click(screen.getByRole("button", { name: "Retry" }));
      await screen.findByText("Page 2 of 3");

      expect(fakePdf.workers).toHaveLength(2);
      expect(fakePdf.workers[1]).toBe(fakePdf.workers[0]);
    });

    it("runs PDF.js in a module worker started from PDF.js's own worker script", async () => {
      show();
      await screen.findByText("Page 2 of 3");

      const { port } = fakePdf.workers[0] as { port: FakeWorker };
      expect(port).toBeInstanceOf(FakeWorker);
      expect(port.url.href).toContain("pdfjs-dist/build/pdf.worker.min.mjs");
      expect(port.options).toEqual({ type: "module" });
    });
  });

  describe("closing", () => {
    it("closes from its own button", async () => {
      const { onClose } = show();

      await userEvent.click(screen.getByRole("button", { name: "Close" }));

      expect(onClose).toHaveBeenCalled();
    });

    it("closes on Escape", async () => {
      const { onClose } = show();

      await userEvent.keyboard("{Escape}");

      expect(onClose).toHaveBeenCalled();
    });

    it("lets go of the document once it is gone", async () => {
      const { unmount } = show();
      await screen.findByText("Page 2 of 3");
      const viewer = getViewer();

      unmount();

      expect(viewer.doc).toBeNull();
      expect(viewer.abortSignal?.aborted).toBe(true);
      expect(fakePdf.destroyedTasks).toBe(1);
    });

    it("stops reading the document's text once closed", async () => {
      fakePdf.holdText = true;
      const { unmount } = show();
      await screen.findByText("Page 1 of 3");

      unmount();
      await act(async () => fakePdf.releaseText?.());

      expect(fakePdf.textReads).toEqual([ 1 ]);
    });

    it("stops opening the document when closed before it opens", async () => {
      let answer: ((response: Response) => void) | undefined;
      vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => {
        answer = resolve;
      })));
      const { unmount } = show();
      await waitFor(() => expect(answer).toBeDefined());

      unmount();
      answer?.(new Response(new ArrayBuffer(8)));
      await act(() => Promise.resolve());

      expect(fakePdf.opened).toBe(0);
    });
  });

  describe("in a container", () => {
    it("shows in the container given, rather than in a dialog", async () => {
      const place = createPlace();
      show({ container: place });

      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      expect(within(place).getByRole("heading", { name: "prc-memo.pdf" })).toBeInTheDocument();
      expect(within(place).getByRole("region", { name: "Document" })).toBeInTheDocument();
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Pop back in" })).not.toBeInTheDocument();
    });

    it("waits for a container that is not there yet, without a dialog in the meantime", async () => {
      const place = createPlace();
      const { rerender } = show({ container: null });
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.queryByRole("region", { name: "Document" })).not.toBeInTheDocument();

      rerender({ container: place });

      await waitFor(() => expect(within(place).getByRole("status")).toHaveTextContent(FOUND));
    });

    it("pops out into a dialog where the reader was, without opening the document again", async () => {
      const place = createPlace();
      show({ container: place });
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));

      const dialog = screen.getByRole("dialog", { name: "prc-memo.pdf" });
      expect(place).toBeEmptyDOMElement();
      await waitFor(() => expect(fakePdf.hashes).toEqual([ "page=2&zoom=2,0,0" ]));
      expect(fakePdf.viewers[0]?.abortSignal?.aborted).toBe(true);
      expect(getViewer().container).toBe(within(dialog).getByRole("region", { name: "Document" }));
      expect(getViewer().currentScaleValue).toBe("2");
      expect(within(dialog).getByText("Page 2 of 3")).toBeInTheDocument();
      expect(fakePdf.opened).toBe(1);
    });

    it("marks the passage again once popped out, without scrolling the reader to it", async () => {
      const place = createPlace();
      show({ container: place });
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));

      await waitFor(() => expect(getDrawnMarks(getFrame())).toEqual({ 2: [ ASSISTANT_MARK ] }));
      expect(getStatus()).toHaveTextContent(FOUND);
      expect(getFrame().scrollTop).toBe(0);
      expect(fakePdf.textReads).toEqual([ 1, 2, 3 ]);
    });

    it("keeps the reader on the page they went to when popping out", async () => {
      const place = createPlace();
      show({ container: place });
      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      await userEvent.click(screen.getByRole("button", { name: "Next page" }));

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));

      await waitFor(() => expect(getDrawnMarks(getFrame())).toEqual({ 2: [ ASSISTANT_MARK ] }));
      expect(getStatus()).toHaveTextContent("The passage is marked on page 2.");
      expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
    });

    it("still takes the reader to the passage when popped out before the document was shown", async () => {
      const place = createPlace();
      fakePdf.holdPages = true;
      show({ container: place });
      await waitFor(() => expect(fakePdf.releasePages).toBeDefined());

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await waitFor(() => expect(fakePdf.viewers).toHaveLength(2));
      act(() => fakePdf.releasePages?.());

      await waitFor(() => expect(getStatus()).toHaveTextContent(FOUND));
      expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
      expect(getFrame().scrollTop).toBe(MATCH_TOP);
    });

    it("returns focus to the button that pops it out, once back in place", async () => {
      const place = createPlace();
      show({ container: place });
      await screen.findByText("Page 2 of 3");

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await userEvent.click(screen.getByRole("button", { name: "Pop back in" }));
      await waitFor(() => expect(screen.getByRole("button", { name: "Pop out" })).toHaveFocus());

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(screen.getByRole("button", { name: "Pop out" })).toHaveFocus());
    });

    it("closes on Escape once its container is gone, even popped out", async () => {
      const place = createPlace();
      const { onClose, rerender } = show({ container: place });
      await screen.findByText("Page 2 of 3");
      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));

      rerender({ container: undefined });
      await userEvent.keyboard("{Escape}");

      expect(onClose).toHaveBeenCalled();
    });

    it("pops back in from its button or on Escape, and closes from Close", async () => {
      const place = createPlace();
      const { onClose } = show({ container: place });
      await screen.findByText("Page 2 of 3");

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await userEvent.click(screen.getByRole("button", { name: "Pop back in" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(within(place).getByRole("region", { name: "Document" })).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(within(place).getByRole("region", { name: "Document" })).toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));
      await userEvent.click(screen.getByRole("button", { name: "Close" }));
      expect(onClose).toHaveBeenCalled();
    });
  });

  describe("with no passages", () => {
    it("shows the document alone", async () => {
      render(withTheme(<PdfViewer url={URL} marks={[]} onClose={vi.fn()} />));

      expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
      expect(getStatus()).toBeEmptyDOMElement();
      expect(screen.queryByRole("button", { name: /Show the passage/ })).not.toBeInTheDocument();
      expect(getDrawnMarks(getFrame())).toEqual({});
    });
  });

  describe("with several passages", () => {
    const TEXTS = [ "The memo, its cover and title.", QUOTE, "The schedule continues for one year." ];
    const MARKS: Mark[] = [
      { id: "missing", quote: "A sentence that is nowhere in the document.", kind: "assistant" },
      { id: "volunteers", quote: TEXTS[1], kind: "assistant" },
      { id: "schedule", quote: TEXTS[2], kind: "annotation" },
    ];

    function showMarks(props: Partial<PdfViewerProps> = {}) {
      const onSelectMark = vi.fn();
      render(withTheme(<PdfViewer url={URL} marks={MARKS} onSelectMark={onSelectMark} onClose={vi.fn()} {...props} />));
      return onSelectMark;
    }

    // Where the fake layout puts every mark, on whichever page
    const AT_MARK = { clientX: 50, clientY: MATCH_TOP + 10 };

    async function clickMark() {
      await userEvent.pointer({ keys: "[MouseLeft]", target: getFrame(), coords: AT_MARK });
    }

    beforeEach(() => {
      fakePdf.pages = [ ...TEXTS ];
    });

    describe("in a narrow viewer", () => {
      it("shows one passage at a time above the document, starting with the first found", async () => {
        showMarks();

        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));
        expect(screen.getByText("1 of 3")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Previous passage" })).toBeDisabled();
        expect(getPassage(`“${QUOTE}”`)).toBeInTheDocument();
        expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
        expect(getFrame().scrollTop).toBe(MATCH_TOP);
        expect(screen.queryByRole("list", { name: "Passages" })).not.toBeInTheDocument();
      });

      it("steps to the next passage, selecting it and taking the reader to it", async () => {
        const onSelectMark = showMarks();
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        await userEvent.click(screen.getByRole("button", { name: "Next passage" }));

        expect(getStatus()).toHaveTextContent("Passage 2 of 3 is marked on page 3.");
        expect(onSelectMark).toHaveBeenCalledWith("schedule");
        expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
        expect(getDrawnMarks(getFrame())).toEqual({
          2: [ "iap-mark iap-mark-assistant" ],
          3: [ "iap-mark iap-mark-annotation selected" ],
        });

        await userEvent.click(screen.getByRole("button", { name: "Previous passage" }));

        expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2.");
        expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
      });

      it("ends with the passages that cannot be marked", async () => {
        showMarks();
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        await userEvent.click(screen.getByRole("button", { name: "Next passage" }));
        await userEvent.click(screen.getByRole("button", { name: "Next passage" }));

        expect(getStatus()).toHaveTextContent("Passage 3 of 3 is not in this document.");
        expect(screen.getByRole("button", { name: "Next passage" })).toBeDisabled();
      });

      it("opens at the passage the caller selected", async () => {
        showMarks({ selectedMarkId: "schedule" });

        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 2 of 3 is marked on page 3."));
        expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
      });

      it("selects the first passage found when the one the caller selected is not among them", async () => {
        showMarks({ selectedMarkId: "removed" });

        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));
      });
    });

    describe("in a wide viewer", () => {
      beforeEach(() => {
        stubLayout({ elementWidth: 1000 });
      });

      it("lists the passages beside the document, the ones found taking the reader to them", async () => {
        showMarks();
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        const passages = within(screen.getByRole("list", { name: "Passages" })).getAllByRole("listitem");
        expect(passages.map(passage => passage.textContent)).toEqual([
          `“${QUOTE}”found on page 2`,
          `“${TEXTS[2]}”found on page 3`,
          "“A sentence that is nowhere in the document.”not found in this document",
        ]);
        expect(screen.getByRole("button", { name: "Show passage 1 on page 2" }))
          .toHaveAttribute("aria-current", "true");
        expect(screen.queryByText("1 of 3")).not.toBeInTheDocument();
      });

      it("selects a passage that was not found, for it to be read whole", async () => {
        showMarks();
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        await userEvent.click(screen.getByRole("button", { name: "Select passage 3" }));

        expect(screen.getByRole("button", { name: "Select passage 3" })).toHaveAttribute("aria-current", "true");
        expect(getStatus()).toHaveTextContent("Passage 3 of 3 is not in this document.");
      });

      it("names both pages of a passage broken across them", async () => {
        fakePdf.pages = [ "The memo. Volunteers who, on a casual basis,", "assist from time to time.", TEXTS[2] ];
        showMarks();

        expect(await screen.findByRole("button", { name: "Show passage 1 on pages 1 and 2" })).toBeInTheDocument();
      });

      it("still lists the passages when the document cannot be opened", async () => {
        stubFetch(404);
        showMarks();

        expect(await screen.findByText("The document could not be opened")).toBeInTheDocument();
        const passages = within(screen.getByRole("list", { name: "Passages" })).getAllByRole("listitem");
        expect(passages[0]).toHaveTextContent(/^“A sentence that is nowhere in the document\.”$/);
      });

      it("selects a passage from the list and takes the reader to it", async () => {
        const onSelectMark = showMarks();
        await waitFor(() => expect(getFrame().scrollTop).toBe(MATCH_TOP));
        getFrame().scrollTop = 0;

        await userEvent.click(screen.getByRole("button", { name: "Show passage 2 on page 3" }));

        expect(onSelectMark).toHaveBeenCalledWith("schedule");
        expect(screen.getByRole("button", { name: "Show passage 2 on page 3" }))
          .toHaveAttribute("aria-current", "true");
        expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
        expect(getFrame().scrollTop).toBe(MATCH_TOP);
      });
    });

    describe("as the passages and the selection change", () => {
      it("keeps the view of the document as the selection moves to passages on other pages", async () => {
        showMarks({ marks: MARKS.map(mark => ({ ...mark, page: mark.id === "schedule" ? 3 : 2 })) });
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        await userEvent.click(screen.getByRole("button", { name: "Next passage" }));

        expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
        expect(fakePdf.viewers).toHaveLength(1);
      });

      it("keeps to the first passage given while it is looked for, even when a later one is found first", async () => {
        fakePdf.pages = [ TEXTS[2], "Contents.", QUOTE ];
        showMarks({ marks: [ MARKS[1], MARKS[2] ] });

        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 2 is marked on page 3."));
        expect(await screen.findByText("Page 3 of 3")).toBeInTheDocument();
        expect(getFrame().scrollTop).toBe(MATCH_TOP);
      });

      it("does not look for the passages again when given equal ones", async () => {
        const onClose = vi.fn();
        const { rerender } = render(withTheme(<PdfViewer url={URL} marks={[ ...MARKS ]} onClose={onClose} />));
        await waitFor(() => expect(getDrawnMarks(getFrame())[2]).toHaveLength(1));

        rerender(withTheme(<PdfViewer url={URL} marks={MARKS.map(mark => ({ ...mark }))} onClose={onClose} />));

        expect(fakePdf.textReads).toEqual([ 1, 2, 3 ]);
        expect(getDrawnMarks(getFrame())[2]).toHaveLength(1);
      });

      // A caller holding the selection, as a review screen will, selecting what the viewer reports.
      function Held({ initial, onReport }: { initial: string; onReport: (id: string) => void }) {
        const [selected, setSelected] = useState(initial);
        const report = (id: string) => {
          onReport(id);
          setSelected(id);
        };
        return <PdfViewer url={URL} marks={MARKS} selectedMarkId={selected} onSelectMark={report} onClose={vi.fn()} />;
      }

      it("takes the reader to a passage the caller selects", async () => {
        const viewer = (selected: string) => (
          withTheme(<PdfViewer url={URL} marks={MARKS} selectedMarkId={selected} onClose={vi.fn()} />)
        );
        const { rerender } = render(viewer("volunteers"));
        await waitFor(() => expect(getFrame().scrollTop).toBe(MATCH_TOP));
        getFrame().scrollTop = 0;

        rerender(viewer("schedule"));

        expect(getStatus()).toHaveTextContent("Passage 2 of 3 is marked on page 3.");
        expect(screen.getByText("Page 3 of 3")).toBeInTheDocument();
        expect(getFrame().scrollTop).toBe(MATCH_TOP);
      });

      it("leaves the reader where they are when the caller selects the mark they clicked", async () => {
        const onReport = vi.fn();
        render(withTheme(<Held initial="schedule" onReport={onReport} />));
        await waitFor(() => expect(getFrame().scrollTop).toBe(MATCH_TOP));
        getFrame().scrollTop = 0;

        // The click falls on the mark drawn first, the one on page 2.
        await userEvent.pointer({ keys: "[MouseLeft]", target: getFrame(), coords: AT_MARK });

        expect(onReport).toHaveBeenCalledWith("volunteers");
        expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2.");
        expect(getFrame().scrollTop).toBe(0);
      });
    });

    describe("in the document", () => {
      it("selects a mark clicked, without moving the reader", async () => {
        // Selected first, on page 3; the click falls on the mark drawn first, on page 2.
        const onSelectMark = showMarks({ marks: [ MARKS[2], MARKS[1] ] });
        await waitFor(() => expect(getFrame().scrollTop).toBe(MATCH_TOP));
        getFrame().scrollTop = 0;

        await clickMark();

        expect(onSelectMark).toHaveBeenCalledWith("volunteers");
        expect(getStatus()).toHaveTextContent("Passage 2 of 2 is marked on page 2.");
        expect(getFrame().scrollTop).toBe(0);
      });

      it("takes neither a drag nor the end of selecting text for a click on a mark", async () => {
        const onSelectMark = showMarks({ marks: [ MARKS[2], MARKS[1] ] });
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 2 is marked on page 3."));

        await userEvent.pointer([
          { keys: "[MouseLeft>]", target: getFrame(), coords: { clientX: 0, clientY: MATCH_TOP + 10 } },
          { coords: { clientX: 50, clientY: MATCH_TOP + 10 } },
          { keys: "[/MouseLeft]" },
        ]);
        vi.spyOn(window, "getSelection").mockReturnValue({ isCollapsed: false } as Selection);
        await clickMark();

        expect(onSelectMark).not.toHaveBeenCalled();
      });

      it("shows a pointer over a mark", async () => {
        showMarks();
        await waitFor(() => expect(getStatus()).toHaveTextContent("Passage 1 of 3 is marked on page 2."));

        await userEvent.pointer({ target: getFrame(), coords: AT_MARK });
        expect(getFrame()).toHaveClass(OVER_MARK_CLASS);

        await userEvent.pointer({ target: getFrame(), coords: { clientX: 50, clientY: 10 } });
        expect(getFrame()).not.toHaveClass(OVER_MARK_CLASS);
      });
    });
  });
});
