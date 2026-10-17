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
  // The passages to mark, in the order to list them: quotes, each found from its own page onwards, or
  // spans, placed where they say. Marks equal to the last ones are not looked for again, even as other
  // objects.
  marks: Mark[];
  // The mark selected, which the viewer takes the reader to once it is found, as it does each time the
  // caller selects another. When absent, or not among the marks, it is the first, in the order given,
  // not known to be missing from the document, until the person selects another.
  selectedMarkId?: string;
  // Told which mark the person selects, in the document or among the passages.
  onSelectMark?: (id: string) => void;
  // Told which passage the person selected to comment on, with the button the viewer then offers beside
  // the text they selected in the document. Without it, the viewer offers none.
  onAnnotate?: (anchor: TextAnchor) => void;
  // What to show with a passage, under it, such as the comments on it, given whether it is the one
  // selected. The same function from one render to the next, as from useCallback, spares drawing the
  // passages again while the document is searched.
  renderMarkDetails?: (mark: Mark, selected: boolean) => ReactNode;
  // What the marks are called where the viewer counts them and steps through them, as in "Passage 3 of 12"
  // or "Next passage", such as annotations on a review screen; passages when absent. The text a mark
  // covers is a passage still.
  markName?: MarkName;
  // What the viewer is called; the PDF's file name when absent.
  title?: string;
  // An element to show the viewer in, filling it, with a button to pop it out into a dialog. The
  // element needs a height of its own. Without one, the viewer opens in a dialog. Null, as a ref is
  // before React attaches it, shows nothing until the element is there.
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
  // The mark last reported to the caller, whose selecting it in turn does not move the reader, and the
  // last the caller selected, which marks changing do not move the reader to again.
  const reportedId = useRef<string | undefined>(undefined);
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
  // The selection is let go of once kept, so the mark the caller makes of it shows.
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
  // puts away the offer to comment on the text selected before it closes anything.
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
  // Kept the same while they are, so the document and the passages are not drawn again as a search counts.
  const status = useMemo(() => ({ openError, page }), [openError, page]);
  const showSelected = useCallback((id: string) => {
    onSelect(id);
    showMark(id);
  }, [onSelect, showMark]);
  const stepTo = (index: number) => showSelected(ordered[index].id);
  // Swiping the card above the document steps through the passages as its controls do, as far as the ends.
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
