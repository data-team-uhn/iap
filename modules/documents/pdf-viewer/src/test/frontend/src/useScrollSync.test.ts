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
// Tests scrolling two versions together, on panes built by hand. jsdom lays nothing out, so each test sets
// where the drawn text is.
import { fireEvent, renderHook } from "@testing-library/react";

import type { Alignment } from "@iap/documents-pdf-viewer/revisionDiffModel";
import type { ShownDocument } from "@iap/documents-pdf-viewer/usePdfView";
import { useScrollSync } from "@iap/documents-pdf-viewer/useScrollSync";

// The one word both versions share: on the older version's first page, and on the newer version's second.
const ALIGNMENT: Alignment = {
  older: [ { page: 1, start: 0, end: 3, text: "Fee" } ],
  newer: [ { page: 2, start: 4, end: 7, text: "Fee" } ],
  pairs: [ [ 0, 0 ] ],
};

// Two words both versions share, a line apart in the older version and two lines apart in the newer.
const SPREAD = {
  older: [ { page: 1, start: 0, end: 3, text: "Fee" }, { page: 1, start: 3, end: 6, text: "Fie" } ],
  newer: [ { page: 1, start: 0, end: 3, text: "Fee" }, { page: 1, start: 3, end: 6, text: "Fie" } ],
  pairs: [ [ 0, 0 ], [ 1, 1 ] ],
} satisfies Alignment;

// A line of drawn text, and how far down the window it starts. Each line is 20 high.
interface Line {
  text: string;
  top: number;
}

// A pane's frame with a first page whose text is not drawn yet, and the event bus that reports when it is.
// `drawText` draws the lines given, by default one word at the top of the window, and tells the bus.
function createShown() {
  const frame = document.createElement("div");
  const page = document.createElement("div");
  page.dataset.pageNumber = "1";
  page.getBoundingClientRect = () => new DOMRect(0, 0, 100, 1000);
  frame.append(page);
  document.body.append(frame);
  const listeners: (() => void)[] = [];
  const eventBus = {
    on: (_name: string, listener: () => void, { signal }: { signal: AbortSignal }) => {
      listeners.push(listener);
      signal.addEventListener("abort", () => listeners.splice(listeners.indexOf(listener), 1));
    },
  };
  const drawText = (lines: Line[] = [ { text: "Fee", top: 0 } ]) => {
    const textLayer = document.createElement("div");
    textLayer.className = "textLayer";
    for (const { text, top } of lines) {
      const span = document.createElement("span");
      span.textContent = text;
      span.getBoundingClientRect = () => new DOMRect(0, top, 10, 20);
      textLayer.append(span);
    }
    page.append(textLayer);
    listeners.forEach(listener => listener());
  };
  return { shown: { frame, eventBus } as unknown as ShownDocument, drawText };
}

// Gives a frame a height, which puts its middle half that far down the window.
function setHeight(frame: HTMLElement, height: number) {
  Object.defineProperty(frame, "clientHeight", { value: height, configurable: true });
}

async function scrollByHand(frame: HTMLElement) {
  fireEvent.wheel(frame);
  fireEvent.scroll(frame);
  await new Promise(resolve => requestAnimationFrame(resolve));
}

