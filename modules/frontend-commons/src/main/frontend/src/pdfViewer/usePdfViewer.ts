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
import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react";

import { useAuthenticatedFetch } from "../reLogin";
import { RequestError } from "../requestFailure";
import { centreIn, drawMarks, findMark, type PageMark } from "./markLayer";
import { MarkLocator, type Mark, type MarkKind, type MarkPlace } from "./marksModel";
import { loadPdfjs, type ViewerComponents } from "./pdfjsClient";
import { describeOpenFailure, INITIAL_VIEWER_STATE, reduceViewerState } from "./pdfViewerModel";

import type { PDFDocumentLoadingTask, PDFDocumentProxy } from "pdfjs-dist";
import type { EventBus, PDFPageView, PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

interface OpenedDocument {
  components: ViewerComponents;
  doc: PDFDocumentProxy;
}

// Where the viewer in viewerRef shows the document, once PDF.js has laid out its pages.
interface ShownDocument {
  eventBus: EventBus;
  frame: HTMLDivElement;
}

const PASSAGE = "passage";

// The text of a page as PDF.js reads it for its text layer, so offsets into it are offsets into the
// layer's text, or none for a page it cannot read.
async function readPageText(doc: PDFDocumentProxy, pageNumber: number): Promise<string> {
  try {
    const content = await (await doc.getPage(pageNumber)).getTextContent({ disableNormalization: true });
    return content.items.map(item => ("str" in item ? item.str : "")).join("");
  } catch {
    return "";
  }
}

// The marks to draw on a page, from where their passages were found.
function getPageMarks(marks: Mark[], places: Partial<Record<string, MarkPlace>>, page: number): PageMark[] {
  return marks.flatMap(({ id, kind }) => {
    const place = places[id];
    return place?.status === "found"
      ? place.spans.filter(span => span.page === page).map(({ start, end }) => ({ id, kind, start, end }))
      : [];
  });
}

// Opens the PDF once, and shows it in whichever frame it is given, with the quote marked where it is
// found, looking from the page given. Moving to another frame keeps the page, the zoom and the marks.
export function usePdfViewer(url: string, page: number | undefined, quote: string, markedBy: MarkKind) {
  const authFetch = useAuthenticatedFetch();
  const [state, dispatch] = useReducer(reduceViewerState, INITIAL_VIEWER_STATE);
  const [opened, setOpened] = useState<OpenedDocument | undefined>(undefined);
  const [frame, setFrame] = useState<HTMLDivElement | null>(null);
  const [shown, setShown] = useState<ShownDocument | undefined>(undefined);
  const marks = useMemo<Mark[]>(() => [ { id: PASSAGE, quote, page, kind: markedBy } ], [quote, page, markedBy]);
  const [found, setFound] = useState<{ marks: Mark[]; places: Partial<Record<string, MarkPlace>> } | undefined>();
  const places = useMemo(
    () => (found?.marks === marks ? found.places : new MarkLocator(marks, 0).getPlaces()),
    [found, marks],
  );
  const viewerRef = useRef<PDFViewer | undefined>(undefined);
  // Where the reader is, as PDF.js's open parameters, once the document has been shown.
  const locationRef = useRef<string | undefined>(undefined);
  // The mark to take the reader to once it is found and its page drawn, until they go elsewhere.
  const goToRef = useRef<string | undefined>(PASSAGE);
  const goToMarkRef = useRef<(() => void) | undefined>(undefined);
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
        // PDF.js's own fonts, not the system's, stand in for the fonts a PDF names without embedding
        // them. A phone without Times New Roman would otherwise set the text in a font of other widths.
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

  // Every page's text is read up front, so a passage on a page not yet drawn can be found and gone to.
  useEffect(() => {
    if (opened === undefined) {
      return undefined;
    }
    const { doc } = opened;
    const locator = new MarkLocator(marks, doc.numPages);
    const stop = new AbortController();
    void (async () => {
      let last = "";
      for (let pageNumber = 1; pageNumber <= doc.numPages; pageNumber++) {
        const text = await readPageText(doc, pageNumber);
        if (stop.signal.aborted) {
          return;
        }
        locator.addPage(pageNumber, text);
        const latest = locator.getPlaces();
        const serialized = JSON.stringify(latest);
        // Most pages change nothing, and each change draws the marks again.
        if (serialized !== last) {
          last = serialized;
          setFound({ marks, places: latest });
        }
      }
    })();
    return () => stop.abort();
  }, [opened, marks]);

  useEffect(() => {
    if (opened === undefined || frame === null) {
      return undefined;
    }
    const { components, doc } = opened;
    const eventBus = new components.EventBus();
    const linkService = new components.PDFLinkService({ eventBus });
    // Aborting the signal is what stops the viewer watching the frame's size and scrolling.
    const stop = new AbortController();
    const options = { container: frame, eventBus, linkService, removePageBorders: true, abortSignal: stop.signal };
    const viewer = new components.PDFViewer(options);
    viewerRef.current = viewer;
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
      setShown({ eventBus, frame });
    });
    eventBus.on("updateviewarea", ({ location }: { location: { pdfOpenParams: string } }) => {
      locationRef.current = location.pdfOpenParams.replace(/^#/, "");
    });
    eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
      dispatch({ type: "paged", page: pageNumber });
    });

    // PDF.js fits the page to the frame's width only when told to. When the frame's width changes, the
    // page is fitted again, unless the reader chose a zoom of their own. The width is the frame's outer
    // one, which a scrollbar coming and going with the fit does not change.
    let width = frame.offsetWidth;
    let fitting = 0;
    const resizes = new ResizeObserver(() => {
      cancelAnimationFrame(fitting);
      fitting = requestAnimationFrame(() => {
        if (frame.offsetWidth !== width && viewer.currentScaleValue === "page-width") {
          viewer.currentScaleValue = "page-width";
        }
        width = frame.offsetWidth;
      });
    });
    resizes.observe(frame, { box: "border-box" });

    viewer.setDocument(doc);
    linkService.setDocument(doc);
    return () => {
      resizes.disconnect();
      cancelAnimationFrame(fitting);
      stop.abort();
      viewer.setDocument(null);
      viewerRef.current = undefined;
      setShown(undefined);
    };
  }, [opened, frame, page]);

  // The marks are drawn on each page as PDF.js draws its text, and again as passages are found.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (shown === undefined || viewer === undefined) {
      return undefined;
    }
    const { eventBus, frame: shownIn } = shown;
    const draw = (pageView: PDFPageView, pageNumber: number) => {
      const textLayer = pageView.textLayer?.div;
      if (textLayer !== undefined) {
        drawMarks(textLayer, getPageMarks(marks, places, pageNumber), PASSAGE);
      }
    };
    const goToMark = () => {
      const id = goToRef.current;
      const place = id === undefined ? undefined : places[id];
      if (id === undefined || place?.status !== "found") {
        return;
      }
      if (viewer.currentPageNumber !== place.page) {
        viewer.currentPageNumber = place.page;
      }
      const mark = findMark(shownIn, id);
      if (mark !== undefined) {
        goToRef.current = undefined;
        centreIn(shownIn, mark);
      }
    };
    const stop = new AbortController();
    eventBus.on("textlayerrendered", ({ source, pageNumber }: { source: PDFPageView; pageNumber: number }) => {
      draw(source, pageNumber);
      goToMark();
    }, { signal: stop.signal });
    for (let index = 0; index < viewer.pagesCount; index++) {
      draw(viewer.getPageView(index) as PDFPageView, index + 1);
    }
    goToMark();
    goToMarkRef.current = goToMark;
    return () => {
      stop.abort();
      goToMarkRef.current = undefined;
    };
  }, [shown, marks, places]);

  // The controls act on the viewer shown, and are disabled until there is one.
  const withViewer = useCallback((act: (viewer: PDFViewer) => void) => {
    if (viewerRef.current !== undefined) {
      act(viewerRef.current);
    }
  }, []);

  // Going to a page by hand stops finding the passage from moving the reader.
  const showPage = useCallback((pageNumber: number) => {
    goToRef.current = undefined;
    withViewer(viewer => {
      viewer.currentPageNumber = pageNumber;
    });
  }, [withViewer]);

  const showPassage = useCallback(() => {
    goToRef.current = PASSAGE;
    goToMarkRef.current?.();
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

  const place: MarkPlace = places[PASSAGE] ?? { status: "searching" };
  return { state, place, setFrame, showPage, showPassage, zoomIn, zoomOut, fitWidth, retry };
}
