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
import {
  centreIn,
  drawMarks,
  findMark,
  getSelectedSpans,
  getTextEnd,
  getTextPointAt,
  getTextPointRect,
  keepInView,
  markAt,
} from "@iap/frontend-commons/pdfViewer/markLayer";

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

  it("draws a point as a marker the line's height, after the character before it, or before the one it starts", () => {
    const textLayer = createTextLayer();

    // After "Volunteers", and before the first character of the second line, which it starts
    drawMarks(textLayer, [
      { id: "a", kind: "removed", start: 10, end: 10 },
      { id: "b", kind: "added", start: 34, end: 34, starts: true },
    ], "a");

    expect(drawn(textLayer)).toEqual([
      {
        id: "a",
        className: "iap-mark iap-mark-removed iap-mark-point selected",
        top: "0%",
        left: "12.5%",
        width: "0%",
        height: "2%",
      },
      { id: "b", className: "iap-mark iap-mark-added iap-mark-point", top: "10%", left: "0%", width: "0%", height: "2%" },
    ]);
  });

  it("marks a point starting the text its mark covers", () => {
    const textLayer = createTextLayer();

    drawMarks(textLayer, [
      { id: "a", kind: "added", start: 34, end: 34, starts: true },
      { id: "a", kind: "added", start: 34, end: 40 },
      { id: "b", kind: "added", start: 10, end: 10 },
    ], undefined);

    expect(drawn(textLayer).map(({ id, className }) => [ id, className ])).toEqual([
      [ "a", "iap-mark iap-mark-added iap-mark-point iap-mark-start" ],
      [ "a", "iap-mark iap-mark-added" ],
      [ "b", "iap-mark iap-mark-added iap-mark-point" ],
    ]);
  });

  it("draws a point at the start of the text before the first character", () => {
    const textLayer = createTextLayer();

    drawMarks(textLayer, [ { id: "a", kind: "removed", start: 0, end: 0 } ], undefined);

    expect(drawn(textLayer).map(({ left, top }) => [ left, top ])).toEqual([ [ "0%", "0%" ] ]);
  });

  it("draws nothing on a page without text", () => {
    const textLayer = createTextLayer([]);

    drawMarks(textLayer, [ { id: "a", kind: "assistant", start: 0, end: 10 } ], undefined);

    expect(textLayer).toBeEmptyDOMElement();
  });
});

