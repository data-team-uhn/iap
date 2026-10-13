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

// Steps through a list one item at a time: back, which of how many, and on, each button disabled at its
// end of the list. Positions count from 1. It spans the width it is given, a button at each end, and as the
// heading of a list, says what it is given between its buttons, in the text's full colour.
export default function StepControls({ position, count, itemName, heading, onStep }: {
  position: number;
  count: number;
  // The item's name in the button labels, as "passage" in "Next passage".
  itemName: string;
  heading?: string;
  onStep: (position: number) => void;
}) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", flexShrink: 0, justifyContent: "space-between" }}>
      <IconButton aria-label={`Previous ${itemName}`} disabled={position <= 1} onClick={() => onStep(position - 1)}>
        <ChevronLeftIcon />
      </IconButton>
      {heading === undefined
        ? <Typography variant="caption" noWrap>{`${position} of ${count}`}</Typography>
        : <Typography variant="subtitle2" component="p" noWrap>{heading}</Typography>}
      <IconButton aria-label={`Next ${itemName}`} disabled={position >= count} onClick={() => onStep(position + 1)}>
        <ChevronRightIcon />
      </IconButton>
    </Box>
  );
}
