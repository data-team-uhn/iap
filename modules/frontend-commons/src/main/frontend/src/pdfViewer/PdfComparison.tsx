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

import ArticleOutlinedIcon from "@mui/icons-material/ArticleOutlined";
import SpellcheckIcon from "@mui/icons-material/Spellcheck";
import VerticalSplitOutlinedIcon from "@mui/icons-material/VerticalSplitOutlined";
import {
  Box,
  Button,
  ClickAwayListener,
  Paper,
  Popper,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { findMark, markAt } from "./markLayer";
import PdfDocumentView from "./PdfDocumentView";
import { PageControls } from "./PdfToolbar";
import { getFileName } from "./pdfViewerModel";
import { describeChange, describeHidden, getChangeMarks, type RevisionChange } from "./revisionDiffModel";
import StepControls from "./StepControls";
import { useComparisonView, useStrictComparison } from "./useComparisonSettings";
import { useMarkPlaces } from "./useMarkPlaces";
import { useNarrowViewer } from "./useNarrowViewer";
import { usePdfDocument } from "./usePdfDocument";
import { usePdfView } from "./usePdfView";
import { useRevisionDiff, type ComparisonState } from "./useRevisionDiff";
import ViewerPlacement, { ViewerTitle } from "./ViewerPlacement";
import ViewerToolbar, { type ViewerToolbarHandle } from "./ViewerToolbar";
import LoadError from "../components/LoadError";
import TextDiff from "../diff/TextDiff";

export interface PdfComparisonProps {
  // The two versions of a PDF to compare, by where each is.
  olderUrl: string;
  newerUrl: string;
  // What each version is called above its pane, such as "Version 2".
  olderLabel?: string;
  newerLabel?: string;
  // What the viewer is called; the newer version's file name when absent.
  title?: string;
  // An element to show the viewer in, as PdfViewer's.
  container?: HTMLElement | null;
  onClose: () => void;
}

// Compares two versions of a PDF by their text, side by side or as the newer one alone with its changes
// marked, as the person chooses, and alone in a viewer too narrow for two. The changes are stepped through
// in order, each taking the reader to it.
export default function PdfComparison(props: PdfComparisonProps) {
  // Keyed so other versions start over with nothing left from the last.
  return <ComparisonSession key={`${props.olderUrl}\n${props.newerUrl}`} {...props} />;
}

// The space between the viewer's rows, kept small on a phone, where the document has the least room.
const ROW_GAP = { xs: 0.5, sm: 1 };

// What a comparison has to say for itself, before a change is selected: in a line under the changes'
// controls, and to a screen reader.
function describeComparison(state: ComparisonState): { line: string; said: string } {
  switch (state.status) {
    case "reading": {
      const line = state.total > 0 ? `Comparing… page ${state.read} of ${state.total}` : "Comparing…";
      return { line, said: "Comparing the versions…" };
    }
    case "comparing":
      return { line: "Comparing…", said: "Comparing the versions…" };
    case "noText": {
      const line = {
        older: "The older version has no text to compare. It may be a scan.",
        newer: "The newer version has no text to compare. It may be a scan.",
        both: "Neither version has text to compare. They may be scans.",
      }[state.without];
      return { line, said: line };
    }
    case "tooDifferent": {
      const line = "These versions differ too much to show their changes.";
      return { line, said: line };
    }
    case "compared": {
      const { length } = state.changes;
      return length === 0
        ? { line: "No changes found.", said: "No changes found." }
        : { line: "", said: `${countChanges(length)} found.` };
    }
  }
}

function countChanges(count: number): string {
  return `${count} ${count === 1 ? "change" : "changes"}`;
}

// The changes a comparison found, none until it is done.
function getChanges(state: ComparisonState): RevisionChange[] {
  return state.status === "compared" ? state.changes : [];
}

function ComparisonSession(props: PdfComparisonProps) {
  const { olderUrl, newerUrl, title, container, onClose } = props;
  const older = usePdfDocument(olderUrl);
  const newer = usePdfDocument(newerUrl);
  const [strict, setStrict] = useStrictComparison();
  const comparison = useRevisionDiff(older.pdf, newer.pdf, strict);
  const [chosenView, setView] = useComparisonView();
  const [body, setBody] = useState<HTMLElement | null>(null);
  // A narrow viewer shows the newer version alone, whatever was chosen, which it keeps for a wider one.
  const narrow = useNarrowViewer(body);
  const split = !narrow && chosenView === "split";
  const changes = useMemo(() => getChanges(comparison), [comparison]);
  const olderMarks = useMemo(() => getChangeMarks(changes, "older", false), [changes]);
  const newerMarks = useMemo(() => getChangeMarks(changes, "newer", !split), [changes, split]);
  const { places: olderPlaces } = useMarkPlaces(older.pdf, olderMarks);
  const { places: newerPlaces } = useMarkPlaces(newer.pdf, newerMarks);
  // The change selected, of the changes found then, and whether what it removed is shown over the newer
  // version, until the person closes it.
  const [selection, setSelection] = useState<{ of: RevisionChange[]; id: string; showRemoved: boolean }>();
  const selectedId = selection?.of === changes ? selection.id : undefined;
  const position = changes.findIndex(change => change.id === selectedId);
  const selected = position < 0 ? undefined : changes[position];
  const showsRemoved = !split && selected !== undefined && selected.removed !== "" && selection?.showRemoved === true;
  const olderView = usePdfView(older.pdf, {
    page: undefined,
    marks: olderMarks,
    places: olderPlaces,
    selectedId,
    onSelectMark: id => show(id, "older"),
  });
  const newerView = usePdfView(newer.pdf, {
    page: undefined,
    marks: newerMarks,
    places: newerPlaces,
    selectedId,
    onSelectMark: id => show(id, "newer"),
  });
  // Selecting a change brings it into view in each version shown, but the one it was selected in.
  const show = (id: string, from?: "older" | "newer") => {
    setSelection({ of: changes, id, showRemoved: true });
    if (from !== "newer") {
      newerView.showMark(id);
    }
    if (split && from !== "older") {
      olderView.showMark(id);
    }
  };
  const closeRemoved = useCallback(() => setSelection(previous => previous && { ...previous, showRemoved: false }), []);
  const titleId = useId();
  const toolbarRef = useRef<ViewerToolbarHandle>(null);
  const opened = newerView.pageCount !== undefined;
  // Ctrl+F, or Cmd+F, searches the versions while focus is in the viewer, rather than the page.
  const onKeyDown = (event: KeyboardEvent) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f" && opened) {
      event.preventDefault();
      toolbarRef.current?.openSearch();
    }
  };
  // One search and one zoom for both versions. The search counts and steps through the newer version's
  // matches, so it means the same shown alone.
  const find = (query: string) => {
    newerView.find(query);
    olderView.find(query);
  };
  const zoom = {
    onZoomIn: () => [ newerView, olderView ].forEach(view => view.zoomIn()),
    onZoomOut: () => [ newerView, olderView ].forEach(view => view.zoomOut()),
    onFitWidth: () => [ newerView, olderView ].forEach(view => view.fitWidth()),
  };
  const { line, said } = describeComparison(comparison);
  const among = { index: position + 1, count: changes.length };
  let status = said;
  if (newerView.search.outcome !== "none") {
    status = newerView.search.announcement;
  } else if (selected !== undefined) {
    status = describeChange(selected, among);
  }
  const share = comparison.status === "reading" && comparison.total > 0 ? comparison.read / comparison.total : 1;
  const reading = comparison.status === "reading" || comparison.status === "comparing";
  const looking = reading && opened ? { share, what: "the changes" } : undefined;
  const hidden = comparison.status === "compared" ? comparison.hidden : 0;

  const pane = (version: "older" | "newer", edgeToEdge: boolean, removed: ReactNode = null) => {
    const view = version === "older" ? olderView : newerView;
    const file = version === "older" ? older : newer;
    return (
      <Box key={version} sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 0", minWidth: 0 }}>
        {split
          ? (
            <Box sx={{ display: "flex", alignItems: "center", minWidth: 0 }}>
              <Typography variant="subtitle2" component="h3" noWrap sx={{ flex: "1 1 auto", minWidth: 0, mr: 1 }}>
                {version === "older" ? props.olderLabel ?? "Older version" : props.newerLabel ?? "Newer version"}
              </Typography>
              <PageControls page={view.page} pageCount={view.pageCount} onShowPage={view.showPage} />
            </Box>
          )
          : null}
        <PdfDocumentView
          setFrame={view.setFrame}
          edgeToEdge={edgeToEdge}
          outlineSelected={changes.length > 1}
          opening={view.pageCount === undefined && file.openError === undefined}
          looking={looking}
        >
          {removed}
        </PdfDocumentView>
      </Box>
    );
  };

  return (
    <ViewerPlacement container={container} titleId={titleId} onClose={onClose}>
      {({ placeButton, inDialog }) => (
        <Box
          ref={setBody}
          onKeyDown={onKeyDown}
          sx={{ display: "flex", flexDirection: "column", gap: ROW_GAP, flex: "1 1 auto", minHeight: 0 }}
        >
          <ViewerTitle
            titleId={titleId}
            title={title ?? getFileName(newerUrl)}
            placeButton={placeButton}
            onClose={onClose}
          />
          {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
          <Box role="status" sx={visuallyHidden}>{status}</Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, minHeight: 40 }}>
            {narrow
              ? null
              : (
                <ToggleButtonGroup
                  size="small"
                  exclusive
                  value={chosenView}
                  aria-label="How to show the versions"
                  onChange={(_event, value: unknown) => {
                    if (value === "split" || value === "unified") {
                      setView(value);
                    }
                  }}
                >
                  <ToggleButton value="split" aria-label="Side by side">
                    <Tooltip title="Side by side"><VerticalSplitOutlinedIcon /></Tooltip>
                  </ToggleButton>
                  <ToggleButton value="unified" aria-label="Newer version alone">
                    <Tooltip title="Newer version alone"><ArticleOutlinedIcon /></Tooltip>
                  </ToggleButton>
                </ToggleButtonGroup>
              )}
            {changes.length > 0
              ? (
                <StepControls
                  position={position + 1}
                  count={changes.length}
                  itemName="change"
                  heading={selected === undefined ? countChanges(changes.length) : undefined}
                  onStep={next => show(changes[next - 1].id)}
                />
              )
              : null}
            <Box sx={{ flexGrow: 1 }} />
            <Tooltip title="Show changes to punctuation and case">
              <ToggleButton
                size="small"
                value="strict"
                selected={strict}
                aria-label="Show changes to punctuation and case"
                onChange={() => setStrict(!strict)}
              >
                <SpellcheckIcon />
              </ToggleButton>
            </Tooltip>
          </Box>
          <ComparisonNote
            line={line}
            failed={comparison.status === "noText" || comparison.status === "tooDifferent"}
            hidden={hidden}
            onShowHidden={() => setStrict(true)}
          />
          {([ [ "older", older ], [ "newer", newer ] ] as const).map(([ version, file ]) => (
            file.openError === undefined
              ? null
              : (
                <LoadError
                  key={version}
                  title={`The ${version} version could not be opened`}
                  message={file.openError}
                  onRetry={file.retry}
                />
              )))}
          <ViewerToolbar
            ref={toolbarRef}
            pages={split
              ? undefined
              : <PageControls page={newerView.page} pageCount={newerView.pageCount} onShowPage={newerView.showPage} />}
            opened={opened}
            search={newerView.search}
            onFind={find}
            onFindAgain={newerView.findAgain}
            {...zoom}
          />
          <Box sx={{ display: "flex", gap: 2, flex: "1 1 auto", minHeight: 0 }}>
            {split ? pane("older", false) : null}
            {pane("newer", !split && inDialog, showsRemoved && newerView.shown !== undefined
              ? (
                <RemovedText
                  frame={newerView.shown.frame}
                  id={selected.id}
                  text={selected.removed}
                  onClose={closeRemoved}
                />
              )
              : null)}
          </Box>
        </Box>
      )}
    </ViewerPlacement>
  );
}

