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
import { useEffect, useId, useRef, type ReactNode } from "react";

import { Box, ButtonBase, Typography } from "@mui/material";

import { keepInView } from "./markLayer";
import { getMarkText, type Mark, type MarkPlace } from "./marksModel";
import { NoteText } from "./PassageCard";
import { cutShort, passageCard, passageTint } from "./passageStyles";
import { describePages, getPassageNote, type ViewerStatus } from "./pdfViewerModel";

import type { MarkPlaces } from "./useMarkPlaces";

// How wide the list is beside the document.
const LIST_WIDTH = 320;

// Past this many lines, a passage is cut short unless it is the one selected.
const MAX_LINES = 4;

// The passages beside the document, in the order given, each on a card in its mark's colour, selecting
// it, with whatever the caller shows with it under it. A passage found takes the reader to it, and the
// one selected is shown whole and kept in view.
export default function MarkList({ marks, places, status, selectedId, renderDetails, onSelect }: {
  marks: Mark[];
  places: MarkPlaces;
  status: ViewerStatus;
  selectedId: string | undefined;
  renderDetails?: (mark: Mark, selected: boolean) => ReactNode;
  onSelect: (id: string) => void;
}) {
  const list = useRef<HTMLUListElement>(null);
  const entries = useRef(new Map<string, HTMLElement>());

  useEffect(() => {
    const entry = selectedId === undefined ? undefined : entries.current.get(selectedId);
    if (list.current !== null && entry !== undefined) {
      keepInView(list.current, entry);
    }
  }, [selectedId]);

  return (
    <Box
      ref={list}
      component="ul"
      aria-label="Passages"
      sx={{
        width: LIST_WIDTH,
        flexShrink: 0,
        overflow: "auto",
        listStyle: "none",
        m: 0,
        p: 0.5,
        display: "flex",
        flexDirection: "column",
        gap: 1,
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
              // Whole, however many there are, the list scrolling rather than squeezing them.
              flexShrink: 0,
              overflow: "hidden",
              outline: selected ? "2px solid" : "none",
              outlineColor: `${mark.kind}.main`,
              outlineOffset: "-2px",
            })}
          >
            <MarkEntry
              mark={mark}
              position={index + 1}
              place={places[mark.id]}
              status={status}
              selected={selected}
              onSelect={() => onSelect(mark.id)}
            />
            {renderDetails?.(mark, selected)}
          </Box>
        );
      })}
    </Box>
  );
}

function MarkEntry({ mark, position, place, status, selected, onSelect }: {
  mark: Mark;
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
  let label = `Select passage ${position}`;
  if (place.status === "found") {
    label = `Show passage ${position} on ${describePages(place.page, place.toPage)}`;
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
        "@media (hover: hover)": { "&:hover": { bgcolor: passageTint(theme, mark.kind, 0.5) } },
        "&.Mui-focusVisible": { bgcolor: passageTint(theme, mark.kind, 0.5) },
      })}
    >
      {content}
    </ButtonBase>
  );
}
