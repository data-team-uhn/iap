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

import { Box, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import CommentButton from "./CommentButton";
import ComparisonControls from "./ComparisonControls";
import { describeChange, describeComparison } from "./comparisonText";
import MarkList from "./MarkList";
import { cannotMark, getMarkPage, getMarksKey, getMarkText, isLooking, orderMarks } from "./marksModel";
import PassageCard from "./PassageCard";
import PdfDocumentView from "./PdfDocumentView";
import { PageControls } from "./PdfToolbar";
import {
  CHANGES,
  describeAmong,
  getFileName,
  getPassageNote,
  getPassageStatus,
  getStepNames,
  getWidestHeadings,
  nameMarks,
  PASSAGES,
  type MarkName,
} from "./pdfViewerModel";
import { countMinorChanges, getChangeInContext, type RevisionChange } from "./revisionDiffModel";
import StepControls from "./StepControls";
import { getTextAnchor } from "./textAnchor";
import { useComparison, type Comparing } from "./useComparison";
import { useMarkPlaces, type MarkPlaces } from "./useMarkPlaces";
import { useNarrowerThan, useNarrowViewer } from "./useNarrowViewer";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import { useSelectedText } from "./useSelectedText";
import ViewerPlacement, { ViewerTitle } from "./ViewerPlacement";
import ViewerToolbar, { type ViewerToolbarHandle } from "./ViewerToolbar";
import LoadError from "../components/LoadError";
import ValueChange from "../diff/ValueChange";
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
  // screen ("Comment 3 of 12"). Passages when absent. Changes are called changes, and counted apart. The
  // text a mark covers is still called a passage.
  markName?: MarkName;
  // What the viewer is called; the PDF's file name when absent.
  title?: string;
  // An older version of the document to compare it with. The changes between them are marked, as marks of a
  // kind of their own, and listed with the passages while this version is shown alone. Its label goes above
  // it when the two are shown side by side. Another older version is compared with in place, the reader
  // keeping their place in this one.
  compareWith?: { url: string; label?: string };
  // What this version is called above it when shown beside the older one, such as "Version 3".
  label?: string;
  // An element for the viewer to fill, with a button to pop it out into a dialog. The element needs a
  // height of its own. Without a container, the viewer opens in a dialog. Null, as a ref is before
  // React attaches it, shows nothing until the element is there.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Opens a PDF, finds the passages in it and marks them. The document still opens when a passage
// cannot be found. One passage is shown above the document; several are listed beside it, or, in a
// viewer too narrow for that, shown one at a time above it. Compared with an older version, it also marks
// what changed, beside the older version or in this one alone.
export default function PdfViewer(props: PdfViewerProps) {
  // Keyed so another document starts over with nothing left from the last one.
  return <PdfViewerSession key={`${props.url}\n${props.page}`} {...props} />;
}

const NO_MARKS: Mark[] = [];

// The document stays open here while the viewer moves between the container and the dialog.
function PdfViewerSession(props: PdfViewerProps) {
  const { url, page, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, title, container, onClose } = props;
  const { compareWith } = props;
  const marksKey = getMarksKey(props.marks);
  const callerMarks = useMemo(() => JSON.parse(marksKey) as Mark[], [marksKey]);
  const [chosenId, setChosenId] = useState<string | undefined>(undefined);
  // The mark last reported to the caller. The caller selecting it in turn does not move the reader.
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
  const olderMarks = comparing?.olderMarks ?? NO_MARKS;
  const { places: olderPlaces } = useMarkPlaces(comparing?.older.pdf, olderMarks);
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
    // A change selected in one version is brought into view in the other.
    onSelectMark: id => {
      select(id);
      if (comparing !== undefined) {
        olderView.showMark(id);
      }
    },
  });
  const olderView = usePdfView(comparing?.older.pdf, {
    page: undefined,
    marks: olderMarks,
    places: olderPlaces,
    selectedId,
    onSelectMark: id => {
      select(id);
      view.showMark(id);
    },
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
  // The older version, shown again beside this one, takes the zoom this one was given meanwhile.
  const { shown: olderShown, zoomTo: zoomOlderTo } = olderView;
  const { getScaleValue } = view;
  useEffect(() => {
    const scale = getScaleValue();
    if (olderShown !== undefined && scale !== undefined) {
      zoomOlderTo(scale);
    }
  }, [olderShown, getScaleValue, zoomOlderTo]);
  // The caller's details go under its own marks, not under the changes, which it does not know of.
  const details = useMemo(() => {
    if (changes === undefined || renderMarkDetails === undefined) {
      return renderMarkDetails;
    }
    const changeIds = new Set(changes.map(change => change.id));
    return (mark: Mark, selected: boolean) => (changeIds.has(mark.id) ? undefined : renderMarkDetails(mark, selected));
  }, [changes, renderMarkDetails]);
  const titleId = useId();

  // Both versions are searched and zoomed alike. Moving to a mark moves both.
  const shownView: typeof view = comparing === undefined
    ? view
    : {
      ...view,
      find: query => {
        view.find(query);
        olderView.find(query);
      },
      zoomIn: () => zoomBoth(each => each.zoomIn()),
      zoomOut: () => zoomBoth(each => each.zoomOut()),
      fitWidth: () => zoomBoth(each => each.fitWidth()),
      showMark: id => {
        view.showMark(id);
        olderView.showMark(id);
      },
    };
  function zoomBoth(zoom: (each: typeof view) => void) {
    [ view, olderView ].forEach(zoom);
  }

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
          view={shownView}
          renderMarkDetails={details}
          selectedText={selectedText}
          onAnnotate={annotate}
          changeIds={changeIds}
          comparison={comparing === undefined
            ? undefined
            : { ...comparing, olderView, olderLabel: compareWith?.label, newerLabel: props.label }}
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

// The narrowest viewer with room for two versions side by side and the list of marks beside them. A
// narrower one shows the marks one at a time above the versions.
const SPLIT_WITH_LIST_WIDTH = 1440;

// A comparison with an older version, and the view showing that version.
type ComparisonPanes = Comparing & {
  olderView: ReturnType<typeof usePdfView>;
  olderLabel?: string;
  newerLabel?: string;
};

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
  comparison: ComparisonPanes | undefined;
  // The changes' ids among the marks.
  changeIds: Set<string>;
  // Moves the viewer between its container and the dialog, when it has a container.
  placeButton: ReactNode;
  // In the dialog on a phone, the document spans the full screen width, past the dialog's padding.
  edgeToEdge: boolean;
  onClose: () => void;
}

function ViewerBody(props: ViewerBodyProps) {
  const { titleId, title, marks, markName, places, pagesRead, selectedId, onSelect, openError, onRetry, view } = props;
  const { renderMarkDetails, selectedText, onAnnotate, comparison, placeButton, edgeToEdge, onClose } = props;
  const { changeIds } = props;
  const { page, pageCount, setFrame, showPage, showMark, zoomIn, zoomOut, fitWidth } = view;
  const { search, find, findAgain } = view;
  const [body, setBody] = useState<HTMLElement | null>(null);
  const narrow = useNarrowViewer(body);
  const noRoomForList = useNarrowerThan(body, SPLIT_WITH_LIST_WIDTH);
  // A narrow viewer shows this version alone, but keeps what was chosen for a wider one.
  const split = comparison !== undefined && !narrow && comparison.view === "split";
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
  const changes = comparison?.changes;
  // Side by side, the versions show the changes, which are stepped through apart from the other marks.
  const listed = useMemo(
    () => (split ? ordered.filter(mark => !changeIds.has(mark.id)) : ordered),
    [ordered, changeIds, split],
  );
  // Changes are called changes, and the caller's marks as it says, each numbered among its own sort.
  const naming = useMemo(
    () => nameMarks(listed, mark => (changeIds.has(mark.id) ? CHANGES : markName)),
    [listed, changeIds, markName],
  );
  const sorts = new Set(Array.from(naming.values(), each => each.name)).size;
  const selectedAt = listed.findIndex(mark => mark.id === selectedId);
  const selected = selectedAt < 0 ? undefined : listed[selectedAt];
  // The card keeps the mark listed last selected while a change is, side by side, which is not listed.
  const [cardId, setCardId] = useState<string | undefined>(undefined);
  if (selected !== undefined && selected.id !== cardId) {
    setCardId(selected.id);
  }
  const cardAt = selected === undefined ? Math.max(0, listed.findIndex(mark => mark.id === cardId)) : selectedAt;
  const onCard = listed.at(cardAt);
  const several = listed.length > 1;
  const listBeside = several && !(split ? noRoomForList : narrow);
  const selectedNaming = selected === undefined ? undefined : naming.get(selected.id);
  // The same object while the error and the page stay the same.
  const status = useMemo(() => ({ openError, page }), [openError, page]);
  const showSelected = useCallback((id: string) => {
    onSelect(id);
    showMark(id);
  }, [onSelect, showMark]);
  const stepTo = (index: number) => showSelected(listed[index].id);
  // Swiping the card above the document steps through the passages, as far as the ends.
  const swipeRef = useSwipe(
    cardAt > 0 ? () => stepTo(cardAt - 1) : undefined,
    cardAt < listed.length - 1 ? () => stepTo(cardAt + 1) : undefined,
  );
  const olderView = comparison?.olderView;
  const olderOpenError = comparison?.older.openError;
  // A version that could not be opened says so itself, and nothing is compared, nor while changes are off.
  const comparing = comparison !== undefined && comparison.view !== "off";
  const comparisonState = comparing && openError === undefined && olderOpenError === undefined
    ? comparison.state
    : undefined;
  const described = comparisonState === undefined || comparison === undefined
    ? undefined
    : describeComparison(comparisonState, comparison.strict);
  const changeIndex = changes?.findIndex(change => change.id === selectedId) ?? -1;
  const selectedChange = changeIndex < 0 ? undefined : changes?.[changeIndex];
  const minor = comparisonState?.status === "compared" ? countMinorChanges(comparisonState.changes) : undefined;
  // Once there are changes, the caption only says what minor ones are left out or included, which a narrow
  // viewer leaves to the menu.
  const caption = narrow && described?.count !== undefined ? "" : described?.caption ?? "";
  // A change is shown as what it removed and added among the words around it, in place of the text it marks,
  // and said as a sentence.
  const renderBody = useMemo(() => {
    const byId = new Map(changes?.map(change => [ change.id, change ]));
    return function renderChange(mark: Mark) {
      const change = byId.get(mark.id);
      return change === undefined ? undefined : <ChangeInContext change={change} />;
    };
  }, [changes]);
  const describeBody = useMemo(() => {
    const said = new Map(changes?.map(change => [ change.id, describeChange(change) ]));
    return (mark: Mark) => said.get(mark.id);
  }, [changes]);

  let said = search.announcement;
  if (search.outcome === "none" && selectedChange !== undefined && changes !== undefined) {
    said = describeChange(selectedChange, { index: changeIndex + 1, count: changes.length });
  } else if (search.outcome === "none" && selected !== undefined && selectedNaming !== undefined) {
    const { name, ...among } = selectedNaming;
    said = getPassageStatus(status, places[selected.id], among.count > 1 ? among : undefined, name);
  } else if (search.outcome === "none" && described !== undefined) {
    said = described.said;
  }

  const settled = comparisonState !== undefined && comparisonState.status !== "reading"
    && comparisonState.status !== "comparing";
  // Said apart from the status while it speaks of a selected mark, rather than of the comparison itself.
  const speaksOfMark = search.outcome === "none" && (selectedChange !== undefined || selected !== undefined);
  const summary = settled && described !== undefined && speaksOfMark ? described.said : "";

  const cardBody = onCard === undefined ? undefined : renderBody(onCard);
  const cardNaming = onCard === undefined ? undefined : naming.get(onCard.id);
  const card = onCard === undefined || listBeside
    ? null
    : (
      <PassageCard
        key={onCard.id}
        ref={several ? swipeRef : undefined}
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
              onStep={next => stepTo(next - 1)}
            />
          )
          : undefined}
        details={renderMarkDetails?.(onCard, onCard === selected)}
        onShowPassage={() => showSelected(onCard.id)}
      />
    );
  // While the versions are read and compared, as while passages are looked for, a bar runs along each.
  let looking: { share: number; what: string } | undefined;
  if (comparisonState?.status === "reading" || comparisonState?.status === "comparing") {
    const share = comparisonState.status === "reading" && comparisonState.total > 0
      ? comparisonState.read / comparisonState.total
      : 1;
    looking = { share, what: "the changes" };
  } else if (pageCount !== undefined && isLooking(places)) {
    const looked = marks.length - changeIds.size;
    looking = { share: pagesRead / pageCount, what: `the ${looked > 1 ? markName.many : markName.one}` };
  }

  const shownDocument = (
    <PdfDocumentView
      setFrame={setFrame}
      edgeToEdge={edgeToEdge && !split}
      outlineSelected={ordered.length > 1}
      opening={pageCount === undefined && openError === undefined}
      looking={pageCount === undefined ? undefined : looking}
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
      {comparison === undefined
        ? null
        : (
          <ComparisonControls
            view={comparison.view}
            shownView={narrow && comparison.view === "split" ? "unified" : comparison.view}
            onViewChange={comparison.setView}
            strict={comparison.strict}
            onStrictChange={comparison.setStrict}
            minor={minor}
            count={described?.count}
            narrow={narrow}
            changes={split ? changes : undefined}
            selectedIndex={changeIndex}
            onShowChange={showSelected}
            caption={caption}
          />
        )}
      {card}
      {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
      {/* What the comparison found, once it is done, before the mark selected along with it */}
      <Box aria-live="polite" sx={visuallyHidden}>{summary}</Box>
      <Box role="status" sx={visuallyHidden}>{said}</Box>
      <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
        <Box sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minWidth: 0 }}>
          <ViewerToolbar
            ref={toolbarRef}
            pages={split ? undefined : <PageControls page={page} pageCount={pageCount} onShowPage={showPage} />}
            opened={pageCount !== undefined}
            search={search}
            onFind={find}
            onFindAgain={findAgain}
            onZoomIn={zoomIn}
            onZoomOut={zoomOut}
            onFitWidth={fitWidth}
          />
          {comparison === undefined || olderOpenError === undefined
            ? null
            : (
              <LoadError
                title="The older version could not be opened"
                message={olderOpenError}
                onRetry={comparison.older.retry}
              />
            )}
          {openError === undefined
            ? null
            : (
              <LoadError
                title={comparison === undefined
                  ? "The document could not be opened"
                  : "The newer version could not be opened"}
                message={openError}
                onRetry={onRetry}
              />
            )}
          <VersionPanes
            older={split && olderView !== undefined
              ? { view: olderView, label: comparison.olderLabel, failed: olderOpenError !== undefined }
              : undefined}
            newerLabel={comparison?.newerLabel}
            view={view}
            outlineSelected={ordered.length > 1}
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

