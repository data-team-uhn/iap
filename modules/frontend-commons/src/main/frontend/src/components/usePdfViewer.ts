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
import { useCallback, useEffect, useReducer, useRef } from "react";

import { loadPdfjs } from "../pdfjsClient";
import {
  findQuoteRects,
  getPageSearchOrder,
  getSearchText,
  parsePdfSource,
  type HighlightRect,
  type PdfTextRun,
} from "../pdfQuoteLocator";
import { isNotAuthenticated, useAuthenticatedFetch } from "../reLogin";
import { describeRequestFailure, RequestError } from "../requestFailure";
import { createViewerState, reduceViewerState, type DrawnPage } from "./pdfViewerModel";

import type { PDFDocumentLoadingTask, PDFDocumentProxy, PDFPageProxy } from "pdfjs-dist";

type TextContentItems = Awaited<ReturnType<PDFPageProxy["getTextContent"]>>["items"];

const SIGNED_OUT = "You are no longer signed in. Sign in and try again.";

function getTextRuns(items: TextContentItems): PdfTextRun[] {
  const runs: PdfTextRun[] = [];
  for (const item of items) {
    // Marked-content items have no string and are not text.
    if (!("str" in item) || item.str.length === 0) {
      continue;
    }
    const [,, skewX = 0, scaleY = 0, x = 0, y = 0] = item.transform as number[];
    const height = item.height > 0 ? item.height : Math.hypot(skewX, scaleY);
    runs.push({ str: item.str, x, y, width: item.width, height });
  }
  return runs;
}

// The search and the drawing both need a page's matches, and PDF.js reads the text again on every
// call. A failed read leaves the cache, so the next visit tries again.
function readRects(
  cache: Map<number, Promise<HighlightRect[]>>,
  doc: PDFDocumentProxy,
  pageNumber: number,
  searchText: string,
): Promise<HighlightRect[]> {
  let rects = cache.get(pageNumber);
  if (rects === undefined) {
    rects = doc.getPage(pageNumber)
      .then(page => page.getTextContent())
      .then(content => findQuoteRects(getTextRuns(content.items), searchText));
    void rects.catch(() => cache.delete(pageNumber));
    cache.set(pageNumber, rects);
  }
  return rects;
}

// Owns everything PdfViewer reads: the file, the PDF.js document, the search for the quote and
// each page's matches. The component only draws.
export function usePdfViewer(source: string, quote: string) {
  const authFetch = useAuthenticatedFetch();
  const rectsCache = useRef(new Map<number, Promise<HighlightRect[]>>());
  const searchText = getSearchText(quote);
  const [state, dispatch] = useReducer(reduceViewerState, searchText !== undefined, createViewerState);
  const { doc, page, attempt } = state;
  const { url, page: preferred } = parsePdfSource(source);

  // The file is fetched here rather than by PDF.js, so an expired session gets the same sign-in
  // every other request gets.
  useEffect(() => {
    let task: PDFDocumentLoadingTask | undefined;
    // Aborted by the cleanup, which also stops the download.
    const stop = new AbortController();
    void (async () => {
      try {
        const [{ pdfjs, worker }, response] = await Promise.all([
          loadPdfjs(),
          authFetch(url, { signal: stop.signal }),
        ]);
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        const data = await response.arrayBuffer();
        stop.signal.throwIfAborted();
        task = pdfjs.getDocument({ data, worker });
        const opened = await task.promise;
        stop.signal.throwIfAborted();
        const first = preferred !== undefined && preferred <= opened.numPages ? preferred : 1;
        dispatch({ type: "opened", doc: opened, page: first });
      } catch (error: unknown) {
        if (!stop.signal.aborted) {
          const message = isNotAuthenticated(error) ? SIGNED_OUT : describeRequestFailure(error);
          dispatch({ type: "openFailed", message });
        }
      }
    })();
    return () => {
      stop.abort();
      // The shared worker is not the task's, so this only drops the document.
      void task?.destroy();
    };
  }, [url, preferred, authFetch, attempt]);

  // The search keeps going when the person pages by hand, so the note can still say where the
  // quote is; it just stops moving the page. A page that cannot be read is skipped.
  useEffect(() => {
    if (doc === undefined || searchText === undefined) {
      return undefined;
    }
    const stop = new AbortController();
    const read = (pageNumber: number) =>
      readRects(rectsCache.current, doc, pageNumber, searchText).catch(() => undefined);
    void (async () => {
      const order = getPageSearchOrder(doc.numPages, preferred);
      let incomplete = false;
      let next = read(order[0]);
      for (const [index, candidate] of order.entries()) {
        const current = next;
        // The worker reads the next page while this one is matched.
        if (index + 1 < order.length) {
          next = read(order[index + 1]);
        }
        const rects = await current;
        if (stop.signal.aborted) {
          return;
        }
        if (rects === undefined) {
          incomplete = true;
        } else if (rects.length > 0) {
          dispatch({ type: "searched", search: { status: "found", page: candidate } });
          return;
        }
      }
      dispatch({ type: "searched", search: { status: "notFound", incomplete } });
    })();
    return () => {
      stop.abort();
    };
  }, [doc, searchText, preferred]);

  // Frees what PDF.js keeps for a page once the person leaves it. A redraw of the same page at a
  // new size still reuses it.
  useEffect(() => {
    if (doc === undefined || page === undefined) {
      return undefined;
    }
    return () => {
      // Fails once the document is closed, which frees the page anyway.
      void doc.getPage(page).then(pdfPage => pdfPage.cleanup()).catch(() => undefined);
    };
  }, [doc, page]);

  // A page's matches: empty for a quote too short to look for, undefined when the text cannot be read.
  const readMatches = useCallback(
    (pdf: PDFDocumentProxy, pageNumber: number): Promise<HighlightRect[] | undefined> =>
      searchText === undefined
        ? Promise.resolve([])
        : readRects(rectsCache.current, pdf, pageNumber, searchText).catch(() => undefined),
    [searchText],
  );

  const showPage = useCallback((next: number) => {
    dispatch({ type: "paged", page: next });
  }, []);

  // The note says the document is opening again, so Retry needs no progress of its own.
  const retry = useCallback((): Promise<void> => {
    dispatch({ type: "retry" });
    return Promise.resolve();
  }, []);

  const markDrawn = useCallback((drawn: DrawnPage) => {
    dispatch({ type: "drawn", ...drawn });
  }, []);

  const markDrawFailed = useCallback((pageNumber: number) => {
    dispatch({ type: "drawFailed", page: pageNumber });
  }, []);

  return { state, readMatches, showPage, retry, markDrawn, markDrawFailed };
}
