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

// PRETOTYPE. Draws the eye to what the reader was just taken to, such as an issue's thread or the note "Show where"
// led to: a ring and a tint of the theme's primary colour that come and go twice; for a reader who asked for less
// motion, a steady highlight that holds, then fades.
export function pulse(element: HTMLElement | null) {
  if (!element) {
    return;
  }
  const channel = getComputedStyle(element).getPropertyValue("--mui-palette-primary-mainChannel").trim() || "25 41 88";
  const on = { backgroundColor: `rgb(${channel} / 0.14)`, boxShadow: `0 0 0 3px rgb(${channel} / 0.45)` };
  const off = { backgroundColor: `rgb(${channel} / 0)`, boxShadow: `0 0 0 3px rgb(${channel} / 0)` };
  const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  element.animate(still ? [ on, { ...on, offset: 0.7 }, off ] : [ off, on, off, on, off ],
    { duration: still ? 2000 : 1800, easing: "ease-in-out" });
}

// Pulses what is found, once it is there and the page has had a moment to bring it into view; looks for it a few
// seconds, as the page it is on may still be opening
export function pulseWhenThere(find: () => HTMLElement | null) {
  const until = Date.now() + 4000;
  const look = () => {
    const found = find();
    if (found) {
      setTimeout(() => pulse(found), 600);
    } else if (Date.now() < until) {
      setTimeout(look, 100);
    }
  };
  setTimeout(look, 100);
}
