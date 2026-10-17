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
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { diffArrays } from "diff";

import { appTheme } from "@iap/frontend-commons/appTheme";
import PdfComparison, { type PdfComparisonProps } from "@iap/frontend-commons/pdfViewer/PdfComparison";

import { fakePdf, FakeWorker, getDrawnMarks, MATCH_TOP, stubFetch, stubLayout } from "./pdfjs.fixture";

import type * as Diff from "diff";

vi.mock("pdfjs-dist", async () => (await import("./pdfjs.fixture")).createPdfjsModule());
vi.mock("pdfjs-dist/web/pdf_viewer.mjs", async () => (await import("./pdfjs.fixture")).createViewerModule());
vi.mock("pdfjs-dist/web/pdf_viewer.css", () => ({}));
vi.mock("@iap/frontend-commons/assetManager", async () => ({
  getAssetURL: (await import("./pdfjs.fixture")).getAssetURL,
}));
vi.mock("diff", async importOriginal => {
  const actual = await importOriginal<typeof Diff>();
  return { ...actual, diffArrays: vi.fn(actual.diffArrays) };
});

const OLDER = "/content/pdfs/protocol-v1.pdf";
const NEWER = "/content/pdfs/protocol-v2.pdf";
const VIEW_KEY = "iap.pdfComparison.view";
const STRICT_KEY = "iap.pdfComparison.strict";

// Where the fake layout puts every mark, and every point, on whichever page
const AT_MARK = { clientX: 50, clientY: MATCH_TOP + 10 };
const AT_POINT = { clientX: 100, clientY: MATCH_TOP + 10 };

function show(older: string[], newer: string[], props: Partial<PdfComparisonProps> = {}) {
  fakePdf.documents = { [OLDER]: older, [NEWER]: newer };
  const onClose = vi.fn();
  const { unmount } = render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <PdfComparison olderUrl={OLDER} newerUrl={NEWER} onClose={onClose} {...props} />
    </ThemeProvider>,
  );
  return { onClose, unmount };
}

function getStatus() {
  return screen.getByRole("status");
}

function getFrames() {
  return screen.getAllByRole("region", { name: "Document" });
}

function getNewerFrame() {
  const frames = getFrames();
  return frames[frames.length - 1];
}

async function waitForStatus(text: string | RegExp) {
  await waitFor(() => expect(getStatus()).toHaveTextContent(text));
}

