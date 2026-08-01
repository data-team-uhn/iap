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

import { useEffect, useState } from "react";

import ClearIcon from "@mui/icons-material/Clear";
import FilterListIcon from "@mui/icons-material/FilterList";
import SearchIcon from "@mui/icons-material/Search";
import ViewColumnIcon from "@mui/icons-material/ViewColumn";
import { Alert, Box, Button, InputAdornment, Stack, TextField, Tooltip, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";
import {
  ColumnsPanelTrigger,
  DataGridPro,
  FilterPanelTrigger,
  type GridColumnVisibilityModel,
  type GridFilterModel,
  GridLogicOperator,
  type GridPaginationModel,
  type GridSortModel,
  QuickFilter,
  QuickFilterClear,
  QuickFilterControl,
  Toolbar,
  ToolbarButton,
} from "@mui/x-data-grid-pro";
import { useNavigate } from "react-router";

// Imported for its side effect: registers the MUI X license before the first Pro render
import "../muiLicense";
import { type DescendantFilter, type EntityRow, type PropertyFilter, fetchEntityPage } from "./pagination";
import { getEntityTypeConfig } from "./registry";
import { toPropertyFilters, withServerFilterOperators } from "./serverFilters";

interface EntityDataGridProps {
  // The entity type to list, e.g. "sub/Submission"; its presentation (homepage, columns, default
  // sort) must have been registered beforehand with registerEntityType
  entityType: string;
  // Extra conditions on the entities' own properties, e.g. only the current user's submissions
  filters?: PropertyFilter[];
  // Extra conditions on a descendant node, e.g. only submissions with a review by the current user
  childFilter?: DescendantFilter;
  // The initial page size; must be one of pageSizeOptions
  pageSize?: number;
  pageSizeOptions?: number[];
  // The height of the grid; the grid always fills its container's width
  height?: number | string;
  // The message shown when there are no entities to list
  emptyMessage?: string;
  // The message shown when a search is active but matches nothing
  noResultsMessage?: string;
  // Render all rows at once instead of virtualizing; needed in test environments with no layout
  disableVirtualization?: boolean;
}

// The grid's toolbar. User feedback (on a sibling product with the same audience) singled out
// the search box as the most valuable tool and the hardest to find, so unlike the stock toolbar
// this one keeps it always visible and prominent: leading the toolbar from its start edge,
// visibly tinted and outlined in the primary color. The panel toggles (columns, filters) stay
// compact at the trailing end, dropping to their own row when width runs out.
function EntityGridToolbar() {
  return (
    <Toolbar style={{ flexWrap: "wrap" }}>
      {/* The sizing lives on the QuickFilter itself: it renders a wrapper div which is the
          actual flex item in the toolbar, so styles on the text field could not affect the
          layout. The trailing auto margin pushes everything after it to the far end. */}
      <QuickFilter expanded style={{ width: 400, maxWidth: "100%", marginInlineEnd: "auto" }}>
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
      <Tooltip title="Columns">
        <ColumnsPanelTrigger render={<ToolbarButton />}>
          <ViewColumnIcon fontSize="small" />
        </ColumnsPanelTrigger>
      </Tooltip>
      <Tooltip title="Filters">
        <FilterPanelTrigger render={<ToolbarButton />}>
          <FilterListIcon fontSize="small" />
        </FilterPanelTrigger>
      </Tooltip>
    </Toolbar>
  );
}

// Which columns the user hid the last time they used a grid for this entity type.
function loadStoredColumnVisibility(storageKey: string): GridColumnVisibilityModel {
  try {
    const stored: unknown = JSON.parse(window.localStorage.getItem(storageKey) ?? "{}");
    return stored && typeof stored === "object" ? stored as GridColumnVisibilityModel : {};
  } catch {
    // Missing/disabled storage or corrupted content: fall back to showing every column
    return {};
  }
}

// A stable object, so the grid's row-count bookkeeping doesn't re-run on every render
const APPROXIMATE_META = { hasNextPage: true };

// What the overlay covering an empty row area should say: the plain empty message, the
// "nothing matched" message, or a fetch failure with a way to retry it.
declare module "@mui/x-data-grid" {
  interface NoRowsOverlayPropsOverrides {
    message?: string;
    error?: string;
    onRetry?: () => void;
  }
}

// The overlay shown over an empty row area. A failed fetch also empties the rows, so this is
// where the error belongs too — inside the grid, with a Retry button, while the toolbar and
// the rest of the controls stay usable around it (changing any request parameter also
// recovers on its own).
function EntityGridStatusOverlay(props: { message?: string; error?: string; onRetry?: () => void }) {
  const { message, error, onRetry } = props;
  if (error) {
    return (
      <Stack sx={{ height: "100%", alignItems: "center", justifyContent: "center", gap: 1, p: 2 }}>
        <Typography variant="body2" color="error" sx={{ textAlign: "center", overflowWrap: "anywhere" }}>
          {error}
        </Typography>
        <Button size="small" onClick={onRetry}>Retry</Button>
      </Stack>
    );
  }
  return (
    <Stack sx={{ height: "100%", alignItems: "center", justifyContent: "center", p: 2 }}>
      <Typography variant="body2" color="text.secondary">{message}</Typography>
    </Stack>
  );
}

// A data grid listing entities of one registered type, fetching one page at a time from the
// pagination servlet. Pagination, sorting and searching are handled server-side, so the grid
// stays fast no matter how many entities exist. The toolbar offers a quick "search" box (routed
// to the servlet's full text search) and a column selector whose choices are remembered in
// localStorage, per entity type. Note: when the server reports its total as approximate, the
// row count shown by the grid is a lower bound that grows as later pages are visited.
function EntityDataGrid(props: EntityDataGridProps) {
  const {
    entityType,
    filters,
    childFilter,
    pageSize = 5,
    pageSizeOptions = [5, 10, 25],
    height = 400,
    emptyMessage = "Nothing to show",
    noResultsMessage = "No results found",
    disableVirtualization = false,
  } = props;
  const config = getEntityTypeConfig(entityType);
  const navigate = useNavigate();
  const columnStorageKey = `iap.entityGrid.${entityType}.columns`;
  const [rows, setRows] = useState<EntityRow[]>([]);
  const [rowCount, setRowCount] = useState(0);
  // Whether the server stopped counting matches early: the row count is then a lower bound
  const [approximate, setApproximate] = useState(false);
  // Bumped by the error overlay's Retry button to re-run the fetch with unchanged parameters
  const [retryCount, setRetryCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({ page: 0, pageSize });
  const [sortModel, setSortModel] = useState<GridSortModel>(
    config?.defaultSort ? [{ field: config.defaultSort.field, sort: config.defaultSort.sort }] : []
  );
  const [fullText, setFullText] = useState("");
  const [columnFilters, setColumnFilters] = useState<PropertyFilter[]>([]);
  const [columnVisibilityModel, setColumnVisibilityModel] =
    useState<GridColumnVisibilityModel>(() => loadStoredColumnVisibility(columnStorageKey));

  // The props holding the fixed filters are typically fresh objects on every render, so effects
  // depend on their content instead of their identity
  const filterKey = JSON.stringify([filters, childFilter, columnFilters]);

  useEffect(() => {
    if (!config) {
      return;
    }
    let cancelled = false;
    // The flag intentionally turns on synchronously with the request it tracks; deriving it
    // from a request key instead proved racy against the grid's own debounced model updates
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    const sortColumn = sortModel[0] && config.columns.find(column => column.field === sortModel[0].field);
    fetchEntityPage({
      homepage: config.homepage,
      offset: paginationModel.page * paginationModel.pageSize,
      limit: paginationModel.pageSize,
      sortBy: sortColumn ? sortColumn.sortProperty ?? sortColumn.field : undefined,
      descending: sortModel[0]?.sort === "desc",
      filters: [...filters ?? [], ...columnFilters],
      childFilter,
      fullText: fullText || undefined,
    }).then(page => {
      if (!cancelled) {
        setRows(page.rows);
        setRowCount(page.totalrows);
        setApproximate(page.totalIsApproximate);
        setError(undefined);
      }
    }).catch((e: unknown) => {
      if (!cancelled) {
        setRows([]);
        setRowCount(0);
        setApproximate(false);
        setError(e instanceof Error ? e.message : String(e));
      }
    }).finally(() => {
      if (!cancelled) {
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [config, paginationModel, sortModel, filterKey, fullText, retryCount]);

  const changeColumnVisibility = (model: GridColumnVisibilityModel) => {
    setColumnVisibilityModel(model);
    try {
      window.localStorage.setItem(columnStorageKey, JSON.stringify(model));
    } catch {
      // Storage may be disabled or full; the selection still applies to the current page view
    }
  };

  if (!config) {
    return <Alert severity="error">Unknown entity type: {entityType}</Alert>;
  }

  // Both filtering UIs are forwarded to the servlet: the toolbar's quick filter terms become a
  // full text search, and the filter panel's column conditions become property filters. The JCR
  // full text search only matches whole words, which feels broken while a word is still being
  // typed, so every term gets a trailing wildcard, turning the search into a prefix match. A new
  // search starts back on the first page.
  const searchFor = (model: GridFilterModel) => {
    const terms = (model.quickFilterValues ?? [])
      .map(String)
      .filter(term => term !== "")
      .map(term => term.endsWith("*") ? term : `${term}*`);
    setFullText(terms.join(" "));
    setColumnFilters(toPropertyFilters(model, config.columns));
    setPaginationModel(current => ({ ...current, page: 0 }));
  };

  // Clicking a row navigates to the entity's own page, when the entity type declares one
  const { rowLink } = config;
  const openRow = rowLink && ((row: EntityRow) => {
    const link = rowLink(row);
    if (link) {
      void navigate(link);
    }
  });

  return (
    <Box sx={{ height, width: "100%", "& .MuiDataGrid-row": { cursor: openRow ? "pointer" : "inherit" } }}>
      <DataGridPro
        columns={withServerFilterOperators(config.columns)}
        rows={rows}
        getRowId={row => String(row["@path"] ?? row["@name"])}
        // An approximate total is only a lower bound: report the count as unknown-but-estimated,
        // so the grid keeps the next page reachable (a plain rowCount would cap the page count)
        // and presents the total with its stock estimate wording. The servlet counts far enough
        // ahead that the estimate is rarely visible at all — most totals arrive exact.
        rowCount={approximate ? -1 : rowCount}
        estimatedRowCount={approximate ? rowCount : undefined}
        paginationMeta={approximate ? APPROXIMATE_META : undefined}
        loading={loading}
        // Unlike the community DataGrid, DataGridPro defaults to one endless list; opt back in
        pagination
        paginationMode="server"
        paginationModel={paginationModel}
        onPaginationModelChange={setPaginationModel}
        pageSizeOptions={pageSizeOptions}
        sortingMode="server"
        sortModel={sortModel}
        onSortModelChange={setSortModel}
        filterMode="server"
        onFilterModelChange={searchFor}
        slots={{ toolbar: EntityGridToolbar, noRowsOverlay: EntityGridStatusOverlay }}
        slotProps={{
          // The servlet only combines conditions with AND, so don't offer OR in the filter panel
          filterPanel: {
            logicOperators: [GridLogicOperator.And],
            sx: {
              // "Add filter" is the panel's primary action; "Remove all" is destructive and
              // secondary, so it steps back to a quiet text style — and the footer keeps its
              // buttons apart even when a narrow panel wraps them
              "& .MuiDataGrid-panelFooter": { gap: 1, flexWrap: "wrap" },
              "& .MuiDataGrid-panelFooter .MuiButton-root + .MuiButton-root": {
                color: "text.secondary",
                border: "none",
              },
            },
          },
          // Filtering happens server-side, so from the grid's point of view an unmatched
          // search and a truly empty collection both look like "zero rows"; which message the
          // overlay shows is chosen here. A failed fetch also empties the rows, and shows as
          // the error variant of the same overlay, with a Retry button.
          noRowsOverlay: {
            message: fullText || columnFilters.length > 0 ? noResultsMessage : emptyMessage,
            error,
            onRetry: () => setRetryCount(count => count + 1),
          },
        }}
        columnVisibilityModel={columnVisibilityModel}
        onColumnVisibilityModelChange={changeColumnVisibility}
        showToolbar
        disableRowSelectionOnClick
        onRowClick={openRow && (params => openRow(params.row as EntityRow))}
        localeText={{
          // Set for completeness, in case the grid's own "no results" overlay path (unused
          // with server-side filtering) is ever triggered
          noResultsOverlayLabel: noResultsMessage,
        }}
        disableVirtualization={disableVirtualization}
      />
    </Box>
  );
}

export default EntityDataGrid;
