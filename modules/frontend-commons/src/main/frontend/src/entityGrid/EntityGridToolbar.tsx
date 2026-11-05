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

import { useState } from "react";

import ClearIcon from "@mui/icons-material/Clear";
import FilterListIcon from "@mui/icons-material/FilterList";
import SearchIcon from "@mui/icons-material/Search";
import SwapVertIcon from "@mui/icons-material/SwapVert";
import ViewColumnIcon from "@mui/icons-material/ViewColumn";
import {
  Badge,
  Box,
  InputAdornment,
  ListItemText,
  Menu,
  MenuItem,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  ColumnsPanelTrigger,
  FilterPanelTrigger,
  type GridSortModel,
  QuickFilter,
  QuickFilterClear,
  QuickFilterControl,
  Toolbar,
  ToolbarButton,
} from "@mui/x-data-grid-pro";

// What the grid passes the toolbar for its sort menu, which stands in for the column headers in
// list mode, and for its search box.
declare module "@mui/x-data-grid" {
  interface ToolbarPropsOverrides {
    // The columns the menu offers, in column order
    sortableColumns?: { field: string; headerName?: string }[];
    sortModel?: GridSortModel;
    onSortModelChange?: (model: GridSortModel) => void;
    // Whether to show the sort menu, which only the list mode needs
    showSortMenu?: boolean;
    // The accessible name of the search box; "Search" if absent
    searchLabel?: string;
  }
}

interface EntityGridToolbarProps {
  sortableColumns?: { field: string; headerName?: string }[];
  sortModel?: GridSortModel;
  onSortModelChange?: (model: GridSortModel) => void;
  showSortMenu?: boolean;
  searchLabel?: string;
}

// The grid's toolbar. The search box leads it, always open, tinted and outlined in the primary
// colour. The sort menu and the columns and filters buttons sit at the far end, and drop to a row
// of their own when there is no room.
export default function EntityGridToolbar(props: EntityGridToolbarProps) {
  const { sortableColumns = [], sortModel = [], onSortModelChange, showSortMenu = false,
    searchLabel = "Search" } = props;
  const [sortAnchor, setSortAnchor] = useState<HTMLElement | null>(null);
  const currentSort = sortModel.at(0);

  // Picking the column already sorted on reverses the order
  const sortBy = (field: string) => {
    const direction = currentSort?.field === field && currentSort.sort === "asc" ? "desc" : "asc";
    onSortModelChange?.([{ field, sort: direction }]);
    setSortAnchor(null);
  };

  return (
    // `flex: none`, or a second row of controls would be drawn over the grid's rows. The stock
    // toolbar keeps its 52px min-height with a flex-basis of 1px.
    <Toolbar style={{ flexWrap: "wrap", flex: "none" }}>
      {/* The QuickFilter renders the div that is the toolbar's flex item, so it is sized here
          and not on the text field. The auto margin pushes the buttons to the far end.
          `minWidth: 0` lets it shrink below the width of its content, which would otherwise
          widen the whole grid. On a narrow screen it takes a row of its own, and the buttons go on
          the next one. On a wider screen it widens while it has the focus or holds a search. */}
      <QuickFilter
        expanded
        render={(
          <Box
            sx={theme => ({
              flexBasis: "100%",
              flexShrink: 1,
              minWidth: 0,
              marginInlineEnd: "auto",
              [theme.breakpoints.up("sm")]: {
                flexBasis: 300,
                transition: theme.transitions.create("flex-basis", {
                  duration: theme.transitions.duration.standard,
                  easing: theme.transitions.easing.easeOut,
                }),
                "@media (prefers-reduced-motion: reduce)": { transition: "none" },
                "&:focus-within, &:has(input:not(:placeholder-shown))": { flexBasis: 400 },
              },
            })}
          />
        )}
      >
        <QuickFilterControl
          render={({ ref, slotProps, ...controlProps }, state) => (
            <TextField
              {...controlProps}
              inputRef={ref}
              placeholder="Search…"
              size="small"
              fullWidth
              sx={{
                "& .MuiOutlinedInput-root": {
                  bgcolor: theme => alpha(theme.palette.primary.main, 0.04),
                  "&:hover, &.Mui-focused": {
                    bgcolor: theme => alpha(theme.palette.primary.main, 0.08),
                  },
                  "& .MuiOutlinedInput-notchedOutline": {
                    border: "2px solid",
                    borderColor: "primary.main",
                  },
                  "&:hover .MuiOutlinedInput-notchedOutline, &.Mui-focused .MuiOutlinedInput-notchedOutline": {
                    borderColor: "primary.main",
                  },
                },
                "& .MuiSvgIcon-root": { color: "primary.main" },
              }}
              slotProps={{
                ...slotProps,
                htmlInput: { ...slotProps?.htmlInput, "aria-label": searchLabel },
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon fontSize="small" />
                    </InputAdornment>
                  ),
                  endAdornment: state.value !== "" && (
                    <InputAdornment position="end">
                      <QuickFilterClear size="small" edge="end" aria-label="Clear search">
                        <ClearIcon fontSize="small" />
                      </QuickFilterClear>
                    </InputAdornment>
                  ),
                },
              }}
            />
          )}
        />
      </QuickFilter>
      {showSortMenu && sortableColumns.length > 0 && (
        <>
          <Tooltip title="Sort">
            <ToolbarButton aria-label="Sort" onClick={event => setSortAnchor(event.currentTarget)}>
              <SwapVertIcon fontSize="small" />
            </ToolbarButton>
          </Tooltip>
          <Menu anchorEl={sortAnchor} open={sortAnchor !== null} onClose={() => setSortAnchor(null)}>
            {sortableColumns.map(column => (
              <MenuItem
                key={column.field}
                selected={currentSort?.field === column.field}
                onClick={() => sortBy(column.field)}
              >
                <ListItemText>{column.headerName ?? column.field}</ListItemText>
                {currentSort?.field === column.field && (
                  <Typography variant="body2" aria-hidden sx={{ color: "text.secondary" }}>
                    {currentSort.sort === "asc" ? "↑" : "↓"}
                  </Typography>
                )}
              </MenuItem>
            ))}
          </Menu>
        </>
      )}
      <Tooltip title="Columns">
        <ColumnsPanelTrigger render={<ToolbarButton />}>
          <ViewColumnIcon fontSize="small" />
        </ColumnsPanelTrigger>
      </Tooltip>
      <Tooltip title="Filters">
        <FilterPanelTrigger
          render={(props, state) => (
            <ToolbarButton {...props}>
              <Badge badgeContent={state.filterCount} color="primary">
                <FilterListIcon fontSize="small" />
              </Badge>
            </ToolbarButton>
          )}
        />
      </Tooltip>
    </Toolbar>
  );
}
