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
import { fireEvent } from "@testing-library/react";

import { scrollsByHand } from "@iap/frontend-commons/pdfViewer/scrollByHand";

// Whether the event fired on the target, in a frame holding a page with a field, counts as scrolling by hand.
function counts(fire: (target: HTMLElement) => void, on: "frame" | "page" | "field" = "frame"): boolean {
  const frame = document.createElement("div");
  frame.innerHTML = '<div class="pdfViewer"><input></div>';
  document.body.append(frame);
  const targets = { frame, page: frame.firstElementChild as HTMLElement, field: frame.querySelector("input")! };
  let counted = false;
  for (const type of [ "keydown", "pointerdown", "wheel", "touchmove" ]) {
    frame.addEventListener(type, event => {
      counted = scrollsByHand(event, frame);
    });
  }
  fire(targets[on]);
  frame.remove();
  return counted;
}

describe("scrollsByHand", () => {
  it("counts a key scrolling the frame, but not one doing something else, nor one typed into a field", () => {
    expect(counts(target => fireEvent.keyDown(target, { key: "PageDown" }))).toBe(true);
    expect(counts(target => fireEvent.keyDown(target, { key: " " }), "page")).toBe(true);
    expect(counts(target => fireEvent.keyDown(target, { key: "Tab" }))).toBe(false);
    expect(counts(target => fireEvent.keyDown(target, { key: "ArrowDown", ctrlKey: true }))).toBe(false);
    expect(counts(target => fireEvent.keyDown(target, { key: " " }), "field")).toBe(false);
  });

  it("counts the pointer pressed on the frame's scrollbars, but not on its pages", () => {
    expect(counts(target => fireEvent.pointerDown(target))).toBe(true);
    expect(counts(target => fireEvent.pointerDown(target), "page")).toBe(false);
  });

  it("counts a wheel or a finger moving", () => {
    expect(counts(target => fireEvent.wheel(target), "page")).toBe(true);
    expect(counts(target => fireEvent.touchMove(target), "page")).toBe(true);
  });
});
