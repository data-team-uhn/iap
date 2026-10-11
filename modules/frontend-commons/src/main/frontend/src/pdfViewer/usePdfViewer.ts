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
import { useCallback, useEffect, useReducer, useRef, useState } from "react";

import { useAuthenticatedFetch } from "../reLogin";
import { RequestError } from "../requestFailure";
import { createQuoteFinder, loadPdfjs, type ViewerComponents } from "./pdfjsClient";
import { createViewerState, describeOpenFailure, reduceViewerState } from "./pdfViewerModel";
import { getSearchText } from "./quoteMatch";

import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";
import type { PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

interface OpenedDocument {
  components: ViewerComponents;
  doc: PDFDocumentProxy;
}

// Opens the PDF once, and shows it in whichever frame it is given, looking for the quote from the
// page given. Moving to another frame keeps the page, the zoom and the marks.
export function usePdfViewer(url: string, page: number | undefined, quote: string) {
  const authFetch = useAuthenticatedFetch();
  const searchText = getSearchText(quote);
  const [state, dispatch] = useReducer(reduceViewerState, searchText !== undefined, createViewerState);
  const [opened, setOpened] = useState<OpenedDocument | undefined>(undefined);
  const [frame, setFrame] = useState<HTMLDivElement | null>(null);
  const viewerRef = useRef<PDFViewer | undefined>(undefined);
  const showPassageRef = useRef<(() => void) | undefined>(undefined);
  // Where the reader is, as PDF.js's open parameters, once the document has been shown.
  const locationRef = useRef<string | undefined>(undefined);
  // Whether finding the quote may still move the reader to it.
  const followRef = useRef(true);
  const { attempt } = state;

  // The file is fetched here rather than by PDF.js, so an expired session gets the same sign-in
  // every other request gets.
  useEffect(() => {
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
        // PDF.js's own fonts rather than the system's, for the fonts a PDF names without embedding them:
        // a phone without Times New Roman would otherwise set the text in a font of other widths.
        task = pdfjs.getDocument({ data, worker, useSystemFonts: false, ...dataUrls });
        const doc = await task.promise;
        stop.signal.throwIfAborted();
        setOpened({ components, doc });
      } catch (error: unknown) {
        if (!stop.signal.aborted) {
          dispatch({ type: "openFailed", message: describeOpenFailure(error) });
        }
      }
    })();
    return () => {
      stop.abort();
      // The shared worker is not the task's, so this only drops the document.
      void task?.destroy();
    };
  }, [url, authFetch, attempt]);

  useEffect(() => {
    if (opened === undefined || frame === null) {
      return undefined;
    }
    const { components, doc } = opened;
    const { FOUND, NOT_FOUND, WRAPPED } = components.FindState;
    const eventBus = new components.EventBus();
    const linkService = new components.PDFLinkService({ eventBus });
    // Aborting the signal is what stops the viewer watching the frame's size and scrolling.
    const stop = new AbortController();
    const options = { container: frame, eventBus, linkService, removePageBorders: true, abortSignal: stop.signal };
    const viewer = new components.PDFViewer(options);
    const findController = createQuoteFinder(components, {
      container: frame,
      viewer,
      doc,
      eventBus,
      follow: () => followRef.current,
    });
    viewer.findController = findController;
    viewerRef.current = viewer;
    // PDF.js scrolls to the match it found when that page's marks are drawn again, which this asks for.
    showPassageRef.current = () => {
      const { selected } = findController;
      if (selected === undefined || selected.pageIdx < 0) {
        return;
      }
      followRef.current = true;
      findController._scrollMatches = true;
      viewer.currentPageNumber = selected.pageIdx + 1;
      eventBus.dispatch("updatetextlayermatches", { source: findController, pageIndex: selected.pageIdx });
    };
    linkService.setViewer(viewer);

    eventBus.on("pagesinit", () => {
      if (locationRef.current === undefined) {
        viewer.currentScaleValue = "page-width";
        const first = page !== undefined && page <= doc.numPages ? page : 1;
        viewer.currentPageNumber = first;
        dispatch({ type: "opened", pageCount: doc.numPages, page: first });
      } else {
        linkService.setHash(locationRef.current);
      }
      if (searchText !== undefined) {
        eventBus.dispatch("find", {
          source: null,
          type: "",
          query: quote,
          caseSensitive: false,
          entireWord: false,
          highlightAll: true,
          findPrevious: false,
          matchDiacritics: false,
        });
      }
    });
    eventBus.on("updateviewarea", ({ location }: { location: { pdfOpenParams: string } }) => {
      locationRef.current = location.pdfOpenParams.replace(/^#/, "");
    });
    eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
      dispatch({ type: "paged", page: pageNumber });
    });
    eventBus.on("updatefindcontrolstate", ({ state: findState }: { state: number }) => {
      const { selected } = findController;
      if ((findState === FOUND || findState === WRAPPED) && selected !== undefined) {
        dispatch({ type: "searched", search: { status: "found", page: selected.pageIdx + 1 } });
      } else if (findState === NOT_FOUND) {
        dispatch({ type: "searched", search: { status: findController.hasText ? "notFound" : "noText" } });
      }
    });

    viewer.setDocument(doc);
    linkService.setDocument(doc);
    return () => {
      // Shown again elsewhere, the reader stays where they were rather than being taken to the quote.
      if (locationRef.current !== undefined) {
        followRef.current = false;
      }
      stop.abort();
      viewer.setDocument(null);
      viewerRef.current = undefined;
      showPassageRef.current = undefined;
    };
  }, [opened, frame, page, quote, searchText]);

  // The controls act on the viewer shown, and are disabled until there is one.
  const withViewer = useCallback((act: (viewer: PDFViewer) => void) => {
    if (viewerRef.current !== undefined) {
      act(viewerRef.current);
    }
  }, []);

  // Going to a page by hand stops the search from moving the reader.
  const showPage = useCallback((pageNumber: number) => {
    followRef.current = false;
    withViewer(viewer => {
      viewer.currentPageNumber = pageNumber;
    });
  }, [withViewer]);

  const showPassage = useCallback(() => {
    showPassageRef.current?.();
  }, []);

  const zoomIn = useCallback(() => withViewer(viewer => viewer.increaseScale()), [withViewer]);

  const zoomOut = useCallback(() => withViewer(viewer => viewer.decreaseScale()), [withViewer]);

  const fitWidth = useCallback(() => withViewer(viewer => {
    viewer.currentScaleValue = "page-width";
  }), [withViewer]);

  // The note says the document is opening again, so Retry needs no progress of its own.
  const retry = useCallback((): Promise<void> => {
    dispatch({ type: "retry" });
    return Promise.resolve();
  }, []);

  return { state, setFrame, showPage, showPassage, zoomIn, zoomOut, fitWidth, retry };
}