describe("PdfComparison", () => {
  beforeEach(() => {
    stubFetch();
    vi.stubGlobal("Worker", FakeWorker);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    window.localStorage.clear();
    fakePdf.reset();
  });

  describe("in a wide viewer", () => {
    beforeEach(() => {
      stubLayout({ elementWidth: 1000, elementHeight: 1000 });
    });

    it("shows the versions side by side, removed text marked in the older and added text in the newer", async () => {
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ], { olderLabel: "Version 1" });

      await waitForStatus("1 change found.");
      expect(screen.getByRole("dialog", { name: "protocol-v2.pdf" })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Version 1" })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Newer version" })).toBeInTheDocument();
      const [ older, newer ] = getFrames();
      await waitFor(() => expect(getDrawnMarks(newer)).toEqual({ 1: [ "iap-mark iap-mark-added" ] }));
      expect(getDrawnMarks(older)).toEqual({ 1: [ "iap-mark iap-mark-removed" ] });
      expect(screen.getByRole("button", { name: "Side by side" })).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByText("1 change")).toBeInTheDocument();
    });

    it("shows a page bar over each version, and one search and zoom for both", async () => {
      show([ "The fee is due.", "Page two." ], [ "The fee is now due." ]);
      await waitForStatus("2 changes found.");

      expect(await screen.findByText("Page 1 of 2")).toBeInTheDocument();
      expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
      expect(fakePdf.scales.filter(scale => scale === 2)).toHaveLength(2);

      await userEvent.type(screen.getByRole("textbox", { name: "Search the document" }), "fee");

      await waitFor(() => expect(fakePdf.finds.filter(find => find.query === "fee")).toHaveLength(2));
      await waitForStatus("Match 1 of 1.");
      await userEvent.keyboard("{Enter}");
      expect(fakePdf.finds.filter(find => find.type === "again")).toHaveLength(1);
    });

    it("steps through the changes in order, bringing each into view in both versions", async () => {
      show([ "One change here.", "Nothing.", "Another here." ], [ "One edit here.", "Nothing.", "Another there." ]);
      await waitForStatus("2 changes found.");
      expect(screen.getByRole("button", { name: "Previous change" })).toBeDisabled();

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await waitForStatus("Change 1 of 2. Replaced on page 1: “change” with “edit”.");
      await waitFor(() => expect(getFrames().map(frame => frame.scrollTop)).toEqual([ MATCH_TOP, MATCH_TOP ]));
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      await waitForStatus("Change 2 of 2. Replaced on page 3: “here.” with “there.”.");
      expect(screen.getByRole("button", { name: "Next change" })).toBeDisabled();
      // Every mark is as far down its page in the stand-in, so each step scrolls as far again.
      await waitFor(() => expect(getFrames().map(frame => frame.scrollTop)).toEqual([ 2 * MATCH_TOP, 2 * MATCH_TOP ]));
      expect(screen.queryByRole("region", { name: "Removed text" })).not.toBeInTheDocument();
    });

    it("selects a change clicked in one version and brings it into view in the other", async () => {
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ]);
      await waitForStatus("1 change found.");
      await waitFor(() => expect(getDrawnMarks(getNewerFrame())).not.toEqual({}));

      await userEvent.pointer({ keys: "[MouseLeft]", target: getNewerFrame(), coords: AT_MARK });

      await waitForStatus("Change 1 of 1. Replaced on page 1: “100” with “120”.");
      await waitFor(() => expect(getFrames()[0].scrollTop).toBe(MATCH_TOP));
    });

    it("selects a change clicked in the older version and brings it into view in the newer", async () => {
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ]);
      await waitForStatus("1 change found.");
      await waitFor(() => expect(getDrawnMarks(getFrames()[0])).not.toEqual({}));

      await userEvent.pointer({ keys: "[MouseLeft]", target: getFrames()[0], coords: AT_MARK });

      await waitForStatus("Change 1 of 1. Replaced on page 1: “100” with “120”.");
      await waitFor(() => expect(getNewerFrame().scrollTop).toBe(MATCH_TOP));
    });

    it("fits both versions to their width with one button", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");
      const fitted = fakePdf.scales.filter(scale => scale === "page-width").length;

      await userEvent.click(screen.getByRole("button", { name: "Fit to width" }));

      expect(fakePdf.scales.filter(scale => scale === "page-width")).toHaveLength(fitted + 2);
    });

    describe("scrolling", () => {
      // A frame of the stand-in, scrolled by hand once both versions are shown, and the next frame drawn after.
      async function scrollByHand(frame: HTMLElement) {
        await waitFor(() => expect(getFrames().map(each => Object.keys(getDrawnMarks(each)))).not.toContainEqual([]));
        fireEvent.wheel(frame);
        fireEvent.scroll(frame);
        await new Promise(resolve => requestAnimationFrame(resolve));
      }

      it("scrolls the other version with the one scrolled by hand, matched by their text", async () => {
        show([ "The fee is due." ], [ "The fee is now due." ]);
        await waitForStatus("1 change found.");
        const [ older, newer ] = getFrames();

        await scrollByHand(older);

        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));
        expect(older.scrollTop).toBe(0);
      });

      it("keeps a version moved with the other from moving it back", async () => {
        show([ "The fee is due." ], [ "The fee is now due." ]);
        await waitForStatus("1 change found.");
        const [ older, newer ] = getFrames();
        await scrollByHand(older);
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));

        fireEvent.scroll(newer);
        await new Promise(resolve => requestAnimationFrame(resolve));

        expect(older.scrollTop).toBe(0);
      });

      it("leaves the other version where it is for scrolling not done by hand", async () => {
        show([ "The fee is due." ], [ "The fee is now due." ]);
        await waitForStatus("1 change found.");
        const [ older, newer ] = getFrames();

        fireEvent.scroll(older);
        await new Promise(resolve => requestAnimationFrame(resolve));

        expect(newer.scrollTop).toBe(0);
      });

      it("moves the older version with the newer after zooming", async () => {
        show([ "The fee is due." ], [ "The fee is now due." ]);
        await waitForStatus("1 change found.");

        await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));

        await waitFor(() => expect(getFrames()[0].scrollTop).toBe(MATCH_TOP));
      });

      it("leaves a version where a change was stepped to, though it was still on its way to a place", async () => {
        fakePdf.drawShownPageOnly = true;
        show([ "Shared words here." ], [ "A page added first.", "Shared words here, and more." ]);
        await waitForStatus("2 changes found.");
        const newer = getNewerFrame();
        await waitFor(() => expect(getDrawnMarks(newer)).not.toEqual({}));
        fakePdf.holdDrawing = true;
        // The newer version's second page, where the place is, waits to be drawn
        await scrollByHand(getFrames()[0]);
        await userEvent.click(screen.getByRole("button", { name: "Next change" }));
        await waitForStatus("Change 1 of 2. Added on page 1: “A page added first.”.");
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));

        // Every page drawn now, the place's page too
        fakePdf.drawShownPageOnly = false;
        act(() => fakePdf.releaseDrawing());

        await waitFor(() => expect(newer.querySelector("[data-page-number=\"2\"] .textLayer")).not.toBeNull());
        expect(newer.scrollTop).toBe(MATCH_TOP);
      });

      it("takes the other version to a page not drawn yet, then to the place once it is", async () => {
        fakePdf.drawShownPageOnly = true;
        show([ "Shared words here." ], [ "A page added first.", "Shared words here." ]);
        await waitForStatus("1 change found.");
        const [ older, newer ] = getFrames();

        await scrollByHand(older);

        expect(await screen.findByText("Page 2 of 2")).toBeInTheDocument();
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));
      });
    });

    it("shows the newer version alone when asked, and remembers it", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");
      await userEvent.click(screen.getByRole("button", { name: "Side by side" }));
      expect(getFrames()).toHaveLength(2);

      await userEvent.click(screen.getByRole("button", { name: "Newer version alone" }));

      expect(getFrames()).toHaveLength(1);
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"unified\"");
      await userEvent.click(screen.getByRole("button", { name: "Side by side" }));
      expect(getFrames()).toHaveLength(2);
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"split\"");
    });

    it("puts the focus in the search on Ctrl+F", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");
      await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));

      await userEvent.keyboard("{Control>}f{/Control}");

      expect(screen.getByRole("textbox", { name: "Search the document" })).toHaveFocus();
    });

    it("zooms the older version as the newer was zoomed while shown alone", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");
      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));

      await userEvent.click(screen.getByRole("button", { name: "Side by side" }));

      await waitFor(() => expect(fakePdf.viewers.at(-1)?.currentScaleValue).toBe("2"));
    });

    it("searches the older version too once it is shown", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");
      await userEvent.type(screen.getByRole("textbox", { name: "Search the document" }), "fee");
      await waitFor(() => expect(fakePdf.finds.filter(find => find.query === "fee")).toHaveLength(1));

      await userEvent.click(screen.getByRole("button", { name: "Side by side" }));

      await waitFor(() => expect(fakePdf.finds.filter(find => find.query === "fee")).toHaveLength(2));
    });

    it("pops out of its place into a dialog", async () => {
      const place = document.createElement("div");
      document.body.append(place);
      show([ "The fee is due." ], [ "The fee is now due." ], { container: place, title: "Protocol" });
      await waitForStatus("1 change found.");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Pop out" }));

      expect(screen.getByRole("dialog", { name: "Protocol" })).toBeInTheDocument();
      place.remove();
    });
  });

  describe("in a narrow viewer", () => {
    beforeEach(() => {
      stubLayout({ elementHeight: 1000 });
    });

    it("shows the newer version alone, whatever was chosen, keeping the choice", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"split\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus("1 change found.");

      expect(getFrames()).toHaveLength(1);
      expect(screen.queryByRole("button", { name: "Side by side" })).not.toBeInTheDocument();
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"split\"");
      await waitFor(() => expect(getDrawnMarks(getNewerFrame())).toEqual({ 1: [ "iap-mark iap-mark-added" ] }));
    });

    it("marks where text was removed, opening what was removed on stepping to it", async () => {
      show([ "The fee is now due." ], [ "The fee is due." ]);
      await waitForStatus("1 change found.");

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      await waitForStatus("Change 1 of 1. Removed on page 1: “now”.");
      const removed = await screen.findByRole("region", { name: "Removed text" });
      expect(within(removed).getByText("now")).toBeInTheDocument();
      expect(getDrawnMarks(getNewerFrame())).toEqual({ 1: [ "iap-mark iap-mark-removed iap-mark-point selected" ] });
    });

    it("opens what the next change removed on stepping to it from another removal", async () => {
      show([ "The fee is now due. Pay it soon please." ], [ "The fee is due. Pay it please." ]);
      await waitForStatus("2 changes found.");
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      expect(within(await screen.findByRole("region", { name: "Removed text" })).getByText("now")).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      await waitForStatus("Change 2 of 2. Removed on page 1: “soon”.");
      expect(within(screen.getByRole("region", { name: "Removed text" })).getByText("soon")).toBeInTheDocument();
    });

    it("keeps what was removed out of sight while its marker is out of view", async () => {
      vi.restoreAllMocks();
      stubLayout({ elementHeight: MATCH_TOP / 2 });
      show([ "The fee is now due." ], [ "The fee is due." ]);
      await waitForStatus("1 change found.");

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      await waitForStatus("Change 1 of 1. Removed on page 1: “now”.");
      expect(screen.queryByRole("region", { name: "Removed text" })).not.toBeInTheDocument();
    });

    it("closes what was removed on Escape, without closing the viewer", async () => {
      const { onClose } = show([ "The fee is now due." ], [ "The fee is due." ]);
      await waitForStatus("1 change found.");
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await screen.findByRole("region", { name: "Removed text" });
      await userEvent.keyboard("a");
      expect(screen.getByRole("region", { name: "Removed text" })).toBeInTheDocument();

      await userEvent.keyboard("{Escape}");

      expect(screen.queryByRole("region", { name: "Removed text" })).not.toBeInTheDocument();
      expect(onClose).not.toHaveBeenCalled();
    });

    it("opens what was removed on clicking its marker, and closes it on a click elsewhere", async () => {
      show([ "The fee is now due." ], [ "The fee is due." ]);
      await waitForStatus("1 change found.");
      await waitFor(() => expect(getDrawnMarks(getNewerFrame())).not.toEqual({}));

      await userEvent.pointer({ keys: "[MouseLeft]", target: getNewerFrame(), coords: AT_POINT });
      await screen.findByRole("region", { name: "Removed text" });
      await userEvent.pointer({ keys: "[MouseLeft]", target: getNewerFrame(), coords: AT_POINT });
      expect(screen.getByRole("region", { name: "Removed text" })).toBeInTheDocument();
      await userEvent.click(screen.getByRole("heading", { name: "protocol-v2.pdf" }));

      expect(screen.queryByRole("region", { name: "Removed text" })).not.toBeInTheDocument();
      expect(getStatus()).toHaveTextContent("Change 1 of 1.");
    });

    it("marks where replaced text went from beside the text that replaced it", async () => {
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ]);
      await waitForStatus("1 change found.");

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      const removed = await screen.findByRole("region", { name: "Removed text" });
      expect(within(removed).getByText("100")).toBeInTheDocument();
      expect(getDrawnMarks(getNewerFrame())).toEqual({
        1: [ "iap-mark iap-mark-removed iap-mark-point selected", "iap-mark iap-mark-added selected" ],
      });
    });

    it("leaves changes of case and punctuation out, saying how many, and shows them when asked", async () => {
      show([ "Hello, world. The fee is due." ], [ "hello world The fee is due." ]);
      await waitForStatus("No changes found.");
      expect(screen.getByText("1 change to punctuation or case hidden.")).toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Show" }));

      await waitForStatus("1 change found.");
      expect(screen.getByRole("button", { name: "Show changes to punctuation and case" }))
        .toHaveAttribute("aria-pressed", "true");
      expect(window.localStorage.getItem(STRICT_KEY)).toBe("true");
      await userEvent.click(screen.getByRole("button", { name: "Show changes to punctuation and case" }));
      await waitForStatus("No changes found.");
    });

    it("always shows a change within a number", async () => {
      show([ "The fee is $1,000." ], [ "The fee is $1.000." ]);

      await waitForStatus("1 change found.");
    });

    it("says how many changes it leaves out beside those it shows", async () => {
      show([ "The Fee is 100." ], [ "The fee is 120." ]);

      await waitForStatus("1 change found.");
      expect(screen.getByText("1 change to punctuation or case hidden.")).toBeInTheDocument();
      expect(screen.queryByText("No changes found.")).not.toBeInTheDocument();
    });

    it("compares versions of different lengths", async () => {
      show([ "The fee is due." ], [ "The fee is due.", "A page added." ]);

      await waitForStatus("1 change found.");
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await waitForStatus("Change 1 of 1. Added on page 2: “A page added.”.");
    });

    it("says how far it has read the versions", async () => {
      fakePdf.holdTextFrom = 2;
      show([ "The fee is due.", "Page two." ], [ "The fee is now due.", "Page two." ]);

      expect(await screen.findByText("Comparing… page 1 of 2")).toBeInTheDocument();
      expect(screen.getByRole("progressbar", { name: "Looking for the changes" })).toHaveAttribute("aria-valuenow", "50");
      act(() => fakePdf.releaseText?.());

      await waitForStatus("1 change found.");
    });

    it("says while it reads and compares the versions", async () => {
      fakePdf.holdText = true;
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus("Comparing the versions…");
      expect(screen.getByText("Comparing…")).toBeInTheDocument();
      expect(await screen.findAllByRole("progressbar", { name: "Looking for the changes" })).toHaveLength(1);
      act(() => fakePdf.releaseText?.());

      await waitForStatus("1 change found.");
      expect(screen.queryByRole("progressbar", { name: "Looking for the changes" })).not.toBeInTheDocument();
    });

    it("stops reading the versions once closed", async () => {
      fakePdf.holdText = true;
      const { unmount } = show([ "The fee is due." ], [ "The fee is now due." ]);
      await screen.findByRole("progressbar", { name: "Looking for the changes" });
      await waitFor(() => expect(fakePdf.releaseText).toBeDefined());
      unmount();

      act(() => fakePdf.releaseText?.());

      await waitFor(() => expect(fakePdf.textReads).toEqual([ 1, 1 ]));
    });

    it("says a version without text cannot be compared", async () => {
      show([ "", " " ], [ "The fee is due." ]);

      await waitForStatus("The older version has no text to compare. It may be a scan.");
    });

    it("says when the versions differ too much to show their changes", async () => {
      vi.mocked(diffArrays).mockReturnValueOnce(undefined as never);
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus("These versions differ too much to show their changes.");
    });

    it("says which version could not be opened", async () => {
      vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(
        new Response(new TextEncoder().encode(url), { status: url === OLDER ? 404 : 200 }),
      )));
      show([ "The fee is due." ], [ "The fee is now due." ]);

      expect(await screen.findByText("The older version could not be opened")).toBeInTheDocument();
      expect(screen.queryByText("The newer version could not be opened")).not.toBeInTheDocument();
      // Nothing is compared, and nothing says it is
      await screen.findByText("Page 1 of 1");
      expect(getStatus()).toHaveTextContent(/^$/);
      expect(screen.queryByText("Comparing…")).not.toBeInTheDocument();
      expect(screen.queryByRole("progressbar", { name: "Looking for the changes" })).not.toBeInTheDocument();
    });

    it("says when the newer version could not be opened", async () => {
      vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(
        new Response(new TextEncoder().encode(url), { status: url === NEWER ? 404 : 200 }),
      )));
      show([ "The fee is due." ], [ "The fee is now due." ]);

      expect(await screen.findByText("The newer version could not be opened")).toBeInTheDocument();
    });
  });
});
