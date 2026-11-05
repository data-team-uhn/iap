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

import KeyboardArrowDownOutlined from "@mui/icons-material/KeyboardArrowDownOutlined";
import KeyboardArrowUpOutlined from "@mui/icons-material/KeyboardArrowUpOutlined";
import SearchOutlined from "@mui/icons-material/SearchOutlined";
import { Box, IconButton, InputAdornment, TextField, Tooltip, Typography } from "@mui/material";

// PRETOTYPE. Searching a whole submission, answers and documents, in place, as a browser finds in a page: the
// occurrences are marked where they are, the contents count them in each part, and Enter or the arrows go to the
// next one, or Shift+Enter to the previous, from one part to the next. In the app bar it starts narrow, leaving room
// for the title beside it, and widens while it is used.
export default function SubmissionSearch({ query, position, total, reading, inBar = false, onQuery, onStep }: {
  query: string;
  // The occurrence the search is on, counted through the whole submission, and how many there are; no total while
  // not searching
  position?: number;
  total?: number;
  // Whether the documents are still being read
  reading: boolean;
  inBar?: boolean;
  onQuery: (query: string) => void;
  onStep: (previous: boolean) => void;
}) {
  let said = "";
  if (total !== undefined) {
    if (total === 0) {
      said = reading ? "Looking…" : "Not found";
    } else {
      said = position === undefined ? `${total} found` : `${position} of ${total}`;
    }
  }
  return (
    <Box sx={{ width: inBar ? { md: 300 } : { xs: "100%", sm: 380 },
      transition: theme => theme.transitions.create("width"), "&:focus-within": inBar ? { width: { md: 440 } } : {} }}>
      <TextField
        fullWidth size="small" value={query} placeholder="Search this submission"
        onChange={event => onQuery(event.target.value)}
        onKeyDown={event => {
          if (event.key === "Enter" || event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            onStep(event.key === "ArrowUp" || (event.key === "Enter" && event.shiftKey));
          } else if (event.key === "Escape") {
            onQuery("");
          }
        }}
        sx={inBar ? { "& .MuiInputBase-root": { bgcolor: "background.paper" } } : undefined}
        slotProps={{
          input: {
            startAdornment: <InputAdornment position="start"><SearchOutlined /></InputAdornment>,
            endAdornment: total === undefined ? undefined : (
              <InputAdornment position="end" sx={{ gap: 0.25 }}>
                <Typography variant="caption" role="status" sx={{ color: "text.secondary", whiteSpace: "nowrap", mr: 0.5 }}>
                  {said}
                </Typography>
                <Tooltip title="Previous (Shift+Enter)">
                  <span>
                    <IconButton size="small" aria-label="Previous" disabled={total === 0} onClick={() => onStep(true)}>
                      <KeyboardArrowUpOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
                <Tooltip title="Next (Enter)">
                  <span>
                    <IconButton size="small" aria-label="Next" disabled={total === 0} onClick={() => onStep(false)}>
                      <KeyboardArrowDownOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              </InputAdornment>
            ),
          },
          htmlInput: { "aria-label": "Search the submission" },
        }}
      />
    </Box>
  );
}
