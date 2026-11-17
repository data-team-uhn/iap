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

import { memo, useEffect, useId, useRef, useState, type ReactNode } from "react";

import { Box, ButtonBase, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { keepInView } from "@iap/frontend-commons/scrollWithin";
import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";

import { getMarkText, type Mark, type MarkPlace } from "./marksModel";
import { NoteText } from "./PassageCard";
import { getCutShortStyle, getPassageCardStyle, getPassageDetailsStyle, getPassageLayerStyle, getPassageTint, getSelectedOutline } from "./passageStyles";
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
import StepControls from "./StepControls";

import type { MarkPlaces } from "./useMarkPlaces";

const LIST_WIDTH = 320;

// How many lines of its passage, and of the caller's details under it, a card shows unless it is selected.
// Moving the focus into the details shows all of them, so nothing is typed out of sight.
const MAX_LINES = 2;
const MAX_DETAILS_LINES = 2;

// The marks beside the document, each on a card in its mark's colour with the caller's details for it
// underneath. Clicking a card selects its mark. The selected mark's card is shown whole and kept in view.
// Above the cards, a heading says which mark is selected of how many, with buttons stepping through them.
function MarkList(props: {
  marks: Mark[];
  // What each mark is called and its place among those of its sort.
  naming: Map<string, MarkNaming>;
  places: MarkPlaces;
  status: ViewerStatus;
  selectedId: string | undefined;
  // What shows a mark in place of its quote, such as a change's diff, if anything.
  renderBody?: (mark: Mark) => ReactNode;
  // What that body says, for a screen reader, given as the description of the card's button.
  describeBody?: (mark: Mark) => string | undefined;
  // What the caller shows under a mark's card, such as its comments, given whether the mark is selected.
  renderDetails?: (mark: Mark, selected: boolean) => ReactNode;
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
  // With no mark selected, the heading says how many there are.
  const heading = selected === undefined ? countMarks(naming.values()) : describeAmong(selected.name, selected);

  return (
    <Box
      sx={{
        width: LIST_WIDTH,
        flexShrink: 0,
        display: "flex",
        flexDirection: "column",
        minHeight: 0,
        bgcolor: "background.default",
        borderRadius: 1,
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
                onSelect={() => onSelect(mark.id)}
              />
              {details === undefined || details === null
                ? null
                : <MarkDetails cut={!selected}>{details}</MarkDetails>}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}

// The caller's details under a card, cut short while `cut`, and shown whole while the focus is inside them.
function MarkDetails({ cut, children }: { cut: boolean; children: ReactNode }) {
  const [box, setBox] = useState<HTMLDivElement | null>(null);
  const edges = useScrollEdges(box);
  return (
    <Box
      ref={setBox}
      // Scrolls back to the top once the focus leaves
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          event.currentTarget.scrollTop = 0;
        }
      }}
      sx={{
        ...getPassageDetailsStyle(edges, cut ? MAX_DETAILS_LINES : undefined),
        "&:focus-within": { maxHeight: "none", maskImage: "none" },
      }}
    >
      {children}
    </Box>
  );
}

// The button showing a mark's passage, or what stands in for it, and its note. Clicking it selects the mark.
function MarkEntry({ mark, body, bodySaid, naming, place, status, selected, onSelect }: {
  mark: Mark;
  body: ReactNode;
  bodySaid: string | undefined;
  naming: MarkNaming;
  place: MarkPlace;
  status: ViewerStatus;
  selected: boolean;
  onSelect: () => void;
}) {
  const quoteId = useId();
  const note = getPassageNote(status, place, "quote" in mark);
  const text = body === undefined || body === null ? getMarkText(mark, place) : undefined;
  const content = (
    <>
      {body === undefined || body === null
        ? null
        : <Box component="span" sx={selected ? { display: "block" } : getCutShortStyle(MAX_LINES)}>{body}</Box>}
      {bodySaid === undefined
        ? null
        : <Box component="span" id={`${quoteId}-said`} sx={visuallyHidden}>{bodySaid}</Box>}
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={selected ? { display: "block" } : getCutShortStyle(MAX_LINES)}
      >
        {text === undefined || text === "" ? null : `“${text}”`}
      </Typography>
      {note === undefined
        ? null
        : (
          <Typography component="span" variant="caption" sx={{ display: "block" }}>
            <NoteText note={note} />
          </Typography>
        )}
    </>
  );
  const { name, index } = naming;
  let label = `Select ${name.one} ${index}`;
  if (place.status === "found") {
    label = `Show ${name.one} ${index} on ${describePages(place.page, place.toPage)}`;
  }

  return (
    <ButtonBase
      focusRipple
      aria-label={label}
      aria-describedby={bodySaid === undefined ? quoteId : `${quoteId}-said`}
      aria-current={selected ? "true" : undefined}
      onClick={onSelect}
      sx={theme => ({
        display: "block",
        width: "100%",
        px: 1.5,
        py: 1,
        textAlign: "start",
        ...getPassageLayerStyle(theme, mark.kind),
        "@media (hover: hover)": { "&:hover": { bgcolor: getPassageTint(theme, mark.kind, 0.5) } },
        "&.Mui-focusVisible": { bgcolor: getPassageTint(theme, mark.kind, 0.5) },
      })}
    >
      {content}
    </ButtonBase>
  );
}

export default memo(MarkList);
