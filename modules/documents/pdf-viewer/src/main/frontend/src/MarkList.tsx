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
// The list of marks shown beside a document.

import { memo, useContext, useEffect, useId, useRef, useState, type ReactNode } from "react";

import { Box, ButtonBase, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { keepInView } from "@iap/frontend-commons/scrollWithin";
import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";

import { getMarkText, type Mark, type MarkPlace } from "./marksModel";
import { NoteText } from "./PassageCard";
import {
  getCutShortStyle,
  getPassageCardStyle,
  getPassageDetailsStyle,
  getPassageHover,
  getPassageLayerStyle,
  getSelectedOutline,
} from "./passageStyles";
import {
  describeAmong,
  describePages,
  countMarks,
  describeSorts,
  getPassageNote,
  getStepNames,
  getWidestHeadings,
  PASSAGES,
  type MarkNaming,
  type ViewerStatus,
} from "./pdfViewerModel";
import { PlainViewer } from "./plainViewer";
import StepControls from "./StepControls";

import type { MarkPlaces } from "./useMarkPlaces";

export const LIST_WIDTH = 320;

// How many lines of its passage, and of the caller's details under it, a card shows unless it is selected.
// Moving the focus into the details shows all of them, so nothing is typed out of sight.
const MAX_LINES = 2;
const MAX_DETAILS_LINES = 2;

// The marks beside the document, each on a card in its mark's colour with the caller's details for it
// underneath. Clicking a card selects its mark. The selected mark's card is shown whole and kept in view.
// Above the cards, a heading says which mark is selected and how many there are, with buttons to step
// through them.
function MarkList(props: {
  marks: Mark[];
  // What each mark is called and its place among those of its sort.
  naming: Map<string, MarkNaming>;
  places: MarkPlaces;
  status: ViewerStatus;
  selectedId: string | undefined;
  // What to show in place of a mark's quote, such as a change's diff. Without it, the quote is shown.
  renderBody?: (mark: Mark) => ReactNode;
  // What that body says, for a screen reader, given as the description of the card's button.
  describeBody?: (mark: Mark) => string | undefined;
  // What the caller shows under a mark's card, such as its comments, given whether the mark is selected.
  renderDetails?: (mark: Mark, selected: boolean) => ReactNode;
  // PRETOTYPE: how many lines an entry not selected keeps of its quote and of its details, for details that matter
  // more than the passage, such as a comment's own text; no lines of quote leave only where the passage is, even
  // selected, since the document shows it
  condensed?: (mark: Mark) => { quote: number; details?: number } | undefined;
  // PRETOTYPE: how wide the list is, when the reader sizes it, or all the width of a column of its own
  width?: number | string;
  // PRETOTYPE: what heads an entry in place of the page its passage is on, such as who wrote a comment and when
  heading?: (mark: Mark) => ReactNode;
  // PRETOTYPE: buttons at the end of an entry's heading, such as changing one's own comment, kept out of the button
  // the entry is, which they cannot go inside
  headingActions?: (mark: Mark) => ReactNode;
  onSelect: (id: string) => void;
}) {
  const { marks, naming, places, status, selectedId, renderBody, describeBody, renderDetails, onSelect } = props;
  const [list, setList] = useState<HTMLUListElement | null>(null);
  const entries = useRef(new Map<string, HTMLElement>());
  const edges = useScrollEdges(list);

  // Keeps the selected card in view.
  useEffect(() => {
    const entry = selectedId === undefined ? undefined : entries.current.get(selectedId);
    if (list !== null && entry !== undefined) {
      keepInView(list, entry);
    }
  }, [list, selectedId]);

  const position = marks.findIndex(mark => mark.id === selectedId) + 1;
  const selected = naming.get(selectedId ?? "");
  const plain = useContext(PlainViewer);
  // With no mark selected, the heading says how many there are. PRETOTYPE: a plain viewer's notes are headed by
  // the choice of which to show, so the position alone says what it counts, while they are all of one sort.
  const sorts = new Set(Array.from(naming.values(), each => each.name.one)).size;
  const heading = selected === undefined ? countMarks(naming.values())
    : plain && sorts === 1 ? `${selected.index} of ${selected.count}` : describeAmong(selected.name, selected);

  return (
    <Box
      sx={{
        width: props.width ?? LIST_WIDTH,
        flexShrink: 0,
        // PRETOTYPE: in a column of its own, as tall as it
        ...typeof props.width === "string" ? { flex: "1 1 auto" } : {},
        display: "flex",
        flexDirection: "column",
        minHeight: 0,
        // PRETOTYPE: a plain viewer's notes are a panel of their own beside the page, on the tint of the panels around it
        bgcolor: plain ? "background.tinted" : "background.default",
        borderRadius: plain ? 0 : 1,
        ...plain ? { px: 0.5, pt: 0.5 } : {},
      }}
    >
      <StepControls
        position={position}
        count={marks.length}
        itemName={getStepNames(marks, naming, position, PASSAGES)}
        heading={heading}
        widest={[ ...getWidestHeadings(naming.values()), countMarks(naming.values()) ]}
        onStep={next => onSelect(marks[next - 1].id)}
      />
      <Box
        ref={setList}
        component="ul"
        aria-label={describeSorts(naming.values())}
        sx={{
          flex: "1 1 auto",
          minHeight: 0,
          overflow: "auto",
          listStyle: "none",
          m: 0,
          p: 0.5,
          display: "flex",
          flexDirection: "column",
          gap: 1,
          maskImage: fadeEdges(edges),
        }}
      >
        {marks.map((mark, index) => {
          const selected = mark.id === selectedId;
          const details = renderDetails?.(mark, selected);
          const lines = props.condensed?.(mark);
          const actions = props.headingActions?.(mark);
          const hasActions = actions !== undefined && actions !== null && actions !== false;
          return (
            <Box
              component="li"
              key={mark.id}
              ref={(element: HTMLLIElement) => {
                entries.current.set(mark.id, element);
                return () => {
                  entries.current.delete(mark.id);
                };
              }}
              sx={theme => ({
                ...getPassageCardStyle(theme, mark.kind, selected ? 0.5 : 0.25),
                position: "relative",
                // Each card keeps its full height, and the list scrolls instead.
                flexShrink: 0,
                overflow: "hidden",
                outline: selected ? "2px solid" : "none",
                outlineColor: getSelectedOutline(theme, mark.kind),
                outlineOffset: "-2px",
              })}
            >
              <MarkEntry
                mark={mark}
                body={renderBody?.(mark)}
                bodySaid={describeBody?.(mark)}
                naming={naming.get(mark.id) ?? { name: PASSAGES, index: index + 1, count: marks.length }}
                place={places[mark.id]}
                status={status}
                selected={selected}
                lines={lines?.quote ?? MAX_LINES}
                heading={props.heading?.(mark)}
                actionsRoom={hasActions}
                onSelect={() => onSelect(mark.id)}
              />
              {hasActions && <Box sx={{ position: "absolute", top: 4, right: 12 }}>{actions}</Box>}
              {details === undefined || details === null
                ? null
                : (
                  <MarkDetails
                    cut={!selected} lines={lines?.details ?? MAX_DETAILS_LINES}
                    onOpen={selected ? undefined : () => onSelect(mark.id)}
                  >
                    {details}
                  </MarkDetails>
                )}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}

// The caller's details under a card, cut short while `cut`, and shown whole while the focus is inside them.
// PRETOTYPE: cut short, clicking anywhere on it opens it, as its heading does for the keyboard, but for what the
// caller shows to be used there, such as a field to type in, which keeps the reader where they are
function MarkDetails({ cut, lines, onOpen, children }: {
  cut: boolean;
  lines: number;
  onOpen?: () => void;
  children: ReactNode;
}) {
  const [box, setBox] = useState<HTMLDivElement | null>(null);
  const edges = useScrollEdges(box);
  return (
    <Box
      ref={setBox}
      role={onOpen ? "presentation" : undefined}
      onClick={onOpen && (event => {
        if (!(event.target as HTMLElement).closest("input, textarea, select, button, a, [role=button]")) {
          onOpen();
        }
      })}
      // Scrolls back to the top once the focus leaves
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          event.currentTarget.scrollTop = 0;
        }
      }}
      sx={{
        ...getPassageDetailsStyle(edges, cut ? lines : undefined),
        ...onOpen ? { cursor: "pointer" } : {},
        "&:focus-within": { maxHeight: "none", maskImage: "none" },
      }}
    >
      {children}
    </Box>
  );
}

// The button that shows a mark's passage, or the body shown in its place, and its note. Clicking it selects
// the mark.
function MarkEntry({ mark, body, bodySaid, naming, place, status, selected, lines = MAX_LINES, heading, actionsRoom,
  onSelect }: {
  mark: Mark;
  body: ReactNode;
  bodySaid: string | undefined;
  naming: MarkNaming;
  place: MarkPlace;
  status: ViewerStatus;
  selected: boolean;
  lines?: number;
  heading?: ReactNode;
  // Whether buttons sit at the end of its heading, over the entry
  actionsRoom?: boolean;
  onSelect: () => void;
}) {
  const quoteId = useId();
  const note = getPassageNote(status, place, "quote" in mark);
  const text = body === undefined || body === null ? getMarkText(mark, place) : undefined;
  // PRETOTYPE: without its quote, an entry says only where the passage is, as a page to go to
  const placeOnly = lines === 0;
  // PRETOTYPE: under a heading of its own, an entry leaves out where its passage is, unless it could not be found
  const headed = heading !== undefined && heading !== null;
  const shownNote = headed && note !== undefined && !("failed" in note && note.failed) ? undefined : note;
  const content = (
    <>
      {headed && <Box sx={{ mb: placeOnly ? 0 : 0.5 }}>{heading}</Box>}
      {body === undefined || body === null
        ? null
        : <Box component="span" sx={selected ? { display: "block" } : getCutShortStyle(lines)}>{body}</Box>}
      {bodySaid === undefined
        ? null
        : <Box component="span" id={`${quoteId}-said`} sx={visuallyHidden}>{bodySaid}</Box>}
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={placeOnly ? visuallyHidden : selected ? { display: "block" } : getCutShortStyle(lines)}
      >
        {text === undefined || text === "" ? null : `“${text}”`}
      </Typography>
      {shownNote === undefined
        ? null
        : (
          <Typography id={`${quoteId}-note`} component="span" variant="caption" sx={{ display: "block" }}>
            <NoteText note={placeOnly && "foundOnPage" in shownNote ? { ...shownNote, searched: false } : shownNote} />
          </Typography>
        )}
    </>
  );
  const { name, index } = naming;
  let label = `Select ${name.one} ${index}`;
  if (place.status === "found") {
    label = `Show ${name.one} ${index} on ${describePages(place.page, place.toPage)}`;
  }
  // A found passage has its page in the label. For any other passage, the note says why it is not marked.
  const described = [
    bodySaid === undefined ? quoteId : `${quoteId}-said`,
    shownNote === undefined || place.status === "found" ? undefined : `${quoteId}-note`,
  ].filter(id => id !== undefined).join(" ");

  return (
    <ButtonBase
      focusRipple
      aria-label={label}
      aria-describedby={described}
      aria-current={selected ? "true" : undefined}
      onClick={onSelect}
      sx={theme => ({
        display: "block",
        width: "100%",
        px: 1.5,
        pr: actionsRoom ? 10 : 1.5,
        py: 1,
        textAlign: "start",
        ...getPassageLayerStyle(theme, mark.kind),
        "@media (hover: hover)": { "&:hover": { bgcolor: getPassageHover(theme, mark.kind) } },
        "&.Mui-focusVisible": { bgcolor: getPassageHover(theme, mark.kind) },
      })}
    >
      {content}
    </ButtonBase>
  );
}

export default memo(MarkList);
