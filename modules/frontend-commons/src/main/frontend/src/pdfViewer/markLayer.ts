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

// Set on the frame while the pointer is over a mark.
export const OVER_MARK_CLASS = "iap-over-mark";
const LAYER_CLASS = "iap-marks";

// A mark to draw on one page, as the offsets it covers in the text of the page's text layer.
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
  for (const { id, kind, start, end } of marks) {
    for (const rect of joinLines(getTextRects(nodes, start, end))) {
      const element = document.createElement("div");
      element.dataset.markId = id;
      element.classList.add(MARK_CLASS, `${MARK_CLASS}-${kind}`);
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

// The first piece of a mark drawn in the frame, if its page has been drawn.
export function findMark(frame: HTMLElement, id: string): HTMLElement | undefined {
  return Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).find(mark => mark.dataset.markId === id);
}

// The mark drawn at a point in the frame, if any. Marks let the pointer through, so text can be
// selected across them, and a click is matched to a mark here by where it falls. Where marks overlap,
// one not selected comes first, so clicking again reaches the other.
export function markAt(frame: HTMLElement, x: number, y: number): string | undefined {
  const hits = Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).filter(mark => {
    const { left, right, top, bottom } = mark.getBoundingClientRect();
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
