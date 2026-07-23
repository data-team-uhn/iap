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

import { Alert, Box } from "@mui/material";
import {
  DataGridPro,
  type GridColumnVisibilityModel,
  type GridFilterModel,
  GridLogicOperator,
  type GridPaginationModel,
  type GridSortModel,
} from "@mui/x-data-grid-pro";

// Imported for its side effect: registers the MUI X license before the first Pro render
import "../muiLicense";
import { type DescendantFilter, type EntityRow, type PropertyFilter, fetchEntityPage } from "./pagination";
import { getEntityTypeConfig } from "./registry";
import { toPropertyFilters, withServerFilterOperators } from "./serverFilters";

type EntityDataGridProps = {
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
};

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
  const columnStorageKey = `iap.entityGrid.${entityType}.columns`;
  const [rows, setRows] = useState<EntityRow[]>([]);
  const [rowCount, setRowCount] = useState(0);
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
        setError(undefined);
      }
    }).catch((e: Error) => {
      if (!cancelled) {
        setRows([]);
        setRowCount(0);
        setError(e.message);
      }
    }).finally(() => {
      if (!cancelled) {
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [config, paginationModel, sortModel, filterKey, fullText]);

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
    setColumnFilters(config ? toPropertyFilters(model, config.columns) : []);
    setPaginationModel(current => ({ ...current, page: 0 }));
  };

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
  if (error) {
    return <Alert severity="error">{error}</Alert>;
  }

  return (
    <Box sx={{ height, width: "100%" }}>
      <DataGridPro
        columns={withServerFilterOperators(config.columns)}
        rows={rows}
        getRowId={row => String(row["@path"] ?? row["@name"])}
        rowCount={rowCount}
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
        // The servlet only combines conditions with AND, so don't offer OR in the filter panel
        slotProps={{ filterPanel: { logicOperators: [GridLogicOperator.And] } }}
        columnVisibilityModel={columnVisibilityModel}
        onColumnVisibilityModelChange={changeColumnVisibility}
        showToolbar
        disableRowSelectionOnClick
        // Filtering happens server-side, so from the grid's point of view an unmatched search and
        // a truly empty collection both look like "zero rows", and it would always pick its
        // "no rows" overlay; which message that overlay shows is chosen here instead, based on
        // whether a search or column filter is active. noResultsOverlayLabel is also set for
        // completeness, in case the grid's own "no results" overlay path is ever triggered.
        localeText={{
          noRowsLabel: fullText || columnFilters.length > 0 ? noResultsMessage : emptyMessage,
          noResultsOverlayLabel: noResultsMessage,
        }}
        disableVirtualization={disableVirtualization}
      />
    </Box>
  );
}

export default EntityDataGrid;
