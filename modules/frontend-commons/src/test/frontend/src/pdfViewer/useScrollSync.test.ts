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
import { fireEvent, renderHook } from "@testing-library/react";

import type { Alignment } from "@iap/frontend-commons/pdfViewer/revisionDiffModel";
import type { ShownDocument } from "@iap/frontend-commons/pdfViewer/usePdfView";
import { useScrollSync } from "@iap/frontend-commons/pdfViewer/useScrollSync";

// The one word both versions share: on the older version's first page, and on the newer version's second.
const ALIGNMENT: Alignment = {
  older: [ { page: 1, start: 0, end: 3, text: "Fee" } ],
  newer: [ { page: 2, start: 4, end: 7, text: "Fee" } ],
  pairs: [ [ 0, 0 ] ],
};

// A frame with a first page, its text not drawn yet, and the bus PDF.js tells when it is drawn.
function createShown() {
  const frame = document.createElement("div");
  const page = document.createElement("div");
  page.dataset.pageNumber = "1";
  frame.append(page);
  document.body.append(frame);
  const listeners: (() => void)[] = [];
  const eventBus = {
    on: (_name: string, listener: () => void, { signal }: { signal: AbortSignal }) => {
      listeners.push(listener);
      signal.addEventListener("abort", () => listeners.splice(listeners.indexOf(listener), 1));
    },
  };
  const drawText = () => {
    const textLayer = document.createElement("div");
    textLayer.className = "textLayer";
    const span = document.createElement("span");
    span.textContent = "Fee";
    textLayer.append(span);
    page.append(textLayer);
    listeners.forEach(listener => listener());
  };
  return { shown: { frame, eventBus } as unknown as ShownDocument, drawText };
}

async function scrollByHand(frame: HTMLElement) {
  fireEvent.wheel(frame);
  fireEvent.scroll(frame);
  await new Promise(resolve => requestAnimationFrame(resolve));
}

describe("useScrollSync", () => {
  // Where the drawn text is, as jsdom cannot lay it out: from the top of the window.
  let textTop = 0;

  beforeEach(() => {
    textTop = 0;
    Range.prototype.getClientRects = () => [ new DOMRect(0, textTop, 10, 20) ] as unknown as DOMRectList;
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
    older.drawText();
    const newer = createShown();
    Object.defineProperty(newer.shown.frame, "clientHeight", { value: 100 });
    // The middle of the one scrolled, where jsdom puts every page, is 10 into a line
    textTop = -10;
    const showNewer = vi.fn();
    renderHook(() => useScrollSync({
      older: { shown: older.shown, showPoint: vi.fn() },
      newer: { shown: newer.shown, showPoint: showNewer },
    }, ALIGNMENT));

    await scrollByHand(older.shown.frame);

    expect(showNewer).toHaveBeenCalledExactlyOnceWith({ page: 2, char: 4 }, 40);
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
    // Held still for longer than a scroll by hand otherwise counts, then dragged on
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
