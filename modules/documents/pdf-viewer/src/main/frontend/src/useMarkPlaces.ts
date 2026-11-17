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
import { useEffect, useMemo, useRef, useState } from "react";

import { getMarkKey, getPlacedPages, isLooking, MarkLocator, type Mark, type MarkPlace } from "./marksModel";

import type { OpenedDocument } from "./usePdfDocument";

// Each mark's place in the document, by mark id.
export type MarkPlaces = Record<string, MarkPlace>;

// The least time, in milliseconds, between reports of how many pages have been read, unless a passage's
// place changes sooner. Each report draws the viewer again.
const PROGRESS_INTERVAL = 200;

// The longest time, in milliseconds, spent reading pages before the browser is let draw. A page already
// read comes back at once, so without a pause the pages could be gone through without the viewer drawing.
const FRAME_BUDGET = 16;

// The places last found in a document, for the marks they were found for.
interface KnownPlaces {
  pdf: OpenedDocument;
  marks: Mark[];
  places: MarkPlaces;
}

// The places already known for the marks that are still the same in the same document. Adding a mark then
// leaves the others where they were found, rather than sending them all back to searching.
function getUnchangedPlaces(known: KnownPlaces | undefined, pdf: OpenedDocument, marks: Mark[]): MarkPlaces {
  if (known?.pdf !== pdf) {
    return {};
  }
  const knownKeys = new Map(known.marks.map(mark => [ mark.id, getMarkKey(mark) ]));
  return Object.fromEntries(marks.flatMap(mark => {
    const place = known.places[mark.id] as MarkPlace | undefined;
    const same = knownKeys.get(mark.id) === getMarkKey(mark) && place !== undefined && place.status !== "searching";
    return same ? [ [ mark.id, place ] ] : [];
  }));
}

// Where each mark's passage is in the document, as its pages are read. Pages that marks say they are on
// are read first, then the rest in order. Reading stops once no passage is still being looked for, as
// reading takes PDF.js from drawing the pages in view. A mark that has not changed keeps the place already
// found for it. Also how many pages have been read so far.
export function useMarkPlaces(pdf: OpenedDocument | undefined, marks: Mark[]): { places: MarkPlaces; read: number } {
  const [found, setFound] = useState<KnownPlaces & { read: number } | undefined>(undefined);
  // The places last found, for the next marks to keep what has not changed.
  const knownRef = useRef<KnownPlaces | undefined>(undefined);

  useEffect(() => {
    if (pdf === undefined) {
      return undefined;
    }
    const { doc, getPageTextWithLines } = pdf;
    const kept = getUnchangedPlaces(knownRef.current, pdf, marks);
    const changed = marks.filter(mark => !(mark.id in kept));
    const locator = new MarkLocator(changed, doc.numPages);
    const stop = new AbortController();
    const placed = getPlacedPages(changed, doc.numPages);
    const rest = Array.from({ length: doc.numPages }, (_, index) => index + 1).filter(page => !placed.includes(page));
    const report = (places: MarkPlaces, read: number) => {
      knownRef.current = { pdf, marks, places };
      setFound({ pdf, marks, places, read });
    };
    void (async () => {
      let places = { ...kept, ...locator.getPlaces() };
      let last = "";
      let reportedAt = 0;
      let pausedAt = performance.now();
      let read = 0;
      if (!isLooking(places)) {
        report(places, read);
        return;
      }
      for (const pageNumber of [ ...placed, ...rest ]) {
        if (!isLooking(places)) {
          return;
        }
        if (performance.now() - pausedAt >= FRAME_BUDGET) {
          await new Promise(resolve => setTimeout(resolve));
          pausedAt = performance.now();
        }
        const page = await getPageTextWithLines(pageNumber);
        if (stop.signal.aborted) {
          return;
        }
        locator.addPage(pageNumber, page);
        read++;
        const latest = { ...kept, ...locator.getPlaces() };
        const serialized = JSON.stringify(latest);
        // Most pages change nothing, and each change draws the marks again.
        const changedPlaces = serialized !== last;
        if (changedPlaces) {
          last = serialized;
          places = latest;
        }
        if (changedPlaces || performance.now() - reportedAt >= PROGRESS_INTERVAL) {
          reportedAt = performance.now();
          report(places, read);
        }
      }
    })();
    return () => stop.abort();
  }, [pdf, marks]);

  return useMemo(() => {
    if (found?.marks === marks && found.pdf === pdf) {
      return { places: found.places, read: found.read };
    }
    const kept = pdf === undefined ? {} : getUnchangedPlaces(found, pdf, marks);
    const searching = new MarkLocator(marks.filter(mark => !(mark.id in kept))).getPlaces();
    return { places: { ...kept, ...searching }, read: 0 };
  }, [found, marks, pdf]);
}
