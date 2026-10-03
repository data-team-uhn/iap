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

import { useMemo, useState } from "react";

import { Alert, Box, Stack, useMediaQuery } from "@mui/material";
import { useTheme } from "@mui/material/styles";
import {
  DataGridPro,
  type GridFilterModel,
  type GridGroupNode,
  type GridListViewColDef,
  type GridPaginationModel,
  type GridRenderCellParams,
  type GridSortModel,
  GridTreeDataGroupingCell,
} from "@mui/x-data-grid-pro";
import { useNavigate } from "react-router";

// Imported for its side effect: registers the MUI X license before the first Pro render
import "../muiLicense";
import { withCompactDates, withElementCellsCentred } from "./columns";
import { BOTTOM_SHEET_SX, EntityGridSheetPanel, RemoveConditionLabel, filterPanelProps } from "./EntityGridPanels";
import EntityGridStatusOverlay from "./EntityGridStatusOverlay";
import EntityGridToolbar from "./EntityGridToolbar";
import EntityListItem, { columnContent } from "./EntityListItem";
import {
  ENTITY_CELL, fromTreeField, groupingColumn, rowId, toTreeField, treeDataPath, treeRows,
} from "./gridRows";
import { type EntityGridChildren, type EntityGridColumn, getEntityTypeConfig } from "./registry";
import { toPropertyFilters, withServerFilterOperators } from "./serverFilters";
import useColumnVisibility from "./useColumnVisibility";
import useEntityPage from "./useEntityPage";

import type { DescendantFilter, EntityRow, PropertyFilter } from "./pagination";

// The tree column's cell: the grid's own, toggle included, with the number of an entity's children
// worded as its type words it, and in the row's regular text rather than the entity's emphasis
function TreeCell({ params, tree }: { params: GridRenderCellParams<EntityRow>; tree: EntityGridChildren }) {
  const node = params.rowNode;
  const count = node.type === "group" ? node.children.length : 0;
  const name = (params.formattedValue ?? (node as GridGroupNode).groupingKey) as string;
  return (
    <GridTreeDataGroupingCell
      {...params as GridRenderCellParams<EntityRow, unknown, unknown, GridGroupNode>}
      hideDescendantCount
      formattedValue={count === 0 ? name : (
        <>
          {name}
          <Box component="span" sx={{ fontWeight: "fontWeightRegular", color: "text.primary" }}>
            {` (${tree.countLabel?.(count) ?? count})`}
          </Box>
        </>
      )}
    />
  );
}

interface EntityDataGridProps {
  // The entity type to list, e.g. "sub/Submission"; its presentation (homepage, columns, default
  // sort) must have been registered beforehand with registerEntityType
  entityType: string;
  // Overrides the entity type's registered homepage. Several homepages may hold the same kind of
  // entity (a location's workflows and the platform's own, say); this lists from one of the others.
  // Defaults to the type's registered homepage.
  homepage?: string;
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
  // An ARIA label for this grid's search box, say which list is being searched.
  searchLabel?: string;
  // Render all rows at once instead of virtualizing; needed in test environments with no layout
  disableVirtualization?: boolean;
  // Columns this grid adds to the entity type's own, e.g. the actions offered on each row. Kept out
  // of the type's registered presentation because what may be done with an entity depends on why it
  // is being listed: the same submission offers deleting it in the submitter's own list and not in a
  // reviewer's queue.
  extraColumns?: EntityGridColumn[];
  // The same, put before the type's own columns, e.g. a box ticking a row
  leadingColumns?: EntityGridColumn[];
  // Change this to make the grid read the current page again, for when something outside it
  // changed what the listing should say, such as a row deleted from an actions column. Any new value
  // will do; the grid only watches for it changing.
  refreshToken?: number;
  // Rows already at hand
  rows?: EntityRow[];
}

// A stable default: a grid adding no columns of its own would otherwise get a fresh array, and so a
// fresh column list, on every render
const NO_EXTRA_COLUMNS: EntityGridColumn[] = [];

// A stable object, so the grid's row-count bookkeeping doesn't re-run on every render
const APPROXIMATE_META = { hasNextPage: true };

