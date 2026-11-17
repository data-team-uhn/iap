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

import { centreIn, keepInView } from "@iap/frontend-commons/scrollWithin";

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