describe("getSelectedSpans", () => {
  // Pages as PDF.js draws them in its viewer. Each is a numbered element holding its text layer, which
  // holds a span per piece of text and a break at each line end. While text is selected, the layer also
  // holds an element with no text.
  function createPages(...pages: string[][]): HTMLDivElement {
    const frame = document.createElement("div");
    pages.forEach((lines, index) => {
      const page = document.createElement("div");
      page.dataset.pageNumber = String(index + 1);
      const canvas = document.createElement("canvas");
      const textLayer = document.createElement("div");
      textLayer.className = "textLayer";
      for (const line of lines) {
        const span = document.createElement("span");
        span.textContent = line;
        textLayer.append(span, document.createElement("br"));
      }
      const endOfContent = document.createElement("div");
      endOfContent.className = "endOfContent";
      textLayer.append(endOfContent);
      page.append(canvas, textLayer);
      frame.append(page);
    });
    document.body.append(frame);
    return frame;
  }

  // The text node of the nth span in the frame.
  function textAt(frame: HTMLElement, index: number) {
    return frame.querySelectorAll("span")[index].firstChild as Text;
  }

  function select(from: [ Node, number ], to: [ Node, number ]): Range {
    const range = document.createRange();
    range.setStart(...from);
    range.setEnd(...to);
    return range;
  }

  afterEach(() => {
    document.body.replaceChildren();
  });

  it("selects offsets into the page's text, which runs on from one line to the next", () => {
    const frame = createPages(LINES);

    const spans = getSelectedSpans(frame, select([ textAt(frame, 0), 16 ], [ textAt(frame, 1), 6 ]));

    expect(spans).toEqual([ { page: 1, start: 16, end: 40 } ]);
    expect(LINES.join("").slice(16, 40)).toBe("on a casual basis,assist");
  });

  it("selects a span on each page a selection runs over, in order", () => {
    const frame = createPages([ "Cover page." ], LINES, [ "The end." ]);

    const spans = getSelectedSpans(frame, select([ textAt(frame, 0), 6 ], [ textAt(frame, 3), 3 ]));

    expect(spans).toEqual([
      { page: 1, start: 6, end: 11 },
      { page: 2, start: 0, end: LINES.join("").length },
      { page: 3, start: 0, end: 3 },
    ]);
  });

  it("selects from the start of the text of a selection starting outside it, and to the end of one ending outside", () => {
    const frame = createPages(LINES);
    const page = frame.firstElementChild as HTMLElement;
    const textLayer = page.querySelector<HTMLElement>(".textLayer")!;

    expect(getSelectedSpans(frame, select([ page, 0 ], [ textAt(frame, 0), 10 ])))
      .toEqual([ { page: 1, start: 0, end: 10 } ]);
    expect(getSelectedSpans(frame, select([ textAt(frame, 1), 7 ], [ textLayer, textLayer.childNodes.length ])))
      .toEqual([ { page: 1, start: 41, end: LINES.join("").length } ]);
    expect(getSelectedSpans(frame, select([ textAt(frame, 1), 7 ], [ frame, 1 ])))
      .toEqual([ { page: 1, start: 41, end: LINES.join("").length } ]);
  });

  it("selects from where the text goes on, at a point between the layer's elements", () => {
    const frame = createPages(LINES);
    const textLayer = frame.querySelector<HTMLElement>(".textLayer")!;
    const endOfContent = textLayer.querySelector(".endOfContent")!;

    expect(getSelectedSpans(frame, select([ textLayer, 2 ], [ textLayer, 3 ])))
      .toEqual([ { page: 1, start: LINES[0].length, end: LINES.join("").length } ]);
    expect(getSelectedSpans(frame, select([ textAt(frame, 1), 7 ], [ endOfContent, 0 ])))
      .toEqual([ { page: 1, start: 41, end: LINES.join("").length } ]);
  });

  it("leaves out pages not drawn yet", () => {
    const frame = createPages([ "Cover page." ], LINES);
    frame.querySelector('[data-page-number="1"] .textLayer')!.remove();

    expect(getSelectedSpans(frame, select([ frame, 0 ], [ textAt(frame, 0), 10 ])))
      .toEqual([ { page: 2, start: 0, end: 10 } ]);
  });

  it("leaves out the spaces at either end of the selection", () => {
    const frame = createPages([ "  The memo  ", "  covers it.  " ]);

    expect(getSelectedSpans(frame, select([ textAt(frame, 0), 0 ], [ textAt(frame, 1), 14 ])))
      .toEqual([ { page: 1, start: 2, end: 24 } ]);
  });

  it("leaves out pages it selects no text of", () => {
    const frame = createPages([ "Cover page." ], [ "   " ], [ "The end." ]);

    expect(getSelectedSpans(frame, select([ textAt(frame, 0), 11 ], [ textAt(frame, 2), 3 ])))
      .toEqual([ { page: 3, start: 0, end: 3 } ]);
    expect(getSelectedSpans(frame, select([ textAt(frame, 0), 11 ], [ textAt(frame, 2), 0 ]))).toEqual([]);
  });

  it("leaves out text outside the pages' text layers", () => {
    const frame = createPages(LINES);
    const caption = document.createElement("p");
    caption.textContent = "Not the document's";
    frame.prepend(caption);

    expect(getSelectedSpans(frame, select([ caption.firstChild as Text, 0 ], [ textAt(frame, 0), 10 ])))
      .toEqual([ { page: 1, start: 0, end: 10 } ]);
  });
});

