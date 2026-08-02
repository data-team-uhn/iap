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

import { Fragment, type ReactNode, useEffect, useState } from "react";

import ClearIcon from "@mui/icons-material/Clear";
import FilterListIcon from "@mui/icons-material/FilterList";
import SearchIcon from "@mui/icons-material/Search";
import SwapVertIcon from "@mui/icons-material/SwapVert";
import ViewColumnIcon from "@mui/icons-material/ViewColumn";
import {
  Alert,
  Badge,
  Box,
  Button,
  IconButton,
  InputAdornment,
  ListItemText,
  Menu,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
  useMediaQuery,
} from "@mui/material";
import { type SxProps, type Theme, alpha, useTheme } from "@mui/material/styles";
import {
  ColumnsPanelTrigger,
  DataGridPro,
  FilterPanelTrigger,
  type GridColumnVisibilityModel,
  type GridFilterModel,
  type GridListViewColDef,
  GridLogicOperator,
  type GridPaginationModel,
  GridPanel,
  type GridPanelProps,
  GridPreferencePanelsValue,
  type GridRenderCellParams,
  type GridSortModel,
  QuickFilter,
  QuickFilterClear,
  QuickFilterControl,
  Toolbar,
  ToolbarButton,
  gridPreferencePanelStateSelector,
  useGridApiContext,
  useGridSelector,
} from "@mui/x-data-grid-pro";
import { useNavigate } from "react-router";

// Imported for its side effect: registers the MUI X license before the first Pro render
import "../muiLicense";
import { type DescendantFilter, type EntityRow, type PropertyFilter, fetchEntityPage } from "./pagination";
import { type EntityGridColumn, getEntityTypeConfig } from "./registry";
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
  }
}

interface EntityGridToolbarProps {
  sortableColumns?: { field: string; headerName?: string }[];
  sortModel?: GridSortModel;
  onSortModelChange?: (model: GridSortModel) => void;
  showSortMenu?: boolean;
}

// The grid's toolbar. User feedback (on a sibling product with the same audience) singled out
// the search box as the most valuable tool and the hardest to find, so unlike the stock toolbar
// this one keeps it always visible and prominent: leading the toolbar from its start edge,
// visibly tinted and outlined in the primary color. The panel toggles (columns, filters) stay
// compact at the trailing end, dropping to their own row when width runs out.
function EntityGridToolbar(props: EntityGridToolbarProps) {
  const { sortableColumns = [], sortModel = [], onSortModelChange, showSortMenu = false } = props;
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
                  <Typography variant="body2" color="text.secondary" aria-hidden>
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

// How entity timestamps display throughout the grids: like toLocaleString, minus the seconds
// — no entity timestamp needs second precision in practice, and they widen every date column.
function formatDateTime(value: Date): string {
  return value.toLocaleString(undefined, {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
  });
}

// Puts the compact timestamp rendering on every dateTime column that doesn't bring its own
// formatter — the stock rendering is a full toLocaleString.
function withCompactDates(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => column.type === "dateTime" && !column.valueFormatter
    ? { ...column, valueFormatter: (value?: Date) => value ? formatDateTime(value) : "" }
    : column);
}

// A generic text rendering of one cell value: dates and primitives have an obvious one,
// nested objects have none — keep object-like typeofs out of the list, or they would
// stringify as "[object Object]".
function scalarContent(value: unknown): ReactNode {
  if (value instanceof Date) {
    return formatDateTime(value);
  }
  return ["string", "number", "boolean"].includes(typeof value) ? String(value) : null;
}

// One value of the narrow-screen card, rendered the way its column would render it: through
// the column's renderCell and valueGetter when present, generically otherwise. Our render
// callbacks only read the row and value, so the partial params object is enough.
function columnContent(column: EntityGridColumn, row: EntityRow): ReactNode {
  const raw = row[column.field];
  const value = column.valueGetter
    ? (column.valueGetter as unknown as (value: unknown, row: EntityRow) => unknown)(raw, row)
    : raw;
  if (column.renderCell) {
    const renderCell =
      column.renderCell as unknown as (params: Pick<GridRenderCellParams, "row" | "value" | "field">) => ReactNode;
    return renderCell({ row, value, field: column.field });
  }
  return scalarContent(value);
}

