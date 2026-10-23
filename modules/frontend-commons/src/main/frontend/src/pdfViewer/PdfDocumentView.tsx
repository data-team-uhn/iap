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
import { memo, type ReactNode } from "react";

import { Box, CircularProgress, LinearProgress } from "@mui/material";

import { MARK_CLASS, OVER_MARK_CLASS, POINT_CLASS } from "./markLayer";
import { MARK_KINDS } from "./marksModel";
import { getMarkColour } from "./passageStyles";

import type { Theme } from "@mui/material/styles";

// Enough page to read, even when a long quote takes most of the viewer.
const MIN_FRAME_HEIGHT = 200;

// A mark's colour, as a CSS variable set on it.
const MARK_COLOUR = "--iap-mark-colour";

// Colours each kind of mark, in a deeper shade when selected. With outlineSelected, the selected mark
// is also outlined.
function markColours(theme: Theme, outlineSelected: boolean): Record<string, object> {
  const { palette } = theme.vars ?? theme;
  return Object.fromEntries(MARK_KINDS.flatMap((kind): [ string, object ][] => [
    [ `& .${MARK_CLASS}-${kind}`, { bgcolor: palette.highlight[kind], [MARK_COLOUR]: getMarkColour(theme, kind) } ],
    [ `& .${MARK_CLASS}-${kind}.selected`, {
      bgcolor: palette.highlight[`${kind}Current`],
      ...outlineSelected ? { outline: `2px solid ${getMarkColour(theme, kind)}` } : {},
    } ],
  ]));
}

// Removed text is struck through, so its colour is not the only sign of it. A point is a caret: a bar the
// line's height under a small triangle, larger when selected.
const CHANGE_MARKS = {
  [`& .${MARK_CLASS}-removed:not(.${POINT_CLASS})`]: {
    backgroundImage: `linear-gradient(transparent calc(50% - 0.5px), var(${MARK_COLOUR}) calc(50% - 0.5px),
      var(${MARK_COLOUR}) calc(50% + 0.5px), transparent calc(50% + 0.5px))`,
  },
  [`& .${POINT_CLASS}, & .${POINT_CLASS}.selected`]: {
    bgcolor: "transparent",
    outline: "none",
    "&::before, &::after": { content: "\"\"", position: "absolute" },
    "&::before": { top: -2, bottom: -2, left: -1, width: 2, bgcolor: `var(${MARK_COLOUR})` },
    "&::after": {
      top: -6,
      left: -5,
      borderStyle: "solid",
      borderWidth: "5px 5px 0",
      borderColor: `var(${MARK_COLOUR}) transparent transparent`,
    },
  },
  [`& .${POINT_CLASS}.selected`]: {
    "&::before": { left: -2, width: 4 },
    "&::after": { top: -9, left: -8, borderWidth: "8px 8px 0" },
  },
};

// The frame PDF.js shows the document in. PDF.js needs its scrolling container absolutely positioned, so the
// box around it gives it the room. Drawn again only when its props change. Its styles are the costliest
// part of the viewer to draw, and the viewer is drawn often while it reads or searches the document.
const DocumentFrame = memo(function DocumentFrame({ setFrame, edgeToEdge, outlineSelected }: {
  setFrame: (frame: HTMLDivElement | null) => void;
  edgeToEdge: boolean;
  outlineSelected: boolean;
}) {
  return (
    <Box
      ref={setFrame}
      role="region"
      aria-label="Document"
      tabIndex={0}
      sx={theme => ({
        position: "absolute",
        inset: 0,
        overflow: "auto",
        // Scrolling the document stops at its ends instead of scrolling what holds it.
        overscrollBehavior: "contain",
        bgcolor: "background.default",
        borderRadius: edgeToEdge ? { xs: 0, sm: 1 } : 1,
        // Sheets on the canvas, apart from each other
        "& .pdfViewer.removePageBorders .page": { my: 1.5, mx: "auto", boxShadow: 2 },
        // Multiplied like a highlighter's ink, so marks tint the paper and leave the text as dark.
        "& .textLayer": { mixBlendMode: "multiply" },
        [`& .${MARK_CLASS}`]: { borderRadius: 0.5 },
        ...markColours(theme, outlineSelected),
        ...CHANGE_MARKS,
        // PDF.js's own highlights of a search's matches
        "& .textLayer .highlight": {
          "--highlight-bg-color": (theme.vars ?? theme).palette.highlight.search,
          "--highlight-selected-bg-color": (theme.vars ?? theme).palette.highlight.searchCurrent,
        },
        // A click on the text over a mark selects the mark, so the cursor becomes a pointer there.
        [`&.${OVER_MARK_CLASS} .textLayer, &.${OVER_MARK_CLASS} .textLayer span`]: { cursor: "pointer" },
      })}
    >
      <div className="pdfViewer" />
    </Box>
  );
});

// The document, filling the room left to it, with a spinner while it opens, and a bar along its top while
// passages are looked for in it. Anything shown over the frame is placed in the frame's coordinates. With
// edgeToEdge, the document spans the full width of a phone screen.
export default function PdfDocumentView({ setFrame, edgeToEdge, outlineSelected, opening, looking, children }: {
  setFrame: (frame: HTMLDivElement | null) => void;
  edgeToEdge: boolean;
  // Whether the selected mark is also outlined.
  outlineSelected: boolean;
  opening: boolean;
  // While marks are being looked for, the share of the document read so far, from 0 to 1, and what is
  // looked for, as in "the passages".
  looking?: { share: number; what: string };
  children?: ReactNode;
}) {
  return (
    <Box
      sx={{
        position: "relative",
        flex: "1 1 auto",
        minHeight: MIN_FRAME_HEIGHT,
        mx: edgeToEdge ? { xs: -2, sm: 0 } : 0,
      }}
    >
      <DocumentFrame setFrame={setFrame} edgeToEdge={edgeToEdge} outlineSelected={outlineSelected} />
      {opening
        ? (
          <CircularProgress
            aria-label="Opening the document"
            sx={{ position: "absolute", top: "50%", left: "50%", translate: "-50% -50%" }}
          />
        )
        : null}
      {looking === undefined
        ? null
        : (
          <LinearProgress
            variant="determinate"
            value={Math.min(100, looking.share * 100)}
            aria-label={`Looking for ${looking.what}`}
            sx={{ position: "absolute", top: 0, insetInline: 0, zIndex: 1 }}
          />
        )}
      {children}
    </Box>
  );
}
