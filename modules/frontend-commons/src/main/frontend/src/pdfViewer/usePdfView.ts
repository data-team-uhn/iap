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

import { centreIn, drawMarks, findMark, getTextPointRect, markAt, OVER_MARK_CLASS, type PageMark } from "./markLayer";
import { createFindController } from "./pdfjsClient";
import { getSearchStatus, IDLE_SEARCH, type DocumentSearch } from "./pdfViewerModel";

import type { Mark, TextPoint } from "./marksModel";
import type { MarkPlaces } from "./useMarkPlaces";
import type { OpenedDocument } from "./usePdfDocument";
import type { EventBus, PDFPageView, PDFViewer } from "pdfjs-dist/web/pdf_viewer.mjs";

// Where the viewer in viewerRef shows the document, once PDF.js has laid out its pages.
export interface ShownDocument {
  eventBus: EventBus;
  frame: HTMLDivElement;
}

// How far the pointer may move between pressing and releasing for it to be a click on a mark, rather
// than a drag selecting text that happened to select nothing.
const DRAG_DISTANCE = 4;

interface MatchesCount {
  current: number;
  total: number;
}

// How often, while a search is still counting its matches, the count is shown, and whether PDF.js has
// searched every page checked: each time draws the viewer again.
const COUNT_INTERVAL = 250;

// Asks PDF.js's search to look for the query, or for its next or previous match, ignoring case and
// accents, and highlighting every match. An empty query clears the highlights.
function dispatchFind(eventBus: EventBus, query: string, again?: { previous: boolean }): void {
  eventBus.dispatch("find", {
    source: null,
    type: again === undefined ? "" : "again",
    query,
    caseSensitive: false,
    entireWord: false,
    highlightAll: true,
    findPrevious: again?.previous ?? false,
    matchDiacritics: false,
  });
}

async function hasText({ doc, getPageText }: OpenedDocument): Promise<boolean> {
  const texts = await Promise.all(Array.from({ length: doc.numPages }, (_, index) => getPageText(index + 1)));
  return texts.some(text => text.trim() !== "");
}

// The marks to draw on a page, from where their passages were found.
function getPageMarks(marks: Mark[], places: MarkPlaces, page: number): PageMark[] {
  return marks.flatMap(({ id, kind }) => {
    const place = places[id];
    return place.status === "found"
      ? place.spans.filter(span => span.page === page).map(span => ({
        id,
        kind: span.kind ?? kind,
        start: span.start,
        end: span.end,
      }))
      : [];
  });
}