describe("getTextEnd", () => {
  beforeEach(() => {
    Range.prototype.getClientRects = function (this: Range) {
      return rectsOf(this) as unknown as DOMRectList;
    };
  });

  afterEach(() => {
    document.body.replaceChildren();
    delete (Range.prototype as Partial<Range>).getClientRects;
  });

  // A frame holding one page, drawn, whose text layer is the one createTextLayer lays out.
  function createFrame(): HTMLDivElement {
    const frame = document.createElement("div");
    const page = document.createElement("div");
    page.dataset.pageNumber = "2";
    const textLayer = createTextLayer();
    textLayer.className = "textLayer";
    page.append(textLayer);
    frame.append(page);
    document.body.append(frame);
    return frame;
  }

  it("is the box of the last piece of text the spans cover, not of the elements holding it", () => {
    const frame = createFrame();

    expect(getTextEnd(frame, [ { page: 2, start: 5, end: 34 + 6 } ])).toEqual(new DOMRect(0, 100, 60, 20));
  });

  it("is none for spans covering nothing, or on a page not drawn", () => {
    const frame = createFrame();

    expect(getTextEnd(frame, [])).toBeUndefined();
    expect(getTextEnd(frame, [ { page: 3, start: 0, end: 5 } ])).toBeUndefined();
  });
});

describe("getTextPointAt and getTextPointRect", () => {
  beforeEach(() => {
    Range.prototype.getClientRects = function (this: Range) {
      return rectsOf(this) as unknown as DOMRectList;
    };
    // Each line's span where createTextLayer lays it out
    vi.spyOn(HTMLSpanElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      return new DOMRect(0, Number(this.dataset.line) * 100, 400, 20);
    });
  });

  afterEach(() => {
    document.body.replaceChildren();
    delete (Range.prototype as Partial<Range>).getClientRects;
    vi.restoreAllMocks();
  });

  // A frame holding a first page, its text drawn as createTextLayer lays it out, 1000 high, and a second page
  // under it, its text not drawn yet.
  function createFrame(): HTMLDivElement {
    const frame = document.createElement("div");
    const textLayer = createTextLayer();
    textLayer.className = "textLayer";
    for (const [ index, top ] of [ 0, 1020 ].entries()) {
      const page = document.createElement("div");
      page.dataset.pageNumber = String(index + 1);
      page.getBoundingClientRect = () => new DOMRect(0, top, 800, 1000);
      page.append(...index === 0 ? [ textLayer ] : []);
      frame.append(page);
    }
    document.body.append(frame);
    return frame;
  }

  it("finds the start of the first piece of text reaching down to a height", () => {
    const frame = createFrame();

    expect(getTextPointAt(frame, 10)).toEqual({ page: 1, char: 0 });
    expect(getTextPointAt(frame, 50)).toEqual({ page: 1, char: 34 });
  });

  it("finds the last piece of text of a page reaching down to a height below its text", () => {
    expect(getTextPointAt(createFrame(), 500)).toEqual({ page: 1, char: 34 });
  });

  it("finds nothing on a page whose text is not drawn, or below every page", () => {
    const frame = createFrame();

    expect(getTextPointAt(frame, 1500)).toBeUndefined();
    expect(getTextPointAt(frame, 3000)).toBeUndefined();
  });

  it("gives the box of the character at a point, or of the point at the end of the text", () => {
    const frame = createFrame();

    expect(getTextPointRect(frame, { page: 1, char: 10 })).toEqual(new DOMRect(100, 0, 10, 20));
    expect(getTextPointRect(frame, { page: 1, char: LINES.join("").length })).toEqual(new DOMRect(250, 100, 0, 20));
    expect(getTextPointRect(frame, { page: 2, char: 0 })).toBeUndefined();
  });
});

describe("findMark", () => {
  it("finds the first piece of a mark drawn", () => {
    const frame = document.createElement("div");
    frame.innerHTML = '<div class="iap-mark" data-mark-id="a"></div>'
      + '<div class="iap-mark" data-mark-id="b&quot;"></div>';

    expect(findMark(frame, "b\"")).toBe(frame.lastElementChild);
    expect(findMark(frame, "c")).toBeUndefined();
  });
});