// The card shown for one entity in list mode, composed from the visible columns according to
// their card slots: the title column leads the card, badge columns sit beside it, caption
// columns join into one muted " • " line below, and "row" columns — the default — become
// labeled rows; omitted columns don't appear at all. Content comes from each column's own
// rendering, unless its cardValue asks for a more compact form. Without a designated (or
// visible) title column, the first regular column leads the card, so a plain column list
// still makes a sensible card with no hints at all.
function GenericListItem({ row, columns }: { row: EntityRow; columns: EntityGridColumn[] }) {
  const content = (column: EntityGridColumn) =>
    column.cardValue ? column.cardValue(row) : columnContent(column, row);
  const shown = columns.filter(column => column.cardSlot !== "omit");
  const badges = shown.filter(column => column.cardSlot === "badge");
  const captions = shown
    .filter(column => column.cardSlot === "caption")
    .map(column => ({ field: column.field, node: content(column) }))
    .filter(part => part.node != null && part.node !== "");
  let title = shown.find(column => column.cardSlot === "title");
  let details = shown.filter(column => (column.cardSlot ?? "row") === "row");
  if (!title && details.length > 0) {
    [title, ...details] = details;
  }
  return (
    <Stack spacing={0.5} sx={{ py: 1, width: "100%" }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="subtitle2" component="div">{title && content(title)}</Typography>
        {badges.length > 0 && (
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
            {badges.map(column => <Fragment key={column.field}>{content(column)}</Fragment>)}
          </Stack>
        )}
      </Stack>
      {captions.length > 0 && (
        <Typography variant="caption" color="text.secondary" component="div">
          {captions.map((part, index) => <Fragment key={part.field}>{index > 0 && " • "}{part.node}</Fragment>)}
        </Typography>
      )}
      {details.map(column => {
        const value = content(column);
        return value == null || value === "" ? null : (
          <Stack key={column.field} direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ minWidth: 96 }}>
              {column.headerName ?? column.field}
            </Typography>
            <Box sx={{ typography: "body2" }}>{value}</Box>
          </Stack>
        );
      })}
    </Stack>
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

// The panels are rendered through the base popper slot, which forwards any extra props — sx
// included — to the underlying MUI Popper; only the slot's declared prop type is narrower.
declare module "@mui/x-data-grid" {
  interface BasePopperPropsOverrides {
    sx?: SxProps<Theme>;
  }
}

// On narrow screens the filters/columns panels cannot float: they are nearly viewport-sized,
// so the popper's collision handling shoves them wherever they fit, with no visual tie to
// their trigger. This turns them into a bottom sheet instead — the !importants beat the
// popper's own inline positioning. In list mode the panels are the only poppers around (no
// column headers means no column menu), so it is safe to restyle the shared slot.
const BOTTOM_SHEET_SX = {
  position: "fixed !important",
  inset: "auto 0 0 0 !important",
  transform: "none !important",
  width: "100%",
  zIndex: "modal",
  // A scrim dimming the page behind the sheet, so the sheet clearly reads as a layer above
  // it. Clicks pass through to the (dimmed) page, where the panel's own click-away listener
  // picks them up — tapping outside the sheet closes it, as bottom sheets are expected to.
  "&::before": {
    content: '""',
    position: "fixed",
    inset: 0,
    bgcolor: "rgba(0, 0, 0, 0.5)",
    pointerEvents: "none",
    zIndex: -1,
  },
  "& .MuiDataGrid-paper": {
    width: "100%",
    maxWidth: "100%",
    maxHeight: "60vh",
    // The stock paper is a flex container that never says which way (its one desktop child
    // doesn't care); with the sheet's header added, stacking must be explicit
    flexDirection: "column",
    borderRadius: "12px 12px 0 0",
    // The theme's outlined-paper default is a hairline that blends into the page; a sheet
    // floating over the content earns a real shadow instead
    border: 0,
    boxShadow: 8,
  },
} as const;

// The panels' frame in bottom-sheet mode: the stock GridPanel, with a header naming the open
// panel and giving the sheet its own close button — without one, the top condition's delete X
// is too easily read as "close the panel".
// What the filter conditions' stock delete icon button shows in bottom-sheet mode: a plain
// "Remove" label. On a touch card, a lone X floating above the inputs reads as anything but
// "remove this condition"; a labeled button under them says exactly what it does.
function RemoveConditionLabel() {
  // The size is inherited: the surrounding button is typeset like a small text Button
  return <Typography variant="button" sx={{ fontSize: "inherit" }}>Remove</Typography>;
}

function EntityGridSheetPanel(props: GridPanelProps) {
  const { children, ...frame } = props;
  const apiRef = useGridApiContext();
  const { openedPanelValue } = useGridSelector(apiRef, gridPreferencePanelStateSelector);
  const title = openedPanelValue === GridPreferencePanelsValue.columns ? "Columns" : "Filters";
  return (
    <GridPanel {...frame}>
      {/* Sticky, so the title and close button stay put while the sheet's content scrolls */}
      <Stack
        direction="row"
        sx={{
          alignItems: "center",
          justifyContent: "space-between",
          pl: 2,
          pr: 1,
          py: 1,
          position: "sticky",
          top: 0,
          zIndex: 1,
          bgcolor: "background.paper",
        }}
      >
        <Typography variant="subtitle1">{title}</Typography>
        <IconButton size="small" aria-label="Close" onClick={() => apiRef.current.hidePreferences()}>
          <ClearIcon fontSize="small" />
        </IconButton>
      </Stack>
      {children}
    </GridPanel>
  );
}

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
  const theme = useTheme();
  // On narrow (typically touch) screens the grid switches to the Pro list mode: one card per
  // row instead of columns, with sorting moved into the toolbar's sort menu
  const compactList = useMediaQuery(theme.breakpoints.down("sm"));
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

  // The single synthetic "column" rendering each row as a card in list mode. The cards honor
  // the column selection just like the regular view, keeping the columns toggle meaningful in
  // list mode: the generic card derives from the visible columns, and a type's own renderer
  // receives the visible fields to apply the selection to its composition.
  // A column absent from the model is visible; the model's index type hides the undefined
  const visibleColumns = config.columns
    .filter(column => (columnVisibilityModel[column.field] as boolean | undefined) !== false);
  const visibleFields = new Set(visibleColumns.map(column => column.field));
  const listColumn: GridListViewColDef<EntityRow> = {
    field: "__listItem__",
    renderCell: params => config.listItem
      ? config.listItem(params.row, visibleFields)
      : <GenericListItem row={params.row} columns={visibleColumns} />,
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
        columns={withCompactDates(withServerFilterOperators(config.columns))}
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
        listView={compactList}
        listViewColumn={listColumn}
        // Cards in list mode have variable height; regular rows keep the default fixed height
        getRowHeight={compactList ? () => "auto" : undefined}
        slots={{
          toolbar: EntityGridToolbar,
          noRowsOverlay: EntityGridStatusOverlay,
          // In bottom-sheet mode the panels get a titled header with a close button, and
          // the filter conditions' delete X becomes a labeled Remove button
          ...compactList && { panel: EntityGridSheetPanel, filterPanelDeleteIcon: RemoveConditionLabel },
        }}
        slotProps={{
          // On narrow screens the filters/columns panels dock to the bottom of the screen
          // instead of floating; see BOTTOM_SHEET_SX
          ...compactList && { basePopper: { sx: BOTTOM_SHEET_SX } },
          // The servlet only combines conditions with AND, so don't offer OR in the filter
          // panel. On narrow screens each condition becomes a vertically stacked card: the
          // usual side-by-side selects add up to more width than a phone has.
          filterPanel: {
            logicOperators: [GridLogicOperator.And],
            ...compactList && {
              // The full widths go through the form's own per-part props — the parts carry
              // fixed widths of their own that outside CSS would not reliably beat
              filterFormProps: {
                columnInputProps: { sx: { width: "100%" } },
                operatorInputProps: { sx: { width: "100%" } },
                valueInputProps: { sx: { width: "100%" } },
                // The stock delete control leads the form in the DOM; on the card it moves
                // below the inputs (flex order), bottom-right, and the round icon button is
                // reshaped and typeset to match the footer's small text buttons (Remove
                // all): same radius, type size and ink
                deleteIconProps: {
                  sx: {
                    order: 99,
                    alignSelf: "flex-end",
                    "& .MuiIconButton-root": {
                      borderRadius: 1,
                      px: 1,
                      fontSize: (theme: Theme) => theme.typography.pxToRem(13),
                      color: "text.secondary",
                    },
                  },
                },
              },
            },
            sx: {
              // "Add filter" is the panel's primary action; "Remove all" is destructive and
              // secondary, so it steps back to a quiet text style — and the footer keeps its
              // buttons apart even when a narrow panel wraps them
              "& .MuiDataGrid-panelFooter": { gap: 1, flexWrap: "wrap" },
              "& .MuiDataGrid-panelFooter .MuiButton-root + .MuiButton-root": {
                color: "text.secondary",
                border: "none",
              },
              ...compactList && {
                // A soft tinted box groups each condition (and visually owns its Remove
                // button) without stacking yet another stroke over the inputs' own outlines
                "& .MuiDataGrid-filterForm": {
                  flexDirection: "column",
                  gap: 1,
                  p: 1,
                  bgcolor: "background.muted",
                  borderRadius: 1,
                },
                "& .MuiDataGrid-filterForm + .MuiDataGrid-filterForm": { mt: 1 },
                // The first condition carries an invisible logic-operator placeholder (it
                // keeps the columns aligned in the row layout); in the stacked card layout
                // it is just a ghost row between the delete X and the column select
                "& .MuiDataGrid-filterForm:first-of-type .MuiDataGrid-filterFormLogicOperatorInput": {
                  display: "none",
                },
                // The value wrapper is already full width, but a plain text input inside it
                // keeps its intrinsic width unless told otherwise (selects and date pickers
                // are the wrapper itself, so they already fill)
                "& .MuiDataGrid-filterFormValueInput .MuiFormControl-root": { width: "100%" },
                // The footer pins to the sheet's bottom edge, mirroring the sticky header,
                // so Add filter / Remove all stay reachable while the conditions scroll
                "& .MuiDataGrid-panelFooter": {
                  position: "sticky",
                  bottom: 0,
                  zIndex: 1,
                  bgcolor: "background.paper",
                },
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
          toolbar: {
            showSortMenu: compactList,
            sortableColumns: config.columns
              .filter(column => column.sortable !== false)
              .map(column => ({ field: column.field, headerName: column.headerName })),
            sortModel,
            onSortModelChange: setSortModel,
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
          // The accessible name follows the visible "Remove" label of the sheet-mode button
          ...compactList && { filterPanelDeleteIconLabel: "Remove" },
        }}
        disableVirtualization={disableVirtualization}
      />
    </Box>
  );
}

export default EntityDataGrid;
