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
import EntityListItem, { CardActions, columnContent } from "./EntityListItem";
import {
  ENTITY_CELL, fromTreeField, groupingColumn, rowId, toTreeField, treeDataPath, treeRows,
} from "./gridRows";
import { type EntityGridChildren, type EntityGridColumn, getEntityTypeConfig, serverProperty } from "./registry";
import { toPropertyFilters, withServerFilterOperators } from "./serverFilters";
import useColumnVisibility from "./useColumnVisibility";
import useEntityPage from "./useEntityPage";

import type { DescendantFilter, EntityRow, PropertyFilter } from "./pagination";

// The tree column's cell: the grid's own, with its toggle. An entity's number of children follows
// its name, worded by the type's countLabel, in the regular weight and colour of the row.
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
  // The entity type to list, such as "sub/Submission", which registerEntityType has registered
  entityType: string;
  // The path of the homepage to list from, when it is not the type's registered one. Several
  // homepages may hold entities of the same type.
  homepage?: string;
  // Conditions on the entities' own properties, ANDed with the user's own filters
  filters?: PropertyFilter[];
  // Conditions on a descendant node, such as a review by the person asking
  childFilter?: DescendantFilter;
  // The initial page size, one of pageSizeOptions; 5 if absent
  pageSize?: number;
  pageSizeOptions?: number[];
  // The height of the grid; 400 pixels if absent. The grid fills its container's width.
  height?: number | string;
  // What the grid says when there are no entities to list
  emptyMessage?: string;
  // What the grid says when a search or a filter matches nothing
  noResultsMessage?: string;
  // The accessible name of the search box, which says what list it searches; "Search" if absent
  searchLabel?: string;
  // Renders every row instead of only the visible ones, for a test environment with no layout
  disableVirtualization?: boolean;
  // Columns after the type's own, such as the actions on each row, which depend on why the
  // entities are listed. They cannot be sorted or filtered unless they say so.
  extraColumns?: EntityGridColumn[];
  // Columns before the type's own, such as a checkbox selecting the row, with the same defaults
  leadingColumns?: EntityGridColumn[];
  // Reads the current page again whenever it changes to any other value, such as after an action
  // column deleted a row
  refreshToken?: number;
  // Rows to show instead of fetching any. Paging, sorting, searching and filtering then happen in
  // the browser.
  rows?: EntityRow[];
}

// One empty array for every grid that adds no columns, so that the column list keeps its identity
// from one render to the next
const NO_EXTRA_COLUMNS: EntityGridColumn[] = [];

// One object for every render, so that the grid does not count its rows again each time
const APPROXIMATE_META = { hasNextPage: true };

