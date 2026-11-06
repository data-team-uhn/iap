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

// PRETOTYPE. Draws the eye to what the reader was just taken to, such as an issue's thread: a tint of the theme's
// primary colour that comes and goes twice, or fades once for a reader who asked for less motion.
export function pulse(element: HTMLElement | null) {
  if (!element) {
    return;
  }
  const channel = getComputedStyle(element).getPropertyValue("--mui-palette-primary-mainChannel").trim() || "37 92 170";
  const tint = (alpha: number) => `rgb(${channel} / ${alpha})`;
  const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  element.animate(still
    ? [ { backgroundColor: tint(0.14) }, { backgroundColor: tint(0) } ]
    : [ { backgroundColor: tint(0) }, { backgroundColor: tint(0.16) }, { backgroundColor: tint(0.02) },
      { backgroundColor: tint(0.16) }, { backgroundColor: tint(0) } ],
  { duration: still ? 1200 : 1600, easing: "ease-in-out" });
}