describe("useScrollSync", () => {
  beforeEach(() => {
    // Text is drawn where the line holding it is.
    Range.prototype.getClientRects = function getClientRects(this: Range) {
      return [ this.startContainer.parentElement?.getBoundingClientRect() ] as unknown as DOMRectList;
    };
  });

  afterEach(() => {
    document.body.replaceChildren();
    delete (Range.prototype as Partial<Range>).getClientRects;
    vi.restoreAllMocks();
  });

  it("moves the other version once the text in the middle of the one scrolled is drawn", async () => {
    const older = createShown();
    const newer = createShown();
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, ALIGNMENT));

    await scrollByHand(older.shown.frame);
    expect(showNewer).not.toHaveBeenCalled();
    newer.drawText();
    expect(showNewer).not.toHaveBeenCalled();
    older.drawText();

    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 2, char: 4 }, 0);
  });

  it("puts the other version's text as far from its middle as the text is from the middle of the one scrolled", async () => {
    const older = createShown();
    // The line of text starts 10 above the middle of the version scrolled.
    older.drawText([ { text: "Fee", top: -10 } ]);
    const newer = createShown();
    setHeight(newer.shown.frame, 100);
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, ALIGNMENT));

    await scrollByHand(older.shown.frame);

    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 2, char: 4 }, 40);
  });

  it("keeps the other version still while the one scrolled goes through text only it has", async () => {
    const older = createShown();
    older.drawText([ { text: "Fee", top: -10 } ]);
    const newer = createShown();
    setHeight(newer.shown.frame, 100);
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, { ...ALIGNMENT, pairs: [] }));

    await scrollByHand(older.shown.frame);

    // The change's place, put in the middle, with no offset for how far down its line the scrolled text is
    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 2, char: 4 }, 50);
  });

  it("moves the other version as far between two of its lines as the middle is between theirs", async () => {
    const older = createShown();
    older.drawText([ { text: "Fee", top: 0 }, { text: "Fie", top: 20 } ]);
    setHeight(older.shown.frame, 10);
    const newer = createShown();
    newer.drawText([ { text: "Fee", top: 0 }, { text: "Fie", top: 40 } ]);
    setHeight(newer.shown.frame, 100);
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, SPREAD));

    await scrollByHand(older.shown.frame);

    // A quarter of the way from one line to the next. The next line is 40 below in the newer version, so
    // the first line goes 10 above the middle.
    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 1, char: 0 }, 40);
  });

  it("moves the other version without a jump into, through and out of text only the one scrolled has", async () => {
    const older = createShown();
    older.drawText([ { text: "Fee", top: 0 }, { text: "New", top: 20 }, { text: "Fie", top: 40 } ]);
    const newer = createShown();
    newer.drawText([ { text: "Fee", top: 0 }, { text: "Fie", top: 20 } ]);
    setHeight(newer.shown.frame, 100);
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, {
      older: [ ...SPREAD.older.slice(0, 1), { page: 1, start: 3, end: 6, text: "New" }, { page: 1, start: 6, end: 9, text: "Fie" } ],
      newer: SPREAD.newer,
      pairs: [ [ 0, 0 ], [ 2, 1 ] ],
    }));
    const lineTops = [ 0, 20 ];
    // How far down the newer version's text its middle is, for each middle of the older version
    const followed = [];
    for (const middle of [ 5, 15, 25, 35, 45 ]) {
      setHeight(older.shown.frame, 2 * middle);
      await scrollByHand(older.shown.frame);
      const [ { char }, atY ] = showNewer.mock.lastCall as [ { char: number }, number ];
      followed.push(lineTops[char / 3] + 50 - atY);
    }

    expect(followed).toEqual([ 5, 15, 20, 20, 25 ]);
  });

  it("follows the line in the middle alone while the other version's text is not drawn", async () => {
    const older = createShown();
    older.drawText([ { text: "Fee", top: 0 }, { text: "Fie", top: 20 } ]);
    setHeight(older.shown.frame, 10);
    const newer = createShown();
    setHeight(newer.shown.frame, 100);
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, SPREAD));

    await scrollByHand(older.shown.frame);

    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 1, char: 0 }, 45);
  });

  it("lets both versions go their own way once released, until the next scroll by hand", async () => {
    const older = createShown();
    older.drawText();
    const showNewer = vi.fn();
    const { result } = renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: createShown().shown, showPoint: showNewer },
    }, ALIGNMENT));
    fireEvent.wheel(older.shown.frame);

    result.current.release();
    fireEvent.scroll(older.shown.frame);
    await new Promise(resolve => requestAnimationFrame(resolve));
    expect(showNewer).not.toHaveBeenCalled();
    await scrollByHand(older.shown.frame);

    expect(showNewer).toHaveBeenCalledOnce();
  });

  it("keeps following while the pointer is held down on the version scrolled, as on its scrollbar", async () => {
    const now = vi.spyOn(performance, "now").mockReturnValue(0);
    const older = createShown();
    older.drawText();
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: createShown().shown, showPoint: showNewer },
    }, ALIGNMENT));
    const scrollAt = async (time: number) => {
      now.mockReturnValue(time);
      fireEvent.scroll(older.shown.frame);
      await new Promise(resolve => requestAnimationFrame(resolve));
    };

    fireEvent.pointerDown(older.shown.frame);
    // The pointer stays down for longer than a scroll by hand counts for, then drags on.
    await scrollAt(2000);
    expect(showNewer).toHaveBeenCalledOnce();
    fireEvent.pointerUp(window);
    await scrollAt(5000);

    expect(showNewer).toHaveBeenCalledOnce();
  });

  it("ends the hold when the pointer moves with no button down, its release not told", async () => {
    const now = vi.spyOn(performance, "now").mockReturnValue(0);
    const older = createShown();
    older.drawText();
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: createShown().shown, showPoint: showNewer },
    }, ALIGNMENT));

    fireEvent.pointerDown(older.shown.frame);
    fireEvent.pointerMove(window, { buttons: 1 });
    fireEvent.pointerMove(window, { buttons: 0 });
    now.mockReturnValue(2000);
    fireEvent.scroll(older.shown.frame);
    await new Promise(resolve => requestAnimationFrame(resolve));

    expect(showNewer).not.toHaveBeenCalled();
  });

  it("stops following once the versions are no longer compared", async () => {
    const older = createShown();
    const showNewer = vi.fn();
    const compared: { alignment: Alignment | undefined } = { alignment: ALIGNMENT };
    const { rerender } = renderHook(({ alignment }) => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: createShown().shown, showPoint: showNewer },
    }, alignment), { initialProps: compared });

    await scrollByHand(older.shown.frame);
    rerender({ alignment: undefined });
    older.drawText();

    expect(showNewer).not.toHaveBeenCalled();
  });
});
