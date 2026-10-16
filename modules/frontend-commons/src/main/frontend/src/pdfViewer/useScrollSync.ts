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

import { getTextPointAt } from "./markLayer";
import { mapPoint, type Alignment } from "./revisionDiffModel";

import type { TextPoint } from "./marksModel";
import type { ShownDocument } from "./usePdfView";

export type Version = "older" | "newer";

// What scrolling one pane with the other needs of each pane's view.
interface SyncedView {
  shown: ShownDocument | undefined;
  showPoint: (point: TextPoint, atY: number) => void;
}

// How long after the person last touched a pane, by wheel, finger, key or pointer, or after it last
// scrolled since, its scrolling is theirs. A fling goes on scrolling after the finger lifts.
const BY_HAND_TIME = 500;

// How long a pane told to lead does, as when a search moves it to a match on a page not drawn yet.
const LEAD_TIME = 1500;

const INPUTS = [ "wheel", "touchstart", "touchmove", "pointerdown", "keydown" ];

const getOther = (version: Version): Version => (version === "older" ? "newer" : "older");

// Scrolls each pane with the other, matched by the text the versions share: the place in the middle of the
// pane scrolled by hand goes to the middle of the other, which therefore moves faster or slower through
// text only one version has. While the text in the middle of the pane is not drawn yet, as on a page just
// scrolled to, the other pane follows once it is. A pane moved this way does not move the first one back.
// Gives a function making a pane lead, as after zooming or searching.
export function useScrollSync(views: Record<Version, SyncedView>, alignment: Alignment | undefined) {
  const { shown: olderShown, showPoint: showOlder } = views.older;
  const { shown: newerShown, showPoint: showNewer } = views.newer;
  const leadRef = useRef<(version: Version) => void>(() => undefined);

  useEffect(() => {
    if (alignment === undefined || olderShown === undefined || newerShown === undefined) {
      return undefined;
    }
    const shown = { older: olderShown, newer: newerShown };
    const show = { older: showOlder, newer: showNewer };
    let waiting: Version | undefined;
    const follow = (from: Version) => {
      const { frame } = shown[from];
      const point = getTextPointAt(frame, frame.getBoundingClientRect().top + frame.clientHeight / 2);
      waiting = point === undefined ? from : undefined;
      if (point !== undefined) {
        const to = getOther(from);
        show[to](mapPoint(alignment, from, point), shown[to].frame.clientHeight / 2);
      }
    };
    let leader: Version | undefined;
    let leadsUntil = 0;
    let request = 0;
    const lead = (version: Version, time: number) => {
      leader = version;
      leadsUntil = performance.now() + time;
    };
    const stop = new AbortController();
    for (const version of [ "older", "newer" ] as const) {
      const { frame, eventBus } = shown[version];
      eventBus.on("textlayerrendered", () => {
        if (waiting === version) {
          follow(version);
        }
      }, { signal: stop.signal });
      for (const type of INPUTS) {
        frame.addEventListener(type, () => lead(version, BY_HAND_TIME), { signal: stop.signal, passive: true });
      }
      frame.addEventListener("scroll", () => {
        if (leader !== version || performance.now() > leadsUntil) {
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
    return () => {
      stop.abort();
      cancelAnimationFrame(request);
      leadRef.current = () => undefined;
    };
  }, [alignment, olderShown, newerShown, showOlder, showNewer]);

  return useCallback((version: Version) => leadRef.current(version), []);
}