// A data grid of the entities of one registered type, read one page at a time from the pagination
// servlet. The server pages, sorts, searches and filters. The toolbar has a search box, which runs
// the servlet's full text search, and a column selector. The columns a user hides stay hidden in
// every grid of the type, on later visits from the same browser. When the server's total is
// approximate, the grid shows it as a lower bound that grows as later pages are read.
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
  // The type's own columns, and the ones this grid adds. An added column usually names no
  // property, so it cannot be sorted or filtered unless it says otherwise.
  const columns = useMemo(() => {
    const added = (column: EntityGridColumn) => ({ sortable: false, filterable: false, ...column });
    return extraColumns.length === 0 && leadingColumns.length === 0 ? config?.columns ?? []
      : [ ...leadingColumns.map(added), ...config?.columns ?? [], ...extraColumns.map(added) ];
  }, [ config?.columns, extraColumns, leadingColumns ]);
  const navigate = useNavigate();
  const theme = useTheme();
  // On a narrow screen the grid uses the Pro list mode: one card per row instead of columns, and a
  // sort menu in the toolbar
  const compactList = useMediaQuery(theme.breakpoints.down("sm"));
  // Children show as a tree in the regular view only; a card lists them itself
  const tree = compactList ? undefined : config?.children;
  const [paginationModel, setPaginationModel] = useState<GridPaginationModel>({ page: 0, pageSize });
  const [sortModel, setSortModel] = useState<GridSortModel>(
    config?.defaultSort ? [{ field: config.defaultSort.field, sort: config.defaultSort.sort }] : []
  );
  const [fullText, setFullText] = useState("");
  const [columnFilters, setColumnFilters] = useState<PropertyFilter[]>([]);
  const [columnVisibilityModel, changeColumnVisibility] = useColumnVisibility(entityType);
  const local = givenRows !== undefined;
  const sortedColumn = columns.find(column => column.field === sortModel[0]?.field);
  const page = useEntityPage({
    config: local ? undefined : config, homepage, paginationModel,
    sortBy: sortedColumn && serverProperty(sortedColumn), descending: sortModel[0]?.sort === "desc",
    filters, childFilter, columnFilters, fullText, refreshToken,
  });
  const rows = givenRows ?? page.rows;
  const { rowCount, approximate, error, retry } = page;

  const gridColumns = useMemo(() => withElementCellsCentred(withCompactDates(withServerFilterOperators(
    tree ? columns.filter(column => column.field !== tree.treeField) : columns))), [columns, tree]);
  const gridRows = useMemo(() => treeRows(rows, tree), [rows, tree]);

  if (!config) {
    return <Alert severity="error">Unknown entity type: {entityType}</Alert>;
  }

  // Sends both kinds of filter to the servlet. The search box's terms become a full text search,
  // and the filter panel's conditions become property filters. The JCR full text search matches
  // whole words only, so every term gets a trailing wildcard and matches as a prefix. A new search
  // starts back on the first page.
  const searchFor = (model: GridFilterModel) => {
    const terms = (model.quickFilterValues ?? [])
      .map(String)
      .filter(term => term !== "")
      .map(term => term.endsWith("*") ? term : `${term}*`);
    setFullText(terms.join(" "));
    setColumnFilters(toPropertyFilters(model, columns));
    setPaginationModel(current => current.page === 0 ? current : { ...current, page: 0 });
  };

  // A new sort starts back on the first page
  const changeSort = (model: GridSortModel) => {
    setSortModel(model);
    setPaginationModel(current => current.page === 0 ? current : { ...current, page: 0 });
  };

  // A card leaves out the columns the user hid. The composed card is made of the visible columns,
  // and a type's own listItem is given their fields.
  // A column absent from the model is visible; the model's index type hides the undefined
  const visibleColumns = columns
    .filter(column => (columnVisibilityModel[column.field] as boolean | undefined) !== false);
  // The type's own fields only, since a listItem knows nothing of the columns this grid adds
  const registeredFields = new Set(config.columns.map(column => column.field));
  const visibleFields = new Set(visibleColumns.map(column => column.field)
    .filter(field => registeredFields.has(field)));
  // The leading columns go before a listItem's card too, unless hidden or kept off cards
  const leadingOnCards = leadingColumns.filter(column => column.cardSlot !== "omit"
    && visibleColumns.some(visible => visible.field === column.field));
  // The columns in the "actions" slot end a listItem's card too, unless hidden
  const actionsOnCards = visibleColumns.filter(column => column.cardSlot === "actions");
  const bespoke = (row: EntityRow, listItem: NonNullable<typeof config.listItem>) =>
    (leadingOnCards.length + actionsOnCards.length === 0
      ? listItem(row, visibleFields)
      : (
        <Stack direction="row" sx={{ alignItems: "flex-start", gap: 1, width: "100%" }}>
          { leadingOnCards.map(column => <Box key={column.field} sx={{ pt: 1 }}>{columnContent(column, row)}</Box>) }
          <Box sx={{ flex: 1, minWidth: 0 }}>{listItem(row, visibleFields)}</Box>
          { actionsOnCards.map(column => (
            <Box key={column.field} sx={{ pt: 1 }}><CardActions>{columnContent(column, row)}</CardActions></Box>
          )) }
        </Stack>
      ));
  const listColumn: GridListViewColDef<EntityRow> = {
    field: "__listItem__",
    renderCell: params => config.listItem
      ? bespoke(params.row, config.listItem)
      : <EntityListItem row={params.row} columns={visibleColumns} config={config} />,
  };

  // A click on a row opens the entity's page, where the type's rowLink gives one
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
        // The rows nested under an entity keep their order, and are never filtered out
        disableChildrenSorting
        disableChildrenFiltering
        // An approximate total is a lower bound. The grid is told the count is unknown, with an
        // estimate, so that it keeps the next page reachable and words the total as an estimate.
        // A rowCount would stop it at the last page that count allows.
        rowCount={local ? undefined : approximate ? -1 : rowCount}
        estimatedRowCount={approximate ? rowCount : undefined}
        paginationMeta={approximate ? APPROXIMATE_META : undefined}
        loading={!local && page.loading}
        // DataGridPro shows one endless list unless told to paginate
        pagination
        paginationMode={local ? "client" : "server"}
        paginationModel={paginationModel}
        onPaginationModelChange={setPaginationModel}
        pageSizeOptions={pageSizeOptions}
        sortingMode={local ? "client" : "server"}
        sortModel={sortModel.map(item => ({ ...item, field: toTreeField(item.field, tree) }))}
        onSortModelChange={model =>
          changeSort(model.map(item => ({ ...item, field: fromTreeField(item.field, tree) })))}
        filterMode={local ? "client" : "server"}
        onFilterModelChange={local ? undefined : searchFor}
        listView={compactList}
        listViewColumn={listColumn}
        // A card is as tall as its content; a regular row keeps the fixed height
        getRowHeight={compactList ? () => "auto" : undefined}
        slots={{
          toolbar: EntityGridToolbar,
          noRowsOverlay: EntityGridStatusOverlay,
          // On a narrow screen a panel has a header with its title and a close button. A filter
          // condition has a Remove button instead of the delete icon.
          ...compactList && { panel: EntityGridSheetPanel, filterPanelDeleteIcon: RemoveConditionLabel },
        }}
        slotProps={{
          // On a narrow screen the filters and columns panels are docked to the bottom of the screen
          ...compactList && { basePopper: { sx: BOTTOM_SHEET_SX } },
          filterPanel: filterPanelProps(compactList),
          // The server filters, so the grid sees zero rows both when nothing matches and when there
          // is nothing to list. The message tells them apart. A failed read also leaves no rows,
          // and shows the error with a Retry button instead.
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
          // The grid's own overlay for no results, which it shows only when it filters in the browser
          noResultsOverlayLabel: noResultsMessage,
          // The accessible name matches the button's visible "Remove"
          ...compactList && { filterPanelDeleteIconLabel: "Remove" },
        }}
        disableVirtualization={disableVirtualization}
      />
    </Box>
  );
}

export default EntityDataGrid;
