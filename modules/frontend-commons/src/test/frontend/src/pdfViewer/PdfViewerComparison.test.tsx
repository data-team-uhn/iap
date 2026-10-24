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
import { useState } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { diffArrays } from "diff";

import { appTheme } from "@iap/frontend-commons/appTheme";
import PdfViewer, { type PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";

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

function show(older: string[], newer: string[], props: Partial<PdfViewerProps> = {}) {
  fakePdf.documents = { [OLDER]: older, [NEWER]: newer };
  const onClose = vi.fn();
  const { unmount } = render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <PdfViewer url={NEWER} marks={[]} compareWith={{ url: OLDER }} onClose={onClose} {...props} />
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

function getCard() {
  return screen.getByRole("group", { name: "Change" });
}

function getChangesMenuButton() {
  return screen.getByRole("button", { name: /^Show (\d+ )?changes? (Off|On the newer version|Side by side)$/ });
}

// Chooses how changes are shown, from their menu.
async function chooseView(name: string) {
  await userEvent.click(getChangesMenuButton());
  await userEvent.click(screen.getByRole("menuitemradio", { name }));
}

describe("PdfViewer compared with an older version", () => {
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
      window.localStorage.setItem(VIEW_KEY, "\"split\"");
    });

    it("shows the changes on the newer version when nothing was chosen", async () => {
      window.localStorage.removeItem(VIEW_KEY);
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus(/^Change 1 of 1\./);
      expect(getFrames()).toHaveLength(1);
      expect(getChangesMenuButton()).toHaveAccessibleName("Show 1 change On the newer version");
    });

    it("neither reads the older version nor shows changes while they are off, until turned on", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"off\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await screen.findByText("Page 1 of 1");

      expect(vi.mocked(fetch).mock.calls.map(([ url ]) => url)).toEqual([ NEWER ]);
      expect(getDrawnMarks(getNewerFrame())).toEqual({});
      expect(getStatus()).toHaveTextContent(/^$/);
      expect(screen.queryByRole("button", { name: /change$/ })).not.toBeInTheDocument();
      await userEvent.click(getChangesMenuButton());
      expect(screen.getByRole("menuitemcheckbox", { name: /Include minor changes/ })).toHaveAttribute("aria-disabled", "true");
      await userEvent.click(screen.getByRole("menuitemradio", { name: "On the newer version" }));

      await waitForStatus(/^Change 1 of 1\./);
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"unified\"");
    });

    it("shows the versions side by side, removed text marked in the older and added text in the newer", async () => {
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ], {
        compareWith: { url: OLDER, label: "Version 1" },
      });

      await waitForStatus("Change 1 of 1. Text replaced on page 1. “100” became “120”.");
      expect(screen.getByRole("dialog", { name: "protocol-v2.pdf" })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Version 1" })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Newer version" })).toBeInTheDocument();
      const [ older, newer ] = getFrames();
      // The text added in place of the text removed starts with a point
      await waitFor(() => expect(getDrawnMarks(newer)).toEqual({
        1: [ "iap-mark iap-mark-added iap-mark-point iap-mark-start selected", "iap-mark iap-mark-added selected" ],
      }));
      expect(getDrawnMarks(older)).toEqual({ 1: [ "iap-mark iap-mark-removed selected" ] });
      expect(getChangesMenuButton()).toHaveAccessibleName("Show 1 change Side by side");
    });

    it("steps through the changes from above both versions, which show them, with no card for them", async () => {
      show([ "The fee is 100.", "Due at 5." ], [ "The fee is 120.", "Due at 6." ]);
      await waitForStatus(/^Change 1 of 2\./);

      expect(screen.getByText("Change 1 of 2")).toBeInTheDocument();
      expect(screen.queryByRole("group", { name: "Change" })).not.toBeInTheDocument();
      expect(screen.queryByRole("list", { name: "Changes" })).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await waitForStatus(/^Change 2 of 2\./);
      expect(screen.getByText("Change 2 of 2")).toBeInTheDocument();
    });

    it("keeps the caller's passage on its card while changes are stepped through beside it", async () => {
      show([ "The fee is 100." ], [ "The fee is 120." ], {
        marks: [ { id: "fee", quote: "The fee", kind: "annotation" } ],
      });
      const card = await screen.findByRole("group", { name: "Passage" });
      await waitFor(() => expect(card).toHaveTextContent("“The fee”"));

      await userEvent.click(await screen.findByRole("button", { name: "Next change" }));

      await waitForStatus(/^Change 1 of 1\./);
      expect(screen.getByRole("group", { name: "Passage" })).toHaveTextContent("“The fee”");
    });

    it("shows a change on its card as what it removed and added, among the words around it, with its page", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is 100 dollars." ], [ "The fee is 120 dollars." ]);

      await waitForStatus(/^Change 1 of 1\./);
      expect(within(getCard()).getByRole("deletion", { hidden: true })).toHaveTextContent("100");
      expect(within(getCard()).getByRole("insertion", { hidden: true })).toHaveTextContent("120");
      expect(getCard()).toHaveTextContent("The fee is 100 → 120 dollars.");
      expect(await within(getCard()).findByRole("button", { name: "Show the change on page 1" }))
        .toHaveTextContent(/page 1$/);
      expect(getCard()).not.toHaveTextContent("found");
    });

    it("compares with another older version in place, the newer keeping its place", async () => {
      const OTHER = "/content/pdfs/protocol-v0.pdf";
      fakePdf.documents = {
        [OLDER]: [ "One change here.", "Nothing.", "Another here." ],
        [OTHER]: [ "Preface.", "One thing here.", "Nothing.", "Another place." ],
        [NEWER]: [ "One edit here.", "Nothing.", "Another there." ],
      };
      // The caller keeps the selection, and still has the last comparison's change selected after it.
      function Compared({ older }: { older: string }) {
        const [ selected, setSelected ] = useState<string | undefined>(undefined);
        return (
          <PdfViewer url={NEWER} marks={[]} compareWith={{ url: older }} selectedMarkId={selected}
            onSelectMark={setSelected} onClose={vi.fn()} />
        );
      }
      const { rerender } = render(<ThemeProvider theme={appTheme} defaultMode="light"><Compared older={OLDER} /></ThemeProvider>);
      await waitForStatus(/^Change 1 of 2\./);
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await waitFor(() => expect(getFrames().map(frame => frame.scrollTop)).toEqual([ 2 * MATCH_TOP, 2 * MATCH_TOP ]));

      rerender(<ThemeProvider theme={appTheme} defaultMode="light"><Compared older={OTHER} /></ThemeProvider>);

      await waitForStatus(/^Change 1 of 3\. Text removed on page 1\./);
      expect(vi.mocked(fetch).mock.calls.map(([ url ]) => url)).toEqual([ NEWER, OLDER, OTHER ]);
      expect(getNewerFrame().scrollTop).toBe(2 * MATCH_TOP);
      // The older version is brought to the newer's place, which in the stand-in scrolls as far again.
      await waitFor(() => expect(getFrames()[0].scrollTop).toBe(3 * MATCH_TOP));
      // Opened at its own first page, not at the last version's page 3; the stand-in counts no page scrolled to.
      expect(screen.getAllByText(/^Page \d of \d$/).map(bar => bar.textContent)).toEqual([ "Page 1 of 4", "Page 3 of 3" ]);
    });

    it("shows a page bar over each version, and one search and zoom for both", async () => {
      show([ "The fee is due.", "Page two." ], [ "The fee is now due." ]);
      await waitForStatus(/^Change 1 of 2\./);

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
      await waitForStatus("Change 1 of 2. Text replaced on page 1. “change” became “edit”.");
      expect(screen.getByRole("button", { name: "Previous change" })).toBeDisabled();
      await waitFor(() => expect(getFrames().map(frame => frame.scrollTop)).toEqual([ MATCH_TOP, MATCH_TOP ]));

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      await waitForStatus("Change 2 of 2. Text replaced on page 3. “here.” became “there.”.");
      expect(screen.getByRole("button", { name: "Next change" })).toBeDisabled();
      // Every mark is as far down its page in the stand-in, so each step scrolls as far again.
      await waitFor(() => expect(getFrames().map(frame => frame.scrollTop)).toEqual([ 2 * MATCH_TOP, 2 * MATCH_TOP ]));
    });

    it("selects a change clicked in one version and brings it into view in the other", async () => {
      show([ "The fee is 100.", "Due at 5." ], [ "The fee is 120.", "Due at 6." ]);
      await waitForStatus(/^Change 1 of 2\./);
      await waitFor(() => expect(getDrawnMarks(getNewerFrame())).not.toEqual({}));
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      await waitForStatus(/^Change 2 of 2\./);
      await waitFor(() => expect(getNewerFrame().scrollTop).toBe(2 * MATCH_TOP));

      await userEvent.pointer({ keys: "[MouseLeft]", target: getFrames()[0], coords: AT_MARK });

      await waitForStatus(/^Change 1 of 2\./);
      // Every mark is as far down its page in the stand-in, so bringing one into view scrolls as far again.
      await waitFor(() => expect(getNewerFrame().scrollTop).toBe(3 * MATCH_TOP));
      expect(getFrames()[0].scrollTop).toBe(2 * MATCH_TOP);
    });

    it("fits both versions to their width with one button", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus(/^Change 1 of 1\./);
      const fitted = fakePdf.scales.filter(scale => scale === "page-width").length;

      await userEvent.click(screen.getByRole("button", { name: "Fit to width" }));

      expect(fakePdf.scales.filter(scale => scale === "page-width")).toHaveLength(fitted + 2);
    });

    describe("scrolling", () => {
      // Opens the versions, scrolled by hand before they are compared, so neither is taken to the first change.
      async function showUnmoved(older: string[], newer: string[], props: Partial<PdfViewerProps> = {}) {
        fakePdf.holdText = true;
        show(older, newer, props);
        await waitFor(() => expect(screen.getAllByText(/^Page 1 of/)).toHaveLength(2));
        getFrames().forEach(frame => fireEvent.wheel(frame));
        await waitFor(() => expect(fakePdf.releaseText).toBeDefined());
        act(() => fakePdf.releaseText?.());
        await waitForStatus(/^Change 1 of/);
        await waitFor(() => expect(getFrames().map(each => Object.keys(getDrawnMarks(each)))).not.toContainEqual([]));
        return getFrames();
      }

      async function scrollByHand(frame: HTMLElement) {
        fireEvent.wheel(frame);
        fireEvent.scroll(frame);
        await new Promise(resolve => requestAnimationFrame(resolve));
      }

      it("scrolls the other version with the one scrolled by hand, matched by their text", async () => {
        const [ older, newer ] = await showUnmoved([ "The fee is due." ], [ "The fee is now due." ]);

        await scrollByHand(older);

        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));
        expect(older.scrollTop).toBe(0);
      });

      it("keeps a version moved with the other from moving it back", async () => {
        const [ older, newer ] = await showUnmoved([ "The fee is due." ], [ "The fee is now due." ]);
        await scrollByHand(older);
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));

        fireEvent.scroll(newer);
        await new Promise(resolve => requestAnimationFrame(resolve));

        expect(older.scrollTop).toBe(0);
      });

      it("leaves the other version where it is for scrolling not done by hand", async () => {
        const [ older, newer ] = await showUnmoved([ "The fee is due." ], [ "The fee is now due." ]);

        fireEvent.scroll(older);
        await new Promise(resolve => requestAnimationFrame(resolve));

        expect(newer.scrollTop).toBe(0);
      });

      it("moves the older version with the newer after zooming", async () => {
        const [ older ] = await showUnmoved([ "The fee is due." ], [ "The fee is now due." ]);
        getNewerFrame().scrollTop = MATCH_TOP;

        await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));

        await waitFor(() => expect(older.scrollTop).toBe(MATCH_TOP));
      });

      it("leaves a version where a change was stepped to, though it was still on its way to a place", async () => {
        fakePdf.drawShownPageOnly = true;
        const [ older, newer ] = await showUnmoved(
          [ "Shared words here." ],
          [ "A page added first.", "Shared words here, and more." ],
        );
        fakePdf.holdDrawing = true;
        // The newer version's second page, where the place is, waits to be drawn
        await scrollByHand(older);
        await userEvent.click(screen.getByRole("button", { name: "Next change" }));
        await userEvent.click(screen.getByRole("button", { name: "Previous change" }));
        await waitForStatus(/^Change 1 of 2\. Text added on page 1\./);
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));

        // Every page drawn now, the place's page too
        fakePdf.drawShownPageOnly = false;
        act(() => fakePdf.releaseDrawing());

        await waitFor(() => expect(newer.querySelector("[data-page-number=\"2\"] .textLayer")).not.toBeNull());
        expect(newer.scrollTop).toBe(MATCH_TOP);
      });

      it("brings the older version along to a mark only the newer has, which it follows", async () => {
        fakePdf.holdText = true;
        show([ "The fee is due.", "Kept." ], [ "The fee is now due.", "Kept." ], {
          marks: [ { id: "fee", quote: "The fee", kind: "annotation" } ],
        });
        await waitFor(() => expect(screen.getAllByText(/^Page 1 of/)).toHaveLength(2));
        const [ older, newer ] = getFrames();
        [ older, newer ].forEach(frame => fireEvent.wheel(frame));
        await waitFor(() => expect(fakePdf.releaseText).toBeDefined());
        act(() => fakePdf.releaseText?.());
        await screen.findByRole("button", { name: /^Show 1 change/ });
        await waitFor(() => expect(Object.keys(getDrawnMarks(newer))).not.toEqual([]));
        const before = older.scrollTop;

        await userEvent.click(await screen.findByRole("button", { name: "Show the passage on page 1" }));

        await waitFor(() => expect(newer.scrollTop).toBeGreaterThan(0));
        await waitFor(() => expect(older.scrollTop).toBeGreaterThan(before));
      });

      it("brings the other version along to a page gone to from a version's page bar", async () => {
        const [ , newer ] = await showUnmoved([ "The fee is due.", "Kept." ], [ "The fee is now due.", "Kept." ]);
        const before = newer.scrollTop;

        // The older version's page bar comes first
        await userEvent.click(screen.getAllByRole("button", { name: "Next page" })[0]);

        await waitFor(() => expect(newer.scrollTop).not.toBe(before));
      });

      it("takes the other version to a page not drawn yet, then to the place once it is", async () => {
        fakePdf.drawShownPageOnly = true;
        const [ older, newer ] = await showUnmoved(
          [ "Shared words here." ],
          [ "A page added first.", "Shared words here." ],
        );

        await scrollByHand(older);

        expect(await screen.findByText("Page 2 of 2")).toBeInTheDocument();
        await waitFor(() => expect(newer.scrollTop).toBe(MATCH_TOP));
      });
    });

    it("shows the newer version alone when asked, and remembers it", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus(/^Change 1 of 1\./);
      expect(getFrames()).toHaveLength(2);

      await chooseView("On the newer version");

      expect(getFrames()).toHaveLength(1);
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"unified\"");
      await chooseView("Side by side");
      expect(getFrames()).toHaveLength(2);
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"split\"");
    });

    it("zooms the older version as the newer was zoomed while shown alone", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus(/^Change 1 of 1\./);
      await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));

      await chooseView("Side by side");

      await waitFor(() => expect(fakePdf.viewers.at(-1)?.currentScaleValue).toBe("2"));
    });

    it("searches the older version too once it is shown", async () => {
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is due." ], [ "The fee is now due." ]);
      await waitForStatus(/^Change 1 of 1\./);
      await userEvent.type(screen.getByRole("textbox", { name: "Search the document" }), "fee");
      await waitFor(() => expect(fakePdf.finds.filter(find => find.query === "fee")).toHaveLength(1));

      await chooseView("Side by side");

      await waitFor(() => expect(fakePdf.finds.filter(find => find.query === "fee")).toHaveLength(2));
    });

    it("leaves minor changes out, saying how many, and includes them when asked, the menu staying open", async () => {
      show([ "Hello, world. The fee is due." ], [ "hello world The fee is due." ]);
      const summary = "No changes found. 1 minor change of punctuation or letter case left out.";
      expect(await screen.findByRole("button", { name: "Show changes Side by side" })).toBeInTheDocument();
      expect(await screen.findByText(summary, { ignore: "[role=status]" })).toBeInTheDocument();
      await userEvent.click(getChangesMenuButton());
      const minor = screen.getByRole("menuitemcheckbox", { name: /Include 1 minor change/ });
      expect(minor).toHaveAttribute("aria-checked", "false");
      expect(minor).toHaveTextContent("Of punctuation or letter case");

      await userEvent.click(minor);

      expect(screen.getByRole("menuitemcheckbox", { name: /Include 1 minor change/ })).toHaveAttribute("aria-checked", "true");
      expect(window.localStorage.getItem(STRICT_KEY)).toBe("true");
      await userEvent.keyboard("{Escape}");
      expect(await screen.findByText("Including 1 minor change of punctuation or letter case.", { ignore: "[role=status]" }))
        .toBeInTheDocument();
      expect(getChangesMenuButton()).toHaveAccessibleName("Show 1 change Side by side");
      await userEvent.click(getChangesMenuButton());
      await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /Include 1 minor change/ }));
      await userEvent.keyboard("{Escape}");
      expect(await screen.findByText(summary, { ignore: "[role=status]" })).toBeInTheDocument();
    });

    it("has no minor changes to include when there are none", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      expect(await screen.findByRole("button", { name: "Show 1 change Side by side" })).toBeInTheDocument();
      await userEvent.click(getChangesMenuButton());

      expect(screen.getByRole("menuitemcheckbox", { name: /No minor changes/ })).toHaveAttribute("aria-disabled", "true");
    });

    it("lists the changes beside the newer version shown alone, where there is room for the list", async () => {
      vi.restoreAllMocks();
      stubLayout({ elementWidth: 1600, elementHeight: 1000 });
      window.localStorage.setItem(VIEW_KEY, "\"unified\"");
      show([ "The fee is 100.", "Due at 5." ], [ "The fee is 120.", "Due at 6." ]);
      await waitForStatus(/^Change 1 of 2\./);

      const list = screen.getByRole("list", { name: "Changes" });

      expect(within(list).getAllByRole("listitem")).toHaveLength(2);
      expect(within(list).getAllByRole("deletion").map(line => line.textContent)).toEqual([ "100.", "5." ]);
      expect(screen.queryByRole("group", { name: "Change" })).not.toBeInTheDocument();
      await userEvent.click(await within(list).findByRole("button", { name: "Show change 2 on page 2" }));
      await waitForStatus(/^Change 2 of 2\./);
    });

    it("lists the caller's passages beside both versions, and with the changes beside the newer alone", async () => {
      vi.restoreAllMocks();
      stubLayout({ elementWidth: 1600, elementHeight: 1000 });
      show([ "The fee is 100.", "Due." ], [ "The fee is 120.", "Due." ], {
        marks: [
          { id: "fee", quote: "The fee", kind: "annotation" },
          { id: "due", quote: "Due", kind: "assistant" },
        ],
        markName: { one: "note", many: "notes" },
      });

      const list = await screen.findByRole("list", { name: "Notes" });
      expect(within(list).getAllByRole("listitem")).toHaveLength(2);
      await chooseView("On the newer version");

      const notes = () => within(screen.getByRole("list", { name: "Notes and changes" }));
      await waitFor(() => expect(notes().getAllByRole("listitem")).toHaveLength(3));
      expect(notes().getByRole("insertion", { hidden: true })).toHaveTextContent("120.");
      // Each is called and numbered as what it is, a change as a change whatever the caller calls its marks.
      expect(screen.getByText("Note 1 of 2")).toBeInTheDocument();
      expect(notes().getByRole("button", { name: /^(Select|Show) note 2\b/ })).toBeInTheDocument();
      expect(notes().getByRole("button", { name: "Show change 1 on page 1" }))
        .toHaveAccessibleDescription("Text replaced on page 1. “100.” became “120.”.");
      await userEvent.click(screen.getByRole("button", { name: "Next change" }));
      expect(await screen.findByText("Change 1 of 1")).toBeInTheDocument();
      await waitForStatus(/^Change 1 of 1\./);
      expect(screen.getByRole("button", { name: "Previous note" })).toBeEnabled();
      expect(screen.getByRole("button", { name: "Next note" })).toBeEnabled();
      // With a change selected, which side by side is not listed, the list says how many notes it has
      await chooseView("Side by side");
      expect(await screen.findByText("2 notes")).toBeInTheDocument();
    });

    it("pops out of its place into a dialog", async () => {
      const place = document.createElement("div");
      document.body.append(place);
      show([ "The fee is due." ], [ "The fee is now due." ], { container: place, title: "Protocol" });
      await waitForStatus(/^Change 1 of 1\./);
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
      await waitForStatus(/^Change 1 of 1\./);

      expect(getFrames()).toHaveLength(1);
      await userEvent.click(getChangesMenuButton());
      expect(screen.getByRole("menuitemradio", { name: /Side by side/ })).toHaveAttribute("aria-disabled", "true");
      expect(screen.getByRole("menuitemradio", { name: "On the newer version" })).toHaveAttribute("aria-checked", "true");
      expect(window.localStorage.getItem(VIEW_KEY)).toBe("\"split\"");
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(getDrawnMarks(getNewerFrame()))
        .toEqual({ 1: [ "iap-mark iap-mark-added selected" ] }));
    });

    it("says what a change did to a screen reader, which reads nothing inside the card's button", async () => {
      show([ "The fee is 100." ], [ "The fee is 120." ]);

      expect(await screen.findByRole("button", { name: "Show the change on page 1" }))
        .toHaveAccessibleDescription("Text replaced on page 1. “100.” became “120.”.");
    });

    it("cuts a long change short on its card, and shows all of it when asked", async () => {
      // Taller than the card's lines, whatever is measured
      vi.spyOn(Element.prototype, "scrollHeight", "get").mockReturnValue(100);
      show([ "The fee is 100." ], [ "The fee is 120." ]);
      const showAll = await screen.findByRole("button", { name: "Show the whole change" });
      expect(showAll).toHaveAttribute("aria-expanded", "false");

      await userEvent.click(showAll);

      expect(screen.getByRole("button", { name: "Show less of the change" })).toHaveAttribute("aria-expanded", "true");
    });

    it("names the card after the mark on it, and each step button after the mark it goes to", async () => {
      show([ "The fee is 100." ], [ "The fee is 120." ], {
        marks: [ { id: "fee", quote: "The fee", kind: "annotation" } ],
      });
      await waitFor(() => expect(screen.getByRole("group", { name: "Passage" })).toHaveTextContent("Passage 1 of 1"));

      await userEvent.click(screen.getByRole("button", { name: "Next change" }));

      expect(await screen.findByRole("group", { name: "Change" })).toHaveTextContent("Change 1 of 1");
      expect(screen.getByRole("button", { name: "Previous passage" })).toBeEnabled();
    });

    it("says what it found once, not again with each search, when nothing is selected", async () => {
      show([ "The fee is due." ], [ "The fee is due." ]);
      await waitForStatus("No changes found.");
      const summary = () => document.querySelector("[aria-live=polite]");

      await userEvent.click(screen.getByRole("button", { name: "Search the document" }));
      await userEvent.keyboard("fee{Enter}");

      await waitFor(() => expect(getStatus()).not.toHaveTextContent("No changes found."));
      expect(summary()).toHaveTextContent(/^$/);
    });

    it("says what it found once compared, when the status says something else", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ], {
        marks: [ { id: "fee", quote: "The fee", kind: "annotation" } ],
      });

      await waitForStatus(/^The passage is marked on page 1/);
      expect(await screen.findByText("1 change found.")).toHaveAttribute("aria-live", "polite");
    });

    it("opens its menu on the choice made, and lets the keyboard reach the choice there is no room for", async () => {
      show([ "The fee is due." ], [ "The fee is now due." ]);
      const button = await screen.findByRole("button", { name: /^Show 1 change/ });
      expect(button).toHaveAttribute("aria-expanded", "false");

      await userEvent.click(button);

      expect(button).toHaveAttribute("aria-expanded", "true");
      expect(document.activeElement).toHaveTextContent("On the newer version");
      await userEvent.keyboard("{ArrowDown}");
      expect(document.activeElement).toHaveTextContent("Side by sideNeeds more room");
    });

    it("marks where text was removed, and shows what was removed on its card", async () => {
      show([ "The fee is now due." ], [ "The fee is due." ]);

      await waitForStatus("Change 1 of 1. Text removed on page 1. It said “now”.");
      expect(within(getCard()).getByRole("deletion", { hidden: true })).toHaveTextContent("now");
      expect(getCard()).not.toHaveTextContent("“”");
      await waitFor(() => expect(getDrawnMarks(getNewerFrame()))
        .toEqual({ 1: [ "iap-mark iap-mark-removed iap-mark-point selected" ] }));
    });

    it("steps through the changes on the card, by its buttons or by swiping it", async () => {
      show([ "The fee is now due. Pay it soon please." ], [ "The fee is due. Pay it please." ]);
      await waitForStatus(/^Change 1 of 2\..*“now”/);

      await userEvent.click(within(getCard()).getByRole("button", { name: "Next change" }));

      await waitForStatus(/^Change 2 of 2\..*“soon”/);
      expect(within(getCard()).getByRole("deletion", { hidden: true })).toHaveTextContent("soon");
    });

    it("leaves what minor changes there are to its menu, once there are other changes", async () => {
      show([ "The Fee is 100." ], [ "The fee is 120." ]);
      await waitForStatus(/^Change 1 of 1\./);
      expect(screen.queryByText(/left out/, { ignore: "[role=status], [aria-live]" })).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Show 1 change On the newer version" }));
      await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /Include 1 minor change/ }));

      expect(window.localStorage.getItem(STRICT_KEY)).toBe("true");
      await userEvent.keyboard("{Escape}");
      await waitForStatus(/^Change 1 of 2\./);
      expect(screen.queryByText(/Including 1 minor change/, { ignore: "[role=status], [aria-live]" }))
        .not.toBeInTheDocument();
    });

    it("says what minor changes there are when there are no other changes", async () => {
      show([ "The Fee is due." ], [ "The fee is due." ]);

      expect(await screen.findByText("No changes found. 1 minor change of punctuation or letter case left out.", {
        ignore: "[role=status]",
      })).toBeInTheDocument();
    });

    it("always shows a change within a number", async () => {
      show([ "The fee is $1,000." ], [ "The fee is $1.000." ]);

      await waitForStatus("Change 1 of 1. Text replaced on page 1. “$1,000.” became “$1.000.”.");
    });

    it("compares versions of different lengths", async () => {
      show([ "The fee is due." ], [ "The fee is due.", "A page added." ]);

      await waitForStatus("Change 1 of 1. Text added on page 2. It says “A page added.”.");
    });

    it("says how far it has read the versions", async () => {
      fakePdf.holdTextFrom = 2;
      show([ "The fee is due.", "Page two." ], [ "The fee is now due.", "Page two." ]);

      expect(await screen.findByText("Comparing… page 1 of 2")).toBeInTheDocument();
      expect(screen.getByRole("progressbar", { name: "Looking for the changes" }))
        .toHaveAttribute("aria-valuenow", "50");
      act(() => fakePdf.releaseText?.());

      await waitForStatus(/^Change 1 of 1\./);
    });

    it("says while it reads and compares the versions", async () => {
      fakePdf.holdText = true;
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus("Comparing the versions…");
      expect(screen.getByText("Comparing…")).toBeInTheDocument();
      expect(await screen.findAllByRole("progressbar", { name: "Looking for the changes" })).toHaveLength(1);
      act(() => fakePdf.releaseText?.());

      await waitForStatus(/^Change 1 of 1\./);
      expect(screen.queryByRole("progressbar", { name: "Looking for the changes" })).not.toBeInTheDocument();
      expect(screen.queryByText("Comparing…")).not.toBeInTheDocument();
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
      const line = "The older version has no text to compare. It may be a scan.";
      expect(screen.getByText(line, { ignore: "[role=status]" })).toBeInTheDocument();
    });

    it("says when the versions differ too much to show their changes", async () => {
      vi.mocked(diffArrays).mockReturnValueOnce(undefined as never);
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus("These versions differ too much to show their changes.");
    });

    it("says when comparing the versions failed", async () => {
      vi.mocked(diffArrays).mockImplementationOnce(() => {
        throw new Error("Unforeseen");
      });
      show([ "The fee is due." ], [ "The fee is now due." ]);

      await waitForStatus("These versions could not be compared.");
    });

    it("says no changes were found", async () => {
      show([ "The fee is due." ], [ "The fee\nis due." ]);

      await waitForStatus("No changes found.");
      expect(screen.getByText("No changes found.", { ignore: "[role=status]" })).toBeInTheDocument();
      expect(screen.queryByRole("group", { name: "Change" })).not.toBeInTheDocument();
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
