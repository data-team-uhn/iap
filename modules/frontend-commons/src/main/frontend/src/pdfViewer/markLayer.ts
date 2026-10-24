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
import type { MarkKind, MarkSpan, TextPoint } from "./marksModel";

export const MARK_CLASS = "iap-mark";

// Set on a mark that is a point between two characters, such as where a comparison found text removed.
export const POINT_CLASS = "iap-mark-point";
// Set as well on a point that starts the text its mark covers, such as text added in place of text removed.
export const START_CLASS = "iap-mark-start";

// Set on the frame while the pointer is over a mark.
export const OVER_MARK_CLASS = "iap-over-mark";
const LAYER_CLASS = "iap-marks";

// A mark to draw on one page, as the offsets it covers in the text of the page's text layer.
export interface PageMark {
  id: string;
  kind: MarkKind;
  start: number;
  end: number;
  // A point starting what follows it, drawn before the character after it.
  starts?: boolean;
}

interface TextNodeAt {
  node: Text;
  start: number;
}

// The text nodes of a text layer, each with where it starts in the layer's text, which is theirs joined:
// the page's text as PDF.js reads it.
function getTextNodes(textLayer: HTMLElement): TextNodeAt[] {
  const nodes: TextNodeAt[] = [];
  let offset = 0;
  const walker = document.createTreeWalker(textLayer, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const textNode = node as Text;
    nodes.push({ node: textNode, start: offset });
    offset += textNode.length;
  }
  return nodes;
}

// Where a point of a text layer falls in its text: in a text node, that far into it, or, between
// elements, where the next text node starts.
function getTextOffset(nodes: TextNodeAt[], length: number, container: Node, offset: number): number {
  const inNode = nodes.find(({ node }) => node === container);
  if (inNode !== undefined) {
    return inNode.start + offset;
  }
  const point = document.createRange();
  point.setStart(container, offset);
  return nodes.find(({ node }) => point.comparePoint(node, 0) > 0)?.start ?? length;
}

// The boxes of the text between two offsets. Each text node gets a range of its own, since a range
// across whole elements adds the elements' boxes too.
function getTextRects(nodes: TextNodeAt[], start: number, end: number): DOMRect[] {
  const range = document.createRange();
  return nodes.flatMap(({ node, start: at }) => {
    const from = Math.max(start, at);
    const to = Math.min(end, at + node.length);
    if (from >= to) {
      return [];
    }
    range.setStart(node, from - at);
    range.setEnd(node, to - at);
    return Array.from(range.getClientRects()).filter(rect => rect.width >= 1 && rect.height >= 1);
  });
}

// Joins boxes on the same line that are less than a line's height apart. A mark then runs on across
// the gaps PDF.js leaves between the pieces of text it lays out separately.
function joinLines(rects: DOMRect[]): DOMRect[] {
  const joined: DOMRect[] = [];
  for (const rect of rects) {
    const last = joined.at(-1);
    const sameLine = last !== undefined && Math.abs(rect.top - last.top) < last.height / 2;
    if (last !== undefined && sameLine && rect.left >= last.left && rect.left - last.right <= last.height) {
      const top = Math.min(last.top, rect.top);
      joined[joined.length - 1] = new DOMRect(
        last.left,
        top,
        Math.max(last.right, rect.right) - last.left,
        Math.max(last.bottom, rect.bottom) - top,
      );
    } else {
      joined.push(rect);
    }
  }
  return joined;
}

// Where a point is drawn: a box with no width, as high as its line. It sits beside the text it belongs to,
// at the left edge of the character after it when it starts that text, and at the right edge of the
// character before it otherwise. Without a character on that side, it sits at the other one.
function getPointRect(nodes: TextNodeAt[], offset: number, starts = false): DOMRect | undefined {
  const after = getTextRects(nodes, offset, offset + 1).at(0);
  const before = offset > 0 ? getTextRects(nodes, offset - 1, offset).at(-1) : undefined;
  if (after !== undefined && (starts || before === undefined)) {
    return new DOMRect(after.left, after.top, 0, after.height);
  }
  return before === undefined ? undefined : new DOMRect(before.right, before.top, 0, before.height);
}

const percent = (part: number, whole: number) => `${(100 * part / whole).toFixed(4)}%`;

