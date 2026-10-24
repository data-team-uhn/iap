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
import { memo, useEffect, useId, useRef, type ReactNode } from "react";

import { Box, ButtonBase, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { keepInView } from "./markLayer";
import { getMarkText, isChangeKind, type Mark, type MarkPlace } from "./marksModel";
import { NoteText } from "./PassageCard";
import { cutShort, getMarkColour, passageCard, passageDetails, passageLayer, passageTint } from "./passageStyles";
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
import { fadeEdges, useScrollEdges } from "../useScrollEdges";

import type { MarkPlaces } from "./useMarkPlaces";

const LIST_WIDTH = 320;

// How many lines of a passage, and of what the caller shows under it, an entry shows unless it is the one
// selected. The focus inside what is cut shows all of it, so nothing is typed out of sight.
const MAX_LINES = 2;
const MAX_DETAILS_LINES = 2;

// The passages beside the document, each on a card in its mark's colour, with the caller's details for
// it underneath. Clicking a card selects its passage. The selected passage is shown whole and kept in
// view. Above them, a heading says which is selected of how many, with buttons stepping through them.
function MarkList(props: {
  marks: Mark[];
  // What each mark is called and its place among those of its sort.
  naming: Map<string, MarkNaming>;
  places: MarkPlaces;
  status: ViewerStatus;
  selectedId: string | undefined;
  // What shows a mark in place of its quote, such as a change's diff, if anything, and what that says to a
  // screen reader, which does not read inside the entry's button.
  renderBody?: (mark: Mark) => ReactNode;
  describeBody?: (mark: Mark) => string | undefined;
  renderDetails?: (mark: Mark, selected: boolean) => ReactNode;
  onSelect: (id: string) => void;
}) {
  const { marks, naming, places, status, selectedId, renderBody, describeBody, renderDetails, onSelect } = props;
  const list = useRef<HTMLUListElement>(null);
  const entries = useRef(new Map<string, HTMLElement>());
  const edges = useScrollEdges(list);

  useEffect(() => {
    const entry = selectedId === undefined ? undefined : entries.current.get(selectedId);
    if (list.current !== null && entry !== undefined) {
      keepInView(list.current, entry);
    }
  }, [selectedId]);

  const position = marks.findIndex(mark => mark.id === selectedId) + 1;
  const selected = naming.get(selectedId ?? "");
  // With none of them selected, as while a change is side by side, it says how many there are.
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
        ref={list}
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
                ...passageCard(theme, mark.kind, selected ? 0.5 : 0.25),
                // Each card keeps its full height, and the list scrolls instead.
                flexShrink: 0,
                overflow: "hidden",
                outline: selected ? "2px solid" : "none",
                outlineColor: isChangeKind(mark.kind) ? "text.secondary" : getMarkColour(theme, mark.kind),
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

function MarkDetails({ cut, children }: { cut: boolean; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  const edges = useScrollEdges(box);
  return (
    <Box
      ref={box}
      // Back to its top once the focus leaves, wherever the focus scrolled it while it was cut
      onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          event.currentTarget.scrollTop = 0;
        }
      }}
      sx={{
        ...passageDetails(edges, cut ? MAX_DETAILS_LINES : undefined),
        "&:focus-within": { maxHeight: "none", maskImage: "none" },
      }}
    >
      {children}
    </Box>
  );
}

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
        : <Box component="span" sx={selected ? { display: "block" } : cutShort(MAX_LINES)}>{body}</Box>}
      {bodySaid === undefined
        ? null
        : <Box component="span" id={`${quoteId}-said`} sx={visuallyHidden}>{bodySaid}</Box>}
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={selected ? { display: "block" } : cutShort(MAX_LINES)}
      >
        {text === undefined || text === "" ? null : `“${text}”`}
      </Typography>
      {note === undefined
        ? null
        : (
          <Typography component="span" variant="caption" sx={{ display: "block", color: "text.secondary" }}>
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
        ...passageLayer(theme, mark.kind),
        "@media (hover: hover)": { "&:hover": { bgcolor: passageTint(theme, mark.kind, 0.5) } },
        "&.Mui-focusVisible": { bgcolor: passageTint(theme, mark.kind, 0.5) },
      })}
    >
      {content}
    </ButtonBase>
  );
}

export default memo(MarkList);
