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

// The grid hands its sorting state to the toolbar through slotProps, so the sort menu — the
// list mode's replacement for clickable column headers — lives with the other toolbar controls.
declare module "@mui/x-data-grid" {
  interface ToolbarPropsOverrides {
    // The columns offered for sorting, in column order
    sortableColumns?: { field: string; headerName?: string }[];
    sortModel?: GridSortModel;
    onSortModelChange?: (model: GridSortModel) => void;
    // Only the list mode needs the menu; regular headers already sort on click
    showSortMenu?: boolean;
    // What this grid's search box is called, an ARIA label
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

// The grid's toolbar. User feedback (on a sibling product with the same audience) singled out
// the search box as the most valuable tool and the hardest to find, so unlike the stock toolbar
// this one keeps it always visible and prominent: leading the toolbar from its start edge,
// visibly tinted and outlined in the primary color. The panel toggles (columns, filters) stay
// compact at the trailing end, dropping to their own row when width runs out.
export default function EntityGridToolbar(props: EntityGridToolbarProps) {
  const { sortableColumns = [], sortModel = [], onSortModelChange, showSortMenu = false,
    searchLabel = "Search" } = props;
  const [sortAnchor, setSortAnchor] = useState<HTMLElement | null>(null);
  const currentSort = sortModel.at(0);

  // Picking the already-active column flips its direction, like clicking a header does
  const sortBy = (field: string) => {
    const direction = currentSort?.field === field && currentSort.sort === "asc" ? "desc" : "asc";
    onSortModelChange?.([{ field, sort: direction }]);
    setSortAnchor(null);
  };

  return (
    // flex none: the stock toolbar is locked to its 52px min-height (flex-basis 1px), so when
    // the controls wrap to a second row they would paint over the grid rows below instead of
    // getting their own space
    <Toolbar style={{ flexWrap: "wrap", flex: "none" }}>
      {/* The sizing lives on the QuickFilter itself: it renders a wrapper div which is the
          actual flex item in the toolbar, so styles on the text field could not affect the
          layout — rendered as a Box so the sizing can respond to breakpoints and focus. The
          trailing auto margin pushes everything after it to the far end, and minWidth 0 lets
          the box shrink below its target — a flex item's implicit minimum would otherwise
          force the whole grid that wide. On narrow screens the box claims a full row, so the
          toolbar is always exactly two tidy rows: an in-between width would otherwise wrap
          the toggles below it one by one. On wider screens the box rests compact and
          stretches to its full target while focused or holding a query (clipping a typed
          query on blur would read as losing it); the panel toggles stay end-anchored, so
          only the gap between them and the box breathes. */}
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