// The line under the changes' controls: how the comparison is going, why it has no changes to show, or
// how many changes it leaves out, with a button showing them.
function ComparisonNote({ line, failed, hidden, onShowHidden }: {
  line: string;
  failed: boolean;
  hidden: number;
  onShowHidden: () => void;
}) {
  if (line === "" && hidden === 0) {
    return null;
  }
  return (
    <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", columnGap: 1 }}>
      {line === ""
        ? null
        : <Typography variant={failed ? "body2" : "description"}>{line}</Typography>}
      {hidden === 0
        ? null
        : (
          <>
            <Typography variant="description">{`${describeHidden(hidden)}.`}</Typography>
            <Button size="small" onClick={onShowHidden}>Show</Button>
          </>
        )}
    </Box>
  );
}

// The text a change removed, over the newer version, at the marker where it went from, kept within the
// screen. It goes with its marker as the document scrolls or is drawn again, and hides while the marker is
// out of view. A tap outside it closes it, but on its marker.
function RemovedText({ frame, id, text, onClose }: {
  frame: HTMLElement;
  id: string;
  text: string;
  onClose: () => void;
}) {
  const [marker, setMarker] = useState<HTMLElement | undefined>(undefined);
  useEffect(() => {
    const update = () => {
      const found = findMark(frame, id, { point: true });
      const box = frame.getBoundingClientRect();
      const at = found?.getBoundingClientRect();
      setMarker(at !== undefined && at.bottom >= box.top && at.top <= box.bottom ? found : undefined);
    };
    update();
    // Marks are drawn again with each page and zoom.
    const observer = new MutationObserver(update);
    observer.observe(frame, { childList: true, subtree: true });
    frame.addEventListener("scroll", update, { passive: true });
    return () => {
      observer.disconnect();
      frame.removeEventListener("scroll", update);
    };
  }, [frame, id]);
  // Escape closes it wherever the focus is, as on a stepper button just disabled, and closes nothing else.
  const shown = marker !== undefined;
  useEffect(() => {
    if (!shown) {
      return undefined;
    }
    const onEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
      }
    };
    document.addEventListener("keydown", onEscape, { capture: true });
    return () => document.removeEventListener("keydown", onEscape, { capture: true });
  }, [shown, onClose]);

  return (
    <Popper
      open={marker !== undefined}
      anchorEl={marker}
      placement="bottom"
      modifiers={[
        { name: "offset", options: { offset: [ 0, 10 ] } },
        { name: "preventOverflow", options: { padding: 8 } },
        { name: "hide", enabled: false },
      ]}
      sx={theme => ({ zIndex: theme.zIndex.modal + 1 })}
    >
      <ClickAwayListener
        touchEvent={false}
        onClickAway={event => {
          const { target } = event;
          const onFrame = event instanceof MouseEvent && target instanceof Node && frame.contains(target);
          if (!onFrame || markAt(frame, event.clientX, event.clientY) !== id) {
            onClose();
          }
        }}
      >
        <Paper
          role="region"
          aria-label="Removed text"
          elevation={8}
          sx={{ p: 1.5, maxWidth: "min(360px, calc(100vw - 32px))", maxHeight: "40vh", overflowY: "auto" }}
        >
          <Typography variant="subheading" component="p" sx={{ mb: 0.5 }}>Removed</Typography>
          <TextDiff lines={[ { change: "removed", parts: [ { text, changed: false } ] } ]} />
        </Paper>
      </ClickAwayListener>
    </Popper>
  );
}
