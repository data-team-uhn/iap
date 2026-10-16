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
import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import { Box } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import CommentButton from "./CommentButton";
import MarkList from "./MarkList";
import { cannotMark, getMarkPage, getMarksKey, getMarkText, isLooking, orderMarks } from "./marksModel";
import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import { PageControls } from "./PdfToolbar";
import { getFileName, getPassageNote, getPassageStatus, PASSAGES, type MarkName } from "./pdfViewerModel";
import StepControls from "./StepControls";
import { getTextAnchor } from "./textAnchor";
import { useMarkPlaces, type MarkPlaces } from "./useMarkPlaces";
import { useNarrowViewer } from "./useNarrowViewer";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import { useSelectedText } from "./useSelectedText";
import ViewerPlacement, { ViewerTitle } from "./ViewerPlacement";
import ViewerToolbar, { type ViewerToolbarHandle } from "./ViewerToolbar";
import LoadError from "../components/LoadError";
import { useSwipe } from "../useSwipe";

import type { Mark, MarkSpan, TextAnchor } from "./marksModel";

export interface PdfViewerProps {
  // The PDF to open.
  url: string;
  // The page to open it at: the selected mark's own page when absent, or else the first.
  page?: number;
  // The passages to mark, in the order to list them. A quote is looked for from its own page onwards,
  // and spans are placed where they say. New marks equal to the last ones are not looked for again.
  marks: Mark[];
  // The selected mark. The viewer takes the reader to it once it is found, and again each time the
  // caller selects another. When absent or not among the marks, the viewer selects the first mark not
  // known to be missing from the document.
  selectedMarkId?: string;
  // Called with the mark the person selects, in the document or in the list of passages.
  onSelectMark?: (id: string) => void;
  // Told the passage the person selected in the document, when they press the Comment button the viewer
  // offers under it. Without it, there is no Comment button.
  onAnnotate?: (anchor: TextAnchor) => void;
  // What to show under a passage, such as the comments on it, given the mark and whether it is selected.
  // Give the same function on every render, such as one from useCallback, or the passages are drawn again
  // each time a search counts its matches.
  renderMarkDetails?: (mark: Mark, selected: boolean) => ReactNode;
  // What the marks are called where the viewer counts and steps through them, such as comments on a review
  // screen ("Comment 3 of 12"). Passages when absent. The text a mark covers is still called a passage.
  markName?: MarkName;
  // What the viewer is called; the PDF's file name when absent.
  title?: string;
  // An element for the viewer to fill, with a button to pop it out into a dialog. The element needs a
  // height of its own. Without a container, the viewer opens in a dialog. Null, as a ref is before
  // React attaches it, shows nothing until the element is there.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Opens a PDF, finds the passages in it and marks them. The document still opens when a passage
// cannot be found. One passage is shown above the document; several are listed beside it, or, in a
// viewer too narrow for that, shown one at a time above it.
export default function PdfViewer(props: PdfViewerProps) {
  // Keyed so another document starts over with nothing left from the last one.
  return <PdfViewerSession key={`${props.url}\n${props.page}`} {...props} />;
}

// The document stays open here while the viewer moves between the container and the dialog.
function PdfViewerSession(props: PdfViewerProps) {
  const { url, page, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, title, container, onClose } = props;
  const markName = props.markName ?? PASSAGES;
  const marksKey = getMarksKey(props.marks);
  const marks = useMemo(() => JSON.parse(marksKey) as Mark[], [marksKey]);
  const [chosenId, setChosenId] = useState<string | undefined>(undefined);
  // The mark last reported to the caller. The caller selecting it in turn does not move the reader.
  const reportedId = useRef<string | undefined>(undefined);
  // The mark the caller last selected. A change to the marks does not move the reader to it again.
  const followedId = useRef<string | undefined>(undefined);
  const { pdf, openError, retry } = usePdfDocument(url);
  const { places, read } = useMarkPlaces(pdf, marks);
  const firstMarkable = marks.find(mark => !cannotMark(places[mark.id]));
  const selectedId = [ selectedMarkId, chosenId ].find(id => marks.some(mark => mark.id === id))
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
    onSelectMark: select,
  });
  const { showMark } = view;
  const selectedText = useSelectedText(view.shown, onAnnotate !== undefined);
  const { dismiss } = selectedText;
  // Clears the selection once the passage is passed on, so the mark the caller makes of it shows.
  const annotate = pdf === undefined || onAnnotate === undefined
    ? undefined
    : async (spans: MarkSpan[]) => {
      const pages = await Promise.all(spans.map(span => pdf.getPageTextWithLines(span.page)));
      onAnnotate(getTextAnchor(spans, page => pages[spans.findIndex(span => span.page === page)]));
      dismiss();
      document.getSelection()?.removeAllRanges();
    };
  useEffect(() => {
    if (selectedMarkId === followedId.current || !marks.some(mark => mark.id === selectedMarkId)) {
      return;
    }
    followedId.current = selectedMarkId;
    if (selectedMarkId !== undefined && selectedMarkId !== reportedId.current) {
      showMark(selectedMarkId);
    }
  }, [selectedMarkId, marks, showMark]);
  const titleId = useId();

  return (
    <ViewerPlacement container={container} titleId={titleId} onClose={onClose}>
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
          view={view}
          renderMarkDetails={renderMarkDetails}
          selectedText={selectedText}
          onAnnotate={annotate}
          placeButton={placeButton}
          edgeToEdge={inDialog}
          onClose={onClose}
        />
      )}
    </ViewerPlacement>
  );
}

