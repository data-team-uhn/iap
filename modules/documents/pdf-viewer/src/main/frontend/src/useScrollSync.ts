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
// Scrolls two versions of a document shown side by side together, matched by the text they share.
import { useCallback, useEffect, useMemo, useRef } from "react";

import { getTextLinesAround, getTextPointAt, getTextPointRect } from "./markLayer";
import { mapPoint, type Alignment } from "./revisionDiffModel";
import { isScrollByHand, SCROLL_INPUTS } from "./scrollByHand";

import type { TextPoint } from "./marksModel";
import type { ShownDocument } from "./usePdfView";

export type Version = "older" | "newer";

// What each pane's view provides for the two panes to scroll together.
interface SyncedView {
  shown: ShownDocument | undefined;
  showPoint: (point: TextPoint, atY: number) => void;
}

// Where a pane puts a place in its text: a point of the text, and how far below the pane's top it goes.
interface Placement {
  point: TextPoint;
  atY: number;
}

// How long a pane's scrolling counts as the person's after they touch it, by wheel, finger, key or pointer,
// in milliseconds. Each scroll within that time extends it, since a fling goes on scrolling after the finger
// lifts. It also counts for as long as the pointer is held down on the pane, as when dragging its scrollbar.
const BY_HAND_TIME = 500;

// How long a pane told to lead keeps leading, in milliseconds. It covers, for example, the pane's page
// controls moving it to a page that is not drawn yet.
const LEAD_TIME = 1500;

// The inputs that make a pane's scrolling the person's. Pressing a finger or the pointer anywhere on the pane
// counts, since dragging the page or a selection can scroll it.
const INPUTS = [ ...SCROLL_INPUTS, "touchstart" ];

const getOther = (version: Version): Version => (version === "older" ? "newer" : "older");

// Scrolls each pane with the other, matched by the text the versions share. The place in the middle of the
// pane scrolled by hand goes to the middle of the other. Through text only one version has, the panes move
// at different speeds. While the text in the middle is not drawn yet, such as on a page just scrolled to,
// the other pane follows once it is. A pane moved this way does not move the first one back.
//
// Returns `lead`, which moves the other pane to the one given and lets that one lead for a while. It also
// returns `release`, which lets both panes go their own way until the next scroll by hand.
export function useScrollSync(views: Record<Version, SyncedView>, alignment: Alignment | undefined) {
  const { shown: olderShown, showPoint: showOlder } = views.older;
  const { shown: newerShown, showPoint: showNewer } = views.newer;
  const leadRef = useRef<(version: Version) => void>(() => undefined);
  const releaseRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    if (alignment === undefined || olderShown === undefined || newerShown === undefined) {
      return undefined;
    }
    const shown = { older: olderShown, newer: newerShown };
    const show = { older: showOlder, newer: showNewer };
    // The pane whose middle text is not drawn yet. The other pane follows it once that text is drawn.
    let waiting: Version | undefined;
    // Where the other pane puts the place in the middle of this one, using the lines on either side of it.
    // Each line goes to its place in the other version, and the middle goes as far between those two places as
    // it is between the lines. Text only one version has maps to one place in the other, where its change is.
    // The other pane then stays still through it, and starts and stops moving smoothly at its ends.
    // `undefined` unless both lines and both places are drawn.
    const placeBetweenLines = (from: Version, middle: number): Placement | undefined => {
      const lines = getTextLinesAround(shown[from].frame, middle);
      if (lines === undefined) {
        return undefined;
      }
      const to = getOther(from);
      const [ above, below ] = [ lines.above, lines.below ].map(line => mapPoint(alignment, from, line.point).point);
      const aboveRect = getTextPointRect(shown[to].frame, above);
      const belowRect = getTextPointRect(shown[to].frame, below);
      if (aboveRect === undefined || belowRect === undefined) {
        return undefined;
      }
      const share = (middle - lines.above.top) / (lines.below.top - lines.above.top);
      return { point: above, atY: shown[to].frame.clientHeight / 2 - share * (belowRect.top - aboveRect.top) };
    };
    // The same, using only the line at the middle. A shared word keeps its offset into the line. Text only
    // one version has goes to where its change is. `undefined` while the text in the middle is not drawn.
    const placeAtLine = (from: Version, middle: number): Placement | undefined => {
      const { frame } = shown[from];
      const point = getTextPointAt(frame, middle);
      if (point === undefined) {
        return undefined;
      }
      const { point: there, shared } = mapPoint(alignment, from, point);
      const line = shared ? getTextPointRect(frame, point) : undefined;
      const below = line === undefined ? 0 : Math.min(Math.max(middle - line.top, 0), line.height);
      return { point: there, atY: shown[getOther(from)].frame.clientHeight / 2 - below };
    };
    // Moves the other pane so that the same text is in its middle.
    const follow = (from: Version) => {
      const { frame } = shown[from];
      const middle = frame.getBoundingClientRect().top + frame.clientHeight / 2;
      const placement = placeBetweenLines(from, middle) ?? placeAtLine(from, middle);
      waiting = placement === undefined ? from : undefined;
      if (placement !== undefined) {
        show[getOther(from)](placement.point, placement.atY);
      }
    };
    // The pane the other follows, and until when, unless the pointer is held down on it.
    let leader: Version | undefined;
    let leadsUntil = 0;
    let request = 0;
    const lead = (version: Version, time: number) => {
      leader = version;
      leadsUntil = performance.now() + time;
    };
    // The pane the pointer is held down on, if any. The browser may not report the release, as when it
    // happens outside the window. The pointer moving with no button down therefore ends the hold too.
    let held: Version | undefined;
    const stop = new AbortController();
    for (const type of [ "pointerup", "pointercancel", "pointermove" ] as const) {
      window.addEventListener(type, event => {
        if (type !== "pointermove" || event.buttons === 0) {
          held = undefined;
        }
      }, { signal: stop.signal, passive: true });
    }
    for (const version of [ "older", "newer" ] as const) {
      const { frame, eventBus } = shown[version];
      eventBus.on("textlayerrendered", () => {
        if (waiting === version) {
          follow(version);
        }
      }, { signal: stop.signal });
      for (const type of INPUTS) {
        frame.addEventListener(type, event => {
          if (event.type !== "keydown" || isScrollByHand(event, frame)) {
            lead(version, BY_HAND_TIME);
            held = event.type === "pointerdown" ? version : held;
          }
        }, { signal: stop.signal, passive: true });
      }
      frame.addEventListener("scroll", () => {
        if (leader !== version || (performance.now() > leadsUntil && held !== version)) {
          return;
        }
        leadsUntil = Math.max(leadsUntil, performance.now() + BY_HAND_TIME);
        cancelAnimationFrame(request);
        request = requestAnimationFrame(() => follow(version));
      }, { signal: stop.signal, passive: true });
    }
    leadRef.current = version => {
      lead(version, LEAD_TIME);
      cancelAnimationFrame(request);
      request = requestAnimationFrame(() => follow(version));
    };
    releaseRef.current = () => {
      leader = undefined;
      waiting = undefined;
      cancelAnimationFrame(request);
    };
    return () => {
      stop.abort();
      cancelAnimationFrame(request);
      leadRef.current = () => undefined;
      releaseRef.current = () => undefined;
    };
  }, [alignment, olderShown, newerShown, showOlder, showNewer]);

  const leadPane = useCallback((version: Version) => leadRef.current(version), []);
  const release = useCallback(() => releaseRef.current(), []);
  return useMemo(() => ({ lead: leadPane, release }), [leadPane, release]);
}
