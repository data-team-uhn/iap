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
import { useCallback, useEffect, useRef } from "react";

import { useTheme } from "@mui/material/styles";

import { TEXT_ENTRY } from "./textEntry";

// How far a touch has to move sideways, more than twice as far as up or down, to count as a swipe rather
// than a tap or a scroll; and how far it moves before the element starts following it.
const SWIPE_DISTANCE = 48;
const DRAG_START = 8;

// How far the element showing what was swiped to slides in from.
const SLIDE_FROM = 48;

// How far the element follows a finger dragging it where there is nowhere to go, as a share of the drag.
const AT_THE_END = 1 / 3;

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// A ref to give an element that can be swiped by touch to go to the previous or the next of something,
// as the content it shows moves: right to the previous, left to the next, the other way round in a
// right-to-left layout. The element follows the finger, springs back when it is let go of too soon, or
// where there is nothing that way, and the element showing what it went to slides in from where the finger
// went, so the element must be a new one for each item. Up and down are left to the browser, to scroll the
// page, as is pinching, to zoom it. A swipe starting in a field is left to the field, to select its text, and
// the tap a browser may take a swipe to end with is not passed on.
export function useSwipe(onPrevious: (() => void) | undefined, onNext: (() => void) | undefined) {
  const handlers = useRef({ onPrevious, onNext });
  useEffect(() => {
    handlers.current = { onPrevious, onNext };
  }, [onPrevious, onNext]);
  // Which way the element showing what was swiped to slides in from, for the render the swipe caused.
  const slideIn = useRef<1 | -1 | undefined>(undefined);
  useEffect(() => {
    slideIn.current = undefined;
  });
  // Whether the last touch was a swipe, whose click is not passed on to whichever element is there by then.
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
    // Where the finger went, as the element follows it: the handler that way, if any.
    const towards = (across: number) => (across < 0 !== rtl ? handlers.current.onNext : handlers.current.onPrevious);
    const springBack = () => {
      const from = element.style.transform;
      element.style.transform = "";
      if (from !== "" && !reducedMotion()) {
        element.animate([ { transform: from }, { transform: "none" } ],
          { duration: duration.shortest, easing: easing.easeOut });
      }
    };

    if (slideIn.current !== undefined && !reducedMotion()) {
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
        const followed = towards(across) === undefined ? across * AT_THE_END : across;
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
      const step = towards(across);
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
      // A click from a key has no pointer behind it, so no swipe before it
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