// The space between the viewer's rows, kept small on a phone, where the document has the least room.
const ROW_GAP = { xs: 0.5, sm: 1 };

interface ViewerBodyProps {
  titleId: string;
  title: string;
  marks: Mark[];
  markName: MarkName;
  places: MarkPlaces;
  // How many pages have been read looking for the passages.
  pagesRead: number;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  openError: string | undefined;
  onRetry: () => Promise<void>;
  view: ReturnType<typeof usePdfView>;
  renderMarkDetails: PdfViewerProps["renderMarkDetails"];
  selectedText: ReturnType<typeof useSelectedText>;
  onAnnotate: ((spans: MarkSpan[]) => Promise<void>) | undefined;
  // Moves the viewer between its container and the dialog, when it has a container.
  placeButton: ReactNode;
  // In the dialog on a phone, the document spans the full screen width, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

function ViewerBody(props: ViewerBodyProps) {
  const { titleId, title, marks, markName, places, pagesRead, selectedId, onSelect, openError, onRetry, view } = props;
  const { renderMarkDetails, selectedText, onAnnotate, placeButton, edgeToEdge, onClose } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;
  const { search, find, findAgain } = view;
  const [body, setBody] = useState<HTMLElement | null>(null);
  const narrow = useNarrowViewer(body);
  const toolbarRef = useRef<ViewerToolbarHandle>(null);
  // Ctrl+F, or Cmd+F, searches the document while focus is in the viewer, rather than the page. Escape
  // hides the Comment button before it closes anything.
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && selectedText.selected !== undefined) {
      event.stopPropagation();
      selectedText.dismiss();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f" && pageCount !== undefined) {
      event.preventDefault();
      toolbarRef.current?.openSearch();
    }
  };
  const ordered = useMemo(() => orderMarks(marks, places), [marks, places]);
  const position = ordered.findIndex(mark => mark.id === selectedId);
  const selected = position < 0 ? undefined : ordered[position];
  const several = ordered.length > 1;
  const among = { index: position + 1, count: ordered.length };
  // The same object while the error and the page stay the same.
  const status = useMemo(() => ({ openError, page }), [openError, page]);
  const showSelected = useCallback((id: string) => {
    onSelect(id);
    showMark(id);
  }, [onSelect, showMark]);
  const stepTo = (index: number) => showSelected(ordered[index].id);
  // Swiping the card above the document steps through the passages, as far as the ends.
  const swipeRef = useSwipe(
    position > 0 ? () => stepTo(position - 1) : undefined,
    position < ordered.length - 1 ? () => stepTo(position + 1) : undefined,
  );

  const card = selected === undefined || (several && !narrow)
    ? null
    : (
      <PassageCard
        key={selected.id}
        ref={several ? swipeRef : undefined}
        name={markName}
        quote={getMarkText(selected, places[selected.id])}
        note={getPassageNote(status, places[selected.id])}
        markedBy={selected.kind}
        stepper={several
          ? (
            <StepControls
              position={among.index}
              count={among.count}
              itemName={markName.one}
              onStep={next => stepTo(next - 1)}
            />
          )
          : undefined}
        details={renderMarkDetails?.(selected, true)}
        onShowPassage={() => showMark(selected.id)}
      />
    );

  return (
    <Box
      ref={setBody}
      onKeyDown={onKeyDown}
      sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minHeight: 0 }}
    >
      <ViewerTitle titleId={titleId} title={title} placeButton={placeButton} onClose={onClose} />
      {card}
      {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
      <Box role="status" sx={visuallyHidden}>
        {search.outcome === "none" && selected !== undefined
          ? getPassageStatus(status, places[selected.id], several ? among : undefined, markName)
          : search.announcement}
      </Box>
      <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minWidth: 0 }}>
          <ViewerToolbar
            ref={toolbarRef}
            pages={<PageControls page={page} pageCount={pageCount} onShowPage={showPage} />}
            opened={pageCount !== undefined}
            search={search}
            onFind={find}
            onFindAgain={findAgain}
            onZoomIn={zoomIn}
            onZoomOut={zoomOut}
            onFitWidth={fitWidth}
          />
          {openError === undefined
            ? null
            : (
              <LoadError
                title="The document could not be opened"
                message={openError}
                onRetry={onRetry}
              />
            )}
          <PdfDocumentView
            setFrame={setFrame}
            edgeToEdge={edgeToEdge}
            outlineSelected={several}
            opening={pageCount === undefined && openError === undefined}
            looking={pageCount !== undefined && isLooking(places)
              ? { share: pagesRead / pageCount, what: `the ${several ? markName.many : markName.one}` }
              : undefined}
          >
            {selectedText.selected === undefined || onAnnotate === undefined
              ? null
              : (
                <CommentButton
                  at={selectedText.selected}
                  onPress={selectedText.hold}
                  onComment={spans => void onAnnotate(spans)}
                />
              )}
          </PdfDocumentView>
        </Box>
        {several && !narrow
          ? (
            <MarkList
              marks={ordered}
              name={markName}
              places={places}
              status={status}
              selectedId={selected?.id}
              renderDetails={renderMarkDetails}
              onSelect={showSelected}
            />
          )
          : null}
      </Box>
    </Box>
  );
}
