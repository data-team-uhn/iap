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

import { MarkLocator, type Mark, type MarkPlace } from "./marksModel";

import type { OpenedDocument } from "./usePdfDocument";

// Each mark's place in the document, by mark id.
export type MarkPlaces = Record<string, MarkPlace>;

// Finds each mark's passage in the document, updating the places as the pages are read in order.
export function useMarkPlaces(pdf: OpenedDocument | undefined, marks: Mark[]): MarkPlaces {
  const [found, setFound] = useState<{ marks: Mark[]; places: MarkPlaces } | undefined>(undefined);

  useEffect(() => {
    if (pdf === undefined) {
      return undefined;
    }
    const { doc, getPageText } = pdf;
    const locator = new MarkLocator(marks, doc.numPages);
    const stop = new AbortController();
    void (async () => {
      let last = "";
      for (let pageNumber = 1; pageNumber <= doc.numPages; pageNumber++) {
        const text = await getPageText(pageNumber);
        if (stop.signal.aborted) {
          return;
        }
        locator.addPage(pageNumber, text);
        const places = locator.getPlaces();
        const serialized = JSON.stringify(places);
        // Most pages change nothing, and each change draws the marks again.
        if (serialized !== last) {
          last = serialized;
          setFound({ marks, places });
        }
      }
    })();
    return () => stop.abort();
  }, [pdf, marks]);

  return useMemo(() => (found?.marks === marks ? found.places : new MarkLocator(marks).getPlaces()), [found, marks]);
}