// Draws the marks over a page's text layer, replacing any drawn before. They go inside the text
// layer, which PDF.js keeps when zooming, and are sized in percentages of it to scale with the page.
export function drawMarks(textLayer: HTMLElement, marks: PageMark[], selectedId: string | undefined): void {
  textLayer.querySelector(`:scope > .${LAYER_CLASS}`)?.remove();
  const nodes = getTextNodes(textLayer);
  if (nodes.length === 0 || marks.length === 0) {
    return;
  }
  const layer = document.createElement("div");
  layer.className = LAYER_CLASS;
  Object.assign(layer.style, { position: "absolute", inset: "0", pointerEvents: "none" });
  const box = textLayer.getBoundingClientRect();
  for (const { id, kind, start, end, starts } of marks) {
    const point = start === end;
    const rects = point
      ? [ getPointRect(nodes, start, starts === true) ].filter(rect => rect !== undefined)
      : getTextRects(nodes, start, end);
    for (const rect of joinLines(rects)) {
      const element = document.createElement("div");
      element.dataset.markId = id;
      element.classList.add(MARK_CLASS, `${MARK_CLASS}-${kind}`);
      element.classList.toggle(POINT_CLASS, point);
      element.classList.toggle(START_CLASS, point && marks.some(other => other.id === id && other.end > other.start));
      element.classList.toggle("selected", id === selectedId);
      element.style.position = "absolute";
      element.style.left = percent(rect.left - box.left, box.width);
      element.style.top = percent(rect.top - box.top, box.height);
      element.style.width = percent(rect.width, box.width);
      element.style.height = percent(rect.height, box.height);
      layer.append(element);
    }
  }
  textLayer.append(layer);
}

// The text a range selects on the pages drawn in the frame, as one span per page, in order, without the
// spaces at either end. None when it selects no text.
export function getSelectedSpans(frame: HTMLElement, range: Range): MarkSpan[] {
  const selected = Array.from(frame.querySelectorAll<HTMLElement>("[data-page-number]")).flatMap(pageView => {
    const textLayer = pageView.querySelector<HTMLElement>(".textLayer");
    if (textLayer === null || !range.intersectsNode(textLayer)) {
      return [];
    }
    const page = Number(pageView.dataset.pageNumber);
    const nodes = getTextNodes(textLayer);
    const layerText = nodes.map(({ node }) => node.data).join("");
    const whole = document.createRange();
    whole.selectNodeContents(textLayer);
    const start = range.compareBoundaryPoints(Range.START_TO_START, whole) > 0
      ? getTextOffset(nodes, layerText.length, range.startContainer, range.startOffset)
      : 0;
    const end = range.compareBoundaryPoints(Range.END_TO_END, whole) < 0
      ? getTextOffset(nodes, layerText.length, range.endContainer, range.endOffset)
      : layerText.length;
    const text = layerText.slice(start, end);
    return text.trim() === "" ? [] : [ { page, start, end, text } ];
  });
  const first = selected.at(0);
  const last = selected.at(-1);
  if (first !== undefined && last !== undefined) {
    first.start += first.text.length - first.text.trimStart().length;
    last.end -= last.text.length - last.text.trimEnd().length;
  }
  return selected.map(({ page, start, end }) => ({ page, start, end }));
}

// The box of the last piece of text the spans cover in the frame. None when they cover no text, or their
// last page is not drawn.
export function getTextEnd(frame: HTMLElement, spans: MarkSpan[]): DOMRect | undefined {
  const last = spans.at(-1);
  if (last === undefined) {
    return undefined;
  }
  const textLayer = frame.querySelector<HTMLElement>(`[data-page-number="${last.page}"] .textLayer`);
  return textLayer === null ? undefined : getTextRects(getTextNodes(textLayer), last.start, last.end).at(-1);
}

// The place in the frame's text at a height in the window. It is the start of the first piece of text
// reaching down to that height, on the first page reaching down to it. None while that page's text is not
// drawn.
export function getTextPointAt(frame: HTMLElement, y: number): TextPoint | undefined {
  const pageView = Array.from(frame.querySelectorAll<HTMLElement>("[data-page-number]"))
    .find(view => view.getBoundingClientRect().bottom >= y);
  const textLayer = pageView?.querySelector<HTMLElement>(".textLayer") ?? null;
  if (pageView === undefined || textLayer === null) {
    return undefined;
  }
  const nodes = getTextNodes(textLayer);
  const at = nodes.find(({ node }) => (node.parentElement?.getBoundingClientRect().bottom ?? y) >= y) ?? nodes.at(-1);
  return at === undefined ? undefined : { page: Number(pageView.dataset.pageNumber), char: at.start };
}

