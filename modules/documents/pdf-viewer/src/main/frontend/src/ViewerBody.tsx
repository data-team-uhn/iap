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
// The viewer's contents, wherever it is placed: its title, the comparison's controls, the passages and the
// versions of the document.
import { useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

import { Box, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import LoadError from "@iap/frontend-commons/components/LoadError";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";

import CommentButton from "./CommentButton";
import ComparisonControls from "./ComparisonControls";
import MarkList from "./MarkList";
import { getMarkText, isLooking } from "./marksModel";
import PageControls from "./PageControls";
import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import {
  describeAmong,
  getPassageNote,
  getPassageStatus,
  getStepNames,
  getWidestHeadings,
  type MarkName,
} from "./pdfViewerModel";
import StepControls from "./StepControls";
import { useComparisonDisplay, type ComparisonPanes, type Progress } from "./useComparisonDisplay";
import { useNarrowViewer } from "./useNarrowViewer";
import { usePassages } from "./usePassages";
import { ViewerTitle } from "./ViewerPlacement";
import ViewerToolbar, { type ViewerToolbarHandle } from "./ViewerToolbar";
import ZoomControls from "./ZoomControls";

import type { Mark, MarkSpan } from "./marksModel";
import type { MarkPlaces } from "./useMarkPlaces";
import type { PdfView } from "./usePdfView";
import type { useSelectedText } from "./useSelectedText";

// The space between the viewer's rows, smaller on a phone.
const ROW_GAP = { xs: 0.5, sm: 1 };

// The narrowest viewer, in pixels, with room for two versions side by side and the list of marks beside
// them. A narrower one shows the marks one at a time above the versions.
const SPLIT_WITH_LIST_WIDTH = 1440;

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
  view: PdfView;
  renderMarkDetails: ((mark: Mark, selected: boolean) => ReactNode) | undefined;
  selectedText: ReturnType<typeof useSelectedText>;
  onAnnotate: ((spans: MarkSpan[]) => Promise<void>) | undefined;
  comparison: ComparisonPanes | undefined;
  // The ids of the marks that are changes.
  changeIds: Set<string>;
  // The button moving the viewer between its container and the dialog, or null without a container.
  placeButton: ReactNode;
  // Whether the document spans the full width of a phone screen, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

// The viewer's contents. A single passage is on a card above the document. Several are listed beside it, or
// shown one at a time on the card where the list has no room.
export default function ViewerBody(props: ViewerBodyProps) {
  const { titleId, title, marks, markName, places, pagesRead, selectedId, onSelect, openError, onRetry, view } = props;
  const { renderMarkDetails, selectedText, onAnnotate, comparison, placeButton, edgeToEdge, onClose } = props;
  const { changeIds } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;
  // The document has opened once its pages are counted.
  const opened = pageCount !== undefined;
  const { search, find, findNext, findPrevious } = view;
  const [body, setBody] = useState<HTMLElement | null>(null);
  const narrow = useNarrowViewer(body);
  const noRoomForList = useNarrowerThan(body, SPLIT_WITH_LIST_WIDTH);
  const compared = useComparisonDisplay(comparison, { narrow, selectedId, openError });
  const { split, renderBody, describeBody } = compared;
  const passages = usePassages({
    marks, places, changeIds, changesListed: !split, markName, selectedId, onSelect, showMark,
  });
  const { listed, naming, sorts, selected, selectedNaming, cardAt, onCard, several, showSelected } = passages;
  const toolbarRef = useRef<ViewerToolbarHandle>(null);
  // Ctrl+F, or Cmd+F, searches the document while the focus is in the viewer, rather than the browser
  // searching the page. Escape hides the Comment button before it closes anything.
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && selectedText.selected !== undefined) {
      event.stopPropagation();
      selectedText.dismiss();
      return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f" && opened) {
      event.preventDefault();
      toolbarRef.current?.openSearch();
    }
  };
  const listBeside = several && !(split ? noRoomForList : narrow);
  // The same object while the error and the page stay the same.
  const status = useMemo(() => ({ openError, page }), [openError, page]);

  // What the status says: the search while there is one, or else the selected change, the selected mark or
  // the comparison, in that order.
  const searching = search.outcome !== "none";
  let said = search.announcement;
  if (!searching && compared.selectedChangeSaid !== undefined) {
    said = compared.selectedChangeSaid;
  } else if (!searching && selected !== undefined && selectedNaming !== undefined) {
    const { name, ...among } = selectedNaming;
    said = getPassageStatus(status, places[selected.id], among.count > 1 ? among : undefined, name);
  } else if (!searching && compared.described !== undefined) {
    said = compared.described.said;
  }
  // What the comparison found, once it is done. It is announced on its own, before the status, while the
  // status speaks of a selected mark.
  const speaksOfMark = !searching && (compared.selectedChangeSaid !== undefined || selected !== undefined);
  const summary = compared.found !== undefined && speaksOfMark ? compared.found.said : "";

  const cardBody = onCard === undefined ? undefined : renderBody(onCard);
  const cardNaming = onCard === undefined ? undefined : naming.get(onCard.id);
  const card = onCard === undefined || listBeside
    ? null
    : (
      <PassageCard
        key={onCard.id}
        ref={several ? passages.swipeRef : undefined}
        name={cardNaming?.name ?? markName}
        quote={cardBody === undefined ? getMarkText(onCard, places[onCard.id]) : undefined}
        body={cardBody}
        bodySaid={describeBody(onCard)}
        what={cardBody === undefined ? "passage" : "change"}
        note={getPassageNote(status, places[onCard.id], "quote" in onCard)}
        markedBy={onCard.kind}
        stepper={several
          ? (
            <StepControls
              position={cardAt + 1}
              count={listed.length}
              itemName={getStepNames(listed, naming, cardAt + 1, markName)}
              heading={sorts > 1 && cardNaming !== undefined ? describeAmong(cardNaming.name, cardNaming) : undefined}
              widest={getWidestHeadings(naming.values())}
              onStep={next => passages.stepTo(next - 1)}
            />
          )
          : undefined}
        details={renderMarkDetails?.(onCard, onCard === selected)}
        onShowPassage={() => showSelected(onCard.id)}
      />
    );
  // A bar along each version shows the progress while the versions are read and compared, or while the
  // passages are looked for.
  const looked = marks.length - changeIds.size;
  const looking: Progress | undefined = compared.progress ?? (opened && isLooking(places)
    ? { share: pagesRead / pageCount, what: `the ${looked > 1 ? markName.many : markName.one}` }
    : undefined);

  // Side by side, each version is named after its label, so that the two can be told apart.
  const olderName = compared.older?.label ?? "Older version";
  const newerName = compared.newerLabel ?? "Newer version";
  const shownDocument = (
    <PdfDocumentView
      setFrame={setFrame}
      name={split ? newerName : "Document"}
      edgeToEdge={edgeToEdge && !split}
      outlineSelected={marks.length > 1}
      opening={!opened && openError === undefined}
      looking={opened ? looking : undefined}
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
  );
  const { older } = compared;

  return (
    <Box
      ref={setBody}
      onKeyDown={onKeyDown}
      sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minHeight: 0 }}
    >
      <ViewerTitle
        titleId={titleId}
        title={title}
        placeButton={placeButton}
        onClose={onClose}
      />
      {compared.controls === undefined
        ? null
        : <ComparisonControls {...compared.controls} onShowChange={showSelected} />}
      {card}
      {/* Always rendered, even empty. A screen reader announces only changes to a live region already there. */}
      <Box aria-live="polite" sx={visuallyHidden}>{summary}</Box>
      <Box role="status" sx={visuallyHidden}>{said}</Box>
      <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minWidth: 0 }}>
          <ViewerToolbar
            ref={toolbarRef}
            start={split ? undefined : <PageControls page={page} pageCount={pageCount} onShowPage={showPage} />}
            tools={
              <ZoomControls disabled={!opened} onZoomIn={zoomIn} onZoomOut={zoomOut} onFitWidth={fitWidth} />
            }
            opened={opened}
            search={search}
            onFind={find}
            onFindNext={findNext}
            onFindPrevious={findPrevious}
          />
          {older?.error === undefined
            ? null
            : <LoadError title="The older version could not be opened" message={older.error} onRetry={older.retry} />}
          {openError === undefined
            ? null
            : (
              <LoadError
                title={compared.shown ? "The newer version could not be opened" : "The document could not be opened"}
                message={openError}
                onRetry={onRetry}
              />
            )}
          <VersionPanes
            older={split && older !== undefined
              ? { view: older.view, label: olderName, failed: older.error !== undefined }
              : undefined}
            newerLabel={newerName}
            view={view}
            outlineSelected={marks.length > 1}
            looking={looking}
          >
            {shownDocument}
          </VersionPanes>
        </Box>
        {listBeside
          ? (
            <MarkList
              marks={listed}
              naming={naming}
              places={places}
              status={status}
              selectedId={selected?.id}
              renderBody={renderBody}
              describeBody={describeBody}
              renderDetails={renderMarkDetails}
              onSelect={showSelected}
            />
          )
          : null}
      </Box>
    </Box>
  );
}

