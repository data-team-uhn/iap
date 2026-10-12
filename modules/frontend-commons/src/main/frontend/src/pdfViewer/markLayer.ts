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
import type { MarkKind } from "./marksModel";

export const MARK_CLASS = "iap-mark";
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

const percent = (part: number, whole: number) => `${(100 * part / whole).toFixed(4)}%`;

// Draws the marks over a page's text layer, inside it, since PDF.js keeps it when zooming, and in
// percentages of it, so they scale with the page. Drawing again replaces what was drawn.
export function drawMarks(textLayer: HTMLElement, marks: PageMark[], selectedId: string | undefined): void {
  textLayer.querySelector(`:scope > .${LAYER_CLASS}`)?.remove();
  const nodes: TextNodeAt[] = [];
  let offset = 0;
  const walker = document.createTreeWalker(textLayer, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    const textNode = node as Text;
    nodes.push({ node: textNode, start: offset });
    offset += textNode.length;
  }
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

// The first piece of a mark drawn in the frame, if its page has been drawn.
export function findMark(frame: HTMLElement, id: string): HTMLElement | undefined {
  return Array.from(frame.querySelectorAll<HTMLElement>(`.${MARK_CLASS}`)).find(mark => mark.dataset.markId === id);
}

// Scrolls the container, and only it, so the element sits in the middle of it.
export function centreIn(container: HTMLElement, element: HTMLElement): void {
  const outer = container.getBoundingClientRect();
  const inner = element.getBoundingClientRect();
  container.scrollTop += inner.top - outer.top - Math.max(0, (container.clientHeight - inner.height) / 2);
  container.scrollLeft += inner.left - outer.left - Math.max(0, (container.clientWidth - inner.width) / 2);
}
