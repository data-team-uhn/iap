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

// What a reader does to scroll a document's frame: a wheel, a finger, the pointer, or a key.
export const SCROLL_INPUTS = [ "wheel", "touchmove", "pointerdown", "keydown" ] as const;

// The keys that scroll a frame with the focus in it.
const SCROLL_KEYS = new Set([
  "ArrowUp",
  "ArrowDown",
  "ArrowLeft",
  "ArrowRight",
  "PageUp",
  "PageDown",
  "Home",
  "End",
  " ",
]);

// Whether an input in a frame scrolls it: a scrolling key without Ctrl, Alt or Meta, which do something
// else, and not typed into a field of the document; the pointer pressed on the frame itself, which only its
// scrollbars show, the pages covering the rest; or a wheel or a finger moving.
export function scrollsByHand(event: Event, frame: HTMLElement): boolean {
  if (event instanceof KeyboardEvent) {
    const typing = event.target instanceof HTMLElement
      && (event.target.isContentEditable || [ "INPUT", "TEXTAREA", "SELECT" ].includes(event.target.tagName));
    return !typing && SCROLL_KEYS.has(event.key) && !event.ctrlKey && !event.altKey && !event.metaKey;
  }
  if (event.type === "pointerdown") {
    return event.target === frame;
  }
  return true;
}