// The versions shown. Side by side, the older version is drawn here beside this one. Otherwise this version
// is shown alone. This version's document comes in as `children`.
function VersionPanes({ older, newerLabel, view, outlineSelected, looking, children }: {
  older: { view: PdfView; label: string; failed: boolean } | undefined;
  newerLabel: string;
  view: PdfView;
  outlineSelected: boolean;
  looking: Progress | undefined;
  children: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minWidth: 0, minHeight: 0 }}>
      {older === undefined
        ? null
        : (
          <Pane key="older" label={older.label} view={older.view}>
            <PdfDocumentView
              setFrame={older.view.setFrame}
              name={older.label}
              edgeToEdge={false}
              outlineSelected={outlineSelected}
              opening={older.view.pageCount === undefined && !older.failed}
              looking={older.view.pageCount === undefined ? undefined : looking}
            />
          </Pane>
        )}
      <Pane key="newer" label={older === undefined ? undefined : newerLabel} view={view}>
        {children}
      </Pane>
    </Box>
  );
}

// One version of the document. With a label, as beside another version, it has a row above it holding the
// label and its own page controls, grouped under the label so that each version's controls can be told apart.
function Pane({ label, view, children }: {
  label: string | undefined;
  view: PdfView;
  children: ReactNode;
}) {
  const labelId = useId();
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 0", minWidth: 0 }}>
      {label === undefined
        ? null
        : (
          <Box role="group" aria-labelledby={labelId} sx={{ display: "flex", alignItems: "center", minWidth: 0 }}>
            <Typography
              id={labelId}
              variant="subtitle2"
              component="h3"
              noWrap
              sx={{ flex: "1 1 auto", minWidth: 0, mr: 1 }}
            >
              {label}
            </Typography>
            <PageControls page={view.page} pageCount={view.pageCount} onShowPage={view.showPage} />
          </Box>
        )}
      {children}
    </Box>
  );
}

