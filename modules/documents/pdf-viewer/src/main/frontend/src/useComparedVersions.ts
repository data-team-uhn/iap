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
// The older version shown beside this one: its view, and what keeps the two versions together as the reader
// moves through either.
import { useCallback, useEffect, useRef } from "react";

import { NO_MARKS } from "./marksModel";
import { useMarkPlaces } from "./useMarkPlaces";
import { usePdfView, type PdfView } from "./usePdfView";
import { useScrollSync } from "./useScrollSync";

import type { Comparing } from "./useComparison";

// Returns the older version's view, which scrolls along with this version's, and this version's view with its
// search, zoom, page and mark controls changed to move both versions. Without a comparison, this version's
// view is returned unchanged.
//
// Both versions are searched and zoomed together. A search, and each step to the next or previous match,
// takes each version to its own match. The two then move apart until the next scroll by hand. Text only the
// older version has can be found that way too. After a zoom, or a page chosen from either version's page
// controls, the other version follows.
export function useComparedVersions(
  comparing: Comparing | undefined,
  view: PdfView,
  { selectedId, select, changeIds }: {
    selectedId: string | undefined;
    // Selects a mark, as if the person had chosen it.
    select: (id: string) => void;
    // The ids of the marks that are changes.
    changeIds: Set<string>;
  },
) {
  const olderMarks = comparing?.olderMarks ?? NO_MARKS;
  const { places: olderPlaces } = useMarkPlaces(comparing?.older.pdf, olderMarks);
  const olderView = usePdfView(comparing?.older.pdf, {
    page: undefined,
    marks: olderMarks,
    places: olderPlaces,
    selectedId,
    onSelectMark: id => {
      select(id);
      release();
      view.showMark(id);
    },
  });
  const alignment = comparing?.state.status === "compared" ? comparing.state.alignment : undefined;
  const { lead, release } = useScrollSync({ older: olderView, newer: view }, alignment);
  const { showMark: showNewerMark } = view;
  const { showMark: showOlderMark } = olderView;

  // Takes both versions to a change. For a mark of the caller's, which only this version has, the older
  // version follows this one.
  const showMark = useCallback((id: string) => {
    showNewerMark(id);
    if (changeIds.has(id)) {
      release();
      showOlderMark(id);
    } else {
      lead("newer");
    }
  }, [changeIds, showNewerMark, showOlderMark, release, lead]);

  // Brings a change the person selected in this version into view in the older one too. For any other mark,
  // which the older version does not have, the older version follows this one.
  const followSelection = (id: string) => {
    if (comparing === undefined) {
      return;
    }
    if (changeIds.has(id)) {
      release();
      showOlderMark(id);
    } else {
      lead("newer");
    }
  };

  // When the older version is shown again beside this one, it takes the zoom this one was given while the
  // older one was hidden.
  const { shown: olderShown, zoomTo: zoomOlderTo } = olderView;
  const { getScaleValue } = view;
  useEffect(() => {
    const scale = getScaleValue();
    if (olderShown !== undefined && scale !== undefined) {
      zoomOlderTo(scale);
      lead("newer");
    }
  }, [olderShown, getScaleValue, zoomOlderTo, lead]);

  // Once the versions are compared, the older one comes to where this one is. On the first comparison it
  // does not when a change is selected, since both versions are already on their way to it.
  const comparedRef = useRef(false);
  const selectedChangeRef = useRef(false);
  useEffect(() => {
    selectedChangeRef.current = selectedId !== undefined && changeIds.has(selectedId);
  });
  useEffect(() => {
    if (alignment !== undefined) {
      if (comparedRef.current || !selectedChangeRef.current) {
        lead("newer");
      }
      comparedRef.current = true;
    }
  }, [alignment, lead]);

  // Zooms both versions, then has the older one follow this one.
  const zoomBoth = (zoom: (each: PdfView) => void) => {
    [ view, olderView ].forEach(zoom);
    lead("newer");
  };
  const newerView: PdfView = comparing === undefined
    ? view
    : {
      ...view,
      find: query => {
        release();
        view.find(query);
        olderView.find(query);
      },
      findNext: () => {
        release();
        view.findNext();
        olderView.findNext();
      },
      findPrevious: () => {
        release();
        view.findPrevious();
        olderView.findPrevious();
      },
      zoomIn: () => zoomBoth(each => each.zoomIn()),
      zoomOut: () => zoomBoth(each => each.zoomOut()),
      fitWidth: () => zoomBoth(each => each.fitWidth()),
      showPage: page => {
        view.showPage(page);
        lead("newer");
      },
      showMark,
    };
  // The older version's own page controls take it to a page, and this version follows.
  const shownOlderView: PdfView = {
    ...olderView,
    showPage: page => {
      olderView.showPage(page);
      lead("older");
    },
  };

  return { newerView, olderView: shownOlderView, showMark, followSelection, lead };
}
