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
// The PDF viewer: it opens a document, marks passages in it, and can compare it with an older version.
// The work is done by hooks. This file holds the session's state, joins the hooks, and hands what they give
// to ViewerBody, which lays it out.
import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";

import { getMarkPage, getMarksKey, isUnmarkable, NO_MARKS } from "./marksModel";
import { getFileName, PASSAGES, type DocumentSearch, type MarkName } from "./pdfViewerModel";
import { PlainViewer } from "./plainViewer";
import { getNearestShown } from "./revisionDiffModel";
import { getTextAnchor } from "./textAnchor";
import { useComparedVersions } from "./useComparedVersions";
import { useComparison } from "./useComparison";
import { useMarkPlaces } from "./useMarkPlaces";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import { useSelectedText } from "./useSelectedText";
import ViewerBody from "./ViewerBody";
import ViewerPlacement from "./ViewerPlacement";

import type { Mark, MarkSpan, TextAnchor } from "./marksModel";

export interface PdfViewerProps {
  // The URL of the PDF to open.
  url: string;
  // The page to open at, counting from 1. When absent, the document opens at the selected mark's page, if
  // it has one, or else at the first page. A different page given later takes the reader there.
  page?: number;
  // The passages to mark, in the order to list them. Those that cannot be marked are listed last. A quote
  // is searched for from its own page to the end, then from the first page. Spans are placed where they
  // say. New marks equal to the last ones given are not searched for again. An id names the same mark from
  // one render to the next, and the person's choice of a mark is kept by its id.
  marks: Mark[];
  // The id of the selected mark. The viewer takes the reader to it once it is found, and again each time
  // the caller selects another. When it is absent or not among the marks, the mark the person last chose
  // stays selected. Until they choose one, it is the first mark not yet known to be impossible to mark.
  selectedMarkId?: string;
  // Called with the id of each mark the person selects, in the document or beside it.
  onSelectMark?: (id: string) => void;
  // Called with the anchor of the text the person selected in the document, when they press the Comment
  // button under it. Without it, the viewer offers no Comment button.
  onAnnotate?: (anchor: TextAnchor) => void;
  // PRETOTYPE: what the button under a selected passage says; Comment when absent
  annotateLabel?: string;
  // What to show under a passage, such as the comments on it, given the mark and whether it is selected.
  // Give the same function on every render, such as one from useCallback, or the passages are drawn again
  // each time a search counts its matches.
  renderMarkDetails?: (mark: Mark, selected: boolean) => ReactNode;
  // PRETOTYPE: how many lines a passage not selected keeps of its quote and of its details beside the document
  condensedMarks?: (mark: Mark) => { quote: number; details?: number } | undefined;
  // What the marks are called where the viewer counts and steps through them, such as comments ("Comment
  // 3 of 12"). Passages when absent. Changes are always called changes, and counted separately. The text a
  // mark covers is still called a passage, as in "Show the passage on page 2".
  markName?: MarkName;
  // The viewer's title. When absent, it is the PDF's file name.
  title?: string;
  // An older version of the document to compare it with. The changes between them are marked, as marks of a
  // kind of their own, and listed with the passages while this version is shown alone. The label goes above
  // the older version when the two are shown side by side. When given another older version later, the
  // viewer compares with that one, and the reader stays where they are in this one.
  compareWith?: { url: string; label?: string };
  // What this version is called above it when shown beside the older one, such as "Version 3". "Newer
  // version" when absent.
  label?: string;
  // PRETOTYPE: shown plain, in a page that already names the document: without the title row and its buttons, and
  // on the page's own surface
  plain?: boolean;
  // PRETOTYPE: how wide the passages beside the document are, sized by dragging the edge between the two
  listWidth?: number;
  onListWidth?: (width: number) => void;
  // PRETOTYPE: what heads each passage beside the document in place of the page it is on
  markHeading?: (mark: Mark) => ReactNode;
  markHeadingActions?: (mark: Mark) => ReactNode;
  // PRETOTYPE: what heads the document's own column, beside the passages, such as the title of the page it is on
  lead?: ReactNode;
  // PRETOTYPE: a column of the page's own to list the passages in, however wide the viewer; null while it lists
  // another's, when the viewer lists none
  listContainer?: HTMLElement | null;
  // PRETOTYPE: searched from outside, by a search covering more than this document, instead of its own search
  // field. A new query is looked for from the page shown; each new step goes to the next match, or the previous
  // one. What the search finds is told back.
  search?: { query: string; step: number; previous: boolean };
  onSearch?: (search: DocumentSearch) => void;
  // An element for the viewer to fill, with a button to pop it out into a dialog. The element needs a
  // height of its own. Without a container, the viewer opens in a dialog. While it is null, as a ref is
  // before React attaches it, the viewer shows nothing.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Opens a PDF, finds the passages given in it, and marks them. A passage that cannot be found does not
// stop the document opening. When given an older version, the viewer also marks what changed, either beside
// the older version or on this one alone.
export default function PdfViewer(props: PdfViewerProps) {
  // Keyed by URL, so another document starts with nothing left from the last one. Another page of the same
  // document only moves the reader, who keeps the zoom and the search.
  return (
    <PlainViewer.Provider value={Boolean(props.plain)}>
      <PdfViewerSession key={props.url} {...props} />
    </PlainViewer.Provider>
  );
}

// Holds the open document and the reader's place in it above the placement, so both survive the viewer
// moving between its container and the dialog.
function PdfViewerSession(props: PdfViewerProps) {
  const { url, page, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, title, container, onClose } = props;
  const { compareWith } = props;
  const marksKey = getMarksKey(props.marks);
  const callerMarks = useMemo(() => JSON.parse(marksKey) as Mark[], [marksKey]);
  const [chosenId, setChosenId] = useState<string | undefined>(undefined);
  // The mark last reported to the caller. When the caller then selects it, the reader stays where they are.
  const reportedId = useRef<string | undefined>(undefined);
  // The mark the caller last selected. A change to the marks does not move the reader to it again.
  const followedId = useRef<string | undefined>(undefined);
  const { pdf, openError, retry } = usePdfDocument(url);
  const comparing = useComparison(compareWith?.url, pdf);
  const changes = comparing?.changes;
  const changeIds = useMemo(() => new Set(changes?.map(change => change.id)), [changes]);
  const markName = props.markName ?? PASSAGES;
  const changeMarks = comparing?.newerMarks ?? NO_MARKS;
  const marks = useMemo(
    () => (changeMarks.length === 0 ? callerMarks : [ ...callerMarks, ...changeMarks ]),
    [callerMarks, changeMarks],
  );
  const { places, read } = useMarkPlaces(pdf, marks);
  const firstMarkable = marks.find(mark => !isUnmarkable(places[mark.id]));
  // When the change the person chose is then left out as a minor change, the shown change nearest to it is
  // selected instead.
  const allChanges = comparing?.state.status === "compared" ? comparing.state.changes : undefined;
  const chosenLeftOut = chosenId !== undefined && !marks.some(mark => mark.id === chosenId);
  const nearestId = !chosenLeftOut || allChanges === undefined || changes === undefined
    ? undefined
    : getNearestShown(allChanges, changes, chosenId)?.id;
  const selectedId = [ selectedMarkId, chosenId, nearestId ].find(id => marks.some(mark => mark.id === id))
    ?? (firstMarkable ?? marks.at(0))?.id;
  const select = useCallback((id: string) => {
    reportedId.current = id;
    setChosenId(id);
    onSelectMark?.(id);
  }, [onSelectMark]);
  const selectedMark = marks.find(mark => mark.id === selectedId);
  const view = usePdfView(pdf, {
    page: page ?? (selectedMark === undefined ? undefined : getMarkPage(selectedMark)),
    marks,
    places,
    selectedId,
    onSelectMark: id => {
      select(id);
      versions.followSelection(id);
    },
  });
  const versions = useComparedVersions(comparing, view, { selectedId, select, changeIds });
  // PRETOTYPE: a search from outside, once the document is open
  const outside = props.search;
  const { onSearch } = props;
  const opened = view.pageCount !== undefined;
  const { find: findHere, findNext: findHereNext, findPrevious: findHerePrevious, search: foundHere } = view;
  // Only a query that changed is looked for: searching, even for nothing, stops taking the reader to a mark
  const searched = useRef("");
  useEffect(() => {
    if (opened && outside !== undefined && outside.query !== searched.current) {
      searched.current = outside.query;
      findHere(outside.query);
    }
  }, [ opened, outside?.query, findHere ]); // eslint-disable-line react-hooks/exhaustive-deps
  const stepped = useRef(outside?.step ?? 0);
  useEffect(() => {
    if (outside !== undefined && outside.step !== stepped.current) {
      stepped.current = outside.step;
      if (outside.previous) {
        findHerePrevious();
      } else {
        findHereNext();
      }
    }
  }, [ outside?.step, outside?.previous, findHereNext, findHerePrevious ]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    onSearch?.(foundHere);
  }, [ foundHere, onSearch ]);
  const { showMark, lead } = versions;
  const selectedText = useSelectedText(view.shown, onAnnotate !== undefined);
  const { dismiss } = selectedText;
  // Passes the selected text on as an anchor, then clears the selection so the mark the caller makes of it
  // shows.
  const annotate = pdf === undefined || onAnnotate === undefined
    ? undefined
    : async (spans: MarkSpan[]) => {
      const pages = await Promise.all(spans.map(span => pdf.getPageTextWithLines(span.page)));
      onAnnotate(getTextAnchor(spans, page => pages[spans.findIndex(span => span.page === page)]));
      dismiss();
      document.getSelection()?.removeAllRanges();
    };
  // Another page the caller asks for takes the reader there once the document is shown. The page asked
  // for first is where the document opens. Asking for no page moves nothing, and the same page asked for
  // after it moves the reader again. A mark the caller selects at the same time wins, because its effect
  // runs after this one.
  const askedPage = useRef(page);
  const { shown: newerShown, showPage: showNewerPage } = view;
  useEffect(() => {
    if (newerShown === undefined || page === askedPage.current) {
      return;
    }
    askedPage.current = page;
    if (page !== undefined) {
      showNewerPage(page);
      lead("newer");
    }
  }, [newerShown, page, showNewerPage, lead]);
  // Takes the reader to each mark the caller selects, unless it is the one the person just chose.
  useEffect(() => {
    if (selectedMarkId === followedId.current || !marks.some(mark => mark.id === selectedMarkId)) {
      return;
    }
    followedId.current = selectedMarkId;
    if (selectedMarkId !== undefined && selectedMarkId !== reportedId.current) {
      showMark(selectedMarkId);
    }
    // The report is used up, whether the caller echoed it or selected another mark. The caller selecting
    // that mark again later moves the reader to it.
    reportedId.current = undefined;
  }, [selectedMarkId, marks, showMark]);
  // The caller's details go under its own marks, not under the changes, which it does not know of.
  const details = useMemo(() => {
    if (changeIds.size === 0 || renderMarkDetails === undefined) {
      return renderMarkDetails;
    }
    return (mark: Mark, selected: boolean) => (changeIds.has(mark.id) ? undefined : renderMarkDetails(mark, selected));
  }, [changeIds, renderMarkDetails]);
  const titleId = useId();

