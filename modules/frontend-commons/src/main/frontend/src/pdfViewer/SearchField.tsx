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
import type { KeyboardEvent, Ref } from "react";

import CloseIcon from "@mui/icons-material/Close";
import KeyboardArrowDownIcon from "@mui/icons-material/KeyboardArrowDown";
import KeyboardArrowUpIcon from "@mui/icons-material/KeyboardArrowUp";
import SearchIcon from "@mui/icons-material/Search";
import { Box, CircularProgress, IconButton, InputAdornment, TextField, Typography } from "@mui/material";

import { failedColour } from "./passageStyles";

import type { DocumentSearch } from "./pdfViewerModel";

// Searching the document: the field, holding at its end a spinner while pages are still being searched,
// and which match is shown of how many, or that there are none, then the previous and next match, wrapping
// round. Enter in the field goes to the next match
// and Shift+Enter to the previous one. Escape anywhere in it clears the search, or closes it where it can
// be closed, rather than closing the viewer around it.
export default function SearchField({ search, inputRef, disabled, onFind, onFindAgain, onClose }: {
  search: DocumentSearch;
  inputRef?: Ref<HTMLInputElement>;
  disabled?: boolean;
  onFind: (query: string) => void;
  onFindAgain: (previous: boolean) => void;
  // Given where the search takes the place of the toolbar, which it then fills.
  onClose?: () => void;
}) {
  const onEnter = (event: KeyboardEvent) => {
    if (event.key === "Enter") {
      event.preventDefault();
      onFindAgain(event.shiftKey);
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
                          ...search.outcome === "found" ? { color: "text.secondary" } : failedColour(theme),
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
      <IconButton aria-label="Previous match" disabled={cannotStep} onClick={() => onFindAgain(true)}>
        <KeyboardArrowUpIcon />
      </IconButton>
      <IconButton aria-label="Next match" disabled={cannotStep} onClick={() => onFindAgain(false)}>
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
