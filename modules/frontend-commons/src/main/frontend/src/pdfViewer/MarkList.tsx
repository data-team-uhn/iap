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

import { keepInView } from "./markLayer";
import { getMarkText, type Mark, type MarkPlace } from "./marksModel";
import { NoteText } from "./PassageCard";
import { cutShort, passageCard, passageDetails, passageLayer, passageTint } from "./passageStyles";
import {
  capitalize,
  describeAmong,
  describePages,
  getPassageNote,
  type MarkName,
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
function MarkList({ marks, name, places, status, selectedId, renderDetails, onSelect }: {
  marks: Mark[];
  name: MarkName;
  places: MarkPlaces;
  status: ViewerStatus;
  selectedId: string | undefined;
  renderDetails?: (mark: Mark, selected: boolean) => ReactNode;
  onSelect: (id: string) => void;
}) {
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

  return (
    <Box sx={{ width: LIST_WIDTH, flexShrink: 0, display: "flex", flexDirection: "column", minHeight: 0 }}>
      <StepControls
        position={position}
        count={marks.length}
        itemName={name.one}
        heading={describeAmong(name, { index: position, count: marks.length })}
        onStep={next => onSelect(marks[next - 1].id)}
      />
      <Box
        ref={list}
        component="ul"
        aria-label={capitalize(name.many)}
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
                outlineColor: `${mark.kind}.main`,
                outlineOffset: "-2px",
              })}
            >
              <MarkEntry
                mark={mark}
                name={name}
                position={index + 1}
                place={places[mark.id]}
                status={status}
                selected={selected}
                onSelect={() => onSelect(mark.id)}
              />
              {renderDetails === undefined
                ? null
                : <MarkDetails cut={!selected}>{renderDetails(mark, selected)}</MarkDetails>}
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

function MarkEntry({ mark, name, position, place, status, selected, onSelect }: {
  mark: Mark;
  name: MarkName;
  position: number;
  place: MarkPlace;
  status: ViewerStatus;
  selected: boolean;
  onSelect: () => void;
}) {
  const quoteId = useId();
  const note = getPassageNote(status, place);
  const text = getMarkText(mark, place);
  const content = (
    <>
      <Typography
        id={quoteId}
        variant="body2"
        component="span"
        sx={selected ? { display: "block" } : cutShort(MAX_LINES)}
      >
        {text === undefined ? null : `“${text}”`}
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
  let label = `Select ${name.one} ${position}`;
  if (place.status === "found") {
    label = `Show ${name.one} ${position} on ${describePages(place.page, place.toPage)}`;
  }

  return (
    <ButtonBase
      focusRipple
      aria-label={label}
      aria-describedby={quoteId}
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