// A data grid listing entities of one registered type, fetching one page at a time from the
// pagination servlet. Pagination, sorting and searching are handled server-side, so the grid
// stays fast no matter how many entities exist. The toolbar offers a quick "search" box (routed
// to the servlet's full text search) and a column selector whose choices are remembered in
// localStorage, per entity type. Note: when the server reports its total as approximate, the
// row count shown by the grid is a lower bound that grows as later pages are visited.
function EntityDataGrid(props: EntityDataGridProps) {
  const {
    entityType,
    homepage,
    filters,
    childFilter,
    pageSize = 5,
    pageSizeOptions = [5, 10, 25],
    height = 400,
    emptyMessage = "Nothing to show",
    noResultsMessage = "No results found",
    searchLabel = "Search",
    disableVirtualization = false,
    extraColumns = NO_EXTRA_COLUMNS,
    leadingColumns = NO_EXTRA_COLUMNS,
    refreshToken = 0,
    rows: givenRows,
  } = props;
  const config = getEntityTypeConfig(entityType);
  // The type's own presentation plus whatever this particular grid adds. An added column is not
  // something the server can sort or filter on, since it usually names no property at all: the
  // defaults say so, and a caller whose column does name one can still say otherwise.
  const columns = useMemo(() => {
    const added = (column: EntityGridColumn) => ({ sortable: false, filterable: false, ...column });
    return extraColumns.length === 0 && leadingColumns.length === 0 ? config?.columns ?? []
      : [ ...leadingColumns.map(added), ...config?.columns ?? [], ...extraColumns.map(added) ];
  }, [ config?.columns, extraColumns, leadingColumns ]);
  const navigate = useNavigate();
  const theme = useTheme();
  // On narrow (typically touch) screens the grid switches to the Pro list mode: one card per
  // row instead of columns, with sorting moved into the toolbar's sort menu
  const compactList = useMediaQuery(theme.breakpoints.down("sm"));
  // Children show as a tree in the regular view only; a list card describes its own
  const tree = compactList ? undefined : config?.children;
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({ page: 0, pageSize });
  const [sortModel, setSortModel] = useState<GridSortModel>(
    config?.defaultSort ? [{ field: config.defaultSort.field, sort: config.defaultSort.sort }] : []
  );
  const [fullText, setFullText] = useState("");
  const [columnFilters, setColumnFilters] = useState<PropertyFilter[]>([]);
  const [columnVisibilityModel, changeColumnVisibility] = useColumnVisibility(entityType);
  const local = givenRows !== undefined;
  const page = useEntityPage({
    config: local ? undefined : config, homepage, columns, paginationModel, sortModel, filters, childFilter,
    columnFilters, fullText, refreshToken,
  });
  const rows = givenRows ?? page.rows;
  const { rowCount, approximate, error, retry } = page;

  const gridColumns = useMemo(() => withElementCellsCentred(withCompactDates(withServerFilterOperators(
    tree ? columns.filter(column => column.field !== tree.treeField) : columns))), [columns, tree]);
  const gridRows = useMemo(() => treeRows(rows, tree), [rows, tree]);

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
    setColumnFilters(toPropertyFilters(model, columns));
    setPaginationModel(current => current.page === 0 ? current : { ...current, page: 0 });
  };

  // Sorting reorders the whole collection server-side, so page 4 of the old order says nothing
  // about where the reader wants to be in the new one; start them at the top of it.
  const sortBy = (model: GridSortModel) => {
    setSortModel(model);
    setPaginationModel(current => current.page === 0 ? current : { ...current, page: 0 });
  };

  // The single synthetic "column" rendering each row as a card in list mode. The cards honor
  // the column selection just like the regular view, keeping the columns toggle meaningful in
  // list mode: the generic card derives from the visible columns, and a type's own renderer
  // receives the visible fields to apply the selection to its composition.
  // A column absent from the model is visible; the model's index type hides the undefined
  const visibleColumns = columns
    .filter(column => (columnVisibilityModel[column.field] as boolean | undefined) !== false);
  // The type's own fields only: a bespoke renderer is asked to honour the user's column selection,
  // and knows nothing about a column this particular grid added.
  const registeredFields = new Set(config.columns.map(column => column.field));
  const visibleFields = new Set(visibleColumns.map(column => column.field)
    .filter(field => registeredFields.has(field)));
  // What this grid puts before the type's own columns stays before a bespoke card too, as it does in a plain one,
  // unless hidden
  const leadingOnCards = leadingColumns.filter(column => column.cardSlot !== "omit"
    && visibleColumns.some(visible => visible.field === column.field));
  const bespoke = (row: EntityRow, listItem: NonNullable<typeof config.listItem>) => (leadingOnCards.length === 0
    ? listItem(row, visibleFields)
    : (
      <Stack direction="row" sx={{ alignItems: "flex-start", gap: 1, width: "100%" }}>
        { leadingOnCards.map(column => <Box key={column.field} sx={{ pt: 1 }}>{columnContent(column, row)}</Box>) }
        <Box sx={{ flex: 1, minWidth: 0 }}>{listItem(row, visibleFields)}</Box>
      </Stack>
    ));
  const listColumn: GridListViewColDef<EntityRow> = {
    field: "__listItem__",
    renderCell: params => config.listItem
      ? bespoke(params.row, config.listItem)
      : <EntityListItem row={params.row} columns={visibleColumns} />,
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
    <Box
      sx={{
        height,
        width: "100%",
        "& .MuiDataGrid-row": { cursor: openRow ? "pointer" : "inherit" },
        [`& .${ENTITY_CELL}`]: { fontWeight: "fontWeightBold", color: "primary.main" },
      }}
    >
      <DataGridPro
        columns={gridColumns}
        rows={gridRows}
        getRowId={row => rowId(row, gridRows)}
        treeData={Boolean(tree)}
        getTreeDataPath={tree && treeDataPath}
        groupingColDef={tree && {
          ...groupingColumn(columns, tree),
          renderCell: (params: GridRenderCellParams<EntityRow>) => <TreeCell params={params} tree={tree} />,
        }}
        isGroupExpandedByDefault={tree?.expanded ? () => true : undefined}
        // Only the entities are sorted and filtered, not the rows nested under them
        disableChildrenSorting
        disableChildrenFiltering
        // An approximate total is only a lower bound: report the count as unknown-but-estimated,
        // so the grid keeps the next page reachable (a plain rowCount would cap the page count)
        // and presents the total with its stock estimate wording. The servlet counts far enough
        // ahead that the estimate is rarely visible at all — most totals arrive exact.
        rowCount={local ? undefined : approximate ? -1 : rowCount}
        estimatedRowCount={approximate ? rowCount : undefined}
        paginationMeta={approximate ? APPROXIMATE_META : undefined}
        loading={!local && page.loading}
        // Unlike the community DataGrid, DataGridPro defaults to one endless list; opt back in
        pagination
        paginationMode={local ? "client" : "server"}
        paginationModel={paginationModel}
        onPaginationModelChange={setPaginationModel}
        pageSizeOptions={pageSizeOptions}
        sortingMode={local ? "client" : "server"}
        sortModel={sortModel.map(item => ({ ...item, field: toTreeField(item.field, tree) }))}
        onSortModelChange={model => sortBy(model.map(item => ({ ...item, field: fromTreeField(item.field, tree) })))}
        filterMode={local ? "client" : "server"}
        onFilterModelChange={local ? undefined : searchFor}
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
          filterPanel: filterPanelProps(compactList),
          // Filtering happens server-side, so from the grid's point of view an unmatched
          // search and a truly empty collection both look like "zero rows"; which message the
          // overlay shows is chosen here. A failed fetch also empties the rows, and shows as
          // the error variant of the same overlay, with a Retry button.
          noRowsOverlay: {
            message: fullText || columnFilters.length > 0 ? noResultsMessage : emptyMessage,
            error,
            onRetry: retry,
          },
          toolbar: {
            showSortMenu: compactList,
            sortableColumns: columns
              .filter(column => column.sortable !== false)
              .map(column => ({ field: column.field, headerName: column.headerName })),
            sortModel,
            onSortModelChange: setSortModel,
            searchLabel,
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