describe("markAt", () => {
  it("finds the mark drawn at a point, if any", () => {
    const frame = document.createElement("div");
    frame.innerHTML = '<div class="iap-mark" data-mark-id="a"></div><div class="iap-mark" data-mark-id="b"></div>';
    const [ a, b ] = Array.from(frame.children) as HTMLElement[];
    a.getBoundingClientRect = () => new DOMRect(0, 0, 100, 20);
    b.getBoundingClientRect = () => new DOMRect(0, 100, 100, 20);

    expect(markAt(frame, 50, 110)).toBe("b");
    expect(markAt(frame, 50, 50)).toBeUndefined();
  });

  it("finds a mark not selected first where marks overlap, so clicking again reaches the other", () => {
    const frame = document.createElement("div");
    frame.innerHTML = '<div class="iap-mark selected" data-mark-id="a"></div><div class="iap-mark" data-mark-id="b"></div>';
    for (const mark of Array.from(frame.children)) {
      mark.getBoundingClientRect = () => new DOMRect(0, 0, 100, 20);
    }

    expect(markAt(frame, 50, 10)).toBe("b");
    frame.children[0].classList.remove("selected");
    frame.children[1].classList.add("selected");
    expect(markAt(frame, 50, 10)).toBe("a");
  });
});

describe("markAt on a point", () => {
  // A point drawn 100 along a line of the height given, as thin as a point is
  function createPoint(height = 20): HTMLElement {
    const frame = document.createElement("div");
    frame.innerHTML = '<div class="iap-mark iap-mark-point" data-mark-id="p"></div>';
    (frame.firstElementChild as HTMLElement).getBoundingClientRect = () => new DOMRect(100, 0, 0, height);
    return frame;
  }

  it("finds a point within a few pixels of it, with a mouse", () => {
    const frame = createPoint();

    expect(markAt(frame, 107, 10)).toBe("p");
    expect(markAt(frame, 109, 10)).toBeUndefined();
  });

  it("finds a point within a fingertip of it, tapped", () => {
    const frame = createPoint();

    expect(markAt(frame, 118, 28, true)).toBe("p");
    expect(markAt(frame, 122, 10, true)).toBeUndefined();
  });

  it("finds a mark tapped on before a point it is only beside, selected or not", () => {
    const frame = createPoint();
    frame.insertAdjacentHTML("beforeend", '<div class="iap-mark selected" data-mark-id="word"></div>');
    (frame.lastElementChild as HTMLElement).getBoundingClientRect = () => new DOMRect(102, 0, 30, 20);

    expect(markAt(frame, 110, 10, true)).toBe("word");
    expect(markAt(frame, 100, 10, true)).toBe("p");
  });

  it("finds a point on its box inside another mark, the one not selected first", () => {
    const frame = createPoint();
    frame.insertAdjacentHTML("beforeend", '<div class="iap-mark" data-mark-id="comment"></div>');
    const comment = frame.lastElementChild as HTMLElement;
    comment.getBoundingClientRect = () => new DOMRect(60, 0, 80, 20);

    comment.classList.add("selected");
    expect(markAt(frame, 103, 10)).toBe("p");
    comment.classList.remove("selected");
    frame.firstElementChild?.classList.add("selected");
    expect(markAt(frame, 103, 10)).toBe("comment");
  });

  it("finds a point anywhere on its box, drawn larger than a fingertip on a tall line", () => {
    const frame = createPoint(100);

    expect(markAt(frame, 135, 105)).toBe("p");
    expect(markAt(frame, 137, 50)).toBeUndefined();
  });
});

describe("keepInView", () => {
  // A container 300 high, 100 down the page, with an element at a given place on the page.
  function scrolledFor(top: number, height = 50): number {
    const container = document.createElement("div");
    const element = document.createElement("div");
    container.getBoundingClientRect = () => new DOMRect(0, 100, 400, 300);
    element.getBoundingClientRect = () => new DOMRect(0, top, 400, height);
    container.scrollTop = 1000;
    keepInView(container, element);
    return container.scrollTop - 1000;
  }

  it("scrolls as little as brings the element into view", () => {
    expect(scrolledFor(40)).toBe(-60);
    expect(scrolledFor(380)).toBe(30);
  });

  it("brings the top of an element taller than the container into view", () => {
    expect(scrolledFor(450, 600)).toBe(350);
  });

  it("leaves an element already in view where it is", () => {
    expect(scrolledFor(200)).toBe(0);
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
