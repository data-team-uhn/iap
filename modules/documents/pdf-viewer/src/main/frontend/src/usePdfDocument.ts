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

// Fetches a PDF and opens it with PDF.js, or says why it could not be opened.

import { useCallback, useEffect, useState } from "react";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, RequestError } from "@iap/frontend-commons/requestFailure";

import { loadPdfViewer, type ViewerComponents } from "./pdfjsViewer";
import { describePdfFailure } from "./pdfViewerModel";

import type { PageText } from "./marksModel";
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";

// A document PDF.js has opened, with the viewer's components to show it in.
export interface OpenedDocument {
  components: ViewerComponents;
  doc: PDFDocumentProxy;
  // The text of a page as PDF.js reads it for its text layer, so offsets into it are offsets into the
  // layer's text. A page PDF.js cannot read has no text. Each page is read once.
  getPageText: (pageNumber: number) => Promise<string>;
  // The same text, with where its lines end. A page gives the same object every time, so what is worked
  // out from its text can be kept against that object.
  getPageTextWithLines: (pageNumber: number) => Promise<PageText>;
  // The name PDF.js gave the font of each piece of a page's text, in the order the text layer draws them, one
  // per span. The name is the one PDF.js loads the font under to draw the page. Read with the text.
  getPageFonts: (pageNumber: number) => Promise<string[]>;
}

// A page's text, and the font of each piece of it.
interface ReadPage {
  text: PageText;
  fonts: string[];
}

// A page's text as PDF.js reads it for its text layer, with where its lines end, and the font of each piece.
// It is empty for a page PDF.js cannot read.
async function readPage(doc: PDFDocumentProxy, pageNumber: number): Promise<ReadPage> {
  const page: ReadPage = { text: { text: "", lineEnds: [] }, fonts: [] };
  try {
    const content = await (await doc.getPage(pageNumber)).getTextContent({ disableNormalization: true });
    for (const item of content.items) {
      if ("str" in item) {
        page.text.text += item.str;
        // PDF.js draws a span only for a piece with text, and ends many lines with an empty one.
        if (item.str !== "") {
          page.fonts.push(item.fontName);
        }
        if (item.hasEOL) {
          page.text.lineEnds.push(page.text.text.length);
        }
      }
    }
    return page;
  } catch {
    return { text: { text: "", lineEnds: [] }, fonts: [] };
  }
}

function createOpenedDocument(components: ViewerComponents, doc: PDFDocumentProxy): OpenedDocument {
  const pages = new Map<number, Promise<ReadPage>>();
  const read = (pageNumber: number) => {
    const page = pages.get(pageNumber) ?? readPage(doc, pageNumber);
    pages.set(pageNumber, page);
    return page;
  };
  const getPageTextWithLines = async (pageNumber: number) => (await read(pageNumber)).text;
  const getPageText = async (pageNumber: number) => (await getPageTextWithLines(pageNumber)).text;
  const getPageFonts = async (pageNumber: number) => (await read(pageNumber)).fonts;
  return { components, doc, getPageText, getPageTextWithLines, getPageFonts };
}

// Opens the PDF at a URL, or says why it could not. `retry` tries again. Without a URL, it opens nothing.
export function usePdfDocument(url: string | undefined) {
  const authFetch = useAuthenticatedFetch();
  const [attempt, setAttempt] = useState(0);
  // What was opened, or why it could not be, and from which URL. A result from another URL is not returned.
  const [opened, setOpened] = useState<{ url?: string; pdf?: OpenedDocument; openError?: string }>({});

  // The file is fetched here rather than by PDF.js, so an expired session gets the same sign-in
  // every other request gets.
  useEffect(() => {
    if (url === undefined) {
      return undefined;
    }
    let task: PDFDocumentLoadingTask | undefined;
    const stop = new AbortController();
    void (async () => {
      try {
        const [{ pdfjs, components, worker, failed, dataUrls }, response] = await Promise.all([
          loadPdfViewer(),
          authFetch(url, { signal: stop.signal }),
        ]);
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        const data = await response.arrayBuffer();
        stop.signal.throwIfAborted();
        // PDF.js's own fonts, not the system's, stand in for the fonts a PDF names without embedding
        // them. Otherwise a phone without Times New Roman would set the text in a font with other widths.
        task = pdfjs.getDocument({ data, worker, useSystemFonts: false, ...dataUrls });
        // A worker that never started never answers, so its failure ends the wait.
        const doc = await Promise.race([ task.promise, failed ]);
        stop.signal.throwIfAborted();
        setOpened({ url, pdf: createOpenedDocument(components, doc) });
      } catch (error: unknown) {
        if (!stop.signal.aborted) {
          setOpened({ url, openError: describePdfFailure(error) ?? describeRequestFailure(error) });
        }
      }
    })();
    return () => {
      stop.abort();
      // This frees the document. The worker is shared, and PDF.js leaves a worker it was handed running.
      void task?.destroy();
      setOpened(previous => (previous.url === url ? {} : previous));
    };
  }, [url, authFetch, attempt]);

  // Opens the document again. The promise resolves at once. Until the new attempt settles, the document has
  // neither a PDF nor an error, so it shows as opening.
  const retry = useCallback((): Promise<void> => {
    setOpened({});
    setAttempt(previous => previous + 1);
    return Promise.resolve();
  }, []);

  const { pdf, openError } = opened.url === url ? opened : { pdf: undefined, openError: undefined };
  return { pdf, openError, retry };
}
