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
import type { MarkKind, MarkSpan } from "./marksModel";

export const MARK_CLASS = "iap-mark";

// Set on a mark that is a point between two characters, such as where a comparison found text removed.
export const POINT_CLASS = "iap-mark-point";

// Set on the frame while the pointer is over a mark.
export const OVER_MARK_CLASS = "iap-over-mark";
const LAYER_CLASS = "iap-marks";

// A mark to draw on one page, over the offsets it covers in the text of the page's text layer.
export interface PageMark {
  id: string;
  kind: MarkKind;
  start: number;
  end: number;
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

// The boxes of the text between two offsets, without those of the elements holding it, which a range
// across whole elements would add.
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

// Boxes running on along a line, less than its height apart, as one, so a mark carries on across the gaps
// PDF.js leaves between the pieces of text it lays out separately.
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

// Where a point between two characters is drawn: at the end of the character before it, or at the start of
// the text when it has none, as a box with no width, the line's height.
function getPointRect(nodes: TextNodeAt[], offset: number): DOMRect | undefined {
  const before = offset > 0 ? getTextRects(nodes, offset - 1, offset).at(-1) : undefined;
  if (before !== undefined) {
    return new DOMRect(before.right, before.top, 0, before.height);
  }
  const after = getTextRects(nodes, offset, offset + 1).at(0);
  return after === undefined ? undefined : new DOMRect(after.left, after.top, 0, after.height);
}

const percent = (part: number, whole: number) => `${(100 * part / whole).toFixed(4)}%`;

// Draws the marks over a page's text layer, inside it, since PDF.js keeps it when zooming, and in
// percentages of it, so they scale with the page. Drawing again replaces what was drawn.
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
  for (const { id, kind, start, end } of marks) {
    const point = start === end;
    const rects = point
      ? [ getPointRect(nodes, start) ].filter(rect => rect !== undefined)
      : getTextRects(nodes, start, end);
    for (const rect of joinLines(rects)) {
      const element = document.createElement("div");
      element.dataset.markId = id;
      element.classList.add(MARK_CLASS, `${MARK_CLASS}-${kind}`);
      element.classList.toggle(POINT_CLASS, point);
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

// What a range selects of the text of the pages drawn in the frame, as one span on each page whose text
// it selects some of, in order, without the spaces at either end. None when it selects no text. Pages not
// drawn yet have no text to select.
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

// The box of the end of the text spans cover in the frame, from the text alone, as marks are drawn, rather
// than from the boxes a range gives of the elements it holds whole: none when they cover no text, or not
// on a page drawn.
export function getTextEnd(frame: HTMLElement, spans: MarkSpan[]): DOMRect | undefined {
  const last = spans.at(-1);
  if (last === undefined) {
    return undefined;
  }
  const textLayer = frame.querySelector<HTMLElement>(`[data-page-number="${last.page}"] .textLayer`);
  return textLayer === null ? undefined : getTextRects(getTextNodes(textLayer), last.start, last.end).at(-1);
}

// The first piece of a mark drawn in the frame, if its page has been drawn, or its first point.
export function findMark(frame: HTMLElement, id: string, { point = false } = {}): HTMLElement | undefined {
  return Array.from(frame.querySelectorAll<HTMLElement>(`.${point ? POINT_CLASS : MARK_CLASS}`))
    .find(mark => mark.dataset.markId === id);
}

// How wide and high a point is to the pointer, however thin it is drawn: a fingertip on a touch screen.
const POINT_HIT_SIZE = { touch: 40, mouse: 16 };

function getHitBox(mark: HTMLElement): DOMRect {
  const box = mark.getBoundingClientRect();
  if (!mark.classList.contains(POINT_CLASS)) {
    return box;
  }
  const touch = typeof window.matchMedia === "function" && window.matchMedia("(pointer: coarse)").matches;
  const size = touch ? POINT_HIT_SIZE.touch : POINT_HIT_SIZE.mouse;
  const width = Math.max(box.width, size);
  const height = Math.max(box.height, size);
  return new DOMRect(box.left + (box.width - width) / 2, box.top + (box.height - height) / 2, width, height);
}

// The mark drawn at a point in the frame, if any. Marks let the pointer through to the text, so text
// can be selected across them, and a click is matched to them by where it falls. Where marks overlap,
// one not selected comes first, so clicking again reaches the other.
export function markAt(frame: HTMLElement, x: number, y: number): string | undefined {
  const hits = Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).filter(mark => {
    const { left, right, top, bottom } = getHitBox(mark);
    return x >= left && x <= right && y >= top && y <= bottom;
  });
  return (hits.find(mark => !mark.classList.contains("selected")) ?? hits.at(0))?.dataset.markId;
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
