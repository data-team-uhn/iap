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
import { useEffect, useState } from "react";

import { compareRevisions, type Comparison } from "./revisionDiffModel";

import type { PageText } from "./marksModel";
import type { OpenedDocument } from "./usePdfDocument";

// How a comparison is going: reading the versions' pages, with how many of the longer version's are read;
// comparing them; or done.
export type ComparisonState =
  | { status: "reading"; read: number; total: number }
  | { status: "comparing" }
  | Comparison;

// The least time between two reports of how far the reading has got. Each report draws the viewer again.
const PROGRESS_INTERVAL = 200;

// Both versions' text, once read, and how far the reading has got, for the documents it was read from.
interface Reading {
  older: OpenedDocument;
  newer: OpenedDocument;
  read: number;
  total: number;
  texts?: { older: PageText[]; newer: PageText[] };
}

function readPage({ doc, getPageTextWithLines }: OpenedDocument, page: number): Promise<PageText | undefined> {
  return page <= doc.numPages ? getPageTextWithLines(page) : Promise.resolve(undefined);
}

// Reads both versions' text, a page of each at a time, and compares them, strictly or not. Comparing
// again the other way does not read the pages again.
export function useRevisionDiff(
  older: OpenedDocument | undefined,
  newer: OpenedDocument | undefined,
  strict: boolean,
): ComparisonState {
  const [reading, setReading] = useState<Reading | undefined>(undefined);
  const [compared, setCompared] = useState<{ comparison: Comparison; strict: boolean; of: object } | undefined>();

  useEffect(() => {
    if (older === undefined || newer === undefined) {
      return undefined;
    }
    const stop = new AbortController();
    const total = Math.max(older.doc.numPages, newer.doc.numPages);
    void (async () => {
      const texts = { older: [] as PageText[], newer: [] as PageText[] };
      let reportedAt = 0;
      for (let page = 1; page <= total; page++) {
        const [ one, other ] = await Promise.all([ readPage(older, page), readPage(newer, page) ]);
        if (stop.signal.aborted) {
          return;
        }
        texts.older.push(...one === undefined ? [] : [ one ]);
        texts.newer.push(...other === undefined ? [] : [ other ]);
        if (performance.now() - reportedAt >= PROGRESS_INTERVAL) {
          reportedAt = performance.now();
          setReading({ older, newer, read: page, total });
        }
      }
      setReading({ older, newer, read: total, total, texts });
    })();
    return () => stop.abort();
  }, [older, newer]);

  const current = reading !== undefined && reading.older === older && reading.newer === newer ? reading : undefined;
  const texts = current?.texts;

  useEffect(() => {
    if (texts === undefined) {
      return undefined;
    }
    let shown = true;
    void compareRevisions(texts.older, texts.newer, { strict }).then(comparison => {
      if (shown) {
        setCompared({ comparison, strict, of: texts });
      }
    });
    return () => {
      shown = false;
    };
  }, [texts, strict]);

  if (texts === undefined) {
    return { status: "reading", read: current?.read ?? 0, total: current?.total ?? 0 };
  }
  return compared?.of === texts && compared.strict === strict ? compared.comparison : { status: "comparing" };
}