  return (
    <ViewerPlacement
      container={container}
      width={comparing === undefined ? "lg" : "xl"}
      titleId={titleId}
      onClose={onClose}
    >
      {({ placeButton, inDialog }) => (
        <ViewerBody
          titleId={titleId}
          title={title ?? getFileName(url)}
          marks={marks}
          markName={markName}
          places={places}
          pagesRead={read}
          selectedId={selectedId}
          onSelect={select}
          openError={openError}
          onRetry={retry}
          view={versions.newerView}
          renderMarkDetails={details}
          selectedText={selectedText}
          onAnnotate={annotate}
          changeIds={changeIds}
          comparison={comparing === undefined
            ? undefined
            : { ...comparing, olderView: versions.olderView, olderLabel: compareWith?.label, newerLabel: props.label }}
          placeButton={placeButton}
          searchedOutside={outside !== undefined}
          annotateLabel={props.annotateLabel}
          condensedMarks={props.condensedMarks}
          listWidth={props.listWidth}
          onListWidth={props.onListWidth}
          markHeading={props.markHeading}
          markHeadingActions={props.markHeadingActions}
          lead={props.lead}
          listContainer={props.listContainer}
          edgeToEdge={inDialog}
          onClose={onClose}
        />
      )}
    </ViewerPlacement>
  );
}
