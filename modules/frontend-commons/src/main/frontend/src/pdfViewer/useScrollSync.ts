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
import { useCallback, useEffect, useMemo, useRef } from "react";

import { getTextPointAt, getTextPointRect } from "./markLayer";
import { mapPoint, type Alignment } from "./revisionDiffModel";
import { SCROLL_INPUTS, scrollsByHand } from "./scrollByHand";

import type { TextPoint } from "./marksModel";
import type { ShownDocument } from "./usePdfView";

export type Version = "older" | "newer";

// What scrolling one pane with the other needs of each pane's view.
interface SyncedView {
  shown: ShownDocument | undefined;
  showPoint: (point: TextPoint, atY: number) => void;
}

// How long a pane's scrolling counts as the person's after they touch it, by wheel, finger, key or
// pointer. Each scroll since extends it, as a fling goes on scrolling after the finger lifts. While the
// pointer is still held down on the pane, as when dragging its scrollbar, it counts for as long as that.
const BY_HAND_TIME = 500;

// How long a pane told to lead keeps leading, such as while its page bar moves it to a page not drawn yet.
const LEAD_TIME = 1500;

// Pressing a finger or the pointer anywhere may start a scroll, by dragging the page or a selection.
const INPUTS = [ ...SCROLL_INPUTS, "touchstart" ];

const getOther = (version: Version): Version => (version === "older" ? "newer" : "older");

// Scrolls each pane with the other, matched by the text the versions share. The place in the middle of the
// pane scrolled by hand goes to the middle of the other. Through text only one version has, the panes move
// at different speeds. While the text in the middle is not drawn yet, such as on a page just scrolled to,
// the other pane follows once it is. A pane moved this way does not move the first one back. Gives `lead`,
// making a pane lead, such as after zooming or going to a page, and `release`, letting both panes go their
// own way until the next scroll by hand, such as when each is taken to the same change or searched.
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
    let waiting: Version | undefined;
    // The middle of one pane goes to the middle of the other, as far into the line of the same text, so the
    // other moves as smoothly, not a line at a time.
    const follow = (from: Version) => {
      const { frame } = shown[from];
      const middle = frame.getBoundingClientRect().top + frame.clientHeight / 2;
      const point = getTextPointAt(frame, middle);
      waiting = point === undefined ? from : undefined;
      if (point !== undefined) {
        const to = getOther(from);
        const line = getTextPointRect(frame, point);
        const below = line === undefined ? 0 : Math.min(Math.max(middle - line.top, 0), line.height);
        show[to](mapPoint(alignment, from, point), shown[to].frame.clientHeight / 2 - below);
      }
    };
    let leader: Version | undefined;
    let leadsUntil = 0;
    let request = 0;
    const lead = (version: Version, time: number) => {
      leader = version;
      leadsUntil = performance.now() + time;
    };
    // The pane the pointer is held down on, if any. A release may never be told, as when it happens outside
    // the window, so the pointer moving with no button down ends the hold too.
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
          if (event.type !== "keydown" || scrollsByHand(event, frame)) {
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
