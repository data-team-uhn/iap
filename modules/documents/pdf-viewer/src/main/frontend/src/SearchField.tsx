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
// The search field of the viewer's toolbar, with the buttons stepping through its matches.
import type { KeyboardEvent, Ref } from "react";

import CloseIcon from "@mui/icons-material/Close";
import KeyboardArrowDownIcon from "@mui/icons-material/KeyboardArrowDown";
import KeyboardArrowUpIcon from "@mui/icons-material/KeyboardArrowUp";
import SearchIcon from "@mui/icons-material/Search";
import { Box, CircularProgress, IconButton, InputAdornment, TextField, Typography } from "@mui/material";

import { getFailedColour } from "./passageStyles";

import type { DocumentSearch } from "./pdfViewerModel";

// The field searching the document, then the buttons going to the previous and next match. The end of the
// field says which match is shown out of how many, or that nothing matches. A spinner shows there while
// pages are still being searched. Enter in the field goes to the next match, and Shift+Enter to the
// previous one. Escape anywhere in it closes the search where it can be closed, or else clears it. Only an
// Escape with nothing to close or clear goes on to the elements around it.
export default function SearchField({ search, inputRef, disabled, onFind, onFindNext, onFindPrevious, onClose }: {
  search: DocumentSearch;
  inputRef?: Ref<HTMLInputElement>;
  disabled?: boolean;
  onFind: (query: string) => void;
  onFindNext: () => void;
  onFindPrevious: () => void;
  // Closes the search. When it is given, the search fills the width it has and ends with a Close button.
  onClose?: () => void;
}) {
  const onEnter = (event: KeyboardEvent) => {
    if (event.key === "Enter") {
      event.preventDefault();
      (event.shiftKey ? onFindPrevious : onFindNext)();
    }
  };
  const onEscape = (event: KeyboardEvent) => {
    if (event.key === "Escape" && (onClose !== undefined || search.query !== "")) {
      event.stopPropagation();
      if (onClose === undefined) {
        onFind("");
      } else {
        onClose();
      }
    }
  };

  const searching = search.query.trim() !== "";
  let result = null;
  if (searching && search.outcome === "found" && search.current > 0) {
    result = `${search.current} of ${search.total}`;
  } else if (searching && (search.outcome === "notFound" || search.outcome === "noText")) {
    result = search.outcome === "noText" ? "No text to search" : "No matches";
  }
  const counting = searching && search.counting;
  const cannotStep = disabled === true || !searching || search.outcome !== "found" || search.total < 2;

  return (
    <Box
      onKeyDown={onEscape}
      sx={{ display: "flex", alignItems: "center", minWidth: 0, flex: onClose === undefined ? "0 1 auto" : 1 }}
    >
      <TextField
        size="small"
        value={search.query}
        placeholder="Search"
        disabled={disabled}
        onChange={event => onFind(event.target.value)}
        onKeyDown={onEnter}
        sx={theme => ({ flex: "1 1 auto", minWidth: 0, width: onClose === undefined ? theme.spacing(32) : "auto" })}
        slotProps={{
          htmlInput: { ref: inputRef, "aria-label": "Search the document" },
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon fontSize="small" />
              </InputAdornment>
            ),
            endAdornment: result === null && !counting
              ? null
              : (
                <InputAdornment position="end" sx={{ gap: 1 }}>
                  {counting ? <CircularProgress size={14} aria-label="Searching the pages" /> : null}
                  {result === null
                    ? null
                    : (
                      <Typography
                        variant="caption"
                        noWrap
                        sx={theme => ({
                          fontVariantNumeric: "tabular-nums",
                          ...search.outcome === "found" ? {} : getFailedColour(theme),
                        })}
                      >
                        {result}
                      </Typography>
                    )}
                </InputAdornment>
              ),
          },
        }}
      />
      <IconButton aria-label="Previous match" disabled={cannotStep} onClick={onFindPrevious}>
        <KeyboardArrowUpIcon />
      </IconButton>
      <IconButton aria-label="Next match" disabled={cannotStep} onClick={onFindNext}>
        <KeyboardArrowDownIcon />
      </IconButton>
      {onClose === undefined
        ? null
        : (
          <IconButton aria-label="Close the search" edge="end" onClick={onClose}>
            <CloseIcon />
          </IconButton>
        )}
    </Box>
  );
}