// Where a point of a page's text is drawn in the frame. It is the box of the character at it, or of the
// point itself at the end of the text. None while its page's text is not drawn.
export function getTextPointRect(frame: HTMLElement, { page, char }: TextPoint): DOMRect | undefined {
  const textLayer = frame.querySelector<HTMLElement>(`[data-page-number="${page}"] .textLayer`);
  if (textLayer === null) {
    return undefined;
  }
  const nodes = getTextNodes(textLayer);
  return getTextRects(nodes, char, char + 1).at(0) ?? getPointRect(nodes, char);
}

// The first piece of a mark drawn in the frame, if its page has been drawn.
export function findMark(frame: HTMLElement, id: string): HTMLElement | undefined {
  return Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).find(mark => mark.dataset.markId === id);
}

// A point is drawn as a box centred on it, POINT_SIZE_RATIO of the line's height high, but no lower than
// POINT_MIN_SIZE, and POINT_WIDTH_RATIO as wide as it is high.
export const POINT_SIZE_RATIO = 1.2;
export const POINT_MIN_SIZE = 22;
export const POINT_WIDTH_RATIO = 0.6;

// The least size of a point's target for the pointer, however small it is drawn. On a touch screen it is
// a fingertip.
const POINT_HIT_SIZE = { touch: 40, mouse: 16 };

function getHitBox(mark: HTMLElement, pointSize: number): DOMRect {
  const box = mark.getBoundingClientRect();
  if (!mark.classList.contains(POINT_CLASS)) {
    return box;
  }
  const drawn = Math.max(box.height * POINT_SIZE_RATIO, POINT_MIN_SIZE);
  const [ width, height ] = [ Math.max(drawn * POINT_WIDTH_RATIO, pointSize), Math.max(drawn, pointSize) ];
  return new DOMRect(box.left + (box.width - width) / 2, box.top + (box.height - height) / 2, width, height);
}

// The mark drawn at a point in the frame, if any. Marks let the pointer through, so text can be
// selected across them, and a click is matched to a mark here by where it falls, or, for a point, within
// a fingertip of it on a touch screen. A mark the click falls on comes before a point it is only beside.
// Where marks overlap, one not selected comes first, so clicking again reaches the other.
export function markAt(frame: HTMLElement, x: number, y: number, touch = false): string | undefined {
  const pointSize = touch ? POINT_HIT_SIZE.touch : POINT_HIT_SIZE.mouse;
  const hits = Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).flatMap(mark => {
    const { left, right, top, bottom } = getHitBox(mark, pointSize);
    return x >= left && x <= right && y >= top && y <= bottom ? [ { mark, away: getDistance(mark, x, y) } ] : [];
  });
  const nearest = Math.min(...hits.map(hit => hit.away));
  const closest = hits.filter(hit => hit.away === nearest).map(hit => hit.mark);
  return (closest.find(mark => !mark.classList.contains("selected")) ?? closest.at(0))?.dataset.markId;
}

// How far a place is from where a mark is drawn, none when on it.
function getDistance(mark: HTMLElement, x: number, y: number): number {
  const { left, right, top, bottom } = getHitBox(mark, 0);
  return Math.hypot(Math.max(left - x, 0, x - right), Math.max(top - y, 0, y - bottom));
}

// Scrolls the container, and only it, as little as brings the element into view.
export function keepInView(container: HTMLElement, element: HTMLElement): void {
  const outer = container.getBoundingClientRect();
  const inner = element.getBoundingClientRect();
  if (inner.top < outer.top) {
    container.scrollTop -= outer.top - inner.top;
  } else if (inner.bottom > outer.bottom) {
    container.scrollTop += Math.min(inner.bottom - outer.bottom, inner.top - outer.top);
  }
}

// Scrolls the container, and only it, so the element sits in the middle of it.
export function centreIn(container: HTMLElement, element: HTMLElement): void {
  const outer = container.getBoundingClientRect();
  const inner = element.getBoundingClientRect();
  container.scrollTop += inner.top - outer.top - Math.max(0, (container.clientHeight - inner.height) / 2);
  container.scrollLeft += inner.left - outer.left - Math.max(0, (container.clientWidth - inner.width) / 2);
}
