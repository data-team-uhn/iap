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
import { useCallback, useEffect, useRef, useState } from "react";

import { centreIn, drawMarks, findMark, markAt, OVER_MARK_CLASS, type PageMark } from "./markLayer";

import type { Mark } from "./marksModel";
import type { MarkPlaces } from "./useMarkPlaces";
import type { OpenedDocument } from "./usePdfDocument";
import type { EventBus, PDFPageView, PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

// Where the viewer in viewerRef shows the document, once PDF.js has laid out its pages.
interface ShownDocument {
  eventBus: EventBus;
  frame: HTMLDivElement;
}

// How far the pointer may move between pressing and releasing for it to be a click on a mark, rather
// than a drag selecting text that happened to select nothing.
const DRAG_DISTANCE = 4;

// The marks to draw on a page, from where their passages were found.
function getPageMarks(marks: Mark[], places: MarkPlaces, page: number): PageMark[] {
  return marks.flatMap(({ id, kind }) => {
    const place = places[id];
    return place.status === "found"
      ? place.spans.filter(span => span.page === page).map(({ start, end }) => ({ id, kind, start, end }))
      : [];
  });
}

// Shows the document in whichever frame it is given, opened at the page given, with the marks drawn
// where they were found, taking the reader to the selected one once it is. Clicking a mark selects it.
// Moving to another frame keeps the page, the zoom and the marks.
export function usePdfView(
  pdf: OpenedDocument | undefined,
  { page, marks, places, selectedId, onSelectMark }: {
    page: number | undefined;
    marks: Mark[];
    places: MarkPlaces;
    selectedId: string | undefined;
    onSelectMark: (id: string) => void;
  },
) {
  const [frame, setFrame] = useState<HTMLDivElement | null>(null);
  // The page given when the view starts, which a later one, as the selection changes, does not move to.
  const [openAt] = useState(page);
  const [shown, setShown] = useState<ShownDocument | undefined>(undefined);
  const [pages, setPages] = useState<{ count?: number; shown?: number }>({});
  const viewerRef = useRef<PDFViewer | undefined>(undefined);
  // Where the reader is, as PDF.js's open parameters, once the document has been shown.
  const locationRef = useRef<string | undefined>(undefined);
  // The mark to take the reader to once it is found and its page drawn, until they go elsewhere: the
  // selected one at first, even as which one that is changes.
  const goToRef = useRef<string | undefined>(undefined);
  const followSelectedRef = useRef(true);
  const goToMarkRef = useRef<(() => void) | undefined>(undefined);
  const onSelectMarkRef = useRef(onSelectMark);
  useEffect(() => {
    onSelectMarkRef.current = onSelectMark;
  }, [onSelectMark]);

  useEffect(() => {
    if (pdf === undefined || frame === null) {
      return undefined;
    }
    const { components, doc } = pdf;
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
        const first = openAt !== undefined && openAt <= doc.numPages ? openAt : 1;
        viewer.currentPageNumber = first;
        setPages({ count: doc.numPages, shown: first });
      } else {
        linkService.setHash(locationRef.current);
      }
      setShown({ eventBus, frame });
    });
    eventBus.on("updateviewarea", ({ location }: { location: { pdfOpenParams: string } }) => {
      locationRef.current = location.pdfOpenParams.replace(/^#/, "");
    });
    eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
      setPages({ count: doc.numPages, shown: pageNumber });
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

    let pressedAt = { x: 0, y: 0 };
    frame.addEventListener("pointerdown", event => {
      pressedAt = { x: event.clientX, y: event.clientY };
    }, { signal: stop.signal });
    frame.addEventListener("click", event => {
      const moved = Math.hypot(event.clientX - pressedAt.x, event.clientY - pressedAt.y) > DRAG_DISTANCE;
      const id = moved || window.getSelection()?.isCollapsed === false
        ? undefined
        : markAt(frame, event.clientX, event.clientY);
      if (id !== undefined) {
        onSelectMarkRef.current(id);
      }
    }, { signal: stop.signal });
    frame.addEventListener("pointermove", event => {
      frame.classList.toggle(OVER_MARK_CLASS, markAt(frame, event.clientX, event.clientY) !== undefined);
    }, { signal: stop.signal });

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
  }, [pdf, frame, openAt]);

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
        drawMarks(textLayer, getPageMarks(marks, places, pageNumber), selectedId);
      }
    };
    const goToMark = () => {
      const id = goToRef.current ?? (followSelectedRef.current ? selectedId : undefined);
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
        followSelectedRef.current = false;
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
  }, [shown, marks, places, selectedId]);

  // The controls act on the viewer shown, and are disabled until there is one.
  const withViewer = useCallback((act: (viewer: PDFViewer) => void) => {
    if (viewerRef.current !== undefined) {
      act(viewerRef.current);
    }
  }, []);

  // Going to a page by hand stops finding a mark from moving the reader.
  const showPage = useCallback((pageNumber: number) => {
    goToRef.current = undefined;
    followSelectedRef.current = false;
    withViewer(viewer => {
      viewer.currentPageNumber = pageNumber;
    });
  }, [withViewer]);

  const showMark = useCallback((id: string) => {
    goToRef.current = id;
    goToMarkRef.current?.();
  }, []);

  const zoomIn = useCallback(() => withViewer(viewer => viewer.increaseScale()), [withViewer]);

  const zoomOut = useCallback(() => withViewer(viewer => viewer.decreaseScale()), [withViewer]);

  const fitWidth = useCallback(() => withViewer(viewer => {
    viewer.currentScaleValue = "page-width";
  }), [withViewer]);

  return { pageCount: pages.count, page: pages.shown, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth };
}
