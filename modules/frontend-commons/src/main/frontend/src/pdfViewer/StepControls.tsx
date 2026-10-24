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
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import { Box, IconButton, Typography } from "@mui/material";

// Steps through a list one item at a time, with Previous and Next buttons around the position, as in
// "2 of 5". Each button is disabled at its end of the list. Positions count from 1. Given a heading, as
// above a list, it shows that between the buttons instead of the count. The text between the buttons
// keeps the width of the widest it can take, the count's at its last position or the `widest` given, laid
// out unseen under it, so stepping never moves the buttons.
export default function StepControls({ position, count, itemName, heading, widest, onStep }: {
  position: number;
  count: number;
  // The item's name in the button labels, as "passage" in "Next passage".
  itemName: string;
  heading?: string;
  // The widest texts the heading takes as the position changes.
  widest?: string[];
  onStep: (position: number) => void;
}) {
  const [ text, sizes, variant ] = heading === undefined
    ? [ `${position} of ${count}`, [ `${count} of ${count}` ], "caption" as const ]
    : [ heading, widest ?? [], "subtitle2" as const ];
  return (
    <Box sx={{ display: "flex", alignItems: "center", flexShrink: 0, justifyContent: "space-between" }}>
      <IconButton aria-label={`Previous ${itemName}`} disabled={position <= 1} onClick={() => onStep(position - 1)}>
        <ChevronLeftIcon />
      </IconButton>
      <Box
        component="p"
        sx={{
          display: "inline-grid",
          justifyItems: "center",
          m: 0,
          minWidth: 0,
          fontVariantNumeric: "tabular-nums",
          "& > *": { gridArea: "1 / 1" },
        }}
      >
        {/* Drawn by CSS rather than as text, so it is no text of the page's */}
        {[ ...new Set(sizes) ].filter(size => size !== text).map(size => (
          <Typography
            key={size}
            variant={variant}
            component="span"
            noWrap
            data-size={size}
            sx={{ visibility: "hidden", "&::before": { content: "attr(data-size)" } }}
          />
        ))}
        <Typography variant={variant} component="span" noWrap sx={{ maxWidth: "100%" }}>{text}</Typography>
      </Box>
      <IconButton aria-label={`Next ${itemName}`} disabled={position >= count} onClick={() => onStep(position + 1)}>
        <ChevronRightIcon />
      </IconButton>
    </Box>
  );
}
