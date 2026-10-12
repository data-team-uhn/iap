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
import { Box } from "@mui/material";

import { MARK_CLASS } from "./markLayer";
import { MARK_KINDS } from "./marksModel";

import type { Theme } from "@mui/material/styles";

// Enough page to read, even when a long quote takes most of the viewer.
const MIN_FRAME_HEIGHT = 200;

// Each kind of mark in its highlight colour, and in a deeper one when selected.
function markColours(theme: Theme) {
  const { highlight } = (theme.vars ?? theme).palette;
  return Object.fromEntries(MARK_KINDS.flatMap(kind => [
    [ `& .${MARK_CLASS}-${kind}`, { bgcolor: highlight[kind] } ],
    [ `& .${MARK_CLASS}-${kind}.selected`, { bgcolor: highlight[`${kind}Current`] } ],
  ]));
}

// The frame PDF.js shows the document in, filling the room left to it. PDF.js needs its scrolling
// container absolutely positioned, so the outer box gives it the room. In the dialog on a phone, the
// document spans the full screen width, past the dialog's padding.
export default function PdfDocumentView({ setFrame, edgeToEdge }: {
  setFrame: (frame: HTMLDivElement | null) => void;
  edgeToEdge: boolean;
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
      <Box
        ref={setFrame}
        role="region"
        aria-label="Document"
        tabIndex={0}
        sx={theme => ({
          position: "absolute",
          inset: 0,
          overflow: "auto",
          bgcolor: "background.default",
          borderRadius: edgeToEdge ? { xs: 0, sm: 1 } : 1,
          // Sheets on the canvas, apart from each other
          "& .pdfViewer.removePageBorders .page": { my: 1.5, mx: "auto", boxShadow: 2 },
          // Multiplied like a highlighter's ink, so marks tint the paper and leave the text as dark.
          "& .textLayer": { mixBlendMode: "multiply" },
          [`& .${MARK_CLASS}`]: { borderRadius: 0.5 },
          ...markColours(theme),
        })}
      >
        <div className="pdfViewer" />
      </Box>
    </Box>
  );
}