// A change among the words around it, what it removed struck through.
function ChangeInContext({ change }: { change: RevisionChange }) {
  const { before, removed, added, after } = getChangeInContext(change);
  return (
    <>
      {before === "" ? null : `${before} `}
      <ValueChange inline before={removed === "" ? undefined : removed} after={added === "" ? undefined : added} />
      {after === "" ? null : ` ${after}`}
    </>
  );
}

// The versions shown: side by side, the older one beside this one, given as its document; otherwise this one
// alone.
function VersionPanes({ older, newerLabel, view, outlineSelected, looking, children }: {
  older: { view: ReturnType<typeof usePdfView>; label: string | undefined; failed: boolean } | undefined;
  newerLabel: string | undefined;
  view: ReturnType<typeof usePdfView>;
  outlineSelected: boolean;
  looking: { share: number; what: string } | undefined;
  children: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minWidth: 0, minHeight: 0 }}>
      {older === undefined
        ? null
        : (
          <Pane key="older" label={older.label ?? "Older version"} view={older.view}>
            <PdfDocumentView
              setFrame={older.view.setFrame}
              edgeToEdge={false}
              outlineSelected={outlineSelected}
              opening={older.view.pageCount === undefined && !older.failed}
              looking={older.view.pageCount === undefined ? undefined : looking}
            />
          </Pane>
        )}
      <Pane key="newer" label={older === undefined ? undefined : newerLabel ?? "Newer version"} view={view}>
        {children}
      </Pane>
    </Box>
  );
}

// A version of the document. Shown beside another, it is under its name and its own page controls.
function Pane({ label, view, children }: {
  label: string | undefined;
  view: ReturnType<typeof usePdfView>;
  children: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 0", minWidth: 0 }}>
      {label === undefined
        ? null
        : (
          <Box sx={{ display: "flex", alignItems: "center", minWidth: 0 }}>
            <Typography variant="subtitle2" component="h3" noWrap sx={{ flex: "1 1 auto", minWidth: 0, mr: 1 }}>
              {label}
            </Typography>
            <PageControls page={view.page} pageCount={view.pageCount} onShowPage={view.showPage} />
          </Box>
        )}
      {children}
    </Box>
  );
}