// Shows the document in whichever frame it is given, opened at the page given, with the marks drawn
// where they were found, taking the reader to the selected one once it is. Clicking a mark selects it.
// Searches the document too. Moving to another frame keeps the page, the zoom, the marks and the search.
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
  // The place in the text to take the reader to once its page is drawn, and how far down the frame.
  const pointRef = useRef<{ point: TextPoint; atY: number } | undefined>(undefined);
  const goToPointRef = useRef<(() => void) | undefined>(undefined);
  const [search, setSearch] = useState<DocumentSearch>(IDLE_SEARCH);
  // What is searched for, which a viewer built again, in another frame, searches for too.
  const queryRef = useRef("");
  const eventBusRef = useRef<EventBus | undefined>(undefined);
  // Starts showing the matches of a new search as PDF.js counts them, or stops.
  const countRef = useRef<{ start: () => void; stop: () => void } | undefined>(undefined);
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
    eventBusRef.current = eventBus;
    linkService.setViewer(viewer);
    const findController = createFindController(components, { container: frame, linkService, eventBus });
    viewer.findController = findController;

    const { FOUND, NOT_FOUND, PENDING, WRAPPED } = components.FindState;
    // Nothing is found in a document without text either, which is worth saying apart.
    const sayIfNoText = async (query: string) => {
      if (!await hasText(pdf) && queryRef.current === query) {
        const noText = { outcome: "noText", current: 0, total: 0 } as const;
        setSearch(previous => ({ ...previous, ...noText, announcement: getSearchStatus(noText) }));
      }
    };
    // PDF.js counts a new search's matches as it searches the pages, telling each page's, which is shown
    // now and then rather than each time, until every page has been searched. It counts them as 0 of 0
    // until it has picked the one to show.
    let latestCount: MatchesCount | undefined;
    let counting = 0;
    const showCount = () => {
      // A new search is pending while PDF.js waits for the typing to stop, the last one's matches still kept.
      const searched = (findController.pageMatches ?? []).reduce((pages: number) => pages + 1, 0);
      const done = findController._dirtyMatch !== true && searched >= doc.numPages;
      if (done) {
        window.clearInterval(counting);
      }
      const count = latestCount;
      setSearch(previous => {
        const next = { ...previous, ...count, counting: !done };
        const same = next.current === previous.current && next.total === previous.total;
        if (same && next.counting === previous.counting) {
          return previous;
        }
        // A match found before its count is known is announced once the count is.
        const firstCount = previous.outcome === "found" && previous.current === 0 && next.current > 0;
        return firstCount ? { ...next, announcement: getSearchStatus(next) } : next;
      });
    };
    const stopCounting = () => {
      window.clearInterval(counting);
      latestCount = undefined;
    };
    countRef.current = {
      start: () => {
        stopCounting();
        setSearch(previous => ({ ...previous, counting: true }));
        counting = window.setInterval(showCount, COUNT_INTERVAL);
      },
      stop: stopCounting,
    };
    eventBus.on("updatefindmatchescount", ({ matchesCount }: { matchesCount: MatchesCount }) => {
      if (matchesCount.current > 0) {
        latestCount = matchesCount;
      }
    });
    eventBus.on("updatefindcontrolstate", ({ state, matchesCount }: { state: number; matchesCount: MatchesCount }) => {
      const query = queryRef.current;
      if (query.trim() === "") {
        return;
      }
      // While a search runs, the last one to finish stays shown and announced.
      if (state === PENDING) {
        return;
      }
      // PDF.js may tell a match is found before it has counted it, as 0 of 0.
      const count = matchesCount.current > 0 ? matchesCount : latestCount ?? matchesCount;
      const settled = state === FOUND || state === WRAPPED
        ? { outcome: "found", ...count } as const
        : { outcome: "notFound", current: 0, total: 0 } as const;
      setSearch(previous => ({ ...previous, ...settled, announcement: getSearchStatus(settled) }));
      if (state === NOT_FOUND) {
        void sayIfNoText(query);
      }
    });

    eventBus.on("pagesinit", () => {
      if (locationRef.current === undefined) {
        viewer.currentScaleValue = "page-width";
        const first = openAt !== undefined && openAt <= doc.numPages ? openAt : 1;
        viewer.currentPageNumber = first;
        setPages({ count: doc.numPages, shown: first });
      } else {
        linkService.setHash(locationRef.current);
      }
      if (queryRef.current.trim() !== "") {
        countRef.current?.start();
        dispatchFind(eventBus, queryRef.current);
      }
      setShown({ eventBus, frame });
    });
    eventBus.on("updateviewarea", ({ location }: { location: { pdfOpenParams: string } }) => {
      locationRef.current = location.pdfOpenParams.replace(/^#/, "");
    });
    eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
      setPages({ count: doc.numPages, shown: pageNumber });
    });

    // PDF.js fits the page to the frame's width only when told to, so a frame whose width changes, with
    // the window or the layout around it, has it fitted again, unless the reader chose a zoom of their own.
    // The width is the frame's outer one, which a scrollbar coming and going with the fit does not change.
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
      stopCounting();
      countRef.current = undefined;
      stop.abort();
      viewer.setDocument(null);
      viewerRef.current = undefined;
      eventBusRef.current = undefined;
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

  // A place in the text is taken to once its page is drawn, as PDF.js draws it.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (shown === undefined || viewer === undefined) {
      return undefined;
    }
    const { eventBus, frame: shownIn } = shown;
    const goToPoint = () => {
      const target = pointRef.current;
      if (target === undefined) {
        return;
      }
      const rect = getTextPointRect(shownIn, target.point);
      if (rect === undefined) {
        if (viewer.currentPageNumber !== target.point.page) {
          viewer.currentPageNumber = target.point.page;
        }
        return;
      }
      pointRef.current = undefined;
      shownIn.scrollTop += rect.top - shownIn.getBoundingClientRect().top - target.atY;
    };
    const stop = new AbortController();
    eventBus.on("textlayerrendered", goToPoint, { signal: stop.signal });
    goToPointRef.current = goToPoint;
    return () => {
      stop.abort();
      goToPointRef.current = undefined;
    };
  }, [shown]);

  // The controls act on the viewer shown, and are disabled until there is one.
  const withViewer = useCallback((act: (viewer: PDFViewer) => void) => {
    if (viewerRef.current !== undefined) {
      act(viewerRef.current);
    }
  }, []);

  // Going to a page by hand stops finding a mark, or a place in the text, from moving the reader.
  const showPage = useCallback((pageNumber: number) => {
    goToRef.current = undefined;
    pointRef.current = undefined;
    followSelectedRef.current = false;
    withViewer(viewer => {
      viewer.currentPageNumber = pageNumber;
    });
  }, [withViewer]);

  // Searching takes the reader from the mark they were being taken to, to the matches.
  const find = useCallback((query: string) => {
    queryRef.current = query;
    goToRef.current = undefined;
    pointRef.current = undefined;
    followSelectedRef.current = false;
    const searching = query.trim() !== "";
    setSearch(previous => (searching ? { ...previous, query } : { ...IDLE_SEARCH, query }));
    if (eventBusRef.current !== undefined) {
      if (searching) {
        countRef.current?.start();
      } else {
        countRef.current?.stop();
      }
      dispatchFind(eventBusRef.current, searching ? query : "");
    }
  }, []);

  const findAgain = useCallback((previous: boolean) => {
    if (eventBusRef.current !== undefined && queryRef.current.trim() !== "") {
      dispatchFind(eventBusRef.current, queryRef.current, { previous });
    }
  }, []);

  const showMark = useCallback((id: string) => {
    goToRef.current = id;
    pointRef.current = undefined;
    goToMarkRef.current?.();
  }, []);

  // Puts a place in the text that far down the frame: the place's page first, while it is not drawn, then
  // the place itself once it is.
  const showPoint = useCallback((point: TextPoint, atY: number) => {
    pointRef.current = { point, atY };
    goToPointRef.current?.();
  }, []);

  const zoomIn = useCallback(() => withViewer(viewer => viewer.increaseScale()), [withViewer]);

  const zoomOut = useCallback(() => withViewer(viewer => viewer.decreaseScale()), [withViewer]);

  const fitWidth = useCallback(() => withViewer(viewer => {
    viewer.currentScaleValue = "page-width";
  }), [withViewer]);

  // The zoom, as PDF.js gives and takes it: a scale, or "page-width" while the page fits the frame's width.
  const getScaleValue = useCallback(() => viewerRef.current?.currentScaleValue, []);

  const zoomTo = useCallback((scale: string) => withViewer(viewer => {
    viewer.currentScaleValue = scale;
  }), [withViewer]);

  return {
    shown,
    pageCount: pages.count,
    page: pages.shown,
    setFrame,
    showPage,
    showMark,
    showPoint,
    zoomIn,
    zoomOut,
    fitWidth,
    getScaleValue,
    zoomTo,
    search,
    find,
    findAgain,
  };
}
