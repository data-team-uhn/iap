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
// Tells whether a scrolling element has more to show above or below, and fades its content out at those
// edges.

import { useEffect, useState } from "react";

// Whether an element has more to show above and below what it shows now.
export interface ScrollEdges {
  above: boolean;
  below: boolean;
}

// How far from an edge the fade reaches, in pixels.
const FADE = 32;

// Whether an element that scrolls up and down has more to show above or below. It is measured again as the
// element scrolls, as it or its children change size, and as children come and go. The result changes only
// when one of the two answers does, not at each step of a scroll. Pass the element itself, as a callback
// ref keeps it, so that an element that appears after the first render is watched too.
export function useScrollEdges(element: HTMLElement | null): ScrollEdges {
  const [ edges, setEdges ] = useState({ above: false, below: false });

  useEffect(() => {
    if (element === null) {
      return undefined;
    }
    const measure = () => {
      const above = element.scrollTop > 0;
      // A pixel of slack, for sizes the browser rounds.
      const below = element.scrollTop + element.clientHeight < element.scrollHeight - 1;
      setEdges(previous => (previous.above === above && previous.below === below ? previous : { above, below }));
    };
    const stop = new AbortController();
    element.addEventListener("scroll", measure, { signal: stop.signal });
    // An element can keep its size while what it holds grows and shrinks, so its children are watched too.
    const resizes = new ResizeObserver(measure);
    resizes.observe(element);
    for (const child of element.children) {
      resizes.observe(child);
    }
    const changes = new MutationObserver(records => {
      for (const { addedNodes, removedNodes } of records) {
        addedNodes.forEach(node => node instanceof Element && resizes.observe(node));
        removedNodes.forEach(node => node instanceof Element && resizes.unobserve(node));
      }
      measure();
    });
    changes.observe(element, { childList: true });
    measure();
    return () => {
      stop.abort();
      resizes.disconnect();
      changes.disconnect();
    };
  }, [element]);

  return edges;
}

// A mask fading an element's content out at its top, its bottom or both, where there is more of it that way.
// Undefined when there is no more either way.
export function fadeEdges({ above, below }: ScrollEdges): string | undefined {
  if (!above && !below) {
    return undefined;
  }
  const top = above ? "transparent" : "black";
  const bottom = below ? "transparent" : "black";
  return `linear-gradient(to bottom, ${top}, black ${FADE}px, black calc(100% - ${FADE}px), ${bottom})`;
}
