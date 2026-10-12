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
import { centreIn, drawMarks, findMark } from "@iap/frontend-commons/pdfViewer/markLayer";

const LINES = [ "Volunteers who, on a casual basis,", "assist from time to time." ];

// A text layer as PDF.js draws one, laid out the way jsdom cannot: 800 by 1000, each line 20 high and
// 100 below the last, and each character 10 wide. A line is one span, or several, each given with
// where it starts along the line.
function createTextLayer(lines: (string | [ string, number ][])[] = LINES): HTMLDivElement {
  const textLayer = document.createElement("div");
  lines.forEach((line, index) => {
    for (const [ text, x ] of typeof line === "string" ? [ [ line, 0 ] as const ] : line) {
      const span = document.createElement("span");
      span.textContent = text;
      span.dataset.line = String(index);
      span.dataset.x = String(x);
      textLayer.append(span);
    }
  });
  textLayer.getBoundingClientRect = () => new DOMRect(0, 0, 800, 1000);
  document.body.append(textLayer);
  return textLayer;
}

// The box of the part of a span's text a range covers, and, as a browser gives too, the box of every
// span the range holds whole.
function rectsOf(range: Range): DOMRect[] {
  const spans = Array.from(document.querySelectorAll("span"));
  return spans.flatMap(span => {
    const text = span.firstChild as Text;
    const top = Number(span.dataset.line) * 100;
    const x = Number(span.dataset.x);
    const whole = range.startContainer !== text && range.endContainer !== text && range.intersectsNode(span)
      ? [ new DOMRect(x, top - 2, text.length * 10, 24) ]
      : [];
    if (!range.intersectsNode(text)) {
      return whole;
    }
    const start = range.startContainer === text ? range.startOffset : 0;
    const end = range.endContainer === text ? range.endOffset : text.length;
    return [ ...whole, new DOMRect(x + start * 10, top, (end - start) * 10, end > start ? 20 : 0) ];
  });
}

function drawn(textLayer: HTMLElement) {
  return Array.from(textLayer.querySelectorAll<HTMLElement>("[data-mark-id]")).map(mark => ({
    id: mark.dataset.markId,
    className: mark.className,
    top: mark.style.top,
    left: mark.style.left,
    width: mark.style.width,
    height: mark.style.height,
  }));
}

describe("drawMarks", () => {
  beforeEach(() => {
    Range.prototype.getClientRects = function (this: Range) {
      return rectsOf(this) as unknown as DOMRectList;
    };
  });

  afterEach(() => {
    document.body.replaceChildren();
    delete (Range.prototype as Partial<Range>).getClientRects;
  });

  it("draws its marks on a layer covering the page, letting the pointer through to the text", () => {
    const textLayer = createTextLayer();

    drawMarks(textLayer, [ { id: "m", kind: "assistant", start: 34, end: 50 } ], undefined);

    const layer = textLayer.lastElementChild as HTMLElement;
    const { position, inset, pointerEvents } = layer.style;
    expect({ position, inset, pointerEvents }).toEqual({ position: "absolute", inset: "0px", pointerEvents: "none" });
    expect((layer.firstElementChild as HTMLElement).style.position).toBe("absolute");
  });

  it("draws a piece of the mark over each line of the text it covers, in parts of the page", () => {
    const textLayer = createTextLayer();

    // From "on a casual basis," to "assist from time"
    drawMarks(textLayer, [ { id: "m", kind: "annotation", start: 16, end: 50 } ], undefined);

    expect(drawn(textLayer)).toEqual([
      { id: "m", className: "iap-mark iap-mark-annotation", top: "0%", left: "20%", width: "22.5%", height: "2%" },
      { id: "m", className: "iap-mark iap-mark-annotation", top: "10%", left: "0%", width: "20%", height: "2%" },
    ]);
  });

  it("runs a mark on across the gaps between the pieces of a line, but not across a wider one", () => {
    const textLayer = createTextLayer([ [ [ "Volunteers who,", 0 ], [ "on a casual", 170 ], [ "basis,", 600 ] ] ]);

    drawMarks(textLayer, [ { id: "m", kind: "assistant", start: 0, end: 32 } ], undefined);

    expect(drawn(textLayer).map(({ left, width }) => [ left, width ])).toEqual([ [ "0%", "35%" ], [ "75%", "7.5%" ] ]);
  });

  it("ends a mark at the end of a line without running onto the next", () => {
    const textLayer = createTextLayer();

    drawMarks(textLayer, [ { id: "m", kind: "assistant", start: 0, end: 34 } ], undefined);

    expect(drawn(textLayer).map(({ top, width }) => [ top, width ])).toEqual([ [ "0%", "42.5%" ] ]);
  });

  it("shows which mark is selected", () => {
    const textLayer = createTextLayer();

    drawMarks(textLayer, [
      { id: "a", kind: "assistant", start: 34, end: 59 },
      { id: "b", kind: "annotation", start: 0, end: 10 },
    ], "a");

    expect(drawn(textLayer).map(({ id, className }) => [ id, className ])).toEqual([
      [ "a", "iap-mark iap-mark-assistant selected" ],
      [ "b", "iap-mark iap-mark-annotation" ],
    ]);
  });

  it("replaces what it drew before", () => {
    const textLayer = createTextLayer();
    drawMarks(textLayer, [ { id: "a", kind: "assistant", start: 34, end: 59 } ], undefined);

    drawMarks(textLayer, [], undefined);

    expect(drawn(textLayer)).toEqual([]);
  });

  it("draws nothing on a page without text", () => {
    const textLayer = createTextLayer([]);

    drawMarks(textLayer, [ { id: "a", kind: "assistant", start: 0, end: 10 } ], undefined);

    expect(textLayer).toBeEmptyDOMElement();
  });
});

describe("findMark", () => {
  it("finds the first piece of a mark drawn", () => {
    const frame = document.createElement("div");
    frame.innerHTML = '<div class="iap-mark" data-mark-id="a"></div><div class="iap-mark" data-mark-id="b&quot;"></div>';

    expect(findMark(frame, "b\"")).toBe(frame.lastElementChild);
    expect(findMark(frame, "c")).toBeUndefined();
  });
});

describe("centreIn", () => {
  it("scrolls the container so the element sits in its middle", () => {
    const container = document.createElement("div");
    const element = document.createElement("div");
    container.getBoundingClientRect = () => new DOMRect(0, 100, 400, 300);
    Object.defineProperty(container, "clientHeight", { value: 300 });
    Object.defineProperty(container, "clientWidth", { value: 400 });
    element.getBoundingClientRect = () => new DOMRect(600, 900, 100, 20);

    centreIn(container, element);

    expect(container.scrollTop).toBe(660);
    expect(container.scrollLeft).toBe(450);
  });
});
