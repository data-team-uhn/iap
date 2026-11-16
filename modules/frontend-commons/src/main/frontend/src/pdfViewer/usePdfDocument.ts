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
import { useCallback, useEffect, useState } from "react";

import { useAuthenticatedFetch } from "../reLogin";
import { RequestError } from "../requestFailure";
import { loadPdfjs, type ViewerComponents } from "./pdfjsClient";
import { describeOpenFailure } from "./pdfViewerModel";

import type { PageText } from "./marksModel";
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";

export interface OpenedDocument {
  components: ViewerComponents;
  doc: PDFDocumentProxy;
  // The text of a page as PDF.js reads it for its text layer, so offsets into it are offsets into the
  // layer's text. A page PDF.js cannot read has no text. Each page is read once.
  getPageText: (pageNumber: number) => Promise<string>;
  // The same, with where its lines end, as the same object for a page every time. What is worked out
  // from a page's text can be kept by that object.
  getPageTextWithLines: (pageNumber: number) => Promise<PageText>;
}

async function readPageText(doc: PDFDocumentProxy, pageNumber: number): Promise<PageText> {
  const page: PageText = { text: "", lineEnds: [] };
  try {
    const content = await (await doc.getPage(pageNumber)).getTextContent({ disableNormalization: true });
    for (const item of content.items) {
      if ("str" in item) {
        page.text += item.str;
        if (item.hasEOL) {
          page.lineEnds.push(page.text.length);
        }
      }
    }
    return page;
  } catch {
    return { text: "", lineEnds: [] };
  }
}

function openedDocument(components: ViewerComponents, doc: PDFDocumentProxy): OpenedDocument {
  const texts = new Map<number, Promise<PageText>>();
  const getPageTextWithLines = (pageNumber: number) => {
    const text = texts.get(pageNumber) ?? readPageText(doc, pageNumber);
    texts.set(pageNumber, text);
    return text;
  };
  const getPageText = async (pageNumber: number) => (await getPageTextWithLines(pageNumber)).text;
  return { components, doc, getPageText, getPageTextWithLines };
}

// Opens the PDF, or says why it could not be opened. `retry` tries again. Without a URL, nothing is opened.
export function usePdfDocument(url: string | undefined) {
  const authFetch = useAuthenticatedFetch();
  const [attempt, setAttempt] = useState(0);
  // What was opened, from where. Another URL's, or none, is not this one's.
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
        const [{ pdfjs, components, worker, dataUrls }, response] = await Promise.all([
          loadPdfjs(),
          authFetch(url, { signal: stop.signal }),
        ]);
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        const data = await response.arrayBuffer();
        stop.signal.throwIfAborted();
        // PDF.js's own fonts, not the system's, stand in for the fonts a PDF names without embedding
        // them. A phone without Times New Roman would otherwise set the text in a font of other widths.
        task = pdfjs.getDocument({ data, worker, useSystemFonts: false, ...dataUrls });
        const doc = await task.promise;
        stop.signal.throwIfAborted();
        setOpened({ url, pdf: openedDocument(components, doc) });
      } catch (error: unknown) {
        if (!stop.signal.aborted) {
          setOpened({ url, openError: describeOpenFailure(error) });
        }
      }
    })();
    return () => {
      stop.abort();
      // The shared worker is not the task's, so this only drops the document, which is then no one's.
      void task?.destroy();
      setOpened(previous => (previous.url === url ? {} : previous));
    };
  }, [url, authFetch, attempt]);

  // The note says the document is opening again, so Retry needs no progress of its own.
  const retry = useCallback((): Promise<void> => {
    setOpened({});
    setAttempt(previous => previous + 1);
    return Promise.resolve();
  }, []);

  const { pdf, openError } = opened.url === url ? opened : { pdf: undefined, openError: undefined };
  return { pdf, openError, retry };
}
