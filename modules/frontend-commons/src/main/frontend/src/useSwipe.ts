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
// Stepping between items by swiping an element sideways with a finger.

import { useCallback, useEffect, useRef } from "react";

import { useTheme } from "@mui/material/styles";

import { TEXT_ENTRY } from "./textEntry";

// How far a touch has to move sideways to count as a swipe, in pixels. It must also move at least twice as
// far across as up or down.
const SWIPE_DISTANCE = 48;
// How far a touch moves sideways, in pixels, before the element starts following it.
const DRAG_START = 8;

// How far away, in pixels, the element showing the item swiped to starts sliding in.
const SLIDE_FROM = 48;

// How far the element follows a finger dragging it where there is nowhere to go, as a share of the drag.
const AT_THE_END = 1 / 3;

const isReducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// A callback ref for an element that people swipe by touch to go to the previous or the next item. Pass
// `undefined` for a side with no item. A swipe right goes to the previous item and a swipe left to the
// next, the other way round in a right-to-left layout. The element follows the finger. It springs back when
// let go too soon, or where there is no item that way. The element showing the item swiped to slides in
// the way the finger went, so give each item an element of its own. Up and down are left to the browser to
// scroll the page, and pinching to zoom it. A swipe starting in a field is left to the field, to select its
// text. A click the browser may fire at the end of a swipe is stopped.
export function useSwipe(onPrevious: (() => void) | undefined, onNext: (() => void) | undefined) {
  const handlers = useRef({ onPrevious, onNext });
  useEffect(() => {
    handlers.current = { onPrevious, onNext };
  }, [onPrevious, onNext]);
  // Which side the next element slides in from, 1 for the right and -1 for the left. It is kept only for
  // the render the swipe causes.
  const slideIn = useRef<1 | -1 | undefined>(undefined);
  useEffect(() => {
    slideIn.current = undefined;
  });
  // Whether the last touch was a swipe, so that the click ending it is stopped.
  const swiped = useRef(false);
  const { duration, easing } = useTheme().transitions;

  return useCallback((element: HTMLElement | null) => {
    if (element === null) {
      return undefined;
    }
    const stop = new AbortController();
    const { signal } = stop;
    let start: { x: number; y: number; pointerId: number } | undefined;
    let dragging = false;
    let rtl = false;
    element.style.touchAction = "pan-y pinch-zoom";
    // The handler for the way the finger went, if there is one.
    const getHandlerTowards = (across: number) => (
      across < 0 !== rtl ? handlers.current.onNext : handlers.current.onPrevious
    );
    const springBack = () => {
      const from = element.style.transform;
      element.style.transform = "";
      if (from !== "" && !isReducedMotion()) {
        element.animate([ { transform: from }, { transform: "none" } ],
          { duration: duration.shortest, easing: easing.easeOut });
      }
    };

    if (slideIn.current !== undefined && !isReducedMotion()) {
      const from = `translateX(${slideIn.current * SLIDE_FROM}px)`;
      element.animate([ { transform: from, opacity: 0.4 }, { transform: "none", opacity: 1 } ],
        { duration: duration.shorter, easing: easing.easeOut });
    }

    element.addEventListener("pointerdown", event => {
      const inField = event.target instanceof Element
        && event.target.closest(TEXT_ENTRY) !== null;
      start = event.pointerType === "touch" && !inField
        ? { x: event.clientX, y: event.clientY, pointerId: event.pointerId }
        : undefined;
      dragging = false;
      rtl = getComputedStyle(element).direction === "rtl";
      swiped.current = false;
    }, { signal });
    element.addEventListener("pointermove", event => {
      if (start?.pointerId !== event.pointerId) {
        return;
      }
      const across = event.clientX - start.x;
      dragging ||= Math.abs(across) >= DRAG_START && Math.abs(across) > 2 * Math.abs(event.clientY - start.y);
      if (dragging) {
        const followed = getHandlerTowards(across) === undefined ? across * AT_THE_END : across;
        element.style.transform = `translateX(${followed}px)`;
      }
    }, { signal });
    element.addEventListener("pointercancel", () => {
      start = undefined;
      springBack();
    }, { signal });
    element.addEventListener("pointerup", event => {
      if (start?.pointerId !== event.pointerId) {
        return;
      }
      const across = event.clientX - start.x;
      const down = event.clientY - start.y;
      start = undefined;
      const step = getHandlerTowards(across);
      if (Math.abs(across) < SWIPE_DISTANCE || Math.abs(across) < 2 * Math.abs(down) || step === undefined) {
        springBack();
        return;
      }
      swiped.current = true;
      element.style.transform = "";
      slideIn.current = across < 0 ? 1 : -1;
      step();
    }, { signal });
    element.addEventListener("click", event => {
      // A click from a key has a `detail` of 0, and no swipe ends with one
      if (swiped.current && event.detail > 0) {
        swiped.current = false;
        event.preventDefault();
        event.stopPropagation();
      }
    }, { signal, capture: true });
    return () => {
      stop.abort();
      element.style.touchAction = "";
      element.style.transform = "";
    };
  }, [ duration, easing ]);
}
