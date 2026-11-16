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
import { useEffect, useMemo, useState } from "react";

import { getPlacedPages, isLooking, MarkLocator, type Mark, type MarkPlace } from "./marksModel";

import type { OpenedDocument } from "./usePdfDocument";

// Each mark's place in the document, by mark id.
export type MarkPlaces = Record<string, MarkPlace>;

// The least time, in milliseconds, between reports of how many pages have been read, unless a passage's
// place changes sooner. Each report draws the viewer again.
const PROGRESS_INTERVAL = 200;

// Where each mark's passage is in the document, as its pages are read. Pages with marks placed by their
// spans or kept as anchors are read first, then the rest in order. Reading stops once no passage is still
// being looked for, as reading takes PDF.js from drawing the pages in view. Also how many pages have been
// read so far.
export function useMarkPlaces(pdf: OpenedDocument | undefined, marks: Mark[]): { places: MarkPlaces; read: number } {
  const [found, setFound] = useState<{ marks: Mark[]; places: MarkPlaces; read: number } | undefined>(undefined);

  useEffect(() => {
    if (pdf === undefined) {
      return undefined;
    }
    const { doc, getPageTextWithLines } = pdf;
    const locator = new MarkLocator(marks, doc.numPages);
    const stop = new AbortController();
    const placed = getPlacedPages(marks, doc.numPages);
    const rest = Array.from({ length: doc.numPages }, (_, index) => index + 1).filter(page => !placed.includes(page));
    void (async () => {
      let places = locator.getPlaces();
      let last = "";
      let reportedAt = 0;
      let read = 0;
      for (const pageNumber of [ ...placed, ...rest ]) {
        if (!isLooking(places)) {
          return;
        }
        const page = await getPageTextWithLines(pageNumber);
        if (stop.signal.aborted) {
          return;
        }
        locator.addPage(pageNumber, page);
        read++;
        const latest = locator.getPlaces();
        const serialized = JSON.stringify(latest);
        // Most pages change nothing, and each change draws the marks again.
        const changed = serialized !== last;
        if (changed) {
          last = serialized;
          places = latest;
        }
        if (changed || performance.now() - reportedAt >= PROGRESS_INTERVAL) {
          reportedAt = performance.now();
          setFound({ marks, places, read });
        }
      }
    })();
    return () => stop.abort();
  }, [pdf, marks]);

  return useMemo(() => (found?.marks === marks
    ? { places: found.places, read: found.read }
    : { places: new MarkLocator(marks).getPlaces(), read: 0 }), [found, marks]);
}
